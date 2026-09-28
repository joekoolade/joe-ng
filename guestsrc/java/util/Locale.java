/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-02
 */
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
     * {@code Locale.Category} -- a plain class, not an enum.
     *
     * <p>THE REASON THIS COMMENT USED TO GIVE HAS EXPIRED, and it is corrected here rather than quietly
     * dropped. It read "for the same reason {@code java.util.concurrent.TimeUnit} is: joe-ng has no enum
     * machinery here and the stock nested enum's {@code <clinit>} is unrunnable". Both halves are false now:
     * {@code java/lang/Enum} is stock, un-overlaid and un-denied, {@code java/math/RoundingMode} is a stock
     * enum this VM runs (PI-VALIDATED, in {@code BigMathProbe}'s {@code divide HALF_UP} arm), and the
     * {@code TimeUnit} overlay this cited is DELETED -- stock's enum runs on the metal.
     *
     * <p>SO THE PLAIN CLASS IS WHAT IS HERE, NOT WHAT IS NECESSARY, and nothing has measured the difference.
     * What IS still true is the layout half: it is declared INSIDE Locale so it compiles to
     * {@code java/util/Locale$Category}, the name stock callers reference; without it the overlay drops the
     * nested type as well as the method. Whether stock's nested enum would run here is a question for its
     * own increment -- the whole reason {@code Locale} is overlaid is locale DATA, which is a separate
     * argument from this nested type's shape.
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

    /**
     * VALUE equality over language/country/variant, which is stock's semantics and was a DROPPED MEMBER here
     * until stock {@code java.util.Formatter} started running on the metal and walked into it.
     *
     * <p>WHAT THE ABSENCE COST, because it is bigger than the one caller that found it. An overlay wins the
     * name, so a stock member it does not declare CEASES TO EXIST -- and for {@code equals} the fallback is
     * not a trap but {@code Object}'s IDENTITY comparison, which answers plausibly and wrongly.
     * {@code Locale.getDefault().equals(Locale.ENGLISH)} was FALSE on this VM (both are {@code new
     * Locale("en")}, distinct objects), and a Locale used as a {@code HashMap} key could never be found
     * again. Silent, in the way this project has recorded ten times.
     *
     * <p>THE CALLER THAT FOUND IT is stock {@code Formatter.localizedMagnitude}, whose grouping path is
     * {@code if (l == null || l.equals(Locale.US)) grpSize = 3; else <the java/text + sun/util provider
     * machinery>}. With identity equality {@code %,d} took the ELSE branch and halted in a denylist trap on
     * {@code NumberFormat.getNumberInstance}; with value equality it takes stock's own fast path and the
     * denied classes stay trap-wired-but-unreached, which is exactly what the denylist is designed for.
     *
     * <p>{@code hashCode} comes with it and is not optional: two objects that are equal must hash equally, and
     * a class that overrides one without the other is broken in every hash container.
     *
     * <p>BOTH GO THROUGH THE GETTERS RATHER THAN THE FIELDS, which is not a style choice: the constructors
     * accept and STORE null for country/variant ({@code new Locale("en", null, null)} is legal here), and the
     * getters are what normalise null to {@code ""}. Comparing the fields directly would NPE on a Locale this
     * class itself can build. Going through them also makes equality agree with what the accessors REPORT, by
     * construction -- two Locales that answer the same language, country and variant are equal, which is the
     * only definition a caller can check.
     */
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof Locale))
        {
            return false;
        }
        Locale other = (Locale) obj;
        return getLanguage().equals(other.getLanguage())
                && getCountry().equals(other.getCountry())
                && getVariant().equals(other.getVariant());
    }

    public int hashCode()
    {
        return getLanguage().hashCode() * 31 * 31 + getCountry().hashCode() * 31 + getVariant().hashCode();
    }

    public String getLanguage()
    {
        return language;
    }
}
