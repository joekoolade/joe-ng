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
 * DNS A-record lookup (RFC 1035) to the lease's DNS server ({@link Ip#dnsIp}), over {@link Link}: through a UDP
 * queue once {@link RxQueues} is active, straight off the link before that. What {@code InetAddress} and SNTP stand
 * on. Moved out of the WiFi driver ({@code board.cyw43.Cyw43.dnsResolve}, M4).
 */
public final class Dns
{
    private Dns()
    {
    }

    /**
     * Resolve {@code host} to its first A record in {@code ipOut} (4 bytes), re-asking every 0.5 s for up to 6 s
     * from a fresh port and id. True on an answer; on failure prints what the lookup SAW -- it reaches the guest as a
     * bare UnknownHostException (InetSocketAddress drops the cause), so this is the one place the reason exists.
     */
    public static boolean resolve(byte[] host, Address ipOut)
    {
        Address buf = Heap.allocData(256);
        int localPort = Ip.ephemeralPort();
        int id = (int) (Ip.nextRandom() & 0xFFFF);
        int flen = buildQuery(buf, host, localPort, id);
        Address rx = Heap.allocData(2048);
        long freq = Magic.readCNTFRQ_EL0();
        long endT = Magic.readCNTPCT_EL0() + freq * 6L;
        long nextSend = 0L;
        int sends = 0;
        int frames = 0;
        int ipv4 = 0;
        int fromDns = 0;
        int toUs = 0;
        int idOk = 0;
        int q = RxQueues.active ? RxQueues.bindUdp(localPort) : -1;
        boolean found = false;
        while (!found && Magic.readCNTPCT_EL0() < endT)
        {
            long now = Magic.readCNTPCT_EL0();
            if (now >= nextSend)
            {
                Link.send(buf, flen);
                nextSend = now + freq / 2L;
                sends = sends + 1;
            }
            int len = Link.recv(q, rx, 2048);
            if (len == 0)
            {
                continue;
            }
            int off = Link.ethOffset(rx, len);
            if (off < 0)
            {
                continue;
            }
            frames = frames + 1;
            Address eth = rx.plus(off);
            int et = (eth.plus(12).loadUnsignedByte() & 0xFF) << 8 | (eth.plus(13).loadUnsignedByte() & 0xFF);
            if (et == 0x0806)
            {
                Ip.answerArp(eth);                       // a stale gateway re-verifies us mid-query
                continue;
            }
            if (et != 0x0800)
            {
                continue;
            }
            ipv4 = ipv4 + 1;
            Address ip = eth.plus(14);
            if ((ip.plus(9).loadUnsignedByte() & 0xFF) != 17 || !Ip.eq(ip.plus(12), Ip.dnsIp))
            {
                continue;                                // not UDP, or not from our DNS server
            }
            fromDns = fromDns + 1;
            Address udp = ip.plus((ip.loadUnsignedByte() & 0x0F) * 4);
            if (((udp.loadUnsignedByte() & 0xFF) << 8 | (udp.plus(1).loadUnsignedByte() & 0xFF)) != 53)
            {
                continue;                                // not from the DNS port
            }
            if (((udp.plus(2).loadUnsignedByte() & 0xFF) << 8 | (udp.plus(3).loadUnsignedByte() & 0xFF)) != localPort)
            {
                continue;                                // not to this lookup's port
            }
            toUs = toUs + 1;
            Address d = udp.plus(8);
            if (((d.loadUnsignedByte() & 0xFF) << 8 | (d.plus(1).loadUnsignedByte() & 0xFF)) != id)
            {
                continue;                                // id mismatch
            }
            idOk = idOk + 1;
            int ancount = (d.plus(6).loadUnsignedByte() & 0xFF) << 8 | (d.plus(7).loadUnsignedByte() & 0xFF);
            if (ancount < 1)
            {
                continue;
            }
            Address rr = skipName(d.plus(12)).plus(4);     // skip question: qname + qtype(2) + qclass(2)
            int a = 0;
            while (a < ancount)
            {
                rr = skipName(rr);                       // answer name (often a compression pointer)
                int type = (rr.loadUnsignedByte() & 0xFF) << 8 | (rr.plus(1).loadUnsignedByte() & 0xFF);
                int rdlen = (rr.plus(8).loadUnsignedByte() & 0xFF) << 8 | (rr.plus(9).loadUnsignedByte() & 0xFF);
                if (type == 1 && rdlen == 4)             // A record
                {
                    Ip.copy4(rr.plus(10), ipOut);
                    found = true;
                    break;
                }
                rr = rr.plus(10 + rdlen);
                a = a + 1;
            }
        }
        RxQueues.unbind(q);
        if (found)
        {
            return true;
        }
        board.Uart.write(Magic.bytes("dns: no A record for "));
        board.Uart.write(host);
        board.Uart.write(Magic.bytes(" in 6 s: sends="));
        VM.printDec(sends);
        board.Uart.write(Magic.bytes(" frames="));
        VM.printDec(frames);
        board.Uart.write(Magic.bytes(" ipv4="));
        VM.printDec(ipv4);
        board.Uart.write(Magic.bytes(" fromDns="));
        VM.printDec(fromDns);
        board.Uart.write(Magic.bytes(" toPort="));
        VM.printDec(toUs);
        board.Uart.write(Magic.bytes(" idOk="));
        VM.printDec(idOk);
        board.Uart.putc(0x0A);
        return false;
    }

    /** Build an A-record query for {@code host} from {@code localPort} with id {@code id}; returns the frame length. */
    private static int buildQuery(Address buf, byte[] host, int localPort, int id)
    {
        int i = 0;
        while (i < 6)
        {
            buf.plus(i).store((byte) Ip.gwMac.plus(i).loadUnsignedByte());    // dst = gateway (route off-subnet)
            buf.plus(6 + i).store((byte) Ip.ourMac.plus(i).loadUnsignedByte());
            i = i + 1;
        }
        buf.plus(12).store((byte) 0x08);
        buf.plus(13).store((byte) 0x00);
        Address ip = buf.plus(14);
        Address udp = buf.plus(34);
        Address d = buf.plus(42);
        Ip.be16(d.plus(0), id);                             // id
        Ip.be16(d.plus(2), 0x0100);                         // flags: recursion desired
        Ip.be16(d.plus(4), 1);                              // qdcount
        Ip.be16(d.plus(6), 0);
        Ip.be16(d.plus(8), 0);
        Ip.be16(d.plus(10), 0);
        int qn = encodeQname(d.plus(12), host);
        Ip.be16(d.plus(12 + qn), 1);                        // qtype = A
        Ip.be16(d.plus(12 + qn + 2), 1);                    // qclass = IN
        int dnsLen = 12 + qn + 4;
        Ip.be16(udp.plus(0), localPort);                    // src port (ephemeral)
        Ip.be16(udp.plus(2), 53);                           // dst port = DNS
        Ip.be16(udp.plus(4), 8 + dnsLen);
        Ip.be16(udp.plus(6), 0);                            // no UDP checksum
        ip.store((byte) 0x45);
        ip.plus(1).store((byte) 0);
        Ip.be16(ip.plus(2), 20 + 8 + dnsLen);
        Ip.be16(ip.plus(4), 0);
        Ip.be16(ip.plus(6), 0);
        ip.plus(8).store((byte) 64);
        ip.plus(9).store((byte) 17);
        Ip.be16(ip.plus(10), 0);
        Ip.copy4(Ip.ourIp, ip.plus(12));
        Ip.copy4(Ip.dnsIp, ip.plus(16));
        Ip.be16(ip.plus(10), Ip.cksum(ip, 20));
        return 14 + 20 + 8 + dnsLen;
    }

    /** Encode a hostname as DNS labels (len-prefixed, root-terminated) at {@code dst}; returns bytes written. */
    private static int encodeQname(Address dst, byte[] host)
    {
        int p = 0;
        int hp = 0;
        while (hp < host.length)
        {
            Address lenPos = dst.plus(p);                   // reserve the label-length byte
            p = p + 1;
            int seg = 0;
            while (hp < host.length && (host[hp] & 0xFF) != 0x2E)   // until '.' or end
            {
                dst.plus(p).store((byte) (host[hp] & 0xFF));
                p = p + 1;
                hp = hp + 1;
                seg = seg + 1;
            }
            lenPos.store((byte) seg);
            if (hp < host.length && (host[hp] & 0xFF) == 0x2E)
            {
                hp = hp + 1;                             // skip the dot
            }
        }
        dst.plus(p).store((byte) 0);                        // root label
        p = p + 1;
        return p;
    }

    /** Advance past a DNS name (labels, or a 2-byte 0xC0 compression pointer); returns the position after it. */
    private static Address skipName(Address q)
    {
        while (true)
        {
            int b = q.loadUnsignedByte() & 0xFF;
            if (b == 0)
            {
                return q.plus(1);
            }
            if ((b & 0xC0) == 0xC0)
            {
                return q.plus(2);                        // compression pointer ends the name
            }
            q = q.plus(1 + b);
        }
    }
}
