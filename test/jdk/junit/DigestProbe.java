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
import java.security.DigestException;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * {@code java.security.MessageDigest} on the metal, over joe-ng's own {@code crypto.Digest}.
 *
 * <p>Every digest arm is a KNOWN-ANSWER vector, not a self-consistency check. A digest that is
 * self-consistent and wrong is the worst failure a hash function has: it is stable, it is the right length,
 * and nothing else in the world agrees with it. The vectors are the published NIST/RFC ones for the empty
 * message and for {@code "abc"}, so they are independent of this VM and of the JDK.
 *
 * <p>The arms that DISCRIMINATE, beyond mere presence:
 * <ul>
 * <li>SHA-224 and SHA-384 are not truncations of SHA-256/SHA-512 -- each has its own initial chaining
 *     value. An implementation that truncated would produce the right LENGTH and the wrong bytes, and only
 *     a known-answer vector catches that.
 * <li>{@code clone()} must DEEP-copy the engine: the arm finishes the clone and keeps feeding the original,
 *     and checks BOTH. A shallow clone -- which is what {@code Object.clone()} gives -- makes the two share
 *     one engine, and both answers come out wrong in a way that still looks like a digest.
 * <li>feeding one byte at a time must equal feeding the whole array, across the 64-byte block boundary and
 *     across the length-field boundary at 56. A streaming bug hides entirely at short lengths.
 * <li>a finished digest must RESET, so the same object hashes the next message correctly.
 * <li>alias and case handling: {@code "sha"}, {@code "SHA1"}, {@code "md5"} must all resolve, because the
 *     stock tests spell them that way.
 * </ul>
 */
public class DigestProbe
{
    private static int fails = 0;
    /** Arms where joe-ng deliberately differs from stock: counted SEPARATELY so both worlds read 0 above. */
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

    /**
     * An arm where joe-ng DELIBERATELY differs from a stock JVM. Printed with both answers and NOT counted
     * as a failure, so "failures=0" means the same thing in both worlds and a real regression still stands
     * out. Reporting these as FAIL on the host control would train a reader to ignore the count.
     */
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
        int i = 0;
        while (i < b.length)
        {
            int v = b[i] & 0xFF;
            s += "0123456789abcdef".charAt(v >>> 4);
            s += "0123456789abcdef".charAt(v & 0xF);
            i += 1;
        }
        return s;
    }

    private static byte[] abc()
    {
        return new byte[] { (byte) 'a', (byte) 'b', (byte) 'c' };
    }

    public static void main(String[] args) throws Exception
    {
        // ---- KNOWN-ANSWER vectors: the empty message ------------------------------------------------
        say("MD5     ()", hex(MessageDigest.getInstance("MD5").digest(new byte[0])),
                "d41d8cd98f00b204e9800998ecf8427e");
        say("SHA-1   ()", hex(MessageDigest.getInstance("SHA-1").digest(new byte[0])),
                "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        say("SHA-224 ()", hex(MessageDigest.getInstance("SHA-224").digest(new byte[0])),
                "d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f");
        say("SHA-256 ()", hex(MessageDigest.getInstance("SHA-256").digest(new byte[0])),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        say("SHA-384 ()", hex(MessageDigest.getInstance("SHA-384").digest(new byte[0])),
                "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b");
        say("SHA-512 ()", hex(MessageDigest.getInstance("SHA-512").digest(new byte[0])),
                "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                        + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e");

        // ---- KNOWN-ANSWER vectors: "abc" -- the FIPS 180-4 worked examples ---------------------------
        say("MD5     (abc)", hex(MessageDigest.getInstance("MD5").digest(abc())),
                "900150983cd24fb0d6963f7d28e17f72");
        say("SHA-1   (abc)", hex(MessageDigest.getInstance("SHA-1").digest(abc())),
                "a9993e364706816aba3e25717850c26c9cd0d89d");
        say("SHA-224 (abc)", hex(MessageDigest.getInstance("SHA-224").digest(abc())),
                "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7");
        say("SHA-256 (abc)", hex(MessageDigest.getInstance("SHA-256").digest(abc())),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        say("SHA-384 (abc)", hex(MessageDigest.getInstance("SHA-384").digest(abc())),
                "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7");
        say("SHA-512 (abc)", hex(MessageDigest.getInstance("SHA-512").digest(abc())),
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");

        // ---- lengths ---------------------------------------------------------------------------------
        say("MD5 len", Integer.valueOf(MessageDigest.getInstance("MD5").getDigestLength()), Integer.valueOf(16));
        say("SHA-1 len", Integer.valueOf(MessageDigest.getInstance("SHA-1").getDigestLength()), Integer.valueOf(20));
        say("SHA-224 len", Integer.valueOf(MessageDigest.getInstance("SHA-224").getDigestLength()), Integer.valueOf(28));
        say("SHA-256 len", Integer.valueOf(MessageDigest.getInstance("SHA-256").getDigestLength()), Integer.valueOf(32));
        say("SHA-384 len", Integer.valueOf(MessageDigest.getInstance("SHA-384").getDigestLength()), Integer.valueOf(48));
        say("SHA-512 len", Integer.valueOf(MessageDigest.getInstance("SHA-512").getDigestLength()), Integer.valueOf(64));

        // ---- aliases and case: the six spellings the stock tests use ---------------------------------
        // Each alias must RESOLVE (a digest of the right length comes back) while getAlgorithm() reports
        // the spelling that was ASKED FOR -- stock stores the requested string, so these two assertions
        // together are what say the alias table is used for lookup and nothing else.
        say("alias sha len", Integer.valueOf(MessageDigest.getInstance("sha").getDigestLength()), Integer.valueOf(20));
        say("alias SHA len", Integer.valueOf(MessageDigest.getInstance("SHA").getDigestLength()), Integer.valueOf(20));
        say("alias SHA1 len", Integer.valueOf(MessageDigest.getInstance("SHA1").getDigestLength()), Integer.valueOf(20));
        say("alias sha-1 len", Integer.valueOf(MessageDigest.getInstance("sha-1").getDigestLength()), Integer.valueOf(20));
        say("alias md5 len", Integer.valueOf(MessageDigest.getInstance("md5").getDigestLength()), Integer.valueOf(16));
        say("alias sha256 len", Integer.valueOf(MessageDigest.getInstance("sha256").getDigestLength()), Integer.valueOf(32));
        say("alias keeps spelling", MessageDigest.getInstance("sha").getAlgorithm(), "sha");
        // and an alias really is the same function, not a lookalike of the right length:
        say("alias sha == SHA-1", hex(MessageDigest.getInstance("sha").digest(abc())),
                "a9993e364706816aba3e25717850c26c9cd0d89d");

        String noAlg = "none";
        try
        {
            MessageDigest.getInstance("SHA3-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            noAlg = "NSAE";
        }
        // NOT implemented, and it says so rather than answering something plausible. A real JVM HAS
        // SHA-3, so this is a capability joe-ng lacks, not a behaviour it changed.
        diverge("SHA3-256 unsupported", noAlg, "NSAE", "a digest (the JDK implements SHA-3)");

        // ---- provider --------------------------------------------------------------------------------
        diverge("provider name", MessageDigest.getInstance("SHA-256").getProvider().getName(), "joe-ng", "SUN");

        // ---- STREAMING: byte-at-a-time must equal one shot, across both block boundaries -------------
        // 200 bytes spans several 64-byte blocks AND crosses 56 (where the length field starts), which is
        // where a padding bug lives. A short message hides both.
        byte[] data = new byte[200];
        int i = 0;
        while (i < data.length)
        {
            data[i] = (byte) (i * 31 + 7);
            i += 1;
        }
        MessageDigest oneShot = MessageDigest.getInstance("SHA-256");
        String want = hex(oneShot.digest(data));

        MessageDigest byteWise = MessageDigest.getInstance("SHA-256");
        i = 0;
        while (i < data.length)
        {
            byteWise.update(data[i]);
            i += 1;
        }
        say("bytewise == oneshot", hex(byteWise.digest()), want);

        MessageDigest ragged = MessageDigest.getInstance("SHA-256");
        ragged.update(data, 0, 1);
        ragged.update(data, 1, 54);
        ragged.update(data, 55, 10);
        ragged.update(data, 65, 135);
        say("ragged == oneshot", hex(ragged.digest()), want);

        // a finished digest must RESET, or the second message digests as the concatenation of both.
        MessageDigest reused = MessageDigest.getInstance("SHA-256");
        reused.update(data);
        reused.digest();
        reused.update(data);
        say("reuse after digest", hex(reused.digest()), want);

        // explicit reset() mid-message must discard what was fed.
        MessageDigest resetMid = MessageDigest.getInstance("SHA-256");
        resetMid.update((byte) 0x42);
        resetMid.reset();
        resetMid.update(data);
        say("reset mid-message", hex(resetMid.digest()), want);

        // ---- clone() must DEEP-copy: finish the clone, keep feeding the original ---------------------
        MessageDigest orig = MessageDigest.getInstance("SHA-256");
        orig.update(data, 0, 150);
        MessageDigest forked = (MessageDigest) orig.clone();
        orig.update(data, 150, 50);
        forked.update(data, 150, 50);
        say("clone: original", hex(orig.digest()), want);
        say("clone: fork", hex(forked.digest()), want);

        // ---- digest(byte[],int,int) ------------------------------------------------------------------
        MessageDigest into = MessageDigest.getInstance("SHA-256");
        into.update(data);
        byte[] out = new byte[40];
        int n = into.digest(out, 4, 32);
        say("digest(buf,off,len) n", Integer.valueOf(n), Integer.valueOf(32));
        byte[] slice = new byte[32];
        System.arraycopy(out, 4, slice, 0, 32);
        say("digest(buf,off,len) bytes", hex(slice), want);

        String tooShort = "none";
        try
        {
            MessageDigest.getInstance("SHA-256").digest(new byte[8], 0, 8);
        }
        catch (DigestException e)
        {
            tooShort = "DE";
        }
        say("digest into short buf", tooShort, "DE");

        // ---- ByteBuffer ------------------------------------------------------------------------------
        MessageDigest viaBuf = MessageDigest.getInstance("SHA-256");
        ByteBuffer bb = ByteBuffer.wrap(data);
        viaBuf.update(bb);
        say("update(ByteBuffer)", hex(viaBuf.digest()), want);
        say("ByteBuffer drained", Integer.valueOf(bb.remaining()), Integer.valueOf(0));

        // ---- argument sanity (what the stock ArgumentSanity test pins) -------------------------------
        MessageDigest md = MessageDigest.getInstance("md5");
        String r1 = "none";
        try
        {
            md.update(null, 5, 20);
        }
        catch (IllegalArgumentException e)
        {
            r1 = "IAE";
        }
        say("update(null,5,20)", r1, "IAE");

        String r2 = "none";
        try
        {
            md.update(new byte[15], 5, 20);
        }
        catch (IllegalArgumentException e)
        {
            r2 = "IAE";
        }
        say("update(short,5,20)", r2, "IAE");

        String r3 = "none";
        try
        {
            md.digest(null, 5, 20);
        }
        catch (IllegalArgumentException e)
        {
            r3 = "IAE";
        }
        say("digest(null,5,20)", r3, "IAE");

        String r4 = "none";
        try
        {
            md.digest(new byte[16], 5, 20);
        }
        catch (IllegalArgumentException e)
        {
            r4 = "IAE";
        }
        say("digest(short,5,20)", r4, "IAE");

        // ---- isEqual -------------------------------------------------------------------------------
        byte[] d1 = MessageDigest.getInstance("SHA-256").digest(abc());
        byte[] d2 = MessageDigest.getInstance("SHA-256").digest(abc());
        byte[] d3 = MessageDigest.getInstance("SHA-256").digest(new byte[] { (byte) 'a' });
        say("isEqual same", MessageDigest.isEqual(d1, d2), Boolean.TRUE);
        say("isEqual diff", MessageDigest.isEqual(d1, d3), Boolean.FALSE);
        say("isEqual null", MessageDigest.isEqual(d1, null), Boolean.FALSE);
        say("isEqual len", MessageDigest.isEqual(d1, new byte[0]), Boolean.FALSE);

        // ---- DigestInputStream / DigestOutputStream (STOCK classes over this MessageDigest) ----------
        MessageDigest inMd = MessageDigest.getInstance("SHA-256");
        DigestInputStream dis = new DigestInputStream(new ByteArrayInputStream(data), inMd);
        int read = 0;
        while (dis.read() != -1)
        {
            read += 1;
        }
        dis.close();
        say("DigestInputStream read", Integer.valueOf(read), Integer.valueOf(200));
        say("DigestInputStream digest", hex(inMd.digest()), want);

        MessageDigest outMd = MessageDigest.getInstance("SHA-256");
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DigestOutputStream dos = new DigestOutputStream(sink, outMd);
        dos.write(data, 0, 100);
        dos.write(data, 100, 100);
        dos.close();
        say("DigestOutputStream wrote", Integer.valueOf(sink.toByteArray().length), Integer.valueOf(200));
        say("DigestOutputStream digest", hex(outMd.digest()), want);

        // toString names the algorithm and the provider rather than being Object's. The provider name is
        // the only part that differs from stock.
        diverge("toString", MessageDigest.getInstance("SHA-256").toString(),
                "SHA-256 Message Digest from joe-ng, <initialized>",
                "SHA-256 Message Digest from SUN, <initialized>");

        // On a stock JVM the three divergence arms are unmet BY CONSTRUCTION (that is what they record);
        // on joe-ng all three must be met. `failures` must be 0 in BOTH worlds.
        System.out.println("DigestProbe done, failures=" + fails + " divergences-unmet=" + unmet);
    }
}
