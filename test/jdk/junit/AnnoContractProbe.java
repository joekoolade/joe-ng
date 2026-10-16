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
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The {@code java.lang.annotation.Annotation} contract on annotation instances: {@code equals} compares the type
 * and every element (arrays by content, floats bitwise), {@code hashCode} is the specified sum of
 * {@code (127 * name.hashCode()) ^ valueHash} -- compared as the formula, since a Class or enum element hashes by
 * identity -- and {@code toString} is the source-like form. Two reads of the
 * same annotation are distinct objects that must be equal, equal annotations on different elements must be
 * equal, and different values must not be; a {@code HashSet} must collapse the equal ones. Every element kind
 * appears: primitives, a float NaN and a negative zero, String, Class, enum, nested annotation and arrays.
 * Prints values only; the host diff is the gate.
 */
public class AnnoContractProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Inner
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Full
    {
        int i() default 1;

        long l() default 2L;

        boolean z() default true;

        char c() default 'c';

        byte b() default 3;

        short s() default 4;

        float f() default 1.5f;

        double d() default -0.0;

        String str() default "s";

        Class<?> cls() default String.class;

        TimeUnit unit() default TimeUnit.SECONDS;

        Inner inner() default @Inner("in");

        int[] ints() default { 1, 2 };

        String[] strs() default { "a", "b" };

        Inner[] inners() default { @Inner("x") };
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker
    {
    }

    @Full
    @Marker
    static class A
    {
    }

    @Full
    static class B
    {
    }

    @Full(i = 9, f = Float.NaN, ints = { 1, 3 })
    static class C
    {
    }

    @Full(i = 9, f = Float.NaN, ints = { 1, 3 })
    static class D
    {
    }

    /** The specified hash: the sum over the elements of {@code (127 * name.hashCode()) ^ valueHash}. */
    static int formula(Annotation a)
    {
        int sum = 0;
        for (java.lang.reflect.Method m : a.annotationType().getDeclaredMethods())
        {
            try
            {
                Object v = m.invoke(a);
                int vh;
                if (v instanceof int[] x)
                {
                    vh = java.util.Arrays.hashCode(x);
                }
                else if (v instanceof Object[] x)
                {
                    vh = java.util.Arrays.hashCode(x);
                }
                else
                {
                    vh = v.hashCode();
                }
                sum += (127 * m.getName().hashCode()) ^ vh;
            }
            catch (ReflectiveOperationException e)
            {
                throw new RuntimeException(e);
            }
        }
        return sum;
    }

    public static void main(String[] args)
    {
        Full a1 = A.class.getAnnotation(Full.class);
        Full a2 = A.class.getAnnotation(Full.class);
        Full b = B.class.getAnnotation(Full.class);
        Full c = C.class.getAnnotation(Full.class);
        Full d = D.class.getAnnotation(Full.class);
        Marker m1 = A.class.getAnnotation(Marker.class);
        Marker m2 = A.class.getAnnotation(Marker.class);
        System.out.println("a1.equals(a2) " + a1.equals(a2));
        System.out.println("a1.equals(b) " + a1.equals(b));
        System.out.println("a1.equals(c) " + a1.equals(c));
        System.out.println("c.equals(d) (NaN element) " + c.equals(d));
        System.out.println("a1.equals(m1) " + a1.equals(m1));
        System.out.println("a1.equals(null) " + a1.equals(null));
        System.out.println("a1.equals(\"x\") " + a1.equals("x"));
        System.out.println("m1.equals(m2) " + m1.equals(m2));
        // Full has a Class and an enum element, whose hashCode is IDENTITY-based -- an arbitrary number on any
        // JVM -- so its absolute hash is not comparable across VMs. What the contract fixes is the FORMULA.
        System.out.println("hash a1==a2 " + (a1.hashCode() == a2.hashCode()) + " a1==b " + (a1.hashCode() == b
                .hashCode()) + " c==d " + (c.hashCode() == d.hashCode()) + " a1!=c " + (a1.hashCode() != c.hashCode()));
        System.out.println("hash matches the formula: a1 " + (a1.hashCode() == formula(a1)) + " c "
                + (c.hashCode() == formula(c)));
        System.out.println("hash marker " + m1.hashCode() + " inner " + a1.inner().hashCode());
        System.out.println("toString marker " + m1);
        System.out.println("toString inner " + a1.inner());
        Set<Annotation> set = new HashSet<>();
        set.add(a1);
        set.add(a2);
        set.add(b);
        set.add(c);
        set.add(d);
        set.add(m1);
        set.add(m2);
        System.out.println("set size " + set.size());
        System.out.println("AnnoContractProbe done");
    }
}
