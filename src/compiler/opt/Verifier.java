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
 * The IR verifier, run after every phase (PLAN.md "The optimizing compiler"). It checks the shape of the CFG
 * (one terminator per block, edges that agree with it, handlers entered only by exception edges), SSA (every
 * operand defined once and dominating its use, a phi operand dominating its predecessor's end), the kind every
 * opcode expects of its operands, and the framestates. Host tooling: it reports with string concatenation.
 */
public final class Verifier
{
    private final Graph g;
    private int[] pos;          // by instruction id: index in its block, -1 for a phi, MIN for not placed
    private Block[] home;       // by instruction id: the block holding it
    private String error;

    private Verifier(Graph g)
    {
        this.g = g;
    }

    /** Null if {@code g} is well formed, else what is wrong with it. */
    public static String verify(Graph g)
    {
        Verifier v = new Verifier(g);
        v.run();
        return v.error;
    }

    private void bad(String what)
    {
        if (error == null)
        {
            error = what;
        }
    }

    private void run()
    {
        Dominators.compute(g);
        int n = g.instrCount();
        pos = new int[n];
        home = new Block[n];
        for (int i = 0; i < n; i++)
        {
            pos[i] = Integer.MIN_VALUE;
        }
        if (g.nBlocks == 0 || g.blocks[0].nPreds != 0 || g.blocks[0].isHandler)
        {
            bad("entry block malformed");
            return;
        }
        for (int b = 0; b < g.nBlocks && error == null; b++)
        {
            place(g.blocks[b], b);
        }
        for (int b = 0; b < g.nBlocks && error == null; b++)
        {
            Block blk = g.blocks[b];
            structure(blk);
            for (int i = 0; i < blk.nPhis && error == null; i++)
            {
                phi(blk, blk.phis[i]);
            }
            for (int i = 0; i < blk.nCode && error == null; i++)
            {
                instr(blk, blk.code[i]);
            }
        }
    }

    private void place(Block blk, int index)
    {
        if (blk.rpo != index)
        {
            bad("B" + blk.id + " rpo " + blk.rpo + " at index " + index);
            return;
        }
        for (int i = 0; i < blk.nPhis; i++)
        {
            placeOne(blk, blk.phis[i], -1);
        }
        for (int i = 0; i < blk.nCode; i++)
        {
            placeOne(blk, blk.code[i], i);
        }
    }

    private void placeOne(Block blk, Instr x, int p)
    {
        if (x.id < 0 || x.id >= pos.length || pos[x.id] != Integer.MIN_VALUE)
        {
            bad("v" + x.id + " placed twice or bad id");
            return;
        }
        if (x.block != blk || x.removed)
        {
            bad(x.describe() + " block field or removed flag wrong in B" + blk.id);
            return;
        }
        pos[x.id] = p;
        home[x.id] = blk;
    }

    private void structure(Block blk)
    {
        if (blk.nCode == 0)
        {
            bad("B" + blk.id + " is empty");
            return;
        }
        for (int i = 0; i < blk.nCode - 1; i++)
        {
            if (Op.isTerminator(blk.code[i].op))
            {
                bad("B" + blk.id + " terminator mid-block: " + blk.code[i].describe());
                return;
            }
            if (blk.code[i].op == Op.CATCH && i != 0)
            {
                bad("B" + blk.id + " catch not first");
                return;
            }
        }
        Instr t = blk.terminator();
        if (t == null)
        {
            bad("B" + blk.id + " has no terminator");
            return;
        }
        int want = 0;
        if (t.op == Op.GOTO)
        {
            want = 1;
        }
        else if (t.op == Op.IF)
        {
            want = 2;
        }
        else if (t.op == Op.SWITCH)
        {
            want = t.keys == null ? -1 : t.keys.length + 1;
        }
        int have = t.targets == null ? 0 : t.targets.length;
        if (want != have)
        {
            bad("B" + blk.id + " " + Op.name(t.op) + " has " + have + " targets, wants " + want);
            return;
        }
        for (int i = 0; i < have; i++)
        {
            if (!contains(blk.succs, blk.nSuccs, t.targets[i]))
            {
                bad("B" + blk.id + " target B" + t.targets[i].id + " not a successor");
                return;
            }
        }
        for (int i = 0; i < blk.nSuccs; i++)
        {
            Block s = blk.succs[i];
            if (!contains(t.targets, have, s))
            {
                bad("B" + blk.id + " successor B" + s.id + " not a target");
                return;
            }
            if (s.predIndex(blk) < 0 || s.rpo < 0 || g.blocks[s.rpo] != s)
            {
                bad("B" + blk.id + " -> B" + s.id + " edge not mirrored or target not in graph");
                return;
            }
        }
        for (int i = 0; i < blk.nPreds; i++)
        {
            if (!contains(blk.preds[i].succs, blk.preds[i].nSuccs, blk))
            {
                bad("B" + blk.id + " pred B" + blk.preds[i].id + " has no edge to it");
                return;
            }
        }
        for (int i = 0; i < blk.nHandlers; i++)
        {
            Block h = blk.handlers[i];
            if (!h.isHandler || !contains(h.excPreds, h.nExcPreds, blk))
            {
                bad("B" + blk.id + " handler B" + h.id + " not mirrored");
                return;
            }
        }
        if (blk.isHandler)
        {
            if (blk.nPreds != 0 || blk.code[0].op != Op.CATCH)
            {
                bad("handler B" + blk.id + " has normal preds or no catch");
            }
        }
    }

    private static boolean contains(Block[] a, int n, Block x)
    {
        for (int i = 0; i < n; i++)
        {
            if (a[i] == x)
            {
                return true;
            }
        }
        return false;
    }

    private void phi(Block blk, Instr p)
    {
        if (p.op != Op.PHI || p.in.length != blk.nPreds)
        {
            bad(p.describe() + " in B" + blk.id + " is not a phi with one operand per pred");
            return;
        }
        if (!valueKind(p.kind))
        {
            bad(p.describe() + " has no value kind");
            return;
        }
        for (int k = 0; k < p.in.length; k++)
        {
            Instr v = p.in[k];
            if (!defined(v, p))
            {
                return;
            }
            if (!compatible(v.kind, p.kind))
            {
                bad(p.describe() + " operand v" + v.id + ":" + Kind.name(v.kind) + " kind mismatch");
                return;
            }
            Block pred = blk.preds[k];
            if (!Dominators.dominates(home[v.id], pred))
            {
                bad(p.describe() + " operand v" + v.id + " does not dominate pred B" + pred.id);
                return;
            }
        }
    }

    private boolean defined(Instr v, Instr user)
    {
        if (v == null || v.id < 0 || v.id >= pos.length || pos[v.id] == Integer.MIN_VALUE || v.removed)
        {
            bad(user.describe() + " uses an undefined or removed value");
            return false;
        }
        if (!valueKind(v.kind))
        {
            bad(user.describe() + " uses v" + v.id + " of kind " + Kind.name(v.kind));
            return false;
        }
        return true;
    }

    /** {@code v} is available at {@code x}, which is at index {@code at} of {@code blk}. */
    private boolean dominatesUse(Instr v, Block blk, int at)
    {
        Block d = home[v.id];
        if (d == blk)
        {
            return pos[v.id] < at;
        }
        return Dominators.dominates(d, blk);
    }

    private static boolean valueKind(int k)
    {
        return k >= Kind.I && k <= Kind.R;
    }

    private static boolean compatible(int have, int want)
    {
        return have == want || (Kind.refLike(have) && Kind.refLike(want));
    }

    private void instr(Block blk, Instr x)
    {
        for (int k = 0; k < x.in.length; k++)
        {
            Instr v = x.in[k];
            if (!defined(v, x))
            {
                return;
            }
            if (!dominatesUse(v, blk, pos[x.id]))
            {
                bad(x.describe() + " operand v" + v.id + " does not dominate it");
                return;
            }
        }
        if (Op.canThrow(x.op, x.kind) && x.state == null)
        {
            bad(x.describe() + " can throw but has no framestate");
            return;
        }
        if (x.state != null && !state(blk, x))
        {
            return;
        }
        String why = kinds(x);
        if (why != null)
        {
            bad(x.describe() + ": " + why);
        }
    }

    private boolean state(Block blk, Instr x)
    {
        FrameState s = x.state;
        if (s.bci != x.bci || s.locals.length != g.maxLocals)
        {
            bad(x.describe() + " framestate bci/locals malformed");
            return false;
        }
        for (int pass = 0; pass < 2; pass++)
        {
            Instr[] a = pass == 0 ? s.locals : s.stack;
            for (int i = 0; i < a.length; i++)
            {
                Instr v = a[i];
                if (v == null)
                {
                    continue;
                }
                if (!defined(v, x))
                {
                    return false;
                }
                if (!dominatesUse(v, blk, pos[x.id]))
                {
                    bad(x.describe() + " framestate slot " + i + " v" + v.id + " does not dominate it");
                    return false;
                }
            }
        }
        return true;
    }

    private static final int[] CONV_FROM = { Kind.I, Kind.I, Kind.I, Kind.J, Kind.J, Kind.J, Kind.F, Kind.F,
                                             Kind.F, Kind.D, Kind.D, Kind.D, Kind.I, Kind.I, Kind.I };
    private static final int[] CONV_TO = { Kind.J, Kind.F, Kind.D, Kind.I, Kind.F, Kind.D, Kind.I, Kind.J,
                                           Kind.D, Kind.I, Kind.J, Kind.F, Kind.I, Kind.I, Kind.I };

    /** Null if the operand and result kinds fit the opcode. */
    private String kinds(Instr x)
    {
        int op = x.op;
        if (op >= Op.ADD && op <= Op.XOR)
        {
            boolean intOnly = op >= Op.SHL;
            if (x.kind != Kind.I && x.kind != Kind.J && (intOnly || (x.kind != Kind.F && x.kind != Kind.D)))
            {
                return "bad result kind";
            }
            if (x.in[0].kind != x.kind)
            {
                return "operand 0 kind";
            }
            if (op == Op.NEG)
            {
                return x.in.length == 1 ? null : "arity";
            }
            int want = (op == Op.SHL || op == Op.SHR || op == Op.USHR) ? Kind.I : x.kind;
            return x.in.length == 2 && x.in[1].kind == want ? null : "operand 1 kind";
        }
        if (op == Op.CMP)
        {
            int k = x.aux2 == 0x94 ? Kind.J : (x.aux2 <= 0x96 ? Kind.F : Kind.D);
            return x.kind == Kind.I && x.in[0].kind == k && x.in[1].kind == k ? null : "cmp kinds";
        }
        if (op == Op.CONV)
        {
            int i = x.aux2 - 0x85;
            return x.kind == CONV_TO[i] && x.in[0].kind == CONV_FROM[i] ? null : "conv kinds";
        }
        if (op == Op.IF)
        {
            boolean ref = x.aux2 == 0xA5 || x.aux2 == 0xA6 || x.aux2 == 0xC6 || x.aux2 == 0xC7;
            for (int k = 0; k < x.in.length; k++)
            {
                if (ref ? !Kind.refLike(x.in[k].kind) : x.in[k].kind != Kind.I)
                {
                    return "if operand kind";
                }
            }
            return null;
        }
        if (op == Op.SWITCH)
        {
            return x.in[0].kind == Kind.I ? null : "switch selector kind";
        }
        if (op == Op.RETURN)
        {
            if (g.returnKind == Kind.V)
            {
                return x.in.length == 0 ? null : "void method returns a value";
            }
            return x.in.length == 1 && compatible(x.in[0].kind, g.returnKind) ? null : "return kind";
        }
        if (op == Op.GETFIELD || op == Op.ARRAYLENGTH || op == Op.CHECKCAST || op == Op.INSTANCEOF
            || op == Op.MONITORENTER || op == Op.MONITOREXIT || op == Op.THROW)
        {
            return Kind.refLike(x.in[0].kind) ? null : "object operand kind";
        }
        if (op == Op.PUTFIELD || op == Op.PUTSTATIC)
        {
            int fk = Kind.ofType(g.cls, ClassReader.refDescOff(g.cls, g.cpOff, (int) x.aux) + 2);
            Instr v = x.in[x.in.length - 1];
            if (op == Op.PUTFIELD && !Kind.refLike(x.in[0].kind))
            {
                return "object operand kind";
            }
            return compatible(v.kind, fk) ? null : "stored value kind";
        }
        if (op == Op.ALOAD || op == Op.ASTORE)
        {
            return Kind.refLike(x.in[0].kind) && x.in[1].kind == Kind.I ? null : "array/index kind";
        }
        if (op == Op.NEWARRAY || op == Op.ANEWARRAY || op == Op.MULTIANEWARRAY)
        {
            for (int k = 0; k < x.in.length; k++)
            {
                if (x.in[k].kind != Kind.I)
                {
                    return "length kind";
                }
            }
            return null;
        }
        if (op == Op.INVOKE)
        {
            return invokeKinds(x);
        }
        return null;
    }

    private String invokeKinds(Instr x)
    {
        int cp = (int) x.aux;
        int descOff = ClassReader.refDescOff(g.cls, g.cpOff, cp);
        int n = ClassReader.descParamCount(g.cls, descOff);
        boolean hasRecv = x.aux2 != 0xB8 && x.aux2 != 0xBA;
        int first = hasRecv ? 1 : 0;
        if (x.in.length != n + first)
        {
            return "argument count";
        }
        if (hasRecv && !Kind.refLike(x.in[0].kind))
        {
            return "receiver kind";
        }
        int q = descOff + 2 + 1;
        for (int i = 0; i < n; i++)
        {
            if (!compatible(x.in[first + i].kind, Kind.ofType(g.cls, q)))
            {
                return "argument " + i + " kind";
            }
            q = Kind.skipType(g.cls, q);
        }
        return x.kind == Kind.returnOf(g.cls, descOff) ? null : "result kind";
    }
}
