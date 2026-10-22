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

/**
 * Array allocation at the edge of the metal compiler's operand-register window. {@code newarray}/{@code anewarray}
 * push the element size over the length for the allocation call -- one entry ABOVE the net depth -- and the
 * depth pre-pass did not count it, so a method whose net peak was exactly 7 (the register window) was compiled
 * shallow and its 8th push failed: {@code JIT unsupported: reason=8 a=7}, at the allocation's bytecode. The
 * {@code seven*} calls put an allocation at exactly that depth (six arguments, then the count); the initializers
 * below them are deep enough for the spilling mode, which already worked, and stay as its coverage.
 */
public class ArrayAllocDepthProbe
{
    static final class Box
    {
        final String v;

        Box(String v)
        {
            this.v = v;
        }

        Box(String a, String b)
        {
            this.v = a + b;
        }
    }

    static int seven(int a, int b, int c, int d, int e, int f, int[] g)
    {
        return a + b + c + d + e + f + g.length;
    }

    static int sevenRef(int a, int b, int c, int d, int e, int f, String[] g)
    {
        return a + b + c + d + e + f + g.length;
    }

    /** A {@code newarray} with six arguments below its count: net depth 7, 8 during the lowering. */
    static int newAtSeven(int n)
    {
        return seven(1, 2, 3, 4, 5, 6, new int[n]);
    }

    /** The same for {@code anewarray}. */
    static int anewAtSeven(int n)
    {
        return sevenRef(1, 2, 3, 4, 5, 6, new String[n]);
    }

    /** max_stack 9: a nested initializer of objects. */
    static Box[][] nested()
    {
        return new Box[][] { { new Box("c") } };
    }

    /** max_stack 6: a flat initializer of objects. */
    static Box[] flat()
    {
        return new Box[] { new Box("a"), new Box("b") };
    }

    /** max_stack 13: three levels, a two-argument constructor at the bottom. */
    static Box[][][] triple()
    {
        return new Box[][][] { { { new Box("x", "y") } } };
    }

    /** max_stack 9: a nested initializer of strings beside objects. */
    static Object[][] mixed()
    {
        return new Object[][] { { "s", new Box("m") }, { 7 } };
    }

    /** Arguments evaluated deep: a call whose arguments each build an array. */
    static String join(Box[] a, Box[] b, Box[] c)
    {
        return a[0].v + b[0].v + c[0].v;
    }

    /** {@link #nested}'s body again, reached only through reflection (compiled on demand by getMethod). */
    public static Object nestedReflective()
    {
        return new Box[][] { { new Box("d") } };
    }

    /** A string concatenation around Method.invoke's varargs array: the shape LateArrayCastProbe's main had. */
    static String concatInvoke(java.lang.reflect.Method m, Object arg) throws Exception
    {
        return "invoked = " + m.invoke(null, arg);
    }

    public static String echo(Object o)
    {
        return String.valueOf(o);
    }

    public static void main(String[] args) throws Exception
    {
        System.out.println("newarray at depth 7 = " + newAtSeven(4));
        System.out.println("anewarray at depth 7 = " + anewAtSeven(5));
        Box[][] n = nested();
        System.out.println("nested = " + n.length + " " + n[0].length + " " + n[0][0].v);
        Box[] f = flat();
        System.out.println("flat = " + f.length + " " + f[0].v + f[1].v);
        Box[][][] t = triple();
        System.out.println("triple = " + t[0][0][0].v);
        Object[][] m = mixed();
        System.out.println("mixed = " + m[0][0] + " " + ((Box) m[0][1]).v + " " + m[1][0]);
        System.out.println("args = " + join(new Box[] { new Box("p") }, new Box[] { new Box("q") },
                new Box[] { new Box("r") }));
        Object r = ArrayAllocDepthProbe.class.getMethod("nestedReflective").invoke(null);
        System.out.println("nested reflective = " + ((Box[][]) r)[0][0].v);
        System.out.println(concatInvoke(ArrayAllocDepthProbe.class.getMethod("echo", Object.class), "e"));
        java.lang.reflect.Method echo = ArrayAllocDepthProbe.class.getMethod("echo", Object.class);
        System.out.println("inline invoke = " + echo.invoke(null, "f"));
        System.out.println("ArrayAllocDepthProbe done");
    }
}
