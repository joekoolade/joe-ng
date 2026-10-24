/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-24
 */

import java.text.DateFormatSymbols;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Locale-specific number and date symbols, arm by arm, against a host control run with
 * {@code --limit-modules java.base -Duser.timezone=GMT}: java.base carries only the ROOT and ENGLISH CLDR data
 * (the rest is jdk.localedata), which is exactly what this VM carries, so the host answers what a correct VM
 * with that data answers. The locale provider arc's third increment: the symbol and NumberFormat overlays
 * gave every locale the ROOT data ("¤"/"XXX" for en_US's currency) -- these arms name that divergence.
 */
public final class LocaleSymbolsProbe
{
    public static void main(String[] args)
    {
        arm("US decimal/grouping", () ->
        {
            DecimalFormatSymbols s = DecimalFormatSymbols.getInstance(Locale.US);
            return s.getDecimalSeparator() + " " + s.getGroupingSeparator() + " " + s.getMinusSign();
        });
        arm("US currency symbol", () -> DecimalFormatSymbols.getInstance(Locale.US).getCurrencySymbol());
        arm("US intl currency", () -> DecimalFormatSymbols.getInstance(Locale.US).getInternationalCurrencySymbol());
        arm("ROOT currency symbol", () -> DecimalFormatSymbols.getInstance(Locale.ROOT).getCurrencySymbol());
        arm("US number", () -> NumberFormat.getNumberInstance(Locale.US).format(1234567.891));
        arm("US integer", () -> NumberFormat.getIntegerInstance(Locale.US).format(1234567.891));
        arm("US percent", () -> NumberFormat.getPercentInstance(Locale.US).format(0.256));
        arm("ROOT number", () -> NumberFormat.getNumberInstance(Locale.ROOT).format(-1234.5));
        arm("US months[11]", () -> DateFormatSymbols.getInstance(Locale.US).getMonths()[11]);
        arm("US short weekdays[2]", () -> DateFormatSymbols.getInstance(Locale.US).getShortWeekdays()[2]);
        arm("US eras", () -> String.join("/", DateFormatSymbols.getInstance(Locale.US).getEras()));
        arm("ROOT months[0]", () -> DateFormatSymbols.getInstance(Locale.ROOT).getMonths()[0]);
        // LAST: these reach java.util.Currency, whose data is the java.base RESOURCE /java/util/currency.data.
        arm("Currency USD", () ->
        {
            Currency c = Currency.getInstance("USD");
            return c.getCurrencyCode() + " " + c.getDefaultFractionDigits() + " " + c.getSymbol(Locale.US);
        });
        arm("Currency of US", () -> Currency.getInstance(Locale.US).getCurrencyCode());
        arm("US currency", () -> NumberFormat.getCurrencyInstance(Locale.US).format(1234.5));
        System.out.println("LocaleSymbolsProbe done");
    }

    private interface Arm
    {
        String run() throws Exception;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = a.run();
        }
        catch (Throwable t)
        {
            r = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        System.out.println(what + " = " + r);
    }
}
