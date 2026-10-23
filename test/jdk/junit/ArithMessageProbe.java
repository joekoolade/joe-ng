/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-23
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The {@code ArithmeticException}s of integer arithmetic, with their messages: the IMPLICIT one the JIT raises for
 * {@code idiv}/{@code irem}/{@code ldiv}/{@code lrem} by zero (stock's message is {@code "/ by zero"}), the
 * overflow ones {@code Math} throws explicitly, and the library ones built on top. The divisor comes from a static
 * so javac cannot fold it. The non-throwing edges ({@code MIN_VALUE / -1}, {@code MIN_VALUE % -1}) are printed
 * too: AArch64's {@code SDIV} answers them without trapping, and Java's answers are the same.
 */
public class ArithMessageProbe
{
    static int zero = 0;
    static long zeroL = 0L;
    static int minusOne = -1;
    static long minusOneL = -1L;
    static int isink;
    static long lsink;

    interface Body
    {
        void run();
    }

    private static void arm(String name, Body b)
    {
        try
        {
            b.run();
            System.out.println(name + " -> no exception");
        }
        catch (ArithmeticException e)
        {
            System.out.println(name + " -> " + e.getClass().getName() + ": " + e.getMessage());
        }
        catch (Throwable t)
        {
            System.out.println(name + " -> WRONG " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * A divide in a method with no handler and NO OTHER CALL, so the exception crosses a frame on its way to
     * {@link #arm} out of a method the compiler could take for a leaf. It did: the divide's throw path is a call,
     * but the method saved no return address, and the exception skipped every handler above it.
     */
    static int divideDeep(int a, int b)
    {
        return a / b;
    }

    /** {@link #divideDeep}'s long remainder twin. */
    static long remDeep(long a, long b)
    {
        return a % b;
    }

    public static void main(String[] args)
    {
        arm("7 / 0", () -> isink = 7 / zero);
        arm("7 % 0", () -> isink = 7 % zero);
        arm("7L / 0L", () -> lsink = 7L / zeroL);
        arm("7L % 0L", () -> lsink = 7L % zeroL);
        arm("0 / 0", () -> isink = 0 / zero);
        arm("divideDeep(1, 0)", () -> isink = divideDeep(1, zero));
        arm("remDeep(1L, 0L)", () -> lsink = remDeep(1L, zeroL));
        try
        {
            isink = divideDeep(2, zero);
            System.out.println("divideDeep from main -> no exception");
        }
        catch (ArithmeticException e)
        {
            System.out.println("divideDeep from main -> caught: " + e.getMessage());
        }
        arm("char / 0", () -> isink = 'c' / zero);
        arm("short % 0", () -> isink = ((short) 9) % zero);
        arm("byte / 0", () -> isink = ((byte) 3) / zero);
        isink = Integer.MIN_VALUE / minusOne;
        System.out.println("MIN_VALUE / -1 = " + isink);
        isink = Integer.MIN_VALUE % minusOne;
        System.out.println("MIN_VALUE % -1 = " + isink);
        lsink = Long.MIN_VALUE / minusOneL;
        System.out.println("Long.MIN_VALUE / -1 = " + lsink);
        lsink = Long.MIN_VALUE % minusOneL;
        System.out.println("Long.MIN_VALUE % -1 = " + lsink);
        System.out.println("7.0 / 0 = " + (7.0 / zero) + ", 0.0 % 0 = " + (0.0 % zero));

        arm("Math.floorDiv(7, 0)", () -> isink = Math.floorDiv(7, zero));
        arm("Math.floorMod(7L, 0L)", () -> lsink = Math.floorMod(7L, zeroL));
        arm("Math.ceilDiv(7, 0)", () -> isink = Math.ceilDiv(7, zero));
        arm("Integer.divideUnsigned(7, 0)", () -> isink = Integer.divideUnsigned(7, zero));
        arm("Long.remainderUnsigned(7, 0)", () -> lsink = Long.remainderUnsigned(7L, zeroL));
        arm("Math.addExact(MAX, 1)", () -> isink = Math.addExact(Integer.MAX_VALUE, 1));
        arm("Math.multiplyExact(MAX, 2L)", () -> lsink = Math.multiplyExact(Long.MAX_VALUE, 2L));
        arm("Math.negateExact(MIN)", () -> isink = Math.negateExact(Integer.MIN_VALUE));
        arm("Math.toIntExact(1L << 40)", () -> isink = Math.toIntExact(1L << 40));
        arm("Math.divideExact(MIN, -1)", () -> isink = Math.divideExact(Integer.MIN_VALUE, -1));
        arm("Math.absExact(Long.MIN)", () -> lsink = Math.absExact(Long.MIN_VALUE));
        arm("BigInteger.ONE / 0", () -> BigInteger.ONE.divide(BigInteger.ZERO));
        arm("BigInteger.ONE mod 0", () -> BigInteger.ONE.mod(BigInteger.ZERO));
        arm("BigInteger 2^40 intValueExact", () -> BigInteger.ONE.shiftLeft(40).intValueExact());
        arm("BigDecimal.ONE / 0", () -> BigDecimal.ONE.divide(BigDecimal.ZERO));
        arm("BigDecimal.ZERO / 0", () -> BigDecimal.ZERO.divide(BigDecimal.ZERO));
        arm("BigDecimal 1/3 exact", () -> BigDecimal.ONE.divide(BigDecimal.valueOf(3)));
        System.out.println("ArithMessageProbe done");
    }
}
