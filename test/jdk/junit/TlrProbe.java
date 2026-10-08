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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stock {@code java.util.concurrent.ThreadLocalRandom}, first from ONE thread, then from four at once.
 *
 * <p>The arms are ordered so a failure names its cause. If the single-thread arm fails, TLR's statics are wrong
 * regardless of concurrency: its {@code <clinit>} reads {@code VM.getSavedProperty} and four
 * {@code Unsafe.objectFieldOffset(Thread.class, ...)} offsets. If only the four-thread arm fails, it is
 * per-thread state, i.e. the {@code Thread} fields TLR reads and writes. {@code ConcurrentHashMap}'s contended
 * counter is TLR's first caller in java.base, which is how this was found. Prints facts that are true on any
 * JVM, so the host diff is the gate.
 */
public class TlrProbe
{
    public static void main(String[] args) throws Exception
    {
        int v = ThreadLocalRandom.current().nextInt(100);
        System.out.println("single thread: nextInt(100) in range = " + (v >= 0 && v < 100));
        long a = ThreadLocalRandom.current().nextLong();
        long b = ThreadLocalRandom.current().nextLong();
        System.out.println("single thread: two nextLong differ = " + (a != b));

        final AtomicInteger bad = new AtomicInteger();
        final AtomicInteger distinctSeeds = new AtomicInteger();
        final long[] first = new long[4];
        Thread[] ts = new Thread[4];
        int t = 0;
        while (t < 4)
        {
            final int id = t;
            ts[t] = new Thread(() ->
            {
                first[id] = ThreadLocalRandom.current().nextLong();
                int i = 0;
                while (i < 1000)
                {
                    int r = ThreadLocalRandom.current().nextInt(10);
                    if (r < 0 || r >= 10)
                    {
                        bad.incrementAndGet();
                    }
                    i += 1;
                }
            });
            ts[t].start();
            t += 1;
        }
        t = 0;
        while (t < 4)
        {
            ts[t].join();
            t += 1;
        }
        int i = 0;
        while (i < 4)
        {
            int j = i + 1;
            boolean unique = true;
            while (j < 4)
            {
                if (first[i] == first[j])
                {
                    unique = false;
                }
                j += 1;
            }
            if (unique)
            {
                distinctSeeds.incrementAndGet();
            }
            i += 1;
        }
        System.out.println("four threads: out-of-range = " + bad.get());
        System.out.println("four threads: per-thread first values distinct = " + (distinctSeeds.get() == 4));
        System.out.println("TlrProbe done");
    }
}
