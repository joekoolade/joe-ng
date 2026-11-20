/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package writer;

import asm.A64Enc;
import java.util.ArrayList;
import java.util.List;
import vm.VM;

/**
 * The start-up routine of a Raspberry Pi 4 image: the first code that runs, before any Java. It takes the boot core
 * from the exception level the firmware entered at (EL2) down to EL1, where the VM runs, and enters the image there.
 *
 * <p>The GPU firmware loads the image at its link address, 0x80000, and starts the core at EL2 (the armstub's
 * hand-off). This routine opens EL1 the way {@code Baseline.lowerDropToEL1} did inside {@code VM.boot}:
 * HCR_EL2.RW (EL1 is AArch64), CPTR_EL2 (no FP trap to EL2), CNTHCTL_EL2/CNTVOFF_EL2 (EL1 may read the counters,
 * virtual = physical), an MMU-off SCTLR_EL1, EL1's stack pointer, then SPSR_EL2 = EL1h with DAIF masked and
 * ELR_EL2 = the image entry, and ERET. Doing it here rather than in Java is what lets {@code VM.boot} be the same on
 * every board: an ERET switches stack pointers ({@code SP_EL2} -> {@code SP_EL1}), so it cannot sit in a method that
 * then returns, and from here on every board enters {@code VM.boot} at EL1.
 *
 * <p>As with {@link VirtPrelude}, the writer appends this routine after the image and replaces word 0 with a branch
 * to it; the routine puts word 0 back first. So after the first entry, a wild branch to 0x80000 lands on
 * {@code VM.boot} itself at EL1, which {@code VM.bootEntered} reports. Firmware's x0..x2 (the device-tree pointer)
 * are left untouched. Entered at EL1 already, the routine skips the drop and just sets the stack and enters.
 */
final class Bcm2711Prelude
{
    private Bcm2711Prelude()
    {
    }

    private static final long CODE_BASE = asm.CodeBuffer.LOAD_ADDRESS;
    private static final long HCR_RW = 0x8000_0000L;        // HCR_EL2.RW: EL1 is AArch64
    private static final long CPTR = 0x33FFL;               // CPTR_EL2: TFP = 0 (FP/SIMD not trapped to EL2), RES1 bits
    private static final long CNTHCTL = 0x3L;               // EL1PCTEN | EL1PCEN: EL1 reads the physical counter + timer
    private static final long SCTLR_EL1 = 0x30D0_0800L;     // RES1 bits, MMU and caches off (VMScheduler turns them on)
    private static final long SPSR_EL1H = 0x3C5L;           // EL1h, D/A/I/F masked

    /**
     * The routine for an image whose word 0 holds {@code word0}; it is placed at byte offset {@code at} from the
     * image start (unused: the Pi loads the image at its link address, so absolute addresses hold).
     */
    static int[] build(int at, int word0)
    {
        List<Integer> w = new ArrayList<>();

        // Put word 0 back (the writer replaced it with the branch that brought us here), and make the instruction
        // fetch see it: clean the line to the point of unification, then invalidate the instruction cache.
        constant(w, 3, CODE_BASE);
        constant(w, 4, word0 & 0xFFFF_FFFFL);
        w.add(A64Enc.strw(4, 3, 0));
        w.add(A64Enc.dcCvau(3));
        w.add(A64Enc.dsb());
        w.add(A64Enc.icIallu());
        w.add(A64Enc.dsb());
        w.add(A64Enc.isb());

        // x5 = EL1's stack top (VM.boot sets the same value again).
        constant(w, 5, VM.STACK_TOP);

        // Already at EL1 (CurrentEL bit 3 clear: EL0/EL1)? Then no drop: set SP and enter.
        w.add(A64Enc.mrs(6, A64Enc.CurrentEL));
        int toEl1 = w.size();
        w.add(0);                                                      // tbz x6, #3, el1

        // EL2 -> EL1.
        constant(w, 6, HCR_RW);
        w.add(A64Enc.msr(A64Enc.HCR_EL2, 6));
        constant(w, 6, CPTR);
        w.add(A64Enc.msr(A64Enc.CPTR_EL2, 6));
        constant(w, 6, CNTHCTL);
        w.add(A64Enc.msr(A64Enc.CNTHCTL_EL2, 6));
        w.add(A64Enc.msr(A64Enc.CNTVOFF_EL2, A64Enc.XZR));
        constant(w, 6, SCTLR_EL1);
        w.add(A64Enc.msr(A64Enc.SCTLR_EL1, 6));
        w.add(A64Enc.msr(A64Enc.SP_EL1, 5));
        constant(w, 6, SPSR_EL1H);
        w.add(A64Enc.msr(A64Enc.SPSR_EL2, 6));
        w.add(A64Enc.msr(A64Enc.ELR_EL2, 3));                         // x3 = CODE_BASE: VM.boot
        w.add(A64Enc.isb());
        w.add(A64Enc.eret());

        int el1 = w.size();
        w.set(toEl1, A64Enc.tbz(6, 3, el1 - toEl1));
        w.add(A64Enc.movToSp(5));
        w.add(A64Enc.br(3));

        int[] out = new int[w.size()];
        int k = 0;
        while (k < out.length)
        {
            out[k] = w.get(k);
            k += 1;
        }
        return out;
    }

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
