/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-23
 */
package board.arm;

import magic.Address;
import magic.Magic;

/**
 * The Arm GICv2 interrupt controller, at the distributor ({@code gicd}) and CPU-interface ({@code gicc}) bases its
 * board passes in: the Pi 4's GIC-400 and QEMU virt's GICv2 alike (PLAN.md §M6). Only the bases and which PPI the
 * board ticks on differ between them; every register offset below is architectural. The board's platform class
 * ({@code board.bcm2711.Bcm2711Platform}, {@code board.virt.VirtPlatform}) owns the bases; the VM reaches this
 * class only through {@link board.Irq}.
 *
 * <p>We run non-secure at EL1. On the Pi the firmware armstub (loaded when {@code enable_gic=1}) has already
 * configured the secure side and placed the PPIs in group 1, so a group-1 interrupt reaches the non-secure CPU
 * interface and signals as IRQ. MMIO is plain {@link Magic#load32}/{@link Magic#store32}.
 */
public final class Gic
{
    private Gic()
    {
    }

    private static final long GICD_CTLR       = 0x000;
    private static final long GICD_TYPER      = 0x004;   // bit 10: SecurityExtn
    private static final long GICD_IGROUPR    = 0x080;   // 1 bit/INTID: 0 = group0, 1 = group1
    private static final long GICD_ISENABLER  = 0x100;   // 1 bit/INTID: write 1 to enable forwarding
    private static final long GICD_ICENABLER  = 0x180;   // 1 bit/INTID: write 1 to disable forwarding
    private static final long GICD_IPRIORITYR = 0x400;   // 1 byte/INTID
    private static final long GICD_ITARGETSR  = 0x800;   // 1 byte/INTID: CPU target list (SPIs only)

    private static final long GICC_CTLR = 0x000;
    private static final long GICC_PMR  = 0x004;         // priority mask
    private static final long GICC_IAR  = 0x00C;         // interrupt acknowledge (NS: group-1)
    private static final long GICC_EOIR = 0x010;         // end of interrupt (NS: group-1)

    /** What GICC_IAR reads when nothing is pending. */
    public static final int SPURIOUS = 0x3FF;

    /** Non-secure EL1 physical timer (CNTP_EL0): the Pi's tick. BCM2711 Figure 7. */
    public static final int PPI_CNTPNS = 30;

    /** EL1 virtual timer (CNTV_EL0): the tick on QEMU virt, where Baseline lowers the timer intrinsics to CNTV. */
    public static final int PPI_CNTV = 27;

    /**
     * The group an interrupt must be in for {@code CTLR = 1} to forward it. With the Security Extensions -- the Pi's
     * GIC-400, after the armstub -- we see the NON-SECURE view, where bit 0 of GICD_CTLR/GICC_CTLR is EnableGrp1,
     * so the interrupt goes in group 1. WITHOUT them -- QEMU's raspi4b model (GICD_TYPER.SecurityExtn = 0) -- the
     * same bit 0 is EnableGrp0, so a group-1 interrupt was enabled, pending and never forwarded: MEASURED
     * (demo/TickProbe, 2026-11-15) ISPENDR0 bit 30 set with GICD_CTLR = GICC_CTLR = 1, and no tick ever taken.
     * That one fault was QEMU's "no timer ticks for a launched program", "no timer PPI to a secondary" and "never
     * raises SPI 125". There the interrupt stays in group 0, which signals as IRQ (FIQEn is 0).
     */
    private static void setGroup(long gicd, int intid)
    {
        if (((Address.fromLong(gicd + GICD_TYPER).loadInt() >> 10) & 1) == 0)
        {
            return;                                                  // no Security Extensions: group 0
        }
        int gr = (int) word(gicd + GICD_IGROUPR, intid).loadInt();
        word(gicd + GICD_IGROUPR, intid).store(gr | bit(intid));     // group 1 (non-secure)
    }

    private static Address word(long base, int intid)
    {
        return Address.fromLong(base).plus((intid / 32) * 4);
    }

    private static int bit(int intid)
    {
        return 1 << (intid % 32);
    }

    /** Turn on the distributor and this core's CPU interface with every priority level unmasked. */
    private static void enableInterfaces(long gicd, long gicc)
    {
        Address.fromLong(gicd + GICD_CTLR).store(1);                 // NS view: bit0 = EnableGrp1
        Address.fromLong(gicc + GICC_PMR).store(0xFF);               // unmask every priority level
        Address.fromLong(gicc + GICC_CTLR).store(1);                 // NS view: bit0 = EnableGrp1
    }

    /**
     * Enable {@code intid} (a PPI: banked per core, so each core calls this for its own) and bring up this core's
     * CPU interface. Setting the group bit only sticks if the firmware armstub already opened the secure side and
     * placed the PPIs in group 1 (our custom armstub does this -- see armstub/README.md); otherwise it is RAZ/WI.
     */
    public static void init(long gicd, long gicc, int intid)
    {
        setGroup(gicd, intid);
        Address.fromLong(gicd + GICD_IPRIORITYR).plus((intid / 4) * 4).store(0);   // priority 0 (highest) for this word
        word(gicd + GICD_ISENABLER, intid).store(bit(intid));        // forwarding enable
        enableInterfaces(gicd, gicc);
    }

    /**
     * Enable a shared peripheral interrupt (SPI, INTID >= 32) and route it to CPU 0. Unlike a PPI, an SPI is not
     * per-core, so it needs an explicit target in ITARGETSR.
     */
    public static void initSpi(long gicd, long gicc, int intid)
    {
        setGroup(gicd, intid);
        Address.fromLong(gicd + GICD_IPRIORITYR).plus((intid / 4) * 4).store(0);   // priority 0 (highest) for this word
        Address taddr = Address.fromLong(gicd + GICD_ITARGETSR).plus((intid / 4) * 4);   // route to CPU 0 (byte = 0x01)
        int shift = (intid % 4) * 8;
        int cur = (int) taddr.loadInt();
        taddr.store((cur & ~(0xFF << shift)) | (0x01 << shift));
        word(gicd + GICD_ISENABLER, intid).store(bit(intid));        // forwarding enable
        enableInterfaces(gicd, gicc);
    }

    /** Acknowledge the pending group-1 interrupt; returns its INTID ({@link #SPURIOUS} if none). */
    public static int acknowledge(long gicc)
    {
        return Address.fromLong(gicc + GICC_IAR).loadInt() & 0x3FF;
    }

    /** Signal end-of-interrupt for {@code intid} (the value returned by {@link #acknowledge}). */
    public static void end(long gicc, int intid)
    {
        Address.fromLong(gicc + GICC_EOIR).store(intid);
    }

    /** Stop forwarding {@code intid} to any CPU (GICD_ICENABLER). Used by an ISR to keep a level interrupt from
     *  re-firing until the handler task has serviced and re-enabled it. */
    public static void disable(long gicd, int intid)
    {
        word(gicd + GICD_ICENABLER, intid).store(bit(intid));
    }

    /** Resume forwarding {@code intid} (GICD_ISENABLER) after servicing. */
    public static void enable(long gicd, int intid)
    {
        word(gicd + GICD_ISENABLER, intid).store(bit(intid));
    }
}
