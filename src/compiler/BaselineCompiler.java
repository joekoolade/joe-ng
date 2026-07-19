package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassFile;
import objectmodel.ObjectModel;

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

    /** Resolves an owner class name (e.g. "vm/Cell") to its parsed classfile. */
    public interface ClassResolver { ClassFile resolve(String owner); }

    /** A single compiled method: its words and the fixups awaiting relocation. */
    public record CompiledMethod(int[] words, List<CallSite> callSites, List<TibRef> tibRefs, List<StrRef> strRefs) {}
    /** A {@code BL} site: word index within the method, and the callee key. */
    public record CallSite(int wordIndex, String calleeKey) {}
    /** A reserved TIB-pointer address load ({@code new}) awaiting the class's TIB address. */
    public record TibRef(int wordIndex, int reg, String className) {}
    /** A reserved address load for an interned string literal ({@code ldc}). */
    public record StrRef(int wordIndex, int reg, String text) {}

    private final ClassFile cf;
    private final ClassResolver resolver;

    private int sp;
    private boolean isEntry;
    private int frameSize, localSaveBase, spillBase, maxLocals;
    private boolean saveLR, nonLeaf;
    private final List<Fixup> fixups = new ArrayList<>();
    private final List<CallSite> callSites = new ArrayList<>();
    private final List<TibRef> tibRefs = new ArrayList<>();
    private final List<StrRef> strRefs = new ArrayList<>();

    public BaselineCompiler(ClassFile cf)                     { this(cf, null); }
    public BaselineCompiler(ClassFile cf, ClassResolver resolver) {
        this.cf = cf; this.resolver = resolver;
    }

    private interface BranchEnc { int encode(int byteOffset); }
    private record Fixup(int wordIndex, int targetBc, BranchEnc enc) {}

    /** Method key used for call resolution: {@code owner.name+descriptor}. */
    public static String key(String owner, String name, String desc) { return owner + "." + name + desc; }

    /** Back-compat single-method compile with no real calls (spin/fixtures). */
    public void compile(ClassFile.Method method, CodeBuffer cb) {
        CompiledMethod cm = compileMethod(method, cb.base(), false);
        if (!cm.callSites.isEmpty() || !cm.tibRefs.isEmpty() || !cm.strRefs.isEmpty())
            throw new IllegalStateException("compile(Method,CodeBuffer) is for self-contained methods; use ImageBuilder");
        for (int w : cm.words) cb.emit(w);
    }

    /** Compile one method at absolute {@code base}; {@code isEntry} => no frame. */
    public CompiledMethod compileMethod(ClassFile.Method method, long base, boolean isEntry) {
        byte[] code = method.code;
        if (code == null) throw new IllegalArgumentException("method " + method.name + " has no Code");

        this.isEntry = isEntry;
        this.maxLocals = method.maxLocals;
        this.nonLeaf = isNonLeaf(code);
        this.saveLR = !isEntry && nonLeaf;
        // frame: [LR?][locals...][operand-stack spill area for calls]
        this.localSaveBase = saveLR ? 8 : 0;
        this.spillBase = localSaveBase + maxLocals * 8;
        int spillWords = (!isEntry && nonLeaf) ? OP_MAX : 0;
        int savedWords = (saveLR ? 1 : 0) + maxLocals + spillWords;
        this.frameSize = isEntry ? 0 : A64.align16(savedWords * 8);
        sp = 0;

        CodeBuffer cb = new CodeBuffer(base);
        if (!isEntry) emitPrologue(cb, method);

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
        return new CompiledMethod(cb.toWords(), List.copyOf(callSites), List.copyOf(tibRefs), List.copyOf(strRefs));
    }

    // ----- prologue / epilogue --------------------------------------------
    private void emitPrologue(CodeBuffer cb, ClassFile.Method method) {
        if (frameSize > 0) cb.emit(A64.subImm(31, 31, frameSize));      // sub sp, sp, #frame
        if (saveLR) cb.emit(A64.strx(30, 31, 0));                        // str x30, [sp]
        for (int i = 0; i < maxLocals; i++) cb.emit(A64.strx(LOC_BASE + i, 31, localSaveBase + i * 8));
        // move incoming arguments (x0..) into their local registers;
        // instance methods receive `this` as x0 -> local slot 0.
        int arg = 0, slot = 0;
        if (!method.isStatic) { cb.emit(A64.movReg(localReg(0), 0)); arg = 1; slot = 1; }
        for (char t : paramTypes(method.descriptor)) {
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
            case 0x12 -> { ldc(cb, code[pos + 1] & 0xFF); return 2; }
            case 0x13 -> { ldc(cb, u2(code, pos + 1)); return 3; }
            case 0x14 -> { loadConst(cb, cf.longAt(u2(code, pos + 1))); return 3; }

            case 0x15, 0x16, 0x19 -> { load(cb, code[pos + 1] & 0xFF); return 2; }     // iload/lload/aload
            case 0x1A, 0x1B, 0x1C, 0x1D -> { load(cb, op - 0x1A); return 1; }         // iload_0..3
            case 0x1E, 0x1F, 0x20, 0x21 -> { load(cb, op - 0x1E); return 1; }         // lload_0..3
            case 0x2A, 0x2B, 0x2C, 0x2D -> { load(cb, op - 0x2A); return 1; }         // aload_0..3

            case 0x36, 0x37, 0x3A -> { store(cb, code[pos + 1] & 0xFF); return 2; }   // istore/lstore/astore
            case 0x3B, 0x3C, 0x3D, 0x3E -> { store(cb, op - 0x3B); return 1; }        // istore_0..3
            case 0x3F, 0x40, 0x41, 0x42 -> { store(cb, op - 0x3F); return 1; }        // lstore_0..3
            case 0x4B, 0x4C, 0x4D, 0x4E -> { store(cb, op - 0x4B); return 1; }        // astore_0..3
            case 0x59 -> { dup(cb); return 1; }                                       // dup
            case 0x84 -> { iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]); return 3; }

            // ---- array element load/store (base + index<<scale) ----
            case 0x33 -> { arrayLoad(cb, 0, false); return 1; }   // baload  (byte)
            case 0x2E -> { arrayLoad(cb, 2, true);  return 1; }   // iaload  (int)
            case 0x2F -> { arrayLoad(cb, 3, false); return 1; }   // laload  (long)
            case 0x32 -> { arrayLoad(cb, 3, false); return 1; }   // aaload  (ref)
            case 0x54 -> { arrayStore(cb, 0); return 1; }         // bastore
            case 0x4F -> { arrayStore(cb, 2); return 1; }         // iastore
            case 0x50 -> { arrayStore(cb, 3); return 1; }         // lastore
            case 0x53 -> { arrayStore(cb, 3); return 1; }         // aastore
            case 0xBE -> { arrayLength(cb); return 1; }           // arraylength

            case 0x60, 0x61 -> { binop(cb, Bin.ADD); return 1; }
            case 0x64, 0x65 -> { binop(cb, Bin.SUB); return 1; }
            case 0x68, 0x69 -> { binop(cb, Bin.MUL); return 1; }
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

            case 0xB4 -> { getfield(cb, u2(code, pos + 1)); return 3; }
            case 0xB5 -> { putfield(cb, u2(code, pos + 1)); return 3; }
            case 0xB6 -> { lowerInvokeVirtual(u2(code, pos + 1), cb); return 3; }
            case 0xB7 -> { lowerInvokeSpecial(u2(code, pos + 1), cb); return 3; }
            case 0xB8 -> { lowerInvokeStatic(u2(code, pos + 1), cb); return 3; }
            case 0xBB -> { lowerNew(u2(code, pos + 1), cb); return 3; }
            case 0xBC -> { lowerNewArray(code[pos + 1] & 0xFF, cb); return 2; }

            default -> throw new UnsupportedOperationException(
                    String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
        }
    }

    private enum Bin { ADD, SUB, MUL, AND }

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
            case MUL -> A64.mulReg(r, a, b);
            case AND -> A64.andReg(r, a, b);
        });
    }

    private void dup(CodeBuffer cb) {
        int top = OP_BASE + sp - 1;
        cb.emit(A64.movReg(pushReg(), top));
    }

    // ----- object fields (8-byte slots; see objectmodel.ObjectModel) --------
    private void getfield(CodeBuffer cb, int cpIndex) {
        int off = fieldOffset(cf.memberRef(cpIndex));
        int obj = popReg(), r = pushReg();
        cb.emit(A64.ldrx(r, obj, off));
    }

    private void putfield(CodeBuffer cb, int cpIndex) {
        int off = fieldOffset(cf.memberRef(cpIndex));
        int val = popReg(), obj = popReg();
        cb.emit(A64.strx(val, obj, off));
    }

    private int fieldOffset(ClassFile.MemberRef ref) {
        ClassFile owner = resolve(ref.owner());
        return ObjectModel.fieldOffset(owner.instanceFieldIndex(ref.name()));
    }

    // ----- allocation: new -> Heap.alloc(size), store TIB, push ref ---------
    private void lowerNew(int classIndex, CodeBuffer cb) {
        expectEmpty("new");
        String className = cf.classAt(classIndex);
        int size = ObjectModel.scalarSize(resolve(className).instanceFieldCount());
        cb.emitAll(A64.loadImm64(0, size));                       // x0 = size (Heap.alloc arg)
        int w = cb.emit(A64.bl(0));                               // x0 = object base
        callSites.add(new CallSite(w, "vm/Heap.alloc(I)J"));
        int t = cb.reserveAddr(1);                                // x1 = &TIB (patched by ImageBuilder)
        tibRefs.add(new TibRef(t, 1, className));
        cb.emit(A64.strx(1, 0, ObjectModel.TIB_OFFSET));          // header.tib = &TIB
        cb.emit(A64.movReg(pushReg(), 0));                        // push the reference
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
        lowerCall(ref, cb, false);
    }

    /** Virtual dispatch through the receiver's TIB vtable. Uses x16 (scratch) for the target. */
    private void lowerInvokeVirtual(int cpIndex, CodeBuffer cb) {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        int slot = resolve(ref.owner()).vtableSlot(ref.name(), ref.descriptor());
        int nargs = paramTypes(ref.descriptor()).length + 1;    // receiver + params
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++) src[k] = popReg();
        for (int k = 0; k < nargs; k++) cb.emit(A64.movReg(nargs - 1 - k, src[k])); // x0 = receiver
        spillLive(cb);
        cb.emit(A64.ldrx(16, 0, ObjectModel.TIB_OFFSET));       // x16 = receiver.tib
        cb.emit(A64.ldrx(16, 16, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)))); // x16 = code
        cb.emit(A64.blr(16));
        reloadLive(cb);
        if (returnType(ref.descriptor()) != 'V') cb.emit(A64.movReg(pushReg(), 0));
    }

    private void lowerInvokeSpecial(int cpIndex, CodeBuffer cb) {
        ClassFile.MemberRef ref = cf.memberRef(cpIndex);
        if (ref.owner().equals("java/lang/Object") && ref.name().equals("<init>")) {
            popReg();                                            // trivial super() — discard receiver
            return;
        }
        lowerCall(ref, cb, true);                                // constructor: receiver is arg0
    }

    /** A real call: args to x0.. (receiver first if any), BL placeholder, result from x0. */
    private void lowerCall(ClassFile.MemberRef ref, CodeBuffer cb, boolean hasReceiver) {
        emitCall(cb, key(ref.owner(), ref.name(), ref.descriptor()), ref.descriptor(), hasReceiver);
    }

    /** Emit a direct call to {@code calleeKey}: args to x0.., BL placeholder, result from x0. */
    private void emitCall(CodeBuffer cb, String calleeKey, String descriptor, boolean hasReceiver) {
        int nargs = paramTypes(descriptor).length + (hasReceiver ? 1 : 0);
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++) src[k] = popReg();       // src[0] = last arg (top of stack)
        for (int k = 0; k < nargs; k++) cb.emit(A64.movReg(nargs - 1 - k, src[k])); // -> x(argIndex)
        spillLive(cb);                                           // preserve operand values below the args
        int w = cb.emit(A64.bl(0));
        callSites.add(new CallSite(w, calleeKey));
        reloadLive(cb);
        if (returnType(descriptor) != 'V') cb.emit(A64.movReg(pushReg(), 0));
    }

    // ----- arrays: [header][length @16][elements @24], element = base + index<<scale -----
    private void lowerNewArray(int atype, CodeBuffer cb) {
        loadConst(cb, arrayElemSize(atype));                     // push elemSize
        emitCall(cb, "vm/Heap.allocArray(II)J", "(II)J", false); // (length, elemSize) -> ref
    }

    private void arrayLength(CodeBuffer cb) {
        int arr = popReg(), r = pushReg();
        cb.emit(A64.ldrx(r, arr, ObjectModel.ARRAY_LENGTH_OFFSET));
    }

    private void arrayLoad(CodeBuffer cb, int scale, boolean word32) {
        int index = popReg(), arr = popReg(), r = pushReg();     // r == arr's register
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));          // arr = &elem[index]
        cb.emit(scale == 0 ? A64.ldrb(r, arr, 0) : word32 ? A64.ldrw(r, arr, 0) : A64.ldrx(r, arr, 0));
    }

    private void arrayStore(CodeBuffer cb, int scale) {
        int val = popReg(), index = popReg(), arr = popReg();
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));
        cb.emit(scale == 0 ? A64.strb(val, arr, 0) : scale == 2 ? A64.strw(val, arr, 0) : A64.strx(val, arr, 0));
    }

    /** newarray atype -> element size in bytes (JVMS Table 6.5.newarray-A). */
    private static int arrayElemSize(int atype) {
        return switch (atype) {
            case 4, 8 -> 1;              // boolean, byte
            case 5, 9 -> 2;              // char, short
            case 6, 10 -> 4;             // float, int
            case 7, 11 -> 8;             // double, long
            default -> throw new UnsupportedOperationException("bad newarray atype " + atype);
        };
    }

    /** Spill operand-stack values (x9..) to the frame so a call can't clobber them. */
    private void spillLive(CodeBuffer cb) {
        for (int i = 0; i < sp; i++) cb.emit(A64.strx(OP_BASE + i, 31, spillBase + i * 8));
    }
    private void reloadLive(CodeBuffer cb) {
        for (int i = 0; i < sp; i++) cb.emit(A64.ldrx(OP_BASE + i, 31, spillBase + i * 8));
    }

    private ClassFile resolve(String owner) {
        if (owner.equals(cf.thisClassName())) return cf;
        if (resolver == null) throw new IllegalStateException("no class resolver for " + owner);
        return resolver.resolve(owner);
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
            case "store64(JJ)V" -> { int val = popReg(), addr = popReg(); cb.emit(A64.strx(val, addr, 0)); }
            case "load32(J)I"   -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrw(r, addr, 0)); }
            case "load8(J)I"    -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrb(r, addr, 0)); }
            case "load64(J)J"   -> { int addr = popReg(), r = pushReg(); cb.emit(A64.ldrx(r, addr, 0)); }

            case "bytes(Ljava/lang/String;)[B" -> { /* no-op: the operand is already an interned byte[] ref */ }

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
            if (op == 0xBB || op == 0xBC || op == 0xB6) return true; // new/newarray -> Heap.*; invokevirtual
            if (op == 0xB8) {                                    // invokestatic
                ClassFile.MemberRef ref = cf.memberRef(u2(code, pos + 1));
                if (!ref.owner().equals("magic/Magic")) return true;
            }
            if (op == 0xB7) {                                    // invokespecial
                ClassFile.MemberRef ref = cf.memberRef(u2(code, pos + 1));
                if (!(ref.owner().equals("java/lang/Object") && ref.name().equals("<init>"))) return true;
            }
            pos += opLen(op, code, pos);
        }
        return false;
    }

    /** Byte length of an opcode — only the ones this compiler emits appear here. */
    private static int opLen(int op, byte[] code, int pos) {
        return switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x19, 0x36, 0x37, 0x3A, 0xBC -> 2; // …/aload/istore/lstore/astore/newarray
            case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
                 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA7,
                 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB -> 3;         // getfield/putfield/invoke*/new
            default -> 1;
        };
    }

    private static void set64(CodeBuffer cb, int rd, long v) { cb.emitAll(A64.loadImm64(rd, v)); }

    /** ldc/ldc_w: int constant, or a String literal interned as a byte[] object. */
    private void ldc(CodeBuffer cb, int cpIndex) {
        if (cf.isStringConst(cpIndex)) {
            int r = pushReg();
            strRefs.add(new StrRef(cb.reserveAddr(r), r, cf.stringAt(cpIndex)));
        } else if (cf.isIntConst(cpIndex)) {
            loadConst(cb, cf.intAt(cpIndex));
        } else {
            throw new UnsupportedOperationException("ldc of unsupported constant #" + cpIndex);
        }
    }

    private static int u2(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    private static int s2(byte[] b, int i) { return (short) u2(b, i); }
}
