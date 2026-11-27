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
 * Dead-code elimination on the machine IR (O2-1), after phi elimination and before the allocator: an instruction
 * the target calls {@link OptTarget#pure pure} whose defs nobody uses is dropped, and so, in turn, is whatever fed
 * only it. It runs on the machine IR rather than the SSA graph because the graph's framestates hold every value a
 * deopt would need (O4); what is dead here is dead only to the code, and that is the only place it is removed.
 * <p>Use counts, then a worklist: dropping an instruction lowers its operands' counts, which may kill their
 * definitions. A vreg has one def until phi elimination, and after it the phi copies are MOVEs, each counted.
 */
public final class DeadCode
{
    private DeadCode()
    {
    }

    /** Drops the dead pure instructions of {@code f}; answers how many. */
    public static int run(MFunc f, OptTarget t)
    {
        int[] uses = new int[f.nV];
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            for (int i = 0; i < blk.n; i++)
            {
                MInstr x = blk.code[i];
                for (int u : x.uses)
                {
                    uses[u] += 1;
                }
            }
        }
        int removed = 0;
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int b = 0; b < f.nBlocks; b++)
            {
                MBlock blk = f.blocks[b];
                int w = 0;
                for (int i = 0; i < blk.n; i++)
                {
                    MInstr x = blk.code[i];
                    if (!x.term && t.pure(x) && dead(x, uses))
                    {
                        for (int u : x.uses)
                        {
                            uses[u] -= 1;
                        }
                        removed += 1;
                        changed = true;
                        continue;
                    }
                    blk.code[w] = x;
                    w += 1;
                }
                for (int i = w; i < blk.n; i++)
                {
                    blk.code[i] = null;
                }
                blk.n = w;
            }
        }
        return removed;
    }

    private static boolean dead(MInstr x, int[] uses)
    {
        for (int d : x.defs)
        {
            if (uses[d] != 0)
            {
                return false;
            }
        }
        return true;
    }
}
