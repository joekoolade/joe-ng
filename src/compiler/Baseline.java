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
        else if (op == 0xAC || op == 0xAD || op == 0xB0)
        {
            cb.emit(A64Enc.movReg(0, popReg()));
            emitEpilogue(cb);
            return 1;
        }  // ireturn/lreturn/areturn

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
        else if (op == 0x10)
        {
            loadConst(cb, (byte) code[pos + 1]);
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

        else if (op == 0x15 || op == 0x16 || op == 0x19)
        {
            load(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // iload/lload/aload
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

        else if (op == 0x36 || op == 0x37 || op == 0x3A)
        {
            store(cb, code[pos + 1] & 0xFF);
            return 2;
        }  // istore/lstore/astore
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
        else if (op == 0x59)
        {
            dup(cb);
            return 1;
        }  // dup
        else if (op == 0x5C)
        {
            dup2(cb);
            return 1;
        }  // dup2 (category-1 form: duplicate the top two slots)
        else if (op == 0x84)
        {
            iinc(cb, code[pos + 1] & 0xFF, (byte) code[pos + 2]);
            return 3;
        }

        // ---- array element load/store (base + index<<scale) ----
        else if (op == 0x33)
        {
            arrayLoad(cb, 0);
            return 1;
        }  // baload  (byte, zero-ext)
        else if (op == 0x34)
        {
            arrayLoad(cb, 1);
            return 1;
        }  // caload  (char, zero-ext)
        else if (op == 0x55)
        {
            arrayStore(cb, 1);
            return 1;
        }  // castore
        else if (op == 0x2E)
        {
            arrayLoad(cb, 2);
            return 1;
        }  // iaload  (int, sign-ext)
        else if (op == 0x2F)
        {
            arrayLoad(cb, 3);
            return 1;
        }  // laload  (long)
        else if (op == 0x32)
        {
            arrayLoad(cb, 3);
            return 1;
        }  // aaload  (ref)
        else if (op == 0x54)
        {
            arrayStore(cb, 0);
            return 1;
        }  // bastore
        else if (op == 0x4F)
        {
            arrayStore(cb, 2);
            return 1;
        }  // iastore
        else if (op == 0x50)
        {
            arrayStore(cb, 3);
            return 1;
        }  // lastore
        else if (op == 0x53)
        {
            arrayStore(cb, 3);
            return 1;
        }  // aastore
        else if (op == 0xBE)
        {
            arrayLength(cb);
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
            binop(cb, BIN_DIV);
            return 1;
        }
        else if (op == 0x70 || op == 0x71)
        {
            irem(cb);
            return 1;
        }  // irem/lrem
        else if (op == 0x74 || op == 0x75)
        {
            int r = OP_BASE + sp - 1;
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
        else if (op == 0x7C || op == 0x7D)
        {
            binop(cb, BIN_LSR);
            return 1;
        }  // iushr/lushr
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

        else if (op == 0x85 || op == 0x88)
        {
            return 1;
        }  // i2l/l2i: no-op (values fit 64-bit)
        else if (op == 0x91)
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64Enc.sxtb(r, r));
            return 1;
        }  // i2b
        else if (op == 0x92)
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64Enc.uxth(r, r));
            return 1;
        }  // i2c
        else if (op == 0x93)
        {
            int r = OP_BASE + sp - 1;
            cb.emit(A64Enc.sxth(r, r));
            return 1;
        }  // i2s

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
        else if (op == 0xA7)
        {
            int target = pos + s2(code, pos + 1);
            int w = cb.emit(A64Enc.b(0));
            addFixup(w, target, FIX_B, 0);
            recordDepth(target);
            return 3;
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
            getfield(cb, u2(code, pos + 1));
            return 3;
        }
        else if (op == 0xB5)
        {
            putfield(cb, u2(code, pos + 1));
            return 3;
        }
        else if (op == 0xB6)
        {
            lowerInvokeVirtual(u2(code, pos + 1), cb);
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
            lowerInvokeInterface(u2(code, pos + 1), cb);
            return 5;
        }  // invokeinterface
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
            lowerAnewArray(cb);                              // operand (element class) unused
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
    private int pushReg()
    {
        if (sp >= OP_MAX)
        {
            symbols.fail(Symbols.FAIL_STACK_OVERFLOW, sp, 0);
        }
        int r = OP_BASE + sp;      // avoid post-increment on a field (javac -> dup_x1)
        sp += 1;
        return r;
    }
    private int popReg()
    {
        if (sp <= 0)
        {
            symbols.fail(Symbols.FAIL_STACK_UNDERFLOW, sp, 0);
        }
        sp -= 1;
        return OP_BASE + sp;
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
        int top = OP_BASE + sp - 1;
        cb.emit(A64Enc.movReg(pushReg(), top));
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
        int lo = OP_BASE + sp - 2;    // v2 (deeper of the two)
        int hi = OP_BASE + sp - 1;    // v1 (top)
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
    private void getfield(CodeBuffer cb, int cpIndex)
    {
        int off = symbols.fieldOffset(cpIndex);
        int obj = popReg();
        int r = pushReg();
        cb.emit(A64Enc.ldrx(r, obj, off));
    }

    private void putfield(CodeBuffer cb, int cpIndex)
    {
        int off = symbols.fieldOffset(cpIndex);
        int val = popReg();
        int obj = popReg();
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
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, target, eq ? FIX_CBZ : FIX_CBNZ, v);
        recordDepth(target);
    }

    private void branchCmpZero(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int v = popReg();
        cb.emit(A64Enc.cmpImm(v, 0));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64Enc.b(0));
        addFixup(w, target, FIX_BCOND, cond);
        recordDepth(target);
    }

    private void branchCmp(CodeBuffer cb, byte[] code, int pos, int cond)
    {
        int b = popReg();
        int a = popReg();
        cb.emit(A64Enc.cmpReg(a, b));
        int target = pos + s2(code, pos + 1);
        int w = cb.emit(A64Enc.b(0));
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
            cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);
        cb.emit(A64Enc.ldrx(16, 0, ObjectModel.TIB_OFFSET));       // x16 = receiver.tib
        cb.emit(A64Enc.ldrx(16, 16, ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)))); // x16 = code
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
            cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // x0 = receiver
        }
        spillLive(cb);

        symbols.interfaceType(cb, 16, cpIndex);                                     // x16 = &interfaceType
        cb.emit(A64Enc.ldrx(17, 0, ObjectModel.TIB_OFFSET));                           // x17 = receiver.tib
        cb.emit(A64Enc.ldrx(17, 17, ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT))); // x17 = Type
        cb.emit(A64Enc.ldrx(17, 17, ObjectModel.TYPE_ITABLE_DIR_OFFSET));              // x17 = itable dir
        int search = cb.wordCount();
        cb.emit(A64Enc.ldrx(9, 17, ObjectModel.ITABLE_ENTRY_IFACE_OFFSET));            // x9 = entry.interfaceType
        cb.emit(A64Enc.cmpReg(9, 16));
        int beq = cb.emit(A64Enc.bcond(A64Enc.EQ, 0));                                    // found?
        cb.emit(A64Enc.addImm(17, 17, ObjectModel.ITABLE_ENTRY_SIZE));                 // next entry
        cb.emit(A64Enc.b(search - cb.wordCount()));                                    // loop
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
            int skip = cb.emit(A64Enc.cbz(cond, 0));
            emitCatch(cb, exHandlerPc[i]);                       // matched
            cb.set(skip, A64Enc.cbz(cond, cb.wordCount() - skip));
        }
        // no local handler: unwind the stack — unwind(exc, thisPC, SP)
        int exc = pushReg();
        emitLoadException(cb, exc);
        int pc = pushReg();
        cb.patchAddr(cb.reserveAddr(pc), pc, cb.pcAt(athrowStart)); // a PC inside this method
        int sp = pushReg();
        cb.emit(A64Enc.movFromSp(sp));
        emitCall(cb, 3, false, false, SYM_HELPER, Symbols.UNWIND);
        emitHalt(cb);                                            // unwind never returns
    }

    /** Push the pending exception and branch to a handler (which expects it at depth 1). */
    private void emitCatch(CodeBuffer cb, int handlerPc)
    {
        emitLoadException(cb, pushReg());
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
        int[] src = new int[nargs];
        for (int k = 0; k < nargs; k++)
        {
            src[k] = popReg();    // src[0] = last arg (top of stack)
        }
        for (int k = 0; k < nargs; k++)
        {
            cb.emit(A64Enc.movReg(nargs - 1 - k, src[k]));    // -> x(argIndex)
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
            cb.emit(A64Enc.movReg(pushReg(), 0));
        }
    }

    // ----- arrays: [header][length @16][elements @24], element = base + index<<scale -----
    private void lowerNewArray(int atype, CodeBuffer cb)
    {
        loadConst(cb, arrayElemSize(atype));                     // push elemSize
        emitCall(cb, 2, true, false, SYM_HELPER, Symbols.HEAP_ALLOC_ARRAY); // (length,elemSize)->ref
    }

    /**
     * anewarray: an array of references — the element is an 8-byte pointer, so it
     * allocates exactly like a {@code long[]}. The constant-pool operand names the
     * element class, but nothing needs it: element access ({@code aaload}/
     * {@code aastore}) is untyped, and array TIBs (for typed GC) are set later.
     */
    private void lowerAnewArray(CodeBuffer cb)
    {
        loadConst(cb, ObjectModel.WORD);                        // 8-byte reference elements
        emitCall(cb, 2, true, false, SYM_HELPER, Symbols.HEAP_ALLOC_ARRAY); // (length,elemSize)->ref
    }

    private void arrayLength(CodeBuffer cb)
    {
        int arr = popReg();
        int r = pushReg();
        cb.emit(A64Enc.ldrx(r, arr, ObjectModel.ARRAY_LENGTH_OFFSET));
    }

    private void arrayLoad(CodeBuffer cb, int scale)
    {
        int index = popReg(), arr = popReg(), r = pushReg();     // r == arr's register
        cb.emit(A64Enc.addImm(arr, arr, ObjectModel.ARRAY_BASE_OFFSET));
        cb.emit(A64Enc.addRegLsl(arr, arr, index, scale));          // arr = &elem[index]
        cb.emit(scale == 0 ? A64Enc.ldrb(r, arr, 0)                 // byte (zero-ext, ASCII)
                : scale == 1 ? A64Enc.ldrh(r, arr, 0)               // char (zero-ext — unsigned)
                : scale == 2 ? A64Enc.ldrsw(r, arr, 0)              // int (sign-ext)
                : A64Enc.ldrx(r, arr, 0));                          // long / ref
    }

    private void arrayStore(CodeBuffer cb, int scale)
    {
        int val = popReg();
        int index = popReg();
        int arr = popReg();
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
        for (int i = 0; i < sp; i++)
        {
            cb.emit(A64Enc.strx(OP_BASE + i, 31, spillBase + i * 8));
        }
    }
    private void reloadLive(CodeBuffer cb)
    {
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
        else if (id == Intrinsics.DSB)
        {
            cb.emit(A64Enc.dsb());
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
        else if (id == Intrinsics.RESUME)
        // exc->x9, SP=sp, br pc (no return)
        {
            int exc = popReg();
            int spv = popReg();
            int pc = popReg();
            cb.emit(A64Enc.movReg(16, pc));                     // target -> scratch (x9 gets clobbered next)
            cb.emit(A64Enc.movReg(9, exc));                     // exception -> handler's stack slot
            cb.emit(A64Enc.movToSp(spv));
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
            if (op == 0xBB || op == 0xBC || op == 0xBD || op == 0xB6 || op == 0xB9 || op == 0xBF || op == 0xC0 || op == 0xC1)
            {
                return true;    // new/newarray/anewarray/invokevirtual/invokeinterface/athrow/checkcast/instanceof
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
        // 2-byte: bipush/ldc/iload/lload/aload/istore/lstore/astore/newarray
        if (op == 0x10 || op == 0x12 || op == 0x15 || op == 0x16 || op == 0x19
            || op == 0x36 || op == 0x37 || op == 0x3A || op == 0xBC)
        {
            return 2;
        }
        if (op == 0xB9)                                      // invokeinterface (idx, count, 0)
        {
            return 5;
        }
        // 3-byte: sipush/ldc_w/ldc2_w/iinc/if*/goto/get-put static-field/invoke*/new/anewarray/checkcast/instanceof
        if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || op == 0x99
            || op == 0x9A || op == 0x9B || op == 0x9C || op == 0x9D || op == 0x9E
            || op == 0x9F || op == 0xA0 || op == 0xA1 || op == 0xA2 || op == 0xA3
            || op == 0xA4 || op == 0xA7 || op == 0xB2 || op == 0xB3 || op == 0xB4
            || op == 0xB5 || op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xBB
            || op == 0xBD || op == 0xC0 || op == 0xC1)
        {
            return 3;
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
        else if (cpTag[cpIndex] == ClassReader.TAG_INTEGER)
        {
            loadConst(cb, ClassReader.intValue(classBytes, cpOff, cpIndex));
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
        this.regLocals = maxLocals < LOC_MAX ? maxLocals : LOC_MAX;
        this.overflowLocals = maxLocals > LOC_MAX ? maxLocals - LOC_MAX : 0;
        // frame: [LR?][saved local regs][overflow locals][operand spill area]
        this.localSaveBase = saveLR ? 8 : 0;
        this.overflowBase = localSaveBase + regLocals * 8;
        this.spillBase = overflowBase + overflowLocals * 8;
        int spillWords = (!isEntry && nonLeaf) ? OP_MAX : 0;
        int savedWords = (saveLR ? 1 : 0) + regLocals + overflowLocals + spillWords;
        this.frameSize = isEntry ? 0 : A64Enc.align16(savedWords * 8);
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
