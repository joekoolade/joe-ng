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

import magic.Address;
import objectmodel.ObjectModel;

/**
 * The instance-field registry: per instance field of each class, its class/name and slot ({@code fldTab}), and the
 * index that resolves a cross-class {@code getfield}/{@code putfield} to its offset. Split out of {@link Loader}.
 */
final class FieldRegistry
{
    private FieldRegistry()
    {
    }

    // Field registry: per instance field of each class, its class/name (base+offset)
    // and slot, so a cross-class get/putfield can find the offset.
    // 16384, not 4096: the cap matched MAXCLASS, but classes average MORE than one instance field, and the TLS
    // stack (SslEngineProbe, 2767 classes) filled 4096 first -- `CAP EXCEEDED: MAXFIELD count=4095`.
    static final int MAXFIELD = 16384;
    static RVMField[] fldTab;   // global instance-field registry (reified: reuses RVMField; slot/access/descOff)
    static int fldCount;
    /**
     * Class+name hash index over the instance-field registry, so {@link #fldIndexOf} is a PROBE rather than a
     * scan comparing two Utf8 strings against every instance field of every loaded class.
     *
     * <p>MEASURED, not guessed: in the first TLS session of SslEngineProbe (QEMU qprof, 53-176 s) 115 of 541
     * busy samples had the compiler on the stack, and about 6 of them were the compiler itself. The rest were
     * {@code getfield}/{@code putfield} compiles resolving a field: {@code fieldIsVolatile} ->
     * {@code registeredFieldAccess} and {@code fieldOffsetOf} -> {@code globalFieldOffset}, each scanning all
     * {@code fldCount} entries -- once per SUPERCLASS LEVEL for an inherited field -- on every field access
     * compiled. Same defect and same remedy as the static registry's {@link Statics#sgIndexOf}.
     */
    private static final int FLDTAB = 8192;              // power of two; chained
    static int[] fldBucket;                      // class+name hash -> first entry index, -1 when empty
    private static int[] fldNext;                        // entry index -> next entry with the same hash
    static int fldIndexed;

    /** Byte offset of instance field {@code fname} declared by registered class {@code classIdx}, or -1.
     *  The byte[]-name sibling of {@link MethodHandles#vhFieldOffset} (which keys on a raw pointer + a TIB). */
    static long instanceFieldOffset(int classIdx, byte[] fname)
    {
        int j = 0;
        while (j < fldCount)
        {
            if (Loader.utf8EqAt(ClassRegistry.clTab[classIdx].base, ClassRegistry.clTab[classIdx].nameOff, fldTab[j].base, fldTab[j].classOff)
                    && Utf8.utf8IsAtBase(fldTab[j].base, fldTab[j].nameOff, fname))
            {
                return 16L + fldTab[j].slot * 8L;
            }
            j += 1;
        }
        return -1L;
    }

    private static void buildFldIndex()
    {
        if (fldBucket == null || fldIndexed > fldCount)   // rebuilt when resetLoader zeroes the table
        {
            fldBucket = new int[FLDTAB];
            fldNext = new int[MAXFIELD];
            int b = 0;
            while (b < FLDTAB)
            {
                fldBucket[b] = -1;                       // allocArray does NOT zero its elements on this VM,
                b += 1;                                  //   and 0 is a valid entry index -- fill explicitly
            }
            fldIndexed = 0;
        }
        while (fldIndexed < fldCount)                    // INCREMENTAL: the registry only grows
        {
            int hh = Utf8.utf8Hash2(fldTab[fldIndexed].base, fldTab[fldIndexed].classOff,
                               fldTab[fldIndexed].base, fldTab[fldIndexed].nameOff) & (FLDTAB - 1);
            fldNext[fldIndexed] = fldBucket[hh];
            fldBucket[hh] = fldIndexed;
            fldIndexed += 1;
        }
    }

    /** The instance-field registry entry DECLARED by class {@code clsBase+clsOff} as field
     *  {@code nameBase+nameOff}, or -1. The predicate the scans used, so a hit is the entry they found. */
    private static int fldIndexOf(Address clsBase, int clsOff, Address nameBase, int nameOff)
    {
        buildFldIndex();
        int best = -1;
        int k = fldBucket[Utf8.utf8Hash2(clsBase, clsOff, nameBase, nameOff) & (FLDTAB - 1)];
        while (k >= 0)
        {
            if (Loader.utf8EqAt(clsBase, clsOff, fldTab[k].base, fldTab[k].classOff)
                    && Loader.utf8EqAt(nameBase, nameOff, fldTab[k].base, fldTab[k].nameOff))
            {
                if (best < 0 || k < best)
                {
                    best = k;                            // lowest index wins, as the scan's first match did
                }
            }
            k = fldNext[k];
        }
        return best;
    }

    private static int globalFieldOffset(int idx)
    {
        int classOff = Loader.refClassNameOff(idx);
        int nameOff = Loader.mrefNameOff(idx);
        int i = fldIndexOf(Loader.gbase, classOff, Loader.gbase, nameOff);   // class-qualified
        if (i >= 0)
        {
            Loader.fieldOffsetLog(classOff, nameOff, 16 + fldTab[i].slot * 8, 1);
            return 16 + fldTab[i].slot * 8;
        }
        // Class-qualified miss: the field is INHERITED. Resolve it by walking the ref class's SUPERCLASS chain
        // and matching a field DECLARED by each ancestor -- NOT a blind name-only match. Multiple unrelated
        // classes can declare a same-named field at different slots (e.g. Pattern.buffer @72 vs
        // Pattern$SliceNode.buffer): a name-only match on `Slice.buffer` wrongly returned Pattern's slot, so the
        // getfield read a bogus field off a Slice object -> null -> the #43 NPE in Pattern$BnM.optimize.
        int pd = Loader.findPdByName(Loader.gbase, classOff);
        while (pd >= 0)
        {
            int i2 = fldIndexOf(Loader.pdBase.get(pd), Loader.pdNameOff[pd], Loader.gbase, nameOff);   // declared by THIS ancestor
            if (i2 >= 0)
            {
                Loader.fieldOffsetLog(classOff, nameOff, 16 + fldTab[i2].slot * 8, 2);
                return 16 + fldTab[i2].slot * 8;
            }
            if (Loader.pdSuperOff[pd] == 0)
            {
                break;
            }
            pd = Loader.findPdByName(Loader.pdBase.get(pd), Loader.pdSuperOff[pd]);   // ascend to the superclass
        }
        // NOTHING MATCHED. `return 16` is slot 0 -- the object's FIRST field -- so an unresolved reference
        // silently reads and writes a field of some other name and type. On a subclass that is exactly an
        // inherited REFERENCE, and an int counter then reads back a heap address (DeflaterClose's nested
        // counters printed 67885768 for an expected 3). The ordinary cause is a class not registered when
        // the referencing body compiled -- a privately nested class the enclosing method touches, pulled
        // late by `newresolve` -- so take the same note-pull-recompile route a static and a class literal
        // take, and name it if it is still missing on the retry.
        if (Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeeded(Loader.gbase.plus(classOff)))
        {
            Loader.fieldOffsetLog(classOff, nameOff, 16, 3);   // 3 = pull noted, compile to be discarded and redone
            return 16;                                  // this compile is discarded and redone with it loaded
        }
        Loader.fieldOffsetLog(classOff, nameOff, 16, 4);       // 4 = gave up: slot 0, and it is NOT necessarily right
        Loader.reportUnresolvedField(Loader.gbase.plus(classOff), Loader.gbase.plus(nameOff));
        return 16;
    }

    /** Instance-field byte offset for the field named by {@code *ref} index. */
    static int fieldOffsetOf(int idx)
    {
        if (Loader.utf8Eq(Loader.refClassNameOff(idx), Loader.gThisNameOff))
        {
            int nameOff = Loader.mrefNameOff(idx);                // Fieldref layout == Methodref layout
            int s = 0;
            while (s < Loader.gifCount)
            {
                if (Loader.gifName[s] == nameOff)
                {
                    Loader.fieldOffsetLog(Loader.refClassNameOff(idx), nameOff, 16 + s * 8, 0);   // 0 = same-class fast path
                    return 16 + s * 8;                     // this class's own field (ObjectModel: +16)
                }
                s += 1;
            }
            Loader.fieldOffsetLog(Loader.refClassNameOff(idx), nameOff, -1, 9);  // 9 = same-class MISS, fell through
        }
        return globalFieldOffset(idx);                     // another class, or an inherited field
    }

    /**
     * Whether Fieldref {@code idx} names a {@code volatile} field -- the JIT then fences the access (see
     * {@code Baseline.getfield}). Resolved the way {@link #fieldOffsetOf} and the static lookup resolve the
     * field itself: this class's own fields, then the registries class-qualified, then the superclass chain.
     * Compile-safe: it walks tables and never re-parses a constant pool. An unresolvable field answers false;
     * the access it guards is then unresolved anyway, and takes that path's own retry or report.
     */
    static boolean fieldIsVolatile(int idx, boolean isStatic)
    {
        int classOff = Loader.refClassNameOff(idx);
        int nameOff = Loader.mrefNameOff(idx);
        if (Loader.utf8Eq(classOff, Loader.gThisNameOff))
        {
            int s = 0;
            int n = isStatic ? Loader.gsfCount : Loader.gifCount;
            while (s < n)
            {
                int nm = isStatic ? Loader.gsfName[s] : Loader.gifName[s];
                if (nm == nameOff)
                {
                    return ((isStatic ? Loader.gsfAccess[s] : Loader.gifAccess[s]) & 0x0040) != 0;   // ACC_VOLATILE
                }
                s += 1;
            }
        }
        Address clsBase = Loader.gbase;
        int clsOff = classOff;
        int hops = 0;
        while (hops < 64)
        {
            int a = registeredFieldAccess(clsBase, clsOff, nameOff, isStatic);
            if (a >= 0)
            {
                return (a & 0x0040) != 0;
            }
            int pd = Loader.findPdByName(clsBase, clsOff);
            if (pd < 0 || Loader.pdSuperOff[pd] == 0)
            {
                return false;
            }
            clsBase = Loader.pdBase.get(pd);
            clsOff = Loader.pdSuperOff[pd];
            hops += 1;
        }
        return false;
    }

    /** access_flags of the registered field {@code nameOff} (in gbase) DECLARED by the class named at
     *  {@code clsBase+clsOff}, or -1. */
    private static int registeredFieldAccess(Address clsBase, int clsOff, int nameOff, boolean isStatic)
    {
        if (isStatic)
        {
            int k = Statics.sgIndexOf(clsBase, clsOff, Loader.gbase, nameOff);   // the static registry's hash index
            return k < 0 ? -1 : Statics.sgTab[k].access;
        }
        int i = fldIndexOf(clsBase, clsOff, Loader.gbase, nameOff);   // the same lookup globalFieldOffset makes
        return i < 0 ? -1 : fldTab[i].access;
    }
}
