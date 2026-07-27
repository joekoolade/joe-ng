package demo;

import java.util.HashMap;
import magic.Magic;

/**
 * Autoboxing on metal: real {@code Integer.valueOf(int)} used as HashMap keys. The keys are outside the
 * [-128,127] cache range, so valueOf takes the {@code new Integer} path (IntegerCache's cache array is null
 * here -- its CDS/property-driven <clinit> isn't runnable, and the low bound -128 is inlined as a constant
 * while high reads 0, so only |v|>128 avoids the null-cache path). Lookups use a DISTINCT boxed Integer of
 * equal value, so they hit via the real {@code Integer.hashCode}/{@code equals} (dispatched through the mini
 * {@link Object} root's vtable slots down the Integer -> Number -> Object chain) — content-based, not identity.
 */
public class BoxingDemo
{
    public static void main()
    {
        HashMap map = new HashMap();
        map.put(Integer.valueOf(1000), "thousand");
        map.put(Integer.valueOf(2000), "two-thousand");
        map.put(Integer.valueOf(-1000), "neg-thousand");

        showStr("get(box 1000)", (String) map.get(Integer.valueOf(1000)));   // thousand
        showStr("get(box 2000)", (String) map.get(Integer.valueOf(2000)));   // two-thousand
        showStr("get(box -1000)", (String) map.get(Integer.valueOf(-1000))); // neg-thousand
        showInt("hashCode(box 1000)", Integer.valueOf(1000).hashCode());     // 1000
        showInt("size", map.size());                                         // 3
        showInt("containsKey(box 9999)", map.containsKey(Integer.valueOf(9999)) ? 1 : 0);   // 0
    }

    private static void showStr(String label, String v)
    {
        Magic.printStr("  Boxing.");
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
