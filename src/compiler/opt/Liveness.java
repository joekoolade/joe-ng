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
 * Linear positions, live-in sets by backward dataflow, and one live interval per virtual register: the hull
 * {@code [from, to)} of everywhere it is live (O1 keeps no holes -- conservative, never wrong).
 *
 * <p>Instruction i of the linear order sits at {@code 4i}: its uses are read at 4i (a use extends an interval to
 * 4i+1), its clobbers occupy [4i+1, 4i+3), its defs are written at 4i+3. So a value that dies at an instruction can
 * share a register with what it defines or destroys, and only a value live ACROSS it meets a clobber.
 */
public final class Liveness
{
    public final int[] from;
    public final int[] to;

    public Liveness(MFunc f)
    {
        int nv = f.nV;
        from = new int[nv];
        to = new int[nv];
        for (int v = 0; v < nv; v++)
        {
            from[v] = Integer.MAX_VALUE;
            to[v] = Integer.MIN_VALUE;
        }
        int pos = 0;
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            blk.from = pos;
            for (int i = 0; i < blk.n; i++)
            {
                blk.code[i].pos = pos;
                pos += 4;
            }
            blk.to = pos;
        }
        int words = (nv + 63) / 64;
        long[][] liveIn = new long[f.nBlocks][words];
        long[][] liveOut = new long[f.nBlocks][words];
        long[][] use = new long[f.nBlocks][words];
        long[][] def = new long[f.nBlocks][words];
        int[] index = new int[maxId(f) + 1];
        for (int b = 0; b < f.nBlocks; b++)
        {
            index[f.blocks[b].id] = b;
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                for (int k = 0; k < x.uses.length; k++)
                {
                    int v = x.uses[k];
                    if (!get(def[b], v))
                    {
                        set(use[b], v);
                    }
                }
                for (int k = 0; k < x.defs.length; k++)
                {
                    set(def[b], x.defs[k]);
                }
            }
        }
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int b = f.nBlocks - 1; b >= 0; b--)
            {
                MBlock blk = f.blocks[b];
                long[] out = liveOut[b];
                for (int s = 0; s < blk.nSuccs; s++)
                {
                    long[] in = liveIn[index[blk.succs[s].id]];
                    for (int w = 0; w < words; w++)
                    {
                        out[w] |= in[w];
                    }
                }
                for (int w = 0; w < words; w++)
                {
                    long in = use[b][w] | (out[w] & ~def[b][w]);
                    if (in != liveIn[b][w])
                    {
                        liveIn[b][w] = in;
                        changed = true;
                    }
                }
            }
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int v = 0; v < nv; v++)
            {
                if (get(liveIn[b], v))
                {
                    extend(v, blk.from, blk.from + 1);
                }
                if (get(liveOut[b], v))
                {
                    extend(v, blk.to - 1, blk.to);
                }
            }
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                for (int k = 0; k < x.uses.length; k++)
                {
                    extend(x.uses[k], x.pos, x.pos + 1);
                }
                for (int k = 0; k < x.defs.length; k++)
                {
                    extend(x.defs[k], x.pos + 3, x.pos + 4);
                }
            }
        }
    }

    private void extend(int v, int a, int b)
    {
        if (a < from[v])
        {
            from[v] = a;
        }
        if (b > to[v])
        {
            to[v] = b;
        }
    }

    /** Whether register v appears anywhere. */
    public boolean present(int v)
    {
        return from[v] != Integer.MAX_VALUE;
    }

    private static int maxId(MFunc f)
    {
        int m = 0;
        for (int b = 0; b < f.nBlocks; b++)
        {
            if (f.blocks[b].id > m)
            {
                m = f.blocks[b].id;
            }
        }
        return m;
    }

    static boolean get(long[] s, int v)
    {
        return (s[v >> 6] & (1L << (v & 63))) != 0;
    }

    static void set(long[] s, int v)
    {
        s[v >> 6] |= 1L << (v & 63);
    }
}
