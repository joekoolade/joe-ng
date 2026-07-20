package compiler;

import asm.A64;
import asm.CodeBuffer;
import classfile.ClassReader;
import objectmodel.ObjectModel;

/**
 * The shared, {@code ClassFile}-free baseline compiler core: bytecode -> A64,
 * reading the constant pool through {@link ClassReader} over {@code byte[]} +
 * offset/tag tables and resolving every symbolic reference through the
 * {@link Symbols} seam. Because it names no JDK {@code ClassFile}, the same class
 * serves both the writer (via {@link BaselineCompiler} + {@link WriterSymbols})
 * and, in time, the on-metal loader (via a metal {@code Symbols}) — one compiler
 * for both worlds (PLAN.md §M5.4.5).
 */
public final class Baseline
{
    private static final int OP_BASE = 9;    // operand stack -> x9..x15
    private static final int OP_MAX = 7;
    private static final int LOC_BASE = 19;  // locals -> x19..x28
    private static final int LOC_MAX = 10;   // beyond this a local lives in the frame
    private static final int SCRATCH = 16;   // IP0 — not a local or operand register

    // The shared cp view (byte[] + offset/tag tables) that the metal loader also
    // parses (§4.3). The lowering above the Symbols seam reads constants,
    // descriptors and names through ClassReader over these, never through the
    // JDK-side ClassFile — which is what lets one lowering serve both worlds (§4.4).
    private final byte[] classBytes;
    private final int[] cpOff;
    private final int[] cpTag;

    private int sp;
    private int[] bcDepth;         // operand-stack depth at each branch target, or -1
    // The method's exception table as parallel arrays (a catchType of 0 = catch-all),
    // the metal-friendly form the shared athrow lowering iterates — no ClassFile.
    private int[] exStartPc;
    private int[] exEndPc;
    private int[] exHandlerPc;
    private int[] exCatchType;
    private int exCount;
    private boolean isEntry;
    private int frameSize;
    private int localSaveBase;
    private int spillBase;
    private int overflowBase;     // frame offset of local slot LOC_MAX
    private int regLocals;        // locals held in x19.. (min(maxLocals, LOC_MAX))
    private int overflowLocals;   // locals held in the frame (maxLocals - LOC_MAX)
    private int maxLocals;
    private boolean saveLR;
    private boolean nonLeaf;
    // Branch fixups never leave this class, so they are plain data rather than a
    // List: an array plus a count, grown by hand. Every future shape of this
    // compiler needs branch patching, so this conversion survives the core/wrapper
    // split (unlike the relocation records below, which are the writer's interface).
    private Fixup[] fixups = new Fixup[8];
    private int fixupCount;

    // ----- symbol seam (PLAN.md §M5.4.2) -----------------------------------
    private static final int SYM_CP = 0;      // symbol identified by a constant-pool index
    private static final int SYM_HELPER = 1;  // symbol is a synthesised runtime helper

    /** The seam the shared lowering emits through. Here it is the {@link WriterSymbols}. */
    private final Symbols symbols;

    // A forward branch is emitted before its target word index is known, so it is
    // patched later. Rather than carry a closure per branch, each fixup records
    // *what kind* of branch it is plus its single operand — a register for
    // cbz/cbnz, a condition code for b.cond, nothing for an unconditional b.
    //
    // That defunctionalisation is not stylistic: a lambda compiles to
    // invokedynamic, which needs a bootstrap-method runtime that does not exist on
    // bare metal, so it would keep this compiler out of its own image (PLAN.md
    // §M5.1). Plain data patches the same way and compiles anywhere.
    private static final int FIX_B = 0;       // unconditional; arg unused
    private static final int FIX_CBZ = 1;     // arg = register to test
    private static final int FIX_CBNZ = 2;    // arg = register to test
    private static final int FIX_BCOND = 3;   // arg = condition code

    private static final class Fixup
    {
        final int wordIndex;
        final int targetBc;
        final int kind;
        final int arg;
        Fixup(int wordIndex, int targetBc, int kind, int arg)
        {
            this.wordIndex = wordIndex;
            this.targetBc = targetBc;
            this.kind = kind;
            this.arg = arg;
        }
    }

    /** Append a pending branch, growing the array by hand (no JDK collections). */
    private void addFixup(int wordIndex, int targetBc, int kind, int arg)
    {
        if (fixupCount == fixups.length)
        {
            Fixup[] bigger = new Fixup[fixups.length * 2];
            for (int i = 0; i < fixups.length; i++)
            {
                bigger[i] = fixups[i];
            }
            fixups = bigger;
        }
        fixups[fixupCount] = new Fixup(wordIndex, targetBc, kind, arg);
        fixupCount += 1;
    }

    /** Encode a pending branch now that the distance to its target is known. */
    private static int encodeBranch(Fixup f, int byteOffset)
    {
        if (f.kind == FIX_CBZ)
        {
            return A64.cbz(f.arg, byteOffset);
        }
        if (f.kind == FIX_CBNZ)
        {
            return A64.cbnz(f.arg, byteOffset);
        }
        if (f.kind == FIX_BCOND)
        {
            return A64.bcond(f.arg, byteOffset);
        }
        return A64.b(byteOffset);
    }

    // Diagnostics build their text by concatenation (and String.format), which javac
    // lowers to invokedynamic — a bootstrap-method runtime that does not exist on
    // bare metal. Funnelling every message through these helpers quarantines that
    // dependency in a handful of methods instead of scattering it across the
    // compiler, so everything else can be compiled into the image. In the eventual
    // core/wrapper split these stay on the writer side (PLAN.md §M5.1 stage 4).
    // The exception *types* are preserved: an unsupported opcode must keep throwing
    // UnsupportedOperationException, which is how gaps stay loud and how the M5Gap
    // harness classifies them.
    private static RuntimeException bad(String what, int value)
    {
        return new IllegalStateException(what + ": " + value);
    }
    private static RuntimeException bad(String what, String value)
    {
        return new IllegalStateException(what + ": " + value);
    }
    private static RuntimeException unsupported(String what, int value)
    {
        return new UnsupportedOperationException(what + " " + value);
    }
    private static RuntimeException unsupported(String what, String value)
    {
        return new UnsupportedOperationException(what + value);
    }
    private static RuntimeException unsupportedOpcode(int op, int pos)
    {
        return new UnsupportedOperationException(
            String.format("opcode 0x%02X at bc=%d not yet supported", op, pos));
    }

    private void emitEpilogue(CodeBuffer cb)
    {
        if (isEntry)
        {
            cb.emit(A64.ret());
            return;
        }
        if (saveLR)
        {
            cb.emit(A64.ldrx(30, 31, 0));
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64.ldrx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        if (frameSize > 0)
        {
            cb.emit(A64.addImm(31, 31, frameSize));
        }
        cb.emit(A64.ret());
    }

    // ----- opcode dispatch -------------------------------------------------
    private int step(int op, byte[] code, int pos, CodeBuffer cb)
    {
        switch (op)
        {
        case 0x00 ->
        {
            return 1;
        }  // nop
        case 0xB1 ->
        {
            emitEpilogue(cb);
            return 1;
        }  // return
        case 0xAC, 0xAD, 0xB0 ->
        {
            cb.emit(A64.movReg(0, popReg()));
            emitEpilogue(cb);
            return 1;
        }  // ireturn/lreturn/areturn

        case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 ->
        {
            loadConst(cb, op - 0x03);
            return 1;
        }
        case 0x01, 0x09 ->
        {
            loadConst(cb, 0);    // aconst_null (null == 0) / lconst_0
            return 1;
        }
        case 0x0A ->
        {
            loadConst(cb, 1);
            return 1;
        }
        case 0x10 ->
        {
            loadConst(cb, (byte) code[pos + 1]);
            return 2;
        }
        case 0x11 ->
        {
            loadConst(cb, (short) u2(code, pos + 1));
            return 3;
        }
        case 0x12 ->
        {
            ldc(cb, code[pos + 1] & 0xFF);
            return 2;
        }
        case 0x13 ->
        {
            ldc(cb, u2(code, pos + 1));
            return 3;
        }
        case 0x14 ->
        {
            loadConst(cb, ClassReader.longValue(classBytes, cpOff, u2(code, pos + 1)));
            return 3;
        }

        case 0x15, 0x16, 0x19 ->
        {
            load(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // iload/lload/aload
        case 0x1A, 0x1B, 0x1C, 0x1D ->
        {
            load(cb, op - 0x1A);
            return 1;
        }  // iload_0..3
        case 0x1E, 0x1F, 0x20, 0x21 ->
        {
            load(cb, op - 0x1E);
            return 1;
        }  // lload_0..3
        case 0x2A, 0x2B, 0x2C, 0x2D ->
        {
            load(cb, op - 0x2A);
            return 1;
        }  // aload_0..3

        case 0x36, 0x37, 0x3A ->
        {
            store(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // istore/lstore/astore
        case 0x3B, 0x3C, 0x3D, 0x3E ->
        {
            store(cb, op - 0x3B);
            return 1;
        }  // istore_0..3
        case 0x3F, 0x40, 0x41, 0x42 ->
        {
            store(cb, op - 0x3F);
            return 1;
        }  // lstore_0..3
        case 0x4B, 0x4C, 0x4D, 0x4E ->
        {
            store(cb, op - 0x4B);
            return 1;
        }  // astore_0..3
        case 0x57 ->
        {
            popReg();
            return 1;
        }  // pop (discard result)
        case 0x59 ->
        {
            dup(cb);
            return 1;
        }  // dup
        case 0x84 ->
        {
            iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]);
            return 3;
        }

        // ---- array element load/store (base + index<<scale) ----
        case 0x33 ->
        {
            arrayLoad(cb, 0);
            return 1;
        }  // baload  (byte, zero-ext)
        case 0x34 ->
        {
            arrayLoad(cb, 1);
            return 1;
        }  // caload  (char, zero-ext)
        case 0x55 ->
        {
            arrayStore(cb, 1);
            return 1;
        }  // castore
        case 0x2E ->
        {
            arrayLoad(cb, 2);
            return 1;
        }  // iaload  (int, sign-ext)
        case 0x2F ->
        {
            arrayLoad(cb, 3);
            return 1;
        }  // laload  (long)
        case 0x32 ->
        {
            arrayLoad(cb, 3);
            return 1;
        }  // aaload  (ref)
        case 0x54 ->
        {
            arrayStore(cb, 0);
            return 1;
        }  // bastore
        case 0x4F ->
        {
            arrayStore(cb, 2);
            return 1;
        }  // iastore
        case 0x50 ->
        {
            arrayStore(cb, 3);
            return 1;
        }  // lastore
        case 0x53 ->
        {
            arrayStore(cb, 3);
            return 1;
        }  // aastore
        case 0xBE ->
        {
            arrayLength(cb);
            return 1;
        }  // arraylength

        case 0x60, 0x61 ->
        {
            binop(cb, Bin.ADD);
            return 1;
        }
        case 0x64, 0x65 ->
        {
            binop(cb, Bin.SUB);
            return 1;
        }
        case 0x68, 0x69 ->
        {
            binop(cb, Bin.MUL);
            return 1;
        }
        case 0x6C, 0x6D ->
        {
            binop(cb, Bin.DIV);
            return 1;
        }
        case 0x74, 0x75 ->
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64.subReg(r, A64.XZR, r));
            return 1;
        }  // ineg/lneg
        case 0x78, 0x79 ->
        {
            binop(cb, Bin.SHL);
            return 1;
        }  // ishl/lshl
        case 0x7A, 0x7B ->
        {
            binop(cb, Bin.ASR);
            return 1;
        }  // ishr/lshr
        case 0x7C, 0x7D ->
        {
            binop(cb, Bin.LSR);
            return 1;
        }  // iushr/lushr
        case 0x7E, 0x7F ->
        {
            binop(cb, Bin.AND);
            return 1;
        }
        case 0x80, 0x81 ->
        {
            binop(cb, Bin.OR);
            return 1;
        }  // ior/lor
        case 0x82, 0x83 ->
        {
            binop(cb, Bin.XOR);
            return 1;
        }  // ixor/lxor
        case 0x94 ->
        {
            lcmp(cb);
            return 1;
        }  // lcmp -> -1/0/1

        case 0x85, 0x88 ->
        {
            return 1;
        }  // i2l/l2i: no-op (values fit 64-bit)
        case 0x91 ->
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64.sxtb(r, r));
            return 1;
        }  // i2b
        case 0x92 ->
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64.uxth(r, r));
            return 1;
        }  // i2c
        case 0x93 ->
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64.sxth(r, r));
            return 1;
        }  // i2s

        case 0x99, 0xC6 ->
        {
            branchZero(cb, code, pos, true);
            return 3;
        }  // ifeq / ifnull (null == 0)
        case 0x9A, 0xC7 ->
        {
            branchZero(cb, code, pos, false);
            return 3;
        }  // ifne / ifnonnull
        case 0x9B ->
        {
            branchCmpZero(cb, code, pos, A64.LT);
            return 3;
        }
        case 0x9C ->
        {
            branchCmpZero(cb, code, pos, A64.GE);
            return 3;
        }
        case 0x9D ->
        {
            branchCmpZero(cb, code, pos, A64.GT);
            return 3;
        }
        case 0x9E ->
        {
            branchCmpZero(cb, code, pos, A64.LE);
            return 3;
        }
        case 0x9F ->
        {
            branchCmp(cb, code, pos, A64.EQ);
            return 3;
        }
        case 0xA0 ->
        {
            branchCmp(cb, code, pos, A64.NE);
            return 3;
        }
        case 0xA1 ->
        {
            branchCmp(cb, code, pos, A64.LT);
            return 3;
        }
        case 0xA2 ->
        {
            branchCmp(cb, code, pos, A64.GE);
            return 3;
        }
        case 0xA3 ->
        {
            branchCmp(cb, code, pos, A64.GT);
            return 3;
        }
        case 0xA4 ->
        {
            branchCmp(cb, code, pos, A64.LE);
            return 3;
        }
        case 0xA7 ->
        {
            int target = pos + s2(code, pos + 1);
            int w = cb.emit(A64.b(0));
            addFixup(w, target, FIX_B, 0);
            recordDepth(target);
            return 3;
        }

        case 0xB2 ->
        {
            getstatic(cb, u2(code, pos + 1));
            return 3;
        }
        case 0xB3 ->
        {
            putstatic(cb, u2(code, pos + 1));
            return 3;
        }
        case 0xB4 ->
        {
            getfield(cb, u2(code, pos + 1));
            return 3;
        }
        case 0xB5 ->
        {
            putfield(cb, u2(code, pos + 1));
            return 3;
        }
        case 0xB6 ->
        {
            lowerInvokeVirtual(u2(code, pos + 1), cb);
            return 3;
        }
        case 0xB7 ->
        {
            lowerInvokeSpecial(u2(code, pos + 1), cb);
            return 3;
        }
        case 0xB8 ->
        {
            lowerInvokeStatic(u2(code, pos + 1), cb);
            return 3;
        }
        case 0xB9 ->
        {
            lowerInvokeInterface(u2(code, pos + 1), cb);
            return 5;
        }  // invokeinterface
        case 0xBB ->
        {
            lowerNew(u2(code, pos + 1), cb);
            return 3;
        }
        case 0xBC ->
        {
            lowerNewArray(code[pos + 1] & 0xFF, cb);
            return 2;
        }
        case 0xBF ->
        {
            athrow(cb, pos);
            return 1;
        }  // athrow
        case 0xC0 ->
        {
            typeCheck(cb, u2(code, pos + 1), Symbols.CHECK_CAST);
            return 3;
        }  // checkcast
        case 0xC1 ->
        {
            typeCheck(cb, u2(code, pos + 1), Symbols.INSTANCE_OF);
            return 3;
        }  // instanceof

        default -> throw unsupportedOpcode(op, pos);
        }
    }

    private enum Bin { ADD, SUB, MUL, DIV, AND, OR, XOR, SHL, ASR, LSR }

    // ----- register allocation ---------------------------------------------
    private int pushReg()
    {
        if (sp >= OP_MAX)
        {
            throw new IllegalStateException("operand stack too deep");
        }
        return OP_BASE + sp++;
    }
    private int popReg()
    {
        if (sp <= 0)
        {
            throw new IllegalStateException("operand stack underflow");
        }
        return OP_BASE + --sp;
    }
    /** Register holding local {@code slot}. Only valid when {@link #inReg} says so. */
    private int localReg(int slot)
    {
        if (slot < 0 || slot >= LOC_MAX)
        {
            throw bad("local slot out of range", slot);
        }
        return LOC_BASE + slot;
    }
    /** True if this local lives in a register rather than the frame. */
    private static boolean inReg(int slot)
    {
        return slot < LOC_MAX;
    }
    /** Frame offset of an overflow local (slot &gt;= LOC_MAX). */
    private int localMem(int slot)
    {
        return overflowBase + (slot - LOC_MAX) * 8;
    }
    private void expectEmpty(String where)
    {
        if (sp != 0)
        {
            throw bad("operand stack not empty at " + where, sp);
        }
    }

    private void loadConst(CodeBuffer cb, long v)
    {
        cb.emitAll(A64.loadImm64(pushReg(), v));
    }
    private void load(CodeBuffer cb, int slot)
    {
        int r = pushReg();
        cb.emit(inReg(slot) ? A64.movReg(r, localReg(slot))
                            : A64.ldrx(r, 31, localMem(slot)));
    }
    private void store(CodeBuffer cb, int slot)
    {
        int r = popReg();
        cb.emit(inReg(slot) ? A64.movReg(localReg(slot), r)
                            : A64.strx(r, 31, localMem(slot)));
    }

    private void iinc(CodeBuffer cb, int slot, int delta)
    {
        if (inReg(slot))
        {
            int r = localReg(slot);
            cb.emit(delta >= 0 ? A64.addImm(r, r, delta) : A64.subImm(r, r, -delta));
            return;
        }
        int r = SCRATCH;                                   // read-modify-write in the frame
        cb.emit(A64.ldrx(r, 31, localMem(slot)));
        cb.emit(delta >= 0 ? A64.addImm(r, r, delta) : A64.subImm(r, r, -delta));
        cb.emit(A64.strx(r, 31, localMem(slot)));
    }

    private void binop(CodeBuffer cb, Bin kind)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(switch (kind)
        {
        case ADD -> A64.addReg(r, a, b);
        case SUB -> A64.subReg(r, a, b);
        case MUL -> A64.mulReg(r, a, b);
        case DIV -> A64.sdivReg(r, a, b);
        case AND -> A64.andReg(r, a, b);
        case OR  -> A64.orrReg(r, a, b);
        case XOR -> A64.eorReg(r, a, b);
        case SHL -> A64.lslv(r, a, b);
        case ASR -> A64.asrv(r, a, b);
        case LSR -> A64.lsrv(r, a, b);
        });
    }

    private void dup(CodeBuffer cb)
    {
        int top = OP_BASE + sp - 1;
        cb.emit(A64.movReg(pushReg(), top));
    }

    /** lcmp: push -1/0/1 for a&lt;b / a==b / a&gt;b (usually consumed by a following if). */
    private void lcmp(CodeBuffer cb)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(A64.cmpReg(a, b));
        cb.emit(A64.cset(r, A64.GT));            // a>b -> 1, else 0
        cb.emit(A64.csinv(r, r, A64.XZR, A64.GE)); // a<b -> -1, else keep
    }

    // ----- static fields: absolute address in the image statics area --------
    private void getstatic(CodeBuffer cb, int cpIndex)
    {
        int r = pushReg();
        symbols.staticField(cb, r, cpIndex);
        cb.emit(A64.ldrx(r, r, 0));
    }
    private void putstatic(CodeBuffer cb, int cpIndex)
    {
        int v = popReg();
        symbols.staticField(cb, 16, cpIndex);
        cb.emit(A64.strx(v, 16, 0));
    }

    /** Load the synthetic $exception static slot into {@code destReg}. */
    private void emitLoadException(CodeBuffer cb, int destReg)
    {
        symbols.exceptionSlot(cb, destReg);
        cb.emit(A64.ldrx(destReg, destReg, 0));
    }
    /** Store {@code valReg} into the synthetic $exception static slot (via x16). */
    private void emitStoreException(CodeBuffer cb, int valReg)
    {
        symbols.exceptionSlot(cb, 16);
        cb.emit(A64.strx(valReg, 16, 0));
    }

    /** instanceof/checkcast: push the target class's Type address, call the VM helper. */
    private void typeCheck(CodeBuffer cb, int classIndex, int helper)
    {
        int r = pushReg();                                       // objref stays below; push targetType addr
        symbols.type(cb, r, classIndex);
        emitCall(cb, 2, true, false, SYM_HELPER, helper);        // (objref, targetType) -> result
    }

    // ----- object fields (8-byte slots; see objectmodel.ObjectModel) --------
    private void getfield(CodeBuffer cb, int cpIndex)
    {
        int off = symbols.fieldOffset(cpIndex);
        int obj = popReg();
        int r = pushReg();
        cb.emit(A64.ldrx(r, obj, off));
    }

    private void putfield(CodeBuffer cb, int cpIndex)
    {
        int off = symbols.fieldOffset(cpIndex);
        int val = popReg();
        int obj = popReg();
        cb.emit(A64.strx(val, obj, off));
    }

    // ----- allocation: new -> Heap.alloc(size), store TIB, push ref ---------
    /**
     * {@code new} is a call underneath ({@code Heap.alloc}), so it clobbers the
     * operand registers exactly like any other call — and is spilled around exactly
     * like one. It used to demand an empty operand stack instead, which made
     * ordinary expressions such as {@code f(new X())} or {@code a.b = new X()}
     * uncompilable; that single restriction blocked 14 of BaselineCompiler's own
     * methods from self-hosting (PLAN.md §M5.1).
     *
     * <p>The entry method is the exception: it is frameless (it sets up SP itself),
     * so there is no spill area to use and the old requirement still holds.
     */
    private void lowerNew(int classIndex, CodeBuffer cb)
    {
        if (isEntry)
        {
            expectEmpty("new");                                   // frameless: nowhere to spill
        }
        int size = symbols.objectSize(classIndex);
        cb.emitAll(A64.loadImm64(0, size));                       // x0 = size (Heap.alloc arg)
        spillLive(cb);                                            // Heap.alloc clobbers x9..
        symbols.callHelper(cb, Symbols.HEAP_ALLOC);               // x0 = object base
        reloadLive(cb);
        symbols.tib(cb, 1, classIndex);                           // x1 = &TIB
        cb.emit(A64.strx(1, 0, ObjectModel.TIB_OFFSET));          // header.tib = &TIB
        cb.emit(A64.movReg(pushReg(), 0));                        // push the reference
    }

    // ----- branches --------------------------------------------------------
    private void branchZero(CodeBuffer cb, byte[] code, int pos, boolean eq)
    {
        int v = popReg();
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        addFixup(w, target, eq ? FIX_CBZ : FIX_CBNZ, v);
        recordDepth(target);
    }

    private void branchCmpZero(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int v = popReg();
        cb.emit(A64.cmpImm(v, 0));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        addFixup(w, target, FIX_BCOND, cond);
        recordDepth(target);
    }

    private void branchCmp(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int b = popReg();
        int a = popReg();
        cb.emit(A64.cmpReg(a, b));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64.b(0));
        addFixup(w, target, FIX_BCOND, cond);
        recordDepth(target);
    }

    /** Record the operand-stack depth on the edge into branch target {@code bc}. */
    private void recordDepth(int bc)
    {
        if (bcDepth[bc] < 0)
        {
            bcDepth[bc] = sp;
        }
        else if (bcDepth[bc] != sp)
        {
            throw bad("inconsistent stack depth at bc", bc);
        }
    }

    // ----- calls / intrinsics ----------------------------------------------
    private void lowerInvokeStatic(int cpIndex, CodeBuffer cb)
    {
        if (symbols.isIntrinsicCall(cpIndex))
        {
            lowerIntrinsic(symbols.intrinsicId(cpIndex), cb);
            return;
        }
        lowerCall(cpIndex, cb, false);
    }

    /** Virtual dispatch through the receiver's TIB vtable. Uses x16 (scratch) for the target. */
    private void lowerInvokeVirtual(int cpIndex, CodeBuffer cb)
    {
        int slot = symbols.vtableSlot(cpIndex);
        int nargs = paramCount(cpIndex) + 1;    // receiver + params
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);
        cb.emit(A64.ldrx(16, 0, ObjectModel.TIB_OFFSET));       // x16 = receiver.tib
        cb.emit(A64.ldrx(16, 16, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)))); // x16 = code
        cb.emit(A64.blr(16));
        reloadLive(cb);
        if (returnsValue(cpIndex))
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    /**
     * Interface dispatch: move args to x0.., then inline-search the receiver's
     * itable directory (Type→dir of {interfaceType, itable}) for the target
     * interface's Type, index the itable by the method's slot, and {@code blr}.
     * Uses x16 (target/code), x17 (walker), x9 (temp) — args in x0..x7 untouched.
     */
    private void lowerInvokeInterface(int cpIndex, CodeBuffer cb)
    {
        int slot = symbols.interfaceSlot(cpIndex);
        int nargs = paramCount(cpIndex) + 1;    // receiver + params
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);

        symbols.interfaceType(cb, 16, cpIndex);                                     // x16 = &interfaceType
        cb.emit(A64.ldrx(17, 0, ObjectModel.TIB_OFFSET));                           // x17 = receiver.tib
        cb.emit(A64.ldrx(17, 17, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT))); // x17 = Type
        cb.emit(A64.ldrx(17, 17, ObjectModel.TYPE_ITABLE_DIR_OFFSET));              // x17 = itable dir
        int search = cb.wordCount();
        cb.emit(A64.ldrx(9, 17, ObjectModel.ITABLE_ENTRY_IFACE_OFFSET));            // x9 = entry.interfaceType
        cb.emit(A64.cmpReg(9, 16));
        int beq = cb.emit(A64.bcond(A64.EQ, 0));                                    // found?
        cb.emit(A64.addImm(17, 17, ObjectModel.ITABLE_ENTRY_SIZE));                 // next entry
        cb.emit(A64.b((search - cb.wordCount()) * 4));                              // loop
        int found = cb.wordCount();
        cb.set(beq, A64.bcond(A64.EQ, (found - beq) * 4));
        cb.emit(A64.ldrx(17, 17, ObjectModel.ITABLE_ENTRY_TABLE_OFFSET));          // x17 = itable
        cb.emit(A64.ldrx(16, 17, slot * ObjectModel.WORD));                        // x16 = code addr
        cb.emit(A64.blr(16));
        reloadLive(cb);
        if (returnsValue(cpIndex))
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    private void lowerInvokeSpecial(int cpIndex, CodeBuffer cb)
    {
        if (symbols.isSkippableInit(cpIndex))
        {
            popReg();                                            // super() into a JDK class — discard receiver
            return;
        }
        lowerCall(cpIndex, cb, true);                            // constructor: receiver is arg0
    }

    /**
     * athrow: stash the exception, then for each covering try/catch entry test the
     * thrown type against the catch type ({@code VM.instanceOf}) and branch to the
     * handler (exception on the operand stack). No matching handler in this method
     * halts — cross-method unwinding is not implemented yet.
     */
    private void athrow(CodeBuffer cb, int pos)
    {
        int athrowStart = cb.wordCount();
        emitStoreException(cb, popReg());                       // $exception = ref
        for (int i = 0; i < exCount; i++)
        {
            if (exStartPc[i] > pos || pos >= exEndPc[i])
            {
                continue;
            }
            if (exCatchType[i] == 0)
            {
                emitCatch(cb, exHandlerPc[i]);    // finally / catch-all
                return;
            }
            int obj = pushReg();
            emitLoadException(cb, obj);
            int t = pushReg();
            symbols.type(cb, t, exCatchType[i]);
            emitCall(cb, 2, true, false, SYM_HELPER, Symbols.INSTANCE_OF);  // (exc, catchType) -> int
            int cond = popReg();
            int skip = cb.emit(A64.cbz(cond, 0));
            emitCatch(cb, exHandlerPc[i]);                       // matched
            cb.set(skip, A64.cbz(cond, (cb.wordCount() - skip) * 4));
        }
        // no local handler: unwind the stack — unwind(exc, thisPC, SP)
        int exc = pushReg();
        emitLoadException(cb, exc);
        int pc = pushReg();
        cb.patchAddr(cb.reserveAddr(pc), pc, cb.pcAt(athrowStart)); // a PC inside this method
        int sp = pushReg();
        cb.emit(A64.movFromSp(sp));
        emitCall(cb, 3, false, false, SYM_HELPER, Symbols.UNWIND);
        emitHalt(cb);                                            // unwind never returns
    }

    /** Push the pending exception and branch to a handler (which expects it at depth 1). */
    private void emitCatch(CodeBuffer cb, int handlerPc)
    {
        emitLoadException(cb, pushReg());
        int w = cb.emit(A64.b(0));
        addFixup(w, handlerPc, FIX_B, 0);
        recordDepth(handlerPc);
        sp = 0;                                                  // fall-through (next check) resumes empty
    }

    private void emitHalt(CodeBuffer cb)
    {
        int h = cb.emit(A64.wfe());
        cb.emit(A64.b((h - cb.wordCount()) * 4));                // spin
    }

    /** Magic.gc(): spill x19..x28 (+LR) so live refs are scannable, call the collector, restore. */
    private void lowerGc(CodeBuffer cb)
    {
        int frame = 96;                                          // 10 locals (80) + LR (8), 16-aligned
        cb.emit(A64.subImm(31, 31, frame));
        cb.emit(A64.strx(30, 31, 80));                          // save LR (we make a call)
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64.strx(19 + i, 31, i * 8));    // spill x19..x28
        }
        cb.emit(A64.movFromSp(0));                              // x0 = scanFrom (bottom of spilled regs)
        symbols.callHelper(cb, Symbols.GC_COLLECT);
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64.ldrx(19 + i, 31, i * 8));    // restore
        }
        cb.emit(A64.ldrx(30, 31, 80));
        cb.emit(A64.addImm(31, 31, frame));
    }

    /** A real call: args to x0.. (receiver first if any), BL to a cp method, result from x0. */
    private void lowerCall(int cpIndex, CodeBuffer cb, boolean hasReceiver)
    {
        emitCall(cb, paramCount(cpIndex), returnsValue(cpIndex), hasReceiver, SYM_CP, cpIndex);
    }

    /** Parameter count of the {@code *ref} at cp index {@code refCp} (each = one arg register). */
    private int paramCount(int refCp)
    {
        return ClassReader.descParamCount(classBytes, ClassReader.refDescOff(classBytes, cpOff, refCp));
    }

    /** Whether the {@code *ref} at cp index {@code refCp} returns a value (non-void). */
    private boolean returnsValue(int refCp)
    {
        return ClassReader.descReturnKind(classBytes, ClassReader.refDescOff(classBytes, cpOff, refCp)) != 'V';
    }

    /**
     * The calling convention around a symbolic call: move args to x0.., spill live
     * operands, delegate the BL to the {@link Symbols} seam, reload, land the result.
     * {@code symKind}/{@code symArg} name the target (a cp index, or a helper id).
     * The signature is given as {@code paramCount}/{@code returnsValue} rather than a
     * descriptor string so helper calls need no String literals (metal has no ldc-string).
     */
    private void emitCall(CodeBuffer cb, int paramCount, boolean returnsValue, boolean hasReceiver, int symKind, int symArg)
    {
        int nargs = paramCount + (hasReceiver ? 1 : 0);
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();    // src[0] = last arg (top of stack)
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64.movReg(nargs - 1 - k, src[k]));    // -> x(argIndex)
        }
        spillLive(cb);                                           // preserve operand values below the args
        if (symKind == SYM_CP)
        {
            symbols.call(cb, symArg);
        }
        else
        {
            symbols.callHelper(cb, symArg);
        }
        reloadLive(cb);
        if (returnsValue)
        {
            cb.emit(A64.movReg(pushReg(), 0));
        }
    }

    // ----- arrays: [header][length @16][elements @24], element = base + index<<scale -----
    private void lowerNewArray(int atype, CodeBuffer cb)
    {
        loadConst(cb, arrayElemSize(atype));                     // push elemSize
        emitCall(cb, 2, true, false, SYM_HELPER, Symbols.HEAP_ALLOC_ARRAY); // (length,elemSize)->ref
    }

    private void arrayLength(CodeBuffer cb)
    {
        int arr = popReg();
        int r = pushReg();
        cb.emit(A64.ldrx(r, arr, ObjectModel.ARRAY_LENGTH_OFFSET));
    }

    private void arrayLoad(CodeBuffer cb, int scale)
    {
        int index = popReg(), arr = popReg(), r = pushReg();     // r == arr's register
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));          // arr = &elem[index]
        cb.emit(scale == 0 ? A64.ldrb(r, arr, 0)                 // byte (zero-ext, ASCII)
                : scale == 1 ? A64.ldrh(r, arr, 0)               // char (zero-ext — unsigned)
                : scale == 2 ? A64.ldrsw(r, arr, 0)              // int (sign-ext)
                : A64.ldrx(r, arr, 0));                          // long / ref
    }

    private void arrayStore(CodeBuffer cb, int scale)
    {
        int val = popReg();
        int index = popReg();
        int arr = popReg();
        cb.emit(A64.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64.addRegLsl(arr, arr, index, scale));
        cb.emit(scale == 0 ? A64.strb(val, arr, 0)
                : scale == 1 ? A64.strh(val, arr, 0)             // char/short
                : scale == 2 ? A64.strw(val, arr, 0)
                : A64.strx(val, arr, 0));
    }

    /** newarray atype -> element size in bytes (JVMS Table 6.5.newarray-A). */
    private static int arrayElemSize(int atype)
    {
        return switch (atype)
        {
        case 4, 8 -> 1;              // boolean, byte
        case 5, 9 -> 2;              // char, short
        case 6, 10 -> 4;             // float, int
        case 7, 11 -> 8;             // double, long
        default -> throw unsupported("bad newarray atype", atype);
        };
    }

    /** Spill operand-stack values (x9..) to the frame so a call can't clobber them. */
    private void spillLive(CodeBuffer cb)
    {
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64.strx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }
    private void reloadLive(CodeBuffer cb)
    {
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64.ldrx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }

    /**
     * Emit a {@code magic/Magic} intrinsic, dispatched by its {@link Intrinsics} id
     * (resolved per world behind {@link Symbols#intrinsicId}). Branching on an int
     * rather than a {@code String} key keeps this compilable on metal.
     */
    private void lowerIntrinsic(int id, CodeBuffer cb)
    {
        switch (id)
        {
        case Intrinsics.WFE -> cb.emit(A64.wfe());
        case Intrinsics.ISB -> cb.emit(A64.isb());
        case Intrinsics.DSB -> cb.emit(A64.dsb());
        case Intrinsics.GC -> lowerGc(cb);
        case Intrinsics.CALL0 ->
        {
            int addr = popReg();
            cb.emit(A64.blr(addr));
            cb.emit(A64.movReg(pushReg(), 0));
        }
        case Intrinsics.CALL2 ->                             // addr, a->x0, b->x1, blr, result x0
        {
            int b = popReg();
            int a = popReg();
            int addr = popReg();
            cb.emit(A64.movReg(16, addr));
            cb.emit(A64.movReg(0, a));
            cb.emit(A64.movReg(1, b));
            cb.emit(A64.blr(16));
            cb.emit(A64.movReg(pushReg(), 0));
        }
        case Intrinsics.ERET -> cb.emit(A64.eret());
        case Intrinsics.DROP_TO_EL1 -> lowerDropToEL1(cb);

        case Intrinsics.WRITE_HCR_EL2 -> cb.emit(A64.msr(A64.HCR_EL2, popReg()));
        case Intrinsics.WRITE_CPTR_EL2 -> cb.emit(A64.msr(A64.CPTR_EL2, popReg()));
        case Intrinsics.WRITE_CNTHCTL_EL2 -> cb.emit(A64.msr(A64.CNTHCTL_EL2, popReg()));
        case Intrinsics.WRITE_CNTVOFF_EL2 -> cb.emit(A64.msr(A64.CNTVOFF_EL2, popReg()));
        case Intrinsics.WRITE_SCTLR_EL1 -> cb.emit(A64.msr(A64.SCTLR_EL1, popReg()));
        case Intrinsics.WRITE_SPSR_EL2 -> cb.emit(A64.msr(A64.SPSR_EL2, popReg()));
        case Intrinsics.WRITE_ELR_EL2 -> cb.emit(A64.msr(A64.ELR_EL2, popReg()));
        case Intrinsics.WRITE_CPACR_EL1 -> cb.emit(A64.msr(A64.CPACR_EL1, popReg()));
        case Intrinsics.WRITE_SP -> cb.emit(A64.movToSp(popReg()));
        case Intrinsics.READ_SP -> cb.emit(A64.movFromSp(pushReg()));
        case Intrinsics.RESUME ->                            // exc->x9, SP=sp, br pc (no return)
        {
            int exc = popReg();
            int spv = popReg();
            int pc = popReg();
            cb.emit(A64.movReg(16, pc));                     // target -> scratch (x9 gets clobbered next)
            cb.emit(A64.movReg(9, exc));                     // exception -> handler's stack slot
            cb.emit(A64.movToSp(spv));
            cb.emit(A64.br(16));
        }

        case Intrinsics.STORE32 ->
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64.strw(val, addr, 0));
        }
        case Intrinsics.STORE8 ->
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64.strb(val, addr, 0));
        }
        case Intrinsics.STORE64 ->
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64.strx(val, addr, 0));
        }
        case Intrinsics.LOAD32 ->
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64.ldrw(r, addr, 0));
        }
        case Intrinsics.LOAD8 ->
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64.ldrb(r, addr, 0));
        }
        case Intrinsics.LOAD64 ->
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64.ldrx(r, addr, 0));
        }

        case Intrinsics.BYTES ->
        {
            /* no-op: the operand is already an interned byte[] ref */;
        }

        default -> throw unsupported("unknown intrinsic id ", id);
        }
    }

    private void lowerDropToEL1(CodeBuffer cb)
    {
        expectEmpty("dropToEL1");
        cb.emit(A64.mrs(0, A64.CurrentEL));
        int tbz = cb.emit(A64.tbz(0, 3, 0));
        set64(cb, 0, 0x8000_0000L);
        cb.emit(A64.msr(A64.HCR_EL2, 0));
        set64(cb, 0, 0x33FFL);
        cb.emit(A64.msr(A64.CPTR_EL2, 0));
        set64(cb, 0, 0x3L);
        cb.emit(A64.msr(A64.CNTHCTL_EL2, 0));
        cb.emit(A64.msr(A64.CNTVOFF_EL2, A64.XZR));
        set64(cb, 0, 0x30D0_0800L);
        cb.emit(A64.msr(A64.SCTLR_EL1, 0));
        set64(cb, 0, 0x3C5L);
        cb.emit(A64.msr(A64.SPSR_EL2, 0));
        int elr = cb.reserveAddr(0);
        cb.emit(A64.msr(A64.ELR_EL2, 0));
        cb.emit(A64.eret());
        int cont = cb.wordCount();
        cb.set(tbz, A64.tbz(0, 3, (cont - tbz) * 4));
        cb.patchAddr(elr, 0, cb.pcAt(cont));
    }

    private boolean isNonLeaf(byte[] code)
    {
        int pos = 0;
        while (pos < code.length)
        {
            int op = code[pos] & 0xFF;
            if (op == 0xBB || op == 0xBC || op == 0xB6 || op == 0xB9 || op == 0xBF || op == 0xC0 || op == 0xC1)
            {
                return true;    // new/newarray/invokevirtual/invokeinterface/athrow/checkcast/instanceof
            }
            if (op == 0xB8)                                      // invokestatic
            {
                int idx = u2(code, pos + 1);
                if (!symbols.isIntrinsicCall(idx) || symbols.intrinsicEmitsCall(idx))
                {
                    return true;    // real call, or an intrinsic that emits BL/BLR
                }
            }
            if (op == 0xB7)                                      // invokespecial
            {
                if (!symbols.isSkippableInit(u2(code, pos + 1)))
                {
                    return true;
                }
            }
            pos += opLen(op, code, pos);
        }
        return false;
    }

    /** Byte length of an opcode — only the ones this compiler emits appear here. */
    private static int opLen(int op, byte[] code, int pos)
    {
        return switch (op)
        {
        case 0x10, 0x12, 0x15, 0x16, 0x19, 0x36, 0x37, 0x3A, 0xBC -> 2; // …/aload/istore/lstore/astore/newarray
        case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
        0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA7,
        0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB,
        0xC0, 0xC1 -> 3;                                 // get/putstatic/field/invoke*/new/checkcast/instanceof
        case 0xB9 -> 5;                                       // invokeinterface (idx, count, 0)
        default -> 1;
        };
    }

    private static void set64(CodeBuffer cb, int rd, long v)
    {
        cb.emitAll(A64.loadImm64(rd, v));
    }

    /** ldc/ldc_w: int constant, or a String literal interned as a byte[] object. */
    private void ldc(CodeBuffer cb, int cpIndex)
    {
        if (cpTag[cpIndex] == ClassReader.TAG_STRING)
        {
            int r = pushReg();
            symbols.string(cb, r, cpIndex);
        }
        else if (cpTag[cpIndex] == ClassReader.TAG_INTEGER)
        {
            loadConst(cb, ClassReader.intValue(classBytes, cpOff, cpIndex));
        }
        else
        {
            throw unsupported("ldc of unsupported constant #", cpIndex);
        }
    }

    private static int u2(byte[] b, int i)
    {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }
    private static int s2(byte[] b, int i)
    {
        return (short) u2(b, i);
    }

    // ---- constructor + method-body compile entry (the core/driver split, M5.4.5) ----

    public Baseline(byte[] classBytes, int[] cpOff, int[] cpTag, Symbols symbols)
    {
        this.classBytes = classBytes;
        this.cpOff = cpOff;
        this.cpTag = cpTag;
        this.symbols = symbols;
    }

    /** Set the method's exception table (parallel arrays) before {@link #compileBody}. */
    public void setExceptionTable(int[] startPc, int[] endPc, int[] handlerPc, int[] catchType, int n)
    {
        this.exStartPc = startPc;
        this.exEndPc = endPc;
        this.exHandlerPc = handlerPc;
        this.exCatchType = catchType;
        this.exCount = n;
    }

    // Handler machine-PC ranges (bytecode -> word index), filled by compileBody for
    // the driver to zip with catch classes into its HandlerRange table.
    private int[] hStartW;
    private int[] hEndW;
    private int[] hHandlerW;

    public int frameSize() { return frameSize; }
    public int handlerCount() { return exCount; }
    public int handlerStartWord(int i) { return hStartW[i]; }
    public int handlerEndWord(int i) { return hEndW[i]; }
    public int handlerWord(int i) { return hHandlerW[i]; }

    /**
     * Compile one method body to A64 words at absolute {@code base}; {@code isEntry}
     * means frameless. Frame size and handler word-ranges are read back via the
     * accessors above. The exception table must already be set.
     */
    public int[] compileBody(byte[] code, int descOff, boolean isStatic, int maxLocals, long base, boolean isEntry)
    {
        this.isEntry = isEntry;
        this.maxLocals = maxLocals;
        this.nonLeaf = isNonLeaf(code);
        this.saveLR = !isEntry && nonLeaf;
        // Locals live in callee-saved x19..x28; a method needing more keeps the
        // overflow in the frame (see localMem). Slots 0..LOC_MAX-1 stay in registers.
        this.regLocals = Math.min(maxLocals, LOC_MAX);
        this.overflowLocals = Math.max(0, maxLocals - LOC_MAX);
        // frame: [LR?][saved local regs][overflow locals][operand spill area]
        this.localSaveBase = saveLR ? 8 : 0;
        this.overflowBase = localSaveBase + regLocals * 8;
        this.spillBase = overflowBase + overflowLocals * 8;
        int spillWords = (!isEntry && nonLeaf) ? OP_MAX : 0;
        int savedWords = (saveLR ? 1 : 0) + regLocals + overflowLocals + spillWords;
        this.frameSize = isEntry ? 0 : A64.align16(savedWords * 8);
        sp = 0;

        CodeBuffer cb = new CodeBuffer(base);
        if (!isEntry)
        {
            emitPrologue(cb, descOff, isStatic);
        }

        int[] bcToWord = new int[code.length];
        bcDepth = new int[code.length];
        for (int k = 0; k < code.length; k++)
        {
            bcToWord[k] = -1;
            bcDepth[k] = -1;
        }
        for (int k = 0; k < exCount; k++)   // handler entry: exception on stack (depth 1)
        {
            bcDepth[exHandlerPc[k]] = 1;
        }
        int pos = 0;
        while (pos < code.length)
        {
            bcToWord[pos] = cb.wordCount();
            if (bcDepth[pos] >= 0)
            {
                sp = bcDepth[pos];    // merge point: adopt the branch-edge depth
            }
            int op = code[pos] & 0xFF;
            pos += step(op, code, pos, cb);
        }

        for (int fi = 0; fi < fixupCount; fi++)
        {
            Fixup f = fixups[fi];
            int target = bcToWord[f.targetBc];
            if (target < 0)
            {
                throw bad("branch to non-instruction bc", f.targetBc);
            }
            cb.set(f.wordIndex, encodeBranch(f, (target - f.wordIndex) * 4));
        }
        int codeWords = cb.wordCount();
        hStartW = new int[exCount];
        hEndW = new int[exCount];
        hHandlerW = new int[exCount];
        for (int k = 0; k < exCount; k++)
        {
            hStartW[k] = bcToWord[exStartPc[k]];
            hEndW[k] = exEndPc[k] < code.length ? bcToWord[exEndPc[k]] : codeWords;
            hHandlerW[k] = bcToWord[exHandlerPc[k]];
        }
        return cb.toWords();
    }

    // ----- prologue / epilogue --------------------------------------------
    private void emitPrologue(CodeBuffer cb, int descOff, boolean isStatic)
    {
        if (frameSize > 0)
        {
            cb.emit(A64.subImm(31, 31, frameSize));    // sub sp, sp, #frame
        }
        if (saveLR)
        {
            cb.emit(A64.strx(30, 31, 0));    // str x30, [sp]
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64.strx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        // instance methods receive `this` as x0 -> slot 0; each parameter is one
        // argument register (long/double included), stepping its local slots wide.
        int arg = 0;
        int slot = 0;
        if (!isStatic)
        {
            cb.emit(A64.movReg(localReg(0), 0));
            arg = 1;
            slot = 1;
        }
        int p = descOff + 2 + 1;                         // past u2 length and '('
        while (ClassReader.u1(classBytes, p) != ')')
        {
            cb.emit(inReg(slot) ? A64.movReg(localReg(slot), arg)
                                : A64.strx(arg, 31, localMem(slot)));
            arg++;
            int q = p;
            while (ClassReader.u1(classBytes, q) == '[')  // array prefixes fold into the element
            {
                q++;
            }
            int elem = ClassReader.u1(classBytes, q);
            slot += (elem == 'J' || elem == 'D') ? 2 : 1; // matches the old paramTypes fold
            if (elem == 'L')
            {
                while (ClassReader.u1(classBytes, q) != ';')
                {
                    q++;
                }
            }
            p = q + 1;
        }
    }
}
