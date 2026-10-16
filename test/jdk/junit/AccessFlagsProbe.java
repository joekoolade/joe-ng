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
import java.io.Serializable;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Comparator;

/**
 * {@code accessFlags()} on every kind of reflective object. A CLASS answers from the classfile's own
 * {@code access_flags} when it is top-level (so {@code SUPER} shows) and from {@code InnerClasses} when it is
 * nested, local, anonymous or an array -- the two disagree for a private or static nested class. Fields,
 * methods and constructors cover the flags javac writes for the compiler's own members: a BRIDGE method, a
 * VARARGS one, a SYNTHETIC outer-instance field, an enum's {@code $VALUES}. Parameters cover the {@code MANDATED}
 * and {@code SYNTHETIC} ones of inner and enum constructors. Also {@code AccessFlag} itself: its mask, source
 * flag and locations. Prints values only; the host diff is the gate.
 */
public class AccessFlagsProbe
{
    public static final int CONST = 1;
    private volatile transient long vt;
    protected static Object prot;

    interface Shape
    {
        double area();

        default String describe()
        {
            return "shape";
        }
    }

    private static class PrivNested
    {
    }

    protected static final class ProtNested implements Comparable<ProtNested>
    {
        public int compareTo(ProtNested o)
        {
            return 0;
        }
    }

    abstract static class Abs
    {
        abstract void run();

        synchronized void sync()
        {
        }

        static strictfp double fp()
        {
            return 0.0;
        }
    }

    class Inner
    {
        Inner(String s)
        {
        }

        Object outer()
        {
            return AccessFlagsProbe.this;
        }
    }

    enum Color
    {
        RED;

        Color()
        {
        }
    }

    @interface Anno
    {
    }

    record Point(int x, int y)
    {
    }

    static void varargs(String... a)
    {
    }

    static void show(String what, java.util.Set<AccessFlag> flags)
    {
        System.out.println(what + " -> " + flags);
    }

    public static void main(String[] args) throws Exception
    {
        System.out.println("-- classes --");
        show("top-level public", AccessFlagsProbe.class.accessFlags());
        show("String", String.class.accessFlags());
        show("Serializable", Serializable.class.accessFlags());
        show("Shape (nested interface)", Shape.class.accessFlags());
        show("PrivNested", PrivNested.class.accessFlags());
        show("ProtNested", ProtNested.class.accessFlags());
        show("Abs", Abs.class.accessFlags());
        show("Inner", Inner.class.accessFlags());
        show("Color", Color.class.accessFlags());
        show("Anno", Anno.class.accessFlags());
        show("Point", Point.class.accessFlags());
        Runnable anon = new Runnable()
        {
            public void run()
            {
            }
        };
        class Local
        {
        }
        show("anonymous", anon.getClass().accessFlags());
        show("local", Local.class.accessFlags());
        show("int[]", int[].class.accessFlags());
        show("String[]", String[].class.accessFlags());
        show("PrivNested[][]", PrivNested[][].class.accessFlags());
        show("int", int.class.accessFlags());
        System.out.println("modifiers: int=" + int.class.getModifiers() + " int[]=" + int[].class.getModifiers()
                + " PrivNested[]=" + PrivNested[].class.getModifiers() + " ProtNested[][]="
                + ProtNested[][].class.getModifiers() + " Abs[]=" + Abs[].class.getModifiers() + " void="
                + void.class.getModifiers());

        System.out.println("-- fields --");
        show("CONST", AccessFlagsProbe.class.getDeclaredField("CONST").accessFlags());
        show("vt", AccessFlagsProbe.class.getDeclaredField("vt").accessFlags());
        show("prot", AccessFlagsProbe.class.getDeclaredField("prot").accessFlags());
        show("Color.RED", Color.class.getDeclaredField("RED").accessFlags());
        show("Color.$VALUES", Color.class.getDeclaredField("$VALUES").accessFlags());
        show("Inner.this$0", Inner.class.getDeclaredField("this$0").accessFlags());
        show("Point.x", Point.class.getDeclaredField("x").accessFlags());

        System.out.println("-- methods and constructors --");
        show("main", AccessFlagsProbe.class.getDeclaredMethod("main", String[].class).accessFlags());
        show("varargs", AccessFlagsProbe.class.getDeclaredMethod("varargs", String[].class).accessFlags());
        show("Abs.run", Abs.class.getDeclaredMethod("run").accessFlags());
        show("Abs.sync", Abs.class.getDeclaredMethod("sync").accessFlags());
        show("Abs.fp", Abs.class.getDeclaredMethod("fp").accessFlags());
        show("Shape.area", Shape.class.getDeclaredMethod("area").accessFlags());
        show("Shape.describe", Shape.class.getDeclaredMethod("describe").accessFlags());
        show("System.arraycopy", System.class.getDeclaredMethod("arraycopy", Object.class, int.class,
                Object.class, int.class, int.class).accessFlags());
        Method bridge = null;
        for (Method m : ProtNested.class.getDeclaredMethods())
        {
            if (m.getName().equals("compareTo") && m.getParameterTypes()[0] == Object.class)
            {
                bridge = m;
            }
        }
        show("ProtNested.compareTo(Object) bridge", bridge.accessFlags());
        show("ProtNested.compareTo(ProtNested)",
                ProtNested.class.getDeclaredMethod("compareTo", ProtNested.class).accessFlags());
        show("Color.values", Color.class.getDeclaredMethod("values").accessFlags());
        show("Point.x()", Point.class.getDeclaredMethod("x").accessFlags());
        Constructor<?> innerCtor = Inner.class.getDeclaredConstructor(AccessFlagsProbe.class, String.class);
        show("Inner ctor", innerCtor.accessFlags());
        show("Color ctor", Color.class.getDeclaredConstructor(String.class, int.class).accessFlags());
        show("Point ctor", Point.class.getDeclaredConstructor(int.class, int.class).accessFlags());

        System.out.println("-- parameters --");
        for (Parameter p : innerCtor.getParameters())
        {
            show("Inner ctor " + p.getName(), p.accessFlags());
        }
        for (Parameter p : Color.class.getDeclaredConstructor(String.class, int.class).getParameters())
        {
            show("Color ctor " + p.getName(), p.accessFlags());
        }
        show("varargs arg0", AccessFlagsProbe.class.getDeclaredMethod("varargs", String[].class)
                .getParameters()[0].accessFlags());

        System.out.println("-- AccessFlag itself --");
        AccessFlag[] all = AccessFlag.values();
        java.util.Arrays.sort(all, Comparator.comparing(Enum::name));
        for (AccessFlag f : all)
        {
            System.out.println(f + " mask=0x" + Integer.toHexString(f.mask()) + " source=" + f.sourceModifier()
                    + " locations=" + new java.util.TreeSet<>(f.locations()));
        }
        System.out.println("AccessFlagsProbe done");
    }
}
