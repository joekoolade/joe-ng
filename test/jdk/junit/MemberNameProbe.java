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
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * MemberName resolution on metal -- increment 1 of the java.lang.invoke arc, where stock MethodHandles runs over a
 * MethodHandleNatives whose natives are Java written against HotSpot's methodHandles.cpp. Nothing here INVOKES a
 * method handle (that is the JIT's increment); every arm builds one and cracks it with revealDirect, which reads
 * back exactly what resolve filled in: the reference kind, declaring class, name, type and modifiers.
 *
 * <p>The arms are chosen where a plausible-looking resolver would answer wrongly: a final class's method (HotSpot
 * devirtualizes it to invokeSpecial, which revealDirect must turn back into invokeVirtual), an inherited method
 * (the DECLARING class comes back, not the one asked about), an Object method reached through an interface, a
 * default method, the error kinds (no such member, wrong type, static/instance mismatch, a private member of
 * another class), and VarHandles built by stock code, including a static int -- the 8-byte static slot is why
 * the deleted overlay refused that one, so the arm reads it back through a widening to long as well.
 */
public final class MemberNameProbe
{
    static int si = -1;
    static long sl = -1L;
    static Object so;
    int fi = 7;
    final String ff = "f";

    static int twice(int x)
    {
        return 2 * x;
    }

    // The handle shapes below are the ones the seed's DirectMethodHandle$Holder PREGENERATES (an interface
    // (Object,Object)int, a virtual (Object,Object)void), so building them spins no class. A shape outside that
    // set needs a spun LambdaForm class, which is a later increment of the arc.
    interface Shape
    {
        int area(Object unit);

        default int scaled(Object unit)
        {
            return 2;
        }
    }

    static class Base
    {
        void v(Object o)
        {
        }
    }

    static class Derived extends Base
    {
    }

    static final class Other
    {
        private static Object secret()
        {
            return "s";
        }
    }

    public static void main(String[] args)
    {
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> me = MemberNameProbe.class;
        arm("findStatic", () -> info(l, l.findStatic(me, "twice", MethodType.methodType(int.class, int.class))));
        arm("findVirtual final class", () -> info(l, l.findVirtual(String.class, "length", MethodType.methodType(int.class))));
        arm("findVirtual non-final", () -> info(l, l.findVirtual(Object.class, "toString", MethodType.methodType(String.class))));
        arm("findVirtual interface", () -> info(l, l.findVirtual(Shape.class, "area", MethodType.methodType(int.class, Object.class))));
        arm("findVirtual default", () -> info(l, l.findVirtual(Shape.class, "scaled", MethodType.methodType(int.class, Object.class))));
        arm("findVirtual inherited", () -> info(l, l.findVirtual(Derived.class, "v", MethodType.methodType(void.class, Object.class))));
        arm("Object method via interface", () -> info(l, l.findVirtual(Shape.class, "toString", MethodType.methodType(String.class))));
        arm("findConstructor", () -> info(l, l.findConstructor(ArrayList.class, MethodType.methodType(void.class))));
        arm("findSpecial", () -> info(l, l.findSpecial(Object.class, "toString", MethodType.methodType(String.class), me)));
        arm("findGetter", () -> info(l, l.findGetter(me, "fi", int.class)));
        arm("findSetter", () -> info(l, l.findSetter(me, "fi", int.class)));
        arm("findStaticGetter", () -> info(l, l.findStaticGetter(me, "si", int.class)));
        arm("unreflect", () -> info(l, l.unreflect(me.getDeclaredMethod("twice", int.class))));
        arm("unreflectConstructor", () -> info(l, l.unreflectConstructor(ArrayList.class.getConstructor())));
        arm("unreflectGetter", () -> info(l, l.unreflectGetter(me.getDeclaredField("fi"))));
        arm("handle type", () -> l.findStatic(me, "twice", MethodType.methodType(int.class, int.class)).type().toString());
        arm("handle toString", () -> l.findVirtual(String.class, "length", MethodType.methodType(int.class)).toString());
        arm("no such method", () -> info(l, l.findStatic(me, "nope", MethodType.methodType(int.class, int.class))));
        arm("wrong type", () -> info(l, l.findStatic(me, "twice", MethodType.methodType(long.class, int.class))));
        arm("static as virtual", () -> info(l, l.findVirtual(me, "twice", MethodType.methodType(int.class, int.class))));
        arm("no such field", () -> info(l, l.findGetter(me, "nope", int.class)));
        arm("static as instance field", () -> info(l, l.findGetter(me, "si", int.class)));
        arm("private of a nestmate", () ->
        {
            // Called first, so Other is initialized: DirectMethodHandle then needs no initialization barrier,
            // and only the barrier-free ()Object form is pregenerated, so the arm builds without spinning.
            Other.secret();
            return info(l, l.findStatic(Other.class, "secret", MethodType.methodType(Object.class)));
        });
        arm("package-private of java.base", () -> info(l, l.findStatic(String.class, "checkIndex",
                MethodType.methodType(void.class, int.class, int.class))));
        arm("VH static int", () ->
        {
            VarHandle vh = l.findStaticVarHandle(me, "si", int.class);
            vh.set(5);
            long wide = si;
            return si + " " + wide + " " + (int) vh.get() + " " + (si == 5);
        });
        arm("VH static long", () ->
        {
            VarHandle vh = l.findStaticVarHandle(me, "sl", long.class);
            vh.set(1L << 40);
            return sl + " " + (long) vh.getAndAdd(1L) + " " + sl;
        });
        arm("VH static ref", () ->
        {
            VarHandle vh = l.findStaticVarHandle(me, "so", Object.class);
            vh.set("x");
            return so + " " + vh.compareAndSet("x", "y") + " " + so;
        });
        arm("VH final field modes", () ->
        {
            VarHandle vh = l.findVarHandle(me, "ff", String.class);
            return vh.isAccessModeSupported(VarHandle.AccessMode.GET) + " "
                    + vh.isAccessModeSupported(VarHandle.AccessMode.SET) + " " + vh.varType().getSimpleName();
        });
        arm("VH toString", () -> l.findVarHandle(me, "fi", int.class).toString());
        // Stock VerifyAccess.isSamePackage compares package names with ==, so getPackageName must intern. The
        // probe's own classes are in the unnamed package, whose "" literal is identical by accident -- which is
        // how a fresh-substring getPackageName passed every arm above and still failed every java.base class
        // that builds a VarHandle on a nested class of its own package (the two arms after this one).
        arm("package name identity", () -> (String.class.getPackageName() == Integer.class.getPackageName())
                + " " + int[].class.getPackageName() + " " + String[][].class.getPackageName()
                + " " + int.class.getPackageName());
        arm("ConcurrentSkipListMap init", () ->
        {
            java.util.concurrent.ConcurrentSkipListMap<Integer, String> m = new java.util.concurrent.ConcurrentSkipListMap<>();
            m.put(2, "b");
            m.put(1, "a");
            return m.toString();
        });
        arm("CompletableFuture init", () -> java.util.concurrent.CompletableFuture.completedFuture("cf").get());
        System.out.println("MemberNameProbe done");
    }

    private static String info(MethodHandles.Lookup l, MethodHandle mh)
    {
        MethodHandleInfo i = l.revealDirect(mh);
        return i + " mods=[" + Modifier.toString(i.getModifiers()) + "] decl=" + i.getDeclaringClass().getSimpleName();
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
            String m = String.valueOf(t.getMessage());
            int from = m.indexOf(", from ");                   // the rest names a module by its identity hash
            r = "THREW " + t.getClass().getName() + ": " + (from < 0 ? m : m.substring(0, from));
        }
        System.out.println(what + " = " + r);
    }
}
