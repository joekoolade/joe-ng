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

import compiler.Baseline;

/**
 * The control-flow graph of a method's bytecode, before any instruction exists: block leaders, normal and exception
 * edges, reverse postorder and loop headers. A block never straddles a try-range boundary, so the handlers covering
 * a block cover every instruction in it.
 *
 * <p>Block 0 is a synthetic entry (no bytecode) that holds the parameters and falls into the block at bci 0, so the
 * first bytecode block can be a loop header like any other.
 */
final class Cfg
{
    private final Graph g;
    private final byte[] code;
    /** pc -> the block starting there, or null. */
    Block[] blockAt;
    /** Blocks in creation order (block id = index): the entry, then one per leader in bytecode order. */
    Block[] all;
    int nAll;
    int reason;
    int reasonBci;

    Cfg(Graph g, byte[] code)
    {
        this.g = g;
        this.code = code;
    }

    /** Build blocks and edges; false (with {@link #reason}) when the bytecode is outside the model. */
    boolean build()
    {
        boolean[] leader = new boolean[code.length + 1];
        if (!findLeaders(leader))
        {
            return false;
        }
        blockAt = new Block[code.length];
        all = new Block[8];
        all[0] = new Block(0, -1);
        nAll = 1;
        for (int pc = 0; pc < code.length; pc++)
        {
            if (leader[pc])
            {
                Block b = new Block(nAll, pc);
                all = Block.grow(all, nAll);
                all[nAll] = b;
                nAll += 1;
                blockAt[pc] = b;
            }
        }
        for (int i = 1; i < nAll; i++)
        {
            Block b = all[i];
            b.endBci = i + 1 < nAll ? all[i + 1].startBci : code.length;
        }
        all[0].addSucc(blockAt[0]);
        for (int i = 1; i < nAll; i++)
        {
            if (!edges(all[i]))
            {
                return false;
            }
        }
        for (int e = 0; e < g.exCount; e++)
        {
            Block h = blockAt[g.exHandler[e]];
            for (int i = 1; i < nAll; i++)
            {
                Block b = all[i];
                if (b.startBci >= g.exStart[e] && b.endBci <= g.exEnd[e])
                {
                    b.addHandler(h);
                }
            }
        }
        order();
        for (int i = 0; i < g.nBlocks; i++)
        {
            Block b = g.blocks[i];
            if (b.nExcPreds > 0)
            {
                b.isHandler = true;
                if (b.nPreds > 0)
                {
                    return fail(SsaBuilder.R_HANDLER_SHAPE, b.startBci);
                }
            }
        }
        return true;
    }

    private boolean fail(int r, int bci)
    {
        reason = r;
        reasonBci = bci;
        return false;
    }

    private boolean findLeaders(boolean[] leader)
    {
        leader[0] = true;
        int pc = 0;
        while (pc < code.length)
        {
            int op = code[pc] & 0xFF;
            if (op == 0xA8 || op == 0xA9 || op == 0xC9)
            {
                return fail(SsaBuilder.R_JSR, pc);
            }
            if (op == 0xC4 && (code[pc + 1] & 0xFF) == 0xA9)
            {
                return fail(SsaBuilder.R_JSR, pc);
            }
            int len = Baseline.opLen(op, code, pc);
            int next = pc + len;
            if (isBranch(op) || op == 0xA7)
            {
                leader[pc + s2(pc + 1)] = true;
                leader[next] = true;
            }
            else if (op == 0xC8)
            {
                leader[pc + Baseline.s4(code, pc + 1)] = true;
                leader[next] = true;
            }
            else if (op == 0xAA || op == 0xAB)
            {
                int n = switchCount(pc);
                for (int k = -1; k < n; k++)
                {
                    leader[switchTarget(pc, k)] = true;
                }
                leader[next] = true;
            }
            else if (isExit(op))
            {
                leader[next] = true;
            }
            pc = next;
        }
        for (int e = 0; e < g.exCount; e++)
        {
            leader[g.exStart[e]] = true;
            leader[g.exEnd[e]] = true;
            leader[g.exHandler[e]] = true;
        }
        return true;
    }

    /** Normal successors of {@code b} from its last bytecode (a block with no branch falls into the next). */
    private boolean edges(Block b)
    {
        int pc = b.startBci;
        int last = pc;
        while (pc < b.endBci)
        {
            last = pc;
            pc += Baseline.opLen(code[pc] & 0xFF, code, pc);
        }
        int op = code[last] & 0xFF;
        if (isBranch(op))
        {
            b.addSucc(blockAt[last + s2(last + 1)]);
            b.addSucc(blockAt[b.endBci]);
        }
        else if (op == 0xA7)
        {
            b.addSucc(blockAt[last + s2(last + 1)]);
        }
        else if (op == 0xC8)
        {
            b.addSucc(blockAt[last + Baseline.s4(code, last + 1)]);
        }
        else if (op == 0xAA || op == 0xAB)
        {
            int n = switchCount(last);
            for (int k = -1; k < n; k++)
            {
                b.addSucc(blockAt[switchTarget(last, k)]);
            }
        }
        else if (!isExit(op))
        {
            if (b.endBci >= code.length)
            {
                return fail(SsaBuilder.R_FALLS_OFF, last);
            }
            b.addSucc(blockAt[b.endBci]);
        }
        return true;
    }

    /** Reverse postorder over normal and exception edges from the entry; unreachable blocks are dropped from
     *  {@link Graph#blocks} and from every reachable block's predecessor lists. */
    private void order()
    {
        Block[] post = new Block[nAll];
        int nPost = 0;
        Block[] stack = new Block[nAll + 1];
        int[] next = new int[nAll];
        boolean[] seen = new boolean[nAll];
        int top = 0;
        stack[0] = all[0];
        seen[0] = true;
        while (top >= 0)
        {
            Block b = stack[top];
            int i = next[b.id];
            int total = b.nSuccs + b.nHandlers;
            if (i < total)
            {
                next[b.id] = i + 1;
                Block s = i < b.nSuccs ? b.succs[i] : b.handlers[i - b.nSuccs];
                if (!seen[s.id])
                {
                    seen[s.id] = true;
                    top += 1;
                    stack[top] = s;
                }
            }
            else
            {
                post[nPost] = b;
                nPost += 1;
                top -= 1;
            }
        }
        g.blocks = new Block[nPost];
        g.nBlocks = nPost;
        for (int i = 0; i < nPost; i++)
        {
            Block b = post[nPost - 1 - i];
            b.rpo = i;
            g.blocks[i] = b;
        }
        for (int i = 0; i < nPost; i++)
        {
            Block b = g.blocks[i];
            int k = 0;
            for (int p = 0; p < b.nPreds; p++)
            {
                if (b.preds[p].rpo >= 0)
                {
                    b.preds[k] = b.preds[p];
                    k += 1;
                }
            }
            b.nPreds = k;
            k = 0;
            for (int p = 0; p < b.nExcPreds; p++)
            {
                if (b.excPreds[p].rpo >= 0)
                {
                    b.excPreds[k] = b.excPreds[p];
                    k += 1;
                }
            }
            b.nExcPreds = k;
            for (int p = 0; p < b.nPreds; p++)
            {
                if (b.preds[p].rpo >= b.rpo)
                {
                    b.loopHeader = true;
                }
            }
            for (int p = 0; p < b.nExcPreds; p++)
            {
                if (b.excPreds[p].rpo >= b.rpo)
                {
                    b.loopHeader = true;
                }
            }
        }
    }

    static boolean isBranch(int op)
    {
        return (op >= 0x99 && op <= 0xA6) || op == 0xC6 || op == 0xC7;
    }

    static boolean isExit(int op)
    {
        return (op >= 0xAC && op <= 0xB1) || op == 0xBF || op == 0xA7 || op == 0xC8;
    }

    private int s2(int i)
    {
        return (short) (((code[i] & 0xFF) << 8) | (code[i + 1] & 0xFF));
    }

    /** First 4-aligned byte after a switch opcode at {@code pc}. */
    static int switchBase(int pc)
    {
        return pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
    }

    /** Number of cases (not counting the default) of the switch at {@code pc}. */
    int switchCount(int pc)
    {
        int p = switchBase(pc);
        if ((code[pc] & 0xFF) == 0xAA)
        {
            return Baseline.s4(code, p + 8) - Baseline.s4(code, p + 4) + 1;
        }
        return Baseline.s4(code, p + 4);
    }

    /** Target of case {@code k} of the switch at {@code pc}; k = -1 is the default. */
    int switchTarget(int pc, int k)
    {
        int p = switchBase(pc);
        if (k < 0)
        {
            return pc + Baseline.s4(code, p);
        }
        if ((code[pc] & 0xFF) == 0xAA)
        {
            return pc + Baseline.s4(code, p + 12 + k * 4);
        }
        return pc + Baseline.s4(code, p + 8 + k * 8 + 4);
    }

    /** Key of case {@code k} of the switch at {@code pc}. */
    int switchKey(int pc, int k)
    {
        int p = switchBase(pc);
        if ((code[pc] & 0xFF) == 0xAA)
        {
            return Baseline.s4(code, p + 4) + k;
        }
        return Baseline.s4(code, p + 8 + k * 8);
    }
}
