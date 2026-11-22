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

import compiler.Baseline;
import board.Uart;
import classfile.ClassReader;
import magic.Address;
import magic.DataReference;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import magic.ObjectReferenceArray;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * invokedynamic and loadable dynamic constants: the BootstrapMethods attribute, recognising the bootstraps the JIT
 * lowers itself (StringConcatFactory, LambdaMetafactory, ObjectMethods, SwitchBootstraps.typeSwitch), and the
 * trampoline + site table for every other one. Split out of {@link Loader}.
 *
 * <p>A site is (caller Type, constant-pool index); the guest linker in MethodHandleNatives reads the bootstrap
 * specifier out of the caller's own class file and links it with stock CallSite.makeSite. {@code dsVal} caches what
 * it answered -- a CallSite, a constant, or a primitive constant's raw bits -- which is safe to hold as an address
 * because the guest's LINKED map keeps it reachable and the collector does not move objects.
 */
final class Indy
{
    private Indy()
    {
    }

    static long indyTrampAddr;
    static long indyTrampLo;
    static long indyTrampHi;
    private static final int MAXDSITE = 4096;
    private static AddressArray dsType;
    private static int[] dsCp;
    private static AddressArray dsDesc;                       // absolute Utf8 descriptor, or 0 for a MethodType/MethodHandle
    private static long[] dsVal;
    /** {@link #dsVal}'s OBJECT values, as references a precise collector follows; dsVal also holds a primitive
     *  constant's raw bits, so it stays a long[] (frames/GC arc S6b). */
    private static ObjectReferenceArray dsObj;
    private static int[] dsDone;
    private static int dsCount;
    /** Dynamic site {@code idx}'s descriptor ({u2 len}{bytes}), or zero: the GC walk types an indy
     *  trampoline's saved arguments by it ({@link FrameMaps}). */
    static Address siteDesc(int idx)
    {
        if (dsDesc == null || idx < 0 || idx >= dsCount)
        {
            return Address.zero();
        }
        return dsDesc.get(idx);
    }

    private static final int DYN_INDY = 0;
    private static final int DYN_CONST = 1;
    private static final int DYN_BITS = 2;
    private static final int[] dynLinkerIdx = { -1, -1, -1 };

    /** The invokedynamic trampoline: {@link MethodHandles#mhTramp}'s shape, resolved by {@link #indyResolve}. */
    static Address indyTramp()
    {
        if (indyTrampAddr == 0L)
        {
            long buf = MethodHandles.buildSaveAreaTramp(Address.fromLong(VM.indyResolveAddr), 5).toLong();
            indyTrampLo = buf + 4L;
            indyTrampHi = MethodHandles.saveTrampHi;
            indyTrampAddr = buf;
            StubFrames.registerTrampFrames();
        }
        return Address.fromLong(indyTrampAddr);
    }

    /** The site index of the invokedynamic or loadable constant at {@code cpIndex} of the class being compiled. */
    static int dynSiteIndex(int cpIndex)
    {
        if (dsType == null)
        {
            dsType = AddressArray.create(MAXDSITE);
            dsCp = new int[MAXDSITE];
            dsDesc = AddressArray.create(MAXDSITE);
            dsVal = new long[MAXDSITE];
            dsObj = ObjectReferenceArray.create(MAXDSITE);
            dsDone = new int[MAXDSITE];
        }
        int reg = ClassRegistry.classRegByNameAt(Loader.gView.base.toAddress(), Loader.gView.thisNameOff);
        DataReference type = reg >= 0 ? ClassRegistry.clTab[reg].type : DataReference.zero();
        int i = 0;
        while (i < dsCount)                             // dedup: the compiler visits each site in both passes
        {
            if (dsType.get(i).EQ(type.toAddress()) && dsCp[i] == cpIndex)
            {
                return i;
            }
            i += 1;
        }
        if (dsCount >= MAXDSITE)
        {
            Loader.capHalt(Magic.bytes("MAXDSITE"), dsCount);
        }
        int tag = u1(Loader.gView.base.plus(Loader.gView.cp[cpIndex] - 1L));         // Loader.gView.cp points just PAST an entry's tag byte
        dsType.set(dsCount, type.toAddress());
        dsCp[dsCount] = cpIndex;
        dsDesc.set(dsCount, (tag == 17 || tag == 18) ? Loader.gView.base.plus(Loader.mrefDescOff(cpIndex)) : Address.zero());
        dsVal[dsCount] = 0L;
        dsDone[dsCount] = 0;
        dsCount += 1;
        return dsCount - 1;
    }

    /**
     * The indy trampoline's resolver: link the site once (the guest's linkIndy), then run its CallSite's CURRENT
     * target -- read each call, so a MutableCallSite retargets -- as invokeBasic: the arguments are shifted one
     * register right and the target handle goes in x0, which is a LambdaForm method's layout.
     */
    static long indyResolve(long save, int idx)
    {
        if (save == 0L || dsType == null || idx < 0 || idx >= dsCount)
        {
            return 0L;                                  // boot force-compile passes 0
        }
        if (dsDone[idx] == 0)
        {
            long cs = dynLink(idx, DYN_INDY);           // may throw BootstrapMethodError: unwinds to the site
            dsVal[idx] = cs;
            dsObj.set(idx, ObjectReference.fromLong(cs));
            Magic.dsb();
            dsDone[idx] = 1;
        }
        if (MethodHandles.mhOffCsTarget < 0L)
        {
            MethodHandles.mhOffCsTarget = MethodHandles.declaredFieldOffset(Magic.bytes("java/lang/invoke/CallSite"), Magic.bytes("target"));
        }
        long target = Address.fromLong(dsVal[idx]).plus(MethodHandles.mhOffCsTarget).loadLong();
        int n = MethodHandles.descParamCountAt(dsDesc.get(idx));
        if (n > StubFrames.STUB_SAVE_HI - 1)
        {
            Uart.write(Magic.bytes("\n  INDY LINK: unsupported arity for "));
            Loader.printNameAt(dsDesc.get(idx), 0);
            Uart.putc(0x0A);
            return 0L;
        }
        int r = n;
        while (r > 0)
        {
            Address.fromLong(save).plus(r * 8L).store(Address.fromLong(save).plus((r - 1) * 8L).loadLong());
            r -= 1;
        }
        Address.fromLong(save).store(target);
        return MethodHandles.lambdaFormTarget(ObjectReference.fromLong(target));
    }

    /** The LDC_CONST helper: a loadable dynamic constant's value -- an object, or a primitive's raw bits. */
    static long ldcConst(int idx)
    {
        if (dsType == null || idx < 0 || idx >= dsCount)
        {
            return 0L;                                  // boot force-compile passes 0
        }
        if (dsDone[idx] != 0)
        {
            return dsVal[idx];
        }
        Address d = dsDesc.get(idx);
        boolean prim = !d.isZero() && u1(d.plus(2L)) != 0x4C && u1(d.plus(2L)) != 0x5B;   // not 'L' and not '['
        long v = dynLink(idx, prim ? DYN_BITS : DYN_CONST);
        dsVal[idx] = v;
        if (!prim)
        {
            dsObj.set(idx, ObjectReference.fromLong(v));
        }
        Magic.dsb();
        dsDone[idx] = 1;
        return v;
    }

    /** Call the guest linker of {@code kind} (MethodHandleNatives.linkIndy / linkConstant / constantBits). */
    private static long dynLink(int idx, int kind)
    {
        int li = dynLinkerIdx[kind];
        if (li < 0)
        {
            int reg = ClassRegistry.classRegByNameBytes(Magic.bytes("java/lang/invoke/MethodHandleNatives"));
            if (reg < 0)
            {
                MethodHandles.ensureMhnLoaded();
                reg = ClassRegistry.classRegByNameBytes(Magic.bytes("java/lang/invoke/MethodHandleNatives"));
            }
            if (reg >= 0)
            {
                byte[] name = kind == DYN_INDY ? Magic.bytes("linkIndy")
                        : kind == DYN_CONST ? Magic.bytes("linkConstant") : Magic.bytes("constantBits");
                byte[] desc = kind == DYN_INDY ? Magic.bytes("(Ljava/lang/Class;I)Ljava/lang/invoke/CallSite;")
                        : kind == DYN_CONST ? Magic.bytes("(Ljava/lang/Class;I)Ljava/lang/Object;")
                        : Magic.bytes("(Ljava/lang/Class;I)J");
                li = Reflection.methodResolveExact(ClassRegistry.clTab[reg].type, ObjectReference.fromLong(Loader.guestBytes(name)),
                        ObjectReference.fromLong(Loader.guestBytes(desc)));
                dynLinkerIdx[kind] = li;
            }
        }
        RVMMethod m = li < 0 ? null : Reflection.reflEntry(li);
        if (m == null || m.buf.toLong() == 0L)
        {
            Uart.write(Magic.bytes("\n  INDY LINK: no MethodHandleNatives linker\n"));
            return 0L;
        }
        return Magic.call2(m.buf.toLong(), ClassMirrors.classMirror(dsType.get(idx)), (long) dsCp[idx]);
    }

    /** Locate the class-level {@code BootstrapMethods} attribute (after the methods table); set gBsmOff (0 if none). */
    static void findBootstrapMethods()
    {
        Loader.gBsmOff = Address.zero();
        Address p = Loader.gView.methodsStart;                         // methods_count
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)                              // skip every method + its attributes
        {
            int macount = u2(p.plus(6));                    // access(2) name(2) desc(2) attrs_count(2)
            p = Loader.skipAttributes(p.plus(8), macount);
            m += 1;
        }
        int cacount = u2(p);                            // class attributes_count
        p = p.plus(2);
        byte[] want = Magic.bytes("BootstrapMethods");
        int a = 0;
        while (a < cacount)
        {
            int nameIdx = u2(p);
            int alen = u4(p.plus(2));
            if (Loader.utf8IsStr(Loader.gView.cp[nameIdx], want))
            {
                Loader.gBsmOff = p.plus(6);                        // attribute body: num_bootstrap_methods
                return;
            }
            p = p.plus(6 + alen);
            a += 1;
        }
    }

    /** Address of {@code bootstrap_methods[k]} (its {@code bootstrap_method_ref}) within the attribute. */
    static Address bsmEntryOff(int k)
    {
        Address p = Loader.gBsmOff.plus(2);                           // skip num_bootstrap_methods
        int j = 0;
        while (j < k)
        {
            int nargs = u2(p.plus(2));
            p = p.plus(4 + nargs * 2);                         // bsm_ref(2) num_args(2) args(num_args*2)
            j += 1;
        }
        return p;
    }

    /** True if the invokedynamic at cp {@code idx} bootstraps via {@code StringConcatFactory.makeConcatWithConstants}. */
    static boolean isStringConcat(int idx)
    {
        if (Loader.gBsmOff.isZero())
        {
            return false;
        }
        int bmIdx = u2(Loader.gView.base.plus(Loader.gView.cp[idx]));               // invokedynamic.bootstrap_method_attr_index
        Address e = bsmEntryOff(bmIdx);
        int mhIdx = u2(e);                              // bootstrap_method_ref -> CONSTANT_MethodHandle
        int mrefIdx = u2(Loader.gView.base.plus(Loader.gView.cp[mhIdx] + 1));       // MethodHandle{ kind(u1), reference_index(u2) }
        return Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/StringConcatFactory"))
            && Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("makeConcatWithConstants"));
    }

    /** Utf8 body offset of the concat recipe (bootstrap_arguments[0], a String) for indy {@code idx}. */
    static int concatRecipeOff(int idx)
    {
        int bmIdx = u2(Loader.gView.base.plus(Loader.gView.cp[idx]));
        Address e = bsmEntryOff(bmIdx);
        int arg0Idx = u2(e.plus(4));                        // bootstrap_arguments[0] = String cp index (the recipe)
        return ClassReader.stringUtf8Off(Loader.gView.bytes, Loader.gView.cp, arg0Idx);
    }

    /**
     * Which record method an {@code ObjectMethods} indy site implements: 1 hashCode, 2 equals, 3 toString,
     * 0 anything else. The name is the indy's own NameAndType -- one bootstrap serves all three, so the
     * site cannot be told apart by its bootstrap.
     */
    static int recordIndyKind(int idx)
    {
        int nat = u2(Loader.gView.base.plus(Loader.gView.cp[idx] + 2));             // invokedynamic.name_and_type_index
        int off = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[nat]))];            // NameAndType.name_index -> Utf8
        if (Loader.utf8IsStr(off, Magic.bytes("hashCode")))
        {
            return 1;
        }
        if (Loader.utf8IsStr(off, Magic.bytes("equals")))
        {
            return 2;
        }
        if (Loader.utf8IsStr(off, Magic.bytes("toString")))
        {
            return 3;
        }
        return 0;
    }

    static int indyBsmIndex(int idx)
    {
        return u2(Loader.gView.base.plus(Loader.gView.cp[idx]));                    // invokedynamic.bootstrap_method_attr_index
    }

    /** {@code bootstrap_arguments[k]} of the indy at {@code idx}, as a constant-pool index. */
    static int bsmArg(int idx, int k)
    {
        return u2(bsmEntryOff(indyBsmIndex(idx)).plus(4 + k * 2));
    }

    /** {@code num_bootstrap_arguments} of the indy at {@code idx}. */
    static int bsmArgCount(int idx)
    {
        return u2(bsmEntryOff(indyBsmIndex(idx)).plus(2));
    }

    /**
     * The {@code int} value of a {@code CONSTANT_Integer} at cp index {@code c}.
     *
     * <p>NO {@code +1} FOR THE TAG: {@code gView.cp[]} holds the offset of an entry's BODY, the tag already
     * consumed -- which is why {@code lambdaSamDescOff} reads a MethodType's descriptor_index at
     * {@code gView.cp[mtIdx]} and a MethodHandle's reference_index at {@code gView.cp[mhIdx] + 1} (past the
     * 1-byte reference_kind, not past a tag). Adding one here read the flags word one byte high, so every
     * {@code altMetafactory} site was refused as if it carried unknown bits.
     */
    static int cpIntAt(int c)
    {
        return u4(Loader.gView.base.plus(Loader.gView.cp[c]));
    }

    /**
     * True if the invokedynamic at {@code idx} bootstraps via {@code LambdaMetafactory} in a form this JIT
     * lowers: {@code metafactory}, or {@code altMetafactory} with an honourable flags tail (see
     * {@link LambdaFactory#altFormSupported}).
     *
     * <p>{@code altMetafactory} is what javac emits for a lambda cast to an INTERSECTION type, and matching
     * the name {@code metafactory} exactly is what kept stock {@code java.util.Comparator} from running at
     * all: every one of its combinators casts its lambda to {@code (Comparator<T> & Serializable)}, so the
     * class has exactly ONE bootstrap method and it is the alt one. The site then fell through to
     * {@code Baseline}'s unsupported-bootstrap arm.
     */
    static boolean isLambdaIndy(int idx)
    {
        if (Loader.gBsmOff.isZero())
        {
            return false;
        }
        Address e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e);                              // bootstrap_method_ref -> MethodHandle
        int mrefIdx = u2(Loader.gView.base.plus(Loader.gView.cp[mhIdx] + 1));       // MethodHandle.reference_index -> Methodref
        if (!Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/LambdaMetafactory")))
        {
            return false;
        }
        if (Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("metafactory")))
        {
            return true;
        }
        return Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("altMetafactory")) && LambdaFactory.altFormSupported(idx);
    }

    /**
     * True if the invokedynamic at {@code idx} bootstraps via {@code java/lang/runtime/ObjectMethods.bootstrap}
     * — a record's synthesised {@code equals}/{@code hashCode}/{@code toString}. We don't synthesise record
     * semantics yet; RTA pulls these in for any instantiated record (e.g. {@code Collectors$CollectorImpl}),
     * but they are essentially never actually invoked in the paths we run (a Collector is never compared,
     * hashed or printed during {@code collect()}). The JIT lowers such an indy to a runtime trap so the record
     * still compiles; see {@code Baseline.lowerRecordTrap}.
     */
    static boolean isRecordIndy(int idx)
    {
        if (Loader.gBsmOff.isZero())
        {
            return false;
        }
        Address e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e);
        int mrefIdx = u2(Loader.gView.base.plus(Loader.gView.cp[mhIdx] + 1));
        return Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/runtime/ObjectMethods"));
    }

    /**
     * True if the invokedynamic at {@code idx} bootstraps via {@code java/lang/runtime/SwitchBootstraps.typeSwitch}
     * -- a pattern-matching {@code switch}. Its static arguments are the case labels; the JIT lowers it inline
     * ({@code Baseline.lowerTypeSwitch}), so no MethodHandle runtime is involved.
     */
    static boolean isTypeSwitchIndy(int idx)
    {
        if (Loader.gBsmOff.isZero())
        {
            return false;
        }
        Address e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e);
        int mrefIdx = u2(Loader.gView.base.plus(Loader.gView.cp[mhIdx] + 1));
        return Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/runtime/SwitchBootstraps"))
                && Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("typeSwitch"));
    }

    /** Number of case labels of the typeSwitch indy at {@code idx}. */
    static int typeSwitchLabelCount(int idx)
    {
        return bsmArgCount(idx);
    }

    /** Constant-pool index of label {@code k} if it is a CONSTANT_Class; -2 for a primitive-class dynamic
     *  constant ({@link #primitiveLabelKind}); else -1. */
    static int typeSwitchLabelClass(int idx, int k)
    {
        int c = bsmArg(idx, k);
        if (Loader.gView.cpTag[c] == 7)
        {
            return c;
        }
        return primitiveLabelKind(c) != 0 ? -2 : -1;
    }

    /** Kind of the typeSwitch selector: the first parameter of the indy's descriptor ({@code (II)I} -> 'I'). */
    static int typeSwitchSelectorKind(int idx)
    {
        return ClassReader.descParamKind(Loader.gView.bytes, Loader.mrefDescOff(idx), 0);
    }

    /** Primitive descriptor char of label {@code k} if it is a primitive-class constant, else 0. */
    static int typeSwitchPrimitiveLabel(int idx, int k)
    {
        return primitiveLabelKind(bsmArg(idx, k));
    }

    /**
     * The descriptor char of a {@code CONSTANT_Dynamic} that is {@code ConstantBootstraps.primitiveClass} -- javac's
     * spelling of {@code int.class} as a typeSwitch LABEL, for a record component pattern like
     * {@code Rect(int w, int h)} -- or 0 for any other constant. The dynamic constant's NAME is the descriptor
     * ("I"); its bootstrap must be primitiveClass, checked rather than assumed.
     */
    private static int primitiveLabelKind(int c)
    {
        if (Loader.gView.cpTag[c] != 17 || Loader.gBsmOff.isZero())
        {
            return 0;
        }
        Address body = Loader.gView.base.plus(Loader.gView.cp[c]);
        int bsm = u2(body);                             // bootstrap_method_attr_index
        int nat = u2(body.plus(2));                         // name_and_type_index
        Address e = bsmEntryOff(bsm);
        int mrefIdx = u2(Loader.gView.base.plus(Loader.gView.cp[u2(e)] + 1));
        if (!Loader.utf8IsStr(Loader.refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/ConstantBootstraps"))
                || !Loader.utf8IsStr(Loader.mrefNameOff(mrefIdx), Magic.bytes("primitiveClass")))
        {
            return 0;
        }
        int nameOff = Loader.gView.cp[u2(Loader.gView.base.plus(Loader.gView.cp[nat]))];        // NameAndType.name -> Utf8 offset
        if (u2(Loader.gView.base.plus(nameOff)) != 1)
        {
            return 0;
        }
        int ch = u1(Loader.gView.base.plus(nameOff + 2));
        return (ch == 'Z' || ch == 'B' || ch == 'C' || ch == 'S' || ch == 'I' || ch == 'J' || ch == 'F' || ch == 'D')
                ? ch : 0;
    }

    /**
     * Type of the WRAPPER for primitive-class label {@code k}: a selector matches {@code int.class} iff it is an
     * {@code Integer}. EXACT for every label javac emits without --enable-preview: a primitive label appears only
     * where the selector's static type IS that primitive (a record component), so the value arrives boxed as
     * exactly its own wrapper. Primitive patterns against an arbitrary Object -- where a Byte would also match
     * {@code int} by exact widening -- are a PREVIEW feature, and would need SwitchBootstraps' conversion table.
     */
    static long typeSwitchWrapperType(int idx, int k)
    {
        int ch = primitiveLabelKind(bsmArg(idx, k));
        byte[] name = ch == 'Z' ? Magic.bytes("java/lang/Boolean")
                : ch == 'B' ? Magic.bytes("java/lang/Byte")
                : ch == 'C' ? Magic.bytes("java/lang/Character")
                : ch == 'S' ? Magic.bytes("java/lang/Short")
                : ch == 'I' ? Magic.bytes("java/lang/Integer")
                : ch == 'J' ? Magic.bytes("java/lang/Long")
                : ch == 'F' ? Magic.bytes("java/lang/Float")
                : Magic.bytes("java/lang/Double");
        int ci = ClassRegistry.classIndexByName(name);
        return ci < 0 ? 0L : ClassRegistry.clTab[ci].type.toLong();
    }
}
