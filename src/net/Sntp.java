/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
package net;

import board.bcm2711.Uart;
import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VM;

/**
 * An SNTP client (RFC 4330): one request to an NTP server once the interface is up, and the answer sets the wall
 * clock ({@link VM#setWallClock}). Without it the clock is the commit time of the image's source plus uptime -- a
 * lower bound, behind by the age of the commit.
 *
 * <p>The request carries our own transmit timestamp and the reply must echo it as its originate timestamp (the
 * RFC's check that the answer is to THIS request); a reply must be mode 4 (server), version 3 or 4, with a nonzero
 * stratum (stratum 0 is a "kiss-o'-death") and a nonzero transmit time. The time taken is the server's transmit
 * time plus half the round trip. An answer earlier than the clock already reads -- the commit-time floor -- is
 * refused: no image runs before its source was committed, so such an answer is wrong.
 */
public final class Sntp
{
    private Sntp()
    {
    }

    /** Seconds from the NTP epoch (1900-01-01) to the Unix epoch (1970-01-01). */
    private static final long NTP_UNIX_OFFSET = 2208988800L;

    private static final int NTP_PORT = 123;
    private static final int RX_WAIT_MS = 200;

    /** Ask, and set the clock from the answer. Prints one {@code clock:} line saying which source it now runs on. */
    public static void sync()
    {
        Address ip = Heap.allocData(4);
        if (!board.cyw43.Cyw43.dnsResolve(Magic.bytes("pool.ntp.org"), ip))                    // the NTP pool's round-robin name
        {
            Uart.write(Magic.bytes("clock: no DNS answer for pool.ntp.org -- stays at the commit time + uptime\n"));
            return;
        }
        long nowMs = query(ip);
        if (nowMs == 0L)
        {
            Uart.write(Magic.bytes("clock: no SNTP answer -- stays at the commit time + uptime\n"));
            return;
        }
        long floor = VM.wallClockNowMs();
        if (nowMs < floor)
        {
            Uart.write(Magic.bytes("clock: SNTP answer is before the commit time, refused: "));
            printDate(nowMs);
            Uart.write(Magic.bytes("\n"));
            return;
        }
        VM.setWallClock(nowMs);
        Uart.write(Magic.bytes("clock: SNTP "));
        printIp(ip);
        Uart.write(Magic.bytes(" -> "));
        printDate(nowMs);
        Uart.write(Magic.bytes(" UTC (was "));
        printDec((nowMs - floor) / 1000L);
        Uart.write(Magic.bytes(" s behind)\n"));
    }

    /** One SNTP exchange with {@code serverIp}, resent every second for 5 s; the time now in ms, or 0. */
    private static long query(Address serverIp)
    {
        Address buf = Heap.allocData(128);
        Address rx = Heap.allocData(2048);
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 5L;
        long nextSend = 0L;
        long sentAt = 0L;
        long stampHi = 0L;
        long stampLo = 0L;
        int localPort = Ip.ephemeralPort();               // one per exchange; resends keep it
        int q = RxQueues.active ? RxQueues.bindUdp(localPort) : -1;
        long got = 0L;
        while (got == 0L && Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                sentAt = now;
                stampHi = now >>> 32;                     // our transmit timestamp: any value the reply must echo
                stampLo = now & 0xFFFFFFFFL;
                int flen = build(buf, serverIp, localPort, (int) stampHi, (int) stampLo);
                Ip.send(buf, flen);
                nextSend = now + freq;
            }
            Address ip = Ip.recvIp(q, rx, 2048, RX_WAIT_MS);
            if (ip.isZero())
            {
                continue;
            }
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 17 || !Ip.eq(ip.plus(12), serverIp))
            {
                continue;                                // not UDP, or not from the server
            }
            Address udp = ip.plus(Ip.lastIhl);
            if (be16(udp) != NTP_PORT || be16(udp.plus(2)) != localPort || be16(udp.plus(4)) < 8 + 48)
            {
                continue;
            }
            Address m = udp.plus(8);
            int b0 = m.loadUnsignedByte() & 0xFF;
            int vn = (b0 >> 3) & 7;
            if ((b0 & 7) != 4 || vn < 3 || vn > 4 || (m.plus(1).loadUnsignedByte() & 0xFF) == 0)
            {
                continue;                                // not a server reply, or a kiss-o'-death
            }
            if ((Ip.readBe32(m.plus(24)) & 0xFFFFFFFFL) != stampHi || (Ip.readBe32(m.plus(28)) & 0xFFFFFFFFL) != stampLo)
            {
                continue;                                // not the answer to our last request
            }
            long secs = Ip.readBe32(m.plus(40)) & 0xFFFFFFFFL;
            long frac = Ip.readBe32(m.plus(44)) & 0xFFFFFFFFL;
            if (secs == 0L)
            {
                continue;
            }
            long rttMs = (Magic.readCNTPCT_EL0() - sentAt) / (freq / 1000L);
            long ms = (secs - NTP_UNIX_OFFSET) * 1000L + (frac * 1000L >>> 32);
            got = ms + rttMs / 2L;
        }
        RxQueues.unbind(q);
        return got;
    }

    /**
     * An Ethernet/IPv4/UDP/NTP client request from {@code localPort} to {@code serverIp} in {@code buf}; returns the
     * frame length.
     */
    private static int build(Address buf, Address serverIp, int localPort, int stampHi, int stampLo)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) Ip.gwMac.plus(i).loadUnsignedByte());                          // off-subnet: to the gateway
            buf.plus(6 + i).store((byte) Ip.ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        Ip.be16(buf.plus(12), 0x0800);
        Address ip = buf.plus(14);
        Address udp = buf.plus(34);
        Address m = buf.plus(42);
        i = 0;
        while (i < 48)
        {
            m.plus(i).store((byte) 0);
            i = i + 1;
        }
        m.store((byte) 0x23);                            // LI 0, version 4, mode 3 (client)
        Ip.be32(m.plus(40), stampHi);                    // transmit timestamp, echoed as the reply's originate
        Ip.be32(m.plus(44), stampLo);
        Ip.be16(udp.plus(0), localPort);
        Ip.be16(udp.plus(2), NTP_PORT);
        Ip.be16(udp.plus(4), 8 + 48);
        Ip.be16(udp.plus(6), 0);                         // no UDP checksum (allowed over IPv4)
        ip.plus(0).store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        Ip.be16(ip.plus(2), 20 + 8 + 48);
        Ip.be16(ip.plus(4), 0);
        Ip.be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);
        ip.plus(9).store((byte) 17);                         // UDP
        Ip.be16(ip.plus(10), 0);
        Ip.copy4(Ip.ourIp, ip.plus(12));
        Ip.copy4(serverIp, ip.plus(16));
        Ip.be16(ip.plus(10), Ip.cksum(ip, 20));
        return 14 + 20 + 8 + 48;
    }

    private static int be16(Address a)
    {
        return ((a.loadUnsignedByte() & 0xFF) << 8) | (a.plus(1).loadUnsignedByte() & 0xFF);
    }

    /** {@code ms} since the epoch as {@code yyyy-mm-dd hh:mm:ss} (UTC), by the days-from-civil inverse. */
    private static void printDate(long ms)
    {
        long secs = ms / 1000L;
        long days = secs / 86400L;
        long rem = secs % 86400L;
        long z = days + 719468L;
        long era = z / 146097L;
        long doe = z - era * 146097L;
        long yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;
        long d = doy - (153L * mp + 2L) / 5L + 1L;
        long mo = mp < 10L ? mp + 3L : mp - 9L;
        long y = yoe + era * 400L + (mo <= 2L ? 1L : 0L);
        printDec(y);
        Uart.putc(0x2D);
        print2(mo);
        Uart.putc(0x2D);
        print2(d);
        Uart.putc(0x20);
        print2(rem / 3600L);
        Uart.putc(0x3A);
        print2(rem / 60L % 60L);
        Uart.putc(0x3A);
        print2(rem % 60L);
    }

    private static void print2(long v)
    {
        Uart.putc(0x30 + (int) (v / 10L));
        Uart.putc(0x30 + (int) (v % 10L));
    }

    private static void printDec(long v)
    {
        if (v < 0L)
        {
            Uart.putc(0x2D);
            v = -v;
        }
        if (v >= 10L)
        {
            printDec(v / 10L);
        }
        Uart.putc(0x30 + (int) (v % 10L));
    }

    private static void printIp(Address a)
    {
        int i = 0;
        while (i < 4)
        {
            printDec(a.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 3)
            {
                Uart.putc(0x2E);
            }
            i = i + 1;
        }
    }
}
