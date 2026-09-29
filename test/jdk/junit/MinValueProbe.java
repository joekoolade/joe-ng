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

/**
 * Renders {@code Integer.MIN_VALUE} and {@code Long.MIN_VALUE} through every hand-rolled decimal emitter in
 * this VM.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>ONE SHAPE, FIVE SITES, AND IT HAS BEEN FIXED FOUR TIMES. {@code -Integer.MIN_VALUE} is still
 * {@code Integer.MIN_VALUE} and {@code -Long.MIN_VALUE} is still {@code Long.MIN_VALUE}, so an emitter that
 * negates and then loops {@code while (v > 0)} writes NO DIGITS AT ALL and the value renders as a BARE
 * MINUS SIGN -- a silently truncated number, not a crash. On 2026-09-19 it was fixed in
 * {@code VMConcat.scInt}, {@code VMConcat.scLong} and {@code VM.printDec}; a sweep for the shape on
 * 2026-09-28 found the two this file covers still live.
 *
 * <p>THE TWO REPAIRS ARE DIFFERENT AND THE DIFFERENCE IS FORCED, not stylistic:
 * <ul>
 *   <li>{@code StringBuilder.append(int)} WIDENS TO LONG, because the int range fits a long with room to
 *       negate. Same repair as {@code scInt} and {@code printDec}.</li>
 *   <li>{@code Loader.putDec} takes a {@code long} and there is no wider type to borrow, so it TAKES ITS
 *       DIGITS IN THE NEGATIVE DOMAIN -- the negative side of two's complement holds one more value than
 *       the positive side. Same repair as {@code scLong}, and the same one stock's own
 *       {@code DecimalDigits} uses ({@code n = val < 0 ? val : -val}).</li>
 * </ul>
 *
 * <p>THE RECORD ARMS ARE WHAT REACH {@code Loader.putDec}, which renders one component of a record's
 * {@code toString} and is the site no earlier sweep had looked at. A {@code long} component at
 * {@code MIN_VALUE} is the direct case.
 *
 * <p>NOT PROBED, AND STATED RATHER THAN QUIETLY OMITTED: a {@code double} or {@code float} component.
 * {@code putComponent} special-cases only {@code Z}, {@code C}, {@code L} and {@code [}, so {@code F} and
 * {@code D} fall through to {@code putDec} and render their RAW BITS where stock renders the value -- a
 * SEPARATE, pre-existing divergence. It matters here because
 * {@code Double.doubleToRawLongBits(-0.0)} is {@code 0x8000000000000000}, which IS {@code Long.MIN_VALUE}:
 * a record with a {@code double} field holding {@code -0.0} was hitting the bare-minus bug through that
 * path. An arm for it could not match a host oracle for reasons that have nothing to do with this fix, and
 * an expected-divergence list is a place for a real regression to hide.
 *
 * <p>THE NON-EXTREME ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both states is
 * not a control: {@code MAX_VALUE}, {@code -1}, {@code 0} and an ordinary value pass whether or not the
 * negation is safe, and are here so that a repair which broke the common case shows up beside the extremes
 * rather than hiding behind them. {@code append(long)} is one too -- it delegates to
 * {@code Long.toString} and was never affected.
 */
public class MinValueProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    /** Component types chosen so every arm matches stock: no float/double (see the class javadoc). */
    public record Rec(long l, int i, String s)
    {
    }

    public static void main(String[] args)
    {
        System.out.println("-- StringBuilder.append(int): widened to long --");
        say("append(Integer.MIN_VALUE)", new StringBuilder().append(Integer.MIN_VALUE).toString());
        say("append(Integer.MAX_VALUE)", new StringBuilder().append(Integer.MAX_VALUE).toString());
        say("append(-1)", new StringBuilder().append(-1).toString());
        say("append(0)", new StringBuilder().append(0).toString());
        say("append(42)", new StringBuilder().append(42).toString());
        say("append(-2147483647)", new StringBuilder().append(-2147483647).toString());

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(Integer.MIN_VALUE).append(']');
        say("surrounded", sb.toString());

        StringBuilder two = new StringBuilder();
        two.append(Integer.MIN_VALUE).append(':').append(Integer.MIN_VALUE);
        say("twice", two.toString());

        System.out.println("-- StringBuilder.append(long): delegates to Long.toString --");
        say("append(Long.MIN_VALUE)", new StringBuilder().append(Long.MIN_VALUE).toString());
        say("append(Long.MAX_VALUE)", new StringBuilder().append(Long.MAX_VALUE).toString());

        System.out.println("-- the paths already repaired on 2026-09-19 --");
        say("String.valueOf(int MIN)", String.valueOf(Integer.MIN_VALUE));
        say("String.valueOf(long MIN)", String.valueOf(Long.MIN_VALUE));
        say("concat int MIN", "" + Integer.MIN_VALUE);
        say("concat long MIN", "" + Long.MIN_VALUE);
        say("Integer.toString(MIN)", Integer.toString(Integer.MIN_VALUE));
        say("Long.toString(MIN)", Long.toString(Long.MIN_VALUE));

        System.out.println("-- record toString: reaches Loader.putDec, the newly-found site --");
        say("long MIN + int MIN", new Rec(Long.MIN_VALUE, Integer.MIN_VALUE, "x").toString());
        say("long MAX + int MAX", new Rec(Long.MAX_VALUE, Integer.MAX_VALUE, "y").toString());
        say("ordinary", new Rec(42L, -7, "z").toString());
        say("zeros", new Rec(0L, 0, "").toString());
        say("long MIN alone", new Rec(Long.MIN_VALUE, 1, "a").toString());
        say("int MIN alone", new Rec(1L, Integer.MIN_VALUE, "b").toString());

        System.out.println("MinValueProbe done");
    }
}
