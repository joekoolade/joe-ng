/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-15
 */
import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * {@code java.lang.reflect.Array} over every element type. Each primitive array is read and written through the
 * typed accessors -- a 1-, 2-, 4- and 8-byte element each, so an accessor with the wrong stride reads a
 * neighbour -- with stock's widening allowed ({@code getInt} on a {@code byte[]}, {@code setInt} on a {@code
 * long[]}) and narrowing refused ({@code getByte} on an {@code int[]}). {@code get} boxes to the element's own
 * wrapper; {@code set} unboxes and widens, and refuses a null for a primitive and a wrong reference type. Then
 * creation: every component type, {@code void}, null, a negative length, and the multi-dimensional forms
 * including an array component type. Each failing arm prints the exception's class only. Prints values only;
 * the host diff is the gate.
 */
public class ReflectArrayProbe
{
    interface Arm
    {
        Object run() throws Throwable;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            Object r = a.run();
            String s;
            if (r != null && r.getClass().isArray())
            {
                s = r.getClass().getName() + " " + Arrays.deepToString(new Object[] { r });
            }
            else
            {
                s = r == null ? "null" : r.getClass().getSimpleName() + " " + r;
            }
            System.out.println(what + " -> " + s);
        }
        catch (Throwable t)
        {
            System.out.println(what + " -> " + t.getClass().getName());
        }
    }

    public static void main(String[] args)
    {
        boolean[] z = { true, false, true };
        byte[] b = { 1, -2, 3 };
        char[] c = { 'a', 'b', 'c' };
        short[] s = { 100, -200, 300 };
        int[] i = { 10, -20, 30 };
        long[] j = { 1L << 40, -5L, 7L };
        float[] f = { 1.5f, -2.25f, 3.0f };
        double[] d = { 0.5, -1.75, 1e100 };
        String[] str = { "x", "y", "z" };
        Number[] num = { 1, 2L, 3.0 };

        System.out.println("-- length and get (boxes to the element's own wrapper) --");
        Object[] all = { z, b, c, s, i, j, f, d, str, num };
        for (Object a : all)
        {
            arm(a.getClass().getName() + " length", () -> Array.getLength(a));
            arm(a.getClass().getName() + " get(1)", () -> Array.get(a, 1));
            arm(a.getClass().getName() + " get(2)", () -> Array.get(a, 2));
            arm(a.getClass().getName() + " get(3)", () -> Array.get(a, 3));
            arm(a.getClass().getName() + " get(-1)", () -> Array.get(a, -1));
        }
        arm("getLength(String)", () -> Array.getLength("not an array"));
        arm("getLength(null)", () -> Array.getLength(null));
        arm("get(Integer, 0)", () -> Array.get(Integer.valueOf(3), 0));

        System.out.println("-- typed getters: widening allowed, narrowing refused --");
        arm("getInt(int[],1)", () -> Array.getInt(i, 1));
        arm("getInt(int[],2)", () -> Array.getInt(i, 2));
        arm("getInt(byte[],1)", () -> Array.getInt(b, 1));
        arm("getInt(char[],2)", () -> Array.getInt(c, 2));
        arm("getInt(short[],1)", () -> Array.getInt(s, 1));
        arm("getInt(long[],1)", () -> Array.getInt(j, 1));
        arm("getInt(boolean[],0)", () -> Array.getInt(z, 0));
        arm("getLong(int[],1)", () -> Array.getLong(i, 1));
        arm("getLong(long[],0)", () -> Array.getLong(j, 0));
        arm("getLong(char[],0)", () -> Array.getLong(c, 0));
        arm("getFloat(long[],0)", () -> Array.getFloat(j, 0));
        arm("getFloat(float[],1)", () -> Array.getFloat(f, 1));
        arm("getFloat(double[],1)", () -> Array.getFloat(d, 1));
        arm("getDouble(float[],1)", () -> Array.getDouble(f, 1));
        arm("getDouble(double[],2)", () -> Array.getDouble(d, 2));
        arm("getDouble(int[],2)", () -> Array.getDouble(i, 2));
        arm("getShort(byte[],1)", () -> Array.getShort(b, 1));
        arm("getShort(short[],2)", () -> Array.getShort(s, 2));
        arm("getShort(char[],0)", () -> Array.getShort(c, 0));
        arm("getChar(char[],1)", () -> Array.getChar(c, 1));
        arm("getChar(byte[],0)", () -> Array.getChar(b, 0));
        arm("getByte(byte[],2)", () -> Array.getByte(b, 2));
        arm("getByte(int[],0)", () -> Array.getByte(i, 0));
        arm("getBoolean(boolean[],1)", () -> Array.getBoolean(z, 1));
        arm("getBoolean(byte[],0)", () -> Array.getBoolean(b, 0));
        arm("getInt(Integer[],0)", () -> Array.getInt(new Integer[] { 4 }, 0));
        arm("getInt(int[],3)", () -> Array.getInt(i, 3));

        System.out.println("-- typed setters --");
        arm("setInt(int[],0,77)", () ->
        {
            Array.setInt(i, 0, 77);
            return i;
        });
        arm("setInt(long[],1,-9)", () ->
        {
            Array.setInt(j, 1, -9);
            return j;
        });
        arm("setInt(double[],0,3)", () ->
        {
            Array.setInt(d, 0, 3);
            return d;
        });
        arm("setLong(int[],0,1)", () ->
        {
            Array.setLong(i, 0, 1L);
            return i;
        });
        arm("setByte(short[],2,-1)", () ->
        {
            Array.setByte(s, 2, (byte) -1);
            return s;
        });
        arm("setByte(char[],0,1)", () ->
        {
            Array.setByte(c, 0, (byte) 1);
            return c;
        });
        arm("setChar(int[],2,'A')", () ->
        {
            Array.setChar(i, 2, 'A');
            return i;
        });
        arm("setChar(char[],2,'q')", () ->
        {
            Array.setChar(c, 2, 'q');
            return c;
        });
        arm("setShort(byte[],0,5)", () ->
        {
            Array.setShort(b, 0, (short) 5);
            return b;
        });
        arm("setShort(float[],0,5)", () ->
        {
            Array.setShort(f, 0, (short) 5);
            return f;
        });
        arm("setFloat(double[],1,0.25f)", () ->
        {
            Array.setFloat(d, 1, 0.25f);
            return d;
        });
        arm("setFloat(long[],0,1f)", () ->
        {
            Array.setFloat(j, 0, 1f);
            return j;
        });
        arm("setDouble(float[],0,1.0)", () ->
        {
            Array.setDouble(f, 0, 1.0);
            return f;
        });
        arm("setBoolean(boolean[],1,true)", () ->
        {
            Array.setBoolean(z, 1, true);
            return z;
        });
        arm("setBoolean(int[],0,true)", () ->
        {
            Array.setBoolean(i, 0, true);
            return i;
        });
        arm("setByte(byte[],1,99)", () ->
        {
            Array.setByte(b, 1, (byte) 99);
            return b;
        });
        arm("setLong(long[],2,max)", () ->
        {
            Array.setLong(j, 2, Long.MAX_VALUE);
            return j;
        });
        arm("setDouble(double[],2,-0.0)", () ->
        {
            Array.setDouble(d, 2, -0.0);
            return d;
        });
        arm("setInt(String[],0,1)", () ->
        {
            Array.setInt(str, 0, 1);
            return str;
        });
        arm("setInt(int[],5,1)", () ->
        {
            Array.setInt(i, 5, 1);
            return i;
        });

        System.out.println("-- set: unbox and widen, or refuse --");
        arm("set(int[],1,Short)", () ->
        {
            Array.set(i, 1, (short) 12);
            return i;
        });
        arm("set(int[],1,Character)", () ->
        {
            Array.set(i, 1, 'Z');
            return i;
        });
        arm("set(long[],0,Integer)", () ->
        {
            Array.set(j, 0, 42);
            return j;
        });
        arm("set(double[],0,Float)", () ->
        {
            Array.set(d, 0, 2.5f);
            return d;
        });
        arm("set(int[],1,Long)", () ->
        {
            Array.set(i, 1, 5L);
            return i;
        });
        arm("set(int[],1,null)", () ->
        {
            Array.set(i, 1, null);
            return i;
        });
        arm("set(int[],1,String)", () ->
        {
            Array.set(i, 1, "5");
            return i;
        });
        arm("set(char[],0,Byte)", () ->
        {
            Array.set(c, 0, (byte) 65);
            return c;
        });
        arm("set(boolean[],0,Boolean)", () ->
        {
            Array.set(z, 0, Boolean.FALSE);
            return z;
        });
        arm("set(boolean[],0,Integer)", () ->
        {
            Array.set(z, 0, 1);
            return z;
        });
        arm("set(String[],0,\"w\")", () ->
        {
            Array.set(str, 0, "w");
            return str;
        });
        arm("set(String[],1,null)", () ->
        {
            Array.set(str, 1, null);
            return str;
        });
        arm("set(String[],2,Integer)", () ->
        {
            Array.set(str, 2, 9);
            return str;
        });
        arm("set(Number[],0,Short)", () ->
        {
            Array.set(num, 0, (short) 8);
            return num;
        });
        arm("set(Number[],1,String)", () ->
        {
            Array.set(num, 1, "s");
            return num;
        });
        arm("set(String,0,x)", () ->
        {
            Array.set("abc", 0, "x");
            return null;
        });

        System.out.println("-- newInstance --");
        Class<?>[] comps = { boolean.class, byte.class, short.class, int.class, long.class, float.class,
                double.class, String.class, Runnable.class, int[].class, Object[].class };
        for (Class<?> k : comps)
        {
            arm("newInstance(" + k.getName() + ",2)", () -> Array.newInstance(k, 2));
        }
        arm("newInstance(char,2)[1] as int", () -> (int) ((char[]) Array.newInstance(char.class, 2))[1]);
        arm("newInstance(void,1)", () -> Array.newInstance(void.class, 1));
        arm("newInstance(null,1)", () -> Array.newInstance(null, 1));
        arm("newInstance(int,-1)", () -> Array.newInstance(int.class, -1));
        arm("newInstance(int,0)", () -> Array.newInstance(int.class, 0));
        arm("instanceof long[]", () -> Array.newInstance(long.class, 3) instanceof long[]);
        arm("instanceof CharSequence[]", () -> Array.newInstance(String.class, 1) instanceof CharSequence[]);
        arm("arrayType int", () -> int.class.arrayType().getName());
        arm("arrayType String[]", () -> String[].class.arrayType().getName());
        arm("componentType [J", () -> Array.newInstance(long.class, 1).getClass().getComponentType());

        System.out.println("-- multi-dimensional --");
        arm("new(int,2,3)", () -> Array.newInstance(int.class, 2, 3));
        arm("new(String,2,1)", () -> Array.newInstance(String.class, 2, 1));
        arm("new(double,1,2,2)", () -> Array.newInstance(double.class, 1, 2, 2));
        arm("new(int[],2)", () -> Array.newInstance(int[].class, new int[] { 2 }));
        arm("new(String[],2,1)", () -> Array.newInstance(String[].class, 2, 1));
        arm("new(long,3,0)", () -> Array.newInstance(long.class, 3, 0));
        arm("new(long,0,3)", () -> Array.newInstance(long.class, 0, 3));
        arm("new(int,no dims)", () -> Array.newInstance(int.class, new int[0]));
        arm("new(int,null dims)", () -> Array.newInstance(int.class, (int[]) null));
        arm("new(int,2,-1)", () -> Array.newInstance(int.class, 2, -1));
        arm("new(void,1,1)", () -> Array.newInstance(void.class, 1, 1));
        arm("inner type", () -> Array.get(Array.newInstance(char.class, 2, 2), 1).getClass().getName());
        arm("int[][] cast", () ->
        {
            int[][] g = (int[][]) Array.newInstance(int.class, 2, 2);
            g[1][1] = 6;
            return g[1][1] + g[0][0];
        });
        System.out.println("ReflectArrayProbe done");
    }
}
