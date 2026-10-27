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
package jdk.internal.access;

import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

import jdk.internal.loader.NativeLibraries;

/**
 * The package-private java.lang members MetalJavaLangAccess forwards to, which a class outside java.lang cannot
 * call. Stock's JavaLangAccess is an anonymous class INSIDE java.lang.System for exactly this reason; joe-ng's
 * lives in this package, so java.lang hands it an implementation of this narrower interface instead
 * (java.lang.MetalSystemLoader registers it). jdk.internal.access is not exported, so the interface is not part of
 * any public surface. Each method's body over there is stock System$2's.
 */
public interface MetalLangBridge
{
    ConcurrentHashMap<?, ?> createOrGetClassLoaderValueMap(ClassLoader cl);

    Class<?> defineClass(ClassLoader loader, String name, byte[] b, ProtectionDomain pd, String source);

    Package definePackage(ClassLoader cl, String name, Module module);

    NativeLibraries nativeLibrariesFor(ClassLoader loader);

    String getLoaderNameID(ClassLoader loader);

    Class<?> findBootstrapClassOrNull(String name);
}
