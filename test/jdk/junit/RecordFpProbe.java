/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-29
 */

/**
 * Renders every primitive component type through a record's {@code toString}.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>{@code Loader.putComponent} SPECIAL-CASES ONLY {@code Z}, {@code C}, {@code L} AND {@code [}; every
 * other type char falls through to {@code putDec}, which renders a DECIMAL INTEGER. For {@code B}, {@code S},
 * {@code I} and {@code J} that is right. For {@code F} and {@code D} it printed the value's RAW BITS -- this
 * VM keeps a double in an ordinary 64-bit slot, so the slot IS
 * {@code Double.doubleToRawLongBits(d)} -- and a record with a {@code double} field came out as a
 * nineteen-digit integer where stock prints the number.
 *
 * <p>IT IS ALSO HOW THE 2026-09-28 BARE-MINUS BUG REACHED A {@code double}:
 * {@code doubleToRawLongBits(-0.0)} is {@code 0x8000000000000000}, which IS {@code Long.MIN_VALUE}, so
 * {@code -0.0} rendered as a bare {@code -} until {@code putDec} was repaired. The {@code -0.0} arms are kept
 * for that reason -- they are the one value where the two defects met.
 *
 * <p>THE NaN AND INFINITY ARMS ARE THE ONES A NAIVE FIX FAILS. Formatting by hand from the mantissa and
 * exponent gets ordinary values right and those three wrong; routing through {@code Double.toString} is what
 * makes them stock's own strings, and it is the same Schubfach formatter {@code VMConcat.scDouble} already
 * resolves by name. {@code float} is NOT a widened {@code double} -- {@code Float.toString(0.1f)} is
 * {@code "0.1"} where {@code Double.toString((double) 0.1f)} is {@code "0.10000000149011612"} -- so the two
 * have separate arms and separate formatters.
 *
 * <p>THE INTEGRAL AND REFERENCE ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both
 * states is not a control: {@code byte}, {@code short}, {@code int}, {@code long}, {@code boolean}, a
 * {@code String}, a null reference and a nested record all pass whether or not the float path is right.
 */
public class RecordFpProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = " + value);
    }

    public record D(double d)
    {
    }

    public record F(float f)
    {
    }

    public record C(char c)
    {
    }

    public record Mixed(int i, double d, String s, float f)
    {
    }

    public record Ints(byte b, short sh, int i, long l, boolean z)
    {
    }

    public record Ref(String s, Object o)
    {
    }

    public static void main(String[] args)
    {
        System.out.println("-- double components --");
        say("1.5", new D(1.5).toString());
        say("0.1", new D(0.1).toString());
        say("-2.75", new D(-2.75).toString());
        say("0.0", new D(0.0).toString());
        say("-0.0", new D(-0.0).toString());
        say("NaN", new D(Double.NaN).toString());
        say("+Infinity", new D(Double.POSITIVE_INFINITY).toString());
        say("-Infinity", new D(Double.NEGATIVE_INFINITY).toString());
        say("1e20", new D(1.0E20).toString());
        say("1e-9", new D(1.0E-9).toString());

        System.out.println("-- float components: NOT a widened double --");
        say("1.5f", new F(1.5f).toString());
        say("0.1f", new F(0.1f).toString());
        say("-0.0f", new F(-0.0f).toString());
        say("NaN f", new F(Float.NaN).toString());
        say("+Infinity f", new F(Float.POSITIVE_INFINITY).toString());

        System.out.println("-- char components --");
        say("'A'", new C('A').toString());
        say("'0'", new C('0').toString());

        System.out.println("-- integral and reference: the built-in comparison --");
        say("ints", new Ints((byte) -1, (short) -2, -3, -4L, true).toString());
        say("ints extremes", new Ints((byte) -128, (short) -32768, Integer.MIN_VALUE,
                Long.MIN_VALUE, false).toString());
        say("refs", new Ref("hi", "obj").toString());
        say("null ref", new Ref(null, null).toString());
        say("mixed", new Mixed(7, 2.5, "s", 1.25f).toString());

        System.out.println("RecordFpProbe done");
    }
}
