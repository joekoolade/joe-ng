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

/**
 * Configure the interface once the link is up: lease an address ({@link Dhcp}), resolve the gateway ({@link Arp}),
 * PUBLISH the result to {@link Ip} -- which is what makes the stack usable: a non-zero {@code Ip.ourIp} is "the
 * network is up" to {@code net.Tcp} and the socket natives -- and ping the gateway as the first proof that IP
 * packets flow. Board-independent: the link driver calls it after its own bring-up (the WiFi join), then starts its
 * receive task. Moved out of {@code board.cyw43.Cyw43.dhcp}/{@code arpPing}.
 */
public final class NetUp
{
    private NetUp()
    {
    }

    /** True when the interface is configured and published; false leaves {@link Ip} unpublished (no network). */
    public static boolean configure()
    {
        Address mac = Link.mac();
        DataReference ip = Heap.allocData(4);
        DataReference mask = Heap.allocData(4);
        DataReference gw = Heap.allocData(4);
        DataReference dns = Heap.allocData(4);
        if (!Dhcp.lease(mac, ip.toAddress(), mask.toAddress(), gw.toAddress(), dns.toAddress()))
        {
            return false;
        }
        DataReference gwMac = Heap.allocData(8);
        if (!Arp.resolve(gw.toAddress(), gwMac.toAddress(), mac, ip.toAddress()))
        {
            board.Uart.write(Magic.bytes("net: arp no reply\n"));
            return false;
        }
        Ip.ourMac = mac;
        Ip.ourIp = ip;
        Ip.netMask = mask;
        Ip.gwIp = gw;
        Ip.dnsIp = dns;
        Ip.gwMac = gwMac;
        board.Uart.write(Magic.bytes("net: gateway mac "));
        Ip.printMac(gwMac.toAddress());
        board.Uart.putc(0x0A);
        if (Icmp.ping(gw.toAddress(), gwMac.toAddress(), mac, ip.toAddress()))
        {
            board.Uart.write(Magic.bytes("net: ping reply from "));
            Ip.printIp(gw.toAddress());
            board.Uart.putc(0x0A);
        }
        else
        {
            board.Uart.write(Magic.bytes("net: ping no reply\n"));
        }
        return true;
    }
}
