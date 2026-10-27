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
package jdk.internal.misc;

/**
 * The VM's one class namespace, as stock {@code java.lang.ClassLoader} sees it. The VM loads every class itself
 * (out of the image, or out of the class-path jar on demand), so the stock loaders -- ClassLoaders' built-in ones
 * and any the program makes -- sit OVER that namespace: their natives (findLoadedClass0, findBootstrapClass,
 * defineClass1) are Java over the two here, and which loader a class belongs to is {@code Class.getClassLoader0}.
 */
public final class MetalLoaders
{
    private MetalLoaders()
    {
    }

    /**
     * The class named {@code name} (a binary name), loading it into the live program if it is not yet, WITHOUT
     * initializing it -- or null when the VM has no such class (or the name is not a valid binary name).
     */
    public static Class<?> lookup(String name)
    {
        if (name == null)
        {
            return null;
        }
        return lookup0(MetalNames.utf8(name));
    }

    /**
     * Define a class from classfile {@code b[off..off+len)} into the namespace; null when the bytes cannot be
     * defined. {@code name} is advisory: the VM uses the classfile's own this_class.
     */
    public static native Class<?> defineClass0(String name, byte[] b, int off, int len);

    /** VM native (Loader.nativeBufAt -> VMNatives.forNameNoInit -> Loader.forNameMirror(name, false)). */
    private static native Class<?> lookup0(byte[] name);

    /**
     * One of java.base's OWN resources -- {@code java/util/currency.data}, the break-iterator rule data, the
     * content-types table -- or null. They live inside the JDK's modules image, in no jar, so the build copies each
     * one the VM serves out of the seed's image to {@code /lib/java.base/<name>} in the RAMFS (Makefile
     * {@code JAVA_BASE_RES}). Class.getResourceAsStream and Module.getResourceAsStream fall back to this when the
     * class path has nothing; stock java.base reads its resources through exactly those two methods.
     */
    public static java.io.InputStream javaBaseResource(String name)
    {
        java.io.File f = new java.io.File(JAVA_BASE_RESOURCES + name);
        if (!f.isFile())
        {
            return null;
        }
        try
        {
            return new java.io.FileInputStream(f);
        }
        catch (java.io.FileNotFoundException e)
        {
            return null;
        }
    }

    /** Where the build puts java.base's own resources in the RAMFS (see {@link #javaBaseResource}). */
    private static final String JAVA_BASE_RESOURCES = "/lib/java.base/";
}
