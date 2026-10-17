/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVMS 5.5 step 2 across threads: while one thread is running a class's {@code <clinit>}, every OTHER thread's
 * active use of that class must wait for it. Thread A starts the initializer through a {@code getstatic}; once
 * the initializer has started (a gate it sets) and while it is still running, threads B, C and D each make the
 * FIRST call to a different static method of the class -- each its own first-call compile -- and must see the
 * value the initializer sets last.
 *
 * <p>Second shape, the one {@code ChmRaceProbe} met in {@code ThreadLocalRandom extends Random}: thread A's first use
 * of {@code Derived} must first initialize its SUPERCLASS {@code Base}, whose initializer is slow; thread B's use of
 * {@code Derived} meanwhile must not run {@code Derived.<clinit>} -- which constructs a {@code Derived}, whose
 * {@code Base} constructor reads {@code Base}'s static -- before {@code Base} is initialized. Prints values only;
 * the host diff is the gate.
 */
public class ClinitRaceProbe
{
    static volatile boolean started;

    static final AtomicInteger seenReady = new AtomicInteger();

    static class Slow
    {
        static final String V;

        static
        {
            started = true;
            try
            {
                Thread.sleep(300);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
            V = "ready";
        }

        static String b()
        {
            return V;
        }

        static String c()
        {
            return V;
        }

        static String d()
        {
            return V;
        }
    }

    static volatile boolean baseStarted;

    static class Base
    {
        static final String S;

        static
        {
            baseStarted = true;
            try
            {
                Thread.sleep(300);
            }
            catch (InterruptedException e)
            {
                throw new AssertionError(e);
            }
            S = "base";
        }

        final String fromBase;

        Base()
        {
            fromBase = S.toUpperCase();
        }
    }

    static class Derived extends Base
    {
        static final Derived INSTANCE = new Derived();
    }

    static void derivedUse(String who)
    {
        try
        {
            record(who, Derived.INSTANCE.fromBase.equals("BASE") ? "ready" : Derived.INSTANCE.fromBase);
        }
        catch (Throwable t)
        {
            System.out.println(who + " -> " + t.getClass().getName());
        }
    }

    static void record(String who, String v)
    {
        if ("ready".equals(v))
        {
            seenReady.incrementAndGet();
        }
        else
        {
            System.out.println(who + " saw " + v);
        }
    }

    public static void main(String[] args) throws Exception
    {
        Thread a = new Thread(() -> record("A", Slow.V));
        a.start();
        while (!started)
        {
            Thread.onSpinWait();
        }
        Thread b = new Thread(() -> record("B", Slow.b()));
        Thread c = new Thread(() -> record("C", Slow.c()));
        Thread d = new Thread(() -> record("D", Slow.d()));
        b.start();
        c.start();
        d.start();
        a.join();
        b.join();
        c.join();
        d.join();
        System.out.println("threads that saw the initialized value: " + seenReady.get() + " of 4");
        seenReady.set(0);
        Thread da = new Thread(() -> derivedUse("Derived A"));
        da.start();
        while (!baseStarted)
        {
            Thread.onSpinWait();
        }
        Thread db = new Thread(() -> derivedUse("Derived B"));
        Thread dc = new Thread(() -> derivedUse("Derived C"));
        db.start();
        dc.start();
        da.join();
        db.join();
        dc.join();
        System.out.println("subclass users that saw an initialized superclass: " + seenReady.get() + " of 3");
        System.out.println("ClinitRaceProbe done");
    }
}
