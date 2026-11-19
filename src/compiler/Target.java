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
package compiler;

import asm.A64Enc;

/**
 * What the compiler lowers against beyond the instruction set: the target machine's choices that change which
 * instruction an intrinsic becomes. Today one: which Arm generic timer is the EL1 tick and counter.
 *
 * <p><b>The board decides; the compiler only reads.</b> Each platform class declares {@code GENERIC_TIMER}
 * ({@link #TIMER_PHYS} on the Pi, {@link #TIMER_VIRT} on QEMU virt, where HVF leaves the physical timer
 * unemulated). The writer sets {@link #genericTimer} from the {@code --board} platform BEFORE it compiles anything
 * (so the baked code lowers right) and bakes the same value into the image (so the on-metal JIT does too). The
 * {@code Magic} timer intrinsics keep their CNTP names; this is where they become CNTP or CNTV.
 */
public final class Target
{
    private Target()
    {
    }

    /** The EL1 physical timer and count: CNTP_TVAL/CNTP_CTL, CNTPCT. */
    public static final long TIMER_PHYS = 0L;

    /** The EL1 virtual timer and count: CNTV_TVAL/CNTV_CTL, CNTVCT. */
    public static final long TIMER_VIRT = 1L;

    /** {@link #TIMER_PHYS} or {@link #TIMER_VIRT}: set by the writer (host) and baked into the image (metal). */
    public static long genericTimer;

    /** The free-running count register {@code readCNTPCT_EL0} reads. */
    public static int counter()
    {
        return genericTimer == TIMER_VIRT ? A64Enc.CNTVCT_EL0 : A64Enc.CNTPCT_EL0;
    }

    /** The countdown register {@code writeCNTP_TVAL_EL0} writes. */
    public static int timerValue()
    {
        return genericTimer == TIMER_VIRT ? A64Enc.CNTV_TVAL_EL0 : A64Enc.CNTP_TVAL_EL0;
    }

    /** The control register {@code writeCNTP_CTL_EL0}/{@code readCNTP_CTL_EL0} access. */
    public static int timerControl()
    {
        return genericTimer == TIMER_VIRT ? A64Enc.CNTV_CTL_EL0 : A64Enc.CNTP_CTL_EL0;
    }
}
