package java.lang.reflect;

import java.lang.annotation.Annotation;

/**
 * {@code java.lang.reflect.Parameter} for joe-ng: one parameter of a {@link Method} or {@link Constructor},
 * identified by its declaring executable and its index.
 *
 * <p>It carries NO state of its own beyond those two, which is what makes it cheap to mint per call: every
 * question is answered by asking the executable. Stock caches an array on the Executable; there is nothing
 * here worth caching.
 *
 * <p>IT IMPLEMENTS {@link AnnotatedElement}, and that is load-bearing rather than decorative. JUnit's
 * {@code AnnotationUtils.getEffectiveAnnotatedParameter} hands a Parameter to
 * {@code findRepeatableAnnotations(AnnotatedElement, Class)}, so the three element methods are reached by
 * INTERFACE dispatch and must match the interface's descriptors exactly -- the same bound that has bitten
 * this overlay three times (see Method.getDeclaredAnnotation).
 */
public class Parameter implements AnnotatedElement
{
    private final Executable executable;
    private final int index;

    Parameter(Executable executable, int index)
    {
        this.executable = executable;
        this.index = index;
    }

    public Executable getDeclaringExecutable()
    {
        return executable;
    }

    /**
     * {@code argN}, which is what STOCK answers for a parameter whose class carries no
     * {@code MethodParameters} attribute -- i.e. anything not compiled with {@code -parameters}, which is
     * every class this VM loads. So this is exact rather than approximate, and {@link #isNamePresent} says so.
     */
    public String getName()
    {
        return "arg" + index;
    }

    public boolean isNamePresent()
    {
        return false;
    }

    public int getModifiers()
    {
        return 0;
    }

    /**
     * The parameter's declared type, indexed out of the executable's own descriptor.
     *
     * <p>THE MOST-CALLED MEMBER OF THIS CLASS, by a distance: eleven JUnit classes reference it
     * ({@code ParameterResolutionUtils}, {@code TempDirectory}, {@code RepetitionExtension} ...), because a
     * parameter resolver's whole job is to decide whether it can supply THIS type. Null only if the index is
     * out of range or the type did not resolve, which the caller sees rather than a plausible substitute.
     */
    public Class<?> getType()
    {
        Class<?>[] ps = executable.getParameterTypes();
        if (index < 0 || index >= ps.length)
        {
            return null;
        }
        return ps[index];
    }

    /**
     * The ERASURE, i.e. exactly {@link #getType}, and a caller is not misled by it: stock returns a
     * {@code ParameterizedType} only when a {@code Signature} attribute is present, and every caller asks
     * {@code instanceof ParameterizedType} first -- false here, so it takes its raw-type path. The same
     * decision already recorded for {@code Method.getGenericParameterTypes}.
     */
    public java.lang.reflect.Type getParameterizedType()
    {
        return getType();
    }

    /** Empty, for the reason stated on {@link Executable#getParameterAnnotations} -- a row of that array. */
    public Annotation[] getDeclaredAnnotations()
    {
        Annotation[][] all = executable.getParameterAnnotations();
        if (index < 0 || index >= all.length)
        {
            return new Annotation[0];
        }
        return all[index];
    }

    /**
     * The same as {@link #getDeclaredAnnotations}, and EXACTLY so rather than approximately: {@code @Inherited}
     * has effect on CLASS declarations alone (JLS 9.6.4.3), so a parameter has no inherited annotations to
     * differ by. Stock agrees by construction -- its {@code getAnnotations} delegates to the declared path.
     */
    public Annotation[] getAnnotations()
    {
        return getDeclaredAnnotations();
    }

    public <T extends Annotation> T getAnnotation(Class<T> annotationClass)
    {
        Annotation[] a = getDeclaredAnnotations();
        int i = 0;
        while (i < a.length)
        {
            if (a[i].annotationType() == annotationClass)
            {
                return (T) a[i];
            }
            i += 1;
        }
        return null;
    }

    public <T extends Annotation> T getDeclaredAnnotation(Class<T> annotationClass)
    {
        return getAnnotation(annotationClass);
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass)
    {
        return getAnnotation(annotationClass) != null;
    }

    public String toString()
    {
        return getName();
    }
}
