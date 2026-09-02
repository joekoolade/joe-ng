package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Character} overlay. It shadows the stock class entirely (overlay wins by
 * name), so it must carry every {@code Character} method a reached path calls -- otherwise the missing method is
 * unresolved and its call hits the denylist trap.
 *
 * <p>{@code digit(char,int)} is the ASCII/Latin1 path real {@code Integer.parseInt} needs. The surrogate /
 * code-point cluster below is what {@code java.util.regex} (Pattern/Matcher) calls while scanning input for a
 * LITERAL {@code String.split} -- all pure bit arithmetic (the stock bodies route case/type queries through the
 * big {@code CharacterData} tables, which stay cold for a literal ASCII match). The boxing members
 * ({@code valueOf}/{@code compareTo}/{@code compare}/{@code MIN_VALUE}/{@code MAX_VALUE}) back {@code Compare}.
 * Values inlined as literals/constant casts so the overlay needs no {@code <clinit>} / static fields on metal.
 */
public final class Character implements Comparable<Character>
{

    /**
     * {@code char.class}. javac compiles a primitive class literal to {@code getstatic Character.TYPE}, so this field
     * is what makes it work -- and a name-winning overlay silently drops it unless it is declared here.
     *
     * <p>Deliberately NOT {@code final} and deliberately UNINITIALIZED: the VM fills it in
     * ({@code Loader.seedPrimitiveTypes}) because the writer cannot bake it -- the seed JVM's value is a host
     * {@code java.lang.Class} with no image representation. An initializer would also run in {@code <clinit>}
     * AFTER the seeding and null it back out.
     */
    public static Class<Character> TYPE;
    public static final char MIN_VALUE = (char) 0x0000;
    public static final char MAX_VALUE = (char) 0xFFFF;

    private final char value;

    public Character(char v)
    {
        this.value = v;
    }

    public static Character valueOf(char c)
    {
        return new Character(c);
    }

    public char charValue()
    {
        return value;
    }

    /** Numeric comparison of two chars (both are unsigned 16-bit, so the difference is signed-correct). */
    public static int compare(char x, char y)
    {
        return x - y;
    }

    public int compareTo(Character other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Character && ((Character) o).value == value;
    }

    public int hashCode()
    {
        return value;
    }

    /**
     * The overlay wins by name, so a member it does not declare is simply GONE -- and a missing {@code
     * toString} does not trap, it silently inherits {@code Object}'s and prints {@code java.lang.Character@71}
     * where the character should be (0x71 being {@code hashCode()}, i.e. the char itself). Boolean, Byte and
     * Short all carry theirs; this one was the omission. Same trap as StringBuilder/Appendable, Class
     * .getPrimitiveClass, the wrappers' TYPE, and Throwable.initCause before it.
     */
    public String toString()
    {
        return String.valueOf(value);
    }

    /** Static form, for {@code Character.toString(c)} and the {@code String.valueOf} path that mirrors it. */
    public static String toString(char c)
    {
        return String.valueOf(c);
    }

    public static int digit(char ch, int radix)
    {
        int d = -1;
        if (ch >= '0' && ch <= '9')
        {
            d = ch - '0';
        }
        else if (ch >= 'a' && ch <= 'z')
        {
            d = ch - 'a' + 10;
        }
        else if (ch >= 'A' && ch <= 'Z')
        {
            d = ch - 'A' + 10;
        }
        if (d >= radix)
        {
            return -1;
        }
        return d;
    }

    // ----- classification predicates -----------------------------------------------------------------------
    // LATIN-1 ONLY, and that is a real limit, stated rather than hidden: the stock answers come from Unicode
    // character-class tables that joe-ng does not carry, so anything above U+00FF is classified by the ASCII
    // rules below and will disagree with stock for scripts that need those tables. Every caller reached so far
    // (picocli's parser, JUnit's identifier and display-name checks, our own tokenizers) is ASCII in practice.
    //
    // Declared at all because a name-winning overlay silently drops what it does not declare: the call then
    // resolves NOWHERE and surfaces as a DENYLIST TRAP. `make overlaycheck` is what listed these.
    public static boolean isDigit(char ch)
    {
        return ch >= '0' && ch <= '9';
    }

    public static boolean isDigit(int cp)
    {
        return cp >= '0' && cp <= '9';
    }

    public static boolean isLetter(char ch)
    {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    public static boolean isLetter(int cp)
    {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    public static boolean isLetterOrDigit(char ch)
    {
        return isLetter(ch) || isDigit(ch);
    }

    public static boolean isLetterOrDigit(int cp)
    {
        return isLetter(cp) || isDigit(cp);
    }

    public static boolean isUpperCase(char ch)
    {
        return ch >= 'A' && ch <= 'Z';
    }

    public static boolean isUpperCase(int cp)
    {
        return cp >= 'A' && cp <= 'Z';
    }

    public static boolean isLowerCase(char ch)
    {
        return ch >= 'a' && ch <= 'z';
    }

    public static boolean isLowerCase(int cp)
    {
        return cp >= 'a' && cp <= 'z';
    }

    /** Space, tab, newline, vertical tab, form feed, carriage return, and the file/group/record/unit separators. */
    public static boolean isWhitespace(char ch)
    {
        return isWhitespace((int) ch);
    }

    public static boolean isWhitespace(int cp)
    {
        return cp == ' ' || (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x1F);
    }

    /** {@code isSpaceChar} is the SPACE-SEPARATOR test, so a tab is NOT one -- unlike {@link #isWhitespace}. */
    public static boolean isSpaceChar(char ch)
    {
        return ch == ' ' || ch == 0x00A0;                 // SPACE, NO-BREAK SPACE
    }

    public static boolean isSpaceChar(int cp)
    {
        return cp == ' ' || cp == 0x00A0;
    }

    public static boolean isISOControl(char ch)
    {
        return isISOControl((int) ch);
    }

    public static boolean isISOControl(int cp)
    {
        return (cp >= 0x00 && cp <= 0x1F) || (cp >= 0x7F && cp <= 0x9F);
    }

    public static boolean isJavaIdentifierStart(char ch)
    {
        return isLetter(ch) || ch == '$' || ch == '_';
    }

    public static boolean isJavaIdentifierStart(int cp)
    {
        return isLetter(cp) || cp == '$' || cp == '_';
    }

    public static boolean isJavaIdentifierPart(char ch)
    {
        return isJavaIdentifierStart(ch) || isDigit(ch);
    }

    public static boolean isJavaIdentifierPart(int cp)
    {
        return isJavaIdentifierStart(cp) || isDigit(cp);
    }

    // ----- Unicode general category -------------------------------------------------------------------------
    // The stock category constants, needed because getType returns one and callers compare against them.
    public static final byte UNASSIGNED = 0;
    public static final byte UPPERCASE_LETTER = 1;
    public static final byte LOWERCASE_LETTER = 2;
    public static final byte DECIMAL_DIGIT_NUMBER = 9;
    public static final byte SPACE_SEPARATOR = 12;
    public static final byte CONTROL = 15;
    public static final byte DASH_PUNCTUATION = 20;
    public static final byte START_PUNCTUATION = 21;
    public static final byte END_PUNCTUATION = 22;
    public static final byte CONNECTOR_PUNCTUATION = 23;
    public static final byte OTHER_PUNCTUATION = 24;
    public static final byte MATH_SYMBOL = 25;
    public static final byte CURRENCY_SYMBOL = 26;
    public static final byte MODIFIER_SYMBOL = 27;

    /**
     * The Unicode general category of {@code ch} -- ASCII only, the same stated limit as the classification
     * predicates above: the real answer comes from Unicode tables joe-ng does not carry, so anything above
     * U+007F reports {@link #UNASSIGNED} rather than a confidently wrong category.
     *
     * <p>Reached from {@code java.time.format.DateTimeFormatterBuilder}, which classifies the characters of a
     * format pattern -- all ASCII in practice. Declared because a name-winning overlay silently drops what it
     * does not declare, and the call then resolves NOWHERE and traps.
     */
    public static int getType(char ch)
    {
        return getType((int) ch);
    }

    public static int getType(int cp)
    {
        if (cp > 0x7F)
        {
            return UNASSIGNED;                          // outside ASCII: see the note above
        }
        if (isUpperCase(cp))
        {
            return UPPERCASE_LETTER;
        }
        if (isLowerCase(cp))
        {
            return LOWERCASE_LETTER;
        }
        if (isDigit(cp))
        {
            return DECIMAL_DIGIT_NUMBER;
        }
        if (cp == ' ')
        {
            return SPACE_SEPARATOR;
        }
        if (cp < 0x20 || cp == 0x7F)
        {
            return CONTROL;                             // includes tab/newline, as Unicode does
        }
        if (cp == '_')
        {
            return CONNECTOR_PUNCTUATION;
        }
        if (cp == '-')
        {
            return DASH_PUNCTUATION;
        }
        if (cp == '(' || cp == '[' || cp == '{')
        {
            return START_PUNCTUATION;
        }
        if (cp == ')' || cp == ']' || cp == '}')
        {
            return END_PUNCTUATION;
        }
        if (cp == '$')
        {
            return CURRENCY_SYMBOL;
        }
        if (cp == '+' || cp == '<' || cp == '=' || cp == '>' || cp == '|' || cp == '~')
        {
            return MATH_SYMBOL;
        }
        if (cp == '^' || cp == '`')
        {
            return MODIFIER_SYMBOL;
        }
        return OTHER_PUNCTUATION;                       // ! " # % & ' * , . / : ; ? @ \ -- the rest of ASCII
    }

    /**
     * The remaining classification and code-point helpers stock text code reaches, listed by
     * {@code make overlaycheck-deep}. ASCII/Latin-1 only, the same stated limit as the predicates above:
     * everything here answers from the character itself, never from a Unicode table joe-ng does not carry.
     *
     * <p>The properties that are MEANINGLESS without those tables -- {@code isMirrored}, {@code isIdeographic},
     * the emoji family, {@code getName} -- are deliberately NOT declared. Answering "false" for them would be a
     * confident claim about a character set this VM cannot see; leaving them absent keeps the gap visible in
     * the deep scan, which is where it belongs.
     */
    public static boolean isAlphabetic(int cp)
    {
        return isLetter(cp);
    }

    public static boolean isDefined(int cp)
    {
        return cp >= 0 && cp <= 0x7F;                   // only ASCII is modelled -- say so rather than claim more
    }

    public static boolean isTitleCase(int cp)
    {
        return false;                                   // ASCII has no title-case characters; this IS exact
    }

    public static boolean isIdentifierIgnorable(int cp)
    {
        return (cp >= 0x00 && cp <= 0x08) || (cp >= 0x0E && cp <= 0x1B) || cp == 0x7F;
    }

    public static boolean isUnicodeIdentifierStart(int cp)
    {
        return isLetter(cp);
    }

    public static boolean isUnicodeIdentifierPart(int cp)
    {
        return isLetter(cp) || isDigit(cp) || cp == '_' || isIdentifierIgnorable(cp);
    }

    /** Digit value in {@code radix}, or -1 -- the code-point twin of {@link #digit(char,int)}. */
    public static int getNumericValue(int cp)
    {
        if (cp >= '0' && cp <= '9')
        {
            return cp - '0';
        }
        if (cp >= 'A' && cp <= 'Z')
        {
            return cp - 'A' + 10;
        }
        if (cp >= 'a' && cp <= 'z')
        {
            return cp - 'a' + 10;
        }
        return -1;
    }

    /** The inverse of {@link #digit}: a digit value to its character, or NUL when out of range (as stock). */
    public static char forDigit(int digit, int radix)
    {
        if (digit < 0 || digit >= radix || radix < 2 || radix > 36)
        {
            return '\0';
        }
        return (char) (digit < 10 ? '0' + digit : 'a' + digit - 10);
    }

    public static int codePointAt(char[] a, int index)
    {
        return a[index];                                // BMP only: a surrogate pair is not combined here
    }

    public static int toChars(int codePoint, char[] dst, int dstIndex)
    {
        dst[dstIndex] = (char) codePoint;
        return 1;                                       // BMP only -- a supplementary point would need 2
    }

    public static char reverseBytes(char ch)
    {
        return (char) (((ch & 0xFF00) >> 8) | ((ch & 0x00FF) << 8));
    }

    // ----- surrogate / BMP predicates (pure bit logic; 0xD800..0xDFFF is the surrogate range) ---------------
    public static boolean isHighSurrogate(char ch)
    {
        return ch >= '\uD800' && ch < '\uDC00';           // MIN_HIGH_SURROGATE .. MAX_HIGH_SURROGATE
    }

    public static boolean isLowSurrogate(char ch)
    {
        return ch >= '\uDC00' && ch <= '\uDFFF';          // MIN_LOW_SURROGATE .. MAX_LOW_SURROGATE
    }

    public static boolean isSurrogate(char ch)
    {
        return ch >= '\uD800' && ch <= '\uDFFF';          // MIN_SURROGATE .. MAX_SURROGATE
    }

    public static boolean isSurrogatePair(char high, char low)
    {
        return isHighSurrogate(high) && isLowSurrogate(low);
    }

    public static boolean isBmpCodePoint(int codePoint)
    {
        return codePoint >>> 16 == 0;
    }

    public static boolean isValidCodePoint(int codePoint)
    {
        return (codePoint >>> 16) < 0x11;                 // (MAX_CODE_POINT + 1) >>> 16 == 0x110000 >>> 16
    }

    public static boolean isSupplementaryCodePoint(int codePoint)
    {
        return codePoint >= 0x10000 && codePoint < 0x110000;
    }

    // ----- code-point <-> surrogate-pair arithmetic --------------------------------------------------------
    public static int charCount(int codePoint)
    {
        return codePoint >= 0x10000 ? 2 : 1;
    }

    public static int toCodePoint(char high, char low)
    {
        return ((high << 10) + low) + (0x10000 - ('\uD800' << 10) - '\uDC00');
    }

    public static char highSurrogate(int codePoint)
    {
        return (char) ((codePoint >>> 10) + ('\uD800' - (0x10000 >>> 10)));
    }

    public static char lowSurrogate(int codePoint)
    {
        return (char) ((codePoint & 0x3FF) + '\uDC00');
    }

    public static int codePointAt(CharSequence seq, int index)
    {
        char c1 = seq.charAt(index);
        if (isHighSurrogate(c1) && ++index < seq.length())
        {
            char c2 = seq.charAt(index);
            if (isLowSurrogate(c2))
            {
                return toCodePoint(c1, c2);
            }
        }
        return c1;
    }

    public static int codePointBefore(CharSequence seq, int index)
    {
        char c2 = seq.charAt(--index);
        if (isLowSurrogate(c2) && index > 0)
        {
            char c1 = seq.charAt(--index);
            if (isHighSurrogate(c1))
            {
                return toCodePoint(c1, c2);
            }
        }
        return c2;
    }

    public static char[] toChars(int codePoint)
    {
        if (isBmpCodePoint(codePoint))
        {
            return new char[] { (char) codePoint };
        }
        char[] result = new char[2];
        result[1] = lowSurrogate(codePoint);
        result[0] = highSurrogate(codePoint);
        return result;
    }

    // Case folding for Latin-1 (code points < 0x100), the same pure-arithmetic path as CharacterDataLatin1. Stock
    // Character.toLowerCase/toUpperCase route through CharacterData.of(ch).toXxxCase(ch), which drags in
    // jdk/internal/lang/CaseFolding (denied on metal) -> a trapwire. StringLatin1.compareToCI (String
    // .CASE_INSENSITIVE_ORDER) only ever passes Latin-1 chars, so this overlay covers the reached path; code
    // points >= 0x100 fall through unchanged (their non-Latin-1 mappings are out of scope, not a native away).

    public static char toLowerCase(char ch)
    {
        return (char) toLowerCase((int) ch);
    }

    public static int toLowerCase(int ch)
    {
        if (ch < 'A')
        {
            return ch;
        }
        int lower = ch | 0x20;
        if (lower <= 'z' || (lower >= 0xE0 && lower <= 0xFE && lower != 0xF7))
        {
            return lower;
        }
        return ch;
    }

    public static char toUpperCase(char ch)
    {
        return (char) toUpperCase((int) ch);
    }

    public static int toUpperCase(int ch)
    {
        if (ch < 'a')
        {
            return ch;
        }
        int upper = ch & 0xDF;
        if (upper <= 'Z' || (upper >= 0xC0 && upper <= 0xDE && upper != 0xD7))
        {
            return upper;
        }
        if (ch == 0xFF)
        {
            return 0x178;                               // y-diaeresis uppercases out of Latin-1
        }
        if (ch == 0xB5)
        {
            return 0x39C;                               // micro sign -> Greek capital Mu
        }
        return ch;
    }
}
