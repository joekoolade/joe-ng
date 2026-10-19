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
 * {@code iinc} constants past a byte are {@code wide iinc} with a SIGNED SHORT, and A64's ADD/SUB immediate holds
 * only 12 bits: the compiler masked the constant, so {@code i += 4096} was {@code i += 0}. Every boundary of the
 * split -- 4095 (one instruction), 4096 (high part only), 4097 (both), the short's extremes, and the negatives --
 * in a local the compiler keeps in a REGISTER (a plain int) and one it keeps in the FRAME (many locals), since
 * iinc lowers both ways. Each step is also checked against the same arithmetic done with iadd.
 */
public class WideIincProbe
{
    public static void main(String[] args)
    {
        int a = 0;
        a += 4095;
        System.out.println("+4095 " + a);
        a += 4096;
        System.out.println("+4096 " + a);
        a += 4097;
        System.out.println("+4097 " + a);
        a += 32767;
        System.out.println("+32767 " + a);
        a -= 32768;
        System.out.println("-32768 " + a);
        a -= 4096;
        System.out.println("-4096 " + a);
        a -= 4097;
        System.out.println("-4097 " + a);
        a += 12345;
        System.out.println("+12345 " + a);

        int w = Integer.MAX_VALUE - 100;
        w += 4096;
        System.out.println("wraps " + w);

        int n = 0;
        int steps = 0;
        while (n < (1 << 20))
        {
            n += 8192;
            steps += 1;
        }
        System.out.println("loop steps " + steps);

        long l0 = 0;
        long l1 = 1;
        long l2 = 2;
        long l3 = 3;
        long l4 = 4;
        long l5 = 5;
        long l6 = 6;
        long l7 = 7;
        int f = 10;
        f += 30000;
        f -= 4100;
        System.out.println("frame local " + f + " " + (l0 + l1 + l2 + l3 + l4 + l5 + l6 + l7));

        int down = 100000;
        int k = 0;
        while (k < 3)
        {
            down -= 20000;
            k += 1;
        }
        System.out.println("down " + down);
        System.out.println("WideIincProbe done");
    }
}
