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
 * Renders record components whose characters do NOT fit a byte.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values.
 *
 * <p>{@code Loader.recordToString} built its rendering in a {@code byte[]} and wrapped the result with
 * {@code guestString}, which hardcodes {@code coder = 0}. So the whole buffer could only ever carry code
 * points 0..255, however each component was appended, and TWO paths lost data:
 *
 * <ul>
 *   <li>a {@code char} component wrote ONE BYTE, so {@code '€'} rendered as {@code '¬'};</li>
 *   <li>a {@code String} component was copied VERBATIM out of its value array -- right for a LATIN1 String
 *       and garbage for a UTF16 one, whose bytes are little-endian code units. {@code "€"} is the byte
 *       pair {@code AC 20} and came back as the TWO characters {@code '¬'} and {@code ' '}.</li>
 * </ul>
 *
 * <p>THE ARMS PRINT CODE POINTS IN DECIMAL RATHER THAN THE CHARACTERS THEMSELVES, and that is deliberate
 * twice over. It keeps every byte of this probe's OUTPUT in ASCII, so the diff cannot be confounded by how
 * the two harnesses encode a non-ASCII character on the way to their console -- a difference that would have
 * nothing to do with the code under test. And it is SHARPER: printing the glyph, {@code C[c=€]} and
 * {@code C[c=¬]} differ by one character that renders, while a LENGTH plus code points also catches a
 * rendering of the right glyph at the wrong length -- which is exactly what the String arm produced.
 *
 * <p>THE BOUNDARY IS PINNED FROM BOTH SIDES. {@code 'ÿ'} is the last character that fits a byte and
 * {@code 'Ā'} the first that does not, so a fix that moves the cutoff by one shows in that pair and
 * nowhere else.
 *
 * <p>THE ARMS THAT PASS IN BOTH STATES ARE THE BUILT-IN COMPARISON, stated because an arm that passes either
 * way is not a control: every ASCII arm, and -- the useful one -- {@code 'é'} and {@code "é"},
 * which are NON-ASCII and still correct before the fix. A character that fits a byte was always stored and
 * wrapped correctly; the defect is specifically about code points above 255, and those two arms are what say
 * so rather than leaving it to be assumed.
 *
 * <p>THE {@code double} ARM MUST NOT MOVE. {@code Double.toString} produces ASCII, so widening the buffer
 * leaves the previous increment's fix rendering byte-for-byte as it did -- and the coder LATIN1.
 */
public class RecordUtf16Probe
{
    /** Print a rendering as its LENGTH and its code points, in decimal -- ASCII whatever it holds. */
    private static void dump(String label, String s)
    {
        StringBuilder b = new StringBuilder();
        b.append("  ").append(label).append(" len=").append(s.length()).append(" cps=");
        int i = 0;
        while (i < s.length())
        {
            if (i > 0)
            {
                b.append(',');
            }
            b.append((int) s.charAt(i));
            i += 1;
        }
        System.out.println(b.toString());
    }

    public record C(char c)
    {
    }

    public record S(String s)
    {
    }

    public record Mixed(char c, String s, int i)
    {
    }

    public record Ascii(int a, boolean b)
    {
    }

    public record D(double d)
    {
    }

    public record Name(int é)
    {
    }

    public static void main(String[] args)
    {
        // char: ASCII, then the two that fit a byte, then the three that do not.
        dump("char A       ", new C('A').toString());
        dump("char e-acute ", new C('é').toString());
        dump("char 00ff    ", new C('ÿ').toString());
        dump("char 0100    ", new C('Ā').toString());
        dump("char euro    ", new C('€').toString());
        dump("char ffff    ", new C('￿').toString());

        // String component: ASCII, a LATIN1 one, then UTF16 ones.
        dump("str abc      ", new S("abc").toString());
        dump("str e-acute  ", new S("é").toString());
        dump("str euro     ", new S("€").toString());
        dump("str a-euro-b ", new S("a€b").toString());
        dump("str null     ", new S(null).toString());

        // One record where the coder decision is made over MIXED content.
        dump("mixed        ", new Mixed('€', "xĀy", -42).toString());

        // Regression: an all-ASCII record, and the previous increment's double arm.
        dump("ascii        ", new Ascii(7, true).toString());
        dump("double 1.5   ", new D(1.5).toString());
        dump("double -0.0  ", new D(-0.0).toString());
        dump("double NaN   ", new D(Double.NaN).toString());

        // A non-ASCII COMPONENT NAME: the name is modified UTF-8 in the classfile too.
        dump("name e-acute ", new Name(5).toString());

        System.out.println("RecordUtf16Probe done");
    }
}
