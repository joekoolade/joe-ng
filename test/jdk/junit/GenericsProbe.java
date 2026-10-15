/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Class-level GENERIC reflection, answered from the {@code Signature} attribute rather than the erasure:
 * {@code getGenericSuperclass}, {@code getGenericInterfaces} and {@code getTypeParameters} over a bounded type
 * variable that refers to ITSELF, a parameterized superclass, a wildcard with an upper bound, a generic array
 * element, a non-static INNER class whose superclass names its OUTER class's type variable (resolved through
 * the enclosing scope), a raw class (no signature: the erasure is stock's answer too) and an interface (null
 * superclass). Then the same questions of baked java.base classes, whose signatures come from the stock
 * classfiles, and {@code toGenericString}/{@code isSealed} over every kind of class: generic, sealed, final and
 * non-sealed permitted subclasses, enum, record with an intersection bound, annotation, arrays, a primitive. Each Type is printed by its stock string form plus its kind, so an erasure where a
 * ParameterizedType belongs shows in the text. Prints values only; the host diff is the gate.
 */
public class GenericsProbe
{
    static class Box<T extends Comparable<T>>
    {
    }

    static class IntBox extends Box<Integer> implements Comparable<IntBox>, Supplier<List<? extends Number>>
    {
        public int compareTo(IntBox o)
        {
            return 0;
        }

        public List<? extends Number> get()
        {
            return null;
        }
    }

    static class Grid<E> extends ArrayList<E[]>
    {
    }

    static class Outer<T>
    {
        class Inner extends ArrayList<T>
        {
        }
    }

    static class Raw
    {
    }

    interface Named extends Comparable<String>
    {
    }

    static class Pair<K, V extends List<K>>
    {
    }

    sealed interface Shape permits Circle, Square
    {
    }

    static final class Circle implements Shape
    {
    }

    static non-sealed class Square implements Shape
    {
    }

    enum Color
    {
        RED
    }

    record Point<N extends Number & Comparable<N>>(N x, N y)
    {
    }

    @interface Marker
    {
    }

    public static void main(String[] args)
    {
        Class<?>[] probes = { Box.class, IntBox.class, Grid.class, Outer.Inner.class, Raw.class, Named.class,
                Pair.class, Integer.class, String.class, HashMap.class, ArrayList.class };
        for (Class<?> c : probes)
        {
            System.out.println("== " + c.getName());
            System.out.println("  superclass  " + describe(c.getGenericSuperclass()));
            for (Type t : c.getGenericInterfaces())
            {
                System.out.println("  interface   " + describe(t));
            }
            for (TypeVariable<?> v : c.getTypeParameters())
            {
                System.out.println("  typeparam   " + v.getName() + " bounds=" + describeAll(v.getBounds())
                        + " declaredBy=" + ((GenericDeclaration) v.getGenericDeclaration() == c));
            }
        }

        TypeVariable<?> t1 = Box.class.getTypeParameters()[0];
        TypeVariable<?> t2 = Box.class.getTypeParameters()[0];
        System.out.println("typevar equal across calls=" + t1.equals(t2) + " hash equal=" + (t1.hashCode() == t2.hashCode()));
        Type self = ((ParameterizedType) t1.getBounds()[0]).getActualTypeArguments()[0];
        System.out.println("self-bound arg equals T=" + self.equals(t1));
        System.out.println("Box is GenericDeclaration=" + ((Object) Box.class instanceof GenericDeclaration));
        System.out.println("enclosing method of Inner=" + Outer.Inner.class.getEnclosingMethod()
                + " ctor=" + Outer.Inner.class.getEnclosingConstructor());
        System.out.println("enclosing method of Box=" + Box.class.getEnclosingMethod()
                + " ctor=" + Box.class.getEnclosingConstructor());
        Class<?>[] kinds = { Box.class, IntBox.class, Pair.class, Named.class, Shape.class, Circle.class,
                Square.class, Color.class, Point.class, Marker.class, HashMap.class, Comparable.class, String.class,
                Integer[].class, Pair[][].class, int.class, int[][].class };
        for (Class<?> c : kinds)
        {
            System.out.println("generic " + c.toGenericString() + " sealed=" + c.isSealed());
        }
        System.out.println("GenericsProbe done");
    }

    static String describeAll(Type[] ts)
    {
        String[] out = new String[ts.length];
        for (int i = 0; i < ts.length; i++)
        {
            out[i] = describe(ts[i]);
        }
        return Arrays.toString(out);
    }

    static String describe(Type t)
    {
        if (t == null)
        {
            return "null";
        }
        if (t instanceof ParameterizedType p)
        {
            return "P(" + p.getTypeName() + " raw=" + ((Class<?>) p.getRawType()).getName()
                    + " args=" + describeAll(p.getActualTypeArguments())
                    + " owner=" + (p.getOwnerType() == null ? "null" : p.getOwnerType().getTypeName()) + ")";
        }
        if (t instanceof TypeVariable<?> v)
        {
            return "V(" + v.getName() + ")";
        }
        if (t instanceof WildcardType w)
        {
            return "W(" + w.getTypeName() + " upper=" + describeAll(w.getUpperBounds())
                    + " lower=" + describeAll(w.getLowerBounds()) + ")";
        }
        if (t instanceof GenericArrayType g)
        {
            return "A(" + g.getTypeName() + " of " + describe(g.getGenericComponentType()) + ")";
        }
        if (t instanceof Class<?> c)
        {
            return "C(" + c.getName() + ")";
        }
        return "?(" + t.getClass().getName() + ")";
    }
}
