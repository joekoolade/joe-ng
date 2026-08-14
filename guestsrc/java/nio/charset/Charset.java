package java.nio.charset;

/**
 * A JDK-free, minimal {@code java/nio/charset/Charset} overlay (wins by name). The stock class's
 * {@code defaultCharset()} reads the {@code file.encoding} property through provider/lookup machinery
 * that cannot run on metal; since JDK 18 the default IS UTF-8, so this returns the
 * {@link sun.nio.cs.UTF_8#INSTANCE} singleton directly — the exact object stock {@code String}'s
 * byte[]-ctor/getBytes fast paths compare against ({@code charset == UTF_8.INSTANCE}), which then decode/
 * encode in pure Java ({@code String.utf8}/{@code encodeUTF8}) with no encoder objects at all. The deep
 * fallback ({@code CharsetDecoder}/{@code CharsetEncoder}/nio buffers) is denylisted — statically
 * unreachable branches only. Field-light: the identity checks call no Charset methods.
 */
public abstract class Charset
{
    private final String name;      // @16

    protected Charset(String canonicalName, String[] aliases)
    {
        name = canonicalName;
    }

    /** Always UTF-8 on metal (matches the JDK 18+ contract; no property machinery). */
    public static Charset defaultCharset()
    {
        return sun.nio.cs.UTF_8.INSTANCE;
    }

    /**
     * Resolve a charset by name to one of the three overlay singletons, WITHOUT the stock provider/
     * {@code StandardCharsets}/ServiceLoader lookup (denylisted on metal). Reached by
     * {@code String.getBytes(String)}/{@code new String(byte[], String)} -> {@code String.lookupCharset}.
     * The returned object is the exact singleton {@code String}'s fast paths compare against by identity, so
     * encode/decode stays pure-Java. An unrecognized name throws (the cold branch; no test uses it).
     */
    public static Charset forName(String csn)
    {
        if (eq(csn, "UTF-8") || eq(csn, "UTF8") || eq(csn, "unicode-1-1-utf-8"))
        {
            return sun.nio.cs.UTF_8.INSTANCE;
        }
        if (eq(csn, "ISO-8859-1") || eq(csn, "ISO8859-1") || eq(csn, "8859_1") || eq(csn, "latin1")
                || eq(csn, "ISO_8859_1") || eq(csn, "l1") || eq(csn, "cp1252"))
        {
            return sun.nio.cs.ISO_8859_1.INSTANCE;
        }
        if (eq(csn, "US-ASCII") || eq(csn, "ASCII") || eq(csn, "ANSI_X3.4-1968") || eq(csn, "646"))
        {
            return sun.nio.cs.US_ASCII.INSTANCE;
        }
        throw new IllegalArgumentException(csn);          // UnsupportedCharsetException is denylisted on metal
    }

    /** Case-insensitive ASCII name match (avoids String.toUpperCase's locale closure). */
    private static boolean eq(String a, String b)
    {
        if (a.length() != b.length())
        {
            return false;
        }
        int i = 0;
        while (i < a.length())
        {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x >= 'A' && x <= 'Z') { x = (char) (x + 32); }
            if (y >= 'A' && y <= 'Z') { y = (char) (y + 32); }
            if (x != y)
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    public final String name()
    {
        return name;
    }

    public String toString()
    {
        return name;
    }
}
