/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-28
 */
import java.util.Arrays;

/**
 * Exercises {@code java.util.Arrays.sort} over all SEVEN primitive array types, whole-array and range forms.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values:
 * run on a host JVM the arms reach stock {@code DualPivotQuicksort}, run on the metal they reach joe-ng's
 * overlay, and every arm must print the same bytes in both worlds. Stock is a dual-pivot quicksort for
 * int/long/float/double and a counting sort for byte/char/short; the overlay is an insertion sort for all
 * seven. **Those are different ALGORITHMS, so agreeing on every arm is a real claim about the ORDER they
 * produce and not a tautology** -- which is what makes an oracle worth more here than a list of expected
 * strings.
 *
 * <p>BYTE, CHAR AND SHORT ARE WHY THIS FILE EXISTS: until 2026-09-28 the overlay declared only the other
 * four, so {@code Arrays.sort(byte[])}, {@code (char[])} and {@code (short[])} resolved NOWHERE and trapped.
 * {@code overlaycheck-deep} named all three, each "referenced by java/util/Arrays" -- the shallow check
 * cannot see them because the only caller is stock java.base.
 *
 * <p>THE SIGN AND SIGNEDNESS ARMS ARE THE ONES THAT DISCRIMINATE, and they are chosen so that the plausible
 * wrong implementation fails:
 * <ul>
 *   <li>{@code char} is UNSIGNED (0..65535). {@code '\\uFFFF'} must sort ABOVE {@code 'a'}; an implementation
 *       that sign-extended would put it first. Java promotes a char to int by ZERO-extension, so a plain
 *       {@code >} is already the unsigned compare -- the arm is here to prove that, not to assume it.</li>
 *   <li>{@code byte} and {@code short} are SIGNED and are meant to be: {@code -128} must sort BELOW
 *       {@code 127}. An implementation that read them the way char is read gives exactly the reverse.</li>
 *   <li>{@code double}/{@code float} must sort in IEEE-754 TOTAL ORDER -- {@code NaN} greatest,
 *       {@code -0.0} strictly below {@code +0.0}. A naive {@code >} insertion sort mis-orders BOTH: NaN
 *       compares false against everything so it stays where it started, and {@code -0.0 > +0.0} is false so
 *       the pair never swaps. The overlay routes through {@code Double.compare}/{@code Float.compare}; this
 *       is the arm that checks that claim rather than restating it.</li>
 * </ul>
 *
 * <p>THE RANGE FORMS ARE A SEPARATE ARM BECAUSE THEY TAKE A DIFFERENT PATH. {@code Arrays.sort(int[],int,int)}
 * calls {@code sort(a, parallelism, low, high)} while {@code Arrays.sort(byte[],int,int)} calls
 * {@code sort(a, low, high)} -- four arguments against three, because stock's counting sort has no parallel
 * variant. **Declaring the four-argument shape for a byte array leaves the trap in place while looking
 * fixed**, so each range arm also prints the elements OUTSIDE the range, which a sort that ignored its
 * bounds would disturb.
 *
 * <p>THE ORDERED, REVERSED, EQUAL AND EMPTY ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that
 * passes in both states is not a control. They pass whatever the algorithm is; they are here so that a
 * change which broke the common case shows up beside the sharp arms rather than hiding behind them.
 *
 * <p>ONE ARM DIVERGES AND IT IS NOT ABOUT SORTING -- STATED RATHER THAN REMOVED. The int MIN/MAX arm prints
 * {@code [-,-1,0,1,2147483647]} on metal against the host's {@code [-2147483648,...]}: a bare minus sign for
 * {@code Integer.MIN_VALUE}. That is {@code StringBuilder.append(int)}, which hand-rolls its digits and does
 * {@code v = -v} -- and {@code -Integer.MIN_VALUE} is still {@code Integer.MIN_VALUE}, so the {@code v > 0}
 * loop writes NO digits. It is the FOURTH site of a defect this project recorded and fixed on 2026-09-19 in
 * {@code VMConcat.scInt}, {@code scLong} and {@code VM.printDec}, whose comment describes this exact shape;
 * {@code append(long)} survives only because it delegates to {@code Long.toString}. **It is present in BOTH
 * arms of this increment's A/B, so it is demonstrably pre-existing and not the sort change** -- the arm is
 * kept failing on purpose, because removing it would hide a live silent wrong answer, and it is fixed in its
 * own increment rather than bundled here.
 *
 * <p>NO ANONYMOUS OR NESTED CLASSES ANYWHERE, deliberately: a probe lives in the DEFAULT PACKAGE, which
 * matches no {@code demandLoadable} prefix, so whether a nested {@code SortPrimProbe$1} reaches the image's
 * classDir is a claim nothing here has measured -- and an arm that cannot load looks exactly like one that
 * passed.
 */
public class SortPrimProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    private static String show(int[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    private static String show(long[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    private static String show(byte[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    private static String show(short[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    /** chars printed as their NUMERIC value: the point of these arms is ordering, and a raw char would put
     *  control characters and U+FFFF on the wire, where a UART log is already awkward to diff. */
    private static String show(char[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append((int) a[i]);
        }
        return "[" + sb + "]";
    }

    private static String show(double[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    private static String show(float[] a)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++)
        {
            if (i > 0) { sb.append(','); }
            sb.append(a[i]);
        }
        return "[" + sb + "]";
    }

    public static void main(String[] args)
    {
        System.out.println("-- int[] --");
        int[] i1 = { 5, -3, 12, 0, 5, -128, 127 };
        Arrays.sort(i1);
        say("mixed", show(i1));
        int[] i2 = { Integer.MAX_VALUE, Integer.MIN_VALUE, 0, -1, 1 };
        Arrays.sort(i2);
        say("MIN/MAX boundary", show(i2));
        int[] i3 = { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0 };
        Arrays.sort(i3);
        say("reversed (insertion worst case)", show(i3));
        int[] i4 = { 1, 2, 3, 4, 5 };
        Arrays.sort(i4);
        say("already ordered", show(i4));
        int[] i5 = { 7, 7, 7, 7 };
        Arrays.sort(i5);
        say("all equal", show(i5));
        int[] i6 = new int[0];
        Arrays.sort(i6);
        say("empty", show(i6));
        int[] i7 = { 42 };
        Arrays.sort(i7);
        say("single", show(i7));

        System.out.println("-- long[] --");
        long[] l1 = { 5L, -3L, 1234567890123L, 0L, Long.MIN_VALUE, Long.MAX_VALUE };
        Arrays.sort(l1);
        say("mixed + MIN/MAX", show(l1));

        System.out.println("-- byte[]: SIGNED, so -128 sorts BELOW 127 --");
        byte[] b1 = { (byte) -1, (byte) 1, (byte) -128, (byte) 127, (byte) 0 };
        Arrays.sort(b1);
        say("signed order", show(b1));
        byte[] b2 = { (byte) 3, (byte) 3, (byte) -3 };
        Arrays.sort(b2);
        say("duplicates", show(b2));
        byte[] b3 = new byte[0];
        Arrays.sort(b3);
        say("empty", show(b3));

        System.out.println("-- short[]: SIGNED --");
        short[] s1 = { (short) -1, (short) 1, (short) -32768, (short) 32767, (short) 0 };
        Arrays.sort(s1);
        say("signed order", show(s1));

        System.out.println("-- char[]: UNSIGNED, so 0xFFFF sorts ABOVE 'a' --");
        char[] c1 = { '￿', 'a', '耀', '\u0001', 'z' };
        Arrays.sort(c1);
        say("unsigned order", show(c1));
        char[] c2 = { 'd', 'c', 'b', 'a' };
        Arrays.sort(c2);
        say("reversed ascii", show(c2));

        System.out.println("-- double[]: IEEE-754 TOTAL ORDER (NaN greatest, -0.0 below +0.0) --");
        double[] d1 = { 3.5, Double.NaN, -0.0, 0.0, -2.5, Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY };
        Arrays.sort(d1);
        say("total order", show(d1));
        say("  -0.0 strictly below +0.0", String.valueOf(
                Double.doubleToRawLongBits(d1[2]) == Double.doubleToRawLongBits(-0.0)
                        && Double.doubleToRawLongBits(d1[3]) == Double.doubleToRawLongBits(0.0)));
        double[] d2 = { Double.NaN, Double.NaN, 1.0 };
        Arrays.sort(d2);
        say("two NaNs", show(d2));

        System.out.println("-- float[]: IEEE-754 TOTAL ORDER --");
        float[] f1 = { 3.5f, Float.NaN, -0.0f, 0.0f, -2.5f, Float.POSITIVE_INFINITY,
                       Float.NEGATIVE_INFINITY };
        Arrays.sort(f1);
        say("total order", show(f1));
        say("  -0.0f strictly below +0.0f", String.valueOf(
                Float.floatToRawIntBits(f1[2]) == Float.floatToRawIntBits(-0.0f)
                        && Float.floatToRawIntBits(f1[3]) == Float.floatToRawIntBits(0.0f)));

        System.out.println("-- range forms: the elements OUTSIDE the range must not move --");
        int[] ri = { 99, 5, 3, 1, 98 };
        Arrays.sort(ri, 1, 4);
        say("int range(1,4)", show(ri));
        long[] rl = { 99L, 5L, 3L, 1L, 98L };
        Arrays.sort(rl, 1, 4);
        say("long range(1,4)", show(rl));
        byte[] rb = { (byte) 99, (byte) 5, (byte) 3, (byte) 1, (byte) 98 };
        Arrays.sort(rb, 1, 4);
        say("byte range(1,4)", show(rb));
        char[] rc = { 'z', 'e', 'c', 'a', 'y' };
        Arrays.sort(rc, 1, 4);
        say("char range(1,4)", show(rc));
        short[] rs = { (short) 99, (short) 5, (short) 3, (short) 1, (short) 98 };
        Arrays.sort(rs, 1, 4);
        say("short range(1,4)", show(rs));
        double[] rd = { 99.0, 5.0, 3.0, 1.0, 98.0 };
        Arrays.sort(rd, 1, 4);
        say("double range(1,4)", show(rd));
        float[] rf = { 99.0f, 5.0f, 3.0f, 1.0f, 98.0f };
        Arrays.sort(rf, 1, 4);
        say("float range(1,4)", show(rf));

        System.out.println("SortPrimProbe done");
    }
}
