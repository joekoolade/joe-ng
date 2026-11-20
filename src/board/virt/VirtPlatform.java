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

import board.Board;
import board.Irq;
import board.Platform;
import board.Uart;
import board.arm.Gic;
import board.arm.Pl011;
import board.arm.Psci;
import compiler.Target;
import magic.Address;
import magic.Magic;
import magic.ObjectReference;
import net.Link;
import objectmodel.ObjectModel;
import vm.VMStats;

/**
 * The QEMU {@code virt} platform: the {@link Platform#OPS} over virtio-rng, with no block device and no firmware.
 * Bound into a {@code --board virt} image by the writer; nothing calls it by name.
 */
public final class VirtPlatform implements magic.Uninterruptible
{
    private VirtPlatform()
    {
    }

    /** The generic timer the tick and counter use ({@code compiler.Target}): the EL1 virtual timer (PPI 27): under HVF the physical timer is not emulated. */
    public static final long GENERIC_TIMER = Target.TIMER_VIRT;

    /** RAM starts at physical 0x4000_0000: virtual 0 maps there ({@code board.Board.ramPhys}). */
    public static final long RAM_PHYS = Board.VIRT_RAM_PHYS;

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

    /** {@code MetalBoard.reboot0()}: drain the console, then PSCI SYSTEM_RESET. Never returns. */
    public static void reboot()
    {
        Uart.flush();
        Psci.systemReset();
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

    /** {@code board.Block.read}: unreachable past a failed init; answers failure if reached. */
    public static long sectorRead(long lba, long dst, long n)
    {
        return -1L;
    }

    /** {@code board.Block.write}: as {@link #sectorRead}. */
    public static long sectorWrite(long lba, long src, long n)
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

    // ----- the console: the PL011 --------------------------------------------------------------------------

    public static void consoleInit()
    {
        Pl011.init(Board.VIRT_PL011);
    }

    public static long consoleTxReady()
    {
        return Pl011.txReady(Board.VIRT_PL011) ? 1L : 0L;
    }

    public static long consoleRxReady()
    {
        return Pl011.rxReady(Board.VIRT_PL011) ? 1L : 0L;
    }

    public static long consoleGet()
    {
        return Pl011.get(Board.VIRT_PL011);
    }

    public static void consolePut(long c)
    {
        Pl011.put(Board.VIRT_PL011, (int) c);
    }

    public static void consoleSetIrq(long want)
    {
        Pl011.setIrq(Board.VIRT_PL011, (int) want);
    }

    /** The PL011's SPI 1, INTID 33. */
    public static long consoleSpi()
    {
        return Board.VIRT_UART_SPI;
    }

    /** The tick: the EL1 virtual timer, PPI 27 (Baseline lowers the timer intrinsics to CNTV here). */
    public static long irqInitTimer()
    {
        Gic.init(Board.VIRT_GICD, Board.VIRT_GICC, Gic.PPI_CNTV);
        return Gic.PPI_CNTV;
    }

    public static long irqAck()
    {
        int id = Gic.acknowledge(Board.VIRT_GICC);
        if (id == Gic.SPURIOUS)
        {
            return Irq.NONE;
        }
        return id;
    }

    public static void irqEnd(long id)
    {
        Gic.end(Board.VIRT_GICC, (int) id);
    }

    public static void irqEnable(long id)
    {
        Gic.initSpi(Board.VIRT_GICD, Board.VIRT_GICC, (int) id);
    }

    public static void irqMask(long id)
    {
        Gic.disable(Board.VIRT_GICD, (int) id);
    }

    public static void irqUnmask(long id)
    {
        Gic.enable(Board.VIRT_GICD, (int) id);
    }

    /** virt's device interrupts: the virtio-net transport's (its INTID is found at probe time) wakes the net RX task. */
    public static long irqDevice(long id)
    {
        if (VirtioNet.intid != 0 && id == VirtioNet.intid)
        {
            if (VirtioNet.onIrq())
            {
                return Irq.DEV_WAKE_NET;
            }
            return Irq.DEV_DONE;
        }
        return Irq.DEV_UNKNOWN;
    }

    /** The link is there when QEMU gave the machine a virtio-net card ({@code scripts/virt.sh --net}). */
    public static long netPresent()
    {
        return VirtioNet.present() ? 1L : 0L;
    }

    /** QEMU -netdev user: DHCP hands out 10.0.2.15, gateway .2, DNS .3. There is no link demo. */
    public static void netUp(long demo)
    {
        VirtioNet.bringUp();
    }

    public static void netSend(long frame, long len)
    {
        VirtioNet.send(Address.fromLong(frame), (int) len);
    }

    public static long netMac()
    {
        return VirtioNet.mac().toLong();
    }

    public static long netWaitFrame(long dst, long cap, long ms)
    {
        return VirtioNet.waitFrame(Address.fromLong(dst), (int) cap, (int) ms);
    }

    /** virtio-net delivers bare Ethernet frames: anything long enough for a header is data. */
    public static long netChannel(long rx, long len)
    {
        return len >= 14 ? Link.CH_DATA : Link.CH_OTHER;
    }

    public static long netEthOffset(long rx, long len)
    {
        return 0L;
    }

    /** No link events on virtio-net. */
    public static void netEvent(long rx, long len)
    {
    }

    /** No link-level protocol of its own (no EAPOL): nothing to do. */
    public static void netLinkFrame(long eth, long len)
    {
    }

    /** The {@code /net} page's link-driver slots: virtio-net keeps none of the WiFi counters, so they read 0. */
    public static long netLinkStats(long arr, long len, long at)
    {
        int i = (int) at;
        int n = 0;
        while (n < WIFI_STATS)
        {
            i = VMStats.put(arr, len, i, 0L);
            n = n + 1;
        }
        return i;
    }

    /** How many link counters the page's layout has after NetStats's (the Pi's WiFi driver's). */
    private static final int WIFI_STATS = 14;

    /**
     * The map for the 2 MiB block at virtual {@code a}: RAM at {@code a + VIRT_RAM_PHYS}, the device window at
     * {@code a - VIRT_DEV_VA}, and the Pi's peripheral window left unmapped (a Pi-only driver then faults by name).
     * Must agree with {@code writer.VirtPrelude}, which built the same map to turn the MMU on.
     */
    public static long pageBlock(long a)
    {
        if (a < Board.VIRT_DEV_LO)
        {
            return (a + Board.VIRT_RAM_PHYS) | 0x701L;   // block | AF | SH=inner | AttrIdx=0 (Normal WB)
        }
        if (a < Board.VIRT_DEV_HI)
        {
            return (a - Board.VIRT_DEV_VA) | 0x405L;     // block | AF | AttrIdx=1 (Device)
        }
        return 0L;
    }

    /** QEMU implements PSCI for its guest: CPU_ON over HVC ({@link Psci}). */
    public static void startSecondaries(long entry)
    {
        Psci.startSecondaries(entry);
    }
}
