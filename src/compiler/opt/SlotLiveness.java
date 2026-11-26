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
 * Which spill slots hold a value someone will still read, after each instruction of the allocated code: backward
 * dataflow where a {@link MInstr#RELOAD} reads its slot and a {@link MInstr#SPILL} writes it. A GC map types only
 * these at a call -- a slot whose value is dead may hold a stale reference, which a moving collector must not
 * treat as live (nor, once it is dead, does anything read it back).
 */
final class SlotLiveness
{
    private final MFunc f;
    private final int words;
    private final long[][] liveOut;
    private final int[] index;

    SlotLiveness(MFunc f)
    {
        this.f = f;
        this.words = (f.nSlots + 63) / 64 + 1;
        int maxId = 0;
        for (int b = 0; b < f.nBlocks; b++)
        {
            if (f.blocks[b].id > maxId)
            {
                maxId = f.blocks[b].id;
            }
        }
        index = new int[maxId + 1];
        for (int b = 0; b < f.nBlocks; b++)
        {
            index[f.blocks[b].id] = b;
        }
        liveOut = new long[f.nBlocks][words];
        long[][] liveIn = new long[f.nBlocks][words];
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (int b = f.nBlocks - 1; b >= 0; b--)
            {
                MBlock blk = f.blocks[b];
                long[] out = liveOut[b];
                for (int s = 0; s < blk.nSuccs; s++)
                {
                    long[] in = liveIn[index[blk.succs[s].id]];
                    for (int w = 0; w < words; w++)
                    {
                        out[w] |= in[w];
                    }
                }
                long[] in = copy(out);
                for (int i = blk.n - 1; i >= 0; i--)
                {
                    step(blk.code[i], in);
                }
                for (int w = 0; w < words; w++)
                {
                    if (in[w] != liveIn[b][w])
                    {
                        liveIn[b][w] = in[w];
                        changed = true;
                    }
                }
            }
        }
    }

    /** Per instruction of {@code blk}: the slots live just after it. */
    long[][] afterEach(MBlock blk)
    {
        long[][] r = new long[blk.n][];
        long[] live = copy(liveOut[index[blk.id]]);
        for (int i = blk.n - 1; i >= 0; i--)
        {
            r[i] = copy(live);
            step(blk.code[i], live);
        }
        return r;
    }

    /** live-before = live-after, minus what x writes, plus what x reads. */
    private static void step(MInstr x, long[] live)
    {
        if (x.op == MInstr.SPILL)
        {
            live[x.aux >> 6] &= ~(1L << (x.aux & 63));
        }
        else if (x.op == MInstr.RELOAD)
        {
            live[x.aux >> 6] |= 1L << (x.aux & 63);
        }
    }

    private long[] copy(long[] a)
    {
        long[] b = new long[words];
        for (int i = 0; i < words; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
