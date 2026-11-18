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
import classfile.ClassReader;
import magic.Address;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * Reflection: resolving a {@code Method}/{@code Constructor}/{@code Field} to the VM's registries, field offsets and
 * modifiers, {@code getDeclared*} enumeration, and reflective instantiation (JikesRVM: {@code
 * runtime/Reflection}). The classfile attributes reflection reports are in {@link ClassAttributes}. Split out of
 * {@link Loader}.
 *
 * <p>STATIC FIELDS. The field registry (fldTab) holds INSTANCE fields only -- it exists to map a field to an offset
 * within an object -- so every static answered -1 from fieldMods and getDeclaredFields dropped it silently. That is a
 * real divergence from stock (getDeclaredFields must list statics, and Field.get(null) must read one), and it also
 * made statics UNDIAGNOSABLE: no probe could read a static's value, which is exactly what chasing picocli's
 * {@code blockingFailure()} needed. Statics are answered from the two places that do know about them: the CLASSFILE
 * for the declaration (access flags, descriptor) and sgTab for the CELL.
 *
 * <p>LOOKUPS THROUGH THE REGISTRY INDEX. ctorResolveRegistry / methodResolveRegistry(Desc) walked EVERY registry
 * entry comparing names, after a linear clTab scan for the class. MEASURED (SslEngineProbe, QEMU 2026-11-07): 685
 * constructor and 867 method-with-descriptor resolves walked 9.46M and 12.95M entries (rgCount reaches 17,852, each
 * a separate object); on the Pi they were the largest busy items inside &lt;clinit&gt; by tick sampling (JCA providers
 * are instantiated reflectively). The registry is already hashed by (class name, method name) -- rgBucket, the key
 * lazyBodyEntry probes -- so these probe that one chain with the scan's own predicate and keep the LOWEST matching
 * index, which is what the scan returned (chains run descending). Checked against the scans (temporary checker): 0
 * mismatches over 685 constructor and 867 descriptor resolves and 4,120 type-memo hits on SslEngineProbe, and over
 * the 25 reflection/invoke/proxy/record probes; the name-only resolve was not reached by any of them -- it has the
 * descriptor form's key and predicate minus one test.
 *
 * <p>ENUMERATION. getDeclaredMethods runs over the METHOD REGISTRY rather than the classfile: the registry is exactly
 * the set the VM knows about (every method of a registered class is there, compiled or as a deferral stub), and it
 * is already keyed by class+name+descriptor. Reflection is not a hot path, so a linear scan is fine.
 */
final class Reflection
{
    private Reflection()
    {
    }

    private static int cfFieldAccess;                   // set by classfileFieldInfo
    private static int cfFieldDescChar;
    /** Method-registry index of the class's {@code <init>} with {@code paramCount} parameters, or -1. */
    /**
     * The DESCRIPTOR a constructor resolve must match exactly (a raw {@code byte[]} address), or 0 for the arity
     * match. Arity alone cannot tell {@code (String)} from {@code (int)}: the first one found answered for both, so
     * {@code getDeclaredConstructors} listed one constructor twice and {@code getDeclaredConstructor(int.class)}
     * could hand back the String one. Set only by {@link #constructorResolveDesc}, under the loader lock, and
     * consulted at the two match points (the registry scan and the on-demand method walk).
     */
    private static ObjectReference ctorWantDesc;

    /** Field-registry index of instance field {@code fname} (raw bytes) declared by the class whose Type is
     *  {@code typeAddr}, or -1. Used by the reflection field-metadata natives. */
    private static int fieldRegIndex(Address typeAddr, Address fnBase, int fnLen)
    {
        int ci = 0;
        while (ci < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[ci].type.EQ(typeAddr))
            {
                int j = 0;
                while (j < FieldRegistry.fldCount)
                {
                    if (Loader.utf8EqAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, FieldRegistry.fldTab[j].base, FieldRegistry.fldTab[j].classOff)
                            && Utf8.rawEqUtf8(fnBase, fnLen, FieldRegistry.fldTab[j].base, FieldRegistry.fldTab[j].nameOff))
                    {
                        return j;
                    }
                    j += 1;
                }
            }
            ci += 1;
        }
        return -1;
    }

    /** Find a field by name in the class's own FIELDS section; sets {@link #cfFieldAccess}/{@link
     *  #cfFieldDescChar}. Both are read in ONE walk: each call re-parses the constant pool, and reading the
     *  descriptor after a second walk would be against a re-derived cursor. */
    private static boolean classfileFieldInfo(Address typeAddr, Address fnBase, int fnLen)
    {
        int ci = ClassRegistry.classRegByType(typeAddr);
        if (ci < 0)
        {
            return false;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        Address p = Loader.gp.plus(6L);                               // past access_flags, this_class, super_class
        p = p.plus(2L + u2(p) * 2L);                           // past the interfaces list
        int fcount = u2(p);
        p = p.plus(2);
        int f = 0;
        while (f < fcount)
        {
            if (Utf8.rawEqUtf8(fnBase, fnLen, base, Loader.gView.cp[u2(p.plus(2))]))
            {
                cfFieldAccess = u2(p);
                cfFieldDescChar = u1(base.plus(Loader.gView.cp[u2(p.plus(4))] + 2L));
                return true;
            }
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));
            f += 1;
        }
        return false;
    }

    /**
     * The ADDRESS of a static field's cell, or 0. {@code Field.get/set} reads and writes through this exactly
     * as an instance field goes through an offset.
     *
     * <p>Runs the class's initializer first, which is what stock does: reflective access to a static is an
     * ACTIVE USE (JVMS 5.5), so returning the cell of a class whose {@code <clinit>} has not run would hand
     * back a zero that looks like a value.
     */
    static long staticCellFor(Address typeAddr, Address fnBase, int fnLen)
    {
        int ci = ClassRegistry.classRegByType(typeAddr);
        if (ci < 0)
        {
            return 0L;
        }
        ClassInitializer.ensureClinit(ci);
        int i = 0;
        while (i < Statics.sgCount)
        {
            if (Loader.utf8EqAt(Statics.sgTab[i].base, Statics.sgTab[i].classOff, ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff)
                    && Utf8.rawEqUtf8(fnBase, fnLen, Statics.sgTab[i].base, Statics.sgTab[i].nameOff))
            {
                if (Loader.STATIC_ADDR_LOG)
                {
                    Uart.write(Magic.bytes("  reflectcell "));
                    Loader.printNameAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff);
                    Uart.write(Magic.bytes(" -> "));
                    VM.printHex(Statics.sgTab[i].addr);
                    Uart.putc(0x0A);
                }
                return Statics.sgTab[i].addr;
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Byte offset of the instance field {@code fnBase/fnLen} declared by the class whose Type is
     * {@code typeAddr}, or -1. The Class-keyed sibling of {@link MethodHandles#vhFieldOffset} (which keys on a TIB read
     * from a live object) -- {@code Unsafe.objectFieldOffset(Class, String)} has only the class, never an
     * instance, so it cannot go through the TIB.
     *
     * <p>Same registry and the same {@code 16 + slot*8} layout as every other field access here, so a handle
     * obtained this way and an ordinary {@code getfield} address the same memory by construction.
     */
    static long fieldOffsetOfType(Address typeAddr, Address fnBase, int fnLen)
    {
        int j = fieldRegIndex(typeAddr, fnBase, fnLen);
        if (j >= 0)
        {
            return 16L + FieldRegistry.fldTab[j].slot * 8L;
        }
        return -1L;
    }

    static int fieldMods(Address typeAddr, Address fnBase, int fnLen)
    {
        int j = fieldRegIndex(typeAddr, fnBase, fnLen);
        if (j >= 0)
        {
            return FieldRegistry.fldTab[j].access;
        }
        return classfileFieldInfo(typeAddr, fnBase, fnLen) ? cfFieldAccess : -1;   // statics live only here
    }

    /**
     * {@code Class.getModifiers()}: the loaded class's Java modifiers. For a nested class the real flags live
     * in the enclosing class's {@code InnerClasses} attribute (this class's own {@code access_flags} lack the
     * {@code private}/{@code protected}/{@code static} distinction), so prefer the {@code InnerClasses} entry
     * whose {@code inner_class_info} is this class. The VM-only {@code ACC_SUPER} (0x20) bit is stripped.
     */
    static int classModifiersOf(Address type)
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(type))
            {
                int inner = innerClassFlags(i);
                if (inner >= 0)
                {
                    return inner & ~0x0020 & 0x7FFF;           // as HotSpot: no ACC_SUPER, written flags only
                }
                return ClassRegistry.clTab[i].modifiers;                     // top-level: access_flags, cached at load time
            }
            i += 1;
        }
        return 0;
    }

    /**
     * The {@code inner_class_access_flags} of this class's OWN entry in its {@code InnerClasses} attribute
     * (JVMS 4.7.6), or -1 when it has none -- a top-level class. A nested class's {@code access_flags} cannot
     * say {@code static}, {@code private} or {@code protected}; only this entry can, so without it every member
     * class read as package-or-public and non-static ({@code toGenericString} printed {@code class X$Y} where
     * stock prints {@code static class X$Y}; GenericsProbe). Read at reflection time, under the loader lock
     * the native takes, from the blob -- the load-time capture keeps the raw flags for the reason noted there.
     * Matched by constant-pool index: javac's {@code inner_class_info_index} for a class's own entry IS its
     * {@code this_class}, as {@link #innerAccessOf} also relies on.
     */
    private static int innerClassFlags(int reg)
    {
        Address e = ownInnerClassEntry(ClassRegistry.clTab[reg].base);
        if (e.isZero())
        {
            return -1;
        }
        return u2(e.plus(6));                               // inner_class_access_flags
    }

    /**
     * Position of the class's OWN entry in its {@code InnerClasses} attribute (JVMS 4.7.6) -- the one whose
     * {@code inner_class_info_index} is {@code this_class} -- or 0 when it has none (a top-level class). The
     * entry is four u2s: inner class, outer class, simple name, access flags.
     */
    static Address ownInnerClassEntry(Address base)
    {
        Address ap = ClassAttributes.classAttrsPos(base);
        if (ap.isZero())
        {
            return Address.zero();
        }
        Address lp = ClassAttributes.innerClassesPos(base, ap.plus(2L), u2(ap));
        if (lp.isZero())
        {
            return Address.zero();
        }
        int thisIdx = u2(base.plus(Loader.gView.afterCp + 2));          // this_class, per the ClassReader.afterCp layout
        if (thisIdx == 0)
        {
            return Address.zero();
        }
        int n = u2(lp);
        Address p = lp.plus(2L);
        int e = 0;
        while (e < n)
        {
            if (u2(p) == thisIdx)                       // inner_class_info_index == this_class
            {
                return p;
            }
            p = p.plus(8);
            e += 1;
        }
        return Address.zero();
    }

    /**
     * Reflection ({@code Class.getDeclaredMethod}): the method-registry index of the first method named
     * {@code nameArr} (a raw {@code byte[]}) declared by the class whose Type is {@code type}, or -1. Matches
     * class name + method name (no overload/param-type resolution yet — first match wins).
     */
    static int methodResolve(Address type, ObjectReference nameArr)
    {
        if (type.isZero() || nameArr.toLong() <= 0x1000L)
        {
            return -1;
        }
        ClassInitializer.ensureClinit(ClassRegistry.classRegByType(type));             // reflective invoke is an active use (JVMS 5.5) --
                                                        // Class.getEnumConstants reaches values() this way, and
                                                        // values() reads $VALUES, which only <clinit> sets
        int idx = methodResolveRegistry(type, nameArr);
        if (idx >= 0)
        {
            return idx;                                    // already compiled + registered
        }
        return LazyCompiler.compileMethodOnDemand(type, nameArr);       // reflectively-only method: JIT it now, then resolve
    }

    /**
     * Resolve a method by name AND DESCRIPTOR -- the only key that identifies one when a class overloads. Falls
     * back to the name-only answer when the descriptor matches nothing, so a caller that has no descriptor (or
     * one this VM spells differently) is never made worse off than {@link #methodResolve}.
     */
    static int methodResolveDesc(Address type, ObjectReference nameArr, ObjectReference descArr)
    {
        if (descArr.toLong() <= 0x1000L)
        {
            return methodResolve(type, nameArr);
        }
        if (type.isZero() || nameArr.toLong() <= 0x1000L)
        {
            return -1;
        }
        ClassInitializer.ensureClinit(ClassRegistry.classRegByType(type));             // reflective invoke is an active use (JVMS 5.5)
        int idx = methodResolveRegistryDesc(type, nameArr, descArr);
        if (idx >= 0)
        {
            return idx;
        }
        idx = LazyCompiler.compileMethodOnDemand(type, nameArr, descArr);   // reflectively-only method: JIT exactly THIS one
        if (idx >= 0)
        {
            return idx;
        }
        VM.loaderLock(VM.LOCK_REFLECT);                 // bodylessIndex re-points the shared parse globals
        try
        {
            idx = bodylessIndex(type, nameArr, descArr); // abstract or native: a Method with no body to call
        }
        finally
        {
            VM.loaderUnlock();
        }
        if (idx >= 0)
        {
            return idx;
        }
        return methodResolve(type, nameArr);            // descriptor matched nothing: no worse than name-only
    }

    /**
     * {@code Method.exact0}: the registry index of the method the class whose Type is {@code type} DECLARES
     * with exactly this name and descriptor and a body to run, compiling it on demand, or -1 -- never the
     * name-only fallback {@link #methodResolveDesc} ends in, and never a bodyless entry. This is the per-class
     * step of reflective VIRTUAL dispatch, where a same-named method with another descriptor is not an
     * override and must not be called. Not an active use of the class: the receiver's class is initialized.
     */
    static int methodResolveExact(Address type, ObjectReference nameArr, ObjectReference descArr)
    {
        if (type.isZero() || nameArr.toLong() <= 0x1000L || descArr.toLong() <= 0x1000L)
        {
            return -1;
        }
        int idx = methodResolveRegistryDesc(type, nameArr, descArr);
        if (idx >= 0)
        {
            return idx;
        }
        return LazyCompiler.compileMethodOnDemand(type, nameArr, descArr);
    }

    /**
     * {@code MethodHandleNatives.declared0}: the reflection index of the method the class whose Type is
     * {@code type} ITSELF declares with exactly this name and descriptor -- a body (compiled on demand), or an
     * abstract/native one through {@link #bodylessIndex} -- or -1.
     *
     * <p>Two things {@link #methodResolveDesc} does are WRONG for MemberName resolution and are absent here. It
     * runs the class's initializer, and HotSpot resolves a MemberName with {@code ClassInitMode::dont_init}:
     * DirectMethodHandle decides for itself whether a handle needs an initialization barrier
     * ({@code Unsafe.shouldBeInitialized}), and an eager one makes that answer always "no". And it falls back to
     * a NAME-ONLY match, where resolution that finds no exact descriptor must fail -- the JVMS 5.4.3.3 walk over
     * superclasses and superinterfaces is the caller's, one class at a time.
     */
    static int mhDeclaredIndex(Address type, ObjectReference nameArr, ObjectReference descArr)
    {
        int idx = methodResolveExact(type, nameArr, descArr);
        if (idx >= 0 || type.isZero() || nameArr.toLong() <= 0x1000L || descArr.toLong() <= 0x1000L)
        {
            return idx;
        }
        VM.loaderLock(VM.LOCK_REFLECT);                 // bodylessIndex re-points the shared parse globals
        try
        {
            return bodylessIndex(type, nameArr, descArr);
        }
        finally
        {
            VM.loaderUnlock();
        }
    }

    /**
     * {@code Method.itableTarget0}: the body the RECEIVER's itable directory answers for the method at reflection
     * index {@code rgIndex}, or 0 -- {@link InterfaceInvocation#resolveViaItableDir}, the tier the JIT's late interface dispatch
     * uses. It is the only route to a receiver the VM SYNTHESISED (a lambda, an annotation instance), which has
     * a Type and an itable but no class file for a class-chain walk to read, and it answers a default method
     * with the implementation THIS receiver runs.
     */
    static long reflectItableTarget(ObjectReference recv, int rgIndex)
    {
        RVMMethod e = reflEntry(rgIndex);
        if (e == null || recv.toLong() <= 0x1000L)
        {
            return 0L;
        }
        long tib = recv.toAddress().loadLong();
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 0L;
        }
        Address type = Address.fromLong(tib).loadAddress();
        if (type.toLong() <= 0x1000L)
        {
            return 0L;
        }
        return InterfaceInvocation.resolveViaItableDir(type, e.base.plus(e.nameOff), e.base.plus(e.descOff));
    }

    /** The entry a reflection index names: a registry slot, or {@code BODYLESS + k} for a bodyless method. */
    static RVMMethod reflEntry(int idx)
    {
        if (idx >= MethodRegistry.BODYLESS)
        {
            int k = idx - MethodRegistry.BODYLESS;
            return k < MethodRegistry.bdCount ? MethodRegistry.bdTab[k] : null;
        }
        if (idx < 0 || idx >= MethodRegistry.rgCount)
        {
            return null;
        }
        return MethodRegistry.rgTab[idx];
    }

    /**
     * A reflection index for the ABSTRACT or NATIVE method the class whose Type is {@code type} declares with
     * this name and descriptor, or -1 when it declares none without a {@code Code} attribute. Registration
     * follows a compiled body, so such a method has no registry slot and, until this, no {@code Method}:
     * {@code getMethod} threw for it and {@code getDeclaredMethods} silently left it out.
     *
     * <p>THE ENTRY IS KEPT OUT OF THE REGISTRY ON PURPOSE. The call linker's lookups ({@code globalBuf},
     * {@code globalBufByRef}'s tiers) return the first matching entry's {@code buf} without testing it, so an
     * entry with no body would answer 0 where the search used to fall through to its later tiers. Only the
     * reflection natives read this table, through {@link #reflEntry}. One entry per method: a repeat lookup
     * answers the same index.
     */
    private static int bodylessIndex(Address type, ObjectReference nameArr, ObjectReference descArr)
    {
        if (descArr.toLong() <= 0x1000L)
        {
            return -1;
        }
        int ci = ClassRegistry.classIndexByType(type);
        if (ci >= ClassRegistry.clCount)
        {
            return -1;
        }
        Address blob = ClassRegistry.clTab[ci].base;
        Loader.parseForMethods(blob, Loader.blobLenOf(blob));
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
        Address nbase = nameArr.toAddress().plus(24L);
        int dlen = (int) descArr.toAddress().plus(16L).loadLong();
        Address dbase = descArr.toAddress().plus(24L);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (Utf8.rawEqUtf8(nbase, nlen, blob, Loader.gView.cp[u2(p.plus(2))]) && Utf8.rawEqUtf8(dbase, dlen, blob, Loader.gView.cp[u2(p.plus(4))]))
            {
                if (Loader.findCode(blob, p.plus(8), attrs) != 0L)
                {
                    return -1;                          // it HAS a body: not this table's business
                }
                int nameOff = Loader.gView.cp[u2(p.plus(2))];
                int descOff = Loader.gView.cp[u2(p.plus(4))];
                int k = 0;
                while (k < MethodRegistry.bdCount)
                {
                    if (MethodRegistry.bdTab[k].base.EQ(blob) && MethodRegistry.bdTab[k].nameOff == nameOff && MethodRegistry.bdTab[k].descOff == descOff)
                    {
                        return MethodRegistry.BODYLESS + k;
                    }
                    k += 1;
                }
                if (MethodRegistry.bdTab == null)
                {
                    MethodRegistry.bdTab = new RVMMethod[256];
                }
                if (MethodRegistry.bdCount >= MethodRegistry.bdTab.length)
                {
                    RVMMethod[] grown = new RVMMethod[MethodRegistry.bdTab.length * 2];
                    int j = 0;
                    while (j < MethodRegistry.bdCount)
                    {
                        grown[j] = MethodRegistry.bdTab[j];
                        j += 1;
                    }
                    MethodRegistry.bdTab = grown;
                }
                RVMMethod e = new RVMMethod();
                e.base = blob;
                e.classOff = ClassRegistry.clTab[ci].nameOff;
                e.nameOff = nameOff;
                e.descOff = descOff;
                e.buf = 0L;                             // nothing to call: Method.invoke dispatches or throws
                e.access = u2(p);
                MethodRegistry.bdTab[MethodRegistry.bdCount] = e;
                MethodRegistry.bdCount += 1;
                return MethodRegistry.BODYLESS + MethodRegistry.bdCount - 1;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return -1;
    }

    /** Parameter count of a raw method descriptor Utf8 ("(...)ret") at {@code descAddr} (array params fold once). */
    private static int descParamCountRaw(Address descAddr)
    {
        long p = descAddr.toLong() + 2 + 1;                 // past u2 length and '('
        int n = 0;
        while (u1(p) != 0x29)                               // ')'
        {
            int c = u1(p);
            if (c == 0x4C)                                  // 'L' reference
            {
                while (u1(p) != 0x3B) { p += 1; }
                p += 1;
                n += 1;
            }
            else if (c == 0x5B)                             // '[' array prefix: folds into its element
            {
                p += 1;
            }
            else                                            // primitive
            {
                p += 1;
                n += 1;
            }
        }
        return n;
    }

    /** Does the {@code <init>} whose descriptor is at {@code base + descOff} match the current request? */
    private static boolean ctorMatches(Address base, int descOff, int paramCount)
    {
        if (!ctorWantDesc.isNull())
        {
            return Utf8.rawEqUtf8(ctorWantDesc.toAddress().plus(24L), (int) ctorWantDesc.toAddress().plus(16L).loadLong(), base, descOff);
        }
        return descParamCountRaw(base.plus(descOff)) == paramCount;
    }

    /** {@link #constructorResolve} matched by DESCRIPTOR (see {@link #ctorWantDesc}); -1 if none. */
    static int constructorResolveDesc(Address type, ObjectReference descArr)
    {
        if (descArr.toLong() <= 0x1000L)
        {
            return -1;
        }
        VM.loaderLock(VM.LOCK_DEFERRED_CT);
        ObjectReference outer = ctorWantDesc;
        int r;
        try
        {
            ctorWantDesc = descArr;
            r = constructorResolveLocked(type, -1);
        }
        finally
        {
            ctorWantDesc = outer;
            VM.loaderUnlock();
        }
        return r;
    }

    /** Constructor of clTab[ci] by arity (or ctorWantDesc): registry index, or -1. Caller holds the loader lock. */
    private static int ctorResolveRegistry(int ci, int paramCount)
    {
        MethodRegistry.buildRegIndex();
        int best = -1;
        int i = MethodRegistry.rgBucket[Utf8.utf8HashThenBytes(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, Magic.bytes("<init>")) & (MethodRegistry.RGTAB - 1)];
        while (i >= 0)
        {
            if ((best < 0 || i < best)
                    && Loader.utf8EqAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].classOff)
                    && Utf8.utf8IsAtBase(MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].nameOff, Magic.bytes("<init>"))
                    && ctorMatches(MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].descOff, paramCount))
            {
                best = i;
            }
            i = MethodRegistry.rgNext[i];
        }
        return best;
    }

    /** Method-registry index of the method named {@code nameArr} declared by the class whose Type is {@code type}
     *  (class name + method name match), or -1 if it is not currently compiled/registered. Takes the loader
     *  lock: it is reached unlocked, and buildRegIndex extends the index a compiling task also extends. */
    static int methodResolveRegistry(Address type, ObjectReference nameArr)
    {
        VM.loaderLock(VM.LOCK_REFLECT);                 // buildRegIndex mutates the index a compiling task extends
        try
        {
            int ci = ClassRegistry.classIndexByType(type);
            if (ci >= ClassRegistry.clCount)
            {
                return -1;
            }
            int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
            Address nbase = nameArr.toAddress().plus(24L);
            MethodRegistry.buildRegIndex();
            int best = -1;
            int i = MethodRegistry.rgBucket[Utf8.utf8HashThenRaw(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, nbase, nlen) & (MethodRegistry.RGTAB - 1)];
            while (i >= 0)
            {
                if ((best < 0 || i < best)
                        && Loader.utf8EqAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].classOff)
                        && Utf8.rawEqUtf8(nbase, nlen, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].nameOff))
                {
                    best = i;
                }
                i = MethodRegistry.rgNext[i];
            }
            return best;
        }
        finally
        {
            VM.loaderUnlock();
        }
    }

    /** {@link #methodResolveRegistry} with the descriptor compared too. Takes the loader lock, likewise. */
    static int methodResolveRegistryDesc(Address type, ObjectReference nameArr, ObjectReference descArr)
    {
        VM.loaderLock(VM.LOCK_REFLECT);
        try
        {
            int ci = ClassRegistry.classIndexByType(type);
            if (ci >= ClassRegistry.clCount)
            {
                return -1;
            }
            int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
            Address nbase = nameArr.toAddress().plus(24L);
            int dlen = (int) descArr.toAddress().plus(16L).loadLong();
            Address dbase = descArr.toAddress().plus(24L);
            MethodRegistry.buildRegIndex();
            int best = -1;
            int i = MethodRegistry.rgBucket[Utf8.utf8HashThenRaw(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, nbase, nlen) & (MethodRegistry.RGTAB - 1)];
            while (i >= 0)
            {
                if ((best < 0 || i < best)
                        && Loader.utf8EqAt(ClassRegistry.clTab[ci].base, ClassRegistry.clTab[ci].nameOff, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].classOff)
                        && Utf8.rawEqUtf8(nbase, nlen, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].nameOff)
                        && Utf8.rawEqUtf8(dbase, dlen, MethodRegistry.rgTab[i].base, MethodRegistry.rgTab[i].descOff))
                {
                    best = i;
                }
                i = MethodRegistry.rgNext[i];
            }
            return best;
        }
        finally
        {
            VM.loaderUnlock();
        }
    }

    /**
     * THE COMPILE CONTEXT IS STATIC, so exactly one task may be inside the compiler at a time. {@code
     * lazyCompile} has always taken {@link VM#loaderLock} for that reason; this path reached {@code compile}
     * without it, so a second task compiling here while another compiled elsewhere clobbered {@code gView.bytes}/
     * {@code gView.cp} mid-compile -- which surfaces as a NULL bytecode array inside the compiler
     * ({@code NullPointerException} in {@code Baseline.s4}, lowering a switch) rather than as anything
     * resembling a locking bug. The lock is RECURSIVE and owned by TASK, so nesting inside an
     * already-locked path costs nothing.
     *
     * <p>The lock covers the COMPILE only, never the running of an initializer: a {@code <clinit>} can block
     * on a monitor or spawn threads, and holding the loader lock across that would deadlock any task that
     * needs to compile before it can release.
     */
    static int constructorResolve(Address type, int paramCount)
    {
        VM.loaderLock(VM.LOCK_DEFERRED_CT);
        int r;
        try
        {
            r = constructorResolveLocked(type, paramCount);
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        return r;
    }

    /**
     * Reflection ({@code Class.getDeclaredConstructor}): registry index of the {@code <init>} of the class whose
     * Type is {@code type} taking {@code paramCount} parameters (matched by ARITY — no param-type resolution yet),
     * compiling it on demand if it was never RTA-reached. Returns -1 if none.
     */
    static int constructorResolveLocked(Address type, int paramCount)
    {
        if (type.isZero())
        {
            return -1;
        }
        int ci = ClassRegistry.classIndexByType(type);
        if (ci >= ClassRegistry.clCount || ClassRegistry.clTab[ci].isIface)
        {
            return -1;
        }
        int idx = ctorResolveRegistry(ci, paramCount);
        if (idx >= 0)
        {
            return idx;
        }
        // on-demand: compile the matching <init> (its declaring class is already structure-loaded)
        Address blob = ClassRegistry.clTab[ci].base;
        int len = Loader.blobLenOf(blob);
        Loader.parseConstPool(blob, len);
        Loader.parseFields();
        int reg = ClassRegistry.classRegByName(Loader.gView.thisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        Loader.gStatics = ClassRegistry.clTab[reg].statics;
        Indy.findBootstrapMethods();
        VtableLayout.parseVtable(blob);
        Loader.gType = ClassRegistry.clTab[reg].type;
        Loader.gTib = ClassRegistry.clTab[reg].tib;
        Loader.parseForMethods(blob, len);
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        long code = 0L;
        int descOff = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int dOff = Loader.gView.cp[u2(p.plus(4))];
            if (Utf8.utf8IsAtBase(blob, Loader.gView.cp[u2(p.plus(2))], Magic.bytes("<init>")) && ctorMatches(blob, dOff, paramCount))
            {
                long c = Loader.findCode(blob, p.plus(8), attrs);
                if (c != 0L)
                {
                    code = c;
                    descOff = dOff;
                    break;
                }
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return -1;
        }
        // THE SAME DEFERRED-PULL RETRY THE OTHER ON-DEMAND PATHS HAVE. This is the FOURTH path that reaches
        // compile() -- lazyCompileLocked, compileMethodOnDemand and compileSigOnDemand were given it in #213,
        // clinitEntryOf in #241, and this one was missed. Without it a getstatic naming a class RTA never
        // pulled resolves to the PERMANENTLY-ZERO cell: `compileReuseTib` means there is no later batch
        // patchRelocs to fill it in, so 0 is the final answer and the field reads null for the life of the VM.
        //
        // picocli's CommandLine.<init> is exactly that: it reads Help.Ansi.AUTO, and reflective construction
        // compiled it here, so the read bound to the zero cell and `defaultColorScheme` NPE'd on a null ansi
        // ("in the classDir, but resolved outside the retry window" -- the report named the cause precisely).
        int rcMark = Relocator.rcCount;                              // patch only THIS compile's relocs
        int rsMark = Relocator.rsCount;
        boolean reuseWas = Loader.compileReuseTib;              // a nested load resets it: restore, never clear
        Loader.compileReuseTib = true;
        boolean outerCompiling = Loader.lzCompiling;
        boolean outerRetried = Loader.lzRetried;
        int outerPullN = Loader.lzPullN;
        Loader.lzCompiling = true;
        Loader.lzPullN = 0;
        Loader.lzRetried = false;
        // JLS 8.8.3 forbids `synchronized` on a constructor, so isSync is 0 by the language, not by omission.
        Loader.compile(code, ClassBytes.codeLength(code), descOff, 0, 0);           // <init> is an instance method (receiver = the new object)
        Loader.lzCompiling = outerCompiling;
        Loader.compileReuseTib = reuseWas;
        boolean pulled = Loader.lzPullN > 0;
        if (pulled)
        {
            Loader.drainPendingPulls();
            Relocator.truncateRelocs(rcMark);                        // drop the doomed body's reloc sites: patching them
            Relocator.rsCount = rsMark;                              //   later would write into reused memory
        }
        Loader.lzRetried = outerRetried;
        Loader.lzPullN = outerPullN;
        if (pulled && !Loader.odRetried)
        {
            Loader.odRetried = true;                              // re-derived from (type, paramCount); bounded to one
            int again = constructorResolveLocked(type, paramCount);
            Loader.odRetried = false;
            return again;
        }
        MethodRegistry.registerAll();
        Relocator.patchRelocs();
        return ctorResolveRegistry(ci, paramCount);
    }

    /** Reflection ({@code Constructor.newInstance}): allocate an instance of the class whose Type is {@code type}
     *  (zeroed fields + its TIB in the header), or 0. The caller runs the {@code <init>} on it. */
    static long allocInstance(Address type)
    {
        if (type.isZero())
        {
            return 0L;
        }
        int ci = ClassRegistry.classIndexByType(type);
        if (ci >= ClassRegistry.clCount || ClassRegistry.clTab[ci].isIface)
        {
            return 0L;
        }
        ClassInitializer.ensureClinit(ci);                               // reflective instantiation is an active use, like `new`
        Address obj = Heap.alloc(16 + ClassRegistry.clTab[ci].fieldCount * 8);  // header(16) + instance fields (incl. inherited)
        obj.plus(ObjectModel.TIB_OFFSET).store(ClassRegistry.clTab[ci].tib);
        return obj.toLong();
    }

    /**
     * Reflection: fill {@code paramCharsArr} (a guest {@code byte[]}) with the first descriptor char of each of
     * method {@code rgIndex}'s parameters (primitives 'I'/'J'/'Z'/... verbatim; a reference or array param as
     * 'L'/'['), and write {@code out} (a guest {@code long[3]}): {@code [buffer, access_flags, returnChar]}.
     * Returns the parameter count. Float/double params are marshalled as their raw bits (no v-register support).
     */
    static int methodInfo(int rgIndex, ObjectReference paramCharsArr, ObjectReference outArr)
    {
        if (reflEntry(rgIndex) == null)
        {
            return 0;
        }
        Address descAddr = reflEntry(rgIndex).base.plus(reflEntry(rgIndex).descOff);   // "(...)ret" Utf8 (u2 length, then bytes)
        Address p = descAddr.plus(2 + 1);                              // past u2 length and '('
        Address pc = paramCharsArr.toAddress().plus(24L);       // byte[] elements
        int n = 0;
        while (u1(p) != 0x29)                                   // ')'
        {
            int c = u1(p);
            if (c == 0x4C)                                      // 'L' reference: skip to ';'
            {
                pc.plus(n).store((byte) 0x4C);
                while (u1(p) != 0x3B) { p = p.plus(1); }               // ';'
                p = p.plus(1);
            }
            else if (c == 0x5B)                                // '[' array: reference; skip prefixes + element
            {
                pc.plus(n).store((byte) 0x5B);
                while (u1(p) == 0x5B) { p = p.plus(1); }
                if (u1(p) == 0x4C) { while (u1(p) != 0x3B) { p = p.plus(1); } }
                p = p.plus(1);
            }
            else                                               // primitive: one char, one slot
            {
                pc.plus(n).store((byte) c);
                p = p.plus(1);
            }
            n += 1;
        }
        outArr.toAddress().plus(24L).plus(0L).store(reflEntry(rgIndex).buf);      // out[0] = compiled buffer
        outArr.toAddress().plus(24L).plus(8L).store((long) reflEntry(rgIndex).access);   // out[1] = access flags
        outArr.toAddress().plus(24L).plus(16L).store((long) u1(p.plus(1)));   // out[2] = return-type char (after ')')
        return n;
    }

    /**
     * Scan the class-level {@code InnerClasses} attribute for the entry whose {@code inner_class_info_index}
     * equals {@code thisClass}; return its {@code inner_class_access_flags}, or -1 if this class is not listed
     * (a top-level class). Assumes {@code gView.methodsStart}/{@code gView.cp} are current (post {@code parseForMethods}).
     */
    private static int innerAccessOf(int thisClass)
    {
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)                                // skip the methods table
        {
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));
            m += 1;
        }
        int cacount = u2(p);                              // class attributes_count
        p = p.plus(2);
        byte[] want = Magic.bytes("InnerClasses");
        int a = 0;
        while (a < cacount)
        {
            int nameIdx = u2(p);
            int alen = u4(p.plus(2));
            if (Loader.utf8IsStr(Loader.gView.cp[nameIdx], want))
            {
                Address q = p.plus(6);                           // attribute body: number_of_classes
                int nc = u2(q);
                q = q.plus(2);
                int e = 0;
                while (e < nc)
                {
                    if (u2(q) == thisClass)               // inner_class_info_index == this_class
                    {
                        return u2(q.plus(6));                 // inner_class_access_flags
                    }
                    q = q.plus(8);                               // inner(2) outer(2) name(2) flags(2)
                    e += 1;
                }
                return -1;
            }
            p = p.plus(6 + alen);
            a += 1;
        }
        return -1;
    }

    /** Reflection: first char of the field's JVM type descriptor (the byte after the u2 Utf8 length), or -1. */
    static int fieldTypeChar(Address typeAddr, Address fnBase, int fnLen)
    {
        int j = fieldRegIndex(typeAddr, fnBase, fnLen);
        if (j >= 0)
        {
            return u1(FieldRegistry.fldTab[j].base.plus(FieldRegistry.fldTab[j].descOff + 2L));
        }
        return classfileFieldInfo(typeAddr, fnBase, fnLen) ? cfFieldDescChar : -1;
    }

    /**
     * The {@code want}-th method DECLARED by the class mirrored by {@code mirror} -- or, for {@code want < 0},
     * how many there are. Walks the CLASSFILE method table, not the method registry.
     *
     * <p>The registry was the obvious source and is the wrong one: it holds only what RTA marked reachable,
     * and a test method is called by nobody, so every {@code @Test} is pruned from it. Enumerating the
     * classfile sees what the class actually declares; the caller then resolves each by name through the
     * ordinary reflection path, which compiles it on demand.
     *
     * <p>{@code <init>}/{@code <clinit>} are excluded, as {@code getDeclaredMethods} specifies.
     */
    static long declaredMethodName(ObjectReference mirror, int want)
    {
        return declaredMethodPart(mirror, want, 0);
    }

    /**
     * The {@code want}-th declared method's DESCRIPTOR, the companion of {@link #declaredMethodName}. Two methods
     * of a class can share a name, so the name ALONE does not identify one: enumerating by name and resolving by
     * name gives the same overload twice and never the other. (That is exactly what a stock {@code
     * @ParameterizedTest} looks like -- the test and its same-named {@code @MethodSource} factory -- and it
     * reported the test's own annotation for both while the factory was unreachable.) The caller pairs this with
     * the name and resolves through {@link #methodResolveDesc}.
     */
    static long declaredMethodDesc(ObjectReference mirror, int want)
    {
        return declaredMethodPart(mirror, want, 1);
    }

    /** Shared classfile walk: {@code part} 0 = the method's name Utf8, 1 = its descriptor Utf8. */
    /**
     * As {@link #declaredMethodPart}, but enumerating the CONSTRUCTORS -- exactly what that one filters out.
     *
     * <p>{@code declaredMethodPart} skips {@code <init>}/{@code <clinit>} because {@code getDeclaredMethods}
     * must not report them; {@code Class.getDeclaredConstructors} needs the complement. Written as its own
     * walk rather than a flag on that one so neither enumeration can quietly change the other's answer.
     *
     * <p>{@code part} 0 = name ({@code <init>}), 1 = descriptor. {@code want < 0} answers the COUNT.
     */
    static long declaredCtorPart(ObjectReference mirror, int want, int part)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int ci = ClassRegistry.classRegByType(mirror.toAddress().plus(16L).loadAddress());
        if (ci < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseForMethods(base, Loader.blobLenOf(base));
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int seen = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            if (Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("<init>")))   // constructors ONLY; <clinit> is not one
            {
                if (want >= 0 && seen == want)
                {
                    return Loader.utf8ToString(base, part == 0 ? nameOff : Loader.gView.cp[u2(p.plus(4))]);
                }
                seen += 1;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return want < 0 ? (long) seen : 0L;
    }

    private static long declaredMethodPart(ObjectReference mirror, int want, int part)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        Address type = mirror.toAddress().plus(16L).loadAddress();
        int ci = ClassRegistry.classRegByType(type);
        if (ci < 0)
        {
            return want < 0 ? 0L : 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseForMethods(base, Loader.blobLenOf(base));
        Address p = Loader.gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int seen = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            int nameOff = Loader.gView.cp[u2(p.plus(2))];
            if (!Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("<init>"))
                    && !Utf8.utf8IsAtBase(base, nameOff, Magic.bytes("<clinit>")))
            {
                if (want >= 0 && seen == want)
                {
                    return Loader.utf8ToString(base, part == 0 ? nameOff : Loader.gView.cp[u2(p.plus(4))]);
                }
                seen += 1;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return want < 0 ? (long) seen : 0L;
    }

    /** The {@code want}-th declared FIELD's name, or the count when {@code want < 0}. */
    static long declaredFieldName(ObjectReference mirror, int want)
    {
        return declaredFieldPart(mirror, want, 0);
    }

    /** The {@code want}-th declared field's DESCRIPTOR -- the companion of {@link #declaredFieldName}. */
    static long declaredFieldDesc(ObjectReference mirror, int want)
    {
        return declaredFieldPart(mirror, want, 1);
    }

    /**
     * Walk the classfile's FIELDS section: {@code part} 0 = the field's name Utf8, 1 = its descriptor.
     *
     * <p>Walked from the constant pool rather than reusing {@code parseFields()}, which is the LAYOUT pass --
     * it assigns slots, consults the superclass chain and writes {@code gsf*}/{@code superUnregistered}. Reusing
     * it for a read-only enumeration would re-run layout as a side effect of reflection, which is how a
     * "harmless" query corrupts state. This walk touches nothing but the cursor.
     *
     * <p>STATIC fields are included, as {@code getDeclaredFields} requires: the caller filters by modifier if
     * it cares, and dropping them here would silently answer a different question.
     */
    private static long declaredFieldPart(ObjectReference mirror, int want, int part)
    {
        if (mirror.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int ci = ClassRegistry.classRegByType(mirror.toAddress().plus(16L).loadAddress());
        if (ci < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        Address p = Loader.gp.plus(6L);                               // past access_flags, this_class, super_class
        p = p.plus(2L + u2(p) * 2L);                           // past the interfaces list
        int fcount = u2(p);
        p = p.plus(2);
        int f = 0;
        while (f < fcount)
        {
            if (want >= 0 && f == want)
            {
                return Loader.utf8ToString(base, Loader.gView.cp[u2(p.plus((part == 0 ? 2 : 4)))]);
            }
            p = Loader.skipAttributes(p.plus(8), u2(p.plus(6)));       // access, name, desc, attr_count, then attributes
            f += 1;
        }
        return want < 0 ? (long) fcount : 0L;
    }

    /**
     * {@code Field.type0}: the {@code Class} mirror of the declared type of field {@code nameArr}, or 0.
     *
     * <p>Recoverable now in a way it was not when {@code getDeclaredFields} landed: the field's DESCRIPTOR is
     * in the classfile, and the annotation runtime already resolves a descriptor to a mirror
     * ({@code annoClassValue}), primitives included. Previously only the type CHAR was kept, which cannot name
     * a reference type -- so the method was left out rather than answering {@code Object} for everything.
     *
     * <p>The descriptor's ABSOLUTE address is taken before resolving: resolving may demand-load, which
     * re-parses a blob and moves the {@code gView} cursor. Blobs themselves do not move, so an
     * absolute Utf8 pointer survives it -- the same property {@code applyAnnoDefaults} relies on.
     */
    static long fieldTypeMirror(ObjectReference mirror, ObjectReference nameArr)
    {
        if (mirror.toLong() <= 0x1000L || nameArr.toLong() <= 0x1000L)
        {
            return 0L;
        }
        int ci = ClassRegistry.classRegByType(mirror.toAddress().plus(16L).loadAddress());
        if (ci < 0)
        {
            return 0L;
        }
        Address base = ClassRegistry.clTab[ci].base;
        Loader.parseConstPool(base, Loader.blobLenOf(base));
        Address p = Loader.gp.plus(6L);
        p = p.plus(2L + u2(p) * 2L);                           // past the interfaces list
        int fcount = u2(p);
        p = p.plus(2);
        int nlen = (int) nameArr.toAddress().plus(16L).loadLong();
        Address nbase = nameArr.toAddress().plus(24L);
        Address descU = Address.zero();
        int f = 0;
        while (f < fcount)
        {
            int attrs = u2(p.plus(6));
            if (Utf8.rawEqUtf8(nbase, nlen, base, Loader.gView.cp[u2(p.plus(2))]))
            {
                descU = base.plus(Loader.gView.cp[u2(p.plus(4))]);          // absolute: survives the re-parse a resolve may cause
                break;
            }
            p = Loader.skipAttributes(p.plus(8), attrs);
            f += 1;
        }
        return descU.isZero() ? 0L : AnnotationInstances.annoClassValue(descU);
    }

    /**
     * {@code Method.paramTypes0}: a {@code Class[]} of the parameter types of the method registered at
     * {@code rgIndex}, resolved from its DESCRIPTOR.
     *
     * <p>The stored {@code paramChars} cannot answer this -- it keeps only the first descriptor character, so
     * every reference parameter looks alike. The descriptor has the real names, and the annotation runtime
     * already turns a descriptor into a mirror.
     *
     * <p>An ARRAY parameter resolves to null rather than to something plausible: joe-ng's array Types are
     * interned per element type and reaching one from a descriptor slice is a separate piece of work. A null
     * element is visibly wrong at the caller; {@code Object.class} would be quietly wrong.
     */
    static long methodParamTypes(int rgIndex)
    {
        if (reflEntry(rgIndex) == null)
        {
            return 0L;
        }
        Address base = reflEntry(rgIndex).base;
        int off = reflEntry(rgIndex).descOff;
        int len = u2(base.plus(off));
        Address d = base.plus(off + 2);                        // the descriptor bytes: "(...)ret"
        int n = countDescParams(d, len);
        Address arr = Heap.allocArray(n, 8);
        Address at = ArrayTypes.refArrayTib(ClassMirrors.classMirrorTypeAddr());
        if (!at.isZero())
        {
            arr.plus(ObjectModel.TIB_OFFSET).store(at);
        }
        int i = 1;                                      // past '('
        int k = 0;
        while (i < len && u1(d.plus(i)) != 0x29)            // ')'
        {
            int start = i;
            while (i < len && u1(d.plus(i)) == 0x5B)        // '[' -- array prefixes
            {
                i += 1;
            }
            long mirror;
            if (u1(d.plus(i)) == 0x4C)                      // 'L' <name> ';'
            {
                int nameStart = i + 1;
                while (i < len && u1(d.plus(i)) != 0x3B)
                {
                    i += 1;
                }
                mirror = ArrayTypes.arrayOf(ClassMirrors.mirrorOfInternalName(d.plus(nameStart), i - nameStart), nameStart - 1 - start);
                i += 1;                                 // past ';'
            }
            else
            {
                mirror = start == i ? Primitives.primitiveMirror(u1(d.plus(i))) : ArrayTypes.primArrayMirror(u1(d.plus(i)), i - start);
                i += 1;
            }
            arr.plus(24L).plus(k * 8L).store(mirror);
            k += 1;
        }
        return arr.toLong();
    }

    /** {@code Method.returnType0}: the declared return type's mirror, resolved from the descriptor. */
    static long methodReturnType(int rgIndex)
    {
        if (reflEntry(rgIndex) == null)
        {
            return 0L;
        }
        Address base = reflEntry(rgIndex).base;
        int off = reflEntry(rgIndex).descOff;
        int len = u2(base.plus(off));
        Address d = base.plus(off + 2);
        int i = 1;
        while (i < len && u1(d.plus(i)) != 0x29)            // skip to just past ')'
        {
            i += 1;
        }
        i += 1;
        int start = i;
        while (i < len && u1(d.plus(i)) == 0x5B)            // array prefixes: resolved by arrayOf/primArrayMirror
        {
            i += 1;
        }
        if (u1(d.plus(i)) == 0x4C)
        {
            int nameStart = i + 1;
            while (i < len && u1(d.plus(i)) != 0x3B)
            {
                i += 1;
            }
            return ArrayTypes.arrayOf(ClassMirrors.mirrorOfInternalName(d.plus(nameStart), i - nameStart), nameStart - 1 - start);
        }
        return start == i ? Primitives.primitiveMirror(u1(d.plus(i))) : ArrayTypes.primArrayMirror(u1(d.plus(i)), i - start);   // 'V' too
    }

    /** Parameter count of the descriptor bytes at {@code d} (arrays fold into their element). */
    private static int countDescParams(Address d, int len)
    {
        int i = 1;
        int n = 0;
        while (i < len && u1(d.plus(i)) != 0x29)
        {
            while (i < len && u1(d.plus(i)) == 0x5B)
            {
                i += 1;
            }
            if (u1(d.plus(i)) == 0x4C)
            {
                while (i < len && u1(d.plus(i)) != 0x3B)
                {
                    i += 1;
                }
            }
            i += 1;
            n += 1;
        }
        return n;
    }

    /** A zero-length {@code Class[]} -- what a class declaring no member classes answers. */
    static long emptyClassArray()
    {
        Address arr = Heap.allocArray(0, 8);
        arr.plus(ObjectModel.TIB_OFFSET).store(ArrayTypes.refArrayTib(ClassMirrors.classMirrorTypeAddr()));
        return arr.toLong();
    }
}
