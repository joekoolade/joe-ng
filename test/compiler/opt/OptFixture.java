/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package compiler.opt;

import magic.Address;
import magic.Optimize;

/** Bytecode shapes for the SSA builder and verifier ({@link OptIrTest}). Never run -- only parsed. */
final class OptFixture
{
    static int field;
    long wide;

    private OptFixture()
    {
    }

    static int straight(int a, int b)
    {
        return a * b + 7;
    }

    /** A loop: the header merges i and s; n passes through unchanged (a trivial phi). */
    static int loop(int n)
    {
        int s = 0;
        for (int i = 0; i < n; i++)
        {
            s += i;
        }
        return s;
    }

    /** A long and a double held across a join, in locals and on the stack. */
    static long wideJoin(boolean c, long a, double d)
    {
        long x = c ? a : a + 1;
        double y = c ? d : -d;
        return x + (long) y;
    }

    /** The same local slot an int on one path and a reference on the other, unread after the join. */
    static int slotReuse(boolean c)
    {
        if (c)
        {
            int k = 3;
            field = k;
        }
        else
        {
            Object o = "x";
            field = o.hashCode();
        }
        return field;
    }

    /** A handler reads locals defined before and inside the try. */
    static int tryCatch(int a)
    {
        int b = a + 1;
        try
        {
            b = b * 2;
            field = b / a;
            b = b + 3;
        }
        catch (ArithmeticException e)
        {
            return b + e.hashCode();
        }
        return b;
    }

    /** finally: javac's catch-all copies, with a handler covering itself. */
    static int tryFinally(Object lock, int a)
    {
        synchronized (lock)
        {
            try
            {
                return a / field;
            }
            finally
            {
                field = 0;
            }
        }
    }

    /** A long live in a handler, in a loop. */
    static long wideInHandler(long n)
    {
        long t = 0;
        for (int i = 0; i < 4; i++)
        {
            try
            {
                t += n / i;
            }
            catch (ArithmeticException e)
            {
                t -= n;
            }
        }
        return t;
    }

    static int switches(int k, String s)
    {
        int r;
        switch (k)
        {
            case 1:
                r = 10;
                break;
            case 2:
            case 3:
                r = 20;
                break;
            case 100:
                r = 30;
                break;
            default:
                r = 0;
        }
        switch (s.length())
        {
            case 0:
                return r;
            case 1:
            case 2:
                return r + 1;
            default:
                return r + 2;
        }
    }

    /** dup2_x1 and friends: compound assignment to a long field and a long array element. */
    long dups(long[] a, int i)
    {
        a[i] += 5;
        long v = wide++;
        return a[i] + v;
    }

    /** The unboxed word types and null as an Address. */
    static long words(Address p, boolean c)
    {
        Address q = c ? p : null;
        Address r = q.plus(8);
        return r.toLong();
    }

    @Optimize
    static int pragma(int x)
    {
        return x + 1;
    }

    /** A loop whose header is bci 0 (the synthetic entry makes it an ordinary header). */
    static int headerAtZero(int n)
    {
        while (n > 0)
        {
            n = n - 3;
        }
        return n;
    }
}
