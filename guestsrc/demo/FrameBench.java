/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package demo;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * The benchmark for the memory-frame / precise-GC arc (PLAN.md): kernels that stress what that arc changes --
 * calls and frames, locals in a hot loop, arrays, allocation and field access, interface dispatch, strings --
 * plus two library workloads the web server lives on (SHA-256, BigInteger modPow as in TLS).
 *
 * <p>Each kernel runs once to warm up (so first-call compiles are not timed), then {@code REPS} times; the best
 * time is printed in milliseconds. Every kernel returns a checksum, printed too: a later change to the compiler
 * must leave every checksum identical, so the same run is also a correctness check. {@code args[0]}, if given,
 * scales the work (default 1).
 *
 * <p><b>Allocation is kept to about 25 MB a run</b> (trees ~8 MB, strings ~6 MB, sort ~5 MB, SHA input ~6 MB):
 * a /run program on cores 1-3 allocates from an arena that is never collected, and an earlier version
 * allocating ~50 MB halted the Pi with heap OOM (BACKLOG.md "Collector").
 */
public final class FrameBench
{
    private FrameBench()
    {
    }

    private static final int REPS = 5;

    private static int scale = 1;

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            scale = Integer.parseInt(args[0]);
        }
        System.out.println("FrameBench scale=" + scale + " reps=" + REPS + " (best ms, checksum)");
        long total = 0L;
        total += run("calls    fib(32)          ", 0);
        total += run("locals   8-local mix loop ", 1);
        total += run("arrays   sort 200k ints   ", 2);
        total += run("objects  tree build+sum   ", 3);
        total += run("dispatch interface calls  ", 4);
        total += run("strings  StringBuilder    ", 5);
        total += run("sha256   1 MiB            ", 6);
        total += run("bigint   modPow 1024-bit  ", 7);
        System.out.println("FrameBench total " + (total / 10) + "." + (total % 10) + " ms");
    }

    /** Warm up once, then time {@code REPS} runs of kernel {@code k}; print the best. Answers it in 0.1 ms. */
    private static long run(String name, int k) throws Exception
    {
        long check = kernel(k);
        long best = Long.MAX_VALUE;
        int r = 0;
        while (r < REPS)
        {
            long t0 = System.nanoTime();
            long c = kernel(k);
            long t = System.nanoTime() - t0;
            if (c != check)
            {
                System.out.println("FrameBench " + name + " CHECKSUM CHANGED " + check + " -> " + c);
            }
            if (t < best)
            {
                best = t;
            }
            r += 1;
        }
        long tenths = best / 100_000L;
        System.out.println(name + " " + (tenths / 10) + "." + (tenths % 10) + " ms  check " + Long.toHexString(check));
        return tenths;
    }

    private static long kernel(int k) throws Exception
    {
        if (k == 0)
        {
            long s = 0L;
            int i = 0;
            while (i < scale)
            {
                s += fib(32);
                i += 1;
            }
            return s;
        }
        if (k == 1)
        {
            return mix(2_000_000 * scale);
        }
        if (k == 2)
        {
            return sort(200_000, scale);
        }
        if (k == 3)
        {
            return trees(scale);
        }
        if (k == 4)
        {
            return dispatch(1_000_000 * scale);
        }
        if (k == 5)
        {
            return strings(scale);
        }
        if (k == 6)
        {
            return sha(scale);
        }
        return bigint(scale);
    }

    private static int fib(int n)
    {
        if (n < 2)
        {
            return n;
        }
        return fib(n - 1) + fib(n - 2);
    }

    /** Eight live locals in one loop: the shape a memory frame pays for on every access. */
    private static long mix(int n)
    {
        int a = 1;
        int b = 2;
        int c = 3;
        int d = 4;
        long e = 5L;
        long f = 6L;
        int i = 0;
        while (i < n)
        {
            a = a * 31 + b;
            b = b ^ (c >>> 3);
            c = c + d * 7;
            d = d - a;
            e = e + (a & 0xFF);
            f = f ^ (e << 1);
            i += 1;
        }
        return e + f + a + b + c + d;
    }

    private static long sort(int n, int times)
    {
        long sum = 0L;
        int t = 0;
        while (t < times)
        {
            int[] v = new int[n];
            int seed = 12345 + t;
            int i = 0;
            while (i < n)
            {
                seed = seed * 1103515245 + 12345;
                v[i] = seed >>> 8;
                i += 1;
            }
            quicksort(v, 0, n - 1);
            i = 1;
            while (i < n)
            {
                if (v[i - 1] > v[i])
                {
                    return -1L;
                }
                i += 1;
            }
            sum += v[n / 2];
            t += 1;
        }
        return sum;
    }

    private static void quicksort(int[] v, int lo, int hi)
    {
        while (lo < hi)
        {
            int p = v[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j)
            {
                while (v[i] < p)
                {
                    i += 1;
                }
                while (v[j] > p)
                {
                    j -= 1;
                }
                if (i <= j)
                {
                    int tmp = v[i];
                    v[i] = v[j];
                    v[j] = tmp;
                    i += 1;
                    j -= 1;
                }
            }
            if (j - lo < hi - i)
            {
                quicksort(v, lo, j);
                lo = i;
            }
            else
            {
                quicksort(v, i, hi);
                hi = j;
            }
        }
    }

    private static final class Node
    {
        final Node left;
        final Node right;
        final int value;

        Node(Node left, Node right, int value)
        {
            this.left = left;
            this.right = right;
            this.value = value;
        }
    }

    private static Node build(int depth, int v)
    {
        if (depth == 0)
        {
            return new Node(null, null, v);
        }
        return new Node(build(depth - 1, v * 2), build(depth - 1, v * 2 + 1), v);
    }

    private static long sum(Node n)
    {
        if (n == null)
        {
            return 0L;
        }
        return n.value + sum(n.left) + sum(n.right);
    }

    private static long trees(int times)
    {
        long s = 0L;
        int t = 0;
        while (t < times)
        {
            s += sum(build(14, 1));
            t += 1;
        }
        return s;
    }

    private interface Shape
    {
        int area(int k);
    }

    private static final class Square implements Shape
    {
        public int area(int k)
        {
            return k * k;
        }
    }

    private static final class Rect implements Shape
    {
        public int area(int k)
        {
            return k * (k + 1);
        }
    }

    private static long dispatch(int n)
    {
        Shape[] shapes = { new Square(), new Rect() };
        long s = 0L;
        int i = 0;
        while (i < n)
        {
            s += shapes[i & 1].area(i & 1023);
            i += 1;
        }
        return s;
    }

    private static long strings(int times)
    {
        long s = 0L;
        int t = 0;
        while (t < times * 25)
        {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < 2000)
            {
                sb.append(i).append(',');
                i += 1;
            }
            s += sb.toString().hashCode();
            t += 1;
        }
        return s;
    }

    private static long sha(int times) throws Exception
    {
        byte[] data = new byte[1 << 20];
        int i = 0;
        while (i < data.length)
        {
            data[i] = (byte) (i * 7);
            i += 1;
        }
        long s = 0L;
        int t = 0;
        while (t < times)
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            s += (h[0] & 0xFF) | ((h[1] & 0xFF) << 8);
            t += 1;
        }
        return s;
    }

    private static long bigint(int times)
    {
        BigInteger m = BigInteger.ONE.shiftLeft(1024).subtract(BigInteger.valueOf(105));
        BigInteger b = BigInteger.valueOf(65537).pow(40).mod(m);
        BigInteger e = BigInteger.ONE.shiftLeft(1023).add(BigInteger.valueOf(12345));
        long s = 0L;
        int t = 0;
        while (t < times)
        {
            s += b.modPow(e, m).intValue();
            t += 1;
        }
        return s;
    }
}
