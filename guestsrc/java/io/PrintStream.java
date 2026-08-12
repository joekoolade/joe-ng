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
 * <p>Field-free by design (instance size stays a bare 16-byte header). Numeric overloads route through the
 * already-working {@code Integer.toString}/{@code Long.toString}; only methods a demo actually reaches compile.
 */
public class PrintStream
{
    public PrintStream()
    {
    }

    /** The one sink: stock UTF-8 encode, then raw bytes to the UART ({@code putc} translates {@code \n}). */
    private static void emit(String s)
    {
        Magic.printStr(s.getBytes());
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
     * A minimal {@code format}: enough of the conversion syntax the reached paths use ({@code %n} newline,
     * {@code %d} decimal, {@code %s} string, {@code %%} literal). No width/precision/flags -- stock
     * {@code java.util.Formatter} pulls the deep nio/locale closure that is stubbed out on metal.
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
            if (c == '%' && i + 1 < n)
            {
                char spec = fmt.charAt(i + 1);
                i += 2;
                if (spec == 'n')
                {
                    sb.append('\n');
                }
                else if (spec == '%')
                {
                    sb.append('%');
                }
                else if (spec == 'd')
                {
                    sb.append(((Integer) args[ai]).intValue());
                    ai += 1;
                }
                else if (spec == 's')
                {
                    Object a = args[ai];
                    sb.append(a == null ? "null" : a.toString());
                    ai += 1;
                }
                else
                {
                    sb.append('%');
                    sb.append(spec);
                }
            }
            else
            {
                sb.append(c);
                i += 1;
            }
        }
        print(sb.toString());
        return this;
    }
}
