/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The stock Deflater over the VM's compressor. A compressor's BYTES are its own business -- this one's are not
 * zlib's -- so every arm prints what any correct DEFLATE/zlib implementation must agree on: that the output
 * inflates back to the input, the byte counts and Adler-32, the zlib header, that a higher level makes
 * compressible text smaller and level 0 does not compress, that a SYNC_FLUSH makes everything so far decodable,
 * the dictionary id, FULL_FLUSH and a level change mid-stream, DeflaterOutputStream / GZIPOutputStream / a
 * DEFLATED zip entry, ByteBuffer (heap and direct), the exact bytes of an empty stream (which ARE fixed), and the
 * refusals. Values only; the host diff is the gate.
 */
public class DeflaterProbe
{
    static byte[] TEXT;

    interface Arm
    {
        Object call() throws Exception;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            System.out.println(what + " -> " + a.call());
        }
        catch (Exception e)
        {
            System.out.println(what + " -> " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static byte[] deflate(Deflater d, byte[] in)
    {
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[97];
        while (!d.finished())
        {
            o.write(buf, 0, d.deflate(buf));
        }
        return o.toByteArray();
    }

    static byte[] inflate(byte[] c, boolean nowrap, byte[] dict) throws DataFormatException
    {
        Inflater f = new Inflater(nowrap);
        if (nowrap && dict != null)
        {
            f.setDictionary(dict);
        }
        f.setInput(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[113];
        while (!f.finished())
        {
            int n = f.inflate(buf);
            if (n == 0 && f.needsDictionary())
            {
                f.setDictionary(dict);
                continue;
            }
            if (n == 0 && f.needsInput())
            {
                break;
            }
            o.write(buf, 0, n);
        }
        f.end();
        return o.toByteArray();
    }

    static String hex(byte[] b, int n)
    {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < n && i < b.length)
        {
            sb.append(String.format("%02x", b[i] & 0xFF));
            i += 1;
        }
        return sb.toString();
    }

    public static void main(String[] args)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++)
        {
            sb.append("line ").append(i).append(": the quick brown fox jumps over the lazy dog ").append(i * 7919 % 1000).append('\n');
        }
        TEXT = sb.toString().getBytes(StandardCharsets.UTF_8);
        arm("round trip at every level, zlib and raw", () ->
        {
            StringBuilder r = new StringBuilder();
            for (int lv = -1; lv <= 9; lv++)
            {
                for (boolean nowrap : new boolean[] { false, true })
                {
                    Deflater d = new Deflater(lv, nowrap);
                    byte[] c = deflate(d, TEXT);
                    boolean ok = Arrays.equals(inflate(c, nowrap, null), TEXT);
                    r.append(ok ? "+" : "X");
                    if (!ok)
                    {
                        r.append(lv).append(nowrap ? "r" : "z");
                    }
                }
            }
            return r;
        });
        arm("counts, adler and header", () ->
        {
            Deflater d = new Deflater(9);
            byte[] c = deflate(d, TEXT);
            return "read=" + d.getBytesRead() + " totalIn=" + d.getTotalIn() + " adler=" + Integer.toHexString(d.getAdler())
                    + " header=" + hex(c, 2) + " written=bytes-out:" + (d.getBytesWritten() == c.length);
        });
        arm("headers by level", () ->
        {
            StringBuilder r = new StringBuilder();
            for (int lv = 0; lv <= 9; lv++)
            {
                r.append(hex(deflate(new Deflater(lv), TEXT), 2)).append(' ');
            }
            return r.toString().trim();
        });
        arm("level 9 < level 1 < level 0, level 0 is no smaller than the input", () ->
        {
            int s0 = deflate(new Deflater(0, true), TEXT).length;
            int s1 = deflate(new Deflater(1, true), TEXT).length;
            int s9 = deflate(new Deflater(9, true), TEXT).length;
            return (s9 < s1) + " " + (s1 < s0) + " " + (s0 >= TEXT.length) + " compresses-below-a-fifth=" + (s9 * 5 < TEXT.length);
        });
        arm("strategies round trip", () ->
        {
            StringBuilder r = new StringBuilder();
            for (int st : new int[] { Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY })
            {
                Deflater d = new Deflater(6, true);
                d.setStrategy(st);
                r.append(Arrays.equals(inflate(deflate(d, TEXT), true, null), TEXT) ? "+" : "X");
            }
            return r;
        });
        arm("empty streams, exact bytes", () ->
                hex(deflate(new Deflater(6), new byte[0]), 20) + " raw=" + hex(deflate(new Deflater(6, true), new byte[0]), 20)
                + " stored=" + hex(deflate(new Deflater(0, true), new byte[0]), 20));
        arm("sync flush makes everything so far decodable", () ->
        {
            Deflater d = new Deflater(6, true);
            d.setInput(TEXT, 0, 5000);
            byte[] buf = new byte[20000];
            int n = 0;
            int k;
            while (!d.needsInput())
            {
                n += d.deflate(buf, n, buf.length - n);
            }
            do
            {
                k = d.deflate(buf, n, buf.length - n, Deflater.SYNC_FLUSH);
                n += k;
            }
            while (k > 0);
            String tail = hex(Arrays.copyOfRange(buf, n - 4, n), 4);
            Inflater f = new Inflater(true);
            f.setInput(buf, 0, n);
            byte[] out = new byte[6000];
            int got = 0;
            int g;
            while ((g = f.inflate(out, got, out.length - got)) > 0)
            {
                got += g;
            }
            return "decoded=" + got + " same=" + Arrays.equals(Arrays.copyOf(out, got), Arrays.copyOf(TEXT, 5000)) + " ends=" + tail;
        });
        arm("full flush and a level change mid-stream", () ->
        {
            Deflater d = new Deflater(1, true);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            d.setInput(TEXT, 0, 4000);
            while (!d.needsInput())
            {
                o.write(buf, 0, d.deflate(buf));
            }
            int k;
            while ((k = d.deflate(buf, 0, buf.length, Deflater.FULL_FLUSH)) > 0)
            {
                o.write(buf, 0, k);
            }
            d.setLevel(9);
            d.setInput(TEXT, 4000, TEXT.length - 4000);
            d.finish();
            while (!d.finished())
            {
                o.write(buf, 0, d.deflate(buf));
            }
            return Arrays.equals(inflate(o.toByteArray(), true, null), TEXT) + " read=" + d.getBytesRead();
        });
        arm("preset dictionary", () ->
        {
            byte[] dict = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
            Deflater d = new Deflater();
            d.setDictionary(dict);
            String id = Integer.toHexString(d.getAdler());
            byte[] c = deflate(d, TEXT);
            Inflater f = new Inflater();
            f.setInput(c);
            f.inflate(new byte[1]);
            return "id=" + id + " needsDictionary=" + f.needsDictionary() + " asks=" + Integer.toHexString(f.getAdler())
                    + " FDICT=" + ((c[1] & 0x20) != 0) + " back=" + Arrays.equals(inflate(c, false, dict), TEXT);
        });
        arm("a dictionary after input", () ->
        {
            Deflater d = new Deflater();
            d.setInput(TEXT);
            d.deflate(new byte[10]);
            d.setDictionary(new byte[] { 1, 2, 3 });
            return "accepted";
        });
        arm("DeflaterOutputStream, then InflaterInputStream", () ->
        {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(o, true))
            {
                out.write(TEXT, 0, 1000);
                out.flush();
                out.write(TEXT, 1000, TEXT.length - 1000);
            }
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(o.toByteArray())))
            {
                return Arrays.equals(in.readAllBytes(), TEXT) + " smaller=" + (o.size() * 3 < TEXT.length);
            }
        });
        arm("GZIPOutputStream, then GZIPInputStream", () ->
        {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (OutputStream out = new GZIPOutputStream(o))
            {
                out.write(TEXT);
            }
            byte[] g = o.toByteArray();
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(g)))
            {
                return Arrays.equals(in.readAllBytes(), TEXT) + " magic=" + hex(g, 3);
            }
        });
        arm("a DEFLATED zip entry is compressed", () ->
        {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (ZipOutputStream zo = new ZipOutputStream(o))
            {
                zo.putNextEntry(new ZipEntry("t.txt"));
                zo.write(TEXT);
            }
            try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(o.toByteArray())))
            {
                ZipEntry e = zi.getNextEntry();
                byte[] b = zi.readAllBytes();
                e = zi.getNextEntry();
                return Arrays.equals(b, TEXT) + " archive-smaller-than-a-third=" + (o.size() * 3 < TEXT.length);
            }
        });
        arm("ByteBuffer in and out, heap and direct", () ->
        {
            Deflater d = new Deflater();
            ByteBuffer in = ByteBuffer.allocateDirect(TEXT.length);
            in.put(TEXT).flip();
            d.setInput(in);
            d.finish();
            ByteBuffer out = ByteBuffer.allocate(TEXT.length);
            while (!d.finished())
            {
                d.deflate(out);
            }
            out.flip();
            byte[] c = new byte[out.remaining()];
            out.get(c);
            return Arrays.equals(inflate(c, false, null), TEXT) + " in.remaining=" + in.remaining();
        });
        arm("an invalid level", () -> new Deflater(10));
        arm("setLevel out of range", () ->
        {
            new Deflater().setLevel(12);
            return "set";
        });
        arm("use after end", () ->
        {
            Deflater d = new Deflater();
            d.end();
            return d.deflate(new byte[4]);
        });
        System.out.println("DeflaterProbe done");
    }
}
