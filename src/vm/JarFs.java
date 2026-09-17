/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-25
 */
package vm;

import board.bcm2711.Uart;
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
    private static boolean cacheFullSaid;              // the overflow report fires once, not per lookup

    private static byte[] key;                         // scratch: "<internal name>.class"

    // ---- fetch counters ---------------------------------------------------------------------------------
    // The jar FETCH is the unnamed term in the mark: a launcher boot charges it to whichever pass triggered
    // it (`pull` or `struct`), and batch 188 of a launcher boot is 564ms of it. Inlining the inflater's inner
    // loops cut `pull` 15-18% on the inflate-heavy batches and moved batch 188 by 0.14%, so THAT batch is not
    // decoding -- it is something else on this path. These rank the candidates. Plain counters, not timers:
    // every one of them sits inside a per-item loop, where two clock reads would be a visible share of what
    // is being measured -- the lesson this arc has now paid for three times.
    public static long jfLookups;                      // entry() calls
    public static long jfScanSteps;                    // cache-name comparisons -- grows with cacheCount
    public static long jfFinds;                        // central-directory searches (a cache MISS)
    public static long jfInflated;                     // bytes handed back by dir.read, i.e. actually decoded

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
        cacheFullSaid = false;
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
    /**
     * True if the open classpath jar carries an entry at exactly this path.
     *
     * <p>Unlike {@link #classBytes} the path is taken VERBATIM: a resource name is not a class name, so it
     * gets no {@code .class} suffix and no dot-to-slash rewriting. Nothing is cached -- resource lookups are
     * rare (a handful per run) and caching them would evict class entries, which are asked for constantly.
     */
    public static boolean hasResource(long namePtr, int len)
    {
        if (dir == null || len <= 0 || len > MAXNAME)
        {
            return false;
        }
        int k = 0;
        while (k < len)
        {
            key[k] = (byte) Magic.load8(namePtr + k);
            k += 1;
        }
        return dir.find(key, len) >= 0;
    }

    /**
     * The uncompressed bytes of the entry at exactly this path, or null when no jar is open, the path is too
     * long, or the archive has no such entry.
     *
     * <p>VERBATIM like {@link #hasResource} -- a resource name is not a class name -- and uncached for the same
     * reason: resource reads are a handful per run, and caching them would evict class entries.
     *
     * <p>The array is ZipDir's own, allocated in whichever world called in. Callers handing it to guest code
     * must copy it into a properly-typed guest array ({@code Loader.guestBytes}) rather than pass it through.
     */
    public static byte[] resourceData(long namePtr, int len)
    {
        if (dir == null || len <= 0 || len > MAXNAME)
        {
            return null;
        }
        int k = 0;
        while (k < len)
        {
            key[k] = (byte) Magic.load8(namePtr + k);
            k += 1;
        }
        int idx = dir.find(key, len);
        return idx < 0 ? null : dir.read(idx);
    }

    private static int entry(long namePtr, int len)
    {
        jfLookups += 1;
        int i = 0;
        while (i < cacheCount)
        {
            jfScanSteps += 1;
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
        jfFinds += 1;
        int idx = dir.find(key, len + 6);
        byte[] data = idx < 0 ? null : dir.read(idx);
        if (data != null)
        {
            jfInflated += data.length;
        }
        return remember(namePtr, len, data);
    }

    /** Record a lookup's outcome; the slot index for a hit, -1 for a miss (or a full cache). */
    private static int remember(long namePtr, int len, byte[] data)
    {
        if (cacheCount >= MAXCACHE)
        {
            // "keep answering, just without memory" is what the comment here USED TO SAY, and it is not what
            // this does: returning -1 makes classBytes answer 0, so a class the jar DOES hold is reported
            // ABSENT and never loads. That is the silent-wrong-answer shape, in the one place this VM can
            // least afford it, so it is reported ONCE by name rather than left to surface somewhere else.
            if (!cacheFullSaid)
            {
                cacheFullSaid = true;
                Uart.write(Magic.bytes("\n  JAR NAME CACHE FULL at "));
                VM.printDec(MAXCACHE);
                Uart.write(Magic.bytes(" -- a class the jar HOLDS now reads as absent; first overflow is "));
                int w = 0;
                while (w < len)
                {
                    Uart.putc((int) Magic.load8(namePtr + w));
                    w += 1;
                }
                Uart.putc(0x0A);
            }
            return -1;
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
