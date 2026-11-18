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

import board.bcm2711.Uart;
import magic.Address;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import magic.ObjectReferenceArray;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * {@code java.lang.Class} mirrors (JikesRVM: {@code RVMType.getClassForType}). Split out of {@link Loader}.
 *
 * <p>A Class object per VM Type, materialised once and cached, so {@code X.class} (ldc class-literal) and
 * {@code obj.getClass()} return the SAME identity for the same Type -- which is what stock code compares (e.g.
 * Arrays.copyOf: {@code newType == Object[].class}). The mirror carries the Type node pointer in its sole field
 * (@16); Class methods (getName/getComponentType/isInstance/...) are added on demand. It is typed as the guest
 * java/lang/Class when loaded, else a bare identity object (TIB 0) -- enough for {@code ==}.
 */
final class ClassMirrors
{
    private ClassMirrors()
    {
    }

    // java.lang.Class mirrors: one Class object per VM Type (identity stable), materialised on ldc class-literal
    // or Object.getClass(). Per batch (they live in the reclaimed demand heap, like array Types).
    /** Class-mirror cache entries. An overflow breaks getClass()/X.class IDENTITY, so this is sized
     *  for the largest closure we run, not for the common case. */
    static final int MAXMIRROR = 4096;
    static AddressArray mirType;        // VM Type addr key
    static ObjectReferenceArray mirObj;   // its Class mirror object (no initializer: created per batch)
    static int mirN;
    static Address classTibCache;    // guest java/lang/Class's TIB (0 = not loaded)
    /** Print each loader reset: it clears the Class-mirror table, so mirrors minted before it go stale. */
    static final boolean MIRROR_RESET_WATCH = false;
    private static boolean mirFullReported;

    /**
     * {@code Class.forName0(byte[])} native: resolve a binary class name (raw ASCII bytes, dots) to its Class
     * mirror, loading the class incrementally into the LIVE program if it is not already loaded. Returns 0 (=>
     * the guest throws {@code ClassNotFoundException}) if the name contains '/' (not a valid binary name), is
     * not embedded in the classDir, or resolves to no loaded Type.
     */
    static long forNameMirror(ObjectReference nameArr)
    {
        return forNameMirror(nameArr, true);
    }

    /**
     * As {@link #forNameMirror(ObjectReference)}, initializing the class only when {@code init} is set: ClassLoader.loadClass
     * and Class.forName(name, false, loader) must not run a {@code <clinit>} (JVMS 5.5 names the initializing
     * triggers, and loading is not one of them).
     */
    static long forNameMirror(ObjectReference nameArr, boolean init)
    {
        if (nameArr.toLong() <= 0x1000L)
        {
            return 0L;                                  // boot force-compile guard passes 0
        }
        int len = (int) nameArr.toAddress().plus(16L).loadLong();    // byte[] length @16
        Address src = nameArr.toAddress().plus(24L);     // byte[] elements @24
        byte[] slash = new byte[len];
        int i = 0;
        while (i < len)
        {
            int c = u1(src.plus(i));
            if (c == 0x2F)                              // '/' is not a valid binary-name char -> not found
            {
                return 0L;
            }
            slash[i] = (byte) (c == 0x2E ? 0x2F : c);   // '.' -> '/' (classDir keys are internal names)
            i += 1;
        }
        if (len > 0 && slash[0] == 0x5B)                // '[': an ARRAY class, named by its descriptor
        {
            return ArrayTypes.arrayForName(slash);
        }
        int ci = ClassRegistry.classIndexByName(slash);
        if (ci >= 0)
        {
            Loader.linkable(ci);                                   // loaded or not, initialized or not: no class at all
            if (init)
            {
                Loader.ensureClinit(ci);                           // JVMS 5.5: Class.forName(name) INITIALIZES the class
            }
            return classMirror(ClassRegistry.clTab[ci].type);             // already loaded: cached mirror (identity-stable)
        }
        // A DENIED CLASS IS NOT FOUND, which is the truth: it is deliberately absent from this VM.
        //
        // `pullClass` goes straight to the classDir and does not consult the denylist -- the same bypass
        // already recorded for the deferred-`new` path. So forName would LOAD a denied class happily, and the
        // denial would only bite later, at a trap-wired call site, where it HALTS. Answering not-found here
        // moves that to a ClassNotFoundException the caller can handle: it is what lets ServiceLoader skip a
        // provider it cannot load (JUnit's file-writing OpenTestReportGeneratingListener) instead of halting
        // the run.
        if (Loader.isDenylisted(Loader.utf8Blob(slash), 0))
        {
            return 0L;                                  // => the guest throws ClassNotFoundException
        }
        Address type = Loader.loadClassIncremental(slash);
        if (!type.isZero())
        {
            Loader.linkable(ClassRegistry.classRegByType(type));
        }
        if (init)
        {
            Loader.ensureClinit(ClassRegistry.classRegByType(type));         // JVMS 5.5: forName INITIALIZES, and this batch's
        }                                               // runClinits left a gated initializer pending
        return type.isZero() ? 0L : classMirror(type);
    }

    /** The Class mirror of the internal name of {@code len} bytes at {@code p}, demand-loading it if needed. */
    static long mirrorOfInternalName(Address p, int len)
    {
        byte[] name = new byte[len];
        int i = 0;
        while (i < len)
        {
            name[i] = (byte) u1(p.plus(i));
            i += 1;
        }
        int ci = ClassRegistry.classIndexByName(name);
        if (ci < 0)
        {
            Loader.loadClassIncremental(name);
            ci = ClassRegistry.classIndexByName(name);
        }
        return ci < 0 ? 0L : classMirror(ClassRegistry.clTab[ci].type);
    }

    /** The Type of {@code java/lang/Class}, for typing a {@code Class[]}. */
    static Address classMirrorTypeAddr()
    {
        int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/Class"));
        return i < 0 ? TibBuilder.objectTypeAddr() : ClassRegistry.clTab[i].type;
    }

    /**
     * The mirror for the class whose internal name is the Utf8 at {@code utf8Addr}, demand-loading it if
     * needed, or 0 when it cannot be loaded (denied, or absent from the classDir).
     *
     * <p>It does NOT initialize. Obtaining a mirror is not one of JVMS 5.5's active uses -- {@code forName}
     * initializes because the specification says so, and {@code getDeclaredClasses} does not. Running the
     * initializer of every nested class a caller merely LOOKS at would be both wrong and, on this VM,
     * expensive: it is exactly the "an initializer pulled a whole subsystem in" hazard.
     */
    static long mirrorForNameAt(Address utf8Addr)
    {
        int len = u2(utf8Addr);
        if (len <= 0 || len > 4096)
        {
            return 0L;
        }
        byte[] name = new byte[len];
        int i = 0;
        while (i < len)
        {
            name[i] = (byte) u1(utf8Addr.plus(2 + i));
            i += 1;
        }
        int ci = ClassRegistry.classIndexByName(name);
        if (ci >= 0)
        {
            return classMirror(ClassRegistry.clTab[ci].type);
        }
        if (Loader.isDenylisted(Loader.utf8Blob(name), 0))
        {
            ClassAttributes.reportNestedNotLoaded(name);
            return 0L;
        }
        Address type = Loader.loadClassIncremental(name);
        if (type.isZero())
        {
            ClassAttributes.reportNestedNotLoaded(name);
            return 0L;
        }
        return classMirror(type);
    }

    /** guest java/lang/Class's TIB, or 0 if it isn't loaded in this batch. */
    private static Address classTib()
    {
        if (classTibCache.isZero())
        {
            int i = 0;
            while (i < ClassRegistry.clCount)
            {
                if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base, ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Class")))
                {
                    classTibCache = ClassRegistry.clTab[i].tib;
                    break;
                }
                i += 1;
            }
        }
        return classTibCache;
    }

    /** The Class mirror for VM Type {@code type}, materialised + cached (identity stable across ldc/getClass). */
    static long classMirror(Address type)
    {
        if (type.isZero())
        {
            return 0L;
        }
        int i = 0;
        while (i < mirN)
        {
            if (mirType.get(i).EQ(type))
            {
                return mirObj.get(i).toLong();
            }
            i += 1;
        }
        // header(16) + typeAddr(@16) + classValueMap(@24) + classLoader(@32) + protectionDomain(@40) +
        // signers(@48). Class mirrors are allocated HERE rather than by a constructor, so this size must track the
        // overlay's field count by hand: a field declared in guestsrc/java/lang/Class.java but not counted here is
        // written PAST the object.
        Address obj = Heap.alloc(56);
        Address ctib = classTib();
        if (mirN >= mirType.length())
        {
            reportMirrorCacheFull(type, ctib);              // past here getClass() mints a NEW mirror each ask
        }
        if (ctib.isZero())
        {
            reportMirrorNoTib(type);                        // a TIB-0 mirror is a WILD DISPATCH, not an identity
        }
        obj.plus(0L).store(ctib);                      // TIB (0 -> a bare identity object; see the report)
        obj.plus(16L).store(type);                     // the VM Type this Class mirrors
        if (mirN < mirType.length())
        {
            mirType.set(mirN, type);
            mirObj.set(mirN, obj.toObjectReference());
            mirN += 1;
        }
        return obj.toLong();
    }

    /**
     * Report the Class-mirror cache filling up, ONCE.
     *
     * <p>The cache is what makes {@code X.class} and {@code obj.getClass()} the same object. Past its end
     * every ask mints a fresh mirror, so {@code getClass() == getClass()} can still hold by luck while
     * {@code getClass() == X.class} does not -- and stock code compares mirrors constantly.
     */
    private static void reportMirrorCacheFull(Address type, Address ctib)
    {
        if (mirFullReported)
        {
            return;
        }
        mirFullReported = true;
        Uart.write(Magic.bytes("\n  CLASS MIRROR CACHE FULL at "));
        VM.printHex(mirN);
        Uart.write(Magic.bytes(" -- getClass() identity is no longer stable; first overflow is "));
        int r = ClassRegistry.regOfType(type);
        if (r < 0)
        {
            Uart.write(Magic.bytes("<type not registered>"));
        }
        else
        {
            Loader.printNameAt(ClassRegistry.clTab[r].base.plus(ClassRegistry.clTab[r].nameOff), 0);
        }
        Uart.write(Magic.bytes(" classTib="));
        VM.printHex(ctib);
        Uart.putc(0x0A);
    }

    /**
     * Report a Class mirror built with NO TIB, naming the class it mirrors.
     *
     * <p>The old comment called a TIB-0 mirror "a bare identity object, fine for ==", and that is true only
     * until someone calls a method on it. {@code instanceof Class} reads {@code [[obj]]}, i.e. address 0 --
     * the firmware's low-memory shim -- and a virtual call loads its target from there too, so the dispatch
     * lands on a plausible-looking address in unrelated code. That is the "reads firmware memory" family
     * again, and it destroys the evidence: the failure surfaces inside whatever method it happened to reach.
     *
     * <p>{@code getClass()} is called on everything, so this is worth a line even though it costs one.
     */
    private static void reportMirrorNoTib(Address type)
    {
        Uart.write(Magic.bytes("\n  CLASS MIRROR WITHOUT A TIB (getClass/instanceof will misdispatch): "));
        int r = ClassRegistry.regOfType(type);
        if (r < 0)
        {
            Uart.write(Magic.bytes("<type not registered> type="));
            VM.printHex(type);
        }
        else
        {
            Loader.printNameAt(ClassRegistry.clTab[r].base.plus(ClassRegistry.clTab[r].nameOff), 0);
        }
        Uart.write(Magic.bytes(" -- java/lang/Class is not registered in this batch\n"));
    }

    static long selfClassMirror()
    {
        return classMirror(Loader.gType);
    }

    static long classLiteral(int classCp)
    {
        Address type = DynamicTypeCheck.typeOfClass(classCp);
        if (type.isZero())
        {
            // A CLASS LITERAL FOR A CLASS NOTHING PULLED. `emitAddr` bakes the mirror as an IMMEDIATE, so
            // unlike a call there is no reloc for a later batch to patch -- 0 here means the literal is null
            // for ever, and the first use is an NPE somewhere else entirely. `Foo.class` compiled before Foo
            // is loaded is the ordinary way to get here: `assertThrows(IllegalThreadStateException.class, ...)`
            // in a lazily compiled test body, where the class is not pulled until something THROWS one --
            // long after the literal was baked. Note it and let lazyCompileLocked pull and recompile, the
            // same route a static of an unpulled class takes.
            int nameOff = Loader.gcp[u2(Loader.gbase.plus(Loader.gcp[classCp]))];
            if (u1(Loader.gbase.plus(nameOff + 2)) != 0x5B                     // an array literal builds its own Type
                    && Loader.lzCompiling && !Loader.lzRetried && Loader.notePullNeeded(Loader.gbase.plus(nameOff)))
            {
                return 0L;                                          // discarded compile: retried with it loaded
            }
            reportNullClassLiteral(Loader.gbase.plus(nameOff));
        }
        return classMirror(type);
    }

    /** Name a class literal that stayed null -- silence here surfaces as an NPE in unrelated code. */
    private static void reportNullClassLiteral(Address clsU)
    {
        if (Loader.unresStaticSeen == null)
        {
            Loader.unresStaticSeen = new long[64];
            Loader.unresStaticSeenN = 0;
        }
        int k = 0;
        while (k < Loader.unresStaticSeenN)
        {
            if (Loader.unresStaticSeen[k] == clsU.toLong())
            {
                return;                                             // once per class, as for a static
            }
            k += 1;
        }
        if (Loader.unresStaticSeenN < 64)
        {
            Loader.unresStaticSeen[Loader.unresStaticSeenN] = clsU.toLong();
            Loader.unresStaticSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  NULL CLASS LITERAL: "));
        Loader.printNameAt(clsU, 0);
        Uart.write(Magic.bytes(".class -- "));
        Loader.printWhyUnpulled(clsU);                         // the CHECKED cause, not "class never pulled"
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

    /**
     * The Class mirror of the JIT'd method containing machine PC {@code pc} (getCallerClass), or 0.
     *
     * <p>THE CONTAINING CODE BLOCK IS MATCHED EXACTLY, not guessed at. This used to take the registered
     * method with the greatest buffer address AT OR BELOW the pc, with no upper bound -- so a pc inside a
     * body the method registry does not hold answered with whatever registered method happened to sit
     * nearest below it in the code arena. That is a silent wrong class, and `MhUtil.findVarHandle` then
     * built a handle against it: on hardware {@code java/net/Socket} got a handle whose receiverType was
     * {@code jdk/internal/access/SharedSecrets}, and the generated accessor's
     * {@code receiverType.cast(holder)} threw a ClassCastException naming two classes with nothing to do
     * with each other. {@code Heap.codeBlockStartAt} answers which block CONTAINS the pc (its own doc:
     * "a pc lying in a DIFFERENT block cannot belong to the method registered at addr, however close the
     * two are"), which is the same bound {@code printFrameAt} had to learn.
     *
     * <p>AND INITIALIZER BODIES ARE SEARCHED TOO, which is the half that makes the common case work rather
     * than merely fail loudly. {@code clinitEntryOfLocked} compiles a {@code <clinit>} into its own buffer
     * and memoizes it in {@code clinitEntry[]} -- it calls {@code register} NOWHERE, so an initializer is in
     * no method registry and the scan above could never have found it. Every one of the 30 java.base sites
     * that reach the caller-sensitive {@code MhUtil.findVarHandle} calls it from a {@code <clinit>}, so that
     * omission was the whole defect; a probe calling the same form from {@code main} passes either way,
     * because {@code main} IS registered and genuinely contains the pc.
     *
     * <p>A miss returns 0 and the caller must REFUSE rather than guess -- image/baked code owns no code
     * block at all, so 0 is also the honest answer for a pc that never came from JIT'd guest code.
     */
    static long classMirrorAtPc(long pc)
    {
        long blk = Heap.codeBlockStartAt(Address.fromLong(pc)).toLong();
        if (blk == 0L)
        {
            return 0L;                                  // not in any JIT'd block (image/baked code)
        }
        int i = 0;
        while (i < MethodRegistry.rgCount)
        {
            if (MethodRegistry.rgTab[i].buf == blk)
            {
                return classMirrorByName(MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].classOff);
            }
            i += 1;
        }
        // Not a registered method: try the initializers, which are registered nowhere.
        if (Loader.clinitEntry != null)
        {
            int c = 0;
            while (c < Loader.clinitN)
            {
                if (Loader.clinitEntry[c] == blk)
                {
                    return classMirrorByName(Loader.clinitBase.get(c), Loader.clinitNameOff[c]);
                }
                c += 1;
            }
        }
        return 0L;
    }

    /** Class mirror of the registered class whose name Utf8 is {@code base + off}, or 0. */
    private static long classMirrorByName(Address base, int off)
    {
        int ci = 0;
        while (ci < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[ci] != null && Loader.utf8EqAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, base, off))
            {
                return classMirror(ClassRegistry.clTab[ci].type);
            }
            ci += 1;
        }
        return 0L;
    }

    /** {@code Object.getClass()}: object header -> TIB -> Type -> its Class mirror. */
    static long getClassOf(ObjectReference obj)
    {
        if (obj.isNull())
        {
            return 0L;
        }
        long tib = obj.toAddress().plus(0L).loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 0L;                                      // a raw array (no Type) — no mirror
        }
        return classMirror(Address.fromLong(tib).loadAddress());              // TIB[0] = Type (class Type or array Type)
    }
}
