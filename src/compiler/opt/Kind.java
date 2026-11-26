/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-26
 */
package compiler.opt;

import classfile.ClassReader;
import compiler.Unboxed;

/**
 * The kind of an SSA value (PLAN.md "The optimizing compiler"). Sub-word Java types (boolean, byte, char, short)
 * are {@link #I}; the unboxed {@code magic} types are words, split by what the collector does with them.
 */
public final class Kind
{
    /** No value: a store, a branch, a void call. */
    public static final int V = 0;
    public static final int I = 1;
    public static final int J = 2;
    public static final int F = 3;
    public static final int D = 4;
    /** A reference. */
    public static final int A = 5;
    /** A raw word: Address, Word, Offset, Extent, CodeAddress. */
    public static final int W = 6;
    /** An unboxed ObjectReference: a word the collector traces as a reference. */
    public static final int O = 7;
    /** A DataReference: a raw data block's payload address, which keeps the block alive. */
    public static final int R = 8;
    /** Not yet known: a phi or a handler's home load before kind inference. */
    public static final int PENDING = 9;
    /** Two different kinds merged: no instruction may use the value. */
    public static final int TOP = 10;
    /** The second JVM slot of a long or double, in a local or on the operand stack. Never an instruction's kind
     *  in a block -- only a marker in builder states. */
    public static final int HI = 11;

    private static final String NAMES = "VIJFDAWORP?H";

    private Kind()
    {
    }

    public static char name(int k)
    {
        return NAMES.charAt(k);
    }

    /** Whether {@code k} takes two JVM slots. */
    public static boolean wide(int k)
    {
        return k == J || k == D;
    }

    /** The reference-like kinds: all of them are one 64-bit word, and the null constant is each of them. */
    public static boolean refLike(int k)
    {
        return k == A || k == W || k == O || k == R;
    }

    /** The kind of the field type whose first character is at {@code p} in {@code cls}; 'V' answers {@link #V}. */
    public static int ofType(byte[] cls, int p)
    {
        int c = ClassReader.u1(cls, p);
        if (c == 'J')
        {
            return J;
        }
        if (c == 'D')
        {
            return D;
        }
        if (c == 'F')
        {
            return F;
        }
        if (c == 'V')
        {
            return V;
        }
        if (c == '[')
        {
            return A;
        }
        if (c != 'L')
        {
            return I;
        }
        long h = 0xcbf29ce484222325L;                     // Unboxed.hash over the class name
        int q = p + 1;
        while (ClassReader.u1(cls, q) != ';')
        {
            h = h ^ (ClassReader.u1(cls, q) & 0xFF);
            h = h * 0x100000001b3L;
            q += 1;
        }
        return ofUnboxed(Unboxed.kindOfHash(h));
    }

    /** The kind of a value of the class whose Utf8 entry body (u2 length, then the name) is at {@code utf8Off}: an
     *  array or an ordinary class is {@link #A}; an unboxed magic class is its word kind. */
    public static int ofClassName(byte[] cls, int utf8Off)
    {
        int len = ClassReader.u2(cls, utf8Off);
        if (len > 0 && ClassReader.u1(cls, utf8Off + 2) == '[')
        {
            return A;
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < len; i++)
        {
            h = h ^ (ClassReader.u1(cls, utf8Off + 2 + i) & 0xFF);
            h = h * 0x100000001b3L;
        }
        return ofUnboxed(Unboxed.kindOfHash(h));
    }

    private static int ofUnboxed(int u)
    {
        if (u == Unboxed.ADDRESS || u == Unboxed.WORD || u == Unboxed.OFFSET || u == Unboxed.EXTENT
            || u == Unboxed.CODE_ADDRESS)
        {
            return W;
        }
        if (u == Unboxed.OBJECT_REFERENCE)
        {
            return O;
        }
        if (u == Unboxed.DATA_REFERENCE)
        {
            return R;
        }
        return A;
    }

    /** Offset just past the field type at {@code p}. */
    public static int skipType(byte[] cls, int p)
    {
        while (ClassReader.u1(cls, p) == '[')
        {
            p += 1;
        }
        if (ClassReader.u1(cls, p) == 'L')
        {
            while (ClassReader.u1(cls, p) != ';')
            {
                p += 1;
            }
        }
        return p + 1;
    }

    /** The return kind of the method descriptor whose Utf8 entry body is at {@code descOff}. */
    public static int returnOf(byte[] cls, int descOff)
    {
        int p = descOff + 2 + 1;
        while (ClassReader.u1(cls, p) != ')')
        {
            p = skipType(cls, p);
        }
        return ofType(cls, p + 1);
    }
}
