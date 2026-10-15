/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-13
 */
package java.lang.reflect;

import magic.Magic;

/**
 * {@code java.lang.reflect.Constructor} for joe-ng: reflective instantiation of a loaded class. A Constructor
 * carries its declaring {@link Class}, the method-registry index of the matching {@code <init>}, access flags,
 * compiled-buffer address, and its descriptor's parameter type-chars (resolved once at construction via the
 * shared {@link #methodInfo0}). {@link #newInstance} allocates a fresh zeroed instance (header + TIB via
 * {@link #allocInstance0}), marshals the {@code Object[]} args into a register buffer (the new object in slot 0
 * as the {@code <init>} receiver, then one slot per parameter — primitives unboxed, references reinterpreted to
 * their address), runs {@code <init>} via {@link Magic#callN}, and returns the object. Overload resolution is by
 * arity only for now (no parameter-type matching). Access enforcement mirrors {@link Method}.
 */
public final class Constructor<T> extends Executable
{
    private final Class<T> clazz;
    private final int access;
    private final long buf;
    private final int paramCount;
    private final byte[] paramChars;   // first descriptor char of each parameter ('I'/'J'/'Z'/.../'L'/'[')
    private final int rgIndex;         // this <init>'s slot in the VM's method registry (descriptor lookup key)

    private Constructor(Class<T> clazz, int access, long buf, int paramCount, byte[] paramChars, int rgIndex)
    {
        this.rgIndex = rgIndex;
        this.clazz = clazz;
        this.access = access;
        this.buf = buf;
        this.paramCount = paramCount;
        this.paramChars = paramChars;
    }

    /** Resolve the {@code <init>} of {@code c} taking {@code paramCount} parameters (arity match, first found).
     *  Public so {@code java.lang.Class.getDeclaredConstructor} (a different package) can build it — an
     *  overlay-only entry point, not part of the stock {@code Constructor} API. */
    public static <T> Constructor<T> resolve(Class<T> c, int paramCount) throws NoSuchMethodException
    {
        int idx = ctorResolve0(c, paramCount);
        if (idx < 0)
        {
            throw new NoSuchMethodException("<init>");
        }
        byte[] pchars = paramCharScratch();
        long[] out = new long[3];                          // {buffer, access, returnChar} — returnChar is 'V'
        int n = methodInfo0(idx, pchars, out);
        return new Constructor<T>(c, (int) out[1], out[0], n, paramChars(pchars, n), idx);
    }

    /**
     * Resolve the {@code <init>} of {@code c} with exactly this DESCRIPTOR. Arity alone cannot tell overloads of
     * the same length apart -- {@code (String)} and {@code (int)} -- so {@link #resolve(Class,int)} answers the
     * first for both. Overlay-only entry point, like that one.
     */
    public static <T> Constructor<T> resolve(Class<T> c, String desc) throws NoSuchMethodException
    {
        int idx = ctorResolveDesc0(c, jdk.internal.misc.MetalNames.utf8(desc));
        if (idx < 0)
        {
            throw new NoSuchMethodException("<init>" + desc);
        }
        byte[] pchars = paramCharScratch();
        long[] out = new long[3];                          // {buffer, access, returnChar} — returnChar is 'V'
        int n = methodInfo0(idx, pchars, out);
        return new Constructor<T>(c, (int) out[1], out[0], n, paramChars(pchars, n), idx);
    }

    private static native int ctorResolve0(Class c, int paramCount);
    private static native int ctorResolveDesc0(Class c, byte[] desc);
    private static native int methodInfo0(int rgIndex, byte[] paramChars, long[] out);
    private static native Object allocInstance0(Class c);

    public int getModifiers()
    {
        return access;
    }

    public Class<T> getDeclaringClass()
    {
        return clazz;
    }

    /** The binary name of the declaring class, which is what stock {@code Constructor.getName()} answers --
     *  a constructor has no name of its own. Declared here because {@link Executable} makes it abstract. */
    public String getName()
    {
        return clazz.getName();
    }

    /** {@inheritDoc} -- the registry slot resolved by {@link #ctorResolve0}, which is what
     *  {@link Executable#getParameterTypes} reads the descriptor through. */
    int registryIndex()
    {
        return rgIndex;
    }

    public int getParameterCount()
    {
        return paramCount;
    }

    /** Stock's: the type variables this constructor declares, in order; empty when it declares none. */
    @SuppressWarnings("unchecked")
    public TypeVariable<Constructor<T>>[] getTypeParameters()
    {
        String sig = genericSignature();
        if (sig != null)
        {
            return (TypeVariable<Constructor<T>>[]) genericInfo(sig).getTypeParameters();
        }
        return (TypeVariable<Constructor<T>>[]) new TypeVariable<?>[0];
    }

    /** Stock's factory and scope: this constructor's own type variables first, then its class's. */
    sun.reflect.generics.repository.ConstructorRepository genericInfo(String signature)
    {
        return sun.reflect.generics.repository.ConstructorRepository.make(signature,
                sun.reflect.generics.factory.CoreReflectionFactory.make(this,
                        sun.reflect.generics.scope.ConstructorScope.make(this)));
    }

    /** Stock's: same declaring class and parameter types (see {@link Method#equals} for why it is needed). */
    public boolean equals(Object obj)
    {
        if (obj instanceof Constructor<?> other)
        {
            if (getDeclaringClass() == other.getDeclaringClass())
            {
                return java.util.Arrays.equals(getParameterTypes(), other.getParameterTypes());
            }
        }
        return false;
    }

    /** Stock's. */
    public int hashCode()
    {
        return getDeclaringClass().getName().hashCode();
    }

    /** Stock's: {@code "public com.x.Foo(int) throws java.io.IOException"}, erased. */
    public String toString()
    {
        return sharedToString(Modifier.constructorModifiers(), false, getParameterTypes(), getExceptionTypes());
    }

    @Override
    void specificToStringHeader(StringBuilder sb)
    {
        sb.append(getDeclaringClass().getTypeName());
    }

    /** Stock's: as {@link #toString}, with the generic types, type parameters and varargs. */
    @Override
    public String toGenericString()
    {
        return sharedToGenericString(Modifier.constructorModifiers(), false);
    }

    @Override
    void specificToGenericStringHeader(StringBuilder sb)
    {
        specificToStringHeader(sb);
    }

    /** Allocate a fresh instance of the declaring class and run this {@code <init>} with {@code args}. */
    public T newInstance(Object... args) throws IllegalAccessException, InvocationTargetException,
                                                InstantiationException
    {
        checkAccess(clazz, access, Magic.readLR());
        checkArgCount(args, paramCount);
        Object obj = allocInstance0(clazz);
        if (obj == null)
        {
            throw new InstantiationException();
        }
        int total = 1 + paramCount;
        long[] slots = argBuffer(total);
        putArg(slots, total, 0, Magic.addrOf(obj));        // receiver in slot 0 (<init> is an instance method)
        Class<?>[] pt = getParameterTypes();
        int k = 0;
        while (k < paramCount)
        {
            putArg(slots, total, 1 + k, convertArg(pt[k], args[k]));
            k += 1;
        }
        try
        {
            Magic.callN(buf, Magic.addrOf(slots) + 24L);  // <init> returns void
        }
        catch (Throwable t)
        {
            throw new InvocationTargetException(t);         // stock: whatever the constructor throws arrives wrapped
        }
        return (T) obj;
    }

    /** ACC_SYNTHETIC / ACC_VARARGS -- see {@link Method#isSynthetic()}; both are bits of this member's own
     *  access_flags, which this Constructor already carries. */
    public boolean isSynthetic()
    {
        return (access & 0x1000) != 0;
    }

    public boolean isVarArgs()
    {
        return (access & 0x0080) != 0;
    }
}
