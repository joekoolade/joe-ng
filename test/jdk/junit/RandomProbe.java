/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-09
 */
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code java.util.Random}: its seeded sequences, the bounded/ranged {@code RandomGenerator} surface, and a SHARED
 * instance under four threads.
 *
 * <p>A seeded {@code Random} is fully specified (the 48-bit LCG and every derived method), so each arm prints
 * values a host JVM prints identically -- the diff is the gate, not "looks random". The ranged methods
 * ({@code nextInt(origin, bound)}, {@code nextLong(bound)}, {@code nextDouble(origin, bound)}, ...) are the
 * members the joe-ng overlay dropped; {@code ThreadLocalRandom} inherits them, and stock
 * {@code ConcurrentHashMap} now pulls that class in.
 *
 * <p>The shared-instance arm is the adversarial one. Stock {@code Random.next} advances the seed with a CAS loop, so
 * under contention every seed step is consumed EXACTLY ONCE: four threads drawing from one {@code Random(7)}
 * produce, between them, the same multiset of values as one thread drawing 4N. A non-atomic {@code next} hands the
 * same step to two threads -- duplicates in, other values missing.
 */
public class RandomProbe
{
    private static final int THREADS = 4;
    private static final int N = 2000;

    public static void main(String[] args) throws Exception
    {
        Random r = new Random(42L);
        System.out.println("nextInt = " + r.nextInt() + ", nextInt(100) = " + r.nextInt(100)
                + ", nextLong = " + r.nextLong());
        System.out.println("nextDouble = " + r.nextDouble() + ", nextFloat = " + r.nextFloat()
                + ", nextBoolean = " + r.nextBoolean() + ", nextGaussian = " + r.nextGaussian());
        byte[] b = new byte[7];
        r.nextBytes(b);
        System.out.println("nextBytes = " + Arrays.toString(b));
        System.out.println("nextInt(5, 10) = " + r.nextInt(5, 10) + ", nextLong(1000) = " + r.nextLong(1000L)
                + ", nextLong(10, 20) = " + r.nextLong(10L, 20L));
        System.out.println("nextDouble(5.0) = " + r.nextDouble(5.0) + ", nextDouble(1, 2) = " + r.nextDouble(1.0, 2.0)
                + ", nextFloat(2) = " + r.nextFloat(2.0f) + ", nextFloat(1, 3) = " + r.nextFloat(1.0f, 3.0f));
        System.out.println("ints(5, 0, 10) = " + Arrays.toString(new Random(3L).ints(5, 0, 10).toArray()));
        r.setSeed(42L);
        System.out.println("setSeed(42) replays: " + (r.nextInt() == new Random(42L).nextInt()));
        System.out.println("two unseeded instances differ: " + (new Random().nextLong() != new Random().nextLong()));

        int t = ThreadLocalRandom.current().nextInt(1, 10);
        long u = ThreadLocalRandom.current().nextLong(100L);
        double d = ThreadLocalRandom.current().nextDouble(2.0, 3.0);
        System.out.println("ThreadLocalRandom ranged in bounds: " + (t >= 1 && t < 10) + " " + (u >= 0 && u < 100)
                + " " + (d >= 2.0 && d < 3.0));

        Random solo = new Random(7L);
        int[] want = new int[THREADS * N];
        int i = 0;
        while (i < want.length)
        {
            want[i] = solo.nextInt();
            i += 1;
        }
        final Random shared = new Random(7L);
        final int[][] got = new int[THREADS][N];
        Thread[] ts = new Thread[THREADS];
        int k = 0;
        while (k < THREADS)
        {
            final int id = k;
            ts[k] = new Thread(() ->
            {
                int j = 0;
                while (j < N)
                {
                    got[id][j] = shared.nextInt();
                    j += 1;
                }
            });
            ts[k].start();
            k += 1;
        }
        k = 0;
        while (k < THREADS)
        {
            ts[k].join();
            k += 1;
        }
        int[] all = new int[THREADS * N];
        k = 0;
        while (k < THREADS)
        {
            System.arraycopy(got[k], 0, all, k * N, N);
            k += 1;
        }
        Arrays.sort(all);
        Arrays.sort(want);
        System.out.println("shared Random(7), 4 threads: same values as one thread drawing " + (THREADS * N) + " = "
                + Arrays.equals(all, want));
        System.out.println("RandomProbe done");
    }
}
