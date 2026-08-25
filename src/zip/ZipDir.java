package zip;

/**
 * A read-only zip/jar directory over a whole archive held in memory — the random-access half of joe-ng's zip
 * support. Stock {@code java.util.zip.ZipFile} reaches its entries through {@code RandomAccessFile},
 * {@code java.nio.file} attributes and native {@code zip_util}, none of which exist on metal, so this parses
 * the archive itself: find the End Of Central Directory record, walk the central directory, and serve entries
 * by name — {@link #read} returns an entry's uncompressed bytes, inflating through {@link Inflate}.
 *
 * <p>Strictly JDK-free (primitive arrays + int math, no imports, no exceptions), so the same code runs on the
 * seed JVM under {@code test/zip/ZipTest} and compiles into the bare-metal image via our own baseline compiler.
 * This is what lets the on-metal class loader pull {@code .class} bytes straight out of a jar.
 *
 * <p>Supported entry methods are STORED (0) and DEFLATE (8) — the only two a javac-produced jar uses.
 * Zip64 archives (more than 65534 entries, or over 4 GiB) and encrypted entries are rejected by {@link #open}
 * rather than silently mis-read.
 */
public final class ZipDir
{
    /** Signature of the End Of Central Directory record. */
    private static final int SIG_END = 0x06054B50;

    /** Signature of a central-directory file header. */
    private static final int SIG_CEN = 0x02014B50;

    /** Signature of a local file header. */
    private static final int SIG_LOC = 0x04034B50;

    /** Fixed size of the EOCD record, before any archive comment. */
    private static final int END_HDR = 22;

    /** Entry compression methods we decode. */
    public static final int STORED = 0;
    public static final int DEFLATED = 8;

    private byte[] zip;
    private int[] cen;                                 // per-entry offset of its central-directory header
    private int count;

    /** The archive bytes this directory was opened over. */
    public byte[] bytes()
    {
        return zip;
    }

    /** How many entries the archive holds (0 until a successful {@link #open}). */
    public int count()
    {
        return count;
    }

    /**
     * Parse {@code archive}'s central directory. Returns false if it is not a zip, is truncated, or uses
     * Zip64 — in which case the directory stays empty rather than answering from half-read state.
     */
    public boolean open(byte[] archive)
    {
        zip = archive;
        count = 0;
        cen = null;
        int end = findEnd(archive);
        if (end < 0)
        {
            return false;
        }
        int total = u16(archive, end + 10);
        int cdSize = u32(archive, end + 12);
        int cdOff = u32(archive, end + 16);
        if (total == 0xFFFF || cdOff == -1 || cdSize == -1)
        {
            return false;                              // Zip64: the real values live in a record we don't read
        }
        if (cdOff < 0 || cdSize < 0 || cdOff + cdSize > archive.length)
        {
            return false;
        }
        int[] offs = new int[total];
        int at = cdOff;
        int i = 0;
        while (i < total)
        {
            if (at + 46 > archive.length || u32(archive, at) != SIG_CEN)
            {
                return false;
            }
            offs[i] = at;
            at = at + 46 + u16(archive, at + 28) + u16(archive, at + 30) + u16(archive, at + 32);
            i += 1;
        }
        cen = offs;
        count = total;
        return true;
    }

    /** Scan back from the end of the archive for the EOCD signature; its offset, or -1. */
    private static int findEnd(byte[] archive)
    {
        int from = archive.length - END_HDR;
        int stop = from - 65535;                       // the archive comment can be at most 64 KiB
        if (stop < 0)
        {
            stop = 0;
        }
        while (from >= stop)
        {
            if (from >= 0 && u32(archive, from) == SIG_END)
            {
                return from;
            }
            from -= 1;
        }
        return -1;
    }

    // ---------------------------------------------------------------- per-entry accessors

    /** Length in bytes of entry {@code i}'s name. */
    public int nameLen(int i)
    {
        return u16(zip, cen[i] + 28);
    }

    /** Entry {@code i}'s name as a fresh byte array (UTF-8 as stored in the archive). */
    public byte[] name(int i)
    {
        int len = nameLen(i);
        int at = cen[i] + 46;
        byte[] out = new byte[len];
        int k = 0;
        while (k < len)
        {
            out[k] = zip[at + k];
            k += 1;
        }
        return out;
    }

    /** Entry {@code i}'s compression method: {@link #STORED} or {@link #DEFLATED}. */
    public int method(int i)
    {
        return u16(zip, cen[i] + 10);
    }

    /** Entry {@code i}'s uncompressed size in bytes. */
    public int size(int i)
    {
        return u32(zip, cen[i] + 24);
    }

    /** Entry {@code i}'s compressed size in bytes. */
    public int csize(int i)
    {
        return u32(zip, cen[i] + 20);
    }

    /** Entry {@code i}'s stored CRC-32 of the uncompressed data. */
    public int crc(int i)
    {
        return u32(zip, cen[i] + 16);
    }

    /** Entry {@code i}'s MS-DOS modification time/date, packed as the zip stores it. */
    public int dosTime(int i)
    {
        return u32(zip, cen[i] + 12);
    }

    /** True when entry {@code i} is a directory (a zero-length entry whose name ends in '/'). */
    public boolean isDirectory(int i)
    {
        int len = nameLen(i);
        return len > 0 && zip[cen[i] + 46 + len - 1] == (byte) '/';
    }

    /** The index of the entry named {@code name}, or -1. */
    public int find(byte[] name)
    {
        return find(name, name.length);
    }

    /** The index of the entry named by the first {@code len} bytes of {@code name}, or -1. Callers that reuse
     *  one scratch buffer for many lookups (the on-metal class loader does) need the explicit length. */
    public int find(byte[] name, int len)
    {
        int i = 0;
        while (i < count)
        {
            if (nameLen(i) == len && nameMatches(i, name, len))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    private boolean nameMatches(int i, byte[] name, int len)
    {
        int at = cen[i] + 46;
        int k = 0;
        while (k < len)
        {
            if (zip[at + k] != name[k])
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    // ---------------------------------------------------------------- entry data

    /** Offset of entry {@code i}'s compressed data, read through its local header, or -1 if malformed. */
    public int dataOffset(int i)
    {
        int loc = u32(zip, cen[i] + 42);
        if (loc < 0 || loc + 30 > zip.length || u32(zip, loc) != SIG_LOC)
        {
            return -1;
        }
        int at = loc + 30 + u16(zip, loc + 26) + u16(zip, loc + 28);
        if (at < 0 || at > zip.length)
        {
            return -1;
        }
        return at;
    }

    /**
     * Entry {@code i}'s uncompressed bytes as a fresh array, or null if it is a directory, uses an unsupported
     * method (anything but STORED/DEFLATE — an encrypted entry lands here), or does not decode to its stored
     * size.
     */
    public byte[] read(int i)
    {
        if (isDirectory(i))
        {
            return null;
        }
        int at = dataOffset(i);
        int csize = csize(i);
        int size = size(i);
        if (at < 0 || size < 0 || csize < 0 || at + csize > zip.length)
        {
            return null;
        }
        if ((u16(zip, cen[i] + 8) & 1) != 0)
        {
            return null;                               // bit 0 = encrypted; joe-ng reads plain archives only
        }
        byte[] out = new byte[size];
        int method = method(i);
        if (method == STORED)
        {
            int k = 0;
            while (k < size && k < csize)
            {
                out[k] = zip[at + k];
                k += 1;
            }
            return out;
        }
        if (method != DEFLATED)
        {
            return null;
        }
        int got = Inflate.inflate(zip, at, csize, out, 0, size);
        if (got != size)
        {
            return null;
        }
        return out;
    }

    /** Entry {@code name}'s uncompressed bytes, or null if the archive has no such entry. */
    public byte[] read(byte[] name)
    {
        int i = find(name);
        if (i < 0)
        {
            return null;
        }
        return read(i);
    }

    // ---------------------------------------------------------------- little-endian readers

    private static int u16(byte[] b, int at)
    {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int at)
    {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16)
                | ((b[at + 3] & 0xFF) << 24);
    }
}
