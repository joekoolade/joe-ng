/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-01
 */
package java.lang;

/**
 * The bare minimum of a module for joe-ng: UNNAMED modules only, one per class loader as in stock (each loader's
 * constructor makes its own, and the bootstrap loader's is {@link #UNNAMED}).
 *
 * <p>joe-ng has no module layer at all -- the boot image is a flat class directory plus demand-loaded jar
 * entries, with no module graph, no readability edges and no {@code ModuleDescriptor}. Stock library code
 * nonetheless reaches {@link Class#getModule()} on ordinary paths: JUnit's {@code ModuleUtils.getModuleVersion}
 * does {@code getModule().isNamed()} and returns {@code Optional.empty()} when it is false, so an unnamed
 * module answers it correctly rather than approximately -- an unnamed module genuinely HAS no descriptor and
 * no version, which is exactly what joe-ng's flat class space is.
 *
 * <p>{@link #getDescriptor()} therefore returns null, matching the JDK's own contract for an unnamed module
 * ("returns null if this module is an unnamed module"). A caller that reaches it without checking
 * {@link #isNamed()} first is making an unsound assumption and gets an NPE at its own site, which is a better
 * outcome than a fabricated descriptor.
 */
public final class Module implements java.lang.reflect.AnnotatedElement
{
    /** The bootstrap loader's unnamed module: every JDK and VM class's module. */
    static final Module UNNAMED = new Module((ClassLoader) null);

    /** The loader this module is the unnamed module of; null for the bootstrap loader's. */
    private final ClassLoader loader;

    /** Stock's: create the unnamed module for {@code loader} -- what every ClassLoader constructor calls. */
    Module(ClassLoader loader)
    {
        this.loader = loader;
    }

    /** Always false: joe-ng's class space is flat, so nothing is in a named module. */
    public boolean isNamed()
    {
        return false;
    }

    /**
     * Stock's answers for an UNNAMED module, which every module here is: it reads every module and exports and
     * opens every package to everyone (Module.canRead and implIsExportedOrOpen both open with
     * {@code if (!isNamed()) return true}). The package name is null-checked first, as stock does. Reached by
     * the stock reflection and method-handle access checks (jdk.internal.reflect.Reflection,
     * sun.invoke.util.VerifyAccess).
     */
    public boolean canRead(Module other)
    {
        java.util.Objects.requireNonNull(other);
        return true;
    }

    public boolean isExported(String pn)
    {
        java.util.Objects.requireNonNull(pn);
        return true;
    }

    public boolean isExported(String pn, Module other)
    {
        java.util.Objects.requireNonNull(pn);
        java.util.Objects.requireNonNull(other);
        return true;
    }

    public boolean isOpen(String pn)
    {
        java.util.Objects.requireNonNull(pn);
        return true;
    }

    public boolean isOpen(String pn, Module other)
    {
        java.util.Objects.requireNonNull(pn);
        java.util.Objects.requireNonNull(other);
        return true;
    }

    /** Null for an unnamed module, per the JDK contract. */
    public java.lang.module.ModuleDescriptor getDescriptor()
    {
        return null;
    }

    /** Null for an unnamed module, per the JDK contract. */
    public String getName()
    {
        return null;
    }

    /** Stock's: the loader this is the unnamed module of. */
    public ClassLoader getClassLoader()
    {
        return loader;
    }

    /** Null: joe-ng has no module GRAPH either, so there is no layer to report. */
    public ModuleLayer getLayer()
    {
        return null;
    }

    /**
     * Null. Stock specifies exactly this "when invoked on an unnamed module", and every module here is one --
     * the stated answer for this VM, not a stand-in for an annotation reader it lacks.
     */
    @Override
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> annotationClass)
    {
        return null;
    }

    /** Empty, as stock specifies for an unnamed module; a fresh array because the caller may modify it. */
    @Override
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return new java.lang.annotation.Annotation[0];
    }

    /** Empty, as stock specifies for an unnamed module; a fresh array because the caller may modify it. */
    @Override
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        return new java.lang.annotation.Annotation[0];
    }

    /**
     * Stock's resource read for a module. This VM has the one unnamed module, so it is stock's unnamed-module arm:
     * the classpath jar first, then java.base's own resources ({@link jdk.internal.misc.MetalLoaders#javaBaseResource}).
     * {@code sun.util.resources.BreakIteratorResourceBundle} loads the break-iterator rule data through this, and
     * the overlay used to drop it. A leading '/' is not part of a module resource name, as in stock.
     */
    public java.io.InputStream getResourceAsStream(String name) throws java.io.IOException
    {
        if (name.startsWith("/"))
        {
            name = name.substring(1);
        }
        java.io.InputStream in = ClassLoader.getSystemResourceAsStream(name);
        return in != null ? in : jdk.internal.misc.MetalLoaders.javaBaseResource(name);
    }

    /** Stock's form for an unnamed module. */
    @Override
    public String toString()
    {
        return "unnamed module @" + Integer.toHexString(hashCode());
    }
}
