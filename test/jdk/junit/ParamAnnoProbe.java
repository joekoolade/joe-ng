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
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;

/**
 * Parameter annotations and {@code Parameter} itself. Annotations sit on the first, a middle and the last
 * parameter, two on one parameter, none on others, and one of CLASS retention that must stay invisible; then
 * the constructors whose {@code RuntimeVisibleParameterAnnotations} count can differ from the descriptor's -- an
 * inner class (the outer instance is a leading implicit parameter) and an enum (name and ordinal are). For each
 * executable the probe prints the rows of {@code getParameterAnnotations}, each parameter's own answers, and
 * {@code Parameter}'s {@code toString} (generic type, varargs as {@code ...}), equality across two
 * {@code getParameters} calls, and the implicit/synthetic/varargs flags. Last, three annotations stacked on one
 * method, one field and one class, in different orders: every one after the first must keep its written
 * values rather than its defaults. Annotations are printed by type and element value, never by
 * {@code toString}. Prints values only; the host diff is the gate.
 */
public class ParamAnnoProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Mark
    {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Num
    {
        int value() default 7;
    }

    @Retention(RetentionPolicy.CLASS)
    @interface Hidden
    {
    }

    static void plain(int a, String b)
    {
    }

    static void mixed(@Tag("first") int a, long b, @Mark @Num(3) String c, @Hidden double d, @Num List<String> e)
    {
    }

    static void varargs(@Mark Map<String, Integer> m, @Tag("rest") int... rest)
    {
    }

    @Mark
    static void methodOnly(int a)
    {
    }

    @Mark
    @Num(3)
    @Tag("third")
    static void stacked()
    {
    }

    @Num(4)
    @Mark
    @Tag("f")
    static int stackedField;

    @Mark
    @Tag("cls")
    @Num(5)
    static class Stacked
    {
    }

    ParamAnnoProbe(@Tag("ctor") String s, int n)
    {
    }

    class Inner
    {
        Inner(@Tag("inner") String s, @Mark int n)
        {
        }
    }

    enum Color
    {
        RED("r");

        Color(@Tag("enum") String code)
        {
        }
    }

    static String show(Annotation a)
    {
        if (a instanceof Tag t)
        {
            return "@Tag(" + t.value() + ")";
        }
        if (a instanceof Num n)
        {
            return "@Num(" + n.value() + ")";
        }
        if (a instanceof Mark)
        {
            return "@Mark";
        }
        return "@?" + a.annotationType().getName();
    }

    static String row(Annotation[] as)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < as.length; i++)
        {
            if (i > 0)
            {
                sb.append(' ');
            }
            sb.append(show(as[i]));
        }
        return sb.append(']').toString();
    }

    static void report(String what, Executable e)
    {
        System.out.println("== " + what + " count=" + e.getParameterCount());
        Annotation[][] pa = e.getParameterAnnotations();
        System.out.println("  rows=" + pa.length);
        for (int i = 0; i < pa.length; i++)
        {
            System.out.println("  row " + i + " " + row(pa[i]));
        }
        Parameter[] ps = e.getParameters();
        Parameter[] again = e.getParameters();
        for (int i = 0; i < ps.length; i++)
        {
            Parameter p = ps[i];
            System.out.println("  param " + i + " toString=\"" + p + "\" name=" + p.getName() + " type="
                    + p.getType().getName() + " declared=" + row(p.getDeclaredAnnotations()) + " all="
                    + row(p.getAnnotations()));
            System.out.println("    getAnnotation(Tag)=" + (p.getAnnotation(Tag.class) == null ? "null"
                    : show(p.getAnnotation(Tag.class))) + " present(Mark)=" + p.isAnnotationPresent(Mark.class)
                    + " present(Hidden)=" + p.isAnnotationPresent(Hidden.class) + " varargs=" + p.isVarArgs()
                    + " implicit=" + p.isImplicit() + " synthetic=" + p.isSynthetic() + " modifiers="
                    + p.getModifiers() + " namePresent=" + p.isNamePresent());
            System.out.println("    equalsAgain=" + p.equals(again[i]) + " sameHash=" + (p.hashCode() == again[i]
                    .hashCode()) + " equalsOther=" + (ps.length > 1 && p.equals(ps[(i + 1) % ps.length]))
                    + " sameExec=" + (p.getDeclaringExecutable() == e || p.getDeclaringExecutable().equals(e)));
        }
    }

    public static void main(String[] args) throws Exception
    {
        Class<?> c = ParamAnnoProbe.class;
        report("plain", c.getDeclaredMethod("plain", int.class, String.class));
        report("mixed", c.getDeclaredMethod("mixed", int.class, long.class, String.class, double.class,
                List.class));
        report("varargs", c.getDeclaredMethod("varargs", Map.class, int[].class));
        Method mo = c.getDeclaredMethod("methodOnly", int.class);
        report("methodOnly", mo);
        System.out.println("methodOnly method-level @Mark=" + mo.isAnnotationPresent(Mark.class));
        report("ctor", c.getDeclaredConstructor(String.class, int.class));
        Constructor<?> inner = Inner.class.getDeclaredConstructor(ParamAnnoProbe.class, String.class, int.class);
        report("inner ctor", inner);
        Constructor<?> en = Color.class.getDeclaredConstructor(String.class, int.class, String.class);
        report("enum ctor", en);
        System.out.println("stacked method " + row(c.getDeclaredMethod("stacked").getDeclaredAnnotations()));
        System.out.println("stacked field " + row(c.getDeclaredField("stackedField").getDeclaredAnnotations()));
        System.out.println("stacked class " + row(Stacked.class.getDeclaredAnnotations()));
        System.out.println("ParamAnnoProbe done");
    }
}
