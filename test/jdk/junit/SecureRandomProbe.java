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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import magic.Magic;

/**
 * {@code java.security.SecureRandom} on the metal, over joe-ng's own {@code crypto.Sha1Prng}.
 *
 * <p>A DRBG is the hardest thing in this VM to test, because EVERY output looks equally correct: a subtly
 * wrong generator is not detectable by inspection, by a statistical test, or by any demo. The only gate
 * that means anything is a KNOWN ANSWER, and the one available here is that SHA1PRNG is DETERMINISTIC from
 * {@code setSeed} -- so running this same source on a host JVM produces the JDK's own stream, and every
 * arm below must match it byte for byte.
 *
 * <p>The arms that DISCRIMINATE rather than merely exercise:
 * <ul>
 * <li>RAGGED draw lengths. A 5-byte request consumes 5 bytes of a 20-byte SHA-1 block and the next request
 *     must continue at byte 5, not generate a fresh block. An implementation that discarded the remainder
 *     produces a perfectly random-looking stream that disagrees with every other SHA1PRNG in the world.
 * <li>{@code setSeed} MID-STREAM. Stock SUPPLEMENTS the state; an implementation that RESET it would let a
 *     caller destroy accumulated entropy, and the two are indistinguishable from one draw.
 * <li>{@code nextInt}/{@code nextLong}/{@code nextBoolean}, which come from {@code java.util.Random} and
 *     are only secure because {@code next(int)} is overridden. Un-overridden they still return plausible
 *     numbers -- from a linear congruential generator. That is the silent downgrade these arms catch.
 * <li>The UNSEEDED arms, which must FAIL on joe-ng. A generator that seeded itself from a clock would pass
 *     every other arm here.
 * </ul>
 */
public class SecureRandomProbe
{
    private static int fails = 0;
    private static int unmet = 0;

    private static void say(String what, Object got, Object want)
    {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok)
        {
            fails += 1;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + what + " = " + got + " (want " + want + ")");
    }

    /** An arm where joe-ng deliberately differs from stock; counted apart so `failures` reads 0 in both. */
    private static void diverge(String what, Object got, Object wantHere, String onStock)
    {
        boolean ok = got == null ? wantHere == null : got.equals(wantHere);
        if (!ok)
        {
            unmet += 1;
        }
        System.out.println((ok ? "ok*  " : "diff ") + what + " = " + got + " (want here " + wantHere
                + "; a stock JVM says " + onStock + ")");
    }

    private static String hex(byte[] b)
    {
        String s = "";
        for (int i = 0; i < b.length; i++)
        {
            int v = b[i] & 0xFF;
            s += "0123456789abcdef".charAt(v >>> 4);
            s += "0123456789abcdef".charAt(v & 0xF);
        }
        return s;
    }

    private static SecureRandom seeded() throws Exception
    {
        SecureRandom sr = SecureRandom.getInstance("SHA1PRNG");
        sr.setSeed(new byte[] { 1, 2, 3, 4 });
        return sr;
    }

    public static void main(String[] args) throws Exception
    {
        // ---- the deterministic stream: must equal the JDK's, byte for byte ---------------------------
        byte[] b = new byte[24];
        seeded().nextBytes(b);
        say("seed{1,2,3,4} x24", hex(b), "f24d7b797432a7aaf05c29e032faa297277a14f8a8c7b477");

        // RAGGED: two 8-byte draws must be the SAME BYTES as one 16-byte draw -- i.e. the remainder of the
        // first 20-byte block is carried, not thrown away.
        SecureRandom r1 = seeded();
        byte[] p1 = new byte[8];
        byte[] p2 = new byte[8];
        r1.nextBytes(p1);
        r1.nextBytes(p2);
        SecureRandom r2 = seeded();
        byte[] whole = new byte[16];
        r2.nextBytes(whole);
        say("ragged 8+8 == 16", hex(p1) + hex(p2), hex(whole));

        // and a draw that straddles the 20-byte block boundary
        SecureRandom r3 = seeded();
        byte[] q = new byte[7];
        String acc = "";
        for (int i = 0; i < 6; i++)
        {
            r3.nextBytes(q);
            acc += hex(q);
        }
        say("six 7-byte draws", acc, "f24d7b797432a7aaf05c29e032faa297277a14f8a8c7b477"
                + "eff89d2215a1d41c88e244ef9d8a617b3519");

        // mid-stream reseed SUPPLEMENTS
        SecureRandom r4 = seeded();
        byte[] junk = new byte[10];
        r4.nextBytes(junk);
        r4.setSeed(new byte[] { 9, 9 });
        byte[] after = new byte[16];
        r4.nextBytes(after);
        say("reseed mid-stream", hex(after), "93d92fd98fc8f32a813790928762797f");

        // setSeed(long) is the eight big-endian bytes
        SecureRandom r5 = SecureRandom.getInstance("SHA1PRNG");
        r5.setSeed(0x0102030405060708L);
        byte[] l = new byte[16];
        r5.nextBytes(l);
        // The pairing IS the assertion: stock spreads a long LOW BYTE FIRST, so the equivalent array is
        // REVERSED. Comparing against the ascending array instead would pass only for a big-endian
        // implementation -- and both orders produce a perfectly good stream, so nothing else can tell them
        // apart. (This arm is what caught the overlay writing it big-endian.)
        SecureRandom r6 = SecureRandom.getInstance("SHA1PRNG");
        r6.setSeed(new byte[] { 8, 7, 6, 5, 4, 3, 2, 1 });
        byte[] l2 = new byte[16];
        r6.nextBytes(l2);
        say("setSeed(long) is little-endian", hex(l), hex(l2));

        SecureRandom r7 = SecureRandom.getInstance("SHA1PRNG");
        r7.setSeed(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        byte[] l3 = new byte[16];
        r7.nextBytes(l3);
        say("...and NOT big-endian", Boolean.valueOf(hex(l).equals(hex(l3))), Boolean.FALSE);

        // ---- the java.util.Random surface must come from THIS generator, not from an LCG -------------
        SecureRandom n1 = seeded();
        SecureRandom n2 = seeded();
        say("nextInt deterministic", Integer.valueOf(n1.nextInt()), Integer.valueOf(n2.nextInt()));
        SecureRandom n3 = seeded();
        say("nextInt from the DRBG", Integer.valueOf(n3.nextInt()), Integer.valueOf(0xf24d7b79));
        SecureRandom n4 = seeded();
        say("nextLong from the DRBG", Long.valueOf(n4.nextLong()), Long.valueOf(0xf24d7b797432a7aaL));
        SecureRandom n5 = seeded();
        say("nextBoolean", n5.nextBoolean(), Boolean.TRUE);
        SecureRandom n6 = seeded();
        say("nextInt(100)", Integer.valueOf(n6.nextInt(100)), Integer.valueOf(76));

        // ---- a constructed-with-seed instance ------------------------------------------------------
        // NOT a say(): stock's `new SecureRandom(byte[])` uses the PLATFORM DEFAULT algorithm (NativePRNG
        // on macOS), so comparing its bytes with joe-ng's would be comparing two different algorithms and
        // would "fail" for a reason that is not a defect. joe-ng has one algorithm, so here it is SHA1PRNG.
        SecureRandom c1 = new SecureRandom(new byte[] { 1, 2, 3, 4 });
        byte[] cb = new byte[24];
        c1.nextBytes(cb);
        diverge("new SecureRandom(seed)", hex(cb),
                "f24d7b797432a7aaf05c29e032faa297277a14f8a8c7b477", "its platform default, not SHA1PRNG");

        // ---- algorithm + lookup ---------------------------------------------------------------------
        say("getAlgorithm", SecureRandom.getInstance("SHA1PRNG").getAlgorithm(), "SHA1PRNG");
        say("case-insensitive", SecureRandom.getInstance("sha1prng").getAlgorithm(), "sha1prng");
        String noAlg = "none";
        try
        {
            SecureRandom.getInstance("NativePRNG");
        }
        catch (NoSuchAlgorithmException e)
        {
            noAlg = "NSAE";
        }
        diverge("NativePRNG", noAlg, "NSAE", "an instance on most platforms");

        // ---- THE SAFETY PROPERTY, and it now depends on the BOARD rather than on joe-ng-vs-stock -----
        //
        // With a hardware RNG present these three succeed; without one (QEMU) all three refuse. Neither
        // outcome is a failure, so none of them is asserted against a fixed expectation -- what IS asserted
        // is that the three AGREE. A VM where getInstanceStrong hands back a generator that then refuses to
        // produce bytes is incoherent whichever board it is running on, and that is a bug no single arm
        // could see.
        boolean seeds = true;
        try
        {
            new SecureRandom().nextBytes(new byte[8]);
        }
        catch (Error e)
        {
            seeds = false;
        }

        boolean gen = true;
        try
        {
            SecureRandom.getInstance("SHA1PRNG").generateSeed(8);
        }
        catch (Error e)
        {
            gen = false;
        }

        boolean strong = true;
        try
        {
            SecureRandom.getInstanceStrong();
        }
        catch (NoSuchAlgorithmException e)
        {
            strong = false;
        }

        System.out.println("     board entropy: nextBytes=" + seeds + " generateSeed=" + gen
                + " getInstanceStrong=" + strong);
        say("entropy answers agree", Boolean.valueOf(seeds == gen && gen == strong), Boolean.TRUE);

        // And where it DOES self-seed, two fresh generators must differ. A stuck source would seed every
        // instance identically and still emit a perfectly random-looking stream -- the one failure that no
        // amount of looking at the output can catch.
        if (seeds)
        {
            byte[] one = new byte[16];
            byte[] two = new byte[16];
            new SecureRandom().nextBytes(one);
            new SecureRandom().nextBytes(two);
            say("two fresh generators differ", Boolean.valueOf(!hex(one).equals(hex(two))), Boolean.TRUE);
        }
        else
        {
            System.out.println("ok   two fresh generators differ = skipped (this board has no source)");
        }

        diverge("provider", SecureRandom.getInstance("SHA1PRNG").getProvider().getName(), "joe-ng", "SUN");

        // ---- is there a hardware entropy source on THIS board? ---------------------------------------
        // READ ONLY, and every read guarded. On QEMU every register in this window faults while PM/GPIO
        // beside it read fine, so "absent" here is measured, not assumed. A WRITE is deliberately not
        // attempted: a store to an unmapped peripheral aborts in a way this VM does NOT recover from,
        // which would take the whole run with it.
        System.out.println("BCM2711 hardware RNG @0xFE104000 (read-only probe):");
        long rng = 0xFE10_4000L;
        System.out.println("  CTRL      +0x00 = " + peek(rng + 0x00));
        System.out.println("  STATUS    +0x04 = " + peek(rng + 0x04));
        System.out.println("  bcm2835 DATA  +0x08 = " + peek(rng + 0x08));
        System.out.println("  rng200 BITCNT +0x0C = " + peek(rng + 0x0C));
        System.out.println("  rng200 FIFOCNT+0x24 = " + peek(rng + 0x24));
        System.out.println("  rng200 FIFO   +0x20 = " + peek(rng + 0x20));

        System.out.println("SecureRandomProbe done, failures=" + fails + " divergences-unmet=" + unmet);
    }

    /** One guarded MMIO read: "FAULT" and a value are different answers and must stay distinguishable. */
    private static String peek(long addr)
    {
        try
        {
            int v = Magic.load32(addr);
            return Integer.toHexString(v);
        }
        catch (Throwable t)
        {
            return "FAULT (no device mapped there)";
        }
    }
}
