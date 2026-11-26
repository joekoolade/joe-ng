/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package compiler.opt;

import compiler.Symbols;
import objectmodel.ObjectModel;

/**
 * AArch64 instruction selection: the IR into machine IR, ONE machine instruction per A64 instruction (or per fixed
 * sequence that reads every operand before it writes its result), so the allocator's "uses read before defs are
 * written" holds of the code as emitted.
 *
 * <p>It produces exactly what Baseline computes in the writer, where {@code implicitChecks()} is off: no null,
 * bounds or divide-by-zero tests (a zero divisor answers 0 from {@code sdiv}, as Baseline's does), ints kept
 * sign-extended in their 64-bit registers, every field an 8-byte slot, a volatile load followed by
 * {@code dmb ish}.
 *
 * <p>Covered (O1-2b, O1-3a): ints, longs, references and the word types; arithmetic, shifts, compares, branches,
 * switches, field and array loads; DIRECT calls (invokestatic, invokespecial) through the same {@link Symbols}
 * seam as Baseline, with yieldpoints where Baseline puts them (the prologue of a method that calls or loops, and
 * every loop header, unless the class is {@code Uninterruptible}). Not yet: virtual and interface dispatch,
 * intrinsics, allocation, type checks, monitors, throws, statics, reference stores, handlers, floats.
 */
public final class A64Select
{
    // A64 machine opcodes (the target's own; >= 0)
    public static final int LIMM = 0;        // defs[0] = imm
    public static final int ADD = 1;         // x-form d = a op b
    public static final int SUB = 2;
    public static final int MUL = 3;
    public static final int SDIV = 4;
    public static final int AND = 5;
    public static final int ORR = 6;
    public static final int EOR = 7;
    public static final int LSLV = 8;
    public static final int LSRV = 9;
    public static final int ASRV = 10;
    public static final int MSUB = 11;       // d = uses[2] - uses[0] * uses[1]
    public static final int NEG = 12;
    public static final int SXTW = 13;
    public static final int SXTB = 14;
    public static final int SXTH = 15;
    public static final int UXTH = 16;
    public static final int UXTW = 17;
    public static final int ANDLOW = 18;     // d = a & ((1 << imm) - 1)
    public static final int LCMP = 19;       // d = -1/0/1
    public static final int ADDI = 20;       // d = a + imm
    public static final int ADDLSL = 21;     // d = a + (b << imm)
    public static final int LDRX = 22;       // d = [a + imm]; aux 1 = volatile (dmb ish after)
    public static final int LDRSB = 23;      // d = [a]
    public static final int LDRH = 24;
    public static final int LDRSH = 25;
    public static final int LDRSW = 26;
    public static final int PARAMS = 27;     // defs = the arguments (pinned to x0..)
    public static final int BCMP = 28;       // term: compare uses[0] with uses[1] (or 0), b.cond aux
    public static final int CBZ = 29;        // term: uses[0] == 0
    public static final int CBNZ = 30;
    public static final int SWITCH = 31;     // term: keys; targets[0] = default
    public static final int RET = 32;        // term: uses[0] in x0, or nothing
    public static final int CALL = 33;       // a direct call: aux = Methodref cp, imm 1 = has a receiver; args pinned
                                             //   to x0.., the result to x0, destroys every caller-saved register
    public static final int YIELD = 34;      // a yieldpoint: poll the request word, call the helper when it is set

    public static final int OK = 0;
    public static final int R_CALL = 1;
    public static final int R_HANDLER = 2;
    public static final int R_FLOAT = 3;
    public static final int R_LOOP = 4;
    public static final int R_ARGS = 5;
    public static final int R_SYNC = 6;
    public static final int R_OP = 7;
    public static final int R_DISPATCH = 8;
    public static final int R_INTRINSIC = 9;
    public static final int REASONS = 10;
    private static final String[] REASON_NAMES = {
        "ok", "allocates, throws or stores a reference", "has a handler", "float/double",
        "loop without yieldpoints", "more than 16 arguments", "synchronized", "op not selected yet",
        "virtual/interface/dynamic call", "intrinsic or special call",
    };

    private static final int MAX_ARGS = 16;

    private final Graph g;
    private final Symbols syms;
    private final boolean uninterruptible;
    private MFunc f;
    private int[] vreg;           // IR instruction id -> virtual register
    private MBlock[] mblock;      // IR block rpo -> machine block
    private MBlock cur;
    public int reason;
    /** The method makes a call or polls: it saves the link register and gets a GC map. */
    public boolean nonLeaf;
    private boolean polls;

    public A64Select(Graph g, Symbols syms, boolean uninterruptible)
    {
        this.g = g;
        this.syms = syms;
        this.uninterruptible = uninterruptible;
    }

    public static String reasonName(int r)
    {
        return REASON_NAMES[r];
    }

    /** The machine IR, or null with {@link #reason} set. */
    public MFunc select()
    {
        reason = screen();
        if (reason != OK)
        {
            return null;
        }
        f = new MFunc();
        vreg = new int[g.instrCount()];
        mblock = new MBlock[g.nBlocks];
        for (int b = 0; b < g.nBlocks; b++)
        {
            mblock[b] = f.newBlock();
        }
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            MBlock mb = mblock[b];
            mb.preds = new MBlock[blk.nPreds + 1];
            mb.nPreds = blk.nPreds;
            for (int i = 0; i < blk.nPreds; i++)
            {
                mb.preds[i] = mblock[blk.preds[i].rpo];       // the IR's order, which the phis' operands follow
            }
            mb.succs = new MBlock[blk.nSuccs + 1];
            mb.nSuccs = blk.nSuccs;
            for (int i = 0; i < blk.nSuccs; i++)
            {
                mb.succs[i] = mblock[blk.succs[i].rpo];
            }
            for (int i = 0; i < blk.nPhis; i++)
            {
                vreg[blk.phis[i].id] = valueVreg(blk.phis[i].kind);
            }
            for (int i = 0; i < blk.nCode; i++)
            {
                if (blk.code[i].kind != Kind.V)
                {
                    vreg[blk.code[i].id] = valueVreg(blk.code[i].kind);
                }
            }
        }
        for (int b = 0; b < g.nBlocks && reason == OK; b++)
        {
            Block blk = g.blocks[b];
            cur = mblock[b];
            for (int i = 0; i < blk.nPhis; i++)
            {
                Instr p = blk.phis[i];
                int[] in = new int[p.in.length];
                for (int k = 0; k < in.length; k++)
                {
                    in[k] = vreg[p.in[k].id];
                }
                cur.add(new MInstr(MInstr.PHI, new int[] { vreg[p.id] }, in));
            }
            if (b == 0)
            {
                params(blk);
                if (polls && nonLeaf)
                {
                    yieldPoint(-1);                          // the prologue yieldpoint, as Baseline's
                }
            }
            else if (polls && blk.loopHeader)
            {
                yieldPoint(blk.startBci);                    // a loop's yieldpoint
            }
            for (int i = 0; i < blk.nCode && reason == OK; i++)
            {
                Instr x = blk.code[i];
                if (x.op != Op.PARAM)
                {
                    one(x);
                }
            }
        }
        return reason == OK ? f : null;
    }

    /** Everything this increment does not select, decided before any machine IR exists. */
    private int screen()
    {
        polls = syms.yieldCellAddr() != 0L && !uninterruptible;
        if (g.isSynchronized)
        {
            return R_SYNC;
        }
        if (g.homes || g.exCount > 0)
        {
            return R_HANDLER;
        }
        if (g.returnKind == Kind.F || g.returnKind == Kind.D)
        {
            return R_FLOAT;
        }
        int args = 0;
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            if (blk.loopHeader && polls)
            {
                nonLeaf = true;                              // its yieldpoint is a call
            }
            for (int i = 0; i < blk.nPhis; i++)
            {
                if (!intLike(blk.phis[i].kind))
                {
                    return R_FLOAT;
                }
            }
            for (int i = 0; i < blk.nCode; i++)
            {
                Instr x = blk.code[i];
                if (x.kind != Kind.V && !intLike(x.kind))
                {
                    return R_FLOAT;
                }
                if (x.op == Op.PARAM)
                {
                    args += 1;
                }
                int r = screenOp(x);
                if (r != OK)
                {
                    return r;
                }
                if (x.op == Op.INVOKE)
                {
                    r = screenCall(x);
                    if (r != OK)
                    {
                        return r;
                    }
                }
            }
        }
        return args > MAX_ARGS ? R_ARGS : OK;
    }

    private static boolean intLike(int k)
    {
        return k == Kind.I || k == Kind.J || Kind.refLike(k);
    }

    /** A direct call (invokestatic, invokespecial) through the {@link Symbols} seam, as Baseline's lowerCall; the
     *  others -- dispatch, intrinsics, the special lowerings -- are later increments. */
    private int screenCall(Instr x)
    {
        int cp = (int) x.aux;
        if (x.aux2 == 0xB6 || x.aux2 == 0xB9 || x.aux2 == 0xBA)
        {
            return R_DISPATCH;
        }
        if (syms.isIntrinsicCall(cp) || syms.mathIntrinsicId(cp) >= 0 || syms.isGetCallerClass(cp)
            || syms.isMhSigPoly(cp) || syms.isWatchedCall(cp) || syms.isWatchedCallArgs(cp))
        {
            return R_INTRINSIC;
        }
        if (x.aux2 == 0xB8 && syms.needsInitGuard(cp))
        {
            return R_INTRINSIC;                              // an init-guard helper call before it (Locale only)
        }
        if (x.in.length > MAX_ARGS)
        {
            return R_ARGS;
        }
        if (x.aux2 == 0xB7 && syms.isSkippableInit(cp))
        {
            return OK;                                       // super() into a root class: no call at all
        }
        nonLeaf = true;
        return OK;
    }

    private static int screenOp(Instr x)
    {
        int op = x.op;
        if (op == Op.NEW || op == Op.NEWARRAY || op == Op.ANEWARRAY || op == Op.MULTIANEWARRAY
            || op == Op.CHECKCAST || op == Op.INSTANCEOF || op == Op.MONITORENTER || op == Op.MONITOREXIT
            || op == Op.THROW || op == Op.GETSTATIC || op == Op.PUTSTATIC || op == Op.PUTFIELD || op == Op.ASTORE
            || op == Op.LDC)
        {
            return R_CALL;
        }
        if (op == Op.CATCH || op == Op.LOAD_LOCAL || op == Op.STORE_LOCAL)
        {
            return R_HANDLER;
        }
        if (op == Op.CMP && x.aux2 != 0x94)
        {
            return R_FLOAT;
        }
        if (op == Op.CONV && !(x.aux2 == 0x85 || x.aux2 == 0x88 || (x.aux2 >= 0x91 && x.aux2 <= 0x93)))
        {
            return R_FLOAT;
        }
        return OK;
    }

    // ----- emission into machine IR -------------------------------------------------------------------------

    private void params(Block entry)
    {
        int n = 0;
        for (int i = 0; i < entry.nCode; i++)
        {
            if (entry.code[i].op == Op.PARAM)
            {
                n += 1;
            }
        }
        int[] defs = new int[n];
        int[] pins = new int[n];
        for (int i = 0; i < entry.nCode; i++)
        {
            Instr x = entry.code[i];
            if (x.op == Op.PARAM)
            {
                defs[(int) x.aux] = vreg[x.id];
                pins[(int) x.aux] = (int) x.aux;               // argument k arrives in xk (Baseline's convention)
            }
        }
        MInstr p = new MInstr(PARAMS, defs, null);
        p.fixedDefs = pins;
        cur.add(p);
    }

    private MInstr emit(int op, int d, int[] uses, Instr from)
    {
        MInstr m = new MInstr(op, d < 0 ? null : new int[] { d }, uses);
        m.bci = from.bci;
        cur.add(m);
        return m;
    }

    private int temp()
    {
        return f.newVreg(RegFile.INT);
    }

    private int v(Instr x)
    {
        return vreg[x.id];
    }

    private void one(Instr x)
    {
        int op = x.op;
        int d = x.kind == Kind.V ? -1 : v(x);
        boolean isInt = x.kind == Kind.I;
        if (op == Op.CONST || op == Op.NULL)
        {
            MInstr m = emit(LIMM, d, null, x);
            m.imm = op == Op.NULL ? 0L : x.aux;
        }
        else if (op == Op.ADD || op == Op.SUB || op == Op.MUL || op == Op.DIV)
        {
            int mop = op == Op.ADD ? ADD : (op == Op.SUB ? SUB : (op == Op.MUL ? MUL : SDIV));
            int[] in = { v(x.in[0]), v(x.in[1]) };
            if (isInt)
            {
                int t = temp();
                emit(mop, t, in, x);
                emit(SXTW, d, new int[] { t }, x);         // re-canonicalize (idiv: INT_MIN / -1 wraps)
            }
            else
            {
                emit(mop, d, in, x);
            }
        }
        else if (op == Op.REM)
        {
            int a = v(x.in[0]);
            int b = v(x.in[1]);
            int q = temp();
            emit(SDIV, q, new int[] { a, b }, x);
            emit(MSUB, d, new int[] { q, b, a }, x);       // a - (a / b) * b: in range for ints, as Baseline's
        }
        else if (op == Op.NEG)
        {
            unaryCanon(NEG, d, v(x.in[0]), isInt, x);
        }
        else if (op == Op.AND || op == Op.OR || op == Op.XOR)
        {
            int mop = op == Op.AND ? AND : (op == Op.OR ? ORR : EOR);
            emit(mop, d, new int[] { v(x.in[0]), v(x.in[1]) }, x);
        }
        else if (op == Op.SHL || op == Op.SHR || op == Op.USHR)
        {
            shift(x, d, isInt);
        }
        else if (op == Op.CMP)
        {
            emit(LCMP, d, new int[] { v(x.in[0]), v(x.in[1]) }, x);
        }
        else if (op == Op.CONV)
        {
            conv(x, d);
        }
        else if (op == Op.GETFIELD)
        {
            int cp = (int) x.aux;
            MInstr m = emit(LDRX, d, new int[] { v(x.in[0]) }, x);
            m.imm = syms.fieldOffset(cp);
            m.aux = syms.isVolatileField(cp, false) ? 1 : 0;
        }
        else if (op == Op.ARRAYLENGTH)
        {
            MInstr m = emit(LDRX, d, new int[] { v(x.in[0]) }, x);
            m.imm = ObjectModel.ARRAY_LENGTH_OFFSET;
        }
        else if (op == Op.ALOAD)
        {
            arrayLoad(x, d);
        }
        else if (op == Op.GOTO)
        {
            MInstr j = MInstr.jump(mblock[x.targets[0].rpo]);
            j.bci = x.bci;
            cur.add(j);
        }
        else if (op == Op.IF)
        {
            branch(x);
        }
        else if (op == Op.SWITCH)
        {
            MInstr m = emit(SWITCH, -1, new int[] { canon(v(x.in[0]), x) }, x);
            m.term = true;
            m.keys = x.keys;
            m.targets = targets(x);
        }
        else if (op == Op.INVOKE)
        {
            call(x, d);
        }
        else if (op == Op.RETURN)
        {
            MInstr m;
            if (x.in.length == 0)
            {
                m = emit(RET, -1, null, x);
            }
            else
            {
                m = emit(RET, -1, new int[] { v(x.in[0]) }, x);
                m.fixedUses = new int[] { 0 };
            }
            m.term = true;
        }
        else
        {
            reason = R_OP;
        }
    }

    /** A register for an IR value, typed for the collector by its kind (an ObjectReference is a reference). */
    private int valueVreg(int kind)
    {
        int v = f.newVreg(RegFile.INT);
        if (kind == Kind.A || kind == Kind.O)
        {
            f.vgc[v] = MFunc.GC_REF;
        }
        else if (kind == Kind.R)
        {
            f.vgc[v] = MFunc.GC_DREF;
        }
        return v;
    }

    private void call(Instr x, int d)
    {
        int cp = (int) x.aux;
        if (x.aux2 == 0xB7 && syms.isSkippableInit(cp))
        {
            return;
        }
        int n = x.in.length;
        int[] args = new int[n];
        int[] pins = new int[n];
        for (int i = 0; i < n; i++)
        {
            args[i] = v(x.in[i]);
            pins[i] = i;                                     // argument i in xi (receiver first)
        }
        MInstr m = emit(CALL, d, args, x);
        m.fixedUses = pins;
        if (d >= 0)
        {
            m.fixedDefs = new int[] { 0 };
        }
        m.aux = cp;
        m.imm = x.aux2 == 0xB8 ? 0 : 1;
        m.clobbers = callerSaved();
    }

    private void yieldPoint(int bci)
    {
        MInstr m = new MInstr(YIELD, null, null);
        m.bci = bci;
        m.clobbers = callerSaved();
        cur.add(m);
    }

    private static long[] callerSaved()
    {
        RegFile rf = new A64Target().regs();
        return new long[] { rf.callerSaved(RegFile.INT), rf.callerSaved(RegFile.FP) };
    }

    private void unaryCanon(int mop, int d, int a, boolean isInt, Instr x)
    {
        if (isInt)
        {
            int t = temp();
            emit(mop, t, new int[] { a }, x);
            emit(SXTW, d, new int[] { t }, x);
        }
        else
        {
            emit(mop, d, new int[] { a }, x);
        }
    }

    /** As Baseline: an int count masked to 5 bits, a long's left to the hardware (mod 64); an int result
     *  re-canonicalized, and an int {@code >>>} zero-extends its operand first. */
    private void shift(Instr x, int d, boolean isInt)
    {
        int a = v(x.in[0]);
        int n = v(x.in[1]);
        int mop = x.op == Op.SHL ? LSLV : (x.op == Op.SHR ? ASRV : LSRV);
        if (!isInt)
        {
            emit(mop, d, new int[] { a, n }, x);
            return;
        }
        int c = temp();
        MInstr mask = emit(ANDLOW, c, new int[] { n }, x);
        mask.imm = 5;
        if (x.op == Op.SHR)
        {
            emit(ASRV, d, new int[] { a, c }, x);           // asr of a canonical int stays canonical
            return;
        }
        int src = a;
        if (x.op == Op.USHR)
        {
            src = temp();
            emit(UXTW, src, new int[] { a }, x);
        }
        int t = temp();
        emit(mop, t, new int[] { src, c }, x);
        emit(SXTW, d, new int[] { t }, x);
    }

    private void conv(Instr x, int d)
    {
        int a = v(x.in[0]);
        int c = x.aux2;
        int mop = c == 0x88 ? SXTW : (c == 0x91 ? SXTB : (c == 0x92 ? UXTH : SXTH));
        emit(c == 0x85 ? SXTW : mop, d, new int[] { a }, x);   // i2l re-sign-extends, as Baseline's does
    }

    /** baload/caload/saload/iaload/laload/aaload: element address, then the sized load (Baseline's widths). */
    private void arrayLoad(Instr x, int d)
    {
        int c = x.aux2;
        int shift = c == 0x33 ? 0 : (c == 0x34 || c == 0x35 ? 1 : (c == 0x2E ? 2 : 3));
        int lop = c == 0x33 ? LDRSB : (c == 0x34 ? LDRH : (c == 0x35 ? LDRSH : (c == 0x2E ? LDRSW : LDRX)));
        int base = temp();
        MInstr a = emit(ADDI, base, new int[] { v(x.in[0]) }, x);
        a.imm = ObjectModel.ARRAY_BASE_OFFSET;
        int elem = temp();
        MInstr s = emit(ADDLSL, elem, new int[] { base, v(x.in[1]) }, x);
        s.imm = shift;
        emit(lop, d, new int[] { elem }, x);
    }

    private static final int[] CONDS = { 0, 1, 11, 10, 12, 13 };     // eq ne lt ge gt le (A64Enc codes)

    private void branch(Instr x)
    {
        int op = x.aux2;
        MInstr m;
        if (op == 0xC6 || op == 0xC7 || op == 0x99 || op == 0x9A)
        {
            boolean eq = op == 0xC6 || op == 0x99;
            m = emit(eq ? CBZ : CBNZ, -1, new int[] { v(x.in[0]) }, x);
        }
        else if (x.in.length == 1)
        {
            m = emit(BCMP, -1, new int[] { canon(v(x.in[0]), x) }, x);
            m.aux = CONDS[op - 0x99];
        }
        else if (op >= 0xA5)
        {
            m = emit(BCMP, -1, new int[] { v(x.in[0]), v(x.in[1]) }, x);   // a reference: the whole register
            m.aux = CONDS[op - 0xA5];
        }
        else
        {
            m = emit(BCMP, -1, new int[] { canon(v(x.in[0]), x), canon(v(x.in[1]), x) }, x);
            m.aux = CONDS[op - 0x9F];
        }
        m.term = true;
        m.targets = targets(x);
    }

    /**
     * An int re-sign-extended where Baseline re-sign-extends it -- the ordered compares, the switches, i2l. Baseline
     * keeps its own results canonical, but an int can arrive with junk above bit 31 (a W-form load in an intrinsic,
     * a zero-extended value written to a field), and Baseline's answer at these points does not depend on it, so
     * this tier's must not either. {@code ifeq}/{@code ifne} test the whole register in both tiers. Dropping the
     * extension where the value is provably canonical is an O2 cleanup.
     */
    private int canon(int a, Instr x)
    {
        int t = temp();
        emit(SXTW, t, new int[] { a }, x);
        return t;
    }

    private MBlock[] targets(Instr x)
    {
        MBlock[] t = new MBlock[x.targets.length];
        for (int i = 0; i < t.length; i++)
        {
            t[i] = mblock[x.targets[i].rpo];
        }
        return t;
    }
}
