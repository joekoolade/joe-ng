/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-18
 */
package java.util.zip;

import jdk.internal.misc.Unsafe;

/**
 * The "address" a zip native hands back for its stream: where Inflater.c answers a {@code z_stream} pointer, the
 * Java natives answer a HANDLE into this table, never 0 (stock reads 0 as "ended"). An ended stream's slot is
 * reused; a handle no live stream owns is an {@code InternalError}, not silence. Also the raw-memory copies the
 * direct-buffer natives need.
 */
final class MetalZStreams
{
    private static Object[] slots = new Object[16];

    private MetalZStreams()
    {
    }

    static synchronized long put(Object stream)
    {
        int i = 0;
        while (i < slots.length && slots[i] != null)
        {
            i += 1;
        }
        if (i == slots.length)
        {
            Object[] bigger = new Object[slots.length * 2];
            System.arraycopy(slots, 0, bigger, 0, slots.length);
            slots = bigger;
        }
        slots[i] = stream;
        return i + 1L;
    }

    static synchronized Object get(long handle)
    {
        Object s = handle >= 1 && handle <= slots.length ? slots[(int) (handle - 1)] : null;
        if (s == null)
        {
            throw new InternalError("no zip stream for handle " + handle);
        }
        return s;
    }

    static synchronized void remove(long handle)
    {
        get(handle);
        slots[(int) (handle - 1)] = null;
    }

    /** {@code len} bytes at a direct buffer's {@code address}, as an array. */
    static byte[] fromAddress(long address, int len)
    {
        Unsafe u = Unsafe.getUnsafe();
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = u.getByte(address + i);
            i += 1;
        }
        return b;
    }

    /** The first {@code len} bytes of {@code b} to a direct buffer's {@code address}. */
    static void toAddress(byte[] b, int len, long address)
    {
        Unsafe u = Unsafe.getUnsafe();
        int i = 0;
        while (i < len)
        {
            u.putByte(address + i, b[i]);
            i += 1;
        }
    }
}
