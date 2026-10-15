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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * EXACT reflective lookup by parameter types, over OVERLOADS of the same name and the same arity -- the shape a
 * name-only or arity-only resolver answers wrongly. A class with three constructors ({@code ()} private,
 * {@code (int)}, {@code (String)}) and four {@code m} overloads; {@code getDeclaredConstructor} and
 * {@code getConstructor} by type, {@code getConstructors}' public count, {@code getDeclaredConstructors}' distinct
 * descriptors; {@code getDeclaredMethod}/{@code getMethod} by type, including an inherited public method, an
 * interface DEFAULT method, an array parameter, and wrong types refused. Each result is INVOKED, so a wrong overload
 * shows in its value. Then {@code JarFile(File)} over the RAMFS jar (the host passes the repo's copy as args[0]).
 * Prints values only; the host diff is the gate.
 */
public class ReflectLookupProbe
{
    interface Greets
    {
        default String greet(String who)
        {
            return "hello " + who;
        }
    }

    public static class Base
    {
        public String inherited(int x)
        {
            return "base" + x;
        }
    }

    public static class Target extends Base implements Greets
    {
        final String how;

        private Target()
        {
            how = "none";
        }

        public Target(int x)
        {
            how = "int:" + x;
        }

        public Target(String s)
        {
            how = "str:" + s;
        }

        public String m()
        {
            return "m()";
        }

        public String m(int x)
        {
            return "m(int " + x + ")";
        }

        public String m(String s)
        {
            return "m(String " + s + ")";
        }

        public String m(int[] a)
        {
            return "m(int[] " + a.length + ")";
        }
    }

    public static void main(String[] args) throws Exception
    {
        Class<Target> t = Target.class;
        Constructor<Target> ci = t.getDeclaredConstructor(int.class);
        Constructor<Target> cs = t.getConstructor(String.class);
        System.out.println("ctor(int)=" + ci.newInstance(7).how + " ctor(String)=" + cs.newInstance("a").how);
        System.out.println("ctor(int) params=" + Arrays.toString(ci.getParameterTypes()));
        System.out.println("public constructors=" + t.getConstructors().length + " declared="
                + t.getDeclaredConstructors().length);
        String[] descs = new String[3];
        Constructor<?>[] all = t.getDeclaredConstructors();
        for (int i = 0; i < all.length; i += 1)
        {
            descs[i] = Arrays.toString(all[i].getParameterTypes());
        }
        Arrays.sort(descs);
        System.out.println("declared constructor params=" + Arrays.toString(descs));
        try
        {
            t.getConstructor();
            System.out.println("getConstructor() of private: returned");
        }
        catch (NoSuchMethodException e)
        {
            System.out.println("getConstructor() of private: NoSuchMethodException");
        }

        Target obj = ci.newInstance(1);
        System.out.println(t.getDeclaredMethod("m").invoke(obj) + " | "
                + t.getDeclaredMethod("m", int.class).invoke(obj, 5) + " | "
                + t.getMethod("m", String.class).invoke(obj, "x") + " | "
                + t.getMethod("m", int[].class).invoke(obj, (Object) new int[3]));
        System.out.println("inherited=" + t.getMethod("inherited", int.class).invoke(obj, 9));
        System.out.println("interfaces=" + Arrays.toString(t.getInterfaces()) + " Greets declares "
                + Greets.class.getDeclaredMethods().length + " method(s)");
        boolean listed = false;
        for (Method m : t.getMethods())
        {
            listed |= m.getName().equals("greet");
        }
        System.out.println("getMethods lists greet=" + listed);
        System.out.println("default=" + t.getMethod("greet", String.class).invoke(obj, "pi"));
        try
        {
            t.getDeclaredMethod("m", long.class);
            System.out.println("m(long): returned");
        }
        catch (NoSuchMethodException e)
        {
            System.out.println("m(long): NoSuchMethodException");
        }
        try
        {
            t.getDeclaredMethod("inherited", int.class);
            System.out.println("getDeclaredMethod of inherited: returned");
        }
        catch (NoSuchMethodException e)
        {
            System.out.println("getDeclaredMethod of inherited: NoSuchMethodException");
        }
        Method mi = t.getMethod("m", int.class);
        System.out.println("m(int) params=" + Arrays.toString(mi.getParameterTypes()) + " declaring="
                + mi.getDeclaringClass().getSimpleName());

        String jar = args.length > 0 ? args[0] : "/lib/app.jar";
        java.util.jar.JarFile jf = new java.util.jar.JarFile(new java.io.File(jar));
        System.out.println("JarFile(File) manifest=" + (jf.getManifest() != null));
        System.out.println("ReflectLookupProbe done");
    }
}
