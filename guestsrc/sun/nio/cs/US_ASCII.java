package sun.nio.cs;

/**
 * Overlay singleton for the stock {@code String} charset fast paths, which compare
 * {@code charset == US_ASCII.INSTANCE} by IDENTITY and never call a method on it. The stock class's
 * constructor pulls {@code sun.nio.cs.StandardCharsets} (generated alias maps) and
 * {@code SharedSecrets} — none of it needed for an identity token. Extends the overlay
 * {@link java.nio.charset.Charset} directly (the stock {@code Unicode} middle class is skipped).
 */
public final class US_ASCII extends java.nio.charset.Charset
{
    public static final US_ASCII INSTANCE = new US_ASCII();

    public US_ASCII()
    {
        super("US-ASCII", null);
    }
}
