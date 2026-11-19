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
 * ARP resolution (RFC 826) over {@link Link}, before the receive queues run: who-has a neighbour's IPv4 address.
 * Moved out of the WiFi driver ({@code board.cyw43.Cyw43.arpResolve}, M4). Answering ARP for OUR address stays in
 * {@link Ip#answerArp}, which every receive loop calls.
 */
public final class Arp
{
    private Arp()
    {
    }

    /**
     * Ask who has {@code targetIp} (from {@code ourMac}/{@code ourIp}), re-asking every 0.5 s for up to 6 s, and copy
     * the answer's sender MAC to {@code macOut}. True on a reply from that address.
     */
    public static boolean resolve(Address targetIp, Address macOut, Address ourMac, Address ourIp)
    {
        Address buf = Heap.allocData(128);
        int flen = build(buf, targetIp, ourMac, ourIp);
        Address rx = Heap.allocData(2048);
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;                              // (re)send the request every 0.5 s
        while (Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                Link.send(buf, flen);
                nextSend = now + freq / 2L;
            }
            int len = Link.waitFrame(rx, 2048, Link.RX_WAIT_MS);
            if (len == 0)
            {
                continue;                                // no delay -- drain the FIFO as fast as possible
            }
            int off = Link.ethOffset(rx, len);
            if (off < 0)
            {
                continue;
            }
            Address eth = rx.plus(off);
            int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if ((eth.loadUnsignedByte() & 0xFF) != 0xFF)       // DIAG: print only UNICAST frames (skip the bcast flood)
            {
                board.bcm2711.Uart.write(Magic.bytes("  uni et="));
                VM.printHex(et);
                board.bcm2711.Uart.write(Magic.bytes(" dst="));
                Ip.printHex2(eth.loadUnsignedByte() & 0xFF);
                Ip.printHex2(eth.plus(5).loadUnsignedByte() & 0xFF);
                board.bcm2711.Uart.putc(0x0A);
            }
            if (et != 0x0806)
            {
                continue;                                // not ARP
            }
            Address arp = eth.plus(14);
            if (((arp.plus(6).loadUnsignedByte() & 0xFF) << 8 | (arp.plus(7).loadUnsignedByte() & 0xFF)) != 2)
            {
                continue;                                // not a reply
            }
            if (Ip.eq(arp.plus(14), targetIp))            // sender protocol addr == target
            {
                int i = 0;
                while (i < 6)
                {
                    macOut.plus(i).store((byte) arp.plus(8 + i).loadUnsignedByte());   // sender hardware addr
                    i = i + 1;
                }
                return true;
            }
        }
        return false;
    }

    /** Build an ARP request (who-has {@code targetIp}) into {@code buf}, padded to 60 bytes; returns 60. */
    private static int build(Address buf, Address targetIp, Address ourMac, Address ourIp)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) 0xFF);                 // dst = broadcast
            buf.plus(6 + i).store((byte) ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);                    // ethertype 0x0806 = ARP
        buf.plus(13).store((byte) 0x06);
        Address a = buf.plus(14);
        Ip.be16(a.plus(0), 1);                              // htype = ethernet
        Ip.be16(a.plus(2), 0x0800);                         // ptype = IPv4
        a.plus(4).store((byte) 6);                          // hlen
        a.plus(5).store((byte) 4);                          // plen
        Ip.be16(a.plus(6), 1);                              // oper = request
        i = 0;
        while (i < 6)
        {
            a.plus(8 + i).store((byte) ourMac.plus(i).loadUnsignedByte());   // sender hardware addr
            i = i + 1;
        }
        Ip.copy4(ourIp, a.plus(14));                        // sender protocol addr
        Ip.copy4(targetIp, a.plus(24));                     // target protocol addr (tha stays 0)
        return 60;                                       // 14 + 28, padded to the 60-byte minimum
    }
}
