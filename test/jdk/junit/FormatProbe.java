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
