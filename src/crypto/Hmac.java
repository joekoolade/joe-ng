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
 * HMAC (RFC 2104) -- the keyed message authentication code, STREAMING and generic over {@link Digest}:
 * MD5, SHA-1, SHA-224, SHA-256, SHA-384 and SHA-512, fed incrementally.
 *
 * <p><b>ONE CONSTRUCTION SERVES EVERYTHING NOW</b> -- {@code javax.crypto.Mac}, PBKDF2, the WPA2 PRF (the
 * PTK) and the EAPOL-Key MIC. Until this class was collapsed the 4-way handshake ran on a separate static
 * one-shot {@code sha1()} over a separate SHA-1 engine, so the image carried TWO implementations of one
 * primitive and only one of them was cross-checked against the JDK. That one-shot was kept while the
 * generic path was unproven on hardware; it is gone now, and CLAUDE.md records what the collapse cost and
 * what gated it.
 *
 * <p>STREAMING rather than one-shot, and that is not a nicety: a {@code Mac} authenticating a large input
 * must not hold it, and the one-shot this replaced buffered {@code block + msgLen} bytes -- which made a
 * MAC's memory cost its MESSAGE SIZE. State is two {@link Digest}s plus two block-sized pads: ~200 bytes
 * whatever it authenticates.
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
}
