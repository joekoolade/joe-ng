/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
package jdk.internal.misc;

import java.util.HashMap;
import magic.Magic;

/**
 * joe-ng's "off-heap" memory for {@code Unsafe.allocateMemory}: a block is the PAYLOAD of a guest {@code byte[]},
 * held in a table from its address until {@code freeMemory}. Sound because the collector never moves objects
 * (compaction was settled unbuilt), so the payload address is stable for as long as the table references the array,
 * and the array is exactly as long as asked. This is what stock {@code DirectByteBuffer} and
 * {@code sun.nio.ch.Util}'s temporary buffers allocate through; there is no other memory beneath the VM to ask.
 *
 * <p>Semantics kept from stock: {@code allocateMemory(0)} answers 0 and {@code freeMemory(0)} is a no-op;
 * {@code reallocateMemory} keeps the common prefix. One difference, harmless: the memory comes back ZEROED, where
 * stock's is uninitialized. Freeing an address this table never handed out is an {@code InternalError}, not silence.
 */
final class MetalOffHeap
{
    private static final Object LOCK = new Object();
    private static final HashMap<Long, byte[]> BLOCKS = new HashMap<>();
    private static final long BASE = 24L;              // byte[] payload offset (Unsafe.ARRAY_BYTE_BASE_OFFSET)

    private MetalOffHeap()
    {
    }

    static long allocate(long bytes)
    {
        if (bytes == 0L)
        {
            return 0L;
        }
        if (bytes < 0L || bytes > Integer.MAX_VALUE - 16L)
        {
            throw new OutOfMemoryError("Unable to allocate " + bytes + " bytes");
        }
        byte[] block = new byte[(int) bytes];
        long address = Magic.addrOf(block) + BASE;
        synchronized (LOCK)
        {
            BLOCKS.put(address, block);
        }
        return address;
    }

    static void free(long address)
    {
        if (address == 0L)
        {
            return;
        }
        byte[] gone;
        synchronized (LOCK)
        {
            gone = BLOCKS.remove(address);
        }
        if (gone == null)
        {
            throw new InternalError("freeMemory: 0x" + Long.toHexString(address) + " was not allocated here");
        }
    }

    static long reallocate(long address, long bytes)
    {
        if (address == 0L)
        {
            return allocate(bytes);
        }
        byte[] old;
        synchronized (LOCK)
        {
            old = BLOCKS.get(address);
        }
        if (old == null)
        {
            throw new InternalError("reallocateMemory: 0x" + Long.toHexString(address) + " was not allocated here");
        }
        long fresh = allocate(bytes);
        long keep = Math.min((long) old.length, bytes);
        long i = 0L;
        while (i < keep)
        {
            Magic.store8(fresh + i, Magic.load8(address + i));
            i += 1L;
        }
        free(address);
        return fresh;
    }
}
