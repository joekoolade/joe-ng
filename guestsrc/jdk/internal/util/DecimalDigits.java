/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
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

    /**
     * The four decimal digits of {@code v}, zero-padded, as {@code java.time} formats a year field.
     *
     * <p>Stock indexes {@code DIGITS} twice -- once for {@code v / 100} and once for {@code v % 100} -- and
     * appends the four bytes through a throwaway LATIN1 String; the digits are computed directly here, which
     * is the same result without the table, the {@code Unsafe} that builds it, or the access shim.
     */
    public static void appendQuad(StringBuilder sb, int v)
    {
        int hi = (v / 100) & 0x7f;
        int lo = (v % 100) & 0x7f;
        sb.append(tensOf(hi));
        sb.append(unitsOf(hi));
        sb.append(tensOf(lo));
        sb.append(unitsOf(lo));
    }

    /**
     * The two characters stock's packed {@code DIGITS[n]} holds, for {@code n} already masked to 0..127.
     *
     * <p>ENTRIES 100..127 ARE NUL IN STOCK, and that is not a detail to round off: the table is filled by a
     * {@code 0..9 x 0..9} double loop, so the 28 entries above it keep the {@code short[]}'s zero fill and an
     * out-of-range caller gets two NUL characters. Computing {@code '0' + n / 10} there instead yields
     * {@code '<'}..{@code '?'} -- a MEASURED divergence, found by the host control before any boot, on 56 of
     * its 394 comparisons. An overlay's job is to be indistinguishable, including where stock is odd.
     */
    private static char tensOf(int n)
    {
        return n < 100 ? (char) ('0' + n / 10) : (char) 0;
    }

    private static char unitsOf(int n)
    {
        return n < 100 ? (char) ('0' + n % 10) : (char) 0;
    }

    // ----- the pair writers, and the char[] and UTF16 digit fills -----

    /**
     * Write {@code v}'s two decimal digits, TENS FIRST, at {@code charPos}.
     *
     * <p>That order is not a choice: stock packs the table entry as {@code tens | (units << 8)} and stores
     * the low byte at {@code charPos}, so a units-first fill would reverse every pair and produce a
     * perfectly plausible wrong number. The {@code & 0x7f} is kept so an out-of-range caller behaves as
     * stock does (the table's 100..127 entries are zero) rather than throwing.
     */
    public static void putPair(char[] buf, int charPos, int v)
    {
        int n = v & 0x7f;
        buf[charPos] = tensOf(n);
        buf[charPos + 1] = unitsOf(n);
    }

    /** {@link #putPair} into a LATIN1 byte buffer -- the form {@code BigDecimal.layoutChars} calls. */
    public static void uncheckedPutPairLatin1(byte[] buf, int charPos, int v)
    {
        int n = v & 0x7f;
        buf[charPos] = (byte) tensOf(n);
        buf[charPos + 1] = (byte) unitsOf(n);
    }

    /**
     * Fill {@code buf[0, index)} with {@code val}'s decimal digits as CHARS; returns the start index.
     *
     * <p>{@code BigDecimal.toString} routes its whole compact-form layout through this, so dropping it took
     * {@code BigDecimal} with it -- a DENYLIST TRAP naming a denylist this class is not on, which is the
     * shape this file records every time an overlay omits a stock member.
     */
    public static int getChars(long val, int index, char[] buf)
    {
        int i = index;
        boolean neg = val < 0L;
        long n = neg ? val : -val;                      // n <= 0 (keeps MIN_VALUE representable)
        do
        {
            i = i - 1;
            buf[i] = (char) ('0' - (int) (n % 10L));
            n = n / 10L;
        }
        while (n < 0L);
        if (neg)
        {
            i = i - 1;
            buf[i] = '-';
        }
        return i;
    }

    /**
     * {@link #uncheckedGetCharsLatin1(int, int, byte[])} into a UTF16-coded buffer: two bytes per char, LOW
     * BYTE FIRST.
     *
     * <p>The order is fixed by this VM rather than chosen: {@code StringUTF16.LO_BYTE_SHIFT} is SEEDED to 8
     * here (its initializer asks Unsafe for the byte order and cannot run on metal), so {@code HI_BYTE_SHIFT}
     * is 0 and {@code putChar} stores the low byte at the even index. Writing them the other way round reads
     * back as a character whose low byte is its high byte -- the failure this file already records for the
     * euro sign, which is a wrong STRING rather than an error.
     */
    public static int uncheckedGetCharsUTF16(int val, int index, byte[] buf)
    {
        int i = index;
        boolean neg = val < 0;
        int n = neg ? val : -val;                       // n <= 0
        do
        {
            i = i - 1;
            putCharUTF16(buf, i, '0' - n % 10);
            n = n / 10;
        }
        while (n < 0);
        if (neg)
        {
            i = i - 1;
            putCharUTF16(buf, i, '-');
        }
        return i;
    }

    /** The {@code long} form of {@link #uncheckedGetCharsUTF16(int, int, byte[])}. */
    public static int uncheckedGetCharsUTF16(long val, int index, byte[] buf)
    {
        int i = index;
        boolean neg = val < 0L;
        long n = neg ? val : -val;                      // n <= 0
        do
        {
            i = i - 1;
            putCharUTF16(buf, i, '0' - (int) (n % 10L));
            n = n / 10L;
        }
        while (n < 0L);
        if (neg)
        {
            i = i - 1;
            putCharUTF16(buf, i, '-');
        }
        return i;
    }

    private static void putCharUTF16(byte[] buf, int charPos, int c)
    {
        buf[charPos << 1] = (byte) c;                   // LO first: HI_BYTE_SHIFT = 0 on this VM
        buf[(charPos << 1) + 1] = (byte) (c >> 8);
    }
}
