/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.lang.reflect.Field;

/**
 * REFLECTIVE PRIMITIVE FIELD ACCESS, read back by COMPILED code. Every narrow field starts NEGATIVE (or with high
 * bits set) and is then overwritten reflectively with a small positive value, and the result is read through an
 * ordinary {@code getfield} and used in arithmetic that a stale high half would corrupt: equality, division, a
 * widening to long. Then float and double through {@code get}/{@code set} (boxed) and
 * {@code getFloat}/{@code getDouble}/{@code setFloat}/{@code setDouble}, including a negative zero and a NaN-free
 * fraction. Prints values only; the host diff is the gate.
 */
public class FieldPrimProbe
{
    int i = -1;
    short s = -1;
    byte b = -1;
    char c = 0xFFFF;
    boolean z = true;
    long l = -1L;
    float f = -2.5f;
    double d = -7.25;

    public static void main(String[] args) throws Exception
    {
        FieldPrimProbe p = new FieldPrimProbe();
        Class<?> k = FieldPrimProbe.class;
        k.getDeclaredField("i").setInt(p, 5);
        System.out.println("setInt: i==5 " + (p.i == 5) + " i/5=" + (p.i / 5) + " (long)i=" + (long) p.i);
        k.getDeclaredField("i").set(p, Integer.valueOf(10));
        System.out.println("set(Integer): i==10 " + (p.i == 10) + " i/5=" + (p.i / 5) + " (long)i=" + (long) p.i);
        k.getDeclaredField("s").setShort(p, (short) 6);
        System.out.println("setShort: s==6 " + (p.s == 6) + " s/3=" + (p.s / 3) + " (long)s=" + (long) p.s);
        k.getDeclaredField("b").setByte(p, (byte) 7);
        System.out.println("setByte: b==7 " + (p.b == 7) + " (long)b=" + (long) p.b);
        k.getDeclaredField("c").setChar(p, 'A');
        System.out.println("setChar: c=='A' " + (p.c == 'A') + " (int)c=" + (int) p.c);
        k.getDeclaredField("z").setBoolean(p, false);
        System.out.println("setBoolean: z=" + p.z + " !z=" + (!p.z));
        k.getDeclaredField("l").setLong(p, 3L);
        System.out.println("setLong: l=" + p.l);

        Field ff = k.getDeclaredField("f");
        Field df = k.getDeclaredField("d");
        System.out.println("get f=" + ff.get(p) + " getFloat=" + ff.getFloat(p));
        System.out.println("get d=" + df.get(p) + " getDouble=" + df.getDouble(p));
        ff.setFloat(p, 1.75f);
        df.setDouble(p, 0.125);
        System.out.println("setFloat/setDouble: f=" + p.f + " d=" + p.d + " f*2=" + (p.f * 2) + " d*8=" + (p.d * 8));
        ff.set(p, Float.valueOf(-0.0f));
        df.set(p, Double.valueOf(3.5));
        System.out.println("set(boxed): f=" + p.f + " d=" + p.d + " get f=" + ff.get(p) + " get d=" + df.get(p));
        System.out.println("getDouble of float field=" + ff.getDouble(p) + " getFloat of int field="
                + k.getDeclaredField("i").getFloat(p));
        System.out.println("FieldPrimProbe done");
    }
}
