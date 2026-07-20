package board.bcm2711;

import magic.Magic;

/**
 * VideoCore mailbox (property interface, channel 8), written as ordinary Java and
 * compiled to A64 by our baseline compiler.
 *
 * <p>It exists for one reason: the mini-UART's baud rate is derived from the VPU
 * <em>core</em> clock, and that clock is not something we can assume. On real Pi 4
 * silicon it moved between firmware builds and even between SD cards (a card
 * carrying recovery files boots different firmware), so every hardcoded divisor we
 * tried — 270 (250 MHz), 541 (500 MHz), 216 (200 MHz) — was right on one setup and
 * garbage on the next. Instead of guessing, {@link #coreClockHz()} asks the
 * firmware for the actual rate and {@link Uart} computes the divisor from it.
 *
 * <p>Every wait is bounded: if the firmware never answers we return 0 and the
 * caller falls back to a compiled-in divisor, so a mailbox problem degrades to the
 * old behaviour rather than hanging the boot.
 */
public final class Mailbox
{
    private Mailbox() {}

    private static final int SPIN_LIMIT = 2_000_000;

    /**
     * Ask the firmware for the core clock in Hz; 0 if it does not answer.
     * Property buffer (8 words): size, request-code, then one GET_CLOCK_RATE tag
     * (id, value-size, req/resp code, clock id, rate-out) and the end tag.
     */
    public static int coreClockHz()
    {
        long b = Bcm2711.MBOX_BUFFER;
        Magic.store32(b, 32);                            // total size in bytes
        Magic.store32(b + 4, 0);                         // 0 = request
        Magic.store32(b + 8, Bcm2711.TAG_GET_CLOCK_RATE);
        Magic.store32(b + 12, 8);                        // value buffer is 8 bytes
        Magic.store32(b + 16, 0);                        // request code
        Magic.store32(b + 20, Bcm2711.CLOCK_ID_CORE);    // in: which clock
        Magic.store32(b + 24, 0);                        // out: rate in Hz
        Magic.store32(b + 28, 0);                        // end tag
        Magic.dsb();
        if (!waitWritable())
        {
            return 0;
        }
        // The VC wants a bus address with the channel in the low 4 bits.
        Magic.store32(Bcm2711.MBOX_WRITE,
                      (int) (b | Bcm2711.MBOX_BUS_ALIAS) | Bcm2711.MBOX_CH_PROP);
        if (!waitResponse())
        {
            return 0;
        }
        Magic.dsb();
        return Magic.load32(b + 24);
    }

    /** Spin until the mailbox can accept a write; false if it never can. */
    private static boolean waitWritable()
    {
        int spins = 0;
        while ((Magic.load32(Bcm2711.MBOX_STATUS) & Bcm2711.MBOX_FULL) != 0)
        {
            spins = spins + 1;
            if (spins > SPIN_LIMIT)
            {
                return false;
            }
        }
        return true;
    }

    /** Spin until a reply on the property channel arrives; false if none does. */
    private static boolean waitResponse()
    {
        int spins = 0;
        while (spins <= SPIN_LIMIT)
        {
            if ((Magic.load32(Bcm2711.MBOX_STATUS) & Bcm2711.MBOX_EMPTY) == 0)
            {
                if ((Magic.load32(Bcm2711.MBOX_READ) & 0xF) == Bcm2711.MBOX_CH_PROP)
                {
                    return true;                         // our channel answered
                }
            }
            spins = spins + 1;
        }
        return false;
    }
}
