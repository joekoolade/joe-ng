/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-14
 */
package java.lang.reflect;

import java.lang.annotation.Annotation;

/**
 * {@code java.lang.reflect.Executable} for joe-ng: the shared supertype of {@link Method} and
 * {@link Constructor}, which is what stock declares and what this overlay had been missing.
 *
 * <p>WHY IT EXISTS AT ALL, since both subclasses already carried every member it declares: a caller with an
 * {@code Executable}-typed reference emits {@code invokevirtual java/lang/reflect/Executable.<m>}, and with no
 * such class the receiver's chain (Constructor -> AccessibleObject) does not contain it. JUnit's
 * {@code ExtensionUtils.registerExtensionsFromExecutableParameters} is exactly that shape and reported
 * {@code VIRTUALRESOLVE FAILED java/lang/reflect/Constructor.getParameters()} -- the late-virtual tier naming
 * the RECEIVER's class, not the one in the descriptor.
 *
 * <p>NOT TAKEN FROM THE JDK 26 SOURCE, and that is a stated exception to the standing overlay rule rather than
 * a shortcut. Stock {@code Executable} is 833 lines built on {@code sun.reflect.generics.*} (a generic
 * signature parser and type-factory) and {@code sun.reflect.annotation.*} (the annotation parser and proxy
 * runtime) -- both denied here, and both far larger than what this VM can execute. The same reasoning already
 * recorded for the {@code ServiceLoader} overlay applies: the stock implementation routes through subsystems
 * this VM deliberately does not carry, so no faithful copy could work whatever its shape.
 *
 * <p>The four members below are ABSTRACT rather than reimplemented because {@link Method} and
 * {@link Constructor} already answer them from their own registry state; this class only gives callers a type
 * to dispatch through.
 */
public abstract class Executable extends AccessibleObject implements Member
{
    Executable()
    {
    }

    public abstract Class<?> getDeclaringClass();

    public abstract String getName();

    public abstract int getModifiers();

    public abstract int getParameterCount();

    /**
     * This executable's slot in the VM's METHOD REGISTRY, which is the key every descriptor- and
     * annotation-reading native takes. Package-private and abstract because the two subclasses each already
     * hold it; hoisting the ACCESSOR rather than the field keeps their constructors untouched.
     */
    abstract int registryIndex();

    /**
     * The declared parameter types, from the registry's copy of the DESCRIPTOR.
     *
     * <p>Not answerable from the {@code paramChars} both subclasses cache -- those keep only the first
     * character of each parameter, so every reference type looks alike. {@link Method} overrides this with the
     * identical body against its own native; the copy here is what makes a {@link Constructor} answer, which
     * it never could before. An ARRAY parameter resolves to null rather than to {@code Object.class}: visibly
     * wrong at the caller, where a plausible answer would be quietly wrong.
     */
    public Class<?>[] getParameterTypes()
    {
        Object o = paramTypes0(registryIndex());
        if (o == null)
        {
            return new Class<?>[0];
        }
        return (Class<?>[]) o;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.methodParamTypes}): registry index -> Class[]. */
    private static native Object paramTypes0(int rgIndex);

    /**
     * {@code "public com.x.Foo(int)"} -- modifiers, declaring class, name, parameter types.
     *
     * <p>The GENERIC part of the name is aspirational here, exactly as on {@link Method#toGenericString}: with
     * no {@code Signature} attribute read, every type prints as its ERASURE. {@link Method} overrides this to
     * put its return type in front; a constructor has none, which is the whole difference.
     */
    public String toGenericString()
    {
        int m = getModifiers();
        StringBuilder b = new StringBuilder();
        if ((m & 0x0001) != 0)
        {
            b.append("public ");
        }
        if ((m & 0x0002) != 0)
        {
            b.append("private ");
        }
        if ((m & 0x0004) != 0)
        {
            b.append("protected ");
        }
        if ((m & 0x0008) != 0)
        {
            b.append("static ");
        }
        if ((m & 0x0010) != 0)
        {
            b.append("final ");
        }
        b.append(getName());
        b.append("(");
        Class<?>[] ps = getParameterTypes();
        int i = 0;
        while (i < ps.length)
        {
            if (i > 0)
            {
                b.append(",");
            }
            b.append(ps[i] == null ? "?" : ps[i].getName());
            i += 1;
        }
        b.append(")");
        return b.toString();
    }

    /**
     * The parameters as {@link Parameter} objects, built on demand from the count.
     *
     * <p>A FRESH ARRAY EACH CALL, as stock: the returned array is the caller's to mutate, and JUnit streams
     * over it. Stock caches and clones; there is nothing here worth caching, since a Parameter carries only
     * its executable and index.
     */
    public Parameter[] getParameters()
    {
        int n = getParameterCount();
        Parameter[] out = new Parameter[n];
        int i = 0;
        while (i < n)
        {
            out[i] = new Parameter(this, i);
            i += 1;
        }
        return out;
    }

    /**
     * Per-parameter annotations: one row per parameter, each row EMPTY.
     *
     * <p>STATED LIMIT, NOT A SILENT ONE. The rows come back empty because this VM does not yet read a method's
     * {@code RuntimeVisibleParameterAnnotations} attribute -- the class- and method-level walks exist
     * ({@code classAnnotationsAll}, {@code methodAnnotationsAll}) and the parameter-level one is their
     * sibling, but it is not written. So a parameter that really carries an annotation reports none.
     *
     * <p>What that costs, precisely: JUnit reads this to find {@code @ExtendWith} on a parameter, so such an
     * extension is not registered. A method whose parameters carry no annotations -- every one reached so far
     * -- gets the correct answer. Returning an empty ROW per parameter rather than a null array is what the
     * callers require: {@code getEffectiveAnnotatedParameter} indexes the result unguarded.
     */
    public Annotation[][] getParameterAnnotations()
    {
        int n = getParameterCount();
        Annotation[][] out = new Annotation[n][];
        int i = 0;
        while (i < n)
        {
            out[i] = new Annotation[0];
            i += 1;
        }
        return out;
    }

    /**
     * True if this method or constructor carries the given annotation. Marker level: presence only, no element
     * values.
     *
     * <p>This and the four annotation readers below live HERE, not on {@link Method}, because the registry
     * holds a constructor's {@code <init>} like any other method: keyed on {@link #registryIndex}, they answer
     * for a {@link Constructor} too, which stock does and which this VM never did -- a constructor's
     * annotations fell through to the base class.
     *
     * <p>Only annotations declared {@code @Retention(RUNTIME)} are visible -- javac writes anything else into
     * RuntimeINVISIBLEAnnotations, i.e. an annotation without RUNTIME retention is not merely unreadable here,
     * it is absent from the classfile.
     */
    public boolean isAnnotationPresent(Class<? extends java.lang.annotation.Annotation> anno)
    {
        if (anno == null)
        {
            return false;
        }
        return annoPresent0(registryIndex(), descriptorOf(anno)) != 0;
    }

    /** "org.junit.jupiter.api.Test" -> the field descriptor "Lorg/junit/jupiter/api/Test;" the classfile holds. */
    private static byte[] descriptorOf(Class<?> anno)
    {
        String n = anno.getName();
        byte[] out = new byte[n.length() + 2];
        out[0] = (byte) 'L';
        int i = 0;
        while (i < n.length())
        {
            char c = n.charAt(i);
            out[i + 1] = (byte) (c == '.' ? '/' : c);
            i += 1;
        }
        out[n.length() + 1] = (byte) ';';
        return out;
    }

    /**
     * The annotation INSTANCE, or null when absent. The object the VM returns implements the annotation
     * interface: its Type carries an itable for that interface whose slots are two-instruction thunks reading
     * the element values out of the instance (see {@code Loader.annoTibFor}). So {@code getAnnotation(X.class)
     * .value()} is an ordinary interface dispatch, not a special case.
     *
     * <p>Element values that need a class RESOLVED -- a {@code Class} element, an enum constant, a nested
     * annotation -- read null in this increment, because resolving one mid-walk would re-parse a blob and
     * clobber the classfile cursor the walk is using.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(registryIndex(), descriptorOf(anno));
    }

    /**
     * The annotation DECLARED DIRECTLY on this method or constructor, or null.
     *
     * <p>IDENTICAL to {@link #getAnnotation}, and for a method that is EXACT rather than an approximation --
     * unlike the {@code Class} pair, where the two differ by {@code @Inherited}. JLS 9.6.4.3 gives
     * {@code @Inherited} effect on class declarations ALONE, and an overriding method does not inherit the
     * annotations of the method it overrides, so for a method "declared" and "present" are the same set.
     * Stock agrees by construction: {@code Executable.getAnnotation} reads {@code declaredAnnotations()}.
     *
     * <p>THE BOUND IS LOAD-BEARING, for the third time in this family. {@code <T extends Annotation>} erases
     * the return to {@code Ljava/lang/annotation/Annotation;} -- the descriptor JUnit's
     * {@code AnnotationUtils.findAnnotation} references. Declared as a plain {@code <T>} it would erase to
     * {@code Ljava/lang/Object;}: a DIFFERENT METHOD that resolves nowhere and traps.
     */
    @SuppressWarnings("unchecked")
    public <T extends java.lang.annotation.Annotation> T getDeclaredAnnotation(Class<T> anno)
    {
        if (anno == null)
        {
            return null;
        }
        return (T) annoGet0(registryIndex(), descriptorOf(anno));
    }

    /**
     * Every annotation DECLARED on this method or constructor. Never null; empty when there are none.
     *
     * <p>{@code findAnnotation} walks this to find META-ANNOTATIONS -- an annotation carried by another
     * annotation -- so an empty answer would not fail, it would silently report "not annotated". That is the
     * shape this VM keeps getting bitten by, which is why it enumerates rather than answering empty.
     *
     * <p>A fresh array each call, because stock specifies the caller may modify the returned one. An
     * annotation whose interface is not loaded is omitted and named on the console: there is no itable to
     * give such an instance, and a null element reads as "present but broken".
     */
    public java.lang.annotation.Annotation[] getDeclaredAnnotations()
    {
        java.lang.annotation.Annotation[] a = annoAll0(registryIndex());
        return a == null ? new java.lang.annotation.Annotation[0] : a;
    }

    /**
     * As {@link #getDeclaredAnnotations}. For a method or constructor the two coincide EXACTLY, for the reason given on
     * {@link #getDeclaredAnnotation}: {@code @Inherited} does not reach method declarations, so there is no
     * indirectly-present annotation for this to add. No divergence from stock here.
     */
    public java.lang.annotation.Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VMNatives.methodAnnoAll}): registry index -> Annotation[]. */
    private static native java.lang.annotation.Annotation[] annoAll0(int rgIndex);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.annoGet}): registry index + descriptor -> instance. */
    private static native Object annoGet0(int rgIndex, byte[] descriptor);

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.annoPresent}): registry index + descriptor -> 0/1. */
    private static native int annoPresent0(int rgIndex, byte[] descriptor);
}
