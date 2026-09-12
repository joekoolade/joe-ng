/**
 * Exercises String.format / String.formatted directly, in the shapes JUnit's AssertionFailureBuilder uses,
 * so a failure there can be told apart from a failure in JUnit's own message plumbing.
 */
public class FormatProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("format probe:");
        show("plain", "no conversions");
        show("one %s", "A");
        show("expected: <%s> but was: <%s>", "2", "3");
        show("expected: %s but was: %s", "x", "y");
        show("%d and %d", Integer.valueOf(7), Long.valueOf(8L));
        show("%x %o %c %b", Integer.valueOf(255), Integer.valueOf(8), Character.valueOf('Z'), Boolean.TRUE);
        show("null arg <%s>", (Object) null);
        show("100%% done", new Object[0]);
        show("%-10s|", "pad");
        show("%q unknown", "arg");

        // the exact call formatValues makes
        String v = "expected: <%s> but was: <%s>".formatted("2", "3");
        System.out.println("  formatted() = [" + v + "]");
        System.out.println("  length      = " + v.length());
        // The PrintWriter path, which is a DIFFERENT construction from String.format: stock builds
        // `new Formatter(this)` and expects the formatter to write THROUGH to the writer rather than
        // accumulate. An overlay missing that constructor traps; one that accumulates instead of writing
        // prints NOTHING and looks like a working call -- so these arms check the TEXT ARRIVED, not that the
        // call returned.
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        pw.printf("pw %s=%d", "n", 7);
        pw.flush();
        System.out.println("  printf -> [" + sw.toString() + "] (want [pw n=7])");

        // Called TWICE on the same writer: PrintWriter caches its Formatter, so a flush that failed to reset
        // would repeat the first call's text here and a one-shot arm would not see it.
        java.io.StringWriter sw2 = new java.io.StringWriter();
        java.io.PrintWriter pw2 = new java.io.PrintWriter(sw2);
        pw2.printf("a%d", 1);
        pw2.printf("b%d", 2);
        pw2.flush();
        System.out.println("  printf x2 -> [" + sw2.toString() + "] (want [a1b2])");

        // String.format must still accumulate -- the sink-less path, unchanged.
        System.out.println("  format still ok = [" + String.format("%s-%d", "x", 9) + "] (want [x-9])");

        System.out.println("survived");
    }

    private static void show(String fmt, Object... args)
    {
        String out;
        try
        {
            out = String.format(fmt, args);
        }
        catch (Throwable t)
        {
            out = "THREW " + t.getClass().getName();
        }
        System.out.println("  [" + fmt + "] -> [" + out + "]");
    }
}
