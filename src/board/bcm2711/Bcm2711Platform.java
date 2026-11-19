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
package board.bcm2711;

import board.Irq;
import board.Platform;
import board.Uart;
import board.arm.Gic;
import board.cyw43.Cyw43;
import magic.ObjectReference;
import objectmodel.ObjectModel;

/**
 * The Raspberry Pi 4 (BCM2711) platform: the {@link Platform#OPS} over the VideoCore mailbox, the PM watchdog,
 * EMMC2 and the RNG200. Bound into a {@code --board pi4} image by the writer; nothing calls it by name.
 */
public final class Bcm2711Platform
{
    private Bcm2711Platform()
    {
    }

    /** {@code MetalBoard.boardId0()}. */
    public static long boardId()
    {
        return Platform.BOARD_BCM2711;
    }

    /**
     * {@code MetalBoard.setRebootFlags0(int)}: firmware property SET_REBOOT_FLAGS ({@code 0x00038064}). Value 1
     * makes the NEXT boot a tryboot -- the firmware reads {@code tryboot.txt} instead of {@code config.txt} -- and
     * the flag is one-shot. 0 = the firmware answered, -1 = it did not.
     */
    public static long setRebootFlags(long flags)
    {
        int[] w = new int[1];
        w[0] = (int) flags;
        return Mailbox.tag(Bcm2711.TAG_SET_REBOOT_FLAGS, w) ? 0L : -1L;
    }

    /** {@code MetalBoard.reboot0()}: drain the console, then the PM watchdog reset. Never returns. */
    public static void reboot()
    {
        Uart.flush();
        Reset.reboot();
    }

    /** {@code MetalBlock.init0()}: bring the microSD card up (EMMC2); 0 or a negative step. */
    public static long blockInit()
    {
        return Emmc.init();
    }

    /** {@code MetalBlock.read0(long,byte[],int,int)}: bounds and alignment checked by the guest. 0 = ok. */
    public static long blockRead(long lba, ObjectReference arr, long off, long n)
    {
        if (arr.isNull() || n <= 0L)
        {
            return n == 0L ? 0L : -1L;
        }
        long dst = arr.toLong() + ObjectModel.ARRAY_BASE_OFFSET + off;
        return Emmc.readBlocks(lba, dst, (int) n) ? 0L : -1L;
    }

    /** {@code MetalBlock.write0(long,byte[],int,int)}: as {@link #blockRead}, the other way. */
    public static long blockWrite(long lba, ObjectReference arr, long off, long n)
    {
        if (arr.isNull() || n <= 0L)
        {
            return n == 0L ? 0L : -1L;
        }
        long src = arr.toLong() + ObjectModel.ARRAY_BASE_OFFSET + off;
        return Emmc.writeBlocks(lba, src, (int) n) ? 0L : -1L;
    }

    /** {@code MetalRamfs.hwEntropy0(byte[],int)}: up to {@code len} bytes from the RNG200; how many it gave. */
    public static long entropy(ObjectReference arr, long len)
    {
        if (arr.isNull() || len <= 0L)
        {
            return 0L;
        }
        long cap = arr.toAddress().plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong();
        long n = len < cap ? len : cap;
        return Rng.fill(arr.toLong() + ObjectModel.ARRAY_BASE_OFFSET, (int) n);
    }

    /** The boot "hw rng:" line. */
    public static void entropyReport()
    {
        Rng.report();
    }

    // ----- the console: the mini-UART ------------------------------------------------------------------------

    public static void consoleInit()
    {
        MiniUart.init();
    }

    public static long consoleTxReady()
    {
        return MiniUart.txReady() ? 1L : 0L;
    }

    public static long consoleRxReady()
    {
        return MiniUart.rxReady() ? 1L : 0L;
    }

    public static long consoleGet()
    {
        return MiniUart.get();
    }

    public static void consolePut(long c)
    {
        MiniUart.put((int) c);
    }

    public static void consoleSetIrq(long want)
    {
        MiniUart.setIrq((int) want);
    }

    /** The AUX interrupt, SPI 125. */
    public static long consoleSpi()
    {
        return Bcm2711.AUX_SPI;
    }

    /** The tick: the non-secure EL1 physical timer, PPI 30, through the GIC-400. */
    public static long irqInitTimer()
    {
        Gic.init(Bcm2711.GICD, Bcm2711.GICC, Gic.PPI_CNTPNS);
        return Gic.PPI_CNTPNS;
    }

    public static long irqAck()
    {
        int id = Gic.acknowledge(Bcm2711.GICC);
        if (id == Gic.SPURIOUS)
        {
            return Irq.NONE;
        }
        return id;
    }

    public static void irqEnd(long id)
    {
        Gic.end(Bcm2711.GICC, (int) id);
    }

    public static void irqEnable(long id)
    {
        Gic.initSpi(Bcm2711.GICD, Bcm2711.GICC, (int) id);
    }

    public static void irqMask(long id)
    {
        Gic.disable(Bcm2711.GICD, (int) id);
    }

    public static void irqUnmask(long id)
    {
        Gic.enable(Bcm2711.GICD, (int) id);
    }

    /** The Pi's device interrupts: the WiFi chip's SDIO card interrupt (SPI 158) wakes the net RX task. */
    public static long irqDevice(long id)
    {
        if (id == Bcm2711.SDIO_SPI)
        {
            if (Cyw43.onIrq())
            {
                return Irq.DEV_WAKE_NET;
            }
            return Irq.DEV_DONE;
        }
        return Irq.DEV_UNKNOWN;
    }
}
