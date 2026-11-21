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

import magic.Address;
import magic.DataReference;
import magic.Magic;
import vm.Heap;

/**
 * The IP foundation of joe-ng's all-Java network stack, independent of the board: the interface config (our
 * MAC/IP, the lease's mask, gateway and DNS server, the gateway MAC) that {@link NetUp} publishes after DHCP and
 * ARP, the one's-complement checksum + big-endian helpers, and send/receive of IPv4 over {@link Link}.
 *
 * <p>All addresses are raw heap pointers and all packet fields are poked via {@link Magic} — this is the
 * same byte-for-byte code the WiFi HTTP-GET path used, only relocated behind a reusable API.
 */
public final class Ip
{
    private Ip() {}

    // Interface config, published by NetUp after DHCP + ARP (all zero until then: the network is not up).
    public static Address ourMac;   // heap addr of our 6-byte station MAC
    public static DataReference ourIp;    // heap addr of our 4-byte leased IP
    public static DataReference netMask;  // heap addr of the lease's 4-byte subnet mask (java.net.NetworkInterface)
    public static DataReference gwMac;    // heap addr of the gateway's 6-byte MAC (all off-subnet traffic routes here)
    public static DataReference gwIp;     // heap addr of the lease's 4-byte default gateway
    public static DataReference dnsIp;    // heap addr of the lease's 4-byte DNS server

    // Scratch describing the last IPv4 frame recvIp() accepted.
    public static int lastIhl;      // IP header length (bytes)
    public static int lastIpTotal;  // IP total length (bytes)

    /** Send a fully-built 802.3 Ethernet frame. */
    public static void send(Address frame, int len)
    {
        Link.send(frame, len);
    }

    /**
     * Block for the next inbound IPv4 frame (up to {@code ms} ms); return the IP header address (inside
     * {@code rx}), or 0 on none/timeout/non-IPv4. Records IHL + total-length in {@link #lastIhl}/{@link
     * #lastIpTotal}. The link says where the 802.3 frame starts ({@link Link#ethOffset}); ARP is answered here.
     */
    public static Address recvIp(Address rx, int cap, int ms)
    {
        return recvIp(-1, rx, cap, ms);
    }

    /**
     * {@link #recvIp}, reading from the RX queue {@code q} (net.RxQueues) once the WiFi RX task owns the chip;
     * {@code q < 0} with the task running means no queue is bound, and nothing can arrive.
     */
    public static Address recvIp(int q, Address rx, int cap, int ms)
    {
        int len;
        if (RxQueues.active)
        {
            if (q < 0)
            {
                vm.VMScheduler.kernelSleep(ms);
                return Address.zero();
            }
            len = RxQueues.recv(q, rx, cap, ms);
        }
        else
        {
            len = Link.waitFrame(rx, cap, ms);
        }
        if (len == 0)
        {
            return Address.zero();
        }
        int off = Link.ethOffset(rx, len);
        if (off < 0)
        {
            return Address.zero();                       // not a data frame (a control reply, a link event)
        }
        Address eth = rx.plus(off);
        int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
        if (et == 0x0806)
        {
            answerArp(eth);
            return Address.zero();
        }
        if (et != 0x0800)
        {
            return Address.zero();                       // not IPv4
        }
        Address ip = eth.plus(14);
        lastIhl = (ip.loadUnsignedByte() & 0x0F) * 4;
        lastIpTotal = ((ip.plus(2).loadUnsignedByte() & 0xFF) << 8) | (ip.plus(3).loadUnsignedByte() & 0xFF);
        return ip;
    }

    /**
     * Answer an ARP request for our IP. A neighbour (the gateway) that learned our MAC re-verifies it with
     * unicast who-has probes once the entry goes stale; a host that never replies is declared unreachable
     * and every later IPv4 frame for it is dropped at the router. Public because every receive loop must call
     * it, not only this one: the driver's DNS resolver has its own loop.
     */
    public static void answerArp(Address eth)
    {
        Address arp = eth.plus(14);
        if ((((arp.plus(6).loadUnsignedByte() & 0xFF) << 8) | (arp.plus(7).loadUnsignedByte() & 0xFF)) != 1)
        {
            return;                                      // not a request
        }
        if (!eq(arp.plus(24), ourIp.toAddress()))
        {
            return;                                      // not asking for us
        }
        DataReference buf = Heap.allocData(64);
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) arp.plus(8 + i).loadUnsignedByte());                // to the requester's MAC
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        be16(buf.plus(12), 0x0806);
        Address r = buf.plus(14);
        be16(r.plus(0), 1);                              // hardware type = Ethernet
        be16(r.plus(2), 0x0800);                         // protocol type = IPv4
        r.plus(4).store((byte) 6);
        r.plus(5).store((byte) 4);
        be16(r.plus(6), 2);                              // opcode = reply
        i = 0;
        while (i < 6)
        {
            r.plus(8 + i).store((byte) ourMac.plus(i).loadUnsignedByte());                                // sender = us
            r.plus(18 + i).store((byte) arp.plus(8 + i).loadUnsignedByte());             // target = the requester
            i = i + 1;
        }
        copy4(ourIp.toAddress(), r.plus(14));
        copy4(arp.plus(14), r.plus(24));
        i = 42;
        while (i < 60)
        {
            buf.plus(i).store((byte) 0);                     // pad to the 60-byte Ethernet minimum
            i = i + 1;
        }
        send(buf.toAddress(), 60);
    }

    /** One's-complement IPv4 header checksum over {@code len} bytes at {@code addr}. */
    public static int cksum(Address a, int len)
    {
        int sum = 0;
        int i = 0;
        while (i < len)
        {
            sum = sum + (((a.plus(i).loadUnsignedByte() & 0xFF) << 8) | (a.plus(i + 1).loadUnsignedByte() & 0xFF));
            i = i + 2;
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Store a 16-bit big-endian (network-order) value. */
    public static void be16(Address a, int v)
    {
        a.store((byte) ((v >> 8) & 0xFF));
        a.plus(1).store((byte) (v & 0xFF));
    }

    /** Store a 32-bit big-endian value. */
    public static void be32(Address a, int v)
    {
        a.store((byte) ((v >> 24) & 0xFF));
        a.plus(1).store((byte) ((v >> 16) & 0xFF));
        a.plus(2).store((byte) ((v >> 8) & 0xFF));
        a.plus(3).store((byte) (v & 0xFF));
    }

    /** Read a 32-bit big-endian value. */
    public static int readBe32(Address a)
    {
        return ((a.loadUnsignedByte() & 0xFF) << 24) | ((a.plus(1).loadUnsignedByte() & 0xFF) << 16)
                | ((a.plus(2).loadUnsignedByte() & 0xFF) << 8) | (a.plus(3).loadUnsignedByte() & 0xFF);
    }

    /** Compare two 4-byte IPv4 addresses. */
    public static boolean eq(Address a, Address b)
    {
        int i = 0;
        while (i < 4)
        {
            if ((a.plus(i).loadUnsignedByte() & 0xFF) != (b.plus(i).loadUnsignedByte() & 0xFF))
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    /** Copy 4 bytes (an IPv4 address). */
    public static void copy4(Address src, Address dst)
    {
        int i = 0;
        while (i < 4)
        {
            dst.plus(i).store((byte) src.plus(i).loadUnsignedByte());
            i = i + 1;
        }
    }

    /** Print a dotted-quad IPv4 address (4 bytes at {@code a}). */
    public static void printIp(Address a)
    {
        int i = 0;
        while (i < 4)
        {
            vm.VM.printDec(a.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 3)
            {
                board.Uart.putc(0x2E);           // '.'
            }
            i = i + 1;
        }
    }

    /** Print a 6-byte MAC address as aa:bb:cc:dd:ee:ff. */
    public static void printMac(Address a)
    {
        int i = 0;
        while (i < 6)
        {
            printHex2(a.plus(i).loadUnsignedByte() & 0xFF);
            if (i < 5)
            {
                board.Uart.putc(0x3A);           // ':'
            }
            i = i + 1;
        }
    }

    /** Print a byte as two lowercase hex digits. */
    public static void printHex2(int v)
    {
        board.Uart.putc(hexDigit((v >> 4) & 0x0F));
        board.Uart.putc(hexDigit(v & 0x0F));
    }

    private static int hexDigit(int n)
    {
        return n < 10 ? (0x30 + n) : (0x61 + n - 10);
    }

    /** splitmix64 state, seeded from the counter on first use (boot-to-boot timing jitter differs per boot). */
    private static long rngState;

    /**
     * The next value of a per-boot pseudo-random stream, for ephemeral ports, TCP initial sequence numbers and DNS
     * query ids. All of them used to be CONSTANTS (TCP port 0xC001 + slot and ISN 0x1000, DNS port 0xC000 and id
     * 0x1234, SNTP port 0xC001), so every boot opened the SAME flows. For TCP that left an earlier boot's connection
     * where a new SYN could meet it (RFC 5961) -- the SUSPECTED, not measured, cause of three Pi boots in a row
     * timing out (2026-10-27). For DNS, a constant port and id make an answer trivially forgeable (RFC 5452).
     */
    public static long nextRandom()
    {
        if (rngState == 0L)
        {
            rngState = Magic.readCNTPCT_EL0() ^ 0x5DEECE66DL;
        }
        rngState = rngState + 0x9E3779B97F4A7C15L;
        long z = rngState;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A fresh local port from the dynamic range (RFC 6335: 49152..65535). */
    public static int ephemeralPort()
    {
        return 49152 + (int) (nextRandom() & 0x3FFF);
    }
}
