package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The baseline compiler: Java bytecode → A64 machine code, compile-only
 * (PLAN.md §2, §5). It runs on the seed JVM inside the writer today and is
 * compiled into the image as the runtime JIT later — same code, two contexts.
 *
 * <p>This is a stack-based one-pass baseline: it walks the bytecode, records a
 * bytecode-offset → word-index map so branches can be resolved, and lowers each
 * opcode directly to instructions. Calls to {@code magic.Magic} intrinsics are
 * recognized and lowered to single privileged instructions rather than real
 * calls — that is what lets boot code be Java (PLAN.md §5.1).
 *
 * <p>Coverage grows milestone by milestone. Anything not yet handled throws
 * {@link UnsupportedOperationException} with the opcode and offset, so gaps are
 * loud, never silent.
 */
public final class BaselineCompiler {

    private final ClassFile cf;

    public BaselineCompiler(ClassFile cf) { this.cf = cf; }

    private record Fixup(int wordIndex, int targetBc) {}

    /** Compile {@code method} into {@code cb}, appending at the current position. */
    public void compile(ClassFile.Method method, CodeBuffer cb) {
        byte[] code = method.code;
        if (code == null) throw new IllegalArgumentException("method " + method.name + " has no Code");

        int[] bcToWord = new int[code.length];
        java.util.Arrays.fill(bcToWord, -1);
        List<Fixup> fixups = new ArrayList<>();

        // Boot-code operand stack: every value is a compile-time constant (int or
        // long). Arithmetic/runtime values arrive with locals+control flow later.
        Deque<Long> consts = new ArrayDeque<>();

        int pos = 0;
        while (pos < code.length) {
            bcToWord[pos] = cb.wordCount();
            int op = code[pos] & 0xFF;
            switch (op) {
                case 0x00 -> pos += 1;                                   // nop
                case 0xB1 -> { cb.emit(A64.ret()); pos += 1; }          // return

                // ---- constant pushes ----
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 ->        // iconst_m1 .. iconst_5
                    { consts.push((long) (op - 0x03)); pos += 1; }
                case 0x09 -> { consts.push(0L); pos += 1; }             // lconst_0
                case 0x0A -> { consts.push(1L); pos += 1; }             // lconst_1
                case 0x10 -> { consts.push((long) (byte) code[pos + 1]); pos += 2; } // bipush
                case 0x11 -> { consts.push((long) (short) u2(code, pos + 1)); pos += 3; } // sipush
                case 0x12 -> { consts.push((long) cf.intAt(code[pos + 1] & 0xFF)); pos += 2; } // ldc (int)
                case 0x13 -> { consts.push((long) cf.intAt(u2(code, pos + 1))); pos += 3; }    // ldc_w (int)
                case 0x14 -> { consts.push(cf.longAt(u2(code, pos + 1))); pos += 3; }          // ldc2_w (long)

                case 0xB8 -> { lowerInvokeStatic(u2(code, pos + 1), cb, consts); pos += 3; } // invokestatic
                case 0xA7 -> {                                           // goto (s2 offset)
                    int target = pos + s2(code, pos + 1);
                    int w = cb.emit(A64.b(0));                           // placeholder
                    fixups.add(new Fixup(w, target));
                    pos += 3;
                }
                default -> throw new UnsupportedOperationException(
                        String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
            }
        }

        for (Fixup f : fixups) {
            int targetWord = bcToWord[f.targetBc];
            if (targetWord < 0) throw new IllegalStateException("branch to non-instruction bc=" + f.targetBc);
            cb.set(f.wordIndex, A64.b((targetWord - f.wordIndex) * 4));
        }
    }

    private void lowerInvokeStatic(int cpIndex, CodeBuffer cb, Deque<Long> consts) {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        String key = ref.owner() + "." + ref.name() + ref.descriptor();
        switch (key) {
            // no-arg privileged ops
            case "magic/Magic.wfe()V"  -> cb.emit(A64.wfe());
            case "magic/Magic.isb()V"  -> cb.emit(A64.isb());
            case "magic/Magic.eret()V" -> cb.emit(A64.eret());

            // system-register writes: value is a compile-time constant -> x0, MSR
            case "magic/Magic.writeHCR_EL2(J)V"     -> msrConst(cb, A64.HCR_EL2, consts);
            case "magic/Magic.writeCPTR_EL2(J)V"    -> msrConst(cb, A64.CPTR_EL2, consts);
            case "magic/Magic.writeCNTHCTL_EL2(J)V" -> msrConst(cb, A64.CNTHCTL_EL2, consts);
            case "magic/Magic.writeCNTVOFF_EL2(J)V" -> msrConst(cb, A64.CNTVOFF_EL2, consts);
            case "magic/Magic.writeSCTLR_EL1(J)V"   -> msrConst(cb, A64.SCTLR_EL1, consts);
            case "magic/Magic.writeSPSR_EL2(J)V"    -> msrConst(cb, A64.SPSR_EL2, consts);
            case "magic/Magic.writeELR_EL2(J)V"     -> msrConst(cb, A64.ELR_EL2, consts);
            case "magic/Magic.writeCPACR_EL1(J)V"   -> msrConst(cb, A64.CPACR_EL1, consts);

            // stack pointer
            case "magic/Magic.writeSP(J)V" -> { load(cb, 0, popLong(consts)); cb.emit(A64.movToSp(0)); }

            // raw MMIO stores: (addr:long, value:int) -> STR/STRB Wval, [Xaddr]
            case "magic/Magic.store32(JI)V" -> store(cb, consts, false);
            case "magic/Magic.store8(JI)V"  -> store(cb, consts, true);

            default -> throw new UnsupportedOperationException("call not yet supported: " + key);
        }
    }

    /** MSR sysreg, x0 where x0 holds a popped constant. */
    private void msrConst(CodeBuffer cb, A64.Sys reg, Deque<Long> consts) {
        load(cb, 0, popLong(consts));
        cb.emit(A64.msr(reg, 0));
    }

    /** STR/STRB Wval,[Xaddr] with both operands popped constants (value on top). */
    private void store(CodeBuffer cb, Deque<Long> consts, boolean byteWide) {
        long value = popLong(consts);   // pushed last
        long addr  = popLong(consts);
        load(cb, 0, addr);
        load(cb, 1, value);
        cb.emit(byteWide ? A64.strb(1, 0, 0) : A64.strw(1, 0, 0));
    }

    private static void load(CodeBuffer cb, int rd, long value) { cb.emitAll(A64.loadImm64(rd, value)); }

    private static long popLong(Deque<Long> consts) {
        Long v = consts.poll();
        if (v == null)
            throw new UnsupportedOperationException("intrinsic argument is not a compile-time constant (needs locals/arithmetic support)");
        return v;
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int s2(byte[] b, int i) { return (short) u2(b, i); }
}
