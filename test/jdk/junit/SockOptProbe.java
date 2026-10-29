/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-29
 */

import java.lang.reflect.Field;
import java.net.SocketOption;

/**
 * The two socket classes that came off the deny list on 2026-10-29. {@code sun.nio.ch.ExtendedSocketOption} holds
 * one static, {@code SO_OOBINLINE}; denied, it read null, and {@code Socket.setOOBInline} handed that null to
 * {@code Net.setSocketOption}. Here it is read reflectively. (Setting it through a socket is NOT checked: no VM
 * native sets a socket option yet, and {@code Net.setIntOption0} throws.) {@code sun.nio.ch.Poller} is reached only
 * from a virtual thread, which this VM never has, so only LOADING it without initializing is checked -- its
 * {@code <clinit>} would start kqueue/epoll poller threads. The host needs
 * {@code --add-opens java.base/sun.nio.ch=ALL-UNNAMED}. Prints values only; the host diff is the gate.
 */
public class SockOptProbe
{
    public static void main(String[] args) throws Exception
    {
        Class<?> eso = Class.forName("sun.nio.ch.ExtendedSocketOption");
        Field f = eso.getDeclaredField("SO_OOBINLINE");
        f.setAccessible(true);
        SocketOption<?> opt = (SocketOption<?>) f.get(null);
        System.out.println("SO_OOBINLINE null=" + (opt == null));
        System.out.println("name=" + opt.name() + " type=" + opt.type().getName() + " toString=" + opt);

        Class<?> poller = Class.forName("sun.nio.ch.Poller", false, null);
        System.out.println("Poller loads uninitialized: " + poller.getName());
        System.out.println("SockOptProbe done");
    }
}
