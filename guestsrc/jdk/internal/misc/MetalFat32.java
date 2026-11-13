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
import java.util.List;

/**
 * The microSD card's FAT32 boot partition, read through {@link MetalBlock}: the MBR's first FAT32 partition (or a
 * card formatted without a partition table), its BPB, cluster chains, and directories with long file names (VFAT
 * LFN). {@link MetalRamfs} mounts it at {@code /boot}; nothing else calls it.
 *
 * <p>Read-only for now. Every entry keeps the sector and offset of its short directory entry, which is where a
 * later write (a replace-by-rename's one-sector switch) will land.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. Not thread-safe: {@code MetalRamfs} calls it under its lock.
 *
 * <p>Sources: Microsoft's "FAT: General Overview of On-Disk Format" (BPB fields, FAT32 entry masks, the LFN
 * checksum and layout) and the Windows NT case bits in byte 12 of a short entry, which macOS also writes.
 */
public final class MetalFat32
{
    private static final int SECTOR = MetalBlock.SECTOR;
    private static final int FAT_WINDOW = 8;             // FAT sectors read at once (1024 entries)
    private static final int RUN_SECTORS = 128;          // the most a data read moves at once (64 KiB)
    private static final int EOC = 0x0FFFFFF8;           // at or above: end of chain
    private static final int BAD = 0x0FFFFFF7;

    private static final int ATTR_VOLUME = 0x08;
    private static final int ATTR_DIR = 0x10;
    private static final int ATTR_LFN = 0x0F;

    /** One file or directory of the partition. */
    public static final class Entry
    {
        public final String name;
        public final boolean dir;
        public final int firstCluster;                   // 0 for an empty file
        public final long size;
        public final long mtimeNanos;                    // the FAT's local time, taken as UTC
        public final long entryLba;                      // where the short entry is
        public final int entryOffset;
        int cursorIndex = -1;                            // the last cluster a read reached, by chain index
        int cursorCluster;

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
    private final long dataLba;
    private final int clusterCount;
    private final int clusterBytes;

    private final byte[] fatBuf = new byte[FAT_WINDOW * SECTOR];
    private long fatBufLba = -1L;
    private final byte[] run = new byte[RUN_SECTORS * SECTOR];

    private MetalFat32(long partitionLba, byte[] bpb) throws IOException
    {
        int bps = u16(bpb, 11);
        int spc = bpb[13] & 0xFF;
        int reserved = u16(bpb, 14);
        int fats = bpb[16] & 0xFF;
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
        if (u16(bpb, 22) != 0 || fatSz == 0L || fats == 0)
        {
            throw new IOException("FAT32: not a FAT32 BPB (sectors/fat16=" + u16(bpb, 22) + ")");
        }
        this.partitionLba = partitionLba;
        this.sectorsPerCluster = spc;
        this.rootCluster = (int) u32(bpb, 44);
        this.fatLba = partitionLba + reserved;
        this.dataLba = fatLba + fats * fatSz;
        long dataSectors = total - (reserved + fats * fatSz);
        this.clusterCount = (int) (dataSectors / spc);
        this.clusterBytes = spc * SECTOR;
        this.label = new String(bpb, 71, 11, StandardCharsets.ISO_8859_1).trim();
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

    /** The root directory as an entry. */
    public Entry root()
    {
        return new Entry("", true, rootCluster, 0L, 0L, -1L, 0);
    }

    // ---- the FAT ------------------------------------------------------------------------------------------

    /** The FAT entry of cluster {@code c}: the next cluster, or a value at or above {@code EOC}. */
    private int next(int c) throws IOException
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
            fatBufLba = -1L;
            MetalBlock.read(win, fatBuf, 0, FAT_WINDOW);
            fatBufLba = win;
        }
        int off = (int) ((lba - win) * SECTOR + byteOff % SECTOR);
        int v = (int) (u32(fatBuf, off) & 0x0FFFFFFFL);
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

    // ---- directories --------------------------------------------------------------------------------------

    /** The entries of directory {@code d}, without "." and "..". */
    public List<Entry> list(Entry d) throws IOException
    {
        List<Entry> out = new ArrayList<>();
        byte[] buf = new byte[clusterBytes];
        char[] lfn = null;
        int lfnNext = 0;                                 // the sequence number the next LFN part must carry
        int lfnSum = 0;
        int c = d.firstCluster;
        int steps = 0;
        while (c >= 2 && c < EOC)
        {
            steps += 1;
            if (steps > clusterCount)
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
                if (lfn != null && lfnNext == 0 && lfnSum == shortSum(buf, o))
                {
                    name = lfnString(lfn);
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
                out.add(new Entry(name, isDir, clus, size, mtime, lba + o / SECTOR, o % SECTOR));
            }
            c = next(c);
        }
        return out;
    }

    private static void putLfn(char[] lfn, int at, byte[] b, int o)
    {
        int[] offs = {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30};
        for (int k = 0; k < 13; k++)
        {
            lfn[at + k] = (char) u16(b, o + offs[k]);
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
            // extend the run over clusters that follow one another on the card
            int inCluster = (int) (p % clusterBytes);
            int startSector = inCluster / SECTOR;
            long need = (long) (want - done) + inCluster % SECTOR;
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
            int n = (int) Math.min((long) sectors, (need + SECTOR - 1) / SECTOR);
            MetalBlock.read(lbaOf(c) + startSector, run, 0, n);
            int from = inCluster % SECTOR;
            int k = Math.min(want - done, n * SECTOR - from);
            System.arraycopy(run, from, b, off + done, k);
            done += k;
            f.cursorIndex = lastIdx;
            f.cursorCluster = last;
        }
        return done;
    }

    /** The cluster at chain index {@code idx} of {@code f}, walking on from the last read's cursor when it can. */
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
}
