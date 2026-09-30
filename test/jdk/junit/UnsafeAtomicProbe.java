/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-30
 */
import jdk.internal.misc.Unsafe;

/**
 * The {@code jdk.internal.misc.Unsafe} ATOMICS at narrow width -- the ~140 members the overlay dropped, which
 * with the accessor increment before it takes {@code Unsafe} from 237 deep-scan gaps to ZERO.
 *
 * <p>WHY THE BODIES ARE NOT ALL STOCK'S. Stock builds every narrow CAS out of {@code getIntVolatile} +
 * {@code weakCompareAndSetInt} on the enclosing FOUR-byte word; joe-ng's int CAS is {@code Magic.cas64} over an
 * EIGHT-byte slot, so that derivation is not merely suboptimal here, it is WRONG -- eight bytes compared and
 * written at a four-aligned address. So exactly two roots are joe-ng's ({@code compareAndExchangeByte} and
 * {@code compareAndExchangeShort}) and everything above them is stock's own code: Char via Short, Boolean via
 * Byte, Float via Int, Double via Long, every delegation and every read-modify-write loop.
 *
 * <p>THE ARMS ARE CHOSEN SO A PLAUSIBLE WRONG IMPLEMENTATION FAILS:
 * <ul>
 *   <li><b>A narrow CAS on a FIELD changes SIGN</b> ({@code -2 -> 5}) and the arm reads the field back through
 *       ORDINARY JAVA as well as through {@code Unsafe}. A field occupies a full 8-byte slot here, so an
 *       implementation that masked only the low two bytes would leave the OLD sign extension behind:
 *       {@code Unsafe.getShort} would answer 5 while the field read as -65531. An arm that only asked
 *       {@code Unsafe} could not tell.</li>
 *   <li><b>A narrow CAS on an ARRAY ELEMENT asserts its NEIGHBOURS</b>, which is what a whole-slot CAS fails --
 *       it would take three neighbouring shorts with it. Elements are exercised at EVERY alignment inside the
 *       8-byte window (indices 0..3 of a {@code short[]} give {@code offset&7} = 0, 2, 4, 6), because a wrong
 *       shift is invisible at index 0 and obvious at index 3.</li>
 *   <li><b>A FAILING CAS must answer the WITNESSED value</b>, not the value the caller guessed. Returning
 *       {@code expected} on failure tells the caller its CAS failed against the value it already had, and every
 *       {@code compareAndExchange} arm here is paired with a failing one.</li>
 *   <li><b>Or/And/Xor get a start and mask where all three answers differ</b> ({@code 0b1100} with
 *       {@code 0b1010} gives 14 / 8 / 6). A shared start of 0 makes two of the three agree and tests nothing
 *       about which operator ran -- the copy-paste slip near-identical bodies invite.</li>
 *   <li><b>Every {@code getAnd*} arm prints the RETURN as well as the field.</b> These answer the value BEFORE
 *       the update; an implementation returning the new one is correct in the field and wrong in the result.</li>
 *   <li><b>{@code char} is UNSIGNED and the arm wraps past 0x7FFF</b>, so an implementation that sign-extended
 *       it reads a negative number.</li>
 *   <li><b>float and double CAS by RAW BITS with {@code -0.0} and {@code NaN} among the values.</b> Stock's own
 *       comment says it CASes bits precisely so a signalling-to-quiet NaN conversion cannot make the loop spin;
 *       {@code NaN != NaN}, so a value comparison would never terminate.</li>
 *   <li><b>Acquire, Release and Plain get the SAME treatment as plain</b>, not a smoke test: they delegate, so
 *       the failure available to them is delegating to the WRONG SIBLING, which any arm checking only
 *       "something changed" passes.</li>
 *   <li><b>The spanning guard is asserted</b> -- a 2-byte update at {@code (offset & 3) == 3} throws
 *       {@code IllegalArgumentException}, stock's own refusal, kept verbatim because it is also exactly strong
 *       enough to keep an element from spanning joe-ng's EIGHT-byte window.</li>
 * </ul>
 *
 * <p>ONE SOURCE, BOTH WORLDS: against the real JDK the arms reach stock {@code Unsafe}, against
 * {@code guestsrc} the overlay, and the gate is a byte-for-byte diff. Host control:
 * {@code java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}.
 *
 * <p>THE STATED PRECONDITION IS AN ARM RATHER THAN A CLAIM, AND MEASURING IT MADE IT SHARPER THAN THE CLAIM
 * WAS. The Int/Long/Reference/Float/Double atomics operate on the whole 8-byte slot -- exact for a FIELD,
 * wrong for an array element of scale below 8 -- because stock derives Float from Int and Long from itself and
 * this takes that derivation verbatim. I wrote that down as "takes its neighbour with it", i.e. silent
 * corruption. The last two arms show it is not silent: an {@code int[]} element sits at a 4-aligned address and
 * {@code LDAXR} requires EIGHT-byte alignment, so the CAS raises an alignment fault that the VM turns into a
 * catchable {@code NullPointerException}. A loud failure, not a quiet one -- which is the better of the two
 * outcomes and is the reason to measure a precondition instead of asserting it. They go through
 * {@code diverge}, so {@code failures=0} means the same thing in both worlds.
 */
public class UnsafeAtomicProbe
{
    boolean z;
    byte b;
    short s;
    char c;
    float f;
    double d;
    int i;

    static Unsafe u;
    static UnsafeAtomicProbe p;
    static int failures;
    static int divergences;

    static void say(String name, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + name + " = " + got + " (want " + want + ")");
    }

    /** An arm joe-ng answers DIFFERENTLY from a host JVM on purpose; counted apart so failures=0 travels. */
    static void diverge(String name, String got)
    {
        divergences++;
        System.out.println("diff " + name + " = " + got);
    }

    static String thrown(Runnable r)
    {
        try
        {
            r.run();
            return "no-throw";
        }
        catch (Throwable t)
        {
            return t.getClass().getName();
        }
    }

    // All three bitwise operators give a different answer from these, and all three differ from the start.
    static final int OS = 12;                  // 0b1100
    static final int OM = 10;                  // 0b1010 -> or 14, and 8, xor 6

    public static void main(String[] args) throws Exception
    {
        u = Unsafe.getUnsafe();
        p = new UnsafeAtomicProbe();
        long zo = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("z"));
        long bo = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("b"));
        long so = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("s"));
        long co = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("c"));
        long fo = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("f"));
        long dof = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("d"));
        long io = u.objectFieldOffset(UnsafeAtomicProbe.class.getDeclaredField("i"));

        // ---- FIELD, narrow CAS across a SIGN CHANGE. The plain field read is what catches a masked-only write.
        p.s = -2;
        say("cas short field", u.compareAndSetShort(p, so, (short) -2, (short) 5) + "/"
            + u.getShort(p, so) + "/" + p.s, "true/5/5");
        p.b = -2;
        say("cas byte field ", u.compareAndSetByte(p, bo, (byte) -2, (byte) 5) + "/"
            + u.getByte(p, bo) + "/" + p.b, "true/5/5");
        // ... and the other direction: positive -> negative must RE-extend the slot.
        p.s = 5;
        say("cas short neg  ", u.compareAndSetShort(p, so, (short) 5, (short) -2) + "/" + p.s, "true/-2");
        p.b = 5;
        say("cas byte neg   ", u.compareAndSetByte(p, bo, (byte) 5, (byte) -2) + "/" + p.b, "true/-2");

        // char is UNSIGNED: 0xFFFF must compare and store as 65535, never as -1.
        p.c = 0xFFFF;
        say("cas char field ", u.compareAndSetChar(p, co, (char) 0xFFFF, (char) 7) + "/" + ((int) p.c),
            "true/7");
        p.z = false;
        say("cas bool field ", u.compareAndSetBoolean(p, zo, false, true) + "/" + p.z, "true/true");

        // A FAILING cas must not change anything, and compareAndExchange must answer the WITNESSED value.
        p.s = -2;
        say("cas short miss ", u.compareAndSetShort(p, so, (short) 99, (short) 5) + "/" + p.s, "false/-2");
        say("cae short miss ", u.compareAndExchangeShort(p, so, (short) 99, (short) 5) + "/" + p.s, "-2/-2");
        say("cae short hit  ", u.compareAndExchangeShort(p, so, (short) -2, (short) 5) + "/" + p.s, "-2/5");
        p.b = -2;
        say("cae byte miss  ", u.compareAndExchangeByte(p, bo, (byte) 99, (byte) 5) + "/" + p.b, "-2/-2");
        p.c = 300;
        say("cae char miss  ", ((int) u.compareAndExchangeChar(p, co, (char) 99, (char) 5)) + "/" + ((int) p.c),
            "300/300");

        // Acquire / Release / Plain delegate -- so the failure available to them is the WRONG SIBLING.
        p.s = 1;
        say("cae short acq  ", u.compareAndExchangeShortAcquire(p, so, (short) 1, (short) 2) + "/" + p.s, "1/2");
        say("cae short rel  ", u.compareAndExchangeShortRelease(p, so, (short) 2, (short) 3) + "/" + p.s, "2/3");
        say("wcas short     ", u.weakCompareAndSetShort(p, so, (short) 3, (short) 4) + "/" + p.s, "true/4");
        say("wcas short pln ", u.weakCompareAndSetShortPlain(p, so, (short) 4, (short) 5) + "/" + p.s, "true/5");
        say("wcas short acq ", u.weakCompareAndSetShortAcquire(p, so, (short) 5, (short) 6) + "/" + p.s, "true/6");
        say("wcas short rel ", u.weakCompareAndSetShortRelease(p, so, (short) 6, (short) 7) + "/" + p.s, "true/7");
        p.b = 1;
        say("wcas byte acq  ", u.weakCompareAndSetByteAcquire(p, bo, (byte) 1, (byte) 2) + "/" + p.b, "true/2");
        p.z = true;
        say("wcas bool rel  ", u.weakCompareAndSetBooleanRelease(p, zo, true, false) + "/" + p.z, "true/false");

        // ---- getAndSet / getAndAdd / getAndBitwise on a FIELD. The RETURN is the value BEFORE the update.
        p.s = -2;
        say("gas short      ", u.getAndSetShort(p, so, (short) 5) + "/" + p.s, "-2/5");
        p.s = -2;
        say("gas short acq  ", u.getAndSetShortAcquire(p, so, (short) 5) + "/" + p.s, "-2/5");
        p.s = -2;
        say("gas short rel  ", u.getAndSetShortRelease(p, so, (short) 5) + "/" + p.s, "-2/5");
        p.b = -2;
        say("gas byte       ", u.getAndSetByte(p, bo, (byte) 5) + "/" + p.b, "-2/5");
        p.c = 300;
        say("gas char       ", ((int) u.getAndSetChar(p, co, (char) 7)) + "/" + ((int) p.c), "300/7");
        p.z = false;
        say("gas bool       ", u.getAndSetBoolean(p, zo, true) + "/" + p.z, "false/true");

        p.s = -2;
        say("gaa short      ", u.getAndAddShort(p, so, (short) 3) + "/" + p.s, "-2/1");
        p.s = -2;
        say("gaa short acq  ", u.getAndAddShortAcquire(p, so, (short) 3) + "/" + p.s, "-2/1");
        p.b = -2;
        say("gaa byte       ", u.getAndAddByte(p, bo, (byte) 3) + "/" + p.b, "-2/1");
        // char wraps at 0x10000 and must stay UNSIGNED through the add.
        p.c = 0xFFFF;
        say("gaa char wrap  ", ((int) u.getAndAddChar(p, co, (char) 2)) + "/" + ((int) p.c), "65535/1");

        p.s = (short) OS;
        say("or short       ", u.getAndBitwiseOrShort(p, so, (short) OM) + "/" + p.s, OS + "/" + (OS | OM));
        p.s = (short) OS;
        say("and short      ", u.getAndBitwiseAndShort(p, so, (short) OM) + "/" + p.s, OS + "/" + (OS & OM));
        p.s = (short) OS;
        say("xor short      ", u.getAndBitwiseXorShort(p, so, (short) OM) + "/" + p.s, OS + "/" + (OS ^ OM));
        p.s = (short) OS;
        say("xor short acq  ", u.getAndBitwiseXorShortAcquire(p, so, (short) OM) + "/" + p.s,
            OS + "/" + (OS ^ OM));
        p.b = (byte) OS;
        say("or byte        ", u.getAndBitwiseOrByte(p, bo, (byte) OM) + "/" + p.b, OS + "/" + (OS | OM));
        p.b = (byte) OS;
        say("xor byte rel   ", u.getAndBitwiseXorByteRelease(p, bo, (byte) OM) + "/" + p.b, OS + "/" + (OS ^ OM));
        p.c = (char) OS;
        say("and char       ", ((int) u.getAndBitwiseAndChar(p, co, (char) OM)) + "/" + ((int) p.c),
            OS + "/" + (OS & OM));
        p.z = true;
        say("and bool       ", u.getAndBitwiseAndBoolean(p, zo, false) + "/" + p.z, "true/false");
        p.z = false;
        say("or bool        ", u.getAndBitwiseOrBoolean(p, zo, true) + "/" + p.z, "false/true");
        p.z = true;
        say("xor bool       ", u.getAndBitwiseXorBoolean(p, zo, true) + "/" + p.z, "true/false");

        // ---- float / double, BY RAW BITS. -0.0 == 0.0 and NaN != NaN, so a value comparison lies both ways.
        p.f = -0.0f;
        say("cas float -0.0 ", u.compareAndSetFloat(p, fo, -0.0f, 1.5f) + "/" + p.f, "true/1.5");
        p.f = Float.NaN;
        say("cas float NaN  ", u.compareAndSetFloat(p, fo, Float.NaN, 2.5f) + "/" + p.f, "true/2.5");
        p.f = 1.5f;
        say("gas float      ", u.getAndSetFloat(p, fo, 2.5f) + "/" + p.f, "1.5/2.5");
        p.f = 1.5f;
        say("gaa float      ", u.getAndAddFloat(p, fo, 0.25f) + "/" + p.f, "1.5/1.75");
        p.d = -0.0;
        say("cas dbl -0.0   ", u.compareAndSetDouble(p, dof, -0.0, 1.5) + "/"
            + Double.doubleToRawLongBits(p.d), "true/" + Double.doubleToRawLongBits(1.5));
        p.d = Double.NaN;
        say("cas dbl NaN    ", u.compareAndSetDouble(p, dof, Double.NaN, 2.5) + "/" + p.d, "true/2.5");
        p.d = 1.5;
        say("gaa double     ", u.getAndAddDouble(p, dof, 0.25) + "/" + p.d, "1.5/1.75");
        p.f = 1.0f;
        say("cae float miss ", u.compareAndExchangeFloat(p, fo, 9.0f, 2.0f) + "/" + p.f, "1.0/1.0");

        // ---- ARRAY ELEMENTS. Every alignment inside the 8-byte window, neighbours asserted. This is the arm
        // a whole-slot CAS fails: it would take three neighbouring shorts with it.
        long sb = Unsafe.ARRAY_SHORT_BASE_OFFSET;
        int ss = Unsafe.ARRAY_SHORT_INDEX_SCALE;
        for (int k = 0; k < 4; k++)
        {
            short[] sa = { 10, 11, 12, 13, 14 };
            boolean okc = u.compareAndSetShort(sa, sb + (long) k * ss, (short) (10 + k), (short) -2);
            say("cas short[" + k + "]  ", okc + "/" + sa[0] + "," + sa[1] + "," + sa[2] + "," + sa[3] + "," + sa[4],
                "true/" + (k == 0 ? -2 : 10) + "," + (k == 1 ? -2 : 11) + "," + (k == 2 ? -2 : 12)
                + "," + (k == 3 ? -2 : 13) + ",14");
        }
        long bb = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        for (int k = 0; k < 8; k++)
        {
            byte[] ba = { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
            boolean okc = u.compareAndSetByte(ba, bb + k, (byte) k, (byte) -2);
            StringBuilder sbuf = new StringBuilder();
            for (int j = 0; j < 9; j++)
            {
                sbuf.append(j == 0 ? "" : ",").append(j == k ? -2 : j);
            }
            say("cas byte[" + k + "]   ", okc + "/" + sbuf, "true/" + sbuf);
        }
        char[] ca = { 'a', 'b', 'c', 'd' };
        say("cas char[2]    ", u.compareAndSetChar(ca, Unsafe.ARRAY_CHAR_BASE_OFFSET
            + 2L * Unsafe.ARRAY_CHAR_INDEX_SCALE, 'c', 'Z') + "/" + new String(ca), "true/abZd");
        boolean[] za = { false, false, false, false };
        say("cas bool[2]    ", u.compareAndSetBoolean(za, Unsafe.ARRAY_BOOLEAN_BASE_OFFSET + 2L, false, true)
            + "/" + za[0] + "," + za[1] + "," + za[2] + "," + za[3], "true/false,false,true,false");

        short[] ra = { 10, (short) OS, 12, 13 };
        say("or short[1]    ", u.getAndBitwiseOrShort(ra, sb + ss, (short) OM) + "/"
            + ra[0] + "," + ra[1] + "," + ra[2] + "," + ra[3], OS + "/10," + (OS | OM) + ",12,13");
        short[] aa = { 10, 11, 12, 13 };
        say("gaa short[2]   ", u.getAndAddShort(aa, sb + 2L * ss, (short) 5) + "/"
            + aa[1] + "," + aa[2] + "," + aa[3], "12/11,17,13");
        byte[] gb = { 0, 1, 2, 3 };
        say("gas byte[1]    ", u.getAndSetByte(gb, bb + 1, (byte) -2) + "/"
            + gb[0] + "," + gb[1] + "," + gb[2], "1/0,-2,2");

        // ---- Stock's spanning refusal: a 2-byte update at (offset & 3) == 3.
        final byte[] span = new byte[16];
        say("span refused   ", thrown(() -> u.compareAndSetShort(span, bb + 3, (short) 0, (short) 1)),
            "java.lang.IllegalArgumentException");
        say("span ok at 2   ", u.compareAndSetShort(span, bb + 2, (short) 0, (short) 1) + "", "true");

        // ---- Int / Long / Reference mode variants, which had no Acquire/Release forms declared.
        p.i = 7;
        say("cae int acq    ", u.compareAndExchangeIntAcquire(p, io, 7, 8) + "/" + p.i, "7/8");
        say("cae int rel    ", u.compareAndExchangeIntRelease(p, io, 8, 9) + "/" + p.i, "8/9");
        say("wcas int pln   ", u.weakCompareAndSetIntPlain(p, io, 9, 10) + "/" + p.i, "true/10");
        say("gaa int acq    ", u.getAndAddIntAcquire(p, io, 5) + "/" + p.i, "10/15");
        say("gas int rel    ", u.getAndSetIntRelease(p, io, 20) + "/" + p.i, "15/20");

        // ---- allocateInstance: no constructor runs, so every field stays zero; and stock's four refusals.
        UnsafeAtomicProbe fresh = (UnsafeAtomicProbe) u.allocateInstance(UnsafeAtomicProbe.class);
        say("allocInstance  ", fresh.i + "," + fresh.s + "," + fresh.z, "0,0,false");
        say("alloc iface    ", thrown(() -> { try { u.allocateInstance(Runnable.class); }
            catch (Exception e) { throw new RuntimeException(e.getClass().getName()); } }),
            "java.lang.RuntimeException");
        say("alloc array    ", thrown(() -> { try { u.allocateInstance(int[].class); }
            catch (Exception e) { throw new RuntimeException(e.getClass().getName()); } }),
            "java.lang.RuntimeException");

        // ---- THE STATED PRECONDITION, MEASURED. The Int/Long/Reference/Float/Double atomics act on the whole
        // 8-byte slot, so an element of scale below 8 takes its neighbour with it. Referenced only by
        // java/lang/invoke/VarHandleXxx$Array, a denied package -- but printed rather than merely claimed.
        final int[] ia = { 100, 200, 300 };
        diverge("int[] element  ", thrown(() -> u.compareAndSetInt(ia,
            Unsafe.ARRAY_INT_BASE_OFFSET + Unsafe.ARRAY_INT_INDEX_SCALE, 200, -2))
            + " -> " + ia[0] + "," + ia[1] + "," + ia[2]);
        final float[] fa = { 1.5f, 2.5f, 3.5f };
        diverge("float[] element", thrown(() -> u.compareAndSetFloat(fa,
            Unsafe.ARRAY_FLOAT_BASE_OFFSET + Unsafe.ARRAY_FLOAT_INDEX_SCALE, 2.5f, -1.5f))
            + " -> " + fa[0] + "," + fa[1] + "," + fa[2]);

        System.out.println("UnsafeAtomicProbe done, failures=" + failures
            + " divergences-unmet=" + divergences);
    }
}
