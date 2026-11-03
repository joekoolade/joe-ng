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

import magic.Magic;
import vm.VM;

/**
 * The network stack's counters, named after their Linux {@code /proc/net/snmp} (RFC 4293 / 4022 / 4113 MIB)
 * and {@code /proc/net/netstat} {@code TcpExt} counterparts so a reading can be compared with a Linux host's
 * {@code nstat}. Every counter is something the stack MEASURED at the point it happened; {@link #print} writes
 * them all on three lines.
 *
 * <p>Inbound counting has two homes. Once the WiFi RX task owns the chip ({@link RxQueues#active}), every
 * frame passes {@link RxQueues#dispatch}, which counts the whole receive path: header checks, delivery,
 * discards and ports with no listener. Before that (bring-up: DHCP, ARP, ping) consumers read the chip
 * themselves, and only {@link #ipIn} runs on each frame, so the delivery-side counters stay at zero there.
 * Outbound is counted once, in {@link #out}, for every frame the WiFi driver transmits.
 */
public final class NetStats
{
    private NetStats() {}

    // Ip
    public static int ipInReceives;          // IPv4 datagrams received (before any check)
    public static int ipInHdrErrors;         // bad version, header length, total length or header checksum
    public static int ipInAddrErrors;        // not addressed to us (or broadcast)
    public static int ipInUnknownProtos;     // a protocol other than TCP, UDP or ICMP
    public static int ipInDiscards;          // valid, but no buffer or queue space for it
    public static int ipInDelivers;          // handed to TCP, UDP or ICMP
    public static int ipReasmFails;          // fragments: this stack does not reassemble, so each is dropped
    public static int ipOutRequests;         // IPv4 datagrams transmitted

    // Icmp
    public static int icmpInMsgs;
    public static int icmpOutMsgs;

    // Tcp
    public static int tcpActiveOpens;        // SYNs sent for a new connection
    public static int tcpAttemptFails;       // connects refused or timed out
    public static int tcpEstabResets;        // established connections ended by a RST
    public static int tcpInSegs;             // segments received (checksum-valid or not)
    public static int tcpOutSegs;            // segments transmitted
    public static int tcpRetransSegs;        // segments sent again (today: only SYN resends)
    public static int tcpInErrs;             // segments dropped as malformed (checksum, header length)
    public static int tcpInCsumErrors;       // ... of which a bad checksum
    public static int tcpOutRsts;            // RSTs sent
    public static int tcpNoConn;             // segments for no connection of ours (dropped)

    // TcpExt
    public static int tcpOfoQueue;           // out-of-order segments held for reassembly
    public static int tcpOfoDrop;            // out-of-order segments dropped (no room in the hole list)
    public static int tcpOfoMerge;           // held segments that became in-order when a hole filled
    public static int tcpDupData;            // segments carrying only bytes already received
    public static int tcpPartialDup;         // segments whose front was already received (trimmed)
    public static int tcpWndDrop;            // payload bytes dropped because they lay beyond our window
    public static int tcpDupAcksOut;         // ACKs sent for an out-of-order or duplicate segment
    public static int tcpChallengeAcks;      // ACKs sent for an in-window but not exact RST (RFC 5961)
    public static int tcpRstIgnored;         // RSTs outside the window
    public static int tcpWinUpdates;         // pure window updates sent after the application read
    public static int tcpSynAckRetrans;      // retransmitted SYN-ACKs re-ACKed (our handshake ACK was lost)

    // Udp
    public static int udpInDatagrams;        // datagrams delivered to a bound port
    public static int udpNoPorts;            // datagrams for a port nobody bound
    public static int udpInErrors;           // malformed (length, checksum)
    public static int udpInCsumErrors;       // ... of which a bad checksum
    public static int udpRcvbufErrors;       // dropped because the port's queue or the buffer pool was full
    public static int udpOutDatagrams;       // datagrams transmitted

    /**
     * Count one received IPv4 header at {@code ip} with {@code avail} bytes of frame behind it, and check it.
     * Returns true when the datagram is valid and for us: version 4, a sane header and total length that fits
     * the frame, a correct header checksum, not a fragment, and addressed to our IP or a broadcast (any address
     * before a lease exists, when DHCP's offer is addressed to an IP we do not have yet).
     */
    public static boolean ipIn(long ip, int avail)
    {
        ipInReceives = ipInReceives + 1;
        int vihl = Magic.load8(ip) & 0xFF;
        int ihl = (vihl & 0x0F) * 4;
        int total = ((Magic.load8(ip + 2) & 0xFF) << 8) | (Magic.load8(ip + 3) & 0xFF);
        if ((vihl >> 4) != 4 || ihl < 20 || total < ihl || total > avail)
        {
            ipInHdrErrors = ipInHdrErrors + 1;
            return false;
        }
        if (Ip.cksum(ip, ihl) != 0)                      // summing a valid header WITH its checksum gives 0
        {
            ipInHdrErrors = ipInHdrErrors + 1;
            return false;
        }
        int frag = ((Magic.load8(ip + 6) & 0xFF) << 8) | (Magic.load8(ip + 7) & 0xFF);
        if ((frag & 0x3FFF) != 0)                        // MF set or a nonzero offset
        {
            ipReasmFails = ipReasmFails + 1;
            return false;
        }
        if (Ip.ourIp != 0L && !Ip.eq(ip + 16, Ip.ourIp) && (Magic.load8(ip + 19) & 0xFF) != 0xFF)
        {
            ipInAddrErrors = ipInAddrErrors + 1;
            return false;
        }
        return true;
    }

    /**
     * Count one frame read off the chip BEFORE the RX task runs ({@code rx}, {@code len} bytes of SDPCM):
     * every reader then parses for itself, so this only counts what arrived -- an IPv4 datagram's checks and
     * the protocol it carries. It drops nothing.
     */
    public static void rxSync(long rx, int len)
    {
        if (len < 12 || (Magic.load8(rx + 5) & 0x0F) != 2)
        {
            return;                                      // no frame, or not the data channel
        }
        long eth = rx + (Magic.load8(rx + 7) & 0xFF);
        eth = eth + 4 + (Magic.load8(eth + 3) & 0xFF) * 4;
        if (((Magic.load8(eth + 12) & 0xFF) << 8 | (Magic.load8(eth + 13) & 0xFF)) != 0x0800)
        {
            return;
        }
        long ip = eth + 14;
        if (!ipIn(ip, len - (int) (ip - rx)))
        {
            return;
        }
        ipInDelivers = ipInDelivers + 1;
        int proto = Magic.load8(ip + 9) & 0xFF;
        if (proto == 6)
        {
            tcpInSegs = tcpInSegs + 1;
        }
        else if (proto == 17)
        {
            udpInDatagrams = udpInDatagrams + 1;
        }
        else if (proto == 1)
        {
            icmpInMsgs = icmpInMsgs + 1;
        }
        else
        {
            ipInUnknownProtos = ipInUnknownProtos + 1;
        }
    }

    /**
     * Check the UDP datagram at {@code udp} ({@code len} bytes of IP payload) of the IPv4 header {@code ip}:
     * its length field, and its checksum unless the sender left it 0 (optional over IPv4, RFC 768). Counts a
     * failure; true when it may be delivered.
     */
    public static boolean udpOk(long ip, long udp, int len)
    {
        int ulen = ((Magic.load8(udp + 4) & 0xFF) << 8) | (Magic.load8(udp + 5) & 0xFF);
        if (len < 8 || ulen < 8 || ulen > len)
        {
            udpInErrors = udpInErrors + 1;
            return false;
        }
        int ck = ((Magic.load8(udp + 6) & 0xFF) << 8) | (Magic.load8(udp + 7) & 0xFF);
        if (ck != 0 && l4Cksum(17, udp, ulen, ip + 12, ip + 16) != 0)
        {
            udpInErrors = udpInErrors + 1;
            udpInCsumErrors = udpInCsumErrors + 1;
            return false;
        }
        return true;
    }

    /**
     * The one's-complement sum of a TCP or UDP segment ({@code len} bytes at {@code seg}) with the IPv4
     * pseudo-header of protocol {@code proto} between {@code srcIp} and {@code dstIp}. Over a segment that
     * carries its checksum the result is 0 when the checksum is right; over one whose checksum field is 0 it
     * is the value to store there.
     */
    public static int l4Cksum(int proto, long seg, int len, long srcIp, long dstIp)
    {
        int sum = 0;
        sum = sum + (((Magic.load8(srcIp) & 0xFF) << 8) | (Magic.load8(srcIp + 1) & 0xFF));
        sum = sum + (((Magic.load8(srcIp + 2) & 0xFF) << 8) | (Magic.load8(srcIp + 3) & 0xFF));
        sum = sum + (((Magic.load8(dstIp) & 0xFF) << 8) | (Magic.load8(dstIp + 1) & 0xFF));
        sum = sum + (((Magic.load8(dstIp + 2) & 0xFF) << 8) | (Magic.load8(dstIp + 3) & 0xFF));
        sum = sum + proto;                               // zero byte + protocol
        sum = sum + len;
        int i = 0;
        while (i + 1 < len)
        {
            sum = sum + (((Magic.load8(seg + i) & 0xFF) << 8) | (Magic.load8(seg + i + 1) & 0xFF));
            i = i + 2;
        }
        if (i < len)
        {
            sum = sum + ((Magic.load8(seg + i) & 0xFF) << 8);   // odd trailing byte
        }
        while ((sum >> 16) != 0)
        {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    /** Count one transmitted 802.3 frame ({@code frame}, {@code len} bytes) by its ethertype and protocol. */
    public static void out(long frame, int len)
    {
        if (len < 34)
        {
            return;
        }
        int et = ((Magic.load8(frame + 12) & 0xFF) << 8) | (Magic.load8(frame + 13) & 0xFF);
        if (et != 0x0800)
        {
            return;
        }
        ipOutRequests = ipOutRequests + 1;
        int proto = Magic.load8(frame + 14 + 9) & 0xFF;
        if (proto == 6)
        {
            tcpOutSegs = tcpOutSegs + 1;
            if ((Magic.load8(frame + 14 + ((Magic.load8(frame + 14) & 0x0F) * 4) + 13) & 0x04) != 0)
            {
                tcpOutRsts = tcpOutRsts + 1;
            }
        }
        else if (proto == 17)
        {
            udpOutDatagrams = udpOutDatagrams + 1;
        }
        else if (proto == 1)
        {
            icmpOutMsgs = icmpOutMsgs + 1;
        }
    }

    /** Print every counter, one line each for Ip (with Icmp), Tcp (with TcpExt) and Udp. */
    public static void print()
    {
        board.bcm2711.Uart.write(Magic.bytes("net: Ip InReceives="));
        VM.printDec(ipInReceives);
        board.bcm2711.Uart.write(Magic.bytes(" InHdrErrors="));
        VM.printDec(ipInHdrErrors);
        board.bcm2711.Uart.write(Magic.bytes(" InAddrErrors="));
        VM.printDec(ipInAddrErrors);
        board.bcm2711.Uart.write(Magic.bytes(" InUnknownProtos="));
        VM.printDec(ipInUnknownProtos);
        board.bcm2711.Uart.write(Magic.bytes(" InDiscards="));
        VM.printDec(ipInDiscards);
        board.bcm2711.Uart.write(Magic.bytes(" InDelivers="));
        VM.printDec(ipInDelivers);
        board.bcm2711.Uart.write(Magic.bytes(" ReasmFails="));
        VM.printDec(ipReasmFails);
        board.bcm2711.Uart.write(Magic.bytes(" OutRequests="));
        VM.printDec(ipOutRequests);
        board.bcm2711.Uart.write(Magic.bytes(" | Icmp InMsgs="));
        VM.printDec(icmpInMsgs);
        board.bcm2711.Uart.write(Magic.bytes(" OutMsgs="));
        VM.printDec(icmpOutMsgs);
        board.bcm2711.Uart.write(Magic.bytes("\nnet: Tcp ActiveOpens="));
        VM.printDec(tcpActiveOpens);
        board.bcm2711.Uart.write(Magic.bytes(" AttemptFails="));
        VM.printDec(tcpAttemptFails);
        board.bcm2711.Uart.write(Magic.bytes(" EstabResets="));
        VM.printDec(tcpEstabResets);
        board.bcm2711.Uart.write(Magic.bytes(" CurrEstab="));
        VM.printDec(Tcp.currEstab());
        board.bcm2711.Uart.write(Magic.bytes(" InSegs="));
        VM.printDec(tcpInSegs);
        board.bcm2711.Uart.write(Magic.bytes(" OutSegs="));
        VM.printDec(tcpOutSegs);
        board.bcm2711.Uart.write(Magic.bytes(" RetransSegs="));
        VM.printDec(tcpRetransSegs);
        board.bcm2711.Uart.write(Magic.bytes(" InErrs="));
        VM.printDec(tcpInErrs);
        board.bcm2711.Uart.write(Magic.bytes(" InCsumErrors="));
        VM.printDec(tcpInCsumErrors);
        board.bcm2711.Uart.write(Magic.bytes(" OutRsts="));
        VM.printDec(tcpOutRsts);
        board.bcm2711.Uart.write(Magic.bytes(" NoConn="));
        VM.printDec(tcpNoConn);
        board.bcm2711.Uart.write(Magic.bytes(" | TcpExt OfoQueue="));
        VM.printDec(tcpOfoQueue);
        board.bcm2711.Uart.write(Magic.bytes(" OfoDrop="));
        VM.printDec(tcpOfoDrop);
        board.bcm2711.Uart.write(Magic.bytes(" OfoMerge="));
        VM.printDec(tcpOfoMerge);
        board.bcm2711.Uart.write(Magic.bytes(" DupData="));
        VM.printDec(tcpDupData);
        board.bcm2711.Uart.write(Magic.bytes(" PartialDup="));
        VM.printDec(tcpPartialDup);
        board.bcm2711.Uart.write(Magic.bytes(" WndDropBytes="));
        VM.printDec(tcpWndDrop);
        board.bcm2711.Uart.write(Magic.bytes(" DupAcksOut="));
        VM.printDec(tcpDupAcksOut);
        board.bcm2711.Uart.write(Magic.bytes(" ChallengeAcks="));
        VM.printDec(tcpChallengeAcks);
        board.bcm2711.Uart.write(Magic.bytes(" RstIgnored="));
        VM.printDec(tcpRstIgnored);
        board.bcm2711.Uart.write(Magic.bytes(" WinUpdates="));
        VM.printDec(tcpWinUpdates);
        board.bcm2711.Uart.write(Magic.bytes(" SynAckRetrans="));
        VM.printDec(tcpSynAckRetrans);
        board.bcm2711.Uart.write(Magic.bytes("\nnet: Udp InDatagrams="));
        VM.printDec(udpInDatagrams);
        board.bcm2711.Uart.write(Magic.bytes(" NoPorts="));
        VM.printDec(udpNoPorts);
        board.bcm2711.Uart.write(Magic.bytes(" InErrors="));
        VM.printDec(udpInErrors);
        board.bcm2711.Uart.write(Magic.bytes(" InCsumErrors="));
        VM.printDec(udpInCsumErrors);
        board.bcm2711.Uart.write(Magic.bytes(" RcvbufErrors="));
        VM.printDec(udpRcvbufErrors);
        board.bcm2711.Uart.write(Magic.bytes(" OutDatagrams="));
        VM.printDec(udpOutDatagrams);
        board.bcm2711.Uart.write(Magic.bytes("\n"));
    }
}
