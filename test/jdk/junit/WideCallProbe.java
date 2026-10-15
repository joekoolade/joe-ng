/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-15
 */
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Reflective calls with MANY arguments. The JIT passes arguments 0..15 in x0..x15 and, past 16, puts a pointer
 * to the rest in x15; reflective invoke loaded x0..x7 only and laid its arguments out in a {@code long[8]}, and
 * resolving a member wrote one byte per parameter into a {@code byte[8]} with no bound. The arities straddle
 * every boundary: 8 and 9 (the old register limit), 15, 16 and 17 (the pointer switch, counted WITH the
 * receiver for the instance and constructor forms), and 20. Each argument is distinct and position-weighted, so
 * a shifted, dropped or duplicated argument changes the answer; mixed int/long/double/float/String arguments
 * check that each keeps its type across the boundary. Every reflective answer is printed beside the direct
 * call's. Prints values only; the host diff is the gate.
 */
public class WideCallProbe
{
    final int base;

    WideCallProbe(int a0, long a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, String a9)
    {
        this.base = a0 + (int) a1 * 2 + a2 * 3 + a3 * 4 + a4 * 5 + a5 * 6 + a6 * 7 + a7 * 8 + a8 * 9
                + a9.length() * 10;
    }

    WideCallProbe(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
            int a11, int a12, int a13, int a14, int a15)
    {
        this.base = a0 + a1 * 2 + a2 * 3 + a3 * 4 + a4 * 5 + a5 * 6 + a6 * 7 + a7 * 8 + a8 * 9 + a9 * 10
                + a10 * 11 + a11 * 12 + a12 * 13 + a13 * 14 + a14 * 15 + a15 * 16;
    }

    static long eight(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7)
    {
        return a0 + a1 * 2L + a2 * 3L + a3 * 4L + a4 * 5L + a5 * 6L + a6 * 7L + a7 * 8L;
    }

    static long nine(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8)
    {
        return eight(a0, a1, a2, a3, a4, a5, a6, a7) + a8 * 9L;
    }

    static String fifteen(int a0, long a1, double a2, float a3, String a4, int a5, int a6, int a7, int a8,
            long a9, double a10, int a11, String a12, int a13, int a14)
    {
        return a0 + "|" + a1 + "|" + a2 + "|" + a3 + "|" + a4 + "|" + a5 + "|" + a6 + "|" + a7 + "|" + a8 + "|"
                + a9 + "|" + a10 + "|" + a11 + "|" + a12 + "|" + a13 + "|" + a14;
    }

    static String sixteen(int a0, long a1, double a2, float a3, String a4, int a5, int a6, int a7, int a8,
            long a9, double a10, int a11, String a12, int a13, int a14, double a15)
    {
        return fifteen(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14) + "|" + a15;
    }

    static String seventeen(int a0, long a1, double a2, float a3, String a4, int a5, int a6, int a7, int a8,
            long a9, double a10, int a11, String a12, int a13, int a14, double a15, String a16)
    {
        return sixteen(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15) + "|" + a16;
    }

    static long twenty(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
            int a11, int a12, int a13, int a14, int a15, int a16, int a17, int a18, long a19)
    {
        return nine(a0, a1, a2, a3, a4, a5, a6, a7, a8) + a9 * 10L + a10 * 11L + a11 * 12L + a12 * 13L
                + a13 * 14L + a14 * 15L + a15 * 16L + a16 * 17L + a17 * 18L + a18 * 19L + a19 * 20L;
    }

    /** Sixteen parameters plus the receiver: the instance form crosses into the pointer convention. */
    String instanceSixteen(int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
            int a11, int a12, int a13, int a14, String a15)
    {
        return base + ":" + (a0 + a1 * 2 + a2 * 3 + a3 * 4 + a4 * 5 + a5 * 6 + a6 * 7 + a7 * 8 + a8 * 9 + a9 * 10
                + a10 * 11 + a11 * 12 + a12 * 13 + a13 * 14 + a14 * 15) + ":" + a15;
    }

    public static void main(String[] args) throws Exception
    {
        Class<?> c = WideCallProbe.class;
        Class<?> I = int.class;
        Class<?> J = long.class;
        Class<?> D = double.class;
        Class<?> F = float.class;
        Class<?> S = String.class;

        Method m8 = c.getDeclaredMethod("eight", I, I, I, I, I, I, I, I);
        System.out.println("eight     reflect=" + m8.invoke(null, 1, 2, 3, 4, 5, 6, 7, 8)
                + " direct=" + eight(1, 2, 3, 4, 5, 6, 7, 8));
        Method m9 = c.getDeclaredMethod("nine", I, I, I, I, I, I, I, I, I);
        System.out.println("nine      reflect=" + m9.invoke(null, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                + " direct=" + nine(1, 2, 3, 4, 5, 6, 7, 8, 9));
        Method m15 = c.getDeclaredMethod("fifteen", I, J, D, F, S, I, I, I, I, J, D, I, S, I, I);
        System.out.println("fifteen   reflect=" + m15.invoke(null, 1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5,
                12, "thirteen", 14, 15));
        System.out.println("          direct=" + fifteen(1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5, 12,
                "thirteen", 14, 15));
        Method m16 = c.getDeclaredMethod("sixteen", I, J, D, F, S, I, I, I, I, J, D, I, S, I, I, D);
        System.out.println("sixteen   reflect=" + m16.invoke(null, 1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5,
                12, "thirteen", 14, 15, 16.75));
        System.out.println("          direct=" + sixteen(1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5, 12,
                "thirteen", 14, 15, 16.75));
        Method m17 = c.getDeclaredMethod("seventeen", I, J, D, F, S, I, I, I, I, J, D, I, S, I, I, D, S);
        System.out.println("seventeen reflect=" + m17.invoke(null, 1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5,
                12, "thirteen", 14, 15, 16.75, "seventeen"));
        System.out.println("          direct=" + seventeen(1, 2L, 3.5, 4.25f, "five", 6, 7, 8, 9, 10L, 11.5, 12,
                "thirteen", 14, 15, 16.75, "seventeen"));
        Method m20 = c.getDeclaredMethod("twenty", I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, J);
        System.out.println("twenty    reflect=" + m20.invoke(null, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20L) + " direct="
                + twenty(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20L));
        System.out.println("twenty params = " + Arrays.toString(m20.getParameterTypes()));

        Constructor<?> k10 = c.getDeclaredConstructor(I, J, I, I, I, I, I, I, I, S);
        WideCallProbe w10 = (WideCallProbe) k10.newInstance(1, 2L, 3, 4, 5, 6, 7, 8, 9, "ten");
        System.out.println("ctor(10)  reflect=" + w10.base + " direct="
                + new WideCallProbe(1, 2L, 3, 4, 5, 6, 7, 8, 9, "ten").base);
        Constructor<?> k16 = c.getDeclaredConstructor(I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, I);
        WideCallProbe w16 = (WideCallProbe) k16.newInstance(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        System.out.println("ctor(16)  reflect=" + w16.base + " direct="
                + new WideCallProbe(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16).base);
        Method mi = c.getDeclaredMethod("instanceSixteen", I, I, I, I, I, I, I, I, I, I, I, I, I, I, I, S);
        System.out.println("inst(16)  reflect=" + mi.invoke(w10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                "last") + " direct=" + w10.instanceSixteen(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, "last"));

        int declared = 0;
        for (Method m : c.getDeclaredMethods())
        {
            if (m.getParameterCount() >= 8)
            {
                declared += 1;
            }
        }
        System.out.println("declared methods with >= 8 params = " + declared);
        try
        {
            m9.invoke(null, 1, 2, 3);
            System.out.println("short args returned");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("short args: " + e.getMessage());
        }
        System.out.println("WideCallProbe done");
    }
}
