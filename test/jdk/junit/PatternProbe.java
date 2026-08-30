import java.util.regex.Pattern;

/** Isolate the \p{Cntrl} compile that kills the JUnit failure path, in a closure QEMU can run in a minute. */
public class PatternProbe {
    private static void t(String what, Runnable r) {
        try { r.run(); System.out.println("  " + what + " ok"); }
        catch (Throwable e) { System.out.println("  " + what + " -> " + e.getClass().getName()); }
    }

    public static void main(String[] args) {
        System.out.println("pattern probe:");
        t("compile(\"abc\")        ", () -> Pattern.compile("abc"));
        t("compile(\"[a-z]\")      ", () -> Pattern.compile("[a-z]"));
        t("compile(\"\\\\s\")         ", () -> Pattern.compile("\\s"));
        t("compile(\"\\\\p{Cntrl}\")  ", () -> Pattern.compile("\\p{Cntrl}"));
        t("compile(\"\\\\p{Cntrl}\",256)", () -> Pattern.compile("\\p{Cntrl}", 256));
        t("compile(\"\\\\p{Alpha}\")  ", () -> Pattern.compile("\\p{Alpha}"));
    }
}
