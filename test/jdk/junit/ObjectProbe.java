/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * java.lang.Object's own contract: identity hash and toString, clone's Cloneable check (CloneNotSupportedException
 * naming the class) and its shallow copy, finalize reachable through super, wait(long, int)'s argument checks, and
 * the members Object declares as reflection sees them. Values only; the host diff is the gate.
 */
public class ObjectProbe
{
    interface Arm
    {
        Object call() throws Throwable;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            System.out.println(what + " -> " + a.call());
        }
        catch (Throwable e)
        {
            System.out.println(what + " -> " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static class Plain
    {
        int v = 7;

        Object copy() throws CloneNotSupportedException
        {
            return clone();
        }
    }

    static class Copyable implements Cloneable
    {
        int v = 7;
        int[] shared = { 1, 2 };

        Copyable copy() throws CloneNotSupportedException
        {
            return (Copyable) clone();
        }
    }

    static class CopyableChild extends Plain implements Cloneable
    {
        long w = 9L;
    }

    static class Finalizing
    {
        int ran;

        @Override
        @SuppressWarnings("removal")
        protected void finalize() throws Throwable
        {
            ran += 1;
            super.finalize();
            ran += 1;
        }
    }

    public static void main(String[] args)
    {
        Object o = new Object();
        arm("toString is name@hex(hashCode)", () ->
                o.toString().equals("java.lang.Object@" + Integer.toHexString(o.hashCode())));
        arm("hashCode == identityHashCode", () -> o.hashCode() == System.identityHashCode(o));
        arm("hashCode stable", () -> o.hashCode() == o.hashCode());
        arm("equals self, other", () -> o.equals(o) + "," + o.equals(new Object()));
        arm("getClass", () -> o.getClass().getName());
        arm("clone of a class without Cloneable", () -> new Plain().copy());
        arm("clone of a Cloneable class", () ->
        {
            Copyable a = new Copyable();
            a.v = 42;
            Copyable b = a.copy();
            return (b != a) + " " + b.getClass().getSimpleName() + " v=" + b.v + " shallow=" + (b.shared == a.shared);
        });
        arm("clone copies a superclass's fields", () ->
        {
            CopyableChild c = new CopyableChild();
            c.v = 5;
            c.w = 6L;
            CopyableChild d = (CopyableChild) c.copy();
            return (d != c) + " v=" + d.v + " w=" + d.w;
        });
        arm("array clone", () ->
        {
            int[] a = { 3, 4, 5 };
            String[] s = { "x", "y" };
            return Arrays.toString(a.clone()) + " " + Arrays.toString(s.clone()) + " " + (a.clone() != a);
        });
        arm("super.finalize from an override", () ->
        {
            Finalizing f = new Finalizing();
            f.finalize();
            return "ran=" + f.ran;
        });
        arm("wait(1, 500000) holding the monitor returns", () ->
        {
            synchronized (o)
            {
                o.wait(1L, 500000);
            }
            return "returned";
        });
        arm("wait(1, -1)", () ->
        {
            synchronized (o)
            {
                o.wait(1L, -1);
            }
            return "returned";
        });
        arm("wait(1, 1000000)", () ->
        {
            synchronized (o)
            {
                o.wait(1L, 1000000);
            }
            return "returned";
        });
        arm("wait(-1, 0)", () ->
        {
            synchronized (o)
            {
                o.wait(-1L, 0);
            }
            return "returned";
        });
        arm("Object's declared methods", () ->
        {
            Method[] ms = Object.class.getDeclaredMethods();
            String[] names = new String[ms.length];
            int i = 0;
            while (i < ms.length)
            {
                Method m = ms[i];
                names[i] = Modifier.toString(m.getModifiers()) + " " + m.getName() + m.getParameterCount();
                i += 1;
            }
            Arrays.sort(names);
            return Arrays.toString(names);
        });
        arm("finalize is protected and deprecated", () ->
        {
            Method m = Object.class.getDeclaredMethod("finalize");
            return Modifier.isProtected(m.getModifiers()) + " " + m.isAnnotationPresent(Deprecated.class);
        });
        System.out.println("ObjectProbe done");
    }
}
