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
package javax.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;
import crypto.Digest;
import crypto.Hmac;

/**
 * Keyed message authentication on bare metal, backed by joe-ng's own {@code crypto.Hmac}.
 *
 * <p>OVERLAID, the same stated exception {@code java.security.MessageDigest} and
 * {@code java.util.ServiceLoader} take, and for a reason that is measured rather than stylistic: stock's
 * {@code getInstance} goes through {@code sun.security.jca.GetInstance} and {@code JceSecurity}, which
 * VERIFY THE PROVIDER'S JAR SIGNATURE. Signature verification is the whole of why {@code sun/security/} is
 * denied here. No faithful copy of the stock class could work whatever its shape -- so what is faithful
 * instead is the API and the BYTES: every MAC this produces is cross-checked against the JDK's own
 * {@code javax.crypto.Mac} in {@code crypto.CryptoTest}, 3,432 comparisons over six algorithms, eleven key
 * lengths, thirteen message lengths and four feeding patterns, plus the published RFC 2202 and RFC 4231
 * vectors.
 *
 * <p>SUPPORTED: {@code HmacMD5}, {@code HmacSHA1}, {@code HmacSHA224}, {@code HmacSHA256},
 * {@code HmacSHA384}, {@code HmacSHA512}, matched case-insensitively as stock matches them.
 *
 * <p>NOT supported, and it THROWS {@link NoSuchAlgorithmException} rather than answering something
 * plausible: the truncated variants {@code HmacSHA512/224} and {@code HmacSHA512/256} (a different output
 * length is a different MAC, not a parameter of this one), the SHA-3 HMACs (no SHA-3 engine -- see
 * {@link Digest}), and the password-based {@code PBEWith*} MACs. A MAC of the right LENGTH that no other
 * implementation agrees with is the worst possible answer here, because every wrong MAC looks exactly as
 * random as every right one.
 *
 * <p>This class is NOT abstract and does not extend {@link MacSpi} -- it HOLDS one, which is stock's shape.
 * A program that supplies its own {@code MacSpi} through the protected constructor works here unchanged.
 */
public class Mac implements Cloneable
{
    private final MacSpi spi;
    private final Provider provider;
    private final String algorithm;
    private boolean initialized;

    /**
     * A MAC over a caller-supplied SPI. For subclasses; ordinary callers use {@link #getInstance(String)}.
     *
     * @param macSpi    the implementation
     * @param provider  the provider it came from
     * @param algorithm the algorithm name
     */
    protected Mac(MacSpi macSpi, Provider provider, String algorithm)
    {
        this.spi = macSpi;
        this.provider = provider;
        this.algorithm = algorithm;
    }

    /**
     * {@return a MAC implementing {@code algorithm}}
     *
     * @param algorithm a standard name, matched case-insensitively
     * @throws NoSuchAlgorithmException if this VM has no implementation of it
     */
    public static final Mac getInstance(String algorithm) throws NoSuchAlgorithmException
    {
        if (algorithm == null)
        {
            throw new NullPointerException("null algorithm name");
        }
        int id = digestIdFor(algorithm);
        if (id < 0)
        {
            throw new NoSuchAlgorithmException(algorithm + " Mac not available");
        }
        // The REQUESTED spelling is what getAlgorithm() reports, not a canonical one -- stock stores the
        // string it was handed. Canonicalising here was the MessageDigest overlay's first bug and a
        // ten-second host control named it; the same trap is not walked into twice.
        return new Mac(new Impl(id), Provider.JOENG, algorithm);
    }

    /**
     * {@return a MAC implementing {@code algorithm} from the named provider}
     *
     * <p>joe-ng carries exactly one provider, so any other name is a {@link NoSuchProviderException} --
     * which is truthful, and a different statement from "no such algorithm".
     *
     * @throws NoSuchAlgorithmException if this VM has no implementation of {@code algorithm}
     * @throws NoSuchProviderException  if {@code provider} is not this VM's provider
     */
    public static final Mac getInstance(String algorithm, String provider)
            throws NoSuchAlgorithmException, NoSuchProviderException
    {
        if (provider == null || provider.isEmpty())
        {
            throw new IllegalArgumentException("missing provider");
        }
        if (!provider.equals(Provider.JOENG.getName()))
        {
            throw new NoSuchProviderException("no such provider: " + provider);
        }
        return getInstance(algorithm);
    }

    /**
     * {@return a MAC implementing {@code algorithm} from {@code provider}}
     *
     * @throws NoSuchAlgorithmException if this VM has no implementation of it, or {@code provider} is not
     *                                  this VM's provider
     */
    public static final Mac getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException
    {
        if (provider == null)
        {
            throw new IllegalArgumentException("missing provider");
        }
        if (provider != Provider.JOENG)
        {
            throw new NoSuchAlgorithmException("no such provider: " + provider.getName());
        }
        return getInstance(algorithm);
    }

    /** {@return the algorithm name AS REQUESTED, not canonicalised} */
    public final String getAlgorithm()
    {
        return algorithm;
    }

    /** {@return the provider this MAC came from} */
    public final Provider getProvider()
    {
        return provider;
    }

    /** {@return the MAC length in bytes} HMAC does not truncate: it is the digest's own length. */
    public final int getMacLength()
    {
        return spi.engineGetMacLength();
    }

    /**
     * Initialize with {@code key}, discarding any state from a previous key.
     *
     * @throws InvalidKeyException if the key is null, is not in {@code RAW} form, or has no bytes
     */
    public final void init(Key key) throws InvalidKeyException
    {
        try
        {
            init(key, null);
        }
        catch (InvalidAlgorithmParameterException e)
        {
            // Unreachable for HMAC: engineInit accepts only a null parameter spec, and this passes null.
            throw new InvalidKeyException("init() failed", e);
        }
    }

    /**
     * Initialize with {@code key} and {@code params}.
     *
     * @throws InvalidKeyException                if the key is unusable
     * @throws InvalidAlgorithmParameterException if {@code params} is non-null -- HMAC takes none
     */
    public final void init(Key key, AlgorithmParameterSpec params)
            throws InvalidKeyException, InvalidAlgorithmParameterException
    {
        spi.engineInit(key, params);
        initialized = true;
    }

    /**
     * Feed one byte.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     */
    public final void update(byte input) throws IllegalStateException
    {
        checkInitialized();
        spi.engineUpdate(input);
    }

    /**
     * Feed all of {@code input}; a null argument feeds nothing, as stock allows.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     */
    public final void update(byte[] input) throws IllegalStateException
    {
        checkInitialized();
        if (input != null)
        {
            spi.engineUpdate(input, 0, input.length);
        }
    }

    /**
     * Feed {@code input[offset..offset+len)}.
     *
     * @throws IllegalStateException     if this MAC has not been initialized
     * @throws IllegalArgumentException  if the range lies outside {@code input}
     */
    public final void update(byte[] input, int offset, int len) throws IllegalStateException
    {
        checkInitialized();
        if (input == null)
        {
            return;
        }
        if (offset < 0 || len < 0 || len > input.length - offset)
        {
            throw new IllegalArgumentException("Bad arguments");
        }
        spi.engineUpdate(input, offset, len);
    }

    /**
     * Feed the buffer's remaining bytes, leaving its position at its limit.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     */
    public final void update(ByteBuffer input)
    {
        checkInitialized();
        if (input == null)
        {
            throw new IllegalArgumentException("Buffer must not be null");
        }
        spi.engineUpdate(input);
    }

    /**
     * {@return the finished MAC} Resets to the state just after {@code init}, so the same object can
     * authenticate another message under the same key.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     */
    public final byte[] doFinal() throws IllegalStateException
    {
        checkInitialized();
        return spi.engineDoFinal();
    }

    /**
     * Finish into {@code output[outOffset..)} and reset.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     * @throws ShortBufferException  if {@code output} has less than {@link #getMacLength()} bytes left
     */
    public final void doFinal(byte[] output, int outOffset)
            throws ShortBufferException, IllegalStateException
    {
        checkInitialized();
        if (output == null)
        {
            throw new IllegalArgumentException("No output buffer given");
        }
        int len = getMacLength();
        if (output.length - outOffset < len)
        {
            throw new ShortBufferException("Cannot store MAC in output buffer");
        }
        byte[] mac = spi.engineDoFinal();
        System.arraycopy(mac, 0, output, outOffset, len);
    }

    /**
     * {@return the MAC of {@code input} appended to whatever has already been fed} Resets afterwards.
     *
     * @throws IllegalStateException if this MAC has not been initialized
     */
    public final byte[] doFinal(byte[] input) throws IllegalStateException
    {
        checkInitialized();
        update(input);
        return doFinal();
    }

    /** Discard any fed input, keeping the key. A MAC that was never initialized stays uninitialized. */
    public final void reset()
    {
        spi.engineReset();
    }

    /**
     * {@return an independent copy, if the underlying SPI supports it}
     *
     * @throws CloneNotSupportedException if it does not
     */
    @Override
    public final Object clone() throws CloneNotSupportedException
    {
        Mac copy = new Mac((MacSpi) spi.clone(), provider, algorithm);
        copy.initialized = initialized;
        return copy;
    }

    private void checkInitialized() throws IllegalStateException
    {
        if (!initialized)
        {
            throw new IllegalStateException("MAC not initialized");
        }
    }

    /**
     * The {@code crypto.Digest} id behind a standard HMAC name, or -1.
     *
     * <p>Case-INSENSITIVE, on an upper-cased copy, so the table reads as the canonical spellings rather
     * than as a list of every capitalisation -- the shape {@code MessageDigest.algorithmId} already uses.
     */
    private static int digestIdFor(String name)
    {
        String n = name.toUpperCase();
        if (n.equals("HMACMD5"))
        {
            return Digest.MD5;
        }
        if (n.equals("HMACSHA1"))
        {
            return Digest.SHA1;
        }
        if (n.equals("HMACSHA224"))
        {
            return Digest.SHA224;
        }
        if (n.equals("HMACSHA256"))
        {
            return Digest.SHA256;
        }
        if (n.equals("HMACSHA384"))
        {
            return Digest.SHA384;
        }
        if (n.equals("HMACSHA512"))
        {
            return Digest.SHA512;
        }
        return -1;
    }

    /** The one implementation: {@code crypto.Hmac} behind the stock SPI. */
    private static final class Impl extends MacSpi implements Cloneable
    {
        private final int digestId;
        private Hmac hmac;

        private Impl(int digestId)
        {
            this.digestId = digestId;
        }

        @Override
        protected int engineGetMacLength()
        {
            return Digest.lengthOf(digestId);
        }

        @Override
        protected void engineInit(Key key, AlgorithmParameterSpec params)
                throws InvalidKeyException, InvalidAlgorithmParameterException
        {
            if (params != null)
            {
                throw new InvalidAlgorithmParameterException("HMAC does not use parameters");
            }
            if (key == null)
            {
                throw new InvalidKeyException("No key given");
            }
            // Stock's SunJCE requires a RAW key and rejects an empty one. Both are matched rather than
            // waved through: a key in some other encoding is not the bytes HMAC needs, and an empty key
            // is a caller mistake that produces a perfectly well-formed and worthless MAC.
            String format = key.getFormat();
            if (format == null || !format.equalsIgnoreCase("RAW"))
            {
                throw new InvalidKeyException("Missing key data");
            }
            byte[] encoded = key.getEncoded();
            if (encoded == null)
            {
                throw new InvalidKeyException("Missing key data");
            }
            if (encoded.length == 0)
            {
                throw new InvalidKeyException("Empty key");
            }
            hmac = new Hmac(digestId, encoded, encoded.length);
            int i = 0;
            while (i < encoded.length)
            {
                encoded[i] = 0;          // getEncoded() handed us a COPY of the key; do not leave it around
                i = i + 1;
            }
        }

        @Override
        protected void engineUpdate(byte input)
        {
            hmac.update(input);
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len)
        {
            hmac.update(input, offset, len);
        }

        @Override
        protected byte[] engineDoFinal()
        {
            return hmac.doFinal();
        }

        /**
         * {@return an independent copy} The {@link Hmac} is DEEP-copied: {@code Object.clone()} is shallow,
         * so an inherited clone would leave both objects driving one engine.
         */
        @Override
        public Object clone() throws CloneNotSupportedException
        {
            Impl c = (Impl) super.clone();
            if (hmac != null)
            {
                c.hmac = hmac.copy();
            }
            return c;
        }

        @Override
        protected void engineReset()
        {
            if (hmac != null)
            {
                hmac.reset();
            }
        }
    }
}
