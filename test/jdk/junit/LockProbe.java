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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code java.util.concurrent.locks.ReentrantLock} under real contention, and the stock blocking queues built on
 * its {@code Condition}s.
 *
 * <p>The joe-ng overlay this replaces made {@code lock()}/{@code unlock()} no-ops, {@code tryLock()} always true
 * and {@code newCondition()} null -- justified as "the socket path on metal is single-threaded", on a VM that
 * schedules guest threads on four cores. Uses only members that overlay also declared, so the same source runs
 * against both; every arm prints a value, so the host diff is the gate.
 *
 * <ul>
 * <li>A plain counter guarded by the lock: four threads x {@link #N}. A lock that does not exclude loses updates.</li>
 * <li>{@code tryLock()} while another thread holds the lock must answer FALSE.</li>
 * <li>A {@code Condition} await/signal handoff.</li>
 * <li>{@code ArrayBlockingQueue} and {@code LinkedBlockingQueue} producer/consumer: every item delivered once,
 *     in order, and a timed {@code poll} on an empty queue returns null after its timeout.</li>
 * </ul>
 */
public class LockProbe
{
    private static final int THREADS = 4;
    private static final int N = 20000;

    static final ReentrantLock lock = new ReentrantLock();
    static int counter;

    public static void main(String[] args) throws Exception
    {
        Thread[] ts = new Thread[THREADS];
        int t = 0;
        while (t < THREADS)
        {
            ts[t] = new Thread(() ->
            {
                int i = 0;
                while (i < N)
                {
                    lock.lock();
                    try
                    {
                        counter = counter + 1;
                    }
                    finally
                    {
                        lock.unlock();
                    }
                    i += 1;
                }
            });
            ts[t].start();
            t += 1;
        }
        t = 0;
        while (t < THREADS)
        {
            ts[t].join();
            t += 1;
        }
        System.out.println("guarded counter = " + counter + " (want " + (THREADS * N) + ")");

        final ReentrantLock held = new ReentrantLock();
        final Object go = new Object();
        final boolean[] holding = new boolean[1];
        Thread holder = new Thread(() ->
        {
            held.lock();
            try
            {
                synchronized (go)
                {
                    holding[0] = true;
                    go.notifyAll();
                    try
                    {
                        go.wait(10000L);
                    }
                    catch (InterruptedException e)
                    {
                        return;
                    }
                }
            }
            finally
            {
                held.unlock();
            }
        });
        holder.start();
        synchronized (go)
        {
            while (!holding[0])
            {
                go.wait();
            }
        }
        System.out.println("tryLock while held elsewhere = " + held.tryLock()
                + ", isHeldByCurrentThread = " + held.isHeldByCurrentThread() + ", isLocked = " + held.isLocked());
        synchronized (go)
        {
            go.notifyAll();
        }
        holder.join();
        boolean got = held.tryLock();
        System.out.println("tryLock after release = " + got + ", isHeldByCurrentThread = " + held.isHeldByCurrentThread());
        if (got)
        {
            held.unlock();
        }

        final ReentrantLock cl = new ReentrantLock();
        final Condition ready = cl.newCondition();
        final int[] box = new int[1];
        Thread signaller = new Thread(() ->
        {
            cl.lock();
            try
            {
                box[0] = 42;
                ready.signal();
            }
            finally
            {
                cl.unlock();
            }
        });
        cl.lock();
        try
        {
            signaller.start();
            while (box[0] == 0)
            {
                ready.await();
            }
        }
        finally
        {
            cl.unlock();
        }
        signaller.join();
        System.out.println("Condition await/signal = " + box[0]);

        System.out.println("ArrayBlockingQueue = " + pipe(new ArrayBlockingQueue<Integer>(8)));
        System.out.println("LinkedBlockingQueue = " + pipe(new LinkedBlockingQueue<Integer>()));
        LinkedBlockingQueue<Integer> empty = new LinkedBlockingQueue<>();
        long t0 = System.nanoTime();
        Integer none = empty.poll(50L, TimeUnit.MILLISECONDS);
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.out.println("timed poll on empty = " + none + ", waited >= 50ms and < 10s = " + (ms >= 50L && ms < 10000L));
        System.out.println("LockProbe done");
    }

    /** One producer puts 0..999, one consumer takes 1000: answers "sum in-order". */
    private static String pipe(java.util.concurrent.BlockingQueue<Integer> q) throws Exception
    {
        Thread producer = new Thread(() ->
        {
            int i = 0;
            while (i < 1000)
            {
                try
                {
                    q.put(i);
                }
                catch (InterruptedException e)
                {
                    return;
                }
                i += 1;
            }
        });
        producer.start();
        long sum = 0;
        boolean inOrder = true;
        int i = 0;
        while (i < 1000)
        {
            int v = q.take();
            if (v != i)
            {
                inOrder = false;
            }
            sum += v;
            i += 1;
        }
        producer.join();
        return sum + " " + inOrder;
    }
}
