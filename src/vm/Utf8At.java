/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package vm;

import magic.Address;

/**
 * A CONSTANT_Utf8 entry in loaded classfile bytes, seen through {@link compiler.Unboxed.Utf8}: {@code base + off}
 * is the entry's length {@code u2}, the bytes follow. How the metal JIT hands a member reference's owner, name and
 * descriptor to the shared unboxed-type classifier.
 */
final class Utf8At implements compiler.Unboxed.Utf8
{
    private final Address at;

    Utf8At(Address base, int off)
    {
        this.at = base.plus(off);
    }

    @Override
    public int length()
    {
        return ((at.loadUnsignedByte() & 0xFF) << 8) | (at.plus(1L).loadUnsignedByte() & 0xFF);
    }

    @Override
    public int byteAt(int i)
    {
        return at.plus(2L + i).loadUnsignedByte() & 0xFF;
    }
}
