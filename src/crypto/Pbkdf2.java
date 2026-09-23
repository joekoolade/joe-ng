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
 * <p>ONE derivation, generic over {@link Digest}'s algorithms. It backs {@code javax.crypto.SecretKeyFactory}
 * AND the WPA2 PMK -- {@code PBKDF2(passphrase, ssid, 4096, 32)} -- which until this class was collapsed had
 * its own one-shot copy over the static {@link Hmac#sha1}.
 *
 * <p><b>THE COLLAPSE WAS NOT MADE ON THE GROUNDS THAT THE TWO LOOK EQUIVALENT.</b> Before it, 84 key/salt/
 * length combinations measured the two paths as byte-identical, so the change had its evidence standing
 * before it was written. That comparison is necessarily GONE now -- with one implementation left there is
 * nothing to compare it to, and a test that compares the survivor to itself passes for ever and means
 * nothing. What replaced it is stronger and different in kind: the WPA2 shape is a KNOWN-ANSWER vector
 * (IEEE 802.11i, {@code "password"}/{@code "IEEE"}/4096/32) asserted against this engine AND against the
 * JDK's own {@code SecretKeyFactory}, so it pins what the answer IS rather than that two of our own
 * implementations agree on it. Agreement was never correctness.
 *
 * <p><b>ONE {@link Hmac} IS BUILT AND REUSED, and that is the whole reason the streaming HMAC exists.</b>
 * Every iteration re-MACs under the SAME key, so a fresh {@code Hmac} per iteration would re-derive the two
 * pads -- and re-HASH the key when it is longer than the block -- once per iteration. At PBKDF2's whole
 * point, a high iteration count, that is the dominant cost and it is pure waste: the WPA2 PMK's 4096
 * iterations would mean 4096 redundant key schedules. {@code reset()} replays the ipad and nothing else.
 *
 * <p>The one-shot it replaced could not do that: {@link Hmac#sha1} re-padded the key on every one of those
 * 4096 calls, and buffered {@code block + msgLen} bytes each time. So the collapse makes the PMK cheaper as
 * well as singular -- which matters on the path it sits on, where the derivation is deliberately hoisted
 * pre-association because the AP restarts the 4-way with a fresh ANonce about once a second and silently
 * drops a stale reply.
 */
public final class Pbkdf2
{
    private Pbkdf2()
    {
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
