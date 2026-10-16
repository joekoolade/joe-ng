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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The parts of reflective member access that a top-level target never reaches, none of them using
 * {@code setAccessible}:
 * <ul>
 *   <li>NESTMATES: an outer class reads a nested class's private field, calls its private method and
 *       constructor, and a nested class reads the outer class's private static -- all allowed since nestmates
 *       (JEP 181) -- plus {@code getNestHost}/{@code isNestmateOf}/{@code getNestMembers} for a member, a local
 *       and an anonymous class;</li>
 *   <li>CLASS ACCESS: a PUBLIC method of a non-public class in another package -- the {@code ArrayList} iterator's
 *       {@code hasNext} -- refuses, though the member itself is public;</li>
 *   <li>PROTECTED INSTANCE TARGET: a subclass of {@code ArrayList} may reach the protected {@code modCount} and
 *       {@code removeRange} on ITSELF, but not on another {@code ArrayList} that is not its subclass.</li>
 * </ul>
 * Each refusal prints the exception's class only. Prints values only; the host diff is the gate.
 */
public class AccessRulesProbe
{
    private static int outerSecret = 42;

    interface Arm
    {
        Object run() throws Throwable;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            Object r = a.run();
            System.out.println(what + " -> " + (r == null ? "null" : r.getClass().getSimpleName() + " " + r));
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> " + t.getClass().getName());
        }
    }

    static class Nested
    {
        private int secret = 7;

        private Nested()
        {
        }

        private Nested(int s)
        {
            secret = s;
        }

        private int twice()
        {
            return secret * 2;
        }

        static Object readOuter() throws Exception
        {
            return AccessRulesProbe.class.getDeclaredField("outerSecret").getInt(null);
        }

        class Deeper
        {
            private int deep = 3;
        }
    }

    static class MyList extends ArrayList<String>
    {
        MyList()
        {
            super(Arrays.asList("a", "b", "c"));
        }

        void probe() throws Exception
        {
            Field mod = java.util.AbstractList.class.getDeclaredField("modCount");
            Method rr = ArrayList.class.getDeclaredMethod("removeRange", int.class, int.class);
            ArrayList<String> other = new ArrayList<>(Arrays.asList("x", "y", "z"));
            arm("modCount on self", () -> mod.getInt(this) >= 0);
            arm("modCount on other ArrayList", () -> mod.getInt(other));
            arm("removeRange on self", () ->
            {
                rr.invoke(this, 0, 1);
                return this.toString();
            });
            arm("removeRange on other ArrayList", () ->
            {
                rr.invoke(other, 0, 1);
                return other.toString();
            });
            arm("canAccess modCount self", () -> mod.canAccess(this));
            arm("canAccess modCount other", () -> mod.canAccess(other));
        }
    }

    public static void main(String[] args) throws Exception
    {
        System.out.println("-- nestmates --");
        Nested n = new Nested();
        Field secret = Nested.class.getDeclaredField("secret");
        Method twice = Nested.class.getDeclaredMethod("twice");
        Constructor<Nested> ctor = Nested.class.getDeclaredConstructor(int.class);
        arm("outer reads nested private field", () -> secret.getInt(n));
        arm("outer writes nested private field", () ->
        {
            secret.setInt(n, 9);
            return n.secret;
        });
        arm("outer calls nested private method", () -> twice.invoke(n));
        arm("outer calls nested private ctor", () -> ctor.newInstance(5).secret);
        arm("canAccess nested private", () -> secret.canAccess(n));
        arm("nested reads outer private static", () -> Nested.readOuter());
        Nested.Deeper d = n.new Deeper();
        arm("outer reads doubly nested private", () -> Nested.Deeper.class.getDeclaredField("deep").getInt(d));
        Runnable anon = new Runnable()
        {
            public void run()
            {
            }
        };
        class Local
        {
        }
        arm("nest host of Nested", () -> Nested.class.getNestHost().getName());
        arm("nest host of Deeper", () -> Nested.Deeper.class.getNestHost().getName());
        arm("nest host of anonymous", () -> anon.getClass().getNestHost().getName());
        arm("nest host of local", () -> Local.class.getNestHost().getName());
        arm("nest host of top-level", () -> AccessRulesProbe.class.getNestHost().getName());
        arm("nest host of String", () -> String.class.getNestHost().getName());
        arm("nest host of int", () -> int.class.getNestHost().getName());
        arm("nest host of int[]", () -> int[].class.getNestHost().getName());
        arm("Nested nestmate of Deeper", () -> Nested.class.isNestmateOf(Nested.Deeper.class));
        arm("Nested nestmate of MyList", () -> Nested.class.isNestmateOf(MyList.class));
        arm("Nested nestmate of String", () -> Nested.class.isNestmateOf(String.class));
        arm("nest members count", () -> AccessRulesProbe.class.getNestMembers().length);
        arm("nest members[0]", () -> AccessRulesProbe.class.getNestMembers()[0].getName());
        arm("nest members contains Deeper", () -> Arrays.asList(Nested.class.getNestMembers())
                .contains(Nested.Deeper.class));
        arm("String nest members", () -> String.class.getNestMembers().length);

        System.out.println("-- class access: a public method of a non-public class --");
        List<String> list = new ArrayList<>(Arrays.asList("p", "q"));
        Iterator<String> it = list.iterator();
        Method hasNext = it.getClass().getMethod("hasNext");
        arm("iterator class public", () -> java.lang.reflect.Modifier.isPublic(it.getClass().getModifiers()));
        arm("invoke hasNext", () -> hasNext.invoke(it));
        arm("canAccess hasNext", () -> hasNext.canAccess(it));
        arm("Iterator.hasNext via interface", () -> Iterator.class.getMethod("hasNext").invoke(it));

        System.out.println("-- protected instance target --");
        new MyList().probe();
        System.out.println("AccessRulesProbe done");
    }
}
