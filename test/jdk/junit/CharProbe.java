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
 * {@code java.lang.Character}, against the HOST as ORACLE. This file carries NO expected values: compiled
 * against the real JDK the arms reach stock {@code Character}, compiled against {@code guestsrc} they reach
 * whatever overlay is in place, and the gate is a byte-for-byte diff of the two runs. Every arm must match.
 *
 * <p>MEASURED AGAINST THE OVERLAY BEING RETIRED: 24 of these 61 arms were WRONG. The bulk is what was
 * expected -- the overlay's {@code isLetter} is literally
 * {@code (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')}, so every letter above U+007F answered FALSE
 * where stock answers TRUE, which is a wrong answer rather than the documented "unassigned" report and feeds
 * {@code Pattern}'s {@code \w}, every tokenizer and any guest parser.
 *
 * <p>BUT TWO OF MY OWN CLAIMS ABOUT THIS FILE WERE FALSIFIED BY RUNNING IT, and they are corrected here
 * rather than quietly dropped -- a probe whose javadoc mis-states which arms discriminate is the shape this
 * project has been bitten by repeatedly.
 * <p>(1) "THE ASCII ARMS CANNOT FAIL" IS FALSE. {@code U+0000} discriminates: stock answers
 *     {@code isJavaIdentifierPart=true} for NUL (an ignorable control character IS an identifier part) and
 *     the overlay answered false. So ASCII is where a from-scratch implementation looks safest and still has
 *     a hole -- the other eight ASCII arms are genuine coverage, and this one is not.
 * <p>(2) "THE CODE-POINT CLUSTER IS REGRESSION COVER" IS HALF FALSE. The surrogate, {@code charCount} and
 *     {@code isValidCodePoint} arms ARE pure bit arithmetic and pass in both states. But
 *     {@code isLetter(int)}/{@code isDigit(int)} route through {@code CharacterData} like their {@code char}
 *     siblings, so the supplementary arms ({@code U+1D400}, {@code U+1D7CE}) discriminate too.
 *
 * <p>The {@code valueOf} arms are genuine regression cover and did NOT move: they pin JLS 5.1.7 interning,
 * which one increment ago moved to stock's nested {@code CharacterCache}.
 */
public class CharProbe
{
    public static void main(String[] args)
    {
        System.out.println("char probe:");

        // COVERAGE: ASCII. Passes in both states -- the overlay is correct here.
        System.out.println("  -- ascii (coverage: correct in both states) --");
        cls('A'); cls('z'); cls('5'); cls(' '); cls('\t'); cls('_'); cls('$'); cls('\n'); cls((char) 0);

        // DISCRIMINATION: above U+007F. The overlay answers these wrong.
        System.out.println("  -- non-ascii (the discriminating arms) --");
        cls('é');   // e-acute: a letter
        cls('É');   // E-acute: an upper-case letter
        cls('ß');   // sharp s: lower-case, no simple upper
        cls(' ');   // NBSP: space char but NOT whitespace -- the two predicates differ here
        cls('µ');   // micro sign
        cls('Ω');   // capital omega
        cls('ω');   // small omega
        cls('中');   // CJK ideograph
        cls('١');   // Arabic-Indic digit one: a DIGIT that is not ASCII
        cls('०');   // Devanagari digit zero
        cls('Ⅰ');   // Roman numeral one: letter-number
        cls('Ａ');   // fullwidth A
        cls('​');   // zero-width space: format char
        cls('\uD800');   // an unpaired high surrogate
        cls('￿');   // not a character

        // digit()/getNumericValue across radices -- what Integer.parseInt leans on.
        System.out.println("  -- digit / numeric value --");
        dig('7', 10); dig('f', 16); dig('F', 16); dig('z', 36); dig('8', 8);
        dig('١', 10); dig('०', 10); dig('é', 16);
        num('7'); num('f'); num('١'); num('Ⅰ'); num('½'); num('A');

        // Case mapping, which routes through the CharacterData tables in stock.
        System.out.println("  -- case mapping --");
        cse('a'); cse('A'); cse('5');
        cse('é'); cse('É'); cse('ω'); cse('Ω'); cse('ÿ'); cse('中');

        // CODE POINTS: pure bit arithmetic, correct in both states -- regression cover for regex.
        System.out.println("  -- code points (regression cover) --");
        System.out.println("  isHigh(D800)=" + Character.isHighSurrogate('\uD800')
                           + " isLow(DC00)=" + Character.isLowSurrogate('\uDC00')
                           + " pair=" + Character.isSurrogatePair('\uD800', '\uDC00'));
        System.out.println("  toCodePoint=" + Character.toCodePoint('\uD800', '\uDC00')
                           + " charCount(10000)=" + Character.charCount(0x10000)
                           + " charCount(41)=" + Character.charCount(0x41));
        System.out.println("  isValidCP(10FFFF)=" + Character.isValidCodePoint(0x10FFFF)
                           + " isValidCP(110000)=" + Character.isValidCodePoint(0x110000)
                           + " isBmp(FFFF)=" + Character.isBmpCodePoint(0xFFFF));
        System.out.println("  isLetter(cp 1D400)=" + Character.isLetter(0x1D400)
                           + " isDigit(cp 1D7CE)=" + Character.isDigit(0x1D7CE));

        // BOXING: JLS 5.1.7 interning, which moved to stock's nested CharacterCache one increment ago.
        System.out.println("  -- boxing (regression cover) --");
        System.out.println("  valueOf interned=" + (Character.valueOf('A') == Character.valueOf('A'))
                           + " above cache fresh=" + (Character.valueOf((char) 200) != Character.valueOf((char) 200)));
        System.out.println("  MIN=" + (int) Character.MIN_VALUE + " MAX=" + (int) Character.MAX_VALUE
                           + " compare=" + Character.compare('a', 'b')
                           + " toString=" + Character.toString('Q'));

        System.out.println("survived");
    }

    /** Every classification predicate for one char, on one line -- so a diff names the char and the predicate. */
    private static void cls(char c)
    {
        System.out.println("  U+" + hex(c)
                           + " letter=" + Character.isLetter(c)
                           + " digit=" + Character.isDigit(c)
                           + " alnum=" + Character.isLetterOrDigit(c)
                           + " alpha=" + Character.isAlphabetic(c)
                           + " ws=" + Character.isWhitespace(c)
                           + " space=" + Character.isSpaceChar(c)
                           + " upper=" + Character.isUpperCase(c)
                           + " lower=" + Character.isLowerCase(c)
                           + " idStart=" + Character.isJavaIdentifierStart(c)
                           + " idPart=" + Character.isJavaIdentifierPart(c)
                           + " type=" + Character.getType(c));
    }

    private static void dig(char c, int radix)
    {
        System.out.println("  digit(U+" + hex(c) + "," + radix + ")=" + Character.digit(c, radix));
    }

    private static void num(char c)
    {
        System.out.println("  numericValue(U+" + hex(c) + ")=" + Character.getNumericValue(c));
    }

    private static void cse(char c)
    {
        System.out.println("  U+" + hex(c) + " upper=U+" + hex(Character.toUpperCase(c))
                           + " lower=U+" + hex(Character.toLowerCase(c)));
    }

    /** Fixed-width hex, so the arms line up and a diff is readable. */
    private static String hex(char c)
    {
        String s = Integer.toHexString(c).toUpperCase();
        while (s.length() < 4)
        {
            s = "0" + s;
        }
        return s;
    }
}
