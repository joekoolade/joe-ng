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
 * A target's registers as the allocator sees them (PLAN.md "Targets"): two classes, integer and floating point,
 * each at most 64 registers numbered from 0 in the target's own numbering, with which of them the allocator may
 * use and which a call destroys. Everything else about a register -- what an instruction requires of it -- is on the
 * machine instruction ({@link MInstr}), so the allocator never names a register of any particular target.
 */
public final class RegFile
{
    public static final int INT = 0;
    public static final int FP = 1;
    public static final int CLASSES = 2;

    private final String[][] names;
    private final long[] allocatable;
    private final long[] callerSaved;

    public RegFile(String[] intNames, long intAllocatable, long intCallerSaved, String[] fpNames,
                   long fpAllocatable, long fpCallerSaved)
    {
        names = new String[][] { intNames, fpNames };
        allocatable = new long[] { intAllocatable, fpAllocatable };
        callerSaved = new long[] { intCallerSaved, fpCallerSaved };
    }

    public int count(int cls)
    {
        return names[cls].length;
    }

    /** Bit p set: the allocator may put a value in register p. */
    public long allocatable(int cls)
    {
        return allocatable[cls];
    }

    /** Bit p set: a call destroys register p. */
    /** The allocatable registers a call preserves: a callee that uses one saves and restores it. */
    public long calleeSaved(int cls)
    {
        return allocatable[cls] & ~callerSaved[cls];
    }

    public long callerSaved(int cls)
    {
        return callerSaved[cls];
    }

    public String name(int cls, int p)
    {
        return names[cls][p];
    }

    /** A mask of the registers named (host convenience for building register files). */
    public static long mask(int... regs)
    {
        long m = 0;
        for (int r : regs)
        {
            m |= 1L << r;
        }
        return m;
    }

    /** A mask of registers lo..hi inclusive. */
    public static long range(int lo, int hi)
    {
        long m = 0;
        for (int r = lo; r <= hi; r++)
        {
            m |= 1L << r;
        }
        return m;
    }
}
