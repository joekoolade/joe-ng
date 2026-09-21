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

/**
 * A cryptographically strong random number generator, over joe-ng's own {@code crypto.Sha1Prng}.
 *
 * <p>OVERLAID, the same stated exception {@link MessageDigest} and {@code java.util.ServiceLoader} take:
 * stock's {@code getInstance} runs through {@code sun.security.jca} to a provider registry read from a
 * properties FILE, and its default construction goes on to {@code sun.security.provider.SunEntries} and a
 * regex over the {@code securerandom.strongAlgorithms} property. None of that machinery exists here.
 *
 * <h2>The seed is the honest part, and this class refuses to paper over it</h2>
 *
 * <p>A DRBG is only as unpredictable as its SEED. The algorithm below is the SUN {@code SHA1PRNG}
 * reproduced exactly -- {@code crypto.CryptoTest} puts it beside the JDK's own and compares byte for byte
 * over 400 draws -- but a perfect algorithm started from a guessable seed produces a guessable stream, and
 * nothing about the output would show it.
 *
 * <p>joe-ng today has NO PROVEN STRONG ENTROPY SOURCE. The BCM2711 carries a hardware RNG at
 * {@code 0xFE104000}; whether it is reachable on this board is MEASURED rather than assumed, and the boot
 * reports what it found (see {@code board.bcm2711.Rng}). Under QEMU it is measurably absent -- every
 * register in that window faults, while PM and GPIO reads beside it succeed.
 *
 * <p>So: <b>an unseeded instance THROWS on first use</b>, naming the problem, instead of quietly seeding
 * itself from a clock. That choice follows this project's standing rule -- a stub that throws names the
 * class and method the moment it is reached, while one that returns a plausible value is indistinguishable
 * from a working one -- and it matters more here than anywhere else in the VM, because "plausible" is all
 * any random output ever looks like. It throws an {@link Error} rather than a {@link RuntimeException} for
 * the reason {@code jdk.internal.lang.CaseFolding} does: an application's broad {@code catch (Exception)}
 * must not be able to turn a missing entropy source into a silently weak key.
 *
 * <p>{@link #setSeed(byte[])} works everywhere, so a caller who has its own seed material -- or who wants
 * the deterministic stream, which is what makes this testable at all -- is served normally.
 */
public class SecureRandom extends java.util.Random implements java.io.Serializable
{
    /** The algorithm this VM implements. */
    private static final String ALGORITHM = "SHA1PRNG";

    private final SecureRandomSpi spi;
    private final Provider provider;
    private final String algorithm;

    /** An unseeded generator. It throws on first use unless {@link #setSeed} is called; see the class note. */
    public SecureRandom()
    {
        this(new Sha1PrngSpi(), Provider.JOENG, ALGORITHM);
    }

    /**
     * A generator seeded with {@code seed}.
     *
     * @param seed the seed material
     */
    public SecureRandom(byte[] seed)
    {
        this(new Sha1PrngSpi(), Provider.JOENG, ALGORITHM);
        setSeed(seed);
    }

    /**
     * For subclasses and for {@link #getInstance}.
     *
     * @param secureRandomSpi the engine
     * @param provider        the provider it came from
     */
    protected SecureRandom(SecureRandomSpi secureRandomSpi, Provider provider)
    {
        this(secureRandomSpi, provider, ALGORITHM);
    }

    private SecureRandom(SecureRandomSpi spi, Provider provider, String algorithm)
    {
        // super(0) IS LOAD-BEARING, and stock does exactly this. java.util.Random's constructor calls
        // setSeed(long) -- VIRTUALLY, so it lands on the override below -- before any field of this object
        // has been assigned. Left implicit, `super()` seeds Random from System.nanoTime(), which would
        // either seed this DRBG FROM A CLOCK (a predictable stream that never throws and looks perfectly
        // random) or dereference a still-null spi. The 0 is what the override's `seed == 0` guard is for.
        super(0);
        this.spi = spi;
        this.provider = provider;
        this.algorithm = algorithm;
    }

    /**
     * {@return a generator implementing {@code algorithm}}
     *
     * @param algorithm a standard name; this VM implements {@code SHA1PRNG}
     * @throws NoSuchAlgorithmException if this VM has no implementation of it
     */
    public static SecureRandom getInstance(String algorithm) throws NoSuchAlgorithmException
    {
        if (algorithm == null)
        {
            throw new NullPointerException("null algorithm name");
        }
        if (!algorithm.equalsIgnoreCase(ALGORITHM))
        {
            throw new NoSuchAlgorithmException(algorithm + " SecureRandom not available");
        }
        return new SecureRandom(new Sha1PrngSpi(), Provider.JOENG, algorithm);
    }

    /**
     * {@return a generator implementing {@code algorithm} from the named provider}
     *
     * @param algorithm a standard name
     * @param provider  the provider name
     * @throws NoSuchAlgorithmException if this VM has no implementation of {@code algorithm}
     * @throws NoSuchProviderException  if {@code provider} is not this VM's provider
     */
    public static SecureRandom getInstance(String algorithm, String provider)
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
     * {@return a generator implementing {@code algorithm} from {@code provider}}
     *
     * @param algorithm a standard name
     * @param provider  the provider
     * @throws NoSuchAlgorithmException if this VM has no implementation of it, or {@code provider} is not
     *                                  this VM's provider
     */
    public static SecureRandom getInstance(String algorithm, Provider provider) throws NoSuchAlgorithmException
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
     * {@return a generator the platform considers STRONG}
     *
     * <p>joe-ng cannot make that claim: "strong" is a statement about the SEED, and this VM has no proven
     * entropy source. Refusing is the truthful answer, and a different one from "no such algorithm" only in
     * the message -- which is why the message says so.
     *
     * @throws NoSuchAlgorithmException always, until this VM has a validated entropy source
     */
    public static SecureRandom getInstanceStrong() throws NoSuchAlgorithmException
    {
        throw new NoSuchAlgorithmException(
                "joe-ng has no validated entropy source, so no SecureRandom here can be called strong; "
                        + "seed one explicitly with setSeed(byte[]) if you have seed material");
    }

    /** {@return this generator's provider} */
    public final Provider getProvider()
    {
        return provider;
    }

    /** {@return this generator's algorithm name} */
    public String getAlgorithm()
    {
        return algorithm;
    }

    /**
     * MIX seed material in. Supplements rather than replaces, as stock does.
     *
     * @param seed the seed material
     */
    public void setSeed(byte[] seed)
    {
        spi.engineSetSeed(seed);
    }

    /**
     * MIX eight bytes of {@code seed} in, LITTLE-endian.
     *
     * <p>The byte order is stock's {@code longToByteArray}, and it is LOW BYTE FIRST -- which is the
     * opposite of the big-endian order everything else in this VM's crypto uses, so it looks like a typo
     * and is not. Written big-endian, every arm of the probe still passed except the one that compares
     * {@code setSeed(0x0102030405060708L)} against {@code setSeed(new byte[]{1,...,8})}: both produce a
     * perfectly good stream, and only that cross-check says they are not the SAME stream a JDK produces.
     *
     * <p>The {@code seed == 0} guard is stock's and is NOT an optimisation: {@code java.util.Random}'s
     * constructor calls {@code setSeed(long)} before this object's own fields exist, so without it every
     * {@code SecureRandom} would be seeded with a constant during construction.
     *
     * @param seed the seed material
     */
    @Override
    public void setSeed(long seed)
    {
        if (seed == 0)
        {
            return;
        }
        byte[] b = new byte[8];
        long v = seed;
        int i = 0;
        while (i < 8)
        {
            b[i] = (byte) v;
            v = v >> 8;
            i = i + 1;
        }
        spi.engineSetSeed(b);
    }

    /**
     * Fill {@code bytes} with generated bytes.
     *
     * @param bytes the buffer to fill
     */
    @Override
    public void nextBytes(byte[] bytes)
    {
        spi.engineNextBytes(bytes);
    }

    /**
     * The {@link java.util.Random} hook: overriding it is what gives {@code nextInt}, {@code nextLong},
     * {@code nextBoolean} and {@code nextInt(bound)} their randomness from THIS generator rather than from
     * Random's linear congruential one -- which would be a catastrophic silent downgrade, since every one
     * of those methods would still return plausible numbers.
     *
     * @param numBits how many bits are wanted
     * @return that many bits, right-justified
     */
    @Override
    protected final int next(int numBits)
    {
        int numBytes = (numBits + 7) / 8;
        byte[] b = new byte[numBytes];
        nextBytes(b);
        int next = 0;
        int i = 0;
        while (i < numBytes)
        {
            next = (next << 8) + (b[i] & 0xFF);
            i = i + 1;
        }
        return next >>> (numBytes * 8 - numBits);
    }

    /**
     * {@return {@code numBytes} of SEED material}
     *
     * @param numBytes how many bytes of seed are wanted
     */
    public byte[] generateSeed(int numBytes)
    {
        return spi.engineGenerateSeed(numBytes);
    }

    /**
     * {@return {@code numBytes} of SEED material from the platform source}
     *
     * @param numBytes how many bytes of seed are wanted
     */
    public static byte[] getSeed(int numBytes)
    {
        return new SecureRandom().generateSeed(numBytes);
    }

    /** {@return a description naming the algorithm and the provider} */
    @Override
    public String toString()
    {
        return provider.getName() + " " + algorithm + " SecureRandom";
    }

    /**
     * The built-in engine: {@code crypto.Sha1Prng}, which is not created until a seed arrives.
     *
     * <p>A null {@code prng} is therefore exactly "nobody has seeded this", and every path that would
     * generate output checks it. That is the whole safety property of this class, so it is ONE check in ONE
     * place rather than a flag each caller is trusted to consult.
     */
    private static final class Sha1PrngSpi extends SecureRandomSpi
    {
        private crypto.Sha1Prng prng;

        @Override
        protected void engineSetSeed(byte[] seed)
        {
            if (seed == null)
            {
                throw new NullPointerException("null seed");
            }
            if (prng == null)
            {
                prng = new crypto.Sha1Prng(seed, seed.length);
            }
            else
            {
                prng.setSeed(seed, seed.length);
            }
        }

        @Override
        protected void engineNextBytes(byte[] bytes)
        {
            if (bytes == null)
            {
                throw new NullPointerException("null output buffer");
            }
            require();
            prng.nextBytes(bytes, 0, bytes.length);
        }

        @Override
        protected byte[] engineGenerateSeed(int numBytes)
        {
            // Deliberately NOT served from the generator. Seed material must come from an entropy source;
            // returning this DRBG's own output would let a caller "reseed" it from itself -- zero new
            // uncertainty, dressed up as fresh seed.
            throw noEntropy("generateSeed");
        }

        private void require()
        {
            if (prng == null)
            {
                throw noEntropy("nextBytes");
            }
        }

        private static Error noEntropy(String what)
        {
            return new Error("SecureRandom." + what + ": joe-ng has no validated entropy source, so this "
                    + "generator has no seed. An unseeded DRBG produces a PREDICTABLE stream that looks "
                    + "exactly like a random one, so this refuses rather than guessing. Call "
                    + "setSeed(byte[]) with real seed material. (The BCM2711 hardware RNG at 0xFE104000 is "
                    + "the intended source; it is absent under QEMU and not yet validated on silicon.)");
        }
    }
}
