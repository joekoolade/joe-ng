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

    public final String name()
    {
        return name;
    }

    public String toString()
    {
        return name;
    }
}
