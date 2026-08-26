package zip;

/**
 * Adler-32 (RFC 1950 §9) — the checksum the zlib wrapper carries, and what {@code java.util.zip.Adler32}
 * computes. Stock {@code Adler32} is native, so this is the implementation both the {@code Adler32} overlay
 * and {@link Deflate}'s zlib trailer use.
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports), so the same code runs on the seed JVM under
 * {@code test/zip/ZipTest} and compiles into the bare-metal image via our own baseline compiler.
 *
 * <p>The running value packs the two halves as {@code (b << 16) | a}, exactly as zlib and stock
 * {@code Adler32.getValue()} do; a fresh checksum starts at 1, not 0.
 */
public final class Adler32
{
    /** The largest prime below 65536, which both halves are reduced modulo. */
    private static final int BASE = 65521;

    private Adler32()
    {
    }

    /** The initial value of an Adler-32 checksum: a = 1, b = 0. */
    public static int initial()
    {
        return 1;
    }

    /** Fold {@code b[off..off+len)} into the running checksum {@code adler}. */
    public static int update(int adler, byte[] b, int off, int len)
    {
        int a = adler & 0xFFFF;
        int s = (adler >>> 16) & 0xFFFF;
        int i = 0;
        while (i < len)
        {
            a = (a + (b[off + i] & 0xFF)) % BASE;
            s = (s + a) % BASE;
            i += 1;
        }
        return (s << 16) | a;
    }

    /** Fold a single byte into the running checksum. */
    public static int updateByte(int adler, int value)
    {
        int a = (adler & 0xFFFF);
        int s = (adler >>> 16) & 0xFFFF;
        a = (a + (value & 0xFF)) % BASE;
        s = (s + a) % BASE;
        return (s << 16) | a;
    }

    /** The Adler-32 of {@code b[off..off+len)} on its own. */
    public static int of(byte[] b, int off, int len)
    {
        return update(initial(), b, off, len);
    }
}
