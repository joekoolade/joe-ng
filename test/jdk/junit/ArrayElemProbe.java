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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Does joe-ng build and drive a REAL {@code arrayElementVarHandle}? This is the sibling gap
 * {@code overlaycheck-deep} named beside {@code byteArrayViewVarHandle}, and unlike that one it carries the
 * FULL atomic surface -- which is why the factory was the small half of the increment.
 *
 * <p>THE DANGEROUS CASE, and the reason most of these arms check a NEIGHBOUR rather than the element. joe-ng's
 * int-width atomics were written on {@code Magic.cas64}: exact for a FIELD (a full 8-byte slot) and spanning
 * TWO elements of a 4-scale array. The project had this recorded as a LOUD failure -- an {@code int[]} element
 * is 4-aligned and LDAXR wants 8, so the CAS faults -- and that is only true at an ODD index. At an EVEN index
 * the address IS 8-aligned, so nothing faults and instead:
 * <ul>
 *   <li>the compare is a sign-extended 32-bit {@code expected} against TWO packed elements, so it answers a
 *       wrong {@code false} whenever the neighbour is non-zero; and</li>
 *   <li>when the neighbour IS zero the compare succeeds and the 8-byte store CLOBBERS it.</li>
 * </ul>
 * Both are SILENT, and this factory is what makes them reachable. So the three arms to read first are
 * {@code int[] cas @0} (neighbour non-zero -- catches the wrong false), {@code int[] cas @0 nb0} (neighbour
 * zero -- catches the clobber) and {@code int[] cas @1} (odd -- catches the fault).
 *
 * <p>EVERY ATOMIC ARM DUMPS THE WHOLE ARRAY, not just the element it touched. A neighbour clobbered two
 * elements over is invisible to an arm that only reads back what it wrote, and that is precisely the defect
 * shape here.
 *
 * <p>ALL NINE ELEMENT TYPES, because the shift comes from {@code Unsafe.arrayIndexScale} and a wrong scale
 * indexes every element at the wrong stride. The narrow widths carry NEGATIVE values: a byte or short element
 * is masked inside its enclosing word, so a sign slip is invisible for a positive value.
 *
 * <p>NOTHING PRINTS A SCALE OR A SHIFT, deliberately. {@code Object[]} is scale 4 on a host JVM (compressed
 * oops) and 8 here (direct refs) -- a platform fact this project already records -- so an arm naming either
 * could not have a single expected value. Behaviour is what must match, and it does.
 *
 * <p>EVERY ACCESS IS CAST AND ASSIGNED, which is required rather than tidy: a VarHandle access whose result is
 * DISCARDED compiles to an OBJECT-returning descriptor, and joe-ng's resolve is width-typed by descriptor, so
 * such a site binds to nothing. That limit is recorded on the byte-array-view card; here it dictates the shape
 * of every arm.
 *
 * <p>Run on a host JVM the same source reaches the real JDK, so the output is a byte-for-byte oracle.
 */
public class ArrayElemProbe
{
    static int failures = 0;

    static void check(String label, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures += 1;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + label + " = " + got + (ok ? "" : " (want " + want + ")"));
    }

    static String d(int[] a)     { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(long[] a)    { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(short[] a)   { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(byte[] a)    { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(char[] a)    { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append((int) a[i]); } return b.toString(); }
    static String d(boolean[] a) { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(float[] a)   { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(double[] a)  { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }
    static String d(Object[] a)  { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length; i += 1) { if (i > 0) { b.append(','); } b.append(a[i]); } return b.toString(); }

    interface Arm { void run() throws Throwable; }

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

    static int sinkI;

    public static void main(String[] args) throws Exception
    {
        VarHandle vi = MethodHandles.arrayElementVarHandle(int[].class);
        VarHandle vj = MethodHandles.arrayElementVarHandle(long[].class);
        VarHandle vs = MethodHandles.arrayElementVarHandle(short[].class);
        VarHandle vb = MethodHandles.arrayElementVarHandle(byte[].class);
        VarHandle vc = MethodHandles.arrayElementVarHandle(char[].class);
        VarHandle vz = MethodHandles.arrayElementVarHandle(boolean[].class);
        VarHandle vf = MethodHandles.arrayElementVarHandle(float[].class);
        VarHandle vd = MethodHandles.arrayElementVarHandle(double[].class);
        VarHandle vo = MethodHandles.arrayElementVarHandle(Object[].class);

        // ---- GET / SET for all nine, with the NEIGHBOURS asserted (a wrong shift shows here) -----------
        int[] ai = { 10, 20, 30, 40 };
        vi.set(ai, 2, -7);
        check("int   set    ", d(ai), "10,20,-7,40");
        check("int   get    ", Integer.toString((int) vi.get(ai, 2)), "-7");

        long[] aj = { 10L, 20L, 30L, 40L };
        vj.set(aj, 2, -7L);
        check("long  set    ", d(aj), "10,20,-7,40");
        check("long  get    ", Long.toString((long) vj.get(aj, 2)), "-7");

        short[] as = { 10, 20, 30, 40 };
        vs.set(as, 2, (short) -7);
        check("short set    ", d(as), "10,20,-7,40");
        check("short get    ", Short.toString((short) vs.get(as, 2)), "-7");

        byte[] ab = { 10, 20, 30, 40 };
        vb.set(ab, 2, (byte) -7);
        check("byte  set    ", d(ab), "10,20,-7,40");
        check("byte  get    ", Byte.toString((byte) vb.get(ab, 2)), "-7");

        char[] ac = { 10, 20, 30, 40 };
        vc.set(ac, 2, (char) 65533);
        check("char  set    ", d(ac), "10,20,65533,40");
        check("char  get    ", Integer.toString((char) vc.get(ac, 2)), "65533");

        boolean[] az = { false, false, false, false };
        vz.set(az, 2, true);
        check("bool  set    ", d(az), "false,false,true,false");
        check("bool  get    ", Boolean.toString((boolean) vz.get(az, 2)), "true");

        float[] af = { 1.0f, 2.0f, 3.0f, 4.0f };
        vf.set(af, 2, -7.5f);
        check("float set    ", d(af), "1.0,2.0,-7.5,4.0");
        check("float get    ", Float.toString((float) vf.get(af, 2)), "-7.5");

        double[] ad = { 1.0, 2.0, 3.0, 4.0 };
        vd.set(ad, 2, -7.5);
        check("dbl   set    ", d(ad), "1.0,2.0,-7.5,4.0");
        check("dbl   get    ", Double.toString((double) vd.get(ad, 2)), "-7.5");

        Object[] ao = { "a", "b", "c", "d" };
        vo.set(ao, 2, "Z");
        check("ref   set    ", d(ao), "a,b,Z,d");
        check("ref   get    ", (String) vo.get(ao, 2), "Z");

        // ---- THE THREE ARMS THE WHOLE INCREMENT TURNS ON ----------------------------------------------
        // EVEN index, neighbour NON-ZERO: pre-fix the compare saw (20<<32|10) against a sign-extended 10,
        // so it answered a wrong `false` and changed nothing.
        int[] c1 = { 10, 20, 30, 40 };
        check("int[] cas @0 ",
                Boolean.toString(vi.compareAndSet(c1, 0, 10, -2)) + " " + d(c1), "true -2,20,30,40");

        // EVEN index, neighbour ZERO: pre-fix the compare SUCCEEDED and the 8-byte store wrote -1 over the
        // neighbour. This is the silent-corruption arm; nothing else in the probe can see it.
        int[] c2 = { 10, 0, 30, 40 };
        check("int[] cas @0 nb0",
                Boolean.toString(vi.compareAndSet(c2, 0, 10, -2)) + " " + d(c2), "true -2,0,30,40");

        // ODD index: 4-aligned, so pre-fix LDAXR raised an alignment fault the VM turned into an NPE.
        int[] c3 = { 10, 20, 30, 40 };
        check("int[] cas @1 ",
                Boolean.toString(vi.compareAndSet(c3, 1, 20, -3)) + " " + d(c3), "true 10,-3,30,40");

        // A MISS must leave the array untouched and say so -- the other half of a CAS being correct.
        int[] c4 = { 10, 20, 30, 40 };
        check("int[] cas miss",
                Boolean.toString(vi.compareAndSet(c4, 0, 999, -2)) + " " + d(c4), "false 10,20,30,40");

        // float[] is scale 4 too, and stock derives its atomics from the int ones, so it rides the same fix.
        float[] cf = { 1.0f, 2.0f, 3.0f, 4.0f };
        check("float cas @0 ",
                Boolean.toString(vf.compareAndSet(cf, 0, 1.0f, -2.5f)) + " " + d(cf), "true -2.5,2.0,3.0,4.0");
        float[] cf2 = { 1.0f, 2.0f, 3.0f, 4.0f };
        check("float cas @1 ",
                Boolean.toString(vf.compareAndSet(cf2, 1, 2.0f, -2.5f)) + " " + d(cf2), "true 1.0,-2.5,3.0,4.0");

        // ---- THE REST OF THE ATOMIC SURFACE, at an EVEN and an ODD index, neighbours asserted ----------
        int[] g1 = { 10, 20, 30, 40 };
        check("int[] gAdd @0", Integer.toString((int) vi.getAndAdd(g1, 0, 5)) + " " + d(g1), "10 15,20,30,40");
        int[] g2 = { 10, 20, 30, 40 };
        check("int[] gAdd @1", Integer.toString((int) vi.getAndAdd(g2, 1, 5)) + " " + d(g2), "20 10,25,30,40");

        int[] g3 = { 10, 20, 30, 40 };
        check("int[] gSet @0", Integer.toString((int) vi.getAndSet(g3, 0, -9)) + " " + d(g3), "10 -9,20,30,40");
        int[] g4 = { 10, 20, 30, 40 };
        check("int[] gSet @1", Integer.toString((int) vi.getAndSet(g4, 1, -9)) + " " + d(g4), "20 10,-9,30,40");

        int[] g5 = { 0x0F, 0x20, 0x30, 0x40 };
        check("int[] gOr  @0", Integer.toString((int) vi.getAndBitwiseOr(g5, 0, 0xF0)) + " " + d(g5),
                "15 255,32,48,64");
        int[] g6 = { 0x0F, 0xFF, 0x30, 0x40 };
        check("int[] gAnd @1", Integer.toString((int) vi.getAndBitwiseAnd(g6, 1, 0x0F)) + " " + d(g6),
                "255 15,15,48,64");
        int[] g7 = { 0x0F, 0x20, 0x30, 0x40 };
        check("int[] gXor @0", Integer.toString((int) vi.getAndBitwiseXor(g7, 0, 0xFF)) + " " + d(g7),
                "15 240,32,48,64");

        // The volatile/acquire/release modes are separate access modes with their own generated bodies.
        int[] v1 = { 10, 20, 30, 40 };
        vi.setVolatile(v1, 1, -4);
        check("int[] setVol ", d(v1), "10,-4,30,40");
        check("int[] getVol ", Integer.toString((int) vi.getVolatile(v1, 1)), "-4");
        int[] v2 = { 10, 20, 30, 40 };
        vi.setRelease(v2, 2, -5);
        check("int[] setRel ", d(v2), "10,20,-5,40");
        check("int[] getAcq ", Integer.toString((int) vi.getAcquire(v2, 2)), "-5");
        int[] v3 = { 10, 20, 30, 40 };
        check("int[] cmpExch", Integer.toString((int) vi.compareAndExchange(v3, 1, 20, -6)) + " " + d(v3),
                "20 10,-6,30,40");
        int[] v4 = { 10, 20, 30, 40 };
        check("int[] weakCAS",
                Boolean.toString(vi.weakCompareAndSet(v4, 1, 20, -8)) + " " + d(v4), "true 10,-8,30,40");

        // ---- NARROW widths: masked inside the enclosing word, so a neighbour clobber is the risk ---------
        byte[] nb = { 10, 20, 30, 40, 50, 60, 70, 80 };
        check("byte  cas @3 ",
                Boolean.toString(vb.compareAndSet(nb, 3, (byte) 40, (byte) -2)) + " " + d(nb),
                "true 10,20,30,-2,50,60,70,80");
        short[] ns = { 10, 20, 30, 40 };
        check("short cas @1 ",
                Boolean.toString(vs.compareAndSet(ns, 1, (short) 20, (short) -2)) + " " + d(ns),
                "true 10,-2,30,40");
        char[] nc = { 10, 20, 30, 40 };
        check("char  cas @2 ",
                Boolean.toString(vc.compareAndSet(nc, 2, (char) 30, (char) 65533)) + " " + d(nc),
                "true 10,20,65533,40");
        boolean[] nz = { false, false, false, false };
        check("bool  cas @2 ",
                Boolean.toString(vz.compareAndSet(nz, 2, false, true)) + " " + d(nz),
                "true false,false,true,false");

        // ---- 8-scale widths, which were already correct: the CONTROL that must not move -----------------
        long[] lj = { 10L, 20L, 30L, 40L };
        check("long  cas @1 ",
                Boolean.toString(vj.compareAndSet(lj, 1, 20L, -2L)) + " " + d(lj), "true 10,-2,30,40");
        double[] ld = { 1.0, 2.0, 3.0, 4.0 };
        check("dbl   cas @1 ",
                Boolean.toString(vd.compareAndSet(ld, 1, 2.0, -2.5)) + " " + d(ld), "true 1.0,-2.5,3.0,4.0");
        Object[] lo = { "a", "b", "c", "d" };
        check("ref   cas @1 ",
                Boolean.toString(vo.compareAndSet(lo, 1, "b", "Z")) + " " + d(lo), "true a,Z,c,d");
        Object[] lo2 = { "a", "b", "c", "d" };
        check("ref   cas miss",
                Boolean.toString(vo.compareAndSet(lo2, 1, "nope", "Z")) + " " + d(lo2), "false a,b,c,d");

        // ---- BOUNDS: stock's Array handles check the index, and skipping it writes outside the array ----
        check("int   oob get", thrown(() -> { sinkI = (int) vi.get(new int[2], 2); }),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   oob set", thrown(() -> vi.set(new int[2], 2, 0)),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   neg get", thrown(() -> { sinkI = (int) vi.get(new int[2], -1); }),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   oob cas", thrown(() -> { boolean b = vi.compareAndSet(new int[2], 2, 0, 1); }),
                "java.lang.ArrayIndexOutOfBoundsException");
        check("int   last ok", thrown(() -> { sinkI = (int) vi.get(new int[2], 1); }), "none");

        // ---- REFUSAL -----------------------------------------------------------------------------------
        check("refuse scalar", thrown(() -> MethodHandles.arrayElementVarHandle(int.class)),
                "java.lang.IllegalArgumentException");
        check("refuse nonarr", thrown(() -> MethodHandles.arrayElementVarHandle(String.class)),
                "java.lang.IllegalArgumentException");

        System.out.println("ArrayElemProbe done, failures=" + failures);
    }
}
