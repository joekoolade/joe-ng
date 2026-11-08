/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-07
 */
package demo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

/**
 * A web server on bare metal: stock {@code java.net.ServerSocket} over net.Tcp's passive open. Every page is a
 * welcome message with the current date and time (the wall clock SNTP set at WiFi bring-up). Launched from
 * /etc/init with {@code net=1}; {@code args=} may give the port (default 80) and a zone id (default UTC), e.g.
 * {@code args=80 America/Chicago}.
 *
 * <p>One connection at a time, each answered and closed ({@code Connection: close}). An accepted socket has a read
 * timeout, so a browser's speculative connection that never sends a request cannot hold the server up.
 */
public class WebServerDemo
{
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss z");

    public static void main(String[] args) throws Exception
    {
        int port = 80;
        if (args.length > 0)
        {
            port = Integer.parseInt(args[0]);
        }
        ZoneId zone = ZoneId.of("UTC");
        if (args.length > 1)
        {
            zone = ZoneId.of(args[1]);
        }
        // The first format loads the CLDR locale data (~3.4 MB of bodies to compile, ~2.6 s on the Pi): do it
        // before listening, so the first visitor does not wait for it -- or time out, as curl's 10 s did.
        System.out.println("web: it is " + ZonedDateTime.now(zone).format(WHEN));
        ServerSocket server = new ServerSocket(port);
        System.out.println("web: listening on port " + server.getLocalPort());
        printUrls(server.getLocalPort());

        System.out.println("web: waiting for connections");
        int served = 0;
        while (true)
        {
            Socket s = server.accept();
            served = served + 1;
            try
            {
                serve(s, zone, served);
            }
            catch (Exception e)
            {
                System.out.println("web: #" + served + " " + e);
            }
            finally
            {
                s.close();
            }
        }
    }

    /** Read one request's head, log its first line, and answer it. */
    private static void serve(Socket s, ZoneId zone, int n) throws Exception
    {
        s.setSoTimeout(5000);
        String line = readHead(s.getInputStream());
        if (line == null)
        {
            System.out.println("web: #" + n + " " + s.getInetAddress().getHostAddress() + " sent no request");
            return;
        }
        System.out.println("web: #" + n + " " + s.getInetAddress().getHostAddress() + " " + line);
        String path = "/";
        String[] parts = line.split(" ");
        if (parts.length >= 2)
        {
            path = parts[1];
        }
        String now = ZonedDateTime.now(zone).format(WHEN);
        String status;
        String body;
        if (path.equals("/") || path.startsWith("/?") || path.equals("/index.html"))
        {
            status = "200 OK";
            body = page(now, n);
        }
        else
        {
            status = "404 Not Found";
            body = "<!doctype html><title>Not found</title><h1>404</h1><p>No page at " + escape(path) + "</p>\n";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Server: joe-ng\r\n"
                + "\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String page(String now, int n)
    {
        return "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\"><title>joe-ng</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{font-family:system-ui,sans-serif;margin:3em auto;max-width:36em;padding:0 1em}"
                + "p.t{font-size:1.4em}</style></head>\n"
                + "<body><h1>Welcome to joe-ng!</h1>\n"
                + "<p>This page is served by a Java VM running bare-metal on a Raspberry Pi 4.</p>\n"
                + "<p class=\"t\">" + escape(now) + "</p>\n"
                + "<p><small>request #" + n + "</small></p>\n"
                + "</body></html>\n";
    }

    /**
     * The request line, after reading the head up to its blank line (or what arrived before the peer stopped
     * sending); null when nothing arrived before the read timeout or the peer closed.
     */
    private static String readHead(InputStream in) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1024];
        try
        {
            while (sb.length() < 8192 && sb.indexOf("\r\n\r\n") < 0)
            {
                int r = in.read(buf);
                if (r < 0)
                {
                    break;
                }
                sb.append(new String(buf, 0, r, StandardCharsets.ISO_8859_1));
            }
        }
        catch (SocketTimeoutException e)
        {
            // what arrived is all there will be
        }
        if (sb.length() == 0)
        {
            return null;
        }
        int eol = sb.indexOf("\r\n");
        return eol < 0 ? sb.toString() : sb.substring(0, eol);
    }

    private static String escape(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Print the URL a browser on the network should open: one per IPv4 address java.net reports (the stack is IPv4-only). */
    private static void printUrls(int port) throws Exception
    {
        String suffix = port == 80 ? "/" : ":" + port + "/";
        Enumeration<NetworkInterface> nis;
        try
        {
            nis = NetworkInterface.getNetworkInterfaces();
        }
        catch (SocketException e)
        {
            System.out.println("web: no network interface yet (" + e.getMessage() + ")");
            return;
        }
        while (nis.hasMoreElements())
        {
            NetworkInterface ni = nis.nextElement();
            for (InterfaceAddress ia : ni.getInterfaceAddresses())
            {
                InetAddress a = ia.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isAnyLocalAddress())
                {
                    System.out.println("web: open http://" + a.getHostAddress() + suffix);
                }
            }
        }
    }
}
