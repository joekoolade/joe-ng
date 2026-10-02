/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.util;

/**
 * A JDK-free {@code java.util.Random}: the exact 48-bit linear-congruential algorithm of the stock class, but
 * seeded from {@code System.nanoTime()} instead of the stock {@code AtomicLong seedUniquifier} (atomics/CAS are
 * absent on metal, and the uniquifier only de-duplicates seeds across concurrently-constructed Randoms). The
 * {@code next}/{@code nextInt}/{@code nextLong}/{@code nextDouble}/{@code nextFloat}/{@code nextBytes}
 * sequence is bit-for-bit the JDK's for a given seed -- which is what lets a seeded probe diff this
 * class's output against a host JVM's rather than merely check that it looks random.
 */
public class Random
{
    private long seed;

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1L;

    /**
     * Stock's {@code DOUBLE_UNIT} = {@code 1.0 / (1L << Double.PRECISION)} = 2^-53, and {@code FLOAT_UNIT} =
     * {@code 1.0f / (1 << Float.PRECISION)} = 2^-24, written as hex float literals exactly as stock does.
     *
     * <p>Both are COMPILE-TIME CONSTANTS (JLS 4.12.4), so javac inlines them as {@code ldc2_w}/{@code ldc} and
     * this class still has NO {@code <clinit>} -- which is load-bearing rather than tidy: an initializer here
     * would have to RUN on metal for a class that sits in many closures, and the overlay has never had one.
     * MEASURED with {@code javap} on the overlay's own class file, not assumed.
     */
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private static final float FLOAT_UNIT = 0x1.0p-24f;

    public Random()
    {
        this(System.nanoTime());
    }

    public Random(long s)
    {
        setSeed(s);
    }

    /**
     * Reset this generator to the given seed, so the sequence from here is the JDK's for that seed.
     *
     * <p>Declared because a name-winning overlay DELETES whatever it does not declare, and this one is
     * load-bearing twice over: {@code java.security.SecureRandom} OVERRIDES it (stock's SecureRandom must
     * intercept {@code Random}'s seeding, or its inherited constructor would quietly install a linear
     * congruential seed), and ordinary code re-seeds a {@code Random} to make a run reproducible.
     *
     * <p>{@code synchronized} as stock is, and the constructor now goes THROUGH it rather than assigning
     * the field directly -- one scrambling rule in one place, so the two spellings cannot drift.
     *
     * @param s the seed
     */
    public synchronized void setSeed(long s)
    {
        this.seed = (s ^ MULT) & MASK;
    }

    protected int next(int bits)
    {
        seed = (seed * MULT + ADD) & MASK;
        return (int) (seed >>> (48 - bits));
    }

    public int nextInt()
    {
        return next(32);
    }

    public long nextLong()
    {
        return ((long) next(32) << 32) + next(32);
    }

    /**
     * {@code nextDouble()} -- stock's expression exactly, and the one member of this family that is MEASURED
     * REACHABLE: {@code java.lang.Math.random()} is literally
     * {@code RandomNumberGeneratorHolder.randomNumberGenerator.nextDouble()}, and {@code StrictMath.random()}
     * is the same. Dropped from this overlay the member CEASED TO EXIST, so {@code Math.random()} halted the
     * VM with {@code VIRTUALRESOLVE FAILED java/util/Random.nextDouble()D} and a {@code DENYLIST TRAP} blaming
     * a list {@code java/util/Random} is not on.
     *
     * <p>TWO DRAWS, NOT ONE, AND THE SPLIT IS NOT ARBITRARY. A double has 53 significand bits and
     * {@code next} yields at most 32, so stock takes 26 bits for the high part and 27 for the low and scales
     * by 2^-53. One 32-bit draw scaled by 2^-32 would be a perfectly uniform double in [0,1) and would NOT be
     * the JDK's value for a given seed -- which is exactly what this class's own comment promises, and what
     * lets a seeded probe diff joe-ng's output against a host JVM's byte for byte.
     *
     * @return a uniform double in {@code [0.0, 1.0)}
     */
    public double nextDouble()
    {
        return (((long) next(26) << 27) + next(27)) * DOUBLE_UNIT;
    }

    /**
     * {@code nextFloat()} -- the one-line sibling of {@link #nextDouble}, kept bit-exact for the same reason.
     *
     * <p>It is here rather than left for later because it is the same {@code next(bits)} foundation, a stock
     * DECLARATION (not an inherited {@code RandomGenerator} default), and one line that the probe measures for
     * free while it is open. A float has 24 significand bits, so it is a SINGLE draw scaled by 2^-24 -- the
     * asymmetry with {@code nextDouble}'s two draws is the JDK's, and an implementation that used
     * {@code (float) nextDouble()} would agree on neither the value nor the number of draws consumed.
     *
     * @return a uniform float in {@code [0.0f, 1.0f)}
     */
    public float nextFloat()
    {
        return next(24) * FLOAT_UNIT;
    }

    /**
     * {@code nextInt(bound)} -- the stock algorithm exactly, including the REJECTION LOOP.
     *
     * <p>The loop is not optional and not an optimisation: the obvious {@code next(31) % bound} is BIASED
     * whenever bound does not divide 2^31, because the low residues get one extra representative each. Stock
     * rejects the values in that overhang and redraws, which is what makes the distribution uniform and what
     * makes the sequence bit-for-bit reproducible against the JDK for a given seed -- the property this class's
     * comment already claims. The power-of-two case is the stock fast path.
     */
    public int nextInt(int bound)
    {
        if (bound <= 0)
        {
            throw new IllegalArgumentException("bound must be positive");
        }
        if ((bound & -bound) == bound)
        {
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits;
        int val;
        do
        {
            bits = next(31);
            val = bits % bound;
        }
        while (bits - val + (bound - 1) < 0);
        return val;
    }

    public boolean nextBoolean()
    {
        return next(1) != 0;
    }

    /**
     * Fill {@code bytes} with random bytes.
     *
     * <p>The JDK's OWN algorithm, kept bit for bit like the rest of this class: one {@code nextInt()} per
     * FOUR bytes, taken low byte first, with a short final group drawing a whole int and discarding the
     * spare bytes. Drawing a fresh int per byte would be a perfectly good random fill and would NOT
     * reproduce the JDK's sequence for a given seed -- which is what this class promises, and what lets a
     * seeded test compare joe-ng's output against a host JVM's.
     *
     * @param bytes the array to fill
     */
    public void nextBytes(byte[] bytes)
    {
        int i = 0;
        int len = bytes.length;
        while (i < len)
        {
            int rnd = nextInt();
            int n = len - i < 4 ? len - i : 4;
            while (n > 0)
            {
                bytes[i] = (byte) rnd;
                i = i + 1;
                n = n - 1;
                rnd = rnd >> 8;
            }
        }
    }
}
