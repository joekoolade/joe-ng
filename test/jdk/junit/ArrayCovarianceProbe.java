/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.RandomAccess;

/**
 * Array covariance (JLS 4.10.3) through {@code instanceof}, {@code checkcast}, {@code Class.isAssignableFrom}
 * and {@code Class.isInstance}, for every shape of element relation: class to superclass, class to an interface
 * it implements directly, class to a SUPER-interface of one it implements, interface to a super-interface,
 * anything to {@code Object}, a two-dimensional array, every array to {@code Cloneable}/{@code Serializable}, and
 * the refusals -- primitive arrays are invariant and an unrelated element is not assignable. The target and
 * source are held in {@code Object} so javac cannot fold any answer. Prints values only; the host diff is the
 * gate.
 */
public class ArrayCovarianceProbe
{
    interface Base
    {
    }

    interface Sub extends Base
    {
    }

    static class Impl implements Sub
    {
    }

    static void row(String what, Object arr, Class<?> target)
    {
        String cast;
        try
        {
            target.cast(arr);
            cast = "ok";
        }
        catch (ClassCastException e)
        {
            cast = "CCE";
        }
        System.out.println(what + " isInstance=" + target.isInstance(arr) + " isAssignableFrom="
                + target.isAssignableFrom(arr.getClass()) + " cast=" + cast);
    }

    public static void main(String[] args)
    {
        Object strings = new String[] { "a" };
        Object subs = new Sub[] { new Impl() };
        Object impls = new Impl[] { new Impl() };
        Object lists = new ArrayList<?>[] { new ArrayList<String>() };
        Object ints2 = new int[2][2];
        Object strings2 = new String[1][1];

        System.out.println("-- instanceof (compiled) --");
        System.out.println("String[] instanceof CharSequence[] " + (strings instanceof CharSequence[]));
        System.out.println("String[] instanceof Comparable[] " + (strings instanceof Comparable[]));
        System.out.println("Sub[] instanceof Base[] " + (subs instanceof Base[]));
        System.out.println("Impl[] instanceof Base[] " + (impls instanceof Base[]));
        System.out.println("ArrayList[] instanceof Collection[] " + (lists instanceof Collection[]));
        System.out.println("ArrayList[] instanceof Iterable[] " + (lists instanceof Iterable[]));
        System.out.println("ArrayList[] instanceof RandomAccess[] " + (lists instanceof RandomAccess[]));
        System.out.println("int[][] instanceof Object[] " + (ints2 instanceof Object[]));
        System.out.println("int[][] instanceof Cloneable[] " + (ints2 instanceof Cloneable[]));
        System.out.println("String[][] instanceof CharSequence[][] " + (strings2 instanceof CharSequence[][]));
        System.out.println("String[][] instanceof Serializable[] " + (strings2 instanceof Serializable[]));
        System.out.println("Impl instanceof Base " + (new Impl() instanceof Base));

        System.out.println("-- checkcast (compiled) --");
        try
        {
            Base[] b = (Base[]) subs;
            Collection<?>[] c = (Collection<?>[]) lists;
            CharSequence[][] cs = (CharSequence[][]) strings2;
            System.out.println("casts ok " + b.length + c.length + cs.length);
        }
        catch (ClassCastException e)
        {
            System.out.println("cast CCE");
        }

        System.out.println("-- reflective --");
        row("Sub[] -> Base[]", subs, Base[].class);
        row("Impl[] -> Base[]", impls, Base[].class);
        row("Impl[] -> Sub[]", impls, Sub[].class);
        row("String[] -> Comparable[]", strings, Comparable[].class);
        row("String[] -> Object[]", strings, Object[].class);
        row("ArrayList[] -> List[]", lists, List[].class);
        row("int[][] -> Object[]", ints2, Object[].class);
        row("int[][] -> long[][]", ints2, long[][].class);
        row("String[] -> Integer[]", strings, Integer[].class);
        row("Sub[] -> Impl[]", subs, Impl[].class);
        row("String[] -> Cloneable", strings, Cloneable.class);
        row("String[] -> Serializable", strings, Serializable.class);
        System.out.println("ArrayCovarianceProbe done");
    }
}
