package compiler;

import asm.A64Enc;
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
    // #43 operand-stack spill: a pre-pass (computeDepths) finds the ACTUAL peak depth; when it exceeds OP_MAX
    // the operand stack becomes a circular register window (slot i -> x(OP_BASE + i%OP_MAX)) backed by frame
    // memory at opStackBase (regHolds[r] = the slot physical reg r holds, -1 = none). Shallow methods
    // (peak <= OP_MAX -- including any with a large DECLARED max_stack they never reach) compile byte-identically.
    private static final int OPSTACK_MARGIN = 4;   // extra deep-spill slots for exception-search temporaries
    private int maxActualDepth;
    private int[] reachDepth;     // computeDepths result: depth[pc] >= 0 iff pc is reachable (else dead code)
    private boolean[] wideTop;    // computeDepths result: the top operand entering pc is a long/double (category-2)
    private int curPos;           // bytecode offset of the op currently being lowered (for wideTop lookup)
    // computeDepths worklist state, held as fields so the seed helpers need not pass depth/mask/work as arguments.
    // This keeps this hot pre-pass register-only (peak <= OP_MAX) instead of spilling operands. NOTE: passing them
    // as params instead is also correct now -- the loadGuest hang this once masked was a `long[]`/`double[]`
    // PARAMETER bug in emitPrologue (a [J/[D param counted as 2 local slots), fixed there; it was never a
    // deep-operand-spill defect (the spill path is exercised correctly by many deep methods).
    private int[] preDepth;       // depth[pc] entering each pc (>=0 reachable)
    private long[] preMask;       // wide-mask entering each pc (bit i = slot i is long/double)
    private int[] preWork;        // worklist scratch
    private int opStackSlots;     // deep: sized operand-spill area (maxActualDepth + margin)
    private boolean deepStack;
    private int[] regHolds = new int[OP_MAX];
    private int[] savedHolds = new int[OP_MAX];   // regHolds snapshot around an off-path (skipped) throw block
    private int opStackBase;
    private CodeBuffer curCb;     // the body's CodeBuffer, so push/pop can emit spill/reload
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
        // Bind the new Fixup to a local before the array store: writing
        // fixups[fixupCount] = new Fixup(a,b,c,d) directly keeps the array ref and
        // index on the operand stack across the 4-arg constructor, peaking at depth 8
        // — one past this compiler's 7 operand registers (OP_MAX). The local keeps it
        // self-compilable.
        Fixup f = new Fixup(wordIndex, targetBc, kind, arg);
        fixups[fixupCount] = f;
        fixupCount += 1;
    }

    /** Encode a pending branch now that the distance to its target is known (in words). */
    private static int encodeBranch(Fixup f, int wordOffset)
    {
        if (f.kind == FIX_CBZ)
        {
            return A64Enc.cbz(f.arg, wordOffset);
        }
        if (f.kind == FIX_CBNZ)
        {
            return A64Enc.cbnz(f.arg, wordOffset);
        }
        if (f.kind == FIX_BCOND)
        {
            return A64Enc.bcond(f.arg, wordOffset);
        }
        return A64Enc.b(wordOffset);
    }

    // A compile failure (unsupported bytecode or a broken invariant) is reported
    // through the Symbols seam, not by building a String message or a JDK exception
    // here: String concat lowers to invokedynamic and java/lang exceptions aren't
    // loaded on metal, either of which would keep this core out of its own image.
    // symbols.fail never returns — the writer throws a rich diagnostic, the metal
    // halts — so call sites still need a dead return to satisfy definite assignment.

    private void emitEpilogue(CodeBuffer cb)
    {
        if (isEntry)
        {
            cb.emit(A64Enc.ret());
            return;
        }
        if (saveLR)
        {
            cb.emit(A64Enc.ldrx(30, 31, 0));
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64Enc.ldrx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        if (frameSize > 0)
        {
            cb.emit(A64Enc.addImm(31, 31, frameSize));
        }
        cb.emit(A64Enc.ret());
    }

    // ----- opcode dispatch -------------------------------------------------
    private int step(int op, byte[] code, int pos, CodeBuffer cb)
    {
        if (op == 0x00)
        {
            return 1;
        }  // nop
        else if (op == 0xB1)
        {
            emitEpilogue(cb);
            return 1;
        }  // return
        else if (op == 0xAC || op == 0xAD || op == 0xB0 || op == 0xAE || op == 0xAF)
        {
            cb.emit(A64Enc.movReg(0, popReg()));
            emitEpilogue(cb);
            return 1;
        }  // ireturn/lreturn/areturn/freturn/dreturn (all bit-preserving)

        else if (op == 0x02 || op == 0x03 || op == 0x04 || op == 0x05 || op == 0x06 || op == 0x07 || op == 0x08)
        {
            loadConst(cb, op - 0x03);
            return 1;
        }
        else if (op == 0x01 || op == 0x09)
        {
            loadConst(cb, 0);    // aconst_null (null == 0) / lconst_0
            return 1;
        }
        else if (op == 0x0A)
        {
            loadConst(cb, 1);
            return 1;
        }
        else if (op == 0x0B)
        {
            loadConst(cb, 0x0000_0000L);        // fconst_0 = 0.0f
            return 1;
        }
        else if (op == 0x0C)
        {
            loadConst(cb, 0x3F80_0000L);        // fconst_1 = 1.0f
            return 1;
        }
        else if (op == 0x0D)
        {
            loadConst(cb, 0x4000_0000L);        // fconst_2 = 2.0f
            return 1;
        }
        else if (op == 0x0E)
        {
            loadConst(cb, 0x0000_0000_0000_0000L);   // dconst_0 = 0.0
            return 1;
        }
        else if (op == 0x0F)
        {
            loadConst(cb, 0x3FF0_0000_0000_0000L);   // dconst_1 = 1.0
            return 1;
        }
        else if (op == 0x10)
        {
            // Mask then cast so the sign-extension is an explicit i2b (sxtb): correct and identical under
            // both baload semantics (the JVM's and this compiler's now both sign-extend; the mask+cast is
            // kept so the expression never depends on which one compiled this code).
            loadConst(cb, (byte) (code[pos + 1] & 0xFF));
            return 2;
        }
        else if (op == 0x11)
        {
            loadConst(cb, (short) u2(code, pos + 1));
            return 3;
        }
        else if (op == 0x12)
        {
            ldc(cb, code[pos + 1] & 0xFF);
            return 2;
        }
        else if (op == 0x13)
        {
            ldc(cb, u2(code, pos + 1));
            return 3;
        }
        else if (op == 0x14)
        {
            loadConst(cb, ClassReader.longValue(classBytes, cpOff, u2(code, pos + 1)));
            return 3;
        }

        else if (op == 0x15 || op == 0x16 || op == 0x19 || op == 0x17 || op == 0x18)
        {
            load(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // iload/lload/aload/fload/dload (all bit-preserving)
        else if (op == 0x22 || op == 0x23 || op == 0x24 || op == 0x25)
        {
            load(cb, op - 0x22);
            return 1;
        }  // fload_0..3
        else if (op == 0x26 || op == 0x27 || op == 0x28 || op == 0x29)
        {
            load(cb, op - 0x26);
            return 1;
        }  // dload_0..3
        else if (op == 0x1A || op == 0x1B || op == 0x1C || op == 0x1D)
        {
            load(cb, op - 0x1A);
            return 1;
        }  // iload_0..3
        else if (op == 0x1E || op == 0x1F || op == 0x20 || op == 0x21)
        {
            load(cb, op - 0x1E);
            return 1;
        }  // lload_0..3
        else if (op == 0x2A || op == 0x2B || op == 0x2C || op == 0x2D)
        {
            load(cb, op - 0x2A);
            return 1;
        }  // aload_0..3

        else if (op == 0x36 || op == 0x37 || op == 0x3A || op == 0x38 || op == 0x39)
        {
            store(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // istore/lstore/astore/fstore/dstore (all bit-preserving)
        else if (op == 0x43 || op == 0x44 || op == 0x45 || op == 0x46)
        {
            store(cb, op - 0x43);
            return 1;
        }  // fstore_0..3
        else if (op == 0x47 || op == 0x48 || op == 0x49 || op == 0x4A)
        {
            store(cb, op - 0x47);
            return 1;
        }  // dstore_0..3
        else if (op == 0x3B || op == 0x3C || op == 0x3D || op == 0x3E)
        {
            store(cb, op - 0x3B);
            return 1;
        }  // istore_0..3
        else if (op == 0x3F || op == 0x40 || op == 0x41 || op == 0x42)
        {
            store(cb, op - 0x3F);
            return 1;
        }  // lstore_0..3
        else if (op == 0x4B || op == 0x4C || op == 0x4D || op == 0x4E)
        {
            store(cb, op - 0x4B);
            return 1;
        }  // astore_0..3
        else if (op == 0x57)
        {
            popReg();
            return 1;
        }  // pop (discard result)
        else if (op == 0x58)
        {
            popReg();                                        // pop2: one slot for a category-2 long/double,
            if (!wideTop[curPos]) { popReg(); }              // two for two category-1 values
            return 1;
        }  // pop2
        else if (op == 0x59)
        {
            dup(cb);
            return 1;
        }  // dup
        else if (op == 0x5A)
        {
            dupX1(cb);
            return 1;
        }  // dup_x1: ..,v2,v1 -> ..,v1,v2,v1
        else if (op == 0x5B)
        {
            dupX2(cb);
            return 1;
        }  // dup_x2 (category-1 form): ..,v3,v2,v1 -> ..,v1,v3,v2,v1
        else if (op == 0x5C)
        {
            dup2(cb);
            return 1;
        }  // dup2 (category-1 form: duplicate the top two slots)
        else if (op == 0x84)
        {
            iinc(cb, code[pos + 1] & 0xFF, (byte) (code[pos + 2] & 0xFF));   // mask+cast: explicit sxtb (see bipush)
            return 3;
        }

        // ---- array element load/store (base + index<<scale) ----
        else if (op == 0x33)
        {
            arrayLoad(cb, 0, pos);
            return 1;
        }  // baload  (byte, zero-ext)
        else if (op == 0x34)
        {
            arrayLoad(cb, 1, pos);
            return 1;
        }  // caload  (char, zero-ext)
        else if (op == 0x35)
        {
            arrayLoad(cb, 4, pos);
            return 1;
        }  // saload  (short, sign-ext)
        else if (op == 0x55)
        {
            arrayStore(cb, 1, pos);
            return 1;
        }  // castore
        else if (op == 0x56)
        {
            arrayStore(cb, 1, pos);
            return 1;
        }  // sastore  (short, 16-bit store — same as castore)
        else if (op == 0x2E)
        {
            arrayLoad(cb, 2, pos);
            return 1;
        }  // iaload  (int, sign-ext)
        else if (op == 0x2F)
        {
            arrayLoad(cb, 3, pos);
            return 1;
        }  // laload  (long)
        else if (op == 0x32)
        {
            arrayLoad(cb, 3, pos);
            return 1;
        }  // aaload  (ref)
        else if (op == 0x30)
        {
            arrayLoad(cb, 2, pos);
            return 1;
        }  // faload (4-byte, bit-preserving)
        else if (op == 0x31)
        {
            arrayLoad(cb, 3, pos);
            return 1;
        }  // daload (8-byte)
        else if (op == 0x54)
        {
            arrayStore(cb, 0, pos);
            return 1;
        }  // bastore
        else if (op == 0x4F)
        {
            arrayStore(cb, 2, pos);
            return 1;
        }  // iastore
        else if (op == 0x50)
        {
            arrayStore(cb, 3, pos);
            return 1;
        }  // lastore
        else if (op == 0x53)
        {
            arrayStore(cb, 3, pos);
            return 1;
        }  // aastore
        else if (op == 0x51)
        {
            arrayStore(cb, 2, pos);
            return 1;
        }  // fastore (4-byte)
        else if (op == 0x52)
        {
            arrayStore(cb, 3, pos);
            return 1;
        }  // dastore (8-byte)
        else if (op == 0xBE)
        {
            arrayLength(cb, pos);
            return 1;
        }  // arraylength

        else if (op == 0x60 || op == 0x61)
        {
            binop(cb, BIN_ADD);
            return 1;
        }
        else if (op == 0x64 || op == 0x65)
        {
            binop(cb, BIN_SUB);
            return 1;
        }
        else if (op == 0x68 || op == 0x69)
        {
            binop(cb, BIN_MUL);
            return 1;
        }
        else if (op == 0x6C || op == 0x6D)
        {
            divisorCheck(cb, opSlot(sp - 1), pos);              // idiv/ldiv by 0 -> ArithmeticException
            binop(cb, BIN_DIV);
            return 1;
        }
        else if (op == 0x70 || op == 0x71)
        {
            divisorCheck(cb, opSlot(sp - 1), pos);              // irem/lrem by 0 -> ArithmeticException
            irem(cb);
            return 1;
        }  // irem/lrem
        else if (op == 0x74 || op == 0x75)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.subReg(r, A64Enc.XZR, r));
            return 1;
        }  // ineg/lneg
        else if (op == 0x78 || op == 0x79)
        {
            binop(cb, BIN_SHL);
            return 1;
        }  // ishl/lshl
        else if (op == 0x7A || op == 0x7B)
        {
            binop(cb, BIN_ASR);
            return 1;
        }  // ishr/lshr
        else if (op == 0x7C)
        {
            iushr(cb);                                   // int >>> : zero-extend the (maybe sign-extended) 32-bit value first
            return 1;
        }  // iushr
        else if (op == 0x7D)
        {
            binop(cb, BIN_LSR);                          // long >>> : the full 64-bit lsrv is correct
            return 1;
        }  // lushr
        else if (op == 0x7E || op == 0x7F)
        {
            binop(cb, BIN_AND);
            return 1;
        }
        else if (op == 0x80 || op == 0x81)
        {
            binop(cb, BIN_OR);
            return 1;
        }  // ior/lor
        else if (op == 0x82 || op == 0x83)
        {
            binop(cb, BIN_XOR);
            return 1;
        }  // ixor/lxor
        else if (op == 0x94)
        {
            lcmp(cb);
            return 1;
        }  // lcmp -> -1/0/1

        else if (op == 0x85)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.sxtw(r, r));     // i2l: sign-extend 32->64 (int ops don't keep the high half signed)
            return 1;
        }  // i2l
        else if (op == 0x88)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.sxtw(r, r));     // l2i: canonicalise to a sign-extended int (the invariant i2l relies
            return 1;                       // on) -- the truncated long may carry dirty/unrelated high bits
        }  // l2i
        else if (op == 0x91)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.sxtb(r, r));
            return 1;
        }  // i2b
        else if (op == 0x92)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.uxth(r, r));
            return 1;
        }  // i2c
        else if (op == 0x93)
        {
            int r = opSlot(sp - 1);
            cb.emit(A64Enc.sxth(r, r));
            return 1;
        }  // i2s

        // ---- floating point: arithmetic, conversion, compare (operands are float/double BITS in GP regs) ----
        else if (op == 0x62 || op == 0x63) { fbinop(cb, 0, op == 0x63); return 1; }   // fadd / dadd
        else if (op == 0x66 || op == 0x67) { fbinop(cb, 1, op == 0x67); return 1; }   // fsub / dsub
        else if (op == 0x6A || op == 0x6B) { fbinop(cb, 2, op == 0x6B); return 1; }   // fmul / dmul
        else if (op == 0x6E || op == 0x6F) { fbinop(cb, 3, op == 0x6F); return 1; }   // fdiv / ddiv
        else if (op == 0x76 || op == 0x77) { fneg(cb, op == 0x77); return 1; }        // fneg / dneg
        else if (op == 0x95) { fcmp(cb, false, false); return 1; }   // fcmpl (NaN -> -1)
        else if (op == 0x96) { fcmp(cb, false, true); return 1; }    // fcmpg (NaN -> +1)
        else if (op == 0x97) { fcmp(cb, true, false); return 1; }    // dcmpl
        else if (op == 0x98) { fcmp(cb, true, true); return 1; }     // dcmpg
        else if (op == 0x86) { int r = opSlot(sp - 1); cb.emit(A64Enc.scvtfSW(0, r)); cb.emit(A64Enc.fmovStoW(r, 0)); return 1; }   // i2f
        else if (op == 0x87) { int r = opSlot(sp - 1); cb.emit(A64Enc.scvtfDW(0, r)); cb.emit(A64Enc.fmovDtoX(r, 0)); return 1; }   // i2d
        else if (op == 0x89) { int r = opSlot(sp - 1); cb.emit(A64Enc.scvtfSX(0, r)); cb.emit(A64Enc.fmovStoW(r, 0)); return 1; }   // l2f
        else if (op == 0x8A) { int r = opSlot(sp - 1); cb.emit(A64Enc.scvtfDX(0, r)); cb.emit(A64Enc.fmovDtoX(r, 0)); return 1; }   // l2d
        else if (op == 0x8B) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovWtoS(0, r)); cb.emit(A64Enc.fcvtzsWS(r, 0)); return 1; }  // f2i
        else if (op == 0x8C) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovWtoS(0, r)); cb.emit(A64Enc.fcvtzsXS(r, 0)); return 1; }  // f2l
        else if (op == 0x8D) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovWtoS(0, r)); cb.emit(A64Enc.fcvtDS(0, 0)); cb.emit(A64Enc.fmovDtoX(r, 0)); return 1; } // f2d
        else if (op == 0x8E) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovXtoD(0, r)); cb.emit(A64Enc.fcvtzsWD(r, 0)); return 1; }  // d2i
        else if (op == 0x8F) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovXtoD(0, r)); cb.emit(A64Enc.fcvtzsXD(r, 0)); return 1; }  // d2l
        else if (op == 0x90) { int r = opSlot(sp - 1); cb.emit(A64Enc.fmovXtoD(0, r)); cb.emit(A64Enc.fcvtSD(0, 0)); cb.emit(A64Enc.fmovStoW(r, 0)); return 1; } // d2f

        else if (op == 0x99 || op == 0xC6)
        {
            branchZero(cb, code, pos, true);
            return 3;
        }  // ifeq / ifnull (null == 0)
        else if (op == 0x9A || op == 0xC7)
        {
            branchZero(cb, code, pos, false);
            return 3;
        }  // ifne / ifnonnull
        else if (op == 0x9B)
        {
            branchCmpZero(cb, code, pos, A64Enc.LT);
            return 3;
        }
        else if (op == 0x9C)
        {
            branchCmpZero(cb, code, pos, A64Enc.GE);
            return 3;
        }
        else if (op == 0x9D)
        {
            branchCmpZero(cb, code, pos, A64Enc.GT);
            return 3;
        }
        else if (op == 0x9E)
        {
            branchCmpZero(cb, code, pos, A64Enc.LE);
            return 3;
        }
        else if (op == 0x9F)
        {
            branchCmp(cb, code, pos, A64Enc.EQ);
            return 3;
        }
        else if (op == 0xA0)
        {
            branchCmp(cb, code, pos, A64Enc.NE);
            return 3;
        }
        else if (op == 0xA1)
        {
            branchCmp(cb, code, pos, A64Enc.LT);
            return 3;
        }
        else if (op == 0xA2)
        {
            branchCmp(cb, code, pos, A64Enc.GE);
            return 3;
        }
        else if (op == 0xA3)
        {
            branchCmp(cb, code, pos, A64Enc.GT);
            return 3;
        }
        else if (op == 0xA4)
        {
            branchCmp(cb, code, pos, A64Enc.LE);
            return 3;
        }
        else if (op == 0xA5)
        {
            branchCmp(cb, code, pos, A64Enc.EQ);            // if_acmpeq — reference compare (addresses <4GB, eq/ne exact)
            return 3;
        }
        else if (op == 0xA6)
        {
            branchCmp(cb, code, pos, A64Enc.NE);            // if_acmpne
            return 3;
        }
        else if (op == 0xA7)
        {
            int target = pos + s2(code, pos + 1);
            syncOut(cb);                                     // canonicalize the stack before the unconditional jump
            int w = cb.emit(A64Enc.b(0));
            addFixup(w, target, FIX_B, 0);
            recordDepth(target);
            return 3;
        }
        else if (op == 0xAA)
        {
            return tableswitch(cb, code, pos);
        }
        else if (op == 0xAB)
        {
            return lookupswitch(cb, code, pos);
        }

        else if (op == 0xB2)
        {
            getstatic(cb, u2(code, pos + 1));
            return 3;
        }
        else if (op == 0xB3)
        {
            putstatic(cb, u2(code, pos + 1));
            return 3;
        }
        else if (op == 0xB4)
        {
            getfield(cb, u2(code, pos + 1), pos);
            return 3;
        }
        else if (op == 0xB5)
        {
            putfield(cb, u2(code, pos + 1), pos);
            return 3;
        }
        else if (op == 0xB6)
        {
            lowerInvokeVirtual(u2(code, pos + 1), cb, pos);
            return 3;
        }
        else if (op == 0xB7)
        {
            lowerInvokeSpecial(u2(code, pos + 1), cb);
            return 3;
        }
        else if (op == 0xB8)
        {
            lowerInvokeStatic(u2(code, pos + 1), cb);
            return 3;
        }
        else if (op == 0xB9)
        {
            lowerInvokeInterface(u2(code, pos + 1), cb, pos);
            return 5;
        }  // invokeinterface
        else if (op == 0xBA)
        {
            lowerInvokeDynamic(u2(code, pos + 1), cb);
            return 5;
        }  // invokedynamic (index(2) + zero(2))
        else if (op == 0xBB)
        {
            lowerNew(u2(code, pos + 1), cb);
            return 3;
        }
        else if (op == 0xBC)
        {
            lowerNewArray(code[pos + 1] & 0xFF, cb);
            return 2;
        }
        else if (op == 0xBD)
        {
            lowerAnewArray(cb, u2(code, pos + 1));           // operand = element-class Class-entry
            return 3;
        }
        else if (op == 0xBF)
        {
            athrow(cb, pos);
            return 1;
        }  // athrow
        else if (op == 0xC0)
        {
            typeCheck(cb, u2(code, pos + 1), Symbols.CHECK_CAST);
            return 3;
        }  // checkcast
        else if (op == 0xC1)
        {
            typeCheck(cb, u2(code, pos + 1), Symbols.INSTANCE_OF);
            return 3;
        }  // instanceof
        else if (op == 0xC2 || op == 0xC3)
        {
            // monitorenter/monitorexit: a real ownership-tracking, recursive, blocking monitor (VM.monEnter/
            // monExit). Needed for Thread.holdsLock + a contested lock; uncontested locks just acquire+release.
            nullCheck(cb, opSlot(sp - 1), pos);              // objectref on top; NPE if null (JVM semantics)
            emitCall(cb, 1, false, false, SYM_HELPER, op == 0xC2 ? Symbols.MON_ENTER : Symbols.MON_EXIT);
            return 1;
        }  // monitorenter / monitorexit

        else
        {
            symbols.fail(Symbols.FAIL_OPCODE, op, pos);
            return 0;                                        // unreachable: fail never returns
        }
    }

    // Binary-op kinds (plain ints, not an enum: the metal compiler has no enum support).
    private static final int BIN_ADD = 0;
    private static final int BIN_SUB = 1;
    private static final int BIN_MUL = 2;
    private static final int BIN_DIV = 3;
    private static final int BIN_AND = 4;
    private static final int BIN_OR = 5;
    private static final int BIN_XOR = 6;
    private static final int BIN_SHL = 7;
    private static final int BIN_ASR = 8;
    private static final int BIN_LSR = 9;

    // ----- register allocation ---------------------------------------------
    // Shallow methods (peak depth <= OP_MAX): slot i lives in x(OP_BASE+i), byte-identical to the original.
    // Deep methods (deepStack): the operand stack is a circular register window -- slot i lives in
    // x(OP_BASE + i%OP_MAX), with the displaced deeper slots held in frame memory at opStackBase. regHolds[r]
    // = the slot whose live value is currently in physical register OP_BASE+r (a "hole" = the mapped slot's
    // value is in memory, not the register). See opSlot/pushReg/popReg/syncOut/syncIn.
    private int pushReg()
    {
        if (!deepStack)
        {
            if (sp >= OP_MAX)
            {
                symbols.fail(Symbols.FAIL_STACK_OVERFLOW, sp, 0);
            }
            int r = OP_BASE + sp;      // avoid post-increment on a field (javac -> dup_x1)
            sp += 1;
            return r;
        }
        if (sp >= opStackSlots)
        {
            symbols.fail(Symbols.FAIL_STACK_OVERFLOW, sp, 0);   // pre-pass under-counted the peak (a bug)
        }
        int r = sp % OP_MAX;
        if (sp >= OP_MAX && regHolds[r] == sp - OP_MAX)         // the displaced deeper slot is live in this reg
        {
            curCb.emit(A64Enc.strx(OP_BASE + r, 31, opStackBase + (sp - OP_MAX) * 8));   // save it before reuse
        }
        regHolds[r] = sp;
        sp += 1;
        return OP_BASE + r;
    }
    private int popReg()
    {
        if (sp <= 0)
        {
            symbols.fail(Symbols.FAIL_STACK_UNDERFLOW, sp, 0);
        }
        sp -= 1;
        if (!deepStack)
        {
            return OP_BASE + sp;
        }
        return opSlot(sp);         // materialise the popped value (reload from memory if it is a hole)
    }

    /**
     * Register holding operand slot {@code slot}, ensuring its live value is resident. For deep methods a slot
     * that was displaced to memory (a hole, or invalidated at a branch merge) is reloaded here on demand. For
     * shallow methods this is exactly {@code OP_BASE + slot} with no emit (byte-identical output).
     */
    private int opSlot(int slot)
    {
        if (!deepStack)
        {
            return OP_BASE + slot;
        }
        int r = slot % OP_MAX;
        if (regHolds[r] != slot)
        {
            curCb.emit(A64Enc.ldrx(OP_BASE + r, 31, opStackBase + slot * 8));
            regHolds[r] = slot;
        }
        return OP_BASE + r;
    }

    /** Spill every resident live operand slot to its memory home so memory is canonical (before a branch/call). */
    private void syncOut(CodeBuffer cb)
    {
        if (!deepStack)
        {
            return;
        }
        for (int slot = 0; slot < sp; slot++)
        {
            int r = slot % OP_MAX;
            if (regHolds[r] == slot)                            // resident (holes are already in memory)
            {
                cb.emit(A64Enc.strx(OP_BASE + r, 31, opStackBase + slot * 8));
            }
        }
    }

    /** Invalidate every operand register at a merge point / after a call: reads reload from (canonical) memory. */
    private void syncIn(CodeBuffer cb)
    {
        if (!deepStack)
        {
            return;
        }
        for (int r = 0; r < OP_MAX; r++)
        {
            regHolds[r] = -1;
        }
    }

    /** Whether {@code op} can fall through to the next bytecode (false = goto/return/athrow/switch). */
    private static boolean fallsThrough(int op)
    {
        return !(op == 0xA7 || op == 0xC8                       // goto / goto_w
              || (op >= 0xAC && op <= 0xB1)                     // *return
              || op == 0xBF                                     // athrow
              || op == 0xAA || op == 0xAB                       // tableswitch / lookupswitch
              || op == 0xA9);                                   // ret
    }
    /** Register holding local {@code slot}. Only valid when {@link #inReg} says so. */
    private int localReg(int slot)
    {
        if (slot < 0 || slot >= LOC_MAX)
        {
            symbols.fail(Symbols.FAIL_LOCAL_SLOT, slot, 0);
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
    private void expectEmpty(int site)
    {
        if (sp != 0)
        {
            symbols.fail(Symbols.FAIL_STACK_NOT_EMPTY, sp, site);
        }
    }

    private void loadConst(CodeBuffer cb, long v)
    {
        cb.emitAll(A64Enc.loadImm64(pushReg(), v));
    }
    private void load(CodeBuffer cb, int slot)
    {
        int r = pushReg();
        cb.emit(inReg(slot) ? A64Enc.movReg(r, localReg(slot))
                            : A64Enc.ldrx(r, 31, localMem(slot)));
    }
    private void store(CodeBuffer cb, int slot)
    {
        int r = popReg();
        cb.emit(inReg(slot) ? A64Enc.movReg(localReg(slot), r)
                            : A64Enc.strx(r, 31, localMem(slot)));
    }

    private void iinc(CodeBuffer cb, int slot, int delta)
    {
        if (inReg(slot))
        {
            int r = localReg(slot);
            cb.emit(delta >= 0 ? A64Enc.addImm(r, r, delta) : A64Enc.subImm(r, r, -delta));
            return;
        }
        int r = SCRATCH;                                   // read-modify-write in the frame
        cb.emit(A64Enc.ldrx(r, 31, localMem(slot)));
        cb.emit(delta >= 0 ? A64Enc.addImm(r, r, delta) : A64Enc.subImm(r, r, -delta));
        cb.emit(A64Enc.strx(r, 31, localMem(slot)));
    }

    /**
     * irem: {@code r = a - (a/b)*b}. AArch64 has no remainder op, so it's an SDIV
     * plus MSUB. {@code r} aliases {@code a}'s register (pop, pop, push), so stash the
     * original {@code a} in SCRATCH first — SDIV would otherwise clobber it before MSUB
     * reads it back.
     */
    private void irem(CodeBuffer cb)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();                          // same register as a
        cb.emit(A64Enc.movReg(SCRATCH, a));         // SCRATCH = a (original dividend)
        cb.emit(A64Enc.sdivReg(r, SCRATCH, b));     // r = a / b
        cb.emit(A64Enc.msub(r, r, b, SCRATCH));     // r = a - (a/b)*b
    }

    /** {@code iushr}: an int logical shift-right. Ints live sign-extended in 64-bit regs (see l2i/i2l), so a
     *  64-bit lsrv would shift the sign bits in — zero-extend the low 32 bits (uxtw) before the shift. */
    private void iushr(CodeBuffer cb)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(A64Enc.uxtw(r, a));
        cb.emit(A64Enc.lsrv(r, r, b));
    }

    private void binop(CodeBuffer cb, int kind)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(kind == BIN_ADD ? A64Enc.addReg(r, a, b)
              : kind == BIN_SUB ? A64Enc.subReg(r, a, b)
              : kind == BIN_MUL ? A64Enc.mulReg(r, a, b)
              : kind == BIN_DIV ? A64Enc.sdivReg(r, a, b)
              : kind == BIN_AND ? A64Enc.andReg(r, a, b)
              : kind == BIN_OR ? A64Enc.orrReg(r, a, b)
              : kind == BIN_XOR ? A64Enc.eorReg(r, a, b)
              : kind == BIN_SHL ? A64Enc.lslv(r, a, b)
              : kind == BIN_ASR ? A64Enc.asrv(r, a, b)
              : A64Enc.lsrv(r, a, b));                                // BIN_LSR
    }

    private void dup(CodeBuffer cb)
    {
        int top = opSlot(sp - 1);              // resident before the push (which may spill a displaced slot)
        cb.emit(A64Enc.movReg(pushReg(), top));
    }

    /**
     * dup_x1: {@code ..,v2,v1 -> ..,v1,v2,v1}. x16 holds v1 across the in-place register shift. Deep-safe: the
     * three slots (sp-2,sp-1,sp) map to distinct physical registers (their indices differ by &lt; OP_MAX), and
     * pushReg only spills a fourth (displaced) register -- so the shuffle registers are undisturbed. opSlot
     * materialises any hole first; for shallow methods it is exactly {@code OP_BASE + slot} (byte-identical).
     */
    private void dupX1(CodeBuffer cb)
    {
        int v1 = opSlot(sp - 1);
        int v2 = opSlot(sp - 2);
        int top = pushReg();
        cb.emit(A64Enc.movReg(16, v1));      // save v1 (the value being inserted below v2)
        cb.emit(A64Enc.movReg(top, 16));     // new top = v1
        cb.emit(A64Enc.movReg(v1, v2));      // shift v2 up
        cb.emit(A64Enc.movReg(v2, 16));      // insert v1 at the bottom
    }

    /** dup_x2 (category-1 form): {@code ..,v3,v2,v1 -> ..,v1,v3,v2,v1}. Deep-safe (see {@link #dupX1}). */
    private void dupX2(CodeBuffer cb)
    {
        int v1 = opSlot(sp - 1);
        int v2 = opSlot(sp - 2);
        int v3 = opSlot(sp - 3);
        int top = pushReg();
        cb.emit(A64Enc.movReg(16, v1));
        cb.emit(A64Enc.movReg(top, 16));     // new top = v1
        cb.emit(A64Enc.movReg(v1, v2));      // shift v2 up
        cb.emit(A64Enc.movReg(v2, v3));      // shift v3 up
        cb.emit(A64Enc.movReg(v3, 16));      // insert v1 at the bottom
    }

    /**
     * dup2, category-1 form: {@code ..., v2, v1 -> ..., v2, v1, v2, v1}. Every value
     * on joe-ng's stack occupies one register (longs included), so this duplicates the
     * top two slots — exactly what {@code arr[i] op= x} emits (dup the array ref+index
     * before the load). The category-2 form (dup2 of a single long/double) is not used
     * by the code we compile and is not handled.
     */
    private void dup2(CodeBuffer cb)
    {
        if (wideTop[curPos])          // category-2: the top is ONE long/double (1 slot) -> duplicate it, like dup
        {
            int top = opSlot(sp - 1);
            cb.emit(A64Enc.movReg(pushReg(), top));
            return;
        }
        int lo = opSlot(sp - 2);      // category-1: two 1-slot values -> duplicate both
        int hi = opSlot(sp - 1);      // v1 (top)
        cb.emit(A64Enc.movReg(pushReg(), lo));
        cb.emit(A64Enc.movReg(pushReg(), hi));
    }

    /** lcmp: push -1/0/1 for a&lt;b / a==b / a&gt;b (usually consumed by a following if). */
    private void lcmp(CodeBuffer cb)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        cb.emit(A64Enc.cmpReg(a, b));
        cb.emit(A64Enc.cset(r, A64Enc.GT));            // a>b -> 1, else 0
        cb.emit(A64Enc.csinv(r, r, A64Enc.XZR, A64Enc.GE)); // a<b -> -1, else keep
    }

    // ----- floating point helpers (bits live in GP regs; v0/v1 are scratch FP regs) -----

    /** Float/double binary op {@code kind} (0 add,1 sub,2 mul,3 div): move to v0/v1, compute, move back. */
    private void fbinop(CodeBuffer cb, int kind, boolean dbl)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();                              // r == a's register
        if (dbl)
        {
            cb.emit(A64Enc.fmovXtoD(0, a));
            cb.emit(A64Enc.fmovXtoD(1, b));
            cb.emit(kind == 0 ? A64Enc.faddd(0, 0, 1) : kind == 1 ? A64Enc.fsubd(0, 0, 1)
                  : kind == 2 ? A64Enc.fmuld(0, 0, 1) : A64Enc.fdivd(0, 0, 1));
            cb.emit(A64Enc.fmovDtoX(r, 0));
        }
        else
        {
            cb.emit(A64Enc.fmovWtoS(0, a));
            cb.emit(A64Enc.fmovWtoS(1, b));
            cb.emit(kind == 0 ? A64Enc.fadds(0, 0, 1) : kind == 1 ? A64Enc.fsubs(0, 0, 1)
                  : kind == 2 ? A64Enc.fmuls(0, 0, 1) : A64Enc.fdivs(0, 0, 1));
            cb.emit(A64Enc.fmovStoW(r, 0));
        }
    }

    /** fneg/dneg on the top-of-stack float/double bits. */
    private void fneg(CodeBuffer cb, boolean dbl)
    {
        int r = opSlot(sp - 1);
        if (dbl)
        {
            cb.emit(A64Enc.fmovXtoD(0, r));
            cb.emit(A64Enc.fnegd(0, 0));
            cb.emit(A64Enc.fmovDtoX(r, 0));
        }
        else
        {
            cb.emit(A64Enc.fmovWtoS(0, r));
            cb.emit(A64Enc.fnegs(0, 0));
            cb.emit(A64Enc.fmovStoW(r, 0));
        }
    }

    /** fcmpl/fcmpg/dcmpl/dcmpg -> -1/0/1. {@code gExpr}: NaN -> +1 (g) vs -1 (l). */
    private void fcmp(CodeBuffer cb, boolean dbl, boolean gExpr)
    {
        int b = popReg();
        int a = popReg();
        int r = pushReg();
        if (dbl)
        {
            cb.emit(A64Enc.fmovXtoD(0, a));
            cb.emit(A64Enc.fmovXtoD(1, b));
            cb.emit(A64Enc.fcmpd(0, 1));
        }
        else
        {
            cb.emit(A64Enc.fmovWtoS(0, a));
            cb.emit(A64Enc.fmovWtoS(1, b));
            cb.emit(A64Enc.fcmps(0, 1));
        }
        if (gExpr)                                      // NaN is unordered -> HI (C set, Z clear) -> +1
        {
            cb.emit(A64Enc.cset(r, A64Enc.HI));
            cb.emit(A64Enc.csinv(r, r, A64Enc.XZR, A64Enc.PL));   // only true LT (N set) -> -1
        }
        else                                            // NaN -> -1 (same shape as lcmp)
        {
            cb.emit(A64Enc.cset(r, A64Enc.GT));
            cb.emit(A64Enc.csinv(r, r, A64Enc.XZR, A64Enc.GE));
        }
    }

    // ----- static fields: absolute address in the image statics area --------
    private void getstatic(CodeBuffer cb, int cpIndex)
    {
        int r = pushReg();
        symbols.staticField(cb, r, cpIndex);
        cb.emit(A64Enc.ldrx(r, r, 0));
    }
    private void putstatic(CodeBuffer cb, int cpIndex)
    {
        int v = popReg();
        symbols.staticField(cb, 16, cpIndex);
        cb.emit(A64Enc.strx(v, 16, 0));
    }

    /** Load the synthetic $exception static slot into {@code destReg}. */
    private void emitLoadException(CodeBuffer cb, int destReg)
    {
        symbols.exceptionSlot(cb, destReg);
        cb.emit(A64Enc.ldrx(destReg, destReg, 0));
    }
    /** Store {@code valReg} into the synthetic $exception static slot (via x16). */
    private void emitStoreException(CodeBuffer cb, int valReg)
    {
        symbols.exceptionSlot(cb, 16);
        cb.emit(A64Enc.strx(valReg, 16, 0));
    }

    /** instanceof/checkcast: push the target class's Type address, call the VM helper. */
    private void typeCheck(CodeBuffer cb, int classIndex, int helper)
    {
        int r = pushReg();                                       // objref stays below; push targetType addr
        symbols.type(cb, r, classIndex);
        emitCall(cb, 2, true, false, SYM_HELPER, helper);        // (objref, targetType) -> result
    }

    // ----- object fields (8-byte slots; see objectmodel.ObjectModel) --------
    private void getfield(CodeBuffer cb, int cpIndex, int pos)
    {
        int off = symbols.fieldOffset(cpIndex);
        int obj = popReg();
        nullCheck(cb, obj, pos);                                 // this.f on null -> NPE
        int r = pushReg();
        cb.emit(A64Enc.ldrx(r, obj, off));
    }

    private void putfield(CodeBuffer cb, int cpIndex, int pos)
    {
        int off = symbols.fieldOffset(cpIndex);
        int val = popReg();
        int obj = popReg();
        nullCheck(cb, obj, pos);                                 // this.f = v on null -> NPE
        cb.emit(A64Enc.strx(val, obj, off));
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
            expectEmpty(Symbols.SITE_NEW);                                   // frameless: nowhere to spill
        }
        int size = symbols.objectSize(classIndex);
        cb.emitAll(A64Enc.loadImm64(0, size));                       // x0 = size (Heap.alloc arg)
        spillLive(cb);                                            // Heap.alloc clobbers x9..
        symbols.callHelper(cb, Symbols.HEAP_ALLOC);               // x0 = object base
        reloadLive(cb);
        symbols.tib(cb, 1, classIndex);                           // x1 = &TIB
        cb.emit(A64Enc.strx(1, 0, ObjectModel.TIB_OFFSET));          // header.tib = &TIB
        cb.emit(A64Enc.movReg(pushReg(), 0));                        // push the reference
    }

    // ----- branches --------------------------------------------------------
    private void branchZero(CodeBuffer cb, byte[] code, int pos, boolean eq)
    {
        int v = popReg();
        int target = pos + s2(code, pos + 1);
        syncOut(cb);                                         // flush the remaining stack so the taken edge is canonical
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, target, eq ? FIX_CBZ : FIX_CBNZ, v);
        recordDepth(target);
    }

    private void branchCmpZero(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int v = popReg();
        cb.emit(A64Enc.sxtw(v, v));                          // canonicalize: an overflowed int isn't sign-extended,
        cb.emit(A64Enc.cmpImm(v, 0));                        // and this is a 64-bit compare
        int target = pos + s2(code, pos + 1);
        syncOut(cb);
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, target, FIX_BCOND, cond);
        recordDepth(target);
    }

    private void branchCmp(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int b = popReg();
        int a = popReg();
        cb.emit(A64Enc.sxtw(a, a));                          // canonicalize both (overflowed ints); harmless for
        cb.emit(A64Enc.sxtw(b, b));                          // canonical ints, equality-preserving for if_acmp refs
        cb.emit(A64Enc.cmpReg(a, b));
        int target = pos + s2(code, pos + 1);
        syncOut(cb);
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, target, FIX_BCOND, cond);
        recordDepth(target);
    }

    /**
     * {@code tableswitch}: pop the index, then a compare-branch chain over {@code low..high} (each case value
     * materialised in scratch x16, index canonicalised sign-extended), falling to {@code default}. The 0-3
     * padding bytes align {@code default} to a 4-byte boundary from the start of the code array.
     */
    private int tableswitch(CodeBuffer cb, byte[] code, int pos)
    {
        int p = pos + 1 + ((4 - ((pos + 1) & 3)) & 3);
        int def = s4(code, p);
        int low = s4(code, p + 4);
        int high = s4(code, p + 8);
        int idx = popReg();
        cb.emit(A64Enc.sxtw(idx, idx));
        syncOut(cb);                                        // all targets (and the default) see a canonical stack
        int k = 0;
        while (k <= high - low)
        {
            int target = pos + s4(code, p + 12 + k * 4);
            cb.emitAll(A64Enc.loadImm64(16, low + k));      // case value (sign-extended) in scratch x16
            cb.emit(A64Enc.cmpReg(idx, 16));
            int w = cb.emit(A64Enc.b(0));                   // placeholder; FIX_BCOND re-encodes as b.eq
            addFixup(w, target, FIX_BCOND, A64Enc.EQ);
            recordDepth(target);
            k += 1;
        }
        int wd = cb.emit(A64Enc.b(0));
        addFixup(wd, pos + def, FIX_B, 0);
        recordDepth(pos + def);
        return (p + 12 + (high - low + 1) * 4) - pos;
    }

    /** {@code lookupswitch}: pop the index, then a compare-branch chain over the sorted {match,offset} pairs. */
    private int lookupswitch(CodeBuffer cb, byte[] code, int pos)
    {
        int p = pos + 1 + ((4 - ((pos + 1) & 3)) & 3);
        int def = s4(code, p);
        int npairs = s4(code, p + 4);
        int idx = popReg();
        cb.emit(A64Enc.sxtw(idx, idx));
        syncOut(cb);                                        // all targets (and the default) see a canonical stack
        int k = 0;
        while (k < npairs)
        {
            int match = s4(code, p + 8 + k * 8);
            int target = pos + s4(code, p + 8 + k * 8 + 4);
            cb.emitAll(A64Enc.loadImm64(16, match));
            cb.emit(A64Enc.cmpReg(idx, 16));
            int w = cb.emit(A64Enc.b(0));
            addFixup(w, target, FIX_BCOND, A64Enc.EQ);
            recordDepth(target);
            k += 1;
        }
        int wd = cb.emit(A64Enc.b(0));
        addFixup(wd, pos + def, FIX_B, 0);
        recordDepth(pos + def);
        return (p + 8 + npairs * 8) - pos;
    }

    /** Signed big-endian 4-byte read (switch payloads). */
    public static int s4(byte[] b, int i)
    {
        return (b[i] << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
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
            symbols.fail(Symbols.FAIL_STACK_DEPTH, bc, 0);
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
    private void lowerInvokeVirtual(int cpIndex, CodeBuffer cb, int pos)
    {
        if (symbols.isGetClass(cpIndex))                        // Object.getClass(): intrinsic, not vtable dispatch
        {                                                       // (works uniformly on arrays, whose TIB has no vtable)
            nullCheck(cb, opSlot(sp - 1), pos);                 // the receiver is the sole operand on top
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.GET_CLASS);   // (obj) -> Class mirror
            return;
        }
        if (symbols.isArrayClone(cpIndex))                      // [T.clone(): intrinsic copy -- an array TIB has no
        {                                                       // vtable, so dispatch would BLR garbage
            nullCheck(cb, opSlot(sp - 1), pos);
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.ARRAY_CLONE);   // (array) -> shallow copy
            return;
        }
        if (symbols.isDesiredAssertionStatus(cpIndex))          // Class.desiredAssertionStatus(): assertions off -> false
        {                                                       // (drop the receiver, push 0; no mirror vtable needed)
            popReg();
            cb.emit(A64Enc.movReg(pushReg(), 31));              // result = XZR = 0 = false
            return;
        }
        int mop = symbols.monitorOp(cpIndex);                   // Object.wait/notify/notifyAll: lower to a VM helper
        if (mop != 0)                                           // directly (final methods; the vtable slot is a no-op)
        {
            if (mop == 1)                                       // wait()V -> objWait(recv, 0)
            {
                nullCheck(cb, opSlot(sp - 1), pos);
                cb.emit(A64Enc.movReg(pushReg(), 31));          // push ms = 0 (XZR) as the 2nd arg
                emitCall(cb, 2, false, false, SYM_HELPER, Symbols.MON_WAIT);
            }
            else if (mop == 2)                                  // wait(J)V -> objWait(recv, ms)
            {
                nullCheck(cb, opSlot(sp - 2), pos);             // receiver is below the ms operand
                emitCall(cb, 2, false, false, SYM_HELPER, Symbols.MON_WAIT);
            }
            else if (mop == 3)                                  // notify()V -> objNotify(recv)
            {
                nullCheck(cb, opSlot(sp - 1), pos);
                emitCall(cb, 1, false, false, SYM_HELPER, Symbols.MON_NOTIFY);
            }
            else                                                // notifyAll()V -> objNotifyAll(recv)
            {
                nullCheck(cb, opSlot(sp - 1), pos);
                emitCall(cb, 1, false, false, SYM_HELPER, Symbols.MON_NOTALL);
            }
            return;
        }
        int slot = symbols.vtableSlot(cpIndex);
        int nargs = paramCount(cpIndex) + 1;    // receiver + params
        if (deepStack)
        {
            marshalArgsFromMemory(cb, nargs);   // receiver -> x0
            nullCheck(cb, 0, pos);              // dispatch on null -> NPE
        }
        else
        {
            int[] src = new int[nargs];
            for (int k = 0; k < nargs; k++)
            {
                src[k] = popReg();
            }
            nullCheck(cb, src[nargs - 1], pos);     // src[nargs-1] = the receiver (deepest); dispatch on null -> NPE
            for (int k = 0; k < nargs; k++)
            {
                cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
            }
            spillLive(cb);
        }
        cb.emit(A64Enc.ldrx(16, 0, ObjectModel.TIB_OFFSET));       // x16 = receiver.tib
        cb.emit(A64Enc.ldrx(16, 16, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)))); // x16 = code
        // Null-vtable-slot guard (metal JIT only; the trusted image writer stays check-free like nullCheck): a 0
        // code word means the resolved global vtable slot has no method for THIS receiver's type -- e.g.
        // globalVtableSlot's name+desc fallback matched an unrelated class's slot (see Loader ~1864). Left as a
        // bare `blr 0` it wild-branches to 0x0 -> the boot trampoline -> a SILENT REBOOT that looks like a reset.
        // Trap it as an NPE at this PC (same shape as the itable-scan sentinel) so it's reported, not a reboot.
        if (symbols.implicitChecks())
        {
            // The resolved code word (x16) must be a plausible metal code address: 4-aligned, below the code
            // ceiling (0x1000_0000), and non-zero. A garbage word -- e.g. a vtable slot index past a SHORT guest
            // vtable (a minimal exception overlay, or an array's tiny TIB) reading adjacent heap DATA as a code
            // pointer -- fails these, so we throw an NPE at this PC instead of a wild `blr` into unmapped memory
            // (which faults as an instruction-abort / silent reboot). Only the metal JIT emits this; trusted image
            // code (implicitChecks()==false) stays check-free. (#43)
            int b0 = cb.emit(A64Enc.tbnz(16, 0, 0));    // misaligned (bit 0 set)
            int b1 = cb.emit(A64Enc.tbnz(16, 1, 0));    // misaligned (bit 1 set)
            cb.emit(A64Enc.lsrImm(17, 16, 28));         // x17 = x16 >> 28  (nonzero => x16 >= 0x1000_0000)
            int b2 = cb.emit(A64Enc.cbnz(17, 0));       // above the code ceiling
            int b3 = cb.emit(A64Enc.cbz(16, 0));        // null slot (unresolved)
            int ok = cb.emit(A64Enc.b(0));              // all good -> skip the throw
            int throwAt = cb.wordCount();
            cb.set(b0, A64Enc.tbnz(16, 0, throwAt - b0));
            cb.set(b1, A64Enc.tbnz(16, 1, throwAt - b1));
            cb.set(b2, A64Enc.cbnz(17, throwAt - b2));
            cb.set(b3, A64Enc.cbz(16, throwAt - b3));
            throwImplicit(cb, pos, Symbols.NEW_AIOOBE);   // OOB vtable slot -> AIOOBE (distinct from a null-receiver NPE)
            cb.set(ok, A64Enc.b(cb.wordCount() - ok));
        }
        cb.emit(A64Enc.blr(16));
        reloadLive(cb);
        if (returnsValue(cpIndex))
        {
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
    }

    /**
     * Interface dispatch: move args to x0.., then inline-search the receiver's
     * itable directory (Type→dir of {interfaceType, itable}) for the target
     * interface's Type, index the itable by the method's slot, and {@code blr}.
     * Uses x16 (target/code), x17 (walker), x9 (temp) — args in x0..x7 untouched.
     */
    private void lowerInvokeInterface(int cpIndex, CodeBuffer cb, int pos)
    {
        int slot = symbols.interfaceSlot(cpIndex);
        int nargs = paramCount(cpIndex) + 1;    // receiver + params
        if (deepStack)
        {
            marshalArgsFromMemory(cb, nargs);   // receiver -> x0
            nullCheck(cb, 0, pos);              // interface dispatch on null -> NPE
        }
        else
        {
            int[] src = new int[nargs];
            for (int k = 0; k < nargs; k++)
            {
                src[k] = popReg();
            }
            nullCheck(cb, src[nargs - 1], pos);     // receiver (deepest); interface dispatch on null -> NPE
            for (int k = 0; k < nargs; k++)
            {
                cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
            }
            spillLive(cb);
        }

        symbols.interfaceType(cb, 16, cpIndex);                                     // x16 = &interfaceType
        cb.emit(A64Enc.ldrx(17, 0, ObjectModel.TIB_OFFSET));                           // x17 = receiver.tib
        cb.emit(A64Enc.ldrx(17, 17, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT))); // x17 = Type
        cb.emit(A64Enc.ldrx(17, 17, ObjectModel.TYPE_ITABLE_DIR_OFFSET));              // x17 = itable dir
        int search = cb.wordCount();
        cb.emit(A64Enc.ldrx(9, 17, ObjectModel.ITABLE_ENTRY_IFACE_OFFSET));            // x9 = entry.interfaceType
        // Directory-sentinel guard (metal JIT only; the image writer's trusted code is check-free like nullCheck):
        // if the scan reaches the 0-terminator without a match, the receiver's itable dir lacks the target
        // interface -- bail with an NPE at this PC rather than dereferencing the sentinel's itable (blr 0) or
        // walking PAST it into arbitrary heap and blr'ing a layout-dependent garbage word (the wild branch that
        // reset/hung nondeterministically). A well-formed program never hits it.
        int miss = symbols.implicitChecks() ? cb.emit(A64Enc.cbz(9, 0)) : -1;
        cb.emit(A64Enc.cmpReg(9, 16));
        int beq = cb.emit(A64Enc.bcond(A64Enc.EQ, 0));                                    // found?
        cb.emit(A64Enc.addImm(17, 17, ObjectModel.ITABLE_ENTRY_SIZE));                 // next entry
        cb.emit(A64Enc.b(search - cb.wordCount()));                                    // loop
        if (miss >= 0)
        {
            cb.set(miss, A64Enc.cbz(9, cb.wordCount() - miss));
            throwImplicit(cb, pos, Symbols.NEW_NPE);
        }
        int found = cb.wordCount();
        cb.set(beq, A64Enc.bcond(A64Enc.EQ, found - beq));
        cb.emit(A64Enc.ldrx(17, 17, ObjectModel.ITABLE_ENTRY_TABLE_OFFSET));          // x17 = itable
        cb.emit(A64Enc.ldrx(16, 17, slot * ObjectModel.WORD));                        // x16 = code addr
        cb.emit(A64Enc.blr(16));
        reloadLive(cb);
        if (returnsValue(cpIndex))
        {
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
    }

    /**
     * invokedynamic — string concatenation only (M-B slice 1). Recognise the
     * {@code StringConcatFactory.makeConcatWithConstants} bootstrap and lower it directly (no MethodHandle
     * runtime): the N call-site args sit on the operand stack (slots 0..N-1); build a byte[] with the
     * VM string-builder (scStart, then scChar per recipe literal and scChar/scInt per  arg), then
     * wrap it in a mini {@code java/lang/String}. Args + builder survive each append via spill/reload.
     */
    private void lowerInvokeDynamic(int cpIndex, CodeBuffer cb)
    {
        if (symbols.isConcatIndy(cpIndex))
        {
            lowerConcat(cpIndex, cb);
        }
        else if (symbols.isLambdaIndy(cpIndex))
        {
            lowerLambda(cpIndex, cb);
        }
        else
        {
            symbols.fail(Symbols.FAIL_OPCODE, 0xBA, 0);          // unsupported bootstrap
        }
    }

    /**
     * A lambda ({@code LambdaMetafactory}): allocate the synthetic lambda object, set its (synthesised) TIB,
     * store the captured values (the call-site args on the operand stack) into its fields, and push it. Its
     * itable then dispatches the functional-interface method into the lambda body. Slice 1c: zero-arg SAM.
     */
    private void lowerLambda(int cpIndex, CodeBuffer cb)
    {
        if (deepStack) { symbols.fail(Symbols.FAIL_OPCODE, 0xBA, 3); return; }   // captures via OP_BASE+slot vs circular window: TODO
        int nc = paramCount(cpIndex);                            // captured values, on operand slots argBase..
        int argBase = sp - nc;
        cb.emitAll(A64Enc.loadImm64(0, symbols.lambdaSize(cpIndex)));   // x0 = instance size
        spillLive(cb);                                           // Heap.alloc clobbers x9.. (the captures)
        symbols.callHelper(cb, Symbols.HEAP_ALLOC);              // x0 = lambda object
        reloadLive(cb);                                          // captures back in their operand slots
        symbols.lambdaTib(cb, 1, cpIndex);                       // x1 = synthetic TIB
        cb.emit(A64Enc.strx(1, 0, ObjectModel.TIB_OFFSET));      // obj.tib = TIB
        int c = 0;
        while (c < nc)
        {
            cb.emit(A64Enc.strx(OP_BASE + argBase + c, 0, 16 + c * 8));   // obj.field[c] = capture c
            c = c + 1;
        }
        sp = argBase;                                            // drop the captures
        cb.emit(A64Enc.movReg(pushReg(), 0));                    // push the lambda object
    }

    /** A string concatenation ({@code StringConcatFactory}). */
    private void lowerConcat(int cpIndex, CodeBuffer cb)
    {
        int nargs = paramCount(cpIndex);                         // args occupy the top nargs operand slots
        int argBase = sp - nargs;                                // operand-slot index of the first arg
        emitCall(cb, 0, true, false, SYM_HELPER, Symbols.SC_START);   // scStart -> builder on top
        int sbIdx = sp - 1;                                      // operand-SLOT of the builder (opSlot maps it to a reg;
                                                                 // in a deep method that's a circular-window register)
        int recipeOff = symbols.concatRecipeOff(cpIndex);        // Utf8 body: [u2 len][chars]
        int len = u2(classBytes, recipeOff);
        int p = recipeOff + 2;
        int argIdx = 0;
        int i = 0;
        while (i < len)
        {
            int c = classBytes[p + i] & 0xFF;
            if (c == 0x01)                                       //  -> the next dynamic arg
            {
                appendArg(cb, sbIdx, argBase + argIdx, cpIndex, argIdx);
                argIdx = argIdx + 1;
            }
            else if (c == 0x02)                                  //  -> a constant operand (slice 1b)
            {
                symbols.fail(Symbols.FAIL_OPCODE, 0xBA, 1);
            }
            else
            {
                appendChar(cb, sbIdx, c);                        // a literal recipe byte
            }
            i = i + 1;
        }
        cb.emit(A64Enc.movReg(0, opSlot(sbIdx)));                // x0 = builder
        sp = argBase;                                            // drop the builder + the nargs args (keep the rest)
        spillLive(cb);                                           // the operand stack is x9.. (caller-saved): preserve any
        symbols.callHelper(cb, Symbols.SC_END);                  // live operand BELOW the args (e.g. a receiver pushed
        symbols.newStringFromBytes(cb);                          // before `a + b`) across these two BLs. x0 = mini String.
        reloadLive(cb);
        cb.emit(A64Enc.movReg(pushReg(), 0));                    // push the result String (at slot argBase)
    }

    /** Append one literal recipe byte {@code c} to the builder at operand-slot {@code sbIdx}. */
    private void appendChar(CodeBuffer cb, int sbIdx, int c)
    {
        cb.emit(A64Enc.movReg(0, opSlot(sbIdx)));
        cb.emitAll(A64Enc.loadImm64(1, c));
        spillLive(cb);                                           // the append helper clobbers x9.. (operand slots)
        symbols.callHelper(cb, Symbols.SC_CHAR);
        reloadLive(cb);
    }

    /** Append the arg at operand-slot {@code argSlot} (call-site arg {@code argIdx}) to the builder, by its kind. */
    private void appendArg(CodeBuffer cb, int sbIdx, int argSlot, int cpIndex, int argIdx)
    {
        int k = paramKind(cpIndex, argIdx);
        int helper;
        if (k == 'C')
        {
            helper = Symbols.SC_CHAR;
        }
        else if (k == 'I' || k == 'S' || k == 'B' || k == 'Z')
        {
            helper = Symbols.SC_INT;
        }
        else if (k == 'J')
        {
            helper = Symbols.SC_LONG;                            // long -> decimal
        }
        else if (k == 'L')
        {
            helper = Symbols.SC_STR;                             // String/byte[] -> its bytes (TIB-disambiguated)
        }
        else
        {
            symbols.fail(Symbols.FAIL_OPCODE, 0xBA, 2);          // unsupported concat arg type (D/F: later)
            return;
        }
        cb.emit(A64Enc.movReg(0, opSlot(sbIdx)));                // x0 = builder (fetched first, so a shared window
        cb.emit(A64Enc.movReg(1, opSlot(argSlot)));              // register can't clobber it) ; x1 = arg
        spillLive(cb);
        symbols.callHelper(cb, helper);
        reloadLive(cb);
    }

    /** Descriptor kind of param {@code j} of the {@code *ref} at {@code refCp}: a primitive char, or 'L' for a ref. */
    private int paramKind(int refCp, int j)
    {
        int descOff = ClassReader.refDescOff(classBytes, cpOff, refCp);
        int p = descOff + 3;                                     // past u2 length(2) + '(' (1)
        int idx = 0;
        while ((classBytes[p] & 0xFF) != ')')
        {
            boolean arr = false;
            while ((classBytes[p] & 0xFF) == '[')
            {
                arr = true;
                p = p + 1;
            }
            int c = classBytes[p] & 0xFF;
            if (c == 'L')
            {
                while ((classBytes[p] & 0xFF) != ';')
                {
                    p = p + 1;
                }
                p = p + 1;
            }
            else
            {
                p = p + 1;
            }
            if (idx == j)
            {
                return arr ? 'L' : c;
            }
            idx = idx + 1;
        }
        return 'V';
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
        throwStored(cb, pos, athrowStart);
    }

    /**
     * Dispatch the exception already stored in {@code $exception} (the throwing bytecode is at {@code pos},
     * whose machine code starts at word {@code athrowStart}): try each covering try/catch entry, else
     * unwind to a caller. Shared by explicit {@code athrow} and the implicit null/bounds checks. Never
     * falls through — every path branches to a handler or halts after the unwind call.
     */
    private void throwStored(CodeBuffer cb, int pos, int athrowStart)
    {
        if (symbols.captureTraces())
        {
            // Record the throw-site frame chain into the exception BEFORE the (possibly same-method) handler
            // search, so printStackTrace() has frames even when this method catches it inline. captureTrace
            // is idempotent (first throw wins), so a later cross-method unwind won't overwrite it.
            int te = pushReg();
            emitLoadException(cb, te);
            int tp = pushReg();
            symbols.codePc(cb, tp, athrowStart);
            int ts = pushReg();
            cb.emit(A64Enc.movFromSp(ts));
            emitCall(cb, 3, false, false, SYM_HELPER, Symbols.CAPTURE_TRACE);
        }
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
            int skip = cb.emit(A64Enc.cbz(cond, 0));
            emitCatch(cb, exHandlerPc[i]);                       // matched
            cb.set(skip, A64Enc.cbz(cond, cb.wordCount() - skip));
        }
        // no local handler: unwind the stack — unwind(exc, thisPC, SP)
        int exc = pushReg();
        emitLoadException(cb, exc);
        int pc = pushReg();
        symbols.codePc(cb, pc, athrowStart);                    // a PC inside this method (relocated by the writer)
        int sp = pushReg();
        cb.emit(A64Enc.movFromSp(sp));
        emitCall(cb, 3, false, false, SYM_HELPER, Symbols.UNWIND);
        emitHalt(cb);                                            // unwind never returns
    }

    // ----- implicit (JVM-synthesised) exceptions: null-deref -> NPE, bad index -> AIOOBE -----
    // Emitted only when symbols.implicitChecks() (the on-metal JIT); the image writer stays check-free.
    // Each check branches OVER an out-of-line throw block, so the common (in-bounds / non-null) path costs
    // one compare + one never-taken branch. The throw block synthesises the exception object and routes it
    // through the same handler-search + unwind as an explicit athrow, at this bytecode's PC.

    /** Allocate the exception (via {@code newHelper}) and throw it as if {@code athrow} occurred at {@code pos}. */
    private void throwImplicit(CodeBuffer cb, int pos, int newHelper)
    {
        int savedSp = sp;                                       // the throw block is off the fall-through path
        // The throw block is skipped at runtime (a never-taken branch guards it), so it must NOT perturb the
        // compiler's operand-stack model for the fall-through code that follows. sp is saved below; for deep
        // methods the circular-window residency map (regHolds) is likewise saved -- the block's spill/reload
        // (syncOut/syncIn) mutates it, but those instructions never execute on the non-throwing path.
        if (deepStack)
        {
            for (int i = 0; i < OP_MAX; i++) { savedHolds[i] = regHolds[i]; }   // (throwImplicit is not re-entrant)
        }
        int athrowStart = cb.wordCount();
        emitCall(cb, 0, true, false, SYM_HELPER, newHelper);    // -> exception object pushed
        emitStoreException(cb, popReg());                       // $exception = it
        sp = 0;                                                 // the JVM clears the operand stack when throwing
        throwStored(cb, pos, athrowStart);                      // handler search + unwind — never falls through
        sp = savedSp;                                           // restore the model for the code after the check
        if (deepStack)
        {
            for (int i = 0; i < OP_MAX; i++) { regHolds[i] = savedHolds[i]; }
        }
    }

    /** Throw ArithmeticException if the divisor {@code divReg} is zero (AArch64 SDIV/UDIV return 0, don't trap). */
    private void divisorCheck(CodeBuffer cb, int divReg, int pos)
    {
        if (!symbols.implicitChecks())
        {
            return;
        }
        int over = cb.emit(A64Enc.cbnz(divReg, 0));            // divisor != 0 -> skip the throw block
        throwImplicit(cb, pos, Symbols.NEW_ARITH);
        cb.set(over, A64Enc.cbnz(divReg, cb.wordCount() - over));
    }

    /** Throw NPE if {@code refReg} is null (a deref/receiver/arraylength of null). */
    private void nullCheck(CodeBuffer cb, int refReg, int pos)
    {
        if (!symbols.implicitChecks())
        {
            return;
        }
        int over = cb.emit(A64Enc.cbnz(refReg, 0));             // ref != null -> skip the throw block
        throwImplicit(cb, pos, Symbols.NEW_NPE);
        cb.set(over, A64Enc.cbnz(refReg, cb.wordCount() - over));
    }

    /** Null-check the array, then throw AIOOBE unless {@code indexReg} is in {@code [0, length)}. */
    private void boundsCheck(CodeBuffer cb, int arrReg, int indexReg, int pos)
    {
        if (!symbols.implicitChecks())
        {
            return;
        }
        nullCheck(cb, arrReg, pos);                             // a[i] on null -> NPE, not AIOOBE
        cb.emit(A64Enc.ldrx(16, arrReg, ObjectModel.ARRAY_LENGTH_OFFSET));   // x16 = length (scratch, no call before use)
        cb.emit(A64Enc.cmpReg(indexReg, 16));
        int over = cb.emit(A64Enc.bcond(A64Enc.LO, 0));         // unsigned index < length -> ok (negative -> huge -> throws)
        throwImplicit(cb, pos, Symbols.NEW_AIOOBE);
        cb.set(over, A64Enc.bcond(A64Enc.LO, cb.wordCount() - over));
    }

    /** Push the pending exception and branch to a handler (which expects it at depth 1). */
    private void emitCatch(CodeBuffer cb, int handlerPc)
    {
        emitLoadException(cb, pushReg());
        syncOut(cb);            // deep: spill the exception (slot 0) to memory; the handler reloads it via syncIn
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, handlerPc, FIX_B, 0);
        recordDepth(handlerPc);
        sp = 0;                                                  // fall-through (next check) resumes empty
    }

    private void emitHalt(CodeBuffer cb)
    {
        int h = cb.emit(A64Enc.wfe());
        cb.emit(A64Enc.b(h - cb.wordCount()));                      // spin
    }

    /** Magic.gc(): spill x19..x28 (+LR) so live refs are scannable, call the collector, restore. */
    private void lowerGc(CodeBuffer cb)
    {
        int frame = 96;                                          // 10 locals (80) + LR (8), 16-aligned
        cb.emit(A64Enc.subImm(31, 31, frame));
        cb.emit(A64Enc.strx(30, 31, 80));                          // save LR (we make a call)
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64Enc.strx(19 + i, 31, i * 8));    // spill x19..x28
        }
        cb.emit(A64Enc.movFromSp(0));                              // x0 = scanFrom (bottom of spilled regs)
        symbols.callHelper(cb, Symbols.GC_COLLECT);
        for (int i = 0; i < 10; i++)
        {
            cb.emit(A64Enc.ldrx(19 + i, 31, i * 8));    // restore
        }
        cb.emit(A64Enc.ldrx(30, 31, 80));
        cb.emit(A64Enc.addImm(31, 31, frame));
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
        if (deepStack)
        {
            marshalArgsFromMemory(cb, nargs);                    // load args straight from canonical memory
        }
        else
        {
            int[] src = new int[nargs];
            for (int k = 0; k < nargs; k++)
            {
                src[k] = popReg();    // src[0] = last arg (top of stack)
            }
            for (int k = 0; k < nargs; k++)
            {
                cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // -> x(argIndex)
            }
            spillLive(cb);                                       // preserve operand values below the args
        }
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
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
    }

    /**
     * Deep-method arg marshalling: spill the operand stack to memory (canonical), then load the top {@code
     * nargs} slots directly into {@code x0..x(nargs-1)} and pop them. Reading from memory rather than the
     * circular registers sidesteps the case where two args map to the same physical register (nargs &gt;
     * OP_MAX) or where an arg is a not-yet-resident hole. The receiver (if any) lands in x0.
     */
    private void marshalArgsFromMemory(CodeBuffer cb, int nargs)
    {
        syncOut(cb);                                            // every live slot now in opStackBase memory
        for (int k = 0; k < nargs; k++)
        {
            int slot = sp - 1 - k;                              // src[0] = top of stack -> highest arg reg
            cb.emit(A64Enc.ldrx(nargs - 1 - k, 31, opStackBase + slot * 8));
        }
        sp -= nargs;                                            // args consumed
    }

    // ----- arrays: [header][length @16][elements @24], element = base + index<<scale -----
    private void lowerNewArray(int atype, CodeBuffer cb)
    {
        loadConst(cb, arrayElemSize(atype));                     // push elemSize
        emitCall(cb, 2, true, false, SYM_HELPER, Symbols.HEAP_ALLOC_ARRAY); // (length,elemSize)->ref
        symbols.tagArray(cb, opSlot(sp - 1), atype, false);    // tag the result (top of stack) as its array Type
    }

    /**
     * anewarray: an array of references — the element is an 8-byte pointer, so it
     * allocates exactly like a {@code long[]}. The constant-pool operand names the
     * element class, but nothing needs it: element access ({@code aaload}/
     * {@code aastore}) is untyped, and array TIBs (for typed GC) are set later.
     */
    private void lowerAnewArray(CodeBuffer cb, int classCp)
    {
        loadConst(cb, ObjectModel.WORD);                        // 8-byte reference elements
        emitCall(cb, 2, true, false, SYM_HELPER, Symbols.HEAP_ALLOC_ARRAY); // (length,elemSize)->ref
        symbols.tagArray(cb, opSlot(sp - 1), classCp, true);  // tag the result as [L<element>;
    }

    private void arrayLength(CodeBuffer cb, int pos)
    {
        int arr = popReg();
        nullCheck(cb, arr, pos);                                 // arraylength of null -> NPE
        int r = pushReg();
        cb.emit(A64Enc.ldrx(r, arr, ObjectModel.ARRAY_LENGTH_OFFSET));
    }

    private void arrayLoad(CodeBuffer cb, int scale, int pos)
    {
        int index = popReg(), arr = popReg();
        boundsCheck(cb, arr, index, pos);                        // null/bounds before the raw deref
        int r = pushReg();                                       // r == arr's register
        int shift = scale == 4 ? 1 : scale;                      // short: 2-byte element (shift 1), signed load
        cb.emit(A64Enc.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64Enc.addRegLsl(arr, arr, index, shift));          // arr = &elem[index]
        cb.emit(scale == 0 ? A64Enc.ldrsb(r, arr, 0)                // byte (SIGN-ext — JVM baload semantics;
                                                                    //   stock String.utf8/countPositives branch
                                                                    //   on negative bytes, so zero-ext mis-decodes)
                : scale == 1 ? A64Enc.ldrh(r, arr, 0)               // char (zero-ext — unsigned)
                : scale == 4 ? A64Enc.ldrsh(r, arr, 0)              // short (sign-ext)
                : scale == 2 ? A64Enc.ldrsw(r, arr, 0)              // int (sign-ext)
                : A64Enc.ldrx(r, arr, 0));                          // long / ref
    }

    private void arrayStore(CodeBuffer cb, int scale, int pos)
    {
        int val = popReg();
        int index = popReg();
        int arr = popReg();
        boundsCheck(cb, arr, index, pos);                        // null/bounds before the raw store
        cb.emit(A64Enc.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64Enc.addRegLsl(arr, arr, index, scale));
        cb.emit(scale == 0 ? A64Enc.strb(val, arr, 0)
                : scale == 1 ? A64Enc.strh(val, arr, 0)             // char/short
                : scale == 2 ? A64Enc.strw(val, arr, 0)
                : A64Enc.strx(val, arr, 0));
    }

    /** newarray atype -> element size in bytes (JVMS Table 6.5.newarray-A). */
    private int arrayElemSize(int atype)
    {
        int size = atype == 4 || atype == 8 ? 1             // boolean, byte
                 : atype == 5 || atype == 9 ? 2             // char, short
                 : atype == 6 || atype == 10 ? 4            // float, int
                 : atype == 7 || atype == 11 ? 8            // double, long
                 : 0;
        if (size == 0)
        {
            symbols.fail(Symbols.FAIL_NEWARRAY_ATYPE, atype, 0);
        }
        return size;
    }

    /** Spill operand-stack values (x9..) to the frame so a call can't clobber them. */
    private void spillLive(CodeBuffer cb)
    {
        if (deepStack)
        {
            syncOut(cb);       // spill the whole circular window to its canonical memory homes
            return;
        }
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64Enc.strx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }
    private void reloadLive(CodeBuffer cb)
    {
        if (deepStack)
        {
            syncIn(cb);        // invalidate registers; reads reload from memory on demand
            return;
        }
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64Enc.ldrx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }

    /**
     * Emit a {@code magic/Magic} intrinsic, dispatched by its {@link Intrinsics} id
     * (resolved per world behind {@link Symbols#intrinsicId}). Branching on an int
     * rather than a {@code String} key keeps this compilable on metal.
     */
    private void lowerIntrinsic(int id, CodeBuffer cb)
    {
        if (id == Intrinsics.WFE)
        {
            cb.emit(A64Enc.wfe());
        }
        else if (id == Intrinsics.ISB)
        {
            cb.emit(A64Enc.isb());
        }
        else if (id == Intrinsics.SVC)
        {
            cb.emit(A64Enc.svc0());
        }
        else if (id == Intrinsics.SEV)
        {
            cb.emit(A64Enc.sev());
        }
        else if (id == Intrinsics.WRITE_MAIR_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.MAIR_EL1, popReg()));
        }
        else if (id == Intrinsics.WRITE_TCR_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.TCR_EL1, popReg()));
        }
        else if (id == Intrinsics.WRITE_TTBR0_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.TTBR0_EL1, popReg()));
        }
        else if (id == Intrinsics.TLBI_ALL)
        {
            cb.emit(A64Enc.tlbiVmalle1());
        }
        else if (id == Intrinsics.SPIN_LOCK)
        {
            int a = popReg();                               // address of the lock word
            cb.emit(A64Enc.movz(16, 1, 0));                 // x16 = 1 (value to store on acquire)
            cb.emit(A64Enc.ldaxrw(17, a));                  // retry: w17 = *lock  (acquire)
            cb.emit(A64Enc.cbnz(17, -1));                   // held -> spin
            cb.emit(A64Enc.stlxrw(17, 16, a));              // try to store 1; w17 = status
            cb.emit(A64Enc.cbnz(17, -3));                   // store failed -> retry (back to ldaxr)
        }
        else if (id == Intrinsics.SPIN_UNLOCK)
        {
            cb.emit(A64Enc.stlrw(31, popReg()));            // STLR wzr, [lock]  (release)
        }
        else if (id == Intrinsics.DSB)
        {
            cb.emit(A64Enc.dsb());
        }
        else if (id == Intrinsics.DC_CVAU)
        {
            cb.emit(A64Enc.dcCvau(popReg()));      // clean the D-cache line at the address arg
        }
        else if (id == Intrinsics.DC_CVAC)
        {
            cb.emit(A64Enc.dcCvac(popReg()));      // clean the D-cache line to PoC at the address arg
        }
        else if (id == Intrinsics.IC_IALLU)
        {
            cb.emit(A64Enc.icIallu());
        }
        else if (id == Intrinsics.DC_CIVAC)
        {
            cb.emit(A64Enc.dcCivac(popReg()));     // clean+invalidate the D-cache line to PoC at the address arg
        }
        else if (id == Intrinsics.READ_CURRENT_EL)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.CurrentEL));
        }
        else if (id == Intrinsics.READ_MPIDR)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.MPIDR_EL1));
        }
        else if (id == Intrinsics.SPAWN)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.SPAWN);       // (runnable) -> void
        }
        else if (id == Intrinsics.SEM_WAIT)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.SEM_WAIT);    // (sem) -> void
        }
        else if (id == Intrinsics.SEM_POST)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.SEM_POST);    // (sem) -> void
        }
        else if (id == Intrinsics.SLEEP_MS)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.SLEEP_MS);    // (ms) -> void
        }
        else if (id == Intrinsics.NEW_SEM)
        {
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.NEW_SEM);      // (initial) -> int
        }
        else if (id == Intrinsics.REPORT)
        {
            emitCall(cb, 2, false, false, SYM_HELPER, Symbols.REPORT);      // (who, state) -> void
        }
        else if (id == Intrinsics.PRINT_STR)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.PRINT_STR);   // (string) -> void
        }
        else if (id == Intrinsics.WRITE_VBAR_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.VBAR_EL1, popReg()));
        }
        else if (id == Intrinsics.READ_ESR_EL1)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.ESR_EL1));
        }
        else if (id == Intrinsics.READ_ELR_EL1)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.ELR_EL1));
        }
        else if (id == Intrinsics.READ_FAR_EL1)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.FAR_EL1));
        }
        else if (id == Intrinsics.READ_CNTFRQ_EL0)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.CNTFRQ_EL0));
        }
        else if (id == Intrinsics.READ_CNTPCT_EL0)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.CNTPCT_EL0));
        }
        else if (id == Intrinsics.WRITE_CNTP_TVAL_EL0)
        {
            cb.emit(A64Enc.msr(A64Enc.CNTP_TVAL_EL0, popReg()));
        }
        else if (id == Intrinsics.WRITE_CNTP_CTL_EL0)
        {
            cb.emit(A64Enc.msr(A64Enc.CNTP_CTL_EL0, popReg()));
        }
        else if (id == Intrinsics.ENABLE_IRQ)
        {
            cb.emit(A64Enc.msrDaifClr(3));                  // unmask IRQ + FIQ (DAIF.I and .F)
        }
        else if (id == Intrinsics.READ_DAIF)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.DAIF));
        }
        else if (id == Intrinsics.DISABLE_IRQ)
        {
            cb.emit(A64Enc.msrDaifSet(3));
        }
        else if (id == Intrinsics.READ_CNTP_CTL_EL0)
        {
            cb.emit(A64Enc.mrs(pushReg(), A64Enc.CNTP_CTL_EL0));
        }
        else if (id == Intrinsics.GC)
        {
            lowerGc(cb);
        }
        else if (id == Intrinsics.CALL0)
        {
            int addr = popReg();
            cb.emit(A64Enc.blr(addr));
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
        else if (id == Intrinsics.CALL2)
        // addr, a->x0, b->x1, blr, result x0
        {
            int b = popReg();
            int a = popReg();
            int addr = popReg();
            cb.emit(A64Enc.movReg(16, addr));
            cb.emit(A64Enc.movReg(0, a));
            cb.emit(A64Enc.movReg(1, b));
            cb.emit(A64Enc.blr(16));
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
        else if (id == Intrinsics.CALL_N)
        // (addr, argsPtr): x0..x7 <- [argsPtr..+56], blr addr, result x0. argsPtr/addr live in operand-stack
        // registers (x9..x15), so loading the argument registers x0..x7 cannot clobber them.
        {
            int argsPtr = popReg();
            int addr = popReg();
            cb.emit(A64Enc.movReg(16, addr));               // stash target in x16 before x0..x7 are loaded
            for (int r = 0; r < 8; r++)
            {
                cb.emit(A64Enc.ldrx(r, argsPtr, r * 8));    // xr <- [argsPtr + r*8]
            }
            cb.emit(A64Enc.blr(16));
            cb.emit(A64Enc.movReg(pushReg(), 0));           // result x0
        }
        else if (id == Intrinsics.ERET)
        {
            cb.emit(A64Enc.eret());
        }
        else if (id == Intrinsics.DROP_TO_EL1)
        {
            lowerDropToEL1(cb);
        }

        else if (id == Intrinsics.WRITE_HCR_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.HCR_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_CPTR_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.CPTR_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_CNTHCTL_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.CNTHCTL_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_CNTVOFF_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.CNTVOFF_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_SCTLR_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.SCTLR_EL1, popReg()));
        }
        else if (id == Intrinsics.WRITE_SPSR_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.SPSR_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_ELR_EL2)
        {
            cb.emit(A64Enc.msr(A64Enc.ELR_EL2, popReg()));
        }
        else if (id == Intrinsics.WRITE_CPACR_EL1)
        {
            cb.emit(A64Enc.msr(A64Enc.CPACR_EL1, popReg()));
        }
        else if (id == Intrinsics.WRITE_SP)
        {
            cb.emit(A64Enc.movToSp(popReg()));
        }
        else if (id == Intrinsics.READ_SP)
        {
            cb.emit(A64Enc.movFromSp(pushReg()));
        }
        else if (id == Intrinsics.READ_LR)
        {
            cb.emit(A64Enc.movReg(pushReg(), 30));             // x30 (link register) = caller return address
        }
        else if (id == Intrinsics.READ_X0)
        {
            cb.emit(A64Enc.movReg(pushReg(), 0));              // x0 = the faulting call's receiver (trap diagnostic)
        }
        else if (id == Intrinsics.RESUME)
        // restore the handler's callee-saved locals, exc->x9, SP=sp, br pc (no return)
        {
            int locBuf = popReg();                              // base of the reconstructed-locals buffer
            int nloc = popReg();                                // regLocals of the handler's method
            int exc = popReg();
            int spv = popReg();
            int pc = popReg();
            cb.emit(A64Enc.movReg(16, pc));                     // x16 = target (x9 gets clobbered below)
            cb.emit(A64Enc.movReg(17, spv));                    // x17 = handler frame SP (installed as the new SP)
            cb.emit(A64Enc.movReg(18, locBuf));                 // x18 = reconstructed-locals buffer base
            // Restore ALL callee-saved registers x19..x28 from [x18 + k*8]. The unwinder rebuilt the full
            // callee-saved state at the handler-frame level: slots [0..handler.regLocals-1] are the handler's
            // own pre-try locals (so a catch reads the live value), and the HIGHER slots hold the handler's
            // CALLER's live registers that the popped frames clobbered but neither the handler nor the popped
            // frames' (skipped) epilogues restored -- restoring only the handler's `nloc` left those clobbered,
            // so a caller local held in x(19+nloc.. ) came back garbage (a leaked code address). `nloc` is now
            // unused but still consumed (a 0 for a handler with no reg-locals is harmless: every slot is seeded).
            int k = 0;
            while (k < LOC_MAX)
            {
                cb.emit(A64Enc.ldrx(LOC_BASE + k, 18, k * 8));
                k += 1;
            }
            cb.emit(A64Enc.movReg(9, exc));                     // exception -> handler's stack slot (x9)
            cb.emit(A64Enc.movToSp(17));
            cb.emit(A64Enc.br(16));
        }

        else if (id == Intrinsics.STORE32)
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64Enc.strw(val, addr, 0));
        }
        else if (id == Intrinsics.STORE8)
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64Enc.strb(val, addr, 0));
        }
        else if (id == Intrinsics.STORE64)
        {
            int val = popReg();
            int addr = popReg();
            cb.emit(A64Enc.strx(val, addr, 0));
        }
        else if (id == Intrinsics.LOAD32)
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64Enc.ldrw(r, addr, 0));
        }
        else if (id == Intrinsics.LOAD8)
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64Enc.ldrb(r, addr, 0));
        }
        else if (id == Intrinsics.ADDR_OF || id == Intrinsics.FROM_ADDR)
        {
            int a = popReg();                   // reference<->address are the same value (no handles/compressed oops)
            int d = pushReg();
            if (d != a)
            {
                cb.emit(A64Enc.movReg(d, a));   // reinterpret in place (pop/push land on the same slot)
            }
        }
        else if (id == Intrinsics.MON_WAIT)
        {
            emitCall(cb, 2, false, false, SYM_HELPER, Symbols.MON_WAIT);    // (obj, ms) -> void
        }
        else if (id == Intrinsics.MON_NOTIFY)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.MON_NOTIFY);  // (obj) -> void
        }
        else if (id == Intrinsics.MON_NOTALL)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.MON_NOTALL);  // (obj) -> void
        }
        else if (id == Intrinsics.THREAD_JOIN)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.THREAD_JOIN); // (threadObj) -> void
        }
        else if (id == Intrinsics.STACK_TRACE)
        {
            // thread receiver is on top; append this call site's PC + SP so a self-trace walks from here
            int tp = pushReg();
            symbols.codePc(cb, tp, cb.wordCount());
            int ts = pushReg();
            cb.emit(A64Enc.movFromSp(ts));
            emitCall(cb, 3, true, false, SYM_HELPER, Symbols.STACK_TRACE);  // (thread, pc, sp) -> StackTraceElement[]
        }
        else if (id == Intrinsics.ALL_THREADS)
        {
            emitCall(cb, 0, true, false, SYM_HELPER, Symbols.ALL_THREADS);  // () -> Thread[]
        }
        else if (id == Intrinsics.HOLDS_LOCK)
        {
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.HOLDS_LOCK);   // (obj) -> int (1 = held by us)
        }
        else if (id == Intrinsics.INTR)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.INTERRUPT);   // (thread) -> void
        }
        else if (id == Intrinsics.IS_INTR)
        {
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.IS_INTERRUPTED);  // (thread) -> int
        }
        else if (id == Intrinsics.WAS_INTR)
        {
            emitCall(cb, 0, true, false, SYM_HELPER, Symbols.CHECK_INTR);   // () -> int (reads + clears)
        }
        else if (id == Intrinsics.IS_ALIVE)
        {
            emitCall(cb, 1, true, false, SYM_HELPER, Symbols.IS_ALIVE);     // (thread) -> int
        }
        else if (id == Intrinsics.JOIN_TIMED)
        {
            emitCall(cb, 2, true, false, SYM_HELPER, Symbols.JOIN_TIMED);   // (thread, millis) -> int status
        }
        else if (id == Intrinsics.PARK)
        {
            emitCall(cb, 0, false, false, SYM_HELPER, Symbols.PARK);        // () -> void
        }
        else if (id == Intrinsics.UNPARK)
        {
            emitCall(cb, 1, false, false, SYM_HELPER, Symbols.UNPARK);      // (thread) -> void
        }
        else if (id == Intrinsics.LOAD64)
        {
            int addr = popReg();
            int r = pushReg();
            cb.emit(A64Enc.ldrx(r, addr, 0));
        }

        else if (id == Intrinsics.BYTES)
        {
            /* no-op: the operand is already an interned byte[] ref */;
        }

        else
        {
            symbols.fail(Symbols.FAIL_INTRINSIC_ID, id, 0);
        }
    }

    private void lowerDropToEL1(CodeBuffer cb)
    {
        expectEmpty(Symbols.SITE_DROP_TO_EL1);
        cb.emit(A64Enc.mrs(0, A64Enc.CurrentEL));
        int tbz = cb.emit(A64Enc.tbz(0, 3, 0));
        set64(cb, 0, 0x8000_0000L);
        cb.emit(A64Enc.msr(A64Enc.HCR_EL2, 0));
        set64(cb, 0, 0x33FFL);
        cb.emit(A64Enc.msr(A64Enc.CPTR_EL2, 0));
        set64(cb, 0, 0x3L);
        cb.emit(A64Enc.msr(A64Enc.CNTHCTL_EL2, 0));
        cb.emit(A64Enc.msr(A64Enc.CNTVOFF_EL2, A64Enc.XZR));
        set64(cb, 0, 0x30D0_0800L);
        cb.emit(A64Enc.msr(A64Enc.SCTLR_EL1, 0));
        set64(cb, 0, 0x3C5L);
        cb.emit(A64Enc.msr(A64Enc.SPSR_EL2, 0));
        int elr = cb.reserveAddr(0);
        cb.emit(A64Enc.msr(A64Enc.ELR_EL2, 0));
        cb.emit(A64Enc.eret());
        int cont = cb.wordCount();
        cb.set(tbz, A64Enc.tbz(0, 3, cont - tbz));
        cb.patchAddr(elr, 0, cb.pcAt(cont));
    }

    private boolean isNonLeaf(byte[] code)
    {
        int pos = 0;
        while (pos < code.length)
        {
            int op = code[pos] & 0xFF;
            if (op == 0xBB || op == 0xBC || op == 0xBD || op == 0xB6 || op == 0xB9 || op == 0xBA || op == 0xBF || op == 0xC0 || op == 0xC1)
            {
                return true;    // new/newarray/anewarray/invokevirtual/invoke{interface,dynamic}/athrow/checkcast/instanceof
            }
            // With implicit checks on, a deref/index emits a BL to newNpe/newAioobe on its throw path — so a
            // method with getfield/putfield/arraylength/array-load/store is non-leaf and must save LR (else a
            // cross-method unwind can't read its return address). Image code (checks off) is unaffected.
            if (symbols.implicitChecks()
                && (op == 0xB4 || op == 0xB5 || op == 0xBE
                    || (op >= 0x2E && op <= 0x35) || (op >= 0x4F && op <= 0x56)))
            {
                return true;
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
    /**
     * Net operand-stack change of one bytecode (this VM keeps EVERY value -- longs/doubles included -- in a
     * single register/slot, so all deltas are category-1). Used by {@link #computeDepths} to pre-compute the
     * actual operand depth at every bytecode, which decides whether the method needs the deep-stack spill path
     * ({@link #deepStack}) -- keyed off ACTUAL depth, not the classfile's declared max_stack.
     */
    private int stackDelta(int op, byte[] code, int pos)
    {
        if (op >= 0x02 && op <= 0x2D) { return 1; }        // *const / bipush..ldc2_w / *load / *load_<n>
        if (op == 0x01) { return 1; }                      // aconst_null
        if (op >= 0x2E && op <= 0x35) { return -1; }       // *aload (pop arrayref+index, push value)
        if (op >= 0x36 && op <= 0x4E) { return -1; }       // *store / *store_<n>
        if (op >= 0x4F && op <= 0x56) { return -3; }       // *astore (pop arrayref+index+value)
        if (op == 0x57) { return -1; }                     // pop
        if (op == 0x58) { return -2; }                     // pop2 (category-1 form)
        if (op == 0x59 || op == 0x5A || op == 0x5B) { return 1; }   // dup / dup_x1 / dup_x2
        if (op == 0x5C || op == 0x5D || op == 0x5E) { return 2; }   // dup2 / dup2_x1 / dup2_x2
        if (op == 0x5F) { return 0; }                      // swap
        if (op >= 0x60 && op <= 0x73) { return -1; }       // add/sub/mul/div/rem (i,l,f,d)
        if (op >= 0x74 && op <= 0x77) { return 0; }        // neg
        if (op >= 0x78 && op <= 0x83) { return -1; }       // shl/shr/ushr/and/or/xor
        if (op == 0x84) { return 0; }                      // iinc
        if (op >= 0x85 && op <= 0x93) { return 0; }        // i2l..i2s conversions (1 slot -> 1 slot)
        if (op >= 0x94 && op <= 0x98) { return -1; }       // lcmp / fcmp / dcmp
        if (op >= 0x99 && op <= 0x9E) { return -1; }       // ifeq..ifle
        if (op >= 0x9F && op <= 0xA6) { return -2; }       // if_icmp* / if_acmp*
        if (op == 0xA7 || op == 0xC8) { return 0; }        // goto / goto_w
        if (op == 0xAA || op == 0xAB) { return -1; }       // tableswitch / lookupswitch (pop index)
        if (op >= 0xAC && op <= 0xB0) { return -1; }       // ireturn..areturn
        if (op == 0xB1) { return 0; }                      // return
        if (op == 0xB2) { return 1; }                      // getstatic
        if (op == 0xB3) { return -1; }                     // putstatic
        if (op == 0xB4) { return 0; }                      // getfield (pop objref, push value)
        if (op == 0xB5) { return -2; }                     // putfield
        if (op == 0xB6 || op == 0xB7 || op == 0xB9)        // invoke virtual/special/interface (has receiver)
        {
            int cp = u2(code, pos + 1);
            return (returnsValue(cp) ? 1 : 0) - paramCount(cp) - 1;
        }
        if (op == 0xB8 || op == 0xBA)                      // invokestatic / invokedynamic (no receiver)
        {
            int cp = u2(code, pos + 1);
            return (returnsValue(cp) ? 1 : 0) - paramCount(cp);
        }
        if (op == 0xBB) { return 1; }                      // new
        if (op == 0xBC || op == 0xBD || op == 0xBE) { return 0; }   // newarray / anewarray / arraylength
        if (op == 0xBF) { return -1; }                     // athrow (then unwinds -- no fallthrough successor)
        if (op == 0xC0 || op == 0xC1) { return 0; }        // checkcast / instanceof
        if (op == 0xC2 || op == 0xC3) { return -1; }       // monitorenter / monitorexit
        if (op == 0xC4)                                    // wide: iinc=0, load=+1, store=-1
        {
            int w = code[pos + 1] & 0xFF;
            return w == 0x84 ? 0 : (w >= 0x36 ? -1 : 1);
        }
        if (op == 0xC5) { return 1 - (code[pos + 3] & 0xFF); }      // multianewarray: -dims + 1
        if (op == 0xC6 || op == 0xC7) { return -1; }       // ifnull / ifnonnull
        if (op == 0xC9) { return 1; }                      // jsr_w
        if (op == 0xA8) { return 1; }                      // jsr
        return 0;                                          // nop, ret, and anything else
    }

    /**
     * Pre-pass: the actual operand-stack depth entering each bytecode, by forward data-flow (valid bytecode has
     * one depth per pc; the JVM verifier guarantees it). Sets {@link #deepStack}/frame sizing from the real
     * peak depth -- so a method whose DECLARED max_stack exceeds the register budget but never actually goes
     * deep (e.g. vm/VM.run) stays on the fast register-only path. Returns depth[] (-1 = unreached).
     */
    private int[] computeDepths(byte[] code)
    {
        int[] depth = new int[code.length];
        long[] mask = new long[code.length];               // wide-mask entering each pc: bit i = slot i is long/double
        wideTop = new boolean[code.length];
        int[] work = new int[code.length + 8];
        preDepth = depth; preMask = mask; preWork = work;   // expose to the seed helpers (keeps calls <= OP_MAX)
        int wc = 0;
        for (int k = 0; k < code.length; k++) { depth[k] = -1; }
        depth[0] = 0; mask[0] = 0L;
        work[wc++] = 0;
        for (int k = 0; k < exCount; k++)                  // handler entry: the caught exception (depth 1, a ref)
        {
            int h = exHandlerPc[k];
            if (depth[h] < 0) { depth[h] = 1; mask[h] = 0L; work[wc++] = h; }
        }
        int peak = 0;
        while (wc > 0)
        {
            int pc = work[--wc];
            int d = depth[pc];
            long m = mask[pc];
            int op = code[pc] & 0xFF;
            boolean tw = d > 0 && ((m >>> (d - 1)) & 1L) != 0L;     // is the top operand a long/double?
            wideTop[pc] = tw;
            int after = d + stackDeltaW(op, code, pc, tw);
            long am = maskAfter(op, code, pc, d, m, tw);
            if (after > peak) { peak = after; }
            int len = opLen(op, code, pc);
            if (op == 0xC8 || op == 0xC9) { len = 5; }
            boolean fall = fallsThrough(op);
            if (fall && pc + len < code.length && depth[pc + len] < 0)
            {
                depth[pc + len] = after; mask[pc + len] = am; work[wc++] = pc + len;
            }
            // branch/switch targets get the POST-op depth+mask too (their operands were already consumed).
            // depth/mask/work live in fields (preDepth/preMask/preWork) so these seed calls stay <= OP_MAX,
            // keeping this hot pre-pass register-only (see the field decls for why this is a perf choice, not
            // a correctness workaround -- the underlying [J/[D-param bug is fixed in emitPrologue).
            if ((op >= 0x99 && op <= 0x9E) || (op >= 0x9F && op <= 0xA6) || op == 0xC6 || op == 0xC7 || op == 0xA7)
            {
                int t = pc + s2(code, pc + 1);
                wc = seedDepth(wc, t, after, am);
            }
            else if (op == 0xC8) { int t = pc + s4(code, pc + 1); wc = seedDepth(wc, t, after, am); }
            else if (op == 0xAA) { wc = tableTargets(code, pc, after, am, wc); }
            else if (op == 0xAB) { wc = lookupTargets(code, pc, after, am, wc); }
        }
        this.maxActualDepth = peak;
        return depth;
    }

    /** Seed {@code preDepth[target]=d}/{@code preMask[target]=m} if unset and enqueue it; returns the new worklist count. */
    private int seedDepth(int wc, int target, int d, long m)
    {
        if (target >= 0 && target < preDepth.length && preDepth[target] < 0)
        {
            preDepth[target] = d; preMask[target] = m; preWork[wc++] = target;
        }
        return wc;
    }
    private int tableTargets(byte[] code, int pc, int d, long m, int wc)
    {
        int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
        int lo = s4(code, p + 4);
        int hi = s4(code, p + 8);
        int t0 = pc + s4(code, p);                                                      // default
        wc = seedDepth(wc, t0, d, m);
        for (int i = 0; i <= hi - lo; i++) { int ti = pc + s4(code, p + 12 + i * 4); wc = seedDepth(wc, ti, d, m); }
        return wc;
    }
    private int lookupTargets(byte[] code, int pc, int d, long m, int wc)
    {
        int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
        int n = s4(code, p + 4);
        int t0 = pc + s4(code, p);                                                      // default
        wc = seedDepth(wc, t0, d, m);
        for (int i = 0; i < n; i++) { int ti = pc + s4(code, p + 8 + i * 8 + 4); wc = seedDepth(wc, ti, d, m); }
        return wc;
    }

    // ----- operand wide-tracking (category-2 = long/double) --------------------------------------------
    // This VM keeps a long/double in ONE operand slot, but the JVM's dup2/pop2/dup2_x* have DIFFERENT stack
    // effects for a category-2 value (one long/double) vs two category-1 values. computeDepths abstract-
    // interprets a wide-mask (which slots hold a long/double) so those ops get the right depth AND so the
    // codegen (via wideTop[pc]) emits the 1-slot form for a long. Without this, `dup2` of a long duplicated the
    // slot BELOW it too -- corrupting the stack (e.g. String.join's length check compared garbage -> false OOM).

    /** Net stack change, wide-aware: pop2/dup2/dup2_x1/dup2_x2 differ when the top is a category-2 long/double. */
    private int stackDeltaW(int op, byte[] code, int pos, boolean topWide)
    {
        if (op == 0x58) { return topWide ? -1 : -2; }                              // pop2
        if (op == 0x5C || op == 0x5D || op == 0x5E) { return topWide ? 1 : 2; }    // dup2 / dup2_x1 / dup2_x2
        return stackDelta(op, code, pos);
    }

    private static boolean mbit(long m, int i) { return i >= 0 && ((m >>> i) & 1L) != 0L; }
    private static long mput(long m, int i, boolean v) { return v ? (m | (1L << i)) : (m & ~(1L << i)); }

    /** The wide-mask entering the NEXT bytecode (bit i set iff operand slot i holds a long/double). */
    private long maskAfter(int op, byte[] code, int pos, int d, long m, boolean tw)
    {
        if (op == 0x59) { return mput(m, d, mbit(m, d - 1)); }                     // dup
        if (op == 0x5F)                                                            // swap (both category-1)
        {
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2);
            return mput(mput(m, d - 1, v2), d - 2, v1);
        }
        if (op == 0x5A)                                                            // dup_x1: v2,v1 -> v1,v2,v1
        {
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2);
            m = mput(m, d - 2, v1); m = mput(m, d - 1, v2); return mput(m, d, v1);
        }
        if (op == 0x5B)                                                            // dup_x2: v3,v2,v1 -> v1,v3,v2,v1
        {
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2), v3 = mbit(m, d - 3);
            m = mput(m, d - 3, v1); m = mput(m, d - 2, v3); m = mput(m, d - 1, v2); return mput(m, d, v1);
        }
        if (op == 0x5C)                                                            // dup2
        {
            if (tw) { return mput(m, d, true); }                                   // cat-2: dup the one long slot
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2);                       // cat-1: dup top two
            m = mput(m, d, v2); return mput(m, d + 1, v1);
        }
        if (op == 0x5D)                                                            // dup2_x1
        {
            if (tw)                                                                // cat-2 long over one cat-1 (= dup_x1)
            {
                boolean w = mbit(m, d - 1), u = mbit(m, d - 2);
                m = mput(m, d - 2, w); m = mput(m, d - 1, u); return mput(m, d, w);
            }
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2), v3 = mbit(m, d - 3);  // cat-1 two over one
            m = mput(m, d - 3, v2); m = mput(m, d - 2, v1); m = mput(m, d - 1, v3);
            m = mput(m, d, v2); return mput(m, d + 1, v1);
        }
        if (op == 0x5E)                                                            // dup2_x2 (rare; cat-1 four-slot form)
        {
            boolean v1 = mbit(m, d - 1), v2 = mbit(m, d - 2), v3 = mbit(m, d - 3), v4 = mbit(m, d - 4);
            m = mput(m, d - 4, v2); m = mput(m, d - 3, v1); m = mput(m, d - 2, v4); m = mput(m, d - 1, v3);
            m = mput(m, d, v2); return mput(m, d + 1, v1);
        }
        // generic: pop P slots off the top, then (maybe) push one value
        int p = pops(op, code, pos);
        int nd = d - p;
        if (nd < 0) { nd = 0; }
        long r = m & (nd >= 64 ? -1L : (1L << nd) - 1L);                            // clear the popped (top) bits
        int pw = pushW(op, code, pos);
        if (pw >= 0) { r = mput(r, nd, pw == 1); }
        return r;
    }

    /** Number of operands {@code op} pops (matches Baseline's popReg count / DepthScan). */
    private int pops(int op, byte[] code, int pos)
    {
        if (op >= 0x2E && op <= 0x35) { return 2; }        // *aload (arrayref, index)
        if (op >= 0x36 && op <= 0x4E) { return 1; }        // *store
        if (op >= 0x4F && op <= 0x56) { return 3; }        // *astore
        if (op == 0x57) { return 1; }                      // pop
        if (op >= 0x60 && op <= 0x73) { return 2; }        // add/sub/mul/div/rem
        if (op >= 0x74 && op <= 0x77) { return 1; }        // neg
        if (op >= 0x78 && op <= 0x83) { return 2; }        // shl/shr/ushr/and/or/xor
        if (op >= 0x85 && op <= 0x93) { return 1; }        // conversions
        if (op >= 0x94 && op <= 0x98) { return 2; }        // lcmp/fcmp/dcmp
        if (op >= 0x99 && op <= 0x9E) { return 1; }        // if<cond>
        if (op >= 0x9F && op <= 0xA6) { return 2; }        // if_icmp / if_acmp
        if (op == 0xAA || op == 0xAB) { return 1; }        // tableswitch / lookupswitch
        if (op >= 0xAC && op <= 0xB0) { return 1; }        // *return (value)
        if (op == 0xB3) { return 1; }                      // putstatic
        if (op == 0xB4) { return 1; }                      // getfield (objref)
        if (op == 0xB5) { return 2; }                      // putfield
        if (op == 0xB6 || op == 0xB7 || op == 0xB9) { return paramCount(u2(code, pos + 1)) + 1; }
        if (op == 0xB8 || op == 0xBA) { return paramCount(u2(code, pos + 1)); }
        if (op == 0xBC || op == 0xBD || op == 0xBE) { return 1; }   // newarray/anewarray/arraylength
        if (op == 0xBF) { return 1; }                      // athrow
        if (op == 0xC0 || op == 0xC1) { return 1; }        // checkcast/instanceof
        if (op == 0xC2 || op == 0xC3) { return 1; }        // monitorenter/exit
        if (op == 0xC5) { return code[pos + 3] & 0xFF; }   // multianewarray dims
        if (op == 0xC6 || op == 0xC7) { return 1; }        // ifnull/ifnonnull
        if (op == 0xC4) { int w = code[pos + 1] & 0xFF; return (w >= 0x36 && w != 0x84) ? 1 : 0; }   // wide store
        return 0;
    }

    /** Whether {@code op} pushes a value, and if so whether it is wide: -1 = pushes nothing, 0 = non-wide, 1 = wide. */
    private int pushW(int op, byte[] code, int pos)
    {
        if (op == 0x09 || op == 0x0A || op == 0x0E || op == 0x0F || op == 0x14 || op == 0x16 || op == 0x18) { return 1; }  // l/d const/ldc2_w/lload/dload
        if (op >= 0x1E && op <= 0x21) { return 1; }        // lload_0-3
        if (op >= 0x26 && op <= 0x29) { return 1; }        // dload_0-3
        if (op == 0x2F || op == 0x31) { return 1; }        // laload / daload
        if (op == 0x61 || op == 0x65 || op == 0x69 || op == 0x6D || op == 0x71) { return 1; }  // ladd/lsub/lmul/ldiv/lrem
        if (op == 0x63 || op == 0x67 || op == 0x6B || op == 0x6F || op == 0x73) { return 1; }  // dadd/dsub/dmul/ddiv/drem
        if (op == 0x75 || op == 0x77) { return 1; }        // lneg / dneg
        if (op == 0x79 || op == 0x7B || op == 0x7D) { return 1; }   // lshl/lshr/lushr
        if (op == 0x7F || op == 0x81 || op == 0x83) { return 1; }   // land/lor/lxor
        if (op == 0x85 || op == 0x87 || op == 0x8A || op == 0x8C || op == 0x8D || op == 0x8F) { return 1; }  // i2l,i2d,l2d,f2l,f2d,d2l
        if (op == 0xB2 || op == 0xB4) { return fieldIsWide(u2(code, pos + 1)) ? 1 : 0; }        // getstatic / getfield
        if (op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9 || op == 0xBA)                 // invoke*
        {
            int cp = u2(code, pos + 1);
            if (!returnsValue(cp)) { return -1; }
            return returnIsWide(cp) ? 1 : 0;
        }
        if (op == 0xC4)                                    // wide: lload/dload push wide, other loads non-wide, store/iinc none
        {
            int w = code[pos + 1] & 0xFF;
            if (w == 0x84 || (w >= 0x36 && w <= 0x39) || w == 0x3A) { return -1; }   // iinc / stores
            return (w == 0x16 || w == 0x18) ? 1 : 0;
        }
        // pushes exactly one non-wide value:
        if (op == 0x01) { return 0; }                      // aconst_null
        if (op >= 0x02 && op <= 0x08) { return 0; }        // iconst
        if (op == 0x0B || op == 0x0C || op == 0x0D) { return 0; }   // fconst
        if (op == 0x10 || op == 0x11 || op == 0x12 || op == 0x13) { return 0; }     // bipush/sipush/ldc/ldc_w
        if (op == 0x15 || op == 0x17 || op == 0x19) { return 0; }   // iload/fload/aload
        if (op >= 0x1A && op <= 0x1D) { return 0; }        // iload_0-3
        if (op >= 0x22 && op <= 0x25) { return 0; }        // fload_0-3
        if (op >= 0x2A && op <= 0x2D) { return 0; }        // aload_0-3
        if (op == 0x2E || op == 0x30 || (op >= 0x32 && op <= 0x35)) { return 0; }   // i/f/a/b/c/s aload
        if (op >= 0x60 && op <= 0x72 && (op & 1) == 0) { return 0; }   // i*/f* add/sub/mul/div/rem (even opcodes)
        if (op == 0x74 || op == 0x76) { return 0; }        // ineg / fneg
        if (op == 0x78 || op == 0x7A || op == 0x7C || op == 0x7E || op == 0x80 || op == 0x82) { return 0; }  // i shifts/logic
        if (op == 0x86 || op == 0x88 || op == 0x89 || op == 0x8B || op == 0x8E || op == 0x90) { return 0; }  // i2f,l2i,l2f,f2i,d2i,d2f
        if (op >= 0x91 && op <= 0x98) { return 0; }        // i2b/i2c/i2s / lcmp/fcmp/dcmp
        if (op == 0xBB || op == 0xC0 || op == 0xC1 || op == 0xC5) { return 0; }     // new/checkcast/instanceof/multianewarray
        return -1;                                         // stores, pop*, returns, branches, put*, void: no push
    }

    private boolean returnIsWide(int refCp)
    {
        int k = ClassReader.descReturnKind(classBytes, ClassReader.refDescOff(classBytes, cpOff, refCp));
        return k == 'J' || k == 'D';
    }
    private boolean fieldIsWide(int fieldCp)
    {
        int off = ClassReader.refDescOff(classBytes, cpOff, fieldCp);   // descriptor Utf8 (u2 length, then bytes)
        int c = ClassReader.u1(classBytes, off + 2);                    // first field-descriptor char
        return c == 'J' || c == 'D';
    }

    public static int opLen(int op, byte[] code, int pos)
    {
        // 2-byte: bipush/ldc/iload/lload/aload/fload/dload/istore/lstore/astore/fstore/dstore/newarray
        if (op == 0x10 || op == 0x12 || op == 0x15 || op == 0x16 || op == 0x19 || op == 0x17 || op == 0x18
            || op == 0x36 || op == 0x37 || op == 0x3A || op == 0x38 || op == 0x39 || op == 0xBC)
        {
            return 2;
        }
        if (op == 0xB9 || op == 0xBA)                        // invokeinterface (idx,count,0) / invokedynamic (idx,0)
        {
            return 5;
        }
        // 3-byte: sipush/ldc_w/ldc2_w/iinc/if*/goto/get-put static-field/invoke*/new/anewarray/checkcast/instanceof
        if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || op == 0x99
            || op == 0x9A || op == 0x9B || op == 0x9C || op == 0x9D || op == 0x9E
            || op == 0x9F || op == 0xA0 || op == 0xA1 || op == 0xA2 || op == 0xA3
            || op == 0xA4 || op == 0xA5 || op == 0xA6 || op == 0xA7 || op == 0xB2 || op == 0xB3 || op == 0xB4
            || op == 0xB5 || op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xBB
            || op == 0xBD || op == 0xC0 || op == 0xC1 || op == 0xC6 || op == 0xC7)   // ifnull / ifnonnull (3-byte)
        {
            return 3;
        }
        if (op == 0xAA)                                     // tableswitch
        {
            int p = pos + 1 + ((4 - ((pos + 1) & 3)) & 3);
            return (p + 12 + (s4(code, p + 8) - s4(code, p + 4) + 1) * 4) - pos;
        }
        if (op == 0xAB)                                     // lookupswitch
        {
            int p = pos + 1 + ((4 - ((pos + 1) & 3)) & 3);
            return (p + 8 + s4(code, p + 4) * 8) - pos;
        }
        if (op == 0xC4)                                     // wide (iinc = 6, else 4)
        {
            return (code[pos + 1] & 0xFF) == 0x84 ? 6 : 4;
        }
        if (op == 0xC5)                                     // multianewarray: index(2)+dims(1)
        {
            return 4;
        }
        return 1;
    }

    private static void set64(CodeBuffer cb, int rd, long v)
    {
        cb.emitAll(A64Enc.loadImm64(rd, v));
    }

    /** ldc/ldc_w: int constant, or a String literal interned as a byte[] object. */
    private void ldc(CodeBuffer cb, int cpIndex)
    {
        if (cpTag[cpIndex] == ClassReader.TAG_STRING)
        {
            int r = pushReg();
            symbols.string(cb, r, cpIndex);
        }
        else if (cpTag[cpIndex] == ClassReader.TAG_INTEGER || cpTag[cpIndex] == 4)
        {
            loadConst(cb, ClassReader.intValue(classBytes, cpOff, cpIndex));   // Integer or Float (32-bit bits)
        }
        else if (cpTag[cpIndex] == 7)                                          // CONSTANT_Class: a class literal (X.class)
        {
            int r = pushReg();
            symbols.classLiteral(cb, r, cpIndex);
        }
        else
        {
            symbols.fail(Symbols.FAIL_LDC_CONST, cpIndex, 0);
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

    // Bytecode-index -> machine word offset (from method start), filled by compileBody. bcToWord[bci] is the
    // word offset of the first instruction of the bytecode at bci, or -1 for non-instruction-boundary bytes /
    // unreached code. The stack-trace resolver inverts it: PC -> word offset -> bci -> source line (via the
    // classfile LineNumberTable). Same array the branch fixups use; captured here for the driver.
    private int[] lastBcToWord;

    public int frameSize() { return frameSize; }

    /** Number of locals held in callee-saved x19.. (so unwind can restore a handler's pre-try locals). */
    public int regLocals() { return regLocals; }
    public int handlerCount() { return exCount; }
    public int handlerStartWord(int i) { return hStartW[i]; }
    public int handlerEndWord(int i) { return hEndW[i]; }
    public int handlerWord(int i) { return hHandlerW[i]; }
    public int[] bcToWord() { return lastBcToWord; }

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
        this.regLocals = maxLocals < LOC_MAX ? maxLocals : LOC_MAX;
        this.overflowLocals = maxLocals > LOC_MAX ? maxLocals - LOC_MAX : 0;
        // frame: [LR?][saved local regs][overflow locals][operand spill area]
        this.localSaveBase = saveLR ? 8 : 0;
        this.overflowBase = localSaveBase + regLocals * 8;
        this.spillBase = overflowBase + overflowLocals * 8;
        // Pre-pass: the ACTUAL peak operand depth (not the DECLARED max_stack, which is often larger than a
        // method ever reaches). Only when the real peak exceeds OP_MAX does the operand stack spill to memory;
        // shallow methods stay register-only and byte-identical. The spill area (opStackBase) doubles as the
        // call-preservation area (spillLive), sized to the peak for deep methods, OP_MAX otherwise.
        this.reachDepth = computeDepths(code);   // depth[pc] >= 0 iff pc is reachable (control-flow pre-pass)
        this.deepStack = maxActualDepth > OP_MAX;
        if (deepStack && isEntry)
        {
            symbols.fail(Symbols.FAIL_STACK_OVERFLOW, maxActualDepth, 0);   // frameless entry can't spill; unexpected
        }
        this.opStackBase = spillBase;
        // +OPSTACK_MARGIN: the exception-search path (throwStored) pushes a few synthetic temporaries (exc/pc/sp,
        // or obj/catchType) ABOVE the current depth, beyond what computeDepths (which sees only normal flow) counts.
        this.opStackSlots = deepStack ? maxActualDepth + OPSTACK_MARGIN : 0;
        int spillWords = deepStack ? opStackSlots : ((!isEntry && nonLeaf) ? OP_MAX : 0);
        int savedWords = (saveLR ? 1 : 0) + regLocals + overflowLocals + spillWords;
        this.frameSize = isEntry ? 0 : A64Enc.align16(savedWords * 8);
        sp = 0;
        for (int r = 0; r < OP_MAX; r++)
        {
            regHolds[r] = -1;
        }

        CodeBuffer cb = new CodeBuffer(base);
        this.curCb = cb;
        if (!isEntry)
        {
            emitPrologue(cb, descOff, isStatic);
        }

        int[] bcToWord = new int[code.length];
        // Drive sp from the control-flow pre-pass at EVERY reachable bytecode, not just at branch targets
        // recorded so far. A loop header reached linearly before its back-edge is compiled would otherwise carry
        // a stale sp (the recordDepth for that edge hasn't run yet) -- and if its linear predecessor is dead/
        // skipped, that stale sp underflows. reachDepth[pc] is the sound operand depth entering pc (>=0 reachable,
        // -1 dead); seeding bcDepth with it makes recordDepth a consistency check rather than the sole source.
        bcDepth = reachDepth;
        for (int k = 0; k < code.length; k++)
        {
            bcToWord[k] = -1;
        }
        int pos = 0;
        boolean prevFalls = true;
        while (pos < code.length)
        {
            bcToWord[pos] = cb.wordCount();
            // Skip UNREACHABLE (dead) bytecode: javac emits cleanup stores after a goto/return/athrow in
            // finally/synchronized patterns that no edge reaches. Compiling it linearly would run the operand
            // model with a stale/empty sp and underflow on those stores. reachDepth (the control-flow pre-pass)
            // marks it; nothing branches into it (a branch target has reachDepth >= 0), so dropping it is safe.
            if (reachDepth[pos] < 0)
            {
                int dop = code[pos] & 0xFF;
                int dlen = (dop == 0xC8 || dop == 0xC9) ? 5 : opLen(dop, code, pos);
                pos += dlen < 1 ? 1 : dlen;
                prevFalls = false;               // no reachable fall-through emerges from skipped code
                continue;
            }
            if (bcDepth[pos] >= 0)
            {
                // Merge point (branch target / handler / label reached by fall-through). Normalise the deep
                // operand stack to memory so every incoming edge agrees: if the linear predecessor falls in
                // here, flush its live registers first; then invalidate so reads reload from memory.
                if (deepStack)
                {
                    if (prevFalls)
                    {
                        syncOut(cb);
                    }
                    syncIn(cb);
                }
                sp = bcDepth[pos];    // merge point: adopt the branch-edge depth
            }
            int op = code[pos] & 0xFF;
            curPos = pos;                          // so wide-sensitive lowerings (dup2/pop2) can read wideTop[pc]
            pos += step(op, code, pos, cb);
            prevFalls = fallsThrough(op);
        }

        for (int fi = 0; fi < fixupCount; fi++)
        {
            Fixup f = fixups[fi];
            int target = bcToWord[f.targetBc];
            if (target < 0)
            {
                symbols.fail(Symbols.FAIL_BRANCH_TARGET, f.targetBc, 0);
            }
            cb.set(f.wordIndex, encodeBranch(f, target - f.wordIndex));
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
        lastBcToWord = bcToWord;                         // capture for the stack-trace resolver (PC -> bci -> line)
        return cb.toWords();
    }

    // ----- prologue / epilogue --------------------------------------------
    private void emitPrologue(CodeBuffer cb, int descOff, boolean isStatic)
    {
        if (frameSize > 0)
        {
            cb.emit(A64Enc.subImm(31, 31, frameSize));    // sub sp, sp, #frame
        }
        if (saveLR)
        {
            cb.emit(A64Enc.strx(30, 31, 0));    // str x30, [sp]
        }
        for (int i = 0; i < regLocals; i++)              // only the register-backed ones
        {
            cb.emit(A64Enc.strx(LOC_BASE + i, 31, localSaveBase + i * 8));
        }
        // instance methods receive `this` as x0 -> slot 0; each parameter is one
        // argument register (long/double included), stepping its local slots wide.
        int arg = 0;
        int slot = 0;
        if (!isStatic)
        {
            cb.emit(A64Enc.movReg(localReg(0), 0));
            arg = 1;
            slot = 1;
        }
        int p = descOff + 2 + 1;                         // past u2 length and '('
        while (ClassReader.u1(classBytes, p) != ')')
        {
            cb.emit(inReg(slot) ? A64Enc.movReg(localReg(slot), arg)
                                : A64Enc.strx(arg, 31, localMem(slot)));
            arg++;
            int q = p;
            while (ClassReader.u1(classBytes, q) == '[')  // array prefixes fold into the element
            {
                q++;
            }
            int elem = ClassReader.u1(classBytes, q);
            // Only a BARE long/double is category-2 (2 local slots). An ARRAY of long/double ([J / [D) is a
            // reference -> 1 slot; treating it as 2 shifted every later parameter's local slot by one register,
            // so the prologue and body disagreed (e.g. seedDepth(int[], long[] mask, int[], ...) read garbage
            // and looped forever). Real bug behind the #43 loadGuest hang -- NOT a deep-operand-spill defect.
            boolean bareWide = (q == p) && (elem == 'J' || elem == 'D');
            slot += bareWide ? 2 : 1;
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
