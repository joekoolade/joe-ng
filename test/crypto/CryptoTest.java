/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-05
 */
package crypto;

import harness.T;

import java.nio.charset.StandardCharsets;

/**
 * Validates the JDK-free {@link crypto} primitives (SHA-1 so far; HMAC/PBKDF2/PRF/AES to follow) on the seed
 * JVM against published test vectors — the same code compiles into the image for the on-metal WPA2 supplicant,
 * so agreement here means the crypto is correct before it ever runs on the metal.
 *
 * <p>Run: {@code java crypto.CryptoTest}
 */
public final class CryptoTest
{
    public static void main(String[] args)
    {
        // SHA-1 (FIPS 180-1 examples + the RFC 3174 boundary case).
        sha1("", "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        sha1("abc", "a9993e364706816aba3e25717850c26c9cd0d89d");
        sha1("The quick brown fox jumps over the lazy dog", "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12");
        sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",   // 56 bytes -> two blocks
                "84983e441c3bd26ebaae4aa1f95129e5e54670f1");

        // HMAC-SHA1 (RFC 2202).
        hmac(rep((byte) 0x0b, 20), "Hi There", "b617318655057264e28bc0b6fb378c8ef146be00");
        hmac(ascii("Jefe"), "what do ya want for nothing?", "effcdf6ae5eb2fa2d27416d5f184df9c259a7c79");

        // PBKDF2-HMAC-SHA1 (RFC 6070).
        pbkdf2("password", "salt", 1, 20, "0c60c80f961f0e71f3a9b524af6012062fe037a6");
        pbkdf2("password", "salt", 2, 20, "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957");
        pbkdf2("password", "salt", 4096, 20, "4b007901b765489abead49d926f721d065a429c1");

        // WPA2 PMK = PBKDF2(passphrase, ssid, 4096, 32) — IEEE 802.11i test vector.
        pbkdf2("password", "IEEE", 4096, 32,
                "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e");

        // PRF self-consistency: each 20-byte block must equal HMAC-SHA1(K, A || 0x00 || B || i). HMAC is
        // already RFC-validated above, so this confirms the PRF's input construction + counter across blocks
        // (the full PTK is ultimately proven by the on-metal 4-way handshake).
        prfConsistency();

        // AES-128 (FIPS-197 C.1), both directions.
        aesEnc("000102030405060708090a0b0c0d0e0f", "00112233445566778899aabbccddeeff",
                "69c4e0d86a7b0430d8cdb78070b4c55a");
        aesDec("000102030405060708090a0b0c0d0e0f", "69c4e0d86a7b0430d8cdb78070b4c55a",
                "00112233445566778899aabbccddeeff");

        // AES Key Unwrap (RFC 3394, 128-bit KEK + 128-bit key) — the GTK path.
        keyUnwrap("000102030405060708090a0b0c0d0e0f",
                "1fa68b0a8112b447aef34bd8fb5a7b829d3e862371d2cfe5", "00112233445566778899aabbccddeeff");

        // Streaming digests (crypto.Digest), which back java.security.MessageDigest.
        digestVectors();
        digestAgainstJdk();

        // The SHA1PRNG DRBG behind java.security.SecureRandom.
        prngAgainstJdk();

        T.summary("crypto");
    }

    /**
     * Known-answer vectors for every algorithm {@link Digest} implements -- the published NIST/RFC digests of
     * the empty message and of {@code "abc"}, so they are independent of both this VM and the JDK.
     *
     * <p>These matter beyond the cross-check below because a WRONG IV is self-consistent: SHA-224 that
     * truncated SHA-256 rather than starting from its own chaining value would produce a stable digest of
     * exactly the right length that nothing else in the world agrees with.
     */
    private static void digestVectors()
    {
        dig(Digest.MD5, "", "d41d8cd98f00b204e9800998ecf8427e");
        dig(Digest.MD5, "abc", "900150983cd24fb0d6963f7d28e17f72");
        dig(Digest.SHA1, "", "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        dig(Digest.SHA1, "abc", "a9993e364706816aba3e25717850c26c9cd0d89d");
        dig(Digest.SHA224, "", "d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f");
        dig(Digest.SHA224, "abc", "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7");
        dig(Digest.SHA256, "", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        dig(Digest.SHA256, "abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        dig(Digest.SHA384, "",
                "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b");
        dig(Digest.SHA384, "abc",
                "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7");
        dig(Digest.SHA512, "",
                "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
                        + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e");
        dig(Digest.SHA512, "abc",
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
    }

    /**
     * Every algorithm, against the JDK's OWN {@code MessageDigest}, over 22 message lengths x five feeding
     * patterns -- 756 byte-for-byte comparisons, and no boot needed.
     *
     * <p>The LENGTHS are the point: 55/56/57, 63/64/65 and 111/112/113, 127/128/129 straddle both block
     * sizes AND the offset where the length field starts, which is where a padding bug lives and where a
     * short message cannot see one. The FEEDING PATTERNS are the other point: one shot, byte at a time,
     * ragged chunks, reuse after {@code digest()}, and {@code copy()} -- a streaming bug that a one-shot
     * arm cannot reach is still a wrong answer for {@code DigestInputStream}.
     *
     * <p>This is what caught MD5's length field: RFC 1321 appends the bit count LOW-ORDER BYTE FIRST while
     * the SHA family is big-endian. Written big-endian for all six, MD5 agreed on the EMPTY message -- where
     * the count is zero and byte order cannot show -- and disagreed on all 21 other lengths.
     */
    private static void digestAgainstJdk()
    {
        String[] names = { "MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512" };
        int[] lens = { 0, 1, 2, 55, 56, 57, 63, 64, 65, 110, 111, 112, 113, 119, 120, 127, 128, 129, 200,
                1000, 4096, 6706 };
        java.util.Random rnd = new java.util.Random(20260921L);
        int compared = 0;
        int bad = 0;
        for (int alg = 0; alg < names.length; alg++)
        {
            java.security.MessageDigest jdk;
            try
            {
                jdk = java.security.MessageDigest.getInstance(names[alg]);
            }
            catch (java.security.NoSuchAlgorithmException e)
            {
                throw new RuntimeException(e);
            }
            T.eq("digest length " + names[alg], jdk.getDigestLength(), Digest.lengthOf(alg));
            for (int li = 0; li < lens.length; li++)
            {
                int len = lens[li];
                byte[] data = new byte[len];
                rnd.nextBytes(data);
                byte[] want = jdk.digest(data);

                Digest oneShot = new Digest(alg);
                oneShot.update(data, 0, len);
                compared += 1;
                bad += same(want, oneShot.digest()) ? 0 : 1;

                Digest byteWise = new Digest(alg);
                for (int i = 0; i < len; i++)
                {
                    byteWise.update(data[i]);
                }
                compared += 1;
                bad += same(want, byteWise.digest()) ? 0 : 1;

                Digest ragged = new Digest(alg);
                int p = 0;
                while (p < len)
                {
                    int n = 1 + rnd.nextInt(70);
                    if (n > len - p)
                    {
                        n = len - p;
                    }
                    ragged.update(data, p, n);
                    p += n;
                }
                compared += 1;
                bad += same(want, ragged.digest()) ? 0 : 1;

                // digest() must RESET, or a reused object hashes the CONCATENATION of both messages.
                Digest reused = new Digest(alg);
                reused.update(data, 0, len);
                reused.digest();
                reused.update(data, 0, len);
                compared += 1;
                bad += same(want, reused.digest()) ? 0 : 1;

                if (len > 4)
                {
                    // copy() must DEEP-copy: finish the fork, keep feeding the original, check BOTH.
                    Digest orig = new Digest(alg);
                    orig.update(data, 0, len - 3);
                    Digest fork = orig.copy();
                    orig.update(data, len - 3, 3);
                    fork.update(data, len - 3, 3);
                    compared += 2;
                    bad += same(want, orig.digest()) ? 0 : 1;
                    bad += same(want, fork.digest()) ? 0 : 1;
                }
            }
        }
        T.eq("digest cross-check comparisons", 756, compared);
        T.eq("digest cross-check failures", 0, bad);
    }

    /**
     * {@link Sha1Prng} against the JDK's OWN {@code SecureRandom.getInstance("SHA1PRNG")}, byte for byte.
     *
     * <p>THIS IS THE ONLY GATE A DRBG CAN HAVE HERE, and it is worth stating why. Every output of a random
     * generator looks equally correct, so a subtly wrong one is invisible to inspection, to a demo, and to
     * any statistical test -- the stream is still random, it is just not the RIGHT random. SHA1PRNG is
     * deterministic from {@code setSeed}, so the JDK can serve as a known-answer oracle; nothing else
     * available to this project can.
     *
     * <p>The draw lengths are RAGGED on purpose. SHA1PRNG generates in 20-byte blocks and carries the
     * unused tail into the next call, so a 5-byte draw followed by a 3-byte draw must come out of ONE
     * block. An implementation that discarded the remainder passes any whole-block test and disagrees with
     * every other SHA1PRNG in the world. The mid-stream reseed is there for the same reason: stock
     * SUPPLEMENTS the state rather than replacing it, and one draw cannot tell those apart.
     */
    private static void prngAgainstJdk()
    {
        java.util.Random rnd = new java.util.Random(31337L);
        int compared = 0;
        int bad = 0;
        for (int trial = 0; trial < 40; trial++)
        {
            byte[] seed = new byte[1 + rnd.nextInt(40)];
            rnd.nextBytes(seed);
            java.security.SecureRandom jdk;
            try
            {
                jdk = java.security.SecureRandom.getInstance("SHA1PRNG");
            }
            catch (java.security.NoSuchAlgorithmException e)
            {
                throw new RuntimeException(e);
            }
            jdk.setSeed(seed);
            Sha1Prng ours = new Sha1Prng(seed, seed.length);

            for (int k = 0; k < 8; k++)
            {
                int n = 1 + rnd.nextInt(50);
                byte[] want = new byte[n];
                byte[] got = new byte[n];
                jdk.nextBytes(want);
                ours.nextBytes(got, 0, n);
                compared += 1;
                bad += same(want, got) ? 0 : 1;
            }

            // setSeed mid-stream SUPPLEMENTS; it must not reset.
            byte[] more = new byte[9];
            rnd.nextBytes(more);
            jdk.setSeed(more);
            ours.setSeed(more, more.length);
            byte[] want = new byte[32];
            byte[] got = new byte[32];
            jdk.nextBytes(want);
            ours.nextBytes(got, 0, 32);
            compared += 1;
            bad += same(want, got) ? 0 : 1;

            // An offset write must touch ONLY its own range -- nextBytes(out, off, len) is joe-ng's
            // spelling, and a fencepost there would corrupt a caller's buffer rather than its own output.
            byte[] canvas = new byte[40];
            for (int i = 0; i < canvas.length; i++)
            {
                canvas[i] = (byte) 0xAA;
            }
            java.security.SecureRandom ref;
            try
            {
                ref = java.security.SecureRandom.getInstance("SHA1PRNG");
            }
            catch (java.security.NoSuchAlgorithmException e)
            {
                throw new RuntimeException(e);
            }
            ref.setSeed(seed);
            byte[] refBytes = new byte[16];
            ref.nextBytes(refBytes);
            new Sha1Prng(seed, seed.length).nextBytes(canvas, 12, 16);
            boolean ok = true;
            for (int i = 0; i < 12; i++)
            {
                ok = ok && canvas[i] == (byte) 0xAA;
            }
            for (int i = 28; i < 40; i++)
            {
                ok = ok && canvas[i] == (byte) 0xAA;
            }
            for (int i = 0; i < 16; i++)
            {
                ok = ok && canvas[12 + i] == refBytes[i];
            }
            compared += 1;
            bad += ok ? 0 : 1;
        }
        T.eq("prng cross-check comparisons", 400, compared);
        T.eq("prng cross-check failures", 0, bad);
    }

    private static boolean same(byte[] a, byte[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i])
            {
                return false;
            }
        }
        return true;
    }

    /** One known-answer vector: {@code algorithm} of {@code msg}'s ASCII bytes must be {@code expect}. */
    private static void dig(int algorithm, String msg, String expect)
    {
        Digest d = new Digest(algorithm);
        byte[] in = ascii(msg);
        d.update(in, 0, in.length);
        byte[] got = d.digest();
        T.eqStr("Digest alg" + algorithm + "(\"" + msg + "\")", expect, hex(got, got.length));
    }

    private static void aesEnc(String keyHex, String ptHex, String expect)
    {
        int[] w = new int[44];
        Aes.expandKey(bytes(keyHex), w);
        byte[] out = new byte[16];
        Aes.encryptBlock(w, bytes(ptHex), 0, out, 0);
        T.eqStr("aes-enc", expect, hex(out, 16));
    }

    private static void aesDec(String keyHex, String ctHex, String expect)
    {
        int[] w = new int[44];
        Aes.expandKey(bytes(keyHex), w);
        byte[] out = new byte[16];
        Aes.decryptBlock(w, bytes(ctHex), 0, out, 0);
        T.eqStr("aes-dec", expect, hex(out, 16));
    }

    private static void keyUnwrap(String kekHex, String wrappedHex, String expect)
    {
        byte[] wrapped = bytes(wrappedHex);
        byte[] out = new byte[wrapped.length - 8];
        boolean ok = KeyWrap.unwrap(bytes(kekHex), wrapped, wrapped.length, out);
        T.check("keyunwrap-iv", ok);
        T.eqStr("keyunwrap", expect, hex(out, out.length));
    }

    private static byte[] bytes(String hex)
    {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++)
        {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static void prfConsistency()
    {
        byte[] key = rep((byte) 0x0b, 32);
        byte[] label = ascii("Pairwise key expansion");
        byte[] data = ascii("some 22-byte nonce-ish");
        byte[] prf = new byte[48];
        Prf.sha1(key, key.length, label, label.length, data, data.length, prf, 48);

        // rebuild the expected first three blocks independently from the tested Hmac
        byte[] in = new byte[label.length + 1 + data.length + 1];
        System.arraycopy(label, 0, in, 0, label.length);
        in[label.length] = 0;
        System.arraycopy(data, 0, in, label.length + 1, data.length);
        for (int blk = 0; blk < 3; blk++)
        {
            in[in.length - 1] = (byte) blk;
            byte[] mac = new byte[Sha1.DIGEST];
            Hmac.sha1(key, key.length, in, in.length, mac);
            int n = Math.min(20, 48 - blk * 20);
            T.eqStr("prf block " + blk, hex(mac, n), hexSlice(prf, blk * 20, n));
        }
    }

    private static String hexSlice(byte[] b, int off, int len)
    {
        byte[] s = new byte[len];
        System.arraycopy(b, off, s, 0, len);
        return hex(s, len);
    }

    private static void hmac(byte[] key, String msg, String expect)
    {
        byte[] m = ascii(msg);
        byte[] out = new byte[Sha1.DIGEST];
        Hmac.sha1(key, key.length, m, m.length, out);
        String label = msg.length() > 12 ? msg.substring(0, 12) + "..." : msg;
        T.eqStr("hmac(\"" + label + "\")", expect, hex(out, out.length));
    }

    private static void pbkdf2(String pw, String salt, int iters, int dkLen, String expect)
    {
        byte[] p = ascii(pw);
        byte[] s = ascii(salt);
        byte[] out = new byte[dkLen];
        Pbkdf2.deriveSha1(p, p.length, s, s.length, iters, out, dkLen);
        T.eqStr("pbkdf2(\"" + salt + "\"," + iters + ")", expect, hex(out, dkLen));
    }

    private static byte[] ascii(String s)
    {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] rep(byte v, int n)
    {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
        {
            b[i] = v;
        }
        return b;
    }

    private static void sha1(String msg, String expect)
    {
        byte[] m = msg.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[Sha1.DIGEST];
        Sha1.hash(m, m.length, out);
        String label = msg.length() > 12 ? msg.substring(0, 12) + "..." : msg;
        T.eqStr("sha1(\"" + label + "\")", expect, hex(out, out.length));
    }

    static String hex(byte[] b, int len)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++)
        {
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
