/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.text.BreakIterator;
import java.util.Arrays;

/**
 * Supertypes an overlay used to DROP, each reached through the INTERFACE type -- the shape that resolves
 * against the interface and so cannot bind when the overlay omits it. {@code Member} over a Field, Method and
 * Constructor; {@code AnnotatedElement} over a Class, Method, Field, Constructor (whose annotations were never
 * readable) and the unnamed Module; {@code Class} as {@code Serializable}; a cloned {@code BreakIterator}
 * iterating independently; and the direct interfaces of the classes that dropped a constants interface
 * ({@code ZipFile}'s {@code ZipConstants}, {@code SocksSocketImpl}'s {@code SocksConsts}). Prints values only;
 * the host diff is the gate.
 */
public class SupertypeProbe
{
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tag
    {
        String value();
    }

    @Tag("type")
    static class Target
    {
        @Tag("field")
        public int x;

        @Tag("ctor")
        public Target(int x)
        {
            this.x = x;
        }

        Target()
        {
        }

        @Tag("method")
        public int twice()
        {
            return x * 2;
        }
    }

    public static void main(String[] args) throws Exception
    {
        Field f = Target.class.getDeclaredField("x");
        Method m = Target.class.getDeclaredMethod("twice");
        Constructor<Target> c = Target.class.getDeclaredConstructor(int.class);
        Constructor<Target> c0 = Target.class.getDeclaredConstructor();

        Member[] members = { f, m, c };
        for (Member mem : members)
        {
            System.out.println("member " + mem.getName() + " mods=" + mem.getModifiers() + " in="
                    + mem.getDeclaringClass().getSimpleName() + " synthetic=" + mem.isSynthetic());
        }

        AnnotatedElement[] elems = { Target.class, f, m, c, c0, SupertypeProbe.class.getModule() };
        String[] names = { "class", "field", "method", "ctor(int)", "ctor()", "module" };
        int i = 0;
        while (i < elems.length)
        {
            AnnotatedElement e = elems[i];
            Tag t = e.getAnnotation(Tag.class);
            Tag d = e.getDeclaredAnnotation(Tag.class);
            Annotation[] all = e.getAnnotations();
            Annotation[] decl = e.getDeclaredAnnotations();
            System.out.println("anno " + names[i] + " present=" + e.isAnnotationPresent(Tag.class)
                    + " get=" + (t == null ? "null" : t.value()) + " declared=" + (d == null ? "null" : d.value())
                    + " all=" + all.length + " decl=" + decl.length
                    + " deprecated=" + e.isAnnotationPresent(Deprecated.class));
            i += 1;
        }

        Target made = c.newInstance(21);
        System.out.println("ctor invoke twice=" + m.invoke(made));

        Object cls = String.class;
        System.out.println("Class is Serializable=" + (cls instanceof Serializable)
                + " AnnotatedElement=" + (cls instanceof AnnotatedElement));
        System.out.println("Module is AnnotatedElement=" + ((Object) String.class.getModule() instanceof AnnotatedElement));
        System.out.println("Field is Member=" + ((Object) f instanceof Member)
                + " Method is Member=" + ((Object) m instanceof Member));

        BreakIterator bi = BreakIterator.getLineInstance();
        bi.setText("alpha beta gamma");
        System.out.println("break first=" + bi.first() + " next=" + bi.next());
        Object cl = bi.clone();
        System.out.println("BreakIterator is Cloneable=" + ((Object) bi instanceof Cloneable)
                + " clone same=" + (cl == bi) + " class=" + (cl.getClass() == bi.getClass()));
        BreakIterator copy = (BreakIterator) cl;
        System.out.println("copy current=" + copy.current() + " next=" + copy.next() + " next=" + copy.next());
        System.out.println("orig current=" + bi.current() + " next=" + bi.next());

        System.out.println("ifaces AccessibleObject="
                + Arrays.toString(java.lang.reflect.AccessibleObject.class.getInterfaces()));
        System.out.println("ifaces Field=" + Arrays.toString(Field.class.getInterfaces()));
        System.out.println("ifaces Module=" + Arrays.toString(Module.class.getInterfaces()));
        System.out.println("ifaces BreakIterator=" + Arrays.toString(BreakIterator.class.getInterfaces()));
        System.out.println("ifaces ZipFile=" + Arrays.toString(java.util.zip.ZipFile.class.getInterfaces()));
        System.out.println("ifaces JarFile=" + Arrays.toString(java.util.jar.JarFile.class.getInterfaces()));
        System.out.println("ifaces SocksSocketImpl="
                + Arrays.toString(Class.forName("java.net.SocksSocketImpl").getInterfaces()));
        System.out.println("SupertypeProbe done");
    }
}
