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
import magic.Magic;
import vm.Heap;
import vm.VM;

/**
 * ICMP echo (ping, RFC 792) over {@link Link}, before the receive queues run: the bring-up's first "IP packets
 * flow" proof. Moved out of the WiFi driver ({@code board.cyw43.Cyw43.ping}, M4).
 */
public final class Icmp
{
    private Icmp()
    {
    }

    /** Send an echo request to {@code targetIp} (via {@code targetMac}) and wait (~4 s) for its reply. */
    public static boolean ping(Address targetIp, Address targetMac, Address ourMac, Address ourIp)
    {
        Address buf = Heap.allocData(128);
        Link.send(buf, build(buf, targetIp, targetMac, ourMac, ourIp));
        Address rx = Heap.allocData(2048);
        long endT = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * 4L;
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
                continue;
            }
            Address eth = rx.plus(off);
            if (((eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF)) != 0x0800)
            {
                continue;
            }
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 1)
            {
                continue;                                // not ICMP
            }
            Address icmp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            if ((icmp.loadUnsignedByte() & 0xFF) != 0)
            {
                continue;                                // not echo reply
            }
            if (Ip.eq(ip.plus(12), targetIp))             // from the target
            {
                return true;
            }
        }
        return false;
    }

    /** Build an ICMP echo request (32-byte payload) into {@code buf}; returns the total frame length. */
    private static int build(Address buf, Address targetIp, Address targetMac, Address ourMac, Address ourIp)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) targetMac.plus(i).loadUnsignedByte());   // dst = the target (the gateway)
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0800
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address icmp = buf.plus(34);
        icmp.store((byte) 8);                               // type = echo request
        icmp.plus(1).store((byte) 0);                       // code
        Ip.be16(icmp.plus(2), 0);                           // checksum (fill below)
        Ip.be16(icmp.plus(4), 0x1234);                      // id
        Ip.be16(icmp.plus(6), 1);                           // seq
        int icmpLen = 8 + 32;                            // header + 32 zero payload bytes
        Ip.be16(icmp.plus(2), Ip.cksum(icmp, icmpLen));     // ICMP checksum over the whole message
        ip.store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        Ip.be16(ip.plus(2), 20 + icmpLen);
        Ip.be16(ip.plus(4), 0);
        Ip.be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);                        // TTL
        ip.plus(9).store((byte) 1);                         // protocol = ICMP
        Ip.be16(ip.plus(10), 0);
        Ip.copy4(ourIp, ip.plus(12));
        Ip.copy4(targetIp, ip.plus(16));
        Ip.be16(ip.plus(10), Ip.cksum(ip, 20));
        return 14 + 20 + icmpLen;
    }
}
