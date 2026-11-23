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
package vm;

import board.Uart;
import magic.Address;
import magic.Magic;
import magic.Uninterruptible;
import objectmodel.ObjectModel;

/**
 * The generational census (frames/GC arc S7-0, {@code gcgen=1}): what a nursery collector would find, measured on
 * the non-moving collector before any object moves. Three questions decide whether a nursery pays and what it costs:
 * <ul>
 *   <li><b>Do young objects die?</b> A block is YOUNG if it was allocated since the last collection, OLD if it
 *       survived one. A bitmap (one bit per 8 heap bytes) records the blocks live after each collection; the sweep's
 *       frees clear theirs, so a reused address reads young. Per collection: young bytes live and dead, old bytes
 *       live and dead.</li>
 *   <li><b>How many old objects point at young ones?</b> That is the remembered set a minor collection scans and the
 *       write barrier maintains: every block-to-block edge the trace follows is attributed to its source -- a root,
 *       a code-root entry (an address baked into compiled code), an old block or a young one.</li>
 *   <li><b>What could not move?</b> Young survivors whose identity hash was taken (S7-1's
 *       {@link IdentityHash} lets them move: a mover re-keys their entry), young survivors a code-root entry names (an immediate in compiled code), and
 *       young {@code allocData} survivors (device buffers are among them).</li>
 * </ul>
 * Off by default: arming allocates the survivor bitmap on the heap (3 MiB for core 0's small and large regions,
 * 1 MiB per secondary arena). Which objects were hashed comes from {@link IdentityHash}'s table (S7-1).
 */
final class GenCensus implements Uninterruptible
{
    private GenCensus()
    {
    }

    /** 1 = armed ({@code gcgen=1}). */
    static int on;

    private static final long CORE0_WORDS = (Heap.LARGE_LIMIT - Heap.BASE) >> 9;   // 1 bit per 8 bytes, 64 per word
    private static final long ARENA_WORDS = Heap.ARENA_SLOT >> 9;
    private static final long WORDS = CORE0_WORDS + 3L * ARENA_WORDS;

    private static long[] survived;

    // Where the edges being traced come from (set by VMGc around each phase).
    static final int SRC_ROOT = 0;
    static final int SRC_HEAP = 1;
    static final int SRC_CODE = 2;
    private static int src;
    private static boolean srcOld;
    private static boolean srcCounted;

    // This collection.
    static long youngLive;
    static long youngDead;
    static long oldLive;
    static long oldDead;
    static long youngLiveN;
    static long oldToYoung;            // edges from an old block to a young one
    static long remset;                // old blocks holding at least one such edge
    static long rootToYoung;           // root words naming a young block
    static long codeToYoung;           // code-root entries naming a young block (could not move)
    static long hashedYoungLive;       // young survivors whose identity hash was taken
    static long dataYoungLive;         // young allocData survivors (bytes)
    // Over the boot.
    static long collections;
    static long youngAllocTotal;
    static long youngLiveTotal;
    static long oldToYoungMax;
    static long remsetMax;
    static long codeToYoungTotal;
    static long hashedYoungLiveTotal;

    /** Words each bitmap needs: {@link VM}'s {@code gcgen=1} switch allocates them and calls {@link #arm}. */
    static int words()
    {
        return (int) WORDS;
    }

    static void arm(long[] survivedMap)
    {
        survived = survivedMap;
        on = 1;
    }

    /** The bit index of heap address {@code w}, or -1 outside every collected region. */
    private static long bit(long w)
    {
        if (w < Heap.BASE)
        {
            return -1L;
        }
        if (w < Heap.LARGE_LIMIT)
        {
            return (w - Heap.BASE) >> 3;
        }
        long c = (w - Heap.LARGE_LIMIT) / Heap.ARENA_SLOT;
        if (c > 2L)
        {
            return -1L;
        }
        return (CORE0_WORDS << 6) + c * (ARENA_WORDS << 6) + ((w - Heap.LARGE_LIMIT - c * Heap.ARENA_SLOT) >> 3);
    }

    private static boolean get(long[] map, long b)
    {
        return b >= 0L && (map[(int) (b >> 6)] & (1L << (int) (b & 63L))) != 0L;
    }

    private static void put(long[] map, long b, boolean v)
    {
        if (b < 0L)
        {
            return;
        }
        int i = (int) (b >> 6);
        long m = 1L << (int) (b & 63L);
        map[i] = v ? map[i] | m : map[i] & ~m;
    }

    /** VMGc: the edges that follow come from a root ({@link #SRC_ROOT}) or a code-root entry ({@link #SRC_CODE}). */
    static void source(int kind)
    {
        src = kind;
    }

    /** VMGc.scanBlock: the edges that follow come from heap block {@code o}. */
    static void sourceBlock(long o)
    {
        src = SRC_HEAP;
        srcOld = get(survived, bit(o));
        srcCounted = false;
    }

    /** VMGc.markBlock: an edge to validated block {@code w}, marked or not. */
    static void edge(long w)
    {
        if (get(survived, bit(w)))
        {
            return;                                    // to an old block: nothing a minor collection needs
        }
        if (src == SRC_ROOT)
        {
            rootToYoung = rootToYoung + 1L;
        }
        else if (src == SRC_CODE)
        {
            codeToYoung = codeToYoung + 1L;
        }
        else if (srcOld)
        {
            oldToYoung = oldToYoung + 1L;
            if (!srcCounted)
            {
                srcCounted = true;
                remset = remset + 1L;
            }
        }
    }

    /** After the trace, before the sweep: classify every block and carry the survivors into the next collection. */
    static void afterTrace()
    {
        youngLive = 0L;
        youngDead = 0L;
        oldLive = 0L;
        oldDead = 0L;
        youngLiveN = 0L;
        hashedYoungLive = 0L;
        dataYoungLive = 0L;
        region(Heap.BASE, Address.fromLong(Heap.PTR_CELL).loadLong());
        region(Heap.LARGE_BASE, Address.fromLong(Heap.LARGE_PTR_CELL).loadLong());
        int c = 1;
        while (c < 4)
        {
            region(Heap.arenaBase(c).toLong(), Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong());
            c += 1;
        }
        collections = collections + 1L;
        youngAllocTotal = youngAllocTotal + youngLive + youngDead;
        youngLiveTotal = youngLiveTotal + youngLive;
        codeToYoungTotal = codeToYoungTotal + codeToYoung;
        hashedYoungLiveTotal = hashedYoungLiveTotal + hashedYoungLive;
        if (oldToYoung > oldToYoungMax)
        {
            oldToYoungMax = oldToYoung;
        }
        if (remset > remsetMax)
        {
            remsetMax = remset;
        }
        print();
        oldToYoung = 0L;
        remset = 0L;
        rootToYoung = 0L;
        codeToYoung = 0L;
    }

    private static void region(long from, long to)
    {
        long o = from;
        while (o < to)
        {
            long st = Address.fromLong(o).plus(ObjectModel.STATUS_OFFSET).loadLong();
            long size = st & -8L;
            if (size == 0L || o + size > to)
            {
                return;                                // as the sweep's guard
            }
            long b = bit(o);
            boolean old = get(survived, b);
            if ((st & Heap.FREE_FLAG) != 0L)
            {
                put(survived, b, false);
            }
            else if ((st & 1L) != 0L)
            {
                if (old)
                {
                    oldLive = oldLive + size;
                }
                else
                {
                    youngLive = youngLive + size;
                    youngLiveN = youngLiveN + 1L;
                    if (IdentityHash.contains(o))
                    {
                        hashedYoungLive = hashedYoungLive + 1L;
                    }
                    long tibw = Address.fromLong(o).loadLong();
                    if (tibw == 0L || PreciseTrace.isLayout(tibw))
                    {
                        dataYoungLive = dataYoungLive + size;
                    }
                }
                put(survived, b, true);
            }
            else
            {
                if (old)
                {
                    oldDead = oldDead + size;
                }
                else
                {
                    youngDead = youngDead + size;
                }
                put(survived, b, false);
            }
            o = o + size;
        }
    }

    private static void print()
    {
        Uart.write(Magic.bytes("gcgen #"));
        VM.printDec((int) collections);
        Uart.write(Magic.bytes(" young KB live/dead="));
        VM.printDec((int) (youngLive >> 10));
        Uart.putc((byte) 0x2F);
        VM.printDec((int) (youngDead >> 10));
        Uart.write(Magic.bytes(" n="));
        VM.printDec((int) youngLiveN);
        Uart.write(Magic.bytes(" old KB live/dead="));
        VM.printDec((int) (oldLive >> 10));
        Uart.putc((byte) 0x2F);
        VM.printDec((int) (oldDead >> 10));
        Uart.write(Magic.bytes(" old->young="));
        VM.printDec((int) oldToYoung);
        Uart.write(Magic.bytes(" remset="));
        VM.printDec((int) remset);
        Uart.write(Magic.bytes(" roots->young="));
        VM.printDec((int) rootToYoung);
        Uart.write(Magic.bytes(" code->young="));
        VM.printDec((int) codeToYoung);
        Uart.write(Magic.bytes(" hashedYoungLive="));
        VM.printDec((int) hashedYoungLive);
        Uart.write(Magic.bytes(" dataYoungLiveKB="));
        VM.printDec((int) (dataYoungLive >> 10));
        Uart.write(Magic.bytes(" hashCalls="));
        VM.printDec((int) VMNatives.identityHashes);
        Uart.putc((byte) 0x0A);
    }
}
