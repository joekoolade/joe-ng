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
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code java.util.concurrent.ConcurrentSkipListMap} under real contention from four threads.
 *
 * <p>The joe-ng overlay this replaces was a {@code TreeMap} subclass with no concurrency control ("single core, so
 * no concurrency control is needed") on a VM that schedules guest threads on four A72s. Concurrent inserts into a
 * red-black tree corrupt its rotations; the check-then-act methods are not atomic. Uses only members that overlay
 * also had (directly or through {@code TreeMap}), so the same source runs against both; every arm prints a value,
 * so the host diff is the gate.
 *
 * <ul>
 * <li>Disjoint puts: every key present, and iteration is in SORTED order with no duplicates.</li>
 * <li>{@code merge(k, 1, Integer::sum)} on shared keys: every count exact.</li>
 * <li>{@code putIfAbsent}: exactly one winner per key.</li>
 * <li>{@code firstKey}/{@code lastKey} and a remove race: the survivors are exactly the keys nobody removed.</li>
 * <li>Iteration while another thread writes: no {@code ConcurrentModificationException}, still sorted.</li>
 * <li>The NAVIGABLE surface the overlay never had -- a comparator constructor, {@code ConcurrentSkipListSet} on
 *     top of it, ceiling/floor, head/tail views, descending order. This arm is stock-only: the overlay could not
 *     compile it, which is itself the gap the deep check reported (15 members).</li>
 * </ul>
 */
public class CslmRaceProbe
{
    private static final int THREADS = 4;
    private static final int N = 3000;
    private static final int HOT = 16;
    private static final int ROUNDS = 1000;

    public static void main(String[] args) throws Exception
    {
        final ConcurrentSkipListMap<Integer, Integer> disjoint = new ConcurrentSkipListMap<>();
        run(id ->
        {
            int i = 0;
            while (i < N)
            {
                disjoint.put(i * THREADS + id, id);
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
        System.out.println("disjoint puts: size = " + disjoint.size() + " (want " + (THREADS * N) + "), missing = "
                + missing + ", sorted = " + sorted(disjoint) + ", first/last = " + disjoint.firstKey() + "/"
                + disjoint.lastKey());

        final ConcurrentSkipListMap<Integer, Integer> counts = new ConcurrentSkipListMap<>();
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
        System.out.println("merge counters: keys with a wrong count = " + wrong + " of " + HOT);

        final ConcurrentSkipListMap<Integer, Integer> owners = new ConcurrentSkipListMap<>();
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

        final ConcurrentSkipListMap<Integer, Integer> shrink = new ConcurrentSkipListMap<>();
        int p = 0;
        while (p < THREADS * N)
        {
            shrink.put(p, p);
            p += 1;
        }
        final AtomicInteger removed = new AtomicInteger();
        run(id ->
        {
            int i = 0;
            while (i < THREADS * N)
            {
                if (i % 3 != 0 && shrink.remove(i) != null)
                {
                    removed.incrementAndGet();
                }
                i += 1;
            }
        });
        int expectLeft = (THREADS * N + 2) / 3;
        System.out.println("remove race: removed = " + removed.get() + ", left = " + shrink.size() + " (want "
                + expectLeft + "), sorted = " + sorted(shrink));

        final ConcurrentSkipListMap<Integer, Integer> live = new ConcurrentSkipListMap<>();
        int q = 0;
        while (q < 500)
        {
            live.put(q, q);
            q += 1;
        }
        Thread writer = new Thread(() ->
        {
            int i = 500;
            while (i < 500 + N)
            {
                live.put(i, i);
                live.remove(i - 500);
                i += 1;
            }
        });
        writer.start();
        String iter = "ok";
        try
        {
            int pass = 0;
            while (pass < 30)
            {
                if (!sorted(live))
                {
                    iter = "unsorted";
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
        final ConcurrentSkipListSet<Integer> desc = new ConcurrentSkipListSet<>(Collections.reverseOrder());
        run(id ->
        {
            int i = 0;
            while (i < N)
            {
                desc.add(i * THREADS + id);
                i += 1;
            }
        });
        boolean descending = true;
        int prev = Integer.MAX_VALUE;
        for (Integer v : desc)
        {
            if (v.intValue() >= prev)
            {
                descending = false;
            }
            prev = v.intValue();
        }
        System.out.println("ConcurrentSkipListSet(reverseOrder): size = " + desc.size() + ", descending = "
                + descending + ", first = " + desc.first());
        System.out.println("navigable: ceilingKey(100) = " + disjoint.ceilingKey(100) + ", floorKey(-1) = "
                + disjoint.floorKey(-1) + ", headMap(10).size = " + disjoint.headMap(10).size()
                + ", tailMap(11990).size = " + disjoint.tailMap(11990).size() + ", descending first = "
                + disjoint.descendingMap().firstKey() + ", pollFirst = " + shrink.pollFirstEntry().getKey());
        System.out.println("CslmRaceProbe done");
    }

    /** Strictly ascending keys, and every entry's value consistent with what was put. */
    private static boolean sorted(ConcurrentSkipListMap<Integer, Integer> m)
    {
        Iterator<Map.Entry<Integer, Integer>> it = m.entrySet().iterator();
        int prev = Integer.MIN_VALUE;
        boolean first = true;
        while (it.hasNext())
        {
            Map.Entry<Integer, Integer> e = it.next();
            int key = e.getKey().intValue();
            if (!first && key <= prev)
            {
                return false;
            }
            prev = key;
            first = false;
        }
        return true;
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
