package demo;

import magic.Magic;

/**
 * The invokedynamic proof: real string concatenation. `"value=" + n + " k=" + k + "\n"` compiles to an
 * {@code invokedynamic StringConcatFactory.makeConcatWithConstants}, which the on-metal JIT intrinsifies
 * into a byte[] build wrapped in a mini {@code java/lang/String}. The writer embeds only these raw bytes;
 * the Loader demand-loads {@code java/lang/String}, JITs the concat, and {@code Magic.printStr} prints it.
 *
 * <p>Only concat results (real String objects) are printed — bare string literals are still raw byte[]
 * on metal, so we keep the newline inside the concat rather than passing a literal to printStr.
 */
public class ConcatDemo
{
    public static void main()
    {
        int n = 42;
        int k = 7;
        String s = "value=" + n + " k=" + k + "\n";     // -> invokedynamic makeConcatWithConstants
        Magic.printStr(s);

        int sum = n + k;
        String t = "sum=" + sum + " (neg " + (0 - sum) + ")\n";
        Magic.printStr(t);
    }
}
