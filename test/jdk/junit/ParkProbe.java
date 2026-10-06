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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * STOCK {@code java.util.concurrent.locks.LockSupport} on the metal -- the overlay that declared only
 * {@code park()}/{@code unpark()} is deleted -- over a {@code Unsafe.park} that honours its timeout and an
 * interrupt.
 *
 * <p>Each arm is a contract the old pair broke or could not express: the TIMED forms (which used to block until
 * unparked, i.e. possibly for ever), a park RETURNING on interrupt with the status still set (it used to block
 * again), the BLOCKER being visible to another thread during {@code park(Object)} and cleared after, and two
 * stock classes built on it -- {@code CountDownLatch} (AQS) and {@code CompletableFuture.get()}
 * ({@code Signaller}, which calls {@code park(Object)}).
 *
 * <p>Timing arms print BOOLEANS against generous bounds, never a duration: a duration differs between any two
 * runs, while "returned after at least the timeout, and well inside 10 s" is the contract and holds on a host
 * and on an emulator alike. The gate is a byte-for-byte diff against a stock JVM running this file.
 */
public class ParkProbe
{
    private static volatile boolean flag;

    private static boolean within(long startNs, long minMs)
    {
        long ms = (System.nanoTime() - startNs) / 1000000L;
        return ms >= minMs && ms < 10000L;
    }

    public static void main(String[] args) throws Exception
    {
        long t0 = System.nanoTime();
        LockSupport.parkNanos(50000000L);
        System.out.println("parkNanos(50ms) returned in [50ms,10s) = " + within(t0, 50));

        t0 = System.nanoTime();
        LockSupport.parkUntil(System.currentTimeMillis() + 50L);
        System.out.println("parkUntil(+50ms) returned in [50ms,10s) = " + within(t0, 40));

        t0 = System.nanoTime();
        LockSupport.parkNanos(-5L);
        LockSupport.parkNanos(0L);
        System.out.println("parkNanos(<=0) returned at once = " + within(t0, 0));

        LockSupport.unpark(Thread.currentThread());
        t0 = System.nanoTime();
        LockSupport.park();
        System.out.println("park() with a pending permit returned at once = " + within(t0, 0));

        final Object blocker = new Object();
        Thread parker = new Thread(() ->
        {
            LockSupport.park(blocker);
            flag = true;
        });
        parker.start();
        long waitStart = System.nanoTime();
        while (LockSupport.getBlocker(parker) != blocker && (System.nanoTime() - waitStart) < 10000000000L)
        {
            Thread.sleep(5L);
        }
        System.out.println("getBlocker while parked == blocker = " + (LockSupport.getBlocker(parker) == blocker));
        LockSupport.unpark(parker);
        parker.join();
        System.out.println("unpark woke it = " + flag + ", getBlocker after = " + LockSupport.getBlocker(parker));

        final boolean[] seen = new boolean[1];
        Thread intr = new Thread(() ->
        {
            LockSupport.park();
            seen[0] = Thread.currentThread().isInterrupted();
        });
        intr.start();
        Thread.sleep(50L);
        intr.interrupt();
        intr.join(10000L);
        System.out.println("interrupt woke park = " + !intr.isAlive() + ", status still set = " + seen[0]);

        CountDownLatch latch = new CountDownLatch(1);
        Thread counter = new Thread(() ->
        {
            try
            {
                Thread.sleep(30L);
            }
            catch (InterruptedException e)
            {
                return;
            }
            latch.countDown();
        });
        counter.start();
        latch.await();
        System.out.println("CountDownLatch.await returned, count = " + latch.getCount());

        CountDownLatch never = new CountDownLatch(1);
        t0 = System.nanoTime();
        boolean got = never.await(50L, TimeUnit.MILLISECONDS);
        System.out.println("timed await = " + got + ", in [50ms,10s) = " + within(t0, 50));

        CompletableFuture<Integer> cf = new CompletableFuture<>();
        Thread completer = new Thread(() ->
        {
            try
            {
                Thread.sleep(30L);
            }
            catch (InterruptedException e)
            {
                return;
            }
            cf.complete(42);
        });
        completer.start();
        System.out.println("CompletableFuture.get = " + cf.get());

        System.out.println("ParkProbe done");
    }
}
