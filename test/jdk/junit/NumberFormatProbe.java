/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-10
 */
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

/**
 * {@code java.text.NumberFormat}'s factories, and the stock caller that reaches them under the DEFAULT locale:
 * {@code Formatter}'s grouping flag. ({@code Scanner} reaches them too, but first needs {@code java.nio.CharBuffer},
 * which is still denied -- the Charset/CharBuffer gap, measured and recorded, not hidden.)
 *
 * <p>joe-ng seeds no {@code user.language}/{@code user.country}, so its default locale is {@code en} -- not
 * {@code Locale.US} -- and {@code Formatter}'s {@code %,d} then calls {@code NumberFormat.getNumberInstance}, whose
 * stock factory asks a locale provider this VM denies. The patterns for {@code en} and ROOT agree for everything
 * printed here (number, integer, percent), so the host's {@code en_US} output is the oracle; the currency instance
 * is left out (it needs {@code java.util.Currency}'s data, a stated limit).
 */
public class NumberFormatProbe
{
    public static void main(String[] args) throws ParseException
    {
        System.out.println("String.format(%,d) default locale -> " + String.format("%,d", 1234567) + " | "
                + String.format("%,d", -9876543210L));
        System.out.println("String.format(%,.2f) default locale -> " + String.format("%,.2f", 1234567.891));
        NumberFormat number = NumberFormat.getNumberInstance(Locale.ROOT);
        System.out.println("number -> " + number.format(1234.5678) + " | " + number.format(-0.0005) + " | parse "
                + number.parse("1,234.5"));
        NumberFormat integer = NumberFormat.getIntegerInstance(Locale.ROOT);
        System.out.println("integer -> " + integer.format(2.5) + " " + integer.format(3.5) + " " + integer.format(1234567)
                + " | parse " + integer.parse("12.7") + " | parseIntegerOnly " + integer.isParseIntegerOnly());
        NumberFormat percent = NumberFormat.getPercentInstance(Locale.ROOT);
        System.out.println("percent -> " + percent.format(0.256) + " " + percent.format(12) + " | parse "
                + percent.parse("50%"));
        System.out.println("getInstance == getNumberInstance: " + NumberFormat.getInstance(Locale.ROOT).equals(number));
        System.out.println("NumberFormatProbe done");
    }
}
