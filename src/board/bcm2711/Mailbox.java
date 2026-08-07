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
 * <p>It asks for the <em>measured</em> rate. A baud sweep on real hardware showed
 * the core running at ~175 MHz while the plain {@code GET_CLOCK_RATE} tag reported
 * 200 MHz — the value {@code config.txt} requested, not the one the silicon
 * delivers.
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
     * Generic single-tag property call. {@code words} holds the tag's value buffer (request words in,
     * response words out in place); {@code words.length * 4} is the value-buffer size. Returns true on a
     * firmware reply, false on timeout (leaving {@code words} untouched). Cache-maintains the buffer, so it
     * is correct both before the MMU is on (maintenance is a harmless no-op) and after (RAM is cacheable and
     * the firmware writes the reply through the uncached bus alias).
     *
     * <p>Buffer layout at {@link Bcm2711#MBOX_BUFFER}: [total size][request=0][tag id][value size]
     * [req code=0][value words...][end tag=0].
     */
    public static boolean tag(int id, int[] words)
    {
        long b = Bcm2711.MBOX_BUFFER;
        int nwords = words.length;
        int total = (6 + nwords) * 4;                    // hdr(2) + tag hdr(3) + value(nwords) + end(1)
        Magic.store32(b, total);
        Magic.store32(b + 4, 0);                         // 0 = request
        Magic.store32(b + 8, id);
        Magic.store32(b + 12, nwords * 4);               // value buffer size in bytes
        Magic.store32(b + 16, 0);                        // request code
        int i = 0;
        while (i < nwords)
        {
            Magic.store32(b + 20 + i * 4, words[i]);
            i = i + 1;
        }
        Magic.store32(b + 20 + nwords * 4, 0);           // end tag
        cleanBuffer(b, total);                           // push the request out to the point of coherence
        if (!waitWritable())
        {
            return false;
        }
        // The VC wants a bus address (uncached alias) with the channel in the low 4 bits.
        Magic.store32(Bcm2711.MBOX_WRITE,
                      (int) (b | Bcm2711.MBOX_BUS_ALIAS) | Bcm2711.MBOX_CH_PROP);
        if (!waitResponse())
        {
            return false;
        }
        invalidateBuffer(b, total);                      // drop our stale cached copy; read the firmware's reply
        i = 0;
        while (i < nwords)
        {
            words[i] = Magic.load32(b + 20 + i * 4);
            i = i + 1;
        }
        return true;
    }

    /** Ask the firmware for the <em>measured</em> core clock in Hz; 0 if it does not answer. */
    public static int coreClockHz()
    {
        int[] w = new int[2];
        w[0] = Bcm2711.CLOCK_ID_CORE;                    // in: which clock
        w[1] = 0;                                        // out: rate in Hz
        if (!tag(Bcm2711.TAG_GET_CLOCK_RATE_MEASURED, w))
        {
            return 0;
        }
        return w[1];
    }

    /** The (requested, not measured) rate of clock {@code clockId} in Hz; 0 if unavailable. Used to compute
     *  the SDIO clock divider from the EMMC clock ({@link Bcm2711#CLOCK_ID_EMMC}). */
    public static int getClockRate(int clockId)
    {
        int[] w = new int[2];
        w[0] = clockId;
        w[1] = 0;
        if (!tag(Bcm2711.TAG_GET_CLOCK_RATE, w))
        {
            return 0;
        }
        return w[1];
    }

    /** Drive a firmware GPIO-expander pin ({@code expanderPin} = {@link Bcm2711#EXPANDER_GPIO_BASE}+n), e.g.
     *  the WiFi WL_ON power line. Returns the firmware's reply success. */
    public static boolean setExpanderGpio(int expanderPin, boolean on)
    {
        int[] w = new int[2];
        w[0] = expanderPin;
        w[1] = on ? 1 : 0;
        return tag(Bcm2711.TAG_SET_GPIO_STATE, w);
    }

    /** Clean the mailbox buffer's cache lines to the point of coherence (make our request visible). */
    private static void cleanBuffer(long b, int total)
    {
        long a = b & ~63L;
        long end = b + total;
        while (a < end)
        {
            Magic.dcCVAC(a);
            a = a + 64L;
        }
        Magic.dsb();
    }

    /** Clean+invalidate the buffer's cache lines (drop stale copies before reading the firmware's reply). */
    private static void invalidateBuffer(long b, int total)
    {
        Magic.dsb();
        long a = b & ~63L;
        long end = b + total;
        while (a < end)
        {
            Magic.dcCIVAC(a);
            a = a + 64L;
        }
        Magic.dsb();
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
