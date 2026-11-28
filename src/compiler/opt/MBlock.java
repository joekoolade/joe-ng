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

/** A machine block: phis first (until {@link PhiElim}), then instructions, the last a terminator. */
public final class MBlock
{
    public final int id;
    public MInstr[] code = new MInstr[8];
    public int n;
    public MBlock[] preds = new MBlock[2];
    public int nPreds;
    public MBlock[] succs = new MBlock[2];
    public int nSuccs;
    /** Linear positions [from, to) after {@link Liveness}. */
    public int from;
    public int to;
    /** Entered by the unwinder (a handler's first block): nothing but the exception, in its pinned register. */
    public boolean handler;
    /** Per exception-table entry: whether its try range covers the bytecode this block's code comes from (null:
     *  none) -- the machine-PC handler ranges are the runs of covered blocks in layout order. */
    public boolean[] cover;

    public MBlock(int id)
    {
        this.id = id;
    }

    public void add(MInstr x)
    {
        insert(n, x);
    }

    public void insert(int at, MInstr x)
    {
        if (n == code.length)
        {
            MInstr[] bigger = new MInstr[code.length * 2];
            for (int i = 0; i < n; i++)
            {
                bigger[i] = code[i];
            }
            code = bigger;
        }
        for (int i = n; i > at; i--)
        {
            code[i] = code[i - 1];
        }
        code[at] = x;
        n += 1;
    }

    public void remove(int at)
    {
        for (int i = at; i + 1 < n; i++)
        {
            code[i] = code[i + 1];
        }
        n -= 1;
        code[n] = null;
    }

    public MInstr last()
    {
        return n == 0 ? null : code[n - 1];
    }

    /** Add the edge this -> s (once). */
    public void link(MBlock s)
    {
        for (int i = 0; i < nSuccs; i++)
        {
            if (succs[i] == s)
            {
                return;
            }
        }
        succs = grow(succs, nSuccs);
        succs[nSuccs] = s;
        nSuccs += 1;
        s.preds = grow(s.preds, s.nPreds);
        s.preds[s.nPreds] = this;
        s.nPreds += 1;
    }

    public int predIndex(MBlock p)
    {
        for (int i = 0; i < nPreds; i++)
        {
            if (preds[i] == p)
            {
                return i;
            }
        }
        return -1;
    }

    static MBlock[] grow(MBlock[] a, int n)
    {
        if (n < a.length)
        {
            return a;
        }
        MBlock[] b = new MBlock[a.length * 2 + 1];
        for (int i = 0; i < n; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
