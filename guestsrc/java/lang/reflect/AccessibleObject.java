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

import java.lang.annotation.Annotation;

/**
 * Base of {@code Field}/{@code Method}/{@code Constructor} — the {@code accessible} override flag that
 * {@code setAccessible(true)} sets to bypass member-access enforcement, plus the enforcement itself
 * ({@link #checkAccess}). Java's protection on joe-ng is language-level (verification + these checks), not
 * hardware rings, so reflective get/set/invoke MUST honour member access or the boundary is void.
 */
public class AccessibleObject implements AnnotatedElement
{
    static
    {
        // joe-ng: stock's registration, which this overlay had dropped -- so SharedSecrets.getJavaLangReflectAccess()
        // answered null, and MethodHandleProxies.newProxyClass (JLRA.getExecutableSharedParameterTypes) and
        // Reflection.isTrustedFinalField both NPE'd on it (InvokeGapProbe). ReflectAccess is the JDK 26 source.
        jdk.internal.access.SharedSecrets.setJavaLangReflectAccess(new ReflectAccess());
    }

    static final int ACC_PUBLIC    = 0x0001;
    static final int ACC_PRIVATE   = 0x0002;
    static final int ACC_PROTECTED = 0x0004;

    boolean override;

    /** Stock's shared instance, which {@code accessFlags()} on every member parses through. */
    static final jdk.internal.reflect.ReflectionFactory reflectionFactory =
            jdk.internal.reflect.ReflectionFactory.getReflectionFactory();

    public void setAccessible(boolean flag)
    {
        this.override = flag;
    }

    public boolean isAccessible()
    {
        return override;
    }

    /**
     * The {@code AnnotatedElement} readers, with stock's base bodies. Every concrete subclass -- {@link Field}
     * and, through {@link Executable}, {@link Method} and {@link Constructor} -- overrides each one from its
     * own registry state, so the {@code AssertionError} bodies are stock's "unreachable" marker, not a gap.
     *
     * <p>{@code <T extends Annotation>} is not decoration -- the BOUND is what erases the return to
     * {@code Ljava/lang/annotation/Annotation;}, the descriptor stock callers reference. An unbounded
     * {@code <T>} erases to {@code Ljava/lang/Object;} and is a DIFFERENT METHOD that resolves nowhere.
     */
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass)
    {
        throw new AssertionError("All subclasses should override this method");
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass)
    {
        return AnnotatedElement.super.isAnnotationPresent(annotationClass);
    }

    public Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    public <T extends Annotation> T getDeclaredAnnotation(Class<T> annotationClass)
    {
        // Only annotations on classes are inherited, for all other objects getDeclaredAnnotation is the same
        // as getAnnotation.
        return getAnnotation(annotationClass);
    }

    public Annotation[] getDeclaredAnnotations()
    {
        throw new AssertionError("All subclasses should override this method");
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.classAtPc}): Class of the method containing frame PC {@code pc}. */
    static native Object callerClass0(long pc);

    /**
     * Enforce Java member access for a member with modifiers {@code mods} declared by {@code declaringClass},
     * from the caller whose frame return-address is {@code callerPc}, on an object of class {@code targetClass}
     * (null for a static member) -- unless {@code setAccessible(true)} was called. Throws
     * {@code IllegalAccessException} when {@link #verify} refuses.
     *
     * <p>The fast path is a public member of a public class, which needs no caller: everything else asks who is
     * calling.
     */
    protected final void checkAccess(Class<?> declaringClass, int mods, long callerPc, Class<?> targetClass)
            throws IllegalAccessException
    {
        if (override || ((mods & ACC_PUBLIC) != 0 && classIsPublic(declaringClass)))
        {
            return;
        }
        Class<?> caller = (Class<?>) callerClass0(callerPc);
        if (!verify(caller, declaringClass, targetClass, mods))
        {
            throw denied(caller, declaringClass, (mods & ACC_PRIVATE) != 0 ? "private"
                    : (mods & ACC_PROTECTED) != 0 ? "protected" : (mods & ACC_PUBLIC) != 0 ? "public"
                    : "package-private");
        }
    }

    /**
     * Whether a class is PUBLIC for access purposes. Stock reads the classfile's own {@code access_flags}, where
     * javac writes {@code ACC_PUBLIC} for a public OR protected nested class and nothing for a private or
     * package-private one; {@code getModifiers} answers from {@code InnerClasses}, so public-or-protected there is
     * the same predicate.
     */
    private static boolean classIsPublic(Class<?> c)
    {
        return (c.getModifiers() & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }

    /**
     * {@link #verify} for {@code java.lang.Class.newInstance}, which must check against ITS caller as stock's
     * {@code Reflection.ensureMemberAccess} does. Overlay-only, like {@code Constructor.resolve}; it answers a
     * question about the classes it is given and grants nothing.
     */
    public static boolean verifyMemberAccess(Class<?> caller, Class<?> memberClass, Class<?> targetClass, int mods)
    {
        if ((mods & ACC_PUBLIC) != 0 && classIsPublic(memberClass))
        {
            return true;
        }
        return verify(caller, memberClass, targetClass, mods);
    }

    /**
     * Stock's {@code Reflection.verifyMemberAccess}, without the module step (every class here is in the unnamed
     * module, which stock's module check passes):
     * <ul>
     *   <li>the declaring class itself must be accessible -- public, or in the caller's package;</li>
     *   <li>a public member is then accessible;</li>
     *   <li>a private member only to a NESTMATE of its declaring class (the declaring class itself included);</li>
     *   <li>a protected member to a subclass, or within the package;</li>
     *   <li>a package-private member within the package;</li>
     *   <li>and a protected INSTANCE member reached from a subclass in another package only on an object of the
     *       caller's own class or a subclass of it ({@code targetClass}).</li>
     * </ul>
     */
    private static boolean verify(Class<?> caller, Class<?> declaringClass, Class<?> targetClass, int mods)
    {
        if (caller == declaringClass)
        {
            return true;
        }
        if (caller == null)
        {
            return false;
        }
        boolean samePkg = samePackage(caller, declaringClass);
        if (!classIsPublic(declaringClass) && !samePkg)
        {
            return false;
        }
        if ((mods & ACC_PUBLIC) != 0)
        {
            return true;
        }
        if ((mods & ACC_PRIVATE) != 0)
        {
            return caller.isNestmateOf(declaringClass);
        }
        boolean ok = samePkg;
        if (!ok && (mods & ACC_PROTECTED) != 0 && declaringClass.isAssignableFrom(caller))
        {
            ok = true;
        }
        if (!ok)
        {
            return false;
        }
        if (targetClass != null && (mods & ACC_PROTECTED) != 0 && targetClass != caller && !samePkg)
        {
            return caller.isAssignableFrom(targetClass);
        }
        return true;
    }

    /**
     * Stock's: can the CALLER access this member on {@code obj}? An instance field or method needs an instance
     * of its declaring class and a static member or constructor needs null, else
     * {@code IllegalArgumentException}; then true if {@code setAccessible(true)} was called, otherwise the
     * access rules decide.
     */
    public final boolean canAccess(Object obj)
    {
        long callerPc = magic.Magic.readLR();
        if (!(this instanceof Member m))
        {
            return override;
        }
        Class<?> declaringClass = m.getDeclaringClass();
        int modifiers = m.getModifiers();
        if (!Modifier.isStatic(modifiers) && (this instanceof Method || this instanceof Field))
        {
            if (obj == null)
            {
                throw new IllegalArgumentException("null object for " + this);
            }
            if (!declaringClass.isAssignableFrom(obj.getClass()))
            {
                throw new IllegalArgumentException("object is not an instance of " + declaringClass.getName());
            }
        }
        else if (obj != null)
        {
            throw new IllegalArgumentException("non-null object for " + this);
        }
        if (override)
        {
            return true;
        }
        Class<?> targetClass;
        if (this instanceof Constructor)
        {
            targetClass = declaringClass;
        }
        else
        {
            targetClass = Modifier.isStatic(modifiers) ? null : obj.getClass();
        }
        return verify((Class<?>) callerClass0(callerPc), declaringClass, targetClass, modifiers);
    }

    /**
     * Stock's: {@code setAccessible(true)} that answers false instead of throwing. It always succeeds here:
     * stock refuses only where a NAMED module has not opened the package, and every class on this VM is in the
     * unnamed module ({@code Class.getModule}), which stock's own rule lets through.
     */
    public final boolean trySetAccessible()
    {
        override = true;
        return true;
    }

    /** Stock's: {@link #setAccessible(boolean)} on each element, in order. */
    public static void setAccessible(AccessibleObject[] array, boolean flag)
    {
        for (AccessibleObject ao : array)
        {
            ao.setAccessible(flag);
        }
    }

    /** True if {@code a} and {@code b} are declared in the same package (by binary-name package prefix). */
    private static boolean samePackage(Class<?> a, Class<?> b)
    {
        return a.getPackageName().equals(b.getPackageName());
    }

    private static IllegalAccessException denied(Class<?> caller, Class<?> declaring, String kind)
    {
        String who = (caller == null) ? "<unknown>" : caller.getName();
        return new IllegalAccessException("Class " + who + " can not access a " + kind
                + " member of class " + declaring.getName());
    }

    /** Stock's: the object this one was copied from, or null for an original (ReflectAccess.getRoot). */
    AccessibleObject getRoot()
    {
        return null;
    }
}
