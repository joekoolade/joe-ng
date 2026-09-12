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
 * argument indexes). Flags, width and precision are PARSED AND DROPPED rather than mis-applied, so
 * {@code %-10s} prints the value unpadded instead of printing something wrong. An unknown conversion is
 * emitted verbatim and consumes no argument, so it shows up in the output instead of vanishing.
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
            int j = i + 1;
            while (j < n && isFlagOrSize(fmt.charAt(j)))
            {
                j += 1;
            }
            if (j >= n)
            {
                out.append(ch);                     // a trailing '%': emit it rather than dropping it
                break;
            }
            char conv = fmt.charAt(j);
            if (conv == '%')
            {
                out.append('%');
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
                String s = convert(conv, a);
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

    /** Flag, width or precision character -- everything between the {@code %} and the conversion. */
    private static boolean isFlagOrSize(char c)
    {
        if (c >= '0' && c <= '9')
        {
            return true;
        }
        return c == '-' || c == '+' || c == ' ' || c == '#' || c == ',' || c == '(' || c == '.';
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

    /** The formatted argument, or {@code null} if this conversion is not supported. */
    private static String convert(char conv, Object a)
    {
        if (conv == 's')
        {
            return String.valueOf(a);
        }
        if (conv == 'S')
        {
            return String.valueOf(a).toUpperCase();
        }
        if (conv == 'd')
        {
            if (isIntegral(a))
            {
                return Long.toString(longOf(a));
            }
            return String.valueOf(a);
        }
        if (conv == 'x' || conv == 'X')
        {
            if (isIntegral(a))
            {
                String h = Long.toHexString(longOf(a));
                if (conv == 'X')
                {
                    return h.toUpperCase();
                }
                return h;
            }
            return String.valueOf(a);
        }
        if (conv == 'o')
        {
            if (isIntegral(a))
            {
                return Long.toOctalString(longOf(a));
            }
            return String.valueOf(a);
        }
        if (conv == 'c')
        {
            if (a instanceof Character)
            {
                return String.valueOf(((Character) a).charValue());
            }
            if (isIntegral(a))
            {
                return String.valueOf((char) longOf(a));
            }
            return String.valueOf(a);
        }
        if (conv == 'b' || conv == 'B')
        {
            String b = "true";
            if (a == null)
            {
                b = "false";
            }
            else if (a instanceof Boolean)
            {
                b = ((Boolean) a).booleanValue() ? "true" : "false";
            }
            if (conv == 'B')
            {
                return b.toUpperCase();
            }
            return b;
        }
        return null;
    }

    public String toString()
    {
        return out.toString();
    }
}
