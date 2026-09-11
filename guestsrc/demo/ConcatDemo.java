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
    public static void main(String[] args)
    {
        int n = 42;
        int k = 7;
        String s = "value=" + n + " k=" + k + "\n";     // int args + literals
        Magic.printStr(s);

        int sum = n + k;
        String t = "sum=" + sum + " (neg " + (0 - sum) + ")\n";
        Magic.printStr(t);

        // slice 1b: a String-object arg (s2 feeds a second concat) + a long arg + a bare-literal print.
        long big = 1234567890123L;
        String label = "val=" + n;                      // -> a String object
        String u = label + " big=" + big + "!\n";       // String arg (label) + long arg (big)
        Magic.printStr(u);
        Magic.printStr("bare literal ok\n");            // a raw byte[] literal -> printStr handles it too

        // A NULL REFERENCE CONCATENATES AS "null" (JLS 15.18.1). This printed an EMPTY STRING, and not
        // merely cosmetically: the append read `0 + 16`, i.e. address 16 -- low firmware memory, readable --
        // so a non-zero length there would have appended that many bytes of garbage.
        String ns = null;
        Object no = null;
        System.out.println("null String  = [" + ns + "] (want [null])");
        System.out.println("null Object  = [" + no + "] (want [null])");
        // Surrounded by text on BOTH sides: an append that emitted nothing still looks right at the end.
        System.out.println("null middle  = [a" + ns + "b] (want [anullb])");
        // Two in a row, so a fix that emits one "null" and stops is caught.
        System.out.println("null twice   = [" + ns + no + "] (want [nullnull])");
        // A non-null reference beside it, so the fix cannot be "always print null".
        System.out.println("null mixed   = [" + ns + "x" + no + "] (want [nullxnull])");
    }
}
