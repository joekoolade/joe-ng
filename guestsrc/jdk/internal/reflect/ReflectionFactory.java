/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-13
 */
package jdk.internal.reflect;

import java.lang.reflect.Constructor;

/**
 * The sliver of {@code jdk.internal.reflect.ReflectionFactory} that serialization's DESCRIBE path touches.
 *
 * <p>{@code java.io.ObjectStreamClass.<init>} calls
 * {@code getReflectionFactory().newConstructorForSerialization(cl)} for every serializable non-record class
 * it describes -- and describing a class is all that {@code ObjectStreamClass.lookup} does, which is what
 * {@code org.junit.platform.engine.UniqueId}'s initializer needs. Without this the call trapped as a
 * DENYLIST TRAP (a real one: {@code jdk/internal/reflect/} is denied as a prefix), so the denial is narrowed
 * to this class alone and the rest of the package stays denied.
 *
 * <p>The rest of the real ReflectionFactory is the bytecode-generating accessor machinery, which this VM
 * deliberately does not carry.
 */
public final class ReflectionFactory
{
    private static final ReflectionFactory SOLE = new ReflectionFactory();

    private ReflectionFactory()
    {
    }

    public static ReflectionFactory getReflectionFactory()
    {
        return SOLE;
    }

    /**
     * Stock's: {@code mask} as the {@link java.lang.reflect.AccessFlag}s valid at {@code location}, read against
     * {@code classFile}'s format version where it has a release one, so a bit means what it meant then.
     */
    public final java.util.Set<java.lang.reflect.AccessFlag> parseAccessFlags(int mask,
            java.lang.reflect.AccessFlag.Location location, Class<?> classFile)
    {
        var cffv = classFileFormatVersion(classFile);
        return cffv == null ? java.lang.reflect.AccessFlag.maskToAccessFlags(mask, location)
                : java.lang.reflect.AccessFlag.maskToAccessFlags(mask, location, cffv);
    }

    /**
     * Stock's, less its {@code assert VM.isSupportedClassFileVersion}: an assert would give this class an
     * initializer for {@code $assertionsDisabled}, and a VM-loaded class is a supported version by construction.
     * {@code ClassFile.JAVA_12_VERSION} and {@code JAVA_1_VERSION} are compile-time constants, inlined by javac.
     */
    private java.lang.reflect.ClassFileFormatVersion classFileFormatVersion(Class<?> cl)
    {
        int raw = jdk.internal.access.SharedSecrets.getJavaLangAccess().classFileVersion(cl);
        int major = raw & 0xFFFF;
        int minor = raw >>> Character.SIZE;
        if (major >= java.lang.classfile.ClassFile.JAVA_12_VERSION)
        {
            if (minor == 0)
            {
                return java.lang.reflect.ClassFileFormatVersion.fromMajor(raw);
            }
            return null;
        }
        else if (major == java.lang.classfile.ClassFile.JAVA_1_VERSION)
        {
            return minor < 3 ? java.lang.reflect.ClassFileFormatVersion.RELEASE_0
                    : java.lang.reflect.ClassFileFormatVersion.RELEASE_1;
        }
        return java.lang.reflect.ClassFileFormatVersion.fromMajor(major);
    }

    /**
     * The no-arg constructor of the first non-serializable superclass, used to build an instance during
     * DESERIALIZATION without running the serializable class's own constructor.
     *
     * <p>Answers {@code null}, and that is a STOCK-LEGAL answer rather than a stub's guess: the caller's own
     * contract is "or null if none found" ({@code ObjectStreamClass.getSerializableConstructor}), so
     * {@code ObjectStreamClass} already handles it -- it stores the field and only dereferences it when
     * something actually DESERIALIZES.
     *
     * <p>So DESCRIBING a class works, which is what is reached here, and deserializing would fail at the
     * point it tries to use this -- the honest place for it, rather than here where nothing is wrong yet.
     * Manufacturing a constructor that skipped the right superclass initializer would be far worse: an object
     * that looks built and is not.
     */
    public Constructor<?> newConstructorForSerialization(Class<?> cl)
    {
        return null;
    }

    /**
     * A fresh {@code Method} object for the same method -- what stock's copy is: same member, its own
     * {@code accessible} flag. Stock {@code TypeVariableImpl.getGenericDeclaration} hands one out so a caller
     * cannot flip the original's access. Made by an EXACT re-lookup (name and parameter types), since this
     * overlay cannot reach Method's package-private constructor; a bridge method sharing those parameter
     * types would be ambiguous, but a bridge declares no type variables, so it never reaches here.
     */
    public java.lang.reflect.Method copyMethod(java.lang.reflect.Method arg)
    {
        try
        {
            return arg.getDeclaringClass().getDeclaredMethod(arg.getName(), arg.getParameterTypes());
        }
        catch (NoSuchMethodException e)
        {
            throw new InternalError(e);
        }
    }

    /** As {@link #copyMethod}, for a constructor. */
    public <T> Constructor<T> copyConstructor(Constructor<T> arg)
    {
        try
        {
            return arg.getDeclaringClass().getDeclaredConstructor(arg.getParameterTypes());
        }
        catch (NoSuchMethodException e)
        {
            throw new InternalError(e);
        }
    }
}
