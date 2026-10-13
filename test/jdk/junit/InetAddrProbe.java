/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-12
 */
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * STOCK {@code java.net.InetAddress} without a network: literals, raw bytes, both families, the address-class
 * predicates, identity, and the socket-address wrapper. The hand-written overlay this replaces answered every
 * predicate false and turned any 16-byte array into an {@code Inet4Address} 0.0.0.0 -- so each line here is
 * one of those answers checked against the host. No lookup leaves the machine: every name is a literal, and
 * {@code getByAddress(host, bytes)} never resolves. Prints values only; the host diff is the gate.
 */
public class InetAddrProbe
{
    public static void main(String[] args) throws Exception
    {
        show("v4 literal", InetAddress.getByName("93.184.215.14"));
        show("v4 bytes", InetAddress.getByAddress(new byte[] { 10, 1, 2, (byte) 200 }));
        show("v4 named", InetAddress.getByAddress("example.com", new byte[] { 93, (byte) 184, (byte) 215, 14 }));
        show("loopback4", InetAddress.getByName("127.0.0.1"));
        show("any4", InetAddress.getByAddress(new byte[4]));
        show("linklocal4", InetAddress.getByName("169.254.3.4"));
        show("sitelocal4", InetAddress.getByName("192.168.1.247"));
        show("multicast4", InetAddress.getByName("224.0.0.251"));
        byte[] v6 = new byte[16];
        show("any6", InetAddress.getByAddress(v6));
        v6[15] = 1;
        show("loopback6", InetAddress.getByAddress(v6));
        show("v6 literal", InetAddress.getByName("2001:db8::8:800:200c:417a"));
        show("v6 linklocal", InetAddress.getByName("fe80::1"));
        show("v4-mapped", InetAddress.getByName("::ffff:192.0.2.1"));
        show("getLoopbackAddress", InetAddress.getLoopbackAddress());

        InetAddress a = InetAddress.getByName("10.0.0.1");
        InetAddress b = InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 });
        System.out.println("equal=" + a.equals(b) + " sameHash=" + (a.hashCode() == b.hashCode()) + " hash=" + a.hashCode());
        System.out.println("v4 vs v6 equal=" + InetAddress.getByName("0.0.0.0").equals(InetAddress.getByAddress(new byte[16])));

        try
        {
            InetAddress.getByAddress(new byte[5]);
            System.out.println("5 bytes: accepted");
        }
        catch (UnknownHostException e)
        {
            System.out.println("5 bytes: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        // Malformed LITERALS: rejected without any lookup, through jdk.internal.util.Exceptions. Two things are
        // NOT printed, each a measured gap recorded in STATUS.md rather than a difference this probe should carry:
        // the UnknownHostException TEXT (it includes the host only once VM.isBooted(), which this VM never
        // reaches -- nothing raises jdk.internal.misc.VM.initLevel; the cause's message is built the same way),
        // and the cause's toString (the Throwable overlay does not declare toString, so it answers
        // Object.toString). Which literals are rejected, and with what cause CLASS, is printed.
        String[] bad = { "[::1", "1:2:3", "0x7f.1", "[1.2.3.4]" };
        for (int i = 0; i < bad.length; i += 1)
        {
            try
            {
                InetAddress.getByName(bad[i]);
                System.out.println("bad " + bad[i] + ": accepted");
            }
            catch (UnknownHostException e)
            {
                Throwable c = e.getCause();
                System.out.println("bad " + bad[i] + ": rejected cause="
                        + (c == null ? "none" : c.getClass().getName()));
            }
        }

        InetSocketAddress sa = new InetSocketAddress(InetAddress.getByName("93.184.215.14"), 80);
        System.out.println("sockaddr=" + sa + " unresolved=" + sa.isUnresolved() + " port=" + sa.getPort());
        InetSocketAddress un = InetSocketAddress.createUnresolved("example.com", 443);
        System.out.println("unresolved=" + un + " isUnresolved=" + un.isUnresolved() + " host=" + un.getHostString());
        System.out.println("InetAddrProbe done");
    }

    static void show(String label, InetAddress a)
    {
        String kind = a instanceof Inet4Address ? "v4" : a instanceof Inet6Address ? "v6" : "plain";
        byte[] raw = a.getAddress();
        System.out.println(label + ": " + kind + " len=" + raw.length + " host=" + a.getHostAddress() + " str=" + a
                + " any=" + a.isAnyLocalAddress() + " loop=" + a.isLoopbackAddress() + " link=" + a.isLinkLocalAddress()
                + " site=" + a.isSiteLocalAddress() + " mc=" + a.isMulticastAddress());
    }
}
