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
    static final int ACC_PUBLIC    = 0x0001;
    static final int ACC_PRIVATE   = 0x0002;
    static final int ACC_PROTECTED = 0x0004;

    boolean override;

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
     * from the caller whose frame return-address is {@code callerPc} — unless {@code setAccessible(true)} was
     * called. Rules: public → always; same declaring class → always; private → only the declaring class;
     * protected → same package or a subclass of the declaring class; package-private → same package. Throws
     * {@code IllegalAccessException} otherwise.
     */
    protected final void checkAccess(Class<?> declaringClass, int mods, long callerPc) throws IllegalAccessException
    {
        if (override || (mods & ACC_PUBLIC) != 0)
        {
            return;
        }
        Object c = callerClass0(callerPc);
        Class<?> caller = (Class<?>) c;
        if (caller == declaringClass)
        {
            return;                                        // same class sees all of its own members
        }
        if ((mods & ACC_PRIVATE) != 0)
        {
            throw denied(caller, declaringClass, "private");
        }
        if ((mods & ACC_PROTECTED) != 0)
        {
            if (caller != null && (samePackage(caller, declaringClass) || declaringClass.isAssignableFrom(caller)))
            {
                return;
            }
            throw denied(caller, declaringClass, "protected");
        }
        // package-private (no access bit): same package only
        if (caller != null && samePackage(caller, declaringClass))
        {
            return;
        }
        throw denied(caller, declaringClass, "package-private");
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
}
