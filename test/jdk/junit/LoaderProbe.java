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

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import org.junit.platform.engine.TestEngine;

/**
 * The built-in class loaders as stock java.base sees them. Every answer here is one java.base code BRANCHES on --
 * MethodHandle.isBuiltinLoader, VM.isSystemDomainLoader, ServiceLoader, the hidden-class definer -- so a VM that
 * answers null for every class (the bootstrap loader) runs paths no stock JVM takes for an application class.
 * Host recipe: {@code java -cp <classes>:ramfs/lib/junit.jar LoaderProbe} (resources come out of junit.jar on
 * both sides; a URL is compared from its last "lib/" on, the part that does not depend on where the jar is).
 */
public final class LoaderProbe
{
    static final String SERVICES = "META-INF/services/org.junit.platform.engine.TestEngine";

    public static void main(String[] args) throws Throwable
    {
        ClassLoader app = LoaderProbe.class.getClassLoader();
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        arm("app loader is the system loader", () -> String.valueOf(app == ClassLoader.getSystemClassLoader()));
        arm("app loader name", () -> app.getName());
        arm("parent is the platform loader", () -> String.valueOf(app.getParent() == platform));
        arm("platform loader name", () -> platform.getName());
        arm("platform parent", () -> String.valueOf(platform.getParent()));
        arm("context loader is the app loader", () -> String.valueOf(Thread.currentThread().getContextClassLoader() == app));
        arm("String / int[] / String[] loader", () -> String.class.getClassLoader() + " " + int[].class.getClassLoader()
                + " " + String[].class.getClassLoader());
        arm("LoaderProbe[][] loader is app", () -> String.valueOf(LoaderProbe[][].class.getClassLoader() == app));
        arm("jar class loader is app", () -> String.valueOf(TestEngine.class.getClassLoader() == app));
        arm("unnamed module", () -> String.valueOf(LoaderProbe.class.getModule() == app.getUnnamedModule()));

        arm("loadClass app class", () -> String.valueOf(app.loadClass("LoaderProbe") == LoaderProbe.class));
        arm("loadClass java.base class", () -> String.valueOf(app.loadClass("java.lang.String") == String.class));
        arm("forName with app loader", () -> String.valueOf(Class.forName("LoaderProbe", false, app) == LoaderProbe.class));
        arm("platform cannot see an app class", () -> platform.loadClass("LoaderProbe").getName());
        arm("loadClass absent", () -> app.loadClass("no.such.Clazz").getName());

        arm("getResource protocol + tail", () -> describe(app.getResource(SERVICES)));
        arm("getResource bytes", () -> bytes(app.getResource(SERVICES).openStream()));
        arm("getResourceAsStream bytes", () -> bytes(app.getResourceAsStream(SERVICES)));
        arm("getResources count", () -> String.valueOf(count(app.getResources(SERVICES))));
        arm("Class.getResource of a jar class", () -> describe(TestEngine.class.getResource("TestEngine.class")));
        arm("getSystemResource absent", () -> String.valueOf(ClassLoader.getSystemResource("no/such/thing")));

        arm("pattern switch in an app class", () -> kind(42) + " " + kind("s") + " " + kind(3.0));
        System.out.println("LoaderProbe done");
    }

    /** A type switch: invokedynamic SwitchBootstraps.typeSwitch, which tests the caller's loader. */
    static String kind(Object o)
    {
        return switch (o)
        {
            case Integer i -> "int" + i;
            case String s -> "str" + s;
            default -> "other";
        };
    }

    static String describe(URL u)
    {
        if (u == null)
        {
            return "null";
        }
        String s = u.toString();
        int at = s.lastIndexOf("lib/");
        return u.getProtocol() + " ..." + (at < 0 ? s : s.substring(at));
    }

    static String bytes(InputStream in) throws Exception
    {
        if (in == null)
        {
            return "null";
        }
        byte[] b = in.readAllBytes();
        in.close();
        long sum = 0;
        for (byte x : b)
        {
            sum = sum * 31 + (x & 0xFF);
        }
        return b.length + " bytes hash=" + Long.toHexString(sum);
    }

    static int count(Enumeration<URL> e)
    {
        int n = 0;
        while (e.hasMoreElements())
        {
            e.nextElement();
            n += 1;
        }
        return n;
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
