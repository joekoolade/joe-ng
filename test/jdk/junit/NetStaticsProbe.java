/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-13
 */
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;

/**
 * What STOCK {@code sun.nio.ch.Net.<clinit>} leaves in Net's statics. It was skipped on this VM, so every one of
 * these read 0, false or null; each line is that answer checked against the host. Only values a macOS and a Linux
 * JDK agree on are printed: the poll bits are the same {@code <poll.h>} values on both, and exclusive bind is -1
 * ("not applicable") on both. IPv6 and SO_REUSEPORT availability differ by platform and stack, so they are not
 * printed. The package-private ones are read reflectively (the host needs {@code --add-opens
 * java.base/sun.nio.ch=ALL-UNNAMED}). Prints values only; the host diff is the gate.
 */
public class NetStaticsProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("POLLIN=" + sun.nio.ch.Net.POLLIN + " POLLOUT=" + sun.nio.ch.Net.POLLOUT
                + " POLLERR=" + sun.nio.ch.Net.POLLERR + " POLLHUP=" + sun.nio.ch.Net.POLLHUP
                + " POLLNVAL=" + sun.nio.ch.Net.POLLNVAL + " POLLCONN=" + sun.nio.ch.Net.POLLCONN);

        Class<?> net = Class.forName("sun.nio.ch.Net");
        System.out.println("useExclusiveBind=" + call(net, "useExclusiveBind"));
        String[] addrs = { "ANY_LOCAL_INET4ADDRESS", "ANY_LOCAL_INET6ADDRESS", "INET4_LOOPBACK_ADDRESS",
            "INET6_LOOPBACK_ADDRESS" };
        for (int i = 0; i < addrs.length; i += 1)
        {
            InetAddress a = (InetAddress) read(net, addrs[i]);
            System.out.println(addrs[i] + "=" + a.getHostAddress() + " len=" + a.getAddress().length
                    + " any=" + a.isAnyLocalAddress() + " loop=" + a.isLoopbackAddress());
        }
        System.out.println("FAST_LOOPBACK=" + read(net, "FAST_LOOPBACK"));
        System.out.println("EXTENDED_OPTIONS set=" + (read(net, "EXTENDED_OPTIONS") != null));
        System.out.println("NetStaticsProbe done");
    }

    static Object read(Class<?> c, String name) throws Exception
    {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    static Object call(Class<?> c, String name) throws Exception
    {
        Method m = c.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }
}
