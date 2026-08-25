package java.util.zip;

import java.nio.charset.Charset;

/**
 * A JDK-free {@code java.util.zip.ZipCoder} overlay (wins by name) — the package-private entry-name codec the
 * stock {@code ZipInputStream} decodes LOC/CEN names with. The stock class reaches for {@code CharsetDecoder}/
 * {@code CharBuffer} and {@code SharedSecrets}' JavaLangAccess string shortcuts, all outside the metal
 * closure; this overlay decodes through the stock {@code String(byte[], int, int)} constructor, whose UTF-8
 * fast path already runs on metal (the {@code CharsetDemo} path).
 *
 * <p>Every archive joe-ng reads is treated as UTF-8, which is what {@code jar} writes and what the zip
 * appnote's language-encoding flag means; a CP437-named legacy archive decodes as UTF-8 bytes rather than
 * being transcoded.
 */
class ZipCoder
{
    private static final ZipCoder UTF8 = new ZipCoder();

    ZipCoder()
    {
    }

    /** The codec for {@code charset} — always the UTF-8 one here. */
    public static ZipCoder get(Charset charset)
    {
        return UTF8;
    }

    /** Decode {@code ba[0..len)} as UTF-8. */
    static String toStringUTF8(byte[] ba, int len)
    {
        return new String(ba, 0, len);
    }

    String toString(byte[] ba, int off, int length)
    {
        return new String(ba, off, length);
    }

    String toString(byte[] ba, int length)
    {
        return toString(ba, 0, length);
    }

    String toString(byte[] ba)
    {
        return toString(ba, 0, ba.length);
    }

    byte[] getBytes(String s)
    {
        return s.getBytes();
    }

    boolean isUTF8()
    {
        return true;
    }
}
