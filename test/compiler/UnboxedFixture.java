/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package compiler;

import magic.Address;
import magic.AddressArray;
import magic.Magic;
import magic.ObjectReference;
import magic.ObjectReferenceArray;
import magic.Offset;
import magic.Word;

/**
 * Pairs for {@link UnboxedTest}: each {@code xLong} is written with raw longs and {@code Magic}, each {@code xAddr}
 * the same thing with the unboxed types. The test requires every pair to compile to the SAME machine words -- the
 * guarantee that converting VM code changes no behaviour. Never run; only compiled.
 */
final class UnboxedFixture
{
    private UnboxedFixture()
    {
    }

    static long loadLong(long a)
    {
        return Magic.load64(a);
    }

    static long loadAddr(Address a)
    {
        return a.loadLong();
    }

    static void storeLong(long a, long v)
    {
        Magic.store64(a, v);
    }

    static void storeAddr(Address a, long v)
    {
        a.store(v);
    }

    static int load32Long(long a)
    {
        return Magic.load32(a);
    }

    static int load32Addr(Address a)
    {
        return a.loadInt();
    }

    static void store32Long(long a, int v)
    {
        Magic.store32(a, v);
    }

    static void store32Addr(Address a, int v)
    {
        a.store(v);
    }

    static int load8Long(long a)
    {
        return Magic.load8(a);
    }

    static int load8Addr(Address a)
    {
        return a.loadUnsignedByte();
    }

    static long fieldLong(long obj)
    {
        return Magic.load64(obj + 16L);
    }

    static long fieldAddr(Address obj)
    {
        return obj.plus(16L).loadLong();
    }

    static long indexLong(long base, long off)
    {
        return Magic.load64(base + off);
    }

    static long indexAddr(Address base, Offset off)
    {
        return base.plus(off).loadLong();
    }

    static long indexOffAddr(Address base, Offset off)
    {
        return base.loadLong(off);
    }

    static boolean casLong(long a, long e, long u)
    {
        return Magic.cas64(a, e, u);
    }

    static boolean casAddr(Address a, Word e, Word u)
    {
        return a.attempt(e, u);
    }

    static long maskLong(long w, long m)
    {
        return (w & m) | (w >>> 3);
    }

    static Word maskWord(Word w, Word m)
    {
        return w.and(m).or(w.rshl(3));
    }

    static long diffLong(long a, long b)
    {
        return a - b;
    }

    static Offset diffAddr(Address a, Address b)
    {
        return a.diff(b);
    }

    static Object objLong(long a)
    {
        return Magic.fromAddr(a);
    }

    static Object objAddr(Address a)
    {
        return a.toObject();
    }

    static long nopLong(long a)
    {
        return a;
    }

    static long nopAddr(long a)
    {
        return Address.fromLong(a).toWord().toAddress().toLong();
    }

    static long getLong(long[] a, int i)
    {
        return a[i];
    }

    static long getAddr(AddressArray a, int i)
    {
        return a.get(i).toLong();
    }

    static void setLong(long[] a, int i, long v)
    {
        a[i] = v;
    }

    static void setAddr(AddressArray a, int i, Address v)
    {
        a.set(i, v);
    }

    static int lenLong(long[] a)
    {
        return a.length;
    }

    static int lenAddr(AddressArray a)
    {
        return a.length();
    }

    static long sumLong(long[] a)
    {
        long s = 0L;
        for (int i = 0; i < a.length; i++)
        {
            s = s + a[i];
        }
        return s;
    }

    static long sumAddr(AddressArray a)
    {
        long s = 0L;
        for (int i = 0; i < a.length(); i++)
        {
            s = s + a.get(i).toLong();
        }
        return s;
    }

    // ---- no long twin: the test states the expected words ----

    static boolean isZero(Address a)
    {
        return a.isZero();
    }

    static boolean lessThan(Address a, Address b)
    {
        return a.LT(b);
    }

    static boolean signedLess(Offset a, Offset b)
    {
        return a.sLT(b);
    }

    static Address zero()
    {
        return Address.zero();
    }

    static int loadShortOff(Address a, Offset o)
    {
        return a.loadShort(o);
    }

    static void storeByteOff(Address a, byte v, Offset o)
    {
        a.store(v, o);
    }

    // ---- a comparison straight into a branch: fused, no CSET ----

    static int branchLong(long a, long b)
    {
        if (a < b)
        {
            return 1;
        }
        return 2;
    }

    static int branchAddr(Address a, Address b)
    {
        if (a.LT(b))
        {
            return 1;
        }
        return 2;
    }

    static int branchZeroLong(long a)
    {
        if (a == 0L)
        {
            return 1;
        }
        return 2;
    }

    static int branchZeroAddr(Address a)
    {
        if (a.isZero())
        {
            return 1;
        }
        return 2;
    }

    /** if_acmp on two Addresses: must compare all 64 bits (no SXTW). */
    @SuppressWarnings("ReferenceEquality")
    static int refEqAddr(Address a, Address b)
    {
        if (a == b)
        {
            return 1;
        }
        return 2;
    }

    static int refEqObj(Object a, Object b)
    {
        if (a != b)
        {
            return 1;
        }
        return 2;
    }

    /** if_icmp keeps its SXTWs: an overflowed int is not sign-extended in its register. */
    static int intCmp(int a, int b)
    {
        if (a == b)
        {
            return 1;
        }
        return 2;
    }

    static Address sext(int i)
    {
        return Address.fromIntSignExtend(i);
    }

    // ObjectReference: each must be the same words as its long / Magic twin above.

    static long refFieldLong(Object o)
    {
        return Magic.load64(Magic.addrOf(o) + 16L);
    }

    static long refFieldRef(Object o)
    {
        return ObjectReference.fromObject(o).toAddress().plus(16L).loadLong();
    }

    static long loadRefAddr(Address a)
    {
        return a.loadObjectReference().toLong();
    }

    static void storeRefAddr(Address a, ObjectReference v)
    {
        a.store(v);
    }

    static Object objRef(ObjectReference r)
    {
        return r.toObject();
    }

    static long refLong(long r)
    {
        return ObjectReference.fromLong(r).toAddress().toObjectReference().toLong();
    }

    static boolean isNullRef(ObjectReference r)
    {
        return r.isNull();
    }

    static boolean eqAddr(Address a, Address b)
    {
        return a.EQ(b);
    }

    static boolean eqRef(ObjectReference a, ObjectReference b)
    {
        return a.EQ(b);
    }

    static boolean neAddr(Address a, Address b)
    {
        return a.NE(b);
    }

    static boolean neRef(ObjectReference a, ObjectReference b)
    {
        return a.NE(b);
    }

    static ObjectReference nullRef()
    {
        return ObjectReference.nullReference();
    }

    // ObjectReferenceArray IS a long[] too: the same words as AddressArray's and long[]'s ops.

    static long getRefArr(ObjectReferenceArray a, int i)
    {
        return a.get(i).toLong();
    }

    static void setRefArr(ObjectReferenceArray a, int i, ObjectReference v)
    {
        a.set(i, v);
    }

    static int lenRefArr(ObjectReferenceArray a)
    {
        return a.length();
    }

    static boolean nullAtRefArr(ObjectReferenceArray a, int i)
    {
        return a.get(i).isNull();
    }

    static boolean zeroAtAddr(AddressArray a, int i)
    {
        return a.get(i).isZero();
    }
}
