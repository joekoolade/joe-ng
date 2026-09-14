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
public abstract class Executable extends AccessibleObject
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
}
