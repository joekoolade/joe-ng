/*
 * Copyright (c) 1996, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.reflect;

import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * The {@code Array} class provides static methods to dynamically create and
 * access Java arrays.
 *
 * <p>{@code Array} permits widening conversions to occur during a get or set
 * operation, but throws an {@code IllegalArgumentException} if a narrowing
 * conversion would occur.
 *
 * @author Nakul Saraiya
 * @since 1.1
 *
 * <p>joe-ng overlay, taken from the JDK 26 source (rule 3). Every member is stock; the natives are Java here.
 * Each accessor dispatches on the array's real type with {@code instanceof} and then indexes it as ordinary
 * bytecode would, so the element width, the bounds check and the widening are the compiler's own -- the old
 * overlay read every element as 8 bytes and wrote references unchecked. Creation goes through the VM native
 * {@code newArray0} after stock's checks; a multi-dimensional array is built one level at a time.
 */
public final
class Array {

    /**
     * Constructor.  Class Array is not instantiable.
     */
    private Array() {}

    /**
     * Creates a new array with the specified component type and
     * length.
     * @implSpec
     * Invoking this method is equivalent to creating an array
     * as follows:
     * <blockquote>
     * {@code Array.newInstance(componentType, new int[]{length});}
     * </blockquote>
     *
     * <p>The number of dimensions of the new array must not
     * exceed 255.
     *
     * @param  componentType the {@code Class} object representing the
     *         component type of the new array
     * @param  length the length of the new array
     * @return the new array
     * @throws NullPointerException if the specified
     *         {@code componentType} parameter is null
     * @throws IllegalArgumentException if componentType is {@link
     *         Void#TYPE} or if the number of dimensions of the requested array
     *         instance exceed 255.
     * @throws NegativeArraySizeException if the specified {@code length}
     *         is negative
     */
    public static Object newInstance(Class<?> componentType, int length)
        throws NegativeArraySizeException {
        return newArray(componentType, length);
    }

    /**
     * Creates a new array
     * with the specified component type and dimensions.
     * If {@code componentType}
     * represents a non-array class or interface, the new array
     * has {@code dimensions.length} dimensions and
     * {@code componentType} as its component type. If
     * {@code componentType} represents an array class, the
     * number of dimensions of the new array is equal to the sum
     * of {@code dimensions.length} and the number of
     * dimensions of {@code componentType}. In this case, the
     * component type of the new array is the component type of
     * {@code componentType}.
     *
     * <p>The number of dimensions of the new array must not
     * exceed 255.
     *
     * @param componentType the {@code Class} object representing the component
     * type of the new array
     * @param dimensions an array of {@code int} representing the dimensions of
     * the new array
     * @return the new array
     * @throws    NullPointerException if any of the specified
     * {@code componentType} or {@code dimensions} arguments is null
     * @throws    IllegalArgumentException if the specified {@code dimensions}
     * argument is a zero-dimensional array, if componentType is {@link
     * Void#TYPE}, or if the number of dimensions of the requested array
     * instance exceed 255.
     * @throws    NegativeArraySizeException if any of the components in
     * the specified {@code dimensions} argument is negative.
     */
    public static Object newInstance(Class<?> componentType, int... dimensions)
        throws IllegalArgumentException, NegativeArraySizeException {
        return multiNewArray(componentType, dimensions);
    }

    /**
     * Returns the length of the specified array object, as an {@code int}.
     *
     * @param array the array
     * @return the length of the array
     * @throws NullPointerException if {@code array} is {@code null}
     * @throws IllegalArgumentException if {@code array} is not an array
     */
    @IntrinsicCandidate
    public static int getLength(Object array)
        throws IllegalArgumentException
    {
        if (array instanceof Object[] a)
        {
            return a.length;
        }
        if (array instanceof int[] a)
        {
            return a.length;
        }
        if (array instanceof byte[] a)
        {
            return a.length;
        }
        if (array instanceof char[] a)
        {
            return a.length;
        }
        if (array instanceof long[] a)
        {
            return a.length;
        }
        if (array instanceof double[] a)
        {
            return a.length;
        }
        if (array instanceof float[] a)
        {
            return a.length;
        }
        if (array instanceof short[] a)
        {
            return a.length;
        }
        if (array instanceof boolean[] a)
        {
            return a.length;
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object.  The value is automatically wrapped in an object
     * if it has a primitive type.
     *
     * @param array the array
     * @param index the index
     * @return the (possibly wrapped) value of the indexed component in
     * the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     */
    public static Object get(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof Object[] a)
        {
            return a[index];
        }
        if (array instanceof int[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        if (array instanceof char[] a)
        {
            return a[index];
        }
        if (array instanceof long[] a)
        {
            return a[index];
        }
        if (array instanceof double[] a)
        {
            return a[index];
        }
        if (array instanceof float[] a)
        {
            return a[index];
        }
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof boolean[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code boolean}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static boolean getBoolean(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof boolean[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code byte}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static byte getByte(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code char}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static char getChar(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof char[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code short}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static short getShort(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as an {@code int}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static int getInt(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof int[] a)
        {
            return a[index];
        }
        if (array instanceof char[] a)
        {
            return a[index];
        }
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code long}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static long getLong(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof long[] a)
        {
            return a[index];
        }
        if (array instanceof int[] a)
        {
            return a[index];
        }
        if (array instanceof char[] a)
        {
            return a[index];
        }
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code float}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static float getFloat(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof float[] a)
        {
            return a[index];
        }
        if (array instanceof long[] a)
        {
            return a[index];
        }
        if (array instanceof int[] a)
        {
            return a[index];
        }
        if (array instanceof char[] a)
        {
            return a[index];
        }
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Returns the value of the indexed component in the specified
     * array object, as a {@code double}.
     *
     * @param array the array
     * @param index the index
     * @return the value of the indexed component in the specified array
     * @throws    NullPointerException If the specified object is null
     * @throws    IllegalArgumentException If the specified object is not
     * an array, or if the indexed element cannot be converted to the
     * return type by an identity or widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to the
     * length of the specified array
     * @see Array#get
     */
    public static double getDouble(Object array, int index)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof double[] a)
        {
            return a[index];
        }
        if (array instanceof float[] a)
        {
            return a[index];
        }
        if (array instanceof long[] a)
        {
            return a[index];
        }
        if (array instanceof int[] a)
        {
            return a[index];
        }
        if (array instanceof char[] a)
        {
            return a[index];
        }
        if (array instanceof short[] a)
        {
            return a[index];
        }
        if (array instanceof byte[] a)
        {
            return a[index];
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified new value.  The new value is first
     * automatically unwrapped if the array has a primitive component
     * type.
     * @param array the array
     * @param index the index into the array
     * @param value the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the array component type is primitive and
     * an unwrapping conversion fails
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     */
    public static void set(Object array, int index, Object value)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof Object[] a)
        {
            if (value != null && !a.getClass().getComponentType().isInstance(value))
            {
                throw new IllegalArgumentException("array element type mismatch");
            }
            a[index] = value;
            return;
        }
        if (value instanceof Boolean v)
        {
            setBoolean(array, index, v.booleanValue());
        }
        else if (value instanceof Byte v)
        {
            setByte(array, index, v.byteValue());
        }
        else if (value instanceof Character v)
        {
            setChar(array, index, v.charValue());
        }
        else if (value instanceof Short v)
        {
            setShort(array, index, v.shortValue());
        }
        else if (value instanceof Integer v)
        {
            setInt(array, index, v.intValue());
        }
        else if (value instanceof Long v)
        {
            setLong(array, index, v.longValue());
        }
        else if (value instanceof Float v)
        {
            setFloat(array, index, v.floatValue());
        }
        else if (value instanceof Double v)
        {
            setDouble(array, index, v.doubleValue());
        }
        else
        {
            getLength(array);                      // NPE for null, IAE for a non-array, before the mismatch
            throw new IllegalArgumentException("argument type mismatch");
        }
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code boolean} value.
     * @param array the array
     * @param index the index into the array
     * @param z the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setBoolean(Object array, int index, boolean z)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof boolean[] a)
        {
            a[index] = z;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code byte} value.
     * @param array the array
     * @param index the index into the array
     * @param b the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setByte(Object array, int index, byte b)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof byte[] a)
        {
            a[index] = b;
            return;
        }
        if (array instanceof short[] a)
        {
            a[index] = b;
            return;
        }
        if (array instanceof int[] a)
        {
            a[index] = b;
            return;
        }
        if (array instanceof long[] a)
        {
            a[index] = b;
            return;
        }
        if (array instanceof float[] a)
        {
            a[index] = b;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = b;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code char} value.
     * @param array the array
     * @param index the index into the array
     * @param c the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setChar(Object array, int index, char c)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof char[] a)
        {
            a[index] = c;
            return;
        }
        if (array instanceof int[] a)
        {
            a[index] = c;
            return;
        }
        if (array instanceof long[] a)
        {
            a[index] = c;
            return;
        }
        if (array instanceof float[] a)
        {
            a[index] = c;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = c;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code short} value.
     * @param array the array
     * @param index the index into the array
     * @param s the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setShort(Object array, int index, short s)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof short[] a)
        {
            a[index] = s;
            return;
        }
        if (array instanceof int[] a)
        {
            a[index] = s;
            return;
        }
        if (array instanceof long[] a)
        {
            a[index] = s;
            return;
        }
        if (array instanceof float[] a)
        {
            a[index] = s;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = s;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code int} value.
     * @param array the array
     * @param index the index into the array
     * @param i the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setInt(Object array, int index, int i)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof int[] a)
        {
            a[index] = i;
            return;
        }
        if (array instanceof long[] a)
        {
            a[index] = i;
            return;
        }
        if (array instanceof float[] a)
        {
            a[index] = i;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = i;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code long} value.
     * @param array the array
     * @param index the index into the array
     * @param l the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setLong(Object array, int index, long l)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof long[] a)
        {
            a[index] = l;
            return;
        }
        if (array instanceof float[] a)
        {
            a[index] = l;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = l;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code float} value.
     * @param array the array
     * @param index the index into the array
     * @param f the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setFloat(Object array, int index, float f)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof float[] a)
        {
            a[index] = f;
            return;
        }
        if (array instanceof double[] a)
        {
            a[index] = f;
            return;
        }
        throw mismatch(array);
    }

    /**
     * Sets the value of the indexed component of the specified array
     * object to the specified {@code double} value.
     * @param array the array
     * @param index the index into the array
     * @param d the new value of the indexed component
     * @throws    NullPointerException If the specified object argument
     * is null
     * @throws    IllegalArgumentException If the specified object argument
     * is not an array, or if the specified value cannot be converted
     * to the underlying array's component type by an identity or a
     * primitive widening conversion
     * @throws    ArrayIndexOutOfBoundsException If the specified {@code index}
     * argument is negative, or if it is greater than or equal to
     * the length of the specified array
     * @see Array#set
     */
    public static void setDouble(Object array, int index, double d)
        throws IllegalArgumentException, ArrayIndexOutOfBoundsException
    {
        if (array instanceof double[] a)
        {
            a[index] = d;
            return;
        }
        throw mismatch(array);
    }

    /*
     * Private
     */

    @IntrinsicCandidate
    private static Object newArray(Class<?> componentType, int length)
        throws NegativeArraySizeException
    {
        if (componentType == void.class)
        {
            throw new IllegalArgumentException("Can not create array of void");
        }
        if (length < 0)
        {
            throw new NegativeArraySizeException(String.valueOf(length));
        }
        if (dimensionsOf(componentType) >= 255)
        {
            throw new IllegalArgumentException("Array type exceeds the 255-dimension limit");
        }
        return newArray0(componentType, length);
    }

    private static Object multiNewArray(Class<?> componentType,
        int[] dimensions)
        throws IllegalArgumentException, NegativeArraySizeException
    {
        if (componentType == null || dimensions == null)
        {
            throw new NullPointerException();
        }
        int n = dimensions.length;
        if (n == 0)
        {
            throw new IllegalArgumentException("Empty dimensions array");
        }
        if (componentType == void.class)
        {
            throw new IllegalArgumentException("Can not create array of void");
        }
        if (dimensionsOf(componentType) + n > 255)
        {
            throw new IllegalArgumentException("Array type exceeds the 255-dimension limit");
        }
        for (int d : dimensions)
        {
            if (d < 0)
            {
                throw new NegativeArraySizeException(String.valueOf(d));
            }
        }
        // levels[k] is the component type of the arrays made at depth k: componentType itself at the innermost
        // depth, one array type wrapped around it per level outwards.
        Class<?>[] levels = new Class<?>[n];
        levels[n - 1] = componentType;
        for (int k = n - 2; k >= 0; k--)
        {
            levels[k] = newArray0(levels[k + 1], 0).getClass();
        }
        return fill(levels, dimensions, 0);
    }

    /*
     * joe-ng
     */

    /**
     * VM native ({@code Loader.nativeBuf} -> {@code VMNatives.newReflectArray}): a {@code length}-element array of
     * {@code componentType}, with the interned array TIB {@code new T[]} uses -- a primitive component gets a
     * primitive-width array, anything else a reference array. The checks are {@link #newArray}'s.
     */
    private static native Object newArray0(Class<?> componentType, int length);

    /** The arrays at depth {@code k} and below of a {@link #multiNewArray}: one per index, each filled in turn. */
    private static Object fill(Class<?>[] levels, int[] dimensions, int k)
    {
        Object a = newArray0(levels[k], dimensions[k]);
        if (k + 1 < dimensions.length)
        {
            Object[] outer = (Object[]) a;
            for (int i = 0; i < outer.length; i++)
            {
                outer[i] = fill(levels, dimensions, k + 1);
            }
        }
        return a;
    }

    /** How many array dimensions {@code c} already has: 0 for a non-array class. */
    private static int dimensionsOf(Class<?> c)
    {
        int n = 0;
        while (c.isArray())
        {
            c = c.getComponentType();
            n++;
        }
        return n;
    }

    /**
     * What a typed accessor throws when no arm matched: what stock throws, in the order stock checks -- a null
     * array is {@code NullPointerException}, a non-array {@code IllegalArgumentException("Argument is not an
     * array")}, an array whose element type cannot be widened to or from the accessor's {@code
     * IllegalArgumentException("argument type mismatch")}.
     */
    private static RuntimeException mismatch(Object array)
    {
        if (array == null)
        {
            return new NullPointerException();
        }
        if (!array.getClass().isArray())
        {
            return new IllegalArgumentException("Argument is not an array");
        }
        return new IllegalArgumentException("argument type mismatch");
    }
}
