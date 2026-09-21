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
package crypto;

/**
 * STREAMING message digests -- MD5 (RFC 1321) and the SHA-2 family (FIPS 180-4): SHA-1, SHA-224, SHA-256,
 * SHA-384, SHA-512. This is what backs the {@code java.security.MessageDigest} overlay.
 *
 * <p>Strictly JDK-free (primitive arrays + int/long math), so the same source runs on the seed JVM under
 * {@code test/crypto/CryptoTest} -- where it is cross-checked against the JDK's OWN {@code MessageDigest},
 * byte for byte, over every algorithm and a sweep of lengths -- and compiles into the bare-metal image via
 * our own baseline compiler. {@code crypto/} is on the writer's demand-loadable prefix list for the same
 * reason {@code zip/} is: the baked copy serves VM code and the SAME source is pulled into the guest world
 * so the overlay can delegate to it.
 *
 * <p>STREAMING rather than one-shot, and that is the whole point of a separate class from {@link Sha1}:
 * {@code MessageDigest.update} is incremental, and buffering an entire input to hash it at the end would
 * make a digest's memory cost its message size -- wrong for {@code DigestInputStream} over a large file, and
 * wrong for the stock tests that feed 6 MB one byte at a time. State is one partial block plus the chaining
 * variables, so a digest costs a fixed ~200 bytes whatever it hashes.
 *
 * <p>SHA-224 and SHA-384 are NOT truncations bolted on: each is the same compression function with a
 * DIFFERENT initial chaining value, then truncated. Sharing an IV would produce a plausible-looking digest
 * of the right length that no other implementation agrees with -- the exact silent wrong answer the
 * known-answer vectors in {@code CryptoTest} exist to catch.
 *
 * <p>NOT implemented, and named rather than faked: the SHA-3 family. SHA3 is a sponge over Keccak-f[1600],
 * a different construction entirely, not a parameter of this one. {@code MessageDigest.getInstance("SHA3-256")}
 * throws {@code NoSuchAlgorithmException}, which is the truthful answer.
 */
public final class Digest
{
    /** Algorithm ids. Used by the {@code java.security.MessageDigest} overlay's name lookup. */
    public static final int MD5 = 0;
    public static final int SHA1 = 1;
    public static final int SHA224 = 2;
    public static final int SHA256 = 3;
    public static final int SHA384 = 4;
    public static final int SHA512 = 5;

    /** SHA-256 round constants (FIPS 180-4 §4.2.2): the first 32 bits of the cube roots of the first 64 primes. */
    private static final int[] K256 = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2 };

    /** SHA-512 round constants (FIPS 180-4 §4.2.3): the first 64 bits of the cube roots of the first 80 primes. */
    private static final long[] K512 = {
        0x428A2F98D728AE22L, 0x7137449123EF65CDL, 0xB5C0FBCFEC4D3B2FL, 0xE9B5DBA58189DBBCL,
        0x3956C25BF348B538L, 0x59F111F1B605D019L, 0x923F82A4AF194F9BL, 0xAB1C5ED5DA6D8118L,
        0xD807AA98A3030242L, 0x12835B0145706FBEL, 0x243185BE4EE4B28CL, 0x550C7DC3D5FFB4E2L,
        0x72BE5D74F27B896FL, 0x80DEB1FE3B1696B1L, 0x9BDC06A725C71235L, 0xC19BF174CF692694L,
        0xE49B69C19EF14AD2L, 0xEFBE4786384F25E3L, 0x0FC19DC68B8CD5B5L, 0x240CA1CC77AC9C65L,
        0x2DE92C6F592B0275L, 0x4A7484AA6EA6E483L, 0x5CB0A9DCBD41FBD4L, 0x76F988DA831153B5L,
        0x983E5152EE66DFABL, 0xA831C66D2DB43210L, 0xB00327C898FB213FL, 0xBF597FC7BEEF0EE4L,
        0xC6E00BF33DA88FC2L, 0xD5A79147930AA725L, 0x06CA6351E003826FL, 0x142929670A0E6E70L,
        0x27B70A8546D22FFCL, 0x2E1B21385C26C926L, 0x4D2C6DFC5AC42AEDL, 0x53380D139D95B3DFL,
        0x650A73548BAF63DEL, 0x766A0ABB3C77B2A8L, 0x81C2C92E47EDAEE6L, 0x92722C851482353BL,
        0xA2BFE8A14CF10364L, 0xA81A664BBC423001L, 0xC24B8B70D0F89791L, 0xC76C51A30654BE30L,
        0xD192E819D6EF5218L, 0xD69906245565A910L, 0xF40E35855771202AL, 0x106AA07032BBD1B8L,
        0x19A4C116B8D2D0C8L, 0x1E376C085141AB53L, 0x2748774CDF8EEB99L, 0x34B0BCB5E19B48A8L,
        0x391C0CB3C5C95A63L, 0x4ED8AA4AE3418ACBL, 0x5B9CCA4F7763E373L, 0x682E6FF3D6B2B8A3L,
        0x748F82EE5DEFB2FCL, 0x78A5636F43172F60L, 0x84C87814A1F0AB72L, 0x8CC702081A6439ECL,
        0x90BEFFFA23631E28L, 0xA4506CEBDE82BDE9L, 0xBEF9A3F7B2C67915L, 0xC67178F2E372532BL,
        0xCA273ECEEA26619CL, 0xD186B8C721C0C207L, 0xEADA7DD6CDE0EB1EL, 0xF57D4F7FEE6ED178L,
        0x06F067AA72176FBAL, 0x0A637DC5A2C898A6L, 0x113F9804BEF90DAEL, 0x1B710B35131C471BL,
        0x28DB77F523047D84L, 0x32CAAB7B40C72493L, 0x3C9EBE0A15C9BEBCL, 0x431D67C49C100D4CL,
        0x4CC5D4BECB3E42B6L, 0x597F299CFC657E2AL, 0x5FCB6FAB3AD6FAECL, 0x6C44198C4A475817L };

    /** MD5 per-round left-rotation amounts (RFC 1321 §3.4). */
    private static final int[] MD5_S = {
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20, 5,  9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21 };

    /** MD5 additive constants: {@code floor(abs(sin(i+1)) * 2^32)}, i = 0..63 (RFC 1321 §3.4). */
    private static final int[] MD5_K = {
        0xD76AA478, 0xE8C7B756, 0x242070DB, 0xC1BDCEEE, 0xF57C0FAF, 0x4787C62A, 0xA8304613, 0xFD469501,
        0x698098D8, 0x8B44F7AF, 0xFFFF5BB1, 0x895CD7BE, 0x6B901122, 0xFD987193, 0xA679438E, 0x49B40821,
        0xF61E2562, 0xC040B340, 0x265E5A51, 0xE9B6C7AA, 0xD62F105D, 0x02441453, 0xD8A1E681, 0xE7D3FBC8,
        0x21E1CDE6, 0xC33707D6, 0xF4D50D87, 0x455A14ED, 0xA9E3E905, 0xFCEFA3F8, 0x676F02D9, 0x8D2A4C8A,
        0xFFFA3942, 0x8771F681, 0x6D9D6122, 0xFDE5380C, 0xA4BEEA44, 0x4BDECFA9, 0xF6BB4B60, 0xBEBFBC70,
        0x289B7EC6, 0xEAA127FA, 0xD4EF3085, 0x04881D05, 0xD9D4D039, 0xE6DB99E5, 0x1FA27CF8, 0xC4AC5665,
        0xF4292244, 0x432AFF97, 0xAB9423A7, 0xFC93A039, 0x655B59C3, 0x8F0CCC92, 0xFFEFF47D, 0x85845DD1,
        0x6FA87E4F, 0xFE2CE6E0, 0xA3014314, 0x4E0811A1, 0xF7537E82, 0xBD3AF235, 0x2AD7D2BB, 0xEB86D391 };

    private final int alg;
    private final int blockLen;                  // 64 (MD5/SHA-1/SHA-224/256) or 128 (SHA-384/512)
    private final int digestLen;
    private final int[] h;                       // 32-bit chaining state; null for the 64-bit algorithms
    private final long[] hl;                     // 64-bit chaining state; null for the 32-bit algorithms
    private final byte[] buf;                    // the partial block not yet compressed
    private int bufLen;
    private long total;                          // message bytes fed so far
    private final int[] w32;                     // per-block message schedule (scratch, reused)
    private final long[] w64;
    private final int[] s32;                     // per-block working variables (scratch, reused)
    private final long[] s64;

    /**
     * A fresh digest for {@code algorithm}, already reset.
     *
     * @param algorithm one of {@link #MD5}, {@link #SHA1}, {@link #SHA224}, {@link #SHA256}, {@link #SHA384},
     *                  {@link #SHA512}
     * @throws IllegalArgumentException if {@code algorithm} is not one of those
     */
    public Digest(int algorithm)
    {
        if (algorithm < MD5 || algorithm > SHA512)
        {
            throw new IllegalArgumentException("unknown digest algorithm id");
        }
        alg = algorithm;
        boolean wide = algorithm == SHA384 || algorithm == SHA512;
        blockLen = wide ? 128 : 64;
        digestLen = lengthOf(algorithm);
        buf = new byte[blockLen];
        if (wide)
        {
            h = null;
            hl = new long[8];
            w32 = null;
            w64 = new long[80];
            s32 = null;
            s64 = new long[8];
        }
        else
        {
            h = new int[8];
            hl = null;
            w32 = new int[algorithm == SHA1 ? 80 : 64];
            w64 = null;
            s32 = new int[8];
            s64 = null;
        }
        reset();
    }

    /** {@return the digest length in bytes for {@code algorithm}, or 0 if it is not one of the six ids} */
    public static int lengthOf(int algorithm)
    {
        if (algorithm == MD5)
        {
            return 16;
        }
        if (algorithm == SHA1)
        {
            return 20;
        }
        if (algorithm == SHA224)
        {
            return 28;
        }
        if (algorithm == SHA256)
        {
            return 32;
        }
        if (algorithm == SHA384)
        {
            return 48;
        }
        if (algorithm == SHA512)
        {
            return 64;
        }
        return 0;
    }

    /** {@return this digest's output length in bytes} */
    public int digestLength()
    {
        return digestLen;
    }

    /** {@return this digest's algorithm id} */
    public int algorithm()
    {
        return alg;
    }

    /** {@return this digest's compression block size in bytes} 64, or 128 for SHA-384/512. */
    public int blockLength()
    {
        return blockLen;
    }

    /** Discard all fed input and return to the initial chaining value. */
    public void reset()
    {
        bufLen = 0;
        total = 0L;
        if (alg == MD5)
        {
            h[0] = 0x67452301;
            h[1] = 0xEFCDAB89;
            h[2] = 0x98BADCFE;
            h[3] = 0x10325476;
        }
        else if (alg == SHA1)
        {
            h[0] = 0x67452301;
            h[1] = 0xEFCDAB89;
            h[2] = 0x98BADCFE;
            h[3] = 0x10325476;
            h[4] = 0xC3D2E1F0;
        }
        else if (alg == SHA224)
        {
            // FIPS 180-4 §5.3.2 -- the second 32 bits of the fractional parts of the square roots of the
            // 9th..16th primes. A DIFFERENT IV from SHA-256, not a truncation of it.
            h[0] = 0xC1059ED8;
            h[1] = 0x367CD507;
            h[2] = 0x3070DD17;
            h[3] = 0xF70E5939;
            h[4] = 0xFFC00B31;
            h[5] = 0x68581511;
            h[6] = 0x64F98FA7;
            h[7] = 0xBEFA4FA4;
        }
        else if (alg == SHA256)
        {
            h[0] = 0x6A09E667;
            h[1] = 0xBB67AE85;
            h[2] = 0x3C6EF372;
            h[3] = 0xA54FF53A;
            h[4] = 0x510E527F;
            h[5] = 0x9B05688C;
            h[6] = 0x1F83D9AB;
            h[7] = 0x5BE0CD19;
        }
        else if (alg == SHA384)
        {
            // FIPS 180-4 §5.3.4 -- the square roots of the 9th..16th primes, again a distinct IV.
            hl[0] = 0xCBBB9D5DC1059ED8L;
            hl[1] = 0x629A292A367CD507L;
            hl[2] = 0x9159015A3070DD17L;
            hl[3] = 0x152FECD8F70E5939L;
            hl[4] = 0x67332667FFC00B31L;
            hl[5] = 0x8EB44A8768581511L;
            hl[6] = 0xDB0C2E0D64F98FA7L;
            hl[7] = 0x47B5481DBEFA4FA4L;
        }
        else
        {
            hl[0] = 0x6A09E667F3BCC908L;
            hl[1] = 0xBB67AE8584CAA73BL;
            hl[2] = 0x3C6EF372FE94F82BL;
            hl[3] = 0xA54FF53A5F1D36F1L;
            hl[4] = 0x510E527FADE682D1L;
            hl[5] = 0x9B05688C2B3E6C1FL;
            hl[6] = 0x1F83D9ABFB41BD6BL;
            hl[7] = 0x5BE0CD19137E2179L;
        }
    }

    /** Feed one byte. */
    public void update(byte b)
    {
        buf[bufLen] = b;
        bufLen = bufLen + 1;
        total = total + 1L;
        if (bufLen == blockLen)
        {
            compress(buf, 0);
            bufLen = 0;
        }
    }

    /**
     * Feed {@code in[off..off+len)}.
     *
     * @throws IllegalArgumentException if the range is outside {@code in}
     */
    public void update(byte[] in, int off, int len)
    {
        if (in == null)
        {
            throw new IllegalArgumentException("no input buffer given");
        }
        if (off < 0 || len < 0 || off > in.length - len)
        {
            throw new IllegalArgumentException("input range out of bounds");
        }
        total = total + (long) len;
        int p = off;
        int end = off + len;
        // Top up a partial block first, so the block-at-a-time loop below can run straight out of the
        // caller's array with no copying -- which is what makes a large update cost one pass, not two.
        if (bufLen > 0)
        {
            while (p < end && bufLen < blockLen)
            {
                buf[bufLen] = in[p];
                bufLen = bufLen + 1;
                p = p + 1;
            }
            if (bufLen == blockLen)
            {
                compress(buf, 0);
                bufLen = 0;
            }
        }
        while (end - p >= blockLen)
        {
            compress(in, p);
            p = p + blockLen;
        }
        while (p < end)
        {
            buf[bufLen] = in[p];
            bufLen = bufLen + 1;
            p = p + 1;
        }
    }

    /**
     * Finish the digest into {@code out[off..off+digestLength())} and RESET, so the same object can hash
     * another message -- the {@code MessageDigest.digest()} contract.
     *
     * @throws IllegalArgumentException if the output range is outside {@code out}
     */
    public void digest(byte[] out, int off)
    {
        if (out == null)
        {
            throw new IllegalArgumentException("no output buffer given");
        }
        if (off < 0 || off > out.length - digestLen)
        {
            throw new IllegalArgumentException("output range out of bounds");
        }
        long bits = total * 8L;
        // Pad: 0x80, then zeros, then the message length as a big-endian 64-bit (128-bit for SHA-512,
        // whose top half is always zero here -- joe-ng cannot be handed 2^61 bytes) count of BITS.
        int lenField = blockLen == 128 ? 16 : 8;
        update((byte) 0x80);
        // update() bumped `total`; the length field must carry the ORIGINAL count, which `bits` holds.
        while (bufLen != blockLen - lenField)
        {
            update((byte) 0x00);
        }
        int i = 0;
        while (i < lenField - 8)
        {
            buf[bufLen] = (byte) 0;
            bufLen = bufLen + 1;
            i = i + 1;
        }
        // MD5 alone appends the bit count LOW-ORDER BYTE FIRST (RFC 1321 §3.2); the SHA family is
        // big-endian (FIPS 180-4 §5.1). Getting this backwards agrees with everyone on the EMPTY message --
        // where the count is 0 and byte order cannot show -- and disagrees on every other input, which is
        // exactly how the cross-check caught it: MD5 len=0 passed while all 21 other lengths failed.
        if (alg == MD5)
        {
            i = 0;
            while (i < 8)
            {
                buf[bufLen] = (byte) (bits >>> (8 * i));
                bufLen = bufLen + 1;
                i = i + 1;
            }
        }
        else
        {
            i = 7;
            while (i >= 0)
            {
                buf[bufLen] = (byte) (bits >>> (8 * i));
                bufLen = bufLen + 1;
                i = i - 1;
            }
        }
        compress(buf, 0);
        bufLen = 0;
        writeState(out, off);
        reset();
    }

    /** {@return a fresh array holding the finished digest} Resets, as {@link #digest(byte[], int)} does. */
    public byte[] digest()
    {
        byte[] out = new byte[digestLen];
        digest(out, 0);
        return out;
    }

    /**
     * A COPY of this digest with the same fed-so-far state, so the caller can finish one branch and keep
     * feeding the other -- {@code MessageDigest.clone()}. The copy shares no array with the original.
     */
    public Digest copy()
    {
        Digest d = new Digest(alg);
        int i = 0;
        if (h != null)
        {
            while (i < h.length)
            {
                d.h[i] = h[i];
                i = i + 1;
            }
        }
        else
        {
            while (i < hl.length)
            {
                d.hl[i] = hl[i];
                i = i + 1;
            }
        }
        i = 0;
        while (i < bufLen)
        {
            d.buf[i] = buf[i];
            i = i + 1;
        }
        d.bufLen = bufLen;
        d.total = total;
        return d;
    }

    /** Write the chaining state out big-endian, truncated to {@code digestLen} (SHA-224 and SHA-384). */
    private void writeState(byte[] out, int off)
    {
        int i = 0;
        if (alg == MD5)
        {
            // MD5 alone is LITTLE-endian, in both its message schedule and its output.
            while (i < 4)
            {
                int v = h[i];
                out[off + i * 4] = (byte) v;
                out[off + i * 4 + 1] = (byte) (v >>> 8);
                out[off + i * 4 + 2] = (byte) (v >>> 16);
                out[off + i * 4 + 3] = (byte) (v >>> 24);
                i = i + 1;
            }
            return;
        }
        if (h != null)
        {
            while (i < digestLen)
            {
                out[off + i] = (byte) (h[i / 4] >>> (24 - 8 * (i % 4)));
                i = i + 1;
            }
            return;
        }
        while (i < digestLen)
        {
            out[off + i] = (byte) (hl[i / 8] >>> (56 - 8 * (i % 8)));
            i = i + 1;
        }
    }

    /** Compress the one block at {@code data[off..off+blockLen)} into the chaining state. */
    private void compress(byte[] data, int off)
    {
        if (alg == MD5)
        {
            md5Block(data, off);
        }
        else if (alg == SHA1)
        {
            sha1Block(data, off);
        }
        else if (h != null)
        {
            sha256Block(data, off);
        }
        else
        {
            sha512Block(data, off);
        }
    }

    private static int rotl32(int x, int n)
    {
        return (x << n) | (x >>> (32 - n));
    }

    private static int rotr32(int x, int n)
    {
        return (x >>> n) | (x << (32 - n));
    }

    private static long rotr64(long x, int n)
    {
        return (x >>> n) | (x << (64 - n));
    }

    private void md5Block(byte[] data, int off)
    {
        int i = 0;
        while (i < 16)
        {
            w32[i] = (data[off + i * 4] & 0xFF) | ((data[off + i * 4 + 1] & 0xFF) << 8)
                    | ((data[off + i * 4 + 2] & 0xFF) << 16) | ((data[off + i * 4 + 3] & 0xFF) << 24);
            i = i + 1;
        }
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        i = 0;
        while (i < 64)
        {
            int f;
            int g;
            if (i < 16)
            {
                f = (b & c) | (~b & d);
                g = i;
            }
            else if (i < 32)
            {
                f = (d & b) | (~d & c);
                g = (5 * i + 1) % 16;
            }
            else if (i < 48)
            {
                f = b ^ c ^ d;
                g = (3 * i + 5) % 16;
            }
            else
            {
                f = c ^ (b | ~d);
                g = (7 * i) % 16;
            }
            int tmp = d;
            d = c;
            c = b;
            b = b + rotl32(a + f + MD5_K[i] + w32[g], MD5_S[i]);
            a = tmp;
            i = i + 1;
        }
        h[0] = h[0] + a;
        h[1] = h[1] + b;
        h[2] = h[2] + c;
        h[3] = h[3] + d;
    }

    private void sha1Block(byte[] data, int off)
    {
        int i = 0;
        while (i < 16)
        {
            w32[i] = ((data[off + i * 4] & 0xFF) << 24) | ((data[off + i * 4 + 1] & 0xFF) << 16)
                    | ((data[off + i * 4 + 2] & 0xFF) << 8) | (data[off + i * 4 + 3] & 0xFF);
            i = i + 1;
        }
        while (i < 80)
        {
            w32[i] = rotl32(w32[i - 3] ^ w32[i - 8] ^ w32[i - 14] ^ w32[i - 16], 1);
            i = i + 1;
        }
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        i = 0;
        while (i < 80)
        {
            int fk;
            if (i < 20)
            {
                fk = ((b & c) | (~b & d)) + 0x5A827999;
            }
            else if (i < 40)
            {
                fk = (b ^ c ^ d) + 0x6ED9EBA1;
            }
            else if (i < 60)
            {
                fk = ((b & c) | (b & d) | (c & d)) + 0x8F1BBCDC;
            }
            else
            {
                fk = (b ^ c ^ d) + 0xCA62C1D6;
            }
            int t = rotl32(a, 5) + e + w32[i] + fk;
            e = d;
            d = c;
            c = rotl32(b, 30);
            b = a;
            a = t;
            i = i + 1;
        }
        h[0] = h[0] + a;
        h[1] = h[1] + b;
        h[2] = h[2] + c;
        h[3] = h[3] + d;
        h[4] = h[4] + e;
    }

    /** SHA-224 and SHA-256: identical compression, distinct IV and output truncation. */
    private void sha256Block(byte[] data, int off)
    {
        int i = 0;
        while (i < 16)
        {
            w32[i] = ((data[off + i * 4] & 0xFF) << 24) | ((data[off + i * 4 + 1] & 0xFF) << 16)
                    | ((data[off + i * 4 + 2] & 0xFF) << 8) | (data[off + i * 4 + 3] & 0xFF);
            i = i + 1;
        }
        while (i < 64)
        {
            int x = w32[i - 15];
            int y = w32[i - 2];
            int s0 = rotr32(x, 7) ^ rotr32(x, 18) ^ (x >>> 3);
            int s1 = rotr32(y, 17) ^ rotr32(y, 19) ^ (y >>> 10);
            w32[i] = w32[i - 16] + s0 + w32[i - 7] + s1;
            i = i + 1;
        }
        i = 0;
        while (i < 8)
        {
            s32[i] = h[i];
            i = i + 1;
        }
        i = 0;
        while (i < 64)
        {
            int s1 = rotr32(s32[4], 6) ^ rotr32(s32[4], 11) ^ rotr32(s32[4], 25);
            int ch = (s32[4] & s32[5]) ^ (~s32[4] & s32[6]);
            int t1 = s32[7] + s1 + ch + K256[i] + w32[i];
            int s0 = rotr32(s32[0], 2) ^ rotr32(s32[0], 13) ^ rotr32(s32[0], 22);
            int maj = (s32[0] & s32[1]) ^ (s32[0] & s32[2]) ^ (s32[1] & s32[2]);
            int t2 = s0 + maj;
            s32[7] = s32[6];
            s32[6] = s32[5];
            s32[5] = s32[4];
            s32[4] = s32[3] + t1;
            s32[3] = s32[2];
            s32[2] = s32[1];
            s32[1] = s32[0];
            s32[0] = t1 + t2;
            i = i + 1;
        }
        i = 0;
        while (i < 8)
        {
            h[i] = h[i] + s32[i];
            i = i + 1;
        }
    }

    /** SHA-384 and SHA-512: the 64-bit compression, distinct IV and output truncation. */
    private void sha512Block(byte[] data, int off)
    {
        int i = 0;
        while (i < 16)
        {
            long v = 0L;
            int k = 0;
            while (k < 8)
            {
                v = (v << 8) | (long) (data[off + i * 8 + k] & 0xFF);
                k = k + 1;
            }
            w64[i] = v;
            i = i + 1;
        }
        while (i < 80)
        {
            long x = w64[i - 15];
            long y = w64[i - 2];
            long s0 = rotr64(x, 1) ^ rotr64(x, 8) ^ (x >>> 7);
            long s1 = rotr64(y, 19) ^ rotr64(y, 61) ^ (y >>> 6);
            w64[i] = w64[i - 16] + s0 + w64[i - 7] + s1;
            i = i + 1;
        }
        i = 0;
        while (i < 8)
        {
            s64[i] = hl[i];
            i = i + 1;
        }
        i = 0;
        while (i < 80)
        {
            long s1 = rotr64(s64[4], 14) ^ rotr64(s64[4], 18) ^ rotr64(s64[4], 41);
            long ch = (s64[4] & s64[5]) ^ (~s64[4] & s64[6]);
            long t1 = s64[7] + s1 + ch + K512[i] + w64[i];
            long s0 = rotr64(s64[0], 28) ^ rotr64(s64[0], 34) ^ rotr64(s64[0], 39);
            long maj = (s64[0] & s64[1]) ^ (s64[0] & s64[2]) ^ (s64[1] & s64[2]);
            long t2 = s0 + maj;
            s64[7] = s64[6];
            s64[6] = s64[5];
            s64[5] = s64[4];
            s64[4] = s64[3] + t1;
            s64[3] = s64[2];
            s64[2] = s64[1];
            s64[1] = s64[0];
            s64[0] = t1 + t2;
            i = i + 1;
        }
        i = 0;
        while (i < 8)
        {
            hl[i] = hl[i] + s64[i];
            i = i + 1;
        }
    }
}
