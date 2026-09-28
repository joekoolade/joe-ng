/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-29
 */
package java.util;

/**
 * A minimal bare-metal {@code java.util.Formatter}: enough of printf for the conversions metal code actually
 * reaches, and none of what the real one drags in (Locale, Calendar, java.time, java.util.regex,
 * ResourceBundle). Stock {@code String.format}/{@code String.formatted} are
 * {@code new Formatter().format(fmt, args).toString()}, so they route through here.
 *
 * <p>This was a STUB that returned the empty string, on the stated premise that "the format path is compiled
 * but never executed on metal". The premise was false and it failed SILENTLY: JUnit's
 * {@code AssertionFailureBuilder} formats its whole message through {@code String.formatted}, so every
 * assertion failure reported an empty message where it should have said
 * {@code expected: <2> but was: <3>}. A stub that returns a plausible value is indistinguishable from one
 * that works -- the same trap as a silently skipped {@code <clinit>}.
 *
 * <p>Supported: {@code %s %S %d %x %X %o %c %b %B %% %n}, arguments consumed left to right (no {@code %1$s}
 * argument indexes), with FLAGS, WIDTH and PRECISION honoured -- {@code -}, {@code 0}, {@code +},
 * {@code ' '}, {@code #}, {@code ,} and {@code (}. An unknown conversion is emitted verbatim and consumes no
 * argument, so it shows up in the output instead of vanishing.
 *
 * <p>FLAGS/WIDTH/PRECISION USED TO BE PARSED AND DROPPED, on the stated ground that printing the value
 * unpadded beat printing something wrong. That was the right call while nothing applied them and the wrong
 * state to stay in: {@code String.format("%5d", 42)} answered {@code "42"}, so every aligned column any
 * caller built came out ragged, silently, with no marker and nothing to catch it. Every rule below was read
 * off a HOST ORACLE -- the same expressions run through the real {@code String.format} -- rather than
 * recalled, and three of them are not what a plausible implementation would have chosen:
 * <p>(1) a zero pad goes AFTER the sign or the {@code 0x} prefix, so {@code %05d} of -42 is {@code -0042}
 *     and {@code %#010x} of 255 is {@code 0x000000ff} -- a pad that ran before the sign would give
 *     {@code 000-42}, which still looks like a padded number.
 * <p>(2) grouping runs BEFORE padding and the pad zeros are NOT themselves grouped: {@code %+,09d} of 12345
 *     is {@code +0012,345}, not {@code +00,012,345}.
 * <p>(3) a width SHORTER than the value does not truncate -- {@code %2d} of 12345 is {@code 12345}.
 *
 * <p>AND THE RADIX CONVERSIONS FORMAT AT THE ARGUMENT'S OWN WIDTH, which is a separate defect this oracle
 * caught rather than the one it was pointed at. {@code %x}/{@code %X}/{@code %o} are UNSIGNED, and joe-ng
 * widened every box to {@code long} before formatting -- so {@code %x} of the {@code int} -1 answered
 * {@code ffffffffffffffff} where stock answers {@code ffffffff}, and a {@code short} or {@code byte} -1 was
 * wrong by four or six digits the same way. The value is masked to the box's own width now
 * ({@link #maskOf}); {@code %d} is signed and takes no mask.
 *
 * <p>ONE STATED DIVERGENCE, and it is bounded: for every LEGAL format string this agrees with stock
 * byte-for-byte, and an ILLEGAL one is FORMATTED where stock throws ({@code %.2d}, {@code %#d},
 * {@code %+x}, {@code %-05d} raise four different {@code IllegalFormatException} subclasses there). Those
 * three classes are deliberately not pulled: this is the PRINTING path, and an exception raised while
 * reporting a failure replaces the failure with itself -- the trap recorded at {@link #flush} that hid the
 * launcher's real error twice. So joe-ng is more permissive about a caller's bug and never wrong about a
 * format that is correct.
 */
public final class Formatter
{
    private final StringBuilder out = new StringBuilder();

    /**
     * Where a completed {@link #format} is flushed, or null to accumulate in {@link #out} for
     * {@link #toString()}. Stock {@code PrintWriter.format} builds {@code new Formatter(this)} and then
     * reads NOTHING back -- the formatter is expected to write THROUGH to the writer -- so a Formatter that
     * only accumulated would print nothing at all and look like a working call.
     */
    private final Appendable sink;

    public Formatter()
    {
        this.sink = null;
    }

    /**
     * The constructor stock {@code PrintWriter.format}/{@code printf} calls ({@code new Formatter(this)}).
     * Its absence is why the console launcher trapped while printing its test summary: an overlay WINS the
     * name, so a stock member it does not declare ceases to exist, the call resolves nowhere, and it
     * surfaces as a DENYLIST TRAP naming a list {@code java/util/Formatter} is not on. Eleventh occurrence.
     */
    public Formatter(Appendable a)
    {
        this.sink = a;
    }

    /**
     * Stock {@code PrintWriter.format} compares this against {@code Locale.getDefault()} BY IDENTITY and
     * rebuilds the formatter when they differ. Answering the default keeps the writer's cached formatter
     * rather than allocating one per call; answering null would merely be wasteful, not wrong.
     */
    public Locale locale()
    {
        return Locale.getDefault();
    }

    /**
     * The locale-taking overload stock calls. joe-ng carries no locale data -- the one place a Locale is read
     * is Pattern's case folding, which wants English -- so the locale is accepted and ignored rather than
     * being made to look as if it selects anything.
     */
    public Formatter format(Locale l, String fmt, Object... args)
    {
        return format(fmt, args);
    }

    public Formatter format(String fmt, Object... args)
    {
        if (fmt == null)
        {
            return this;
        }
        int argi = 0;
        int i = 0;
        int n = fmt.length();
        while (i < n)
        {
            char ch = fmt.charAt(i);
            if (ch != '%')
            {
                out.append(ch);
                i += 1;
                continue;
            }

            // FLAGS, then WIDTH, then '.'PRECISION -- in that order, which is what makes '0' a flag rather
            // than the first digit of a width: for %05d the flag loop takes the '0' and stops at the '5',
            // while for %50d it stops immediately (a '5' is no flag) and the width reads 50.
            int p = i + 1;
            int flags = 0;
            while (p < n && flagBit(fmt.charAt(p)) != 0)
            {
                flags |= flagBit(fmt.charAt(p));
                p += 1;
            }
            int width = 0;
            while (p < n && fmt.charAt(p) >= '0' && fmt.charAt(p) <= '9')
            {
                width = width * 10 + (fmt.charAt(p) - '0');
                p += 1;
            }
            int prec = -1;
            if (p < n && fmt.charAt(p) == '.')
            {
                p += 1;
                prec = 0;
                while (p < n && fmt.charAt(p) >= '0' && fmt.charAt(p) <= '9')
                {
                    prec = prec * 10 + (fmt.charAt(p) - '0');
                    p += 1;
                }
            }
            int j = p;
            if (j >= n)
            {
                out.append(ch);                     // a trailing '%': emit it rather than dropping it
                break;
            }
            char conv = fmt.charAt(j);
            if (conv == '%')
            {
                out.append(pad("", "%", width, flags));   // stock pads this too: %5% is four spaces then '%'
            }
            else if (conv == 'n')
            {
                out.append('\n');
            }
            else
            {
                Object a = null;
                if (args != null && argi < args.length)
                {
                    a = args[argi];
                }
                String s = render(conv, a, flags, width, prec);
                if (s == null)
                {
                    out.append(fmt, i, j + 1);      // unknown conversion: verbatim, and it took no argument
                }
                else
                {
                    out.append(s);
                    argi += 1;
                }
            }
            i = j + 1;
        }
        flush();
        return this;
    }

    /**
     * Hand a completed format to the sink and reset. Done per format() call rather than at close(), because
     * PrintWriter.printf's contract is that the text has been written when it returns -- and this VM may halt
     * at any point, so anything buffered for later is simply lost.
     */
    private void flush()
    {
        if (sink == null || out.length() == 0)
        {
            return;
        }
        try
        {
            sink.append(out);
        }
        catch (java.io.IOException e)
        {
            // Appendable.append declares IOException; the sinks reached here (PrintWriter, StringBuilder) do
            // not throw it. Swallowed rather than wrapped: this is the PRINTING path, and an exception raised
            // while reporting a failure replaces the failure with itself -- which is the trap that hid the
            // launcher's real error twice in this arc.
        }
        out.setLength(0);
    }

    private static final int F_LEFT = 1;            // '-'  left-justify
    private static final int F_ALT = 2;             // '#'  alternate form: 0x for hex, 0 for octal
    private static final int F_PLUS = 4;            // '+'  always sign
    private static final int F_SPACE = 8;           // ' '  space where a '+' would go
    private static final int F_ZERO = 16;           // '0'  pad with zeros rather than spaces
    private static final int F_GROUP = 32;          // ','  grouping separator
    private static final int F_PAREN = 64;          // '('  negative in parentheses

    /** The bit for a flag character, or 0 if {@code c} is not a flag (which ends the flag run). */
    private static int flagBit(char c)
    {
        if (c == '-')
        {
            return F_LEFT;
        }
        if (c == '#')
        {
            return F_ALT;
        }
        if (c == '+')
        {
            return F_PLUS;
        }
        if (c == ' ')
        {
            return F_SPACE;
        }
        if (c == '0')
        {
            return F_ZERO;
        }
        if (c == ',')
        {
            return F_GROUP;
        }
        if (c == '(')
        {
            return F_PAREN;
        }
        return 0;
    }

    /**
     * Widen {@code lead + body} to {@code width}. Split in two because WHERE the fill goes depends on which
     * kind it is: spaces go outside the whole thing, zeros go BETWEEN the lead and the body -- so a sign or a
     * {@code 0x} stays in front of its own padding ({@code -0042}, {@code 0x000000ff}) instead of being
     * pushed right by it. A width at or below the current length adds nothing and never truncates.
     */
    private static String pad(String lead, String body, int width, int flags)
    {
        int len = lead.length() + body.length();
        if (width <= len)
        {
            return lead + body;
        }
        int fill = width - len;
        StringBuilder b = new StringBuilder();
        if ((flags & F_LEFT) != 0)
        {
            b.append(lead);
            b.append(body);
            for (int k = 0; k < fill; k += 1)
            {
                b.append(' ');
            }
            return b.toString();
        }
        if ((flags & F_ZERO) != 0)
        {
            b.append(lead);
            for (int k = 0; k < fill; k += 1)
            {
                b.append('0');
            }
            b.append(body);
            return b.toString();
        }
        for (int k = 0; k < fill; k += 1)
        {
            b.append(' ');
        }
        b.append(lead);
        b.append(body);
        return b.toString();
    }

    /** {@code 12345678} -> {@code 12,345,678}. Applied to the DIGITS only, before any padding. */
    private static String group(String digits)
    {
        int n = digits.length();
        if (n <= 3)
        {
            return digits;
        }
        int first = n % 3;
        if (first == 0)
        {
            first = 3;
        }
        StringBuilder b = new StringBuilder();
        b.append(digits.substring(0, first));
        int k = first;
        while (k < n)
        {
            b.append(',');
            b.append(digits.substring(k, k + 3));
            k += 3;
        }
        return b.toString();
    }

    /**
     * The digits of {@code |v|}, taken by formatting and dropping the sign rather than by negating:
     * {@code -Long.MIN_VALUE} is still {@code Long.MIN_VALUE}, so a negate here would answer the magnitude of
     * every value except the one that cannot be represented -- which is the failure mode this VM has already
     * paid for in three integer formatters.
     */
    private static String magnitude(long v)
    {
        String s = Long.toString(v);
        if (v < 0)
        {
            return s.substring(1);
        }
        return s;
    }

    /**
     * The mask that formats a box at its OWN width. {@code %x}/{@code %X}/{@code %o} are unsigned, so the
     * answer depends on how many bits the argument has: the {@code int} -1 is {@code ffffffff} and the
     * {@code long} -1 is {@code ffffffffffffffff}. Widening everything to {@code long} first -- which this
     * class used to do -- makes every negative {@code int}, {@code short} and {@code byte} wrong.
     */
    private static long maskOf(Object a)
    {
        if (a instanceof Long)
        {
            return -1L;
        }
        if (a instanceof Integer)
        {
            return 0xFFFFFFFFL;
        }
        if (a instanceof Short)
        {
            return 0xFFFFL;
        }
        return 0xFFL;                               // Byte
    }

    // The boxes are tested concretely rather than as Number: the guestsrc Number overlay declares none of
    // the value accessors ("aren't needed here"), so `((Number) a).longValue()` does not compile against it,
    // and widening Number would shift every subclass's vtable for the sake of a formatter.
    private static boolean isIntegral(Object a)
    {
        return a instanceof Long || a instanceof Integer || a instanceof Short || a instanceof Byte;
    }

    private static long longOf(Object a)
    {
        if (a instanceof Long)
        {
            return ((Long) a).longValue();
        }
        if (a instanceof Integer)
        {
            return ((Integer) a).longValue();
        }
        if (a instanceof Short)
        {
            return ((Short) a).longValue();
        }
        return ((Byte) a).longValue();
    }

    /**
     * The formatted argument with flags, width and precision applied, or {@code null} if this conversion is
     * not supported (the caller then emits the specifier verbatim and consumes no argument).
     *
     * <p>{@code lead} is the part a zero pad must stay behind -- a sign for {@code %d}, a {@code 0x}/{@code 0}
     * for the radix conversions -- and {@code body} is everything after it.
     */
    private static String render(char conv, Object a, int flags, int width, int prec)
    {
        String lead = "";
        String body;
        if (conv == 's' || conv == 'S')
        {
            body = clip(String.valueOf(a), prec);
            if (conv == 'S')
            {
                body = body.toUpperCase();
            }
        }
        else if (conv == 'b' || conv == 'B')
        {
            body = "true";
            if (a == null)
            {
                body = "false";
            }
            else if (a instanceof Boolean)
            {
                body = ((Boolean) a).booleanValue() ? "true" : "false";
            }
            body = clip(body, prec);
            if (conv == 'B')
            {
                body = body.toUpperCase();
            }
        }
        else if (conv == 'c')
        {
            if (a instanceof Character)
            {
                body = String.valueOf(((Character) a).charValue());
            }
            else if (isIntegral(a))
            {
                body = String.valueOf((char) longOf(a));
            }
            else
            {
                body = String.valueOf(a);
            }
        }
        else if (conv == 'd')
        {
            if (!isIntegral(a))
            {
                body = String.valueOf(a);
            }
            else
            {
                long v = longOf(a);
                body = magnitude(v);
                if ((flags & F_GROUP) != 0)
                {
                    body = group(body);             // before padding: %+,09d of 12345 is +0012,345
                }
                if (v < 0)
                {
                    if ((flags & F_PAREN) != 0)
                    {
                        lead = "(";
                        body = body + ")";
                    }
                    else
                    {
                        lead = "-";
                    }
                }
                else if ((flags & F_PLUS) != 0)
                {
                    lead = "+";
                }
                else if ((flags & F_SPACE) != 0)
                {
                    lead = " ";
                }
            }
        }
        else if (conv == 'x' || conv == 'X' || conv == 'o')
        {
            if (!isIntegral(a))
            {
                body = String.valueOf(a);
            }
            else
            {
                long u = longOf(a) & maskOf(a);
                if (conv == 'o')
                {
                    body = Long.toOctalString(u);
                }
                else
                {
                    body = Long.toHexString(u);
                    if (conv == 'X')
                    {
                        body = body.toUpperCase();
                    }
                }
                if ((flags & F_ALT) != 0)
                {
                    if (conv == 'o')
                    {
                        lead = "0";
                    }
                    else if (conv == 'X')
                    {
                        lead = "0X";
                    }
                    else
                    {
                        lead = "0x";
                    }
                }
            }
        }
        else
        {
            return null;
        }
        return pad(lead, body, width, flags);
    }

    /** Truncate to {@code prec} characters; {@code prec < 0} means none was given. */
    private static String clip(String s, int prec)
    {
        if (prec >= 0 && prec < s.length())
        {
            return s.substring(0, prec);
        }
        return s;
    }

    public String toString()
    {
        return out.toString();
    }
}
