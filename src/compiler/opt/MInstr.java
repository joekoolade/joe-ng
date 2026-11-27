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
 * A machine instruction: a TARGET opcode (zero or more; its meaning is the target's) or one of the generic pseudo
 * opcodes below, with everything the allocator needs stated as data -- the virtual registers it defines and uses,
 * a use tied to its first def (two-address forms: x86), registers an operand must be in, and the registers it
 * destroys. The allocator reads only these, never the opcode, which is what keeps it free of any one target.
 *
 * <p>Uses are read before defs are written, so a def may take the register of a use that dies here.
 */
public final class MInstr
{
    /** defs[0] = uses[0]. */
    public static final int MOVE = -1;
    /** defs[0] = uses[k] when control arrives from the block's k-th predecessor. Removed by {@link PhiElim}. */
    public static final int PHI = -2;
    /** Store uses[0] to spill slot {@link #aux}. Inserted by the allocator. */
    public static final int SPILL = -3;
    /** defs[0] = spill slot {@link #aux}. Inserted by the allocator. */
    public static final int RELOAD = -4;
    /** Control continues at targets[0]. */
    public static final int JUMP = -5;

    private static final int[] NONE = new int[0];

    public final int op;
    public int[] defs;
    public int[] uses;
    /** Index into {@link #uses} of the use that must be in the same register as defs[0], or -1. */
    public int tied = -1;
    /** Per def / per use: the register it must be in, or -1; null when unconstrained. */
    public int[] fixedDefs;
    public int[] fixedUses;
    /** Per register class: the registers this instruction destroys (a call: the caller-saved set). */
    public long[] clobbers;
    /** A control transfer: ends its block. */
    public boolean term;
    /** Where control can go next (a terminator), in the target's own order. */
    public MBlock[] targets;
    public long imm;
    public int aux;
    /** A target's case keys (a switch). */
    public int[] keys;
    /** The bytecode offset this came from, or -1 (for the bci -> machine-code map). */
    public int bci = -1;
    /** A magic intrinsic's instruction: never removed or moved (an MMIO load reads a device). */
    public boolean pinned;
    /** Linear position, set by {@link Liveness}: uses read at 4*index, clobbers at +1..+2, defs written at +3. */
    public int pos;

    public MInstr(int op, int[] defs, int[] uses)
    {
        this.op = op;
        this.defs = defs == null ? NONE : defs;
        this.uses = uses == null ? NONE : uses;
    }

    public static MInstr move(int dst, int src)
    {
        return new MInstr(MOVE, new int[] { dst }, new int[] { src });
    }

    public static MInstr jump(MBlock to)
    {
        MInstr j = new MInstr(JUMP, null, null);
        j.term = true;
        j.targets = new MBlock[] { to };
        return j;
    }

    public int fixedDef(int i)
    {
        return fixedDefs == null ? -1 : fixedDefs[i];
    }

    public int fixedUse(int i)
    {
        return fixedUses == null ? -1 : fixedUses[i];
    }

    public long clobbers(int cls)
    {
        return clobbers == null ? 0L : clobbers[cls];
    }

    public boolean uses(int v)
    {
        for (int i = 0; i < uses.length; i++)
        {
            if (uses[i] == v)
            {
                return true;
            }
        }
        return false;
    }

    public boolean defines(int v)
    {
        for (int i = 0; i < defs.length; i++)
        {
            if (defs[i] == v)
            {
                return true;
            }
        }
        return false;
    }
}
