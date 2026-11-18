/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import magic.Address;
import magic.ObjectReference;

import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;

/**
 * Comparing and hashing the names the loader works with, where they lie: a classfile CONSTANT_Utf8 entry
 * ({@code u2} length + bytes) inside a loaded blob, a raw byte range in memory, or a {@code byte[]} literal.
 * The FNV-1a folds here key every name index the loader builds, so all of them must agree on one fold.
 * Stateless. Split out of {@link Loader} (JikesRVM keeps the same role in {@code Atom}, which also interns;
 * nothing here interns -- a name is still identified by where it lives).
 */
final class Utf8
{
    private Utf8()
    {
    }

    /** {@link #utf8Hash} of a name given as bytes: the same FNV-1a fold, so it lands in the same bucket. */
    static int bytesHash(byte[] name)
    {
        int h = 0x811C9DC5;
        int i = 0;
        while (i < name.length)
        {
            h = (h ^ (name[i] & 0xFF)) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /** FNV-1a over a name Utf8 and a descriptor Utf8 -- the key {@link MethodTableCache#matchLevel} probes on. */
    static int sigHash(Address base, int nameOff, int descOff)
    {
        return utf8Hash(base, nameOff) * 31 + utf8Hash(base, descOff);
    }

    /** FNV-1a over the bytes of the length-prefixed Utf8 at {@code base + off}. */
    /**
     * Hash of TWO Utf8 runs, for an index keyed on class+name rather than name alone. Folding the class in
     * is what makes the registry bucket narrow: keyed on the NAME only, a hot name chains one entry per
     * class that declares it, so `<init>`/`run`/`get` grow a chain as long as the registry -- MEASURED at
     * ~60 entries per resolve on a 192-blob suite, and the dominant term in `lookT` (t1 steps 17k -> 156k
     * tracked lookT 12.1 -> 96.0ms across batches 40-65, while the blob scan I first suspected grew 1.6x).
     * Continuing the FNV chain across both runs costs one extra pass over a class name and removes the walk.
     */
    static int utf8Hash2(Address b1, int o1, Address b2, int o2)
    {
        int len = u2(b1.plus(o1));
        Address q = b1.plus(o1 + 2L);
        int h = 0x811C9DC5;
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        len = u2(b2.plus(o2));
        q = b2.plus(o2 + 2L);
        i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        return h;
    }

    static int utf8Hash(Address base, int off)
    {
        int len = u2(base.plus(off));
        Address q = base.plus(off + 2L);
        int h = 0x811C9DC5;
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /**
     * Continue an FNV-1a fold over one more Utf8 run. FNV IS A FOLD, so
     * {@code utf8HashFrom(utf8Hash(b1, o1), b2, o2)} is BIT-IDENTICAL to {@code utf8Hash2(b1, o1, b2, o2)} --
     * which is the whole point: the class half of a class+name key can be cached ({@link RVMClass#nameHash})
     * without changing the key, so every existing probe of the same index still lands in the same bucket.
     * Changing the key function instead would have meant changing all four sites that probe {@code rgBucket}
     * at once, and a site left behind would silently find nothing.
     */
    static int utf8HashFrom(int h, Address base, int off)
    {
        int len = u2(base.plus(off));
        Address q = base.plus(off + 2L);
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /**
     * Two byte[]s with equal contents. Written out because java.util.Arrays is not usable from here: its
     * initializer is a bake stub, and a baked Loader method that reaches it halts the boot (bakeresolve-early).
     */
    static boolean sameBytes(byte[] a, byte[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        int i = 0;
        while (i < a.length)
        {
            if (a[i] != b[i])
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    static boolean isName(Address base, int off, long expected, int len)
    {
        if (off == 0)
        {
            return false;
        }
        if (u2(base.plus(off)) != len)
        {
            return false;
        }
        long got = 0L;
        int j = 0;
        while (j < len)
        {
            got = (got << 8) | u1(base.plus(off + 2 + j));
            j += 1;
        }
        return got == expected;
    }

    /** utf8Hash2 of (the Utf8 at {@code b1 + o1}, then the raw bytes {@code [raw, raw + rawLen)}). */
    static int utf8HashThenRaw(Address b1, int o1, Address raw, int rawLen)
    {
        int len = u2(b1.plus(o1));
        Address q = b1.plus(o1 + 2L);
        int h = 0x811C9DC5;
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        i = 0;
        while (i < rawLen)
        {
            h = (h ^ u1(raw.plus(i))) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /** utf8Hash2 of (the Utf8 at {@code b1 + o1}, then the bytes of {@code name}). */
    static int utf8HashThenBytes(Address b1, int o1, byte[] name)
    {
        int len = u2(b1.plus(o1));
        Address q = b1.plus(o1 + 2L);
        int h = 0x811C9DC5;
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q.plus(i))) * 0x01000193;
            i += 1;
        }
        i = 0;
        while (i < name.length)
        {
            h = (h ^ (name[i] & 0xFF)) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /** True if the raw byte range {@code rawBase..+rawLen} equals the Utf8 (u2 length + bytes) at {@code utBase+utOff}. */
    static boolean rawEqUtf8(Address rawBase, int rawLen, Address utBase, int utOff)
    {
        if (u2(utBase.plus(utOff)) != rawLen)
        {
            return false;
        }
        int k = 0;
        while (k < rawLen)
        {
            if ((rawBase.plus(k).loadUnsignedByte() & 0xFF) != u1(utBase.plus(utOff + 2 + k)))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /** Two Utf8s, each at an absolute address (u2 len + bytes), are byte-equal. */
    static boolean utf8Same(Address a, Address b)
    {
        int len = u2(a);
        if (len != u2(b))
        {
            return false;
        }
        int i = 0;
        while (i < len)
        {
            if (u1(a.plus(2L + i)) != u1(b.plus(2L + i)))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** The Utf8 entry at blob offset {@code off} (u2 len + bytes) equals the Utf8 at absolute address {@code u}. */
    static boolean utf8BlobSame(Address base, int off, Address u)
    {
        return utf8Same(base.plus(off), u);
    }

    /** True if the Utf8 at {@code base+off} equals the {@code n} bytes of the byte[] payload at {@code arr}. */
    static boolean utf8EqArr(Address base, int off, ObjectReference arr, int n)
    {
        if (u2(base.plus(off)) != n)
        {
            return false;
        }
        int k = 0;
        while (k < n)
        {
            if (u1(base.plus(off + 2 + k)) != (arr.toAddress().plus(24L).plus(k).loadUnsignedByte() & 0xFF))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /** True if the Utf8 at {@code off} in blob {@code base} equals the literal {@code want}. */
    static boolean utf8IsAtBase(Address base, int off, byte[] want)
    {
        if (u2(base.plus(off)) != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < want.length)
        {
            if (u1(base.plus(off + 2 + j)) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** True if the Utf8 at {@code off} in {@code base} equals the {@code len} raw bytes at {@code raw}. */
    static boolean rawNameEq(Address base, int off, Address raw, int len)
    {
        if (u2(base.plus(off)) != len)
        {
            return false;
        }
        int i = 0;
        while (i < len)
        {
            if (u1(base.plus(off + 2 + i)) != u1(raw.plus(i)))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** True if the {@code n}-byte slice at {@code aBase+aOff} equals the Utf8 name at {@code bBase+bNameOff}. */
    static boolean utf8SliceEq(Address aBase, int aOff, int n, Address bBase, int bNameOff)
    {
        if (u2(bBase.plus(bNameOff)) != n)
        {
            return false;
        }
        int j = 0;
        while (j < n)
        {
            if (u1(aBase.plus(aOff + j)) != u1(bBase.plus(bNameOff + 2 + j)))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Whether the {@code len} bytes at {@code p} are exactly {@code want}. */
    static boolean bytesAt(Address p, int len, byte[] want)
    {
        if (len != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < len)
        {
            if (u1(p.plus(j)) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Whether the {@code len} bytes at {@code a} and at {@code b} are equal. */
    static boolean sameBytesAt(Address a, Address b, int len)
    {
        int j = 0;
        while (j < len)
        {
            if (u1(a.plus(j)) != u1(b.plus(j)))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }
}
