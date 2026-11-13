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

/**
 * The microSD card as a block device: 512-byte sectors, addressed by LBA from the start of the card. The VM side
 * is {@code board.bcm2711.Emmc} (EMMC2, polled PIO, CMD18/CMD25 runs) through three natives; everything above it
 * -- the FAT32 filesystem behind {@code /boot} -- is guest Java.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. Callers serialize: there is one card and one controller, and
 * the natives keep no lock of their own.
 */
public final class MetalBlock
{
    private MetalBlock()
    {
    }

    /** Sector size, in bytes. */
    public static final int SECTOR = 512;

    /** VM native ({@code VMNatives.blockInit}): bring the card up; 0, or Emmc.init's negative step code. */
    private static native int init0();

    /** VM native ({@code VMNatives.blockRead}): read {@code n} sectors at {@code lba} into {@code b[off..]}. */
    private static native int read0(long lba, byte[] b, int off, int n);

    /** VM native ({@code VMNatives.blockWrite}): write {@code n} sectors at {@code lba} from {@code b[off..]}. */
    private static native int write0(long lba, byte[] b, int off, int n);

    private static boolean up;

    /** Bring the card up once. Throws if there is no card or it will not initialize. */
    public static void open() throws IOException
    {
        if (up)
        {
            return;
        }
        int rc = init0();
        if (rc != 0)
        {
            throw new IOException("SD card init failed (step " + rc + ")");
        }
        up = true;
    }

    /** Read {@code n} sectors at {@code lba} into {@code b} from {@code off} (a multiple of 4). */
    public static void read(long lba, byte[] b, int off, int n) throws IOException
    {
        check(b, off, n);
        open();
        if (read0(lba, b, off, n) != 0)
        {
            throw new IOException("SD read failed at sector " + lba + " (+" + n + ")");
        }
    }

    /** Write {@code n} sectors at {@code lba} from {@code b} at {@code off} (a multiple of 4). */
    public static void write(long lba, byte[] b, int off, int n) throws IOException
    {
        check(b, off, n);
        open();
        if (write0(lba, b, off, n) != 0)
        {
            throw new IOException("SD write failed at sector " + lba + " (+" + n + ")");
        }
    }

    /** The PIO loop moves whole words, so the buffer offset must be word aligned. */
    private static void check(byte[] b, int off, int n)
    {
        if (b == null)
        {
            throw new NullPointerException();
        }
        if (n < 0 || off < 0 || (off & 3) != 0 || (long) off + (long) n * SECTOR > b.length)
        {
            throw new IndexOutOfBoundsException("off=" + off + " n=" + n + " length=" + b.length);
        }
    }
}
