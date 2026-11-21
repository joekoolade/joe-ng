/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package net;

import magic.Address;
import magic.DataReference;
import magic.Magic;
import vm.Heap;
import vm.VM;

/**
 * DHCP client (RFC 2131): DISCOVER -> OFFER -> REQUEST -> ACK over {@link Link}, before the receive queues run.
 * Moved out of the WiFi driver ({@code board.cyw43.Cyw43.dhcp}, M4) unchanged, so any link can lease an address.
 */
public final class Dhcp
{
    private Dhcp()
    {
    }

    /**
     * Lease an address for {@code mac}: fill {@code ipOut}, {@code maskOut}, {@code gwOut}, {@code dnsOut} (4 bytes
     * each) from the ACK. True on a lease. Retries the handshake: right after a WPA2 4-way the first exchange can drop
     * a packet (we saw OFFER arrive but the REQUEST draw no ACK), so a lost ACK resends the REQUEST a few times and a
     * still-failing exchange re-DISCOVERs. The xid is a fixed constant, so retries stay in one transaction.
     */
    public static boolean lease(Address mac, Address ipOut, Address maskOut, Address gwOut, Address dnsOut)
    {
        DataReference serverId = Heap.allocData(4);
        DataReference buf = Heap.allocData(512);
        DataReference buf2 = Heap.allocData(512);
        DataReference rx = Heap.allocData(2048);
        int disc = 0;
        while (disc < 4)
        {
            disc = disc + 1;
            board.Uart.write(Magic.bytes("net: dhcp discover\n"));
            Link.send(buf.toAddress(), build(buf.toAddress(), mac, 1, Address.zero(), Address.zero()));          // DISCOVER
            if (!await(rx.toAddress(), 2, ipOut, serverId.toAddress(), Address.zero(), Address.zero(), Address.zero()))   // OFFER (type 2)
            {
                board.Uart.write(Magic.bytes("net: dhcp no offer, retry\n"));
                continue;
            }
            board.Uart.write(Magic.bytes("net: dhcp offer ip "));
            Ip.printIp(ipOut);
            board.Uart.putc(0x0A);

            boolean acked = false;
            int rq = 0;
            while (rq < 3 && !acked)                      // REQUEST -> ACK, resending REQUEST on a lost ACK
            {
                rq = rq + 1;
                board.Uart.write(Magic.bytes("net: dhcp request\n"));
                Link.send(buf2.toAddress(), build(buf2.toAddress(), mac, 3, ipOut, serverId.toAddress()));   // REQUEST (opt 50 req-ip, opt 54 server-id)
                acked = await(rx.toAddress(), 5, ipOut, serverId.toAddress(), maskOut, gwOut, dnsOut);   // ACK (type 5)
            }
            if (!acked)
            {
                board.Uart.write(Magic.bytes("net: dhcp no ack, re-discover\n"));
                continue;
            }
            board.Uart.write(Magic.bytes("net: dhcp ACK ip "));
            Ip.printIp(ipOut);
            board.Uart.write(Magic.bytes("\n  gateway "));
            Ip.printIp(gwOut);
            board.Uart.write(Magic.bytes("\n  subnet  "));
            Ip.printIp(maskOut);
            board.Uart.write(Magic.bytes("\n  dns     "));
            Ip.printIp(dnsOut);
            board.Uart.putc(0x0A);
            return true;
        }
        board.Uart.write(Magic.bytes("net: dhcp FAILED after retries\n"));
        return false;
    }

    /**
     * Build a DHCP message into {@code buf}: Ethernet(bcast)/IP/UDP(68->67)/BOOTP + options. {@code msgType}
     * 1=DISCOVER 3=REQUEST. If {@code reqIp}/{@code serverId} are non-zero, adds option 50 (requested IP) /
     * option 54 (server id). Returns the total frame length.
     */
    private static int build(Address buf, Address mac, int msgType, Address reqIp, Address serverId)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) 0xFF);                 // dst = broadcast
            buf.plus(6 + i).store((byte) mac.plus(i).loadUnsignedByte());   // src = our MAC
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0800
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address udp = buf.plus(34);
        Address bootp = buf.plus(42);
        bootp.store((byte) 1);                              // op = BOOTREQUEST
        bootp.plus(1).store((byte) 1);                      // htype
        bootp.plus(2).store((byte) 6);                      // hlen
        bootp.plus(4).store(0x3903F326);                    // xid
        Ip.be16(bootp.plus(10), 0x8000);                    // broadcast flag
        i = 0;
        while (i < 6)
        {
            bootp.plus(28 + i).store((byte) mac.plus(i).loadUnsignedByte());   // chaddr
            i = i + 1;
        }
        bootp.plus(236).store((byte) 0x63);                 // magic cookie
        bootp.plus(237).store((byte) 0x82);
        bootp.plus(238).store((byte) 0x53);
        bootp.plus(239).store((byte) 0x63);
        Address opt = bootp.plus(240);
        int p = 0;
        opt.plus(p).store((byte) 53);                       // DHCP message type
        opt.plus(p + 1).store((byte) 1);
        opt.plus(p + 2).store((byte) msgType);
        p = p + 3;
        if (!reqIp.isZero())
        {
            opt.plus(p).store((byte) 50);                   // requested IP address
            opt.plus(p + 1).store((byte) 4);
            Ip.copy4(reqIp, opt.plus(p + 2));
            p = p + 6;
        }
        if (!serverId.isZero())
        {
            opt.plus(p).store((byte) 54);                   // server identifier
            opt.plus(p + 1).store((byte) 4);
            Ip.copy4(serverId, opt.plus(p + 2));
            p = p + 6;
        }
        opt.plus(p).store((byte) 55);                       // parameter request list
        opt.plus(p + 1).store((byte) 4);
        opt.plus(p + 2).store((byte) 1);                    //   subnet
        opt.plus(p + 3).store((byte) 3);                    //   router
        opt.plus(p + 4).store((byte) 6);                    //   DNS
        opt.plus(p + 5).store((byte) 15);                   //   domain
        p = p + 6;
        opt.plus(p).store((byte) 255);                      // end
        p = p + 1;
        int dhcpLen = 240 + p;
        Ip.be16(udp.plus(0), 68);                           // UDP src port
        Ip.be16(udp.plus(2), 67);                           // UDP dst port
        Ip.be16(udp.plus(4), 8 + dhcpLen);
        Ip.be16(udp.plus(6), 0);                            // no UDP checksum
        ip.store((byte) 0x45);                              // IPv4, IHL=5
        ip.plus(1).store((byte) 0);
        Ip.be16(ip.plus(2), 20 + 8 + dhcpLen);
        Ip.be16(ip.plus(4), 0);
        Ip.be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);                        // TTL
        ip.plus(9).store((byte) 17);                        // UDP
        Ip.be16(ip.plus(10), 0);
        i = 0;
        while (i < 4)
        {
            ip.plus(16 + i).store((byte) 0xFF);             // dst = 255.255.255.255
            i = i + 1;
        }
        Ip.be16(ip.plus(10), Ip.cksum(ip, 20));
        return 14 + 20 + 8 + dhcpLen;
    }

    /**
     * Wait (~6 s) for a DHCP reply of message type {@code wantType} (2=Offer, 5=Ack) on UDP port 68. On a
     * match: copies yiaddr -> {@code yiOut} and, when the corresponding out is non-zero, options
     * 54->serverIdOut, 1->snOut, 3->gwOut, 6->dnsOut. Returns true if a matching reply arrived.
     */
    private static boolean await(Address rx, int wantType, Address yiOut, Address serverIdOut, Address snOut,
            Address gwOut, Address dnsOut)
    {
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 6L;
        while (Magic.readCNTPCT_EL0() < endT)
        {
            int len = Link.waitFrame(rx, 2048, Link.RX_WAIT_MS);
            if (len == 0)
            {
                VM.delayMs(2);
                continue;
            }
            int off = Link.ethOffset(rx, len);
            if (off < 0)
            {
                continue;                                // not a data frame (an ioctl ack)
            }
            Address eth = rx.plus(off);
            int ethertype = ((eth.plus(12).loadUnsignedByte() & 0xFF) << 8) | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if (ethertype != 0x0800)
            {
                continue;
            }
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 17)
            {
                continue;
            }
            Address udp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            int dport = ((udp.plus(2).loadUnsignedByte() & 0xFF) << 8) | (udp.plus(3).loadUnsignedByte() & 0xFF);
            if (dport != 68)
            {
                continue;
            }
            Address bootp = udp.plus(8);
            Address opt = bootp.plus(240);
            Address optEnd = rx.plus(len);
            if (optFirst(opt, optEnd, 53) != wantType)   // DHCP message type option
            {
                continue;
            }
            Ip.copy4(bootp.plus(16), yiOut);              // yiaddr
            optCopy(opt, optEnd, 54, serverIdOut);
            optCopy(opt, optEnd, 1, snOut);
            optCopy(opt, optEnd, 3, gwOut);
            optCopy(opt, optEnd, 6, dnsOut);
            return true;
        }
        return false;
    }

    /** Return the first value byte of DHCP option {@code code}, or -1 if absent. */
    private static int optFirst(Address opt, Address end, int code)
    {
        Address o = opt;
        while (o.toLong() < end.toLong())
        {
            int c = o.loadUnsignedByte() & 0xFF;
            if (c == 255)
            {
                return -1;
            }
            if (c == 0)
            {
                o = o.plus(1);
                continue;
            }
            int l = o.plus(1).loadUnsignedByte() & 0xFF;
            if (c == code)
            {
                return o.plus(2).loadUnsignedByte() & 0xFF;
            }
            o = o.plus(2 + l);
        }
        return -1;
    }

    /** Copy up to 4 bytes of DHCP option {@code code} to {@code dst} (no-op if dst==0 or option absent). */
    private static void optCopy(Address opt, Address end, int code, Address dst)
    {
        if (dst.isZero())
        {
            return;
        }
        Address o = opt;
        while (o.toLong() < end.toLong())
        {
            int c = o.loadUnsignedByte() & 0xFF;
            if (c == 255)
            {
                return;
            }
            if (c == 0)
            {
                o = o.plus(1);
                continue;
            }
            int l = o.plus(1).loadUnsignedByte() & 0xFF;
            if (c == code)
            {
                int i = 0;
                while (i < 4 && i < l)
                {
                    dst.plus(i).store((byte) o.plus(2 + i).loadUnsignedByte());
                    i = i + 1;
                }
                return;
            }
            o = o.plus(2 + l);
        }
    }
}
