/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-02
 */
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
 * <p>WHY THE OVERLAY STAYS, MEASURED RATHER THAN READ OFF THIS COMMENT: the blocker is the ForkJoin RUNTIME,
 * not an opcode. Stock's own blob names only 25 {@code CONSTANT_Class} entries and none of them is a ForkJoin
 * class; it is the NESTED {@code Sorter} and {@code Merger} that extend {@code CountedCompleter} and
 * {@code RunMerger} that extends {@code RecursiveTask}. And its three {@code invokedynamic} sites are
 * ORDINARY {@code LambdaMetafactory.metafactory} lambdas (method references to {@code mixedInsertionSort} and
 * {@code insertionSort}), which this VM synthesises perfectly well -- so "an unsupported 0xBA" was never this
 * class's own. What is genuinely absent is a scheduler-backed common pool, the same absence that made
 * {@code BigInteger.<clinit>} -> {@code RecursiveOp} -> {@code ForkJoinPool.getCommonPoolParallelism} a
 * landmine this project backed out of once.
 *
 * <p>ALL SEVEN PRIMITIVE TYPES ARE COVERED, and the three added last are a DIFFERENT SIGNATURE SHAPE, which
 * is the trap in extending this class. {@code Arrays.sort(int[]/long[]/float[]/double[])} calls
 * {@code sort(a, parallelism, low, high)} -- FOUR arguments -- because those have a parallel variant;
 * {@code Arrays.sort(byte[]/char[]/short[])} calls {@code sort(a, low, high)} -- THREE -- because stock sorts
 * those with a counting sort, which has none. Declaring the four-argument shape for a byte array leaves
 * {@code sort([BII)V} resolving nowhere exactly as before, while LOOKING fixed; {@code overlaycheck-deep} is
 * what named all three, each "referenced by java/util/Arrays".
 *
 * <p>Insertion sort (O(n^2)) throughout — fine for the small arrays metal demos sort; swap for a real
 * quicksort if a hot path ever needs it. STATED LIMIT, because {@code Arrays.sort} is public API a guest
 * program may call with any array: stock is O(n log n) for these four and O(n + range) counting sort for
 * byte/char/short, so a large array is far slower here than on a real JVM. It is a cost, not a wrong answer.
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

    // Arrays.sort(byte[] a) -> sort(a, 0, a.length): (array, low, high). NO parallelism argument -- stock
    // sorts byte/char/short with a counting sort, which has no parallel variant, so the descriptor is
    // sort([BII)V and not sort([BIII)V. Declaring the four-argument shape here would leave the call
    // resolving nowhere while looking fixed.
    static void sort(byte[] a, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            byte key = a[i];
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

    // char is UNSIGNED (0..65535). Java promotes both operands to int and zero-extends a char, so a plain
    // `>` is the correct unsigned comparison here -- unlike byte and short, which sign-extend and are meant to.
    static void sort(char[] a, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            char key = a[i];
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

    static void sort(short[] a, int low, int high)
    {
        int i = low + 1;
        while (i < high)
        {
            short key = a[i];
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
}
