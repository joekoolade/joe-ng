package java.util;

/**
 * A JDK-free, minimal {@code java/util/Locale} overlay: the metal has no system properties, so the stock
 * {@code Locale.getDefault()} (which runs {@code initDefault()} -> {@code GetPropertyAction}/{@code sun.util}
 * property + charset machinery -> a large native closure) is unrunnable. Case conversion only needs three
 * members from Locale, so we substitute the whole class (wins by name over the stock one, like the mini
 * exception overlays):
 *   - {@link #getDefault()} — what {@code String.toLowerCase()}/{@code toUpperCase()} call to pick a locale;
 *   - {@link #getLanguage()} — {@code StringLatin1.toLowerCase} reads it to special-case tr/az/lt (Turkic
 *     dotted-I). Returning "en" keeps every string on the plain per-char {@code CharacterDataLatin1} path;
 *   - {@link #ENGLISH} — {@code java.util.regex.Pattern} reads this static for CASE_INSENSITIVE folding.
 *
 * <p>Field-light on purpose: reached code passes a Locale around opaquely and only calls {@code getLanguage()}.
 * The default IS {@code ENGLISH}, so tr/az/lt never triggers and {@code ConditionalSpecialCasing} (which drags
 * in {@code java/text/BreakIterator}) stays unreached for ASCII/Latin1 text.
 */
public final class Locale
{
    // Referenced by regex Pattern (getstatic Locale.ENGLISH) for CASE_INSENSITIVE case folding. Also our default.
    public static final Locale ENGLISH = new Locale("en");
    public static final Locale ROOT = new Locale("");
    public static final Locale US = new Locale("en");

    private static final Locale DEFAULT = ENGLISH;

    private final String language;

    public Locale(String language)
    {
        this.language = language;
    }

    public Locale(String language, String country)
    {
        this.language = language;
    }

    public Locale(String language, String country, String variant)
    {
        this.language = language;
    }

    public static Locale getDefault()
    {
        return DEFAULT;
    }

    public String getLanguage()
    {
        return language;
    }
}
