package java.lang.reflect;

/**
 * A JDK-free, minimal {@code java.lang.reflect.Array} overlay (wins by name). Stock {@code Array.newInstance}
 * is a native over the JVM's typed-array creation. On metal we provide the 1-D {@code newInstance(Class, int)}
 * used by the collection/sort machinery ({@code TimSort}/{@code ComparableTimSort} temp arrays,
 * {@code Arrays.copyOf}, {@code AbstractCollection.toArray(T[])}): the VM native allocates an 8-byte-element
 * (reference) array of the given length, TYPED as {@code [L<component>;} — the VM tags it with the interned
 * per-element array-TIB ({@code Loader.refArrayTib}), the same one {@code new component[]} /
 * {@code instanceof component[]} use, so {@code instanceof}-checking the result (e.g. {@code toArray(T[])})
 * matches. Element access is 8-byte references. Primitive-element arrays fall back to an untyped raw array
 * (the collection/sort callers only pass reference component types).
 */
public final class Array
{
    private Array()
    {
    }

    /** A new length-{@code length} reference array (8-byte elements). {@code componentType} is accepted for
     *  signature compatibility but not used (the result is untyped — see the class note). */
    public static Object newInstance(Class<?> componentType, int length)
    {
        if (length < 0)
        {
            throw new NegativeArraySizeException();
        }
        return newArray0(componentType, length);
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.newReflectArray}): a raw {@code length}-element,
     *  8-byte-per-element reference array. */
    private static native Object newArray0(Class componentType, int length);
}
