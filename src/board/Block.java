/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package board;

import magic.Magic;

/**
 * The board's block device, as the VM sees it: bring it up, and move whole 512-byte sectors between it and memory.
 * Each operation goes through the board's platform class ({@link Platform#OPS}) -- the Pi's microSD card (EMMC2);
 * QEMU virt has none, so {@link #init} answers {@link Platform#BLOCK_NO_DEVICE} there and nothing else is reached.
 *
 * <p>The guest's {@code jdk.internal.misc.MetalBlock} reaches the same device through {@code blockRead}/{@code
 * blockWrite} (a byte[] and an offset); this class is the VM's own raw-address door to it, which {@link Fat32} and
 * the self-build's generation counter use.
 */
public final class Block
{
    private Block()
    {
    }

    /** The board's block operations ({@link Platform#OPS}); filled by the writer, never written at run time. */
    public static long hwInitAddr;    // blockInit()J: 0, or a negative step (BLOCK_NO_DEVICE: none on this board)
    public static long hwReadAddr;    // sectorRead(JJJ)J: lba, address, count -> 0 ok / -1
    public static long hwWriteAddr;   // sectorWrite(JJJ)J: as sectorRead, the other way

    /** The size of one sector, in bytes. */
    public static final int SECTOR = 512;

    /** Bring the device up (idempotent): 0 when it is ready, negative when there is none or it failed. */
    public static int init()
    {
        return (int) Magic.call0(hwInitAddr);
    }

    /** Read {@code n} sectors from {@code lba} into memory at {@code dst} (word aligned). {@link #init} first. */
    public static boolean read(long lba, long dst, int n)
    {
        return Magic.call3(hwReadAddr, lba, dst, n) == 0L;
    }

    /** Write {@code n} sectors to {@code lba} from memory at {@code src} (word aligned). {@link #init} first. */
    public static boolean write(long lba, long src, int n)
    {
        return Magic.call3(hwWriteAddr, lba, src, n) == 0L;
    }
}
