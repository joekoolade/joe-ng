package demo;

import magic.Magic;

/**
 * String search + slice on metal: {@code indexOf(String)}, {@code indexOf(char)}, {@code substring(int)},
 * {@code substring(int,int)} on the real-shaped mini {@link String} (LATIN1). Chained too (substring of an
 * indexOf result). Int results printed via real {@code Integer.toString}.
 */
public class StrOpsDemo
{
    public static void main()
    {
        String s = "hello world";
        showInt("indexOf(\"world\")", s.indexOf("world"));                 // 6
        showInt("indexOf(\"lo\")", s.indexOf("lo"));                       // 3
        showInt("indexOf(\"xyz\")", s.indexOf("xyz"));                     // -1
        showInt("indexOf('o')", s.indexOf('o'));                           // 4
        showStr("substring(6)", s.substring(6));                           // world
        showStr("substring(0,5)", s.substring(0, 5));                      // hello
        showStr("substring(indexOf(' ')+1)", s.substring(s.indexOf(' ') + 1));   // world

        String h = "hello";
        showBool("h.startsWith(\"hel\")", h.startsWith("hel"));            // 1
        showBool("h.startsWith(\"xyz\")", h.startsWith("xyz"));            // 0
        showBool("h.startsWith(\"hello!\")", h.startsWith("hello!"));      // 0 (prefix longer)
        showInt("h.compareTo(\"hello\")", h.compareTo("hello"));           // 0
        showInt("h.compareTo(\"apple\")", h.compareTo("apple"));           // 7  ('h'-'a')
        showInt("h.compareTo(\"world\")", h.compareTo("world"));           // -15 ('h'-'w')
        showInt("h.compareTo(\"hell\")", h.compareTo("hell"));             // 1  (length)

        showStr("\"  hi  \".trim()", "  hi  ".trim());                      // hi
        showStr("\"nospace\".trim()", "nospace".trim());                   // nospace
        showStr("\"   \".trim()", "[" + "   ".trim() + "]");               // []
        showStr("\"a.b.c\".replace('.','/')", "a.b.c".replace('.', '/'));  // a/b/c
        showStr("\"hello\".replace('l','L')", "hello".replace('l', 'L'));  // heLLo
        showStr("\"none\".replace('x','y')", "none".replace('x', 'y'));    // none

        showStr("join(\",\", \"a\",\"b\",\"c\")", String.join(",", "a", "b", "c"));      // a,b,c  (varargs)
        showStr("join(\"/\", \"one\")", String.join("/", "one"));                        // one    (single elem)

        // split() (#43): a MULTI-char delimiter "::" takes String.split's REGEX path (Pattern.compile), unlike
        // single-char delimiters which take the fast path. Reaches the java.util.regex Pattern closure -- the
        // wall is the reachable-class count (>1024 -> MAXBLOB) + ICU/Locale/foreign subtrees pruned via the
        // denylist.
        String[] parts = "a::b::c".split("::");
        showInt("split(\"::\").length", parts.length);     // 3
        showStr("split[0]", parts[0]);                      // a
        showStr("split[1]", parts[1]);                      // b
        showStr("split[2]", parts[2]);                      // c

        // toUpperCase/toLowerCase (#42): the no-arg forms call Locale.getDefault(); the stock initDefault reads
        // system properties (unrunnable on metal), so a minimal guest Locale overlay returns ENGLISH. The LATIN1
        // happy path is StringLatin1.to{Upper,Lower}Case -> CharacterDataLatin1 per-char tables (already loaded
        // for split) + Locale.getLanguage()=="en" skipping the tr/az/lt Turkic branch.
        showStr("\"HeLLo\".toUpperCase()", "HeLLo".toUpperCase());        // HELLO
        showStr("\"WoRLD\".toLowerCase()", "WoRLD".toLowerCase());        // world
        showStr("\"abc123\".toUpperCase()", "abc123".toUpperCase());      // ABC123 (digits pass through)
        showStr("\"MixEd\".toLowerCase()", "MixEd".toLowerCase());        // mixed

        // switch opcodes (compiler slice for real java.base): dense -> tableswitch, sparse -> lookupswitch.
        showInt("dense(0)", dense(0));            // 10
        showInt("dense(2)", dense(2));            // 12
        showInt("dense(9)", dense(9));            // -1  (default)
        showInt("sparse(1)", sparse(1));          // 100
        showInt("sparse(1000)", sparse(1000));    // 300
        showInt("sparse(5)", sparse(5));          // -9  (default)

        // System.out.println (M2): stock System.out/err are set by the native-heavy initPhase1/setOut0 (absent
        // on metal), so Loader.seedSystemStreams installs a guest PrintStream overlay that encodes Latin1 and
        // writes straight to the UART. getstatic System.out -> invokevirtual println dispatches to the overlay.
        System.out.println("System.out.println works on metal");
        System.out.println("concat int=" + 42 + " bool=" + true);   // reduces to println(String)
        System.out.print("print(no-newline) ");
        System.out.println("then newline");
        System.out.println(12345);                 // println(int)  -> Integer.toString
        System.out.println(-7);                    // println(int)  negative
        System.out.println(true);                  // println(boolean)
        System.err.println("System.err.println works too");
    }

    private static int dense(int x)
    {
        switch (x)
        {
            case 0: return 10;
            case 1: return 11;
            case 2: return 12;
            case 3: return 13;
            default: return -1;
        }
    }

    private static int sparse(int x)
    {
        switch (x)
        {
            case 1: return 100;
            case 100: return 200;
            case 1000: return 300;
            default: return -9;
        }
    }


    private static void showBool(String label, boolean v)
    {
        showInt(label, v ? 1 : 0);
    }

    private static void showStr(String label, String v)
    {
        Magic.printStr("  Str.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(v);
        Magic.printStr("\n");
    }

    private static void showInt(String label, int v)
    {
        showStr(label, Integer.toString(v));
    }
}
