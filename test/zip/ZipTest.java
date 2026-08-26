package zip;

import harness.T;

import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Validates the JDK-free {@link zip} engine — {@link Inflate}, {@link Huff}, {@link ZipDir}, {@link Crc32} — on
 * the seed JVM against {@code java.util.zip} itself: archives are built with the JDK's {@code ZipOutputStream}/
 * {@code JarOutputStream} and {@code Deflater}, then read back with our code and compared byte-for-byte. The
 * same classes compile into the bare-metal image, so agreement here means jar/zip reading is correct before it
 * ever runs on the metal (the {@code crypto.CryptoTest} pattern).
 *
 * <p>Run: {@code java -cp out zip.ZipTest}
 */
public final class ZipTest
{
    public static void main(String[] args) throws Exception
    {
        crc32();
        inflateRoundTrip();
        inflateStreaming();
        deflateRoundTrip();
        zipDirectory();
        jarArchive();
        malformed();
        T.summary("zip");
    }

    // ---------------------------------------------------------------- CRC-32

    private static void crc32()
    {
        crcOne("empty", new byte[0]);
        crcOne("abc", ascii("abc"));
        crcOne("check", ascii("123456789"));           // the standard CRC-32 check value, 0xCBF43926
        crcOne("pseudo-random 1000", pseudo(1000, 7));
        T.eq("Crc32 check value", 0xCBF43926L, Crc32.of(ascii("123456789"), 0, 9) & 0xFFFFFFFFL);

        // Incremental single-byte updates must agree with the whole-array form.
        byte[] data = pseudo(300, 11);
        int running = 0;
        int i = 0;
        while (i < data.length)
        {
            running = Crc32.updateByte(running, data[i] & 0xFF);
            i += 1;
        }
        T.eq("Crc32 byte-at-a-time", Crc32.of(data, 0, data.length) & 0xFFFFFFFFL, running & 0xFFFFFFFFL);
    }

    private static void crcOne(String name, byte[] data)
    {
        CRC32 jdk = new CRC32();
        jdk.update(data, 0, data.length);
        T.eq("Crc32 " + name, jdk.getValue(), Crc32.of(data, 0, data.length) & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- raw DEFLATE

    /** Every deflate strategy/level our decoder must handle, one-shot: stored, fixed and dynamic blocks. */
    private static void inflateRoundTrip() throws Exception
    {
        oneShot("empty", new byte[0], Deflater.DEFAULT_COMPRESSION);
        oneShot("short literal", ascii("hello from joe-ng"), Deflater.DEFAULT_COMPRESSION);
        oneShot("no compression (stored blocks)", pseudo(5000, 3), Deflater.NO_COMPRESSION);
        oneShot("highly repetitive", repeat(ascii("joe-ng "), 4000), Deflater.BEST_COMPRESSION);
        oneShot("incompressible", pseudo(20000, 5), Deflater.BEST_COMPRESSION);
        oneShot("past the 32K window", repeat(ascii("abcdefghij"), 20000), Deflater.BEST_COMPRESSION);
        oneShot("mixed", mixed(70000), Deflater.DEFAULT_COMPRESSION);
        oneShot("huffman only", mixed(30000), HUFFMAN_ONLY);
    }

    /** A pseudo-level for {@link #deflate}: compress with {@code HUFFMAN_ONLY} (no LZ matches at all). */
    private static final int HUFFMAN_ONLY = -99;

    private static void oneShot(String name, byte[] data, int level) throws Exception
    {
        byte[] deflated = deflate(data, level);
        byte[] out = new byte[data.length];
        int n = Inflate.inflate(deflated, 0, deflated.length, out, 0, out.length);
        T.eq("inflate " + name + " length", data.length, n);
        T.eqBytes("inflate " + name, data, out);
    }

    /**
     * The streaming contract the {@code java.util.zip.Inflater} overlay depends on: compressed bytes arrive in
     * small chunks and output is drained in small chunks, so the decoder must stop and resume mid-block,
     * mid-symbol and mid-LZ-copy without losing a bit.
     */
    private static void inflateStreaming() throws Exception
    {
        stream("1-byte in, 1-byte out", mixed(9000), 1, 1);
        stream("3-byte in, 7-byte out", mixed(40000), 3, 7);
        stream("13-byte in, 512-byte out", repeat(ascii("abcdefghij"), 8000), 13, 512);
        stream("whole input, 5-byte out", mixed(20000), Integer.MAX_VALUE, 5);
    }

    private static void stream(String name, byte[] data, int inChunk, int outChunk) throws Exception
    {
        byte[] deflated = deflate(data, Deflater.DEFAULT_COMPRESSION);
        Inflate z = new Inflate();
        z.reset();
        byte[] out = new byte[outChunk];
        byte[] got = new byte[data.length + 16];
        int have = 0;
        int fed = 0;
        int guard = 0;
        while (!z.finished() && !z.failed() && guard < 10_000_000)
        {
            guard += 1;
            int n = z.inflate(out, 0, outChunk);
            if (n > 0)
            {
                int k = 0;
                while (k < n && have < got.length)
                {
                    got[have] = out[k];
                    have += 1;
                    k += 1;
                }
                continue;
            }
            if (fed >= deflated.length)
            {
                break;                                 // no output and no input left: done or stuck
            }
            int chunk = Math.min(inChunk, deflated.length - fed);
            z.input(deflated, fed, chunk);
            fed += chunk;
        }
        T.check("stream " + name + " finished", z.finished());
        T.eq("stream " + name + " length", data.length, have);
        byte[] trimmed = new byte[have];
        System.arraycopy(got, 0, trimmed, 0, have);
        T.eqBytes("stream " + name, data, trimmed);
        T.eq("stream " + name + " bytesWritten", data.length, z.bytesWritten());
    }

    // ---------------------------------------------------------------- DEFLATE (compress)

    /**
     * Our compressor's output decoded by the JDK's OWN {@code Inflater}. That direction is the one that
     * matters: a stored-block stream is only useful if a conforming inflater accepts the framing, and
     * checking it against our own {@link Inflate} would only prove the two agree with each other.
     */
    private static void deflateRoundTrip() throws Exception
    {
        deflateOne("empty", new byte[0]);
        deflateOne("short", ascii("hello from joe-ng"));
        deflateOne("one block boundary", pseudo(8192, 3));
        deflateOne("just over a block", pseudo(8193, 4));
        deflateOne("many blocks", pseudo(70000, 5));
        deflateOne("repetitive", repeat(ascii("joe-ng "), 4000));
    }

    private static void deflateOne(String name, byte[] data) throws Exception
    {
        // raw (what a zip entry stores), decoded by the JDK
        byte[] raw = compress(data, true, 1);
        T.eqBytes("deflate raw " + name, data, jdkInflate(raw, true, data.length));
        // zlib-wrapped, decoded by the JDK -- exercises the header and the Adler-32 trailer
        byte[] wrapped = compress(data, false, 1);
        T.eqBytes("deflate zlib " + name, data, jdkInflate(wrapped, false, data.length));
        // and a one-byte-at-a-time drain, since the caller's buffer size must not affect framing
        byte[] dribbled = compress(data, true, 1_000_000);
        T.eqBytes("deflate drip " + name, raw, dribbled);
        // finally our own inflater, closing the loop both ways
        byte[] back = new byte[data.length];
        int n = Inflate.inflate(raw, 0, raw.length, back, 0, back.length);
        T.eq("deflate self " + name + " length", data.length, n);
        T.eqBytes("deflate self " + name, data, back);
    }

    /** Compress with our Deflate, draining at most {@code chunk} bytes per call. */
    private static byte[] compress(byte[] data, boolean nowrap, int chunk)
    {
        Deflate d = new Deflate();
        d.reset(nowrap);
        d.input(data, 0, data.length);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[chunk < 4096 ? chunk : 4096];
        int guard = 0;
        while (!d.finished() && guard < 10_000_000)
        {
            guard += 1;
            int n = d.deflate(buf, 0, chunk < buf.length ? chunk : buf.length);
            if (n <= 0)
            {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decode with the JDK's Inflater — the reference implementation. */
    private static byte[] jdkInflate(byte[] compressed, boolean nowrap, int expected) throws Exception
    {
        java.util.zip.Inflater inf = new java.util.zip.Inflater(nowrap);
        inf.setInput(compressed);
        byte[] out = new byte[expected == 0 ? 1 : expected];
        int total = 0;
        while (!inf.finished() && total < out.length)
        {
            int n = inf.inflate(out, total, out.length - total);
            if (n == 0)
            {
                break;
            }
            total += n;
        }
        inf.end();
        byte[] exact = new byte[total];
        System.arraycopy(out, 0, exact, 0, total);
        return exact;
    }

    // ---------------------------------------------------------------- zip archives

    private static void zipDirectory() throws Exception
    {
        byte[] hello = ascii("hello from a zip\n");
        byte[] big = mixed(50000);
        byte[] stored = ascii("this entry is STORED, not deflated");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bytes);
        zos.putNextEntry(new ZipEntry("hello.txt"));
        zos.write(hello);
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("dir/"));
        zos.closeEntry();
        zos.putNextEntry(new ZipEntry("dir/big.bin"));
        zos.write(big);
        zos.closeEntry();
        ZipEntry raw = new ZipEntry("raw.txt");
        raw.setMethod(ZipEntry.STORED);
        raw.setSize(stored.length);
        raw.setCompressedSize(stored.length);
        CRC32 crc = new CRC32();
        crc.update(stored);
        raw.setCrc(crc.getValue());
        zos.putNextEntry(raw);
        zos.write(stored);
        zos.closeEntry();
        zos.close();

        ZipDir dir = new ZipDir();
        T.check("ZipDir open", dir.open(bytes.toByteArray()));
        T.eq("ZipDir count", 4, dir.count());
        T.eqStr("ZipDir first name", "hello.txt", new String(dir.name(0), "UTF-8"));
        T.check("ZipDir directory entry", dir.isDirectory(dir.find(ascii("dir/"))));
        T.check("ZipDir file is not a directory", !dir.isDirectory(dir.find(ascii("hello.txt"))));
        T.eqBytes("ZipDir deflated entry", hello, dir.read(ascii("hello.txt")));
        T.eqBytes("ZipDir large deflated entry", big, dir.read(ascii("dir/big.bin")));
        T.eqBytes("ZipDir stored entry", stored, dir.read(ascii("raw.txt")));
        T.eq("ZipDir missing entry", -1, dir.find(ascii("nope.txt")));
        T.eq("ZipDir stored method", ZipDir.STORED, dir.method(dir.find(ascii("raw.txt"))));
        T.eq("ZipDir deflated method", ZipDir.DEFLATED, dir.method(dir.find(ascii("dir/big.bin"))));
        T.eq("ZipDir size", big.length, dir.size(dir.find(ascii("dir/big.bin"))));

        // Every entry's CRC must match what the archive recorded — end-to-end proof of the inflate.
        int i = 0;
        while (i < dir.count())
        {
            byte[] data = dir.read(i);
            if (data != null)
            {
                T.eq("ZipDir crc " + new String(dir.name(i), "UTF-8"),
                        dir.crc(i) & 0xFFFFFFFFL, Crc32.of(data, 0, data.length) & 0xFFFFFFFFL);
            }
            i += 1;
        }
    }

    /** The real target: a jar built the way javac's jars are, read as class bytes. */
    private static void jarArchive() throws Exception
    {
        byte[] classBytes = new byte[4096];            // a stand-in classfile: compressible but not trivial
        int i = 0;
        while (i < classBytes.length)
        {
            classBytes[i] = (byte) ((i * 31 + (i >> 4)) & 0xFF);
            i += 1;
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "app/Main");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JarOutputStream jos = new JarOutputStream(bytes, mf);
        jos.putNextEntry(new ZipEntry("app/Main.class"));
        jos.write(classBytes);
        jos.closeEntry();
        jos.close();

        ZipDir jar = new ZipDir();
        T.check("jar open", jar.open(bytes.toByteArray()));
        T.check("jar has a manifest", jar.find(ascii("META-INF/MANIFEST.MF")) >= 0);
        T.eqBytes("jar class entry", classBytes, jar.read(ascii("app/Main.class")));
        byte[] manifest = jar.read(ascii("META-INF/MANIFEST.MF"));
        T.check("jar manifest names the main class",
                new String(manifest, "UTF-8").contains("Main-Class: app/Main"));
    }

    // ---------------------------------------------------------------- robustness

    /** Malformed input must be reported, never thrown — image code has no exception budget here. */
    private static void malformed() throws Exception
    {
        Inflate z = new Inflate();
        z.reset();
        byte[] junk = { (byte) 0x07, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        z.input(junk, 0, junk.length);
        int n = z.inflate(new byte[64], 0, 64);
        T.check("malformed stream reports failure", z.failed() || n == 0);

        ZipDir dir = new ZipDir();
        T.check("not a zip", !dir.open(ascii("this is definitely not a zip archive")));
        T.eq("not a zip has no entries", 0, dir.count());

        // A truncated archive: the EOCD is gone, so the directory must refuse it rather than mis-read.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bytes);
        zos.putNextEntry(new ZipEntry("a.txt"));
        zos.write(ascii("aaaa"));
        zos.closeEntry();
        zos.close();
        byte[] full = bytes.toByteArray();
        byte[] cut = new byte[full.length - 8];
        System.arraycopy(full, 0, cut, 0, cut.length);
        T.check("truncated zip", !new ZipDir().open(cut));
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] deflate(byte[] data, int level) throws Exception
    {
        Deflater d = new Deflater(level == HUFFMAN_ONLY ? Deflater.DEFAULT_COMPRESSION : level, true);
        if (level == HUFFMAN_ONLY)
        {
            d.setStrategy(Deflater.HUFFMAN_ONLY);
        }
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished())
        {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    private static byte[] ascii(String s)
    {
        byte[] b = new byte[s.length()];
        int i = 0;
        while (i < s.length())
        {
            b[i] = (byte) s.charAt(i);
            i += 1;
        }
        return b;
    }

    private static byte[] repeat(byte[] unit, int times)
    {
        byte[] out = new byte[unit.length * times];
        int i = 0;
        while (i < times)
        {
            System.arraycopy(unit, 0, out, i * unit.length, unit.length);
            i += 1;
        }
        return out;
    }

    /** A deterministic pseudo-random filler — incompressible enough to force literal-heavy blocks. */
    private static byte[] pseudo(int len, int seed)
    {
        byte[] out = new byte[len];
        int state = seed | 1;
        int i = 0;
        while (i < len)
        {
            state = state * 1103515245 + 12345;
            out[i] = (byte) (state >>> 16);
            i += 1;
        }
        return out;
    }

    /** Repetitive runs interleaved with noise — exercises long matches, far distances and literal runs. */
    private static byte[] mixed(int len)
    {
        byte[] out = new byte[len];
        int state = 12345;
        int i = 0;
        while (i < len)
        {
            state = state * 1103515245 + 12345;
            if ((state >>> 28) < 6)
            {
                int run = 20 + ((state >>> 8) & 0x1FF);
                int b = (state >>> 3) & 0xFF;
                int k = 0;
                while (k < run && i < len)
                {
                    out[i] = (byte) b;
                    i += 1;
                    k += 1;
                }
            }
            else
            {
                out[i] = (byte) (state >>> 16);
                i += 1;
            }
        }
        return out;
    }
}
