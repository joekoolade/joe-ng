package java.util;

/**
 * A JDK-free, sequential {@code java/util/DualPivotQuicksort} overlay (wins by name; package-private like the
 * stock class, so {@code Arrays.sort} in the same package binds to it). The stock class references its PARALLEL
 * path — {@code ForkJoinTask}/{@code ThreadLocalRandom}/{@code LockSupport}/{@code Random} — which RTA pulls
 * (branch reachability is static), exploding the closure and tripping an unsupported {@code invokedynamic}
 * (0xBA) somewhere in the ForkJoin machinery. We don't have a scheduler-backed ForkJoin pool on metal, so we
 * substitute a plain sequential sort: {@code Arrays.sort(int[])} still calls {@code sort([IIII)V}, but the body
 * is a simple in-place sort of {@code a[low..high)} that ignores the {@code parallelism} hint and pulls nothing.
 *
 * <p>Insertion sort (O(n^2)) — fine for the small arrays metal demos sort; swap for a real quicksort if a hot
 * path ever needs it. Covers {@code int[]}, {@code long[]}, {@code double[]} and {@code float[]}; extend to
 * other primitives on demand.
 *
 * <p>The {@code double[]}/{@code float[]} overloads sort by IEEE-754 <em>total order</em> ({@code Double.compare}/
 * {@code Float.compare}, which canonicalise NaN via {@code doubleToLongBits}/{@code floatToIntBits}): NaN sorts
 * greatest and {@code -0.0} sorts below {@code +0.0}. Stock {@code Arrays.sort(double[])} demands exactly this
 * (see {@code FloatDoubleOrder}), and a naive {@code >} insertion sort would mis-order both NaN and signed zero.
 */
final class DualPivotQuicksort
{
    private DualPivotQuicksort()
    {
    }

    // Arrays.sort(int[] a) -> sort(a, 0, 0, a.length): (array, parallelism, low, high). parallelism ignored.
    static void sort(int[] a, int parallelism, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            int key = a[i];
            int j = i - 1;
            while (j >= low && a[j] > key)
            {
                a[j + 1] = a[j];
                j -= 1;
            }
            a[j + 1] = key;
            i += 1;
        }
    }

    static void sort(long[] a, int parallelism, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            long key = a[i];
            int j = i - 1;
            while (j >= low && a[j] > key)
            {
                a[j + 1] = a[j];
                j -= 1;
            }
            a[j + 1] = key;
            i += 1;
        }
    }

    // Total-order sort: Double.compare(a[j], key) > 0 puts NaN last and -0.0 before +0.0 (stable).
    static void sort(double[] a, int parallelism, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            double key = a[i];
            int j = i - 1;
            while (j >= low && Double.compare(a[j], key) > 0)
            {
                a[j + 1] = a[j];
                j -= 1;
            }
            a[j + 1] = key;
            i += 1;
        }
    }

    static void sort(float[] a, int parallelism, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            float key = a[i];
            int j = i - 1;
            while (j >= low && Float.compare(a[j], key) > 0)
            {
                a[j + 1] = a[j];
                j -= 1;
            }
            a[j + 1] = key;
            i += 1;
        }
    }
}
