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
import java.lang.reflect.Method;

/**
 * Annotations whose TYPE nothing else names. No class literal, cast or call mentions these annotation types, so
 * nothing loads them before reflection reads them -- and stock reports every one. Covered: on a class, a method,
 * a field and a parameter; as the value of another annotation's element (a nested use), and in an array of
 * them; and the class's annotations read by the bulk readers. Each annotation is printed by its simple type
 * name and element, never by {@code toString}. Prints values only; the host diff is the gate.
 */
public class AnnoLoadProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface OnClass
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface OnMethod
    {
        int value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface OnField
    {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface OnParam
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Inner
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Outer
    {
        Inner one();

        Inner[] many();
    }

    @OnClass("c")
    @Outer(one = @Inner("x"), many = { @Inner("y"), @Inner("z") })
    static class Target
    {
        @OnField
        int field;

        @OnMethod(3)
        void method(@OnParam("p") int p)
        {
        }
    }

    static String describe(Annotation[] as)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < as.length; i++)
        {
            sb.append(i > 0 ? " " : "").append('@').append(as[i].annotationType().getSimpleName());
        }
        return sb.append(']').toString();
    }

    public static void main(String[] args) throws Exception
    {
        System.out.println("class " + describe(Target.class.getDeclaredAnnotations()));
        System.out.println("class all " + describe(Target.class.getAnnotations()));
        System.out.println("field " + describe(Target.class.getDeclaredField("field").getDeclaredAnnotations()));
        Method m = Target.class.getDeclaredMethod("method", int.class);
        System.out.println("method " + describe(m.getDeclaredAnnotations()));
        System.out.println("param " + describe(m.getParameterAnnotations()[0]));
        for (Annotation a : Target.class.getDeclaredAnnotations())
        {
            Class<? extends Annotation> t = a.annotationType();
            if (t.getSimpleName().equals("Outer"))
            {
                Method one = t.getDeclaredMethod("one");
                Method many = t.getDeclaredMethod("many");
                Annotation in = (Annotation) one.invoke(a);
                Annotation[] ins = (Annotation[]) many.invoke(a);
                System.out.println("nested one " + (in == null ? "null" : "@" + in.annotationType().getSimpleName()
                        + "(" + in.annotationType().getDeclaredMethod("value").invoke(in) + ")"));
                StringBuilder sb = new StringBuilder();
                for (Annotation x : ins)
                {
                    sb.append(x == null ? "null " : "@" + x.annotationType().getSimpleName() + "("
                            + x.annotationType().getDeclaredMethod("value").invoke(x) + ") ");
                }
                System.out.println("nested many " + sb.toString().trim());
            }
        }
        System.out.println("AnnoLoadProbe done");
    }
}
