/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-13
 */
package jdk.internal.misc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The microSD card's FAT32 boot partition, through {@link MetalBlock}: the MBR's first FAT32 partition (or a card
 * formatted without a partition table), its BPB, cluster chains, and directories with long file names (VFAT LFN).
 * {@link MetalRamfs} mounts it at {@code /boot}; nothing else calls it.
 *
 * <p>Reads and writes. A write keeps one order -- file DATA, then the FAT, then the DIRECTORY ENTRY -- so a power
 * loss part way leaves at worst clusters nobody owns or a chain longer than its file, both of which {@code fsck}
 * repairs, and never an entry pointing at clusters that are free. Both FAT copies are written. FSInfo's free count
 * is kept exact -- the mount's count, less each allocation, plus each free -- and written after the FAT it
 * describes; a count the card already had as "unknown" stays unknown. Replacing a file by rename
 * switches the target's short entry to the new clusters in ONE sector write, so the target name never goes missing.
 *
 * <p>Every entry keeps its directory, the slot of its short entry and how many long-name slots precede it, which is
 * what an unlink, a rename or a size change rewrites. An unlinked or replaced entry is DETACHED ({@code entryLba
 * < 0}): its clusters stay allocated until {@link #release} (MetalRamfs calls it once no descriptor has it open).
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. Not thread-safe: {@code MetalRamfs} calls it under its lock.
 *
 * <p>Sources: Microsoft's "FAT: General Overview of On-Disk Format" (BPB and FSInfo fields, FAT32 entry masks, the
 * LFN checksum and layout, the short-name basis and numeric tail) and the Windows NT case bits in byte 12 of a short
 * entry, which macOS and Linux also write.
 */
public final class MetalFat32
{
    private static final int SECTOR = MetalBlock.SECTOR;
    private static final int FAT_WINDOW = 32;            // FAT sectors cached at once (4096 entries)
    private static final int RUN_SECTORS = 128;          // the most one data transfer moves (64 KiB)
    private static final int EOC = 0x0FFFFFF8;           // at or above: end of chain
    private static final int EOC_MARK = 0x0FFFFFFF;      // what a new chain end is written as
    private static final int BAD = 0x0FFFFFF7;

    private static final int ATTR_VOLUME = 0x08;
    private static final int ATTR_DIR = 0x10;
    private static final int ATTR_ARCHIVE = 0x20;
    private static final int ATTR_LFN = 0x0F;
    private static final int[] LFN_OFFS = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
    private static final String SHORT_OK = "!#$%&'()-@^_`{}~";

    /** No free cluster is left. */
    public static final class NoSpace extends IOException
    {
        NoSpace()
        {
            super("FAT32: no free cluster");
        }
    }

    /** A name FAT cannot hold. */
    public static final class BadName extends IOException
    {
        BadName(String name)
        {
            super("FAT32: not a valid name: " + name);
        }
    }

    /** One file or directory of the partition. */
    public static final class Entry
    {
        public String name;
        public final boolean dir;
        public int firstCluster;                         // 0 for an empty file
        public long size;
        public long mtimeNanos;                          // the FAT's local time, taken as UTC
        public long entryLba;                            // where the short entry is; < 0 for the root or DETACHED
        public int entryOffset;
        Entry parent;                                    // the directory holding the entry
        int slot;                                        // the short entry's 32-byte slot in the parent
        int lfnSlots;                                    // long-name slots just before it
        int cursorIndex = -1;                            // the last cluster a transfer reached, by chain index
        int cursorCluster;
        int chainLen = -1;                               // clusters in the chain, -1 until walked
        int chainLast;

        Entry(String name, boolean dir, int firstCluster, long size, long mtimeNanos, long entryLba, int entryOffset)
        {
            this.name = name;
            this.dir = dir;
            this.firstCluster = firstCluster;
            this.size = size;
            this.mtimeNanos = mtimeNanos;
            this.entryLba = entryLba;
            this.entryOffset = entryOffset;
        }
    }

    public final long partitionLba;
    public final int sectorsPerCluster;
    public final int rootCluster;
    public final String label;
    private final long fatLba;
    private final long fatSize;                          // sectors per FAT copy
    private final int fats;
    private final long dataLba;
    private final int clusterCount;
    private final int clusterBytes;
    private final long fsInfoLba;                        // -1 if there is no valid FSInfo sector
    private byte[] fsInfo;                               // the FSInfo sector as read at mount
    private long freeCount = -1L;                        // FSInfo's free-cluster count, -1 if unknown
    private boolean fsInfoDirty;
    private int freeHint = 2;                            // where the next free-cluster search starts
    private final Entry rootEntry;

    private final byte[] fatBuf = new byte[FAT_WINDOW * SECTOR];
    private long fatBufLba = -1L;
    private int dirtyLo = -1;                            // the window's changed sectors, -1 if none
    private int dirtyHi = -1;
    private final byte[] run = new byte[RUN_SECTORS * SECTOR];

    private MetalFat32(long partitionLba, byte[] bpb) throws IOException
    {
        int bps = u16(bpb, 11);
        int spc = bpb[13] & 0xFF;
        int reserved = u16(bpb, 14);
        int nfats = bpb[16] & 0xFF;
        long fatSz = u32(bpb, 36);
        long total = u32(bpb, 32);
        if (bps != SECTOR)
        {
            throw new IOException("FAT32: " + bps + " bytes per sector (only 512 is supported)");
        }
        if (spc == 0 || (spc & (spc - 1)) != 0 || spc > RUN_SECTORS)
        {
            throw new IOException("FAT32: bad sectors per cluster " + spc);
        }
        if (u16(bpb, 22) != 0 || fatSz == 0L || nfats == 0)
        {
            throw new IOException("FAT32: not a FAT32 BPB (sectors/fat16=" + u16(bpb, 22) + ")");
        }
        this.partitionLba = partitionLba;
        this.sectorsPerCluster = spc;
        this.rootCluster = (int) u32(bpb, 44);
        this.fatLba = partitionLba + reserved;
        this.fatSize = fatSz;
        this.fats = nfats;
        this.dataLba = fatLba + nfats * fatSz;
        long dataSectors = total - (reserved + nfats * fatSz);
        long count = Math.min(dataSectors / spc, fatSz * SECTOR / 4L - 2L);
        this.clusterCount = (int) count;
        this.clusterBytes = spc * SECTOR;
        this.label = new String(bpb, 71, 11, StandardCharsets.ISO_8859_1).trim();
        this.rootEntry = new Entry("", true, rootCluster, 0L, 0L, -1L, 0);
        int info = u16(bpb, 48);
        long infoLba = -1L;
        if (info != 0 && info != 0xFFFF && info < reserved)
        {
            byte[] s = new byte[SECTOR];
            MetalBlock.read(partitionLba + info, s, 0, 1);
            if (u32(s, 0) == 0x41615252L && u32(s, 484) == 0x61417272L)
            {
                infoLba = partitionLba + info;
                fsInfo = s;
                long free = u32(s, 488);
                if (free <= count)
                {
                    freeCount = free;
                }
                long hint = u32(s, 492);
                if (hint >= 2L && hint < clusterCount + 2L)
                {
                    freeHint = (int) hint;
                }
            }
        }
        this.fsInfoLba = infoLba;
    }

    /**
     * Find and open the FAT32 partition: the MBR's first entry of type 0x0B or 0x0C, else sector 0 itself if it is
     * a FAT32 boot sector (a card formatted without a partition table).
     */
    public static MetalFat32 mount() throws IOException
    {
        byte[] s = new byte[SECTOR];
        MetalBlock.read(0L, s, 0, 1);
        if ((s[510] & 0xFF) != 0x55 || (s[511] & 0xFF) != 0xAA)
        {
            throw new IOException("FAT32: sector 0 has no 55aa signature");
        }
        if (isFat32Bpb(s))
        {
            return new MetalFat32(0L, s);
        }
        for (int i = 0; i < 4; i++)
        {
            int e = 446 + i * 16;
            int type = s[e + 4] & 0xFF;
            if (type == 0x0B || type == 0x0C)
            {
                long start = u32(s, e + 8);
                byte[] bpb = new byte[SECTOR];
                MetalBlock.read(start, bpb, 0, 1);
                return new MetalFat32(start, bpb);
            }
        }
        throw new IOException("FAT32: no FAT32 partition in the MBR");
    }

    private static boolean isFat32Bpb(byte[] s)
    {
        int jmp = s[0] & 0xFF;
        return (jmp == 0xEB || jmp == 0xE9) && new String(s, 82, 5, StandardCharsets.ISO_8859_1).equals("FAT32");
    }

    /** The root directory as an entry (always the same object). */
    public Entry root()
    {
        return rootEntry;
    }

    // ---- the FAT ------------------------------------------------------------------------------------------

    /** The byte offset in {@code fatBuf} of cluster {@code c}'s entry, loading its window (after a flush). */
    private int fatOffset(int c) throws IOException
    {
        if (c < 2 || c >= clusterCount + 2)
        {
            throw new IOException("FAT32: cluster " + c + " out of range");
        }
        long byteOff = (long) c * 4L;
        long lba = fatLba + byteOff / SECTOR;
        long win = lba - (lba - fatLba) % FAT_WINDOW;
        if (win != fatBufLba)
        {
            flushFat();
            fatBufLba = -1L;
            int n = (int) Math.min((long) FAT_WINDOW, fatLba + fatSize - win);
            MetalBlock.read(win, fatBuf, 0, n);
            fatBufLba = win;
        }
        return (int) ((lba - win) * SECTOR + byteOff % SECTOR);
    }

    /** Cluster {@code c}'s FAT entry, masked to 28 bits and not checked. */
    private int fatGet(int c) throws IOException
    {
        return (int) (u32(fatBuf, fatOffset(c)) & 0x0FFFFFFFL);
    }

    /** Set cluster {@code c}'s FAT entry in the cached window; the top four bits are kept, as the spec asks. */
    private void fatSet(int c, int v) throws IOException
    {
        int o = fatOffset(c);
        long old = u32(fatBuf, o);
        put32(fatBuf, o, (old & 0xF0000000L) | (v & 0x0FFFFFFFL));
        int s = o / SECTOR;
        dirtyLo = dirtyLo < 0 ? s : Math.min(dirtyLo, s);
        dirtyHi = Math.max(dirtyHi, s);
    }

    /** Write the window's changed sectors to every FAT copy, then FSInfo if its count or hint moved. */
    private void flushFat() throws IOException
    {
        if (dirtyLo >= 0)
        {
            int n = dirtyHi - dirtyLo + 1;
            for (int f = 0; f < fats; f++)
            {
                MetalBlock.write(fatBufLba + f * fatSize + dirtyLo, fatBuf, dirtyLo * SECTOR, n);
            }
            dirtyLo = -1;
            dirtyHi = -1;
        }
        if (fsInfoDirty && fsInfoLba >= 0L)
        {
            put32(fsInfo, 488, freeCount < 0L ? 0xFFFFFFFFL : freeCount);
            put32(fsInfo, 492, freeHint);
            MetalBlock.write(fsInfoLba, fsInfo, 0, 1);
            fsInfoDirty = false;
        }
    }

    /** The FAT entry of cluster {@code c}: the next cluster, or a value at or above {@code EOC}. */
    private int next(int c) throws IOException
    {
        int v = fatGet(c);
        if (v == BAD || v < 2)
        {
            throw new IOException("FAT32: broken chain at cluster " + c + " (entry 0x" + Integer.toHexString(v) + ")");
        }
        return v;
    }

    private long lbaOf(int cluster)
    {
        return dataLba + (long) (cluster - 2) * sectorsPerCluster;
    }

    /** A free cluster, now the end of a chain (in the cached FAT): {@code prev + 1} if that one is free. */
    private int allocate(int prev) throws IOException
    {
        int c = -1;
        if (prev >= 2 && prev + 1 < clusterCount + 2 && fatGet(prev + 1) == 0)
        {
            c = prev + 1;
        }
        for (int i = 0; c < 0 && i < clusterCount; i++)
        {
            int k = 2 + (freeHint - 2 + i) % clusterCount;
            if (fatGet(k) == 0)
            {
                c = k;
            }
        }
        if (c < 0)
        {
            throw new NoSpace();
        }
        fatSet(c, EOC_MARK);
        freeHint = c + 1 < clusterCount + 2 ? c + 1 : 2;
        if (freeCount > 0L)
        {
            freeCount -= 1L;
        }
        fsInfoDirty = true;
        return c;
    }

    /** Free the chain from {@code c} (in the cached FAT), stopping at its end or at anything that is not a link. */
    private void freeChain(int c) throws IOException
    {
        int steps = 0;
        while (c >= 2 && c < clusterCount + 2 && steps <= clusterCount)
        {
            int nx = fatGet(c);
            fatSet(c, 0);
            if (freeCount >= 0L)
            {
                freeCount += 1L;
            }
            fsInfoDirty = true;
            steps += 1;
            if (nx == BAD || nx >= EOC)
            {
                break;
            }
            c = nx;
        }
    }

    /** Walk {@code f}'s chain once to learn its length and last cluster. */
    private void chainInfo(Entry f) throws IOException
    {
        if (f.chainLen >= 0)
        {
            return;
        }
        if (f.firstCluster < 2)
        {
            f.chainLen = 0;
            f.chainLast = 0;
            return;
        }
        int n = 1;
        int c = f.firstCluster;
        int v = next(c);
        while (v < EOC)
        {
            c = v;
            n += 1;
            if (n > clusterCount)
            {
                throw new IOException("FAT32: chain of " + f.name + " loops");
            }
            v = next(c);
        }
        f.chainLen = n;
        f.chainLast = c;
    }

    /** Grow {@code f}'s chain to hold {@code bytes} bytes (in the cached FAT). */
    private void ensureClusters(Entry f, long bytes) throws IOException
    {
        chainInfo(f);
        long need = (bytes + clusterBytes - 1) / clusterBytes;
        while (f.chainLen < need)
        {
            int c = allocate(f.chainLast);
            if (f.chainLen == 0)
            {
                f.firstCluster = c;
            }
            else
            {
                fatSet(f.chainLast, c);
            }
            f.chainLast = c;
            f.chainLen += 1;
        }
    }

    // ---- directories --------------------------------------------------------------------------------------

    /** The entries of directory {@code d}, without "." and "..". */
    public List<Entry> list(Entry d) throws IOException
    {
        List<Entry> out = new ArrayList<>();
        byte[] buf = new byte[clusterBytes];
        int perCluster = clusterBytes / 32;
        char[] lfn = null;
        int lfnNext = 0;                                 // the sequence number the next LFN part must carry
        int lfnSum = 0;
        int lfnParts = 0;
        int c = d.firstCluster;
        int idx = 0;
        while (c >= 2 && c < EOC)
        {
            if (idx >= clusterCount)
            {
                throw new IOException("FAT32: directory chain loops");
            }
            long lba = lbaOf(c);
            MetalBlock.read(lba, buf, 0, sectorsPerCluster);
            for (int o = 0; o < clusterBytes; o += 32)
            {
                int first = buf[o] & 0xFF;
                if (first == 0x00)
                {
                    return out;
                }
                int attr = buf[o + 11] & 0xFF;
                if (first == 0xE5)
                {
                    lfn = null;
                    continue;
                }
                if (attr == ATTR_LFN)
                {
                    int seq = first & 0x1F;
                    int sum = buf[o + 13] & 0xFF;
                    if ((first & 0x40) != 0)
                    {
                        lfn = seq == 0 ? null : new char[seq * 13];
                        lfnNext = seq;
                        lfnSum = sum;
                        lfnParts = seq;
                    }
                    if (lfn == null || seq != lfnNext || sum != lfnSum || seq == 0)
                    {
                        lfn = null;
                        continue;
                    }
                    putLfn(lfn, (seq - 1) * 13, buf, o);
                    lfnNext = seq - 1;
                    continue;
                }
                if ((attr & ATTR_VOLUME) != 0)
                {
                    lfn = null;
                    continue;
                }
                String name;
                int parts = 0;
                if (lfn != null && lfnNext == 0 && lfnSum == shortSum(buf, o))
                {
                    name = lfnString(lfn);
                    parts = lfnParts;
                }
                else
                {
                    name = shortName(buf, o);
                }
                lfn = null;
                if (name.equals(".") || name.equals(".."))
                {
                    continue;
                }
                int clus = (u16(buf, o + 20) << 16) | u16(buf, o + 26);
                boolean isDir = (attr & ATTR_DIR) != 0;
                long size = isDir ? 0L : u32(buf, o + 28);
                long mtime = fatTime(u16(buf, o + 24), u16(buf, o + 22));
                Entry e = new Entry(name, isDir, clus, size, mtime, lba + o / SECTOR, o % SECTOR);
                e.parent = d;
                e.slot = idx * perCluster + o / 32;
                e.lfnSlots = parts;
                out.add(e);
            }
            c = next(c);
            idx += 1;
        }
        return out;
    }

    /** The card byte address of slot {@code slot} of directory {@code d}, or -1 past the end of its chain. */
    private long slotAddr(Entry d, int slot) throws IOException
    {
        int perCluster = clusterBytes / 32;
        int c = d.firstCluster;
        for (int i = slot / perCluster; i > 0; i--)
        {
            c = next(c);
            if (c >= EOC)
            {
                return -1L;
            }
        }
        return lbaOf(c) * SECTOR + (long) (slot % perCluster) * 32L;
    }

    /**
     * Write {@code k} 32-byte entries from {@code ents} into slots {@code first..first+k-1} of {@code d}, or mark
     * them deleted if {@code ents} is null. Sectors go out in slot order, so the short entry -- the last slot -- is
     * in the last sector written.
     */
    private void putSlots(Entry d, int first, int k, byte[] ents) throws IOException
    {
        byte[] sec = new byte[SECTOR];
        long cur = -1L;
        for (int i = 0; i < k; i++)
        {
            long a = slotAddr(d, first + i);
            if (a < 0L)
            {
                throw new IOException("FAT32: directory slot " + (first + i) + " is past its chain");
            }
            long lba = a / SECTOR;
            int o = (int) (a % SECTOR);
            if (lba != cur)
            {
                if (cur >= 0L)
                {
                    MetalBlock.write(cur, sec, 0, 1);
                }
                MetalBlock.read(lba, sec, 0, 1);
                cur = lba;
            }
            if (ents == null)
            {
                sec[o] = (byte) 0xE5;
            }
            else
            {
                System.arraycopy(ents, i * 32, sec, o, 32);
            }
        }
        if (cur >= 0L)
        {
            MetalBlock.write(cur, sec, 0, 1);
        }
    }

    /** The first of {@code k} free consecutive slots of {@code d}, growing it by zeroed clusters if it must. */
    private int findSlots(Entry d, int k) throws IOException
    {
        int perCluster = clusterBytes / 32;
        byte[] buf = new byte[clusterBytes];
        int c = d.firstCluster;
        int last = c;
        int idx = 0;
        int runLen = 0;
        int runStart = 0;
        while (c >= 2 && c < EOC)
        {
            MetalBlock.read(lbaOf(c), buf, 0, sectorsPerCluster);
            for (int o = 0; o < clusterBytes; o += 32)
            {
                int first = buf[o] & 0xFF;
                if (first == 0x00 || first == 0xE5)
                {
                    if (runLen == 0)
                    {
                        runStart = idx * perCluster + o / 32;
                    }
                    runLen += 1;
                    if (runLen == k)
                    {
                        return runStart;
                    }
                }
                else
                {
                    runLen = 0;
                }
            }
            last = c;
            c = next(c);
            idx += 1;
            if (idx > clusterCount)
            {
                throw new IOException("FAT32: directory chain loops");
            }
        }
        java.util.Arrays.fill(buf, (byte) 0);
        while (runLen < k)
        {
            int nc = allocate(last);
            MetalBlock.write(lbaOf(nc), buf, 0, sectorsPerCluster);
            fatSet(last, nc);
            flushFat();
            if (runLen == 0)
            {
                runStart = idx * perCluster;
            }
            runLen += perCluster;
            last = nc;
            idx += 1;
            d.chainLen = -1;
        }
        return runStart;
    }

    /** Every live short name (the 11 raw bytes) in directory {@code d}, "." and ".." included. */
    private Set<String> shortNames(Entry d) throws IOException
    {
        Set<String> out = new HashSet<>();
        byte[] buf = new byte[clusterBytes];
        int c = d.firstCluster;
        int idx = 0;
        while (c >= 2 && c < EOC)
        {
            MetalBlock.read(lbaOf(c), buf, 0, sectorsPerCluster);
            for (int o = 0; o < clusterBytes; o += 32)
            {
                int first = buf[o] & 0xFF;
                if (first == 0x00)
                {
                    return out;
                }
                if (first != 0xE5 && (buf[o + 11] & 0xFF) != ATTR_LFN)
                {
                    out.add(new String(buf, o, 11, StandardCharsets.ISO_8859_1));
                }
            }
            c = next(c);
            idx += 1;
            if (idx > clusterCount)
            {
                throw new IOException("FAT32: directory chain loops");
            }
        }
        return out;
    }

    /**
     * Give {@code e} the name {@code name} in directory {@code dir}: long-name slots (unless the name is an exact
     * 8.3 name, written with the NT case bits) and a short entry pointing at {@code e}'s clusters. {@code e}'s
     * location becomes the new short entry's. The old name, if any, is left for the caller to remove.
     */
    private void placeName(Entry dir, String name, int attr, Entry e) throws IOException
    {
        if (!validName(name))
        {
            throw new BadName(name);
        }
        Set<String> taken = shortNames(dir);
        byte[] name11 = new byte[11];
        int nt = exactShort(name, name11);
        int parts = 0;
        if (nt < 0 || taken.contains(new String(name11, 0, 11, StandardCharsets.ISO_8859_1)))
        {
            nt = 0;
            generateShort(name, taken, name11);
            parts = (name.length() + 12) / 13;
        }
        int k = parts + 1;
        byte[] ents = new byte[k * 32];
        int sum = shortSum(name11, 0);
        for (int i = 0; i < parts; i++)
        {
            int seq = parts - i;
            lfnEntry(ents, i * 32, name, seq, i == 0, sum);
        }
        int o = parts * 32;
        System.arraycopy(name11, 0, ents, o, 11);
        ents[o + 11] = (byte) attr;
        ents[o + 12] = (byte) nt;
        putEntryFields(ents, o, e);
        int[] dt = fatDateTime(e.mtimeNanos);
        put16(ents, o + 14, dt[1]);                     // created = modified
        put16(ents, o + 16, dt[0]);
        put16(ents, o + 18, dt[0]);                     // accessed
        flushFat();
        int first = findSlots(dir, k);
        putSlots(dir, first, k, ents);
        long a = slotAddr(dir, first + parts);
        e.parent = dir;
        e.slot = first + parts;
        e.lfnSlots = parts;
        e.entryLba = a / SECTOR;
        e.entryOffset = (int) (a % SECTOR);
        e.name = name;
    }

    /** Cluster, size and modified time of {@code e} into the short entry at {@code b[o..o+32)}. */
    private static void putEntryFields(byte[] b, int o, Entry e)
    {
        put16(b, o + 20, e.firstCluster >>> 16);
        put16(b, o + 26, e.firstCluster & 0xFFFF);
        put32(b, o + 28, e.dir ? 0L : e.size);
        int[] dt = fatDateTime(e.mtimeNanos);
        put16(b, o + 22, dt[1]);
        put16(b, o + 24, dt[0]);
    }

    /** Rewrite {@code e}'s short entry (cluster, size, time) in place; nothing for the root or a detached entry. */
    private void flushEntry(Entry e) throws IOException
    {
        if (e.entryLba < 0L)
        {
            return;
        }
        byte[] sec = new byte[SECTOR];
        MetalBlock.read(e.entryLba, sec, 0, 1);
        putEntryFields(sec, e.entryOffset, e);
        MetalBlock.write(e.entryLba, sec, 0, 1);
    }

    /** Mark {@code e}'s long-name slots and short entry deleted; {@code e} is DETACHED afterwards. */
    private void removeSlots(Entry e) throws IOException
    {
        if (e.entryLba < 0L)
        {
            return;
        }
        putSlots(e.parent, e.slot - e.lfnSlots, e.lfnSlots + 1, null);
        e.entryLba = -1L;
    }

    // ---- changes ------------------------------------------------------------------------------------------

    /**
     * A new empty file or directory {@code name} in {@code dir}, modified at {@code mtimeNanos}. A directory gets
     * one zeroed cluster with its "." and ".." entries, written before the entry that names it.
     */
    public Entry create(Entry dir, String name, boolean isDir, long mtimeNanos) throws IOException
    {
        if (!validName(name))
        {
            throw new BadName(name);
        }
        Entry e = new Entry(name, isDir, 0, 0L, mtimeNanos, -1L, 0);
        if (isDir)
        {
            int c = allocate(0);
            byte[] buf = new byte[clusterBytes];
            dotEntry(buf, 0, ".", c, mtimeNanos);
            dotEntry(buf, 32, "..", dir == rootEntry ? 0 : dir.firstCluster, mtimeNanos);
            MetalBlock.write(lbaOf(c), buf, 0, sectorsPerCluster);
            e.firstCluster = c;
            e.chainLen = 1;
            e.chainLast = c;
        }
        else
        {
            e.chainLen = 0;
        }
        placeName(dir, name, isDir ? ATTR_DIR : ATTR_ARCHIVE, e);
        return e;
    }

    private static void dotEntry(byte[] b, int o, String dots, int cluster, long mtime)
    {
        for (int k = 0; k < 11; k++)
        {
            b[o + k] = (byte) (k < dots.length() ? '.' : ' ');
        }
        b[o + 11] = (byte) ATTR_DIR;
        put16(b, o + 20, cluster >>> 16);
        put16(b, o + 26, cluster & 0xFFFF);
        int[] dt = fatDateTime(mtime);
        put16(b, o + 14, dt[1]);
        put16(b, o + 16, dt[0]);
        put16(b, o + 18, dt[0]);
        put16(b, o + 22, dt[1]);
        put16(b, o + 24, dt[0]);
    }

    /**
     * Write {@code len} bytes of {@code b} from {@code off} into file {@code f} at {@code pos}, growing it; a gap
     * past the old end is written as zeros. Data first, then the FAT, then the entry's new size.
     */
    public void write(Entry f, long pos, byte[] b, int off, int len, long mtimeNanos) throws IOException
    {
        if (len <= 0)
        {
            return;
        }
        long end = pos + len;
        if (end > 0xFFFFFFFFL)
        {
            throw new IOException("FAT32: a file cannot pass 4 GiB");
        }
        ensureClusters(f, Math.max(end, f.size));
        if (pos > f.size)
        {
            writeData(f, f.size, null, 0, pos - f.size);
        }
        writeData(f, pos, b, off, len);
        flushFat();
        if (end > f.size)
        {
            f.size = end;
        }
        f.mtimeNanos = mtimeNanos;
        flushEntry(f);
    }

    /** Set {@code f}'s length: past the old end reads as zeros; a shorter file frees what it no longer needs. */
    public void truncate(Entry f, long size, long mtimeNanos) throws IOException
    {
        if (size > 0xFFFFFFFFL || size < 0L)
        {
            throw new IOException("FAT32: bad size " + size);
        }
        f.mtimeNanos = mtimeNanos;
        if (size >= f.size)
        {
            if (size > f.size)
            {
                ensureClusters(f, size);
                writeData(f, f.size, null, 0, size - f.size);
                flushFat();
                f.size = size;
            }
            flushEntry(f);
            return;
        }
        chainInfo(f);
        int keep = (int) ((size + clusterBytes - 1) / clusterBytes);
        f.cursorIndex = -1;
        if (keep == 0)
        {
            int old = f.firstCluster;
            f.firstCluster = 0;
            f.size = 0L;
            flushEntry(f);                               // the entry lets go first: a crash leaks, never cross-links
            freeChain(old);
            f.chainLen = 0;
            f.chainLast = 0;
        }
        else
        {
            int c = clusterAt(f, keep - 1);
            f.size = size;
            flushEntry(f);
            if (keep < f.chainLen)
            {
                int rest = next(c);
                fatSet(c, EOC_MARK);
                freeChain(rest);
                f.chainLen = keep;
                f.chainLast = c;
            }
        }
        flushFat();
        f.cursorIndex = -1;
    }

    /** Set {@code e}'s modified time. */
    public void setTime(Entry e, long mtimeNanos) throws IOException
    {
        e.mtimeNanos = mtimeNanos;
        flushEntry(e);
    }

    /**
     * Remove {@code e}'s name (a directory must be empty -- the caller checks). Its clusters stay allocated until
     * {@link #release}: MetalRamfs releases them at once, or at the last close of a file still open.
     */
    public void unlink(Entry e) throws IOException
    {
        removeSlots(e);
    }

    /** Free a DETACHED entry's clusters. */
    public void release(Entry e) throws IOException
    {
        if (e.entryLba >= 0L || e.firstCluster < 2)
        {
            return;
        }
        freeChain(e.firstCluster);
        flushFat();
        e.firstCluster = 0;
        e.chainLen = 0;
        e.size = 0L;
    }

    /**
     * Rename {@code e} to {@code name} in {@code dir}. With a {@code target} (an existing file, or an empty
     * directory when {@code e} is one) the target's short entry is switched to {@code e}'s clusters in one sector
     * write -- the target name is never missing -- and {@code target} is DETACHED for the caller to release.
     * Otherwise the new name is written before the old one is removed. A directory moved to another parent gets
     * its ".." rewritten.
     */
    public void rename(Entry e, Entry dir, String name, Entry target) throws IOException
    {
        Entry oldParent = e.parent;
        if (target != null)
        {
            byte[] sec = new byte[SECTOR];
            MetalBlock.read(target.entryLba, sec, 0, 1);
            putEntryFields(sec, target.entryOffset, e);
            MetalBlock.write(target.entryLba, sec, 0, 1);
            removeSlots(e);
            e.parent = target.parent;
            e.slot = target.slot;
            e.lfnSlots = target.lfnSlots;
            e.entryLba = target.entryLba;
            e.entryOffset = target.entryOffset;
            e.name = target.name;
            target.entryLba = -1L;
        }
        else
        {
            int attr = e.dir ? ATTR_DIR : ATTR_ARCHIVE;
            if (e.entryLba >= 0L)
            {
                byte[] sec = new byte[SECTOR];
                MetalBlock.read(e.entryLba, sec, 0, 1);
                attr = sec[e.entryOffset + 11] & 0xFF;
            }
            Entry old = new Entry(e.name, e.dir, 0, 0L, 0L, e.entryLba, e.entryOffset);
            old.parent = e.parent;
            old.slot = e.slot;
            old.lfnSlots = e.lfnSlots;
            placeName(dir, name, attr, e);
            removeSlots(old);
        }
        if (e.dir && e.parent != oldParent && e.firstCluster >= 2)
        {
            byte[] sec = new byte[SECTOR];
            long lba = lbaOf(e.firstCluster);
            MetalBlock.read(lba, sec, 0, 1);
            int up = e.parent == rootEntry ? 0 : e.parent.firstCluster;
            put16(sec, 32 + 20, up >>> 16);
            put16(sec, 32 + 26, up & 0xFFFF);
            MetalBlock.write(lba, sec, 0, 1);
        }
    }

    // ---- names --------------------------------------------------------------------------------------------

    /** A name FAT can hold: 1..255 UTF-16 units, no control character or {@code "*\/:<>?\|}, no trailing dot/space. */
    public static boolean validName(String n)
    {
        if (n.isEmpty() || n.length() > 255 || n.equals(".") || n.equals(".."))
        {
            return false;
        }
        char lastc = n.charAt(n.length() - 1);
        if (lastc == '.' || lastc == ' ')
        {
            return false;
        }
        for (int i = 0; i < n.length(); i++)
        {
            char ch = n.charAt(i);
            if (ch < 0x20 || "\"*/:<>?\\|".indexOf(ch) >= 0)
            {
                return false;
            }
        }
        return true;
    }

    private static boolean shortChar(char ch)
    {
        return (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || SHORT_OK.indexOf(ch) >= 0;
    }

    /**
     * If {@code name} is an exact 8.3 name -- each half in one case -- its 11 upper-case bytes into {@code out}
     * and the NT case bits; else -1 (it needs a long name).
     */
    private static int exactShort(String name, byte[] out)
    {
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        if (base.isEmpty() || base.length() > 8 || ext.length() > 3 || base.indexOf('.') >= 0)
        {
            return -1;
        }
        int bc = halfCase(base);
        int ec = halfCase(ext);
        if (bc < 0 || ec < 0)
        {
            return -1;
        }
        java.util.Arrays.fill(out, (byte) ' ');
        String ub = base.toUpperCase(java.util.Locale.ROOT);
        String ue = ext.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < ub.length(); i++)
        {
            out[i] = (byte) ub.charAt(i);
        }
        for (int i = 0; i < ue.length(); i++)
        {
            out[8 + i] = (byte) ue.charAt(i);
        }
        return (bc == 1 ? 0x08 : 0) | (ec == 1 ? 0x10 : 0);
    }

    /** 0 if every letter is upper case, 1 if every one is lower, -1 if mixed or a character 8.3 cannot hold. */
    private static int halfCase(String s)
    {
        boolean upper = false;
        boolean lower = false;
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            if (ch >= 'a' && ch <= 'z')
            {
                lower = true;
            }
            else if (ch >= 'A' && ch <= 'Z')
            {
                upper = true;
            }
            else if (!shortChar(ch))
            {
                return -1;
            }
        }
        if (upper && lower)
        {
            return -1;
        }
        return lower ? 1 : 0;
    }

    /** The short name a long name is stored with: the basis upper-cased, with a numeric tail {@code ~N} not taken. */
    private static void generateShort(String name, Set<String> taken, byte[] out) throws IOException
    {
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot + 1);
        String b = basis(base, 8);
        String x = basis(ext, 3);
        if (b.isEmpty())
        {
            b = "_";
        }
        for (int n = 1; n < 1000000; n++)
        {
            String tail = "~" + n;
            String head = b.substring(0, Math.min(b.length(), 8 - tail.length()));
            java.util.Arrays.fill(out, (byte) ' ');
            String s = head + tail;
            for (int i = 0; i < s.length(); i++)
            {
                out[i] = (byte) s.charAt(i);
            }
            for (int i = 0; i < x.length(); i++)
            {
                out[8 + i] = (byte) x.charAt(i);
            }
            if (!taken.contains(new String(out, 0, 11, StandardCharsets.ISO_8859_1)))
            {
                return;
            }
        }
        throw new IOException("FAT32: no free short name for " + name);
    }

    /** Upper-cased, spaces and dots dropped, any other character 8.3 cannot hold made {@code _}, cut to {@code max}. */
    private static String basis(String s, int max)
    {
        StringBuilder sb = new StringBuilder();
        String u = s.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < u.length() && sb.length() < max; i++)
        {
            char ch = u.charAt(i);
            if (ch == ' ' || ch == '.')
            {
                continue;
            }
            sb.append(shortChar(ch) ? ch : '_');
        }
        return sb.toString();
    }

    /** Long-name part {@code seq} (1-based) of {@code name} at {@code b[o..o+32)}. */
    private static void lfnEntry(byte[] b, int o, String name, int seq, boolean last, int sum)
    {
        b[o] = (byte) (seq | (last ? 0x40 : 0));
        b[o + 11] = (byte) ATTR_LFN;
        b[o + 12] = 0;
        b[o + 13] = (byte) sum;
        b[o + 26] = 0;
        b[o + 27] = 0;
        for (int k = 0; k < 13; k++)
        {
            int i = (seq - 1) * 13 + k;
            int ch;
            if (i < name.length())
            {
                ch = name.charAt(i);
            }
            else if (i == name.length())
            {
                ch = 0;
            }
            else
            {
                ch = 0xFFFF;
            }
            put16(b, o + LFN_OFFS[k], ch);
        }
    }

    private static void putLfn(char[] lfn, int at, byte[] b, int o)
    {
        for (int k = 0; k < 13; k++)
        {
            lfn[at + k] = (char) u16(b, o + LFN_OFFS[k]);
        }
    }

    private static String lfnString(char[] lfn)
    {
        int n = 0;
        while (n < lfn.length && lfn[n] != 0)
        {
            n += 1;
        }
        return new String(lfn, 0, n);
    }

    /** The checksum of the 11-byte short name that each of its LFN entries carries. */
    private static int shortSum(byte[] b, int o)
    {
        int sum = 0;
        for (int k = 0; k < 11; k++)
        {
            sum = (((sum & 1) << 7) + (sum >> 1) + (b[o + k] & 0xFF)) & 0xFF;
        }
        return sum;
    }

    /** The 8.3 name, lower-cased where byte 12's NT case bits say (bit 3 the base, bit 4 the extension). */
    private static String shortName(byte[] b, int o)
    {
        byte[] raw = new byte[11];
        System.arraycopy(b, o, raw, 0, 11);
        if ((raw[0] & 0xFF) == 0x05)
        {
            raw[0] = (byte) 0xE5;
        }
        String base = new String(raw, 0, 8, StandardCharsets.ISO_8859_1).stripTrailing();
        String ext = new String(raw, 8, 3, StandardCharsets.ISO_8859_1).stripTrailing();
        int nt = b[o + 12] & 0xFF;
        if ((nt & 0x08) != 0)
        {
            base = base.toLowerCase(java.util.Locale.ROOT);
        }
        if ((nt & 0x10) != 0)
        {
            ext = ext.toLowerCase(java.util.Locale.ROOT);
        }
        return ext.isEmpty() ? base : base + "." + ext;
    }

    // ---- time ---------------------------------------------------------------------------------------------

    /** A FAT date and time (2-second resolution, local time taken as UTC) in nanoseconds since the epoch. */
    static long fatTime(int date, int time)
    {
        if (date == 0)
        {
            return 0L;
        }
        int y = 1980 + (date >> 9);
        int m = (date >> 5) & 0x0F;
        int d = date & 0x1F;
        if (m < 1 || m > 12 || d < 1)
        {
            return 0L;
        }
        long days = daysFromCivil(y, m, d);
        long secs = days * 86400L + (time >> 11) * 3600L + ((time >> 5) & 0x3F) * 60L + (time & 0x1F) * 2L;
        return secs * 1_000_000_000L;
    }

    /** {date, time} in FAT form for nanoseconds since the epoch (UTC), clamped to FAT's 1980..2107. */
    static int[] fatDateTime(long nanos)
    {
        long secs = Math.floorDiv(nanos, 1_000_000_000L);
        long days = Math.floorDiv(secs, 86400L);
        int sod = (int) (secs - days * 86400L);
        long z = days + 719468L;
        long era = (z >= 0L ? z : z - 146096L) / 146097L;
        long doe = z - era * 146097L;
        long yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        int d = (int) (doy - (153L * mp + 2L) / 5L + 1L);
        int m = (int) (mp < 10L ? mp + 3L : mp - 9L);
        long y = yoe + era * 400L + (m <= 2 ? 1L : 0L);
        if (y < 1980L)
        {
            return new int[] {(1 << 5) | 1, 0};
        }
        if (y > 2107L)
        {
            return new int[] {(127 << 9) | (12 << 5) | 31, (23 << 11) | (59 << 5) | 29};
        }
        int date = (int) ((y - 1980L) << 9) | (m << 5) | d;
        int time = (sod / 3600) << 11 | ((sod / 60) % 60) << 5 | (sod % 60) / 2;
        return new int[] {date, time};
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's days_from_civil). */
    private static long daysFromCivil(int y, int m, int d)
    {
        int yy = m <= 2 ? y - 1 : y;
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int mp = m > 2 ? m - 3 : m + 9;
        int doy = (153 * mp + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097L + doe - 719468L;
    }

    // ---- file data ----------------------------------------------------------------------------------------

    /**
     * Read {@code len} bytes of file {@code f} from {@code pos} into {@code b[off..]}: the count, short only at the
     * end of the file. Contiguous clusters go out as one multi-block read of up to 64 KiB.
     */
    public int read(Entry f, long pos, byte[] b, int off, int len) throws IOException
    {
        if (pos < 0L || pos >= f.size || len <= 0)
        {
            return 0;
        }
        int want = (int) Math.min((long) len, f.size - pos);
        int done = 0;
        while (done < want)
        {
            long p = pos + done;
            int idx = (int) (p / clusterBytes);
            int c = clusterAt(f, idx);
            int inCluster = (int) (p % clusterBytes);
            int startSector = inCluster / SECTOR;
            int from = inCluster % SECTOR;
            int n = runSectors(f, idx, c, startSector, (long) (want - done) + from);
            MetalBlock.read(lbaOf(c) + startSector, run, 0, n);
            int k = Math.min(want - done, n * SECTOR - from);
            System.arraycopy(run, from, b, off + done, k);
            done += k;
        }
        return done;
    }

    /**
     * Write {@code len} bytes (zeros if {@code b} is null) into {@code f}'s clusters at {@code pos}; the chain
     * already reaches. A sector only partly covered is read first, so the bytes around the write survive.
     */
    private void writeData(Entry f, long pos, byte[] b, int off, long len) throws IOException
    {
        long done = 0L;
        while (done < len)
        {
            long p = pos + done;
            int idx = (int) (p / clusterBytes);
            int c = clusterAt(f, idx);
            int inCluster = (int) (p % clusterBytes);
            int startSector = inCluster / SECTOR;
            int from = inCluster % SECTOR;
            int n = runSectors(f, idx, c, startSector, (len - done) + from);
            int k = (int) Math.min(len - done, (long) n * SECTOR - from);
            long lba = lbaOf(c) + startSector;
            if (from != 0)
            {
                MetalBlock.read(lba, run, 0, 1);
            }
            int end = from + k;
            if (end % SECTOR != 0 && (end > SECTOR || from == 0))
            {
                MetalBlock.read(lba + n - 1, run, (n - 1) * SECTOR, 1);
            }
            if (b == null)
            {
                java.util.Arrays.fill(run, from, end, (byte) 0);
            }
            else
            {
                System.arraycopy(b, off + (int) done, run, from, k);
            }
            MetalBlock.write(lba, run, 0, n);
            done += k;
        }
    }

    /**
     * How many sectors one transfer from {@code startSector} of cluster {@code c} (chain index {@code idx}) can move
     * toward {@code need} bytes: on through clusters that follow one another on the card, up to 64 KiB. Leaves the
     * cursor on the last cluster reached.
     */
    private int runSectors(Entry f, int idx, int c, int startSector, long need) throws IOException
    {
        int lastIdx = idx;
        int last = c;
        int sectors = sectorsPerCluster - startSector;
        while (sectors * (long) SECTOR < need && sectors + sectorsPerCluster <= RUN_SECTORS)
        {
            int nx = next(last);
            if (nx != last + 1)
            {
                break;
            }
            last = nx;
            lastIdx += 1;
            sectors += sectorsPerCluster;
        }
        f.cursorIndex = lastIdx;
        f.cursorCluster = last;
        return (int) Math.min((long) sectors, (need + SECTOR - 1) / SECTOR);
    }

    /** The cluster at chain index {@code idx} of {@code f}, walking on from the last transfer's cursor when it can. */
    private int clusterAt(Entry f, int idx) throws IOException
    {
        int i = 0;
        int c = f.firstCluster;
        if (f.cursorIndex >= 0 && f.cursorIndex <= idx)
        {
            i = f.cursorIndex;
            c = f.cursorCluster;
        }
        while (i < idx)
        {
            c = next(c);
            if (c >= EOC)
            {
                throw new IOException("FAT32: chain of " + f.name + " ends before its size");
            }
            i += 1;
        }
        if (c < 2)
        {
            throw new IOException("FAT32: " + f.name + " has no clusters");
        }
        f.cursorIndex = idx;
        f.cursorCluster = c;
        return c;
    }

    private static int u16(byte[] b, int off)
    {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int off)
    {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }

    private static void put16(byte[] b, int off, int v)
    {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    private static void put32(byte[] b, int off, long v)
    {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
