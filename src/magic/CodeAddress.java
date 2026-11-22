/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-22
 */
package magic;

/**
 * An address in compiled code: a method's entry, a stub, a call site, a line table -- anything in the code arena.
 *
 * <p>UNBOXED, lowered exactly like {@link Address} (the same rules in {@code compiler.Unboxed}). It exists for the
 * collector: a FIELD of this type is a code root (frames/GC arc S6b-3). The code arena has no headers; a compiled
 * method stays alive while some word the trace follows points into it, and a precise trace follows a {@code long}
 * field no further than an {@code int}. A {@code CodeAddress} field is followed: its value is recorded in the code
 * bitmap and nothing on the heap is marked. A LOCAL of this type is raw, as an {@code Address} local is: code a frame
 * is running is kept by the frame's return address, not by its locals.
 *
 * <p>Holds 0, an address in the code arena, or an address outside it (the boot image's code, never collected). The
 * bodies here only throw: nothing runs them on the seed JVM.
 */
public final class CodeAddress
{
    private CodeAddress()
    {
    }

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic.CodeAddress is unboxed -- lowered by the compiler, never executed");
    }

    /** The null code address. */
    public static CodeAddress zero()
    {
        throw intrinsic();
    }

    /** The code address whose bits are {@code address}. */
    public static CodeAddress fromLong(long address)
    {
        throw intrinsic();
    }

    /** The code address at {@code address}. */
    public static CodeAddress fromAddress(Address address)
    {
        throw intrinsic();
    }

    /** As an {@link Address}. */
    public Address toAddress()
    {
        throw intrinsic();
    }

    /** The address's bits. */
    public long toLong()
    {
        throw intrinsic();
    }

    /** Whether this is the null code address. */
    public boolean isZero()
    {
        throw intrinsic();
    }

    /** Whether this and {@code other} are the same address. */
    public boolean EQ(CodeAddress other)
    {
        throw intrinsic();
    }

    /** Whether this and {@code other} differ. */
    public boolean NE(CodeAddress other)
    {
        throw intrinsic();
    }
}
