/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-22
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package vm;

import magic.Magic;
import objectmodel.ObjectModel;

/**
 * {@code java/lang/System.arraycopy}: the checks, the messages and the copy, in HotSpot's order
 * ({@code JVM_ArrayCopy}, then {@code TypeArrayKlass}/{@code ObjArrayKlass::copy_array} in the JDK 26 tree).
 *
 * <p>The native used to copy {@code len * elemSize(src)} bytes and check nothing: a null array read address 0, a
 * String was copied as if it were an array, {@code int[]} into {@code Object[]} stored int bits as references,
 * and a negative or overlong range wrote outside the destination. Every one was silent.
 *
 * <ul>
 * <li>A null array is a {@code NullPointerException} with no message.</li>
 * <li>A source that is not an array, then a destination that is not one or is the other KIND (primitive versus
 * reference), or a primitive destination of another element type, is an {@code ArrayStoreException} before
 * anything is copied.</li>
 * <li>A negative position or length, then a range past either end, is an
 * {@code ArrayIndexOutOfBoundsException}, also before anything is copied.</li>
 * <li>Reference arrays whose source element type is not a subtype of the destination's are checked ELEMENT BY
 * ELEMENT: the elements before the first unassignable one ARE copied, then it throws -- so a {@code String[]} of
 * nulls copies into an {@code Integer[]} without complaint, as on stock.</li>
 * </ul>
 *
 * <p>A RAW array (its TIB slot holds only the element size, no Type) cannot be named or kind-checked, so only
 * its bounds are checked, and a raw array is refused only against an array of another element SIZE.
 */
final class ArrayCopy
{
    private ArrayCopy()
    {
    }

    private static final int NOT_ARRAY = 0;
    private static final int PRIM = 1;
    private static final int REF = 2;
    private static final int RAW = 3;

    /** What {@code arr} is: not an array, a primitive array, a reference array, or a raw (untyped) array. */
    private static int kindOf(long arr)
    {
        long tib = Magic.load64(arr + ObjectModel.TIB_OFFSET);
        if (tib > 0L && tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return RAW;
        }
        if (tib == 0L)
        {
            return NOT_ARRAY;
        }
        long type = Magic.load64(tib);
        if (!Loader.isArrayType(type))
        {
            return NOT_ARRAY;
        }
        if (Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET) != 0L)
        {
            return REF;
        }
        return Loader.primArrayElemChar(type) != 0 ? PRIM : REF;   // no element and no primitive: unresolved ref
    }

    private static long typeOf(long obj)
    {
        long tib = Magic.load64(obj + ObjectModel.TIB_OFFSET);
        return tib > ObjectModel.MAX_RAW_ARRAY_TIB ? Magic.load64(tib) : 0L;
    }

    /** A reference array's element Type, or 0 if it is raw or the element never resolved. */
    private static long elemTypeOf(long arr)
    {
        long type = typeOf(arr);
        return type == 0L ? 0L : Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
    }

    private static int lengthOf(long arr)
    {
        return (int) Magic.load64(arr + ObjectModel.ARRAY_LENGTH_OFFSET);
    }

    /** The name HotSpot prints for an array's element in a bounds message: "int", or "object array". */
    private static byte[] kindName(long arr, int kind)
    {
        if (kind == PRIM)
        {
            return Loader.primName(Loader.primArrayElemChar(typeOf(arr)));
        }
        return Magic.bytes("object array");
    }

    static void arraycopy(long src, int srcPos, long dst, int dstPos, int len)
    {
        if (src == 0L || dst == 0L)
        {
            throw (NullPointerException) Magic.fromAddr(Loader.newExcWithMessage(
                    Magic.bytes("java/lang/NullPointerException"), null));
        }
        int sk = kindOf(src);
        int dk = kindOf(dst);
        checkKinds(src, sk, dst, dk);
        checkBounds(src, sk, srcPos, dst, dk, dstPos, len);
        if (len == 0)
        {
            return;
        }
        long es = VMNatives.elemSize(src);
        long from = src + (long) ObjectModel.ARRAY_BASE_OFFSET + (long) srcPos * es;
        long to = dst + (long) ObjectModel.ARRAY_BASE_OFFSET + (long) dstPos * es;
        if (sk == REF && dk == REF && src != dst)
        {
            long bound = elemTypeOf(dst);
            long stype = elemTypeOf(src);
            if (bound != 0L && stype != 0L && VM.typesAssignable(stype, bound) == 0)
            {
                copyChecked(from, to, len, stype, bound);
                return;
            }
        }
        move(from, to, (long) len * es, es);
    }

    /** The ArrayStoreException checks that need no element: what each side IS. */
    private static void checkKinds(long src, int sk, long dst, int dk)
    {
        if (sk == NOT_ARRAY)
        {
            throwAse(cat(Magic.bytes("arraycopy: source type "), Loader.typeNameBytes(typeOf(src)),
                    Magic.bytes(" is not an array")));
        }
        if (dk == NOT_ARRAY)
        {
            throwAse(cat(Magic.bytes("arraycopy: destination type "), Loader.typeNameBytes(typeOf(dst)),
                    Magic.bytes(" is not an array")));
        }
        if (sk == RAW || dk == RAW)
        {
            if (VMNatives.elemSize(src) != VMNatives.elemSize(dst))
            {
                throwAse(Magic.bytes("arraycopy: type mismatch"));
            }
            return;
        }
        if (sk == PRIM && dk == REF)
        {
            throwAse(cat(Magic.bytes("arraycopy: type mismatch: can not copy "), kindName(src, sk),
                    Magic.bytes("[] into object array[]")));
        }
        if (sk == REF && dk == PRIM)
        {
            throwAse(cat(Magic.bytes("arraycopy: type mismatch: can not copy object array[] into "),
                    kindName(dst, dk), Magic.bytes("[]")));
        }
        if (sk == PRIM && Loader.primArrayElemChar(typeOf(src)) != Loader.primArrayElemChar(typeOf(dst)))
        {
            throwAse(cat(cat(Magic.bytes("arraycopy: type mismatch: can not copy "), kindName(src, sk),
                    Magic.bytes("[] into ")), kindName(dst, dk), Magic.bytes("[]")));
        }
    }

    /** Negative positions and length, then the unsigned end of each range against its array's length. */
    private static void checkBounds(long src, int sk, int srcPos, long dst, int dk, int dstPos, int len)
    {
        int sl = lengthOf(src);
        int dl = lengthOf(dst);
        if (srcPos < 0)
        {
            throwAioobe(cat(Magic.bytes("arraycopy: source index "), dec(srcPos), outOf(src, sk, sl)));
        }
        if (dstPos < 0)
        {
            throwAioobe(cat(Magic.bytes("arraycopy: destination index "), dec(dstPos), outOf(dst, dk, dl)));
        }
        if (len < 0)
        {
            throwAioobe(cat(Magic.bytes("arraycopy: length "), dec(len), Magic.bytes(" is negative")));
        }
        long srcEnd = (long) srcPos + (long) len;          // both non-negative now, so no unsigned wrap
        long dstEnd = (long) dstPos + (long) len;
        if (srcEnd > (long) sl)
        {
            throwAioobe(cat(Magic.bytes("arraycopy: last source index "), decLong(srcEnd), outOf(src, sk, sl)));
        }
        if (dstEnd > (long) dl)
        {
            throwAioobe(cat(Magic.bytes("arraycopy: last destination index "), decLong(dstEnd), outOf(dst, dk, dl)));
        }
    }

    /** " out of bounds for int[2]". */
    private static byte[] outOf(long arr, int kind, int length)
    {
        return cat(cat(Magic.bytes(" out of bounds for "), kindName(arr, kind), Magic.bytes("[")), dec(length),
                Magic.bytes("]"));
    }

    /**
     * The element-by-element copy between reference arrays of unrelated element types: each non-null element must
     * be an instance of the destination's element type, and the ones before the first that is not stay copied.
     */
    private static void copyChecked(long from, long to, int len, long stype, long bound)
    {
        long i = 0L;
        while (i < (long) len)
        {
            long v = Magic.load64(from + i * 8L);
            if (v != 0L && VM.instanceOf(v, bound) == 0)
            {
                byte[] s = Loader.typeNameBytes(stype);
                byte[] b = Loader.typeNameBytes(bound);
                if (VM.typesAssignable(bound, stype) == 0)
                {
                    throwAse(cat(cat(Magic.bytes("arraycopy: type mismatch: can not copy "), s,
                            Magic.bytes("[] into ")), b, Magic.bytes("[]")));
                }
                throwAse(cat(cat(Magic.bytes("arraycopy: element type mismatch: can not cast one of the elements of "),
                        s, Magic.bytes("[] to the type of the destination array, ")), b, new byte[0]));
            }
            Magic.store64(to + i * 8L, v);
            i += 1L;
        }
    }

    /**
     * Copy {@code n} bytes, overlap-safe like {@code memmove}, a whole element at a time where the element is a
     * word or an int so no reference or long is ever half-written.
     */
    private static void move(long from, long to, long n, long es)
    {
        long step = es == 8L || es == 4L ? es : 1L;
        if (to <= from)
        {
            long i = 0L;
            while (i < n)
            {
                moveOne(from + i, to + i, step);
                i += step;
            }
        }
        else
        {
            long i = n;
            while (i > 0L)
            {
                i -= step;
                moveOne(from + i, to + i, step);
            }
        }
    }

    private static void moveOne(long from, long to, long step)
    {
        if (step == 8L)
        {
            Magic.store64(to, Magic.load64(from));
        }
        else if (step == 4L)
        {
            Magic.store32(to, Magic.load32(from));
        }
        else
        {
            Magic.store8(to, (byte) Magic.load8(from));
        }
    }

    private static void throwAse(byte[] msg)
    {
        throw (ArrayStoreException) Magic.fromAddr(Loader.newExcWithMessage(
                Magic.bytes("java/lang/ArrayStoreException"), msg));
    }

    private static void throwAioobe(byte[] msg)
    {
        throw (ArrayIndexOutOfBoundsException) Magic.fromAddr(Loader.newExcWithMessage(
                Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"), msg));
    }

    private static byte[] cat(byte[] a, byte[] b, byte[] c)
    {
        byte[] out = new byte[a.length + b.length + c.length];
        int p = 0;
        int k = 0;
        while (k < a.length)
        {
            out[p] = a[k];
            p += 1;
            k += 1;
        }
        k = 0;
        while (k < b.length)
        {
            out[p] = b[k];
            p += 1;
            k += 1;
        }
        k = 0;
        while (k < c.length)
        {
            out[p] = c[k];
            p += 1;
            k += 1;
        }
        return out;
    }

    private static byte[] dec(int v)
    {
        return decLong(v);
    }

    /** {@code v} in decimal; long math, so -MIN_VALUE of an int fits. */
    private static byte[] decLong(long v)
    {
        byte[] tmp = new byte[21];
        int n = 0;
        long m = v < 0L ? -v : v;
        do
        {
            tmp[n] = (byte) (0x30 + (int) (m % 10L));
            n += 1;
            m = m / 10L;
        }
        while (m > 0L);
        int sign = v < 0L ? 1 : 0;
        byte[] out = new byte[n + sign];
        if (sign == 1)
        {
            out[0] = 0x2D;
        }
        int k = 0;
        while (k < n)
        {
            out[sign + k] = tmp[n - 1 - k];
            k += 1;
        }
        return out;
    }
}
