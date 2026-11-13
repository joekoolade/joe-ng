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
package demo;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import jdk.internal.misc.MetalBlock;

/**
 * The microSD block device ({@link MetalBlock}) end to end: the MBR's first partition, the FAT32 boot sector, and
 * the first 4096 sectors read twice -- one sector per command and in multi-block runs -- which must agree; their
 * SHA-256 is printed to compare with the host ({@code dd if=<card> bs=512 count=4096 | shasum -a 256}).
 *
 * <p>{@code args=net} (with {@code net=1}) then resolves a host name, to show the WiFi controller survived the
 * card's controller reset. {@code args=write <lba>} adds a write test at that sector: save 16 sectors, write a pattern, read it back,
 * restore the original, read that back. ONLY for a QEMU scratch image -- on a real card it writes the card.
 */
public final class BlockDemo
{
    private BlockDemo()
    {
    }

    private static final int SPAN = 4096;          // sectors hashed (2 MiB)

    public static void main(String[] args) throws Exception
    {
        byte[] s0 = new byte[512];
        MetalBlock.read(0L, s0, 0, 1);
        System.out.println("block: mbr signature " + hex2(s0, 510) + " (55aa expected)");
        int type = s0[446 + 4] & 0xFF;
        long start = u32(s0, 446 + 8);
        long count = u32(s0, 446 + 12);
        System.out.println("block: partition 1 type 0x" + Integer.toHexString(type) + " start " + start
                + " sectors " + count);
        byte[] bpb = new byte[512];
        MetalBlock.read(start, bpb, 0, 1);
        System.out.println("block: bpb bytes/sector " + u16(bpb, 11) + " sectors/cluster " + (bpb[13] & 0xFF)
                + " reserved " + u16(bpb, 14) + " fats " + (bpb[16] & 0xFF) + " sectors/fat " + u32(bpb, 36)
                + " root cluster " + u32(bpb, 44) + " fs '" + new String(bpb, 82, 8, "US-ASCII") + "'");

        byte[] one = new byte[SPAN * 512];
        long t0 = System.nanoTime();
        int i = 0;
        while (i < SPAN)
        {
            MetalBlock.read(i, one, i * 512, 1);
            i += 1;
        }
        long t1 = System.nanoTime();
        byte[] multi = new byte[SPAN * 512];
        MetalBlock.read(0L, multi, 0, SPAN);
        long t2 = System.nanoTime();
        System.out.println("block: single-sector read " + rate(t1 - t0) + ", multi-block read " + rate(t2 - t1)
                + ", same bytes: " + Arrays.equals(one, multi));
        System.out.println("block: sha256 of sectors 0.." + (SPAN - 1) + " = " + sha(multi));

        if (args.length >= 2 && args[0].equals("write"))
        {
            writeTest(Long.parseLong(args[1]));
        }
        if (args.length >= 1 && args[0].equals("net"))
        {
            // The card's controller was just reset and re-initialized; the WiFi's (0xFE300000) must not have been.
            System.out.println("block: network after card init: example.com = "
                    + java.net.InetAddress.getByName("example.com").getHostAddress());
        }
    }

    private static void writeTest(long lba) throws Exception
    {
        int n = 16;
        byte[] saved = new byte[n * 512];
        MetalBlock.read(lba, saved, 0, n);
        byte[] pat = new byte[n * 512];
        int i = 0;
        while (i < pat.length)
        {
            pat[i] = (byte) (i * 7 + (i >> 9) * 13 + 1);
            i += 1;
        }
        MetalBlock.write(lba, pat, 0, n);
        byte[] back = new byte[n * 512];
        MetalBlock.read(lba, back, 0, n);
        boolean patOk = Arrays.equals(pat, back);
        MetalBlock.write(lba, saved, 0, n);
        MetalBlock.read(lba, back, 0, n);
        boolean restored = Arrays.equals(saved, back);
        System.out.println("block: write test at " + lba + ": pattern read back " + patOk + ", original restored "
                + restored);
    }

    private static String rate(long ns)
    {
        long kb = SPAN / 2L;
        long ms = ns / 1_000_000L;
        return ms + " ms (" + (ms == 0 ? kb : kb * 1000L / ms) + " KB/s)";
    }

    private static String sha(byte[] b) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String hex2(byte[] b, int off)
    {
        return HexFormat.of().formatHex(b, off, off + 2);
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
