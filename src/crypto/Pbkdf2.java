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

/**
 * PBKDF2 (RFC 2898 / PKCS#5 v2.1), JDK-free.
 *
 * <p>Two entry points, and the split is deliberate rather than tidy:
 * <ul>
 * <li>{@link #deriveSha1} is the WPA2 path -- {@code PMK = PBKDF2(passphrase, ssid, 4096, 32)} -- and is
 *     left BYTE-FOR-BYTE alone over the static {@link Hmac#sha1}. It is the most hardware-validated code in
 *     this tree, collapsing it onto the generic path below buys WPA2 nothing, and the only gate for that
 *     change is a flash. {@code CryptoTest.pbkdf2GenericMatchesWpa2} measures the two as byte-identical
 *     instead, so the collapse is a change whose evidence already stands rather than a guess.
 * <li>{@link #derive} is generic over {@link Digest}'s algorithms and backs
 *     {@code javax.crypto.SecretKeyFactory}.
 * </ul>
 *
 * <p><b>ONE {@link Hmac} IS BUILT AND REUSED, and that is the whole reason the streaming HMAC exists.</b>
 * Every iteration re-MACs under the SAME key, so a fresh {@code Hmac} per iteration would re-derive the two
 * pads -- and re-HASH the key when it is longer than the block -- once per iteration. At PBKDF2's whole
 * point, a high iteration count, that is the dominant cost and it is pure waste: 4096 iterations means 4096
 * redundant key schedules. {@code reset()} replays the ipad and nothing else.
 */
public final class Pbkdf2
{
    private Pbkdf2()
    {
    }

    /** PBKDF2-HMAC-SHA1({@code pw}, {@code salt}, {@code iters}) → {@code dkLen} bytes into {@code out}. */
    public static void deriveSha1(byte[] pw, int pwLen, byte[] salt, int saltLen, int iters, byte[] out, int dkLen)
    {
        byte[] u = new byte[Sha1.DIGEST];
        byte[] t = new byte[Sha1.DIGEST];
        byte[] block = new byte[saltLen + 4];            // salt || INT_BE(blockIndex)
        int outPos = 0;
        int b = 1;
        while (outPos < dkLen)
        {
            int i = 0;
            while (i < saltLen)
            {
                block[i] = salt[i];
                i = i + 1;
            }
            block[saltLen] = (byte) (b >>> 24);
            block[saltLen + 1] = (byte) (b >>> 16);
            block[saltLen + 2] = (byte) (b >>> 8);
            block[saltLen + 3] = (byte) b;

            Hmac.sha1(pw, pwLen, block, saltLen + 4, u);  // U1
            i = 0;
            while (i < Sha1.DIGEST)
            {
                t[i] = u[i];
                i = i + 1;
            }
            int c = 1;
            while (c < iters)                             // U2..Uc, XOR-accumulated into T
            {
                Hmac.sha1(pw, pwLen, u, Sha1.DIGEST, u);
                i = 0;
                while (i < Sha1.DIGEST)
                {
                    t[i] = (byte) (t[i] ^ u[i]);
                    i = i + 1;
                }
                c = c + 1;
            }
            i = 0;
            while (i < Sha1.DIGEST && outPos < dkLen)
            {
                out[outPos] = t[i];
                outPos = outPos + 1;
                i = i + 1;
            }
            b = b + 1;
        }
    }

    /**
     * PBKDF2-HMAC-{@code algorithm}({@code pw}, {@code salt}, {@code iters}) -> {@code dkLen} bytes into
     * {@code out}.
     *
     * <p>{@code dkLen} is in BYTES. The stock {@code PBEKeySpec} counts BITS and truncates by integer
     * division -- MEASURED against the JDK, which answers a ZERO-LENGTH key for 7 bits and the 32-byte
     * answer's first 31 bytes for 255 -- so that conversion belongs to the overlay, not here.
     *
     * @param algorithm one of {@link Digest#MD5}, {@link Digest#SHA1}, {@link Digest#SHA224},
     *                  {@link Digest#SHA256}, {@link Digest#SHA384}, {@link Digest#SHA512}
     * @param pw        the password bytes; an EMPTY password is legal (RFC 2104 pads any key, including a
     *                  zero-length one, out to the block) and the JDK accepts it too
     * @param salt      the salt bytes
     * @param iters     the iteration count, at least 1
     * @throws IllegalArgumentException if a length or count is out of range
     */
    public static void derive(int algorithm, byte[] pw, int pwLen, byte[] salt, int saltLen,
            int iters, byte[] out, int dkLen)
    {
        if (pw == null || salt == null || out == null)
        {
            throw new IllegalArgumentException("null password, salt or output");
        }
        if (pwLen < 0 || pwLen > pw.length || saltLen < 0 || saltLen > salt.length)
        {
            throw new IllegalArgumentException("password or salt length out of bounds");
        }
        if (dkLen < 0 || dkLen > out.length)
        {
            throw new IllegalArgumentException("derived key length out of bounds");
        }
        if (iters < 1)
        {
            throw new IllegalArgumentException("iteration count must be at least 1");
        }

        // Built ONCE. Every doFinal() resets under the same key, so the pads are derived here and never
        // again -- see the class comment for why that is the point rather than an optimisation.
        Hmac mac = new Hmac(algorithm, pw, pwLen);
        int hLen = mac.macLength();
        byte[] u = new byte[hLen];
        byte[] t = new byte[hLen];

        int outPos = 0;
        int b = 1;
        while (outPos < dkLen)
        {
            // U1 = PRF(pw, salt || INT_BE(b)). The block index is fed as four bytes rather than staged into
            // a copy of the salt: the salt can be long, and copying it per block is the one allocation this
            // loop does not need.
            mac.update(salt, 0, saltLen);
            mac.update((byte) (b >>> 24));
            mac.update((byte) (b >>> 16));
            mac.update((byte) (b >>> 8));
            mac.update((byte) b);
            mac.doFinal(u, 0);

            int i = 0;
            while (i < hLen)
            {
                t[i] = u[i];
                i = i + 1;
            }

            int c = 1;
            while (c < iters)                             // U2..Uc, XOR-accumulated into T
            {
                mac.update(u, 0, hLen);
                mac.doFinal(u, 0);                        // reads u through the digest, then overwrites it
                i = 0;
                while (i < hLen)
                {
                    t[i] = (byte) (t[i] ^ u[i]);
                    i = i + 1;
                }
                c = c + 1;
            }

            i = 0;
            while (i < hLen && outPos < dkLen)
            {
                out[outPos] = t[i];
                outPos = outPos + 1;
                i = i + 1;
            }
            b = b + 1;
        }

        // T and U are the derived key and its last intermediate: both reproduce output nobody should be
        // able to read out of a freed block, for the reason SecureRandom's seed array is zeroed.
        int z = 0;
        while (z < hLen)
        {
            t[z] = 0;
            u[z] = 0;
            z = z + 1;
        }
    }
}
