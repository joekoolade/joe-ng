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
 * AArch64, the first target. The registers the allocator may use are the ones Baseline code already treats as
 * free across a bytecode: x0..x15 (arguments, results, temporaries; every one destroyed by a call). Never
 * allocated: x16/x17 (IP0/IP1, the scratch veneers and helper sequences use), x18 (platform), x19..x28 (the
 * image entry's locals -- no other method may touch them, PLAN.md frames/GC S1; callee-saved allocation is a
 * later phase), x29 (frame pointer), x30 (link register), sp. Floating point: v0..v7 and v16..v31, which a call
 * destroys; v8..v15 are callee-saved in their low halves and stay out until callee-saved allocation exists.
 */
public final class A64Target extends OptTarget implements ReloadReuse.Overwrites
{
    private static final RegFile REGS = build();

    /** The write barrier consumes its two operand registers (they are copies, A64Select.barrier). */
    public boolean overwritesUses(MInstr x)
    {
        return x.op == A64Select.BARRIER;
    }

    public boolean pure(MInstr x)
    {
        return A64Select.pure(x);
    }

    private static RegFile build()
    {
        String[] x = new String[32];
        String[] v = new String[32];
        for (int i = 0; i < 31; i++)
        {
            x[i] = "x" + i;
        }
        x[31] = "sp";
        for (int i = 0; i < 32; i++)
        {
            v[i] = "v" + i;
        }
        long intCallerSaved = RegFile.range(0, 18) | RegFile.mask(30);
        long fpCallerSaved = RegFile.range(0, 7) | RegFile.range(16, 31);
        return new RegFile(x, RegFile.range(0, 15), intCallerSaved, v, fpCallerSaved, fpCallerSaved);
    }

    public String name()
    {
        return "aarch64";
    }

    public RegFile regs()
    {
        return REGS;
    }

    public int wordBytes()
    {
        return 8;
    }

    public boolean returnAddressOnStack()
    {
        return false;
    }

    public int stackAlign()
    {
        return 16;
    }
}
