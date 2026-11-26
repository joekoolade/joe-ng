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

/**
 * The builder's cleanup: remove trivial phis (every operand the phi itself or one other value), infer the kind of
 * each remaining phi and handler home load from what flows into it, and drop the ones whose kinds conflict -- a
 * slot two paths fill with different kinds, which no later bytecode may read. A real use of such a value is a
 * failure (the bytecode verifier would have rejected it, so it means the builder is wrong).
 */
final class PhiResolver
{
    /** A merge input that is the null constant: it takes the kind of any reference-like partner. */
    private static final int NULLISH = 12;

    private final Graph g;
    int badBci = -1;

    PhiResolver(Graph g)
    {
        this.g = g;
    }

    boolean resolve()
    {
        removeTrivial();
        inferKinds();
        if (!rewriteUses())
        {
            return false;
        }
        compact();
        return true;
    }

    private void removeTrivial()
    {
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int b = 0; b < g.nBlocks; b++)
            {
                Block blk = g.blocks[b];
                for (int i = 0; i < blk.nPhis; i++)
                {
                    Instr p = blk.phis[i];
                    if (p.removed)
                    {
                        continue;
                    }
                    Instr same = null;
                    boolean trivial = true;
                    for (int k = 0; k < p.in.length; k++)
                    {
                        Instr v = operand(p.in[k]);
                        if (v == p || v == same)
                        {
                            continue;
                        }
                        if (same != null)
                        {
                            trivial = false;
                            break;
                        }
                        same = v;
                    }
                    if (trivial)
                    {
                        p.forward = same == null ? SsaBuilder.UNDEF : same;
                        p.removed = true;
                        changed = true;
                    }
                }
            }
        }
    }

    private static Instr operand(Instr v)
    {
        if (v == null)
        {
            return SsaBuilder.UNDEF;
        }
        return Instr.resolve(v);
    }

    private static boolean merging(Instr x)
    {
        return x.op == Op.PHI || x.op == Op.LOAD_LOCAL;
    }

    /** The lattice value of {@code v} for a merge. */
    private static int latticeOf(Instr v)
    {
        if (v == SsaBuilder.UNDEF)
        {
            return Kind.TOP;
        }
        if (v == SsaBuilder.HI)
        {
            return Kind.HI;
        }
        if (v.op == Op.NULL)
        {
            return NULLISH;
        }
        return v.kind;
    }

    private static int merge(int a, int b)
    {
        if (a == Kind.PENDING)
        {
            return b;
        }
        if (b == Kind.PENDING || a == b)
        {
            return a;
        }
        if (a == NULLISH && Kind.refLike(b))
        {
            return b;
        }
        if (b == NULLISH && Kind.refLike(a))
        {
            return a;
        }
        return Kind.TOP;
    }

    private void inferKinds()
    {
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int b = 0; b < g.nBlocks; b++)
            {
                Block blk = g.blocks[b];
                for (int i = 0; i < blk.nPhis; i++)
                {
                    Instr p = blk.phis[i];
                    if (!p.removed && infer(p, p.in, p.in.length))
                    {
                        changed = true;
                    }
                }
                for (int i = 0; i < blk.nCode; i++)
                {
                    Instr x = blk.code[i];
                    if (x.op == Op.LOAD_LOCAL && infer(x, x.src, x.nSrc))
                    {
                        changed = true;
                    }
                }
            }
        }
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            for (int i = 0; i < blk.nPhis; i++)
            {
                settle(blk.phis[i]);
            }
            for (int i = 0; i < blk.nCode; i++)
            {
                if (blk.code[i].op == Op.LOAD_LOCAL)
                {
                    settle(blk.code[i]);
                }
            }
        }
    }

    private static boolean infer(Instr x, Instr[] from, int n)
    {
        int k = Kind.PENDING;
        if (n == 0)
        {
            k = Kind.TOP;                               // a home nothing covered ever stored
        }
        for (int i = 0; i < n; i++)
        {
            Instr v = operand(from[i]);
            if (v == x)
            {
                continue;
            }
            k = merge(k, latticeOf(v));
        }
        if (k == Kind.PENDING)
        {
            return false;
        }
        int now = merge(x.kind, k);
        if (x.kind == Kind.PENDING)
        {
            now = k;
        }
        if (now != x.kind)
        {
            x.kind = now;
            return true;
        }
        return false;
    }

    private static void settle(Instr x)
    {
        if (x.removed)
        {
            return;
        }
        if (x.kind == NULLISH)
        {
            x.kind = Kind.A;
        }
        else if (x.kind == Kind.PENDING)
        {
            x.kind = Kind.TOP;
        }
        else if (x.kind == Kind.HI)
        {
            x.forward = SsaBuilder.HI;
            x.removed = true;
        }
    }

    /** Point every operand, framestate slot and home source at what stands for it now; false if an instruction
     *  uses a value no bytecode may read. */
    private boolean rewriteUses()
    {
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            for (int i = 0; i < blk.nPhis; i++)
            {
                Instr p = blk.phis[i];
                if (p.removed || p.kind == Kind.TOP)
                {
                    continue;
                }
                for (int k = 0; k < p.in.length; k++)
                {
                    p.in[k] = operand(p.in[k]);
                }
            }
            for (int i = 0; i < blk.nCode; i++)
            {
                Instr x = blk.code[i];
                for (int k = 0; k < x.in.length; k++)
                {
                    Instr v = operand(x.in[k]);
                    if (unusable(v))
                    {
                        badBci = x.bci;
                        return false;
                    }
                    x.in[k] = v;
                }
                if (x.state != null)
                {
                    cleanState(x.state.locals);
                    cleanState(x.state.stack);
                }
                for (int k = 0; k < x.nSrc; k++)
                {
                    x.src[k] = operand(x.src[k]);
                }
            }
        }
        return true;
    }

    private static boolean unusable(Instr v)
    {
        return v == SsaBuilder.UNDEF || v == SsaBuilder.HI || v.kind == Kind.TOP || v.removed;
    }

    private static void cleanState(Instr[] a)
    {
        for (int i = 0; i < a.length; i++)
        {
            Instr v = operand(a[i]);
            a[i] = unusable(v) ? null : v;
        }
    }

    /** Drop removed and conflicting phis and home loads from their blocks. */
    private void compact()
    {
        for (int b = 0; b < g.nBlocks; b++)
        {
            Block blk = g.blocks[b];
            int k = 0;
            for (int i = 0; i < blk.nPhis; i++)
            {
                Instr p = blk.phis[i];
                if (!p.removed && p.kind != Kind.TOP)
                {
                    blk.phis[k] = p;
                    k += 1;
                }
                else
                {
                    p.removed = true;
                }
            }
            blk.nPhis = k;
            k = 0;
            for (int i = 0; i < blk.nCode; i++)
            {
                Instr x = blk.code[i];
                if (x.op == Op.LOAD_LOCAL && (x.removed || x.kind == Kind.TOP))
                {
                    x.removed = true;
                    continue;
                }
                blk.code[k] = x;
                k += 1;
            }
            blk.nCode = k;
        }
    }
}
