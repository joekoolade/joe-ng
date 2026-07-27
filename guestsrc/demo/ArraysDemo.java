package demo;

import java.util.Arrays;
import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code java.util.Arrays} on metal: {@code fill} (pure loop), {@code equals}
 * (via a mini {@code ArraysSupport.mismatch}), and {@code binarySearch} (its private {@code binarySearch0}
 * is a leaf). Real array algorithms on {@code int[]} — the array side of the surface.
 */
public class ArraysDemo
{
    public static void main()
    {
        int[] a = new int[5];
        Arrays.fill(a, 7);
        showInt("fill(a,7) -> a[0]+a[4]", a[0] + a[4]);          // 14

        int[] b = { 1, 2, 3, 4, 5 };
        int[] c = { 1, 2, 3, 4, 5 };
        int[] d = { 1, 2, 9, 4, 5 };
        showBool("equals(b,c)", Arrays.equals(b, c));            // 1
        showBool("equals(b,d)", Arrays.equals(b, d));            // 0

        int[] s = { 2, 4, 6, 8, 10, 12 };
        showInt("binarySearch(s,8)", Arrays.binarySearch(s, 8)); // 3
        showInt("binarySearch(s,10)", Arrays.binarySearch(s, 10)); // 4
        showInt("binarySearch(s,5)", Arrays.binarySearch(s, 5)); // -3 (insertion point 2 -> -(2)-1)
    }

    private static void showBool(String label, boolean v)
    {
        showInt(label, v ? 1 : 0);
    }

    private static void showInt(String label, int v)
    {
        Magic.printStr("  Arrays.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(Integer.toString(v));
        Magic.printStr("\n");
    }
}
