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
 * The nursery and the minor collection (frames/GC arc S7-4a, {@code gcnursery=1}). Each core bump-allocates its
 * young objects in its own nursery, fixed RAM above the write barrier's log bitmap ({@link #BASE}); a minor
 * collection copies the reachable ones into the mature heap (the existing non-moving one) and the nursery is
 * empty again. Pretenured blocks (S7-3: allocData, Class mirrors, string literals) and large objects never enter it.
 * <p><b>Mostly-copying.</b> A word the collector can only guess at -- a conservatively scanned stack range, an
 * untyped stub argument -- cannot be updated, so the block it names is PINNED: it stays where it is for this cycle
 * (a RESIDENT), its own slots are forwarded like any root, and its space is skipped by the allocator until the next
 * minor collection, which treats it as young again. A first pass pins (before anything moves, so nothing is copied
 * that a guess names); a second forwards every exact slot: typed stack slots, typed stub arguments, the image
 * statics by their kind maps, heap statics blocks, JIT map entries, and the REMEMBERED SET -- every object the write
 * barrier logged (S7-2), scanned and its bit cleared unless it still names a resident. Copies are queued and
 * scanned in turn (a worklist, since the mature heap is not contiguous). A copy that finds no mature space stays
 * resident instead: promotion failure costs space, not correctness.
 * <p>A FORWARDED block keeps its size in its status word (bit 0 set) and the new address in its TIB word, so the
 * nursery stays walkable by size. Nothing outside a collection sees either flag.
 * <p>Every collection begins with this minor phase; a full one then continues with residents as roots ({@link
 * #markResidents}), clears the heap's log bits (blocks it frees must not leave stale bits) and has its trace re-log
 * every live heap block that names a resident ({@link #fullEdge}). {@code gcnursery=2} makes every collection full
 * and POISONS the evacuated space, so a stale copy of an address -- a raw {@code long} or {@code Address} the
 * collector could not see -- faults where it is used instead of reading plausible junk; the full trace reports any
 * reference into evacuated space.
 */
final class Nursery implements Uninterruptible
{
    private Nursery()
    {
    }

    /** 1 = {@code gcnursery=1}; 2 = verify (every collection full, evacuated space poisoned). */
    static int on;

    /** Core {@code c}'s nursery is [{@code BASE + c * SLOT_MAX}, + {@link #slot}). Above the log bitmap, so the
     *  barrier skips young sources for free: it logs only objects below {@link Symbols#LOG_SPAN}. */
    static final long BASE = 0x1E80_0000L;
    static final long SLOT_MAX = 0x0080_0000L;                  // 8 MiB per core
    static final long END = BASE + 4L * SLOT_MAX;               // 0x2080_0000
    /** Block starts in the nurseries, one bit per 8 bytes, built on the first pin of a collection. */
    static final long BITS = END;
    static final long BITS_END = BITS + ((END - BASE) >> 6);     // 512 KiB
    /** The copy worklist: copied and resident blocks whose slots are still to be forwarded. */
    static final long WORK = BITS_END;
    static final long WORK_END = WORK + 0x0040_0000L;            // 4 MiB = 512k entries
    /** Blocks pinned this collection (for a full collection's roots). */
    static final long PINS = WORK_END;
    static final long PINS_END = PINS + 0x8000L;                 // 4096 entries

    /** Bytes per core's nursery ({@code nursery=<KB>}, default 4 MiB, at most 8). */
    static long slot = 0x0040_0000L;

    static final long FWD = 1L;                                  // status bit 0: forwarded, the copy in the TIB word
    static final long RESIDENT = 2L;                             // status bit 1: pinned this collection
    static final long POISON = 0x00DE_AD00_DEAD_0000L;           // far above mapped RAM: a use faults

    private static final int GAPS = 512;                        // free runs per core between residents
    private static long[] cur;                                   // per core: bump pointer and limit of the gap
    private static long[] lim;
    private static int[] gapIdx;
    private static int[] gapN;
    private static long[] gapLo;                                 // core * GAPS + i
    private static long[] gapHi;

    /** The full collection that follows a minor phase: tryMarkExact hands nursery words to {@link #fullEdge}. */
    static int fullTrace;
    /** 1 asks the next collection for the minor phase only. */
    static int minorOnly;

    private static long workSp;
    private static int pinN;
    private static int bitsBuilt;                                // bit c: core c's block starts are in BITS
    private static boolean stillYoung;                           // the last slot forwarded still names the nursery

    // Statistics (/gc): over the boot unless noted.
    static long minors;
    static long fulls;                                           // collections that continued as full ones
    static long copiedBytes;
    static long copiedObjects;
    static long pinnedTotal;
    static long promotionFailures;
    static long remembered;                                      // logged objects scanned, last minor phase
    static long rememberedMax;
    static long survivedLast;                                    // bytes copied by the last minor phase
    static long allocBytes;                                      // bytes allocated in the nurseries
    static long staleRefs;                                       // gcnursery=2: references into evacuated space
    static long unknownBlocks;                                   // logged objects with no precise layout
    static long minorTicks;                                      // minor phases' time, summed (counter ticks)
    static long minorPauseTicks;                                 // minor-only collections' pauses, summed
    static long minorPauseMax;
    // S7-4b: where a minor phase's time goes (counter ticks, summed)
    static long tPin;                                            // pass 1: pin ambiguous roots (and build BITS)
    static long tFwd;                                            // pass 2: forward exact stack slots
    static long tRoots;                                          // image statics, heap statics, JIT map entries
    static long tRemset;                                         // the logged objects
    static long tDrain;                                          // residents and copies, scanned
    static long tAfter;
    static long tBits;                                           // ... of the pin pass: building BITS (S7-4b probe)
    static long nBits;                                          // IdentityHash re-key and the gap rebuild
    private static int stalePrinted;

    /** VMGc.finishMinor: a minor-only collection's pause, entry to restart. */
    static void minorPause(long ticks)
    {
        minorPauseTicks = minorPauseTicks + ticks;
        if (ticks > minorPauseMax)
        {
            minorPauseMax = ticks;
        }
    }

    /** {@code gcnursery=1|2} and {@code nursery=<KB>}: allocate the tables (still mature: {@link #on} is 0), lay a
     *  filler over each nursery, clear the log bitmap (nothing logged before can name a young object). */
    static void arm(int mode, long kb)
    {
        if (kb >= 64L && kb * 1024L <= SLOT_MAX)
        {
            slot = (kb * 1024L) & -4096L;
        }
        cur = new long[4];
        lim = new long[4];
        gapIdx = new int[4];
        gapN = new int[4];
        gapLo = new long[4 * GAPS];
        gapHi = new long[4 * GAPS];
        int c = 0;
        while (c < 4)
        {
            long b = BASE + (long) c * SLOT_MAX;
            filler(b, slot);
            gapLo[c * GAPS] = b;
            gapHi[c * GAPS] = b + slot;
            gapN[c] = 1;
            gapIdx[c] = 0;
            cur[c] = b;
            lim[c] = b + slot;
            c += 1;
        }
        WriteBarrier.clearAll();
        on = mode;
    }

    static boolean contains(long w)
    {
        return w >= BASE && w < END;
    }

    /** A block that holds nothing: a gap's header, so the nursery stays walkable by size. */
    private static void filler(long at, long size)
    {
        Address.fromLong(at).store(0L);
        Address.fromLong(at).plus(ObjectModel.STATUS_OFFSET).store(size);
    }

    // ---- allocation --------------------------------------------------------------------------------------------

    /**
     * {@code size} bytes in this core's nursery, header zeroed and status = size; zero when the nursery cannot
     * serve it (the caller allocates mature). IRQs masked (Heap.allocAs). An exhausted nursery asks for a minor
     * collection once.
     */
    static Address alloc(int size)
    {
        int core = (int) (Magic.readMPIDR() & 3L);
        long aligned = ((long) size + 7L) & -8L;
        if (aligned < (long) ObjectModel.HEADER_SIZE)
        {
            aligned = (long) ObjectModel.HEADER_SIZE;
        }
        int tries = 0;
        while (true)
        {
            long c = cur[core];
            long l = lim[core];
            long want = aligned;
            if (l - c - want == 8L)
            {
                want = want + 8L;                      // never leave an 8-byte hole: no header fits it
            }
            if (c + want <= l)
            {
                cur[core] = c + want;
                Address p = Address.fromLong(c);
                p.store(0L);
                p.plus(ObjectModel.STATUS_OFFSET).store(want);
                Address z = p.plus(ObjectModel.HEADER_SIZE);
                Address e = p.plus(want);
                while (z.LT(e))
                {
                    z.store(0L);
                    z = z.plus(8);
                }
                allocBytes = allocBytes + want;
                return p;
            }
            if (l > c)
            {
                filler(c, l - c);                      // the rest of this gap, so a walk can cross it
                cur[core] = l;
            }
            int g = gapIdx[core] + 1;
            if (g < gapN[core])
            {
                gapIdx[core] = g;
                cur[core] = gapLo[core * GAPS + g];
                lim[core] = gapHi[core * GAPS + g];
                continue;
            }
            if (tries > 0)
            {
                return Address.zero();
            }
            tries += 1;
            minorOnly = 1;
            Heap.gcPressure += 1;
            Magic.gc();                                // spills x19..x28: the callers' frames are walkable
            minorOnly = 0;
        }
    }

    /** Before a minor phase: the unused rest of each core's current gap gets its header. */
    private static void seal()
    {
        int c = 0;
        while (c < 4)
        {
            if (lim[c] > cur[c])
            {
                filler(cur[c], lim[c] - cur[c]);
            }
            c += 1;
        }
    }

    // ---- the minor phase ---------------------------------------------------------------------------------------

    /** VMGc.gcCollect, the world stopped: evacuate every nursery. */
    static void collect(long scanFrom, long stackTop)
    {
        long t0 = Magic.readCNTPCT_EL0();
        seal();
        workSp = WORK;
        pinN = 0;
        bitsBuilt = 0;
        long copied0 = copiedBytes;
        Heap.noCollect = 1;
        // 1. PIN: every word only a guess names, before anything moves.
        StackRoots.minor = 1;
        StackRoots.markStacks(scanFrom, stackTop);
        long t1 = Magic.readCNTPCT_EL0();
        tPin = tPin + (t1 - t0);
        // 2. FORWARD every exact slot.
        StackRoots.minor = 2;
        StackRoots.markStacks(scanFrom, stackTop);
        StackRoots.minor = 0;
        long t2 = Magic.readCNTPCT_EL0();
        tFwd = tFwd + (t2 - t1);
        statics();
        heapStatics();
        gcMaps();
        long t3 = Magic.readCNTPCT_EL0();
        tRoots = tRoots + (t3 - t2);
        int i = 0;
        while (i < pinN)
        {
            push(Address.fromLong(PINS + (long) i * 8L).loadLong());   // residents: scanned in place
            i += 1;
        }
        remset();
        long t4 = Magic.readCNTPCT_EL0();
        tRemset = tRemset + (t4 - t3);
        drain();
        Heap.noCollect = 0;
        long t5 = Magic.readCNTPCT_EL0();
        tDrain = tDrain + (t5 - t4);
        IdentityHash.afterMinor();
        rebuild();
        tAfter = tAfter + (Magic.readCNTPCT_EL0() - t5);
        minors = minors + 1L;
        survivedLast = copiedBytes - copied0;
        minorTicks = minorTicks + (Magic.readCNTPCT_EL0() - t0);
    }

    /** Pin pass: the stack range [lo, hi) was scanned conservatively. */
    static void pinRange(long lo, long hi)
    {
        long a = lo & -8L;
        while (a < hi)
        {
            pinWord(Address.fromLong(a).loadLong());
            a += 8L;
        }
    }

    /** Pin pass: {@code w} may be a reference -- to any byte of a nursery block. */
    static void pinWord(long w)
    {
        if (!contains(w))
        {
            return;
        }
        long b = blockOf(w);
        if (b == 0L)
        {
            return;
        }
        Address st = Address.fromLong(b).plus(ObjectModel.STATUS_OFFSET);
        long s = st.loadLong();
        if ((s & RESIDENT) != 0L)
        {
            return;
        }
        st.store(s | RESIDENT);
        pinnedTotal = pinnedTotal + 1L;
        if (pinN < (int) ((PINS_END - PINS) >> 3))
        {
            Address.fromLong(PINS + (long) pinN * 8L).store(b);
            pinN += 1;
        }
        else
        {
            halt(Magic.bytes("nursery: pin list full"));
        }
    }

    /** The nursery block holding {@code w}, or 0 (a filler, or no block). */
    private static long blockOf(long w)
    {
        int core = (int) ((w - BASE) / SLOT_MAX);
        if (w >= BASE + (long) core * SLOT_MAX + slot)
        {
            return 0L;                                 // past this core's nursery: its BITS are never built
        }
        if ((bitsBuilt & (1 << core)) == 0)
        {
            long tb = Magic.readCNTPCT_EL0();
            buildBits(core);
            tBits = tBits + (Magic.readCNTPCT_EL0() - tb);
            nBits = nBits + 1L;
        }
        long a = w & -8L;
        long floor = BASE + ((w - BASE) / SLOT_MAX) * SLOT_MAX;
        while (a >= floor)
        {
            long bi = (a - BASE) >> 3;
            long word = Address.fromLong(BITS + ((bi >> 6) << 3)).loadLong();
            if (((word >>> (int) (bi & 63L)) & 1L) != 0L)
            {
                long size = Address.fromLong(a).plus(ObjectModel.STATUS_OFFSET).loadLong() & -8L;
                if (w >= a + size)
                {
                    return 0L;
                }
                return a;                              // fillers too: pinning one only idles its gap a cycle
            }
            a -= 8L;
        }
        return 0L;
    }

    /** Walk core {@code c}'s nursery by size, recording its block starts (the pin pass's lookup), on first need. */
    private static void buildBits(int c)
    {
        bitsBuilt = bitsBuilt | (1 << c);
        long o = BASE + (long) c * SLOT_MAX;
        long top = o + slot;
        Address z = Address.fromLong(BITS + (((o - BASE) >> 9) << 3));
        Address zEnd = Address.fromLong(BITS + (((top - BASE) >> 9) << 3));
        while (z.LT(zEnd))
        {
            z.store(0L);
            z = z.plus(8);
        }
        while (o < top)
        {
            long size = Address.fromLong(o).plus(ObjectModel.STATUS_OFFSET).loadLong() & -8L;
            if (size < (long) ObjectModel.HEADER_SIZE || o + size > top)
            {
                corrupt(o, size);
                break;
            }
            long bi = (o - BASE) >> 3;
            Address bw = Address.fromLong(BITS + ((bi >> 6) << 3));
            bw.store(bw.loadLong() | (1L << (int) (bi & 63L)));
            o = o + size;
        }
    }

    /** Forward pass: the exact reference slot at {@code a}. */
    static void fwdSlot(long a)
    {
        long w = Address.fromLong(a).loadLong();
        if (!contains(w))
        {
            return;
        }
        long n = forward(w);
        if (n != w)
        {
            Address.fromLong(a).store(n);
        }
        if (contains(n))
        {
            stillYoung = true;
        }
    }

    /** The address young block {@code w} lives at after this collection: its copy, or itself if resident. */
    private static long forward(long w)
    {
        Address o = Address.fromLong(w);
        Address st = o.plus(ObjectModel.STATUS_OFFSET);
        long s = st.loadLong();
        if ((s & FWD) != 0L)
        {
            return o.loadLong();
        }
        if ((s & RESIDENT) != 0L)
        {
            return w;
        }
        long size = s & -8L;
        if (size < (long) ObjectModel.HEADER_SIZE || size > slot || (w & 7L) != 0L)
        {
            corrupt(w, size);
            return w;
        }
        Address n = Heap.allocInGc((int) size);
        if (n.isZero())
        {
            st.store(s | RESIDENT);                    // promotion failed: it stays, scanned in place
            promotionFailures = promotionFailures + 1L;
            push(w);
            return w;
        }
        n.store(o.loadLong());                         // the TIB
        long k = (long) ObjectModel.HEADER_SIZE;
        while (k < size)
        {
            n.plus(k).store(o.plus(k).loadLong());
            k += 8L;
        }
        o.store(n);                                    // the forwarding address, in the TIB word
        st.store(s | FWD);
        copiedBytes = copiedBytes + size;
        copiedObjects = copiedObjects + 1L;
        push(n.toLong());
        return n.toLong();
    }

    private static void push(long b)
    {
        if (workSp >= WORK_END)
        {
            halt(Magic.bytes("nursery: copy worklist full"));
        }
        Address.fromLong(workSp).store(b);
        workSp = workSp + 8L;
    }

    /** Scan each queued block's slots; a copy that still names a resident is remembered (logged). */
    private static void drain()
    {
        while (workSp > WORK)
        {
            workSp = workSp - 8L;
            long b = Address.fromLong(workSp).loadLong();
            stillYoung = false;
            scanSlots(b);
            if (stillYoung)
            {
                WriteBarrier.log(b);                   // mature (a copy) -> a resident: next cycle's remembered set
            }
        }
    }

    /** The image statics by their kind maps: the reference slots (not data, not code). */
    private static void statics()
    {
        long start = VM.staticsStart;
        long end = VM.staticsEnd;
        long map = VM.staticsMap;
        if (map == 0L || PreciseTrace.on == 0)
        {
            halt(Magic.bytes("nursery: the statics have no kind map"));
        }
        int slots = (int) ((end - start) >> 3);
        long longs = (long) ((slots + 63) >> 6);
        Address refs = Address.fromLong(map);
        Address datas = refs.plus(longs * 8L);
        int s = 0;
        while (s < slots)
        {
            long rw = refs.plus((long) (s >> 6) * 8L).loadLong();
            long dw = datas.plus((long) (s >> 6) * 8L).loadLong();
            if (rw == 0L && (s & 63) == 0)
            {
                s += 64;
                continue;
            }
            long bit = 1L << (s & 63);
            if ((rw & bit) != 0L && (dw & bit) == 0L)
            {
                fwdSlot(start + (long) s * 8L);
            }
            s += 1;
        }
    }

    /** Heap statics blocks (classes loaded on the metal): roots too -- {@code putstatic} has no barrier. */
    private static void heapStatics()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            RVMClass cb = ClassRegistry.clTab[i];
            if (cb != null && !cb.statics.isZero())
            {
                long p = cb.statics.toLong() - (long) ObjectModel.HEADER_SIZE;   // the block
                if (p >= Heap.BASE && p < VMGc.META_HIGH)
                {
                    scanSlots(p);
                }
            }
            i += 1;
        }
    }

    /** The JIT local table's map entries: each names a heap int[] by its first element. */
    private static void gcMaps()
    {
        if (VM.jitLocalTable.isZero())
        {
            return;
        }
        long i = 0L;
        while (i < VM.jitLocalCount)
        {
            Address e = VM.jitLocalTable.plus(i * 24L + 16L);
            long m = e.loadLong();
            if (m > 0L && contains(m - (long) ObjectModel.ARRAY_BASE_OFFSET))
            {
                long n = forward(m - (long) ObjectModel.ARRAY_BASE_OFFSET);
                e.store(n + (long) ObjectModel.ARRAY_BASE_OFFSET);
            }
            i += 1L;
        }
    }

    /**
     * The remembered set: every object the barrier logged since the last collection (image and heap, below
     * {@link Symbols#LOG_SPAN}). Its slots are forwarded; its bit is cleared unless one still names a resident.
     */
    private static void remset()
    {
        remN = 0L;
        remsetRange(0x80000L, Heap.IMAGE_CEILING);                         // the image's objects
        int c = 0;
        while (c < 4)
        {
            remsetRange(Heap.arenaBase(c).toLong(), Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong());
            c += 1;
        }
        remsetRange(Heap.LARGE_BASE, Address.fromLong(Heap.LARGE_PTR_CELL).loadLong());
        if (on == 2)
        {
            outsideRanges();
        }
        remembered = remN;
        if (remN > rememberedMax)
        {
            rememberedMax = remN;
        }
    }

    private static long remN;

    /**
     * The logged objects in {@code [lo, hi)}: S7-4b scans only where objects can be -- the image, each arena up to
     * its bump pointer, the large region up to its top -- not the whole 480 MiB span (7.5 MiB of bitmap, 53% of a
     * minor phase on virt). Eight bitmap words are tested at once: almost all of them are zero.
     */
    private static void remsetRange(long lo, long hi)
    {
        if (hi <= lo)
        {
            return;
        }
        long w = Symbols.LOG_BITMAP + ((lo >> 9) << 3);
        long end = Symbols.LOG_BITMAP + (((hi + 511L) >> 9) << 3);
        while (w + 64L <= end)
        {
            Address a = Address.fromLong(w);
            if ((a.loadLong() | a.plus(8).loadLong() | a.plus(16).loadLong() | a.plus(24).loadLong()
                 | a.plus(32).loadLong() | a.plus(40).loadLong() | a.plus(48).loadLong() | a.plus(56).loadLong()) != 0L)
            {
                int k = 0;
                while (k < 8)
                {
                    remsetWord(w + (long) k * 8L);
                    k += 1;
                }
            }
            w += 64L;
        }
        while (w < end)
        {
            remsetWord(w);
            w += 8L;
        }
    }

    /** One bitmap word at {@code w}: scan each logged object; keep the bits of those that still name a resident. */
    private static void remsetWord(long w)
    {
        Address wa = Address.fromLong(w);
        long bits = wa.loadLong();
        if (bits == 0L)
        {
            return;
        }
        long base = (w - Symbols.LOG_BITMAP) << 6;     // word k (byte 8k) covers [512k, 512k+512)
        long keep = 0L;
        while (bits != 0L)
        {
            int b = lowBit(bits);
            bits = bits & (bits - 1L);
            long o = base + ((long) b << 3);
            remN = remN + 1L;
            if (o >= Heap.BASE && (Address.fromLong(o).plus(ObjectModel.STATUS_OFFSET).loadLong()
                                   & Heap.FREE_FLAG) != 0L)
            {
                continue;                              // freed since (a full collection clears these)
            }
            stillYoung = false;
            scanSlots(o);
            if (stillYoung)
            {
                keep = keep | (1L << b);
            }
        }
        wa.store(keep);
    }

    /** VERIFY: a logged bit outside the ranges {@link #remset} scans would be a remembered object it never saw. */
    private static void outsideRanges()
    {
        long w = Symbols.LOG_BITMAP;
        long end = Symbols.LOG_BITMAP + (Symbols.LOG_SPAN >> 6);
        while (w < end)
        {
            if (Address.fromLong(w).loadLong() != 0L)
            {
                long a = (w - Symbols.LOG_BITMAP) << 6;
                if (!inScanned(a))
                {
                    Uart.write(Magic.bytes("nursery: a logged object outside the remembered-set ranges @"));
                    VM.printHex(a);
                    Uart.putc(0x0A);
                    halt(Magic.bytes("nursery: remembered-set ranges miss a logged object"));
                }
            }
            w += 8L;
        }
    }

    private static boolean inScanned(long a)
    {
        if (a + 512L > 0x80000L && a < Heap.IMAGE_CEILING)
        {
            return true;
        }
        int c = 0;
        while (c < 4)
        {
            if (a + 512L > Heap.arenaBase(c).toLong() && a < Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong())
            {
                return true;
            }
            c += 1;
        }
        return a + 512L > Heap.LARGE_BASE && a < Address.fromLong(Heap.LARGE_PTR_CELL).loadLong();
    }

    /** The index of the lowest set bit of {@code x} (nonzero). */
    private static int lowBit(long x)
    {
        int i = 0;
        long v = x;
        if ((v & 0xFFFF_FFFFL) == 0L)
        {
            v = v >>> 32;
            i += 32;
        }
        if ((v & 0xFFFFL) == 0L)
        {
            v = v >>> 16;
            i += 16;
        }
        if ((v & 0xFFL) == 0L)
        {
            v = v >>> 8;
            i += 8;
        }
        while ((v & 1L) == 0L)
        {
            v = v >>> 1;
            i += 1;
        }
        return i;
    }

    /** Forward the object-reference slots of the object or statics block at {@code o} (heap or image). */
    static void scanSlots(long o)
    {
        Address b = Address.fromLong(o);
        long tibw = b.loadLong();
        if (tibw == 0L || tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return;                                    // bytes, or a raw array: no references
        }
        if (PreciseTrace.isLayout(tibw))
        {
            if (PreciseTrace.layoutKind(tibw) == ObjectModel.LAYOUT_STATICS)
            {
                staticsBlock(b, tibw);
            }
            return;                                    // other layouts hold data and code references only
        }
        if (tibw < VMGc.META_LOW || tibw >= VMGc.META_HIGH || (tibw & 7L) != 0L)
        {
            unknown(b);
            return;
        }
        long type = Address.fromLong(tibw).plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
        if (type < VMGc.META_LOW || type >= VMGc.META_HIGH || (type & 7L) != 0L)
        {
            unknown(b);
            return;
        }
        Address t = Address.fromLong(type);
        long isz = t.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
        {
            boolean refs = t.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong() != 0L
                           || (isz & ObjectModel.ARRAY_REF_ELEMS) != 0L;
            if ((isz & 0xFFFFL) != (long) ObjectModel.WORD || !refs
                || (isz & (ObjectModel.ARRAY_DATA_ELEMS | ObjectModel.ARRAY_CODE_ELEMS)) != 0L)
            {
                return;
            }
            long len = b.plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong();
            long a = o + (long) ObjectModel.ARRAY_BASE_OFFSET;
            long e = a + len * 8L;
            while (a < e)
            {
                fwdSlot(a);
                a += 8L;
            }
            return;
        }
        long r0 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET).loadLong();
        if ((r0 & 1L) == 0L)
        {
            unknown(b);
            return;
        }
        long r1 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET + 8).loadLong();
        long d0 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET).loadLong();
        long d1 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET + 8).loadLong();
        long size = b.plus(ObjectModel.STATUS_OFFSET).loadLong() & -8L;
        if (size == 0L)
        {
            size = isz & 0xFFFF_FFFFL;                 // an image object: no size in its status word
        }
        long slots = (size - (long) ObjectModel.HEADER_SIZE) >> 3;
        long i = 0L;
        while (i < slots && i <= (long) ObjectModel.TYPE_REFMAP_MAX_SLOT)
        {
            int sh = (int) (i < 63L ? i + 1L : i - 63L);
            long rb = i < 63L ? r0 >>> sh : r1 >>> sh;
            long db = i < 63L ? d0 >>> sh : d1 >>> sh;
            if ((rb & 1L) != 0L && (db & 1L) == 0L)
            {
                fwdSlot(o + (long) ObjectModel.HEADER_SIZE + i * 8L);
            }
            i += 1L;
        }
    }

    /** A statics block's reference slots (kind bitmaps after the slots, as Heap.allocStatics lays them out). */
    private static void staticsBlock(Address b, long layout)
    {
        Address p = b.plus(ObjectModel.HEADER_SIZE);
        int n = (int) (layout >>> 8);
        int words = ObjectModel.staticsMapWords(n);
        Address refs = p.plus((n + 1) * 8L);
        Address datas = refs.plus(words * 8L);
        int s = 0;
        while (s < n)
        {
            long bit = 1L << (s & 63);
            boolean r = (refs.plus((s >> 6) * 8L).loadLong() & bit) != 0L;
            boolean d = (datas.plus((s >> 6) * 8L).loadLong() & bit) != 0L;
            if (r && !d)
            {
                fwdSlot(p.toLong() + (long) s * 8L);
            }
            s += 1;
        }
    }

    /** A logged object with no precise layout: none has been seen (S6c's fallbacks are 0); say so if one is. */
    private static void unknown(Address b)
    {
        unknownBlocks = unknownBlocks + 1L;
        if (unknownBlocks <= 4L)
        {
            Uart.write(Magic.bytes("nursery: a logged block with no precise layout @"));
            VM.printHex(b.toLong());
            Uart.write(Magic.bytes(" header "));
            VM.printHex(b.loadLong());
            Uart.putc((byte) 0x0A);
        }
    }

    /** After the copy: each core's free runs are what is not resident; residents become ordinary young blocks. */
    private static void rebuild()
    {
        int c = 0;
        while (c < 4)
        {
            long o = BASE + (long) c * SLOT_MAX;
            long top = o + slot;
            int n = 0;
            long run = 0L;
            while (o < top)
            {
                Address st = Address.fromLong(o).plus(ObjectModel.STATUS_OFFSET);
                long s = st.loadLong();
                long size = s & -8L;
                if (size < (long) ObjectModel.HEADER_SIZE || o + size > top)
                {
                    corrupt(o, size);
                    break;
                }
                if ((s & RESIDENT) != 0L)
                {
                    st.store(size);
                    if (run != 0L)
                    {
                        n = gap(c, n, run, o);
                        run = 0L;
                    }
                }
                else if (run == 0L)
                {
                    run = o;
                }
                o = o + size;
            }
            if (run != 0L)
            {
                n = gap(c, n, run, top);
            }
            gapN[c] = n;
            gapIdx[c] = 0;
            if (n > 0)
            {
                cur[c] = gapLo[c * GAPS];
                lim[c] = gapHi[c * GAPS];
            }
            else
            {
                cur[c] = top;
                lim[c] = top;
            }
            c += 1;
        }
    }

    /** Record the free run [lo, hi) of core {@code c}'s nursery as gap {@code n}; returns the next index. */
    private static int gap(int c, int n, long lo, long hi)
    {
        filler(lo, hi - lo);
        if (on == 2)
        {
            Address p = Address.fromLong(lo + (long) ObjectModel.HEADER_SIZE);
            Address e = Address.fromLong(hi);
            while (p.LT(e))
            {
                p.store(POISON);
                p = p.plus(8);
            }
        }
        if (n >= GAPS)
        {
            return n;                                  // too fragmented: this run waits for the next collection
        }
        gapLo[c * GAPS + n] = lo;
        gapHi[c * GAPS + n] = hi;
        return n + 1;
    }

    // ---- addresses nothing rewrites -----------------------------------------------------------------------------

    /**
     * An OLD copy of the young object {@code o}, header and all, or {@code o} itself when it is not young. For an
     * object whose address is about to be BAKED where no collection can rewrite it -- a proxy thunk's Method
     * literal. Only for objects whose identity no caller relies on (a reflective Method is a fresh copy anyway).
     * The copy may name young objects, so it is logged for the remembered set.
     */
    static long tenure(long o)
    {
        if (!contains(o))
        {
            return o;
        }
        long size = Address.fromLong(o).plus(ObjectModel.STATUS_OFFSET).loadLong() & -8L;
        long c = Heap.allocOld((int) size).toLong();
        Address.fromLong(c).store(Address.fromLong(o).loadLong());     // the TIB; the status word is the copy's own
        long i = 16L;
        while (i < size)
        {
            Address.fromLong(c).plus(i).store(Address.fromLong(o).plus(i).loadLong());
            i += 8L;
        }
        WriteBarrier.log(c);
        return c;
    }

    // ---- the full collection after a minor phase ---------------------------------------------------------------

    /** Full trace: the residents are roots (their referents marked; their nursery referents re-logged). */
    static void markResidents()
    {
        int i = 0;
        while (i < pinN)
        {
            long b = Address.fromLong(PINS + (long) i * 8L).loadLong();
            long size = Address.fromLong(b).plus(ObjectModel.STATUS_OFFSET).loadLong() & -8L;
            VMGc.scanSrc = b;
            PreciseTrace.scanBlock(Address.fromLong(b), size);
            VMGc.scanSrc = 0L;
            i += 1;
        }
    }

    /**
     * Full trace: an exact word names the nursery. After the minor phase only residents live there, so the block
     * holding it ({@link VMGc#scanSrc}, a heap block) is remembered; with {@code gcnursery=2}, a word naming anything
     * but a block that is still there is a STALE reference -- the evacuated copy is elsewhere and this word was not
     * updated.
     */
    static void fullEdge(long w)
    {
        long src = VMGc.scanSrc;
        if (src > 0L)
        {
            WriteBarrier.log(src);
        }
        if (on != 2)
        {
            return;
        }
        long s = Address.fromLong(w).plus(ObjectModel.STATUS_OFFSET).loadLong();
        long tibw = Address.fromLong(w).loadLong();
        if ((s & FWD) == 0L && tibw != POISON && s != POISON)
        {
            return;
        }
        staleRefs = staleRefs + 1L;
        if (stalePrinted < 12)
        {
            stalePrinted += 1;
            Uart.write(Magic.bytes("NURSERY STALE: a reference into evacuated space w="));
            VM.printHex(w);
            if (src > 0L)
            {
                Uart.write(Magic.bytes(" from block "));
                VM.printHex(src);
                Uart.write(Magic.bytes(" type "));
                HeapShadow.printTypeOf(Address.fromLong(src).loadLong());
            }
            else
            {
                Uart.write(Magic.bytes(" from a root"));
            }
            Uart.putc((byte) 0x0A);
        }
    }

    private static void corrupt(long o, long size)
    {
        Uart.write(Magic.bytes("nursery: a block with a bad size @"));
        VM.printHex(o);
        Uart.write(Magic.bytes(" size "));
        VM.printHex(size);
        Uart.putc((byte) 0x0A);
        halt(Magic.bytes("nursery: corrupt"));
    }

    private static void halt(byte[] why)
    {
        Uart.write(why);
        Uart.putc((byte) 0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }
}
