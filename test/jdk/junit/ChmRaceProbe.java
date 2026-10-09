/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-08
 */
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code java.util.concurrent.ConcurrentHashMap} under real contention from four threads.
 *
 * <p>The joe-ng overlay this replaces was a {@code HashMap} subclass with no concurrency control, justified as
 * "joe-ng runs on a single unparked core" -- on a VM that schedules guest threads on four A72s. A concurrent
 * {@code put} into a plain {@code HashMap} loses entries (two threads resize or link into the same bucket), and
 * {@code merge}/{@code putIfAbsent} are check-then-act. Uses only members that overlay also had (it inherited
 * {@code HashMap}'s), so the same source runs against both; every arm prints a value, so the host diff is the gate.
 *
 * <ul>
 * <li>Disjoint puts: each thread inserts its own {@link #N} keys into one map. Every key must be present.</li>
 * <li>{@code merge(k, 1, Integer::sum)}, first from one thread (so a wrong count there is {@code merge} itself, not
 *     contention), then on {@link #HOT} shared keys: every count must equal {@code THREADS*ROUNDS}.</li>
 * <li>{@code putIfAbsent} race: exactly one winner per key.</li>
 * <li>{@code computeIfAbsent}: the mapping function runs exactly once per key.</li>
 * <li>Iteration while another thread writes: no {@code ConcurrentModificationException}.</li>
 * </ul>
 *
 * <p>The remapping function is {@code Integer::sum}, a method reference whose primitive parameters receive boxed
 * SAM arguments. Until the thunks unboxed them it summed two heap ADDRESSES (100 merges counted 91,635,776) --
 * which is how that bug was found; {@code MethodRefUnboxProbe} is its own regression.
 */
public class ChmRaceProbe
{
    private static final int THREADS = 4;
    private static final int N = 5000;
    private static final int HOT = 16;
    private static final int ROUNDS = 2000;

    public static void main(String[] args) throws Exception
    {
        final ConcurrentHashMap<Integer, Integer> disjoint = new ConcurrentHashMap<>();
        run(id ->
        {
            int i = 0;
            while (i < N)
            {
                disjoint.put(id * N + i, i);
                i += 1;
            }
        });
        int missing = 0;
        int k = 0;
        while (k < THREADS * N)
        {
            if (!disjoint.containsKey(k))
            {
                missing += 1;
            }
            k += 1;
        }
        System.out.println("disjoint puts: size = " + disjoint.size() + " (want " + (THREADS * N) + "), missing = " + missing);

        ConcurrentHashMap<Integer, Integer> one = new ConcurrentHashMap<>();
        int m = 0;
        while (m < 100)
        {
            one.merge(7, 1, Integer::sum);
            m += 1;
        }
        System.out.println("merge, one thread: count = " + one.get(7) + " (want 100)");

        final ConcurrentHashMap<Integer, Integer> counts = new ConcurrentHashMap<>();
        run(id ->
        {
            int r = 0;
            while (r < ROUNDS)
            {
                int h = 0;
                while (h < HOT)
                {
                    counts.merge(h, 1, Integer::sum);
                    h += 1;
                }
                r += 1;
            }
        });
        int wrong = 0;
        int h = 0;
        while (h < HOT)
        {
            Integer c = counts.get(h);
            if (c == null || c.intValue() != THREADS * ROUNDS)
            {
                wrong += 1;
            }
            h += 1;
        }
        System.out.println("merge counters: keys with a wrong count = " + wrong + " of " + HOT
                + ", key 0 = " + counts.get(0) + " (want " + (THREADS * ROUNDS) + ")");

        final ConcurrentHashMap<Integer, Integer> owners = new ConcurrentHashMap<>();
        final AtomicInteger wins = new AtomicInteger();
        run(id ->
        {
            int i = 0;
            while (i < N)
            {
                if (owners.putIfAbsent(i, id) == null)
                {
                    wins.incrementAndGet();
                }
                i += 1;
            }
        });
        System.out.println("putIfAbsent: winners = " + wins.get() + " (want " + N + "), size = " + owners.size());

        final ConcurrentHashMap<Integer, Integer> lazy = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        run(id ->
        {
            int i = 0;
            while (i < N)
            {
                lazy.computeIfAbsent(i, key ->
                {
                    calls.incrementAndGet();
                    return key * 2;
                });
                i += 1;
            }
        });
        System.out.println("computeIfAbsent: function calls = " + calls.get() + " (want " + N + ")");

        final ConcurrentHashMap<Integer, Integer> live = new ConcurrentHashMap<>();
        int p = 0;
        while (p < 1000)
        {
            live.put(p, p);
            p += 1;
        }
        Thread writer = new Thread(() ->
        {
            int i = 1000;
            while (i < 1000 + N)
            {
                live.put(i, i);
                live.remove(i - 1000);
                i += 1;
            }
        });
        writer.start();
        String iter = "ok";
        try
        {
            int pass = 0;
            while (pass < 50)
            {
                Iterator<Map.Entry<Integer, Integer>> it = live.entrySet().iterator();
                while (it.hasNext())
                {
                    Map.Entry<Integer, Integer> e = it.next();
                    if (!e.getKey().equals(e.getValue()))
                    {
                        iter = "torn entry";
                    }
                }
                pass += 1;
            }
        }
        catch (RuntimeException e)
        {
            iter = e.getClass().getName();
        }
        writer.join();
        System.out.println("iterate during writes: " + iter + ", final size = " + live.size());
        System.out.println("ChmRaceProbe done");
    }

    interface Body
    {
        void run(int id);
    }

    private static void run(Body b) throws Exception
    {
        Thread[] ts = new Thread[THREADS];
        int t = 0;
        while (t < THREADS)
        {
            final int id = t;
            ts[t] = new Thread(() -> b.run(id));
            ts[t].start();
            t += 1;
        }
        t = 0;
        while (t < THREADS)
        {
            ts[t].join();
            t += 1;
        }
    }
}
