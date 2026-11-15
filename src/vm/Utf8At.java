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

import magic.Magic;

/**
 * A CONSTANT_Utf8 entry in loaded classfile bytes, seen through {@link compiler.Unboxed.Utf8}: {@code base + off}
 * is the entry's length {@code u2}, the bytes follow. How the metal JIT hands a member reference's owner, name and
 * descriptor to the shared unboxed-type classifier.
 */
final class Utf8At implements compiler.Unboxed.Utf8
{
    private final long at;

    Utf8At(long base, int off)
    {
        this.at = base + off;
    }

    @Override
    public int length()
    {
        return ((Magic.load8(at) & 0xFF) << 8) | (Magic.load8(at + 1L) & 0xFF);
    }

    @Override
    public int byteAt(int i)
    {
        return Magic.load8(at + 2L + i) & 0xFF;
    }
}
