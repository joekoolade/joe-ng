/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-11-19
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Real TCP over a real link, in the shapes that break a stack: a large download (the receive window and the RX
 * queues full), a large upload (the transmit ring full), several connections in a row (ports and connection state
 * reused), and two downloads at once on two threads. Talks HTTP/1.0 over a raw {@link Socket} to the server
 * {@code scripts/Regress.java} runs ({@code NetTcpServer}): {@code GET /blob/<n>} answers {@code n} bytes of a
 * fixed pattern and {@code POST /sink} answers the CRC-32 and length of what it received.
 *
 * <p>Arguments: the server's host and port -- {@code 10.0.2.2 <port>} on QEMU virt (user networking maps 10.0.2.2
 * to the host's loopback), {@code 127.0.0.1 <port>} for the host control. Nothing printed depends on either, so one
 * expected output serves both. Every check prints its verdict; a mismatch says what it got.
 */
public final class NetTcpProbe
{
    private static String host;
    private static int port;

    public static void main(String[] args) throws Exception
    {
        host = args[0];
        port = Integer.parseInt(args[1]);
        download("small download", 1000);
        download("large download", 600_000);
        upload("large upload", 400_000);
        int ok = 0;
        int i = 0;
        while (i < 8)
        {
            if (fetch(5000 + i * 333) == expectedCrc(5000 + i * 333))
            {
                ok = ok + 1;
            }
            i = i + 1;
        }
        System.out.println("eight in a row: " + ok + "/8 ok");
        long[] got = new long[2];
        Thread a = new Thread(() -> got[0] = fetchOrFail(300_000));
        Thread b = new Thread(() -> got[1] = fetchOrFail(250_000));
        a.start();
        b.start();
        a.join();
        b.join();
        System.out.println("two at once: " + (got[0] == expectedCrc(300_000) ? "ok" : "BAD") + " "
                + (got[1] == expectedCrc(250_000) ? "ok" : "BAD"));
        System.out.println("NetTcpProbe done");
    }

    /** The pattern the server sends: byte i of a blob is {@code (i * 31 + 7) & 0xFF}. */
    static byte[] pattern(int n)
    {
        byte[] b = new byte[n];
        int i = 0;
        while (i < n)
        {
            b[i] = (byte) (i * 31 + 7);
            i = i + 1;
        }
        return b;
    }

    static long expectedCrc(int n)
    {
        CRC32 c = new CRC32();
        c.update(pattern(n));
        return c.getValue();
    }

    private static void download(String what, int n) throws IOException
    {
        long crc = fetch(n);
        System.out.println(what + ": " + n + " bytes " + (crc == expectedCrc(n) ? "ok" : "BAD crc=" + Long.toHexString(crc)));
    }

    private static long fetchOrFail(int n)
    {
        try
        {
            return fetch(n);
        }
        catch (IOException e)
        {
            System.out.println("fetch " + n + " threw " + e);
            return -1L;
        }
    }

    /** GET /blob/n; the CRC-32 of the body, or -1 if the status or the length is wrong. */
    private static long fetch(int n) throws IOException
    {
        try (Socket s = new Socket(host, port))
        {
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /blob/" + n + " HTTP/1.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] body = readResponse(s.getInputStream());
            if (body == null || body.length != n)
            {
                System.out.println("  GET " + n + ": got " + (body == null ? "no 200" : body.length + " bytes"));
                return -1L;
            }
            CRC32 c = new CRC32();
            c.update(body);
            return c.getValue();
        }
    }

    private static void upload(String what, int n) throws IOException
    {
        byte[] data = pattern(n);
        try (Socket s = new Socket(host, port))
        {
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write(("POST /sink HTTP/1.0\r\nContent-Length: " + n + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            int off = 0;
            while (off < n)
            {
                int len = Math.min(8192, n - off);           // many writes, as a program streaming a file would
                out.write(data, off, len);
                off = off + len;
            }
            out.flush();
            byte[] body = readResponse(s.getInputStream());
            String want = "crc=" + Long.toHexString(expectedCrc(n)) + " len=" + n;
            String got = body == null ? "no 200" : new String(body, StandardCharsets.US_ASCII).strip();
            System.out.println(what + ": " + n + " bytes " + (got.equals(want) ? "ok" : "BAD server saw " + got));
        }
    }

    /** Read an HTTP/1.0 response to EOF; the body, or null unless the status is 200. */
    private static byte[] readResponse(InputStream in) throws IOException
    {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r = in.read(buf);
        while (r > 0)
        {
            all.write(buf, 0, r);
            r = in.read(buf);
        }
        byte[] b = all.toByteArray();
        int i = 0;
        while (i + 3 < b.length)
        {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n')
            {
                String head = new String(b, 0, i, StandardCharsets.US_ASCII);
                if (!head.startsWith("HTTP/1.0 200") && !head.startsWith("HTTP/1.1 200"))
                {
                    return null;
                }
                byte[] body = new byte[b.length - i - 4];
                System.arraycopy(b, i + 4, body, 0, body.length);
                return body;
            }
            i = i + 1;
        }
        return null;
    }
}
