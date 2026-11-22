/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-16
 */
package demo;

import magic.Address;
import magic.AddressArray;
import magic.ObjectReference;
import magic.ObjectReferenceArray;
import magic.Word;

/**
 * FIELDS of the unboxed types on the METAL JIT -- the prerequisite for retyping the VM's long address fields.
 * An {@code Lmagic/Address;} field is a plain 64-bit slot: getstatic/putstatic/getfield/putfield move the raw word,
 * a never-written field reads as zero, and the collector traces it like the {@code long} it replaces (it counts L
 * and J alike and validates every candidate), so a value that is NOT an object -- an address outside the heap, the
 * all-ones word, an interior address of an array that has since died -- must survive collections unchanged and must
 * not upset the tracer. An Address does not keep its target alive (a real reference does); this probe keeps the
 * array it reads through alive with an ordinary reference.
 *
 * <p>Also {@link AddressArray}, which IS a {@code long[]} at run time: create/get/set/length lower to newarray long /
 * laload / lastore / arraylength, so the array is a real object (an AddressArray field keeps it alive) and an access
 * throws exactly what the array bytecode would. The throwing accessors sit in methods whose ONLY operation is the
 * access: with implicit checks on, that throw path is a BL, so such a method must be non-leaf (save LR) or its
 * caller's catch is skipped -- the trap recorded for divideDeep and the guarded-static `getstatic; areturn`.
 *
 * <p>And {@link ObjectReference}: a field of it is an {@code L} field too, but it holds an object's START, which the
 * tracer accepts as a reference -- so unlike an Address it MUST keep its object alive. Each ObjectReference field
 * below is the only reference to a fresh object across collections with churn between them; a block the collector
 * freed and reused would show as wrong contents or a wrong class. CANNOT FAIL YET (measured 2026-11-16): a negative
 * control -- a long[6] held only as two int statics, allocated in a frame already popped -- was still NOT freed
 * after the same collections (word 0 still its TIB, not a free-list link), because a launched program's arena on
 * cores 1-3 is never collected (BACKLOG, "Collector") and roots are conservative. The checks stay so that they
 * start to bite when that is fixed; until then keep-alive rests on the mechanism (an L field, mayHoldPointer).
 */
public class AddressFieldProbe
{
    private static Address sBase;       // a heap address: element 0 of a live long[]

    private static Address sRaw;        // an address outside the heap (never dereferenced)

    private static Address sUnset;      // never written: must read as zero

    private static Word sWord;

    private static AddressArray sTable;   // the ONLY reference to its array across the collections

    private static ObjectReference sRef;  // the ONLY reference to its long[] across the collections

    private static ObjectReference sRefUnset;

    private static ObjectReferenceArray sRefs;  // the ONLY reference to its long[] across the collections

    private static int failures;

    private static int checks;

    private long before;                // sentinels on both sides: an Address store must not clobber them

    private Address iBase;

    private Address iInterior;          // into an array that dies before the collections

    private Address iMax;               // the all-ones word: not a plausible pointer

    private Address iUnset;

    private Word iWord;

    private ObjectReference iRef;       // the ONLY reference to its long[] across the collections

    private long after;

    public static void main(String[] args)
    {
        long[] mem = new long[4];
        for (int i = 0; i < mem.length; i++)
        {
            mem[i] = 0x5A5A_0000_0000L + i;
        }
        AddressFieldProbe p = new AddressFieldProbe();
        p.before = 0x1111_2222_3333_4444L;
        p.after = 0x5555_6666_7777_8888L;
        check("unset static reads zero", sUnset.isZero() && sUnset.toLong() == 0L);
        check("unset instance reads zero", p.iUnset.isZero());

        setStatics(mem);
        p.setInstance(mem);
        check("static store/load", sBase.toLong() == Address.fromObject(mem).plus(24).toLong());
        check("static raw", sRaw.toLong() == 0xFE20_0000L);
        check("static Word", sWord.toLong() == 0xF0F0_F0F0_F0F0_F0F0L);
        check("instance store/load", p.iBase.toLong() == sBase.toLong() && p.iMax.isMax());
        check("instance Word", p.iWord.toLong() == -2L);
        check("sentinels intact", p.before == 0x1111_2222_3333_4444L && p.after == 0x5555_6666_7777_8888L);
        check("load through static", sBase.loadLong() == 0x5A5A_0000_0000L && sBase.plus(24).loadLong() == mem[3]);
        check("load through instance", p.iBase.plus(8).loadLong() == 0x5A5A_0000_0001L);
        long interior = p.iInterior.toLong();

        int sum = 0;
        for (int round = 0; round < 4; round++)
        {
            sum = sum + churn(round);
            System.gc();
        }
        check("churned", sum > 0);

        check("static survives gc", sBase.toLong() == Address.fromObject(mem).plus(24).toLong());
        check("raw survives gc", sRaw.toLong() == 0xFE20_0000L && sWord.toLong() == 0xF0F0_F0F0_F0F0_F0F0L);
        check("instance survives gc", p.iBase.toLong() == sBase.toLong() && p.iMax.isMax()
                && p.iWord.toLong() == -2L && p.iUnset.isZero());
        check("dead interior unchanged", p.iInterior.toLong() == interior);
        check("sentinels survive gc", p.before == 0x1111_2222_3333_4444L && p.after == 0x5555_6666_7777_8888L);
        check("load after gc", p.iBase.plus(16).loadLong() == 0x5A5A_0000_0002L && mem[0] == 0x5A5A_0000_0000L);
        p.iBase.plus(8).store(42L);
        check("store through field", mem[1] == 42L);

        arrays(mem);
        compares(mem);
        references(p);

        System.out.println("AddressFieldProbe " + checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "AddressFieldProbe PASS" : "AddressFieldProbe FAIL");
    }

    private static void setStatics(long[] mem)
    {
        sBase = Address.fromObject(mem).plus(24);
        sRaw = Address.fromLong(0xFE20_0000L);
        sWord = Word.fromLong(0xF0F0_F0F0_F0F0_F0F0L);
    }

    private void setInstance(long[] mem)
    {
        iBase = Address.fromObject(mem).plus(24);
        iMax = Address.max();
        iWord = Word.fromLong(-2L);
        long[] doomed = new long[16];
        iInterior = Address.fromObject(doomed).plus(24 + 40);
    }

    private static void arrays(long[] mem)
    {
        AddressArray t = AddressArray.create(5);
        check("AddressArray length", t.length() == 5);
        check("AddressArray starts zero", t.get(0).isZero() && t.get(4).isZero());
        Address base = Address.fromObject(mem).plus(24);
        for (int i = 0; i < t.length(); i++)
        {
            t.set(i, base.plus(8 * (i % 4)));
        }
        check("AddressArray set/get", t.get(3).toLong() == base.plus(24).toLong() && t.get(4).toLong() == base.toLong());
        check("AddressArray load through element", t.get(2).loadLong() == 0x5A5A_0000_0002L);
        check("AddressArray is a long[]", ((Object) t) instanceof long[] && ((long[]) (Object) t)[1] == base.plus(8).toLong());
        sTable = AddressArray.create(3);
        sTable.set(0, Address.max());
        sTable.set(1, base);
        sTable.set(2, Address.fromLong(0xFE20_0000L));
        t = null;
        int sum = 0;
        for (int round = 0; round < 3; round++)
        {
            sum = sum + churn(round);
            System.gc();
        }
        check("AddressArray churned", sum > 0);
        check("AddressArray field keeps its array", sTable.length() == 3 && sTable.get(0).isMax()
                && sTable.get(1).toLong() == base.toLong() && sTable.get(2).toLong() == 0xFE20_0000L);
        check("AddressArray element after gc", sTable.get(1).plus(8).loadLong() == 42L);

        check("get out of bounds throws", throwsAioobe(sTable, 3) && throwsAioobe(sTable, -1));
        check("set out of bounds throws", setThrowsAioobe(sTable, 3));
        check("length of null throws NPE", lengthThrowsNpe(null));
        check("negative create throws", createThrowsNase(-1));
        check("in-bounds accessor returns", at(sTable, 2).toLong() == 0xFE20_0000L);
    }

    /**
     * {@code Address == Address} compiles to if_acmp. Two addresses that differ only above bit 31 must compare
     * unequal (the lowering once SXTW'd both operands, a low-32-bit compare), and real references still compare
     * exactly. fromIntSignExtend of an int that overflowed must sign-extend it.
     */
    @SuppressWarnings("ReferenceEquality")
    private static void compares(long[] mem)
    {
        Address lo = Address.fromLong(0x0000_0000_FE20_0000L);
        Address hi = Address.fromLong(0x0000_0001_FE20_0000L);
        Address lo2 = Address.fromLong(0xFE20_0000L);
        check("== differs above bit 31", !same(lo, hi) && differ(lo, hi));
        check("== same word", same(lo, lo2) && !differ(lo, lo2));
        check("EQ agrees with ==", lo.EQ(lo2) && lo.NE(hi));
        Object a = mem;
        Object b = new long[4];
        check("object refs compare exactly", sameObj(a, mem) && !sameObj(a, b));
        int big = Integer.MAX_VALUE;
        int wrapped = big + bump();             // overflows to MIN_VALUE in a 64-bit register
        check("fromIntSignExtend of an overflowed int", Address.fromIntSignExtend(wrapped).toLong() == -2147483648L);
    }

    /** ObjectReference: null, identity, keep-alive across collections, compares, loads and stores of references. */
    private static void references(AddressFieldProbe p)
    {
        check("unset ObjectReference is null", sRefUnset.isNull() && sRefUnset.toLong() == 0L);
        check("nullReference is null", ObjectReference.nullReference().isNull()
                && ObjectReference.nullReference().toObject() == null);
        long[] held = new long[6];
        long[] held2 = new long[6];
        for (int i = 0; i < 6; i++)
        {
            held[i] = 0x0BE0_0000_0000L + i;
            held2[i] = 0x0BE1_0000_0000L + i;
        }
        ObjectReference r = ObjectReference.fromObject(held);
        check("fromObject is the object's address", r.toLong() == Address.fromObject(held).toLong()
                && r.toAddress().EQ(Address.fromObject(held)));
        check("toObject round trip", r.toObject() == held && r.toAddress().toObjectReference().toObject() == held);
        check("fromLong round trip", ObjectReference.fromLong(r.toLong()).EQ(r));
        check("not null", !r.isNull());
        check("field read through the reference", r.toAddress().plus(24L + 8L * 5).loadLong() == 0x0BE0_0000_0005L);
        sRef = r;
        p.iRef = ObjectReference.fromObject(held2);
        held = null;
        held2 = null;
        r = null;
        int sum = 0;
        for (int round = 0; round < 4; round++)
        {
            sum = sum + churn(round + 10);
            System.gc();
        }
        check("ObjectReference churned", sum > 0);
        check("static ObjectReference keeps its object", keptAlive(sRef, 0x0BE0_0000_0000L));
        check("instance ObjectReference keeps its object", keptAlive(p.iRef, 0x0BE1_0000_0000L));
        check("EQ / NE", sRef.EQ(sRef) && sRef.NE(p.iRef) && !sRef.EQ(p.iRef));
        ObjectReference lo = ObjectReference.fromLong(0x0000_0000_FE20_0000L);
        ObjectReference hi = ObjectReference.fromLong(0x0000_0001_FE20_0000L);
        check("EQ is all 64 bits", lo.NE(hi) && !lo.EQ(hi));

        Object[] slots = new Object[2];
        slots[0] = sRef.toObject();
        Address slot0 = ObjectReference.fromObject(slots).toAddress().plus(24);
        check("loadObjectReference reads a reference element", slot0.loadObjectReference().EQ(sRef)
                && slot0.loadObjectReference().toObject() == slots[0]);
        slot0.plus(8).store(p.iRef);
        check("store(ObjectReference) writes a reference element", slots[1] == p.iRef.toObject());
        sRef = ObjectReference.nullReference();
        check("a field set to null reads null", sRef.isNull());
        referenceArrays(p);
    }

    /** ObjectReferenceArray: a long[] whose elements read and write as ObjectReference. */
    private static void referenceArrays(AddressFieldProbe p)
    {
        ObjectReferenceArray t = ObjectReferenceArray.create(4);
        check("ObjectReferenceArray length", t.length() == 4);
        check("ObjectReferenceArray starts null", t.get(0).isNull() && t.get(3).isNull());
        long[] a = new long[6];
        for (int i = 0; i < 6; i++)
        {
            a[i] = 0x0BE3_0000_0000L + i;
        }
        t.set(1, ObjectReference.fromObject(a));
        t.set(2, p.iRef);
        check("ObjectReferenceArray set/get", t.get(1).toObject() == a && t.get(2).EQ(p.iRef) && t.get(0).isNull());
        // An Object[] at run time, as in VM code (frames/GC arc S6c: the metal JIT tags it too), so a precise trace
        // follows its elements; element 1 still holds the reference's bits.
        check("ObjectReferenceArray is an Object[]", ((Object) t) instanceof Object[]
                && Address.fromObject(t).plus(24L + 8L).loadLong() == Address.fromObject(a).toLong());
        sRefs = t;
        t = null;
        a = null;
        int sum = 0;
        for (int round = 0; round < 3; round++)
        {
            sum = sum + churn(round + 20);
            System.gc();
        }
        check("ObjectReferenceArray churned", sum > 0);
        check("ObjectReferenceArray field keeps its array and elements", sRefs.length() == 4
                && keptAlive(sRefs.get(1), 0x0BE3_0000_0000L) && sRefs.get(2).EQ(p.iRef));
        check("ObjectReferenceArray get out of bounds throws", refGetThrowsAioobe(sRefs, 4));
    }

    private static boolean refGetThrowsAioobe(ObjectReferenceArray a, int i)
    {
        try
        {
            a.get(i);
            return false;
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return true;
        }
    }

    /** The reference still names a live long[] holding base, base+1, ... base+5 (not a freed, reused block). */
    private static boolean keptAlive(ObjectReference ref, long base)
    {
        Object o = ref.toObject();
        if (!(o instanceof long[]))
        {
            return false;
        }
        long[] a = (long[]) o;
        if (a.length != 6)
        {
            return false;
        }
        for (int i = 0; i < 6; i++)
        {
            if (a[i] != base + i)
            {
                return false;
            }
        }
        return true;
    }

    private static int bump()
    {
        return 1;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean same(Address x, Address y)
    {
        return x == y;
    }

    @SuppressWarnings("ReferenceEquality")
    private static boolean differ(Address x, Address y)
    {
        return x != y;
    }

    private static boolean sameObj(Object x, Object y)
    {
        return x == y;
    }

    /** Only the access: a leaf unless its throw path is counted as a call. */
    private static Address at(AddressArray a, int i)
    {
        return a.get(i);
    }

    private static void put(AddressArray a, int i, Address v)
    {
        a.set(i, v);
    }

    private static int len(AddressArray a)
    {
        return a.length();
    }

    private static boolean throwsAioobe(AddressArray a, int i)
    {
        try
        {
            at(a, i);
            return false;
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return true;
        }
    }

    private static boolean setThrowsAioobe(AddressArray a, int i)
    {
        try
        {
            put(a, i, Address.zero());
            return false;
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return true;
        }
    }

    private static boolean lengthThrowsNpe(AddressArray a)
    {
        try
        {
            len(a);
            return false;
        }
        catch (NullPointerException e)
        {
            return true;
        }
    }

    private static boolean createThrowsNase(int n)
    {
        try
        {
            AddressArray.create(n);
            return false;
        }
        catch (NegativeArraySizeException e)
        {
            return true;
        }
    }

    /** Allocation that dies at once, so each round's collection has garbage to reclaim. */
    private static int churn(int round)
    {
        int n = 0;
        for (int i = 0; i < 2000; i++)
        {
            byte[] b = new byte[64 + (i & 31)];
            b[0] = (byte) (i + round);
            n = n + b.length;
        }
        return n;
    }

    private static void check(String what, boolean ok)
    {
        checks = checks + 1;
        if (!ok)
        {
            failures = failures + 1;
            System.out.println("FAIL " + what);
        }
    }
}
