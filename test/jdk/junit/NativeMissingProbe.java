/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-30
 */

/**
 * A call to a native method nothing implements throws {@code UnsatisfiedLinkError} naming the method, on any JVM.
 * On this VM it used to HALT the task on the link trap (reported as a DENYLIST TRAP for a class that is not
 * denied). Shapes: a static native, called twice (the second call reuses the memoised target); an instance
 * native; a native with ten arguments, past the eight passed in registers; and one thrown three frames down,
 * whose {@code finally} blocks must run on the way out. HotSpot's message text differs from this VM's, so only
 * the error's class and whether its message names the method are printed; the host diff is the gate.
 */
public class NativeMissingProbe
{
    static native int missing();

    native int inst(int x);

    static native long many(long a, long b, long c, long d, long e, long f, long g, long h, long i, long j);

    static int unwound;

    public static void main(String[] args)
    {
        report("static", () -> missing());
        report("static again", () -> missing());
        report("instance", () -> new NativeMissingProbe().inst(5));
        report("ten args", () -> many(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        report("three frames down", () -> depth(3));
        System.out.println("finally blocks run " + unwound);
        System.out.println("NativeMissingProbe done");
    }

    static int depth(int n)
    {
        try
        {
            return n == 0 ? missing() : depth(n - 1);
        }
        finally
        {
            unwound += 1;
        }
    }

    interface Call
    {
        long run();
    }

    static void report(String what, Call c)
    {
        try
        {
            long v = c.run();
            System.out.println(what + ": returned " + v);
        }
        catch (UnsatisfiedLinkError e)
        {
            String m = String.valueOf(e.getMessage());
            boolean named = m.contains("NativeMissingProbe.missing") || m.contains("NativeMissingProbe.inst")
                    || m.contains("NativeMissingProbe.many");
            System.out.println(what + ": " + e.getClass().getName() + " names the method " + named);
        }
    }
}
