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
}
