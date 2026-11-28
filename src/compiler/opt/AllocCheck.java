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
 * Checks an allocation by abstract interpretation, independently of how it was made: track which value each
 * physical register and spill slot holds (by the virtual register it stands for), merge at joins, and require
 * every read to find its value where the allocation says it is. Also checks pins, tied operands, classes and that
 * only allocatable or pinned registers are used. Host tooling: it reports with string concatenation.
 */
public final class AllocCheck
{
    private static final int UNKNOWN = -1;
    private static final int UNSET = -2;

    private final MFunc f;
    private final RegFile rf;
    private String error;

    private AllocCheck(MFunc f, RegFile rf)
    {
        this.f = f;
        this.rf = rf;
    }

    /** Null if the allocation in {@code f} is sound, else the first problem. */
    public static String check(MFunc f, RegFile rf)
    {
        AllocCheck c = new AllocCheck(f, rf);
        c.run();
        return c.error;
    }

    private void bad(String s)
    {
        if (error == null)
        {
            error = s;
        }
    }

    private int width()
    {
        return RegFile.CLASSES * 64 + f.nSlots;
    }

    private void run()
    {
        if (!operands())
        {
            return;
        }
        int w = width();
        int[][] in = new int[f.nBlocks][];
        int[] index = new int[1 + maxId()];
        for (int b = 0; b < f.nBlocks; b++)
        {
            index[f.blocks[b].id] = b;
            in[b] = new int[w];
            for (int i = 0; i < w; i++)
            {
                in[b][i] = b == 0 || f.blocks[b].handler ? UNKNOWN : UNSET;   // the unwinder enters a handler
            }
        }
        boolean changed = true;
        while (changed && error == null)
        {
            changed = false;
            for (int b = 0; b < f.nBlocks && error == null; b++)
            {
                if (in[b][0] == UNSET && b != 0)
                {
                    continue;                                   // not reached yet
                }
                int[] s = copy(in[b]);
                MBlock blk = f.blocks[b];
                for (int i = 0; i < blk.n && error == null; i++)
                {
                    step(blk, blk.code[i], s);
                }
                for (int k = 0; k < blk.nSuccs; k++)
                {
                    int[] t = in[index[blk.succs[k].id]];
                    for (int i = 0; i < w; i++)
                    {
                        int m = t[i] == UNSET ? s[i] : (t[i] == s[i] ? t[i] : UNKNOWN);
                        if (m != t[i])
                        {
                            t[i] = m;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /** Per instruction, independent of flow: every operand allocated, in class, legal, pins and ties held. */
    private boolean operands()
    {
        if (f.loc == null)
        {
            bad("no allocation");
            return false;
        }
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                if (x.op == MInstr.PHI)
                {
                    bad("phi left in B" + blk.id);
                    return false;
                }
                for (int k = 0; k < x.uses.length; k++)
                {
                    if (!legal(x.uses[k], x.fixedUse(k), blk))
                    {
                        return false;
                    }
                }
                for (int k = 0; k < x.defs.length; k++)
                {
                    if (!legal(x.defs[k], x.fixedDef(k), blk))
                    {
                        return false;
                    }
                }
                if (x.tied >= 0 && f.loc[x.defs[0]] != f.loc[x.uses[x.tied]])
                {
                    bad("B" + blk.id + " op " + x.op + " tied operand in another register");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean legal(int v, int pin, MBlock blk)
    {
        int p = f.loc[v];
        int cls = f.vclass[v];
        if (p < 0 || p >= rf.count(cls))
        {
            bad("v" + v + " in B" + blk.id + " has no register");
            return false;
        }
        if (pin >= 0 && p != pin)
        {
            bad("v" + v + " in B" + blk.id + " must be in " + rf.name(cls, pin) + ", is in " + rf.name(cls, p));
            return false;
        }
        if (f.fixed[v] < 0 && (rf.allocatable(cls) & (1L << p)) == 0)
        {
            bad("v" + v + " in B" + blk.id + " given unallocatable " + rf.name(cls, p));
            return false;
        }
        if ((rf.calleeSaved(cls) & (1L << p)) != 0 && (!f.noAddress[v] || !f.calleeSavedOk))
        {
            // O2-3: the collector never reads a register (to update a reference or pin a raw address), and a leaf
            // has no save area
            bad("v" + v + " in B" + blk.id + (!f.noAddress[v] ? " (may hold an address)" : " (a leaf)")
                + " in callee-saved " + rf.name(cls, p));
            return false;
        }
        return true;
    }

    private void step(MBlock blk, MInstr x, int[] s)
    {
        for (int k = 0; k < x.uses.length; k++)
        {
            int v = x.uses[k];
            int at = f.vclass[v] * 64 + f.loc[v];
            if (s[at] != f.origin[v])
            {
                bad("B" + blk.id + " op " + x.op + " reads v" + v + " from " + rf.name(f.vclass[v], f.loc[v])
                    + " which holds " + describe(s[at]));
                return;
            }
        }
        if (x.op == MInstr.RELOAD)
        {
            int v = x.defs[0];
            int at = RegFile.CLASSES * 64 + x.aux;
            if (s[at] != f.origin[v])
            {
                bad("B" + blk.id + " reload of v" + v + " from slot " + x.aux + " which holds " + describe(s[at]));
                return;
            }
        }
        for (int cls = 0; cls < RegFile.CLASSES; cls++)
        {
            long c = x.clobbers(cls);
            for (int p = 0; c != 0 && p < 64; p++)
            {
                if ((c & (1L << p)) != 0)
                {
                    s[cls * 64 + p] = UNKNOWN;
                }
            }
        }
        if (x.op == MInstr.SPILL)
        {
            s[RegFile.CLASSES * 64 + x.aux] = f.origin[x.uses[0]];
        }
        if (x.op == A64Select.HSTORE)
        {
            s[RegFile.CLASSES * 64 + x.aux] = f.origin[x.uses[0]];   // a home store writes a frame slot too (O2-2:
        }                                                            // a home can be a spilled value's slot)
        for (int k = 0; k < x.defs.length; k++)
        {
            int v = x.defs[k];
            int o = f.origin[v];
            boolean copy = x.op == MInstr.RELOAD || (x.op == MInstr.MOVE && f.origin[x.uses[0]] == o);
            for (int i = 0; i < s.length && !copy; i++)
            {
                if (s[i] == o)
                {
                    s[i] = UNKNOWN;                            // a NEW value of o: older copies are stale now (a
                                                               // reload, or a move of o itself, copies the value)
                }
            }
            s[f.vclass[v] * 64 + f.loc[v]] = o;
        }
    }

    private static String describe(int v)
    {
        if (v == UNKNOWN)
        {
            return "nothing known";
        }
        if (v == UNSET)
        {
            return "nothing";
        }
        return "v" + v;
    }

    private int maxId()
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

    private static int[] copy(int[] a)
    {
        int[] b = new int[a.length];
        for (int i = 0; i < a.length; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
