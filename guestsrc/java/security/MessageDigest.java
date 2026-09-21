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
package java.security;

import java.nio.ByteBuffer;

/**
 * Message digests on bare metal, backed by joe-ng's own {@code crypto.Digest}.
 *
 * <p>OVERLAID, a stated exception to "guestsrc is only for classes that need natives" and the same one
 * {@code java.util.ServiceLoader} takes: stock's {@code getInstance} goes through
 * {@code sun.security.jca.GetInstance} to a {@code Provider} registry read from a properties FILE and
 * populated by {@code ServiceLoader} over the module graph, then instantiates the SPI reflectively through
 * {@code java.lang.invoke}. All three of those subsystems are deliberately absent here, so no faithful copy
 * of the stock class could work whatever its shape. What IS faithful is the API and the digests: the bytes
 * this produces are cross-checked against the JDK's own {@code MessageDigest} in {@code crypto.CryptoTest},
 * over every algorithm, 22 message lengths and five feeding patterns -- 756 comparisons, byte for byte.
 *
 * <p>SUPPORTED: {@code MD5}, {@code SHA-1}, {@code SHA-224}, {@code SHA-256}, {@code SHA-384},
 * {@code SHA-512}, under the stock aliases and case-insensitively ({@code "sha"}, {@code "SHA1"},
 * {@code "md5"} all resolve, as the stock tests require).
 *
 * <p>NOT supported, and it THROWS {@link NoSuchAlgorithmException} rather than answering something plausible:
 * the SHA-3 family. SHA3 is a sponge over Keccak-f[1600] -- a different construction, not a parameter of
 * this one -- so it is a separate piece of work, and a digest of the right LENGTH that no other
 * implementation agrees with is the worst possible answer in a hash function.
 *
 * <p>The class stays ABSTRACT and still {@code extends MessageDigestSpi}, which is stock's shape: a program
 * that subclasses {@code MessageDigest} and implements the {@code engine*} methods works here unchanged, and
 * so does one that passes the result where a {@code MessageDigestSpi} is wanted.
 */
public abstract class MessageDigest extends MessageDigestSpi
{
    private final String algorithm;
    /** The provider this instance came from; null for a subclass a caller constructed directly. */
    Provider provider;

    /** State machine, exactly as stock: a digest that has been finished must be reset before it is reused. */
    private static final int INITIAL = 0;
    private static final int IN_PROGRESS = 1;
    private int state = INITIAL;

    /**
     * A digest for the named algorithm. For subclasses; ordinary callers use {@link #getInstance(String)}.
     *
     * @param algorithm the algorithm name
     */
    protected MessageDigest(String algorithm)
    {
        this.algorithm = algorithm;
    }

    /**
     * {@return a digest implementing {@code algorithm}}
     *
     * @param algorithm a standard name or alias, matched case-insensitively
     * @throws NoSuchAlgorithmException if this VM has no implementation of it
     */
    public static MessageDigest getInstance(String algorithm) throws NoSuchAlgorithmException
    {
        if (algorithm == null)
        {
            throw new NullPointerException("null algorithm name");
        }
        int id = algorithmId(algorithm);
        if (id < 0)
        {
            throw new NoSuchAlgorithmException(algorithm + " MessageDigest not available");
        }
        // The REQUESTED spelling is what getAlgorithm() reports, not a canonical one -- stock stores the
        // string it was handed, so getInstance("sha").getAlgorithm() is "sha". Measured on a host JVM
        // rather than assumed: canonicalising here was this overlay's first bug, and a ten-second host
        // control named it before any boot.
        Impl md = new Impl(algorithm, id);
        md.provider = Provider.JOENG;
        return md;
    }

    /**
     * {@return a digest implementing {@code algorithm} from the named provider}
     *
     * <p>joe-ng carries exactly one provider, so any other name is a {@link NoSuchProviderException} -- which
     * is the truthful answer, and a different statement from "no such algorithm".
     *
     * @param algorithm a standard name or alias
     * @param provider  the provider name
     * @throws NoSuchAlgorithmException if this VM has no implementation of {@code algorithm}
     * @throws NoSuchProviderException  if {@code provider} is not this VM's provider
     */
    public static MessageDigest getInstance(String algorithm, String provider)
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
     * {@return a digest implementing {@code algorithm} from {@code provider}}
     *
     * @param algorithm a standard name or alias
     * @param provider  the provider
     * @throws NoSuchAlgorithmException if this VM has no implementation of it, or {@code provider} is not
     *                                  this VM's provider
     */
    public static MessageDigest getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException
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

    /**
     * The algorithm id for a standard name or alias, or -1.
     *
     * <p>Case-INSENSITIVE and alias-aware because the stock tests demand it: {@code TestSameLength} sweeps
     * {@code "SHA"}, {@code "Sha"}, {@code "SHA-1"}, {@code "sha-1"}, {@code "SHA1"} and {@code "sha1"} as
     * six spellings of one algorithm. Comparison is on an upper-cased copy, so the table below reads as the
     * canonical spellings rather than as a list of every capitalisation.
     */
    private static int algorithmId(String name)
    {
        String n = name.toUpperCase();
        if (n.equals("MD5"))
        {
            return crypto.Digest.MD5;
        }
        if (n.equals("SHA") || n.equals("SHA1") || n.equals("SHA-1"))
        {
            return crypto.Digest.SHA1;
        }
        if (n.equals("SHA224") || n.equals("SHA-224"))
        {
            return crypto.Digest.SHA224;
        }
        if (n.equals("SHA256") || n.equals("SHA-256"))
        {
            return crypto.Digest.SHA256;
        }
        if (n.equals("SHA384") || n.equals("SHA-384"))
        {
            return crypto.Digest.SHA384;
        }
        if (n.equals("SHA512") || n.equals("SHA-512"))
        {
            return crypto.Digest.SHA512;
        }
        return -1;
    }

    /** {@return the provider this digest came from, or null for a subclass that did not come from one} */
    public final Provider getProvider()
    {
        return provider;
    }

    /** {@return this digest's standard algorithm name} */
    public final String getAlgorithm()
    {
        return algorithm;
    }

    /** {@return the digest length in bytes, or 0 if the implementation does not know it} */
    public final int getDigestLength()
    {
        return engineGetDigestLength();
    }

    /** Feed one byte. */
    public void update(byte input)
    {
        engineUpdate(input);
        state = IN_PROGRESS;
    }

    /**
     * Feed {@code input[offset..offset+len)}.
     *
     * <p>A bad range is an {@link IllegalArgumentException}, NOT an
     * {@code ArrayIndexOutOfBoundsException} -- the stock behaviour, and what {@code ArgumentSanity} pins.
     *
     * @param input  the bytes
     * @param offset where they start
     * @param len    how many
     */
    public void update(byte[] input, int offset, int len)
    {
        if (input == null)
        {
            throw new IllegalArgumentException("No input buffer given");
        }
        if (input.length - offset < len)
        {
            throw new IllegalArgumentException("Input buffer too short");
        }
        engineUpdate(input, offset, len);
        state = IN_PROGRESS;
    }

    /**
     * Feed all of {@code input}.
     *
     * @param input the bytes
     */
    public void update(byte[] input)
    {
        if (input == null)
        {
            throw new IllegalArgumentException("No input buffer given");
        }
        engineUpdate(input, 0, input.length);
        state = IN_PROGRESS;
    }

    /**
     * Feed the buffer's remaining bytes, leaving its position at its limit.
     *
     * @param input the buffer
     */
    public final void update(ByteBuffer input)
    {
        if (input == null)
        {
            throw new NullPointerException();
        }
        engineUpdate(input);
        state = IN_PROGRESS;
    }

    /** {@return the finished digest} Resets this object, so it can hash another message. */
    public byte[] digest()
    {
        byte[] result = engineDigest();
        state = INITIAL;
        return result;
    }

    /**
     * Finish the digest into {@code buf[offset..offset+len)}.
     *
     * @param buf    where to put it
     * @param offset where in {@code buf}
     * @param len    how much room there is
     * @return the number of bytes written
     * @throws DigestException if the digest does not fit, or the range is out of bounds
     */
    public int digest(byte[] buf, int offset, int len) throws DigestException
    {
        if (buf == null)
        {
            throw new IllegalArgumentException("No output buffer given");
        }
        if (buf.length - offset < len)
        {
            throw new IllegalArgumentException("Output buffer too small for specified offset and length");
        }
        int numBytes = engineDigest(buf, offset, len);
        state = INITIAL;
        return numBytes;
    }

    /**
     * Feed {@code input}, then finish.
     *
     * @param input the last bytes to feed
     * @return the finished digest
     */
    public byte[] digest(byte[] input)
    {
        update(input);
        return digest();
    }

    /** Discard all fed input. */
    public void reset()
    {
        engineReset();
        state = INITIAL;
    }

    /** {@return a description naming the algorithm and whether this digest has been fed} */
    @Override
    public String toString()
    {
        return algorithm + " Message Digest from " + (provider == null ? "<none>" : provider.getName())
                + ", " + (state == INITIAL ? "<initialized>" : "<in progress>");
    }

    /**
     * {@return whether two digests are equal}
     *
     * <p>CONSTANT TIME in the length of the first argument, as stock is: an early return on the first
     * differing byte leaks, through timing, how much of a digest an attacker has guessed. The odd-looking
     * {@code indexB} keeps the loop reading inside {@code digestb} when the lengths differ, without a
     * branch that depends on the data.
     *
     * @param digesta one digest
     * @param digestb the other
     */
    public static boolean isEqual(byte[] digesta, byte[] digestb)
    {
        if (digesta == digestb)
        {
            return true;
        }
        if (digesta == null || digestb == null)
        {
            return false;
        }
        int lenA = digesta.length;
        int lenB = digestb.length;
        if (lenB == 0)
        {
            return lenA == 0;
        }
        int result = 0;
        result |= lenA - lenB;
        int i = 0;
        while (i < lenA)
        {
            int indexB = ((i - lenB) >>> 31) * i;
            result |= digesta[i] ^ digestb[indexB];
            i = i + 1;
        }
        return result == 0;
    }

    /**
     * {@return a copy of this digest with the same fed-so-far state}
     *
     * @throws CloneNotSupportedException if the implementation does not support it
     */
    @Override
    public Object clone() throws CloneNotSupportedException
    {
        return super.clone();
    }

    /**
     * The built-in implementation: a thin shell over {@code crypto.Digest}, which is where the actual
     * compression functions live (JDK-free, so the same source runs on the seed JVM under CryptoTest and
     * compiles into the image).
     *
     * <p>{@code clone()} DEEP-COPIES the engine. {@code Object.clone()} is shallow, so an inherited clone
     * would hand back a second {@code MessageDigest} sharing ONE engine: feeding either would advance both,
     * and the whole point of cloning a digest is to fork the state. That is a silent wrong answer -- two
     * plausible digests, neither correct -- which is why the probe finishes the clone and keeps feeding the
     * original, and checks BOTH.
     */
    private static final class Impl extends MessageDigest implements Cloneable
    {
        private crypto.Digest engine;

        Impl(String algorithm, int id)
        {
            super(algorithm);
            engine = new crypto.Digest(id);
        }

        @Override
        protected int engineGetDigestLength()
        {
            return engine.digestLength();
        }

        @Override
        protected void engineUpdate(byte input)
        {
            engine.update(input);
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len)
        {
            engine.update(input, offset, len);
        }

        @Override
        protected byte[] engineDigest()
        {
            return engine.digest();
        }

        @Override
        protected void engineReset()
        {
            engine.reset();
        }

        @Override
        public Object clone() throws CloneNotSupportedException
        {
            Impl copy = (Impl) super.clone();
            copy.engine = engine.copy();
            return copy;
        }
    }
}
