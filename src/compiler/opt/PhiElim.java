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
 * Out of SSA: each block's phis become one parallel copy at the end of every predecessor, sequentialized into moves
 * (a cycle broken through a new temporary). A copy cannot go before a terminator that branches elsewhere or reads a
 * register, so such an edge is split first and the copies go in the new block.
 */
public final class PhiElim
{
    private PhiElim()
    {
    }

    public static void run(MFunc f)
    {
        int n = f.nBlocks;
        MBlock[] snapshot = new MBlock[n];
        for (int i = 0; i < n; i++)
        {
            snapshot[i] = f.blocks[i];
        }
        for (int i = 0; i < n; i++)
        {
            MBlock b = snapshot[i];
            int nPhi = 0;
            while (nPhi < b.n && b.code[nPhi].op == MInstr.PHI)
            {
                nPhi += 1;
            }
            if (nPhi == 0)
            {
                continue;
            }
            for (int k = 0; k < b.nPreds; k++)
            {
                MBlock p = b.preds[k];
                MBlock e = p;
                MInstr t = p.last();
                if (p.nSuccs > 1 || t.uses.length > 0 || t.op != MInstr.JUMP)
                {
                    e = split(f, p, b, k);
                }
                int[] dst = new int[nPhi];
                int[] src = new int[nPhi];
                for (int j = 0; j < nPhi; j++)
                {
                    dst[j] = b.code[j].defs[0];
                    src[j] = b.code[j].uses[k];
                }
                sequentialize(f, e, dst, src, nPhi);
            }
            for (int j = nPhi; j < b.n; j++)
            {
                b.code[j - nPhi] = b.code[j];
            }
            b.n -= nPhi;
        }
    }

    /** Put a new block on the edge p -> b (b's k-th predecessor). */
    private static MBlock split(MFunc f, MBlock p, MBlock b, int k)
    {
        MBlock e = f.newBlockAfter(p);
        e.add(MInstr.jump(b));
        MInstr t = p.last();
        for (int i = 0; i < t.targets.length; i++)
        {
            if (t.targets[i] == b)
            {
                t.targets[i] = e;
            }
        }
        for (int i = 0; i < p.nSuccs; i++)
        {
            if (p.succs[i] == b)
            {
                p.succs[i] = e;
            }
        }
        e.preds[0] = p;
        e.nPreds = 1;
        e.succs[0] = b;
        e.nSuccs = 1;
        b.preds[k] = e;
        return e;
    }

    /** Emit the parallel copy dst[i] = src[i] as moves before {@code e}'s terminator. */
    private static void sequentialize(MFunc f, MBlock e, int[] dst, int[] src, int n)
    {
        boolean[] done = new boolean[n];
        int left = 0;
        for (int i = 0; i < n; i++)
        {
            if (dst[i] == src[i])
            {
                done[i] = true;
            }
            else
            {
                left += 1;
            }
        }
        while (left > 0)
        {
            boolean progress = false;
            for (int i = 0; i < n; i++)
            {
                if (done[i] || readByPending(dst[i], src, done, n, i))
                {
                    continue;
                }
                e.insert(e.n - 1, MInstr.move(dst[i], src[i]));
                done[i] = true;
                left -= 1;
                progress = true;
            }
            if (!progress)
            {
                // every pending destination is still to be read: a cycle. Park one destination's value.
                int i = 0;
                while (done[i])
                {
                    i += 1;
                }
                int d = dst[i];
                int tmp = f.newVreg(f.vclass[d]);
                e.insert(e.n - 1, MInstr.move(tmp, d));
                for (int j = 0; j < n; j++)
                {
                    if (!done[j] && src[j] == d)
                    {
                        src[j] = tmp;
                    }
                }
            }
        }
    }

    private static boolean readByPending(int r, int[] src, boolean[] done, int n, int self)
    {
        for (int j = 0; j < n; j++)
        {
            if (j != self && !done[j] && src[j] == r)
            {
                return true;
            }
        }
        return false;
    }
}
