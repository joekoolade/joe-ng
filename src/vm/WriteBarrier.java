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

import board.Uart;
import compiler.Symbols;
import magic.Address;
import magic.Magic;
import magic.Uninterruptible;
import objectmodel.ObjectModel;

/**
 * The write barrier's run-time side (frames/GC arc S7-2). Baseline emits the barrier inline after every reference
 * {@code putfield} and {@code aastore} ({@code Baseline.writeBarrier}): it sets the stored-into object's bit in the
 * LOG BITMAP ({@link Symbols#LOG_BITMAP}, one bit per 8 bytes of everything below {@link Symbols#LOG_SPAN}). A
 * logged object is one a minor collection would scan for references into the nursery -- the remembered set,
 * object-remembering rather than cards (S7-0: a few hundred old blocks, some of them large arrays). Since S7-4d'
 * an {@code aastore} logs the ELEMENT instead, in the twin SLOT LOG ({@link Symbols#SLOT_BITMAP}), so a large table
 * costs a minor collection the slots written, not its length; raw stores still log the whole object.
 * <p>Nothing moves yet, so the barrier is checked IN SHADOW: with {@code gcgen=1} the census attributes every
 * old->young edge to its source block, and a source whose bit is not set is a MISS ({@link #checkEdge}) -- a store
 * the barrier did not see. An edge out of a STATICS block is counted apart ({@link #fromStatics}): {@code putstatic}
 * has no barrier, because statics are roots a minor collection scans whole. A miss INTO an {@code allocData} block is
 * also attributed ({@link #missesToData}); since S7-3 those blocks are pretenured, so it should not happen at all. With {@code gcbarrier=1} every collection clears the bitmap after the trace (so a bit
 * means "stored into since the last collection", the shadow check's premise) and counts what was logged; without it
 * the bits accumulate, and the barrier's cost is its already-logged path. VM code that stores a reference with a raw
 * {@code Address.store} calls {@link #log} itself.
 */
final class WriteBarrier implements Uninterruptible
{
    private WriteBarrier()
    {
    }

    /** 1 = {@code gcbarrier=1}: clear and count the log bitmap at every collection. */
    static int on;

    // Per collection (gcbarrier=1), and the shadow check (gcgen=1 + gcbarrier=1).
    static long loggedImage;           // logged objects in the image, last collection
    static long loggedHeap;            // ... in the heap
    static long loggedHeapMax;
    static long misses;                // old->young edges whose source was not logged, this collection
    static long missesTotal;
    static long fromStatics;           // ... edges out of a statics block (putstatic has no barrier: statics are
                                       //   roots), over the boot
    static long missesToData;          // ... of which into an allocData block (pretenured since S7-3: none
                                       //   expected), over the boot
    static long checked;               // old->young edges checked, over the boot
    private static int printed;
    private static long lastSrc;                         // one line per source block, not per edge
    private static final int PRINT_MAX = 40;            // over the boot

    /** Boot, after the MMU is on: the bitmap is raw RAM (all-ones on a cold Pi), so start it empty. */
    static void init()
    {
        clear(Symbols.LOG_BITMAP, Symbols.LOG_BITMAP + (Symbols.LOG_SPAN >> 6));
        clear(Symbols.SLOT_BITMAP, Symbols.SLOT_BITMAP + (Symbols.LOG_SPAN >> 6));
        clearSummary(0L, Symbols.LOG_SPAN);
    }

    /** The nursery is armed (S7-4a): nothing logged before can name a young object. */
    static void clearAll()
    {
        clear(Symbols.LOG_BITMAP, Symbols.LOG_BITMAP + (Symbols.LOG_SPAN >> 6));
        clearSlots(0L, Symbols.LOG_SPAN);
        clearSummary(0L, Symbols.LOG_SPAN);
    }

    /**
     * A full collection after the minor phase (S7-4a): the heap's bits go, since the sweep may free a logged block and
     * a stale bit would later name the middle of another; the trace re-logs every live block that still names a
     * resident. The image's bits stay: its objects are never freed.
     */
    static void clearHeap()
    {
        clear(bitmapOf(Heap.BASE), Symbols.LOG_BITMAP + (Symbols.LOG_SPAN >> 6));
        clearSlots(Heap.BASE, Symbols.LOG_SPAN);
        clearSummary(Heap.BASE, Symbols.LOG_SPAN);
    }

    /** Log the object at {@code o}: what the compiled barrier does, for VM code's raw reference stores. */
    static void log(long o)
    {
        if (o <= 0L || o >= Symbols.LOG_SPAN)
        {
            return;
        }
        Address w = Address.fromLong(Symbols.LOG_BITMAP + ((o >> 9) << 3));
        long bit = 1L << (int) ((o >> 3) & 63L);
        long old = w.loadLong();
        // The OR is ATOMIC, as the compiled barrier's is (S7-4d'''): a word covers 512 bytes, so two cores logging
        // neighbouring objects share it, and a plain load/or/store loses a bit. INLINE, not a helper: MEASURED, a
        // shared private helper here sent ClinitRaceProbe (nursery on) to a wild pc 0x80 in 24 of 24 boots; why is
        // not established -- this runs inside collections (the drain, fullEdge), so keep it call-free.
        while ((old & bit) == 0L && !w.attempt(old, old | bit))
        {
            old = w.loadLong();
        }
        Address.fromLong(Symbols.LOG_SUMMARY + (o >> 15)).store((byte) 0);   // dirty, as the compiled barrier does
    }

    /**
     * Log the reference SLOT at {@code a} in the slot log (S7-4d''): what the compiled {@code putstatic} and
     * {@code aastore} barriers do, for VM code that stores a reference into a static cell with a raw store. Statics
     * are not scanned whole by a minor collection any more, so an unlogged one is a reference it never updates.
     */
    static void logSlot(long a)
    {
        if (a <= 0L || a >= Symbols.LOG_SPAN)
        {
            return;
        }
        Address w = Address.fromLong(Symbols.SLOT_BITMAP + ((a >> 9) << 3));
        long bit = 1L << (int) ((a >> 3) & 63L);
        long old = w.loadLong();
        // The OR is ATOMIC, as the compiled barrier's is (S7-4d'''): a word covers 512 bytes, so two cores logging
        // neighbouring objects share it, and a plain load/or/store loses a bit. INLINE, not a helper: MEASURED, a
        // shared private helper here sent ClinitRaceProbe (nursery on) to a wild pc 0x80 in 24 of 24 boots; why is
        // not established -- this runs inside collections (the drain, fullEdge), so keep it call-free.
        while ((old & bit) == 0L && !w.attempt(old, old | bit))
        {
            old = w.loadLong();
        }
        Address.fromLong(Symbols.LOG_SUMMARY + (a >> 15)).store((byte) 0);
    }

    /**
     * VM code's reference FIELD store (S7-4d'''): {@code holder.off = v}, then the compiled barrier's job by hand --
     * log the holder when it is old and {@code v} young. For a holder filled across ALLOCATING calls: a minor
     * collection in between can promote a young holder, or find a mature one (logged at birth) naming nothing young
     * and clear its bit, and a raw store after that is an old->young edge no remembered set sees. Pass the holder's
     * address re-derived AFTER the allocating call, never one computed before it.
     */
    static void putRef(Address holder, long off, long v)
    {
        holder.plus(off).store(v);
        if (Nursery.contains(v) && !Nursery.contains(holder.toLong()))
        {
            log(holder.toLong());
        }
    }

    /** ... and an ELEMENT store {@code arr[k] = v} of a reference array: logs the slot, as {@code aastore} does. */
    static void putElem(Address arr, long k, long v)
    {
        Address slot = arr.plus((long) ObjectModel.ARRAY_BASE_OFFSET + k * 8L);
        slot.store(v);
        if (Nursery.contains(v) && !Nursery.contains(arr.toLong()))
        {
            logSlot(slot.toLong());
        }
    }

    /** Whether the object at {@code o} has been logged since the bitmap was last cleared. */
    static boolean logged(long o)
    {
        if (o <= 0L || o >= Symbols.LOG_SPAN)
        {
            return false;
        }
        long w = Address.fromLong(Symbols.LOG_BITMAP + ((o >> 9) << 3)).loadLong();
        return ((w >>> (int) ((o >> 3) & 63L)) & 1L) != 0L;
    }

    /** GenCensus.edge: an old->young edge from heap block {@code src}; a source the barrier did not log is a MISS. */
    static void checkEdge(long src, long dst)
    {
        checked = checked + 1L;
        if (logged(src) || slotLogged(src))
        {
            return;
        }
        long stib = Address.fromLong(src).loadLong();
        if (PreciseTrace.isLayout(stib) && PreciseTrace.layoutKind(stib) == ObjectModel.LAYOUT_STATICS)
        {
            fromStatics = fromStatics + 1L;            // a statics block: a root of every minor collection (S7-4)
            return;
        }
        long dtib = Address.fromLong(dst).loadLong();
        if (dtib == 0L || PreciseTrace.isLayout(dtib))
        {
            missesToData = missesToData + 1L;          // to an allocData block: pretenured since S7-3, so never young
        }                                              //   -- a real miss, attributed
        misses = misses + 1L;
        if (printed < PRINT_MAX && src != lastSrc)
        {
            printed += 1;
            lastSrc = src;
            Uart.write(Magic.bytes("gcbarrier MISS: "));
            HeapShadow.printTypeOf(Address.fromLong(src).loadLong());
            Uart.write(Magic.bytes(" @"));
            VM.printHex(src);
            Uart.write(Magic.bytes(" -> "));
            HeapShadow.printTypeOf(Address.fromLong(dst).loadLong());
            Uart.write(Magic.bytes(" @"));
            VM.printHex(dst);
            Uart.putc((byte) 0x0A);
        }
    }

    /** VMGc, after the trace and the census: count what was logged, report, and clear for the next cycle. */
    static void afterTrace()
    {
        loggedImage = count(Symbols.LOG_BITMAP, bitmapOf(Heap.IMAGE_CEILING));
        long h = 0L;
        h = h + count(bitmapOf(Heap.BASE), bitmapOf(Address.fromLong(Heap.PTR_CELL).loadLong() + 511L));
        h = h + count(bitmapOf(Heap.LARGE_BASE), bitmapOf(Address.fromLong(Heap.LARGE_PTR_CELL).loadLong() + 511L));
        int c = 1;
        while (c < 4)
        {
            long base = Heap.arenaBase(c).toLong();
            h = h + count(bitmapOf(base), bitmapOf(Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong() + 511L));
            c += 1;
        }
        loggedHeap = h;
        if (h > loggedHeapMax)
        {
            loggedHeapMax = h;
        }
        missesTotal = missesTotal + misses;
        if (GenCensus.on != 0)
        {
            Uart.write(Magic.bytes("gcbarrier: logged image/heap="));
            VM.printDec((int) loggedImage);
            Uart.putc((byte) 0x2F);
            VM.printDec((int) loggedHeap);
            Uart.write(Magic.bytes(" misses="));
            VM.printDec((int) misses);
            Uart.write(Magic.bytes(" fromStatics="));
            VM.printDec((int) fromStatics);
            Uart.write(Magic.bytes(" toData="));
            VM.printDec((int) missesToData);
            Uart.write(Magic.bytes(" of old->young checked="));
            VM.printDec((int) checked);
            Uart.putc((byte) 0x0A);
        }
        misses = 0L;
        clear(Symbols.LOG_BITMAP, Symbols.LOG_BITMAP + (Symbols.LOG_SPAN >> 6));
        clearSlots(0L, Symbols.LOG_SPAN);
        clearSummary(0L, Symbols.LOG_SPAN);
    }

    /**
     * Whether any slot of the block at {@code src} is in the slot log (an {@code aastore} logs the element, S7-4d').
     * The census does not say which slot held the edge, so for an array this accepts any logged element: looser than
     * the object check, but a barrier that logged nothing at all is still a miss.
     */
    private static boolean slotLogged(long src)
    {
        long tib = Address.fromLong(src).loadLong();
        if (tib < VMGc.META_LOW || tib >= VMGc.META_HIGH || (tib & 7L) != 0L)
        {
            return false;                              // a layout block or raw data: never an aastore target
        }
        long type = Address.fromLong(tib).plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
        if (type < VMGc.META_LOW || type >= VMGc.META_HIGH || (type & 7L) != 0L)
        {
            return false;
        }
        long isz = Address.fromLong(type).plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) != ObjectModel.ARRAY_TYPE_TAG)
        {
            return false;                              // not an array: only its start is ever logged
        }
        long len = Address.fromLong(src).plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong();
        long a = src + (long) ObjectModel.ARRAY_BASE_OFFSET;
        long e = a + len * 8L;                         // the image's arrays too: no size in their status word
        while (a < e)
        {
            long w = Address.fromLong(Symbols.SLOT_BITMAP + ((a >> 9) << 3)).loadLong();
            if (((w >>> (int) ((a >> 3) & 63L)) & 1L) != 0L)
            {
                return true;
            }
            a += 8L;
        }
        return false;
    }

    /**
     * Clear the slot log for memory {@code [lo, hi)} (32 KiB aligned) where the summary is dirty: every bit is set
     * with its summary byte, so a clean byte's 64 words are already zero. Saves clearing 6 MiB per full collection.
     */
    private static void clearSlots(long lo, long hi)
    {
        long s = lo >> 15;
        long sEnd = hi >> 15;
        while (s < sEnd)
        {
            if (Address.fromLong(Symbols.LOG_SUMMARY + s).loadByte() == 0)
            {
                clear(Symbols.SLOT_BITMAP + (s << 9), Symbols.SLOT_BITMAP + (s << 9) + 512L);
            }
            s += 1L;
        }
    }

    /** The bitmap word holding address {@code a}'s bit. */
    private static long bitmapOf(long a)
    {
        return Symbols.LOG_BITMAP + ((a >> 9) << 3);
    }

    /** Count the set bits in bitmap words {@code [from, to)}. */
    private static long count(long from, long to)
    {
        long n = 0L;
        long p = from;
        while (p < to)
        {
            long w = Address.fromLong(p).loadLong();
            while (w != 0L)
            {
                w = w & (w - 1L);
                n = n + 1L;
            }
            p = p + 8L;
        }
        return n;
    }

    /** Mark the summary bytes for memory {@code [lo, hi)} (both 256 KiB aligned) clean: 0xFF (zero is dirty). */
    private static void clearSummary(long lo, long hi)
    {
        long p = Symbols.LOG_SUMMARY + (lo >> 15);
        long to = Symbols.LOG_SUMMARY + (hi >> 15);
        while (p < to)
        {
            Address.fromLong(p).store(-1L);
            p = p + 8L;
        }
    }

    private static void clear(long from, long to)
    {
        long p = from;
        while (p < to)
        {
            Address.fromLong(p).store(0L);
            p = p + 8L;
        }
    }
}
