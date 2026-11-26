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

/** One SSA instruction: the value it defines (if its kind is not {@link Kind#V}) and what it does. */
public final class Instr
{
    public final int id;
    public final int op;
    public int kind;
    public Instr[] in;
    public long aux;
    public int aux2;
    /** The bytecode offset this instruction came from (-1 for a phi or a parameter). */
    public final int bci;
    public Block block;
    /** For an instruction that can throw or call: the bytecode state to resume at (deopt, O4). */
    public FrameState state;
    /** {@link Op#SWITCH}: the case keys. */
    public int[] keys;
    /** A terminator's targets in its own order ({@link Op#IF}: taken, fall-through; {@link Op#SWITCH}: default,
     *  then one per key; {@link Op#GOTO}: the target). The block's succs are the same blocks, each once. */
    public Block[] targets;
    /** {@link Op#LOAD_LOCAL}: the values stored to the home at the points the handler covers (kind inference and
     *  checking only -- not operands). */
    public Instr[] src;
    public int nSrc;
    /** Set when the instruction is replaced by another value (a trivial phi); uses are rewritten through it. */
    public Instr forward;
    public boolean removed;

    Instr(int id, int op, int kind, Instr[] in, int bci)
    {
        this.id = id;
        this.op = op;
        this.kind = kind;
        this.in = in;
        this.bci = bci;
    }

    /** Follow {@link #forward} to the value that stands for this one now. */
    public static Instr resolve(Instr v)
    {
        Instr x = v;
        while (x != null && x.forward != null)
        {
            x = x.forward;
        }
        return x;
    }

    void addSrc(Instr v)
    {
        if (src == null)
        {
            src = new Instr[4];
        }
        for (int i = 0; i < nSrc; i++)
        {
            if (src[i] == v)
            {
                return;
            }
        }
        if (nSrc == src.length)
        {
            Instr[] bigger = new Instr[src.length * 2];
            for (int i = 0; i < nSrc; i++)
            {
                bigger[i] = src[i];
            }
            src = bigger;
        }
        src[nSrc] = v;
        nSrc += 1;
    }

    /** "v12:I add v3 v7" -- for reports (host only: string concatenation). */
    public String describe()
    {
        StringBuilder sb = new StringBuilder();
        sb.append('v').append(id).append(':').append(Kind.name(kind)).append(' ').append(Op.name(op));
        if (op == Op.CONST || op == Op.PARAM || op == Op.LOAD_LOCAL || op == Op.STORE_LOCAL)
        {
            sb.append(' ').append(aux);
        }
        else if (aux != 0 || aux2 != 0)
        {
            sb.append(" #").append(aux).append('/').append(aux2);
        }
        for (int i = 0; i < in.length; i++)
        {
            Instr v = in[i];
            sb.append(' ');
            if (v == null)
            {
                sb.append("null!");
            }
            else
            {
                sb.append('v').append(v.id);
            }
        }
        if (bci >= 0)
        {
            sb.append(" @").append(bci);
        }
        return sb.toString();
    }
}
