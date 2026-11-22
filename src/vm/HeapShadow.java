/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */
package vm;

import static vm.VM.*;

import board.Uart;
import magic.Address;
import magic.Magic;
import magic.Uninterruptible;
import objectmodel.ObjectModel;

/**
 * The heap shadow check (frames/GC arc S6a, {@code gcheap=2} in {@code /etc/init}): what a PRECISE trace of the heap
 * would lose, measured against the conservative one the collector still runs -- the heap's counterpart of S2's
 * stack shadow ({@link GcMapShadow}).
 *
 * <p>The trace runs in two phases. The first follows only words whose kind is EXACTLY known: a scalar's slots its
 * Type's kind bitmaps call a reference or a data reference ({@code ObjectModel.TYPE_REFKIND_OFFSET}), and the
 * elements of an array whose Type names an element Type. Then every block it marked is rescanned the way the
 * collector scans today (every word that may be a pointer, at {@code w} and at {@code w-16}), and each block only
 * that rescan reaches is a MISS: a block a precise collector would have freed. A MISS found while rescanning a
 * block the first phase marked is PRIMARY and reported against its source -- a class's field slot, or the
 * allocation site of an array or {@code allocData} struct, and the word's offset -- once per boot. Blocks reached
 * only from other missed blocks are SECONDARY and only counted. Those reports are S6b's worklist, as S2's were S4's.
 *
 * <p>The collection itself is unchanged: the rescan closes the same conservative set the collector always marked,
 * so the check is an instrument, never a behaviour.
 */
final class HeapShadow implements Uninterruptible
{
    private HeapShadow()
    {
    }

    /** 1 = run the check at every collection ({@code gcheap=2}). */
    static int on;
    /** 1 while the first (precise) phase traces: {@link VMGc#scanBlock} hands each block here. */
    static int precise;
    /** 1 while the rescan runs: {@link VMGc#tryMark} reports every new mark here. */
    static int rescan;

    // Source kinds of a MISS.
    private static final int S_MAPPED_RAW = 0;     // a slot the reference map marks but the kinds call raw
    private static final int S_UNMAPPED = 1;       // a scalar whose Type has no map or no kinds
    private static final int S_WORD_ARRAY = 2;     // a typed word array with no element Type (long[], AddressArray...)
    private static final int S_RAW_ARRAY = 3;      // a raw array (header word = element size)
    private static final int S_DATA = 4;           // an allocData struct
    private static final int S_OTHER = 5;          // a header word that is none of the above
    private static final int S_REF_DATA = 6;       // an exact reference slot or element, read as a data reference
    private static final int S_REF_PAYLOAD = 7;    // ... whose w-16 block is LARGER than a header: w is a payload
    private static final int S_STATIC = 8;         // a root: an image statics slot its kind map does not follow (S6d)
    private static final int S_CODE_ROOT = 9;      // a root: a code-root entry, at the probe its kind does not make
    private static final int S_N = 10;

    private static int srcKind;
    private static long srcBlock;
    private static long srcOff;
    private static int srcViaData;
    private static int secondary;                  // 1 while draining blocks the rescan itself marked

    static long checks;
    static long codeMisses;                        // this collection's primary misses whose target is compiled code
    static long codeTotal;                         // ... over the boot
    static long primary;
    static long secondaries;
    private static long[] byKind;                  // this collection's primary misses by source kind
    private static long[] totalByKind;             // ... over the boot

    /** 1 once {@link #arm} ran: every array and allocData block allocated since carries its allocation site in its
     *  last word ({@link Heap#allocData}, {@link Heap#allocArray} ask for one word more). */
    static int sites;

    // Reported (source, offset) keys, so each prints once per boot.
    private static final int SEEN_SLOTS = 1 << 15;
    private static long[] seen;
    private static int[] printed;                  // per source kind, so one noisy kind cannot hide the others
    private static final int PRINT_MAX = 120;

    /** Turn the check on and allocate its tables (at boot, from {@code /etc/init}: a collection must not allocate). */
    static void arm()
    {
        byKind = new long[S_N];
        totalByKind = new long[S_N];
        seen = new long[SEEN_SLOTS];
        printed = new int[S_N + 1];                    // + the code misses
        sites = 1;
        on = 1;
    }

    /** The allocation site stored in {@code block}'s last word, or 0 when that word is not a code address (a block
     *  allocated before {@link #arm}, whose last word is payload). */
    private static long siteOf(long block)
    {
        Address o = Address.fromLong(block);
        long pc = o.plus((o.plus(8).loadLong() & -8L) - 8L).loadLong();
        if (pc == 0L || (pc & 3L) != 0L || VM.frameSizeAt(Address.fromLong(pc - 4L)) == 0L)
        {
            return 0L;
        }
        return pc;
    }

    private static int hash(long v, int slots)
    {
        return (int) ((v ^ (v >>> 17) ^ (v >>> 31)) >>> 3) & (slots - 1);
    }

    /** Primary misses from source kind {@code k} over the boot (0 when the check is off). For {@code /gc}. */
    static long total(int k)
    {
        return totalByKind == null ? 0L : totalByKind[k];
    }

    // ---- phase 1: the precise trace ---------------------------------------------------------------------------

    /** Scan block {@code o} the way the precise trace does ({@link PreciseTrace#scanBlock}). */
    static void scanPrecise(Address o, long size)
    {
        PreciseTrace.scanBlock(o, size);
    }

    // ---- phase 2: the conservative rescan ---------------------------------------------------------------------

    /** Begin a collection's check: the precise phase traces from here. */
    static void begin()
    {
        precise = 1;
        int i = 0;
        while (i < S_N)
        {
            byKind[i] = 0L;
            i += 1;
        }
        codeMisses = 0L;
    }

    /**
     * End of the precise phase: rescan every block it marked conservatively, attributing each block only the rescan
     * reaches. Leaves the marks a conservative trace would have made (the caller drains and closes the set).
     */
    static void rescanAll()
    {
        precise = 0;
        rescan = 1;
        secondary = 0;
        checks = checks + 1L;
        rescanRoots();                                 // S6d: the roots a precise collector reads by kind
        rescanIn(Address.fromLong(Heap.BASE), Address.fromLong(Heap.PTR_CELL).loadAddress());
        rescanIn(Address.fromLong(Heap.LARGE_BASE), Address.fromLong(Heap.LARGE_PTR_CELL).loadAddress());
        int c = 1;
        while (c < 4)
        {
            rescanIn(Heap.arenaBase(c), Address.fromLong(Heap.PTR_CELL + c * 8L).loadAddress());
            c += 1;
        }
        secondary = 1;                                 // what the caller drains next was marked by the rescan
    }

    /** The collection's trace is closed: stop attributing, print the summary. */
    static void end()
    {
        rescan = 0;
        secondary = 0;
        summary();
    }

    /** The image statics region and the code-root table, word by word as the conservative root scan reads them. */
    private static void rescanRoots()
    {
        srcBlock = VM.staticsStart;
        words(S_STATIC, Address.fromLong(VM.staticsStart), Address.fromLong(VM.staticsEnd), Address.fromLong(VM.staticsStart));
        Address p = Address.fromLong(Loader.CODE_ROOTS);
        Address end = p.plus(Loader.codeRootN * Loader.CODE_ROOT_ENTRY);
        while (p.LT(end))
        {
            long e = p.loadLong();
            srcBlock = p.plus(8L).loadLong();          // the owning code buffer, for the report
            srcKind = S_CODE_ROOT;
            srcOff = e & 1L;                           // the entry's kind: 1 = data reference
            srcViaData = 0;
            VMGc.markRescan(e & -8L);
            srcViaData = 1;
            VMGc.markRescan((e & -8L) - 16L);
            p = p.plus(Loader.CODE_ROOT_ENTRY);
        }
    }

    private static void rescanIn(Address from, Address to)
    {
        Address o = from;
        while (o.LT(to))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(to) || o.plus(size).LE(o))
            {
                return;
            }
            if ((st & 3L) == 1L)                      // marked by the precise phase (bit 1 = by the rescan)
            {
                rescanBlock(o, size);
            }
            o = o.plus(size);
        }
    }

    /** Conservatively rescan one precisely-marked block, word by word, as {@link VMGc#scanBlock} would. */
    private static void rescanBlock(Address o, long size)
    {
        srcBlock = o.toLong();
        long tibw = o.plus(ObjectModel.TIB_OFFSET).loadLong();
        if (tibw != 0L && tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            if (tibw >= 8L)
            {
                words(S_RAW_ARRAY, o.plus(ObjectModel.ARRAY_BASE_OFFSET), PreciseTrace.arrayEnd(o, size), o);
            }
            return;
        }
        if (PreciseTrace.isLayout(tibw))
        {
            // A word its layout follows exactly is reported like an exact slot of an object (ref-as-data: only its
            // w-16 probe can miss); a word it calls raw is a data MISS -- a pointer the layout does not know about.
            Address a = o.plus(ObjectModel.HEADER_SIZE);
            while (a.LT(o.plus(size)))
            {
                word(PreciseTrace.layoutExact(o, size, tibw, a.diff(o).toLong() - (long) ObjectModel.HEADER_SIZE) ? S_REF_DATA : S_DATA,
                     a, o);
                a = a.plus(8);
            }
            return;
        }
        if (tibw == 0L)
        {
            words(S_DATA, o.plus(ObjectModel.HEADER_SIZE), o.plus(size), o);
            return;
        }
        if (tibw < VMGc.META_LOW || tibw >= VMGc.META_HIGH || (tibw & 7L) != 0L)
        {
            words(S_OTHER, o.plus(ObjectModel.HEADER_SIZE), o.plus(size), o);
            return;
        }
        long type = Address.fromLong(tibw).plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
        if (type < VMGc.META_LOW || type >= VMGc.META_HIGH || (type & 7L) != 0L)
        {
            words(S_OTHER, o.plus(ObjectModel.HEADER_SIZE), o.plus(size), o);
            return;
        }
        Address t = Address.fromLong(type);
        long isz = t.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
        if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
        {
            if ((isz & 0xFFFFL) >= (long) ObjectModel.WORD)
            {
                boolean refs = t.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong() != 0L
                               || (isz & ObjectModel.ARRAY_REF_ELEMS) != 0L;
                words(refs ? S_REF_DATA : S_WORD_ARRAY, o.plus(ObjectModel.ARRAY_BASE_OFFSET), PreciseTrace.arrayEnd(o, size), o);
            }
            return;
        }
        long w0 = t.plus(ObjectModel.TYPE_REFMAP_OFFSET).loadLong();
        if ((w0 & 1L) == 0L)
        {
            words(S_UNMAPPED, o.plus(ObjectModel.HEADER_SIZE), o.plus(size), o);
            return;
        }
        long w1 = t.plus(ObjectModel.TYPE_REFMAP_OFFSET + 8).loadLong();
        long r0 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET).loadLong();
        long r1 = t.plus(ObjectModel.TYPE_REFKIND_OFFSET + 8).loadLong();
        long d0 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET).loadLong();
        long d1 = t.plus(ObjectModel.TYPE_DATAKIND_OFFSET + 8).loadLong();
        boolean kinds = (r0 & 1L) != 0L;
        long slots = (size - (long) ObjectModel.HEADER_SIZE) >> 3;
        long i = 0;
        while (i < slots && i <= (long) ObjectModel.TYPE_REFMAP_MAX_SLOT)
        {
            int sh = (int) (i < 63L ? i + 1L : i - 63L);
            if ((((i < 63L ? w0 : w1) >>> sh) & 1L) != 0L)
            {
                boolean ref = kinds && (((i < 63L ? r0 : r1) >>> sh) & 1L) != 0L;
                boolean data = kinds && (((i < 63L ? d0 : d1) >>> sh) & 1L) != 0L;
                Address a = o.plus(ObjectModel.HEADER_SIZE).plus(i * 8L);
                int k = !kinds ? S_UNMAPPED : (ref || data) ? S_REF_DATA : S_MAPPED_RAW;
                word(k, a, o);
            }
            i += 1;
        }
    }

    private static void words(int kind, Address lo, Address hi, Address o)
    {
        while (lo.LT(hi))
        {
            word(kind, lo, o);
            lo = lo.plus(8);
        }
    }

    /** One word, probed at {@code w} and {@code w-16} like the conservative scan. */
    private static void word(int kind, Address a, Address o)
    {
        long w = a.loadLong();
        if (w == 0L)
        {
            return;
        }
        srcKind = kind;
        srcOff = a.diff(o).toLong();
        srcViaData = 0;
        VMGc.markRescan(w);
        srcViaData = 1;
        VMGc.markRescan(w - 16L);
    }

    /**
     * The rescan hit code-arena word {@code w} no earlier mark had recorded: compiled code a precise trace would let
     * the code sweep free. Only a word probed AS ITSELF counts -- the {@code w-16} probe of an exact data reference
     * can land in the code before it, which says nothing.
     */
    static void missedCode(long w)
    {
        if (srcViaData != 0)
        {
            return;
        }
        if (secondary != 0)
        {
            return;                                    // reached through a block only the rescan kept: counted there
        }
        if (srcKind == S_STATIC)
        {
            // A PINNED block is never swept, so no word needs to reach it -- the trampolines a raw static names (S6d).
            // Asked only for the ~1,800 statics slots: the block lookup can fall back to a linear scan of every code
            // block, and asked for each code-range NUMBER in the heap's word arrays it stalled a collection for good.
            Address st = Heap.codeBlockStartAt(Address.fromLong(w));
            if (!st.isZero() && Heap.codePinnedIn(st, Heap.codeBlockEndAt(Address.fromLong(w)).diff(st).toLong()) != 0)
            {
                return;
            }
        }
        codeMisses = codeMisses + 1L;                  // apart from the heap's primary count: a separate worklist
        codeTotal = codeTotal + 1L;
        if (srcKind == S_STATIC || srcKind == S_CODE_ROOT)
        {
            reportRoot(srcKind, w, true);              // a root word, not a block: name its slot or owner
            return;
        }
        report(srcKind, w, true);
    }

    /** {@link VMGc#tryMark} newly marked {@code block} during the rescan: a MISS of the precise phase. */
    static void missed(long block)
    {
        if (secondary != 0)
        {
            secondaries = secondaries + 1L;
            return;
        }
        primary = primary + 1L;
        int k = srcKind;
        if (k == S_REF_DATA && srcViaData == 0)
        {
            k = S_MAPPED_RAW;                          // cannot happen: an exact word the precise phase skipped
        }
        if ((k == S_STATIC || k == S_CODE_ROOT) && srcViaData != 0
            && (Address.fromLong(block).plus(8).loadLong() & -8L) == (long) ObjectModel.HEADER_SIZE)
        {
            // A root's w-16 probe on a 16-byte neighbour: the false positive, COUNTED with the others but not reported
            // -- report() would read the root's source as a heap block (siteOf faulted on the statics region inside
            // the collection, and the world never restarted).
            byKind[S_REF_DATA] = byKind[S_REF_DATA] + 1L;
            totalByKind[S_REF_DATA] = totalByKind[S_REF_DATA] + 1L;
            return;
        }
        if (k == S_STATIC || k == S_CODE_ROOT)
        {
            byKind[k] = byKind[k] + 1L;
            totalByKind[k] = totalByKind[k] + 1L;
            reportRoot(k, block, false);
            return;
        }
        else if (k == S_REF_DATA && (Address.fromLong(block).plus(8).loadLong() & -8L) > (long) ObjectModel.HEADER_SIZE)
        {
            // The w-16 probe of an exact reference usually lands on a 16-byte neighbour (w is the next block's base:
            // a conservative false positive). A LARGER block means w is inside it, one header past its base -- an
            // allocData payload held in a reference slot, which a precise trace would drop (frames/GC arc S6c).
            k = S_REF_PAYLOAD;
        }
        byKind[k] = byKind[k] + 1L;
        totalByKind[k] = totalByKind[k] + 1L;
        report(k, block, false);
    }

    // ---- reporting ----------------------------------------------------------------------------------------------

    /** A root MISS: a statics slot (offset into the region: out/statics-map.txt names it) or a code root (its owner). */
    private static void reportRoot(int k, long block, boolean code)
    {
        long key = (k == S_STATIC ? srcOff : srcBlock) * 64L + (long) k + (long) srcViaData * 16L + (code ? 32L : 0L);
        if (printed[k] >= PRINT_MAX || !firstSeen(key))
        {
            return;
        }
        printed[k] = printed[k] + 1;
        Uart.write(Magic.bytes(code ? "heap MISS code from " : "heap MISS "));
        Uart.write(Magic.bytes(k == S_STATIC ? "static" : "code-root"));
        if (srcViaData != 0)
        {
            Uart.write(Magic.bytes(" (w-16)"));
        }
        if (k == S_STATIC)
        {
            Uart.write(Magic.bytes(" off="));
            printDec((int) srcOff);
        }
        else
        {
            Uart.write(Magic.bytes(srcOff != 0L ? " data owner " : " object owner "));
            StackTrace.printFrameAt(Address.fromLong(srcBlock));
        }
        Uart.write(Magic.bytes(" -> "));
        if (code)
        {
            StackTrace.printFrameAt(Address.fromLong(block));
            Uart.putc((byte) 0x0A);
            return;
        }
        printTypeOf(Address.fromLong(block).loadLong());
        Uart.write(Magic.bytes(" size "));
        printDec((int) (Address.fromLong(block).plus(8).loadLong() & -8L));
        Uart.putc((byte) 0x0A);
    }

    /** Print a primary MISS the first time its (source, offset) is seen; {@code code}: the target is a code address. */
    private static void report(int k, long block, boolean code)
    {
        Address src = Address.fromLong(srcBlock);
        long tibw = src.plus(ObjectModel.TIB_OFFSET).loadLong();
        boolean scalar = k == S_MAPPED_RAW || k == S_UNMAPPED || k == S_OTHER || k == S_REF_PAYLOAD;
        long who = scalar ? tibw : siteOf(srcBlock);
        long off = (k == S_WORD_ARRAY || k == S_RAW_ARRAY || k == S_REF_DATA && !scalar) ? 0L : srcOff;
        long key = (who * 31L + off) * 8L + (long) k + (long) srcViaData * 4096L + (code ? 8192L : 0L);
        int pk = code ? S_N : k;
        if (printed[pk] >= PRINT_MAX || !firstSeen(key))
        {
            return;
        }
        printed[pk] = printed[pk] + 1;
        Uart.write(Magic.bytes(code ? "heap MISS code from " : "heap MISS "));
        Uart.write(kindName(k));
        if (srcViaData != 0)
        {
            Uart.write(Magic.bytes(" (w-16)"));
        }
        Uart.write(Magic.bytes(" off="));
        printDec((int) srcOff);
        Uart.write(Magic.bytes(" src="));
        if (k == S_DATA || k == S_RAW_ARRAY || k == S_WORD_ARRAY || k == S_REF_DATA)
        {
            Uart.write(Magic.bytes("size "));
            printDec((int) (src.plus(8).loadLong() & -8L));
            Uart.write(Magic.bytes(" allocated at "));
            if (who == 0L)
            {
                Uart.write(Magic.bytes("<unknown>"));
            }
            else
            {
                StackTrace.printFrameAt(Address.fromLong(who - 4L));
            }
        }
        else
        {
            printTypeOf(tibw);
        }
        Uart.write(Magic.bytes(" -> "));
        if (code)
        {
            StackTrace.printFrameAt(Address.fromLong(block));
            Uart.putc((byte) 0x0A);
            return;
        }
        printTypeOf(Address.fromLong(block).loadLong());
        Uart.write(Magic.bytes(" size "));
        printDec((int) (Address.fromLong(block).plus(8).loadLong() & -8L));
        Uart.putc((byte) 0x0A);
    }

    private static boolean firstSeen(long key)
    {
        int h = hash(key, SEEN_SLOTS);
        int probe = 0;
        while (probe < SEEN_SLOTS)
        {
            long s = seen[h];
            if (s == key)
            {
                return false;
            }
            if (s == 0L)
            {
                seen[h] = key;
                return true;
            }
            h = (h + 1) & (SEEN_SLOTS - 1);
            probe += 1;
        }
        return false;
    }

    /** The class of an object whose header word is {@code tibw}, or what the header word says instead. */
    private static void printTypeOf(long tibw)
    {
        if (tibw == 0L)
        {
            Uart.write(Magic.bytes("<data>"));
            return;
        }
        if (PreciseTrace.isLayout(tibw))
        {
            Uart.write(Magic.bytes("<data layout "));
            printDec(PreciseTrace.layoutKind(tibw));
            Uart.putc((byte) 0x3E);
            return;
        }
        if (tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            Uart.write(Magic.bytes("<raw array "));
            printDec((int) tibw);
            Uart.putc((byte) 0x3E);
            return;
        }
        if (tibw < VMGc.META_LOW || tibw >= VMGc.META_HIGH || (tibw & 7L) != 0L)
        {
            Uart.write(Magic.bytes("<header "));
            printHex(tibw);
            Uart.putc((byte) 0x3E);
            return;
        }
        long type = Address.fromLong(tibw).plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
        Address t = Address.fromLong(type);
        long isz = type >= VMGc.META_LOW && type < VMGc.META_HIGH ? t.loadLong() : 0L;
        if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
        {
            long et = t.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong();
            Uart.write(Magic.bytes("array("));
            printDec((int) (isz & 0xFFFFL));
            Uart.write(Magic.bytes(") of "));
            if (et == 0L)
            {
                Uart.write(Magic.bytes("<primitive>"));
            }
            else
            {
                Loader.printTypeName(Address.fromLong(et));
            }
            return;
        }
        Loader.printTypeName(t);
    }

    private static byte[] kindName(int k)
    {
        if (k == S_MAPPED_RAW)
        {
            return Magic.bytes("raw-slot");
        }
        if (k == S_UNMAPPED)
        {
            return Magic.bytes("unmapped");
        }
        if (k == S_WORD_ARRAY)
        {
            return Magic.bytes("word-array");
        }
        if (k == S_RAW_ARRAY)
        {
            return Magic.bytes("raw-array");
        }
        if (k == S_DATA)
        {
            return Magic.bytes("data");
        }
        if (k == S_REF_DATA)
        {
            return Magic.bytes("ref-as-data");
        }
        if (k == S_REF_PAYLOAD)
        {
            return Magic.bytes("ref-holds-payload");
        }
        return Magic.bytes("other");
    }

    private static void summary()
    {
        Uart.write(Magic.bytes("gcheap shadow #"));
        printDec((int) checks);
        Uart.write(Magic.bytes(" misses: raw-slot="));
        printDec((int) byKind[S_MAPPED_RAW]);
        Uart.write(Magic.bytes(" unmapped="));
        printDec((int) byKind[S_UNMAPPED]);
        Uart.write(Magic.bytes(" word-array="));
        printDec((int) byKind[S_WORD_ARRAY]);
        Uart.write(Magic.bytes(" raw-array="));
        printDec((int) byKind[S_RAW_ARRAY]);
        Uart.write(Magic.bytes(" data="));
        printDec((int) byKind[S_DATA]);
        Uart.write(Magic.bytes(" ref-as-data="));
        printDec((int) byKind[S_REF_DATA]);
        Uart.write(Magic.bytes(" other="));
        printDec((int) byKind[S_OTHER]);
        Uart.write(Magic.bytes(" ref-holds-payload="));
        printDec((int) byKind[S_REF_PAYLOAD]);
        Uart.write(Magic.bytes(" static="));
        printDec((int) byKind[S_STATIC]);
        Uart.write(Magic.bytes(" code-root="));
        printDec((int) byKind[S_CODE_ROOT]);
        Uart.write(Magic.bytes(" code="));
        printDec((int) codeMisses);
        Uart.write(Magic.bytes(" | total primary="));
        printDec((int) primary);
        Uart.write(Magic.bytes(" secondary="));
        printDec((int) secondaries);
        Uart.putc((byte) 0x0A);
    }
}
