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
 * A method in machine IR: blocks in layout order (block 0 the entry) and the virtual registers they name. After
 * {@link LinearScan}, {@link #loc} gives every virtual register its physical register, and {@link #nSlots} is the
 * number of spill slots the frame needs.
 */
public final class MFunc
{
    public MBlock[] blocks = new MBlock[8];
    public int nBlocks;
    private int nextBlockId;

    public int nV;
    /** Per virtual register: its {@link RegFile} class. */
    public int[] vclass = new int[16];
    /** Per virtual register: the physical register it is pinned to, or -1. */
    public int[] fixed = new int[16];
    /** Per virtual register: never spilled (a reload or spill temporary, live across one instruction). */
    public boolean[] noSpill = new boolean[16];
    /** Per virtual register: the register it stands for (itself, or the spilled one a temporary carries). */
    public int[] origin = new int[16];
    /** Per virtual register: what the collector must do with it in a frame slot -- {@link #GC_RAW} (the default),
     *  {@link #GC_REF} or {@link #GC_DREF}; the same 2-bit codes as a GMAP map. */
    public byte[] vgc = new byte[16];
    /** Per virtual register: its physical register, after allocation. */
    public int[] loc;
    public int nSlots;
    /** Per spill slot: the virtual register spilled there (its {@link #vgc} types the slot in a GC map). */
    public int[] slotVreg = new int[8];

    public static final byte GC_REF = 1;
    public static final byte GC_RAW = 2;
    public static final byte GC_DREF = 3;

    /** Give {@code dst} the GC kind of {@code src}: a copy holds what its source holds. */
    public void copyGc(int dst, int src)
    {
        vgc[dst] = vgc[src];
    }

    /** A new spill slot for virtual register {@code v}. */
    public int newSlot(int v)
    {
        if (nSlots == slotVreg.length)
        {
            slotVreg = growInt(slotVreg, nSlots * 2);
        }
        slotVreg[nSlots] = v;
        nSlots += 1;
        return nSlots - 1;
    }

    public MBlock newBlock()
    {
        MBlock b = new MBlock(nextBlockId);
        nextBlockId += 1;
        blocks = MBlock.grow(blocks, nBlocks);
        blocks[nBlocks] = b;
        nBlocks += 1;
        return b;
    }

    /** Insert a new block into the layout right after {@code after}. */
    MBlock newBlockAfter(MBlock after)
    {
        MBlock b = newBlock();
        int at = 0;
        while (blocks[at] != after)
        {
            at += 1;
        }
        for (int i = nBlocks - 1; i > at + 1; i--)
        {
            blocks[i] = blocks[i - 1];
        }
        blocks[at + 1] = b;
        return b;
    }

    public int newVreg(int cls)
    {
        if (nV == vclass.length)
        {
            int m = nV * 2;
            vclass = growInt(vclass, m);
            fixed = growInt(fixed, m);
            origin = growInt(origin, m);
            boolean[] ns = new boolean[m];
            byte[] gc = new byte[m];
            for (int i = 0; i < nV; i++)
            {
                ns[i] = noSpill[i];
                gc[i] = vgc[i];
            }
            noSpill = ns;
            vgc = gc;
        }
        int v = nV;
        vclass[v] = cls;
        vgc[v] = GC_RAW;
        fixed[v] = -1;
        origin[v] = v;
        nV += 1;
        return v;
    }

    /** A virtual register pinned to physical register {@code p}. */
    public int fixedVreg(int cls, int p)
    {
        int v = newVreg(cls);
        fixed[v] = p;
        noSpill[v] = true;
        return v;
    }

    private static int[] growInt(int[] a, int m)
    {
        int[] b = new int[m];
        for (int i = 0; i < a.length; i++)
        {
            b[i] = a[i];
        }
        return b;
    }
}
