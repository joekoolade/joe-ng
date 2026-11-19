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
package board.bcm2711;

import board.Board;
import magic.Address;
import magic.Magic;

/**
 * The ARM GIC-400 (GICv2) interrupt controller on the Pi 4 (PLAN.md §M6). When the GIC is the
 * selected controller (the BCM2711 default, {@code enable_gic=1}), each core's timer IRQs are wired
 * straight to the GIC as PPIs — the ARM-local per-core router is bypassed. The non-secure EL1
 * physical timer (CNTP_EL0) is <b>PPI INTID 30</b> (BCM2711 peripherals §6.3, Figure 7).
 *
 * <p>We run non-secure at EL1. On real hardware the firmware armstub (loaded when {@code
 * enable_gic=1}) has already configured the secure side and placed the PPIs in group 1, so a
 * group-1 interrupt reaches the non-secure CPU interface and signals as IRQ. Ordinary Java compiled
 * to A64; MMIO is plain {@link Magic#load32}/{@link Magic#store32}.
 */
public final class Gic
{
    private Gic() {}

    /**
     * Base = 0x4c0040000; in Low Peripheral mode it maps to 0xFF840000 (peripherals §6.5.1). QEMU {@code virt} has
     * a GICv2 too, programmed identically, at {@link Board#VIRT_GICD}/{@link Board#VIRT_GICC}: the two bases are
     * the only difference, so they are chosen per board (the register offsets below are architectural).
     */
    private static final long PI_GICD = 0xFF84_1000L;   // distributor  (base + 0x1000)
    private static final long PI_GICC = 0xFF84_2000L;   // CPU interface (base + 0x2000)

    private static long gicd()
    {
        return Board.isVirt() ? Board.VIRT_GICD : PI_GICD;
    }

    private static long gicc()
    {
        return Board.isVirt() ? Board.VIRT_GICC : PI_GICC;
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

    /** Non-secure EL1 physical timer (CNTP_EL0). BCM2711 Figure 7. */
    public static final int PPI_CNTPNS = 30;

    /** EL1 virtual timer (CNTV_EL0): the tick on QEMU virt, where Baseline lowers the timer intrinsics to CNTV. */
    public static final int PPI_CNTV = 27;

    /** The timer PPI this board ticks on: the physical timer's on the Pi, the virtual timer's on QEMU virt. */
    public static int timerPpi()
    {
        return Board.isVirt() ? PPI_CNTV : PPI_CNTPNS;
    }

    /**
     * The group an interrupt must be in for {@code CTLR = 1} to forward it. With the Security Extensions -- the Pi's
     * GIC-400, after the armstub -- we see the NON-SECURE view, where bit 0 of GICD_CTLR/GICC_CTLR is EnableGrp1,
     * so the interrupt goes in group 1. WITHOUT them -- QEMU's raspi4b model (GICD_TYPER.SecurityExtn = 0) -- the
     * same bit 0 is EnableGrp0, so a group-1 interrupt was enabled, pending and never forwarded: MEASURED
     * (demo/TickProbe, 2026-11-15) ISPENDR0 bit 30 set with GICD_CTLR = GICC_CTLR = 1, and no tick ever taken.
     * That one fault was QEMU's "no timer ticks for a launched program", "no timer PPI to a secondary" and "never
     * raises SPI 125". There the interrupt stays in group 0, which signals as IRQ (FIQEn is 0).
     */
    private static void setGroup(int intid)
    {
        if (((Address.fromLong(gicd() + GICD_TYPER).loadInt() >> 10) & 1) == 0)
        {
            return;                                                  // no Security Extensions: group 0
        }
        int gr = (int) word(gicd() + GICD_IGROUPR, intid).loadInt();
        word(gicd() + GICD_IGROUPR, intid).store(gr | bit(intid));   // group 1 (non-secure)
    }

    private static Address word(long base, int intid)
    {
        return Address.fromLong(base).plus((intid / 32) * 4);
    }

    private static int bit(int intid)
    {
        return 1 << (intid % 32);
    }

    /**
     * Enable {@code intid} as a non-secure group-1 interrupt and bring up the CPU interface. Setting
     * the group bit only sticks if the firmware armstub already opened the secure side and placed the
     * PPIs in group 1 (our custom armstub does this — see armstub/README.md); otherwise it is RAZ/WI.
     */
    public static void init(int intid)
    {
        setGroup(intid);
        Address.fromLong(gicd() + GICD_IPRIORITYR).plus((intid / 4) * 4).store(0);   // priority 0 (highest) for this word
        word(gicd() + GICD_ISENABLER, intid).store(bit(intid));              // forwarding enable
        Address.fromLong(gicd() + GICD_CTLR).store(1);                        // NS view: bit0 = EnableGrp1
        Address.fromLong(gicc() + GICC_PMR).store(0xFF);                      // unmask every priority level
        Address.fromLong(gicc() + GICC_CTLR).store(1);                        // NS view: bit0 = EnableGrp1
    }

    /**
     * Enable a shared peripheral interrupt (SPI, INTID >= 32) as non-secure group 1 and route it to
     * CPU 0. Unlike a PPI, an SPI is not per-core, so it needs an explicit target in ITARGETSR.
     */
    public static void initSpi(int intid)
    {
        setGroup(intid);
        Address.fromLong(gicd() + GICD_IPRIORITYR).plus((intid / 4) * 4).store(0);   // priority 0 (highest) for this word
        Address taddr = Address.fromLong(gicd() + GICD_ITARGETSR).plus((intid / 4) * 4);   // route to CPU 0 (byte = 0x01)
        int shift = (intid % 4) * 8;
        int cur = (int) taddr.loadInt();
        taddr.store((cur & ~(0xFF << shift)) | (0x01 << shift));
        word(gicd() + GICD_ISENABLER, intid).store(bit(intid));              // forwarding enable
        Address.fromLong(gicd() + GICD_CTLR).store(1);                        // NS view: bit0 = EnableGrp1
        Address.fromLong(gicc() + GICC_PMR).store(0xFF);                      // unmask every priority level
        Address.fromLong(gicc() + GICC_CTLR).store(1);                        // NS view: bit0 = EnableGrp1
    }

    /** Acknowledge the pending group-1 interrupt; returns its INTID (0x3FF = spurious). */
    public static int acknowledge()
    {
        return Address.fromLong(gicc() + GICC_IAR).loadInt() & 0x3FF;
    }

    /** Signal end-of-interrupt for {@code intid} (the value returned by {@link #acknowledge}). */
    public static void end(int intid)
    {
        Address.fromLong(gicc() + GICC_EOIR).store(intid);
    }

    /** Stop forwarding {@code intid} to any CPU (GICD_ICENABLER). Used by an ISR to prevent a level interrupt
     *  re-firing until the handler task has serviced and re-enabled it. */
    public static void disableSpi(int intid)
    {
        word(gicd() + GICD_ICENABLER, intid).store(bit(intid));
    }

    /** Resume forwarding {@code intid} (GICD_ISENABLER) after servicing. */
    public static void enableSpi(int intid)
    {
        word(gicd() + GICD_ISENABLER, intid).store(bit(intid));
    }

}
