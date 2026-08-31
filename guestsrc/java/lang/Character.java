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
