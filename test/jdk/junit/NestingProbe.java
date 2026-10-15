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
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Class NESTING read from the classfile, never from the binary name: {@code getSimpleName}, {@code
 * getCanonicalName}, {@code isMemberClass}/{@code isLocalClass}/{@code isAnonymousClass}, {@code
 * getEnclosingClass}, {@code getDeclaringClass}, {@code getEnclosingMethod} and {@code getEnclosingConstructor}
 * over every kind of class: top-level, static member, inner, local and anonymous in a method, in a constructor,
 * in a static and an instance initializer, inside a LAMBDA body (javac names the SOURCE method, not
 * the synthetic {@code lambda$...}), a local class in an overload, and -- the shape a name-based answer gets wrong -- a
 * TOP-LEVEL class whose own name contains '$'. A generic local class's type variable is bounded by its
 * enclosing METHOD's, which resolves only if the enclosing method is found. Arrays, a primitive and baked
 * {@code Map.Entry}/{@code String} close it. Prints values only; the host diff is the gate.
 */
public class NestingProbe
{
    static class Member
    {
    }

    class Inner
    {
    }

    static Class<?> fromStatic;
    Class<?> fromInstanceInit;
    Class<?> fromCtor;
    Class<?> fromCtorAnon;

    static
    {
        class InStatic
        {
        }
        fromStatic = InStatic.class;
    }

    {
        Object o = new Object()
        {
        };
        fromInstanceInit = o.getClass();
    }

    NestingProbe()
    {
        class InCtor
        {
        }
        fromCtor = InCtor.class;
        fromCtorAnon = new Object()
        {
        }.getClass();
    }

    NestingProbe(int unused)
    {
        class InCtorInt
        {
        }
        fromCtor = InCtorInt.class;
    }

    static Class<?> overload(int x)
    {
        class InOverloadInt
        {
        }
        return InOverloadInt.class;
    }

    static Class<?> overload(String[] x)
    {
        class InOverloadArr
        {
        }
        return InOverloadArr.class;
    }

    static <T extends Number> Class<?> generic()
    {
        class Holder<U extends T>
        {
        }
        return Holder.class;
    }

    public static void main(String[] args)
    {
        class Local
        {
        }
        Object anon = new Runnable()
        {
            public void run()
            {
            }
        };
        Supplier<Class<?>> inLambda = () ->
        {
            class InLambda
            {
            }
            return InLambda.class;
        };
        NestingProbe p = new NestingProbe();
        NestingProbe q = new NestingProbe(1);

        show("top", NestingProbe.class);
        show("dollar top", Odd$Name.class);
        show("member", Member.class);
        show("inner", Inner.class);
        show("member of member", Member2.Deeper.class);
        show("local", Local.class);
        show("anon", anon.getClass());
        show("static init", fromStatic);
        show("instance init", p.fromInstanceInit);
        show("ctor()", p.fromCtor);
        show("ctor() anon", p.fromCtorAnon);
        show("ctor(int)", q.fromCtor);
        show("overload(int)", overload(1));
        show("overload(String[])", overload(null));
        show("in lambda", inLambda.get());
        show("generic local", generic());
        show("local[]", Local[].class);
        show("member[][]", Member[][].class);
        show("anon[]", java.lang.reflect.Array.newInstance(anon.getClass(), 0).getClass());
        show("int", int.class);
        show("int[]", int[].class);
        show("Map.Entry", Map.Entry.class);
        show("String", String.class);

        TypeVariable<?> u = generic().getTypeParameters()[0];
        System.out.println("generic local U bounds = " + Arrays.toString(u.getBounds()));
        TypeVariable<?> t = (TypeVariable<?>) u.getBounds()[0];
        System.out.println("bound T declared by = " + t.getGenericDeclaration());
        System.out.println("generic local toGenericString = " + generic().toGenericString());
        System.out.println("NestingProbe done");
    }

    static class Member2
    {
        static class Deeper
        {
        }
    }

    static void show(String what, Class<?> c)
    {
        System.out.println(what + ":");
        System.out.println("  name=" + c.getName() + " simple=" + c.getSimpleName() + " canonical="
                + c.getCanonicalName() + " type=" + c.getTypeName());
        System.out.println("  member=" + c.isMemberClass() + " local=" + c.isLocalClass() + " anon="
                + c.isAnonymousClass());
        System.out.println("  enclosingClass=" + c.getEnclosingClass() + " declaringClass="
                + c.getDeclaringClass());
        System.out.println("  enclosingMethod=" + c.getEnclosingMethod());
        System.out.println("  enclosingCtor=" + c.getEnclosingConstructor());
    }
}

/** A TOP-LEVEL class whose name contains '$': not nested in anything, whatever its name suggests. */
class Odd$Name
{
}
