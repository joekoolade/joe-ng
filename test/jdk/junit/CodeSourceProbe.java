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

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;

/**
 * java.security.CodeSource and SecureClassLoader: what every stock built-in loader IS (BuiltinClassLoader extends
 * SecureClassLoader) and what it defines a class with. The last arm defines a class through a SecureClassLoader
 * with a CodeSource and reads it back off the class's protection domain -- the record a stock JVM keeps per class.
 * The class is generated with java.lang.classfile: this probe is baked into the image on metal, so its own class
 * files are not resources there.
 */
public final class CodeSourceProbe
{
    static final class Definer extends SecureClassLoader
    {
        Definer(ClassLoader parent)
        {
            super(parent);
        }

        Class<?> define(String name, byte[] b, CodeSource cs)
        {
            return defineClass(name, b, 0, b.length, cs);
        }
    }

    public static void main(String[] args) throws Throwable
    {
        URL loc = new URL("file:/probe/lib/code.jar");
        CodeSource cs = new CodeSource(loc, (CodeSigner[]) null);
        arm("location", () -> cs.getLocation().toString());
        arm("signers / certificates", () -> cs.getCodeSigners() + " " + cs.getCertificates());
        arm("equals same location", () -> String.valueOf(cs.equals(new CodeSource(new URL("file:/probe/lib/code.jar"), (CodeSigner[]) null))));
        arm("hashCode stable", () -> String.valueOf(cs.hashCode() == new CodeSource(loc, (CodeSigner[]) null).hashCode()));
        arm("implies a directory wildcard", () -> String.valueOf(new CodeSource(new URL("file:/probe/lib/*"), (CodeSigner[]) null).implies(cs)));
        arm("does not imply a sibling", () -> String.valueOf(new CodeSource(new URL("file:/other/*"), (CodeSigner[]) null).implies(cs)));
        arm("toString", () -> cs.toString());
        arm("null location", () -> String.valueOf(new CodeSource(null, (CodeSigner[]) null).getLocation()));

        ClassLoader app = CodeSourceProbe.class.getClassLoader();
        Definer d = new Definer(app);
        arm("SecureClassLoader parent", () -> String.valueOf(d.getParent() == app));
        arm("SecureClassLoader delegates", () -> String.valueOf(d.loadClass("java.lang.String") == String.class));
        arm("SecureClassLoader is parallel capable", () -> String.valueOf(d.isRegisteredAsParallelCapable()));
        arm("define with a CodeSource", () ->
        {
            byte[] b = ClassFile.of().build(ClassDesc.of("CsPayload"), cb -> cb.withFlags(ClassFile.ACC_PUBLIC));
            Class<?> c = d.define("CsPayload", b, cs);
            ProtectionDomain pd = c.getProtectionDomain();
            return "loader=" + (c.getClassLoader() == d) + " cs=" + pd.getCodeSource()
                    + " same pd twice=" + (pd == c.getProtectionDomain());
        });
        System.out.println("CodeSourceProbe done");
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
