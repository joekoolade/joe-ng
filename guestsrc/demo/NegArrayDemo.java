/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-19
 */
package demo;

/**
 * {@code newarray}/{@code anewarray} with a negative length throw {@code NegativeArraySizeException}
 * (JVMS 6.5). This is not a pedantic missing-throw: it is a BOUNDS-CHECK BYPASS, and the last two arms are
 * what demonstrate that rather than assert it.
 *
 * <p>{@code Heap.allocArray} stores the length as a sign-extended 64-bit word, and the JIT's bounds check
 * compares it UNSIGNED -- deliberately, so that a negative INDEX becomes a huge one and throws. A length of
 * {@code -1} therefore reads back as {@code 0xFFFF_FFFF_FFFF_FFFF}, every index is below it, and
 * {@code new int[-1]} allocated 20 bytes and then accepted a load or a store at ANY index.
 *
 * <p>NEGATIVE CONTROL. With {@code Baseline.negativeSizeCheck} removed, arms 1-5 print
 * {@code no exception} and arms 6-7 report {@code len=-1} and a SUCCESSFUL out-of-bounds store at index
 * 1000000 -- a write a megabyte past a 20-byte object, with no fault. With the check in, every arm throws
 * where Java throws and arms 6-7 never run at all.
 *
 * <p>Lengths come from a method rather than a literal so javac cannot fold them, and so the value reaches
 * the check in a register the way a length read from data does.
 */
public class NegArrayDemo
{
    /** Opaque to javac's constant folding: the length arrives as a runtime value. */
    private static int len(int n)
    {
        return n;
    }

    /** An arm that must throw ArrayIndexOutOfBoundsException -- the control proving bounds checks still work
     *  on a legitimately-sized array, so "nothing throws" cannot be mistaken for "the guard is in". */
    private static String oob(String what, Runnable r)
    {
        try
        {
            r.run();
            return what + " = no exception (WRONG)";
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return what + " = ArrayIndexOutOfBoundsException";
        }
        catch (Throwable t)
        {
            return what + " = " + t.getClass().getName() + " (WRONG)";
        }
    }

    private static String arm(String what, Runnable r)
    {
        try
        {
            r.run();
            return what + " = no exception (WRONG)";
        }
        catch (NegativeArraySizeException e)
        {
            return what + " = NegativeArraySizeException";
        }
        catch (Throwable t)
        {
            return what + " = " + t.getClass().getName() + " (WRONG)";
        }
    }

    public static void main(String[] args)
    {
        // 1-3: newarray (0xBC) over three element widths -- the size computation differs per width, so a
        // check that fired only for one of them would look like a pass here.
        System.out.println(arm("new int[-1]    ", () -> { int[] a = new int[len(-1)]; }));
        System.out.println(arm("new byte[-1]   ", () -> { byte[] a = new byte[len(-1)]; }));
        System.out.println(arm("new long[-7]   ", () -> { long[] a = new long[len(-7)]; }));

        // 4: anewarray (0xBD) -- a different lowering, so it needs its own arm.
        System.out.println(arm("new Object[-1] ", () -> { Object[] a = new Object[len(-1)]; }));

        // 5: INT_MIN, where the byte size overflows int and wraps POSITIVE. A guard written as
        //    "the computed size is negative" rather than "the LENGTH is negative" passes this one.
        System.out.println(arm("new int[MIN]   ", () -> { int[] a = new int[len(Integer.MIN_VALUE)]; }));

        // Controls: a zero and an ordinary length must still allocate, and must still bounds-check.
        int[] ok = new int[len(3)];
        ok[2] = 42;
        int[] empty = new int[len(0)];
        System.out.println("new int[3]     = len " + ok.length + " ok[2]=" + ok[2]);
        System.out.println("new int[0]     = len " + empty.length);
        System.out.println(oob("ok[3] (oob)    ", () -> { int[] a = new int[len(3)]; a[3] = 1; }));

        // THE POINT, and the reason this demo is worth a boot. On a correct VM the allocation throws and the
        // two lines below it never run. With the check removed they print a NEGATIVE length and then a store
        // a million elements past a 20-byte object that reports no error at all -- the bounds check waved it
        // through, because it compares an all-ones length UNSIGNED. Caught, so a correct VM still exits 0.
        try
        {
            int[] bad = new int[len(-1)];
            System.out.println("bypass: len=" + bad.length + " (BOUNDS CHECK IS BYPASSED)");
            bad[1000000] = 0x5A;
            System.out.println("bypass: stored at index 1000000 with NO bounds error -- HEAP CORRUPTED");
        }
        catch (NegativeArraySizeException e)
        {
            System.out.println("bypass        = blocked at the allocation (correct)");
        }
    }
}
