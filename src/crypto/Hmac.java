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
 * HMAC (RFC 2104) -- the keyed message authentication code, in two shapes over two engines.
 *
 * <p>The INSTANCE side is STREAMING and generic over {@link Digest}: MD5, SHA-1, SHA-224, SHA-256, SHA-384
 * and SHA-512, fed incrementally. That is what backs the {@code javax.crypto.Mac} overlay, and streaming is
 * not a nicety there -- a {@code Mac} authenticating a large input must not hold it, and buffering the whole
 * message would make a MAC's memory cost its MESSAGE SIZE. State is two {@link Digest}s plus two block-sized
 * pads: ~200 bytes whatever it authenticates.
 *
 * <p>The STATIC {@link #sha1} is the one-shot HMAC-SHA1 over {@link Sha1} that backs PBKDF2 (PMK derivation),
 * the WPA2 PRF (PTK) and the EAPOL-Key MIC. **It is deliberately left alone by this increment.** Collapsing
 * it onto the instance path would switch the most hardware-validated code in this tree -- the 4-way handshake
 * that reaches HTTP 200 OK on every Pi boot -- onto a different SHA-1 implementation, which buys WPA2 nothing
 * and can only be gated by a flash. {@code CryptoTest.hmacAgreesWithWpa2} MEASURES the two as byte-identical
 * across key and message lengths, so that collapse is a separate, separately-gated increment with its
 * evidence already standing rather than a guess.
 *
 * <p>JDK-free, so the same source runs the seed-JVM vectors, compiles into the image, and demand-loads into
 * the guest world.
 */
public final class Hmac
{
    private final Digest inner;          // H( (K' ^ ipad) || message ), fed as the caller updates
    private final Digest outer;          // H( (K' ^ opad) || inner-hash ), fed only at doFinal
    private final byte[] ipadKey;        // K' ^ 0x36, replayed at every reset
    private final byte[] opadKey;        // K' ^ 0x5C, replayed at every doFinal
    private final int macLen;

    /**
     * A fresh HMAC under {@code key[0..keyLen)}, ready to be fed.
     *
     * @param algorithm one of {@link Digest#MD5}, {@link Digest#SHA1}, {@link Digest#SHA224},
     *                  {@link Digest#SHA256}, {@link Digest#SHA384}, {@link Digest#SHA512}
     * @param key       the key; NOT retained -- only the two derived pads are kept
     * @param keyLen    how many bytes of {@code key} to use
     * @throws IllegalArgumentException if the algorithm or the key range is not valid
     */
    public Hmac(int algorithm, byte[] key, int keyLen)
    {
        inner = new Digest(algorithm);
        outer = new Digest(algorithm);
        macLen = inner.digestLength();
        int block = inner.blockLength();

        if (key == null)
        {
            throw new IllegalArgumentException("no key given");
        }
        if (keyLen < 0 || keyLen > key.length)
        {
            throw new IllegalArgumentException("key length out of bounds");
        }

        // RFC 2104 §2: a key LONGER than the block is replaced by its own hash; any key is then zero-padded
        // out to the block. Hashing is not an optimisation -- a long key fed raw would not fit the pad.
        byte[] padded = new byte[block];
        if (keyLen > block)
        {
            Digest kd = new Digest(algorithm);
            kd.update(key, 0, keyLen);
            kd.digest(padded, 0);
        }
        else
        {
            int j = 0;
            while (j < keyLen)
            {
                padded[j] = key[j];
                j = j + 1;
            }
        }

        ipadKey = new byte[block];
        opadKey = new byte[block];
        int i = 0;
        while (i < block)
        {
            int kb = padded[i] & 0xFF;
            ipadKey[i] = (byte) (kb ^ 0x36);
            opadKey[i] = (byte) (kb ^ 0x5C);
            padded[i] = 0;               // scratch that reproduces the key: do not leave it in the heap
            i = i + 1;
        }

        reset();
    }

    /** {@return this MAC's length in bytes} The underlying digest's length -- HMAC does not truncate. */
    public int macLength()
    {
        return macLen;
    }

    /** {@return the digest algorithm id this MAC is keyed over} */
    public int algorithm()
    {
        return inner.algorithm();
    }

    /** Discard any partly-fed message and start again under the SAME key -- {@code Mac.reset()}. */
    public void reset()
    {
        inner.reset();
        outer.reset();
        inner.update(ipadKey, 0, ipadKey.length);
    }

    /** Feed one byte. */
    public void update(byte b)
    {
        inner.update(b);
    }

    /** Feed {@code in[off..off+len)}. */
    public void update(byte[] in, int off, int len)
    {
        inner.update(in, off, len);
    }

    /**
     * Finish the MAC into {@code out[off..off+macLength())} and RESET under the same key, so the object can
     * authenticate another message -- the {@code Mac.doFinal()} contract.
     *
     * @throws IllegalArgumentException if the output range is outside {@code out}
     */
    public void doFinal(byte[] out, int off)
    {
        if (out == null)
        {
            throw new IllegalArgumentException("no output buffer given");
        }
        if (off < 0 || off > out.length - macLen)
        {
            throw new IllegalArgumentException("output range out of bounds");
        }

        byte[] ih = new byte[macLen];
        inner.digest(ih, 0);                         // finishes the inner hash (and resets that Digest)
        outer.update(opadKey, 0, opadKey.length);
        outer.update(ih, 0, macLen);
        outer.digest(out, off);

        int i = 0;
        while (i < macLen)
        {
            ih[i] = 0;                               // the inner hash is key-dependent scratch
            i = i + 1;
        }
        reset();
    }

    /**
     * A COPY with the same key and the same fed-so-far state, so the caller can finish one branch and keep
     * feeding the other -- {@code Mac.clone()}.
     *
     * <p>The two {@link Digest}s are deep-copied. The PADS are SHARED, deliberately: they are final, are
     * written only by the constructor, and nothing mutates them -- so sharing is safe, and it keeps ONE
     * copy of the key-derived material in the heap instead of two.
     *
     * <p>This method exists because {@code Object.clone()} is SHALLOW, which would leave a cloned
     * {@code Mac} sharing one engine with its original: both would then authenticate the interleaving of
     * two messages and BOTH answers would look exactly like MACs. The digest overlay already paid for that
     * lesson once.
     */
    public Hmac copy()
    {
        return new Hmac(this);
    }

    private Hmac(Hmac src)
    {
        inner = src.inner.copy();
        outer = src.outer.copy();
        ipadKey = src.ipadKey;
        opadKey = src.opadKey;
        macLen = src.macLen;
    }

    /** {@return a fresh array holding the finished MAC} Resets, as {@link #doFinal(byte[], int)} does. */
    public byte[] doFinal()
    {
        byte[] out = new byte[macLen];
        doFinal(out, 0);
        return out;
    }

    /**
     * One-shot generic HMAC: {@code out[0..)} = HMAC-{@code algorithm}(key, msg).
     *
     * @param out must hold at least {@link Digest#lengthOf(int)} bytes for this algorithm
     */
    public static void mac(int algorithm, byte[] key, int keyLen, byte[] msg, int msgLen, byte[] out)
    {
        Hmac h = new Hmac(algorithm, key, keyLen);
        h.update(msg, 0, msgLen);
        h.doFinal(out, 0);
    }

    /**
     * HMAC-SHA1 of {@code msg[0..msgLen)} under {@code key[0..keyLen)}; 20-byte MAC into {@code out}.
     *
     * <p>THE WPA2 PATH, over {@link Sha1} rather than {@link Digest}, and untouched on purpose -- see the
     * class javadoc. Equivalent to {@code mac(Digest.SHA1, ...)}, which {@code CryptoTest} measures rather
     * than assumes. One-shot by construction: it buffers {@code block + msgLen} bytes, which is right for the
     * handshake's tiny frames and is exactly why the instance API above exists for anything larger.
     */
    public static void sha1(byte[] key, int keyLen, byte[] msg, int msgLen, byte[] out)
    {
        byte[] k = new byte[Sha1.BLOCK];                 // key padded/hashed to the 64-byte block
        if (keyLen > Sha1.BLOCK)
        {
            byte[] kh = new byte[Sha1.DIGEST];
            Sha1.hash(key, keyLen, kh);
            copy(kh, k, Sha1.DIGEST);
        }
        else
        {
            copy(key, k, keyLen);
        }

        byte[] inner = new byte[Sha1.BLOCK + msgLen];    // SHA1( (k^ipad) || msg )
        int i = 0;
        while (i < Sha1.BLOCK)
        {
            inner[i] = (byte) ((k[i] & 0xFF) ^ 0x36);
            i = i + 1;
        }
        i = 0;
        while (i < msgLen)
        {
            inner[Sha1.BLOCK + i] = msg[i];
            i = i + 1;
        }
        byte[] ih = new byte[Sha1.DIGEST];
        Sha1.hash(inner, Sha1.BLOCK + msgLen, ih);

        byte[] outer = new byte[Sha1.BLOCK + Sha1.DIGEST];   // SHA1( (k^opad) || inner-hash )
        i = 0;
        while (i < Sha1.BLOCK)
        {
            outer[i] = (byte) ((k[i] & 0xFF) ^ 0x5C);
            i = i + 1;
        }
        i = 0;
        while (i < Sha1.DIGEST)
        {
            outer[Sha1.BLOCK + i] = ih[i];
            i = i + 1;
        }
        Sha1.hash(outer, Sha1.BLOCK + Sha1.DIGEST, out);
    }

    private static void copy(byte[] src, byte[] dst, int len)
    {
        int i = 0;
        while (i < len)
        {
            dst[i] = src[i];
            i = i + 1;
        }
    }
}
