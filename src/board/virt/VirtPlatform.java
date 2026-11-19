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
package board.virt;

import board.Platform;
import board.bcm2711.Uart;
import magic.Magic;
import magic.ObjectReference;
import objectmodel.ObjectModel;

/**
 * The QEMU {@code virt} platform: the {@link Platform#OPS} over virtio-rng, with no block device and no firmware.
 * Bound into a {@code --board virt} image by the writer; nothing calls it by name.
 */
public final class VirtPlatform
{
    private VirtPlatform()
    {
    }

    /** {@code MetalBoard.boardId0()}. */
    public static long boardId()
    {
        return Platform.BOARD_VIRT;
    }

    /** {@code MetalBoard.setRebootFlags0(int)}: there is no firmware to ask, so -1, "it did not answer". */
    public static long setRebootFlags(long flags)
    {
        return -1L;
    }

    /**
     * {@code MetalBoard.reboot0()}: NOT a reset yet -- that is PSCI SYSTEM_RESET over HVC, not wired. Say so and
     * stop this core, rather than reach for the Pi's watchdog (unmapped here, so it faulted).
     */
    public static void reboot()
    {
        Uart.write(Magic.bytes("\nreboot: no reset on virt yet (PSCI SYSTEM_RESET not wired) -- halted\n"));
        Uart.flush();
        while (true)
        {
            Magic.wfe();
        }
    }

    /** {@code MetalBlock.init0()}: no SD controller on virt -- {@link Platform#BLOCK_NO_DEVICE}. */
    public static long blockInit()
    {
        return Platform.BLOCK_NO_DEVICE;
    }

    /** {@code MetalBlock.read0}: unreachable past a failed init; answers failure if reached. */
    public static long blockRead(long lba, ObjectReference arr, long off, long n)
    {
        return -1L;
    }

    /** {@code MetalBlock.write0}: as {@link #blockRead}. */
    public static long blockWrite(long lba, ObjectReference arr, long off, long n)
    {
        return -1L;
    }

    /** {@code MetalRamfs.hwEntropy0(byte[],int)}: up to {@code len} bytes from virtio-rng; how many it gave. */
    public static long entropy(ObjectReference arr, long len)
    {
        if (arr.isNull() || len <= 0L)
        {
            return 0L;
        }
        long cap = arr.toAddress().plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong();
        long n = len < cap ? len : cap;
        return VirtioRng.fill(arr.toLong() + ObjectModel.ARRAY_BASE_OFFSET, (int) n);
    }

    /** The boot "hw rng:" line. */
    public static void entropyReport()
    {
        VirtioRng.report();
    }
}
