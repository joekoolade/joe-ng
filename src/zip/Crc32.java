package zip;

/**
 * CRC-32 (the reflected IEEE 802.3 polynomial 0xEDB88320) — the checksum every zip entry carries and the one
 * {@code java.util.zip.CRC32} computes. Stock {@code CRC32} is native (an intrinsic over hardware CRC
 * instructions), so on metal this is the implementation the {@code java.util.zip.CRC32} overlay calls.
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports), so the same code runs on the seed JVM under
 * {@code test/zip/ZipTest} and compiles into the bare-metal image via our own baseline compiler.
 *
 * <p>Values are carried in the running form stock {@code CRC32} exposes: {@link #update} takes and returns the
 * checksum with no pre/post inversion applied, exactly like {@code CRC32.updateBytes0}.
 */
public final class Crc32
{
    private static final int[] TABLE = buildTable();

    private Crc32()
    {
    }

    private static int[] buildTable()
    {
        int[] t = new int[256];
        int n = 0;
        while (n < 256)
        {
            int c = n;
            int k = 0;
            while (k < 8)
            {
                if ((c & 1) != 0)
                {
                    c = 0xEDB88320 ^ (c >>> 1);
                }
                else
                {
                    c = c >>> 1;
                }
                k += 1;
            }
            t[n] = c;
            n += 1;
        }
        return t;
    }

    /** Fold {@code b[off..off+len)} into the running checksum {@code crc} (stock {@code CRC32} convention). */
    public static int update(int crc, byte[] b, int off, int len)
    {
        int c = ~crc;
        int i = 0;
        while (i < len)
        {
            c = TABLE[(c ^ b[off + i]) & 0xFF] ^ (c >>> 8);
            i += 1;
        }
        return ~c;
    }

    /** Fold a single byte into the running checksum. */
    public static int updateByte(int crc, int b)
    {
        int c = ~crc;
        c = TABLE[(c ^ b) & 0xFF] ^ (c >>> 8);
        return ~c;
    }

    /** The CRC-32 of {@code b[off..off+len)} on its own. */
    public static int of(byte[] b, int off, int len)
    {
        return update(0, b, off, len);
    }
}
