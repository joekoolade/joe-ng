package jdk.internal.util;

/**
 * A JDK-free, real-shaped {@code jdk/internal/util/ArraysSupport}: just {@code mismatch(int[],int[],int)},
 * which real {@code Arrays.equals(int[],int[])} calls. The real one is Unsafe-vectorized; this compares
 * element-by-element (same contract: the first differing index in {@code [0,length)}, or -1 if all equal).
 * Compiled as a {@code java.base} patch so it carries the real name.
 */
public class ArraysSupport
{
    public static int mismatch(int[] a, int[] b, int length)
    {
        int i = 0;
        while (i < length)
        {
            if (a[i] != b[i])
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }
}
