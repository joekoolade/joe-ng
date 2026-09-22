/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-22
 */
package board.bcm2711;

import magic.Magic;

/**
 * The BCM2711's hardware random number generator -- an RNG200 block at {@code 0xFE104000}, and joe-ng's only
 * source of real entropy.
 *
 * <h2>Which block this is was MEASURED, not read off a datasheet</h2>
 *
 * <p>The Pi 4 could plausibly have carried either the older BCM2835 RNG ({@code CTRL/STATUS/DATA}) or the
 * RNG200 ({@code CTRL/.../FIFO_DATA(0x20)/FIFO_COUNT(0x24)}), and those layouts OVERLAP at different
 * offsets -- so code written for one and run against the other reads plausible-looking nonsense, which in an
 * RNG is the one wrong answer nobody can spot by looking at it. A read-only probe settled it on silicon:
 *
 * <pre>
 *   CTRL   +0x00 = 0x00007fff     RNG200: RBGEN(bits 12:0) = 0x1FFF, i.e. ENABLED
 *   STATUS +0x04 = 0x00000000     BCM2835 would read this as "0 words available"
 *   DATA   +0x08 = 0x00000000     ...and this as the data port, empty
 *   FIFOCNT+0x24 = 0x40001010     RNG200: count = 16, threshold = 16
 * </pre>
 *
 * <p>Only the RNG200 decode is COHERENT: the BCM2835 one says an enabled generator has nothing available.
 * A second boot then confirmed the FIFO is LIVE rather than merely plausible --
 * {@code count 16 -> 13, words a77bf507 f2077214 65f887c6} -- three reads draining exactly three words.
 *
 * <h2>This driver never WRITES, and that is a safety property rather than laziness</h2>
 *
 * <p>The VideoCore firmware has already enabled the block and let the FIFO fill, so there is nothing to
 * configure. That matters because a guarded READ of an unmapped peripheral raises a fault this VM recovers
 * from and reports, while a STORE does not -- the probe that first tried {@code CTRL = 1} died at an
 * {@code <unclaimed pc>} the unwinder could not attribute. So if this board's CTRL ever reads as DISABLED,
 * this reports "no entropy" rather than enabling it: refusing is recoverable, and guessing at the layout of
 * a live peripheral is not.
 *
 * <h2>The liveness check is what separates "a register decodes plausibly" from "a source works"</h2>
 *
 * <p>{@link #available()} draws sample words and REJECTS the source unless they vary. A stuck-at line, a
 * counter, or a window that reads back a constant all produce a perfectly stable stream that looks like
 * data; nothing downstream could tell, because every output of a random source looks equally correct. The
 * check runs once and latches, and its samples are DISCARDED rather than fed to a caller.
 *
 * <p>Every access is inside a {@code try}. Under QEMU this whole window faults -- measurably, while PM and
 * GPIO beside it read fine -- and this must degrade to "absent" rather than take the VM with it.
 */
public final class Rng
{
    private Rng() {}

    private static final long BASE = 0xFE10_4000L;
    private static final long CTRL = BASE + 0x00;
    private static final long FIFO_DATA = BASE + 0x20;
    private static final long FIFO_COUNT = BASE + 0x24;

    /** {@code RNG_CTRL_RNG_RBGEN_MASK} -- any bit set means the generator is running. */
    private static final int RBGEN_MASK = 0x0000_1FFF;

    /** How long to spin for the FIFO to refill before giving up on a word. Bounded, never forever. */
    private static final int SPIN = 200000;

    /** Sample words the liveness check draws. Enough to catch a constant without draining the FIFO. */
    private static final int SAMPLES = 4;

    private static final int UNPROBED = 0;
    private static final int USABLE = 1;
    private static final int UNUSABLE = -1;

    private static int state = UNPROBED;

    /** Why the source was rejected, for the boot report. 0 = fine. */
    private static int why;

    private static final int WHY_FAULT = 1;
    private static final int WHY_DISABLED = 2;
    private static final int WHY_EMPTY = 3;
    private static final int WHY_STUCK = 4;

    /**
     * {@return whether this board has a usable hardware entropy source}
     *
     * <p>Probes once and latches. The probe COSTS a few words of entropy, which is the right trade: an
     * unchecked source that turns out to be stuck would seed every {@code SecureRandom} on the board
     * identically, for ever, with nothing in the output to show it.
     */
    public static boolean available()
    {
        if (state != UNPROBED)
        {
            return state == USABLE;
        }
        state = UNUSABLE;
        try
        {
            int ctrl = Magic.load32(CTRL);
            if ((ctrl & RBGEN_MASK) == 0)
            {
                // Deliberately NOT enabled here -- see the class note on why this driver never writes.
                why = WHY_DISABLED;
                return false;
            }
            int first = drawWord();
            if (first == 0 && why == WHY_EMPTY)
            {
                return false;
            }
            boolean varies = false;
            int i = 1;
            while (i < SAMPLES)
            {
                int w = drawWord();
                if (why == WHY_EMPTY)
                {
                    return false;
                }
                if (w != first)
                {
                    varies = true;
                }
                i = i + 1;
            }
            if (!varies)
            {
                // Every sample identical: a stuck line, a constant, or a window that is not really a FIFO.
                why = WHY_STUCK;
                return false;
            }
            why = 0;
            state = USABLE;
            return true;
        }
        catch (Throwable t)
        {
            // An unmapped window. QEMU is exactly this case, and it must not take the boot with it.
            why = WHY_FAULT;
            return false;
        }
    }

    /**
     * One word from the FIFO, spinning a BOUNDED number of times for it to refill.
     *
     * <p>Sets {@link #why} to {@code WHY_EMPTY} and returns 0 if the FIFO never produced one -- a caller
     * must check, because 0 is also a perfectly legal random word.
     */
    private static int drawWord()
    {
        int spins = 0;
        while (spins < SPIN)
        {
            if ((Magic.load32(FIFO_COUNT) & 0xFF) != 0)
            {
                return Magic.load32(FIFO_DATA);
            }
            spins = spins + 1;
        }
        why = WHY_EMPTY;
        return 0;
    }

    /**
     * Fill {@code len} bytes of entropy at {@code dst}.
     *
     * @param dst raw destination address
     * @param len how many bytes are wanted
     * @return how many were actually written -- SHORT OF {@code len} if the FIFO ran dry, and 0 if this
     *         board has no usable source. A caller must treat a short answer as a failure rather than pad
     *         it: partial entropy stretched to a full seed is a weak seed that looks like a strong one.
     */
    public static int fill(long dst, int len)
    {
        if (!available())
        {
            return 0;
        }
        int done = 0;
        try
        {
            while (done < len)
            {
                why = 0;
                int w = drawWord();
                if (why == WHY_EMPTY)
                {
                    return done;
                }
                int k = 0;
                while (k < 4 && done < len)
                {
                    Magic.store8(dst + done, (w >>> (8 * k)) & 0xFF);
                    done = done + 1;
                    k = k + 1;
                }
            }
        }
        catch (Throwable t)
        {
            return done;
        }
        return done;
    }

    /** {@return a short reason the source was rejected, for the boot report} 0 once it is usable. */
    public static int whyUnusable()
    {
        return why;
    }

    /** Print what the probe found. Called once from boot so a log says whether this board has entropy. */
    public static void report()
    {
        Uart.write(Magic.bytes("hw rng: "));
        if (available())
        {
            Uart.write(Magic.bytes("RNG200 at 0xFE104000, live\n"));
            return;
        }
        int w = whyUnusable();
        if (w == WHY_FAULT)
        {
            Uart.write(Magic.bytes("absent (no device mapped at 0xFE104000)\n"));
        }
        else if (w == WHY_DISABLED)
        {
            Uart.write(Magic.bytes("present but DISABLED (this driver does not write CTRL)\n"));
        }
        else if (w == WHY_EMPTY)
        {
            Uart.write(Magic.bytes("present but the FIFO never filled\n"));
        }
        else
        {
            Uart.write(Magic.bytes("present but STUCK (samples did not vary) -- refusing to trust it\n"));
        }
    }
}
