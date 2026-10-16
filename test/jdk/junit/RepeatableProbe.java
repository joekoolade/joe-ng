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
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;

/**
 * Repeated annotations through {@code getAnnotationsByType} / {@code getDeclaredAnnotationsByType}. A
 * {@code @Repeatable} annotation written twice is stored as ONE container annotation, so these methods must look
 * through the container's {@code value()} -- on a class, a method, a field and a parameter -- as well as find a
 * single direct use, an {@code @Inherited} repeatable on a superclass (by-type only, not declared), a
 * non-repeatable annotation, and nothing. The returned array's component type is the annotation type, and
 * {@code getAnnotation} of the repeated type answers null while the container answers both. First, the map the
 * {@code AnnotatedElement} default builds from {@code getDeclaredAnnotations} with a stream, keyed by
 * {@code annotationType}. Prints values only; the host diff is the gate.
 */
public class RepeatableProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(Tags.class)
    @interface Tag
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Tags
    {
        Tag[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @Repeatable(Roles.class)
    @interface Role
    {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @interface Roles
    {
        Role[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Plain
    {
        int value();
    }

    @Tag("a")
    @Tag("b")
    @Plain(1)
    @Role("admin")
    @Role("user")
    static class Twice
    {
        @Tag("f1")
        @Tag("f2")
        int field;

        @Tag("m1")
        @Tag("m2")
        @Tag("m3")
        void method(@Tag("p1") @Tag("p2") int p)
        {
        }
    }

    @Tag("only")
    static class Once
    {
    }

    static class Sub extends Twice
    {
    }

    static class None
    {
    }

    static String tags(Annotation[] as)
    {
        StringBuilder sb = new StringBuilder(as.getClass().getComponentType().getSimpleName() + "[");
        for (int i = 0; i < as.length; i++)
        {
            sb.append(i > 0 ? " " : "");
            Annotation a = as[i];
            if (a instanceof Tag t)
            {
                sb.append("@Tag(").append(t.value()).append(")");
            }
            else if (a instanceof Role r)
            {
                sb.append("@Role(").append(r.value()).append(")");
            }
            else if (a instanceof Plain p)
            {
                sb.append("@Plain(").append(p.value()).append(")");
            }
            else
            {
                sb.append("@").append(a.annotationType().getSimpleName());
            }
        }
        return sb.append("]").toString();
    }

    static void show(String what, AnnotatedElement e)
    {
        System.out.println(what + " byType(Tag)=" + tags(e.getAnnotationsByType(Tag.class)) + " declaredByType(Tag)="
                + tags(e.getDeclaredAnnotationsByType(Tag.class)) + " getAnnotation(Tag)="
                + (e.getAnnotation(Tag.class) == null ? "null" : e.getAnnotation(Tag.class).value())
                + " container=" + (e.getAnnotation(Tags.class) == null ? "null"
                        : tags(e.getAnnotation(Tags.class).value())));
    }

    public static void main(String[] args) throws Exception
    {
        java.util.Map<Class<? extends Annotation>, Annotation> byStream = java.util.Arrays
                .stream(Twice.class.getDeclaredAnnotations())
                .collect(java.util.stream.Collectors.toMap(Annotation::annotationType,
                        java.util.function.Function.identity(), (first, second) -> first,
                        java.util.LinkedHashMap::new));
        StringBuilder keys = new StringBuilder();
        for (Class<? extends Annotation> k : byStream.keySet())
        {
            keys.append(k.getSimpleName()).append(' ');
        }
        System.out.println("stream map keys: " + keys + "get(Tags)=" + (byStream.get(Tags.class) != null));
        show("Twice", Twice.class);
        show("Once", Once.class);
        show("None", None.class);
        show("Sub", Sub.class);
        show("field", Twice.class.getDeclaredField("field"));
        Method m = Twice.class.getDeclaredMethod("method", int.class);
        show("method", m);
        show("param", m.getParameters()[0]);
        System.out.println("Twice byType(Plain)=" + tags(Twice.class.getAnnotationsByType(Plain.class)));
        System.out.println("Twice byType(Role)=" + tags(Twice.class.getAnnotationsByType(Role.class)));
        System.out.println("Sub byType(Role)=" + tags(Sub.class.getAnnotationsByType(Role.class)));
        System.out.println("Sub declaredByType(Role)=" + tags(Sub.class.getDeclaredAnnotationsByType(Role.class)));
        System.out.println("Sub getAnnotation(Roles)=" + (Sub.class.getAnnotation(Roles.class) == null ? "null"
                : tags(Sub.class.getAnnotation(Roles.class).value())));
        System.out.println("Twice byType(Tags)=" + Twice.class.getAnnotationsByType(Tags.class).length);
        System.out.println("Sub getAnnotations=" + tags(Sub.class.getAnnotations()) + " declared="
                + tags(Sub.class.getDeclaredAnnotations()) + " present(Roles)=" + Sub.class.isAnnotationPresent(Roles.class)
                + " present(Tags)=" + Sub.class.isAnnotationPresent(Tags.class));
        System.out.println("RepeatableProbe done");
    }
}
