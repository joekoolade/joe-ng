/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-27
 */

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A connect that fails must THROW, as stock does. The VM's connect native used to answer a failure with 0, which
 * stock NioSocketImpl reads as "in progress": it parked and called the unimplemented Net.poll, and the VM halted
 * (MEASURED on the Pi, 2026-10-27, after a TCP connect timed out). Nothing on a host or on this VM listens on
 * 127.0.0.1 port 1, so every connect here is refused on both sides -- and needs no network, so QEMU runs it.
 */
public final class ConnectFailProbe
{
    static final InetSocketAddress NOBODY = new InetSocketAddress("127.0.0.1", 1);

    public static void main(String[] args) throws Throwable
    {
        arm("Socket(host, port)", () ->
        {
            new Socket("127.0.0.1", 1).close();
            return "connected";
        });
        arm("Socket.connect", () ->
        {
            Socket s = new Socket();
            try
            {
                s.connect(NOBODY);
                return "connected";
            }
            finally
            {
                System.out.println("  after: connected=" + s.isConnected() + " closed=" + s.isClosed());
            }
        });
        arm("loopback address", () ->
        {
            new Socket(InetAddress.getLoopbackAddress(), 1).close();
            return "connected";
        });
        arm("ten refused in a row", () ->
        {
            int refused = 0;
            int i = 0;
            while (i < 10)
            {
                try
                {
                    new Socket("127.0.0.1", 1).close();
                }
                catch (java.net.ConnectException e)
                {
                    refused += 1;
                }
                i += 1;
            }
            return "refused=" + refused;
        });
        System.out.println("ConnectFailProbe done");
    }

    private interface Arm
    {
        String run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println(what + " = " + r);
    }
}
