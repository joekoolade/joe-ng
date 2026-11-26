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
 * A basic block: phis, then a straight line of instructions ending in exactly one terminator. Normal successors
 * are the terminator's targets; exception successors are the handlers whose try range covers the block's
 * bytecode (no SSA value flows along them -- a handler reads the locals' home slots).
 */
public final class Block
{
    public final int id;
    /** First bytecode offset of the block; the block covers [startBci, endBci). */
    public final int startBci;
    public int endBci;
    public Instr[] phis = new Instr[2];
    public int nPhis;
    public Instr[] code = new Instr[8];
    public int nCode;
    public Block[] preds = new Block[2];
    public int nPreds;
    public Block[] succs = new Block[2];
    public int nSuccs;
    public Block[] handlers = new Block[0];
    public int nHandlers;
    /** Blocks that reach this one only by an exception edge (handler entries): their predecessor count is 0. */
    public Block[] excPreds = new Block[0];
    public int nExcPreds;
    public boolean isHandler;
    /** Reverse-postorder number, -1 when unreachable. */
    public int rpo = -1;
    /** Immediate dominator (null for the entry). */
    public Block idom;
    /** Loop header: the target of a back edge. */
    public boolean loopHeader;
    /** Operand-stack depth (JVM slots) entering the block. */
    public int entryDepth;

    Block(int id, int startBci)
    {
        this.id = id;
        this.startBci = startBci;
    }

    public Instr terminator()
    {
        if (nCode == 0)
        {
            return null;
        }
        Instr t = code[nCode - 1];
        if (!Op.isTerminator(t.op))
        {
            return null;
        }
        return t;
    }

    void append(Instr x)
    {
        code = grow(code, nCode);
        code[nCode] = x;
        nCode += 1;
        x.block = this;
    }

    void addPhi(Instr x)
    {
        phis = grow(phis, nPhis);
        phis[nPhis] = x;
        nPhis += 1;
        x.block = this;
    }

    /** Add a normal edge this -> s, once (a switch may name one target twice). */
    void addSucc(Block s)
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

    void addHandler(Block h)
    {
        for (int i = 0; i < nHandlers; i++)
        {
            if (handlers[i] == h)
            {
                return;
            }
        }
        handlers = grow(handlers, nHandlers);
        handlers[nHandlers] = h;
        nHandlers += 1;
        h.excPreds = grow(h.excPreds, h.nExcPreds);
        h.excPreds[h.nExcPreds] = this;
        h.nExcPreds += 1;
    }

    public int predIndex(Block p)
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

    static Instr[] grow(Instr[] a, int n)
    {
        if (n < a.length)
        {
            return a;
        }
        Instr[] b = new Instr[a.length * 2 + 1];
        for (int i = 0; i < n; i++)
        {
            b[i] = a[i];
        }
        return b;
    }

    static Block[] grow(Block[] a, int n)
    {
        if (n < a.length)
        {
            return a;
        }
        Block[] b = new Block[a.length * 2 + 1];
        for (int i = 0; i < n; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
