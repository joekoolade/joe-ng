/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code Long.parseLong(String)} and {@code Long.toHexString(long)} on metal.
 * parseLong shares parseInt's mini deps (String.charAt/length, Character.digit, NumberFormatException);
 * toHexString goes through {@code Long.numberOfLeadingZeros} + {@code Math.max} + {@code formatUnsignedLong0}
 * (indexing the loader-seeded {@code Integer.digits}) + {@code newStringWithLatin1Bytes}. Prints each result.
 */
public class LongMoreDemo
{
    public static void main(String[] args)
    {
        showParse("12345");                             // 12345
        showParse("-9999999999");                       // -9999999999 (> 32 bits)
        showParse("9223372036854775807");               // Long.MAX_VALUE
        showHex(255L);                                  // ff
        showHex(-1L);                                   // ffffffffffffffff (unsigned 64-bit)
        showHex(4886718345L);                           // 123456789

        // THE EXTREMES OF THE RANGE, through STRING CONCAT rather than Long.toString: concat has its own
        // formatter (VM.scLong), and it negated the value to take digits -- which is a no-op for
        // Long.MIN_VALUE, so the digit loop ran zero times and the whole number printed as a bare "-".
        // A silently truncated number, not a crash. Long.toString is the stock JDK path and was always fine,
        // so printing both is what tells the two apart.
        Magic.printStr("  concat MIN = " + lv(-9223372036854775808L) + " (want -9223372036854775808)\n");
        Magic.printStr("  concat MAX = " + lv(9223372036854775807L) + " (want 9223372036854775807)\n");
        Magic.printStr("  concat -1  = " + lv(-1L) + " (want -1)\n");
        Magic.printStr("  toString MIN = " + Long.toString(lv(-9223372036854775808L))
                + " (want -9223372036854775808)\n");
    }

    /** Opaque to javac's constant folding, so the concat sees a runtime value. */
    private static long lv(long v)
    {
        return v;
    }

    private static void showParse(String s)
    {
        long v = Long.parseLong(s);                     // real, unmodified Long.parseLong
        Magic.printStr("  Long.parseLong -> ");
        Magic.printStr(Long.toString(v));               // round-trip back to decimal
        Magic.printStr("\n");
    }

    private static void showHex(long v)
    {
        Magic.printStr("  Long.toHexString -> ");
        Magic.printStr(Long.toHexString(v));
        Magic.printStr("\n");
    }
}
