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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * The stock Inflater over the VM's engine, against streams REAL zlib compressed (embedded below, made on a host
 * JDK -- this VM's Deflater writes stored blocks only, so it could not produce them): a zlib stream (level 9), a
 * raw one (level 1), one with a preset dictionary (the needsDictionary / getAdler-is-the-dictionary-id / set
 * flow), gzip through GZIPInputStream (whose trailer is read from what the Inflater leaves unconsumed), the input
 * fed a byte at a time and the output taken a byte at a time, trailing bytes after the stream (getRemaining),
 * getBytesRead/Written, getAdler, reset and reuse, ByteBuffer input and output (heap and direct), and the errors:
 * a bad header, a wrong check, the wrong dictionary, a dictionary nobody asked for, use after end. Each arm prints
 * values only; the host diff is the gate.
 */
public class InflaterProbe
{
    static final byte[] ZLIB = {
            120,-38,-107,-43,91,78,3,49,12,-123,-31,-9,-82,34,75,-120,-19,36,-114,-39,13,-105,-127,94,
            -122,14,-76,-108,22,86,-113,104,-50,44,-32,60,71,-65,100,-55,-97,-100,121,119,-100,82,126,72,
            95,-37,41,125,94,118,-49,-121,-12,116,90,-82,-57,-12,-70,-36,-46,-2,-14,-2,113,78,-53,-9,
            116,-70,63,-49,-113,-65,63,-23,101,121,75,121,51,-1,87,-62,85,33,49,58,-27,-70,110,125,
            116,-58,117,94,125,116,-123,-21,-102,-73,-47,85,-82,-85,81,71,-41,-56,78,-54,-24,-100,-21,-118,
            -39,-24,58,-41,89,-43,-47,5,-41,-87,11,-10,78,114,-111,88,-63,-112,98,36,67,-116,-112,100,
            20,98,-124,36,19,5,100,-124,52,-45,27,-52,8,-119,-58,59,-48,8,-87,-58,51,-44,8,-55,
            -90,41,-40,8,-23,-90,22,-72,17,18,78,105,-128,-93,36,28,-21,-128,-93,36,28,-115,-11,-44,
            -80,112,4,114,-108,-108,35,6,57,74,-54,-87,-128,-93,36,-100,112,-64,81,18,78,15,-64,81,
            18,78,23,-64,81,18,-114,27,-32,40,9,-89,85,-64,49,18,78,117,-64,49,18,78,-23,-128,
            99,36,-100,-110,-41,79,-118,-124,99,10,56,70,-62,-47,2,57,70,-54,-111,6,57,-58,-54,1,
            28,35,-31,-128,-115,-111,108,66,-63,-58,-126,-99,83,54,127,-125,-82,0,75,
    };
    static final byte[] RAW = {
            -107,-43,75,82,-61,48,16,-124,-31,125,78,-95,35,120,30,-46,104,-72,13,15,3,1,19,67,
            66,72,-32,-12,20,-88,125,-128,94,79,-3,-69,-81,122,-106,-3,97,46,-45,77,-7,124,-98,-53,
            -57,121,127,-1,90,-18,-114,-21,-27,80,30,-41,107,121,57,-65,-67,-97,-54,-6,53,31,-1,-49,
            -53,-19,-49,119,121,88,-97,-54,-76,91,-2,42,-31,-86,-108,28,-99,114,93,-73,62,58,-29,-70,
            -88,49,58,-25,-70,22,109,116,-107,-21,106,-42,-47,53,-78,19,31,93,112,-99,-101,-115,-82,115,
            -99,85,29,93,114,-99,-122,-116,78,72,46,-110,27,24,82,-116,76,16,35,36,25,-123,24,33,
            -55,-92,-125,-116,-112,102,122,-125,25,33,-47,68,7,26,33,-43,-60,4,53,66,-78,105,10,54,
            66,-70,-87,14,55,66,-62,-15,6,56,74,-62,-79,14,56,74,-62,-47,-36,-90,-122,-123,35,-112,
            -93,-92,28,49,-56,81,82,78,5,28,37,-31,100,0,-114,-110,112,122,2,-114,-110,112,-70,0,
            -114,-110,112,-62,0,71,73,56,-83,2,-114,-111,112,106,0,-114,-111,112,-68,3,-114,-111,112,124,
            -38,-98,20,9,-57,20,112,-116,-124,-93,14,57,70,-54,-111,6,57,-58,-54,1,28,35,-31,-128,
            -115,-111,108,82,-63,-58,72,54,-35,101,-9,11,
    };
    static final byte[] DICT = {
            120,-69,97,60,15,-6,-107,-43,-69,109,3,65,12,69,-47,92,85,-88,-124,-27,103,-122,-92,-6,
            113,96,64,112,-1,-95,1,-51,-37,92,55,37,112,35,30,-112,-17,-33,-65,-97,-25,-11,-6,-52,
            -66,76,-98,-41,-29,83,25,-85,-58,-26,116,-50,-70,-114,62,93,-80,-82,86,-99,46,89,-73,107,
            -97,110,-79,110,-51,58,-35,-122,-99,-27,-23,-118,117,25,113,-70,102,93,44,63,-35,-80,-50,-53,
            -76,119,-56,-59,-26,6,3,-59,-40,37,49,6,-55,-72,-60,24,36,51,41,50,6,-51,-12,-106,
            25,-125,104,-86,-123,-58,-96,-102,-70,-92,-58,32,-101,-19,98,99,-48,-51,74,-71,49,8,39,-73,
            -32,56,-124,19,45,56,14,-31,-8,-36,-89,-122,-62,49,-55,113,40,-57,66,114,28,-54,89,-126,
            -29,16,-50,-108,-32,56,-124,-45,35,56,14,-31,-76,9,-114,67,56,21,-126,-29,16,-50,94,-126,
            19,16,-50,42,-63,9,8,39,91,112,2,-62,-55,-21,126,82,16,78,-72,-32,4,-124,-29,41,
            57,1,-27,-40,-106,-100,-96,114,4,39,32,28,-79,9,-56,102,92,108,2,-78,-23,-76,-57,63,
            -125,-82,0,75,
    };
    static final byte[] GZ = {
            31,-117,8,0,0,0,0,0,0,-1,-107,-43,-53,81,3,65,12,69,-47,-67,-93,-24,16,70,
            82,119,75,34,27,62,3,24,6,15,-40,24,27,-94,-89,-96,-33,4,-16,-42,-86,-69,-46,41,
            105,-39,31,-26,50,-35,-108,-49,-25,-71,124,-100,-9,-9,-81,-27,-18,-72,94,14,-27,113,-67,-106,
            -105,-13,-37,-5,-87,-84,95,-13,-15,127,-68,-36,-2,124,-105,-121,-11,-87,76,-69,-27,-81,18,-82,
            74,-55,-47,41,-41,-123,-59,-24,-116,-21,-68,-7,-24,42,-41,117,-17,-93,107,92,-41,-78,-115,-82,
            -109,-99,-44,-47,57,-41,85,-77,-47,5,-41,89,-45,-47,37,-41,-87,11,-10,78,114,-111,-36,-64,
            -112,98,100,-126,24,33,-55,40,-60,8,73,38,43,-56,8,105,38,58,-52,8,-119,-58,3,104,
            -124,84,-29,19,-44,8,-55,-90,43,-40,8,-23,-90,85,-72,17,18,78,-19,-128,-93,36,28,11,
            -64,81,18,-114,-26,118,106,88,56,2,57,74,-54,17,-125,28,37,-27,52,-64,81,18,78,58,
            -32,40,9,39,18,112,-108,-124,19,2,56,74,-62,113,3,28,37,-31,-12,6,56,70,-62,105,
            14,56,70,-62,-87,1,56,70,-62,-87,-45,-10,-92,72,56,-90,-128,99,36,28,-83,-112,99,-92,
            28,-23,-112,99,-84,28,-64,49,18,14,-40,24,-55,38,21,108,-116,100,19,85,118,-65,-116,46,
            -93,37,-41,8,0,0,
    };


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

    static String sum(byte[] b, int len)
    {
        return len + "/" + Integer.toHexString(Arrays.hashCode(Arrays.copyOf(b, len)));
    }

    static String all(Inflater f, byte[] in, int inStep, int outStep) throws DataFormatException
    {
        byte[] out = new byte[4096];
        int op = 0;
        int ip = 0;
        while (!f.finished())
        {
            if (f.needsInput())
            {
                if (ip >= in.length)
                {
                    return "ran out at " + op;
                }
                int l = Math.min(inStep, in.length - ip);
                f.setInput(in, ip, l);
                ip += l;
            }
            if (f.needsDictionary())
            {
                return "needs a dictionary at " + op;
            }
            op += f.inflate(out, op, Math.min(outStep, out.length - op));
        }
        return sum(out, op) + " read=" + f.getBytesRead() + " written=" + f.getBytesWritten() + " adler="
                + Integer.toHexString(f.getAdler()) + " remaining=" + f.getRemaining();
    }

    public static void main(String[] args)
    {
        arm("zlib, whole", () -> all(new Inflater(), ZLIB, 1 << 20, 4096));
        arm("zlib, a byte in at a time", () -> all(new Inflater(), ZLIB, 1, 4096));
        arm("zlib, a byte out at a time", () -> all(new Inflater(), ZLIB, 1 << 20, 1));
        arm("raw, whole", () -> all(new Inflater(true), RAW, 1 << 20, 4096));
        arm("raw, 7 in / 13 out", () -> all(new Inflater(true), RAW, 7, 13));
        arm("trailing bytes stay unconsumed", () ->
        {
            byte[] in = Arrays.copyOf(ZLIB, ZLIB.length + 5);
            return all(new Inflater(), in, 1 << 20, 4096);
        });
        arm("a fresh zlib Inflater's adler", () -> new Inflater().getAdler() + " raw=" + new Inflater(true).getAdler());
        arm("dictionary flow", () ->
        {
            Inflater f = new Inflater();
            f.setInput(DICT);
            byte[] out = new byte[4096];
            int n = f.inflate(out);
            String first = "n=" + n + " needsDictionary=" + f.needsDictionary() + " id=" + Integer.toHexString(f.getAdler());
            f.setDictionary("the quick brown fox jumps over the lazy dog".getBytes("UTF-8"));
            int op = 0;
            while (!f.finished())
            {
                op += f.inflate(out, op, out.length - op);
            }
            return first + " then " + sum(out, op) + " adler=" + Integer.toHexString(f.getAdler());
        });
        arm("the wrong dictionary", () ->
        {
            Inflater f = new Inflater();
            f.setInput(DICT);
            f.inflate(new byte[16]);
            f.setDictionary("not it".getBytes("UTF-8"));
            return "accepted";
        });
        arm("a dictionary nobody asked for", () ->
        {
            Inflater f = new Inflater();
            f.setInput(ZLIB);
            f.setDictionary("x".getBytes("UTF-8"));
            return "accepted";
        });
        arm("gzip through GZIPInputStream", () ->
        {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(GZ)))
            {
                byte[] b = in.readAllBytes();
                return sum(b, b.length);
            }
        });
        arm("two gzip members back to back", () ->
        {
            byte[] two = Arrays.copyOf(GZ, GZ.length * 2);
            System.arraycopy(GZ, 0, two, GZ.length, GZ.length);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(two)))
            {
                byte[] b = in.readAllBytes();
                return sum(b, b.length);
            }
        });
        arm("InflaterInputStream a byte at a time", () ->
        {
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(ZLIB)))
            {
                int c = 0;
                int h = 0;
                int b = in.read();
                while (b >= 0)
                {
                    c += 1;
                    h = h * 31 + b;
                    b = in.read();
                }
                return c + "/" + Integer.toHexString(h);
            }
        });
        arm("reset and reuse", () ->
        {
            Inflater f = new Inflater();
            String one = all(f, ZLIB, 1 << 20, 4096);
            f.reset();
            return one.equals(all(f, ZLIB, 1 << 20, 4096)) + " read=" + f.getBytesRead();
        });
        arm("ByteBuffer in and out, heap", () ->
        {
            Inflater f = new Inflater();
            f.setInput(ByteBuffer.wrap(ZLIB));
            ByteBuffer out = ByteBuffer.allocate(4096);
            while (!f.finished())
            {
                f.inflate(out);
            }
            out.flip();
            byte[] b = new byte[out.remaining()];
            out.get(b);
            return sum(b, b.length);
        });
        arm("ByteBuffer in and out, direct", () ->
        {
            Inflater f = new Inflater();
            ByteBuffer in = ByteBuffer.allocateDirect(ZLIB.length);
            in.put(ZLIB).flip();
            f.setInput(in);
            ByteBuffer out = ByteBuffer.allocateDirect(4096);
            while (!f.finished())
            {
                f.inflate(out);
            }
            out.flip();
            byte[] b = new byte[out.remaining()];
            out.get(b);
            return sum(b, b.length) + " in.remaining=" + in.remaining();
        });
        arm("a bad header", () ->
        {
            Inflater f = new Inflater();
            f.setInput(RAW);
            return f.inflate(new byte[64]);
        });
        arm("a wrong check", () ->
        {
            byte[] bad = ZLIB.clone();
            bad[bad.length - 1] ^= 1;
            return all(new Inflater(), bad, 1 << 20, 4096);
        });
        arm("a corrupt block", () ->
        {
            byte[] bad = RAW.clone();
            bad[0] = (byte) 0xFF;
            return all(new Inflater(true), bad, 1 << 20, 4096);
        });
        arm("use after end", () ->
        {
            Inflater f = new Inflater();
            f.end();
            f.end();
            return f.inflate(new byte[1]);
        });
        System.out.println("InflaterProbe done");
    }
}
