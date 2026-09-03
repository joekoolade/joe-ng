package java.io;

import magic.Magic;

/**
 * A JDK-free, minimal {@code java/io/PrintStream} overlay (wins by name, like the mini exception / Locale
 * overlays). Stock {@code PrintStream.println(String)} routes through {@code textOut:BufferedWriter} ->
 * {@code OutputStreamWriter} -> {@code StreamEncoder}/{@code CharsetEncoder} — the deep nio charset closure —
 * and the raw sink under it is a native ({@code FileOutputStream.writeBytes}). Both are absent on metal, so we
 * substitute the whole class: every print encodes through the STOCK {@code String.getBytes()} (the UTF-8 fast
 * path of the charset closure) and writes the raw bytes to the UART via {@code Magic.printStr(byte[])} — so a
 * Latin1 é goes out as {@code 0xC3 0xA9} and a UTF16 string (chars &gt; 0xFF) encodes correctly; ASCII bytes
 * are unchanged. (The metal CONCAT intrinsic builds Latin1 byte-strings, so non-Latin1 text should be printed
 * directly, not via {@code "x" + s} concatenation.)
 *
 * <p>{@code System.out}/{@code System.err} are installed by {@code Loader.seedSystemStreams()} — which allocates
 * a bare instance of this class (no ctor call, no instance state) and stores it into the static slots — because
 * stock {@code System.initPhase1}/{@code setOut0} that would normally set them are native-heavy and unrunnable.
 * So these methods must not depend on any instance field; they write to the one global UART sink.
 *
 * <p><b>It EXTENDS {@link java.io.OutputStream}, and that is load-bearing.</b> Stock is
 * {@code PrintStream extends FilterOutputStream extends OutputStream}, so every library that wraps
 * {@code System.out} in something -- {@code new PrintWriter(System.out)}, {@code new OutputStreamWriter(...)} --
 * binds it as an OutputStream. A name-winning overlay silently drops the stock SUPERCLASS just as it drops
 * interfaces (the StringBuilder/Appendable trap), and nothing complains: JUnit's ConsoleLauncher simply
 * produced NO OUTPUT AT ALL, having wrapped a System.out that was not an OutputStream.
 *
 * <p>Field-free by design (instance size stays a bare 16-byte header). Numeric overloads route through the
 * already-working {@code Integer.toString}/{@code Long.toString}; only methods a demo actually reaches compile.
 */
public class PrintStream extends java.io.OutputStream
{
    /**
     * The stream this PrintStream wraps, or NULL for the UART.
     *
     * <p>Null is the normal case and must stay correct: {@code Loader.seedSystemStreams()} allocates
     * {@code System.out}/{@code err} WITHOUT running a constructor, so this field is never assigned for them.
     * It reads null rather than garbage because {@code Heap.alloc} zeroes an allocation's payload -- checked,
     * not assumed, since a garbage value here would be handed to a virtual call.
     */
    private OutputStream out;

    public PrintStream()
    {
    }

    /**
     * Wrap another stream, as stock. Added because JUnit's {@code StreamInterceptor} and picocli both do
     * {@code new PrintStream(someStream)} to CAPTURE output: ignoring the argument and writing to the UART
     * anyway would leave the capture silently empty, which reads as a mysteriously failing assertion rather
     * than as a missing feature.
     *
     * <p>{@code autoFlush} is accepted and ignored: every write here goes straight to the sink, so the stream
     * is never in a buffered state for a flush to resolve.
     */
    public PrintStream(OutputStream out)
    {
        this.out = out;
    }

    public PrintStream(OutputStream out, boolean autoFlush)
    {
        this.out = out;
    }

    /** The sink: the wrapped stream if there is one, else stock UTF-8 encode + raw bytes to the UART. */
    private void emit(String s)
    {
        byte[] b = s.getBytes();
        if (out == null)
        {
            Magic.printStr(b);
            return;
        }
        try
        {
            out.write(b, 0, b.length);
        }
        catch (IOException e)
        {
            // A PrintStream never propagates an IOException -- stock sets an internal error flag instead.
        }
    }

    public void print(String s)
    {
        emit(s == null ? "null" : s);
    }

    public void println(String s)
    {
        print(s);
        emit("\n");
    }

    public void println()
    {
        emit("\n");
    }

    public void print(int i)
    {
        emit(Integer.toString(i));
    }

    public void println(int i)
    {
        println(Integer.toString(i));
    }

    public void print(long l)
    {
        emit(Long.toString(l));
    }

    public void println(long l)
    {
        println(Long.toString(l));
    }

    public void print(boolean b)
    {
        emit(b ? "true" : "false");
    }

    public void println(boolean b)
    {
        println(b ? "true" : "false");
    }

    public void print(char c)
    {
        emit(String.valueOf(c));                        // a char > 0x7F UTF-8-encodes via getBytes
    }

    public void println(char c)
    {
        print(c);
        emit("\n");
    }

    public void print(Object o)
    {
        emit(String.valueOf(o));
    }

    public void println(Object o)
    {
        println(String.valueOf(o));
    }

    /** Stream semantics: ONE raw byte on the wire, never re-encoded. */
    public void write(int b)
    {
        byte[] one = new byte[1];
        one[0] = (byte) b;
        Magic.printStr(one);
    }

    public void flush()
    {
    }

    public PrintStream printf(String fmt, Object... args)
    {
        return format(fmt, args);
    }

    /**
     * A self-contained mini-{@code Formatter} covering the conversion syntax the JDK test suite's summary +
     * diagnostic prints use — {@code %[flags][width][.precision]conv} for conv in {@code n % d x X o b c s S} —
     * over any boxed {@code Number} (so {@code %d} with a {@code long} works, not just {@code int}). Flags
     * {@code -} (left-justify) and {@code 0} (zero-pad) + width padding are applied; other flags
     * ({@code + # , ( space}) are parsed and ignored; precision truncates {@code %s}. This deliberately avoids
     * stock {@code java.util.Formatter} (deep nio/locale closure) and {@code String.toUpperCase} (locale
     * closure) — uppercasing is ASCII-only here. Positional args ({@code %1$s}) are not supported.
     */
    public PrintStream format(String fmt, Object... args)
    {
        StringBuilder sb = new StringBuilder();
        int ai = 0;
        int i = 0;
        int n = fmt.length();
        while (i < n)
        {
            char c = fmt.charAt(i);
            if (c != '%')
            {
                sb.append(c);
                i += 1;
                continue;
            }
            int j = i + 1;                                  // parse flags
            boolean left = false;
            boolean zero = false;
            while (j < n)
            {
                char f = fmt.charAt(j);
                if (f == '-')
                {
                    left = true;
                }
                else if (f == '0')
                {
                    zero = true;
                }
                else if (f == '+' || f == ' ' || f == '#' || f == ',' || f == '(')
                {
                    // parsed and ignored
                }
                else
                {
                    break;
                }
                j += 1;
            }
            int width = 0;                                  // parse width
            while (j < n && fmt.charAt(j) >= '0' && fmt.charAt(j) <= '9')
            {
                width = width * 10 + (fmt.charAt(j) - '0');
                j += 1;
            }
            int prec = -1;                                  // parse precision
            if (j < n && fmt.charAt(j) == '.')
            {
                j += 1;
                prec = 0;
                while (j < n && fmt.charAt(j) >= '0' && fmt.charAt(j) <= '9')
                {
                    prec = prec * 10 + (fmt.charAt(j) - '0');
                    j += 1;
                }
            }
            if (j >= n)
            {
                sb.append('%');
                break;
            }
            char conv = fmt.charAt(j);
            i = j + 1;
            if (conv == 'n')
            {
                sb.append('\n');
                continue;
            }
            if (conv == '%')
            {
                sb.append('%');
                continue;
            }
            String out;
            if (conv == 'd')
            {
                out = Long.toString(numLong(args[ai]));
                ai += 1;
            }
            else if (conv == 'x')
            {
                out = Long.toHexString(numLong(args[ai]));
                ai += 1;
            }
            else if (conv == 'X')
            {
                out = upper(Long.toHexString(numLong(args[ai])));
                ai += 1;
            }
            else if (conv == 'o')
            {
                out = Long.toOctalString(numLong(args[ai]));
                ai += 1;
            }
            else if (conv == 'b')
            {
                Object a = args[ai];
                ai += 1;
                out = a == null ? "false" : (a instanceof Boolean ? a.toString() : "true");
            }
            else if (conv == 'c')
            {
                Object a = args[ai];
                ai += 1;
                char ch = a instanceof Character ? ((Character) a).charValue() : (char) numLong(a);
                out = String.valueOf(ch);
            }
            else if (conv == 's' || conv == 'S')
            {
                Object a = args[ai];
                ai += 1;
                out = a == null ? "null" : a.toString();
                if (prec >= 0 && out.length() > prec)
                {
                    out = out.substring(0, prec);
                }
                if (conv == 'S')
                {
                    out = upper(out);
                }
            }
            else
            {
                sb.append('%');
                sb.append(conv);
                continue;
            }
            pad(sb, out, width, left, zero && conv != 's' && conv != 'S');
        }
        print(sb.toString());
        return this;
    }

    /** Append {@code out} padded to {@code width} — left-justified, or right-justified with spaces (or '0'). */
    private static void pad(StringBuilder sb, String out, int width, boolean left, boolean zero)
    {
        int gap = width - out.length();
        if (gap <= 0)
        {
            sb.append(out);
            return;
        }
        if (left)
        {
            sb.append(out);
            while (gap > 0)
            {
                sb.append(' ');
                gap -= 1;
            }
            return;
        }
        char pc = zero ? '0' : ' ';
        while (gap > 0)
        {
            sb.append(pc);
            gap -= 1;
        }
        sb.append(out);
    }

    /** The 64-bit value of a boxed integral/char arg (the overlay {@code Number} has no {@code longValue}, so
     *  dispatch on the concrete boxed type instead). */
    private static long numLong(Object a)
    {
        if (a instanceof Integer)
        {
            return ((Integer) a).intValue();
        }
        if (a instanceof Long)
        {
            return ((Long) a).longValue();
        }
        if (a instanceof Short)
        {
            return ((Short) a).shortValue();
        }
        if (a instanceof Byte)
        {
            return ((Byte) a).byteValue();
        }
        if (a instanceof Character)
        {
            return ((Character) a).charValue();
        }
        return 0L;
    }

    /** ASCII-only uppercase (avoids {@code String.toUpperCase}'s locale/special-casing closure). */
    private static String upper(String s)
    {
        char[] cs = s.toCharArray();
        int i = 0;
        while (i < cs.length)
        {
            if (cs[i] >= 'a' && cs[i] <= 'z')
            {
                cs[i] = (char) (cs[i] - 32);
            }
            i += 1;
        }
        return new String(cs);
    }

    /** {@code Appendable}-shaped and raw-byte writes, plus {@code close}. */
    public PrintStream append(char c)
    {
        print(c);
        return this;
    }

    public PrintStream append(CharSequence cs)
    {
        print(cs == null ? "null" : cs.toString());
        return this;
    }

    public void write(byte[] buf, int off, int len)
    {
        if (out == null)
        {
            byte[] slice = new byte[len];
            System.arraycopy(buf, off, slice, 0, len);
            Magic.printStr(slice);
            return;
        }
        try
        {
            out.write(buf, off, len);
        }
        catch (IOException e)
        {
        }
    }

    /** Closing the UART would silence the console for the rest of the boot, so only a WRAPPED stream closes. */
    public void close()
    {
        if (out != null)
        {
            try
            {
                out.close();
            }
            catch (IOException e)
            {
            }
        }
    }
}
