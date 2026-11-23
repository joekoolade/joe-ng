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
package vm;

import magic.Address;
import magic.Magic;
import magic.Uninterruptible;
import objectmodel.ObjectModel;

/**
 * Identity hashes that survive a move (frames/GC arc S7-1). An object's identity hash is the address it had when the
 * hash was first taken, and it keeps that value for life: {@code System.identityHashCode} and {@code Object.hashCode}
 * must not change when a copying collector moves the object.
 * <p><b>The side table.</b> Every heap object whose hash was taken has an entry, keyed by its CURRENT address, whose
 * value is its hash. An unmoved object's entry has value == key; a mover calls {@link #move} as it copies, which
 * re-keys the entry and keeps the value. Objects outside the collected heap (the boot image) never move and never get
 * an entry: their hash is their address. The status word has no spare low bit (0 and 1 are the collector's marks, 2
 * is {@link Heap#FREE_FLAG}) and every size read masks it with {@code -8}, so a header HASHED bit would mean changing
 * every one of those reads; the census measured 850 hashes per Pi boot, so a table lookup per hash costs nothing.
 * <p><b>Lifetime.</b> {@link #purge} runs after the trace, before the sweep, and drops the entry of every unmarked
 * block -- so an address the sweep frees and the allocator reuses never answers a dead object's hash. A block freed
 * explicitly (not by a sweep) keeps its entry until the next collection; that is harmless while value == key, and
 * nothing frees a moved object explicitly.
 * <p><b>Locking.</b> Open addressing in one {@code long[]} of (key, value) pairs; 0 = empty, 1 = tombstone. Lookups
 * and inserts hold {@link #LOCK} with IRQs masked once more than one core schedules. The table grows OUTSIDE the
 * lock (an allocation can collect, and the collection purges the table); the critical section never allocates.
 */
final class IdentityHash implements Uninterruptible
{
    private IdentityHash()
    {
    }

    /** Raw scratch word in the lock page, its own cache line (see Uart's UART_LOCK list): 0x180. */
    private static final long LOCK = 0x0302_0180L;

    private static long[] table;       // (key, value) pairs
    private static int cap;            // pair slots, a power of two
    private static int used;           // live entries + tombstones
    private static int live;

    // Over the boot (VMStats).
    static long lookups;               // hashes asked of a heap object
    static long inserts;               // first hashes (entries made)
    static long moves;                 // entries re-keyed by a move
    static long purged;                // entries dropped by a collection
    static long liveMax;

    static long liveNow()
    {
        return (long) live;
    }

    /** Whether {@code x} lies in a collected heap region (core 0's small and large regions, arenas 1-3). */
    static boolean movable(long x)
    {
        if (x < Heap.BASE)
        {
            return false;
        }
        if (x < Heap.LARGE_LIMIT)
        {
            return true;
        }
        return x < Heap.LARGE_LIMIT + 3L * Heap.ARENA_SLOT;
    }

    /** The identity hash of the object at {@code x}: the address it was first hashed at. */
    static long hash(long x)
    {
        if (x == 0L || !movable(x))
        {
            return x;
        }
        ensureRoom();
        long daif = lock();
        lookups = lookups + 1L;
        long h = find(x);
        if (h == 0L)
        {
            h = x;
            if (insert(x, x))
            {
                inserts = inserts + 1L;
            }
        }
        unlock(daif);
        return h;
    }

    /**
     * A mover copied the object at {@code from} to {@code to}: its hash, if it has one, now belongs to {@code to}.
     * Returns the hash carried over, or 0 when the object had never been hashed (it gets {@code to}'s address if it
     * is hashed later).
     */
    static long move(long from, long to)
    {
        ensureRoom();
        long daif = lock();
        long h = 0L;
        int i = slotOf(from);
        if (i >= 0)
        {
            h = table[2 * i + 1];
            table[2 * i] = 1L;
            table[2 * i + 1] = 0L;
            live = live - 1;
            if (insert(to, h))
            {
                moves = moves + 1L;
            }
        }
        unlock(daif);
        return h;
    }

    /** VMGc, after the trace and before the sweep (stop-the-world): drop every entry whose block is unmarked. */
    static void purge()
    {
        if (table == null)
        {
            return;
        }
        int i = 0;
        while (i < cap)
        {
            long k = table[2 * i];
            if (k > 1L && (Address.fromLong(k).plus(ObjectModel.STATUS_OFFSET).loadLong() & 1L) == 0L)
            {
                table[2 * i] = 1L;
                table[2 * i + 1] = 0L;
                live = live - 1;
                purged = purged + 1L;
            }
            i += 1;
        }
    }

    /** Whether the object at {@code x} has had its hash taken (stop-the-world readers: the census). */
    static boolean contains(long x)
    {
        return slotOf(x) >= 0;
    }

    /** The hash recorded for key {@code x}, or 0 when it has none. */
    private static long find(long x)
    {
        int i = slotOf(x);
        if (i < 0)
        {
            return 0L;
        }
        return table[2 * i + 1];
    }

    private static int slotOf(long x)
    {
        if (table == null)
        {
            return -1;
        }
        int mask = cap - 1;
        int h = start(x, mask);
        int probe = 0;
        while (probe < cap)
        {
            long k = table[2 * h];
            if (k == x)
            {
                return h;
            }
            if (k == 0L)
            {
                return -1;
            }
            h = (h + 1) & mask;
            probe += 1;
        }
        return -1;
    }

    private static int start(long x, int mask)
    {
        return (int) ((x ^ (x >>> 17)) >>> 3) & mask;
    }

    /** Add (key, value) to the current table; false when it is full (ensureRoom keeps it at most half full). */
    private static boolean insert(long key, long value)
    {
        if (table == null)
        {
            return false;
        }
        boolean ok = put(table, cap, key, value);
        if (ok)
        {
            live = live + 1;
            used = used + 1;
            if ((long) live > liveMax)
            {
                liveMax = (long) live;
            }
        }
        return ok;
    }

    private static boolean put(long[] t, int n, long key, long value)
    {
        int mask = n - 1;
        int h = start(key, mask);
        int probe = 0;
        while (probe < n)
        {
            long k = t[2 * h];
            if (k == 0L || k == 1L)
            {
                t[2 * h + 1] = value;
                t[2 * h] = key;
                return true;
            }
            h = (h + 1) & mask;
            probe += 1;
        }
        return false;
    }

    /**
     * Keep at least one free slot per insert to come: past half full, rebuild -- at twice the size when live entries
     * fill a quarter or more, else at the same size (dropping the tombstones). The new table is allocated unlocked
     * and filled under the lock; a second core that rebuilt first makes this one's copy garbage.
     */
    private static void ensureRoom()
    {
        while (table == null || (used + 4) * 2 > cap)
        {
            int n = cap == 0 ? 256 : cap;
            if (live * 4 >= cap)
            {
                n = n * 2;
            }
            long[] fresh = new long[2 * n];
            long daif = lock();
            if (table == null || (used + 4) * 2 > cap)
            {
                int i = 0;
                int moved = 0;
                while (i < cap)
                {
                    long k = table[2 * i];
                    if (k > 1L)
                    {
                        boolean ok = put(fresh, n, k, table[2 * i + 1]);
                        moved += 1;
                    }
                    i += 1;
                }
                table = fresh;
                cap = n;
                used = moved;
                live = moved;
            }
            unlock(daif);
        }
    }

    private static long lock()
    {
        long daif = Magic.readDaif();
        Magic.disableIrq();
        if (VM.smpSched != 0)
        {
            Magic.spinLock(LOCK);
            return daif | 1L;                                   // bit 0 (never a DAIF bit): this hold took the lock
        }
        return daif;
    }

    private static void unlock(long daif)
    {
        if ((daif & 1L) != 0L)
        {
            Magic.spinUnlock(LOCK);
        }
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
    }

    /** VM boot: the lock word is raw scratch RAM, so free it before any core can take it. */
    static void init()
    {
        Address.fromLong(LOCK).store(0L);
    }
}
