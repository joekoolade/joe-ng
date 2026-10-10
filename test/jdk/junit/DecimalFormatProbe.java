/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-09
 */
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Locale;

/**
 * Stock {@code java.text.DecimalFormat} over the ROOT {@code DecimalFormatSymbols}, and the symbols themselves.
 *
 * <p>joe-ng's {@code DecimalFormatSymbols} overlay used to carry four getters (zero digit, decimal and grouping
 * separators, minus sign); stock {@code DecimalFormat} calls thirty more (percent, per mille, exponent separator,
 * NaN, infinity, the monetary separators, the pattern separator, ...), so formatting a percent, an exponent or a
 * negative subpattern reached a missing method. The ROOT locale is used explicitly so the host's answer is the
 * same data joe-ng carries. Non-ASCII symbols are compared, not printed, so the console's encoding cannot move the
 * diff, which is the gate.
 */
public class DecimalFormatProbe
{
    public static void main(String[] args) throws ParseException
    {
        DecimalFormatSymbols root = DecimalFormatSymbols.getInstance(Locale.ROOT);
        System.out.println("symbols: decimal=" + root.getDecimalSeparator() + " grouping=" + root.getGroupingSeparator()
                + " pattern=" + root.getPatternSeparator() + " percent=" + root.getPercent() + " zero="
                + root.getZeroDigit() + " digit=" + root.getDigit() + " minus=" + root.getMinusSign() + " exp="
                + root.getExponentSeparator() + " NaN=" + root.getNaN() + " monetary=" + root.getMonetaryDecimalSeparator()
                + root.getMonetaryGroupingSeparator());
        System.out.println("non-ASCII symbols: perMill=" + (root.getPerMill() == '‰') + " infinity="
                + root.getInfinity().equals("∞") + " currency=" + root.getCurrencySymbol().equals("¤")
                + " intl=" + root.getInternationalCurrencySymbol());

        DecimalFormat grouped = new DecimalFormat("#,##0.00", root);
        System.out.println("#,##0.00 -> " + grouped.format(1234567.891) + " | " + grouped.format(-0.005) + " | "
                + grouped.format(new BigDecimal("12345.6789")));
        System.out.println("0.###E0 -> " + new DecimalFormat("0.###E0", root).format(12345) + " | "
                + new DecimalFormat("00.00E00", root).format(0.000123));
        System.out.println("00.0% -> " + new DecimalFormat("00.0%", root).format(0.256));
        System.out.println("#,##0.00;(#,##0.00) -> " + new DecimalFormat("#,##0.00;(#,##0.00)", root).format(-42.5));
        System.out.println("per mille -> " + new DecimalFormat("#‰", root).format(0.123).equals("123‰"));
        System.out.println("NaN/infinity -> " + grouped.format(Double.NaN) + " "
                + grouped.format(Double.POSITIVE_INFINITY).equals("∞") + " "
                + grouped.format(Double.NEGATIVE_INFINITY).equals("-∞"));
        System.out.println("parse -> " + grouped.parse("1,234.50") + " " + grouped.parse("-7") + " "
                + new DecimalFormat("00.0%", root).parse("12.5%"));
        DecimalFormatSymbols custom = DecimalFormatSymbols.getInstance(Locale.ROOT);
        custom.setDecimalSeparator(',');
        custom.setGroupingSeparator('.');
        System.out.println("custom separators -> " + new DecimalFormat("#,##0.00", custom).format(1234.5)
                + ", equals(root) = " + custom.equals(root) + ", clone equals = " + custom.clone().equals(custom));
        // Locale.US explicitly: under the DEFAULT locale (en, no country) Formatter's grouping branch calls
        // NumberFormat.getNumberInstance, whose locale-provider factory is still denied -- the recorded next gap.
        System.out.println("String.format(US, %,.2f) -> " + String.format(Locale.US, "%,.2f", 1234567.891));
        System.out.println("DecimalFormatProbe done");
    }
}
