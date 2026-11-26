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

import classfile.ClassReader;

/**
 * Bytecode -> SSA (PLAN.md "The optimizing compiler", O1-1). An abstract interpretation of each block in reverse
 * postorder over a state of SSA values, one per JVM local and operand-stack slot (a long or double takes two, the
 * second holding the {@link #HI} marker). A block with one predecessor starts from that predecessor's exit state;
 * a block with more starts from a phi per slot, created when its first predecessor finishes and completed as the
 * others do -- reverse postorder guarantees a forward predecessor finishes first, so a loop header's phis exist
 * before its back edges arrive. {@link PhiResolver} then removes the trivial phis and infers the kinds.
 *
 * <p>A handler block is reached only by exception edges and starts from the homes: a {@link Op#CATCH} and a
 * {@link Op#LOAD_LOCAL} per local. In a method with a handler, every local definition also stores the home.
 */
public final class SsaBuilder
{
    public static final int OK = 0;
    public static final int R_JSR = 1;
    public static final int R_HANDLER_SHAPE = 2;
    public static final int R_FALLS_OFF = 3;
    public static final int R_STACK = 4;
    public static final int R_OPCODE = 5;
    public static final int R_KIND = 6;
    public static final int R_EMPTY = 7;
    public static final int R_NO_ENTRY = 8;
    public static final int REASONS = 9;
    private static final String[] REASON_NAMES = {
        "ok", "jsr/ret", "handler reached by a normal edge", "falls off the end", "stack depth at a join",
        "unknown opcode", "kind conflict in use", "no code", "block entered before its predecessor",
    };

    /** The second slot of a long or double. */
    static final Instr HI = new Instr(-1, Op.CONST, Kind.HI, new Instr[0], -1);
    /** A slot holding no value (never written, or overwritten by half of a wide value). */
    static final Instr UNDEF = new Instr(-2, Op.CONST, Kind.TOP, new Instr[0], -1);

    private final Graph g;
    private final byte[] code;
    private final byte[] cls;
    private final int[] cpOff;
    private final int[] cpTag;
    private Cfg cfg;
    private int reason;
    private int reasonBci;

    private Instr[][] entryLoc;     // by block id; null until a predecessor finishes
    private Instr[][] entryStk;
    private int[] entrySp;
    private Instr[][] handlerLoads; // by handler block id: the LOAD_LOCAL per local slot

    private Instr[] loc;
    private Instr[] stk;
    private int sp;
    private Block cur;
    private int pc;
    private int opSp;               // the stack depth when the current bytecode began (its framestate's)

    public SsaBuilder(byte[] cls, int[] cpOff, int[] cpTag, byte[] code, int descOff, boolean isStatic,
                      boolean isSynchronized, int maxLocals, int[] exStart, int[] exEnd, int[] exHandler,
                      int[] exCatch, int exCount)
    {
        this.g = new Graph(cls, cpOff, cpTag, descOff, isStatic, isSynchronized, maxLocals, exStart, exEnd,
                           exHandler, exCatch, exCount);
        this.code = code;
        this.cls = cls;
        this.cpOff = cpOff;
        this.cpTag = cpTag;
    }

    public static String reasonName(int r)
    {
        return REASON_NAMES[r];
    }

    public int reason()
    {
        return reason;
    }

    public int reasonBci()
    {
        return reasonBci;
    }

    /** Build the graph, or answer null with {@link #reason} set. */
    public Graph build()
    {
        if (code == null || code.length == 0)
        {
            reason = R_EMPTY;
            return null;
        }
        cfg = new Cfg(g, code);
        if (!cfg.build())
        {
            reason = cfg.reason;
            reasonBci = cfg.reasonBci;
            return null;
        }
        int n = cfg.nAll;
        entryLoc = new Instr[n][];
        entryStk = new Instr[n][];
        entrySp = new int[n];
        handlerLoads = new Instr[n][];
        for (int i = 0; i < g.nBlocks; i++)
        {
            if (g.blocks[i].isHandler)
            {
                g.homes = true;
            }
        }
        for (int i = 0; i < g.nBlocks; i++)
        {
            if (g.blocks[i].isHandler)
            {
                startHandler(g.blocks[i]);
            }
        }
        buildEntry();
        for (int i = 1; i < g.nBlocks && reason == OK; i++)
        {
            buildBlock(g.blocks[i]);
        }
        if (reason != OK)
        {
            return null;
        }
        PhiResolver pr = new PhiResolver(g);
        if (!pr.resolve())
        {
            reason = R_KIND;
            reasonBci = pr.badBci;
            return null;
        }
        return g;
    }

    private void fail(int r)
    {
        if (reason == OK)
        {
            reason = r;
            reasonBci = pc;
        }
    }

    // ----- blocks ------------------------------------------------------------------------------------------

    /** The synthetic entry: a PARAM per argument, in the locals the JVM assigns them. */
    private void buildEntry()
    {
        Block b = g.blocks[0];
        cur = b;
        pc = -1;
        loc = new Instr[g.maxLocals];
        fillUndef(loc);
        stk = new Instr[4];
        sp = 0;
        int slot = 0;
        int index = 0;
        if (!g.isStatic)
        {
            Instr p = emit(Op.PARAM, Kind.A, new Instr[0]);
            p.aux = 0;
            setLocal(0, p, false);
            slot = 1;
            index = 1;
        }
        int q = g.descOff + 2 + 1;
        while (ClassReader.u1(cls, q) != ')')
        {
            int k = Kind.ofType(cls, q);
            Instr p = emit(Op.PARAM, k, new Instr[0]);
            p.aux = index;
            setLocal(slot, p, Kind.wide(k));
            slot += Kind.wide(k) ? 2 : 1;
            index += 1;
            q = Kind.skipType(cls, q);
        }
        Instr gt = emit(Op.GOTO, Kind.V, new Instr[0]);
        gt.targets = new Block[] { b.succs[0] };
        flowTo(b.succs[0]);
    }

    /** Create a handler's entry instructions before any block runs, so covered blocks can add their sources. */
    private void startHandler(Block h)
    {
        cur = h;
        pc = h.startBci;
        Instr c = emit(Op.CATCH, Kind.A, new Instr[0]);
        c.state = null;
        Instr[] loads = new Instr[g.maxLocals];
        for (int s = 0; s < g.maxLocals; s++)
        {
            Instr l = emit(Op.LOAD_LOCAL, Kind.PENDING, new Instr[0]);
            l.aux = s;
            loads[s] = l;
        }
        handlerLoads[h.id] = loads;
        entryLoc[h.id] = copy(loads, g.maxLocals);
        entryStk[h.id] = new Instr[] { c };
        entrySp[h.id] = 1;
    }

    private void buildBlock(Block b)
    {
        cur = b;
        pc = b.startBci;
        Instr[] el = entryLoc[b.id];
        if (el == null)
        {
            fail(R_NO_ENTRY);                            // a reachable block no predecessor flowed into
            return;
        }
        loc = copy(el, g.maxLocals);
        stk = copy(entryStk[b.id], entryStk[b.id].length + 8);
        sp = entrySp[b.id];
        noteHomes(-1);
        while (pc < b.endBci && reason == OK)
        {
            int op = code[pc] & 0xFF;
            int len = compiler.Baseline.opLen(op, code, pc);
            opSp = sp;
            step(op);
            if (cur.terminator() != null)
            {
                return;
            }
            pc += len;
        }
        if (reason == OK)
        {
            Block next = cfg.blockAt[b.endBci];
            Instr gt = emit(Op.GOTO, Kind.V, new Instr[0]);
            gt.targets = new Block[] { next };
            flowTo(next);
        }
    }

    /** Carry the current state into successor {@code s}: its entry state, or one operand of each of its phis. */
    private void flowTo(Block s)
    {
        if (s.nPreds == 1)
        {
            entryLoc[s.id] = copy(loc, g.maxLocals);
            entryStk[s.id] = copy(stk, sp);
            entrySp[s.id] = sp;
            return;
        }
        if (entryLoc[s.id] == null)
        {
            // The second half of a wide value is not a value: it stays the HI marker. On the stack every other
            // predecessor must agree (checked below); in a local a disagreeing predecessor makes the slot one no
            // bytecode may read, which the marker already is.
            Instr[] pl = new Instr[g.maxLocals];
            for (int i = 0; i < g.maxLocals; i++)
            {
                pl[i] = loc[i] == HI ? HI : newPhi(s);
            }
            Instr[] ps = new Instr[sp];
            for (int i = 0; i < sp; i++)
            {
                ps[i] = stk[i] == HI ? HI : newPhi(s);
            }
            entryLoc[s.id] = pl;
            entryStk[s.id] = ps;
            entrySp[s.id] = sp;
        }
        if (entrySp[s.id] != sp)
        {
            fail(R_STACK);
            return;
        }
        int k = s.predIndex(cur);
        Instr[] pl = entryLoc[s.id];
        for (int i = 0; i < g.maxLocals; i++)
        {
            if (pl[i] != HI)
            {
                pl[i].in[k] = loc[i];
            }
        }
        Instr[] ps = entryStk[s.id];
        for (int i = 0; i < sp; i++)
        {
            if (ps[i] == HI || stk[i] == HI)
            {
                if (ps[i] != stk[i])
                {
                    fail(R_STACK);
                    return;
                }
                continue;
            }
            ps[i].in[k] = stk[i];
        }
    }

    private Instr newPhi(Block s)
    {
        Instr p = g.make(Op.PHI, Kind.PENDING, new Instr[s.nPreds], -1);
        s.addPhi(p);
        return p;
    }

    /** Record the current home contents as sources of every covering handler's loads: all locals at block
     *  entry ({@code slot} -1), or the slots a store to {@code slot} changed. */
    private void noteHomes(int slot)
    {
        if (cur.nHandlers == 0)
        {
            return;
        }
        for (int h = 0; h < cur.nHandlers; h++)
        {
            Instr[] loads = handlerLoads[cur.handlers[h].id];
            if (slot < 0)
            {
                for (int s = 0; s < g.maxLocals; s++)
                {
                    loads[s].addSrc(loc[s]);
                }
            }
            else
            {
                for (int s = slot - 1; s <= slot + 2; s++)
                {
                    if (s >= 0 && s < g.maxLocals)
                    {
                        loads[s].addSrc(loc[s]);
                    }
                }
            }
        }
    }

    // ----- state -------------------------------------------------------------------------------------------

    private static Instr[] copy(Instr[] a, int n)
    {
        Instr[] b = new Instr[n < 4 ? 4 : n];
        int m = a.length < n ? a.length : n;
        for (int i = 0; i < m; i++)
        {
            b[i] = a[i];
        }
        return b;
    }

    private static void fillUndef(Instr[] a)
    {
        for (int i = 0; i < a.length; i++)
        {
            a[i] = UNDEF;
        }
    }

    private Instr emit(int op, int kind, Instr[] in)
    {
        Instr x = g.make(op, kind, in, pc);
        if (Op.canThrow(op, kind))
        {
            x.state = snapshot();
        }
        cur.append(x);
        return x;
    }

    /** The state BEFORE the current bytecode: its operands are still in {@code stk} below {@link #opSp}, since a
     *  pop only moves {@code sp} and every throwing instruction is emitted before its result is pushed. */
    private FrameState snapshot()
    {
        Instr[] l = new Instr[g.maxLocals];
        for (int i = 0; i < g.maxLocals; i++)
        {
            l[i] = loc[i];
        }
        Instr[] s = new Instr[opSp];
        for (int i = 0; i < opSp; i++)
        {
            s[i] = stk[i];
        }
        return new FrameState(pc, l, s);
    }

    private void push(Instr v)
    {
        if (sp + 2 > stk.length)
        {
            stk = copy(stk, stk.length * 2 + 2);
        }
        stk[sp] = v;
        sp += 1;
        if (Kind.wide(v.kind))
        {
            stk[sp] = HI;
            sp += 1;
        }
    }

    private void pushSlot(Instr v)
    {
        if (sp + 1 > stk.length)
        {
            stk = copy(stk, stk.length * 2 + 2);
        }
        stk[sp] = v;
        sp += 1;
    }

    private Instr popSlot()
    {
        if (sp == 0)
        {
            fail(R_STACK);
            return UNDEF;
        }
        sp -= 1;
        return stk[sp];
    }

    /** Pop one value of kind {@code k} (two slots if {@code k} is wide). */
    private Instr pop(int k)
    {
        if (Kind.wide(k))
        {
            Instr hi = popSlot();
            if (hi != HI)
            {
                fail(R_STACK);
            }
        }
        return popSlot();
    }

    /** Store {@code v} to local {@code n}; {@code wide} comes from the bytecode, since a phi's kind is not known
     *  yet. */
    private void setLocal(int n, Instr v, boolean wide)
    {
        if (n > 0 && loc[n] == HI)
        {
            loc[n - 1] = UNDEF;                          // the low half of a wide value just lost its high half
        }
        loc[n] = v;
        int after = n + 1;
        if (wide)
        {
            loc[n + 1] = HI;
            after = n + 2;
        }
        if (after < g.maxLocals && loc[after] == HI)
        {
            loc[after] = UNDEF;                          // the high half of a wide value whose low half just went
        }
        if (g.homes)
        {
            Instr st = g.make(Op.STORE_LOCAL, Kind.V, new Instr[] { v }, pc);
            st.aux = n;
            cur.append(st);
            noteHomes(n);
        }
    }

    private Instr constant(int kind, long bits)
    {
        Instr c = emit(Op.CONST, kind, new Instr[0]);
        c.aux = bits;
        return c;
    }

    private int s1(int i)
    {
        return code[i];
    }

    private int u1(int i)
    {
        return code[i] & 0xFF;
    }

    private int s2(int i)
    {
        return (short) ((u1(i) << 8) | u1(i + 1));
    }

    private int u2(int i)
    {
        return (u1(i) << 8) | u1(i + 1);
    }

    // ----- the interpreter ---------------------------------------------------------------------------------

    private static final int[] ARITH_KINDS = { Kind.I, Kind.J, Kind.F, Kind.D };

    private void step(int op)
    {
        if (op == 0x00)
        {
            return;
        }
        if (op == 0x01)
        {
            push(emit(Op.NULL, Kind.A, new Instr[0]));
        }
        else if (op >= 0x02 && op <= 0x08)
        {
            push(constant(Kind.I, op - 0x03));
        }
        else if (op == 0x09 || op == 0x0A)
        {
            push(constant(Kind.J, op - 0x09));
        }
        else if (op >= 0x0B && op <= 0x0D)
        {
            push(constant(Kind.F, Float.floatToRawIntBits(op - 0x0B) & 0xFFFFFFFFL));
        }
        else if (op == 0x0E || op == 0x0F)
        {
            push(constant(Kind.D, Double.doubleToRawLongBits(op - 0x0E)));
        }
        else if (op == 0x10)
        {
            push(constant(Kind.I, s1(pc + 1)));
        }
        else if (op == 0x11)
        {
            push(constant(Kind.I, s2(pc + 1)));
        }
        else if (op == 0x12)
        {
            ldc(u1(pc + 1));
        }
        else if (op == 0x13 || op == 0x14)
        {
            ldc(u2(pc + 1));
        }
        else if (op >= 0x15 && op <= 0x19)
        {
            load(u1(pc + 1), op - 0x15);
        }
        else if (op >= 0x1A && op <= 0x2D)
        {
            load((op - 0x1A) & 3, (op - 0x1A) >> 2);
        }
        else if (op >= 0x2E && op <= 0x35)
        {
            arrayLoad(op);
        }
        else if (op >= 0x36 && op <= 0x3A)
        {
            store(u1(pc + 1), op - 0x36);
        }
        else if (op >= 0x3B && op <= 0x4E)
        {
            store((op - 0x3B) & 3, (op - 0x3B) >> 2);
        }
        else if (op >= 0x4F && op <= 0x56)
        {
            arrayStore(op);
        }
        else if (op >= 0x57 && op <= 0x5F)
        {
            stackOp(op);
        }
        else if (op >= 0x60 && op <= 0x73)
        {
            int k = ARITH_KINDS[(op - 0x60) & 3];
            int[] ops = { Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.REM };
            Instr b = pop(k);
            Instr a = pop(k);
            push(emit(ops[(op - 0x60) >> 2], k, new Instr[] { a, b }));
        }
        else if (op >= 0x74 && op <= 0x77)
        {
            int k = ARITH_KINDS[op - 0x74];
            push(emit(Op.NEG, k, new Instr[] { pop(k) }));
        }
        else if (op >= 0x78 && op <= 0x7D)
        {
            int k = (op & 1) == 0 ? Kind.I : Kind.J;
            int[] ops = { Op.SHL, Op.SHR, Op.USHR };
            Instr n = pop(Kind.I);
            Instr a = pop(k);
            push(emit(ops[(op - 0x78) >> 1], k, new Instr[] { a, n }));
        }
        else if (op >= 0x7E && op <= 0x83)
        {
            int k = (op & 1) == 0 ? Kind.I : Kind.J;
            int[] ops = { Op.AND, Op.OR, Op.XOR };
            Instr b = pop(k);
            Instr a = pop(k);
            push(emit(ops[(op - 0x7E) >> 1], k, new Instr[] { a, b }));
        }
        else if (op == 0x84)
        {
            iinc(u1(pc + 1), s1(pc + 2));
        }
        else if (op >= 0x85 && op <= 0x93)
        {
            conv(op);
        }
        else if (op >= 0x94 && op <= 0x98)
        {
            int k = op == 0x94 ? Kind.J : (op <= 0x96 ? Kind.F : Kind.D);
            Instr b = pop(k);
            Instr a = pop(k);
            Instr c = emit(Op.CMP, Kind.I, new Instr[] { a, b });
            c.aux2 = op;
            push(c);
        }
        else if ((op >= 0x99 && op <= 0x9E) || op == 0xC6 || op == 0xC7)
        {
            Instr a = pop(op >= 0xC6 ? Kind.A : Kind.I);
            branch(op, new Instr[] { a });
        }
        else if (op >= 0x9F && op <= 0xA6)
        {
            int k = op >= 0xA5 ? Kind.A : Kind.I;
            Instr b = pop(k);
            Instr a = pop(k);
            branch(op, new Instr[] { a, b });
        }
        else if (op == 0xA7 || op == 0xC8)
        {
            int off = op == 0xA7 ? s2(pc + 1) : compiler.Baseline.s4(code, pc + 1);
            Block t = cfg.blockAt[pc + off];
            Instr gt = emit(Op.GOTO, Kind.V, new Instr[0]);
            gt.targets = new Block[] { t };
            flowTo(t);
        }
        else if (op == 0xAA || op == 0xAB)
        {
            tableSwitch();
        }
        else if (op >= 0xAC && op <= 0xB0)
        {
            int k = op == 0xAD ? Kind.J : (op == 0xAF ? Kind.D : (op == 0xAE ? Kind.F : Kind.I));
            if (op == 0xB0)
            {
                k = Kind.A;
            }
            emit(Op.RETURN, Kind.V, new Instr[] { pop(k) });
        }
        else if (op == 0xB1)
        {
            emit(Op.RETURN, Kind.V, new Instr[0]);
        }
        else if (op >= 0xB2 && op <= 0xB5)
        {
            field(op, u2(pc + 1));
        }
        else if (op >= 0xB6 && op <= 0xBA)
        {
            invoke(op, u2(pc + 1));
        }
        else if (op == 0xBB)
        {
            Instr x = emit(Op.NEW, Kind.A, new Instr[0]);
            x.aux = u2(pc + 1);
            push(x);
        }
        else if (op == 0xBC)
        {
            Instr x = emit(Op.NEWARRAY, Kind.A, new Instr[] { pop(Kind.I) });
            x.aux2 = u1(pc + 1);
            push(x);
        }
        else if (op == 0xBD)
        {
            Instr x = emit(Op.ANEWARRAY, Kind.A, new Instr[] { pop(Kind.I) });
            x.aux = u2(pc + 1);
            push(x);
        }
        else if (op == 0xBE)
        {
            push(emit(Op.ARRAYLENGTH, Kind.I, new Instr[] { pop(Kind.A) }));
        }
        else if (op == 0xBF)
        {
            emit(Op.THROW, Kind.V, new Instr[] { pop(Kind.A) });
        }
        else if (op == 0xC0)
        {
            int cp = u2(pc + 1);
            Instr obj = pop(Kind.A);
            Instr x = emit(Op.CHECKCAST, Kind.ofClassName(cls, ClassReader.classNameOff(cls, cpOff, cp)),
                           new Instr[] { obj });
            x.aux = cp;
            push(x);
        }
        else if (op == 0xC1)
        {
            Instr obj = pop(Kind.A);
            Instr x = emit(Op.INSTANCEOF, Kind.I, new Instr[] { obj });
            x.aux = u2(pc + 1);
            push(x);
        }
        else if (op == 0xC2 || op == 0xC3)
        {
            emit(op == 0xC2 ? Op.MONITORENTER : Op.MONITOREXIT, Kind.V, new Instr[] { pop(Kind.A) });
        }
        else if (op == 0xC4)
        {
            wide();
        }
        else if (op == 0xC5)
        {
            int dims = u1(pc + 3);
            Instr[] in = new Instr[dims];
            for (int i = dims - 1; i >= 0; i--)
            {
                in[i] = pop(Kind.I);
            }
            Instr x = emit(Op.MULTIANEWARRAY, Kind.A, in);
            x.aux = u2(pc + 1);
            push(x);
        }
        else
        {
            fail(R_OPCODE);
        }
    }

    /** xload: {@code t} is 0..4 for i, l, f, d, a. */
    private void load(int n, int t)
    {
        pushSlot(loc[n]);
        if (t == 1 || t == 3)
        {
            pushSlot(HI);
        }
    }

    /** xstore: {@code t} is 0..4 for i, l, f, d, a. */
    private void store(int n, int t)
    {
        int k = t == 1 ? Kind.J : (t == 3 ? Kind.D : Kind.I);
        if (t == 2)
        {
            k = Kind.F;
        }
        if (t == 4)
        {
            k = Kind.A;
        }
        setLocal(n, pop(k), Kind.wide(k));
    }

    private void iinc(int n, int delta)
    {
        Instr c = constant(Kind.I, delta);
        setLocal(n, emit(Op.ADD, Kind.I, new Instr[] { loc[n], c }), false);
    }

    private void wide()
    {
        int op = u1(pc + 1);
        int n = u2(pc + 2);
        if (op == 0x84)
        {
            iinc(n, s2(pc + 4));
        }
        else if (op >= 0x15 && op <= 0x19)
        {
            load(n, op - 0x15);
        }
        else if (op >= 0x36 && op <= 0x3A)
        {
            store(n, op - 0x36);
        }
        else
        {
            fail(R_OPCODE);
        }
    }

    private void ldc(int ci)
    {
        int tag = cpTag[ci];
        if (tag == 3)
        {
            push(constant(Kind.I, ClassReader.intValue(cls, cpOff, ci)));
        }
        else if (tag == 4)
        {
            push(constant(Kind.F, ClassReader.intValue(cls, cpOff, ci) & 0xFFFFFFFFL));
        }
        else if (tag == 5)
        {
            push(constant(Kind.J, ClassReader.longValue(cls, cpOff, ci)));
        }
        else if (tag == 6)
        {
            push(constant(Kind.D, ClassReader.longValue(cls, cpOff, ci)));
        }
        else
        {
            int k = Kind.A;
            if (tag == 17)
            {
                k = Kind.ofType(cls, ClassReader.refDescOff(cls, cpOff, ci) + 2);
            }
            Instr x = emit(Op.LDC, k, new Instr[0]);
            x.aux = ci;
            push(x);
        }
    }

    private static final int[] ALOAD_KINDS = { Kind.I, Kind.J, Kind.F, Kind.D, Kind.A, Kind.I, Kind.I, Kind.I };

    private void arrayLoad(int op)
    {
        Instr idx = pop(Kind.I);
        Instr arr = pop(Kind.A);
        Instr x = emit(Op.ALOAD, ALOAD_KINDS[op - 0x2E], new Instr[] { arr, idx });
        x.aux2 = op;
        push(x);
    }

    private void arrayStore(int op)
    {
        Instr v = pop(ALOAD_KINDS[op - 0x4F]);
        Instr idx = pop(Kind.I);
        Instr arr = pop(Kind.A);
        Instr x = emit(Op.ASTORE, Kind.V, new Instr[] { arr, idx, v });
        x.aux2 = op;
    }

    private void conv(int op)
    {
        // from, to for i2l .. i2s (0x85 .. 0x93)
        int[] from = { Kind.I, Kind.I, Kind.I, Kind.J, Kind.J, Kind.J, Kind.F, Kind.F, Kind.F, Kind.D, Kind.D,
                       Kind.D, Kind.I, Kind.I, Kind.I };
        int[] to = { Kind.J, Kind.F, Kind.D, Kind.I, Kind.F, Kind.D, Kind.I, Kind.J, Kind.D, Kind.I, Kind.J,
                     Kind.F, Kind.I, Kind.I, Kind.I };
        int i = op - 0x85;
        Instr x = emit(Op.CONV, to[i], new Instr[] { pop(from[i]) });
        x.aux2 = op;
        push(x);
    }

    /** pop .. swap, as permutations of JVM slots (a wide value's {@link #HI} moves with it). */
    private void stackOp(int op)
    {
        if (op == 0x57)
        {
            popSlot();
            return;
        }
        if (op == 0x58)
        {
            popSlot();
            popSlot();
            return;
        }
        if (op == 0x5F)
        {
            Instr a = popSlot();
            Instr b = popSlot();
            pushSlot(a);
            pushSlot(b);
            return;
        }
        int n = op == 0x59 || op == 0x5A || op == 0x5B ? 1 : 2;      // slots duplicated
        int under = 0;                                                 // slots the copy goes beneath
        if (op == 0x5A || op == 0x5D)
        {
            under = 1;
        }
        else if (op == 0x5B || op == 0x5E)
        {
            under = 2;
        }
        if (sp < n + under)
        {
            fail(R_STACK);
            return;
        }
        Instr[] top = new Instr[n + under];
        for (int i = n + under - 1; i >= 0; i--)
        {
            top[i] = popSlot();
        }
        for (int i = under; i < n + under; i++)
        {
            pushSlot(top[i]);
        }
        for (int i = 0; i < n + under; i++)
        {
            pushSlot(top[i]);
        }
    }

    private void branch(int op, Instr[] in)
    {
        Instr x = emit(Op.IF, Kind.V, in);
        x.aux2 = op;
        Block taken = cfg.blockAt[pc + s2(pc + 1)];
        Block fall = cfg.blockAt[cur.endBci];
        x.targets = new Block[] { taken, fall };
        flowTo(taken);
        if (fall != taken)
        {
            flowTo(fall);
        }
    }

    private void tableSwitch()
    {
        int n = cfg.switchCount(pc);
        Instr sel = pop(Kind.I);
        Instr x = emit(Op.SWITCH, Kind.V, new Instr[] { sel });
        x.keys = new int[n];
        x.targets = new Block[n + 1];
        x.targets[0] = cfg.blockAt[cfg.switchTarget(pc, -1)];
        for (int k = 0; k < n; k++)
        {
            x.keys[k] = cfg.switchKey(pc, k);
            x.targets[k + 1] = cfg.blockAt[cfg.switchTarget(pc, k)];
        }
        for (int i = 0; i < cur.nSuccs; i++)
        {
            flowTo(cur.succs[i]);
        }
    }

    private void field(int op, int cp)
    {
        int k = Kind.ofType(cls, ClassReader.refDescOff(cls, cpOff, cp) + 2);
        Instr x;
        if (op == 0xB2)
        {
            x = emit(Op.GETSTATIC, k, new Instr[0]);
            x.aux = cp;
            push(x);
        }
        else if (op == 0xB3)
        {
            x = emit(Op.PUTSTATIC, Kind.V, new Instr[] { pop(k) });
            x.aux = cp;
        }
        else if (op == 0xB4)
        {
            Instr obj = pop(Kind.A);
            x = emit(Op.GETFIELD, k, new Instr[] { obj });
            x.aux = cp;
            push(x);
        }
        else
        {
            Instr v = pop(k);
            Instr obj = pop(Kind.A);
            x = emit(Op.PUTFIELD, Kind.V, new Instr[] { obj, v });
            x.aux = cp;
        }
    }

    private void invoke(int op, int cp)
    {
        int descOff = ClassReader.refDescOff(cls, cpOff, cp);
        int n = ClassReader.descParamCount(cls, descOff);
        boolean hasRecv = op != 0xB8 && op != 0xBA;
        int[] kinds = new int[n];
        int q = descOff + 2 + 1;
        for (int i = 0; i < n; i++)
        {
            kinds[i] = Kind.ofType(cls, q);
            q = Kind.skipType(cls, q);
        }
        int total = hasRecv ? n + 1 : n;
        Instr[] in = new Instr[total];
        for (int i = n - 1; i >= 0; i--)
        {
            in[hasRecv ? i + 1 : i] = pop(kinds[i]);
        }
        if (hasRecv)
        {
            in[0] = pop(Kind.A);
        }
        int rk = Kind.returnOf(cls, descOff);
        Instr x = emit(Op.INVOKE, rk, in);
        x.aux = cp;
        x.aux2 = op;
        if (rk != Kind.V)
        {
            push(x);
        }
    }
}
