package board.bcm2711;

import magic.Magic;
import vm.Heap;

/**
 * A minimal read/write FAT32 driver over {@link Emmc} — the filesystem half of
 * the self-hosting fixpoint (PLAN.md §M5.5d). The Pi's boot partition is a FAT32
 * volume inside an MBR; this finds {@code KERNEL8.IMG} in the root directory and
 * overwrites its cluster chain with the metal-reproduced image, so the next boot
 * loads what joe-ng wrote. Just enough FAT to rewrite one existing file in place:
 * no allocation, no directory growth, no long-name handling.
 *
 * <p>Ordinary Java compiled to A64 by our own baseline compiler; block I/O is
 * {@link Emmc#readBlock}/{@link Emmc#writeBlock}.
 */
public final class Fat32
{
    private Fat32() {}

    private static long fatStart;      // LBA of the first FAT
    private static long dataStart;     // LBA of the first data cluster (cluster 2)
    private static int  secPerClus;    // sectors per cluster
    private static long rootCluster;   // first cluster of the root directory

    private static long kernCluster;   // KERNEL8.IMG's first cluster (0 until found)
    private static long kernSize;      // ... and its byte length

    private static long fatBuf;        // reused 512-byte scratch for FAT / directory sectors

    private static final long EOC = 0x0FFFFFF8L;   // cluster chain terminator (>= this = end)

    /**
     * Parse the MBR + FAT32 BPB into the layout fields. Returns true on a FAT32
     * volume (partition type 0x0B/0x0C) whose parameters we understand.
     */
    public static boolean mount()
    {
        fatBuf = Heap.alloc(512);
        long mbr = Heap.alloc(512);
        if (!Emmc.readBlock(0L, mbr))
        {
            return false;
        }
        int type = Magic.load8(mbr + 446 + 4);                 // partition 1: type byte
        if (type != 0x0B && type != 0x0C)
        {
            return false;                                      // not a FAT32 partition
        }
        long partStart = u32(mbr, 446 + 8);                    // partition 1 start LBA
        long bpb = Heap.alloc(512);
        if (!Emmc.readBlock(partStart, bpb))
        {
            return false;
        }
        if (u16(bpb, 11) != 512)
        {
            return false;                                      // we assume 512-byte sectors
        }
        secPerClus = Magic.load8(bpb + 13);
        int reserved = u16(bpb, 14);
        int numFats = Magic.load8(bpb + 16);
        long secPerFat = u32(bpb, 36);
        rootCluster = u32(bpb, 44);
        fatStart = partStart + reserved;
        dataStart = partStart + reserved + (long) numFats * secPerFat;
        return true;
    }

    /**
     * Scan the root directory for {@code KERNEL8.IMG} (8.3 short name). On success
     * records its first cluster + size and returns true. {@link #mount} must have run.
     */
    public static boolean findKernel()
    {
        long clus = rootCluster;
        while (clus >= 2L && clus < EOC)
        {
            long s0 = dataStart + (clus - 2L) * secPerClus;
            int sc = 0;
            while (sc < secPerClus)
            {
                if (!Emmc.readBlock(s0 + sc, fatBuf))
                {
                    return false;
                }
                int off = 0;
                while (off < 512)
                {
                    int first = Magic.load8(fatBuf + off);
                    if (first == 0)
                    {
                        return false;                          // end of directory: not found
                    }
                    int attr = Magic.load8(fatBuf + off + 11);
                    if (first != 0xE5 && attr != 0x0F && isKernel8Img(fatBuf + off))
                    {
                        kernCluster = ((long) u16(fatBuf, off + 20) << 16) | u16(fatBuf, off + 26);
                        kernSize = u32(fatBuf, off + 28);
                        return true;
                    }
                    off += 32;
                }
                sc += 1;
            }
            clus = nextCluster(clus);
        }
        return false;
    }

    /** KERNEL8.IMG's first cluster (valid after {@link #findKernel}). */
    public static long kernelCluster()
    {
        return kernCluster;
    }

    /** KERNEL8.IMG's size in bytes. */
    public static long kernelSize()
    {
        return kernSize;
    }

    /** First LBA of {@code cluster}'s data. */
    public static long clusterLba(long cluster)
    {
        return dataStart + (cluster - 2L) * secPerClus;
    }

    /**
     * Overwrite KERNEL8.IMG's existing clusters with {@code len} bytes from the
     * heap buffer at {@code src} (padded to a whole sector). The file must already
     * be at least {@code len} bytes (we reuse its chain; we never allocate). Returns
     * true if the whole length was written.
     */
    public static boolean writeKernel(long src, long len)
    {
        long clus = kernCluster;
        long done = 0L;
        while (clus >= 2L && clus < EOC && done < len)
        {
            long s0 = dataStart + (clus - 2L) * secPerClus;
            int sc = 0;
            while (sc < secPerClus && done < len)
            {
                if (!Emmc.writeBlock(s0 + sc, src + done))
                {
                    return false;
                }
                done += 512L;
                sc += 1;
            }
            clus = nextCluster(clus);
        }
        return done >= len;
    }

    /** Read up to {@code len} bytes of KERNEL8.IMG's clusters into {@code dst}. Returns true if all read. */
    public static boolean readKernel(long dst, long len)
    {
        long clus = kernCluster;
        long done = 0L;
        while (clus >= 2L && clus < EOC && done < len)
        {
            long s0 = dataStart + (clus - 2L) * secPerClus;
            int sc = 0;
            while (sc < secPerClus && done < len)
            {
                if (!Emmc.readBlock(s0 + sc, dst + done))
                {
                    return false;
                }
                done += 512L;
                sc += 1;
            }
            clus = nextCluster(clus);
        }
        return done >= len;
    }

    /** The FAT32 successor of {@code cluster} (masked to 28 bits), or {@link #EOC} on I/O error. */
    private static long nextCluster(long cluster)
    {
        long fatByte = cluster * 4L;
        if (!Emmc.readBlock(fatStart + fatByte / 512L, fatBuf))
        {
            return EOC;
        }
        return u32(fatBuf, (int) (fatByte % 512L)) & 0x0FFFFFFFL;
    }

    /** Whether the 11-byte 8.3 name at {@code p} is {@code "KERNEL8 IMG"}. */
    private static boolean isKernel8Img(long p)
    {
        byte[] want = Magic.bytes("KERNEL8 IMG");
        int i = 0;
        while (i < 11)
        {
            if (Magic.load8(p + i) != (want[i] & 0xFF))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    private static int u16(long buf, int off)
    {
        return Magic.load8(buf + off) | (Magic.load8(buf + off + 1) << 8);
    }

    private static long u32(long buf, int off)
    {
        return (Magic.load8(buf + off) & 0xFFL)
             | ((Magic.load8(buf + off + 1) & 0xFFL) << 8)
             | ((Magic.load8(buf + off + 2) & 0xFFL) << 16)
             | ((Magic.load8(buf + off + 3) & 0xFFL) << 24);
    }
}
