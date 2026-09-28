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
package java.text;

import java.io.Serializable;
import java.util.Locale;

/**
 * The number symbols for a VM that carries NO LOCALE DATA: ASCII, for every locale.
 *
 * <p>WHY THIS IS AN OVERLAY AND {@code java/util/Formatter} IS NOT -- the distinction is the whole point of
 * this file. The standing rule is that {@code guestsrc} shadows a stock class only where the stock one needs
 * a NATIVE, or is built on a subsystem this VM deliberately does not carry; a class that is pure logic runs
 * STOCK however large its source. Stock {@code Formatter} is 5,036 lines of pure logic with ZERO natives, so
 * it runs stock. Stock {@code DecimalFormatSymbols} is the opposite: its whole job is to READ LOCALE DATA,
 * through {@code LocaleProviderAdapter} -> {@code ResourceBundle} -> the module/service machinery this VM
 * denies. No faithful copy of it could work here whatever its shape, which is the same stated exception
 * {@code MessageDigest} and {@code ServiceLoader} already take.
 *
 * <p>SO THE ANSWER IS THE TRUTHFUL ONE RATHER THAN A GUESS. joe-ng has one locale (see {@code Locale}, itself
 * overlaid because stock {@code getDefault()} runs {@code initDefault()} -> {@code sun.util}), and its
 * symbols are the ASCII ones: {@code '0'}, {@code '.'}, {@code ','}, {@code '-'}. Those are exactly the
 * characters stock {@code Formatter} falls back to on its OWN null-locale branch
 * ({@code locale == null ? '0' : getDecimalFormatSymbols(locale).getZeroDigit()}), so a joe-ng
 * {@code String.format} agrees byte-for-byte with a stock one in the C/POSIX locale.
 *
 * <p>STATED DIVERGENCE, and it is the honest one: a caller who asks for a locale whose digits are not ASCII
 * gets ASCII. That is a missing capability reported as the neutral answer rather than as a wrong one -- there
 * is no locale data here to give a different reply from, and inventing per-locale symbols would be the
 * plausible-looking wrong answer this project has been bitten by repeatedly.
 *
 * <p>The surface is EXACTLY what stock {@code Formatter} calls and no more -- four symbol accessors plus the
 * factory and {@code getLocale()}, which it uses to validate its one-entry {@code DFS} cache. Deliberately
 * not the whole stock API: a member nothing reaches is a member nothing tests.
 *
 * <p>The two supertypes are DECLARED because an overlay wins the name, so a marker it omits CEASES TO EXIST
 * and {@code instanceof Serializable} answers false where stock says true -- the trap that cost this project
 * {@code StringBuilder implements CharSequence} and {@code PrintStream extends OutputStream}. Both are free
 * markers with no members. {@code clone()} itself is deliberately NOT overridden: nothing here reaches it,
 * and {@code Object.clone}'s shallow copy would be correct anyway for a single final field.
 */
public class DecimalFormatSymbols implements Cloneable, Serializable
{
    private final Locale locale;

    private DecimalFormatSymbols(Locale locale)
    {
        this.locale = locale;
    }

    public static final DecimalFormatSymbols getInstance()
    {
        return getInstance(Locale.getDefault());
    }

    public static final DecimalFormatSymbols getInstance(Locale locale)
    {
        return new DecimalFormatSymbols(locale);
    }

    /**
     * The locale this was built for. Stock {@code Formatter} caches ONE instance in a static and validates it
     * with {@code dfs.getLocale().equals(locale)}, so this has to hand back what {@code getInstance} was
     * given rather than a canonical one -- otherwise the cache misses on every call and allocates.
     */
    public Locale getLocale()
    {
        return locale;
    }

    public char getZeroDigit()
    {
        return '0';
    }

    public char getDecimalSeparator()
    {
        return '.';
    }

    public char getGroupingSeparator()
    {
        return ',';
    }

    public char getMinusSign()
    {
        return '-';
    }
}
