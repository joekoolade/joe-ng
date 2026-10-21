/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-21
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * {@code java.net.NetworkInterface}: the lookups' contracts, and every interface the system lists agreeing with
 * itself under each lookup. A host and the metal list DIFFERENT interfaces (a laptop has a dozen; QEMU has no
 * WiFi and so none), so the probe prints no names, addresses or counts -- only answers that hold on any machine:
 * the misses, the argument refusals, and one {@code consistent=} verdict over whatever the list is. A failed check
 * prints which one, so a divergence names itself in the host diff.
 *
 * <p>The first line is the class literal {@code StandardSocketOptions.IP_MULTICAST_IF} carries as its type,
 * which reads null while the class is denied.
 */
public class NetIfProbe
{
    private static int bad = 0;

    public static void main(String[] args) throws Exception
    {
        Class<?> t = StandardSocketOptions.IP_MULTICAST_IF.type();
        System.out.println("IP_MULTICAST_IF type=" + (t == null ? "null" : t.getName()));

        System.out.println("getByName(no such)=" + NetworkInterface.getByName("joe-ng-no-such-if"));
        try
        {
            NetworkInterface.getByName(null);
            System.out.println("getByName(null) answered");
        }
        catch (NullPointerException e)
        {
            System.out.println("getByName(null) -> NullPointerException");
        }
        try
        {
            NetworkInterface.getByIndex(-1);
            System.out.println("getByIndex(-1) answered");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("getByIndex(-1) -> IllegalArgumentException: " + e.getMessage());
        }
        System.out.println("getByIndex(MAX)=" + NetworkInterface.getByIndex(Integer.MAX_VALUE));
        InetAddress testNet = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1});
        System.out.println("getByInetAddress(192.0.2.1)=" + NetworkInterface.getByInetAddress(testNet));
        try
        {
            NetworkInterface.getByInetAddress(null);
            System.out.println("getByInetAddress(null) answered");
        }
        catch (NullPointerException e)
        {
            System.out.println("getByInetAddress(null) -> NullPointerException");
        }

        List<NetworkInterface> all = new ArrayList<>();
        long streamed = -1;
        try
        {
            all = Collections.list(NetworkInterface.getNetworkInterfaces());
            streamed = NetworkInterface.networkInterfaces().count();
        }
        catch (SocketException e)
        {
            // "No network interfaces configured": the metal without WiFi. Both enumerations refuse alike.
            streamed = 0;
            try
            {
                NetworkInterface.networkInterfaces();
                check(false, "networkInterfaces() answered where getNetworkInterfaces() threw");
            }
            catch (SocketException again)
            {
                check(again.getMessage().equals(e.getMessage()), "the two refusals differ");
            }
        }
        check(streamed == all.size(), "stream count " + streamed + " != enumeration " + all.size());
        for (NetworkInterface ni : all)
        {
            checkInterface(ni);
        }
        System.out.println("interfaces consistent=" + (bad == 0));
        System.out.println("NetIfProbe done");
    }

    private static void checkInterface(NetworkInterface ni) throws Exception
    {
        String name = ni.getName();
        check(name != null, "a listed interface has no name");
        check(ni.equals(NetworkInterface.getByName(name)), name + ": getByName disagrees");
        check(ni.getParent() == null, name + ": a top-level interface has a parent");
        check(!ni.isVirtual(), name + ": a top-level interface is virtual");
        check(ni.toString().startsWith("name:" + name), name + ": toString " + ni);
        check(ni.hashCode() == name.hashCode(), name + ": hashCode is not the name's");
        if (ni.getIndex() > 0)
        {
            NetworkInterface byIndex = NetworkInterface.getByIndex(ni.getIndex());
            check(byIndex != null && name.equals(byIndex.getName()), name + ": getByIndex disagrees");
        }
        List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
        check(addrs.size() == ni.inetAddresses().count(), name + ": address stream count");
        for (InetAddress a : addrs)
        {
            if (a instanceof Inet4Address)
            {
                NetworkInterface owner = NetworkInterface.getByInetAddress(a);
                check(owner != null && name.equals(owner.getName()), name + ": getByInetAddress disagrees");
            }
        }
        for (InterfaceAddress b : ni.getInterfaceAddresses())
        {
            check(addrs.contains(b.getAddress()), name + ": a binding's address is not listed");
            if (b.getAddress() instanceof Inet4Address)
            {
                check(b.getNetworkPrefixLength() >= 0 && b.getNetworkPrefixLength() <= 32, name + ": prefix length");
            }
            else
            {
                check(b.getBroadcast() == null, name + ": an IPv6 binding has a broadcast");
            }
        }
        byte[] mac = ni.getHardwareAddress();
        check(mac == null || mac.length > 0, name + ": an empty hardware address");
        boolean up = ni.isUp();
        boolean loop = ni.isLoopback();
        boolean p2p = ni.isPointToPoint();
        boolean mc = ni.supportsMulticast();
        int mtu = ni.getMTU();
        check(!up || mtu != 0, name + ": up with MTU 0 (" + loop + p2p + mc + ")");
        Enumeration<NetworkInterface> subs = ni.getSubInterfaces();
        while (subs.hasMoreElements())
        {
            check(subs.nextElement().getParent() == ni, name + ": a sub-interface does not name its parent");
        }
    }

    private static void check(boolean ok, String what)
    {
        if (!ok)
        {
            bad += 1;
            System.out.println("FAILED: " + what);
        }
    }
}
