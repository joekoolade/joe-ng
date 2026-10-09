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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stock {@code java.util.concurrent} thread pools: {@code Executors.newFixedThreadPool}, futures, {@code invokeAll},
 * a task that throws, orderly shutdown -- and the {@code Thread} members they reach.
 *
 * <p>{@code Executors$DefaultThreadFactory} builds workers with {@code Thread(ThreadGroup, Runnable, String, long)}
 * and pool workers consult {@code getUncaughtExceptionHandler}/{@code setUncaughtExceptionHandler}; the joe-ng
 * {@code Thread} overlay lacked all of them (deep-check gaps), so a pool could not even create a worker. Prints
 * values only (worker NAMES included: the factory's {@code pool-N-thread-M} scheme is part of the contract), so the
 * host diff is the gate.
 *
 * <p>The handler arms check that a handler FIRES, not just that it can be set: a {@code Runnable} that throws, a
 * {@code Thread} SUBCLASS whose {@code run()} override throws, and the default handler as the fallback. On joe-ng
 * the VM's own unwinder used to report an escaping throwable itself, so an accepted handler was never called.
 */
public class ExecutorProbe
{
    public static void main(String[] args) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> fs = new ArrayList<>();
        int i = 0;
        while (i < 20)
        {
            final int n = i;
            fs.add(pool.submit(() -> n * n));
            i += 1;
        }
        int sum = 0;
        for (Future<Integer> f : fs)
        {
            sum += f.get();
        }
        System.out.println("20 submitted squares, summed = " + sum + " (want 2470)");

        final AtomicInteger counter = new AtomicInteger();
        List<Callable<Integer>> work = new ArrayList<>();
        i = 0;
        while (i < 8)
        {
            work.add(() ->
            {
                int k = 0;
                while (k < 1000)
                {
                    counter.incrementAndGet();
                    k += 1;
                }
                return 1;
            });
            i += 1;
        }
        int done = 0;
        for (Future<Integer> f : pool.invokeAll(work))
        {
            done += f.get();
        }
        System.out.println("invokeAll: " + done + " tasks, counter = " + counter.get() + " (want 8000)");

        Future<Integer> bad = pool.submit(() ->
        {
            throw new IllegalStateException("boom");
        });
        try
        {
            bad.get();
            System.out.println("failing task: no exception (WRONG)");
        }
        catch (ExecutionException e)
        {
            System.out.println("failing task: ExecutionException caused by " + e.getCause().getClass().getName() + ": "
                    + e.getCause().getMessage());
        }

        final CountDownLatch latch = new CountDownLatch(4);
        final String[] names = new String[4];
        i = 0;
        while (i < 4)
        {
            final int slot = i;
            pool.execute(() ->
            {
                names[slot] = Thread.currentThread().getName().startsWith("pool-") ? "pool-worker" : "other";
                latch.countDown();
            });
            i += 1;
        }
        System.out.println("latch released = " + latch.await(10, TimeUnit.SECONDS) + ", worker names = "
                + String.join(",", names));

        pool.shutdown();
        System.out.println("shutdown: terminated = " + pool.awaitTermination(10, TimeUnit.SECONDS) + ", isShutdown = "
                + pool.isShutdown());

        Thread named = new Thread("probe-named");
        System.out.println("Thread(String).getName = " + named.getName());
        Thread.UncaughtExceptionHandler h = (t, e) -> { };
        named.setUncaughtExceptionHandler(h);
        System.out.println("set/getUncaughtExceptionHandler round-trips = " + (named.getUncaughtExceptionHandler() == h));

        final String[] seen = new String[3];
        Thread thrower = new Thread(() ->
        {
            throw new IllegalArgumentException("from runnable");
        });
        thrower.setUncaughtExceptionHandler((t, e) -> seen[0] = e.getMessage());
        thrower.start();
        thrower.join();
        Thread sub = new Thread("sub")
        {
            @Override
            public void run()
            {
                throw new UnsupportedOperationException("from override");
            }
        };
        sub.setUncaughtExceptionHandler((t, e) -> seen[1] = t.getName() + ":" + e.getMessage());
        sub.start();
        sub.join();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> seen[2] = "default:" + e.getMessage());
        Thread plain = new Thread(() ->
        {
            throw new IllegalStateException("to default");
        });
        plain.start();
        plain.join();
        Thread.setDefaultUncaughtExceptionHandler(null);
        System.out.println("handlers fired: " + seen[0] + " | " + seen[1] + " | " + seen[2]);
        System.out.println("terminated thread's handler = " + thrower.getUncaughtExceptionHandler());
        System.out.println("ExecutorProbe done");
    }
}
