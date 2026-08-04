package sun.nio.cs;

/**
 * Overlay singleton for the stock {@code String} charset fast paths, which compare
 * {@code charset == UTF_8.INSTANCE} by IDENTITY and never call a method on it. The stock class's
 * constructor pulls {@code sun.nio.cs.StandardCharsets} (generated alias maps) and
 * {@code SharedSecrets} — none of it needed for an identity token. Extends the overlay
 * {@link java.nio.charset.Charset} directly (the stock {@code Unicode} middle class is skipped).
 */
public final class UTF_8 extends java.nio.charset.Charset
{
    public static final UTF_8 INSTANCE = new UTF_8();

    public UTF_8()
    {
        super("UTF-8", null);
    }
}
