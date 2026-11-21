/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-23
 */
package board;

import magic.Address;
import magic.DataReference;
import magic.Magic;
import vm.Heap;

/**
 * A minimal read/write FAT32 driver over the board's {@link Block} device — the filesystem half of
 * the self-hosting fixpoint (PLAN.md §M5.5d). The Pi's boot partition is a FAT32
 * volume inside an MBR; this finds {@code KERNEL8.IMG} in the root directory and
 * overwrites its cluster chain with the metal-reproduced image, so the next boot
 * loads what joe-ng wrote. Just enough FAT to rewrite one existing file in place:
 * no allocation, no directory growth, no long-name handling.
 *
 * <p>Ordinary Java compiled to A64 by our own baseline compiler; block I/O is
 * {@link Block#read}/{@link Block#write}, one sector at a time; the caller brings the device up
 * ({@link Block#init}) first.
 */
public final class Fat32
{
    private Fat32()
    {
    }

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
        fatBuf = Heap.allocData(512).toLong();
        DataReference mbrRef = Heap.allocData(512);
        long mbr = mbrRef.toLong();                  // a raw copy; mbrRef keeps the block alive
        if (!Block.read(0L, mbr, 1))
        {
            return false;
        }
        int type = Address.fromLong(mbr).plus(446 + 4).loadUnsignedByte();   // partition 1: type byte
        if (type != 0x0B && type != 0x0C)
        {
            return false;                                      // not a FAT32 partition
        }
        long partStart = u32(mbr, 446 + 8);                    // partition 1 start LBA
        DataReference bpbRef = Heap.allocData(512);
        long bpb = bpbRef.toLong();                  // a raw copy; bpbRef keeps the block alive
        if (!Block.read(partStart, bpb, 1))
        {
            return false;
        }
        if (u16(bpb, 11) != 512)
        {
            return false;                                      // we assume 512-byte sectors
        }
        secPerClus = Address.fromLong(bpb).plus(13).loadUnsignedByte();
        int reserved = u16(bpb, 14);
        int numFats = Address.fromLong(bpb).plus(16).loadUnsignedByte();
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
                if (!Block.read(s0 + sc, fatBuf, 1))
                {
                    return false;
                }
                int off = 0;
                while (off < 512)
                {
                    Address ent = Address.fromLong(fatBuf).plus(off);
                    int first = ent.loadUnsignedByte();
                    if (first == 0)
                    {
                        return false;                          // end of directory: not found
                    }
                    int attr = ent.plus(11).loadUnsignedByte();
                    if (first != 0xE5 && attr != 0x0F && isKernel8Img(ent))
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
                if (!Block.write(s0 + sc, src + done, 1))
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
                if (!Block.read(s0 + sc, dst + done, 1))
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
        if (!Block.read(fatStart + fatByte / 512L, fatBuf, 1))
        {
            return EOC;
        }
        return u32(fatBuf, (int) (fatByte % 512L)) & 0x0FFFFFFFL;
    }

    /** Whether the 11-byte 8.3 name at {@code p} is {@code "KERNEL8 IMG"}. */
    private static boolean isKernel8Img(Address p)
    {
        byte[] want = Magic.bytes("KERNEL8 IMG");
        int i = 0;
        while (i < 11)
        {
            if (p.plus(i).loadUnsignedByte() != (want[i] & 0xFF))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    private static int u16(long buf, int off)
    {
        Address p = Address.fromLong(buf).plus(off);
        return p.loadUnsignedByte() | (p.plus(1).loadUnsignedByte() << 8);
    }

    private static long u32(long buf, int off)
    {
        Address p = Address.fromLong(buf).plus(off);
        return (p.loadUnsignedByte() & 0xFFL)
             | ((p.plus(1).loadUnsignedByte() & 0xFFL) << 8)
             | ((p.plus(2).loadUnsignedByte() & 0xFFL) << 16)
             | ((p.plus(3).loadUnsignedByte() & 0xFFL) << 24);
    }
}
