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
 * One method's IR: its blocks in reverse postorder (entry first, unreachable blocks dropped) and what the method
 * is -- the constant pool it reads, its descriptor and its exception table, which the back end turns into
 * machine-PC handler ranges in the same table order.
 */
public final class Graph
{
    public final byte[] cls;
    public final int[] cpOff;
    public final int[] cpTag;
    public final int descOff;
    public final boolean isStatic;
    public final boolean isSynchronized;
    public final int maxLocals;
    public final int returnKind;
    public final int[] exStart;
    public final int[] exEnd;
    public final int[] exHandler;
    public final int[] exCatch;
    public final int exCount;

    /** The method's bytecode. */
    public byte[] code;
    public Block[] blocks = new Block[0];
    public int nBlocks;
    /** Whether bytecode locals are mirrored in home slots (the method has a reachable handler). */
    public boolean homes;
    private int nextId;

    Graph(byte[] cls, int[] cpOff, int[] cpTag, int descOff, boolean isStatic, boolean isSynchronized,
          int maxLocals, int[] exStart, int[] exEnd, int[] exHandler, int[] exCatch, int exCount)
    {
        this.cls = cls;
        this.cpOff = cpOff;
        this.cpTag = cpTag;
        this.descOff = descOff;
        this.isStatic = isStatic;
        this.isSynchronized = isSynchronized;
        this.maxLocals = maxLocals;
        this.returnKind = Kind.returnOf(cls, descOff);
        this.exStart = exStart;
        this.exEnd = exEnd;
        this.exHandler = exHandler;
        this.exCatch = exCatch;
        this.exCount = exCount;
    }

    public Block entry()
    {
        return blocks[0];
    }

    Instr make(int op, int kind, Instr[] in, int bci)
    {
        Instr x = new Instr(nextId, op, kind, in, bci);
        nextId += 1;
        return x;
    }

    public int instrCount()
    {
        return nextId;
    }

    /** A textual dump (host only), for a failure report. */
    public String dump()
    {
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < nBlocks; b++)
        {
            Block blk = blocks[b];
            sb.append("B").append(blk.id).append(" @").append(blk.startBci).append(" preds");
            for (int i = 0; i < blk.nPreds; i++)
            {
                sb.append(" B").append(blk.preds[i].id);
            }
            if (blk.nHandlers > 0)
            {
                sb.append(" handlers");
                for (int i = 0; i < blk.nHandlers; i++)
                {
                    sb.append(" B").append(blk.handlers[i].id);
                }
            }
            sb.append('\n');
            for (int i = 0; i < blk.nPhis; i++)
            {
                appendInstr(sb, blk.phis[i]);
            }
            for (int i = 0; i < blk.nCode; i++)
            {
                appendInstr(sb, blk.code[i]);
            }
        }
        return sb.toString();
    }

    private static void appendInstr(StringBuilder sb, Instr x)
    {
        sb.append("  ").append(x.describe());
        if (x.targets != null)
        {
            sb.append(" ->");
            for (int s = 0; s < x.targets.length; s++)
            {
                sb.append(" B").append(x.targets[s].id);
            }
        }
        sb.append('\n');
    }
}
