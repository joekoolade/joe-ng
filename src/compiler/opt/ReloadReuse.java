/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-27
 */
package compiler.opt;

/**
 * Redundant reloads, after allocation (O2-1). The allocator spills everywhere: a spilled register is stored at each
 * write and reloaded at EVERY read, even when a register still holds the slot's value from the store or the reload
 * just before. Within a block this pass tracks which slot's value each register holds: a SPILL makes its source
 * register hold the slot, a RELOAD its destination. A RELOAD of a slot some register already holds becomes a MOVE
 * from it, or vanishes when that is its own destination.
 * <p>Knowledge is dropped at a block's start (no flow analysis), at every def of a register, at every register an
 * instruction CLOBBERS -- each call and yieldpoint, so no register's copy of a reference outlives a point where the
 * collector may move it; the slot itself is what the GC map updates -- and at {@code overwritten} registers, those
 * an instruction writes outside its defs (the write barrier consumes its operands). A second SPILL to a slot drops
 * every other register's claim on it.
 */
public final class ReloadReuse
{
    private ReloadReuse()
    {
    }

    /** What a target says an instruction writes besides its defs and clobbers. */
    public interface Overwrites
    {
        boolean overwritesUses(MInstr x);
    }

    /** Rewrites {@code f}'s redundant reloads (registers from {@link MFunc#loc}); answers how many. */
    public static int run(MFunc f, RegFile rf, Overwrites ow)
    {
        int nRegs = rf.count(RegFile.INT);
        int[] holds = new int[nRegs];                        // the slot register p holds, or -1
        int[] holder = new int[nRegs];                       // a vreg located in p (for a MOVE's use)
        int changed = 0;
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int p = 0; p < nRegs; p++)
            {
                holds[p] = -1;
            }
            int w = 0;
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                if (x.op == MInstr.RELOAD && intReg(f, x.defs[0]))
                {
                    int d = f.loc[x.defs[0]];
                    int src = -1;
                    for (int p = 0; p < nRegs; p++)
                    {
                        if (holds[p] == x.aux)
                        {
                            src = p == d ? p : (src < 0 ? p : src);
                        }
                    }
                    if (src == d)
                    {
                        changed += 1;                        // the value is already in its register
                        continue;
                    }
                    if (src >= 0)
                    {
                        MInstr m = MInstr.move(x.defs[0], holder[src]);
                        m.bci = x.bci;
                        m.pos = x.pos;
                        blk.code[w] = m;
                        w += 1;
                        changed += 1;
                        forget(holds, d);
                        holds[d] = x.aux;
                        holder[d] = x.defs[0];
                        continue;
                    }
                    blk.code[w] = x;
                    w += 1;
                    forget(holds, d);
                    holds[d] = x.aux;
                    holder[d] = x.defs[0];
                    continue;
                }
                blk.code[w] = x;
                w += 1;
                if (x.op == MInstr.SPILL && intReg(f, x.uses[0]))
                {
                    for (int p = 0; p < nRegs; p++)
                    {
                        if (holds[p] == x.aux)
                        {
                            holds[p] = -1;                   // the slot has a new value
                        }
                    }
                    int s = f.loc[x.uses[0]];
                    holds[s] = x.aux;
                    holder[s] = x.uses[0];
                    continue;
                }
                for (int d : x.defs)
                {
                    if (intReg(f, d))
                    {
                        forget(holds, f.loc[d]);
                    }
                }
                long c = x.clobbers(RegFile.INT);
                for (int p = 0; p < nRegs; p++)
                {
                    if ((c & (1L << p)) != 0)
                    {
                        forget(holds, p);
                    }
                }
                if (ow.overwritesUses(x))
                {
                    for (int u : x.uses)
                    {
                        if (intReg(f, u))
                        {
                            forget(holds, f.loc[u]);
                        }
                    }
                }
                if (x.op == A64Select.HSTORE)
                {
                    for (int p = 0; p < nRegs; p++)
                    {
                        if (holds[p] == x.aux)
                        {
                            holds[p] = -1;                   // a home's new value (homes and spill slots share
                        }                                    // the frame's numbering)
                    }
                }
            }
            for (int i = w; i < blk.n; i++)
            {
                blk.code[i] = null;
            }
            blk.n = w;
        }
        return changed;
    }

    private static boolean intReg(MFunc f, int v)
    {
        return f.vclass[v] == RegFile.INT && f.loc[v] >= 0;
    }

    private static void forget(int[] holds, int p)
    {
        if (p >= 0 && p < holds.length)
        {
            holds[p] = -1;
        }
    }
}
