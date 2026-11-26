/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-11-25
 *
 * SPDX-License-Identifier: EPL-2.0
 */

/**
 * VM code that fills a container with RAW stores across allocating calls (frames/GC arc S7-4d''').
 *
 * <p>The shape: the container is mature by the time a store lands -- born mature (16 KiB or more, logged at
 * birth), or promoted by a minor collection in mid-fill -- and a minor in between found it naming nothing young,
 * so it is not logged. The next element is young and the raw store skips the barrier: the next minor moves the
 * element and leaves the container's slot naming the evacuated copy. {@code multianewarray} fills its levels
 * this way; each case below is one of its shapes. The churn after each build forces more minors, and the read
 * back checks every element is intact. With the nursery off, or the stores logged, the output is the host's.
 */
public class NurseryFillProbe
{
    static Object sink;

    /** Allocate {@code n} short-lived arrays: minor collections, without keeping anything. */
    static void churn(int n)
    {
        int i = 0;
        while (i < n)
        {
            sink = new long[32];
            i += 1;
        }
    }

    static long sum(int[][] a)
    {
        long s = 0L;
        int i = 0;
        while (i < a.length)
        {
            int j = 0;
            while (j < a[i].length)
            {
                s += a[i][j];
                j += 1;
            }
            i += 1;
        }
        return s;
    }

    static void fill(int[][] a)
    {
        int i = 0;
        while (i < a.length)
        {
            int j = 0;
            while (j < a[i].length)
            {
                a[i][j] = i * 31 + j;
                j += 1;
            }
            i += 1;
        }
    }

    /** A small outer array, large inner ones: the outer is promoted in mid-fill. */
    static void smallOuter()
    {
        int[][] a = new int[200][1000];
        churn(20000);
        fill(a);
        churn(20000);
        System.out.println("small outer, large inner: " + a.length + " x " + a[0].length + " sum=" + sum(a));
    }

    /** A large outer array (born mature), small inner ones. */
    static void largeOuter()
    {
        int[][] a = new int[5000][4];
        churn(20000);
        fill(a);
        churn(20000);
        System.out.println("large outer, small inner: " + a.length + " x " + a[0].length + " sum=" + sum(a));
    }

    /** Three levels: the middle level is filled across allocations too. */
    static void threeLevels()
    {
        long[][][] a = new long[40][50][20];
        churn(20000);
        long s = 0L;
        int i = 0;
        while (i < 40)
        {
            int j = 0;
            while (j < 50)
            {
                int k = 0;
                while (k < 20)
                {
                    a[i][j][k] = (long) i * 1000L + j * 20L + k;
                    s += a[i][j][k];
                    k += 1;
                }
                j += 1;
            }
            i += 1;
        }
        churn(20000);
        long t = 0L;
        i = 0;
        while (i < 40)
        {
            int j = 0;
            while (j < 50)
            {
                int k = 0;
                while (k < 20)
                {
                    t += a[i][j][k];
                    k += 1;
                }
                j += 1;
            }
            i += 1;
        }
        System.out.println("three levels: " + (s == t) + " sum=" + t);
    }

    /** A reference leaf level: dims fewer than the rank leaves the innermost elements null. */
    static void partialDims()
    {
        String[][][] a = new String[300][100][];
        churn(20000);
        int nulls = 0;
        int i = 0;
        while (i < 300)
        {
            int j = 0;
            while (j < 100)
            {
                if (a[i][j] == null)
                {
                    nulls += 1;
                }
                j += 1;
            }
            i += 1;
        }
        System.out.println("partial dims: nulls=" + nulls);
    }

    public static void main(String[] args)
    {
        int round = 0;
        while (round < 3)
        {
            smallOuter();
            largeOuter();
            threeLevels();
            partialDims();
            round += 1;
        }
        System.out.println("NurseryFillProbe done");
    }
}
