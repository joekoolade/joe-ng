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
    public static final int VCALL = 35;      // vtable dispatch: imm = the vtable slot; as CALL otherwise
    public static final int ICALL = 36;      // itable dispatch: aux = InterfaceMethodref cp, imm = the itable slot
    public static final int HCALL = 37;      // a VM helper call: aux = the Symbols helper id
    public static final int STATICADDR = 38; // defs[0] = the address of the static at Fieldref aux (relocated)
    public static final int STRX = 39;       // [uses[0] + imm] = uses[1]
    public static final int STRB = 40;       // [uses[0]] = uses[1], one byte
    public static final int STRH = 41;
    public static final int STRW = 42;
    public static final int DMB = 43;        // dmb ish (a volatile access's fence)
    public static final int BARRIER = 44;    // the write barrier: uses = object (or slot address), value -- both
                                             //   copies that die here, since the barrier overwrites them; aux 1 = slot log
    public static final int LDCSTR = 45;     // defs[0] = the String literal at cp aux (relocated)
    public static final int LDCCLASS = 46;   // defs[0] = the class literal at cp aux (relocated)
    public static final int CSET = 47;       // defs[0] = (uses[0] cmp uses[1] (or #0)) ? 1 : 0, condition aux
    public static final int UBNOT = 48;      // defs[0] = ~uses[0]
    public static final int LDRB = 49;       // defs[0] = [uses[0]], one byte zero-extended
    public static final int SMULH = 50;      // defs[0] = high 64 bits of uses[0] * uses[1], signed
    public static final int UMULH = 51;      // ... unsigned
    public static final int FENCE = 52;      // aux 0 = dsb sy, 1 = isb
    public static final int MRS = 53;        // defs[0] = the system register aux
    public static final int TIBSTORE = 54;   // header.tib of uses[0] = the TIB of class cp aux (relocated, via x16)
    public static final int TAGARRAY = 55;   // the array uses[0] gets its array Type: aux = atype or element cp,
                                             //   imm 1 = a reference array (Symbols.tagArray)
    public static final int TAGUNBOXED = 56; // ... retyped for an unboxed array's owner (Symbols.tagUnboxedArray)
    public static final int TYPEADDR = 57;   // defs[0] = the Type of class cp aux (relocated)
    public static final int DESCADDR = 58;   // defs[0] = the {u2 len}{bytes} descriptor of array class cp aux

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
    public static final int R_UNRESOLVED = 10;
    public static final int REASONS = 11;
    private static final String[] REASON_NAMES = {
        "ok", "allocates, throws or stores a reference", "has a handler", "float/double",
        "loop without yieldpoints", "more than 16 arguments", "synchronized", "op not selected yet",
        "invokedynamic or default-method dispatch", "intrinsic or special call", "virtual call with no slot",
    };

    private static final int MAX_ARGS = 16;
    private static final int MAX_MULTI_DIMS = 4;             // Baseline's: the counts ride in x2..x5

    private final Graph g;
    private final Symbols syms;
    private final boolean uninterruptible;
    private MFunc f;
    private int[] vreg;           // IR instruction id -> virtual register
    private MBlock[] mblock;      // IR block rpo -> machine block
    private MBlock cur;
    private boolean[] loopPolls;     // Baseline's loop headers (a backward branch's target), when polls are on
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
            else if (loopPolls != null && blk.startBci >= 0 && loopPolls[blk.startBci] && blk.entryDepth == 0)
            {
                yieldPoint(blk.startBci);                    // a loop's yieldpoint, where Baseline puts one
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
        loopPolls = polls ? baselineLoopHeaders(g.code) : null;
        if (loopPolls != null && anyTrue(loopPolls))
        {
            nonLeaf = true;                                  // a loop's yieldpoint is a call
        }
        if (baselineNonLeaf(g.code))
        {
            nonLeaf = true;                                  // Baseline's leaf decision, so the polls and LR match
        }
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
                if ((x.op == Op.GETSTATIC || x.op == Op.PUTSTATIC) && syms.needsInitGuard((int) x.aux))
                {
                    return R_INTRINSIC;                      // an init-guard helper call before it (Locale only)
                }
                if (x.op == Op.NEW && syms.needsInitGuardClass((int) x.aux))
                {
                    return R_INTRINSIC;
                }
                if (x.op == Op.NEW || x.op == Op.NEWARRAY || x.op == Op.ANEWARRAY || x.op == Op.MULTIANEWARRAY
                    || x.op == Op.CHECKCAST || x.op == Op.INSTANCEOF)
                {
                    nonLeaf = true;                          // each is a helper call
                }
                if (x.op == Op.LDC)
                {
                    int tag = g.cpTag[(int) x.aux];
                    if (tag != 7 && tag != 8)
                    {
                        return R_OP;                         // MethodHandle / MethodType / dynamic: later
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
        if (x.aux2 == 0xBA)
        {
            return R_DISPATCH;
        }
        if (x.aux2 != 0xB7 && syms.isIntrinsicCall(cp))
        {
            if (!supportedIntrinsic(syms.intrinsicId(cp)))
            {
                return R_INTRINSIC;
            }
            if (syms.intrinsicEmitsCall(cp))
            {
                nonLeaf = true;                              // AddressArray.create allocates: a call, as Baseline sees it
            }
            return OK;
        }
        if (x.aux2 == 0xB8 && syms.mathIntrinsicId(cp) >= 0)
        {
            return OK;
        }
        if (x.in.length > MAX_ARGS)
        {
            return R_ARGS;
        }
        if (x.aux2 == 0xB6 || x.aux2 == 0xB9)
        {
            return screenDispatch(x, cp);
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

    /** invokevirtual / invokeinterface: Baseline's dispatch shapes, in the writer's world (no guards). */
    private int screenDispatch(Instr x, int cp)
    {
        if (syms.isIntrinsicCall(cp) || syms.isMhSigPoly(cp) || syms.isWatchedCall(cp) || syms.isWatchedCallArgs(cp)
            || syms.isWatchedRecv(cp))
        {
            return R_INTRINSIC;
        }
        nonLeaf = true;
        if (x.aux2 == 0xB9 && !syms.isObjectPublicMethod(cp))
        {
            return OK;                                       // itable dispatch
        }
        if (syms.isGetClass(cp) || syms.isArrayClone(cp) || syms.monitorOp(cp) != 0)
        {
            return OK;                                       // a helper call
        }
        if (syms.isDesiredAssertionStatus(cp))
        {
            return OK;                                       // folds to false
        }
        if (syms.defaultDispatch(cp))
        {
            return R_DISPATCH;
        }
        return syms.vtableSlot(cp) < 0 ? R_UNRESOLVED : OK;
    }

    /** Debug (JOENG_OPT_BLOCKERS): every op that blocks the back end, counted by name across the build. */
    public static final int[] BLOCKED_BY = new int[Op.COUNT];

    private static int screenOp(Instr x)
    {
        int r = screenOp0(x);
        if (r != OK)
        {
            BLOCKED_BY[x.op] += 1;
        }
        return r;
    }

    private static int screenOp0(Instr x)
    {
        int op = x.op;
        if (op == Op.MONITORENTER || op == Op.MONITOREXIT || op == Op.THROW)
        {
            return R_CALL;
        }
        if (op == Op.MULTIANEWARRAY && x.in.length > MAX_MULTI_DIMS)
        {
            return R_OP;                                     // as Baseline, which refuses it at compile time
        }
        if (op == Op.LDC && x.kind != Kind.A)
        {
            return R_OP;                                     // a dynamic constant of a primitive type
        }
        if (op == Op.ASTORE && (x.aux2 == 0x51 || x.aux2 == 0x52))
        {
            return R_FLOAT;
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
        else if (op == Op.GETSTATIC)
        {
            int cp = (int) x.aux;
            int a = temp();
            MInstr sa = emit(STATICADDR, a, null, x);
            sa.aux = cp;
            MInstr m = emit(LDRX, d, new int[] { a }, x);
            m.imm = 0;
            m.aux = syms.isVolatileField(cp, true) ? 1 : 0;
        }
        else if (op == Op.PUTSTATIC)
        {
            int cp = (int) x.aux;
            int a = temp();
            MInstr sa = emit(STATICADDR, a, null, x);
            sa.aux = cp;
            store(STRX, a, v(x.in[0]), 0, syms.isVolatileField(cp, true), x);
            if (compiler.Baseline.fieldIsRef(g.cls, g.cpOff, cp))
            {
                barrier(a, v(x.in[0]), true, x);             // the CELL into the slot log (S7-4d''), as Baseline
            }
        }
        else if (op == Op.PUTFIELD)
        {
            int cp = (int) x.aux;
            int obj = v(x.in[0]);
            store(STRX, obj, v(x.in[1]), syms.fieldOffset(cp), syms.isVolatileField(cp, false), x);
            if (compiler.Baseline.fieldIsRef(g.cls, g.cpOff, cp))
            {
                barrier(obj, v(x.in[1]), false, x);
            }
        }
        else if (op == Op.ASTORE)
        {
            arrayStore(x);
        }
        else if (op == Op.NEW)
        {
            int cp = (int) x.aux;
            int size = temp();
            MInstr k = emit(LIMM, size, null, x);
            k.imm = syms.objectSize(cp);
            callInstr(HCALL, d, new int[] { size }, Symbols.HEAP_ALLOC, 0, x);
            MInstr t = emit(TIBSTORE, -1, new int[] { d }, x);
            t.aux = cp;
        }
        else if (op == Op.NEWARRAY || op == Op.ANEWARRAY)
        {
            boolean ref = op == Op.ANEWARRAY;
            int t = newArray(x, v(x.in[0]), ref ? 8 : elemSize(x.aux2));
            MInstr tag = emit(TAGARRAY, -1, new int[] { t }, x);
            tag.aux = ref ? (int) x.aux : x.aux2;
            tag.imm = ref ? 1 : 0;
            MInstr m = MInstr.move(d, t);
            m.bci = x.bci;
            cur.add(m);
        }
        else if (op == Op.MULTIANEWARRAY)
        {
            int dims = x.in.length;
            int[] args = new int[2 + MAX_MULTI_DIMS];        // vm/VM.multiNewArray(desc, dims, c0..c3)
            args[0] = temp();
            MInstr da = emit(DESCADDR, args[0], null, x);
            da.aux = (int) x.aux;
            args[1] = temp();
            MInstr dk = emit(LIMM, args[1], null, x);
            dk.imm = dims;
            for (int i = 0; i < MAX_MULTI_DIMS; i++)
            {
                if (i < dims)
                {
                    args[2 + i] = v(x.in[i]);
                }
                else
                {
                    args[2 + i] = temp();
                    MInstr z = emit(LIMM, args[2 + i], null, x);
                    z.imm = 0;
                }
            }
            callInstr(HCALL, d, args, Symbols.MULTI_NEW_ARRAY, 0, x);
        }
        else if (op == Op.CHECKCAST || op == Op.INSTANCEOF)
        {
            int ty = temp();
            MInstr ta = emit(TYPEADDR, ty, null, x);
            ta.aux = (int) x.aux;
            callInstr(HCALL, d, new int[] { v(x.in[0]), ty }, op == Op.CHECKCAST ? Symbols.CHECK_CAST
                      : Symbols.INSTANCE_OF, 0, x);
        }
        else if (op == Op.LDC)
        {
            int tag = g.cpTag[(int) x.aux];
            MInstr m = emit(tag == 8 ? LDCSTR : LDCCLASS, d, null, x);
            m.aux = (int) x.aux;
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
        if (x.aux2 != 0xB7 && syms.isIntrinsicCall(cp))
        {
            intrinsic(x, d, syms.intrinsicId(cp));
            return;
        }
        if (x.aux2 == 0xB8 && syms.mathIntrinsicId(cp) >= 0)
        {
            int mh = syms.mathIntrinsicId(cp);
            emit(mh == compiler.Intrinsics.SMULH ? SMULH : UMULH, d, new int[] { v(x.in[0]), v(x.in[1]) }, x);
            return;
        }
        if (x.aux2 == 0xB6 || (x.aux2 == 0xB9 && syms.isObjectPublicMethod(cp)))
        {
            if (virtualSpecial(x, d, cp))
            {
                return;
            }
        }
        int n = x.in.length;
        int[] args = new int[n];
        int[] pins = new int[n];
        for (int i = 0; i < n; i++)
        {
            args[i] = v(x.in[i]);
            pins[i] = i;                                     // argument i in xi (receiver first)
        }
        int mop = CALL;
        long imm = x.aux2 == 0xB8 ? 0 : 1;
        if (x.aux2 == 0xB9 && !syms.isObjectPublicMethod(cp))
        {
            mop = ICALL;
            imm = syms.interfaceSlot(cp);
        }
        else if (x.aux2 == 0xB6 || x.aux2 == 0xB9)
        {
            mop = VCALL;
            imm = syms.vtableSlot(cp);
        }
        callInstr(mop, d, args, cp, imm, x);
    }

    private void callInstr(int mop, int d, int[] args, int aux, long imm, Instr x)
    {
        int[] pins = new int[args.length];
        for (int i = 0; i < args.length; i++)
        {
            pins[i] = i;                                     // argument i in xi (receiver first)
        }
        MInstr m = emit(mop, d, args, x);
        m.fixedUses = pins;
        if (d >= 0)
        {
            m.fixedDefs = new int[] { 0 };
        }
        m.aux = aux;
        m.imm = imm;
        m.clobbers = callerSaved();
    }

    private static boolean anyTrue(boolean[] b)
    {
        for (int i = 0; i < b.length; i++)
        {
            if (b[i])
            {
                return true;
            }
        }
        return false;
    }

    /** As Baseline's loopHeaders: every target of a backward (or self) branch -- where it polls when the operand
     *  stack is empty. */
    private static boolean[] baselineLoopHeaders(byte[] code)
    {
        boolean[] h = new boolean[code.length];
        int pos = 0;
        while (pos < code.length)
        {
            int op = code[pos] & 0xFF;
            int target = -1;
            if ((op >= 0x99 && op <= 0xA7) || op == 0xC6 || op == 0xC7)
            {
                target = pos + (short) (((code[pos + 1] & 0xFF) << 8) | (code[pos + 2] & 0xFF));
            }
            else if (op == 0xC8)
            {
                target = pos + compiler.Baseline.s4(code, pos + 1);
            }
            if (target >= 0 && target <= pos && target < code.length)
            {
                h[target] = true;
            }
            int len = compiler.Baseline.opLen(op, code, pos);
            pos += len < 1 ? 1 : len;
        }
        return h;
    }

    /**
     * As Baseline's isNonLeaf in the writer (no implicit checks): whether its code for this bytecode calls -- over
     * ALL the bytecode, reachable or not, as Baseline scans it. A method that is non-leaf there saves LR and polls in
     * its prologue, so this tier must agree even where its own code makes no call (Math.multiplyHigh is one
     * instruction here, a call-shaped invokestatic there).
     */
    private boolean baselineNonLeaf(byte[] code)
    {
        int pos = 0;
        while (pos < code.length)
        {
            int op = code[pos] & 0xFF;
            int idx = pos + 2 < code.length ? ((code[pos + 1] & 0xFF) << 8) | (code[pos + 2] & 0xFF) : 0;
            boolean intrinsic = (op == 0xB6 || op == 0xB8) && syms.isIntrinsicCall(idx);
            if (intrinsic && syms.intrinsicId(idx) >= compiler.Intrinsics.AA_NEW
                && syms.intrinsicId(idx) <= compiler.Intrinsics.AA_LENGTH)
            {
                if (syms.intrinsicId(idx) == compiler.Intrinsics.AA_NEW)
                {
                    return true;
                }
            }
            else if (op == 0xB6 && intrinsic && !syms.intrinsicEmitsCall(idx))
            {
                op = 0;                                      // an unboxed type's method: instructions
            }
            else if (op == 0xBB || op == 0xBC || op == 0xBD || op == 0xB6 || op == 0xB9 || op == 0xBA || op == 0xBF
                     || op == 0xC0 || op == 0xC1 || op == 0x72 || op == 0x73 || op == 0xC5)
            {
                return true;
            }
            else if ((op == 0xB2 || op == 0xB3) && syms.needsInitGuard(idx))
            {
                return true;
            }
            else if (op == 0x12 || op == 0x13 || op == 0x14)
            {
                int ci = op == 0x12 ? (code[pos + 1] & 0xFF) : idx;
                int tag = g.cpTag[ci];
                if (tag == 15 || tag == 16 || tag == 17)
                {
                    return true;
                }
            }
            else if (op == 0xB8 && (!intrinsic || syms.intrinsicEmitsCall(idx)))
            {
                return true;
            }
            else if (op == 0xB7 && !syms.isSkippableInit(idx))
            {
                return true;
            }
            pos += compiler.Baseline.opLen(code[pos] & 0xFF, code, pos);
        }
        return false;
    }

    /** The intrinsics this tier lowers (O1-6a): those that are instructions on their operands -- no call, no
     *  allocation, no system state but the counter and the barriers. */
    static boolean supportedIntrinsic(int id)
    {
        if (id >= compiler.Intrinsics.UB_MOVE && id <= compiler.Intrinsics.UB_ST64_OFF)
        {
            return true;
        }
        return id == compiler.Intrinsics.AA_NEW || id == compiler.Intrinsics.AA_GET || id == compiler.Intrinsics.AA_SET
               || id == compiler.Intrinsics.AA_LENGTH || id == compiler.Intrinsics.LOAD8
               || id == compiler.Intrinsics.LOAD32 || id == compiler.Intrinsics.LOAD64
               || id == compiler.Intrinsics.STORE8 || id == compiler.Intrinsics.STORE32
               || id == compiler.Intrinsics.STORE64 || id == compiler.Intrinsics.BYTES
               || id == compiler.Intrinsics.ADDR_OF || id == compiler.Intrinsics.FROM_ADDR
               || id == compiler.Intrinsics.DSB || id == compiler.Intrinsics.ISB
               || id == compiler.Intrinsics.READ_CNTPCT_EL0 || id == compiler.Intrinsics.READ_CNTFRQ_EL0;
    }

    /** Baseline's lowerUnboxed / lowerIntrinsic / lowerAddressArray for the ids {@link #supportedIntrinsic} names,
     *  instruction for instruction in effect. Operands are in bytecode order: the receiver (or first argument)
     *  first. */
    private void intrinsic(Instr x, int d, int id)
    {
        int a = x.in.length > 0 ? v(x.in[0]) : -1;
        int b = x.in.length > 1 ? v(x.in[1]) : -1;
        if (id == compiler.Intrinsics.UB_MOVE || id == compiler.Intrinsics.BYTES
            || id == compiler.Intrinsics.ADDR_OF || id == compiler.Intrinsics.FROM_ADDR)
        {
            MInstr m = MInstr.move(d, a);                    // a reinterpret: the same 64-bit word
            m.bci = x.bci;
            cur.add(m);
        }
        else if (id == compiler.Intrinsics.UB_ZEXT32 || id == compiler.Intrinsics.UB_SXTW)
        {
            emit(id == compiler.Intrinsics.UB_ZEXT32 ? UXTW : SXTW, d, new int[] { a }, x);
        }
        else if (id >= compiler.Intrinsics.UB_ZERO && id <= compiler.Intrinsics.UB_SMAX)
        {
            MInstr m = emit(LIMM, d, null, x);
            m.imm = unboxedConstant(id);
        }
        else if (id == compiler.Intrinsics.UB_IS_ZERO)
        {
            MInstr m = emit(CSET, d, new int[] { a }, x);
            m.aux = 0;                                       // eq
        }
        else if (id == compiler.Intrinsics.UB_IS_MAX || id == compiler.Intrinsics.UB_IS_SMAX)
        {
            int k = temp();
            MInstr c = emit(LIMM, k, null, x);
            c.imm = id == compiler.Intrinsics.UB_IS_MAX ? -1L : Long.MAX_VALUE;
            MInstr m = emit(CSET, d, new int[] { a, k }, x);
            m.aux = 0;
        }
        else if (id >= compiler.Intrinsics.UB_ADD && id <= compiler.Intrinsics.UB_ASR && id != compiler.Intrinsics.UB_NOT)
        {
            int[] ops = { ADD, SUB, AND, ORR, EOR, -1, LSLV, LSRV, ASRV };
            emit(ops[id - compiler.Intrinsics.UB_ADD], d, new int[] { a, b }, x);
        }
        else if (id == compiler.Intrinsics.UB_NOT)
        {
            emit(UBNOT, d, new int[] { a }, x);
        }
        else if (id >= compiler.Intrinsics.UB_EQ && id <= compiler.Intrinsics.UB_SGE)
        {
            MInstr m = emit(CSET, d, new int[] { a, b }, x);
            m.aux = unboxedCond(id);
        }
        else if (id == compiler.Intrinsics.UB_LD8S || id == compiler.Intrinsics.UB_LD16U
                 || id == compiler.Intrinsics.UB_LD16S)
        {
            emit(id == compiler.Intrinsics.UB_LD8S ? LDRSB : (id == compiler.Intrinsics.UB_LD16U ? LDRH : LDRSH), d,
                 new int[] { a }, x);
        }
        else if (id == compiler.Intrinsics.UB_ST16)
        {
            store(STRH, a, b, 0, false, x);
        }
        else if (id >= compiler.Intrinsics.UB_LD8S_OFF && id <= compiler.Intrinsics.UB_LD64_OFF)
        {
            int t = temp();
            emit(ADD, t, new int[] { a, b }, x);             // address + Offset
            int k = id - compiler.Intrinsics.UB_LD8S_OFF;
            int[] lops = { LDRSB, LDRB, LDRH, LDRSH, LDRSW, LDRX };
            MInstr m = emit(lops[k], d, new int[] { t }, x);
            m.imm = 0;
        }
        else if (id >= compiler.Intrinsics.UB_ST8_OFF && id <= compiler.Intrinsics.UB_ST64_OFF)
        {
            int t = temp();
            emit(ADD, t, new int[] { a, v(x.in[2]) }, x);    // (address, value, offset)
            int[] sops = { STRB, STRH, STRW, STRX };
            store(sops[id - compiler.Intrinsics.UB_ST8_OFF], t, b, 0, false, x);
        }
        else if (id == compiler.Intrinsics.LOAD8 || id == compiler.Intrinsics.LOAD32
                 || id == compiler.Intrinsics.LOAD64)
        {
            MInstr m = emit(id == compiler.Intrinsics.LOAD8 ? LDRB : (id == compiler.Intrinsics.LOAD32 ? LDRSW : LDRX),
                            d, new int[] { a }, x);
            m.imm = 0;
        }
        else if (id == compiler.Intrinsics.STORE8 || id == compiler.Intrinsics.STORE32
                 || id == compiler.Intrinsics.STORE64)
        {
            store(id == compiler.Intrinsics.STORE8 ? STRB : (id == compiler.Intrinsics.STORE32 ? STRW : STRX), a, b, 0,
                  false, x);
        }
        else if (id == compiler.Intrinsics.AA_NEW)
        {
            int t = newArray(x, a, 8);                       // a long[] (newarray long) ...
            MInstr tag = emit(TAGARRAY, -1, new int[] { t }, x);
            tag.aux = 11;
            MInstr un = emit(TAGUNBOXED, -1, new int[] { t }, x);   // ... retyped when its elements are refs
            un.aux = (int) x.aux;
            MInstr m = MInstr.move(d, t);
            m.bci = x.bci;
            cur.add(m);
        }
        else if (id == compiler.Intrinsics.AA_GET)
        {
            elementAddress(x, a, b, 3, d, LDRX);
        }
        else if (id == compiler.Intrinsics.AA_SET)
        {
            int elem = elementAddress(x, a, b, 3, -1, -1);
            store(STRX, elem, v(x.in[2]), 0, false, x);
            if (ownerHoldsRefs((int) x.aux))
            {
                barrier(elem, v(x.in[2]), true, x);          // Object/DataReferenceArray: the slot log
            }
        }
        else if (id == compiler.Intrinsics.AA_LENGTH)
        {
            MInstr m = emit(LDRX, d, new int[] { a }, x);
            m.imm = ObjectModel.ARRAY_LENGTH_OFFSET;
        }
        else if (id == compiler.Intrinsics.DSB || id == compiler.Intrinsics.ISB)
        {
            MInstr m = emit(FENCE, -1, null, x);
            m.aux = id == compiler.Intrinsics.DSB ? 0 : 1;
        }
        else
        {
            MInstr m = emit(MRS, d, null, x);                // the generic timer's count or its frequency
            m.aux = id == compiler.Intrinsics.READ_CNTPCT_EL0 ? compiler.Target.counter() : asm.A64Enc.CNTFRQ_EL0;
        }
    }

    /** Heap.allocArray(length, elemSize) into a new register (the array, untagged). */
    private int newArray(Instr x, int length, int elemSize)
    {
        int es = temp();
        MInstr k = emit(LIMM, es, null, x);
        k.imm = elemSize;
        int t = temp();
        f.vgc[t] = MFunc.GC_REF;
        callInstr(HCALL, t, new int[] { length, es }, Symbols.HEAP_ALLOC_ARRAY, 0, x);
        return t;
    }

    /** As Baseline's arrayElemSize: the newarray atype's element size in bytes. */
    private static int elemSize(int atype)
    {
        if (atype == 4 || atype == 8)
        {
            return 1;
        }
        if (atype == 5 || atype == 9)
        {
            return 2;
        }
        if (atype == 6 || atype == 10)
        {
            return 4;
        }
        return 8;
    }

    /** {@code &array[index]} for an element of {@code 1 << shift} bytes; with {@code lop} >= 0 also load it into
     *  {@code d}. Answers the address register. */
    private int elementAddress(Instr x, int arr, int index, int shift, int d, int lop)
    {
        int base = temp();
        MInstr a = emit(ADDI, base, new int[] { arr }, x);
        a.imm = ObjectModel.ARRAY_BASE_OFFSET;
        int elem = temp();
        MInstr s = emit(ADDLSL, elem, new int[] { base, index }, x);
        s.imm = shift;
        if (lop >= 0)
        {
            emit(lop, d, new int[] { elem }, x);
        }
        return elem;
    }

    private static long unboxedConstant(int id)
    {
        if (id == compiler.Intrinsics.UB_ZERO)
        {
            return 0L;
        }
        if (id == compiler.Intrinsics.UB_ONE)
        {
            return 1L;
        }
        return id == compiler.Intrinsics.UB_MAX ? -1L : Long.MAX_VALUE;
    }

    /** As Baseline's unboxedCond: the unsigned compares for addresses and words, the signed ones for Offset. */
    private static int unboxedCond(int id)
    {
        int[] conds = { 0, 1, 3, 9, 8, 2, 11, 13, 12, 10 };   // eq ne lo ls hi hs lt le gt ge
        return conds[id - compiler.Intrinsics.UB_EQ];
    }

    /** As Baseline's ownerHoldsRefs: the call's owner is an ObjectReferenceArray or DataReferenceArray, whose set
     *  takes the write barrier; an AddressArray or CodeArray holds raw words. */
    private boolean ownerHoldsRefs(int cp)
    {
        int off = classfile.ClassReader.refClassNameOff(g.cls, g.cpOff, cp);
        int n = classfile.ClassReader.u2(g.cls, off);
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < n; i++)
        {
            h = h ^ (classfile.ClassReader.u1(g.cls, off + 2 + i) & 0xFF);
            h = h * 0x100000001b3L;
        }
        int k = compiler.Unboxed.kindOfHash(h);
        return k == compiler.Unboxed.OBJECT_REFERENCE_ARRAY || k == compiler.Unboxed.DATA_REFERENCE_ARRAY;
    }

    /** The invokevirtuals Baseline lowers to a helper or a constant rather than a dispatch; true if this was one. */
    private boolean virtualSpecial(Instr x, int d, int cp)
    {
        int recv = v(x.in[0]);
        if (syms.isGetClass(cp))
        {
            callInstr(HCALL, d, new int[] { recv }, Symbols.GET_CLASS, 0, x);
            return true;
        }
        if (syms.isArrayClone(cp))
        {
            callInstr(HCALL, d, new int[] { recv }, Symbols.ARRAY_CLONE, 0, x);
            return true;
        }
        if (syms.isDesiredAssertionStatus(cp))
        {
            MInstr m = emit(LIMM, d, null, x);               // assertions are off: false
            m.imm = 0;
            return true;
        }
        int mop = syms.monitorOp(cp);
        if (mop == 1)
        {
            int zero = temp();
            MInstr z = emit(LIMM, zero, null, x);
            z.imm = 0;
            callInstr(HCALL, -1, new int[] { recv, zero }, Symbols.MON_WAIT, 0, x);   // wait() = wait(0)
            return true;
        }
        if (mop == 2)
        {
            callInstr(HCALL, -1, new int[] { recv, v(x.in[1]) }, Symbols.MON_WAIT, 0, x);
            return true;
        }
        if (mop == 3 || mop == 4)
        {
            callInstr(HCALL, -1, new int[] { recv }, mop == 3 ? Symbols.MON_NOTIFY : Symbols.MON_NOTALL, 0, x);
            return true;
        }
        return false;
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

    /** A store, fenced on both sides when the field is volatile (Baseline's release + store-load). */
    private void store(int op, int base, int val, int off, boolean vol, Instr x)
    {
        if (vol)
        {
            emit(DMB, -1, null, x);
        }
        MInstr m = emit(op, -1, new int[] { base, val }, x);
        m.imm = off;
        if (vol)
        {
            emit(DMB, -1, null, x);
        }
    }

    /** The write barrier on copies of {@code obj} and {@code val}: it overwrites both registers, and the copies die
     *  at it, so no value anyone still needs is in them. */
    private void barrier(int obj, int val, boolean slotLog, Instr x)
    {
        int o = temp();
        int w = temp();
        MInstr a = MInstr.move(o, obj);
        a.bci = x.bci;
        cur.add(a);
        MInstr b = MInstr.move(w, val);
        b.bci = x.bci;
        cur.add(b);
        MInstr m = emit(BARRIER, -1, new int[] { o, w }, x);
        m.aux = slotLog ? 1 : 0;
    }

    /** xastore: the element's address, the sized store; aastore logs the ELEMENT in the slot log (S7-4d'). */
    private void arrayStore(Instr x)
    {
        int c = x.aux2;
        int shift = c == 0x54 ? 0 : (c == 0x55 || c == 0x56 ? 1 : (c == 0x4F ? 2 : 3));
        int sop = c == 0x54 ? STRB : (c == 0x55 || c == 0x56 ? STRH : (c == 0x4F ? STRW : STRX));
        int base = temp();
        MInstr a = emit(ADDI, base, new int[] { v(x.in[0]) }, x);
        a.imm = ObjectModel.ARRAY_BASE_OFFSET;
        int elem = temp();
        MInstr s = emit(ADDLSL, elem, new int[] { base, v(x.in[1]) }, x);
        s.imm = shift;
        store(sop, elem, v(x.in[2]), 0, false, x);
        if (c == 0x53)
        {
            barrier(elem, v(x.in[2]), true, x);
        }
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
