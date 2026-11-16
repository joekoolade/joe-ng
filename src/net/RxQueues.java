/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-02
 */
package net;

import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VMScheduler;

/**
 * The receive side of the network stack once the WiFi RX task runs (board.cyw43.WlanPoll, the NAPI-style
 * poller): a fixed pool of frame buffers and a table of queues, one per consumer -- the firmware's control
 * channel and each bound UDP port. The RX task drains the chip into pool buffers and {@link #dispatch}es
 * every frame to the queue it belongs to (answering ARP and running TCP itself, so TCP has no queue); a
 * consumer blocks in {@link #recv} on its own queue's semaphore and never touches the chip.
 *
 * <p>This replaces "whoever happens to be waiting reads the next frame and drops what it does not want":
 * a DNS lookup used to discard a TCP segment that arrived during it, and while nothing waited at all (a
 * long class load between connect and the first write) nothing drained the chip.
 *
 * <p>A queue's semaphore counts its frames: {@link #enqueue} posts once per frame, {@link #recv} consumes
 * one token per frame. A token left over after {@link #unbind} only makes the next owner's recv find the
 * ring empty and return 0, which every caller already treats as "nothing yet".
 */
public final class RxQueues
{
    private RxQueues() {}

    public static final int FRAME = 2048;                // one SDPCM frame (header + BDC + 802.3)
    private static final int POOL = 64;                  // frame buffers
    private static final int MAXQ = 12;                  // queues: control + UDP ports
    private static final int DEPTH = 32;                 // frames per queue before it drops

    private static final int KIND_FREE = 0;
    private static final int KIND_CTRL = 1;
    private static final int KIND_UDP = 2;

    /** The firmware control channel (ioctl/iovar acks). Bound by {@link #init}. */
    public static final int Q_CTRL = 0;

    /** true once the RX task owns the chip: every receive must go through a queue. */
    public static boolean active;

    private static long pool;                            // POOL * FRAME bytes
    private static int[] freeList;                       // free buffer indices (a stack)
    private static int freeTop;
    private static int lockSem = -1;                     // mutex over the pool and every queue's ring

    private static int[] qKind;
    private static int[] qPort;                          // local port (UDP/TCP), 0 for control
    private static int[] qSem;                           // counts frames in the queue
    private static int[] qHead;
    private static int[] qCount;
    private static int[] qBuf;                           // MAXQ * DEPTH buffer indices
    private static int[] qLen;                           // ... and their frame lengths

    /** Allocate the pool, the queue table and their semaphores, and bind the control queue. Call once. */
    public static void init()
    {
        pool = Heap.allocData(POOL * FRAME).toLong();
        freeList = new int[POOL];
        int i = 0;
        while (i < POOL)
        {
            freeList[i] = i;
            i = i + 1;
        }
        freeTop = POOL;
        qKind = new int[MAXQ];
        qPort = new int[MAXQ];
        qSem = new int[MAXQ];
        qHead = new int[MAXQ];
        qCount = new int[MAXQ];
        qBuf = new int[MAXQ * DEPTH];
        qLen = new int[MAXQ * DEPTH];
        i = 0;
        while (i < MAXQ)
        {
            qSem[i] = VMScheduler.kernelSem(0);
            i = i + 1;
        }
        lockSem = VMScheduler.kernelSem(1);
        qKind[Q_CTRL] = KIND_CTRL;
    }

    private static void lock()
    {
        VMScheduler.semWait(lockSem);
    }

    private static void unlock()
    {
        VMScheduler.kernelPost(lockSem);
    }

    /** A free buffer index, or -1 if the pool is exhausted. */
    public static int allocBuf()
    {
        lock();
        int b = -1;
        if (freeTop > 0)
        {
            freeTop = freeTop - 1;
            b = freeList[freeTop];
        }
        unlock();
        return b;
    }

    /** Return buffer {@code b} to the pool. */
    public static void freeBuf(int b)
    {
        lock();
        freeList[freeTop] = b;
        freeTop = freeTop + 1;
        unlock();
    }

    /** The address of buffer {@code b}. */
    public static long bufAddr(int b)
    {
        return pool + (long) b * FRAME;
    }

    /** Bind a queue for UDP datagrams to local port {@code port}; its index, or -1 if the table is full. */
    public static int bindUdp(int port)
    {
        return bind(KIND_UDP, port);
    }

    private static int bind(int kind, int port)
    {
        lock();
        int q = -1;
        int i = 1;
        while (i < MAXQ)
        {
            if (qKind[i] == KIND_FREE)
            {
                qKind[i] = kind;
                qPort[i] = port;
                qHead[i] = 0;
                qCount[i] = 0;
                q = i;
                break;
            }
            i = i + 1;
        }
        unlock();
        return q;
    }

    /** Release queue {@code q}, returning any frames still in it to the pool. */
    public static void unbind(int q)
    {
        if (q <= Q_CTRL || q >= MAXQ)
        {
            return;
        }
        lock();
        while (qCount[q] > 0)
        {
            int b = qBuf[q * DEPTH + qHead[q]];
            freeList[freeTop] = b;
            freeTop = freeTop + 1;
            qHead[q] = (qHead[q] + 1) % DEPTH;
            qCount[q] = qCount[q] - 1;
        }
        qKind[q] = KIND_FREE;
        qPort[q] = 0;
        unlock();
    }

    /**
     * Append buffer {@code b} ({@code len} bytes) to queue {@code q} and wake its consumer; drop it if full.
     * True when it was queued.
     */
    private static boolean enqueue(int q, int b, int len)
    {
        lock();
        if (qKind[q] == KIND_FREE || qCount[q] >= DEPTH)
        {
            freeList[freeTop] = b;
            freeTop = freeTop + 1;
            unlock();
            return false;
        }
        int slot = q * DEPTH + (qHead[q] + qCount[q]) % DEPTH;
        qBuf[slot] = b;
        qLen[slot] = len;
        qCount[q] = qCount[q] + 1;
        unlock();
        VMScheduler.kernelPost(qSem[q]);
        return true;
    }

    /**
     * Wait up to {@code ms} for the next frame on queue {@code q} and copy it (at most {@code cap} bytes) to
     * {@code dst}, raw SDPCM framing included, as the chip delivered it. Returns its length, or 0 on timeout.
     */
    public static int recv(int q, long dst, int cap, long ms)
    {
        if (!VMScheduler.semWaitTimeout(qSem[q], ms))
        {
            return 0;
        }
        lock();
        if (qCount[q] == 0)
        {
            unlock();
            return 0;                                    // a token left from an earlier owner of this slot
        }
        int slot = q * DEPTH + qHead[q];
        int b = qBuf[slot];
        int len = qLen[slot];
        qHead[q] = (qHead[q] + 1) % DEPTH;
        qCount[q] = qCount[q] - 1;
        unlock();
        int n = len < cap ? len : cap;
        long src = bufAddr(b);
        Address srcA = Address.fromLong(src);
        Address dstA = Address.fromLong(dst);
        int i = 0;
        while (i < n)
        {
            dstA.plus(i).store((byte) srcA.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        freeBuf(b);
        return len;
    }

    /** The queue bound to {@code kind}/{@code port}, or -1. */
    private static int find(int kind, int port)
    {
        lock();
        int q = -1;
        int i = 1;
        while (i < MAXQ)
        {
            if (qKind[i] == kind && qPort[i] == port)
            {
                q = i;
                break;
            }
            i = i + 1;
        }
        unlock();
        return q;
    }

    /**
     * Route one received SDPCM frame (in buffer {@code b}, {@code len} bytes) to its consumer, or free it.
     * Control acks go to {@link #Q_CTRL}; data frames are 802.3. ARP is answered here (a gateway re-verifying
     * us must be answered whoever is waiting). An IPv4 datagram is checked and counted ({@link NetStats});
     * TCP is processed right here, on the RX task, by {@link Tcp#input} -- so it is ACKed on arrival, not when
     * the application next reads -- and UDP goes to the queue bound to its destination port. Firmware events
     * (channel 1) have no consumer after bring-up and are counted as dropped.
     */
    public static void dispatch(int b, int len)
    {
        long rx = bufAddr(b);
        Address rxA = Address.fromLong(rx);
        int ch = rxA.plus(5).loadUnsignedByte() & 0x0F;
        if (ch == 0)
        {
            enqueue(Q_CTRL, b, len);
            return;
        }
        if (ch != 2)
        {
            if (ch == 1)
            {
                board.cyw43.Cyw43.logRxEvent(rx, len);
            }
            freeBuf(b);
            return;
        }
        long eth = rx + (rxA.plus(7).loadUnsignedByte() & 0xFF);    // SDPCM data offset
        eth = eth + 4 + (Address.fromLong(eth).plus(3).loadUnsignedByte() & 0xFF) * 4;   // skip the BDC header
        Address ethA = Address.fromLong(eth);
        int et = (ethA.plus(12).loadUnsignedByte() & 0xFF) << 8 | (ethA.plus(13).loadUnsignedByte() & 0xFF);
        if (et == 0x0806)
        {
            Ip.answerArp(eth);
            freeBuf(b);
            return;
        }
        if (et != 0x0800)
        {
            if (et == 0x888E)
            {
                board.cyw43.Supplicant.input(eth, len - (int) (eth - rx));
                freeBuf(b);                              // answered here: a re-handshake must be, or the AP
                return;                                  //   deauths us (reason 15, Pi run 13)
            }
            freeBuf(b);
            return;
        }
        long ip = eth + 14;
        if (!NetStats.ipIn(ip, len - (int) (ip - rx)))
        {
            freeBuf(b);
            return;
        }
        Address ipA = Address.fromLong(ip);
        int ihl = (ipA.loadUnsignedByte() & 0x0F) * 4;
        int total = (ipA.plus(2).loadUnsignedByte() & 0xFF) << 8 | (ipA.plus(3).loadUnsignedByte() & 0xFF);
        int proto = ipA.plus(9).loadUnsignedByte() & 0xFF;
        if (proto == 6)
        {
            NetStats.ipInDelivers = NetStats.ipInDelivers + 1;
            NetStats.tcpInSegs = NetStats.tcpInSegs + 1;
            long t0 = Magic.readCNTPCT_EL0();
            Tcp.input(ip, total);
            NetStats.rxTcpTicks = NetStats.rxTcpTicks + (Magic.readCNTPCT_EL0() - t0);
            freeBuf(b);
            return;
        }
        if (proto == 1)
        {
            NetStats.ipInDelivers = NetStats.ipInDelivers + 1;
            NetStats.icmpInMsgs = NetStats.icmpInMsgs + 1;   // nothing consumes ICMP after bring-up
            freeBuf(b);
            return;
        }
        if (proto != 17)
        {
            NetStats.ipInUnknownProtos = NetStats.ipInUnknownProtos + 1;
            freeBuf(b);
            return;
        }
        NetStats.ipInDelivers = NetStats.ipInDelivers + 1;
        long udp = ip + ihl;
        if (!NetStats.udpOk(ip, udp, total - ihl))
        {
            freeBuf(b);
            return;
        }
        Address udpA = Address.fromLong(udp);
        int dport = (udpA.plus(2).loadUnsignedByte() & 0xFF) << 8 | (udpA.plus(3).loadUnsignedByte() & 0xFF);
        int q = find(KIND_UDP, dport);
        if (q < 0)
        {
            NetStats.udpNoPorts = NetStats.udpNoPorts + 1;
            freeBuf(b);
            return;
        }
        if (enqueue(q, b, len))
        {
            NetStats.udpInDatagrams = NetStats.udpInDatagrams + 1;
        }
        else
        {
            NetStats.udpRcvbufErrors = NetStats.udpRcvbufErrors + 1;
            NetStats.ipInDiscards = NetStats.ipInDiscards + 1;
        }
    }
}
