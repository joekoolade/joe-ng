/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */

/**
 * STRING CONCATENATION OUTSIDE LATIN-1 ({@code invokedynamic makeConcatWithConstants}). joe-ng's concat builder was a
 * BYTE buffer: a UTF-16 String argument's storage was copied verbatim, a {@code char} lost its high byte, a non-ASCII
 * literal in the recipe was appended as its modified-UTF-8 bytes, and the result was always LATIN1. Every arm builds
 * its operands at RUN time (javac folds constant expressions), and prints UTF-16 code units and lengths rather than
 * the text, so the console's encoding cannot hide a wrong answer. Prints values only; the host diff is the gate.
 */
public class ConcatUtf16Probe
{
    public static void main(String[] args)
    {
        String euro = new String(new char[] {'H', 'i', (char) 0x20AC});
        char c = (char) (0x20A0 + args.length + 12);
        char e = (char) (0xE0 + args.length + 9);
        String latin = "caf" + e;
        show("utf16 arg", "x=" + euro);
        show("utf16 char var", "x=" + c);
        show("latin1 char var", "x=" + e);
        show("non-ascii literal", "café " + args.length);
        show("utf16 literal", "€" + args.length + "é");
        show("latin1 then utf16", latin + euro);
        show("utf16 then latin1", euro + latin);
        show("utf16 + int + char", euro + 42 + c);
        show("supplementary", "<" + new String(Character.toChars(0x1F600)) + ">" + args.length);
        String nothing = null;
        show("utf16 + null", euro + nothing);
        Integer boxed = 7;
        show("utf16 + boxed", euro + boxed);
        String chain = "";
        for (int i = 0; i < 40; i += 1)
        {
            chain = chain + (char) (0x3B1 + (i % 24));
        }
        show("grown utf16", chain);
        System.out.println("equals(new String)=" + ("x=" + euro).equals(new String(new char[] {'x', '=', 'H', 'i', (char) 0x20AC}))
                + " hash=" + ("x=" + euro).hashCode());
        System.out.println("ConcatUtf16Probe done");
    }

    private static void show(String label, String s)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 1)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(Integer.toHexString(s.charAt(i)));
        }
        System.out.println(label + ": len=" + s.length() + " units=" + sb);
    }
}
