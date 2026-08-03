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

        // sort(int[]) (#34): small arrays take DualPivotQuicksort's insertion-sort path (a plain loop, no
        // natives / Unsafe) -- the tractable slice of the real sort. Verify order + endpoints.
        int[] u = { 5, 2, 8, 1, 9, 3, 7, 4, 6, 0 };
        Arrays.sort(u);
        showInt("sort[0]", u[0]);                                // 0
        showInt("sort[9]", u[9]);                                // 9
        boolean asc = true;
        for (int i = 1; i < u.length; i++)
        {
            if (u[i] < u[i - 1])
            {
                asc = false;
            }
        }
        showBool("sort ascending", asc);                         // 1

        int[] neg = { 3, -1, 0, -5, 2 };
        Arrays.sort(neg);
        showInt("sort(neg)[0]", neg[0]);                         // -5
        showInt("sort(neg)[4]", neg[4]);                         // 3
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
