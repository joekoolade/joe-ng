package java.lang.reflect;

/**
 * A JDK-free, minimal {@code java.lang.reflect.Array} overlay (wins by name). Stock {@code Array.newInstance}
 * is a native over the JVM's typed-array creation. On metal we provide the 1-D {@code newInstance(Class, int)}
 * used by the collection/sort machinery ({@code TimSort}/{@code ComparableTimSort} temp arrays,
 * {@code Arrays.copyOf}, {@code AbstractCollection.toArray(T[])}): the VM native allocates an 8-byte-element
 * (reference) array of the given length.
 *
 * <p><b>Untyped:</b> the returned array carries the raw reference-array header, not a {@code [L<component>;}
 * type — so it works for the fill-and-return / temp-work uses (element access is untyped on metal), but a
 * caller that {@code instanceof}-checks the result against a specific {@code T[]} (e.g. some
 * {@code toArray(T[])} tests) will see it fail. Full typed reflective arrays need runtime array-Type
 * construction; deferred.
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
