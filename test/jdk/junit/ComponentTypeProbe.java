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
import java.lang.reflect.Array;
import jdk.internal.misc.Unsafe;

/**
 * {@code Class.getComponentType()} for a PRIMITIVE array, and the two things that consume it.
 *
 * <p>THE DEFECT, found while adding {@code Unsafe}'s bulk moves: an array Type's element slot is 0 for a
 * PRIMITIVE element ({@code ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET} exists for reference-array covariance), so
 * {@code componentTypeOf} answered NULL for every {@code byte[]}, {@code int[]}, {@code double[]}... And the
 * element SIZE cannot recover which primitive it is -- {@code byte[]} and {@code boolean[]} are both 1,
 * {@code int[]} and {@code float[]} both 4 -- so the Type alone does not carry the answer. It is recovered by
 * IDENTITY against the per-atype array-TIB cache, which is the same trick {@code Class.getName} already uses to
 * render {@code "[I"}, and which also works for a writer-BAKED array Type the loader merely adopted.
 *
 * <p>A null answer is not an error a caller sees; it is the answer for "not an array". So this was the quiet
 * kind of wrong: every caller branching on it took the not-an-array path for a real array.
 *
 * <p>THE ARMS ARE IDENTITY, NOT NON-NULLNESS. {@code getComponentType()} must answer the SAME object as the
 * class literal ({@code byte[].class.getComponentType() == byte.class}), because that is what callers compare
 * against; an implementation minting a fresh mirror per call would pass a non-null check and fail every real
 * use. All eight primitives are covered, because the pairs that share an element size are exactly the ones an
 * implementation reading the size instead of the identity would confuse.
 *
 * <p>THE CONSUMERS ARE HERE TOO, and that is the point of the probe rather than a bonus:
 * <ul>
 *   <li>{@code Unsafe.arrayIndexScale} falls through its {@code c == null || !c.isPrimitive()} arm and answered
 *       8 for every primitive array. Nothing had noticed because the {@code ARRAY_*_INDEX_SCALE} constants are
 *       assigned directly rather than computed from it -- so the CONSTANTS were right and the METHOD was not.</li>
 *   <li>{@code Array.newInstance(a.getClass().getComponentType(), n)} is the idiom {@code Class}'s own javadoc
 *       names ({@code TimSort}/{@code Arrays.copyOf}/{@code toArray}). It allocates 8-byte REFERENCE elements
 *       unconditionally, so a primitive component gives an array of the wrong element WIDTH and the wrong TIB --
 *       and its length and contents are then read by ordinary bytecode. These arms assert the length, a
 *       round-trip through {@code Array.set}/{@code Array.get}, and the CLASS of the result, because an array
 *       of the right length whose elements are the wrong width is exactly the shape that reads as working.</li>
 * </ul>
 *
 * <p>ONE SOURCE, BOTH WORLDS: against the real JDK the arms reach stock, against {@code guestsrc} the overlay,
 * and the gate is a byte-for-byte diff. Host control:
 * {@code java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}.
 */
public class ComponentTypeProbe
{
    static int failures;

    static void say(String name, String got, String want)
    {
        boolean ok = got.equals(want);
        if (!ok)
        {
            failures++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + name + " = " + got + " (want " + want + ")");
    }

    static String guard(java.util.concurrent.Callable<String> c)
    {
        try
        {
            return c.call();
        }
        catch (Throwable t)
        {
            return "threw:" + t.getClass().getName();
        }
    }

    public static void main(String[] args) throws Exception
    {
        // ---- IDENTITY against the class literal, all eight primitives. The pairs sharing an element size
        // (byte/boolean = 1, char/short = 2, int/float = 4, long/double = 8) are what an implementation
        // reading the SIZE instead of the identity confuses, so all eight are here rather than a sample.
        say("comp boolean[]", "" + (boolean[].class.getComponentType() == boolean.class), "true");
        say("comp byte[]   ", "" + (byte[].class.getComponentType() == byte.class), "true");
        say("comp char[]   ", "" + (char[].class.getComponentType() == char.class), "true");
        say("comp short[]  ", "" + (short[].class.getComponentType() == short.class), "true");
        say("comp int[]    ", "" + (int[].class.getComponentType() == int.class), "true");
        say("comp float[]  ", "" + (float[].class.getComponentType() == float.class), "true");
        say("comp long[]   ", "" + (long[].class.getComponentType() == long.class), "true");
        say("comp double[] ", "" + (double[].class.getComponentType() == double.class), "true");

        // ... and by NAME as well, because an identity arm alone cannot say WHICH primitive was answered if
        // both sides were wrong in the same way.
        say("comp names    ", byte[].class.getComponentType().getName() + ","
            + int[].class.getComponentType().getName() + ","
            + double[].class.getComponentType().getName(), "byte,int,double");

        // Reached through an INSTANCE, which is the route every real caller takes.
        byte[] ba = new byte[3];
        int[] ia = new int[3];
        say("inst byte[]   ", "" + (ba.getClass().getComponentType() == byte.class), "true");
        say("inst int[]    ", "" + (ia.getClass().getComponentType() == int.class), "true");

        // The cases that must NOT change: a reference array, a nested array, and a non-array.
        String[] sa = new String[2];
        say("comp String[] ", "" + (sa.getClass().getComponentType() == String.class), "true");
        say("comp int[][]  ", "" + (int[][].class.getComponentType() == int[].class), "true");
        say("comp scalar   ", "" + (String.class.getComponentType() == null), "true");
        say("comp prim     ", "" + (int.class.getComponentType() == null), "true");

        // ---- CONSUMER 1: Unsafe.arrayIndexScale, which reads getComponentType().isPrimitive().
        Unsafe u = Unsafe.getUnsafe();
        say("scale bool    ", "" + u.arrayIndexScale(boolean[].class), "" + Unsafe.ARRAY_BOOLEAN_INDEX_SCALE);
        say("scale byte    ", "" + u.arrayIndexScale(byte[].class), "" + Unsafe.ARRAY_BYTE_INDEX_SCALE);
        say("scale char    ", "" + u.arrayIndexScale(char[].class), "" + Unsafe.ARRAY_CHAR_INDEX_SCALE);
        say("scale short   ", "" + u.arrayIndexScale(short[].class), "" + Unsafe.ARRAY_SHORT_INDEX_SCALE);
        say("scale int     ", "" + u.arrayIndexScale(int[].class), "" + Unsafe.ARRAY_INT_INDEX_SCALE);
        say("scale float   ", "" + u.arrayIndexScale(float[].class), "" + Unsafe.ARRAY_FLOAT_INDEX_SCALE);
        say("scale long    ", "" + u.arrayIndexScale(long[].class), "" + Unsafe.ARRAY_LONG_INDEX_SCALE);
        say("scale double  ", "" + u.arrayIndexScale(double[].class), "" + Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
        // Printed as a COMPARISON rather than a value: ARRAY_OBJECT_INDEX_SCALE is genuinely 4 on a host JVM
        // (compressed oops) and 8 here (direct 8-byte refs). A platform fact, not a divergence -- so the arm
        // asserts that the METHOD agrees with the CONSTANT in whichever world it runs, and the two worlds then
        // print the same text and the diff stays a diff. The eight primitive scales above need no such care:
        // 1/1/2/2/4/4/8/8 is the same in both.
        say("scale Object  ", "" + (u.arrayIndexScale(Object[].class) == Unsafe.ARRAY_OBJECT_INDEX_SCALE),
            "true");

        // ---- CONSUMER 2: Array.newInstance, both spellings. The CLASS of the result is asserted as well as
        // the length and a value round-trip: an array of the right length whose elements are the wrong WIDTH
        // reads as working until something indexes past the first element.
        say("newInst int   ", guard(() -> {
            Object a = Array.newInstance(int.class, 3);
            Array.set(a, 2, 42);
            return Array.getLength(a) + "/" + Array.get(a, 2) + "/" + a.getClass().getName();
        }), "3/42/[I");
        say("newInst byte  ", guard(() -> {
            Object a = Array.newInstance(byte.class, 4);
            Array.set(a, 3, (byte) -2);
            return Array.getLength(a) + "/" + Array.get(a, 3) + "/" + a.getClass().getName();
        }), "4/-2/[B");
        say("newInst double", guard(() -> {
            Object a = Array.newInstance(double.class, 2);
            Array.set(a, 1, 1.5);
            return Array.getLength(a) + "/" + Array.get(a, 1) + "/" + a.getClass().getName();
        }), "2/1.5/[D");

        // The idiom Class.getComponentType's own javadoc names.
        say("newInst idiom ", guard(() -> {
            int[] src = { 7, 8, 9 };
            Object a = Array.newInstance(src.getClass().getComponentType(), src.length);
            Array.set(a, 0, src[0]);
            return Array.getLength(a) + "/" + Array.get(a, 0) + "/" + a.getClass().getName();
        }), "3/7/[I");

        // A cast to the real array type is what a caller actually does with the result, and it is the arm a
        // wrong TIB fails while every reflective read still looks right.
        say("newInst cast  ", guard(() -> {
            Object a = Array.newInstance(int.class, 2);
            int[] cast = (int[]) a;
            cast[1] = 5;
            return cast.length + "/" + cast[1];
        }), "2/5");

        // The reference case, which must not move.
        say("newInst ref   ", guard(() -> {
            Object a = Array.newInstance(String.class, 2);
            Array.set(a, 1, "x");
            return Array.getLength(a) + "/" + Array.get(a, 1) + "/" + (a instanceof String[]);
        }), "2/x/true");

        System.out.println("ComponentTypeProbe done, failures=" + failures);
    }
}
