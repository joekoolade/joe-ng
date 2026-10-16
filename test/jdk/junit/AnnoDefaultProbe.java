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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * {@code Method.getDefaultValue}: the default of an annotation element, of every kind an element may have --
 * each primitive, {@code String}, {@code Class}, an enum constant, a nested annotation, and an array of each
 * reference kind -- and null for an element with NO default, for a method of an ordinary class, and for an
 * interface method. Two calls must answer equal values but never share a mutable array. Then the same kinds
 * WRITTEN on a use of the annotation, read through its instance -- the decoder is shared, so a value kind it
 * cannot read is wrong in both places. Values print through
 * {@code Arrays.deepToString} and, for a nested annotation, its own element, never its {@code toString}.
 * Prints values only; the host diff is the gate.
 */
public class AnnoDefaultProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Inner
    {
        String value() default "inner-default";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Defaults
    {
        int i() default 7;

        long l() default 1L << 40;

        boolean z() default true;

        byte b() default -3;

        short s() default 300;

        char c() default 'q';

        float f() default 1.5f;

        double d() default -2.25;

        String str() default "hello";

        Class<?> cls() default String.class;

        TimeUnit unit() default TimeUnit.SECONDS;

        Inner nested() default @Inner("given");

        int[] ints() default { 1, 2, 3 };

        String[] strs() default { "a", "b" };

        Class<?>[] classes() default { Integer.class, int[].class };

        TimeUnit[] units() default { TimeUnit.DAYS, TimeUnit.NANOSECONDS };

        Inner[] nesteds() default { @Inner, @Inner("two") };

        int[] empty() default { };

        String required();
    }

    interface Plain
    {
        int x();
    }

    @Defaults(required = "r", d = 3.5, f = 2.5f, ints = { 4, 5 }, classes = { long[].class, String[][].class },
            nested = @Inner("written"), nesteds = { @Inner("w1") }, units = { TimeUnit.HOURS }, c = 'z',
            l = -9L)
    static class Annotated
    {
    }

    static String show(Object v)
    {
        if (v == null)
        {
            return "null";
        }
        if (v instanceof Inner in)
        {
            return "@Inner(" + in.value() + ")";
        }
        if (v instanceof Inner[] ins)
        {
            StringBuilder sb = new StringBuilder("[");
            for (int k = 0; k < ins.length; k++)
            {
                sb.append(k > 0 ? ", " : "").append(show(ins[k]));
            }
            return sb.append("]").toString();
        }
        String body = Arrays.deepToString(new Object[] { v });
        return v.getClass().getSimpleName() + " " + body.substring(1, body.length() - 1);
    }

    public static void main(String[] args) throws Exception
    {
        Method[] ms = Defaults.class.getDeclaredMethods();
        Arrays.sort(ms, (a, b) -> a.getName().compareTo(b.getName()));
        for (Method m : ms)
        {
            System.out.println(m.getName() + " -> " + show(m.getDefaultValue()));
        }
        Method ints = Defaults.class.getDeclaredMethod("ints");
        int[] first = (int[]) ints.getDefaultValue();
        first[0] = 99;
        System.out.println("ints after mutating a returned copy -> " + show(ints.getDefaultValue()));
        System.out.println("equal across calls -> "
                + Arrays.equals((String[]) Defaults.class.getDeclaredMethod("strs").getDefaultValue(),
                        (String[]) Defaults.class.getDeclaredMethod("strs").getDefaultValue()));
        System.out.println("Inner.value -> " + show(Inner.class.getDeclaredMethod("value").getDefaultValue()));
        System.out.println("Plain.x -> " + show(Plain.class.getDeclaredMethod("x").getDefaultValue()));
        System.out.println("String.length -> " + show(String.class.getDeclaredMethod("length").getDefaultValue()));
        Defaults w = Annotated.class.getAnnotation(Defaults.class);
        System.out.println("written: required=" + w.required() + " d=" + w.d() + " f=" + w.f() + " c=" + w.c()
                + " l=" + w.l() + " ints=" + Arrays.toString(w.ints()) + " classes=" + Arrays.toString(w.classes())
                + " nested=" + show(w.nested()) + " nesteds=" + show(w.nesteds()) + " units="
                + Arrays.toString(w.units()) + " i=" + w.i() + " empty=" + w.empty().length + " str=" + w.str());
        System.out.println("AnnoDefaultProbe done");
    }
}
