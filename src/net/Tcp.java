/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-09
 */
package net;

import magic.Magic;
import vm.Heap;
import vm.VM;
import vm.VMScheduler;

/**
 * A TCP client: a connection table the VM's socket layer (stock {@code java.net} over the sun.nio.ch natives)
 * drives through {@link #connect}/{@link #write}/{@link #read}/{@link #poll}/{@link #close}.
 *
 * <p><b>Receive runs in the network's receive context, not the application's.</b> Once the WiFi RX task owns
 * the chip ({@link RxQueues#active}), {@link RxQueues#dispatch} hands every TCP segment to {@link #input} on
 * that task: the segment is checked, its payload lands in the connection's receive ring, and it is ACKed
 * there and then -- whatever the application is doing. An application read only copies out of the ring.
 * Before this, a segment was processed (and ACKed) only when the application called read, and Pi run 10 lost
 * its TLS connection because the application spent 14 s between reads while the server's data sat un-ACKed.
 *
 * <p>Out-of-order data is kept: a segment past a hole is copied to its place in the ring and remembered in a
 * short hole list, and when the missing bytes arrive the receive point jumps over everything that was held.
 * The window advertised is the ring's free space, so the peer never sends more than there is room for.
 *
 * <p>Before the RX task starts (no task baked, or code that runs during bring-up) the application's own wait
 * reads the chip and feeds {@link #input} itself: one receive path, two places it can run.
 *
 * <p>Scope: IPv4, one route (everything to the gateway MAC). Sent data is not retransmitted and a write is
 * one segment (increment C); connect resends the SYN until answered.
 */
public final class Tcp
{
    private Tcp() {}

    private static final int MAXCONN = 4;
    private static final int FREE = 0;
    private static final int CONNECTED = 1;
    private static final int CLOSED = 2;
    private static final int RESERVED = 3;   // alloc()'d (a socket fd exists) but not yet connected
    private static final int SYN_SENT = 4;   // connect() sent the SYN; waiting for the SYN-ACK

    private static final int RX_WAIT_MS = 200;
    private static final int RCVBUF = 32768;         // receive ring per connection (a power of two, < 65536)
    private static final int RCVMASK = RCVBUF - 1;
    private static final int OUR_MSS = 1460;         // what we ask the peer to send (Ethernet MTU 1500 - 40)
    private static final int MAXHOLE = 8;            // out-of-order runs held per connection

    /**
     * Why a call failed, as the NEGATED Linux errno the C library would have set -- the socket layer (the
     * sun.nio.ch overlays) throws for each exactly what unix/native/libnio throws for that errno.
     */
    public static final int EBADF = 9;
    public static final int EINVAL = 22;
    public static final int EPIPE = 32;
    public static final int ECONNRESET = 104;
    public static final int ENOTCONN = 107;
    public static final int ENETUNREACH = 101;
    public static final int ETIMEDOUT = 110;
    public static final int ECONNREFUSED = 111;

    private static int[]  st    = new int[MAXCONN];   // slot state
    private static long[] rip   = new long[MAXCONN];  // remote IPv4 (4-byte heap buf)
    private static int[]  rport = new int[MAXCONN];   // remote port
    private static int[]  lport = new int[MAXCONN];   // local (ephemeral) port
    private static int[]  snd   = new int[MAXCONN];   // our next send sequence number
    private static int[]  una   = new int[MAXCONN];   // the oldest of our bytes the peer has not ACKed
    private static int[]  rcv   = new int[MAXCONN];   // our ack = next expected sequence number
    private static int[]  rdSeq = new int[MAXCONN];   // sequence number of the next byte the application reads
    private static int[]  finIn = new int[MAXCONN];   // 1 = the peer's FIN is accepted (counted in rcv, not data)
    private static int[]  finPend = new int[MAXCONN]; // 1 = a FIN arrived past a hole; finAt is its sequence
    private static int[]  finAt = new int[MAXCONN];
    private static long[] ring  = new long[MAXCONN];  // RCVBUF-byte receive ring, indexed by sequence number
    private static int[]  holeS = new int[MAXCONN * MAXHOLE];   // out-of-order runs [holeS, holeE) held in
    private static int[]  holeE = new int[MAXCONN * MAXHOLE];   //   the ring past rcv
    private static int[]  holes = new int[MAXCONN];
    private static int[]  lastWin = new int[MAXCONN]; // the window our last segment advertised
    private static int[]  peerMss = new int[MAXCONN]; // the MSS option of the peer's SYN-ACK (536 if absent)
    private static int[]  nb    = new int[MAXCONN];   // 1 = non-blocking (IOUtil.configureBlocking(fd, false))
    private static int[]  waiting = new int[MAXCONN]; // 1 = the application waits on connSem (input posts it)
    private static int[]  connSem = new int[MAXCONN]; // per-connection wakeup (allocated by init)
    private static int[]  refused = new int[MAXCONN]; // 1 = our SYN was answered by a RST
    private static int[]  rst   = new int[MAXCONN];   // 1 = closed by the peer's RST (reads/writes: ECONNRESET)
    private static int[]  finOut = new int[MAXCONN];  // 1 = our FIN is sent (shutdown SHUT_WR): writes get EPIPE
    private static int[]  rdShut = new int[MAXCONN];  // 1 = shutdown SHUT_RD: reads end once the buffer is empty

    // What each connection MEASURED.
    private static int[]  segs  = new int[MAXCONN];   // segments received since connect (any flags)
    private static int[]  lastFl = new int[MAXCONN];  // flags of the last segment received
    private static int[]  inord = new int[MAXCONN];   // data segments accepted at rcv
    private static int[]  ooo   = new int[MAXCONN];   // data segments past rcv (held for reassembly or dropped)
    private static int[]  rbytes = new int[MAXCONN];  // payload bytes accepted in order (incl. merged runs)
    private static int[]  wbytes = new int[MAXCONN];  // payload bytes written
    private static long[] tStart = new long[MAXCONN]; // CNTPCT when connect() began (trace times are from here)
    private static long[] tConn = new long[MAXCONN];  // CNTPCT when the SYN-ACK was accepted
    private static long[] tWr   = new long[MAXCONN];  // CNTPCT of the first data write (0 = none)
    private static int[]  isn0  = new int[MAXCONN];   // our ISN (the trace prints seq relative to it)
    private static int[]  pisn  = new int[MAXCONN];   // the peer's ISN (0 until the SYN-ACK)
    private static int[]  dumped = new int[MAXCONN];  // 1 = this connection's trace has been printed

    /**
     * MEASUREMENT: the first {@link #TRACE_SEGS} segments of each connection, sent or received, RECORDED when
     * they happen (a received one when the RX task reads it off the chip -- its arrival time) and PRINTED only
     * when the connection ends badly (RST, a refused or timed-out connect, a poll timeout). Printing from the RX
     * task would stall the very ACKs being measured: one UART line is ~7 ms.
     */
    private static final int TRACE_SEGS = 64;
    private static int[]  trc    = new int[MAXCONN];
    private static int[]  trTx   = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trFl   = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trSeq  = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trAck  = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trLen  = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trWin  = new int[MAXCONN * TRACE_SEGS];
    private static int[]  trRcv  = new int[MAXCONN * TRACE_SEGS];   // our rcv after the segment (rx only)
    private static long[] trT    = new long[MAXCONN * TRACE_SEGS];

    private static int lock = -1;                     // one mutex over the whole table (-1: before init, no-op)
    private static long syncRx;                       // receive buffer for the pre-RX-task path

    /** {@code sun.nio.ch.IOStatus.UNAVAILABLE}: a non-blocking read found nothing to return yet. */
    public static final int UNAVAILABLE = -2;

    /** The Linux {@code <poll.h>} bits, as {@code Net.pollinValue()} and its siblings answer them here. */
    public static final int POLLIN = 0x1;
    public static final int POLLOUT = 0x4;
    public static final int POLLHUP = 0x10;
    public static final int POLLNVAL = 0x20;

    /**
     * Build the table lock and the per-connection wakeups. Called by the WiFi driver before its RX task starts
     * feeding {@link #input}; kernel semaphores are allocated without a lock, so only at bring-up.
     */
    public static void init()
    {
        int i = 0;
        while (i < MAXCONN)
        {
            connSem[i] = VMScheduler.kernelSem(0);
            i = i + 1;
        }
        lock = VMScheduler.kernelSem(1);
    }

    private static void lock()
    {
        if (lock >= 0)
        {
            VMScheduler.semWait(lock);
        }
    }

    private static void unlock()
    {
        if (lock >= 0)
        {
            VMScheduler.kernelPost(lock);
        }
    }

    /**
     * Allocate a socket slot (like {@code Net.socket()}): reserve a connection handle without connecting.
     * Returns the handle (0..MAXCONN-1) or -1 if none free. The socket layer's {@code fd} int is this handle.
     */
    public static int alloc()
    {
        lock();
        int h = -1;
        int i = 0;
        while (i < MAXCONN)
        {
            if (st[i] == FREE)
            {
                if (ring[i] == 0L)
                {
                    ring[i] = Heap.allocData(RCVBUF);
                    rip[i] = Heap.allocData(4);
                }
                st[i] = RESERVED;
                rcv[i] = 0;
                rdSeq[i] = 0;
                finIn[i] = 0;
                finOut[i] = 0;
                rdShut[i] = 0;
                nb[i] = 0;                               // a new socket blocks, as on a JVM
                h = i;
                break;
            }
            i = i + 1;
        }
        unlock();
        return h;
    }

    /**
     * Connect an alloc()'d handle to {@code ipBe}:{@code port} (blocking SYN/SYN-ACK; ipBe = big-endian IPv4
     * int). Returns 1 on success, else a negated errno: -ECONNREFUSED (a RST of our SYN, or a loopback address,
     * where nothing on this VM listens), -ETIMEDOUT, -ENETUNREACH (no WiFi lease), -EBADF (not a socket). The
     * SYN-ACK itself is handled by {@link #input}, which ACKs it at once.
     */
    public static int connect(int h, int ipBe, int port)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return -EBADF;
        }
        if (((ipBe >>> 24) & 0xFF) == 127)
        {
            return -ECONNREFUSED;                        // 127/8: no socket on this VM listens
        }
        if (Ip.ourIp == 0L)
        {
            return -ENETUNREACH;                         // no interface is up (no WiFi, or QEMU)
        }
        int isn = (int) Ip.nextRandom();                // a fresh initial sequence number
        lock();
        Magic.store8(rip[h] + 0, (ipBe >> 24) & 0xFF);
        Magic.store8(rip[h] + 1, (ipBe >> 16) & 0xFF);
        Magic.store8(rip[h] + 2, (ipBe >> 8) & 0xFF);
        Magic.store8(rip[h] + 3, ipBe & 0xFF);
        rport[h] = port;
        lport[h] = Ip.ephemeralPort();                  // a fresh ephemeral port per connection
        snd[h] = isn;
        una[h] = isn;
        rcv[h] = 0;
        rdSeq[h] = 0;
        finIn[h] = 0;
        finPend[h] = 0;
        holes[h] = 0;
        peerMss[h] = 536;
        refused[h] = 0;
        rst[h] = 0;
        waiting[h] = 0;
        trc[h] = 0;
        dumped[h] = 0;
        tStart[h] = Magic.readCNTPCT_EL0();
        tConn[h] = 0L;
        tWr[h] = 0L;
        isn0[h] = isn;
        pisn[h] = 0;
        segs[h] = 0;
        lastFl[h] = -1;
        inord[h] = 0;
        ooo[h] = 0;
        rbytes[h] = 0;
        wbytes[h] = 0;
        st[h] = SYN_SENT;
        NetStats.tcpActiveOpens = NetStats.tcpActiveOpens + 1;
        unlock();

        int syns = 0;
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            lock();
            if (st[h] == CONNECTED)
            {
                unlock();
                return 1;
            }
            if (refused[h] != 0)
            {
                st[h] = RESERVED;
                NetStats.tcpAttemptFails = NetStats.tcpAttemptFails + 1;
                unlock();
                dump(h, Magic.bytes("refused"));
                return -ECONNREFUSED;
            }
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                snd[h] = isn;
                sendSeg(h, 0x02, 0L, 0);                 // SYN (with our MSS)
                snd[h] = isn + 1;
                syns = syns + 1;
                if (syns > 1)
                {
                    NetStats.tcpRetransSegs = NetStats.tcpRetransSegs + 1;
                }
                nextSend = now + freq / 2L;
            }
            waiting[h] = 1;
            unlock();
            waitEvent(h, RX_WAIT_MS);
        }
        lock();
        boolean won = st[h] == CONNECTED;               // the SYN-ACK can land between the last look and now
        if (!won)
        {
            st[h] = RESERVED;
            NetStats.tcpAttemptFails = NetStats.tcpAttemptFails + 1;
        }
        unlock();
        if (won)
        {
            return 1;
        }
        board.bcm2711.Uart.write(Magic.bytes("tcp: connect timed out: syns="));
        VM.printDec(syns);
        board.bcm2711.Uart.write(Magic.bytes(" fromPeer="));
        VM.printDec(segs[h]);
        board.bcm2711.Uart.write(Magic.bytes(" lastFlags="));
        VM.printDec(lastFl[h]);
        board.bcm2711.Uart.write(Magic.bytes(" lport="));
        VM.printDec(lport[h]);
        board.bcm2711.Uart.write(Magic.bytes("\n"));
        dump(h, Magic.bytes("connect timed out"));
        return -ETIMEDOUT;
    }

    /** Convenience for driver code (e.g. the WiFi HTTP demo): allocate + connect to a 4-byte IPv4 at
     *  {@code ipAddr}. Returns a connection handle, or -1 on failure. */
    public static int connect(long ipAddr, int port)
    {
        int h = alloc();
        if (h < 0)
        {
            return -1;
        }
        if (connect(h, Ip.readBe32(ipAddr), port) != 1)
        {
            close(h);
            return -1;
        }
        return h;
    }

    /**
     * Send {@code len} bytes from {@code buf+off} as one PSH|ACK segment; returns {@code len}, or the negated errno
     * {@code write(2)} would set: -ECONNRESET after the peer's RST, -EPIPE after its FIN, -ENOTCONN on a socket
     * that never connected, -EBADF on a bad handle. Never -1: NioSocketImpl adds the result to its offset, and a
     * -1 (its EOF code, which no native write returns) made the next put run at offset -1 (Pi run 7/8 IOOBE).
     */
    public static int write(int h, long buf, int off, int len)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return -EBADF;
        }
        if (st[h] == CLOSED)
        {
            dump(h, Magic.bytes("write after close"));
            return rst[h] != 0 ? -ECONNRESET : -EPIPE;
        }
        if (st[h] != CONNECTED)
        {
            return -ENOTCONN;
        }
        if (finOut[h] != 0)
        {
            return -EPIPE;                               // write(2) after shutdown(SHUT_WR)
        }
        if (tWr[h] == 0L)                                // MEASUREMENT: how stale is the connection by now?
        {
            tWr[h] = Magic.readCNTPCT_EL0();
            board.bcm2711.Uart.write(Magic.bytes("tcp: h="));
            VM.printDec(h);
            board.bcm2711.Uart.write(Magic.bytes(" first write "));
            VM.printDec(len);
            board.bcm2711.Uart.write(Magic.bytes(" bytes, "));
            VM.printDec(msBetween(tConn[h], tWr[h]));
            board.bcm2711.Uart.write(Magic.bytes(" ms after the SYN-ACK\n"));
        }
        lock();
        if (st[h] != CONNECTED)                          // the RX task closed it since the look above
        {
            int r = rst[h] != 0 ? -ECONNRESET : -EPIPE;
            unlock();
            return r;
        }
        sendSeg(h, 0x18, buf + off, len);               // PSH|ACK
        snd[h] = snd[h] + len;
        wbytes[h] = wbytes[h] + len;
        unlock();
        return len;
    }

    /**
     * Copy up to {@code len} received bytes into {@code buf+off} and return the count; -1 at end-of-stream (the
     * peer's FIN), -ECONNRESET after its RST. The bytes were already received and ACKed by {@link #input}; this
     * only copies them out of the ring. A BLOCKING socket waits until there is data or the stream ends, with no
     * time limit, as {@code read(2)} does -- {@code setSoTimeout} bounds it through {@link #poll}. A NON-blocking
     * socket answers {@link #UNAVAILABLE} when nothing has arrived.
     */
    public static int read(int h, long buf, int off, int len)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return -EBADF;
        }
        if (st[h] != CONNECTED && st[h] != CLOSED)
        {
            return -ENOTCONN;
        }
        boolean looked = false;
        while (true)
        {
            lock();
            int avail = readable(h);
            if (avail > 0)
            {
                int n = copyOut(h, buf + off, avail < len ? avail : len);
                unlock();
                return n;
            }
            if (rdShut[h] != 0 && rst[h] == 0)
            {
                unlock();
                return -1;                               // shutdown(SHUT_RD): end-of-stream, as read(2) answers 0
            }
            if (st[h] == CLOSED)
            {
                int r = rst[h] != 0 ? -ECONNRESET : -1;  // RST: reset (stock throws); FIN: end-of-stream
                unlock();
                if (rst[h] != 0)
                {
                    dump(h, Magic.bytes("reset"));
                }
                return r;
            }
            if (nb[h] != 0 && looked)
            {
                unlock();
                return UNAVAILABLE;
            }
            waiting[h] = 1;
            unlock();
            if (nb[h] != 0)
            {
                looked = true;
                if (!RxQueues.active)
                {
                    waitEvent(h, 0);                        // pre-RX-task: look at the chip once
                }
                continue;
            }
            waitEvent(h, RX_WAIT_MS);
        }
    }

    /**
     * {@code Net.poll}: wait until connection {@code h} is ready for {@code events} ({@link #POLLIN} /
     * {@link #POLLOUT}), or {@code timeoutMs} elapses (negative: no limit, 0: just look). Returns the ready
     * events, 0 on timeout, {@link #POLLNVAL} for a handle that is not a socket, and {@link #POLLHUP} for one
     * that will never become ready (not connected, or closed with only {@code POLLOUT} asked for). Readable
     * means data is buffered or the peer has closed (a read then answers -1); writable means connected, since
     * {@link #write} never waits.
     */
    public static int poll(int h, int events, long timeoutMs)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return POLLNVAL;
        }
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + (timeoutMs < 0L ? 0L : timeoutMs * freq / 1000L);
        while (true)
        {
            lock();
            int avail = readable(h);
            int ready = 0;
            if ((events & POLLIN) != 0 && (avail > 0 || st[h] == CLOSED || rdShut[h] != 0))
            {
                ready = ready | POLLIN;
            }
            if ((events & POLLOUT) != 0 && st[h] == CONNECTED)
            {
                ready = ready | POLLOUT;
            }
            if (ready != 0)
            {
                unlock();
                return ready;
            }
            if (st[h] != CONNECTED)
            {
                unlock();
                return POLLHUP;                          // nothing this socket can still deliver
            }
            int waitMs = RX_WAIT_MS;
            if (timeoutMs >= 0L)
            {
                long left = (endT - Magic.readCNTPCT_EL0()) * 1000L / freq;
                if (left <= 0L)
                {
                    unlock();
                    report(h);
                    return 0;                            // timed out
                }
                if (left < waitMs)
                {
                    waitMs = (int) left;
                }
            }
            waiting[h] = 1;
            unlock();
            waitEvent(h, waitMs);
        }
    }

    /** {@code IOUtil.configureBlocking}: make connection {@code h}'s reads blocking or non-blocking. */
    public static void setBlocking(int h, boolean blocking)
    {
        if (h >= 0 && h < MAXCONN)
        {
            nb[h] = blocking ? 0 : 1;
        }
    }

    /** Close the connection (send FIN|ACK if still open) and free the slot. */
    public static void close(int h)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return;
        }
        lock();
        if (st[h] == CONNECTED && finOut[h] == 0)
        {
            sendSeg(h, 0x11, 0L, 0);                     // FIN|ACK
            snd[h] = snd[h] + 1;
        }
        unlock();
        if (tConn[h] != 0L && dumped[h] == 0)
        {
            NetStats.print();                            // MEASUREMENT: the stack's counters at every close
        }
        lock();
        st[h] = FREE;
        unlock();
    }

    /**
     * {@code shutdown(2)} for {@code Net.shutdown}: {@code how} is Net.SHUT_RD (0), SHUT_WR (1) or SHUT_RDWR (2).
     * Shutting the write side sends our FIN once and keeps the connection open for reading (later writes answer
     * -EPIPE); shutting the read side makes reads answer end-of-stream once the buffered bytes are gone. Returns
     * 0, or -EBADF / -EINVAL. A socket that is not connected answers 0: shutdown(2) would set ENOTCONN, and
     * Net.c ignores exactly that errno. A connection the peer has closed already sent our FIN on its FIN.
     */
    public static int shutdown(int h, int how)
    {
        if (h < 0 || h >= MAXCONN || st[h] == FREE)
        {
            return -EBADF;
        }
        if (how < 0 || how > 2)
        {
            return -EINVAL;
        }
        lock();
        if (how != 1)
        {
            rdShut[h] = 1;
        }
        if (how != 0 && st[h] == CONNECTED && finOut[h] == 0)
        {
            sendSeg(h, 0x11, 0L, 0);                     // FIN|ACK
            snd[h] = snd[h] + 1;
            finOut[h] = 1;
        }
        unlock();
        return 0;
    }

    /** Bytes immediately available without blocking. */
    public static int available(int h)
    {
        return (h < 0 || h >= MAXCONN || st[h] == FREE) ? 0 : readable(h);
    }

    /** Connections in the established state (NetStats CurrEstab). */
    public static int currEstab()
    {
        int n = 0;
        int i = 0;
        while (i < MAXCONN)
        {
            if (st[i] == CONNECTED)
            {
                n = n + 1;
            }
            i = i + 1;
        }
        return n;
    }

    // ----- receive (the RX task, or the waiting application before it runs) -----

    /**
     * Process one received TCP segment: the IPv4 header is at {@code ip}, {@code ipTotal} bytes long, and the
     * caller has already checked it and counted it. Called on the RX task for every TCP frame
     * ({@link RxQueues#dispatch}); the frame's buffer is reused when this returns, so everything kept is
     * copied. Checks the TCP checksum, finds the connection, applies the segment and wakes a waiting reader.
     */
    public static void input(long ip, int ipTotal)
    {
        int ihl = (Magic.load8(ip) & 0x0F) * 4;
        long tcp = ip + ihl;
        int tlen = ipTotal - ihl;
        if (tlen < 20)
        {
            NetStats.tcpInErrs = NetStats.tcpInErrs + 1;
            return;
        }
        int dataOff = ((Magic.load8(tcp + 12) >> 4) & 0x0F) * 4;
        if (dataOff < 20 || dataOff > tlen)
        {
            NetStats.tcpInErrs = NetStats.tcpInErrs + 1;
            return;
        }
        if (NetStats.l4Cksum(6, tcp, tlen, ip + 12, ip + 16) != 0)
        {
            NetStats.tcpInErrs = NetStats.tcpInErrs + 1;
            NetStats.tcpInCsumErrors = NetStats.tcpInCsumErrors + 1;
            return;
        }
        int sport = ((Magic.load8(tcp) & 0xFF) << 8) | (Magic.load8(tcp + 1) & 0xFF);
        int dport = ((Magic.load8(tcp + 2) & 0xFF) << 8) | (Magic.load8(tcp + 3) & 0xFF);
        long now = Magic.readCNTPCT_EL0();
        lock();
        int h = find(ip + 12, sport, dport);
        if (h < 0)
        {
            NetStats.tcpNoConn = NetStats.tcpNoConn + 1;
            unlock();
            return;
        }
        int flags = Magic.load8(tcp + 13) & 0x3F;
        int payLen = tlen - dataOff;
        segs[h] = segs[h] + 1;
        lastFl[h] = flags;
        int k = trace(h, false, tcp, payLen, now);       // before applying it: its ACK must trace after it
        if (st[h] == SYN_SENT)
        {
            synSent(h, tcp, flags, dataOff, now);
        }
        else
        {
            established(h, tcp, flags, payLen, dataOff);
        }
        if (k >= 0)
        {
            trRcv[k] = rcv[h];                           // our rcv once the segment was applied
        }
        if (waiting[h] != 0 && lock >= 0)
        {
            waiting[h] = 0;
            VMScheduler.kernelPost(connSem[h]);
        }
        unlock();
    }

    /** The connection a segment from {@code srcIp}:{@code sport} to our {@code dport} belongs to, or -1. */
    private static int find(long srcIp, int sport, int dport)
    {
        int i = 0;
        while (i < MAXCONN)
        {
            int s = st[i];
            if ((s == SYN_SENT || s == CONNECTED || s == CLOSED) && lport[i] == dport && rport[i] == sport
                    && Ip.eq(srcIp, rip[i]))
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /** A segment for a connection whose SYN is out: the SYN-ACK completes it, a RST refuses it. */
    private static void synSent(int h, long tcp, int flags, int dataOff, long now)
    {
        boolean ackOk = (flags & 0x10) != 0 && Ip.readBe32(tcp + 8) == isn0[h] + 1;
        if ((flags & 0x04) != 0)                         // RST: refused, if it acknowledges our SYN (RFC 9293)
        {
            if (ackOk)
            {
                refused[h] = 1;
            }
            return;
        }
        if ((flags & 0x10) != 0 && !ackOk)               // an ACK of something we never sent: the peer holds an
        {                                                //   old connection on this 4-tuple. RST it (seq = their
            int keep = snd[h];                           //   ack, RFC 9293 3.10.7.3); connect keeps sending SYNs
            snd[h] = Ip.readBe32(tcp + 8);
            sendSeg(h, 0x04, 0L, 0);
            snd[h] = keep;
            return;
        }
        if ((flags & 0x12) == 0x12)                      // SYN|ACK of our SYN
        {
            int seq = Ip.readBe32(tcp + 4);
            pisn[h] = seq;
            rcv[h] = seq + 1;                            // their ISN + 1
            rdSeq[h] = seq + 1;
            snd[h] = isn0[h] + 1;
            una[h] = snd[h];
            peerMss[h] = mssOption(tcp, dataOff);
            st[h] = CONNECTED;
            tConn[h] = now;
            sendSeg(h, 0x10, 0L, 0);                     // ACK: the handshake's third segment (Pi run 9)
        }
    }

    /** The MSS option in a SYN-ACK's options ({@code tcp+20 .. tcp+dataOff}), or 536 (RFC 9293) without one. */
    private static int mssOption(long tcp, int dataOff)
    {
        int p = 20;
        while (p < dataOff)
        {
            int kind = Magic.load8(tcp + p) & 0xFF;
            if (kind == 0)
            {
                break;                                   // end of options
            }
            if (kind == 1)
            {
                p = p + 1;                               // no-op
                continue;
            }
            if (p + 1 >= dataOff)
            {
                break;
            }
            int olen = Magic.load8(tcp + p + 1) & 0xFF;
            if (olen < 2)
            {
                break;                                   // malformed: stop
            }
            if (kind == 2 && olen == 4 && p + 4 <= dataOff)
            {
                return ((Magic.load8(tcp + p + 2) & 0xFF) << 8) | (Magic.load8(tcp + p + 3) & 0xFF);
            }
            p = p + olen;
        }
        return 536;
    }

    /** A segment for an established (or peer-closed) connection. */
    private static void established(int h, long tcp, int flags, int payLen, int dataOff)
    {
        int segSeq = Ip.readBe32(tcp + 4);
        if ((flags & 0x12) == 0x12)
        {
            if (segSeq == pisn[h])                       // a retransmitted SYN-ACK: our handshake ACK was lost --
            {                                            //   send it again (RFC 9293 3.10.7.4)
                NetStats.tcpSynAckRetrans = NetStats.tcpSynAckRetrans + 1;
                sendSeg(h, 0x10, 0L, 0);
            }
            return;
        }
        if ((flags & 0x04) != 0)                         // RST
        {
            if (segSeq == rcv[h])                        // exactly the next byte: accept (RFC 5961 3.2)
            {
                if (st[h] == CONNECTED)
                {
                    NetStats.tcpEstabResets = NetStats.tcpEstabResets + 1;
                }
                st[h] = CLOSED;
                rst[h] = 1;
            }
            else if (st[h] == CONNECTED && segSeq - rcv[h] > 0 && segSeq - rcv[h] < window(h))
            {
                NetStats.tcpChallengeAcks = NetStats.tcpChallengeAcks + 1;
                sendSeg(h, 0x10, 0L, 0);                 // in the window but not exact: a challenge ACK
            }
            else
            {
                NetStats.tcpRstIgnored = NetStats.tcpRstIgnored + 1;
            }
            return;
        }
        if ((flags & 0x10) != 0)
        {
            int ack = Ip.readBe32(tcp + 8);
            if (ack - una[h] > 0 && ack - snd[h] <= 0)
            {
                una[h] = ack;
            }
        }
        boolean fin = (flags & 0x01) != 0;
        if (payLen == 0 && !fin)
        {
            return;                                      // a pure ACK (or window update)
        }
        if (st[h] == CLOSED)                             // the peer resends after we closed: ACK what we have
        {
            NetStats.tcpDupAcksOut = NetStats.tcpDupAcksOut + 1;
            sendSeg(h, 0x10, 0L, 0);
            return;
        }
        receive(h, tcp + dataOff, segSeq, payLen, fin);
    }

    /**
     * Take the payload ({@code len} bytes at {@code data}, sequence {@code seq}) and FIN of one segment into
     * connection {@code h}'s ring, advance {@code rcv} over everything now contiguous, and ACK.
     */
    private static void receive(int h, long data, int seq, int len, boolean fin)
    {
        int finSeq = seq + len;                          // where a FIN on this segment sits
        boolean dup = false;
        int off = seq - rcv[h];
        if (len > 0 && off < 0)
        {
            if (off + len <= 0)                          // nothing new in it
            {
                NetStats.tcpDupData = NetStats.tcpDupData + 1;
                len = 0;
                dup = true;
            }
            else
            {
                NetStats.tcpPartialDup = NetStats.tcpPartialDup + 1;
                data = data - off;                       // trim the front we already have
                len = len + off;
                seq = rcv[h];
                off = 0;
            }
        }
        if (len > 0)
        {
            int room = rdSeq[h] + RCVBUF - seq;          // bytes the ring can still take from seq on
            if (room < 0)
            {
                room = 0;
            }
            if (len > room)
            {
                NetStats.tcpWndDrop = NetStats.tcpWndDrop + (len - room);
                len = room;
                fin = false;                             // its FIN lies beyond what we kept
            }
        }
        if (len > 0)
        {
            copyIn(h, seq, data, len);
            if (off == 0)
            {
                rcv[h] = rcv[h] + len;
                inord[h] = inord[h] + 1;
                rbytes[h] = rbytes[h] + len;
                mergeHoles(h);
            }
            else
            {
                ooo[h] = ooo[h] + 1;
                holdRun(h, seq, seq + len);
            }
        }
        if (fin && finIn[h] == 0)
        {
            finPend[h] = 1;
            finAt[h] = finSeq;
        }
        if (finPend[h] != 0 && finAt[h] == rcv[h])       // every byte before the FIN is here: take it
        {
            finPend[h] = 0;
            finIn[h] = 1;
            rcv[h] = rcv[h] + 1;
            if (finOut[h] != 0)
            {
                sendSeg(h, 0x10, 0L, 0);                 // ours went out at shutdown(SHUT_WR): ACK its FIN only
            }
            else
            {
                sendSeg(h, 0x11, 0L, 0);                 // ACK its FIN and send ours (FIN|ACK)
                snd[h] = snd[h] + 1;
            }
            st[h] = CLOSED;
            return;
        }
        if (dup || off != 0)
        {
            NetStats.tcpDupAcksOut = NetStats.tcpDupAcksOut + 1;   // a duplicate ACK (RFC 5681 4.2): it tells
        }                                                          //   the sender exactly which byte is missing
        sendSeg(h, 0x10, 0L, 0);
    }

    /** Copy {@code n} bytes from {@code src} into connection {@code h}'s ring at sequence {@code seq}. */
    private static void copyIn(int h, int seq, long src, int n)
    {
        long r = ring[h];
        int idx = seq - (pisn[h] + 1);
        int i = 0;
        while (i < n)
        {
            Magic.store8(r + ((idx + i) & RCVMASK), Magic.load8(src + i));
            i = i + 1;
        }
    }

    /** Copy {@code n} buffered bytes to {@code dst}, advance the read point, and reopen a closed-down window. */
    private static int copyOut(int h, long dst, int n)
    {
        long r = ring[h];
        int idx = rdSeq[h] - (pisn[h] + 1);
        int i = 0;
        while (i < n)
        {
            Magic.store8(dst + i, Magic.load8(r + ((idx + i) & RCVMASK)));
            i = i + 1;
        }
        rdSeq[h] = rdSeq[h] + n;
        if (st[h] == CONNECTED && lastWin[h] < RCVBUF / 4 && window(h) >= RCVBUF / 2)
        {
            NetStats.tcpWinUpdates = NetStats.tcpWinUpdates + 1;
            sendSeg(h, 0x10, 0L, 0);                     // tell the peer the room is back (no SWS: big steps only)
        }
        return n;
    }

    /** Bytes received in order and not yet read. */
    private static int readable(int h)
    {
        int s = st[h];
        if (s != CONNECTED && s != CLOSED)
        {
            return 0;
        }
        return rcv[h] - finIn[h] - rdSeq[h];
    }

    /** The receive window: the ring's free space past {@code rcv}. */
    private static int window(int h)
    {
        int s = st[h];
        if (s != CONNECTED && s != CLOSED)
        {
            return RCVBUF;
        }
        int w = RCVBUF - (rcv[h] - finIn[h] - rdSeq[h]);
        return w < 0 ? 0 : w;
    }

    /** Remember the out-of-order run [s, e) held in the ring, merging it with any run it touches. */
    private static void holdRun(int h, int s, int e)
    {
        int base = h * MAXHOLE;
        int i = 0;
        while (i < holes[h])
        {
            int hs = holeS[base + i];
            int he = holeE[base + i];
            if (hs - e <= 0 && s - he <= 0)              // overlapping or adjacent: absorb it
            {
                if (hs - s < 0)
                {
                    s = hs;
                }
                if (he - e > 0)
                {
                    e = he;
                }
                removeHole(h, i);
                continue;
            }
            i = i + 1;
        }
        if (holes[h] == MAXHOLE)
        {
            NetStats.tcpOfoDrop = NetStats.tcpOfoDrop + 1;   // no room to remember it: the bytes stay in the
            return;                                          //   ring but rcv will not jump them; resent later
        }
        holeS[base + holes[h]] = s;
        holeE[base + holes[h]] = e;
        holes[h] = holes[h] + 1;
        NetStats.tcpOfoQueue = NetStats.tcpOfoQueue + 1;
    }

    /** {@code rcv} just advanced: absorb every held run that now starts at or before it. */
    private static void mergeHoles(int h)
    {
        int base = h * MAXHOLE;
        boolean moved = true;
        while (moved)
        {
            moved = false;
            int i = 0;
            while (i < holes[h])
            {
                if (holeS[base + i] - rcv[h] <= 0)
                {
                    int e = holeE[base + i];
                    if (e - rcv[h] > 0)
                    {
                        rbytes[h] = rbytes[h] + (e - rcv[h]);
                        rcv[h] = e;
                    }
                    removeHole(h, i);
                    NetStats.tcpOfoMerge = NetStats.tcpOfoMerge + 1;
                    moved = true;
                    break;
                }
                i = i + 1;
            }
        }
    }

    private static void removeHole(int h, int i)
    {
        int base = h * MAXHOLE;
        int last = holes[h] - 1;
        holeS[base + i] = holeS[base + last];
        holeE[base + i] = holeE[base + last];
        holes[h] = last;
    }

    /**
     * Wait for a connection event for up to {@code ms}. With the RX task running, that is a post of this
     * connection's semaphore by {@link #input} (the caller set {@code waiting} under the lock first, so a post
     * between its unlock and this wait is kept as a token, not lost). Before the RX task, the waiter reads the
     * chip itself and feeds what arrives to {@link #input}.
     */
    private static void waitEvent(int h, int ms)
    {
        if (RxQueues.active)
        {
            VMScheduler.semWaitTimeout(connSem[h], ms);
            return;
        }
        if (syncRx == 0L)
        {
            syncRx = Heap.allocData(2048);
        }
        long ip = Ip.recvIp(syncRx, 2048, ms);
        if (ip != 0L && (Magic.load8(ip + 9) & 0xFF) == 6)
        {
            input(ip, Ip.lastIpTotal);
        }
    }

    // ----- transmit -----

    /**
     * Build an Ethernet/IP/TCP segment for connection {@code h} and send it. A SYN carries our MSS option; every
     * segment advertises the ring's free space as its window. Called under the table lock.
     */
    private static void sendSeg(int h, int flags, long payload, int payLen)
    {
        long buf = Heap.allocData(2048);
        int i = 0;
        while (i < 6)
        {
            Magic.store8(buf + i, Magic.load8(Ip.gwMac + i));
            Magic.store8(buf + 6 + i, Magic.load8(Ip.ourMac + i));
            i = i + 1;
        }
        Magic.store8(buf + 12, 0x08);
        Magic.store8(buf + 13, 0x00);
        long ip = buf + 14;
        long tcp = buf + 34;
        int hdr = (flags & 0x02) != 0 ? 24 : 20;         // a SYN carries the 4-byte MSS option
        int win = window(h);
        Ip.be16(tcp + 0, lport[h]);
        Ip.be16(tcp + 2, rport[h]);
        Ip.be32(tcp + 4, snd[h]);
        Ip.be32(tcp + 8, (flags & 0x10) != 0 ? rcv[h] : 0);
        Magic.store8(tcp + 12, (hdr / 4) << 4);          // data offset
        Magic.store8(tcp + 13, flags);
        Ip.be16(tcp + 14, win);
        Ip.be16(tcp + 16, 0);                            // checksum (filled below)
        Ip.be16(tcp + 18, 0);                            // urgent pointer
        if (hdr == 24)
        {
            Magic.store8(tcp + 20, 2);                   // kind = MSS
            Magic.store8(tcp + 21, 4);
            Ip.be16(tcp + 22, OUR_MSS);
        }
        i = 0;
        while (i < payLen)
        {
            Magic.store8(tcp + hdr + i, Magic.load8(payload + i));
            i = i + 1;
        }
        int tcpLen = hdr + payLen;
        Ip.be16(tcp + 16, NetStats.l4Cksum(6, tcp, tcpLen, Ip.ourIp, rip[h]));
        Magic.store8(ip + 0, 0x45);
        Magic.store8(ip + 1, 0);
        Ip.be16(ip + 2, 20 + tcpLen);
        Ip.be16(ip + 4, 0);
        Ip.be16(ip + 6, 0x4000);                         // don't fragment
        Magic.store8(ip + 8, 64);
        Magic.store8(ip + 9, 6);                         // protocol = TCP
        Ip.be16(ip + 10, 0);
        Ip.copy4(Ip.ourIp, ip + 12);
        Ip.copy4(rip[h], ip + 16);
        Ip.be16(ip + 10, Ip.cksum(ip, 20));
        lastWin[h] = win;
        trace(h, true, tcp, payLen, Magic.readCNTPCT_EL0());
        Ip.send(buf, 14 + 20 + tcpLen);
    }

    // ----- measurement -----

    /** Milliseconds between two CNTPCT readings. */
    private static int msBetween(long from, long to)
    {
        return (int) ((to - from) / (Magic.readCNTFRQ_EL0() / 1000L));
    }

    /**
     * MEASUREMENT: record one segment of connection {@code h} (see {@link #TRACE_SEGS}) under the lock; the
     * record's index, or -1 once the trace is full.
     */
    private static int trace(int h, boolean tx, long tcp, int payLen, long when)
    {
        if (trc[h] >= TRACE_SEGS)
        {
            return -1;
        }
        int k = h * TRACE_SEGS + trc[h];
        trTx[k] = tx ? 1 : 0;
        trFl[k] = Magic.load8(tcp + 13) & 0x3F;
        trSeq[k] = Ip.readBe32(tcp + 4);
        trAck[k] = Ip.readBe32(tcp + 8);
        trLen[k] = payLen;
        trWin[k] = ((Magic.load8(tcp + 14) & 0xFF) << 8) | (Magic.load8(tcp + 15) & 0xFF);
        trRcv[k] = rcv[h];
        trT[k] = when;
        trc[h] = trc[h] + 1;
        return k;
    }

    /**
     * MEASUREMENT: print connection {@code h}'s end state, its recorded segments and the stack's counters, once
     * per connection. Sequence numbers are relative to the ISN that owns them (ours for our seq and their ack,
     * the peer's for theirs; raw before the SYN-ACK told us the peer's); t is ms since connect began, and for a
     * received segment it is when the RX task read it off the chip. Never called under the lock.
     */
    private static void dump(int h, byte[] why)
    {
        if (dumped[h] != 0)
        {
            return;
        }
        dumped[h] = 1;
        board.bcm2711.Uart.write(Magic.bytes("tcp: h="));
        VM.printDec(h);
        board.bcm2711.Uart.write(Magic.bytes(" end ("));
        board.bcm2711.Uart.write(why);
        board.bcm2711.Uart.write(Magic.bytes(") st="));
        VM.printDec(st[h]);
        board.bcm2711.Uart.write(Magic.bytes(" wrote="));
        VM.printDec(wbytes[h]);
        board.bcm2711.Uart.write(Magic.bytes(" unacked="));
        VM.printDec(snd[h] - una[h]);
        board.bcm2711.Uart.write(Magic.bytes(" read="));
        VM.printDec(rbytes[h]);
        board.bcm2711.Uart.write(Magic.bytes(" unread="));
        VM.printDec(readable(h));
        board.bcm2711.Uart.write(Magic.bytes(" segs="));
        VM.printDec(segs[h]);
        board.bcm2711.Uart.write(Magic.bytes(" inorder="));
        VM.printDec(inord[h]);
        board.bcm2711.Uart.write(Magic.bytes(" ooo="));
        VM.printDec(ooo[h]);
        board.bcm2711.Uart.write(Magic.bytes(" holes="));
        VM.printDec(holes[h]);
        board.bcm2711.Uart.write(Magic.bytes(" peerMss="));
        VM.printDec(peerMss[h]);
        board.bcm2711.Uart.write(Magic.bytes(" connToWrite="));
        VM.printDec(tWr[h] == 0L ? -1 : msBetween(tConn[h], tWr[h]));
        board.bcm2711.Uart.write(Magic.bytes("ms sinceWrite="));
        VM.printDec(tWr[h] == 0L ? -1 : msBetween(tWr[h], Magic.readCNTPCT_EL0()));
        board.bcm2711.Uart.write(Magic.bytes("ms traced="));
        VM.printDec(trc[h]);
        board.bcm2711.Uart.write(Magic.bytes("\n"));
        int n = trc[h];
        int i = 0;
        while (i < n)
        {
            int k = h * TRACE_SEGS + i;
            boolean tx = trTx[k] != 0;
            board.bcm2711.Uart.write(Magic.bytes("tcp: h="));
            VM.printDec(h);
            if (tx)
            {
                board.bcm2711.Uart.write(Magic.bytes(" tx"));
            }
            else
            {
                board.bcm2711.Uart.write(Magic.bytes(" rx"));
            }
            board.bcm2711.Uart.write(Magic.bytes(" flags="));
            VM.printDec(trFl[k]);
            board.bcm2711.Uart.write(Magic.bytes(" seq="));
            if (tx)
            {
                VM.printDec(trSeq[k] - isn0[h]);
            }
            else
            {
                VM.printDec(pisn[h] == 0 ? trSeq[k] : trSeq[k] - pisn[h]);
            }
            board.bcm2711.Uart.write(Magic.bytes(" ack="));
            if (tx)
            {
                VM.printDec(pisn[h] == 0 ? trAck[k] : trAck[k] - pisn[h]);
            }
            else
            {
                VM.printDec(trAck[k] - isn0[h]);
            }
            board.bcm2711.Uart.write(Magic.bytes(" len="));
            VM.printDec(trLen[k]);
            board.bcm2711.Uart.write(Magic.bytes(" win="));
            VM.printDec(trWin[k]);
            if (!tx)
            {
                board.bcm2711.Uart.write(Magic.bytes(" rcv="));
                VM.printDec(pisn[h] == 0 ? trRcv[k] : trRcv[k] - pisn[h]);
            }
            board.bcm2711.Uart.write(Magic.bytes(" t="));
            VM.printDec(msBetween(tStart[h], trT[k]));
            board.bcm2711.Uart.write(Magic.bytes("ms\n"));
            i = i + 1;
        }
        NetStats.print();
        board.cyw43.WlanPoll.printBeat(tStart[h]);      // MEASUREMENT: did the RX task stall?
        Ip.printArp(tStart[h]);                          // MEASUREMENT: did the gateway lose our MAC?
    }

    /** A poll ran out of time: the connection's trace and the RX path's counters. */
    private static void report(int h)
    {
        board.bcm2711.Uart.write(Magic.bytes("tcp: poll timed out: h="));
        VM.printDec(h);
        board.bcm2711.Uart.write(Magic.bytes(" | rx: frames="));
        VM.printDec(RxQueues.framesIn);
        board.bcm2711.Uart.write(Magic.bytes(" toQueue="));
        VM.printDec(RxQueues.toQueue);
        board.bcm2711.Uart.write(Magic.bytes(" noQueue="));
        VM.printDec(RxQueues.dropNoQueue);
        board.bcm2711.Uart.write(Magic.bytes(" full="));
        VM.printDec(RxQueues.dropFull);
        board.bcm2711.Uart.write(Magic.bytes(" pool="));
        VM.printDec(RxQueues.dropPool);
        board.bcm2711.Uart.write(Magic.bytes(" arp="));
        VM.printDec(RxQueues.arpAnswered);
        board.bcm2711.Uart.write(Magic.bytes(" polls="));
        VM.printDec(board.cyw43.WlanPoll.polls);
        board.bcm2711.Uart.write(Magic.bytes(" irq="));
        VM.printDec(board.cyw43.WlanPoll.irqPolls);
        board.bcm2711.Uart.write(Magic.bytes(" chip="));
        VM.printDec(board.cyw43.WlanPoll.frames);
        board.bcm2711.Uart.write(Magic.bytes(" credit="));
        VM.printDec(board.cyw43.Cyw43.creditWaits());
        board.bcm2711.Uart.write(Magic.bytes("/"));
        VM.printDec(board.cyw43.Cyw43.creditForced());
        board.bcm2711.Uart.write(Magic.bytes(" fromRx="));
        VM.printDec(board.cyw43.Cyw43.creditRx());
        board.bcm2711.Uart.write(Magic.bytes("\n"));
        dump(h, Magic.bytes("poll timed out"));
        board.cyw43.Cyw43.stormReport(board.cyw43.WlanPoll.polls, board.cyw43.WlanPoll.irqPolls,
                board.cyw43.WlanPoll.frames);           // MEASUREMENT (storm)
    }
}
