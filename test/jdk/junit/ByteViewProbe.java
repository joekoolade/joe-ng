/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-02
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Does joe-ng build and drive a REAL byte-array-view {@code VarHandle}? These are the handles that view a
 * {@code byte[]} as an array of a wider primitive, and until this probe's own increment
 * {@code MethodHandles.byteArrayViewVarHandle} was a member the name-winning overlay DID NOT DECLARE -- so it
 * ceased to exist and every call resolved nowhere.
 *
 * <p>THE DEFECT WAS REACHABLE AND MEASURED BEFORE THE FIX, not argued for. Every multi-byte read on a stock
 * {@code java.io.DataInputStream} goes {@code readInt -> jdk.internal.util.ByteArray.getInt -> } one of these
 * handles, and {@code ByteArray.<clinit>} builds all six through that factory. On QEMU, pre-fix:
 * {@code LINK FAILED: java/lang/invoke/MethodHandles.byteArrayViewVarHandle(...)} then a
 * {@code DENYLIST TRAP} blaming a list {@code MethodHandles} is not even on. So
 * {@code DataInputStream.readInt()} HALTED THE VM.
 *
 * <p>EVERY ARM DUMPS THE WHOLE 16-BYTE ARRAY AS HEX, which is deliberate and is what makes one string catch
 * four different defects at once: the WIDTH written (a wrong-width handle writes 2 or 8 bytes where 4
 * belong), the BYTE ORDER (big- vs little-endian reverse each other), the OFFSET (an index scaled by the
 * wrong stride lands elsewhere), and any OVERRUN past the intended span. A probe that only compared the
 * value read back would pass over all four.
 *
 * <p>BOTH BYTE ORDERS, EVERY WIDTH. A factory that ignored its {@code ByteOrder} argument -- the easiest
 * mistake here -- passes a big-endian-only probe perfectly. Real java.base exercises both:
 * {@code jdk.internal.util.ByteArray} builds its six handles BIG-endian and
 * {@code ByteArrayLittleEndian} builds them little-endian.
 *
 * <p>THE UNALIGNED ARMS ARE THE POINT OF THE WHOLE CLASS, and one of them is sharper than the rest. These
 * handles exist to read a wide value at an arbitrary byte offset, so an implementation that only worked at
 * index 0 would pass a lazier probe. {@code long} at index 5 is the arm to watch: the element sits at
 * {@code 24 + 5 = 29} and spans {@code [29,37)}, STRADDLING the 8-byte word boundary at 32. joe-ng's
 * {@code Unsafe.putLongUnaligned} does not split that the way stock does -- it issues ONE unaligned
 * {@code Magic.store64} and leaves the straddle to the hardware -- so this arm is what says that is sound.
 *
 * <p>STATED LIMIT, found by this probe and NOT fixed here: a VarHandle access whose result is DISCARDED or
 * used as {@code Object} compiles to an OBJECT-RETURNING descriptor, and joe-ng cannot resolve one. javac
 * emits {@code get:([BI)Ljava/lang/Object;} for a bare {@code vh.get(b, i)} in a void context; the uniform
 * descriptor rule rewrites that to {@code (LVarHandle;LObject;I)LObject;} while the generated accessor is
 * {@code (LVarHandle;LObject;I)I}, so the site binds to NOTHING -- measured on QEMU as
 * {@code VIRTUALRESOLVE FAILED ...ArrayHandle.get([BI)Ljava/lang/Object;}. Stock adapts the return through
 * the VarHandle invoker, which needs the MethodHandle runtime this VM does not carry.
 *
 * <p>IT IS DOCUMENTED RATHER THAN ARMED, deliberately: the failure is a HALTING denylist trap, so an arm
 * asserting it would end the probe instead of reporting it. The limit is NARROW rather than alarming -- it
 * bites only a PRIMITIVE handle whose value is thrown away, which real code has no reason to do (a
 * reference-typed handle returns Object legitimately, and those resolve) -- and it applies equally to the
 * FIELD handles, so it predates this increment.
 *
 * <p>Run on a host JVM the same source reaches the real JDK, so the output is a byte-for-byte oracle.
 */
public class ByteViewProbe
{
    static int failures = 0;

    /**
     * A SINK for the bounds arms below, and it is load-bearing rather than tidy. A VarHandle access
     * whose result is DISCARDED compiles to an OBJECT-returning descriptor -- javac emits
     * {@code get:([BI)Ljava/lang/Object;} for a bare {@code vh.get(b, i)} in a void context -- and
     * joe-ng's resolve is width-typed BY DESCRIPTOR, so such a site binds to nothing:
     * {@code VIRTUALRESOLVE FAILED ...ArrayHandle.get([BI)Ljava/lang/Object;}, measured on QEMU.
     * Assigning through the cast is what real code does and what keeps these arms about BOUNDS.
     * See the class javadoc's STATED LIMIT.
     */
    static int sinkI;

    static void check(String label, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures += 1;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + label + " = " + got + (ok ? "" : " (want " + want + ")"));
    }

    /** A fresh zeroed array per arm, so every assertion means "this set wrote exactly its own bytes". */
    static byte[] fresh()
    {
        return new byte[16];
    }

    static String hex(byte[] b)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i += 1)
        {
            int v = b[i] & 0xFF;
            sb.append("0123456789abcdef".charAt(v >>> 4));
            sb.append("0123456789abcdef".charAt(v & 0xF));
        }
        return sb.toString();
    }

    static VarHandle vh(Class<?> view, boolean big)
    {
        return MethodHandles.byteArrayViewVarHandle(view,
                big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws Exception
    {
        // ---- WIDTH AND BYTE ORDER, each width in BOTH orders -------------------------------------------
        // The values carry DISTINCT bytes on purpose: 0x01020304 reversed is visibly different, where a
        // palindrome or a small value would read the same either way and discriminate nothing.
        byte[] b;

        VarHandle si = vh(short[].class, true);
        b = fresh();
        si.set(b, 0, (short) 0x0102);
        check("short BE set ", hex(b), "01020000000000000000000000000000");
        check("short BE get ", Integer.toHexString((short) si.get(b, 0) & 0xFFFF), "102");

        VarHandle sl = vh(short[].class, false);
        b = fresh();
        sl.set(b, 0, (short) 0x0102);
        check("short LE set ", hex(b), "02010000000000000000000000000000");

        VarHandle ci = vh(char[].class, true);
        b = fresh();
        ci.set(b, 0, (char) 0xFEDC);
        check("char  BE set ", hex(b), "fedc0000000000000000000000000000");
        check("char  BE get ", Integer.toHexString((char) ci.get(b, 0)), "fedc");

        VarHandle cl = vh(char[].class, false);
        b = fresh();
        cl.set(b, 0, (char) 0xFEDC);
        check("char  LE set ", hex(b), "dcfe0000000000000000000000000000");

        VarHandle ii = vh(int[].class, true);
        b = fresh();
        ii.set(b, 0, 0x01020304);
        check("int   BE set ", hex(b), "01020304000000000000000000000000");
        check("int   BE get ", Integer.toHexString((int) ii.get(b, 0)), "1020304");

        VarHandle il = vh(int[].class, false);
        b = fresh();
        il.set(b, 0, 0x01020304);
        check("int   LE set ", hex(b), "04030201000000000000000000000000");
        check("int   LE get ", Integer.toHexString((int) il.get(b, 0)), "1020304");

        VarHandle ji = vh(long[].class, true);
        b = fresh();
        ji.set(b, 0, 0x0102030405060708L);
        check("long  BE set ", hex(b), "01020304050607080000000000000000");
        check("long  BE get ", Long.toHexString((long) ji.get(b, 0)), "102030405060708");

        VarHandle jl = vh(long[].class, false);
        b = fresh();
        jl.set(b, 0, 0x0102030405060708L);
        check("long  LE set ", hex(b), "08070605040302010000000000000000");

        // float/double go through a RAW-BITS detour in the generated code (getIntUnaligned then
        // intBitsToFloat), so they are a different path from the integral widths, not more of the same.
        VarHandle fi = vh(float[].class, true);
        b = fresh();
        fi.set(b, 0, 1.5f);
        check("float BE set ", hex(b), "3fc00000000000000000000000000000");
        check("float BE get ", Float.toString((float) fi.get(b, 0)), "1.5");

        VarHandle fl = vh(float[].class, false);
        b = fresh();
        fl.set(b, 0, 1.5f);
        check("float LE set ", hex(b), "0000c03f000000000000000000000000");

        VarHandle di = vh(double[].class, true);
        b = fresh();
        di.set(b, 0, 2.5);
        check("dbl   BE set ", hex(b), "40040000000000000000000000000000");
        check("dbl   BE get ", Double.toString((double) di.get(b, 0)), "2.5");

        VarHandle dl = vh(double[].class, false);
        b = fresh();
        dl.set(b, 0, 2.5);
        check("dbl   LE set ", hex(b), "00000000000004400000000000000000");

        // ---- UNALIGNED INDICES: the whole reason this handle shape exists ------------------------------
        // index 1/2/3 cover every value of (offset & 3), i.e. all three arms of stock's own split, which
        // joe-ng collapses into one unaligned store.
        for (int k = 1; k <= 3; k += 1)
        {
            b = fresh();
            ii.set(b, k, 0x01020304);
            String want = "00000000000000000000000000000000".substring(0, k * 2)
                    + "01020304"
                    + "00000000000000000000000000000000".substring((k + 4) * 2);
            check("int   BE @" + k + "  ", hex(b), want);
            check("int   BE @" + k + " rt", Integer.toHexString((int) ii.get(b, k)), "1020304");
        }

        // THE STRADDLE ARM. offset 24+5 = 29, eight bytes -> [29,37), crossing the 8-byte word boundary at
        // 32. joe-ng issues ONE unaligned Magic.store64 here where stock would split; if that were wrong
        // the high half of the value would land in the wrong word and this is the only arm that shows it.
        b = fresh();
        ji.set(b, 5, 0x0102030405060708L);
        check("long  BE @5  ", hex(b), "00000000000102030405060708000000");
        check("long  BE @5 rt", Long.toHexString((long) ji.get(b, 5)), "102030405060708");

        // A PURE GET from a pre-filled array, so a broken set cannot mask a broken get (every arm above
        // round-trips through this implementation's own set).
        byte[] pre = { 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
                       0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19 };
        check("int   BE pure", Integer.toHexString((int) ii.get(pre, 3)), "d0e0f10");
        check("int   LE pure", Integer.toHexString((int) il.get(pre, 3)), "100f0e0d");
        check("long  BE pure", Long.toHexString((long) ji.get(pre, 1)), "b0c0d0e0f101112");

        // ---- BOUNDS: index() is Preconditions.checkIndex(index, len - ALIGN, AIOOBE_FORMATTER) ----------
        // An implementation that skipped this would WRITE OUTSIDE THE ARRAY, which is the worst failure
        // available on this VM -- so the throw is asserted, not the absence of a crash.
        check("int   oob get", thrown(() -> { sinkI = (int) ii.get(new byte[4], 1); }),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   oob set", thrown(() -> ii.set(new byte[4], 1, 0)),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   neg get", thrown(() -> { sinkI = (int) ii.get(new byte[8], -1); }),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   last ok", thrown(() -> { sinkI = (int) ii.get(new byte[4], 0); }), "none");

        // ---- REFUSALS, all three stock's own ------------------------------------------------------------
        // byte[] and boolean[] are ABSENT BY DESIGN: a one-byte view of a byte[] has nothing to do, so the
        // JDK generates no such handle. Answering a plausible one would be the silent wrong answer.
        check("refuse byte[]", thrown(() -> vh(byte[].class, true)),
                "java.lang.UnsupportedOperationException");
        check("refuse bool[]", thrown(() -> vh(boolean[].class, true)),
                "java.lang.UnsupportedOperationException");
        check("refuse scalar", thrown(() -> vh(int.class, true)),
                "java.lang.IllegalArgumentException");
        check("refuse nonarr", thrown(() -> vh(String.class, true)),
                "java.lang.IllegalArgumentException");
        check("refuse null bo", thrown(() -> MethodHandles.byteArrayViewVarHandle(int[].class, null)),
                "java.lang.NullPointerException");

        // ---- THE REAL CONSUMER: stock java.io, which is what made this reachable ------------------------
        // jdk.internal.util.ByteArray is not exported, so it cannot be named from a probe -- and reaching it
        // through DataInputStream is the better arm regardless, because that is the path real code takes.
        byte[] io = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(io));
        check("dis readInt ", Integer.toHexString(in.readInt()), "1020304");
        check("dis readInt2", Integer.toHexString(in.readInt()), "5060708");

        in = new DataInputStream(new ByteArrayInputStream(io));
        check("dis readLong", Long.toHexString(in.readLong()), "102030405060708");

        in = new DataInputStream(new ByteArrayInputStream(io));
        check("dis readShrt", Integer.toHexString(in.readShort() & 0xFFFF), "102");
        check("dis readChar", Integer.toHexString(in.readChar()), "304");
        check("dis readFlt ", Float.toString(in.readFloat()), "6.301941E-36");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x01020304);
        out.writeLong(0x0506070809000102L);
        out.writeShort(0x0A0B);
        out.writeChar(0x0C0D);
        out.writeDouble(2.5);
        out.flush();
        check("dos writes  ", hex(bos.toByteArray()),
                "010203040506070809000102" + "0a0b" + "0c0d" + "4004000000000000");

        // A ROUND TRIP through both, which is what a real caller does and what would catch the two
        // disagreeing about byte order (each alone could be self-consistently wrong).
        bos = new ByteArrayOutputStream();
        out = new DataOutputStream(bos);
        out.writeInt(-2);
        out.writeLong(Long.MIN_VALUE);
        out.writeDouble(-0.0);
        out.flush();
        in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        check("io roundtrip", Integer.toString(in.readInt()) + "/" + Long.toString(in.readLong())
                + "/" + Double.toString(in.readDouble()),
                "-2/-9223372036854775808/-0.0");

        System.out.println("ByteViewProbe done, failures=" + failures);
    }

    interface Arm
    {
        void run() throws Throwable;
    }

    /** The THROWN class name, or "none". Naming the class is what separates a bounds check from a crash. */
    static String thrown(Arm a)
    {
        try
        {
            a.run();
            return "none";
        }
        catch (Throwable t)
        {
            return t.getClass().getName();
        }
    }
}
