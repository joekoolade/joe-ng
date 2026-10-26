/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * INVOKING stock method handles on metal -- increment 2 of the java.lang.invoke arc, where the JIT lowers
 * {@code invokeExact}/{@code invoke}/{@code invokeBasic}/{@code linkTo*} to a trampoline whose resolver walks
 * {@code mh.form.vmentry} and the trailing MemberName. Every handle here has a shape the seed's
 * DirectMethodHandle$Holder PREGENERATES, so no LambdaForm class is spun (that is a later increment).
 *
 * <p>Each arm aims at one way the lowering could be wrong while looking right: the four dispatch kinds
 * (invokeBasic into a static form, linkToSpecial for a final class and a constructor, linkToVirtual with two
 * receiver classes through ONE handle, linkToInterface), an exception thrown by the target unwinding through
 * the trampoline, WrongMethodTypeException from the exact check, a null handle, a hot loop, and double/float
 * arguments -- the register save the trampoline does is x0..x15, so a floating-point argument that travelled
 * in a d register would arrive as garbage.
 */
public final class MhInvokeProbe
{
    int f = 7;
    static int sf = 9;
    static int counter;

    static int add(int a, int b)
    {
        return a + b;
    }

    static Object id(Object o)
    {
        return o;
    }

    static void bump()
    {
        counter += 1;
    }

    static void boom()
    {
        throw new IllegalStateException("boom");
    }

    static final class Point
    {
        final int x;
        final int y;

        Point(int x, int y)
        {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString()
        {
            return "(" + x + "," + y + ")";
        }
    }

    static class Named
    {
        @Override
        public String toString()
        {
            return "named";
        }
    }

    public static void main(String[] args) throws Throwable
    {
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> me = MhInvokeProbe.class;
        MethodHandle add = l.findStatic(me, "add", MethodType.methodType(int.class, int.class, int.class));
        MethodHandle id = l.findStatic(me, "id", MethodType.methodType(Object.class, Object.class));
        MethodHandle bump = l.findStatic(me, "bump", MethodType.methodType(void.class));
        MethodHandle boom = l.findStatic(me, "boom", MethodType.methodType(void.class));
        MethodHandle trim = l.findVirtual(String.class, "trim", MethodType.methodType(String.class));
        MethodHandle toStr = l.findVirtual(Object.class, "toString", MethodType.methodType(String.class));
        MethodHandle cmp = l.findVirtual(Comparable.class, "compareTo", MethodType.methodType(int.class, Object.class));
        MethodHandle ctor = l.findConstructor(Point.class, MethodType.methodType(void.class, int.class, int.class));
        MethodHandle get = l.findGetter(me, "f", int.class);
        MethodHandle set = l.findSetter(me, "f", int.class);
        MethodHandle sget = l.findStaticGetter(me, "sf", int.class);
        MethodHandle dcmp = l.findStatic(Double.class, "compare", MethodType.methodType(int.class, double.class, double.class));
        MethodHandle fcmp = l.findStatic(Float.class, "compare", MethodType.methodType(int.class, float.class, float.class));

        arm("static (int,int)int", () -> String.valueOf((int) add.invokeExact(40, 2)));
        arm("static identity", () -> String.valueOf((Object) id.invokeExact((Object) "same") == "same"));
        arm("static void x3", () ->
        {
            bump.invokeExact();
            bump.invokeExact();
            bump.invokeExact();
            return String.valueOf(counter);
        });
        arm("target throws", () ->
        {
            try
            {
                boom.invokeExact();
                return "no throw";
            }
            catch (IllegalStateException e)
            {
                return "caught " + e.getMessage();
            }
        });
        arm("final-class virtual", () -> "[" + (String) trim.invokeExact("  t  ") + "]");
        arm("virtual override", () -> (String) toStr.invokeExact((Object) new Named()));
        arm("virtual other receiver", () -> (String) toStr.invokeExact((Object) Integer.valueOf(5)));
        arm("interface Integer", () -> String.valueOf((int) cmp.invokeExact((Comparable) Integer.valueOf(3), (Object) Integer.valueOf(5))));
        arm("interface String", () -> String.valueOf((int) cmp.invokeExact((Comparable) "b", (Object) "a")));
        arm("constructor", () -> String.valueOf((Point) ctor.invokeExact(1, 2)));
        arm("getter/setter", () ->
        {
            MhInvokeProbe p = new MhInvokeProbe();
            int before = (int) get.invokeExact(p);
            set.invokeExact(p, 11);
            return before + " " + p.f + " " + (int) get.invokeExact(p);
        });
        arm("static getter", () -> String.valueOf((int) sget.invokeExact()));
        arm("double args", () -> String.valueOf((int) dcmp.invokeExact(1.5, 2.5)) + " " + (int) dcmp.invokeExact(2.5, 1.5));
        arm("float args", () -> String.valueOf((int) fcmp.invokeExact(-0.5f, -0.25f)) + " " + (int) fcmp.invokeExact(3.0f, 3.0f));
        arm("wrong type", () ->
        {
            try
            {
                long r = (long) add.invokeExact(1, 2);
                return "no throw " + r;
            }
            catch (java.lang.invoke.WrongMethodTypeException e)
            {
                return "WrongMethodTypeException: " + e.getMessage();
            }
        });
        arm("invoke same type", () -> String.valueOf((int) add.invoke(20, 22)));
        arm("null handle", () ->
        {
            MethodHandle none = null;
            try
            {
                int r = (int) none.invokeExact(1, 2);
                return "no throw " + r;
            }
            catch (NullPointerException e)
            {
                return "NullPointerException";
            }
        });
        arm("hot loop", () ->
        {
            int s = 0;
            int i = 0;
            while (i < 1000)
            {
                s = (int) add.invokeExact(s, i);
                i += 1;
            }
            return String.valueOf(s);
        });
        System.out.println("MhInvokeProbe done");
    }

    private interface Arm
    {
        String run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println(what + " = " + r);
    }
}
