package jdk.internal.util;

/**
 * A JDK-free, real-shaped {@code jdk/internal/util/DecimalDigits}: the two methods real
 * {@code Integer.toString(int)} calls — {@code stringSize} (decimal digit count, incl. sign) and
 * {@code uncheckedGetCharsLatin1} (write the digits into a LATIN1 buffer). The real ones read a
 * Unsafe-built {@code DIGITS} table; this computes directly (ASCII), which is all metal needs. Negative
 * accumulators avoid {@code Integer.MIN_VALUE} overflow without 64-bit math. Compiled as a java.base patch.
 */
public final class DecimalDigits
{
    public static int stringSize(int val)
    {
        int sign = val < 0 ? 1 : 0;
        int n = val < 0 ? val : -val;                   // n <= 0 (keeps MIN_VALUE representable)
        int digits = 1;
        while (n <= -10)
        {
            digits = digits + 1;
            n = n / 10;
        }
        return digits + sign;
    }

    /** Fill {@code buf[0, index)} with {@code val}'s decimal digits (least-significant last); returns the start. */
    public static int uncheckedGetCharsLatin1(int val, int index, byte[] buf)
    {
        int i = index;
        boolean neg = val < 0;
        int n = neg ? val : -val;                       // n <= 0
        do
        {
            i = i - 1;
            buf[i] = (byte) ('0' - n % 10);             // n % 10 in [-9, 0] -> the digit
            n = n / 10;
        }
        while (n < 0);
        if (neg)
        {
            i = i - 1;
            buf[i] = (byte) '-';
        }
        return i;
    }

    // ----- the (long) overloads real Long.toString(long) calls -----

    public static int stringSize(long val)
    {
        int sign = val < 0L ? 1 : 0;
        long n = val < 0L ? val : -val;                 // n <= 0 (keeps MIN_VALUE representable)
        int digits = 1;
        while (n <= -10L)
        {
            digits = digits + 1;
            n = n / 10L;
        }
        return digits + sign;
    }

    public static int uncheckedGetCharsLatin1(long val, int index, byte[] buf)
    {
        int i = index;
        boolean neg = val < 0L;
        long n = neg ? val : -val;                      // n <= 0
        do
        {
            i = i - 1;
            buf[i] = (byte) ('0' - (int) (n % 10L));
            n = n / 10L;
        }
        while (n < 0L);
        if (neg)
        {
            i = i - 1;
            buf[i] = (byte) '-';
        }
        return i;
    }

    /**
     * Append the two decimal digits of {@code i} (zero-padded), as {@code java.time} formatting does for a
     * minutes/seconds/hours field.
     *
     * <p>Stock indexes a packed {@code DIGITS} table with {@code i & 0x7f} and appends the two bytes through
     * {@code JavaLangAccess}; computing the digits directly is the same result without the table, the
     * {@code Unsafe} it is built with, or the access shim. The mask is kept so an out-of-range caller behaves
     * as stock does rather than throwing.
     *
     * <p>Declared here because a name-winning overlay silently drops what it does not declare: the call then
     * resolves nowhere and surfaces as a DENYLIST TRAP naming a denylist this class is not on. That is the
     * NINTH time this trap has been paid in joe-ng.
     */
    public static void appendPair(StringBuilder sb, int i)
    {
        int v = i & 0x7f;
        sb.append((char) ('0' + v / 10));
        sb.append((char) ('0' + v % 10));
    }
}
