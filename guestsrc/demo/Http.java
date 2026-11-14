/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-13
 */
package demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The HTTP/1.x the web server speaks: one request's head (method, target, headers), its body as a stream bounded by
 * {@code Content-Length}, and a response. One request per connection, answered with {@code Connection: close}.
 */
final class Http
{
    private Http()
    {
    }

    /** The longest head accepted; a longer one is answered 431. */
    static final int MAX_HEAD = 8192;

    /** One parsed request. */
    static final class Request
    {
        /** The request line as sent, for the log. */
        final String line;

        final String method;

        /** The target's path, without its query. */
        final String path;

        /** The query after '?', or "" when there is none. */
        final String query;

        /** Header values by lowercase name; a repeated header keeps its last value. */
        final Map<String, String> headers;

        /** Body bytes that arrived in the same reads as the head. */
        private final byte[] early;

        private final InputStream in;

        /** False when the head did not end within {@link #MAX_HEAD} bytes or before the peer stopped sending. */
        final boolean complete;

        Request(String line, String method, String path, String query, Map<String, String> headers, byte[] early,
                InputStream in, boolean complete)
        {
            this.line = line;
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.early = early;
            this.in = in;
            this.complete = complete;
        }

        String header(String lowercaseName)
        {
            return headers.get(lowercaseName);
        }

        /** {@code Content-Length}, or -1 when absent or not a number. */
        long contentLength()
        {
            String v = headers.get("content-length");
            if (v == null)
            {
                return -1L;
            }
            try
            {
                long n = Long.parseLong(v.trim());
                return n < 0L ? -1L : n;
            }
            catch (NumberFormatException e)
            {
                return -1L;
            }
        }

        /** True when the query holds {@code name=value} as one of its {@code &}-separated parts. */
        boolean queryHas(String name, String value)
        {
            for (String part : query.split("&"))
            {
                if (part.equals(name + "=" + value))
                {
                    return true;
                }
            }
            return false;
        }

        /** The body: exactly {@link #contentLength()} bytes, or fewer if the peer stops sending. */
        InputStream body()
        {
            return new Body(early, in, Math.max(0L, contentLength()));
        }
    }

    /** The early bytes, then the socket, never past the declared length. */
    private static final class Body extends InputStream
    {
        private final byte[] early;

        private int earlyPos;

        private final InputStream in;

        private long left;

        Body(byte[] early, InputStream in, long length)
        {
            this.early = early;
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException
        {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if (left <= 0L)
            {
                return -1;
            }
            int want = (int) Math.min((long) len, left);
            int r;
            if (earlyPos < early.length)
            {
                r = Math.min(want, early.length - earlyPos);
                System.arraycopy(early, earlyPos, b, off, r);
                earlyPos = earlyPos + r;
            }
            else
            {
                r = in.read(b, off, want);
                if (r < 0)
                {
                    return -1;
                }
            }
            left = left - r;
            return r;
        }
    }

    /**
     * Read one request's head, up to its blank line. Null when nothing arrived before the read timeout or the peer
     * closed. Bytes after the blank line that came in the same reads are kept as the start of the body.
     */
    static Request read(InputStream in) throws IOException
    {
        byte[] buf = new byte[MAX_HEAD];
        int n = 0;
        int end = -1;
        try
        {
            while (n < buf.length && end < 0)
            {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0)
                {
                    break;
                }
                n = n + r;
                end = headEnd(buf, n);
            }
        }
        catch (SocketTimeoutException e)
        {
            // what arrived is all there will be
        }
        if (n == 0)
        {
            return null;
        }
        boolean complete = end >= 0;
        int headLen = complete ? end : n;
        String head = new String(buf, 0, headLen, StandardCharsets.ISO_8859_1);
        String[] lines = head.split("\r\n");
        String line = lines[0];
        String[] parts = line.split(" ");
        String method = parts.length >= 1 ? parts[0] : "";
        String target = parts.length >= 2 ? parts[1] : "/";
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0)
        {
            query = target.substring(q + 1);
            target = target.substring(0, q);
        }
        Map<String, String> headers = new HashMap<>();
        int i = 1;
        while (i < lines.length)
        {
            int c = lines[i].indexOf(':');
            if (c > 0)
            {
                headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
            }
            i = i + 1;
        }
        byte[] early = new byte[0];
        if (complete)
        {
            int bodyStart = end + 4;
            early = new byte[n - bodyStart];
            System.arraycopy(buf, bodyStart, early, 0, early.length);
        }
        return new Request(line, method, target, query, headers, early, in, complete);
    }

    /** Index of the CRLFCRLF that ends the head in {@code b[0..n)}, or -1. */
    private static int headEnd(byte[] b, int n)
    {
        int i = 0;
        while (i + 3 < n)
        {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n')
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /** Write a whole response: status line, the fixed headers, and {@code body}. */
    static void respond(OutputStream out, String status, String type, byte[] body) throws IOException
    {
        String head = "HTTP/1.0 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Server: joe-ng\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** A plain-text response: {@code text} plus a newline. */
    static void text(OutputStream out, String status, String text) throws IOException
    {
        respond(out, status, "text/plain; charset=utf-8", (text + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
