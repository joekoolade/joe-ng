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

/**
 * java.lang's side of the JavaLangAccess members stock's System$2 answers with a package-private java.lang call
 * ({@link Bridge}), which MetalJavaLangAccess -- in jdk.internal.access -- cannot make itself. Its initializer
 * registers the bridge; MetalJavaLangAccess runs it on the first such call, through Class.forName, so nothing
 * reaches this class (or the loader code behind the bridge) before a program asks for it. The built-in loaders
 * ClassLoaders creates are the first callers (ClassLoaderValue, ServicesCatalog).
 *
 * <p>This class used to run System.initPhase3's class-loader half at LAUNCH as well: level 3, the system class
 * loader, the main thread's context loader, level 4. It was right about the answers and wrong about the cost --
 * the stock loader stack's reachable closure (+567 classes) went in front of every program. The system loader is
 * now installed on first use (ClassLoader.getSystemClassLoader), and so is the main thread's context loader
 * (Thread.getContextClassLoader).
 */
final class MetalSystemLoader
{
    static
    {
        MetalJavaLangAccess.setBridge(new Bridge());
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
