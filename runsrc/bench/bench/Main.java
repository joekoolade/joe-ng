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
package bench;

import java.lang.reflect.Method;

/**
 * What a stop poll costs a {@code /run} program's loops: the same loops ({@code PollLoops}) timed twice -- the
 * image's {@code demo.PollLoops}, compiled without polls, and this jar's {@code bench.PollLoops}, made from the same
 * source and defined as a run's class, so every loop header polls. Each shape runs alternately, {@code reps} times
 * per copy, and the MINIMUM is reported (the run least disturbed by the server, the collector or another core).
 * Args: {@code [n] [reps]}, n the passes per measurement (default 20,000,000).
 */
public class Main
{
    public static void main(String[] args) throws Exception
    {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20_000_000;
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        Class<?> base = Class.forName("demo.PollLoops");
        Class<?> polled = PollLoops.class;
        System.out.println("stop-poll cost: n=" + n + " reps=" + reps + " (min ns per loop-header pass)");
        int[] a = new int[1000];
        for (int i = 0; i < a.length; i++)
        {
            a[i] = i * 7;
        }
        measure("count", base, polled, n, new Class<?>[] { int.class }, new Object[] { n }, reps);
        measure("array", base, polled, n, new Class<?>[] { int[].class, int.class },
                new Object[] { a, n / a.length }, reps);
        measure("calls", base, polled, n, new Class<?>[] { int.class }, new Object[] { n }, reps);
        int outer = n / 100;
        measure("nested", base, polled, outer * 101, new Class<?>[] { int.class, int.class },
                new Object[] { outer, 100 }, reps);
    }

    /**
     * Time {@code name} on both copies, alternating, and print the minimum per pass of each. {@code passes} is how
     * many times a loop header is passed in one call (for nested: outer + outer*inner).
     */
    private static void measure(String name, Class<?> base, Class<?> polled, long passes, Class<?>[] types,
            Object[] callArgs, int reps) throws Exception
    {
        Method mb = base.getMethod(name, types);
        Method mp = polled.getMethod(name, types);
        Object rb = mb.invoke(null, callArgs);                  // warm up: compile both, and check they agree
        Object rp = mp.invoke(null, callArgs);
        if (!rb.equals(rp))
        {
            System.out.println(name + ": RESULTS DIFFER " + rb + " vs " + rp);
        }
        long bestBase = Long.MAX_VALUE;
        long bestPolled = Long.MAX_VALUE;
        for (int r = 0; r < reps; r++)
        {
            long t0 = System.nanoTime();
            mb.invoke(null, callArgs);
            long t1 = System.nanoTime();
            mp.invoke(null, callArgs);
            long t2 = System.nanoTime();
            bestBase = Math.min(bestBase, t1 - t0);
            bestPolled = Math.min(bestPolled, t2 - t1);
        }
        double nb = (double) bestBase / passes;
        double np = (double) bestPolled / passes;
        System.out.println(String.format("%-7s no polls %7.3f ns   polls %7.3f ns   +%6.3f ns  (%+5.1f%%)",
                name, nb, np, np - nb, 100.0 * (np - nb) / nb));
    }
}
