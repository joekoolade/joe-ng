package net;

import magic.Magic;
import vm.Heap;

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

    private static final int RX_WAIT_MS = 200;

    private static int[]  st    = new int[MAXCONN];   // slot state
    private static long[] rip   = new long[MAXCONN];  // remote IPv4 (4-byte heap buf)
    private static int[]  rport = new int[MAXCONN];   // remote port
    private static int[]  lport = new int[MAXCONN];   // local (ephemeral) port
    private static int[]  snd   = new int[MAXCONN];   // our next send sequence number
    private static int[]  rcv   = new int[MAXCONN];   // our ack = next expected sequence number
    private static long[] rxb   = new long[MAXCONN];  // per-conn RX scratch (also backs pending-read data)
    private static long[] ppay  = new long[MAXCONN];  // pending unread payload address (into rxb)
    private static int[]  plen  = new int[MAXCONN];   // pending unread byte count

    /**
     * Open a connection to {@code ip}:{@code port} (blocking SYN/SYN-ACK). Returns a connection handle
     * (0..MAXCONN-1) or -1 on no free slot / reset / timeout.
     */
    public static int connect(long ip, int port)
    {
        int h = -1;
        int i = 0;
        while (i < MAXCONN)
        {
            if (st[i] == FREE)
            {
                h = i;
                break;
            }
            i = i + 1;
        }
        if (h < 0)
        {
            return -1;
        }
        if (rxb[h] == 0L)
        {
            rxb[h] = Heap.allocData(2048);
            rip[h] = Heap.allocData(4);
        }
        Ip.copy4(ip, rip[h]);
        rport[h] = port;
        lport[h] = 0xC001 + h;                          // a distinct ephemeral port per slot
        int isn = 0x1000;
        snd[h] = isn;
        rcv[h] = 0;
        plen[h] = 0;

        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                sendSeg(h, 0x02, 0L, 0);                 // SYN
                nextSend = now + freq / 2L;
            }
            long tcp = recvSeg(h);
            if (tcp == 0L)
            {
                continue;
            }
            int flags = Magic.load8(tcp + 13) & 0x3F;
            if ((flags & 0x04) != 0)                     // RST
            {
                return -1;
            }
            if ((flags & 0x12) == 0x12)                  // SYN|ACK
            {
                rcv[h] = Ip.readBe32(tcp + 4) + 1;       // their ISN + 1
                snd[h] = isn + 1;
                st[h] = CONNECTED;
                return h;
            }
        }
        return -1;
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
     * Block until inbound data arrives, copy up to {@code len} bytes into {@code buf+off}, ACK it, and return
     * the count. Returns -1 at end-of-stream (peer FIN), or 0 on timeout with no data. Payload beyond
     * {@code len} is held in the connection and returned by the next read (so this honours arbitrary read
     * sizes).
     */
    public static int read(int h, long buf, int off, int len)
    {
        if (h < 0 || st[h] == FREE)
        {
            return -1;
        }
        if (plen[h] > 0)                                 // serve buffered bytes first (no new frame yet)
        {
            return drainPending(h, buf, off, len);
        }
        if (st[h] == CLOSED)
        {
            return -1;                                   // FIN already seen and drained
        }
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 8L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            long tcp = recvSeg(h);
            if (tcp == 0L)
            {
                continue;
            }
            int flags = Magic.load8(tcp + 13) & 0x3F;
            int segSeq = Ip.readBe32(tcp + 4);
            int dataOff = ((Magic.load8(tcp + 12) >> 4) & 0x0F) * 4;
            int payLen = Ip.lastIpTotal - Ip.lastIhl - dataOff;
            long payload = tcp + dataOff;
            if (payLen > 0 && segSeq == rcv[h])          // in-order data
            {
                rcv[h] = rcv[h] + payLen;
                sendSeg(h, 0x10, 0L, 0);                 // ACK
                ppay[h] = payload;                       // payload lives in rxb[h] until the next recvSeg
                plen[h] = payLen;
                int n = drainPending(h, buf, off, len);
                if ((flags & 0x01) != 0)                 // FIN rode along with the data
                {
                    finish(h);
                }
                return n;
            }
            if ((flags & 0x01) != 0)                     // FIN, no (new) data
            {
                finish(h);
                return -1;
            }
        }
        return 0;                                        // timeout, no data
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

    /** Block for the next TCP segment belonging to connection {@code h}; return its TCP header addr or 0. */
    private static long recvSeg(int h)
    {
        long ip = Ip.recvIp(rxb[h], 2048, RX_WAIT_MS);
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
