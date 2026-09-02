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
    // Country and variant are KEPT now rather than discarded. The constructors always accepted them and threw
    // them away, so getCountry() would have had to lie; storing two references is cheaper than a wrong answer,
    // and Locale carries no VM-fixed field offsets (unlike Thread), so widening it is safe.
    private final String country;
    private final String variant;

    public Locale(String language)
    {
        this(language, "", "");
    }

    public Locale(String language, String country)
    {
        this(language, country, "");
    }

    public Locale(String language, String country, String variant)
    {
        this.language = language;
        this.country = country;
        this.variant = variant;
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

    /**
     * {@code Locale.Category} -- a plain class, not an enum, for the same reason {@link
     * java.util.concurrent.TimeUnit} is: joe-ng has no enum machinery here and the stock nested enum's
     * {@code <clinit>} is unrunnable. Declared INSIDE Locale so it compiles to {@code java/util/Locale$Category},
     * the name stock callers reference; without it the overlay drops the nested type as well as the method.
     */
    public static final class Category
    {
        public static final Category DISPLAY = new Category("DISPLAY", 0);
        public static final Category FORMAT = new Category("FORMAT", 1);

        private final String name;
        private final int ordinal;

        private Category(String name, int ordinal)
        {
            this.name = name;
            this.ordinal = ordinal;
        }

        public String name()
        {
            return name;
        }

        public int ordinal()
        {
            return ordinal;
        }

        public String toString()
        {
            return name;
        }

        public static Category[] values()
        {
            return new Category[] { DISPLAY, FORMAT };
        }
    }

    /**
     * Per-category default -- the same Locale for both, since joe-ng carries no locale data and therefore has
     * nothing to distinguish a DISPLAY default from a FORMAT one. Reached from {@code java.time.format}.
     */
    public static Locale getDefault(Category category)
    {
        return DEFAULT;
    }

    /** The simple accessors. Country/variant/script are empty because a joe-ng Locale carries only a language;
     *  {@code toLanguageTag} therefore reports just that, which is a VALID BCP-47 tag rather than a truncation. */
    public String getCountry()
    {
        return country == null ? "" : country;
    }

    public String getVariant()
    {
        return variant == null ? "" : variant;
    }

    public String getScript()
    {
        return "";
    }

    public String toLanguageTag()
    {
        String c = getCountry();
        return c.isEmpty() ? getLanguage() : getLanguage() + "-" + c;
    }

    public String getDisplayName()
    {
        return toLanguageTag();
    }

    public Locale stripExtensions()
    {
        return this;                                    // no extensions are ever carried
    }

    public static Locale of(String language)
    {
        return new Locale(language);
    }

    public static Locale of(String language, String country)
    {
        return new Locale(language, country);
    }

    public static Locale of(String language, String country, String variant)
    {
        return new Locale(language, country, variant);
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
