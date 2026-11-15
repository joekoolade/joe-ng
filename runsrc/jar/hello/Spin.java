/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package hello;

/**
 * A run that has to be STOPPED, run with {@code main=hello.Spin args=<mode>}:
 * <ul>
 * <li>{@code spin}: a loop with no calls, holding {@code Spin.class}'s monitor, inside a {@code finally}: only a
 *     forced stop ends it, and the finally must run and the monitor must be free afterwards.</li>
 * <li>{@code sleep}: sleeps for ever: the interrupt ends it.</li>
 * <li>{@code polite}: spins until interrupted, then returns: ends without being forced.</li>
 * <li>{@code catch}: catches Throwable around its inner loop and goes round again: still stops.</li>
 * <li>{@code threads}: three more threads spin beside main: every one is stopped.</li>
 * <li>{@code jdk}: each pass calls into the JDK (StringBuilder): the program's own loop still polls.</li>
 * <li>{@code monitor}: enters {@code Spin.class}'s monitor, which a caller can hold: blocked there no interrupt or
 *     poll reaches it, so a stop stays PENDING until the caller lets go.</li>
 * </ul>
 * A second argument {@code yield} makes every pass call {@code Thread.yield()}: QEMU gives a launched program no
 * timer ticks, so there a loop that never yields keeps its core for good and nothing else would run to stop it.
 * It does not change what has to stop it -- the loops still ignore interrupts.
 */
public class Spin
{
    static volatile long passes;

    static boolean yields;

    public static void main(String[] args) throws Exception
    {
        String mode = args.length > 0 ? args[0] : "spin";
        yields = args.length > 1 && args[1].equals("yield");
        System.out.println("spin: " + mode + " started");
        if (mode.equals("sleep"))
        {
            try
            {
                Thread.sleep(Long.MAX_VALUE);
            }
            catch (InterruptedException e)
            {
                System.out.println("spin: sleep interrupted");
            }
        }
        else if (mode.equals("polite"))
        {
            while (!Thread.currentThread().isInterrupted())
            {
                passes = passes + 1;
                if (yields)
                {
                    Thread.yield();
                }
            }
            System.out.println("spin: polite, saw the interrupt");
        }
        else if (mode.equals("catch"))
        {
            int caught = 0;
            while (true)
            {
                try
                {
                    while (true)
                    {
                        passes = passes + 1;
                        if (yields)
                        {
                            Thread.yield();
                        }
                    }
                }
                catch (Throwable t)
                {
                    caught = caught + 1;
                    System.out.println("spin: caught " + t + " (" + caught + ")");
                }
            }
        }
        else if (mode.equals("threads"))
        {
            for (int i = 0; i < 3; i++)
            {
                final int n = i;
                Thread t = new Thread(() ->
                {
                    try
                    {
                        while (true)
                        {
                            passes = passes + 1;
                            if (yields)
                            {
                                Thread.yield();
                            }
                        }
                    }
                    finally
                    {
                        System.out.println("spin: thread " + n + " finally");
                    }
                }, "spinner-" + i);
                t.start();
            }
            spin();
        }
        else if (mode.equals("monitor"))
        {
            synchronized (Spin.class)
            {
                System.out.println("spin: monitor entered");
            }
        }
        else if (mode.equals("jdk"))
        {
            StringBuilder sb = new StringBuilder();
            while (true)
            {
                sb.setLength(0);
                sb.append(passes);
                passes = passes + 1;
                if (yields)
                {
                    Thread.yield();
                }
            }
        }
        else
        {
            synchronized (Spin.class)
            {
                spin();
            }
        }
    }

    private static void spin()
    {
        try
        {
            while (true)
            {
                passes = passes + 1;
                if (yields)
                {
                    Thread.yield();
                }
            }
        }
        finally
        {
            System.out.println("spin: finally ran");
        }
    }
}
