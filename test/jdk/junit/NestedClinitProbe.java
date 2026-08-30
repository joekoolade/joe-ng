import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Minimal shape of the JUnit failure: a class whose <clinit> runs nested inside a LAZY COMPILE, because the
 * only thing that reaches it is a reflective call. Pattern.compile at top level is fine (PatternProbe), so
 * several shapes are tried here to find which part of the nesting breaks.
 */
public class NestedClinitProbe {

    static class Trivial   { static final int V = 7;                                   static int use() { return V; } }
    static class Alloc     { static final int[] A = new int[4];                        static int use() { return A.length; } }
    static class Str       { static final StringBuilder S = new StringBuilder();    static int use() { S.append(1); return S.length(); } }
    static class Regex     { static final Pattern P = Pattern.compile("\\p{Cntrl}");   static int use() { return P == null ? 0 : 1; } }
    static class PlainRe   { static final Pattern P = Pattern.compile("abc");          static int use() { return P == null ? 0 : 1; } }

    public static void viaTrivial() { System.out.println("  Trivial = " + Trivial.use()); }
    public static void viaAlloc()   { System.out.println("  Alloc   = " + Alloc.use()); }
    public static void viaStr()     { System.out.println("  Str     = " + Str.use()); }
    public static void viaPlainRe() { System.out.println("  PlainRe = " + PlainRe.use()); }
    public static void viaRegex()   { System.out.println("  Regex   = " + Regex.use()); }

    private static void run(String name) {
        try {
            Method m = NestedClinitProbe.class.getDeclaredMethod(name);
            m.invoke(null, new Object[0]);
        } catch (Throwable t) {
            System.out.println("  " + name + " -> " + t.getClass().getName());
        }
    }

    public static void main(String[] args) {
        System.out.println("nested clinit probe:");
        // Warm Pattern at TOP LEVEL first: if the nested cases then pass, the bug is about INITIALIZING
        // Pattern from a nested clinit, not about calling it there.
        System.out.println("  warm  = " + (Pattern.compile("z") == null ? 0 : 1));
        run("viaTrivial");
        run("viaAlloc");
        run("viaStr");
        run("viaPlainRe");
        run("viaRegex");
        System.out.println("  survived");
    }
}
