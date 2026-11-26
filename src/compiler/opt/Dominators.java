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
 * Immediate dominators over normal and exception edges, by the iterative algorithm of Cooper, Harvey and Kennedy
 * ("A Simple, Fast Dominance Algorithm", 2001): intersect the dominators of each block's processed predecessors in
 * reverse postorder until nothing changes.
 */
public final class Dominators
{
    private Dominators()
    {
    }

    public static void compute(Graph g)
    {
        Block entry = g.blocks[0];
        for (int i = 0; i < g.nBlocks; i++)
        {
            g.blocks[i].idom = null;
        }
        entry.idom = entry;
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int i = 1; i < g.nBlocks; i++)
            {
                Block b = g.blocks[i];
                Block d = null;
                for (int p = 0; p < b.nPreds + b.nExcPreds; p++)
                {
                    Block q = p < b.nPreds ? b.preds[p] : b.excPreds[p - b.nPreds];
                    if (q.idom == null)
                    {
                        continue;
                    }
                    d = d == null ? q : intersect(q, d);
                }
                if (d != b.idom)
                {
                    b.idom = d;
                    changed = true;
                }
            }
        }
        entry.idom = null;
    }

    private static Block intersect(Block a, Block b)
    {
        while (a != b)
        {
            while (a.rpo > b.rpo)
            {
                a = a.idom;
            }
            while (b.rpo > a.rpo)
            {
                b = b.idom;
            }
        }
        return a;
    }

    /** Whether {@code a} dominates {@code b} (every block dominates itself). */
    public static boolean dominates(Block a, Block b)
    {
        Block x = b;
        while (x != null)
        {
            if (x == a)
            {
                return true;
            }
            x = x.idom;
        }
        return false;
    }
}
