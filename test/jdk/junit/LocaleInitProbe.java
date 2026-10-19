/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-19
 */
import java.util.Locale;

/** Locale's on-metal initializer and the first String.format: the default locale, then each category's. */
public class LocaleInitProbe
{
    public static void main(String[] args)
    {
        System.out.println("step 1");
        Locale d = Locale.getDefault();
        System.out.println("default " + d.getLanguage());
        Locale f = Locale.getDefault(Locale.Category.FORMAT);
        System.out.println("format " + f.getLanguage());
        Locale g = Locale.getDefault(Locale.Category.DISPLAY);
        System.out.println("display " + g.getLanguage());
        System.out.println(String.format("format %d", 5));
        System.out.println("LocaleInitProbe done");
    }
}
