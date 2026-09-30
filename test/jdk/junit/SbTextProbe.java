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
 * {@code StringBuilder} over text that does NOT fit a byte.
 *
 * <p>THE HOST RUN IS THE ORACLE AND THE GATE IS A BYTE-FOR-BYTE DIFF. This file carries NO expected values,
 * and every arm reports a LENGTH and hex CODE POINTS rather than the characters themselves, so its own output
 * is pure ASCII and a divergence can only implicate {@code StringBuilder}, never the console's encoding.
 *
 * <p>WHAT THE DELETED OVERLAY DID. It was a {@code byte[]} plus a count with NO coder: {@code put()} stored
 * {@code (byte) b} and {@code toString()} ended in {@code new String(t, (byte) 0)} -- LATIN1, hardcoded. So
 * the whole builder could only carry code points 0..255: {@code append('€')} TRUNCATED to
 * {@code '¬'}, {@code U+0100} became a NUL, and appending a UTF16 {@code String} contributed two
 * Latin-1 characters for each of its characters -- the wrong LENGTH as well as the wrong contents.
 *
 * <p>THAT IS THE SAME DEFECT FAMILY AS THE RECORD-{@code toString} BUFFER, in the hottest class in the VM.
 * Stock {@code AbstractStringBuilder} carries a real {@code coder} and inflates LATIN1 to UTF16 on demand, so
 * deleting the overlay fixes all of it at once rather than one member at a time.
 *
 * <p>THE ASCII AND LATIN1 ARMS ARE THE BUILT-IN COMPARISON, stated because an arm that passes in both states
 * is not a control: everything ASCII worked under the overlay, and so did a character up to {@code U+00FF},
 * because one byte was all it needed. They are what says stock did not change the answer where the overlay
 * was already right.
 *
 * <p>THE BOUNDARY IS PINNED FROM BOTH SIDES -- {@code U+00FF} last good, {@code U+0100} first bad -- and the
 * INFLATE arm is the one a coder-aware builder can still get wrong: append ASCII first, THEN a character
 * above 255, so the buffer must be widened in place with the characters already in it preserved.
 */
public class SbTextProbe
{
    private static void say(String label, String v)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(label).append(" len=").append(v.length()).append(" [");
        int i = 0;
        while (i < v.length())
        {
            if (i > 0)
            {
                sb.append(' ');
            }
            sb.append(Integer.toHexString(v.charAt(i)));
            i += 1;
        }
        sb.append(']');
        System.out.println(sb.toString());
    }

    /** Reduce a comparison to its SIGN, so the arm asserts the ordering and not an unspecified magnitude. */
    private static String sign(int c)
    {
        return c < 0 ? "lt" : c > 0 ? "gt" : "eq";
    }

    public static void main(String[] args)
    {
        System.out.println("-- append(char) --");
        say("A", new StringBuilder().append('A').toString());
        say("00e9", new StringBuilder().append('é').toString());
        say("00ff last ok", new StringBuilder().append('ÿ').toString());
        say("0100 first bad", new StringBuilder().append('Ā').toString());
        say("euro 20ac", new StringBuilder().append('€').toString());
        say("ffff", new StringBuilder().append('￿').toString());

        System.out.println("-- INFLATE: ASCII first, then a character above 255 --");
        say("ab then euro", new StringBuilder().append("ab").append('€').toString());
        say("euro then ab", new StringBuilder().append('€').append("ab").toString());
        say("grow past 16", new StringBuilder().append("0123456789abcdef").append('€').toString());

        System.out.println("-- append(String) --");
        say("ascii", new StringBuilder().append("hi").toString());
        say("latin1", new StringBuilder().append("é").toString());
        say("utf16", new StringBuilder().append("a€b").toString());
        say("cjk", new StringBuilder().append("中文").toString());
        say("surrogate pair", new StringBuilder().append("𝐀").toString());

        System.out.println("-- insert / reverse / length over UTF16 --");
        say("insert euro", new StringBuilder("ab").insert(1, '€').toString());
        say("reverse utf16", new StringBuilder("a€b").reverse().toString());
        say("charAt/len", new StringBuilder().append('€').append('x').toString());

        System.out.println("-- the ctor forms --");
        say("ctor String utf16", new StringBuilder("€").toString());
        say("ctor CharSeq utf16", new StringBuilder((CharSequence) "€z").toString());

        // THE OVERLAY DROPPED THREE SUPERTYPES, and a dropped supertype is how this VM has lost members
        // before: StringBuilder itself once dropped Appendable (breaking String.replaceAll) and PrintStream
        // dropped OutputStream (total silence from the launcher). Stock is
        // `StringBuilder extends AbstractStringBuilder implements Appendable, CharSequence, Serializable,
        // Comparable<StringBuilder>`; the overlay declared only the first two of those four interfaces, so
        // Comparable and Serializable CEASED TO EXIST for it and compareTo could not bind at all.
        System.out.println("-- supertypes the overlay dropped --");
        StringBuilder p1 = new StringBuilder("abc");
        StringBuilder p2 = new StringBuilder("abd");
        say("cmp lt sign", sign(p1.compareTo(p2)));
        say("cmp gt sign", sign(p2.compareTo(p1)));
        say("cmp eq sign", sign(p1.compareTo(new StringBuilder("abc"))));
        say("is Comparable", p1 instanceof Comparable ? "1" : "0");
        say("is Serializable", p1 instanceof java.io.Serializable ? "1" : "0");
        say("is CharSequence", p1 instanceof CharSequence ? "1" : "0");
        say("is Appendable", p1 instanceof Appendable ? "1" : "0");

        System.out.println("SbTextProbe done");
    }
}
