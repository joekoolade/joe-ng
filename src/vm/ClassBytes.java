/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-17
 */
package vm;

import magic.Address;

/**
 * Big-endian reads of classfile bytes in memory, and bytecode instruction lengths -- the loader's view of a
 * {@code .class} blob that has not been copied onto the heap (JikesRVM: {@code BytecodeStream}). Stateless.
 * Split out of {@link Loader}; the loader and its sibling classes reach these through {@code import static}.
 */
final class ClassBytes
{
    private ClassBytes()
    {
    }

    static int u1(long p)
    {
        return Address.fromLong(p).loadUnsignedByte() & 0xFF;
    }

    static int u2(long p)
    {
        return (u1(p) << 8) | u1(p + 1);
    }

    static int u4(long p)
    {
        return (u2(p) << 16) | u2(p + 2);
    }

    /** {@link #u1(long)} at an {@link Address}: the same zero-extending 8-bit read. */
    static int u1(Address p)
    {
        return p.loadUnsignedByte() & 0xFF;
    }

    /** {@link #u2(long)} at an {@link Address}: big-endian, as the classfile stores it. */
    static int u2(Address p)
    {
        return (u1(p) << 8) | u1(p.plus(1L));
    }

    /** {@link #u4(long)} at an {@link Address}: big-endian, as the classfile stores it. */
    static int u4(Address p)
    {
        return (u2(p) << 16) | u2(p.plus(2L));
    }

    static int opLen(int op)
    {
        if (op == 0x10 || op == 0x12 || op == 0x15 || op == 0x16 || op == 0x17 || op == 0x18 || op == 0x19
                || op == 0x36 || op == 0x37 || op == 0x38 || op == 0x39 || op == 0x3a || op == 0xa9 || op == 0xbc)
        {
            return 2;    // bipush/ldc/iload/lload/fload/dload/aload/istore/lstore/fstore/dstore/astore/ret/newarray
        }
        if (op == 0xb9 || op == 0xba)
        {
            return 5;    // invokeinterface: index(2)+count(1)+zero(1) / invokedynamic: index(2)+zero(2)
        }
        if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || (op >= 0x99 && op <= 0xa8) || op == 0xc6 || op == 0xc7
                || op == 0xb2 || op == 0xb3 || op == 0xb4 || op == 0xb5 || op == 0xb6 || op == 0xb7 || op == 0xb8
                || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1)
        {
            return 3;    // sipush/ldc_w/ldc2_w/iinc/if*/goto/jsr/get-put(static|field)/invoke*/new/anewarray/cast/instanceof
        }
        return 1;
    }

    /**
     * Full instruction length at {@code pc} in the bytecode at memory {@code code} — position-aware, so the
     * variable-length {@code tableswitch}/{@code lookupswitch}/{@code wide}/{@code multianewarray} (present in
     * real java.base) don't misalign the scanners. Falls back to the fixed table {@link #opLen(int)}.
     */
    static int insnLen(long code, int pc)
    {
        int op = u1(code + pc);
        if (op == 0xAA)                                    // tableswitch
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            return (p + 12 + (u4(code + p + 8) - u4(code + p + 4) + 1) * 4) - pc;
        }
        if (op == 0xAB)                                    // lookupswitch
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            return (p + 8 + u4(code + p + 4) * 8) - pc;
        }
        if (op == 0xC4)                                    // wide (iinc = 6, else 4)
        {
            return u1(code + pc + 1) == 0x84 ? 6 : 4;
        }
        if (op == 0xC5)                                    // multianewarray
        {
            return 4;
        }
        return opLen(op);
    }
}
