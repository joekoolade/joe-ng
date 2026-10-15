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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Supplier;

/**
 * Reflective {@code Method} objects for ABSTRACT methods, and {@code Method.invoke} as VIRTUAL dispatch.
 *
 * <p>Abstract members must exist: {@code getMethod} on an interface's abstract method, {@code
 * getDeclaredMethods} listing them, their modifiers and {@code toString}. Invoke must reach the RECEIVER's
 * body: a superclass {@code Method} on an overriding subclass, an abstract method on its implementation, an
 * interface method on a lambda and on an anonymous class, a default that a class overrides, one a
 * SUB-INTERFACE overrides, and one nobody overrides. The shapes that must NOT dispatch are here too: an
 * overload with another descriptor is not an override, and a private method is never overridden. Baked
 * receivers ({@code Object.toString} on an Integer, {@code Comparator.compare} on a lambda and on {@code
 * String.CASE_INSENSITIVE_ORDER}) and an annotation element read reflectively off a VM-synthesised instance
 * close it, with the two receiver errors stock throws. A {@code double} return printed garbage first: invoke
 * boxed FP results as object references, so FP arguments and returns are covered too. Prints values only; the host diff is the gate.
 */
public class VirtualInvokeProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag
    {
        String value();

        int weight() default 7;
    }

    interface Shape
    {
        double area();

        default String kind()
        {
            return "shape";
        }

        default String label()
        {
            return "label:" + kind();
        }
    }

    interface Polygon extends Shape
    {
        @Override
        default String kind()
        {
            return "polygon";
        }
    }

    abstract static class Base implements Shape
    {
        public String greet()
        {
            return "base greet";
        }

        public String take(Object o)
        {
            return "base take(Object)";
        }

        private String secret()
        {
            return "base secret";
        }

        public String callSecret()
        {
            return secret();
        }

        public abstract int sides();
    }

    @Tag("square")
    static class Square extends Base implements Polygon
    {
        public double area()
        {
            return 4.0;
        }

        @Override
        public String greet()
        {
            return "square greet";
        }

        public String take(String s)
        {
            return "square take(String)";
        }

        @SuppressWarnings("unused")
        private String secret()
        {
            return "square secret";
        }

        public int sides()
        {
            return 4;
        }
    }

    static class Circle extends Base
    {
        public double area()
        {
            return 3.0;
        }

        @Override
        public String kind()
        {
            return "circle";
        }

        public int sides()
        {
            return 0;
        }
    }

    static class Blob extends Base
    {
        public double area()
        {
            return 1.0;
        }

        public int sides()
        {
            return -1;
        }
    }

    static double scale(double d, float f, int i)
    {
        return d * f * i;
    }

    static float half(float f)
    {
        return f / 2;
    }

    public static void main(String[] args) throws Exception
    {
        Method area = Shape.class.getMethod("area");
        System.out.println("Shape.area = " + area + " abstract=" + Modifier.isAbstract(area.getModifiers()));
        Method sides = Base.class.getDeclaredMethod("sides");
        System.out.println("Base.sides = " + sides);
        Method run = Runnable.class.getMethod("run");
        System.out.println("Runnable.run = " + run);
        String[] shapeDeclared = Arrays.stream(Shape.class.getDeclaredMethods()).map(Method::getName).sorted()
                .toArray(String[]::new);
        System.out.println("Shape declared = " + Arrays.toString(shapeDeclared));
        String[] baseDeclared = Arrays.stream(Base.class.getDeclaredMethods()).map(Method::getName).sorted()
                .toArray(String[]::new);
        System.out.println("Base declared = " + Arrays.toString(baseDeclared));
        String[] tagDeclared = Arrays.stream(Tag.class.getDeclaredMethods()).map(Method::getName).sorted()
                .toArray(String[]::new);
        System.out.println("Tag declared = " + Arrays.toString(tagDeclared));

        Square sq = new Square();
        Circle ci = new Circle();
        Blob bl = new Blob();
        System.out.println("area(square) = " + area.invoke(sq));
        System.out.println("area(circle) = " + area.invoke(ci));
        System.out.println("sides(square) = " + sides.invoke(sq));
        System.out.println("greet via Base on square = " + Base.class.getMethod("greet").invoke(sq));
        System.out.println("greet via Base on circle = " + Base.class.getMethod("greet").invoke(ci));
        System.out.println("take(Object) via Base on square = "
                + Base.class.getMethod("take", Object.class).invoke(sq, "x"));
        Method secret = Base.class.getDeclaredMethod("secret");
        secret.setAccessible(true);
        System.out.println("private secret via Base on square = " + secret.invoke(sq));
        Method kind = Shape.class.getMethod("kind");
        System.out.println("kind(square: sub-interface default) = " + kind.invoke(sq));
        System.out.println("kind(circle: class override) = " + kind.invoke(ci));
        System.out.println("kind(blob: inherited default) = " + kind.invoke(bl));
        System.out.println("label(square) = " + Shape.class.getMethod("label").invoke(sq));

        Runnable r = () -> System.out.println("  lambda ran");
        run.invoke(r);
        run.invoke(new Runnable()
        {
            public void run()
            {
                System.out.println("  anonymous ran");
            }
        });
        Supplier<String> sup = () -> "supplied";
        System.out.println("Supplier.get on lambda = " + Supplier.class.getMethod("get").invoke(sup));

        Method compare = Comparator.class.getMethod("compare", Object.class, Object.class);
        Comparator<String> byLen = (a, b) -> Integer.compare(a.length(), b.length());
        System.out.println("compare(lambda) = " + compare.invoke(byLen, "aaa", "b"));
        System.out.println("compare(CASE_INSENSITIVE) = " + compare.invoke(String.CASE_INSENSITIVE_ORDER, "ABC",
                "abc"));
        System.out.println("Object.toString on Integer = " + Object.class.getMethod("toString").invoke(5));
        System.out.println("Object.hashCode on String = " + Object.class.getMethod("hashCode").invoke("abc"));

        Method scale = VirtualInvokeProbe.class.getDeclaredMethod("scale", double.class, float.class, int.class);
        System.out.println("scale(1.5, 2.5f, 3) = " + scale.invoke(null, 1.5, 2.5f, 3));
        System.out.println("half(5f) = " + VirtualInvokeProbe.class.getDeclaredMethod("half", float.class)
                .invoke(null, 5f));

        Tag tag = Square.class.getAnnotation(Tag.class);
        System.out.println("Tag.value reflectively = " + Tag.class.getMethod("value").invoke(tag));
        System.out.println("Tag.weight reflectively = " + Tag.class.getMethod("weight").invoke(tag));

        try
        {
            area.invoke(null);
            System.out.println("null receiver returned");
        }
        catch (NullPointerException e)
        {
            System.out.println("null receiver: NullPointerException");
        }
        try
        {
            area.invoke("not a shape");
            System.out.println("wrong receiver returned");
        }
        catch (IllegalArgumentException e)
        {
            System.out.println("wrong receiver: IllegalArgumentException");
        }
        System.out.println("VirtualInvokeProbe done");
    }
}
