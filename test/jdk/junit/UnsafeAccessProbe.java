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
 * The {@code jdk.internal.misc.Unsafe} ACCESSOR surface -- the narrow, float/double, memory-mode, absolute and
 * unaligned families the overlay had dropped, plus the Object-array constants and the layout queries.
 *
 * <p>WHY THIS EXISTS. An overlay wins the name, so a stock member it does not declare CEASES TO EXIST: the
 * call resolves nowhere and surfaces as a {@code DENYLIST TRAP} blaming a list {@code Unsafe} is not even on.
 * That trap has cost nine recorded sessions. Only {@code make overlaycheck-deep} sees these, because every
 * caller is stock java.base rather than anything we ship.
 *
 * <p>THE ARM THAT MATTERS IS THE ARRAY-NEIGHBOUR ARM, and it is what this whole increment turns on. joe-ng
 * addresses memory two ways behind one {@code (Object,long)} signature and they disagree about WIDTH: an
 * instance FIELD occupies a full 8-byte slot whatever its declared type, read and written with
 * {@code ldrx}/{@code strx}, while an ARRAY ELEMENT occupies its natural width at {@code 24 + index*scale}.
 * So the two plausible implementations each fail on exactly one side:
 * <ul>
 *   <li>write the NATURAL width always -&gt; a {@code short} field's slot keeps a stale high half, and
 *       ordinary Java then reads the field as a DIFFERENT NUMBER. Caught by the field arms, which read back
 *       through the plain field as well as through {@code Unsafe}.</li>
 *   <li>write the whole 8-byte SLOT always -&gt; an element write takes its NEIGHBOURS with it. Caught by the
 *       array arms, which write element [1] and assert [0] and [2] are untouched.</li>
 * </ul>
 * Neither the offset nor its alignment can tell the two apart -- offset 24 is both field slot 1 and array
 * element 0 -- so the overlay asks the OBJECT. Both arm sets must pass together, which is why they are here
 * together; either alone is satisfied by a wrong implementation.
 *
 * <p>THE OTHER DISCRIMINATING ARMS:
 * <ul>
 *   <li><b>Every narrow field arm carries a NEGATIVE or high-bit value</b> ({@code -2}, {@code 0xFFFF},
 *       {@code -1}): a byte/short must come back SIGN-extended and a char ZERO-extended, and an
 *       implementation that picked one rule for all of them reads 65534 where it should read -2. Zero and
 *       small positives pass under every extension rule and would test nothing.</li>
 *   <li><b>float and double are compared BY RAW BITS</b>, with {@code -0.0} and {@code NaN} among the values:
 *       {@code -0.0 == 0.0} is true and {@code NaN != NaN}, so a value comparison passes over a wrong answer
 *       in one direction and fails a right one in the other.</li>
 *   <li><b>The unaligned arms use an ODD offset</b> into a {@code byte[]}, which is the whole point of the
 *       family, and the {@code bigEndian} overloads are asserted against {@code Integer.reverseBytes} of the
 *       little-endian answer -- so a {@code convEndian} that ignored its flag, or reversed both arms, shows
 *       up. They also assert the bytes OUTSIDE the written region are untouched, because an unaligned put
 *       that routed through {@code putInt} (which writes a whole slot here) would overrun by four bytes.</li>
 *   <li><b>{@code copyMemory} is given an OVERLAPPING move in the direction that breaks a naive forward
 *       loop</b> (destination above source): stock's {@code copyMemory0} is a memmove, so the direction
 *       choice is part of the contract rather than a nicety.</li>
 *   <li><b>The Object-array constants are USED to address an element</b>, not printed. A pair that was
 *       merely non-zero would pass a printed check and read the wrong word here.</li>
 * </ul>
 *
 * <p>NO EXPECTED VALUES ARE TRANSCRIBED. Every {@code want} is computed by Java's own operators, which is an
 * oracle independent of this overlay. And the whole file is ONE SOURCE compiled against BOTH worlds: against
 * the real JDK the arms reach stock {@code Unsafe}, against {@code guestsrc} they reach the overlay, so the
 * gate is a byte-for-byte diff of the two runs. Host control:
 * {@code java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}.
 *
 * <p>MUST-MATCH AND DELIBERATELY-DIFFERENT ARE COUNTED APART. The off-heap stubs THROW here and SUCCEED on a
 * host JVM, so reporting them as failures would train a reader to ignore the count; they print through
 * {@code diverge} against a second counter, and {@code failures=0} therefore means the same thing in both
 * worlds.
 *
 * <p>NOT PROBED, and stated rather than left to be assumed: the fourteen ABSOLUTE {@code getX(long)}/
 * {@code putX(long,x)} forms. They are stock's own one-line bodies over the {@code (Object,long)} forms these
 * arms do cover, and the null-base arm of {@code at} they rest on is the one
 * {@code staticFieldOffset}/{@code staticFieldBase} have used since they were written. Exercising them needs
 * an absolute address, which no single source can name in both worlds -- a host JVM cannot hand out a Java
 * object's address and metal cannot {@code allocateMemory}.
 */
public class UnsafeAccessProbe
{
    // Instance fields, one per width under test.
    boolean z;
    byte b;
    short s;
    char c;
    float f;
    double d;

    static Unsafe u;
    static UnsafeAccessProbe p;
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

    public static void main(String[] args) throws Exception
    {
        u = Unsafe.getUnsafe();
        p = new UnsafeAccessProbe();

        long zo = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("z"));
        long bo = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("b"));
        long so = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("s"));
        long co = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("c"));
        long fo = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("f"));
        long dob = u.objectFieldOffset(UnsafeAccessProbe.class.getDeclaredField("d"));

        // ---- FIELDS: a narrow write must leave the slot in the state ordinary Java reads back. Each arm
        // reports BOTH the Unsafe read and the plain field, because an implementation can get one right and
        // the other wrong -- that is exactly the stale-high-half failure.
        u.putBoolean(p, zo, true);
        say("field boolean", u.getBoolean(p, zo) + "/" + p.z, "true/true");

        u.putByte(p, bo, (byte) -2);
        say("field byte   ", u.getByte(p, bo) + "/" + p.b, "-2/-2");

        u.putShort(p, so, (short) -2);
        say("field short  ", u.getShort(p, so) + "/" + p.s, "-2/-2");

        // char is UNSIGNED: 0xFFFF must read 65535, not -1.
        u.putChar(p, co, (char) 0xFFFF);
        say("field char   ", ((int) u.getChar(p, co)) + "/" + ((int) p.c), "65535/65535");

        // Raw bits, because -0.0 == 0.0 is true and would pass over a sign that was lost.
        u.putFloat(p, fo, -0.0f);
        say("field float  ", Float.floatToRawIntBits(u.getFloat(p, fo)) + "/" + Float.floatToRawIntBits(p.f),
            Float.floatToRawIntBits(-0.0f) + "/" + Float.floatToRawIntBits(-0.0f));

        u.putDouble(p, dob, Double.NaN);
        say("field double ", Double.doubleToRawLongBits(u.getDouble(p, dob)) + "/"
            + Double.doubleToRawLongBits(p.d),
            Double.doubleToRawLongBits(Double.NaN) + "/" + Double.doubleToRawLongBits(Double.NaN));

        // ---- MEMORY MODES on a narrow field. They delegate, so the failure available to them is delegating
        // to the wrong sibling or to the wrong WIDTH; the plain field read is what catches the second.
        u.putShortVolatile(p, so, (short) -3);
        say("short volatile", u.getShortVolatile(p, so) + "/" + p.s, "-3/-3");
        say("short acquire ", "" + u.getShortAcquire(p, so), "-3");
        say("short opaque  ", "" + u.getShortOpaque(p, so), "-3");
        u.putShortRelease(p, so, (short) -4);
        say("short release ", u.getShort(p, so) + "/" + p.s, "-4/-4");
        u.putCharOpaque(p, co, (char) 258);
        say("char opaque   ", ((int) u.getCharAcquire(p, co)) + "/" + ((int) p.c), "258/258");
        u.putBooleanVolatile(p, zo, false);
        say("bool volatile ", u.getBooleanAcquire(p, zo) + "/" + p.z, "false/false");
        u.putByteVolatile(p, bo, (byte) -5);
        say("byte volatile ", u.getByteOpaque(p, bo) + "/" + p.b, "-5/-5");
        u.putFloatVolatile(p, fo, 1.5f);
        say("float volatile", u.getFloatAcquire(p, fo) + "/" + p.f, "1.5/1.5");
        u.putDoubleVolatile(p, dob, 2.5);
        say("dbl volatile  ", u.getDoubleOpaque(p, dob) + "/" + p.d, "2.5/2.5");

        // ---- ARRAY ELEMENTS: write [1], assert [0] and [2] UNTOUCHED. This is the arm a whole-slot write
        // fails, and the reason the overlay has to ask the object which layout it is addressing.
        byte[] ba = { 11, 0, 33 };
        u.putByte(ba, Unsafe.ARRAY_BYTE_BASE_OFFSET + 1, (byte) -2);
        say("array byte   ", ba[0] + "," + ba[1] + "," + ba[2] + "/" + u.getByte(ba, Unsafe.ARRAY_BYTE_BASE_OFFSET + 1),
            "11,-2,33/-2");

        short[] sa = { 111, 0, 333 };
        u.putShort(sa, Unsafe.ARRAY_SHORT_BASE_OFFSET + Unsafe.ARRAY_SHORT_INDEX_SCALE, (short) -2);
        say("array short  ", sa[0] + "," + sa[1] + "," + sa[2], "111,-2,333");

        char[] ca = { 'a', 'x', 'c' };
        u.putChar(ca, Unsafe.ARRAY_CHAR_BASE_OFFSET + Unsafe.ARRAY_CHAR_INDEX_SCALE, (char) 0xFFFF);
        say("array char   ", ca[0] + "," + ((int) ca[1]) + "," + ca[2], "a,65535,c");

        boolean[] za = { true, false, true };
        u.putBoolean(za, Unsafe.ARRAY_BOOLEAN_BASE_OFFSET + 1, true);
        say("array boolean", za[0] + "," + za[1] + "," + za[2], "true,true,true");

        float[] fa = { 1.25f, 0.0f, 3.75f };
        u.putFloat(fa, Unsafe.ARRAY_FLOAT_BASE_OFFSET + Unsafe.ARRAY_FLOAT_INDEX_SCALE, -0.0f);
        say("array float  ", fa[0] + "," + Float.floatToRawIntBits(fa[1]) + "," + fa[2],
            "1.25," + Float.floatToRawIntBits(-0.0f) + ",3.75");

        double[] da = { 1.25, 0.0, 3.75 };
        u.putDouble(da, Unsafe.ARRAY_DOUBLE_BASE_OFFSET + Unsafe.ARRAY_DOUBLE_INDEX_SCALE, 9.5);
        say("array double ", da[0] + "," + da[1] + "," + da[2], "1.25,9.5,3.75");

        // The Object-array pair, USED rather than printed: a merely non-zero pair reads the wrong word.
        Object[] oa = { "zero", "one", "two" };
        say("array object ",
            (String) u.getReference(oa, Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE),
            "one");

        // ---- UNALIGNED over a byte[]: an ODD offset, and the bytes outside the region must be untouched.
        byte[] ua = new byte[16];
        long ub = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        u.putIntUnaligned(ua, ub + 1, 0x01020304);
        say("unaligned int ", "" + u.getIntUnaligned(ua, ub + 1), "" + 0x01020304);
        say("unaligned edge", ua[0] + "," + ua[5], "0,0");
        say("unaligned BE  ", "" + u.getIntUnaligned(ua, ub + 1, true), "" + Integer.reverseBytes(0x01020304));
        u.putIntUnaligned(ua, ub + 8, 0x01020304, true);
        say("unaligned putBE", "" + u.getIntUnaligned(ua, ub + 8), "" + Integer.reverseBytes(0x01020304));

        // A FRESH array per width, so each edge assertion means "this put wrote exactly its own bytes"
        // rather than "some earlier put happened to leave a zero there" -- the first cut of this arm read a
        // byte the int put above had already filled, and the host oracle is what caught the transcription.
        byte[] sua = new byte[16];
        u.putShortUnaligned(sua, ub + 1, (short) -2);
        say("unaligned short", u.getShortUnaligned(sua, ub + 1) + "/" + sua[0] + "," + sua[3], "-2/0,0");
        say("unaligned sBE  ", "" + u.getShortUnaligned(sua, ub + 1, true), "" + Short.reverseBytes((short) -2));

        byte[] cua = new byte[16];
        u.putCharUnaligned(cua, ub + 5, (char) 0xBEEF);
        say("unaligned char ", ((int) u.getCharUnaligned(cua, ub + 5)) + "/" + cua[4] + "," + cua[7],
            0xBEEF + "/0,0");
        say("unaligned cBE  ", "" + ((int) u.getCharUnaligned(cua, ub + 5, true)),
            "" + ((int) Character.reverseBytes((char) 0xBEEF)));

        byte[] lua = new byte[16];
        u.putLongUnaligned(lua, ub + 3, 0x0102030405060708L);
        say("unaligned long ", u.getLongUnaligned(lua, ub + 3) + "/" + lua[2] + "," + lua[11],
            0x0102030405060708L + "/0,0");
        say("unaligned lBE  ", "" + u.getLongUnaligned(lua, ub + 3, true),
            "" + Long.reverseBytes(0x0102030405060708L));
        u.putLongUnaligned(lua, ub + 3, 0x0102030405060708L, true);
        say("unaligned lputBE", "" + u.getLongUnaligned(lua, ub + 3),
            "" + Long.reverseBytes(0x0102030405060708L));

        // ---- BULK MOVES. The overlapping arm moves UP, which is the direction a naive forward loop corrupts.
        final byte[] src = { 1, 2, 3, 4, 5 };
        final byte[] dst = new byte[5];
        u.copyMemory(src, ub, dst, ub, 5);
        say("copyMemory    ", dst[0] + "," + dst[1] + "," + dst[2] + "," + dst[3] + "," + dst[4], "1,2,3,4,5");

        byte[] ov = { 1, 2, 3, 4, 5 };
        u.copyMemory(ov, ub, ov, ub + 2, 3);
        say("copy overlap  ", ov[0] + "," + ov[1] + "," + ov[2] + "," + ov[3] + "," + ov[4], "1,2,1,2,3");

        byte[] fill = new byte[4];
        u.setMemory(fill, ub + 1, 2, (byte) -1);
        say("setMemory     ", fill[0] + "," + fill[1] + "," + fill[2] + "," + fill[3], "0,-1,-1,0");

        // ---- LAYOUT QUERIES. Factual on this platform rather than chosen.
        say("ADDRESS_SIZE  ", Unsafe.ADDRESS_SIZE + "/" + u.addressSize(), "8/8");
        say("unalignedAcc  ", "" + u.unalignedAccess(), "true");
        u.storeStoreFence();
        say("storeStoreFence", "returned", "returned");
        say("isBigEndian   ", "" + u.isBigEndian(), "false");

        // Stock REFUSES a non-primitive-array base and a negative size, with IllegalArgumentException; the
        // overlay uses stock's own copyMemoryChecks/setMemoryChecks, so these are must-match arms rather than
        // divergences. The host control is what established that -- the first cut invented an InternalError
        // here and the oracle showed stock already had an answer.
        final long fOff2 = fo;
        final long dOff2 = dob;
        say("copy scalar base", thrown(() -> u.copyMemory(p, fOff2, p, dOff2, 4L)),
            "java.lang.IllegalArgumentException");
        say("copy neg size  ", thrown(() -> u.copyMemory(src, ub, dst, ub, -1L)),
            "java.lang.IllegalArgumentException");
        say("set scalar base", thrown(() -> u.setMemory(p, fOff2, 4L, (byte) 0)),
            "java.lang.IllegalArgumentException");
        // A REFERENCE array is refused too: a byte-granular walk over it would copy raw pointers as bytes.
        // This is the arm that made the element-KIND question worth answering rather than a bare is-an-array.
        final Object[] refs = { "a", "b" };
        say("copy ref array ", thrown(() -> u.copyMemory(refs, ub, refs, ub, 8L)),
            "java.lang.IllegalArgumentException");

        // ---- DELIBERATELY DIFFERENT: there is no off-heap memory under this VM, so these THROW where a host
        // JVM answers. Rule 3 -- a stub that returned a plausible address would have every later access
        // scribble on whatever lives there, while a throw names itself the moment it is reached.
        // Every address handed to these is one the HOST can survive: allocateMemory leaks 16 bytes and the
        // scalar-base copy moves four bytes between two of this probe's OWN fields. freeMemory is
        // deliberately absent -- on a host JVM a fabricated address takes the whole process down, and it is
        // the same one-line throw as allocateMemory, so probing it would buy a crashed control.
        final long fOff = fo;
        final long dOff = dob;
        diverge("allocateMemory", thrown(() -> u.allocateMemory(16L)));
        diverge("pageSize      ", thrown(() -> u.pageSize()));
        diverge("isWriteback   ", "" + u.isWritebackEnabled());

        System.out.println("UnsafeAccessProbe done, failures=" + failures
            + " divergences-unmet=" + divergences);
    }
}
