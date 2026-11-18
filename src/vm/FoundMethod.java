/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package vm;

/**
 * What a by-name method lookup in the current blob found: {@code Loader.findMethod}, {@code findMethodByBytes} and
 * {@code findMethodByOffsets} return one, or null when the class has no such method with a body. It replaces three
 * statics ({@code gFoundDescOff}, {@code gFoundStatic}, {@code gFoundSync}) that each lookup used to leave behind
 * for its caller -- and that any lookup in between, a demand-load or a nested compile, could silently overwrite.
 *
 * <p>The bytecode length and {@code max_locals} are not stored: they sit just ahead of {@link #code} in the
 * Code attribute, and {@link ClassBytes#codeLength} / {@link ClassBytes#maxLocals} read them from there.
 * IMMUTABLE once returned.
 */
final class FoundMethod
{
    final long code;      // bytecode start inside the blob's Code attribute
    final int descOff;    // descriptor Utf8 offset in the blob (for the compiler's prologue)
    final int isStatic;   // 1 if ACC_STATIC
    final int isSync;     // 1 if ACC_SYNCHRONIZED (the implicit method monitor, JVMS 2.11.10)

    FoundMethod(long code, int descOff, int access)
    {
        this.code = code;
        this.descOff = descOff;
        this.isStatic = (access & 0x0008) != 0 ? 1 : 0;
        this.isSync = (access & 0x0020) != 0 ? 1 : 0;
    }

    /** The bytecode address of {@code f}, or 0 when the lookup found nothing -- for callers that need only that. */
    static long codeOf(FoundMethod f)
    {
        if (f == null)
        {
            return 0L;
        }
        return f.code;
    }
}
