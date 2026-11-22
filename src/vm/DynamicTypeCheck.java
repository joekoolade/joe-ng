/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import board.Uart;
import magic.Address;
import magic.DataReference;
import magic.Magic;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Dynamic type checks (JikesRVM: {@code DynamicTypeCheck}): each Type's superclass DISPLAY and implemented-interface
 * BITMAP, which make {@code instanceof}/{@code checkcast} constant-time, and resolving the Type a checkcast or
 * instanceof names. Split out of {@link Loader}.
 */
final class DynamicTypeCheck
{
    private DynamicTypeCheck()
    {
    }

    private static long[] unresTypeSeen;
    private static int unresTypeSeenN;

    /** Type node of the class named by {@code Class} entry {@code classIdx}, or 0 if unloaded. */
    static DataReference typeOfClass(int classIdx)
    {
        // Self-reference (a class doing `x instanceof Self` / `(Self) x`): the current class isn't in the
        // registry until AFTER its own compile, but its Type node (gBuild.type) is already built by buildTib —
        // and it's the very node the class's own instances carry, so instanceof/checkcast resolve correctly.
        int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classIdx]))];
        if (u1(Loader.gView.base.plus(nameOff + 2)) == 0x5B)            // '[' : an array class ("[B", "[Ljava/...;", "[[I")
        {
            Address tib = ArrayTypes.arrayTibOfDesc(Loader.gView.base.toAddress(), nameOff);
            return tib.isZero() ? DataReference.zero() : tib.loadDataReference();  // the array Type (checkcast/instanceof target)
        }
        if (Loader.utf8EqAt(Loader.gView.base.toAddress(), nameOff, Loader.gView.base.toAddress(), Loader.gView.thisNameOff))
        {
            return Loader.gBuild.type;
        }
        int r = ClassRegistry.classRegOf(classIdx);
        return r >= 0 ? ClassRegistry.clTab[r].type : DataReference.zero();
    }

    /** O(1) type checks: give {@code type} a depth + superclass display (depth+1 Type addrs,
     *  display[d] = ancestor at depth d, display[depth] = self). Copies the super's display, so it
     *  requires the super's Type to be complete (supers register first; adopted supers carry the
     *  writer's). A super without a display leaves this one without too (the walk fallback holds). */
    static void buildDisplay(Address type, Address superType)
    {
        long d = 0L;
        Address sd = Address.zero();
        if (!superType.isZero())
        {
            sd = superType.plus(ObjectModel.TYPE_DISPLAY_OFFSET).loadAddress();
            if (sd.isZero())
            {
                return;                                  // super has no display: stay on the walk
            }
            d = superType.plus(ObjectModel.TYPE_DEPTH_OFFSET).loadLong() + 1L;
        }
        DataReference disp = Heap.allocDataRefs(((int) d + 1) * 8);
        long i = 0L;
        while (i < d)
        {
            disp.plus(i * 8L).store(sd.plus(i * 8L).loadLong());
            i += 1;
        }
        disp.plus(d * 8L).store(type);
        type.plus(ObjectModel.TYPE_DEPTH_OFFSET).store(d);
        type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).store(disp);
    }

    /** O(1) interface checks: (re)build {@code type}'s doesImplement bitmap = the super's bitmap |
     *  any bits already present (an ADOPTED node keeps its writer bits) | this type's own itable-dir
     *  interfaces' ID bits. Unnumbered dir interfaces are simply skipped -- their targets fall back
     *  to the dir walk, so the bitmap stays definitive for every NUMBERED interface. A super without
     *  a computed bitmap leaves this type without one too (walk fallback). */
    static void buildImplBitmap(Address type)
    {
        long b0 = 1L;                                    // bit 0 = computed marker
        long b1 = 0L;
        DataReference superType = type.plus(ObjectModel.TYPE_SUPER_OFFSET).loadDataReference();
        if (!superType.isZero())
        {
            long sb0 = superType.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).loadLong();
            if ((sb0 & 1L) == 0L)
            {
                return;                                  // super uncomputed: stay on the walk
            }
            b0 |= sb0;
            b1 |= superType.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).loadLong();
        }
        long own0 = type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).loadLong();
        if ((own0 & 1L) != 0L)                           // adopted node: keep the writer's bits
        {
            b0 |= own0;
            b1 |= type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).loadLong();
        }
        Address e = type.plus(ObjectModel.TYPE_ITABLE_DIR_OFFSET).loadAddress();
        if (!e.isZero())
        {
            Address iface = e.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
            while (!iface.isZero())
            {
                long id = iface.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).loadLong();
                if (id > 0L && id < 64L)
                {
                    b0 |= 1L << (int) id;
                }
                else if (id >= 64L && id < 128L)
                {
                    b1 |= 1L << (int) (id - 64L);
                }
                e = e.plus(ObjectModel.ITABLE_ENTRY_SIZE);
                iface = e.plus(ObjectModel.ITABLE_ENTRY_IFACE_OFFSET).loadAddress();
            }
        }
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).store(b0);
        type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).store(b1);
    }

    /**
     * The target Type of an {@code instanceof} / {@code checkcast}, which the compiler bakes as an IMMEDIATE --
     * so a class not registered when the body compiles was a 0 for ever, and against 0 every instanceof answers
     * FALSE and every checkcast of a non-null throws. Measured: stock StreamZipEntriesTest's
     * {@code zf.stream().forEach(e -> assertTrue(e instanceof ZipEntry))} printed {@code e=java.util.zip.ZipEntry
     * inst=false}, because the lambda body compiled when the lambda was LINKED, before the first ZipEntry
     * existed. The class-literal route, for the same reason: note the pull, let the compile be discarded and
     * redone with the class loaded. A class that is still absent afterwards is NAMED (denylisted ones excepted,
     * where absent is the intended state) rather than left to answer false in silence.
     */
    static long typeOfCheckedClass(int classCp)
    {
        long type = typeOfClass(classCp).toLong();
        int nameOff0 = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classCp]))];
        if (u1(Loader.gView.base.plus(nameOff0 + 2)) == 0x5B)
        {
            // AN ARRAY OF A CLASS NOTHING PULLED. typeOfClass does not answer 0 for it: it answers the array Type
            // keyed by element Type 0 -- ONE shared "array of ?" node -- so `(Parameter[]) x` compiled before
            // Parameter was registered cast against that node and threw `cannot be cast to class [?` for ever
            // (AccessFlagsProbe in a batch: Executable.getParameters' clone() checkcast, compiled lazily). Same
            // route as a class: pull the ELEMENT and redo the compile.
            int len = u2(Loader.gView.base.plus(nameOff0));
            int d = 0;
            while (d < len && u1(Loader.gView.base.plus(nameOff0 + 2 + d)) == 0x5B)
            {
                d += 1;
            }
            if (d < len && u1(Loader.gView.base.plus(nameOff0 + 2 + d)) == 0x4C
                    && ArrayTypes.elementTypeOfArrayDescN(Loader.gView.base.toAddress(), nameOff0 + 2 + d - 1, len - d + 1).isZero())
            {
                int elOff = nameOff0 + 2 + d + 1;       // past the '['s and the 'L'
                int elLen = len - d - 2;                // minus the trailing ';'
                if (Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeededElement(Loader.gView.base.toAddress(), elOff, elLen))
                {
                    return 0L;                          // discarded compile: retried with the element loaded
                }
                if (!Loader.isDenylistedSlice(Loader.gView.base.toAddress(), elOff, elLen))
                {
                    reportUnresolvedTypeCheck(Loader.gView.base.plus(nameOff0));
                }
            }
            return type;
        }
        if (type == 0L)
        {
            int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[classCp]))];
            if (u1(Loader.gView.base.plus(nameOff + 2)) != 0x5B
                    && Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeeded(Loader.gView.base.plus(nameOff)))
            {
                return 0L;                                          // discarded compile: retried with it loaded
            }
            if (!Loader.isDenylisted(Loader.gView.base.plus(nameOff), 0))
            {
                reportUnresolvedTypeCheck(Loader.gView.base.plus(nameOff));
            }
        }
        return type;
    }

    /** Name a type-check target that stayed unresolved, once per class (the NULL CLASS LITERAL report's shape). */
    private static void reportUnresolvedTypeCheck(Address clsU)
    {
        if (unresTypeSeen == null)
        {
            unresTypeSeen = new long[64];
            unresTypeSeenN = 0;
        }
        int k = 0;
        while (k < unresTypeSeenN)
        {
            if (unresTypeSeen[k] == clsU.toLong())
            {
                return;
            }
            k += 1;
        }
        if (unresTypeSeenN < 64)
        {
            unresTypeSeen[unresTypeSeenN] = clsU.toLong();
            unresTypeSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  UNRESOLVED TYPE CHECK: "));
        Loader.printNameAt(clsU, 0);
        Uart.write(Magic.bytes(" -- instanceof answers false and checkcast throws; "));
        Loader.printWhyUnpulled(clsU);
        if (!Loader.lzCompiling)
        {
            Uart.write(Magic.bytes(", and OUTSIDE the retry window"));
        }
        else if (Loader.lzRetried)
        {
            Uart.write(Magic.bytes(", and the body's single retry was already spent"));
        }
        Uart.write(Magic.bytes("; needed by "));
        Loader.printCompiling();
        Uart.putc(0x0A);
    }

    /** TIB of the class at {@code Class} entry {@code classIdx} (its own if not yet registered). */
    static long tibOfClass(int classIdx)
    {
        int r = ClassRegistry.classRegOf(classIdx);
        ClassInitializer.noteInitNeeded(r);                              // `new C` is an active use of C (JVMS 5.5)
        return (r >= 0 ? ClassRegistry.clTab[r].tib : Loader.gBuild.tib).toLong();            // unregistered => the SELF case (objectSizeOf traps the rest)
    }
}
