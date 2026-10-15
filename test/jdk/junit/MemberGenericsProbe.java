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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MEMBER-level generic reflection from each member's own {@code Signature} attribute: {@code Field.getGenericType},
 * {@code Method.getGenericReturnType}/{@code getGenericParameterTypes}/{@code getTypeParameters} and the
 * {@code Constructor} pair. Covered: a method type variable SHADOWING nothing and one bounded by the class's
 * (resolved through the method scope, then the class scope), a {@code ? super} wildcard, a generic array,
 * overloads that differ only in parameter type (each must answer from its OWN signature), a constructor with
 * its own type variable, non-generic members (the erasure is stock's answer), and the {@code equals}/
 * {@code hashCode} contract members need for a type variable to equal itself across lookups. Then baked
 * java.base methods. Members are looked up by name and types, never enumerated, so declaration order cannot
 * matter. Prints values only; the host diff is the gate.
 */
public class MemberGenericsProbe
{
    static class Holder<T>
    {
        List<String> names;
        T value;
        Map<String, ? super Integer> sink;
        T[] arr;
        int plain;

        <V> Holder(V v, List<? extends T> ts)
        {
        }

        Holder()
        {
        }

        <U extends Number> List<U> pick(Map<T, U> in, U[] us)
        {
            return null;
        }

        static <K extends Comparable<K>> K max(K a, K b)
        {
            return a;
        }

        <S extends T> S narrow(S s)
        {
            return s;
        }

        String plain(int x)
        {
            return null;
        }

        String plain(List<Integer> xs)
        {
            return null;
        }

        int[][] grid(String[] names, long[] ids, T[][] cells)
        {
            return null;
        }
    }

    public static void main(String[] args) throws Exception
    {
        Class<Holder> h = Holder.class;
        for (String f : new String[] { "names", "value", "sink", "arr", "plain" })
        {
            Field fd = h.getDeclaredField(f);
            System.out.println("field " + f + " " + describe(fd.getGenericType()));
        }

        show(h.getDeclaredMethod("pick", Map.class, Number[].class));
        show(h.getDeclaredMethod("max", Comparable.class, Comparable.class));
        show(h.getDeclaredMethod("narrow", Object.class));
        show(h.getDeclaredMethod("plain", int.class));
        show(h.getDeclaredMethod("plain", List.class));
        Constructor<Holder> c2 = h.getDeclaredConstructor(Object.class, List.class);
        System.out.println("ctor(V,List) typeparams=" + params(c2.getTypeParameters())
                + " generic=" + describeAll(c2.getGenericParameterTypes()));
        Constructor<Holder> c0 = h.getDeclaredConstructor();
        System.out.println("ctor() typeparams=" + params(c0.getTypeParameters())
                + " generic=" + describeAll(c0.getGenericParameterTypes()));

        Method a = h.getDeclaredMethod("pick", Map.class, Number[].class);
        Method b = h.getDeclaredMethod("pick", Map.class, Number[].class);
        Method other = h.getDeclaredMethod("plain", int.class);
        System.out.println("method equals=" + a.equals(b) + " identical=" + (a == b) + " hash=" + (a.hashCode() == b.hashCode())
                + " other=" + a.equals(other) + " overloads=" + other.equals(h.getDeclaredMethod("plain", List.class)));
        TypeVariable<Method> u1 = a.getTypeParameters()[0];
        TypeVariable<Method> u2 = b.getTypeParameters()[0];
        System.out.println("method typevar equal=" + u1.equals(u2) + " declaredBy=" + u1.getGenericDeclaration().equals(a)
                + " declName=" + u1.getGenericDeclaration().getName());
        System.out.println("field equals=" + h.getDeclaredField("value").equals(h.getDeclaredField("value"))
                + " other=" + h.getDeclaredField("value").equals(h.getDeclaredField("arr")));
        System.out.println("ctor equals=" + c2.equals(h.getDeclaredConstructor(Object.class, List.class))
                + " other=" + c2.equals(c0) + " hash=" + (c2.hashCode() == c0.hashCode()));
        TypeVariable<?> v = c2.getTypeParameters()[0];
        System.out.println("ctor typevar declaredBy=" + v.getGenericDeclaration().equals(c2));

        Method g = h.getDeclaredMethod("grid", String[].class, long[].class, Object[][].class);
        show(g);
        Class<?>[] gp = g.getParameterTypes();
        System.out.println("erased grid params=" + Arrays.toString(gp) + " returns=" + g.getReturnType()
                + " identity=" + (gp[0] == String[].class) + "," + (gp[1] == long[].class) + ","
                + (gp[2] == Object[][].class) + "," + (g.getReturnType() == int[][].class));
        show(HashMap.class.getDeclaredMethod("put", Object.class, Object.class));
        show(Collections.class.getDeclaredMethod("emptyList"));
        show(Arrays.class.getDeclaredMethod("asList", Object[].class));
        show(Collections.class.getDeclaredMethod("max", java.util.Collection.class));
        System.out.println("MemberGenericsProbe done");
    }

    static void show(Method m)
    {
        System.out.println("method " + m.getDeclaringClass().getSimpleName() + "." + m.getName()
                + " typeparams=" + params(m.getTypeParameters())
                + " params=" + describeAll(m.getGenericParameterTypes())
                + " returns=" + describe(m.getGenericReturnType()));
    }

    static String params(TypeVariable<?>[] vs)
    {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < vs.length; i++)
        {
            if (i > 0)
            {
                b.append(", ");
            }
            b.append(vs[i].getName()).append(" bounds=").append(describeAll(vs[i].getBounds()));
        }
        return b.append("]").toString();
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
            return "P(" + p.getTypeName() + " args=" + describeAll(p.getActualTypeArguments()) + ")";
        }
        if (t instanceof TypeVariable<?> v)
        {
            Object d = v.getGenericDeclaration();
            String by = d instanceof Class<?> c ? c.getSimpleName() : ((java.lang.reflect.Member) d).getName();
            return "V(" + v.getName() + " of " + by + ")";
        }
        if (t instanceof WildcardType w)
        {
            return "W(" + w.getTypeName() + ")";
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
