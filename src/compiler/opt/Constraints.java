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
 * Turn operand constraints into copies, so the allocator sees only virtual registers, some of them pinned:
 * <ul>
 * <li>a use TIED to the first def (x86's two-address forms) becomes {@code d = u} before the instruction, which
 *     then reads and writes {@code d};</li>
 * <li>an operand FIXED to a register (an argument register, x86's rax/rdx for divide, cl for a shift) becomes a
 *     copy into, or out of, a new virtual register pinned to it -- live only across the copy and the
 *     instruction, so the pin costs the rest of the method nothing.</li>
 * </ul>
 * Run after {@link PhiElim} and before {@link LinearScan}.
 */
public final class Constraints
{
    private Constraints()
    {
    }

    public static void run(MFunc f)
    {
        for (int b = 0; b < f.nBlocks; b++)
        {
            MBlock blk = f.blocks[b];
            int i = 0;
            while (i < blk.n)
            {
                i = one(f, blk, i);
            }
        }
    }

    /** Rewrite the instruction at {@code i}; answer the index after it. */
    private static int one(MFunc f, MBlock blk, int i)
    {
        MInstr x = blk.code[i];
        if (x.tied >= 0)
        {
            int d = x.defs[0];
            int u = x.uses[x.tied];
            if (x.fixedUse(x.tied) >= 0 || x.fixedDef(0) >= 0)
            {
                throw new IllegalStateException("a tied operand may not also be fixed");
            }
            if (u != d)
            {
                for (int k = 0; k < x.uses.length; k++)
                {
                    if (k != x.tied && x.uses[k] == d)
                    {
                        int keep = f.newVreg(f.vclass[d]);         // d's old value, read after d is overwritten
                        f.copyGc(keep, d);
                        blk.insert(i, MInstr.move(keep, d));
                        i += 1;
                        x.uses[k] = keep;
                    }
                }
                blk.insert(i, MInstr.move(d, u));
                i += 1;
                x.uses[x.tied] = d;
            }
        }
        for (int k = 0; k < x.uses.length; k++)
        {
            int p = x.fixedUse(k);
            if (p >= 0 && f.fixed[x.uses[k]] != p)
            {
                int t = f.fixedVreg(f.vclass[x.uses[k]], p);
                f.copyGc(t, x.uses[k]);
                blk.insert(i, MInstr.move(t, x.uses[k]));
                i += 1;
                x.uses[k] = t;
            }
        }
        int after = i + 1;
        for (int k = 0; k < x.defs.length; k++)
        {
            int p = x.fixedDef(k);
            if (p >= 0 && f.fixed[x.defs[k]] != p)
            {
                if (x.term)
                {
                    throw new IllegalStateException("a terminator may not define a fixed register");
                }
                int t = f.fixedVreg(f.vclass[x.defs[k]], p);
                f.copyGc(t, x.defs[k]);
                blk.insert(after, MInstr.move(x.defs[k], t));
                after += 1;
                x.defs[k] = t;
            }
        }
        return after;
    }
}
