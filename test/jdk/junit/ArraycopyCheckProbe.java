/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-22
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.util.Arrays;

/**
 * {@code System.arraycopy}'s checks, in the JVMS order and with stock's messages: a null array is a
 * NullPointerException; a non-array, or two arrays of incompatible kinds, an ArrayStoreException before anything
 * is copied; a negative position or length, or a range past either end, an ArrayIndexOutOfBoundsException with
 * nothing copied; and a reference element not assignable to the destination's element type an
 * ArrayStoreException AFTER the elements before it were copied. The destination is printed after every failure,
 * so a copy that went ahead anyway shows. Element SIZE does not decide compatibility ({@code boolean[]} and
 * {@code byte[]} are both 1), a {@code String[]} of nulls copies into an {@code Integer[]} because only the
 * elements are checked, and one exception has to leave a frame with no handler. The legal cases cover overlap in
 * both directions, a {@code char[]} range and a zero-length copy at the very end.
 */
public class ArraycopyCheckProbe
{
    static String show(Object a)
    {
        if (a instanceof int[] x)
        {
            return Arrays.toString(x);
        }
        if (a instanceof long[] x)
        {
            return Arrays.toString(x);
        }
        if (a instanceof byte[] x)
        {
            return Arrays.toString(x);
        }
        if (a instanceof float[] x)
        {
            return Arrays.toString(x);
        }
        if (a instanceof char[] x)
        {
            return new String(x);
        }
        if (a instanceof Object[] x)
        {
            return Arrays.deepToString(x);
        }
        return String.valueOf(a);
    }

    static void copy(String what, Object src, int sp, Object dst, int dp, int n)
    {
        try
        {
            System.arraycopy(src, sp, dst, dp, n);
            System.out.println(what + ": copied " + show(dst));
        }
        catch (RuntimeException e)
        {
            System.out.println(what + ": " + e.getClass().getName() + ": " + e.getMessage() + " dst " + show(dst));
        }
    }

    /** No handler here: the exception has to leave this frame for main's. */
    static void through(Object[] src, Object[] dst)
    {
        System.arraycopy(src, 0, dst, 0, src.length);
        System.out.println("through: not reached " + dst[0]);
    }

    public static void main(String[] args)
    {
        copy("null src", null, 0, new int[2], 0, 1);
        copy("null dst", new int[2], 0, null, 0, 1);
        copy("String src", "s", 0, new Object[2], 0, 1);
        copy("String dst", new Object[2], 0, "s", 0, 1);
        copy("int[] to long[]", new int[] { 1, 2 }, 0, new long[2], 0, 1);
        copy("int[] to Object[]", new int[] { 1, 2 }, 0, new Object[2], 0, 1);
        copy("Object[] to int[]", new Object[2], 0, new int[2], 0, 1);
        copy("src negative", new int[] { 1, 2 }, -1, new int[2], 0, 1);
        copy("dst negative", new int[] { 1, 2 }, 0, new int[2], -1, 1);
        copy("length negative", new int[] { 1, 2 }, 0, new int[2], 0, -1);
        copy("src overrun", new int[] { 1, 2 }, 1, new int[2], 0, 2);
        copy("dst overrun", new Object[] { "a", "b" }, 0, new Object[3], 2, 2);
        copy("Integer among Strings", new Object[] { "a", "b", 7, "d" }, 0, new String[4], 0, 4);
        copy("Integer among Strings from 1", new Object[] { "a", "b", 7 }, 1, new String[3], 0, 2);
        copy("String[][] to Integer[][]", new String[][] { { "s" } }, 0, new Integer[1][1], 0, 1);
        copy("Integer[] to Number[]", new Integer[] { 1, 2 }, 0, new Number[2], 0, 2);
        copy("Object[] of Integers to Number[]", new Object[] { 1, 2 }, 0, new Number[2], 0, 2);
        copy("nulls to String[]", new Object[2], 0, new String[] { "x", "y" }, 0, 2);
        copy("boolean[] to byte[]", new boolean[2], 0, new byte[2], 0, 1);
        copy("int[] to float[]", new int[2], 0, new float[2], 0, 1);
        copy("String[] of nulls to Integer[]", new String[2], 0, new Integer[] { 1, 2 }, 0, 2);
        copy("char[] middle", "abcdef".toCharArray(), 1, "......".toCharArray(), 2, 3);
        copy("zero length at the end", new int[] { 1, 2 }, 2, new int[2], 2, 0);
        int[] a = { 1, 2, 3, 4, 5 };
        System.arraycopy(a, 0, a, 1, 4);
        System.out.println("overlap forward " + Arrays.toString(a));
        Object[] o = { "a", "b", "c", "d", "e" };
        System.arraycopy(o, 1, o, 0, 4);
        System.out.println("overlap backward " + Arrays.toString(o));
        try
        {
            through(new Object[] { "a", 1 }, new String[2]);
            System.out.println("through: copied");
        }
        catch (ArrayStoreException e)
        {
            System.out.println("through a frame that does not catch: " + e.getMessage());
        }
        System.out.println("ArraycopyCheckProbe done");
    }
}
