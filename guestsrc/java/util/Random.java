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
}
