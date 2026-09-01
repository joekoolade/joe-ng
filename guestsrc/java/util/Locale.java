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

    /**
     * {@code setDefault} is accepted and IGNORED, and {@code forLanguageTag} parses only the language subtag.
     * joe-ng carries no locale data at all -- there is nothing for a different default to select, and the one
     * place a Locale is actually read ({@code Pattern}'s CASE_INSENSITIVE folding) wants ENGLISH, which is
     * already the default. Accepting the call is what matters: a name-winning overlay that omits it drops the
     * member, and the call traps instead of being harmlessly inert.
     */
    public static void setDefault(Locale l)
    {
    }

    /** BCP-47 tag -> Locale, language subtag only (everything before the first '-'). */
    public static Locale forLanguageTag(String tag)
    {
        if (tag == null || tag.isEmpty())
        {
            return ROOT;
        }
        int dash = tag.indexOf('-');
        return new Locale(dash < 0 ? tag : tag.substring(0, dash));
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
