/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * STOCK {@code java.util.concurrent.Semaphore} (AQS-based), which replaced a three-method overlay over the VM's
 * fixed table of sixteen scheduler semaphores. The shapes: the members the overlay dropped ({@code tryAcquire},
 * {@code availablePermits}); multi-permit acquire/release; {@code drainPermits}; a fair semaphore; a timed
 * {@code tryAcquire} that must time out; FORTY semaphores alive at once (the overlay ran out at sixteen); and four
 * threads contending on one permit, checking mutual exclusion. Prints values only; the host diff is the gate.
 */
public class SemaphoreProbe
{
    static volatile int inside;
    static volatile int maxInside;
    static int total;

    public static void main(String[] args) throws Exception
    {
        Semaphore s = new Semaphore(3);
        System.out.println("permits=" + s.availablePermits());
        System.out.println("tryAcquire=" + s.tryAcquire() + " permits=" + s.availablePermits());
        s.acquire(2);
        System.out.println("after acquire(2) permits=" + s.availablePermits() + " tryAcquire=" + s.tryAcquire());
        s.release(3);
        System.out.println("after release(3) permits=" + s.availablePermits());
        System.out.println("drained=" + s.drainPermits() + " permits=" + s.availablePermits());
        System.out.println("timed tryAcquire=" + s.tryAcquire(20, TimeUnit.MILLISECONDS));
        Semaphore fair = new Semaphore(1, true);
        System.out.println("fair=" + fair.isFair() + " unfair=" + s.isFair());

        Semaphore[] many = new Semaphore[40];
        int ok = 0;
        for (int i = 0; i < many.length; i += 1)
        {
            many[i] = new Semaphore(1);
            if (many[i].tryAcquire())
            {
                ok += 1;
            }
        }
        System.out.println("forty semaphores, each acquired once: " + ok);

        Semaphore mutex = new Semaphore(1);
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t += 1)
        {
            ts[t] = new Thread(() ->
            {
                for (int k = 0; k < 200; k += 1)
                {
                    mutex.acquireUninterruptibly();
                    inside += 1;
                    if (inside > maxInside)
                    {
                        maxInside = inside;
                    }
                    total += 1;
                    inside -= 1;
                    mutex.release();
                }
            });
            ts[t].start();
        }
        for (Thread t : ts)
        {
            t.join();
        }
        System.out.println("contended: total=" + total + " (want 800) maxInside=" + maxInside + " permits="
                + mutex.availablePermits());
        System.out.println("SemaphoreProbe done");
    }
}
