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

/**
 * A blocking TCP client, factored out of the CYW43 WiFi HTTP-GET demo into a reusable connection table so the
 * VM's socket layer (stock {@code java.net} in M3) can drive it. Each connection is a slot in parallel
 * arrays; {@link #connect}/{@link #write}/{@link #read}/{@link #close} block via the driver's IRQ-driven RX
 * ({@link Ip#recvIp}) and post TCP segments via {@link Ip#send}. The segment bytes and the SYN/ACK/FIN logic
 * are the exact code the single-connection demo used — only the state (seq/ack/ports) moved from local
 * variables into per-slot arrays.
 *
 * <p>Scope: IPv4, one in-flight segment, everything routed to the gateway MAC — enough for a client GET.
 * No retransmission of data (the network is a local WiFi link); connect resends the SYN until answered.
 */
public final class Tcp
{
    private Tcp() {}

    private static final int MAXCONN = 4;
    private static final int FREE = 0;
    private static final int CONNECTED = 1;
    private static final int CLOSED = 2;
    private static final int RESERVED = 3;   // alloc()'d (a socket fd exists) but not yet connected

    private static final int RX_WAIT_MS = 200;

    /**
     * Why a {@link #connect} failed, as the NEGATED Linux errno the C library would have set -- the socket layer
     * (the sun.nio.ch.Net overlay) throws for each exactly what unix/native/libnio/ch/Net.c handleSocketError
     * throws for that errno.
     */
    public static final int EBADF = 9;
    public static final int EINVAL = 22;
    public static final int ENETUNREACH = 101;
    public static final int ETIMEDOUT = 110;
    public static final int ECONNREFUSED = 111;

    private static int[]  st    = new int[MAXCONN];   // slot state
    private static long[] rip   = new long[MAXCONN];  // remote IPv4 (4-byte heap buf)
    private static int[]  rport = new int[MAXCONN];   // remote port
    private static int[]  lport = new int[MAXCONN];   // local (ephemeral) port
    private static int[]  snd   = new int[MAXCONN];   // our next send sequence number
    private static int[]  rcv   = new int[MAXCONN];   // our ack = next expected sequence number
    private static long[] rxb   = new long[MAXCONN];  // per-conn RX scratch (also backs pending-read data)
    private static long[] ppay  = new long[MAXCONN];  // pending unread payload address (into rxb)
    private static int[]  plen  = new int[MAXCONN];   // pending unread byte count
    private static int[]  nb    = new int[MAXCONN];   // 1 = non-blocking (IOUtil.configureBlocking(fd, false))

    /** {@code sun.nio.ch.IOStatus.UNAVAILABLE}: a non-blocking read found nothing to return yet. */
    public static final int UNAVAILABLE = -2;

    /** The Linux {@code <poll.h>} bits, as {@code Net.pollinValue()} and its siblings answer them here. */
    public static final int POLLIN = 0x1;
    public static final int POLLOUT = 0x4;
    public static final int POLLHUP = 0x10;
    public static final int POLLNVAL = 0x20;

    /**
     * Allocate a socket slot (like {@code Net.socket()}): reserve a connection handle without connecting.
     * Returns the handle (0..MAXCONN-1) or -1 if none free. The socket layer's {@code fd} int is this handle.
     */
    public static int alloc()
    {
        int i = 0;
        while (i < MAXCONN)
        {
            if (st[i] == FREE)
            {
                if (rxb[i] == 0L)
                {
                    rxb[i] = Heap.allocData(2048);
                    rip[i] = Heap.allocData(4);
                }
                st[i] = RESERVED;
                plen[i] = 0;
                nb[i] = 0;                               // a new socket blocks, as on a JVM
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /**
     * Connect an alloc()'d handle to {@code ipBe}:{@code port} (blocking SYN/SYN-ACK; ipBe = big-endian IPv4
     * int). Returns 1 on success, else a negated errno: -ECONNREFUSED (a RST of our SYN, or a loopback address,
     * where nothing on this VM listens), -ETIMEDOUT, -ENETUNREACH (no WiFi lease), -EBADF (not a socket).
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
        Magic.store8(rip[h] + 0, (ipBe >> 24) & 0xFF);
        Magic.store8(rip[h] + 1, (ipBe >> 16) & 0xFF);
        Magic.store8(rip[h] + 2, (ipBe >> 8) & 0xFF);
        Magic.store8(rip[h] + 3, ipBe & 0xFF);
        rport[h] = port;
        lport[h] = Ip.ephemeralPort();                  // a fresh ephemeral port per connection
        int isn = (int) Ip.nextRandom();                // and a fresh initial sequence number
        snd[h] = isn;
        rcv[h] = 0;
        plen[h] = 0;

        int syns = 0;
        int fromPeer = 0;
        int lastFlags = -1;
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                snd[h] = isn;
                sendSeg(h, 0x02, 0L, 0);                 // SYN
                syns += 1;
                nextSend = now + freq / 2L;
            }
            long tcp = recvSeg(h, RX_WAIT_MS);
            if (tcp == 0L)
            {
                continue;
            }
            int flags = Magic.load8(tcp + 13) & 0x3F;
            fromPeer += 1;
            lastFlags = flags;
            boolean ackOk = (flags & 0x10) != 0 && Ip.readBe32(tcp + 8) == isn + 1;
            if ((flags & 0x04) != 0)                     // RST: refused, if it acknowledges our SYN (RFC 9293)
            {
                if (ackOk)
                {
                    return -ECONNREFUSED;
                }
                continue;
            }
            if ((flags & 0x10) != 0 && !ackOk)           // an ACK of something we never sent: the peer
            {                                            //   holds an old connection on this 4-tuple. RST
                snd[h] = Ip.readBe32(tcp + 8);           //   it (seq = their ack, RFC 9293 3.10.7.3) and
                sendSeg(h, 0x04, 0L, 0);                 //   keep sending our SYN
                continue;
            }
            if ((flags & 0x12) == 0x12)                  // SYN|ACK of our SYN
            {
                rcv[h] = Ip.readBe32(tcp + 4) + 1;       // their ISN + 1
                snd[h] = isn + 1;
                st[h] = CONNECTED;
                return 1;
            }
        }
        board.bcm2711.Uart.write(Magic.bytes("tcp: connect timed out: syns="));
        VM.printDec(syns);
        board.bcm2711.Uart.write(Magic.bytes(" fromPeer="));
        VM.printDec(fromPeer);
        board.bcm2711.Uart.write(Magic.bytes(" lastFlags="));
        VM.printDec(lastFlags);
        board.bcm2711.Uart.write(Magic.bytes(" lport="));
        VM.printDec(lport[h]);
        board.bcm2711.Uart.write(Magic.bytes("\n"));
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

    /** Send {@code len} bytes from {@code buf+off} as one PSH|ACK segment; returns {@code len}. */
    public static int write(int h, long buf, int off, int len)
    {
        if (h < 0 || st[h] != CONNECTED)
        {
            return -1;
        }
        sendSeg(h, 0x18, buf + off, len);               // PSH|ACK
        snd[h] = snd[h] + len;
        return len;
    }

    /**
     * Copy up to {@code len} received bytes into {@code buf+off}, ACKing what arrives, and return the count; -1 at
     * end-of-stream (the peer's FIN or RST) or on a socket that is not connected. Payload beyond {@code len} is
     * held for the next read. A BLOCKING socket waits until there is data or end-of-stream, with no time limit,
     * as {@code read(2)} does -- {@code setSoTimeout} is how a caller bounds it, through {@link #poll}. A
     * NON-blocking socket answers {@link #UNAVAILABLE} when nothing has arrived.
     *
     * <p>This used to give up after a fixed 8 s and return 0, which no stream read may return for a non-empty
     * buffer: NetDemo's {@code while (n > 0)} loop took it as the end and reported {@code bytes=0}.
     */
    public static int read(int h, long buf, int off, int len)
    {
        if (h < 0 || h >= MAXCONN || (st[h] != CONNECTED && st[h] != CLOSED))
        {
            return -1;
        }
        while (true)
        {
            if (plen[h] > 0)                             // serve buffered bytes first (no new frame yet)
            {
                return drainPending(h, buf, off, len);
            }
            if (st[h] == CLOSED)
            {
                return -1;                               // FIN or RST already seen and drained
            }
            boolean got = pump(h, nb[h] != 0 ? 0 : RX_WAIT_MS);
            if (!got && nb[h] != 0)
            {
                return UNAVAILABLE;
            }
        }
    }

    /**
     * {@code Net.poll}: wait until connection {@code h} is ready for {@code events} ({@link #POLLIN} /
     * {@link #POLLOUT}), or {@code timeoutMs} elapses (negative: no limit, 0: just look). Returns the ready
     * events, 0 on timeout, {@link #POLLNVAL} for a handle that is not a socket, and {@link #POLLHUP} for one
     * that will never become ready (not connected, or closed with only {@code POLLOUT} asked for). Readable
     * means data is pending or the peer has closed (a read then answers -1); writable means connected, since
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
            int ready = 0;
            if ((events & POLLIN) != 0 && (plen[h] > 0 || st[h] == CLOSED))
            {
                ready = ready | POLLIN;
            }
            if ((events & POLLOUT) != 0 && st[h] == CONNECTED)
            {
                ready = ready | POLLOUT;
            }
            if (ready != 0)
            {
                return ready;
            }
            if (st[h] != CONNECTED || plen[h] > 0)
            {
                return POLLHUP;                          // nothing this socket can still deliver
            }
            int waitMs = RX_WAIT_MS;
            if (timeoutMs >= 0L)
            {
                long left = (endT - Magic.readCNTPCT_EL0()) * 1000L / freq;
                if (left <= 0L)
                {
                    return 0;                            // timed out
                }
                if (left < waitMs)
                {
                    waitMs = (int) left;
                }
            }
            pump(h, waitMs);
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
        if (h < 0 || st[h] == FREE)
        {
            return;
        }
        if (st[h] == CONNECTED)
        {
            sendSeg(h, 0x11, 0L, 0);                     // FIN|ACK
        }
        st[h] = FREE;
        plen[h] = 0;
    }

    /** Bytes immediately available without blocking (the pending-read count). */
    public static int available(int h)
    {
        return (h < 0 || st[h] == FREE) ? 0 : plen[h];
    }

    // ----- internals -----

    /**
     * Receive at most one segment for connection {@code h}, waiting up to {@code ms}, and apply it: in-order data
     * becomes the pending read (and is ACKed), a FIN closes the receive side, and a RST whose sequence number is
     * the one we expect ends the connection. Returns true if a segment for {@code h} arrived. Called only with
     * nothing pending, because the next frame is received into {@code rxb[h]}, where the pending payload lives.
     *
     * <p>A RST reads as end-of-stream (-1). Stock throws {@code SocketException("Connection reset")} for it;
     * before this, a RST was ignored and the read waited out its time limit.
     */
    private static boolean pump(int h, int ms)
    {
        long tcp = recvSeg(h, ms);
        if (tcp == 0L)
        {
            return false;
        }
        int flags = Magic.load8(tcp + 13) & 0x3F;
        int segSeq = Ip.readBe32(tcp + 4);
        int dataOff = ((Magic.load8(tcp + 12) >> 4) & 0x0F) * 4;
        int payLen = Ip.lastIpTotal - Ip.lastIhl - dataOff;
        if ((flags & 0x04) != 0)                         // RST
        {
            if (segSeq == rcv[h])
            {
                st[h] = CLOSED;
            }
            return true;
        }
        if (payLen > 0 && segSeq == rcv[h])              // in-order data
        {
            rcv[h] = rcv[h] + payLen;
            sendSeg(h, 0x10, 0L, 0);                     // ACK
            ppay[h] = tcp + dataOff;                     // payload lives in rxb[h] until the next recvSeg
            plen[h] = payLen;
            if ((flags & 0x01) != 0)                     // FIN rode along with the data
            {
                finish(h);
            }
            return true;
        }
        if ((flags & 0x01) != 0)                         // FIN, no (new) data
        {
            finish(h);
        }
        return true;
    }

    /** Copy up to {@code len} pending bytes into {@code buf+off}; advance the pending window. */
    private static int drainPending(int h, long buf, int off, int len)
    {
        int n = plen[h] < len ? plen[h] : len;
        int i = 0;
        while (i < n)
        {
            Magic.store8(buf + off + i, Magic.load8(ppay[h] + i));
            i = i + 1;
        }
        ppay[h] = ppay[h] + n;
        plen[h] = plen[h] - n;
        return n;
    }

    /** Peer FIN: ack it, send FIN|ACK, mark closed (but keep any just-returned pending data readable). */
    private static void finish(int h)
    {
        rcv[h] = rcv[h] + 1;
        sendSeg(h, 0x11, 0L, 0);                         // FIN|ACK
        st[h] = CLOSED;
    }

    /** Build an Ethernet/IP/TCP segment for connection {@code h} and send it. */
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
        Ip.be16(tcp + 0, lport[h]);
        Ip.be16(tcp + 2, rport[h]);
        Ip.be32(tcp + 4, snd[h]);
        Ip.be32(tcp + 8, rcv[h]);
        Magic.store8(tcp + 12, 0x50);                    // data offset = 5 (20-byte header)
        Magic.store8(tcp + 13, flags);
        Ip.be16(tcp + 14, 64240);                        // window
        Ip.be16(tcp + 16, 0);                            // checksum (filled below)
        Ip.be16(tcp + 18, 0);                            // urgent pointer
        i = 0;
        while (i < payLen)
        {
            Magic.store8(tcp + 20 + i, Magic.load8(payload + i));
            i = i + 1;
        }
        int tcpLen = 20 + payLen;
        Ip.be16(tcp + 16, tcpCksum(tcp, tcpLen, Ip.ourIp, rip[h]));
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
        Ip.send(buf, 14 + 20 + tcpLen);
    }

    /** Wait up to {@code ms} for the next TCP segment belonging to connection {@code h}; its TCP header or 0. */
    private static long recvSeg(int h, int ms)
    {
        long ip = Ip.recvIp(rxb[h], 2048, ms);
        if (ip == 0L)
        {
            return 0L;
        }
        if ((Magic.load8(ip + 9) & 0xFF) != 6)          // not TCP
        {
            return 0L;
        }
        if (!Ip.eq(ip + 12, rip[h]))                    // not from our peer
        {
            return 0L;
        }
        long tcp = ip + Ip.lastIhl;
        if ((((Magic.load8(tcp + 2) & 0xFF) << 8) | (Magic.load8(tcp + 3) & 0xFF)) != lport[h])
        {
            return 0L;                                   // not our connection
        }
        return tcp;
    }

    /** TCP checksum over the IPv4 pseudo-header + segment (checksum field must be 0). */
    private static int tcpCksum(long tcp, int tcpLen, long srcIp, long dstIp)
    {
        int sum = 0;
        sum = sum + (((Magic.load8(srcIp) & 0xFF) << 8) | (Magic.load8(srcIp + 1) & 0xFF));
        sum = sum + (((Magic.load8(srcIp + 2) & 0xFF) << 8) | (Magic.load8(srcIp + 3) & 0xFF));
        sum = sum + (((Magic.load8(dstIp) & 0xFF) << 8) | (Magic.load8(dstIp + 1) & 0xFF));
        sum = sum + (((Magic.load8(dstIp + 2) & 0xFF) << 8) | (Magic.load8(dstIp + 3) & 0xFF));
        sum = sum + 6;                                   // zero byte + protocol
        sum = sum + tcpLen;
        int i = 0;
        while (i + 1 < tcpLen)
        {
            sum = sum + (((Magic.load8(tcp + i) & 0xFF) << 8) | (Magic.load8(tcp + i + 1) & 0xFF));
            i = i + 2;
        }
        if (i < tcpLen)
        {
            sum = sum + ((Magic.load8(tcp + i) & 0xFF) << 8);   // odd trailing byte
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }
}
