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
 * {@code StringBuilder}'s {@code float}/{@code double} surface: {@code append} and {@code insert}.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>WHAT WAS BROKEN. The overlay declared {@code append(float)} and NOT {@code append(double)} -- and an
 * overlay WINS the name, so the double form did not fall back to stock, it ceased to exist. The call
 * resolved nowhere and surfaced as a {@code DENYLIST TRAP} naming a list {@code StringBuilder} is not on.
 * Neither {@code insert} overload existed either.
 *
 * <p>THE {@code float} ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both states is
 * not a control: {@code append(float)} was already there, so every float arm passes whether or not the
 * double work is right. They are here because they are what says the double fix did not disturb its sibling.
 *
 * <p>THE {@code float} AND {@code double} ARMS ARE NOT REDUNDANT WITH EACH OTHER, which is the point of
 * pairing them on the same value: shortest-round-trip is relative to the type's OWN precision, so
 * {@code 0.1f} must render as {@code "0.1"} while the same quantity widened to a double renders as
 * {@code "0.10000000149011612"}. An implementation that routed the double form through the float formatter,
 * or a float through the double one, passes every other arm and fails that pair.
 *
 * <p>NaN, THE TWO INFINITIES AND THE SIGNED ZEROS ARE THE ARMS A HAND-ROLLED FORMATTER FAILS while looking
 * careful, which is why the fix delegates to {@code Double.toString} rather than formatting here. {@code -0.0}
 * is kept apart from {@code 0.0} because they compare EQUAL under {@code ==} and differ in their rendering.
 *
 * <p>{@code insert} carries an OFFSET, so each insert arm places the value between existing characters
 * rather than at either end -- an implementation that appended instead of inserting, or that inserted at a
 * fixed position, passes an at-the-end arm and fails these.
 */
public class SbFpProbe
{
    private static void say(String label, String value)
    {
        System.out.println("  " + label + " = [" + value + "]");
    }

    public static void main(String[] args)
    {
        System.out.println("-- append(double) --");
        say("1.5", new StringBuilder().append(1.5).toString());
        say("0.1", new StringBuilder().append(0.1).toString());
        say("-2.75", new StringBuilder().append(-2.75).toString());
        say("0.0", new StringBuilder().append(0.0).toString());
        say("-0.0", new StringBuilder().append(-0.0).toString());
        say("NaN", new StringBuilder().append(Double.NaN).toString());
        say("+Inf", new StringBuilder().append(Double.POSITIVE_INFINITY).toString());
        say("-Inf", new StringBuilder().append(Double.NEGATIVE_INFINITY).toString());
        say("1e20", new StringBuilder().append(1.0E20).toString());
        say("1e-9", new StringBuilder().append(1.0E-9).toString());
        say("MAX", new StringBuilder().append(Double.MAX_VALUE).toString());
        say("MIN", new StringBuilder().append(Double.MIN_VALUE).toString());

        System.out.println("-- append(float): the built-in comparison --");
        say("1.5f", new StringBuilder().append(1.5f).toString());
        say("0.1f", new StringBuilder().append(0.1f).toString());
        say("-0.0f", new StringBuilder().append(-0.0f).toString());
        say("NaN f", new StringBuilder().append(Float.NaN).toString());

        System.out.println("-- float is NOT a widened double --");
        say("0.1f vs 0.1d", new StringBuilder().append(0.1f).append('|').append((double) 0.1f).toString());

        System.out.println("-- in context, and mixed with other appends --");
        say("surrounded", new StringBuilder().append('[').append(2.5).append(']').toString());
        say("chained", new StringBuilder().append(1).append(',').append(2.5).append(',').append(3L).toString());

        System.out.println("-- insert(int, double) / insert(int, float) --");
        say("ins d mid", new StringBuilder("ab").insert(1, 2.5).toString());
        say("ins f mid", new StringBuilder("ab").insert(1, 0.5f).toString());
        say("ins d head", new StringBuilder("ab").insert(0, -1.25).toString());
        say("ins d tail", new StringBuilder("ab").insert(2, 9.75).toString());
        say("ins d NaN", new StringBuilder("ab").insert(1, Double.NaN).toString());

        System.out.println("SbFpProbe done");
    }
}
