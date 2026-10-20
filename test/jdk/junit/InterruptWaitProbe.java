/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-20
 */

/**
 * {@code Object.wait} and the interrupt status. A wait entered with the status already set throws
 * {@code InterruptedException} at once; a wait interrupted while blocked throws it after REACQUIRING the monitor
 * (with the recursion depth intact); either way the status is cleared. The compiler lowers {@code wait} straight to
 * a VM helper, so every form is exercised -- untimed, timed, {@code wait(ms, ns)} -- plus the two that must NOT
 * throw: an interrupt delivered to a thread blocked ENTERING a monitor, and a wait notified normally afterwards.
 */
public class InterruptWaitProbe
{
    static final Object lock = new Object();
    static volatile boolean ready;
    static volatile String result;

    public static void main(String[] args) throws Exception
    {
        System.out.println("pre-set wait(): " + preSet(() -> lock.wait()));
        System.out.println("pre-set wait(5000): " + preSet(() -> lock.wait(5000)));
        System.out.println("pre-set wait(0,1): " + preSet(() -> lock.wait(0, 1)));
        System.out.println("blocked wait(): " + blocked(0L, false));
        System.out.println("blocked wait(10000): " + blocked(10000L, false));
        System.out.println("blocked recursive wait(): " + blocked(0L, true));
        System.out.println("blocked enter: " + blockedEnter());
        System.out.println("notified wait: " + notified());
        System.out.println("main status after: " + Thread.currentThread().isInterrupted());
        System.out.println("InterruptWaitProbe done");
    }

    interface Action
    {
        void run() throws Exception;
    }

    /** Interrupt self, then wait while owning the monitor: it must throw without blocking and clear the status. */
    static String preSet(Action a)
    {
        long t0 = System.nanoTime();
        String what;
        synchronized (lock)
        {
            Thread.currentThread().interrupt();
            try
            {
                a.run();
                what = "returned";
            }
            catch (InterruptedException e)
            {
                what = "InterruptedException holds=" + Thread.holdsLock(lock);
            }
            catch (Exception e)
            {
                what = e.getClass().getSimpleName();
            }
        }
        boolean quick = (System.nanoTime() - t0) < 2_000_000_000L;
        return what + " status=" + Thread.currentThread().isInterrupted() + " quick=" + quick;
    }

    /**
     * A thread waits on {@code lock} (nested twice when {@code recursive}); main interrupts it while STILL holding
     * the monitor, so the waiter can only throw once main lets go -- the exception is raised with the monitor held.
     */
    static String blocked(long ms, boolean recursive) throws Exception
    {
        ready = false;
        result = null;
        Thread t = new Thread(() ->
        {
            synchronized (lock)
            {
                if (recursive)
                {
                    synchronized (lock)
                    {
                        result = waitOnce(ms);
                    }
                    result = result + " outerHolds=" + Thread.holdsLock(lock);
                }
                else
                {
                    result = waitOnce(ms);
                }
            }
        });
        long t0 = System.nanoTime();
        synchronized (lock)
        {
            t.start();
            while (!ready)
            {
                lock.wait();
            }
            t.interrupt();                                 // the waiter is in wait(): its notify released us
        }
        t.join();
        boolean quick = (System.nanoTime() - t0) < 5_000_000_000L;
        return result + " quick=" + quick;
    }

    static String waitOnce(long ms)
    {
        ready = true;
        lock.notifyAll();
        try
        {
            lock.wait(ms);
            return "returned";
        }
        catch (InterruptedException e)
        {
            return "InterruptedException holds=" + Thread.holdsLock(lock)
                + " status=" + Thread.currentThread().isInterrupted();
        }
    }

    /** Interrupting a thread blocked on monitorenter does not throw: it enters when released, status still set. */
    static String blockedEnter() throws Exception
    {
        Object gate = new Object();
        result = null;
        Thread t = new Thread(() ->
        {
            synchronized (gate)
            {
                result = "entered status=" + Thread.currentThread().isInterrupted();
            }
        });
        synchronized (gate)
        {
            t.start();
            Thread.sleep(50);
            t.interrupt();
            Thread.sleep(50);
            if (result != null)
            {
                return "entered while held";
            }
        }
        t.join();
        return result;
    }

    /** After all that, an ordinary notify still wakes an ordinary wait normally. */
    static String notified() throws Exception
    {
        ready = false;
        result = null;
        Thread t = new Thread(() ->
        {
            synchronized (lock)
            {
                result = waitOnce(0L);
            }
        });
        synchronized (lock)
        {
            t.start();
            while (!ready)
            {
                lock.wait();
            }
            lock.notify();
        }
        t.join();
        return result;
    }
}
