package java.io;

import magic.Magic;

/**
 * A JDK-free, minimal {@code java/io/PrintStream} overlay (wins by name, like the mini exception / Locale
 * overlays). Stock {@code PrintStream.println(String)} routes through {@code textOut:BufferedWriter} ->
 * {@code OutputStreamWriter} -> {@code StreamEncoder}/{@code CharsetEncoder} — the deep nio charset closure —
 * and the raw sink under it is a native ({@code FileOutputStream.writeBytes}). Both are absent on metal, so we
 * substitute the whole class: every method encodes Latin1 and writes straight to the UART via the
 * {@code Magic.printStr} intrinsic (which already understands the stock {@code String} value@16/coder@24 shape).
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

    public void print(String s)
    {
        Magic.printStr(s == null ? "null" : s);
    }

    public void println(String s)
    {
        print(s);
        Magic.printStr("\n");
    }

    public void println()
    {
        Magic.printStr("\n");
    }

    public void print(int i)
    {
        Magic.printStr(Integer.toString(i));
    }

    public void println(int i)
    {
        println(Integer.toString(i));
    }

    public void print(long l)
    {
        Magic.printStr(Long.toString(l));
    }

    public void println(long l)
    {
        println(Long.toString(l));
    }

    public void print(boolean b)
    {
        Magic.printStr(b ? "true" : "false");
    }

    public void println(boolean b)
    {
        println(b ? "true" : "false");
    }

    public void print(char c)
    {
        Magic.printStr(String.valueOf(c));
    }

    public void println(char c)
    {
        print(c);
        Magic.printStr("\n");
    }

    public void print(Object o)
    {
        Magic.printStr(String.valueOf(o));
    }

    public void println(Object o)
    {
        println(String.valueOf(o));
    }

    public void write(int b)
    {
        Magic.printStr(String.valueOf((char) (b & 0xff)));
    }

    public void flush()
    {
    }
}
