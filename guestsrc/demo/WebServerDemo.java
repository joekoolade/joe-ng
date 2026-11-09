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
import jdk.internal.misc.MetalStats;

/**
 * A web server on bare metal: stock {@code java.net.ServerSocket} over net.Tcp's passive open. The front page is
 * a welcome message with the current date and time (the wall clock SNTP set at WiFi bring-up), linking to the
 * VM's live statistics: {@code /memory}, {@code /gc}, {@code /locks}, {@code /threads} and {@code /net}, each
 * read from the VM's own counters ({@code jdk.internal.misc.MetalStats}) when the page is asked for. Launched from
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
        int q = path.indexOf('?');
        if (q >= 0)
        {
            path = path.substring(0, q);
        }
        String status = "200 OK";
        String body;
        if (path.equals("/") || path.equals("/index.html"))
        {
            body = page(now, n);
        }
        else if (path.equals("/memory"))
        {
            body = statsPage("Memory", now, MetalStats.MEMORY);
        }
        else if (path.equals("/gc"))
        {
            body = statsPage("Garbage collector", now, MetalStats.GC);
        }
        else if (path.equals("/locks"))
        {
            body = statsPage("Locks", now, MetalStats.LOCKS);
        }
        else if (path.equals("/threads"))
        {
            body = threadsPage(now);
        }
        else if (path.equals("/net"))
        {
            body = statsPage("Network", now, MetalStats.NET);
        }
        else
        {
            status = "404 Not Found";
            body = head("Not found") + "<h1>404</h1><p>No page at " + escape(path) + "</p>\n" + FOOT;
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

    private static final String FOOT = "</body></html>\n";

    /** The document head, the style and the link bar every page shares. */
    private static String head(String title)
    {
        return "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\"><title>" + escape(title) + " - joe-ng</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{font-family:system-ui,sans-serif;margin:3em auto;max-width:44em;padding:0 1em}"
                + "p.t{font-size:1.4em}nav a{margin-right:1em}"
                + "table{border-collapse:collapse}td,th{padding:.2em .8em;border-bottom:1px solid #ddd;text-align:left}"
                + "td.n{text-align:right;font-variant-numeric:tabular-nums}</style></head>\n"
                + "<body><nav><a href=\"/\">home</a><a href=\"/memory\">memory</a><a href=\"/gc\">gc</a>"
                + "<a href=\"/locks\">locks</a><a href=\"/threads\">threads</a><a href=\"/net\">network</a></nav>\n";
    }

    private static String page(String now, int n)
    {
        return head("Welcome")
                + "<h1>Welcome to joe-ng!</h1>\n"
                + "<p>This page is served by a Java VM running bare-metal on a Raspberry Pi 4.</p>\n"
                + "<p class=\"t\">" + escape(now) + "</p>\n"
                + "<p>Live VM statistics: <a href=\"/memory\">memory usage</a>, <a href=\"/gc\">garbage collection</a>, "
                + "<a href=\"/locks\">lock statistics</a>, <a href=\"/threads\">threads</a> and "
                + "<a href=\"/net\">network</a>.</p>\n"
                + "<p><small>request #" + n + "</small></p>\n"
                + FOOT;
    }

    /** A name/value table of one MetalStats group, read now. */
    private static String statsPage(String title, String now, int group)
    {
        long[] v = MetalStats.read(group);
        String[] names = MetalStats.names(group);
        StringBuilder sb = new StringBuilder();
        sb.append(head(title)).append("<h1>").append(escape(title)).append("</h1>\n");
        sb.append("<p><small>").append(escape(now)).append("</small></p>\n<table>\n");
        int i = 0;
        while (i < names.length)
        {
            sb.append("<tr><td>").append(escape(names[i])).append("</td><td class=\"n\">")
                    .append(grouped(v[i])).append("</td></tr>\n");
            i = i + 1;
        }
        sb.append("</table>\n").append(FOOT);
        return sb.toString();
    }

    /** The scheduler's summary, then one row per task: its Thread's name where it has one. */
    private static String threadsPage(String now)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Threads")).append("<h1>Threads</h1>\n");
        sb.append("<p><small>").append(escape(now)).append("</small></p>\n<table>\n");
        long[] v = MetalStats.read(MetalStats.THREADS);
        String[] names = MetalStats.THREADS_NAMES;
        int i = 0;
        while (i < names.length)
        {
            sb.append("<tr><td>").append(escape(names[i])).append("</td><td class=\"n\">")
                    .append(grouped(v[i])).append("</td></tr>\n");
            i = i + 1;
        }
        sb.append("</table>\n<h2>Tasks</h2>\n<table>\n");
        sb.append("<tr><th>task</th><th>name</th><th>state</th><th>priority</th><th>core</th><th>daemon</th></tr>\n");
        int count = (int) v[0];
        int t = 0;
        while (t < count)
        {
            long[] r = MetalStats.task(t);
            if (r != null && r[MetalStats.TASK_STATE] != 0)
            {
                sb.append("<tr><td class=\"n\">").append(t).append("</td><td>").append(escape(taskName(t, r)))
                        .append("</td><td>").append(stateName(r)).append("</td><td class=\"n\">")
                        .append(priority(r)).append("</td><td class=\"n\">")
                        .append(r[MetalStats.TASK_ON_CORE] < 0 ? "" : String.valueOf(r[MetalStats.TASK_ON_CORE]))
                        .append("</td><td>").append(daemon(t)).append("</td></tr>\n");
            }
            t = t + 1;
        }
        sb.append("</table>\n").append(FOOT);
        return sb.toString();
    }

    private static String taskName(int t, long[] r)
    {
        Thread th = MetalStats.taskThread(t);
        if (th != null)
        {
            String name = th.getName();                  // null for a Thread the VM wrapped itself (task 0's)
            return name != null ? name : "(unnamed Thread)";
        }
        if (r[MetalStats.TASK_IDLE] != 0)
        {
            return "(idle, core " + r[MetalStats.TASK_AFFINITY] + ")";
        }
        if (t == 0)
        {
            return "(boot)";
        }
        return "(kernel task)";
    }

    private static String stateName(long[] r)
    {
        if (r[MetalStats.TASK_DONE] != 0)
        {
            return "terminated";
        }
        int s = (int) r[MetalStats.TASK_STATE];
        String name = s >= 0 && s < MetalStats.STATE_NAMES.length ? MetalStats.STATE_NAMES[s] : "state " + s;
        if (s == 3)
        {
            long w = r[MetalStats.TASK_WAIT_ON];
            if (w == -1)
            {
                name = "waiting (Object.wait)";
            }
            else if (w == -2)
            {
                name = "blocked on a monitor";
            }
            else if (w == -3)
            {
                name = "parked";
            }
            else if (w >= 0)
            {
                name = "blocked on semaphore " + w;
            }
        }
        return name;
    }

    private static String priority(long[] r)
    {
        long p = r[MetalStats.TASK_PRIO];
        long base = r[MetalStats.TASK_BASE_PRIO];
        return p == base ? String.valueOf(p) : p + " (base " + base + ")";
    }

    private static String daemon(int t)
    {
        Thread th = MetalStats.taskThread(t);
        if (th == null)
        {
            return "";
        }
        return th.isDaemon() ? "yes" : "no";
    }

    /** {@code v} with thousands separators. */
    private static String grouped(long v)
    {
        String d = Long.toString(Math.abs(v));
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < d.length())
        {
            if (i > 0 && (d.length() - i) % 3 == 0)
            {
                sb.append(',');
            }
            sb.append(d.charAt(i));
            i = i + 1;
        }
        return v < 0 ? "-" + sb : sb.toString();
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
