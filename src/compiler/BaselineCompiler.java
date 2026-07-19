package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The baseline compiler: Java bytecode → A64 machine code, compile-only
 * (PLAN.md §2, §5). Runs on the seed JVM inside the writer today, compiled into
 * the image as the runtime JIT later.
 *
 * <p><b>Register model</b> (simple, no spilling): JVM operand stack → x9..x15 by
 * depth; JVM local slot {@code k} → x{@code 19+k}. Values are 64-bit; int/long
 * are not distinguished (our values fit). Holds because javac keeps the operand
 * stack shallow and empty at branch/call boundaries.
 *
 * <p><b>Calling convention</b> (ours, bare metal): arguments in x0..x7, return
 * value in x0, link register x30. Locals live in callee-saved x19.., so a
 * method's prologue saves the ones it will use (plus x30 if it makes any call)
 * and moves incoming arguments into them; each {@code return} restores and
 * {@code ret}s. The entry method ({@code isEntry}) has no frame — it sets its own
 * stack and never returns. Inter-method call targets are left as {@code BL}
 * placeholders and resolved by {@link writer.ImageBuilder} after layout.
 *
 * <p>Magic intrinsics lower to privileged/MMIO instructions inline (no call).
 * Unhandled opcodes throw loudly — gaps are never silent.
 */
public final class BaselineCompiler {

    private static final int OP_BASE = 9,  OP_MAX = 7;    // operand stack -> x9..x15
    private static final int LOC_BASE = 19, LOC_MAX = 10; // locals -> x19..x28

    /** A single compiled method: its words and the calls awaiting relocation. */
    public record CompiledMethod(int[] words, List<CallSite> callSites) {}
    /** A {@code BL} site: word index within the method, and the callee key. */
    public record CallSite(int wordIndex, String calleeKey) {}

    private final ClassFile cf;
    private final byte[] imageData;

    private int sp;
    private boolean isEntry;
    private int frameSize, localSaveBase, maxLocals;
    private boolean saveLR;
    private final List<Fixup> fixups = new ArrayList<>();
    private final List<AddrSlot> messageSlots = new ArrayList<>();
    private final List<CallSite> callSites = new ArrayList<>();

    public BaselineCompiler(ClassFile cf)                  { this(cf, null); }
    public BaselineCompiler(ClassFile cf, byte[] imageData) { this.cf = cf; this.imageData = imageData; }

    private interface BranchEnc { int encode(int byteOffset); }
    private record Fixup(int wordIndex, int targetBc, BranchEnc enc) {}
    private record AddrSlot(int wordIndex, int reg) {}

    /** Method key used for call resolution: {@code owner.name+descriptor}. */
    public static String key(String owner, String name, String desc) { return owner + "." + name + desc; }

    /** Back-compat single-method compile with no real calls (spin/fixtures). */
    public void compile(ClassFile.Method method, CodeBuffer cb) {
        CompiledMethod cm = compileMethod(method, cb.base(), false);
        if (!cm.callSites.isEmpty())
            throw new IllegalStateException("compile(Method,CodeBuffer) is for call-free methods; use ImageBuilder");
        for (int w : cm.words) cb.emit(w);
    }

    /** Compile one method at absolute {@code base}; {@code isEntry} => no frame. */
    public CompiledMethod compileMethod(ClassFile.Method method, long base, boolean isEntry) {
        byte[] code = method.code;
        if (code == null) throw new IllegalArgumentException("method " + method.name + " has no Code");

        this.isEntry = isEntry;
        this.maxLocals = method.maxLocals;
        this.saveLR = !isEntry && isNonLeaf(code);
        int numSaved = (saveLR ? 1 : 0) + maxLocals;
        this.localSaveBase = saveLR ? 8 : 0;
        this.frameSize = isEntry ? 0 : A64.align16(numSaved * 8);
        sp = 0;

        CodeBuffer cb = new CodeBuffer(base);
        if (!isEntry) emitPrologue(cb, method.descriptor);

        int[] bcToWord = new int[code.length];
        java.util.Arrays.fill(bcToWord, -1);
        int pos = 0;
        while (pos < code.length) {
            bcToWord[pos] = cb.wordCount();
            int op = code[pos] & 0xFF;
            pos += step(op, code, pos, cb);
        }

        for (Fixup f : fixups) {
            int target = bcToWord[f.targetBc];
            if (target < 0) throw new IllegalStateException("branch to non-instruction bc=" + f.targetBc);
            cb.set(f.wordIndex, f.enc.encode((target - f.wordIndex) * 4));
        }
        if (!messageSlots.isEmpty()) {
            if (imageData == null) throw new IllegalStateException("Magic.message() used but no image data provided");
            long dataAddr = cb.here();
            emitBytes(cb, imageData);
            for (AddrSlot s : messageSlots) cb.patchAddr(s.wordIndex, s.reg, dataAddr);
        }
        return new CompiledMethod(cb.toWords(), List.copyOf(callSites));
    }

    // ----- prologue / epilogue --------------------------------------------
    private void emitPrologue(CodeBuffer cb, String descriptor) {
        if (frameSize > 0) cb.emit(A64.subImm(31, 31, frameSize));      // sub sp, sp, #frame
        if (saveLR) cb.emit(A64.strx(30, 31, 0));                        // str x30, [sp]
        for (int i = 0; i < maxLocals; i++) cb.emit(A64.strx(LOC_BASE + i, 31, localSaveBase + i * 8));
        // move incoming arguments (x0..) into their local registers
        int arg = 0, slot = 0;
        for (char t : paramTypes(descriptor)) {
            cb.emit(A64.movReg(localReg(slot), arg));
            arg++;
            slot += (t == 'J' || t == 'D') ? 2 : 1;
        }
    }

    private void emitEpilogue(CodeBuffer cb) {
        if (isEntry) { cb.emit(A64.ret()); return; }
        if (saveLR) cb.emit(A64.ldrx(30, 31, 0));
        for (int i = 0; i < maxLocals; i++) cb.emit(A64.ldrx(LOC_BASE + i, 31, localSaveBase + i * 8));
        if (frameSize > 0) cb.emit(A64.addImm(31, 31, frameSize));
        cb.emit(A64.ret());
    }

    // ----- opcode dispatch -------------------------------------------------
    private int step(int op, byte[] code, int pos, CodeBuffer cb) {
        switch (op) {
            case 0x00 -> { return 1; }                                  // nop
            case 0xB1 -> { emitEpilogue(cb); return 1; }               // return
            case 0xAC, 0xAD, 0xB0 -> { cb.emit(A64.movReg(0, popReg())); emitEpilogue(cb); return 1; } // ireturn/lreturn/areturn

            case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> { loadConst(cb, op - 0x03); return 1; }
            case 0x09 -> { loadConst(cb, 0); return 1; }
            case 0x0A -> { loadConst(cb, 1); return 1; }
            case 0x10 -> { loadConst(cb, (byte) code[pos + 1]); return 2; }
            case 0x11 -> { loadConst(cb, (short) u2(code, pos + 1)); return 3; }
            case 0x12 -> { loadConst(cb, cf.intAt(code[pos + 1] & 0xFF)); return 2; }
            case 0x13 -> { loadConst(cb, cf.intAt(u2(code, pos + 1))); return 3; }
            case 0x14 -> { loadConst(cb, cf.longAt(u2(code, pos + 1))); return 3; }

            case 0x15, 0x16 -> { load(cb, code[pos + 1] & 0xFF); return 2; }
            case 0x1A, 0x1B, 0x1C, 0x1D -> { load(cb, op - 0x1A); return 1; }
            case 0x1E, 0x1F, 0x20, 0x21 -> { load(cb, op - 0x1E); return 1; }

            case 0x36, 0x37 -> { store(cb, code[pos + 1] & 0xFF); return 2; }
            case 0x3B, 0x3C, 0x3D, 0x3E -> { store(cb, op - 0x3B); return 1; }
            case 0x3F, 0x40, 0x41, 0x42 -> { store(cb, op - 0x3F); return 1; }
            case 0x84 -> { iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]); return 3; }

            case 0x60, 0x61 -> { binop(cb, Bin.ADD); return 1; }
            case 0x64, 0x65 -> { binop(cb, Bin.SUB); return 1; }
            case 0x7E, 0x7F -> { binop(cb, Bin.AND); return 1; }

            case 0x85, 0x88, 0x91, 0x92 -> { return 1; }               // i2l/l2i/i2b/i2c: no-op

            case 0x99 -> { branchZero(cb, code, pos, true);  return 3; }
            case 0x9A -> { branchZero(cb, code, pos, false); return 3; }
            case 0x9B -> { branchCmpZero(cb, code, pos, A64.LT); return 3; }
            case 0x9C -> { branchCmpZero(cb, code, pos, A64.GE); return 3; }
            case 0x9D -> { branchCmpZero(cb, code, pos, A64.GT); return 3; }
            case 0x9E -> { branchCmpZero(cb, code, pos, A64.LE); return 3; }
            case 0x9F -> { branchCmp(cb, code, pos, A64.EQ); return 3; }
            case 0xA0 -> { branchCmp(cb, code, pos, A64.NE); return 3; }
            case 0xA1 -> { branchCmp(cb, code, pos, A64.LT); return 3; }
            case 0xA2 -> { branchCmp(cb, code, pos, A64.GE); return 3; }
            case 0xA3 -> { branchCmp(cb, code, pos, A64.GT); return 3; }
            case 0xA4 -> { branchCmp(cb, code, pos, A64.LE); return 3; }
            case 0xA7 -> {
                int target = pos + s2(code, pos + 1);
                int w = cb.emit(A64.b(0));
                fixups.add(new Fixup(w, target, A64::b));
                expectEmpty("goto");
                return 3;
            }

            case 0xB8 -> { lowerInvokeStatic(u2(code, pos + 1), cb); return 3; }

            default -> throw new UnsupportedOperationException(
                    String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
        }
    }

    private enum Bin { ADD, SUB, AND }

    // ----- register allocation ---------------------------------------------
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
        cb.emit(delta >= 0 ? A64.addImm(r, r, delta) : A64.subImm(r, r, -delta));
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

    // ----- calls / intrinsics ----------------------------------------------
    private void lowerInvokeStatic(int cpIndex, CodeBuffer cb) {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        if (ref.owner().equals("magic/Magic")) { lowerIntrinsic(ref, cb); return; }
        lowerCall(ref, cb);
    }

    /** A real static call: args to x0.., BL placeholder, result from x0. */
    private void lowerCall(ClassFile.MemberRef ref, CodeBuffer cb) {
        char[] params = paramTypes(ref.descriptor());
        int nargs = params.length;
        // pop args: top of stack is the last argument
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++) src[k] = popReg();      // src[0]=last arg
        for (int k = 0; k < nargs; k++) cb.emit(A64.movReg(nargs - 1 - k, src[k])); // -> x(argIndex)
        expectEmpty("call");
        int w = cb.emit(A64.bl(0));                             // placeholder; resolved after layout
        callSites.add(new CallSite(w, key(ref.owner(), ref.name(), ref.descriptor())));
        if (returnType(ref.descriptor()) != 'V') cb.emit(A64.movReg(pushReg(), 0));
    }

    private void lowerIntrinsic(ClassFile.MemberRef ref, CodeBuffer cb) {
        String key = ref.name() + ref.descriptor();
        switch (key) {
            case "wfe()V"  -> cb.emit(A64.wfe());
            case "isb()V"  -> cb.emit(A64.isb());
            case "eret()V" -> cb.emit(A64.eret());
            case "dropToEL1()V" -> lowerDropToEL1(cb);

            case "writeHCR_EL2(J)V"     -> cb.emit(A64.msr(A64.HCR_EL2, popReg()));
            case "writeCPTR_EL2(J)V"    -> cb.emit(A64.msr(A64.CPTR_EL2, popReg()));
            case "writeCNTHCTL_EL2(J)V" -> cb.emit(A64.msr(A64.CNTHCTL_EL2, popReg()));
            case "writeCNTVOFF_EL2(J)V" -> cb.emit(A64.msr(A64.CNTVOFF_EL2, popReg()));
            case "writeSCTLR_EL1(J)V"   -> cb.emit(A64.msr(A64.SCTLR_EL1, popReg()));
            case "writeSPSR_EL2(J)V"    -> cb.emit(A64.msr(A64.SPSR_EL2, popReg()));
            case "writeELR_EL2(J)V"     -> cb.emit(A64.msr(A64.ELR_EL2, popReg()));
            case "writeCPACR_EL1(J)V"   -> cb.emit(A64.msr(A64.CPACR_EL1, popReg()));
            case "writeSP(J)V"          -> cb.emit(A64.movToSp(popReg()));

            case "store32(JI)V" -> { int val = popReg(), addr = popReg(); cb.emit(A64.strw(val, addr, 0)); }
            case "store8(JI)V"  -> { int val = popReg(), addr = popReg(); cb.emit(A64.strb(val, addr, 0)); }
            case "load32(J)I"   -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrw(r, addr, 0)); }
            case "load8(J)I"    -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrb(r, addr, 0)); }

            case "message()J"    -> { int r = pushReg(); messageSlots.add(new AddrSlot(cb.reserveAddr(r), r)); }
            case "messageLen()I" -> loadConst(cb, imageData == null ? 0 : imageData.length);

            default -> throw new UnsupportedOperationException("unknown intrinsic magic/Magic." + key);
        }
    }

    private void lowerDropToEL1(CodeBuffer cb) {
        expectEmpty("dropToEL1");
        cb.emit(A64.mrs(0, A64.CurrentEL));
        int tbz = cb.emit(A64.tbz(0, 3, 0));
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

    // ----- descriptor helpers ----------------------------------------------
    private static char[] paramTypes(String descriptor) {
        List<Character> out = new ArrayList<>();
        int i = descriptor.indexOf('(') + 1;
        while (descriptor.charAt(i) != ')') {
            char c = descriptor.charAt(i);
            if (c == 'L') { out.add('L'); i = descriptor.indexOf(';', i) + 1; }
            else if (c == '[') { i++; }                          // array: skip, dimension folds into element
            else { out.add(c); i++; }
        }
        char[] a = new char[out.size()];
        for (int k = 0; k < a.length; k++) a[k] = out.get(k);
        return a;
    }
    private static char returnType(String descriptor) { return descriptor.charAt(descriptor.indexOf(')') + 1); }

    private boolean isNonLeaf(byte[] code) {
        int pos = 0;
        while (pos < code.length) {
            int op = code[pos] & 0xFF;
            if (op == 0xB8) {                                    // invokestatic
                ClassFile.MemberRef ref = cf.memberRef(u2(code, pos + 1));
                if (!ref.owner().equals("magic/Magic")) return true;
            }
            pos += opLen(op, code, pos);
        }
        return false;
    }

    /** Byte length of an opcode — only the ones this compiler emits appear here. */
    private static int opLen(int op, byte[] code, int pos) {
        return switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x36, 0x37 -> 2;        // bipush/ldc/iload/lload/istore/lstore
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
                 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA7, 0xB8 -> 3;
            default -> 1;
        };
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
