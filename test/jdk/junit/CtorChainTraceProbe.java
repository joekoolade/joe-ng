/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-28
 */

import java.net.InetAddress;
import java.net.Socket;

/**
 * A stack trace through a constructor that a public constructor reaches by {@code this(...)}. On the Pi, a refused
 * NetDemo connect printed its uncaught trace down to {@code Socket.connect} and then {@code <unclaimed pc ... after
 * java/net/Socket.<init>>} and stopped: the frame of the PRIVATE {@code Socket(SocketAddress, SocketAddress,
 * boolean)} -- the one {@code Socket(String, int)} delegates to -- was in a code block no registry entry claimed,
 * and every frame above it was lost. STATUS.md had recorded that one unclaimed pc in every NetDemo boot.
 *
 * <p>The refused connect itself cannot be compared with the host: its frames below the constructor differ by design
 * (the host has a native {@code connect0} and {@code SocksSocketImpl}), and the VM keeps only eight frames, seven
 * of them below that constructor. So the socket arm reaches the SAME private constructor with a null address,
 * which its first statement, {@code requireNonNull}, rejects. The guest arm is the shape in a guest class, and
 * {@code Hashtable(-1)} is a public-to-public {@code this(...)} chain in java.base. Frames are printed by name, down
 * to this probe's {@code main}.
 */
public final class CtorChainTraceProbe
{
    static final class Chain
    {
        final int v;

        Chain(String s)
        {
            this(s.length(), true);
        }

        private Chain(int n, boolean strict)
        {
            if (strict)
            {
                fail(n);
            }
            this.v = n;
        }

        private static void fail(int n)
        {
            throw new IllegalStateException("n=" + n);
        }
    }

    public static void main(String[] args)
    {
        try
        {
            new Chain("abc");
        }
        catch (IllegalStateException e)
        {
            print("guest this(...) chain", e);
        }
        try
        {
            new java.util.Hashtable<String, String>(-1);
        }
        catch (IllegalArgumentException e)
        {
            print("java.base public this(...) chain", e);
        }
        try
        {
            new Socket((InetAddress) null, 1).close();
        }
        catch (Exception e)
        {
            print("Socket(null address, port)", e);
        }
        System.out.println("CtorChainTraceProbe done");
    }

    private static void print(String what, Throwable t)
    {
        System.out.println(what + ": " + t);
        StackTraceElement[] st = t.getStackTrace();
        int i = 0;
        while (i < st.length && i < 8)
        {
            System.out.println("    [" + i + "] " + st[i].getClassName() + "." + st[i].getMethodName());
            if (st[i].getClassName().equals("CtorChainTraceProbe") && st[i].getMethodName().equals("main"))
            {
                break;                                   // a batch runs main reflectively: stop at our own
            }
            i += 1;
        }
    }
}
