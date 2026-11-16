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
 */
public class AddressFieldProbe
{
    private static Address sBase;       // a heap address: element 0 of a live long[]

    private static Address sRaw;        // an address outside the heap (never dereferenced)

    private static Address sUnset;      // never written: must read as zero

    private static Word sWord;

    private static AddressArray sTable;   // the ONLY reference to its array across the collections

    private static int failures;

    private static int checks;

    private long before;                // sentinels on both sides: an Address store must not clobber them

    private Address iBase;

    private Address iInterior;          // into an array that dies before the collections

    private Address iMax;               // the all-ones word: not a plausible pointer

    private Address iUnset;

    private Word iWord;

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
