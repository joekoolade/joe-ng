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
import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VM;
import vm.VMScheduler;

/**
 * The network interface on QEMU {@code virt}: a virtio-net device ({@code -device virtio-net-device}) on the
 * machine's virtio-mmio transports -- the link {@link net.Link} hands the stack to there, where the Pi has its
 * CYW43455. Same shape as the Pi driver: synchronous receives during bring-up ({@link #waitFrame}, while the boot
 * task is the only reader), then an RX task ({@link #rxTask}) woken by the device's interrupt that hands every
 * frame to {@link net.RxQueues#dispatch}.
 *
 * <p>MODERN virtio only (virtio-mmio version 2), like {@link VirtioRng}: the runner passes
 * {@code -global virtio-mmio.force-legacy=false}. Features accepted: VERSION_1 and MAC, nothing else -- no checksum
 * offload, no merged RX buffers, so every frame carries the 12-byte {@code virtio_net_hdr_v1} with nothing in it.
 * The header is stripped on receive and zeroed on send: a frame reaches the stack as a plain Ethernet frame, and
 * {@link net.Link#ethOffset} is 0 here.
 *
 * <p>Two virtqueues, each with its descriptors permanently bound to one buffer of its own: queue 0 (receive)
 * keeps all {@link #RXQ} buffers posted to the device and re-posts each one as soon as its frame is copied out;
 * queue 1 (transmit) copies a frame into a free buffer and posts it, with completions reclaimed before the next
 * send. The rings and buffers come from the heap (held by statics, so the collector keeps them) and the device
 * reaches them by PHYSICAL address through {@link Board#phys}.
 */
public final class VirtioNet
{
    private VirtioNet()
    {
    }

    /** QEMU virt: 32 virtio-mmio transports of 0x200 bytes from physical 0x0A00_0000; transport n raises SPI 16+n. */
    private static final long MMIO_BASE = 0x0A00_0000L + Board.VIRT_DEV_VA;
    private static final int MMIO_SLOTS = 32;
    private static final long MMIO_STRIDE = 0x200L;
    private static final int MMIO_FIRST_INTID = 32 + 16;

    private static final long REG_MAGIC = 0x000;
    private static final long REG_VERSION = 0x004;
    private static final long REG_DEVICE_ID = 0x008;
    private static final long REG_DEVICE_FEATURES = 0x010;
    private static final long REG_DEVICE_FEATURES_SEL = 0x014;
    private static final long REG_DRIVER_FEATURES = 0x020;
    private static final long REG_DRIVER_FEATURES_SEL = 0x024;
    private static final long REG_QUEUE_SEL = 0x030;
    private static final long REG_QUEUE_NUM_MAX = 0x034;
    private static final long REG_QUEUE_NUM = 0x038;
    private static final long REG_QUEUE_READY = 0x044;
    private static final long REG_QUEUE_NOTIFY = 0x050;
    private static final long REG_INTERRUPT_STATUS = 0x060;
    private static final long REG_INTERRUPT_ACK = 0x064;
    private static final long REG_STATUS = 0x070;
    private static final long REG_QUEUE_DESC_LO = 0x080;
    private static final long REG_QUEUE_DESC_HI = 0x084;
    private static final long REG_QUEUE_DRIVER_LO = 0x090;
    private static final long REG_QUEUE_DRIVER_HI = 0x094;
    private static final long REG_QUEUE_DEVICE_LO = 0x0A0;
    private static final long REG_QUEUE_DEVICE_HI = 0x0A4;
    private static final long REG_CONFIG = 0x100;           // virtio_net_config: mac[6] first

    private static final int MAGIC = 0x7472_6976;           // "virt"
    private static final int DEVICE_NET = 1;
    private static final int ST_ACKNOWLEDGE = 1;
    private static final int ST_DRIVER = 2;
    private static final int ST_DRIVER_OK = 4;
    private static final int ST_FEATURES_OK = 8;
    private static final int F_MAC = 1 << 5;                // VIRTIO_NET_F_MAC (feature bit 5)
    private static final int VIRTQ_DESC_F_WRITE = 2;        // the device writes this buffer
    private static final int VIRTQ_AVAIL_F_NO_INTERRUPT = 1;

    private static final int RX = 0;
    private static final int TX = 1;
    private static final int RXQ = 32;                      // receive buffers kept posted
    private static final int TXQ = 16;                      // transmit buffers
    private static final int HDR = 12;                      // virtio_net_hdr_v1 (num_buffers present under VERSION_1)
    private static final int BUF = 2048;                    // per buffer: header + a 1514-byte frame, rounded up
    private static final int RING_BYTES = 0x1000;           // desc table (16 * n) + driver ring + device ring
    private static final long OFF_AVAIL = 0x400L;           // within a ring block (desc table first)
    private static final long OFF_USED = 0x800L;
    private static final int BUDGET = 32;                   // frames per RX-task poll before yielding
    private static final int WATCHDOG_MS = 100;             // the RX task polls this often even without an interrupt

    /** The interrupt this device raises (GIC INTID), once found; 0 = none. */
    public static int intid;

    private static long base;                               // the device's transport (virtual), 0 = not found
    private static Address mac;                             // 6 bytes
    private static Address rxRingMem;                       // the heap blocks (kept for the collector) ...
    private static Address txRingMem;
    private static Address rxBufs;
    private static Address txBufs;
    private static Address lockMem;
    private static long rxRing;                             // ... and the 16-aligned ring addresses inside them
    private static long txRing;
    private static int rxAvail;                             // driver-ring idx for each queue
    private static int txAvail;
    private static int rxUsed;                              // device-ring idx as last consumed
    private static int txUsed;
    private static int txBusy;                              // bit i: transmit buffer i is with the device
    private static int rxTaskId = -1;

    /** Frames sent, frames received, sends dropped (no free buffer in time), received frames too big for the reader. */
    public static long txFrames;
    public static long rxFrames;
    public static long txDropped;
    public static long rxTruncated;

    /**
     * Bring the interface up as the OS service a {@code net=1} program needs: find and start the device, configure
     * IPv4 ({@link net.NetUp} -- DHCP, the gateway's ARP, publish, ping), hand the device to the RX task, and set
     * the wall clock. Logs each step; on any failure the stack stays unpublished (no network).
     */
    public static void bringUp()
    {
        board.Uart.write(Magic.bytes("net: virtio-net bring-up\n"));
        if (!init())
        {
            return;
        }
        if (!net.NetUp.configure())
        {
            return;
        }
        if (!start())
        {
            return;
        }
        net.Sntp.sync();
    }

    /** Our MAC (6 bytes), or a zero address before {@link #init}. */
    public static Address mac()
    {
        return base == 0L ? Address.zero() : mac;
    }

    /** Find the device and bring it to DRIVER_OK with both queues set up and every receive buffer posted. */
    static boolean init()
    {
        int slot = 0;
        while (slot < MMIO_SLOTS)
        {
            long b = MMIO_BASE + slot * MMIO_STRIDE;
            if (reg(b, REG_MAGIC) == MAGIC && reg(b, REG_DEVICE_ID) == DEVICE_NET)
            {
                base = b;
                break;
            }
            slot += 1;
        }
        if (base == 0L)
        {
            board.Uart.write(Magic.bytes("net: no virtio-net device (-device virtio-net-device)\n"));
            return false;
        }
        if (reg(base, REG_VERSION) != 2)
        {
            board.Uart.write(Magic.bytes("net: virtio-net is legacy-only (-global virtio-mmio.force-legacy=false)\n"));
            base = 0L;
            return false;
        }
        setReg(REG_STATUS, 0);                                       // reset
        setReg(REG_STATUS, ST_ACKNOWLEDGE);
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER);
        setReg(REG_DEVICE_FEATURES_SEL, 1);                          // features 32..63
        int hi = reg(base, REG_DEVICE_FEATURES);
        setReg(REG_DEVICE_FEATURES_SEL, 0);
        int lo = reg(base, REG_DEVICE_FEATURES);
        if ((hi & 1) == 0)
        {
            board.Uart.write(Magic.bytes("net: virtio-net refused (no VERSION_1)\n"));
            return false;
        }
        int accept = lo & F_MAC;
        setReg(REG_DRIVER_FEATURES_SEL, 1);
        setReg(REG_DRIVER_FEATURES, 1);                              // VERSION_1
        setReg(REG_DRIVER_FEATURES_SEL, 0);
        setReg(REG_DRIVER_FEATURES, accept);                         // MAC, if offered
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER | ST_FEATURES_OK);
        if ((reg(base, REG_STATUS) & ST_FEATURES_OK) == 0)
        {
            board.Uart.write(Magic.bytes("net: virtio-net refused FEATURES_OK\n"));
            return false;
        }
        mac = Heap.allocData(8);
        int i = 0;
        while (i < 6)
        {
            int m = accept != 0 ? Address.fromLong(base + REG_CONFIG + i).loadUnsignedByte() & 0xFF : 0;
            if (accept == 0)
            {
                m = i == 0 ? 0x52 : i == 1 ? 0x54 : i == 2 ? 0x00 : 0x12 + i;   // QEMU's default range
            }
            mac.plus(i).store((byte) m);
            i += 1;
        }
        rxRingMem = Heap.allocData(RING_BYTES + 16);
        txRingMem = Heap.allocData(RING_BYTES + 16);
        rxRing = (rxRingMem.toLong() + 15L) & ~15L;
        txRing = (txRingMem.toLong() + 15L) & ~15L;
        rxBufs = Heap.allocData(RXQ * BUF);
        txBufs = Heap.allocData(TXQ * BUF);
        lockMem = Heap.allocData(16);
        if (!setupQueue(RX, rxRing, RXQ) || !setupQueue(TX, txRing, TXQ))
        {
            board.Uart.write(Magic.bytes("net: virtio-net queue too small\n"));
            return false;
        }
        i = 0;
        while (i < RXQ)                                              // descriptor i = receive buffer i, forever
        {
            Address d = Address.fromLong(rxRing + i * 16L);
            d.store(Board.phys(rxBufs.toLong() + (long) i * BUF));
            d.plus(8L).store(BUF);
            d.plus(12L).store((short) VIRTQ_DESC_F_WRITE);
            Address.fromLong(rxRing + OFF_AVAIL + 4L + i * 2L).store((short) i);
            i += 1;
        }
        i = 0;
        while (i < TXQ)
        {
            Address.fromLong(txRing + i * 16L).store(Board.phys(txBufs.toLong() + (long) i * BUF));
            i += 1;
        }
        Address.fromLong(txRing + OFF_AVAIL).store((short) VIRTQ_AVAIL_F_NO_INTERRUPT);   // completions are polled
        Magic.dsb();
        rxAvail = RXQ;
        Address.fromLong(rxRing + OFF_AVAIL + 2L).store((short) rxAvail);
        setReg(REG_STATUS, ST_ACKNOWLEDGE | ST_DRIVER | ST_FEATURES_OK | ST_DRIVER_OK);
        Magic.dsb();
        setReg(REG_QUEUE_NOTIFY, RX);
        intid = MMIO_FIRST_INTID + slot;
        board.Uart.write(Magic.bytes("net: virtio-net mac "));
        net.Ip.printMac(mac);
        board.Uart.write(Magic.bytes(" irq "));
        VM.printDec(intid);
        board.Uart.putc(0x0A);
        return true;
    }

    /** Zero queue {@code q}'s ring block and hand its three parts to the device. */
    private static boolean setupQueue(int q, long ring, int size)
    {
        setReg(REG_QUEUE_SEL, q);
        if (reg(base, REG_QUEUE_NUM_MAX) < size)
        {
            return false;
        }
        long p = ring;
        while (p < ring + RING_BYTES)
        {
            Address.fromLong(p).store(0L);
            p += 8L;
        }
        setReg(REG_QUEUE_NUM, size);
        setAddr(REG_QUEUE_DESC_LO, REG_QUEUE_DESC_HI, Board.phys(ring));
        setAddr(REG_QUEUE_DRIVER_LO, REG_QUEUE_DRIVER_HI, Board.phys(ring + OFF_AVAIL));
        setAddr(REG_QUEUE_DEVICE_LO, REG_QUEUE_DEVICE_HI, Board.phys(ring + OFF_USED));
        setReg(REG_QUEUE_READY, 1);
        return true;
    }

    /**
     * Send one Ethernet frame: reclaim finished transmits, copy the frame behind a zeroed header into a free
     * buffer, post it and notify. Any task may send (the RX task's ACKs, a program's writes), so the ring is taken
     * under a spinlock with IRQs masked. A frame that finds no free buffer within ~100 ms is dropped and counted.
     */
    public static void send(Address frame, int len)
    {
        if (base == 0L || len > BUF - HDR)
        {
            txDropped = txDropped + 1L;
            return;
        }
        long daif = Magic.readDaif();
        Magic.disableIrq();
        Magic.spinLock(lockMem.toLong());
        int slot = freeTxSlot();
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 10L;
        while (slot < 0 && Magic.readCNTPCT_EL0() < deadline)
        {
            slot = freeTxSlot();
        }
        if (slot >= 0)
        {
            Address buf = txBufs.plus((long) slot * BUF);
            int k = 0;
            while (k < HDR)
            {
                buf.plus(k).store((byte) 0);
                k += 1;
            }
            copy(frame, buf.plus(HDR), len);
            Address d = Address.fromLong(txRing + slot * 16L);
            d.plus(8L).store(HDR + len);
            d.plus(12L).store((short) 0);
            Address.fromLong(txRing + OFF_AVAIL + 4L + (txAvail % TXQ) * 2L).store((short) slot);
            txBusy = txBusy | (1 << slot);
            Magic.dsb();                                             // the buffer and descriptor before the index
            txAvail = (txAvail + 1) & 0xFFFF;
            Address.fromLong(txRing + OFF_AVAIL + 2L).store((short) txAvail);
            Magic.dsb();                                             // the index before the notify
            setReg(REG_QUEUE_NOTIFY, TX);
            txFrames = txFrames + 1L;
        }
        else
        {
            txDropped = txDropped + 1L;
        }
        Magic.spinUnlock(lockMem.toLong());
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
    }

    /** Reclaim the transmits the device has finished, then the lowest free buffer, or -1. Under the TX lock. */
    private static int freeTxSlot()
    {
        Magic.dsb();
        int used = Address.fromLong(txRing + OFF_USED + 2L).loadChar() & 0xFFFF;
        while (txUsed != used)
        {
            int id = Address.fromLong(txRing + OFF_USED + 4L + (txUsed % TXQ) * 8L).loadInt();
            txBusy = txBusy & ~(1 << (id & 31));
            txUsed = (txUsed + 1) & 0xFFFF;
        }
        int i = 0;
        while (i < TXQ)
        {
            if ((txBusy & (1 << i)) == 0)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * The next received frame, copied (Ethernet header first, the virtio header dropped) to {@code dst}, or 0 if
     * none is waiting. Its buffer goes straight back to the device. One reader at a time: the boot task during
     * bring-up, the RX task after.
     */
    static int readOnce(Address dst, int cap)
    {
        Magic.dsb();
        int used = Address.fromLong(rxRing + OFF_USED + 2L).loadChar() & 0xFFFF;
        if (used == rxUsed)
        {
            return 0;
        }
        Magic.dsb();                                                 // the device's writes before we read them
        Address e = Address.fromLong(rxRing + OFF_USED + 4L + (rxUsed % RXQ) * 8L);
        int id = e.loadInt() % RXQ;
        int len = e.plus(4L).loadInt() - HDR;
        rxUsed = (rxUsed + 1) & 0xFFFF;
        if (len > cap)
        {
            rxTruncated = rxTruncated + 1L;
            len = cap;
        }
        if (len > 0)
        {
            copy(rxBufs.plus((long) id * BUF + HDR), dst, len);
        }
        else
        {
            len = 0;
        }
        Address.fromLong(rxRing + OFF_AVAIL + 4L + (rxAvail % RXQ) * 2L).store((short) id);   // re-post
        Magic.dsb();
        rxAvail = (rxAvail + 1) & 0xFFFF;
        Address.fromLong(rxRing + OFF_AVAIL + 2L).store((short) rxAvail);
        Magic.dsb();
        setReg(REG_QUEUE_NOTIFY, RX);
        rxFrames = rxFrames + 1L;
        return len;
    }

    /** Wait up to {@code ms} for a frame (bring-up only, before the RX task): its length, or 0. */
    public static int waitFrame(Address dst, int cap, int ms)
    {
        if (base == 0L)
        {
            return 0;
        }
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * ms / 1000L;
        while (true)
        {
            int len = readOnce(dst, cap);
            if (len > 0 || Magic.readCNTPCT_EL0() >= deadline)
            {
                return len;
            }
        }
    }

    /**
     * The ISR (from {@code VMScheduler.schedule}): acknowledge the device so its level interrupt drops, and answer
     * whether it reported a used buffer -- a received frame for the RX task.
     */
    public static boolean onIrq()
    {
        int st = reg(base, REG_INTERRUPT_STATUS);
        setReg(REG_INTERRUPT_ACK, st);
        return (st & 1) != 0;
    }

    /**
     * Hand the device to the RX task: build the queues, enable the interrupt, spawn the task. From here every
     * receive goes through {@link net.RxQueues}. False (synchronous receives stay in charge) if the task's entry
     * was not baked into the image.
     */
    static boolean start()
    {
        long entry = VM.virtNetRxTaskAddr;
        if (entry == 1L)
        {
            rxTask();                                    // never runs: makes the writer compile + stash rxTask
        }
        if (entry == 0L)
        {
            board.Uart.write(Magic.bytes("net: rx task not in the image -- staying synchronous\n"));
            return false;
        }
        net.RxQueues.init();
        net.Tcp.init();
        net.RxQueues.active = true;
        rxTaskId = VMScheduler.spawnKernel(entry);
        board.bcm2711.Gic.initSpi(intid);
        board.Uart.write(Magic.bytes("net: rx task started (virtio-net, interrupt-driven)\n"));
        return true;
    }

    /** The RX task: sleep until the interrupt (or the watchdog), then take every waiting frame. Never returns. */
    static void rxTask()
    {
        while (true)
        {
            VMScheduler.semWaitTimeout(VM.WIFI_SEM, WATCHDOG_MS);   // the network RX semaphore, shared with WiFi
            long t0 = Magic.readCNTPCT_EL0();
            int n = 0;
            while (true)
            {
                int b = net.RxQueues.allocBuf();
                if (b < 0)
                {
                    VMScheduler.kernelYield();           // consumers hold every buffer: let them drain
                    continue;
                }
                int len = readOnce(net.RxQueues.bufAddr(b), net.RxQueues.FRAME);
                if (len == 0)
                {
                    net.RxQueues.freeBuf(b);
                    break;
                }
                net.RxQueues.dispatch(b, len);
                n = n + 1;
                if (n % BUDGET == 0)
                {
                    VMScheduler.kernelYield();
                }
            }
            net.NetStats.rxBusyTicks = net.NetStats.rxBusyTicks + (Magic.readCNTPCT_EL0() - t0);
        }
    }

    /** Copy {@code len} bytes: whole longs while both ends are 8-aligned (the usual case), bytes otherwise. */
    private static void copy(Address src, Address dst, int len)
    {
        int i = 0;
        if (((src.toLong() | dst.toLong()) & 7L) == 0L)
        {
            while (i + 8 <= len)
            {
                dst.plus(i).store(src.plus(i).loadLong());
                i += 8;
            }
        }
        while (i < len)
        {
            dst.plus(i).store(src.plus(i).loadByte());
            i += 1;
        }
    }

    private static int reg(long b, long off)
    {
        return Address.fromLong(b + off).loadInt();
    }

    private static void setReg(long off, int v)
    {
        Address.fromLong(base + off).store(v);
    }

    private static void setAddr(long lo, long hi, long pa)
    {
        setReg(lo, (int) pa);
        setReg(hi, (int) (pa >>> 32));
    }
}
