/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-06
 */
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * java.util.concurrent.atomic under REAL contention: four threads, each doing {@link #N} updates, on every
 * atomic type. Every total must be exactly {@code THREADS * N}.
 *
 * <p>The joe-ng overlays these classes used to be justified themselves as "plain field access on joe-ng's single
 * core" -- {@code compareAndSet} a check-then-act, {@code incrementAndGet} a {@code ++value}. The scheduler has run
 * guest threads on all four A72s since the SMP arc, so a lost update shows here as a total BELOW the target. The
 * {@code AtomicBoolean} arm uses the boolean as a spinlock around a PLAIN counter, so it tests mutual exclusion
 * rather than arithmetic.
 *
 * <p>Uses only members the overlays also declared, so the same source runs against both -- the overlays are the
 * negative control. Prints totals only; the gate is a byte-for-byte diff against a stock JVM.
 */
public class AtomicRaceProbe
{
    private static final int THREADS = 4;
    private static final int N = 20000;

    static final AtomicInteger ai = new AtomicInteger();
    static final AtomicLong al = new AtomicLong();
    static final AtomicLong alAdd = new AtomicLong();
    static final AtomicReference<Integer> ar = new AtomicReference<>(0);
    static final AtomicIntegerArray aia = new AtomicIntegerArray(3);
    static final AtomicLongArray ala = new AtomicLongArray(3);
    static final AtomicReferenceArray<Integer> ara = new AtomicReferenceArray<>(3);
    static final AtomicBoolean lock = new AtomicBoolean();
    static int guarded;

    private static void work()
    {
        int i = 0;
        while (i < N)
        {
            ai.incrementAndGet();
            al.incrementAndGet();
            alAdd.getAndAdd(3L);
            while (true)
            {
                Integer v = ar.get();
                if (ar.compareAndSet(v, Integer.valueOf(v.intValue() + 1)))
                {
                    break;
                }
            }
            while (true)
            {
                int v = aia.get(1);
                if (aia.compareAndSet(1, v, v + 1))
                {
                    break;
                }
            }
            while (true)
            {
                long v = ala.get(1);
                if (ala.compareAndSet(1, v, v + 1L))
                {
                    break;
                }
            }
            while (true)
            {
                Integer v = ara.get(1);
                if (ara.compareAndSet(1, v, Integer.valueOf(v.intValue() + 1)))
                {
                    break;
                }
            }
            while (!lock.compareAndSet(false, true))
            {
                Thread.onSpinWait();
            }
            guarded = guarded + 1;
            lock.set(false);
            i += 1;
        }
    }

    public static void main(String[] args) throws Exception
    {
        ara.set(0, 0);
        ara.set(1, 0);
        ara.set(2, 0);
        Thread[] ts = new Thread[THREADS];
        int t = 0;
        while (t < THREADS)
        {
            ts[t] = new Thread(AtomicRaceProbe::work);
            ts[t].start();
            t += 1;
        }
        t = 0;
        while (t < THREADS)
        {
            ts[t].join();
            t += 1;
        }
        int want = THREADS * N;
        System.out.println("want " + want);
        System.out.println("AtomicInteger.incrementAndGet = " + ai.get());
        System.out.println("AtomicLong.incrementAndGet = " + al.get());
        System.out.println("AtomicLong.getAndAdd(3) = " + alAdd.get() + " (want " + (3L * want) + ")");
        System.out.println("AtomicReference CAS loop = " + ar.get());
        System.out.println("AtomicIntegerArray CAS loop = " + aia.get(1) + " neighbours " + aia.get(0) + "," + aia.get(2));
        System.out.println("AtomicLongArray CAS loop = " + ala.get(1) + " neighbours " + ala.get(0) + "," + ala.get(2));
        System.out.println("AtomicReferenceArray CAS loop = " + ara.get(1) + " neighbours " + ara.get(0) + "," + ara.get(2));
        System.out.println("AtomicBoolean spinlock, plain counter = " + guarded);
        System.out.println("AtomicRaceProbe done");
    }
}
