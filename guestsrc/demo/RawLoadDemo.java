/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-20
 */
package demo;

import magic.Magic;

/**
 * {@code Magic.load32} returns a CANONICAL int -- sign-extended in its 64-bit register, as its declared
 * return type requires. It lowered to {@code ldrw}, which ZERO-extends, so a raw 32-bit word with bit 31
 * set (an ordinary MMIO status register, or any word whose top bit is a flag) was inconsistent with itself:
 * a compare and a widening cast saw {@code -1} because both sign-extend first, while {@code /}, {@code %}
 * and {@code >>} read the whole register and answered from {@code 4294967295}.
 *
 * <p>Same invariant, and the same repair, as {@code f2i}/{@code d2i} -- and the same reason it hid: the
 * consumers that happen to sign-extend first were right all along and printed the right answer.
 *
 * <p>The arms below are split into the ones that CHANGE and the ones that could not. The second group is
 * the point of the audit that preceded this: every one of the 79 {@code Magic.load32} call sites in the
 * tree either masks, uses {@code >>>}, compares, widens to long, or does {@code + - *} -- all of which are
 * immune -- so this fix could be made at all without a hardware regression.
 */
public class RawLoadDemo
{
    public static void main(String[] args)
    {
        long cell = Magic.addrOf(new long[2]) + 24L;   // a heap word we own (the CasDemo idiom)

        Magic.store32(cell, 0xFFFFFFFF);               // every bit set: the int -1
        int v = Magic.load32(cell);

        // ---- the arms that CHANGE: they read the whole register ----
        System.out.println("load32(-1) / 2     = " + (v / 2) + " (want 0)");
        System.out.println("load32(-1) >> 1    = " + (v >> 1) + " (want -1)");
        System.out.println("load32(-1) % 7     = " + (v % 7) + " (want -1)");

        Magic.store32(cell, 0x80000000);               // INT_MIN
        int m = Magic.load32(cell);
        System.out.println("load32(MIN) >> 4   = " + (m >> 4) + " (want -134217728)");
        System.out.println("load32(MIN) / 2    = " + (m / 2) + " (want -1073741824)");

        // ---- the arms that could NOT change: this is what made the fix safe ----
        System.out.println("load32(-1)         = " + v + " (want -1)");
        System.out.println("load32(-1) < 0     = " + (v < 0 ? 1 : 0) + " (want 1)");
        System.out.println("load32(-1) & 0xFF  = " + (v & 0xFF) + " (want 255)");
        System.out.println("load32(-1) >>> 24  = " + (v >>> 24) + " (want 255)");
        System.out.println("load32(-1) as long = " + ((long) v) + " (want -1)");
        System.out.println("load32(-1) + 1     = " + (v + 1) + " (want 0)");

        // ---- a positive word is untouched either way (the everyday case) ----
        Magic.store32(cell, 0x12345678);
        int p = Magic.load32(cell);
        System.out.println("load32(0x12345678) = " + p + " (want 305419896)");
        System.out.println("  >> 16            = " + (p >> 16) + " (want 4660)");
        System.out.println("  masked           = " + ((p >> 16) & 0xFFFF) + " (want 4660)");

        // ---- the sibling accessors are UNCHANGED: load8 is an unsigned byte by construction ----
        Magic.store8(cell, 0xFF);
        System.out.println("load8(0xFF)        = " + Magic.load8(cell) + " (want 255)");
        Magic.store64(cell, -1L);
        System.out.println("load64(-1)         = " + Magic.load64(cell) + " (want -1)");
    }
}
