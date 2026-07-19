package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The baseline compiler: Java bytecode → A64 machine code, compile-only
 * (PLAN.md §2, §5). Runs on the seed JVM inside the writer today, compiled into
 * the image as the runtime JIT later — same code, two contexts.
 *
 * <p>Register model (deliberately simple, no spilling): the JVM operand stack
 * maps to x9..x15 by depth, and JVM local slot {@code k} maps to x{@code 19+k}.
 * Values are 64-bit registers; int/long are not distinguished (our values fit).
 * This holds because javac keeps the operand stack shallow and empty at branch
 * boundaries. Calls to {@code magic.Magic} intrinsics lower to privileged/MMIO
 * instructions rather than real calls (PLAN.md §5.1). Anything unhandled throws
 * loudly — gaps are never silent.
 */
public final class BaselineCompiler {

    private static final int OP_BASE = 9,  OP_MAX = 7;   // operand stack -> x9..x15
    private static final int LOC_BASE = 19, LOC_MAX = 10; // locals -> x19..x28

    private final ClassFile cf;
    private final byte[] imageData;      // appended data blob for Magic.message(), or null

    private int sp;                                        // operand stack depth
    private final List<Fixup> fixups = new ArrayList<>();
    private final List<AddrSlot> messageSlots = new ArrayList<>();

    public BaselineCompiler(ClassFile cf)                  { this(cf, null); }
    public BaselineCompiler(ClassFile cf, byte[] imageData) { this.cf = cf; this.imageData = imageData; }

    private interface BranchEnc { int encode(int byteOffset); }
    private record Fixup(int wordIndex, int targetBc, BranchEnc enc) {}
    private record AddrSlot(int wordIndex, int reg) {}

    /** Compile {@code method} into {@code cb} at the current position. */
    public void compile(ClassFile.Method method, CodeBuffer cb) {
        byte[] code = method.code;
        if (code == null) throw new IllegalArgumentException("method " + method.name + " has no Code");

        int[] bcToWord = new int[code.length];
        java.util.Arrays.fill(bcToWord, -1);
        sp = 0;

        int pos = 0;
        while (pos < code.length) {
            bcToWord[pos] = cb.wordCount();
            int op = code[pos] & 0xFF;
            pos += step(op, code, pos, cb);
        }

        // resolve branches (targets are all within the code, unaffected by data)
        for (Fixup f : fixups) {
            int target = bcToWord[f.targetBc];
            if (target < 0) throw new IllegalStateException("branch to non-instruction bc=" + f.targetBc);
            cb.set(f.wordIndex, f.enc.encode((target - f.wordIndex) * 4));
        }

        // append the data blob and fill in Magic.message() address slots
        if (!messageSlots.isEmpty()) {
            if (imageData == null) throw new IllegalStateException("Magic.message() used but no image data provided");
            long dataAddr = cb.here();
            emitBytes(cb, imageData);
            for (AddrSlot s : messageSlots) cb.patchAddr(s.wordIndex, s.reg, dataAddr);
        }
    }

    /** Handle one opcode; return its byte length. */
    private int step(int op, byte[] code, int pos, CodeBuffer cb) {
        switch (op) {
            case 0x00 -> { return 1; }                                  // nop
            case 0xB1 -> { cb.emit(A64.ret()); return 1; }              // return

            // ---- constant pushes ----
            case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> { loadConst(cb, op - 0x03); return 1; } // iconst_m1..5
            case 0x09 -> { loadConst(cb, 0); return 1; }                // lconst_0
            case 0x0A -> { loadConst(cb, 1); return 1; }                // lconst_1
            case 0x10 -> { loadConst(cb, (byte) code[pos + 1]); return 2; }        // bipush
            case 0x11 -> { loadConst(cb, (short) u2(code, pos + 1)); return 3; }   // sipush
            case 0x12 -> { loadConst(cb, cf.intAt(code[pos + 1] & 0xFF)); return 2; } // ldc (int)
            case 0x13 -> { loadConst(cb, cf.intAt(u2(code, pos + 1))); return 3; }    // ldc_w (int)
            case 0x14 -> { loadConst(cb, cf.longAt(u2(code, pos + 1))); return 3; }   // ldc2_w (long)

            // ---- local loads (copy local reg -> new stack reg) ----
            case 0x15, 0x16 -> { load(cb, code[pos + 1] & 0xFF); return 2; }         // iload/lload
            case 0x1A, 0x1B, 0x1C, 0x1D -> { load(cb, op - 0x1A); return 1; }        // iload_0..3
            case 0x1E, 0x1F, 0x20, 0x21 -> { load(cb, op - 0x1E); return 1; }        // lload_0..3

            // ---- local stores (pop stack reg -> local reg) ----
            case 0x36, 0x37 -> { store(cb, code[pos + 1] & 0xFF); return 2; }        // istore/lstore
            case 0x3B, 0x3C, 0x3D, 0x3E -> { store(cb, op - 0x3B); return 1; }       // istore_0..3
            case 0x3F, 0x40, 0x41, 0x42 -> { store(cb, op - 0x3F); return 1; }       // lstore_0..3
            case 0x84 -> { iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]); return 3; } // iinc

            // ---- arithmetic (64-bit; int/long alike) ----
            case 0x60, 0x61 -> { binop(cb, Bin.ADD); return 1; }        // iadd/ladd
            case 0x64, 0x65 -> { binop(cb, Bin.SUB); return 1; }        // isub/lsub
            case 0x7E, 0x7F -> { binop(cb, Bin.AND); return 1; }        // iand/land

            // ---- widen/narrow: no-ops for our value model ----
            case 0x85, 0x88, 0x91, 0x92 -> { return 1; }               // i2l, l2i, i2b, i2c

            // ---- branches ----
            case 0x99 -> { branchZero(cb, code, pos, true);  return 3; } // ifeq  -> CBZ
            case 0x9A -> { branchZero(cb, code, pos, false); return 3; } // ifne  -> CBNZ
            case 0x9B -> { branchCmpZero(cb, code, pos, A64.LT); return 3; } // iflt
            case 0x9C -> { branchCmpZero(cb, code, pos, A64.GE); return 3; } // ifge
            case 0x9D -> { branchCmpZero(cb, code, pos, A64.GT); return 3; } // ifgt
            case 0x9E -> { branchCmpZero(cb, code, pos, A64.LE); return 3; } // ifle
            case 0x9F -> { branchCmp(cb, code, pos, A64.EQ); return 3; } // if_icmpeq
            case 0xA0 -> { branchCmp(cb, code, pos, A64.NE); return 3; } // if_icmpne
            case 0xA1 -> { branchCmp(cb, code, pos, A64.LT); return 3; } // if_icmplt
            case 0xA2 -> { branchCmp(cb, code, pos, A64.GE); return 3; } // if_icmpge
            case 0xA3 -> { branchCmp(cb, code, pos, A64.GT); return 3; } // if_icmpgt
            case 0xA4 -> { branchCmp(cb, code, pos, A64.LE); return 3; } // if_icmple
            case 0xA7 -> {                                              // goto
                int target = pos + s2(code, pos + 1);
                int w = cb.emit(A64.b(0));
                fixups.add(new Fixup(w, target, A64::b));
                expectEmpty("goto");
                return 3;
            }

            case 0xB8 -> { lowerInvokeStatic(u2(code, pos + 1), cb); return 3; } // invokestatic

            default -> throw new UnsupportedOperationException(
                    String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
        }
    }

    private enum Bin { ADD, SUB, AND }

    // ----- stack / local register allocation -------------------------------
    private int pushReg() { if (sp >= OP_MAX) throw new IllegalStateException("operand stack too deep"); return OP_BASE + sp++; }
    private int popReg()  { if (sp <= 0) throw new IllegalStateException("operand stack underflow"); return OP_BASE + --sp; }
    private int localReg(int slot) {
        if (slot < 0 || slot >= LOC_MAX) throw new IllegalStateException("local slot out of range: " + slot);
        return LOC_BASE + slot;
    }
    private void expectEmpty(String where) {
        if (sp != 0) throw new IllegalStateException("operand stack not empty (" + sp + ") at " + where);
    }

    private void loadConst(CodeBuffer cb, long v) { cb.emitAll(A64.loadImm64(pushReg(), v)); }
    private void load(CodeBuffer cb, int slot)    { int r = pushReg(); cb.emit(A64.movReg(r, localReg(slot))); }
    private void store(CodeBuffer cb, int slot)   { int r = popReg();  cb.emit(A64.movReg(localReg(slot), r)); }

    private void iinc(CodeBuffer cb, int slot, int delta) {
        int r = localReg(slot);
        if (delta >= 0) cb.emit(A64.addImm(r, r, delta));
        else            cb.emit(A64.subImm(r, r, -delta));
    }

    private void binop(CodeBuffer cb, Bin kind) {
        int b = popReg(), a = popReg(), r = pushReg();
        cb.emit(switch (kind) {
            case ADD -> A64.addReg(r, a, b);
            case SUB -> A64.subReg(r, a, b);
            case AND -> A64.andReg(r, a, b);
        });
    }

    // ----- branches --------------------------------------------------------
    private void branchZero(CodeBuffer cb, byte[] code, int pos, boolean eq) {
        int v = popReg();
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, eq ? off -> A64.cbz(v, off) : off -> A64.cbnz(v, off)));
        expectEmpty("if");
    }

    private void branchCmpZero(CodeBuffer cb, byte[] code, int pos, int cond) {
        int v = popReg();
        cb.emit(A64.cmpImm(v, 0));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, off -> A64.bcond(cond, off)));
        expectEmpty("if<cond>");
    }

    private void branchCmp(CodeBuffer cb, byte[] code, int pos, int cond) {
        int b = popReg(), a = popReg();
        cb.emit(A64.cmpReg(a, b));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        fixups.add(new Fixup(w, target, off -> A64.bcond(cond, off)));
        expectEmpty("if_icmp");
    }

    // ----- intrinsic calls -------------------------------------------------
    private void lowerInvokeStatic(int cpIndex, CodeBuffer cb) {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        String key = ref.owner() + "." + ref.name() + ref.descriptor();
        switch (key) {
            case "magic/Magic.wfe()V"  -> cb.emit(A64.wfe());
            case "magic/Magic.isb()V"  -> cb.emit(A64.isb());
            case "magic/Magic.eret()V" -> cb.emit(A64.eret());
            case "magic/Magic.dropToEL1()V" -> lowerDropToEL1(cb);

            case "magic/Magic.writeHCR_EL2(J)V"     -> { cb.emit(A64.msr(A64.HCR_EL2, popReg())); }
            case "magic/Magic.writeCPTR_EL2(J)V"    -> { cb.emit(A64.msr(A64.CPTR_EL2, popReg())); }
            case "magic/Magic.writeCNTHCTL_EL2(J)V" -> { cb.emit(A64.msr(A64.CNTHCTL_EL2, popReg())); }
            case "magic/Magic.writeCNTVOFF_EL2(J)V" -> { cb.emit(A64.msr(A64.CNTVOFF_EL2, popReg())); }
            case "magic/Magic.writeSCTLR_EL1(J)V"   -> { cb.emit(A64.msr(A64.SCTLR_EL1, popReg())); }
            case "magic/Magic.writeSPSR_EL2(J)V"    -> { cb.emit(A64.msr(A64.SPSR_EL2, popReg())); }
            case "magic/Magic.writeELR_EL2(J)V"     -> { cb.emit(A64.msr(A64.ELR_EL2, popReg())); }
            case "magic/Magic.writeCPACR_EL1(J)V"   -> { cb.emit(A64.msr(A64.CPACR_EL1, popReg())); }
            case "magic/Magic.writeSP(J)V"          -> { cb.emit(A64.movToSp(popReg())); }

            case "magic/Magic.store32(JI)V" -> { int val = popReg(), addr = popReg(); cb.emit(A64.strw(val, addr, 0)); }
            case "magic/Magic.store8(JI)V"  -> { int val = popReg(), addr = popReg(); cb.emit(A64.strb(val, addr, 0)); }
            case "magic/Magic.load32(J)I"   -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrw(r, addr, 0)); }
            case "magic/Magic.load8(J)I"    -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrb(r, addr, 0)); }

            case "magic/Magic.message()J" -> { int r = pushReg(); messageSlots.add(new AddrSlot(cb.reserveAddr(r), r)); }
            case "magic/Magic.messageLen()I" -> loadConst(cb, imageData == null ? 0 : imageData.length);

            default -> throw new UnsupportedOperationException("call not yet supported: " + key);
        }
    }

    /**
     * Lower the EL2→EL1 drop (mirrors vm.EmitBoot). Self-referential: ELR_EL2
     * points at the instruction right after ERET, which we backpatch. Uses x0 as
     * scratch — safe because callers invoke this at a statement boundary (empty
     * operand stack, x9+ free).
     */
    private void lowerDropToEL1(CodeBuffer cb) {
        expectEmpty("dropToEL1");
        cb.emit(A64.mrs(0, A64.CurrentEL));
        int tbz = cb.emit(A64.tbz(0, 3, 0));                 // already EL1 -> skip
        set64(cb, 0, 0x8000_0000L); cb.emit(A64.msr(A64.HCR_EL2, 0));
        set64(cb, 0, 0x33FFL);      cb.emit(A64.msr(A64.CPTR_EL2, 0));
        set64(cb, 0, 0x3L);         cb.emit(A64.msr(A64.CNTHCTL_EL2, 0));
        cb.emit(A64.msr(A64.CNTVOFF_EL2, A64.XZR));
        set64(cb, 0, 0x30D0_0800L); cb.emit(A64.msr(A64.SCTLR_EL1, 0));
        set64(cb, 0, 0x3C5L);       cb.emit(A64.msr(A64.SPSR_EL2, 0));
        int elr = cb.reserveAddr(0); cb.emit(A64.msr(A64.ELR_EL2, 0));
        cb.emit(A64.eret());
        int cont = cb.wordCount();
        cb.set(tbz, A64.tbz(0, 3, (cont - tbz) * 4));
        cb.patchAddr(elr, 0, cb.pcAt(cont));
    }

    private static void set64(CodeBuffer cb, int rd, long v) { cb.emitAll(A64.loadImm64(rd, v)); }

    private static void emitBytes(CodeBuffer cb, byte[] bytes) {
        int padded = (bytes.length + 3) & ~3;
        for (int i = 0; i < padded; i += 4) {
            int w = 0;
            for (int b = 0; b < 4; b++) {
                int idx = i + b;
                if (idx < bytes.length) w |= (bytes[idx] & 0xFF) << (b * 8);
            }
            cb.emit(w);
        }
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int s2(byte[] b, int i) { return (short) u2(b, i); }
}
