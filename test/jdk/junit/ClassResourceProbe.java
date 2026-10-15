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
import java.io.InputStream;

import org.junit.platform.engine.TestEngine;

/**
 * {@code Class.getResourceAsStream}/{@code getResource} name resolution, and {@code ClassLoader.getParent}/
 * {@code getDefinedPackage}. A name without a leading '/' is relative to the class's PACKAGE, so the same string
 * finds an entry from one class and misses from another -- the shape a resolver that ignores the package gets
 * wrong. Read against the JUnit jar (metal: {@code classpath=/lib/junit.jar}; host: the same jar on -cp): a class
 * file read relative to its own class, a services file read absolute, the same services name read RELATIVE from
 * a packaged class (a miss) and from the default package (a hit). Prints values only; the host diff is the gate.
 * A present resource's URL is deliberately not printed: this VM cannot build one, which ResourceProbe covers.
 */
public class ClassResourceProbe
{
    static final String SERVICES = "META-INF/services/org.junit.platform.engine.TestEngine";

    public static void main(String[] args) throws Exception
    {
        show("TestEngine rel TestEngine.class", TestEngine.class.getResourceAsStream("TestEngine.class"));
        show("TestEngine abs /org/.../TestEngine.class",
                TestEngine.class.getResourceAsStream("/org/junit/platform/engine/TestEngine.class"));
        show("TestEngine abs /" + SERVICES, TestEngine.class.getResourceAsStream("/" + SERVICES));
        show("TestEngine rel " + SERVICES, TestEngine.class.getResourceAsStream(SERVICES));
        show("default-pkg rel " + SERVICES, ClassResourceProbe.class.getResourceAsStream(SERVICES));
        show("default-pkg abs /no/such/thing", ClassResourceProbe.class.getResourceAsStream("/no/such/thing"));

        System.out.println("getResource rel absent = " + TestEngine.class.getResource("NoSuch.class"));
        System.out.println("getResource abs absent = " + ClassResourceProbe.class.getResource("/no/such/thing"));
        System.out.println("getSystemResource absent = " + ClassLoader.getSystemResource("no/such/thing"));

        ClassLoader sys = ClassLoader.getSystemClassLoader();
        ClassLoader child = new ClassLoader()
        {
        };
        ClassLoader orphan = new ClassLoader(null)
        {
        };
        ClassLoader grandchild = new ClassLoader(child)
        {
        };
        System.out.println("default parent is system = " + (child.getParent() == sys));
        System.out.println("explicit null parent = " + orphan.getParent());
        System.out.println("explicit parent kept = " + (grandchild.getParent() == child));
        System.out.println("getDefinedPackage(no.such.pkg) = " + sys.getDefinedPackage("no.such.pkg"));
        System.out.println("child getDefinedPackage(java.lang) = " + child.getDefinedPackage("java.lang"));
        try
        {
            sys.getDefinedPackage(null);
            System.out.println("getDefinedPackage(null) returned");
        }
        catch (NullPointerException e)
        {
            System.out.println("getDefinedPackage(null) NPE: " + e.getMessage());
        }
        System.out.println("ClassResourceProbe done");
    }

    static void show(String what, InputStream in) throws Exception
    {
        if (in == null)
        {
            System.out.println(what + " -> null");
            return;
        }
        byte[] b = in.readAllBytes();
        in.close();
        String head = b.length >= 4
                ? String.format("%02x%02x%02x%02x", b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF) : "";
        long sum = 0;
        for (byte x : b)
        {
            sum = sum * 31 + (x & 0xFF);
        }
        System.out.println(what + " -> " + b.length + " bytes head=" + head + " hash=" + Long.toHexString(sum));
    }
}
