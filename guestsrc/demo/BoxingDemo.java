package demo;

import java.util.HashMap;
import magic.Magic;

/**
 * Real {@code Integer.valueOf} autoboxing on metal, with the {@code [-128,127]} cache seeded by the loader
 * (its CDS/property-driven <clinit> isn't runnable). Small keys hit the cache (valueOf returns the SAME
 * interned instance — identity ==); large keys take the {@code new Integer} path (distinct instances,
 * content-matched). HashMap dispatches key.hashCode()/equals() through the mini {@link Object} root's vtable
 * slots down the {@code Integer -> Number -> Object} chain into Integer's real implementations.
 */
public class BoxingDemo
{
    public static void main(String[] args)
    {
        HashMap map = new HashMap();
        map.put(Integer.valueOf(5), "five");                                 // cached
        map.put(Integer.valueOf(-100), "neg-hundred");                       // cached
        map.put(Integer.valueOf(1000), "thousand");                         // new Integer (out of cache)

        showStr("get(box 5)", (String) map.get(Integer.valueOf(5)));         // five
        showStr("get(box -100)", (String) map.get(Integer.valueOf(-100)));   // neg-hundred
        showStr("get(box 1000)", (String) map.get(Integer.valueOf(1000)));   // thousand (distinct box, content match)
        showInt("hashCode(box -100)", Integer.valueOf(-100).hashCode());     // -100
        showInt("size", map.size());                                         // 3

        // The cache: valueOf returns the SAME instance for small ints, a fresh one otherwise.
        showBool("valueOf(5)==valueOf(5) cached", Integer.valueOf(5) == Integer.valueOf(5));         // 1
        showBool("valueOf(1000)==valueOf(1000) new", Integer.valueOf(1000) == Integer.valueOf(1000)); // 0
    }

    private static void showBool(String label, boolean v)
    {
        showInt(label, v ? 1 : 0);
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
