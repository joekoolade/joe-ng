package sun.nio.cs;

/**
 * Overlay singleton for the stock {@code String} charset fast paths, which compare
 * {@code charset == ISO_8859_1.INSTANCE} by IDENTITY and never call a method on it. The stock class's
 * constructor pulls {@code sun.nio.cs.StandardCharsets} (generated alias maps) and
 * {@code SharedSecrets} — none of it needed for an identity token. Extends the overlay
 * {@link java.nio.charset.Charset} directly (the stock {@code Unicode} middle class is skipped).
 */
public final class ISO_8859_1 extends java.nio.charset.Charset
{
    public static final ISO_8859_1 INSTANCE = new ISO_8859_1();

    public ISO_8859_1()
    {
        super("ISO-8859-1", null);
    }
}
