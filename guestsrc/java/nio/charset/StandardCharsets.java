package java.nio.charset;

/**
 * A JDK-free {@code java.nio.charset.StandardCharsets} overlay (wins by name). The stock class's
 * initializer builds all NINE standard charsets, six of which ({@code UTF_16BE/LE}, {@code UTF_16},
 * {@code UTF_32BE/LE}, {@code UTF_32}) are {@code new sun.nio.cs.UTF_16*}/{@code UTF_32*} instances whose
 * constructors pull the {@code CharsetDecoder}/{@code CharsetEncoder} machinery joe-ng denylists. Running it
 * therefore traps; NOT running it leaves every field null, which is how a stock test doing
 * {@code s.getBytes(StandardCharsets.UTF_8)} got a NullPointerException inside {@code String.getBytes}
 * rather than any hint of the real cause (java/util/jar/Attributes/TestAttrsNL).
 *
 * <p>The three charsets metal actually supports are bound to the SAME overlay singletons
 * {@link java.nio.charset.Charset#forName} returns, which is what matters: stock {@code String}'s
 * encode/decode fast paths compare {@code charset == UTF_8.INSTANCE} by IDENTITY, so a separate-but-equal
 * object would silently take the denylisted encoder path instead.
 *
 * <p>The UTF-16/32 fields stay null deliberately — there is no encoder for them here, and a null that fails
 * at the use site is more honest than an object that cannot encode.
 */
public final class StandardCharsets
{
    private StandardCharsets()
    {
    }

    public static final Charset US_ASCII = sun.nio.cs.US_ASCII.INSTANCE;
    public static final Charset ISO_8859_1 = sun.nio.cs.ISO_8859_1.INSTANCE;
    public static final Charset UTF_8 = sun.nio.cs.UTF_8.INSTANCE;

    /** Unsupported on metal (no encoder): see the class note. */
    public static final Charset UTF_16BE = null;
    public static final Charset UTF_16LE = null;
    public static final Charset UTF_16 = null;
    public static final Charset UTF_32BE = null;
    public static final Charset UTF_32LE = null;
    public static final Charset UTF_32 = null;
}
