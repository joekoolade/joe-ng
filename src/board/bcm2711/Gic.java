package board.bcm2711;

import magic.Magic;

/**
 * The ARM GIC-400 (GICv2) interrupt controller on the Pi 4 (PLAN.md §M6) — the
 * distributor routes interrupt sources to the CPU interface, which the core takes
 * as an IRQ. This brings up both and enables one interrupt (the EL1 physical
 * timer's private peripheral interrupt), the minimum to drive a periodic tick.
 *
 * <p>Ordinary Java compiled to A64 by our baseline compiler; MMIO is plain
 * {@link Magic#load32}/{@link Magic#store32}.
 */
public final class Gic
{
    private Gic() {}

    private static final long GICD = 0xFF84_1000L;   // distributor
    private static final long GICC = 0xFF84_2000L;   // CPU interface

    private static final long GICD_CTLR       = GICD + 0x000;
    private static final long GICD_IGROUPR    = GICD + 0x080;   // 1 bit/INTID: 0 = group0, 1 = group1
    private static final long GICD_ISENABLER  = GICD + 0x100;   // 1 bit/INTID
    private static final long GICD_IPRIORITYR = GICD + 0x400;   // 1 byte/INTID

    private static final long GICC_CTLR = GICC + 0x000;
    private static final long GICC_PMR  = GICC + 0x004;        // priority mask
    private static final long GICC_IAR  = GICC + 0x00C;        // interrupt acknowledge
    private static final long GICC_EOIR = GICC + 0x010;        // end of interrupt

    /** Bring up the distributor + CPU interface and enable {@code intid}. */
    public static void init(int intid)
    {
        Magic.store32(GICD_IPRIORITYR + (intid / 4) * 4, 0);   // priority 0 (highest) for this word
        Magic.store32(GICD_ISENABLER + (intid / 32) * 4, 1 << (intid % 32));
        Magic.store32(GICD_CTLR, 3);                            // enable both groups
        Magic.store32(GICC_PMR, 0xFF);                         // unmask every priority level
        Magic.store32(GICC_CTLR, 3);                            // enable both groups (bit3 FIQEn stays 0)
    }

    /** Acknowledge the pending interrupt; returns its INTID (0x3FF = spurious). */
    public static int acknowledge()
    {
        return Magic.load32(GICC_IAR) & 0x3FF;
    }

    /** Signal end-of-interrupt for {@code intid}. */
    public static void end(int intid)
    {
        Magic.store32(GICC_EOIR, intid);
    }
}
