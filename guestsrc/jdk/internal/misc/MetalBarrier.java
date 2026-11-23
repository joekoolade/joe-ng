/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-23
 */
package jdk.internal.misc;

import compiler.Symbols;
import magic.Address;
import magic.Magic;

/**
 * The write barrier for guest code that stores a reference WITHOUT a {@code putfield}/{@code aastore} -- through
 * {@code magic.Address} (Unsafe, the field updaters). The compiler emits the barrier after every reference
 * {@code putfield} and {@code aastore}; a raw store is invisible to it, so its writer logs the object here: the same
 * bit in the same log bitmap (frames/GC arc S7-2, {@code vm.WriteBarrier}). The constants are the compiler's,
 * inlined by javac.
 */
public final class MetalBarrier
{
    private MetalBarrier()
    {
    }

    /** A reference was just stored into the object {@code o} (any field or element of it). */
    public static void log(Object o)
    {
        if (!Symbols.WRITE_BARRIER)
        {
            return;
        }
        long a = Magic.addrOf(o);
        if (a <= 0L || a >= Symbols.LOG_SPAN)
        {
            return;
        }
        Address w = Address.fromLong(Symbols.LOG_BITMAP + ((a >> 9) << 3));
        w.store(w.loadLong() | (1L << (int) ((a >> 3) & 63L)));
    }
}
