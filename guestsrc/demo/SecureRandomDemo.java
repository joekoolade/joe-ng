/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */
package demo;

import java.security.SecureRandom;
import magic.Magic;

/**
 * {@code java.security.SecureRandom} in the boot suite, so the DRBG is PI-GATED -- and so the board reports
 * whether it has a hardware entropy source.
 *
 * <p>Two things it pins, both of which are invisible any other way:
 * <ul>
 * <li>The stream is a KNOWN ANSWER. A DRBG's output always looks correct, so the only check worth having is
 *     that these exact bytes are what a JDK produces for this seed. {@code crypto.CryptoTest} does the wide
 *     version (400 comparisons); this is the one line the suite can afford to print.
 * <li>An UNSEEDED generator REFUSES. That is the safety property of the whole class: joe-ng has no
 *     validated entropy source, and a generator that quietly seeded itself from a clock would pass every
 *     other arm here while producing a predictable stream.
 * </ul>
 *
 * <p>The last section is a MEASUREMENT the emulator cannot make. The BCM2711 carries a hardware RNG at
 * {@code 0xFE104000}; under QEMU every register in that window faults while PM and GPIO beside it read
 * fine, so it is measurably absent there. What a real Pi answers decides whether {@code SecureRandom} can
 * ever self-seed here. The reads are GUARDED and READ-ONLY on purpose: a fault is recoverable and reports
 * cleanly, but a STORE to an unmapped peripheral aborts in a way this VM does not recover from, which
 * would take the boot with it.
 */
public final class SecureRandomDemo
{
    private static String hex(byte[] b)
    {
        String s = "";
        for (int i = 0; i < b.length; i++)
        {
            int v = b[i] & 0xFF;
            s = s + "0123456789abcdef".charAt(v >>> 4) + "0123456789abcdef".charAt(v & 0xF);
        }
        return s;
    }

    private static void arm(String what, String got, String want)
    {
        System.out.println("  " + what + " = " + got + (got.equals(want) ? "" : " (want " + want + ")"));
    }

    /** One guarded MMIO read; "FAULT" and a value must stay distinguishable. */
    private static String peek(long addr)
    {
        try
        {
            return Integer.toHexString(Magic.load32(addr));
        }
        catch (Throwable t)
        {
            return "FAULT";
        }
    }

    public static void main(String[] args) throws Exception
    {
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        sr.setSeed(new byte[] { 1, 2, 3, 4 });
        byte[] b = new byte[24];
        sr.nextBytes(b);
        arm("sha1prng seeded", hex(b), "f24d7b797432a7aaf05c29e032faa297277a14f8a8c7b477");

        // RAGGED: 8+8 must come out of ONE 20-byte block, i.e. the remainder is carried.
        SecureRandom r1 = SecureRandom.getInstance("SHA1PRNG");
        r1.setSeed(new byte[] { 1, 2, 3, 4 });
        byte[] p1 = new byte[8];
        byte[] p2 = new byte[8];
        r1.nextBytes(p1);
        r1.nextBytes(p2);
        arm("sha1prng ragged", hex(p1) + hex(p2), "f24d7b797432a7aaf05c29e032faa297");

        // nextInt must come from the DRBG, not from Random's linear congruential generator.
        SecureRandom r2 = SecureRandom.getInstance("SHA1PRNG");
        r2.setSeed(new byte[] { 1, 2, 3, 4 });
        arm("sha1prng nextInt", Integer.toString(r2.nextInt()), "-229803143");

        String unseeded = "PRODUCED BYTES";
        try
        {
            new SecureRandom().nextBytes(new byte[8]);
        }
        catch (Error e)
        {
            unseeded = "refused (no entropy source)";
        }
        arm("unseeded", unseeded, "refused (no entropy source)");

        System.out.println("  hw RNG @0xFE104000: CTRL=" + peek(0xFE10_4000L)
                + " STATUS=" + peek(0xFE10_4004L)
                + " bcm2835DATA=" + peek(0xFE10_4008L)
                + " rng200FIFOCNT=" + peek(0xFE10_4024L));

        // THE CONCLUSIVE MEASUREMENT, and the reason it is separate from the register dump above.
        // A register that DECODES plausibly as a FIFO count is not the same thing as a live entropy
        // source. On a Pi 4 the dump reads CTRL=0x7fff (RNG200's RBGEN field fully set) and
        // FIFO_COUNT=0x40001010 (count 16, threshold 16), which is coherent under the RNG200 layout and
        // incoherent under the BCM2835 one -- but coherent is not the same as alive.
        //
        // What would settle it: successive FIFO_DATA words that DIFFER, while FIFO_COUNT DECREMENTS. A
        // constant value, or a count that never moves, means the decode above is a coincidence and there
        // is no usable source here.
        //
        // Gated on a non-zero count and READ-ONLY: draining an empty RNG200 FIFO is not defined to return
        // anything meaningful, and this VM must never WRITE to a peripheral window whose layout it has not
        // yet confirmed -- a store fault here is not recoverable.
        String cnt = peek(0xFE10_4024L);
        if (cnt.equals("FAULT"))
        {
            System.out.println("  hw RNG fifo: absent (no device mapped)");
        }
        else
        {
            int before = Magic.load32(0xFE10_4024L) & 0xFF;
            if (before == 0)
            {
                System.out.println("  hw RNG fifo: EMPTY (count=0) -- nothing to draw, would need a CTRL write");
            }
            else
            {
                String w0 = peek(0xFE10_4020L);
                String w1 = peek(0xFE10_4020L);
                String w2 = peek(0xFE10_4020L);
                int after = Magic.load32(0xFE10_4024L) & 0xFF;
                boolean vary = !w0.equals(w1) && !w1.equals(w2) && !w0.equals(w2);
                System.out.println("  hw RNG fifo: count " + before + " -> " + after
                        + "  words " + w0 + " " + w1 + " " + w2
                        + "  (distinct=" + vary + ", drained=" + (before > after) + ")");
            }
        }
    }
}
