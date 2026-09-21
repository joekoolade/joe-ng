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
 * {@code next}/{@code nextInt}/{@code nextLong} sequence is bit-for-bit the JDK's for a given seed.
 */
public class Random
{
    private long seed;

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1L;

    public Random()
    {
        this(System.nanoTime());
    }

    public Random(long s)
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
