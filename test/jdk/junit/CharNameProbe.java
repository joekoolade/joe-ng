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

/**
 * {@code Character.getName} and {@code Character.codePointOf}, arm by arm, against the host: both read the
 * Unicode name table from the java.base resource java/lang/uniName.dat (java.lang.CharacterName), which used to
 * be denied. The unassigned and Hangul/ideograph arms are the ones answered by rule rather than by the table.
 */
public final class CharNameProbe
{
    public static void main(String[] args)
    {
        arm("A", () -> Character.getName('A'));
        arm("space", () -> Character.getName(' '));
        arm("e-acute", () -> Character.getName(0xE9));
        arm("euro", () -> Character.getName(0x20AC));
        arm("emoji", () -> Character.getName(0x1F600));
        arm("hangul", () -> Character.getName(0xAC00));
        arm("cjk", () -> Character.getName(0x4E00));
        arm("unassigned", () -> String.valueOf(Character.getName(0x0378)));
        arm("codePointOf LATIN SMALL LETTER Z", () -> Integer.toHexString(Character.codePointOf("LATIN SMALL LETTER Z")));
        arm("codePointOf lower-case name", () -> Integer.toHexString(Character.codePointOf("euro sign")));
        arm("codePointOf bogus", () -> Integer.toHexString(Character.codePointOf("NOT A REAL CHARACTER NAME")));
        System.out.println("CharNameProbe done");
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
