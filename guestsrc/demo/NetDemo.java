/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package demo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.HexFormat;

/**
 * M3 networking demo: a plain HTTP GET over STOCK {@code java.net.Socket} / {@code sun.nio.ch.NioSocketImpl}
 * running on bare metal, backed by joe-ng's net.Tcp stack. Resolves the host (InetAddress overlay -> WiFi
 * DNS), opens a socket, writes the request, reads the response, closes. Launched via the /etc/init manifest
 * with {@code net=1} so the OS brings the WiFi interface up first.
 *
 * <p>Every read has a timeout ({@code setSoTimeout}), which is what a real client sets: stock NioSocketImpl then
 * reads non-blocking and waits in {@code Net.poll}, so this exercises that path on every response. A second
 * connection that sends nothing must time out with stock's {@code SocketTimeoutException}.
 */
public class NetDemo
{
    public static void main(String[] args) throws Exception
    {
        String host = args.length > 0 ? args[0] : "example.com";
        Socket s = new Socket(host, 80);
        s.setSoTimeout(15000);
        System.out.println("socket connected");

        OutputStream o = s.getOutputStream();
        o.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes());
        System.out.println("GET sent -----");

        InputStream in = s.getInputStream();
        byte[] buf = new byte[2048];
        int total = 0;
        int n = in.read(buf);
        while (n > 0)
        {
            System.out.print(new String(buf, 0, n));
            total = total + n;
            n = in.read(buf);
        }
        s.close();
        System.out.println();
        System.out.print("----- http done bytes=");
        System.out.println(total);

        // A read nothing will answer: the server is still waiting for a request.
        Socket idle = new Socket(host, 80);
        idle.setSoTimeout(2000);
        long t0 = System.nanoTime();
        try
        {
            int r = idle.getInputStream().read();
            System.out.println("idle read returned " + r + " -- expected a timeout");
        }
        catch (SocketTimeoutException e)
        {
            long ms = (System.nanoTime() - t0) / 1000000L;
            System.out.println("idle read timed out: " + e.getMessage() + " after " + (ms / 100L * 100L) + "+ ms");
        }
        idle.close();

        // The interface the request went out on, as java.net reports it: its lease, broadcast and MAC should
        // match what the WiFi bring-up printed.
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        while (nis.hasMoreElements())
        {
            NetworkInterface ni = nis.nextElement();
            StringBuilder line = new StringBuilder("netif " + ni.getName());
            for (InterfaceAddress a : ni.getInterfaceAddresses())
            {
                line.append(' ').append(a);
            }
            byte[] mac = ni.getHardwareAddress();
            line.append(" mac ").append(mac == null ? "none" : HexFormat.ofDelimiter(":").formatHex(mac));
            line.append(" up=").append(ni.isUp()).append(" mtu=").append(ni.getMTU());
            System.out.println(line);
        }
    }
}
