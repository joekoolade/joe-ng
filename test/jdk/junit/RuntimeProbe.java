/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */

/**
 * Runtime's memory natives and gc(), and System.runFinalization. Prints only RELATIONS the spec promises on any
 * JVM -- never a byte count -- so the host run is the control: the figures are consistent with each other, an
 * allocation that is kept shows up as use (both measured after a collection, so garbage cannot hide it), a
 * collection keeps what is still reachable, and gc() works from a spawned thread as well as from main. Its page
 * stride ({@code i += 4096}) is a wide iinc, which the compiler once truncated to {@code += 0}.
 */
public class RuntimeProbe
{
    static final int BIG = 4 << 20;

    static long used(Runtime rt)
    {
        return rt.totalMemory() - rt.freeMemory();
    }

    public static void main(String[] args) throws Exception
    {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        System.out.println("max>0 " + (max > 0));
        System.out.println("total>0 " + (total > 0));
        System.out.println("total<=max " + (total <= max));
        System.out.println("0<=free<=total " + (free >= 0 && free <= total));
        System.out.println("processors>=1 " + (rt.availableProcessors() >= 1));

        rt.gc();                                     // the baseline is a live set, not live + uncollected garbage:
        long before = used(rt);                      //   no collector promises use DROPS by less than the garbage
        byte[] big = new byte[BIG];
        int i = 0;
        while (i < BIG)
        {
            big[i] = (byte) (i * 31);
            i += 4096;
        }
        long after = used(rt);
        System.out.println("kept 4 MiB counted " + (after - before >= BIG));

        rt.gc();
        System.out.println("gc returned");
        boolean intact = true;
        i = 0;
        while (i < BIG)
        {
            if (big[i] != (byte) (i * 31))
            {
                intact = false;
            }
            i += 4096;
        }
        System.out.println("reachable survived " + intact);
        // NOT `used - before >= BIG`: the rest of the live set can shrink between two collections (measured on
        // metal: 41 KB, under a conservative collector), so only the absolute bound is a promise.
        System.out.println("still counted " + (used(rt) >= BIG));

        System.gc();
        System.runFinalization();
        System.out.println("System.gc + runFinalization returned");

        boolean[] ran = new boolean[1];
        Thread t = new Thread(() ->
        {
            Runtime.getRuntime().gc();
            ran[0] = true;
        });
        t.start();
        t.join();
        System.out.println("gc from a thread " + ran[0]);

        long f2 = rt.freeMemory();
        System.out.println("free<=total after " + (f2 >= 0 && f2 <= rt.totalMemory()));
        System.out.println("RuntimeProbe done " + big.length);
    }
}
