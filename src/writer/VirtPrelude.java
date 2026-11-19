/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package writer;

import asm.A64Enc;
import board.Board;
import java.util.ArrayList;
import java.util.List;
import vm.VM;

/**
 * The start-up routine of a QEMU {@code virt} image: the first code that runs, before any Java.
 *
 * <p>QEMU loads the image at physical 0x4008_0000 (RAM starts at {@link Board#VIRT_RAM_PHYS}) and enters it at EL1
 * with the MMU off, but every address in the image -- code, statics, the heap layout -- was fixed for a load at
 * 0x80000. This routine makes those addresses true by turning the MMU on with virtual {@code va} mapped to physical
 * {@code va + 0x4000_0000}, then jumps to the image entry at its virtual address. It is written with PC-relative
 * addressing only (it runs at an address it was not linked for), and it learns the load offset by asking where it is
 * ({@code BL} + {@code x30}) rather than assuming 0x4000_0000.
 *
 * <p>The writer appends it after the image and replaces word 0 with a branch to it; the routine puts word 0 back
 * before the jump, so the Pi image and the virt image differ only by this tail and that one restored word.
 *
 * <p>It builds the SAME tables {@code VMScheduler.buildPageTables} builds for {@code virt}, at the same place
 * ({@link VM#PT_BASE}), plus one temporary identity entry for the 2 MiB block it is executing from -- the
 * instruction after the {@code SCTLR_EL1} write is fetched at its physical address, so that block must translate to
 * itself for the few instructions until the jump. {@code buildPageTables} later rewrites that entry to the ordinary
 * mapping.
 */
final class VirtPrelude
{
    private VirtPrelude()
    {
    }

    private static final int HS = 2;    // unsigned >=
    private static final int NE = 1;

    /** Registers: x1 = where "here" is, x2 = load offset (phys - virt), the rest scratch. */
    private static final int X_HERE = 1;
    private static final int X_OFF = 2;

    /**
     * The routine for an image whose word 0 holds {@code word0} and which places the routine at byte offset
     * {@code at} from the image start. Returns its instruction words.
     */
    static int[] build(int at, int word0)
    {
        List<Integer> w = new ArrayList<>();
        // x1 = the address of the instruction after the BL, i.e. of "here".
        w.add(A64Enc.bl(1));
        int here = w.size();
        w.add(A64Enc.movReg(X_HERE, 30));
        // x2 = load offset = actual address of "here" - its link address.
        long hereLink = CODE_BASE + at + here * 4L;
        constant(w, 3, hereLink);
        w.add(A64Enc.subReg(X_OFF, X_HERE, 3));

        // Put word 0 back (the writer replaced it with the branch that brought us here).
        constant(w, 3, CODE_BASE);
        w.add(A64Enc.addReg(3, 3, X_OFF));
        constant(w, 4, word0 & 0xFFFF_FFFFL);
        w.add(A64Enc.strw(4, 3, 0));

        // x5 = L1 table, x6 = the first of four L2 tables just above it (physical addresses).
        constant(w, 5, VM.PT_BASE);
        w.add(A64Enc.addReg(5, 5, X_OFF));
        w.add(A64Enc.addImmLsl12(6, 5, 1));
        // L1[i] = L2_i | table (3), i = 0..3.
        w.add(A64Enc.addImm(7, 6, 3));
        int i = 0;
        while (i < 4)
        {
            w.add(A64Enc.strx(7, 5, i * 8));
            w.add(A64Enc.addImmLsl12(7, 7, 1));
            i += 1;
        }

        // Fill the four L2 tables: 2048 2 MiB blocks covering virtual [0, 4 GiB).
        w.add(A64Enc.movz(8, 0, 0));                                  // x8 = va of this block
        w.add(A64Enc.movReg(9, 6));                                   // x9 = descriptor pointer
        constant(w, 10, Board.VIRT_DEV_LO);
        constant(w, 11, Board.VIRT_DEV_HI);
        constant(w, 13, 0x1_0000_0000L);
        constant(w, 14, RAM_ATTR);
        constant(w, 15, Board.VIRT_DEV_VA);
        constant(w, 16, DEV_ATTR);
        int loop = w.size();
        w.add(A64Enc.cmpReg(8, 10));
        int toDev = w.size();
        w.add(0);                                                     // b.hs dev
        w.add(A64Enc.addReg(7, 8, X_OFF));                            // RAM: pa = va + offset
        w.add(A64Enc.orrReg(7, 7, 14));
        int toStore1 = w.size();
        w.add(0);                                                     // b store
        int dev = w.size();
        w.add(A64Enc.cmpReg(8, 11));
        int toNone = w.size();
        w.add(0);                                                     // b.hs none
        w.add(A64Enc.subReg(7, 8, 15));                               // device: pa = va - VIRT_DEV_VA
        w.add(A64Enc.orrReg(7, 7, 16));
        int toStore2 = w.size();
        w.add(0);                                                     // b store
        int none = w.size();
        w.add(A64Enc.movz(7, 0, 0));                                  // the Pi's window: unmapped
        int store = w.size();
        w.add(A64Enc.strx(7, 9, 0));
        w.add(A64Enc.addImm(9, 9, 8));
        w.add(A64Enc.addImmLsl12(8, 8, 0x200));                       // next 2 MiB
        w.add(A64Enc.cmpReg(8, 13));
        w.add(A64Enc.bcond(NE, loop - w.size()));
        w.set(toDev, A64Enc.bcond(HS, dev - toDev));
        w.set(toStore1, A64Enc.b(store - toStore1));
        w.set(toNone, A64Enc.bcond(HS, none - toNone));
        w.set(toStore2, A64Enc.b(store - toStore2));

        // The temporary identity entry: the 2 MiB block this routine runs in maps to itself.
        w.add(A64Enc.lsrImm(7, X_HERE, 21));
        w.add(A64Enc.lslImm(8, 7, 3));
        w.add(A64Enc.addReg(9, 6, 8));                                // &L2[pa >> 21] (pa < 4 GiB)
        w.add(A64Enc.lslImm(7, 7, 21));
        w.add(A64Enc.orrReg(7, 7, 14));
        w.add(A64Enc.strx(7, 9, 0));
        w.add(A64Enc.dsb());

        // MMU on with the attributes enableMmuThisCore uses.
        constant(w, 7, 0xFFL);
        w.add(A64Enc.msr(A64Enc.MAIR_EL1, 7));
        constant(w, 7, TCR);
        w.add(A64Enc.msr(A64Enc.TCR_EL1, 7));
        w.add(A64Enc.msr(A64Enc.TTBR0_EL1, 5));
        w.add(A64Enc.dsb());
        w.add(A64Enc.tlbiVmalle1());
        w.add(A64Enc.dsb());
        w.add(A64Enc.icIallu());                                      // word 0 was rewritten
        w.add(A64Enc.dsb());
        w.add(A64Enc.isb());
        constant(w, 7, SCTLR);
        w.add(A64Enc.msr(A64Enc.SCTLR_EL1, 7));
        w.add(A64Enc.isb());

        // The stack below the image, as VM.boot sets it, and into the image at its link address.
        constant(w, 7, VM.STACK_TOP);
        w.add(A64Enc.movToSp(7));
        constant(w, 16, CODE_BASE);
        w.add(A64Enc.br(16));

        int[] out = new int[w.size()];
        int k = 0;
        while (k < out.length)
        {
            out[k] = w.get(k);
            k += 1;
        }
        return out;
    }

    private static final long CODE_BASE = asm.CodeBuffer.LOAD_ADDRESS;
    private static final long RAM_ATTR = 0x701L;            // block | AF | SH=inner | AttrIdx 0 (Normal WB)
    private static final long DEV_ATTR = 0x405L;            // block | AF | AttrIdx 1 (Device-nGnRnE)
    private static final long TCR = 0x2_0080_3520L;         // as VMScheduler.enableMmuThisCore
    private static final long SCTLR = 0x30D0_1805L;         // as VMScheduler.enableMmuThisCore

    /** Load a 64-bit constant into {@code rd} (MOVZ + MOVKs for the non-zero 16-bit lanes). */
    private static void constant(List<Integer> w, int rd, long v)
    {
        w.add(A64Enc.movz(rd, (int) (v & 0xFFFF), 0));
        int hw = 1;
        while (hw < 4)
        {
            int lane = (int) ((v >>> (16 * hw)) & 0xFFFF);
            if (lane != 0)
            {
                w.add(A64Enc.movk(rd, lane, hw));
            }
            hw += 1;
        }
    }
}
