package java.lang.reflect;

import magic.Magic;

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

    /**
     * Length, and reference element get/set, read straight out of the object layout: {@link
     * objectmodel.ObjectModel} puts an array's length at {@code +16} and its elements at {@code +24}. That is
     * the same layout {@code arraylength} and {@code aaload} are lowered against, so this agrees with ordinary
     * bytecode by construction rather than by convention.
     *
     * <p>Bounds are checked here. A reflective accessor that trusted its index would read or WRITE outside the
     * object -- silent heap corruption, which is the failure mode this VM is least able to diagnose.
     */
    public static int getLength(Object array)
    {
        if (array == null)
        {
            throw new NullPointerException();
        }
        return (int) Magic.load64(Magic.addrOf(array) + 16L);
    }

    public static Object get(Object array, int index)
    {
        checkIndex(array, index);
        long e = Magic.load64(Magic.addrOf(array) + 24L + (long) index * 8L);
        return Magic.fromAddr(e);
    }

    public static void set(Object array, int index, Object value)
    {
        checkIndex(array, index);
        Magic.store64(Magic.addrOf(array) + 24L + (long) index * 8L,
                value == null ? 0L : Magic.addrOf(value));
    }

    public static long getLong(Object array, int index)
    {
        checkIndex(array, index);
        return Magic.load64(Magic.addrOf(array) + 24L + (long) index * 8L);
    }

    public static int getInt(Object array, int index)
    {
        checkIndex(array, index);
        return Magic.load32(Magic.addrOf(array) + 24L + (long) index * 8L);
    }

    /** Multi-dimensional {@code newInstance}. Only the 1-D case is real here (see the class note); a deeper
     *  request is REFUSED rather than quietly given one dimension. */
    public static Object newInstance(Class<?> componentType, int... dimensions)
    {
        if (dimensions == null || dimensions.length == 0)
        {
            throw new IllegalArgumentException("dimensions is empty");
        }
        if (dimensions.length > 1)
        {
            throw new UnsupportedOperationException(
                    "joe-ng: reflective multi-dimensional arrays are not supported (" + dimensions.length + "D)");
        }
        return newInstance(componentType, dimensions[0]);
    }

    private static void checkIndex(Object array, int index)
    {
        int n = getLength(array);
        if (index < 0 || index >= n)
        {
            throw new ArrayIndexOutOfBoundsException(index);
        }
    }
}
