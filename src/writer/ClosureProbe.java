package writer;

import util.StrIntTable;
import util.StrSet;
import util.Vec;

import java.io.IOException;
import java.nio.file.Path;

/**
 * HOST-ONLY closure probe (task #41). Runs {@link ImageBuilder#analyzeClosure} over the SAME class set the
 * image embeds ({@link BuildRuntimeImage#populateRegistry}) and reports, for a given guest entry method, the
 * size and shape of the reachable class closure the metal demand-loader would pull — plus the exact call
 * chain that first drags in each named "offender" subtree. Lets us measure/attack the String-ops closure
 * without a hardware round-trip.
 *
 * <p>Usage: {@code java -cp out writer.ClosureProbe <entryKey> [classesDir]}
 * e.g. {@code writer.ClosureProbe "demo/StrOpsDemo.main()V"}
 */
public final class ClosureProbe
{
    private ClosureProbe() {}

    // Subtrees whose presence signals over-pull; the probe counts each and traces who pulled it.
    private static final String[] BUCKETS = {
        "java/util/regex/", "jdk/internal/icu/", "java/text/", "java/util/ServiceLoader",
        "java/lang/ClassLoader", "jdk/internal/loader/", "sun/nio/fs/", "java/nio/file/",
        "java/lang/foreign/", "jdk/internal/foreign/", "java/lang/invoke/", "java/util/stream/",
        "java/util/concurrent/", "sun/util/locale/", "java/util/Locale",
    };

    // The StrOpsDemo operations, each measured as its own entry (bypasses main's string-concat indy, which
    // the host compiler can't lower — that's loader-resident on metal — and gives per-op attribution).
    private static final String[] OPS = {
        "java/lang/String.indexOf(Ljava/lang/String;)I",
        "java/lang/String.indexOf(I)I",
        "java/lang/String.substring(I)Ljava/lang/String;",
        "java/lang/String.substring(II)Ljava/lang/String;",
        "java/lang/String.startsWith(Ljava/lang/String;)Z",
        "java/lang/String.compareTo(Ljava/lang/String;)I",
        "java/lang/String.trim()Ljava/lang/String;",
        "java/lang/String.replace(CC)Ljava/lang/String;",
        "java/lang/String.toUpperCase()Ljava/lang/String;",
        "java/lang/String.toLowerCase()Ljava/lang/String;",
        "java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;",
        "java/lang/String.join(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;",
    };

    public static void main(String[] args) throws IOException
    {
        Path classesDir = Path.of(args.length > 1 ? args[1] : "out");
        ClassRegistry registry = BuildRuntimeImage.populateRegistry(classesDir);
        ImageBuilder ib = new ImageBuilder(registry);

        if (args.length > 0)
        {
            detail(ib, args[0]);
            return;
        }
        // Default: per-op closure-size table (which ops are happy-path vs closure bombs).
        System.out.println("per-op reachable-class closure (MAXBLOB budget = 1024):");
        for (String op : OPS)
        {
            ImageBuilder.ClosureReport r = ib.analyzeClosure(op);
            System.out.println("  " + pad(String.valueOf(r.classes().size()), 6)
                    + (r.failures().size() > 0 ? "(" + r.failures().size() + " skip) " : "        ")
                    + op);
        }
        System.out.println();
        System.out.println("run with an entry-key arg for the offender buckets + full list of that op.");
    }

    private static void detail(ImageBuilder ib, String entry)
    {
        ImageBuilder.ClosureReport rep = ib.analyzeClosure(entry);

        StrSet classes = rep.classes();
        System.out.println("entry: " + entry);
        System.out.println("reachable classes: " + classes.size() + "   (MAXBLOB budget = 1024)");
        System.out.println("compile-skipped methods (jitFail-and-continue): " + rep.failures().size());
        for (int i = 0; i < rep.failures().size() && i < 25; i++)
        {
            System.out.println("    SKIP " + rep.failures().get(i));
        }
        System.out.println();

        System.out.println("=== offender subtrees (count :: shortest pull-chain to first member) ===");
        for (String b : BUCKETS)
        {
            int n = 0;
            String first = null;
            for (int i = 0; i < classes.size(); i++)
            {
                String c = classes.at(i);
                if (c.startsWith(b))
                {
                    n++;
                    if (first == null) { first = c; }
                }
            }
            if (n > 0)
            {
                System.out.println("  " + pad(b, 30) + n);
                System.out.println("      first: " + first);
                traceClass(rep, first);
            }
        }

        System.out.println();
        System.out.println("=== full class list (sorted) ===");
        String[] sorted = new String[classes.size()];
        for (int i = 0; i < classes.size(); i++) { sorted[i] = classes.at(i); }
        java.util.Arrays.sort(sorted);
        for (String c : sorted) { System.out.println("  " + c); }
    }

    /** Print the method-key chain from the first worklist key owned by {@code cls} back to the root. */
    private static void traceClass(ImageBuilder.ClosureReport rep, String cls)
    {
        StrIntTable parent = rep.parent();
        String start = null;
        for (int i = 0; i < parent.size(); i++)
        {
            String k = parent.keyAt(i);
            if (ownerOf(k).equals(cls)) { start = k; break; }
        }
        if (start == null) { System.out.println("      (no pull chain found)"); return; }
        Vec<String> pk = rep.parentKey();
        String k = start;
        int guard = 0;
        while (k != null && !k.equals("<root>") && guard++ < 40)
        {
            System.out.println("        <- " + k);
            if (!parent.containsKey(k)) { break; }
            k = pk.get(parent.get(k));
        }
    }

    private static String ownerOf(String key)
    {
        int paren = key.indexOf('(');
        int dot = key.lastIndexOf('.', paren >= 0 ? paren : key.length());
        return dot < 0 ? key : key.substring(0, dot);
    }

    private static String pad(String s, int n)
    {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) { b.append(' '); }
        return b.toString();
    }
}
