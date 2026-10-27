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
package java.lang;

import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

import jdk.internal.access.MetalJavaLangAccess;
import jdk.internal.access.MetalLangBridge;
import jdk.internal.loader.NativeLibraries;
import jdk.internal.misc.VM;

/**
 * The class-loader half of stock {@code System.initPhase3}, which joe-ng does not run: raise the init level to 3,
 * install the system class loader, make it the main thread's context loader, and raise the level to 4
 * ({@code SYSTEM_BOOTED}). Its initializer IS the work; {@code Loader.seedBooted} runs it once, after the other
 * launch seeds, so the system properties it reads (java.class.path, java.system.class.loader) are already there.
 *
 * <p>Without it {@code ClassLoader.getSystemClassLoader()} answered null at level 4 (stock's {@code Holder.scl},
 * which only initSystemClassLoader writes) and the main thread had NO context loader -- MEASURED by LoaderProbe,
 * "context loader is the app loader = false". A thread inherits its creator's context loader, so seeding the
 * main thread is enough.
 *
 * <p>It first hands MetalJavaLangAccess the java.lang side of the JavaLangAccess members stock's System$2 answers
 * with a package-private call ({@link Bridge}): the built-in loaders initSystemClassLoader creates are their
 * first callers (ClassLoaderValue, ServicesCatalog).
 */
final class MetalSystemLoader
{
    static
    {
        MetalJavaLangAccess.setBridge(new Bridge());
        VM.initLevel(3);
        ClassLoader scl = ClassLoader.initSystemClassLoader();
        Thread.currentThread().setContextClassLoader(scl);
        VM.initLevel(4);
    }

    private MetalSystemLoader()
    {
    }

    /** Stock System$2's bodies for the members MetalJavaLangAccess forwards here. */
    private static final class Bridge implements MetalLangBridge
    {
        @Override
        public ConcurrentHashMap<?, ?> createOrGetClassLoaderValueMap(ClassLoader cl)
        {
            return cl.createOrGetClassLoaderValueMap();
        }

        @Override
        public Class<?> defineClass(ClassLoader loader, String name, byte[] b, ProtectionDomain pd, String source)
        {
            return ClassLoader.defineClass1(loader, name, b, 0, b.length, pd, source);
        }

        @Override
        public Package definePackage(ClassLoader cl, String name, Module module)
        {
            return cl.definePackage(name, module);
        }

        @Override
        public NativeLibraries nativeLibrariesFor(ClassLoader loader)
        {
            return ClassLoader.nativeLibrariesFor(loader);
        }

        @Override
        public String getLoaderNameID(ClassLoader loader)
        {
            return loader != null ? loader.nameAndId() : "null";
        }

        @Override
        public Class<?> findBootstrapClassOrNull(String name)
        {
            return ClassLoader.findBootstrapClassOrNull(name);
        }
    }
}
