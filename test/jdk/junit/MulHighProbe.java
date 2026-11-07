/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-07
 */

/**
 * {@code Math.multiplyHigh} and {@code Math.unsignedMultiplyHigh} are lowered to the A64 {@code SMULH} / {@code UMULH}
 * instructions instead of being called. This pins that the lowering computes exactly what the stock Java bodies do:
 * the sign corners ({@code MIN_VALUE * MIN_VALUE}, {@code -1}, mixed signs, where a signed/unsigned mix-up shows) and
 * a pseudo-random sweep, each also checked against a reference built from 32-bit halves that never calls either
 * method. The call shapes vary on purpose: operands from locals, from a field and from another call's result, and a
 * result consumed directly by arithmetic, because the lowering pops and pushes the operand stack itself.
 */
public final class MulHighProbe
{
    static long field = 0x0123456789ABCDEFL;

    public static void main(String[] args)
    {
        long[] corners = {
            0L, 1L, -1L, 2L, -2L, 3L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE - 1L,
            0xFFFFFFFFL, 0x100000000L, -0x100000000L, 0x7FFFFFFFL, 0x80000000L, 0xDEADBEEFCAFEBABEL,
        };
        int bad = 0;
        long sig = 0L;
        for (int i = 0; i < corners.length; i++)
        {
            for (int j = 0; j < corners.length; j++)
            {
                bad += check(corners[i], corners[j]);
                sig = sig * 31L + Math.multiplyHigh(corners[i], corners[j]);
                sig = sig * 31L + Math.unsignedMultiplyHigh(corners[i], corners[j]);
            }
        }
        long x = 0x9E3779B97F4A7C15L;
        for (int k = 0; k < 4000; k++)
        {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            long y = x * 0xBF58476D1CE4E5B9L + k;
            bad += check(x, y);
            sig ^= Math.multiplyHigh(x, y) + 3L * Math.unsignedMultiplyHigh(y, x);
        }
        long viaField = Math.multiplyHigh(field, Math.unsignedMultiplyHigh(field, -field)) - Math.multiplyHigh(-1L, field);
        System.out.println("corners " + corners.length * corners.length + " sweep 4000 mismatches " + bad);
        System.out.println("signature " + Long.toHexString(sig));
        System.out.println("nested " + Long.toHexString(viaField));
        System.out.println("MIN*MIN " + Math.multiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE)
                + " unsigned " + Long.toHexString(Math.unsignedMultiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE)));
        System.out.println("-1*-1 " + Math.multiplyHigh(-1L, -1L)
                + " unsigned " + Long.toHexString(Math.unsignedMultiplyHigh(-1L, -1L)));
    }

    /** 1 if either method disagrees with the 32-bit-halves reference for (a, b). */
    static int check(long a, long b)
    {
        long u = refUnsignedHigh(a, b);
        long s = u - ((a >> 63) & b) - ((b >> 63) & a);   // signed high from unsigned high (Hacker's Delight 8-3)
        int r = 0;
        if (Math.unsignedMultiplyHigh(a, b) != u)
        {
            r = 1;
        }
        if (Math.multiplyHigh(a, b) != s)
        {
            r = 1;
        }
        return r;
    }

    /** High 64 bits of the unsigned product, from 32-bit halves only. */
    static long refUnsignedHigh(long a, long b)
    {
        long a0 = a & 0xFFFFFFFFL;
        long a1 = a >>> 32;
        long b0 = b & 0xFFFFFFFFL;
        long b1 = b >>> 32;
        long p00 = a0 * b0;
        long p01 = a0 * b1;
        long p10 = a1 * b0;
        long p11 = a1 * b1;
        long mid = (p00 >>> 32) + (p01 & 0xFFFFFFFFL) + (p10 & 0xFFFFFFFFL);
        return p11 + (p01 >>> 32) + (p10 >>> 32) + (mid >>> 32);
    }
}
