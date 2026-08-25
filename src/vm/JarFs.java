package vm;

import magic.Magic;
import zip.ZipDir;

/**
 * The classpath jar: one zip archive from the RAMFS whose {@code .class} entries the on-metal class loader can
 * pull by name. {@code /etc/init}'s {@code classpath=<path>} line names it, {@link VM#launchInit} opens it, and
 * {@link VM#dirBytes}/{@link VM#dirLen} fall back to it when the writer-baked class directory has no such
 * class — so a program and its whole closure can live in a jar the image has never seen, exactly as a
 * classpath jar works on a hosted JVM.
 *
 * <p>JDK-free (primitive arrays, {@link Magic} raw access, {@link zip.ZipDir}), because it is reached from the
 * loader's own class-resolution path and compiles into the image with our own baseline compiler.
 *
 * <p>Lookups are cached — positively AND negatively. Class resolution asks about every name a classfile
 * mentions, and almost all of them are java.base classes the jar does not hold; without negative caching each
 * one would re-scan the whole central directory. A cached hit owns a copy of the inflated classfile in a
 * {@link Heap#allocData} blob, which is what the loader registers, so the archive's own bytes are never handed
 * out.
 */
public final class JarFs
{
    /** How many distinct class names are remembered (hits and misses together). */
    private static final int MAXCACHE = 2048;

    /** Longest class name we will look for; longer names simply miss. */
    private static final int MAXNAME = 480;

    private static ZipDir dir;                         // the opened classpath jar, or null

    private static long[] cacheName;                   // blob holding the class's internal name ...
    private static int[] cacheNameLen;                 // ... and its length
    private static long[] cacheBlob;                   // the inflated classfile blob, 0 for a known miss
    private static int[] cacheBlobLen;
    private static int cacheCount;

    private static byte[] key;                         // scratch: "<internal name>.class"

    private JarFs()
    {
    }

    /** True once {@link #open} has accepted an archive. */
    public static boolean isOpen()
    {
        return dir != null;
    }

    /** How many entries the classpath jar holds (0 when none is open). */
    public static int count()
    {
        return dir == null ? 0 : dir.count();
    }

    /** The open archive, for callers that want entries other than classes; null when none is open. */
    public static ZipDir dir()
    {
        return dir;
    }

    /**
     * Open the RAMFS file {@code path} as the classpath jar, copying it out of the image into the heap (the
     * directory indexes it in place). Returns false if the file is missing or is not a readable zip.
     */
    public static boolean open(byte[] path)
    {
        long e = VM.fileFind(path);
        if (e == 0L)
        {
            return false;
        }
        long src = Magic.load64(e + 16L);
        int len = (int) Magic.load64(e + 24L);
        byte[] raw = new byte[len];
        int i = 0;
        while (i < len)
        {
            raw[i] = (byte) Magic.load8(src + i);
            i += 1;
        }
        ZipDir opened = new ZipDir();
        if (!opened.open(raw))
        {
            return false;
        }
        dir = opened;
        cacheName = new long[MAXCACHE];
        cacheNameLen = new int[MAXCACHE];
        cacheBlob = new long[MAXCACHE];
        cacheBlobLen = new int[MAXCACHE];
        cacheCount = 0;
        key = new byte[MAXNAME + 8];
        return true;
    }

    /** Address of the classfile bytes for the internal name at {@code [namePtr, namePtr+len)}, or 0. */
    public static long classBytes(long namePtr, long len)
    {
        int i = entry(namePtr, (int) len);
        return i < 0 ? 0L : cacheBlob[i];
    }

    /** Companion to {@link #classBytes}: the classfile's byte length, or 0. */
    public static long classLen(long namePtr, long len)
    {
        int i = entry(namePtr, (int) len);
        return i < 0 ? 0L : cacheBlobLen[i];
    }

    /**
     * The cache slot for a class name, filling it on first ask: the jar entry is located, inflated, and copied
     * into a heap blob. Returns -1 when no jar is open, the name is too long, or the entry is absent — a miss
     * is itself cached (slot with a 0 blob) so the next ask costs one name compare instead of a directory scan.
     */
    private static int entry(long namePtr, int len)
    {
        int i = 0;
        while (i < cacheCount)
        {
            if (cacheNameLen[i] == len && sameName(cacheName[i], namePtr, len))
            {
                return cacheBlob[i] == 0L ? -1 : i;
            }
            i += 1;
        }
        if (dir == null || len <= 0 || len > MAXNAME)
        {
            return -1;
        }
        int k = 0;
        while (k < len)
        {
            key[k] = (byte) Magic.load8(namePtr + k);
            k += 1;
        }
        key[len] = (byte) '.';
        key[len + 1] = (byte) 'c';
        key[len + 2] = (byte) 'l';
        key[len + 3] = (byte) 'a';
        key[len + 4] = (byte) 's';
        key[len + 5] = (byte) 's';
        int idx = dir.find(key, len + 6);
        byte[] data = idx < 0 ? null : dir.read(idx);
        return remember(namePtr, len, data);
    }

    /** Record a lookup's outcome; the slot index for a hit, -1 for a miss (or a full cache). */
    private static int remember(long namePtr, int len, byte[] data)
    {
        if (cacheCount >= MAXCACHE)
        {
            return -1;                                 // full: keep answering, just without memory
        }
        long nameBlob = Heap.allocData(len);
        int k = 0;
        while (k < len)
        {
            Magic.store8(nameBlob + k, Magic.load8(namePtr + k));
            k += 1;
        }
        long blob = 0L;
        int blobLen = 0;
        if (data != null)
        {
            blobLen = data.length;
            blob = Heap.allocData(blobLen);
            k = 0;
            while (k < blobLen)
            {
                Magic.store8(blob + k, data[k]);
                k += 1;
            }
        }
        cacheName[cacheCount] = nameBlob;
        cacheNameLen[cacheCount] = len;
        cacheBlob[cacheCount] = blob;
        cacheBlobLen[cacheCount] = blobLen;
        cacheCount += 1;
        return blob == 0L ? -1 : cacheCount - 1;
    }

    private static boolean sameName(long a, long b, int len)
    {
        int i = 0;
        while (i < len)
        {
            if (Magic.load8(a + i) != Magic.load8(b + i))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }
}
