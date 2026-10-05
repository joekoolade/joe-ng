/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-05
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.StringJoiner;

/**
 * The {@code JavaLangAccess} members joe-ng now IMPLEMENTS, reached through their real stock callers.
 *
 * <p>Each was a stub answering 0/null/nothing. Every arm goes through stock java.base code that calls the member,
 * so the probe tests the member where it is used rather than in isolation:
 * <ul>
 * <li>{@code DataOutputStream.writeUTF} -> {@code countNonZeroAscii}; {@code DataInputStream.readUTF} ->
 *     {@code countPositives}. The NUL and non-ASCII arms are the ones a wrong count gets wrong: modified UTF-8
 *     encodes U+0000 as TWO bytes, so an all-ASCII fast path that does not stop at NUL writes the wrong bytes.</li>
 * <li>A {@code double}/{@code float} appended to a {@code StringBuilder} already holding a char above U+00FF
 *     (UTF16 coder) -> {@code jdk.internal.math.ToDecimal} -> {@code uncheckedPutCharUTF16}. An empty body there
 *     leaves the digits unwritten.</li>
 * <li>{@code StringJoiner} -> {@code join}.</li>
 * </ul>
 * Every arm prints LENGTHS and code points as well as text, so the host-vs-metal diff cannot pass over a right
 * string at the wrong length. The gate is a byte-for-byte diff against a stock JVM running this file.
 */
public class JlaProbe
{
    private static String codes(String s)
    {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length())
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append((int) s.charAt(i));
            i += 1;
        }
        return sb.toString();
    }

    private static String hex(byte[] b)
    {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < b.length)
        {
            String h = Integer.toHexString(b[i] & 0xFF);
            if (h.length() < 2)
            {
                sb.append('0');
            }
            sb.append(h);
            i += 1;
        }
        return sb.toString();
    }

    private static void utf(String label, String s) throws Exception
    {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeUTF(s);
        out.flush();
        byte[] b = bo.toByteArray();
        String back = new DataInputStream(new ByteArrayInputStream(b)).readUTF();
        System.out.println(label + " bytes=" + hex(b) + " back=" + codes(back) + " same=" + back.equals(s));
    }

    public static void main(String[] args) throws Exception
    {
        utf("utf ascii", "hello");
        utf("utf empty", "");
        utf("utf nul", "a\u0000b");
        utf("utf latin1", "café");
        utf("utf euro", "x€y");
        utf("utf mixed", "abcÿĀxyz");

        StringBuilder d = new StringBuilder("€");
        d.append(1.5);
        System.out.println("utf16 sb + double len=" + d.length() + " codes=" + codes(d.toString()));
        StringBuilder f = new StringBuilder("€");
        f.append(0.1f);
        System.out.println("utf16 sb + float len=" + f.length() + " codes=" + codes(f.toString()));
        StringBuilder e = new StringBuilder("€");
        e.append(1.0e20);
        System.out.println("utf16 sb + 1e20 len=" + e.length() + " codes=" + codes(e.toString()));
        StringBuilder l = new StringBuilder("e");
        l.append(-2.5);
        System.out.println("latin1 sb + double = " + l);

        StringJoiner sj = new StringJoiner("|", "<", ">");
        sj.add("p");
        sj.add("€");
        System.out.println("joiner = " + codes(sj.toString()));

        System.out.println("JlaProbe done");
    }
}
