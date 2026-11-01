/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-01
 */

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.Set;

/**
 * The java/net deny leftovers are gone: java/net/SocketCleanable and the rest of sun/net/ext named nothing in JDK 26,
 * and java/net/HttpConnectSocketImpl (the HTTP-proxy socket) loads now. Its <clinit> reflects on the sun/net/www HTTP
 * client, which stays denied, so this probe reaches it by NAME without initializing it; the host answers the same.
 * The other arms are the socket paths next to it: the extended-options lookup and the NO_PROXY and SOCKS impls.
 * Each arm runs on its own and prints what it got.
 */
public class NetLeftoversProbe
{
    interface Arm
    {
        String run() throws Throwable;
    }

    static void report(String what, Arm arm)
    {
        String got;
        try
        {
            got = arm.run();
        }
        catch (Throwable t)
        {
            got = "threw " + t.getClass().getName();
        }
        System.out.println(what + ": " + got);
    }

    public static void main(String[] args)
    {
        report("HttpConnectSocketImpl by name", () ->
        {
            Class<?> c = Class.forName("java.net.HttpConnectSocketImpl", false, null);
            int statics = 0;
            for (Field f : c.getDeclaredFields())
            {
                if (Modifier.isStatic(f.getModifiers()))
                {
                    statics += 1;
                }
            }
            return c.getSuperclass().getName() + " statics=" + statics;
        });
        report("supportedOptions", () ->
        {
            Set<SocketOption<?>> s = new Socket().supportedOptions();
            return "keepAlive=" + s.contains(StandardSocketOptions.SO_KEEPALIVE) + " noDelay="
                    + s.contains(StandardSocketOptions.TCP_NODELAY);
        });
        report("NO_PROXY socket", () ->
        {
            Socket s = new Socket(Proxy.NO_PROXY);
            return "connected=" + s.isConnected() + " closed=" + s.isClosed();
        });
        report("SOCKS socket", () ->
        {
            Socket s = new Socket(new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy", 1080)));
            return "connected=" + s.isConnected() + " closed=" + s.isClosed();
        });
        System.out.println("NetLeftoversProbe done");
    }
}
