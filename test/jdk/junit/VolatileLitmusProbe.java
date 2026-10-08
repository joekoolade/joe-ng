/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-08
 */

/**
 * The STORE-BUFFERING litmus test on {@code volatile} fields: thread A does {@code x = 1; r1 = y}, thread B does
 * {@code y = 1; r2 = x}. The Java memory model makes volatile accesses sequentially consistent, so the outcome
 * {@code r1 == 0 && r2 == 0} is FORBIDDEN. ARMv8 permits exactly that reordering for plain loads and stores (a
 * store may sit in the store buffer while a later load to a different address completes), so a VM that compiles
 * volatile accesses without a barrier produces it.
 *
 * <p>Each of {@link #ROUNDS} rounds resets x/y, releases both threads through a shared round counter, and records
 * whether the forbidden outcome occurred. The gate is the count: it must be 0. The coordination itself is all
 * volatile, so it is ordered by the same barriers under test. Prints only the count and a liveness check, so the
 * output is identical on a host JVM (where the count is 0 by construction of HotSpot's volatiles).
 */
public class VolatileLitmusProbe
{
    private static final int ROUNDS = 20000;

    static volatile int x;
    static volatile int y;
    static volatile int round;
    static volatile int doneA;
    static volatile int doneB;
    static int[] r1 = new int[ROUNDS];
    static int[] r2 = new int[ROUNDS];

    public static void main(String[] args) throws Exception
    {
        Thread a = new Thread(() ->
        {
            int i = 0;
            while (i < ROUNDS)
            {
                while (round != i + 1)
                {
                    Thread.onSpinWait();
                }
                x = 1;
                r1[i] = y;
                doneA = i + 1;
                i += 1;
            }
        });
        Thread b = new Thread(() ->
        {
            int i = 0;
            while (i < ROUNDS)
            {
                while (round != i + 1)
                {
                    Thread.onSpinWait();
                }
                y = 1;
                r2[i] = x;
                doneB = i + 1;
                i += 1;
            }
        });
        a.start();
        b.start();
        int i = 0;
        while (i < ROUNDS)
        {
            x = 0;
            y = 0;
            round = i + 1;
            while (doneA != i + 1 || doneB != i + 1)
            {
                Thread.onSpinWait();
            }
            i += 1;
        }
        a.join();
        b.join();
        int forbidden = 0;
        int sawBoth = 0;
        int k = 0;
        while (k < ROUNDS)
        {
            if (r1[k] == 0 && r2[k] == 0)
            {
                forbidden += 1;
            }
            if (r1[k] == 1 || r2[k] == 1)
            {
                sawBoth += 1;
            }
            k += 1;
        }
        System.out.println("rounds = " + ROUNDS);
        System.out.println("forbidden r1==0 && r2==0 = " + forbidden);
        System.out.println("every round saw a store = " + (sawBoth + forbidden == ROUNDS));
        System.out.println("VolatileLitmusProbe done");
    }
}
