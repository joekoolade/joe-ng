/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package compiler;

import asm.A64;
import asm.A64Enc;
import asm.CodeBuffer;
import classfile.ClassFile;
import harness.T;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

/**
 * The unboxed types ({@code magic.Address}, {@code Word}, {@code Offset}, {@code Extent}) on the host:
 * <ul>
 * <li>every public method of the four classes classifies ({@link Unboxed#op} is never -1) -- a method with no
 *     lowering would otherwise compile as a call to a body that throws;</li>
 * <li>the name hashes in {@link Unboxed} are right (spot-checked by classifying names to their expected ops);</li>
 * <li>each pair in {@link UnboxedFixture} compiles to the same code with longs and with unboxed types, apart from
 *     LOCAL-REGISTER ALLOCATION: a {@code long} parameter takes two JVM local slots and an {@code Address} one, so
 *     the long version keeps its locals in more callee-saved registers and saves more of them. {@link #normal}
 *     removes exactly that -- the frame adjust and the x19..x28 spills/fills -- and numbers x19..x28 by first use;
 *     every other word must match;</li>
 * <li>the ops with no long twin compile to the words written here (normalized the same way).</li>
 * </ul>
 */
public final class UnboxedTest
{
    private UnboxedTest()
    {
    }

    public static void main(String[] args) throws Exception
    {
        Path out = Path.of(args.length > 0 ? args[0] : "out");
        classifiesAll();
        spotChecks();
        ClassFile fx = ClassFile.parse(out.resolve("compiler/UnboxedFixture.class"));
        same(fx, "loadLong", "(J)J", "loadAddr", "(Lmagic/Address;)J");
        same(fx, "storeLong", "(JJ)V", "storeAddr", "(Lmagic/Address;J)V");
        same(fx, "load32Long", "(J)I", "load32Addr", "(Lmagic/Address;)I");
        same(fx, "store32Long", "(JI)V", "store32Addr", "(Lmagic/Address;I)V");
        same(fx, "load8Long", "(J)I", "load8Addr", "(Lmagic/Address;)I");
        same(fx, "fieldLong", "(J)J", "fieldAddr", "(Lmagic/Address;)J");
        same(fx, "indexLong", "(JJ)J", "indexAddr", "(Lmagic/Address;Lmagic/Offset;)J");
        same(fx, "indexLong", "(JJ)J", "indexOffAddr", "(Lmagic/Address;Lmagic/Offset;)J");
        same(fx, "casLong", "(JJJ)Z", "casAddr", "(Lmagic/Address;Lmagic/Word;Lmagic/Word;)Z");
        same(fx, "maskLong", "(JJ)J", "maskWord", "(Lmagic/Word;Lmagic/Word;)Lmagic/Word;");
        same(fx, "diffLong", "(JJ)J", "diffAddr", "(Lmagic/Address;Lmagic/Address;)Lmagic/Offset;");
        same(fx, "objLong", "(J)Ljava/lang/Object;", "objAddr", "(Lmagic/Address;)Ljava/lang/Object;");
        same(fx, "nopLong", "(J)J", "nopAddr", "(J)J");
        // ObjectReference is moves and compares: the same words as the long / Address code it replaces.
        same(fx, "refFieldLong", "(Ljava/lang/Object;)J", "refFieldRef", "(Ljava/lang/Object;)J");
        same(fx, "loadLong", "(J)J", "loadRefAddr", "(Lmagic/Address;)J");
        same(fx, "storeLong", "(JJ)V", "storeRefAddr", "(Lmagic/Address;Lmagic/ObjectReference;)V");
        same(fx, "objAddr", "(Lmagic/Address;)Ljava/lang/Object;", "objRef", "(Lmagic/ObjectReference;)Ljava/lang/Object;");
        same(fx, "nopLong", "(J)J", "refLong", "(J)J");
        same(fx, "isZero", "(Lmagic/Address;)Z", "isNullRef", "(Lmagic/ObjectReference;)Z");
        same(fx, "eqAddr", "(Lmagic/Address;Lmagic/Address;)Z", "eqRef",
                "(Lmagic/ObjectReference;Lmagic/ObjectReference;)Z");
        same(fx, "neAddr", "(Lmagic/Address;Lmagic/Address;)Z", "neRef",
                "(Lmagic/ObjectReference;Lmagic/ObjectReference;)Z");
        same(fx, "zero", "()Lmagic/Address;", "nullRef", "()Lmagic/ObjectReference;");
        // AddressArray IS a long[]: each op compiles to exactly its array bytecode's words.
        // (create allocates through a helper, which this harness cannot compile: AddressFieldProbe covers it on metal.)
        same(fx, "getLong", "([JI)J", "getAddr", "(Lmagic/AddressArray;I)J");
        same(fx, "setLong", "([JIJ)V", "setAddr", "(Lmagic/AddressArray;ILmagic/Address;)V");
        same(fx, "lenLong", "([J)I", "lenAddr", "(Lmagic/AddressArray;)I");
        same(fx, "sumLong", "([J)J", "sumAddr", "(Lmagic/AddressArray;)J");
        // ObjectReferenceArray IS a long[] as well.
        same(fx, "getLong", "([JI)J", "getRefArr", "(Lmagic/ObjectReferenceArray;I)J");
        same(fx, "setLong", "([JIJ)V", "setRefArr", "(Lmagic/ObjectReferenceArray;ILmagic/ObjectReference;)V");
        same(fx, "lenLong", "([J)I", "lenRefArr", "(Lmagic/ObjectReferenceArray;)I");
        same(fx, "zeroAtAddr", "(Lmagic/AddressArray;I)Z", "nullAtRefArr", "(Lmagic/ObjectReferenceArray;I)Z");

        // x9 is operand slot 0, x10 slot 1, x11 slot 2; locals live in frame slots (after normal(): numbered 0, 8, 16..
        // in first-use order). Every method below stores its arguments to their local slots first (x0 -> [sp], x1 ->
        // [sp, #8], ...), loads them back onto the operand stack, and returns in x0.
        int[] in1 = { A64Enc.strx(0, 31, 0), A64Enc.ldrx(9, 31, 0) };
        int[] in2 = { A64Enc.strx(0, 31, 0), A64Enc.strx(1, 31, 8), A64Enc.ldrx(9, 31, 0), A64Enc.ldrx(10, 31, 8) };
        int[] ret = { A64Enc.movReg(0, 9), A64.ret() };
        T.eqWords("isZero", cat(in1, new int[] { A64Enc.cmpImm(9, 0), A64Enc.cset(9, A64Enc.EQ) }, ret),
                normal(compile(fx, "isZero", "(Lmagic/Address;)Z")));
        T.eqWords("LT (unsigned)", cat(in2, new int[] { A64Enc.cmpReg(9, 10), A64Enc.cset(9, A64Enc.LO) }, ret),
                normal(compile(fx, "lessThan", "(Lmagic/Address;Lmagic/Address;)Z")));
        T.eqWords("sLT (signed)", cat(in2, new int[] { A64Enc.cmpReg(9, 10), A64Enc.cset(9, A64Enc.LT) }, ret),
                normal(compile(fx, "signedLess", "(Lmagic/Offset;Lmagic/Offset;)Z")));
        T.eqWords("zero", new int[] { A64Enc.movz(9, 0, 0), A64Enc.movReg(0, 9), A64.ret() },
                normal(compile(fx, "zero", "()Lmagic/Address;")));
        T.eqWords("loadShort(Offset)", cat(in2, new int[] { A64Enc.addReg(9, 9, 10), A64Enc.ldrsh(9, 9, 0) }, ret),
                normal(compile(fx, "loadShortOff", "(Lmagic/Address;Lmagic/Offset;)I")));
        T.eqWords("store(byte, Offset)", new int[] { A64Enc.strx(0, 31, 0), A64Enc.strx(1, 31, 8), A64Enc.strx(2, 31, 16),
            A64Enc.ldrx(9, 31, 0), A64Enc.ldrx(10, 31, 8), A64Enc.ldrx(11, 31, 16), A64Enc.addReg(16, 9, 11),
            A64Enc.strb(10, 16, 0), A64.ret() },
                normal(compile(fx, "storeByteOff", "(Lmagic/Address;BLmagic/Offset;)V")));
        fused(fx);
        T.summary("unboxed");
    }

    /** Every public method of the four types maps to a lowering. */
    private static void classifiesAll()
    {
        String[] types = { "Address", "Word", "Offset", "Extent", "AddressArray", "ObjectReference",
            "ObjectReferenceArray" };
        int n = 0;
        int missing = 0;
        for (String t : types)
        {
            Class<?> c;
            try
            {
                c = Class.forName("magic." + t);
            }
            catch (ClassNotFoundException e)
            {
                throw new AssertionError(e);
            }
            int kind = Unboxed.ownerKind(utf8("magic/" + t));
            T.check("owner kind of magic/" + t, kind != Unboxed.NONE);
            for (Method m : c.getDeclaredMethods())
            {
                if (!Modifier.isPublic(m.getModifiers()))
                {
                    continue;
                }
                n = n + 1;
                int op = Unboxed.op(kind, utf8(m.getName()), utf8(descriptor(m)));
                if (op < 0)
                {
                    missing = missing + 1;
                    System.out.println("  NO LOWERING: magic." + t + "." + m.getName() + descriptor(m));
                }
            }
        }
        System.out.println("  " + n + " public methods classified");
        T.eq("public methods without a lowering", 0, missing);
        T.check("not an unboxed owner", Unboxed.ownerKind(utf8("magic/Magic")) == Unboxed.NONE);
    }

    /** Names to their expected ops: a wrong hash constant shows here. */
    private static void spotChecks()
    {
        int a = Unboxed.ADDRESS;
        T.eq("plus", Intrinsics.UB_ADD, Unboxed.op(a, utf8("plus"), utf8("(I)Lmagic/Address;")));
        T.eq("minus", Intrinsics.UB_SUB, Unboxed.op(a, utf8("minus"), utf8("(J)Lmagic/Address;")));
        T.eq("diff", Intrinsics.UB_SUB, Unboxed.op(a, utf8("diff"), utf8("(Lmagic/Address;)Lmagic/Offset;")));
        T.eq("fromIntSignExtend", Intrinsics.UB_SXTW,
                Unboxed.op(a, utf8("fromIntSignExtend"), utf8("(I)Lmagic/Address;")));
        T.eq("loadLong()", Intrinsics.LOAD64, Unboxed.op(a, utf8("loadLong"), utf8("()J")));
        T.eq("loadLong(Offset)", Intrinsics.UB_LD64_OFF,
                Unboxed.op(a, utf8("loadLong"), utf8("(Lmagic/Offset;)J")));
        T.eq("store(int)", Intrinsics.STORE32, Unboxed.op(a, utf8("store"), utf8("(I)V")));
        T.eq("store(Word,Offset)", Intrinsics.UB_ST64_OFF,
                Unboxed.op(a, utf8("store"), utf8("(Lmagic/Word;Lmagic/Offset;)V")));
        T.eq("store(char)", Intrinsics.UB_ST16, Unboxed.op(a, utf8("store"), utf8("(C)V")));
        T.eq("LT", Intrinsics.UB_LO, Unboxed.op(a, utf8("LT"), utf8("(Lmagic/Address;)Z")));
        T.eq("sGE", Intrinsics.UB_SGE, Unboxed.op(Unboxed.OFFSET, utf8("sGE"), utf8("(Lmagic/Offset;)Z")));
        T.eq("Offset.max", Intrinsics.UB_SMAX, Unboxed.op(Unboxed.OFFSET, utf8("max"), utf8("()Lmagic/Offset;")));
        T.eq("Word.max", Intrinsics.UB_MAX, Unboxed.op(Unboxed.WORD, utf8("max"), utf8("()Lmagic/Word;")));
        T.eq("rsha", Intrinsics.UB_ASR, Unboxed.op(Unboxed.WORD, utf8("rsha"), utf8("(I)Lmagic/Word;")));
        T.eq("fromIntZeroExtend", Intrinsics.UB_ZEXT32,
                Unboxed.op(a, utf8("fromIntZeroExtend"), utf8("(I)Lmagic/Address;")));
        T.eq("an unknown name", -1, Unboxed.op(a, utf8("prefetch"), utf8("()V")));
        int aa = Unboxed.ADDRESS_ARRAY;
        T.eq("AddressArray owner", aa, Unboxed.ownerKind(utf8("magic/AddressArray")));
        T.eq("AddressArray.create", Intrinsics.AA_NEW, Unboxed.op(aa, utf8("create"), utf8("(I)Lmagic/AddressArray;")));
        T.eq("AddressArray.get", Intrinsics.AA_GET, Unboxed.op(aa, utf8("get"), utf8("(I)Lmagic/Address;")));
        T.eq("AddressArray.set", Intrinsics.AA_SET, Unboxed.op(aa, utf8("set"), utf8("(ILmagic/Address;)V")));
        T.eq("AddressArray.length", Intrinsics.AA_LENGTH, Unboxed.op(aa, utf8("length"), utf8("()I")));
        T.eq("AddressArray: an Address name is not an array op", -1,
                Unboxed.op(aa, utf8("plus"), utf8("(I)Lmagic/Address;")));
        int ra = Unboxed.OBJECT_REFERENCE_ARRAY;
        T.eq("ObjectReferenceArray owner", ra, Unboxed.ownerKind(utf8("magic/ObjectReferenceArray")));
        T.eq("ObjectReferenceArray.create", Intrinsics.AA_NEW,
                Unboxed.op(ra, utf8("create"), utf8("(I)Lmagic/ObjectReferenceArray;")));
        T.eq("ObjectReferenceArray.get", Intrinsics.AA_GET, Unboxed.op(ra, utf8("get"), utf8("(I)Lmagic/ObjectReference;")));
        T.eq("ObjectReferenceArray.set", Intrinsics.AA_SET,
                Unboxed.op(ra, utf8("set"), utf8("(ILmagic/ObjectReference;)V")));
        T.eq("ObjectReferenceArray.length", Intrinsics.AA_LENGTH, Unboxed.op(ra, utf8("length"), utf8("()I")));
        T.eq("ObjectReferenceArray: no isNull", -1, Unboxed.op(ra, utf8("isNull"), utf8("()Z")));
        int r = Unboxed.OBJECT_REFERENCE;
        T.eq("ObjectReference owner", r, Unboxed.ownerKind(utf8("magic/ObjectReference")));
        T.eq("ObjectReference.isNull", Intrinsics.UB_IS_ZERO, Unboxed.op(r, utf8("isNull"), utf8("()Z")));
        T.eq("ObjectReference.nullReference", Intrinsics.UB_ZERO,
                Unboxed.op(r, utf8("nullReference"), utf8("()Lmagic/ObjectReference;")));
        T.eq("ObjectReference.fromObject", Intrinsics.ADDR_OF,
                Unboxed.op(r, utf8("fromObject"), utf8("(Ljava/lang/Object;)Lmagic/ObjectReference;")));
        T.eq("ObjectReference.toObject", Intrinsics.FROM_ADDR,
                Unboxed.op(r, utf8("toObject"), utf8("()Ljava/lang/Object;")));
        T.eq("ObjectReference.toAddress", Intrinsics.UB_MOVE,
                Unboxed.op(r, utf8("toAddress"), utf8("()Lmagic/Address;")));
        T.eq("Address.toObjectReference", Intrinsics.UB_MOVE,
                Unboxed.op(a, utf8("toObjectReference"), utf8("()Lmagic/ObjectReference;")));
        T.eq("Address.loadObjectReference()", Intrinsics.LOAD64,
                Unboxed.op(a, utf8("loadObjectReference"), utf8("()Lmagic/ObjectReference;")));
        T.eq("Address.loadObjectReference(Offset)", Intrinsics.UB_LD64_OFF,
                Unboxed.op(a, utf8("loadObjectReference"), utf8("(Lmagic/Offset;)Lmagic/ObjectReference;")));
        T.eq("Address.store(ObjectReference)", Intrinsics.STORE64,
                Unboxed.op(a, utf8("store"), utf8("(Lmagic/ObjectReference;)V")));
        // No arithmetic, ordering, loads -- and no inherited equals/hashCode: those must fail the compile.
        T.eq("ObjectReference: no plus", -1, Unboxed.op(r, utf8("plus"), utf8("(I)Lmagic/ObjectReference;")));
        T.eq("ObjectReference: no LT", -1, Unboxed.op(r, utf8("LT"), utf8("(Lmagic/ObjectReference;)Z")));
        T.eq("ObjectReference: no loadLong", -1, Unboxed.op(r, utf8("loadLong"), utf8("()J")));
        T.eq("ObjectReference: no equals", -1, Unboxed.op(r, utf8("equals"), utf8("(Ljava/lang/Object;)Z")));
        T.eq("ObjectReference: no hashCode", -1, Unboxed.op(r, utf8("hashCode"), utf8("()I")));
    }

    /**
     * A comparison feeding an if compiles to CMP + B.cond (no CSET), and an isZero test to a CBZ/CBNZ: what the long
     * code compiles to. Printed beside the long version, whose lcmp lowering differs, for the record.
     */
    private static void fused(ClassFile fx)
    {
        int[] lt = normal(compile(fx, "branchAddr", "(Lmagic/Address;Lmagic/Address;)I"));
        int[] z = normal(compile(fx, "branchZeroAddr", "(Lmagic/Address;)I"));
        T.check("LT + if: no CSET", count(lt, 0x9A9F07E0, 0xFFFF0FE0) == 0);
        T.check("LT + if: one CMP", count(lt, A64Enc.cmpReg(0, 0), 0xFFE0FC1F) == 1);
        T.check("LT + if: one B.cond", count(lt, 0x54000000, 0xFF000010) == 1);
        T.check("isZero + if: no CMP, no CSET", count(z, A64Enc.cmpImm(0, 0), 0xFFC003FF) == 0
                && count(z, 0x9A9F07E0, 0xFFFF0FE0) == 0);
        T.check("isZero + if: a CBZ/CBNZ", count(z, 0xB4000000, 0xFE000000) == 1);
        int sxtw = A64Enc.sxtw(0, 0);
        int[] ra = normal(compile(fx, "refEqAddr", "(Lmagic/Address;Lmagic/Address;)I"));
        int[] ro = normal(compile(fx, "refEqObj", "(Ljava/lang/Object;Ljava/lang/Object;)I"));
        int[] ic = normal(compile(fx, "intCmp", "(II)I"));
        T.check("Address == Address: no SXTW (all 64 bits)", count(ra, sxtw, 0xFFFFFC00) == 0
                && count(ra, A64Enc.cmpReg(0, 0), 0xFFE0FC1F) == 1);
        T.check("Object != Object: no SXTW", count(ro, sxtw, 0xFFFFFC00) == 0
                && count(ro, A64Enc.cmpReg(0, 0), 0xFFE0FC1F) == 1);
        T.check("int == int: both SXTWs kept", count(ic, sxtw, 0xFFFFFC00) == 2);
        T.check("fromIntSignExtend: one SXTW", count(normal(compile(fx, "sext", "(I)Lmagic/Address;")), sxtw,
                0xFFFFFC00) == 1);
        System.out.println("  branch, long:    " + hex(normal(compile(fx, "branchLong", "(JJ)I"))));
        System.out.println("  branch, Address: " + hex(lt));
        System.out.println("  isZero, long:    " + hex(normal(compile(fx, "branchZeroLong", "(J)I"))));
        System.out.println("  isZero, Address: " + hex(z));
    }

    private static int count(int[] w, int pattern, int mask)
    {
        int n = 0;
        for (int x : w)
        {
            if ((x & mask) == (pattern & mask))
            {
                n = n + 1;
            }
        }
        return n;
    }

    private static String hex(int[] w)
    {
        StringBuilder sb = new StringBuilder();
        for (int x : w)
        {
            sb.append(String.format("%08x ", x));
        }
        return sb.toString().trim();
    }

    private static void same(ClassFile fx, String l, String ld, String u, String ud)
    {
        int[] want = normal(compile(fx, l, ld));
        int[] got = normal(compile(fx, u, ud));
        T.eqWords(u + " == " + l, want, got);
    }

    /**
     * The words without what local allocation alone decides: {@code sub/add sp, sp, #n}; the frame offset of each
     * local's slot in {@code str/ldr xN, [sp, #k]} (renumbered 0, 8, 16.. by first use -- a {@code long} parameter
     * takes two local slots where an {@code Address} takes one, so the raw offsets differ); and, for the frameless
     * entry's register locals, the {@code str/ldr x19..x28, [sp, #k]} saves and the numbering of x19..x28 in register
     * moves. The fixtures touch locals only through those, so anything else that differs is a real difference.
     */
    private static int[] normal(int[] w)
    {
        int[] map = new int[32];
        int next = 19;
        int[] slotMap = new int[4096];
        int nextSlot = 1;                               // 0 = unmapped; slot k (1-based) -> offset (k-1)*8
        int[] out = new int[w.length];
        int n = 0;
        for (int x : w)
        {
            if ((x & 0xFF8003FF) == 0xD10003FF || (x & 0xFF8003FF) == 0x910003FF)
            {
                continue;                               // sub/add sp, sp, #imm
            }
            int rt = x & 31;
            if (((x & 0xFFC003E0) == 0xF90003E0 || (x & 0xFFC003E0) == 0xF94003E0) && rt >= 19 && rt <= 28)
            {
                continue;                               // str/ldr x19..x28, [sp, #imm]
            }
            if (((x & 0xFFC003E0) == 0xF90003E0 || (x & 0xFFC003E0) == 0xF94003E0) && rt != 30)
            {
                int imm = (x >> 10) & 0xFFF;            // str/ldr xN, [sp, #imm*8]: a local's (or spill) slot
                if (slotMap[imm] == 0)
                {
                    slotMap[imm] = nextSlot;
                    nextSlot = nextSlot + 1;
                }
                x = (x & ~(0xFFF << 10)) | ((slotMap[imm] - 1) << 10);
            }
            if ((x & 0xFFE0FFE0) == 0xAA0003E0)         // mov xd, xm (orr xd, xzr, xm)
            {
                int rd = x & 31;
                int rm = (x >> 16) & 31;
                if (rd >= 19 && rd <= 28)
                {
                    if (map[rd] == 0)
                    {
                        map[rd] = next;
                        next = next + 1;
                    }
                    rd = map[rd];
                }
                if (rm >= 19 && rm <= 28)
                {
                    if (map[rm] == 0)
                    {
                        map[rm] = next;
                        next = next + 1;
                    }
                    rm = map[rm];
                }
                x = (x & ~0x001F001F) | (rm << 16) | rd;
            }
            out[n] = x;
            n = n + 1;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private static int[] cat(int[]... parts)
    {
        int n = 0;
        for (int[] p : parts)
        {
            n = n + p.length;
        }
        int[] r = new int[n];
        int k = 0;
        for (int[] p : parts)
        {
            System.arraycopy(p, 0, r, k, p.length);
            k = k + p.length;
        }
        return r;
    }

    private static int[] compile(ClassFile cf, String method, String desc)
    {
        CodeBuffer cb = new CodeBuffer();
        new BaselineCompiler(cf).compile(cf.method(method, desc), cb);
        return cb.toWords();
    }

    private static String descriptor(Method m)
    {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes())
        {
            sb.append(p.descriptorString());
        }
        return sb.append(')').append(m.getReturnType().descriptorString()).toString();
    }

    private static Unboxed.Utf8 utf8(String s)
    {
        return new Unboxed.Utf8()
        {
            @Override
            public int length()
            {
                return s.length();
            }

            @Override
            public int byteAt(int i)
            {
                return s.charAt(i);
            }
        };
    }
}
