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
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * {@code Method}/{@code Constructor} {@code toString} and {@code toGenericString}, {@code getExceptionTypes} and
 * {@code getGenericExceptionTypes}. The shapes are the ones an erasure-only, throws-blind printer gets wrong: a
 * {@code throws} list of two, a THROWN TYPE VARIABLE (generic {@code X}, erased to its bound), varargs ({@code
 * T...} only in the generic form), a default interface method ({@code default} sits between the access word and
 * the rest), modifier ORDER ({@code static synchronized}, {@code protected final synchronized}), a
 * generic constructor that throws, and member arrays. Baked {@code Object.wait}, {@code String.getBytes},
 * {@code List.of}, {@code Collections.max} and {@code Comparator.reversed} close it. Members are looked up by
 * name and types, never enumerated. Prints values only; the host diff is the gate.
 *
 * <p>NO ABSTRACT MEMBER: an abstract (or native) method has no registry slot on this VM, so it has no
 * {@code Method} at all yet -- {@code getMethod} on one throws. That is its own increment.
 */
public class MemberToStringProbe
{
    interface Shape
    {
        default String describe(int depth) throws IOException
        {
            return "";
        }
    }

    static class Base implements Shape
    {
        protected final synchronized void draw(String[][] grid) throws IOException, InterruptedException
        {
        }
    }

    <T> MemberToStringProbe(T seed, List<? extends T> more) throws IOException
    {
    }

    private MemberToStringProbe() throws CloneNotSupportedException, IllegalStateException
    {
    }

    MemberToStringProbe(String... names)
    {
    }

    public static synchronized <X extends Exception> void rethrow(X x) throws X
    {
    }

    @SafeVarargs
    static final <T extends Comparable<? super T>> T maxOf(T... items)
    {
        return null;
    }

    public long[] plain(int a, Object[] b, List<String> c)
    {
        return null;
    }

    public static void main(String[] args) throws Exception
    {
        Class<?> c = MemberToStringProbe.class;
        show(Shape.class.getMethod("describe", int.class));
        show(Base.class.getDeclaredMethod("draw", String[][].class));
        show(c.getDeclaredMethod("rethrow", Exception.class));
        show(c.getDeclaredMethod("maxOf", Comparable[].class));
        show(c.getDeclaredMethod("plain", int.class, Object[].class, List.class));
        show(c.getDeclaredMethod("main", String[].class));
        show(c.getDeclaredConstructor(Object.class, List.class));
        show(c.getDeclaredConstructor());
        show(c.getDeclaredConstructor(String[].class));

        show(Object.class.getMethod("wait", long.class));
        show(String.class.getMethod("getBytes", String.class));
        show(List.class.getMethod("of", Object[].class));
        show(Collections.class.getMethod("max", java.util.Collection.class));
        show(Comparator.class.getMethod("reversed"));
        show(String.class.getConstructor(byte[].class, String.class));
        System.out.println("MemberToStringProbe done");
    }

    static void show(Method m)
    {
        System.out.println(m.getName() + ":");
        System.out.println("  toString        = " + m);
        System.out.println("  toGenericString = " + m.toGenericString());
        System.out.println("  exceptions      = " + Arrays.toString(m.getExceptionTypes()));
        System.out.println("  generic excs    = " + Arrays.toString(m.getGenericExceptionTypes()));
    }

    static void show(Constructor<?> k)
    {
        System.out.println("<init>" + Arrays.toString(k.getParameterTypes()) + ":");
        System.out.println("  toString        = " + k);
        System.out.println("  toGenericString = " + k.toGenericString());
        System.out.println("  exceptions      = " + Arrays.toString(k.getExceptionTypes()));
        System.out.println("  generic excs    = " + Arrays.toString(k.getGenericExceptionTypes()));
    }
}
