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
 * bit in the same log bitmap (frames/GC arc S7-2, {@code vm.WriteBarrier}), and the same summary byte (S7-4d: a
 * bit set without it is never scanned, and the object's young referents go stale). The constants are the
 * compiler's, inlined by javac.
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
        Address.fromLong(Symbols.LOG_SUMMARY + (a >> 15)).store((byte) 0);   // and its summary byte: 0 = dirty (S7-4d)
    }

    /** A reference was just stored at {@code offset} in {@code o}; a null {@code o} makes {@code offset} the absolute
     *  address of a STATIC's cell (Unsafe's staticFieldBase/Offset pair), logged as a slot (S7-4d''). */
    public static void logAt(Object o, long offset)
    {
        if (o == null)
        {
            logSlot(offset);
        }
        else
        {
            log(o);
        }
    }

    /** A reference was just stored into the static cell at {@code a}: log the slot, as {@code putstatic} does. */
    public static void logSlot(long a)
    {
        if (!Symbols.WRITE_BARRIER)
        {
            return;
        }
        if (a <= 0L || a >= Symbols.LOG_SPAN)
        {
            return;
        }
        Address w = Address.fromLong(Symbols.SLOT_BITMAP + ((a >> 9) << 3));
        w.store(w.loadLong() | (1L << (int) ((a >> 3) & 63L)));
        Address.fromLong(Symbols.LOG_SUMMARY + (a >> 15)).store((byte) 0);
    }
}
