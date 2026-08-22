package vm;

import magic.Magic;
import objectmodel.ObjectModel;

/**
 * The heap — a bump allocator with a conservative mark-sweep collector
 * ({@link VM#gcCollect}). Every object records its own allocation size in the
 * status word (low bit reserved as the GC mark), so the heap is walkable and the
 * collector can size and trace objects without per-type maps. Freed objects go on
 * a free list that {@link #alloc} reuses (first fit) before bumping.
 *
 * <p>The MMU is off (flat RAM); objects stay 8-byte aligned so unaligned 8-byte
 * accesses don't fault.
 */
public final class Heap
{
    private Heap() {}

    // PER-CORE ARENAS. Each core bump-allocates from its OWN region + free-list, so cores 1-3 can allocate
    // concurrently with core 0 (no shared-bump-pointer race), and core 0's between-batch demand-load heap
    // reclaim (Loader) only rewinds ITS arena — never under a secondary that's allocating. Core 0's arena is
    // the original heap (BASE, reclaimed); the secondaries only ever hold short-lived SMP-demo data, so their
    // 64 MiB arenas up high are plenty and are never GC'd. The MMU identity-maps the low 4 GiB (Pi 4 = 4 GiB).

    /** Base of the per-core bump-pointer cells: core c's cell is {@code PTR_CELL + c*8}. Core 0's stays at
     *  {@code PTR_CELL} so {@link VM#gcCollect} still reads it directly. */
    public static final long PTR_CELL  = 0x03FF_0000L;
    /** Base of the per-core free-list heads: core c's head is {@code FREE_CELL + c*8}. */
    public static final long FREE_CELL = 0x03FF_0100L;
    /** Start of CORE 0's allocation region (64 MiB) — above the ~29 MiB all-java.base image (from 0x80000)
     *  and the 48-56 MiB scratch band; grows up. {@link VM#gcCollect} walks BASE..PTR_CELL. */
    public static final long BASE      = 0x0400_0000L;

    // ----- JIT code arena (#43) -------------------------------------------------------------------------
    // Demand-loaded methods are compiled into their OWN low arena, NOT interleaved with the data heap. Data
    // (TIBs, 4KiB imaps, interned strings, per-batch scratch) outweighs code ~400x and marches the data heap
    // pointer to ~200MiB; if code buffers rode along there, a JIT'd bl to the image's VM-helper island (~0.5MiB)
    // would exceed the A64 +-128MiB reach (FAIL_BL_RANGE). Placed at 32-48MiB (above the ~28MiB image, below
    // the 48MiB SEC_STUB scratch), every code buffer stays within ~48MiB of the helpers and of each other. The
    // whole boot's code is only a few MiB, so this simple no-reclaim bump arena has ample room.
    public static final long CODE_BASE     = 0x0200_0000L;   // 32 MiB
    public static final long CODE_LIMIT    = 0x0300_0000L;   // 48 MiB (= VM.SEC_STUB) — overflow guard
    public static final long CODE_PTR_CELL = 0x03FF_0200L;   // code-arena bump pointer (near PTR_CELL/FREE_CELL)

    /** Fixed scratch base for the JIT unwind tables (frame/local/handler). They must live OUTSIDE the managed
     *  heap [BASE, PTR): they are held only by VM static pointers for the whole run, but the mark-sweep GC
     *  reclaims dead blocks onto the free list and the demand-loader rewinds the bump pointer per batch -- either
     *  way a table allocated in the heap gets its memory reused by a later `new byte[]` (e.g. a loaded classfile
     *  copy) while addJitFrame keeps writing to it via the stale pointer, scribbling the live object. Sits in the
     *  free scratch window between {@code MARK_BITMAP}'s end (0x03E0_0000) and the heap cells (0x03FF_0000);
     *  needs 2*JIT_FRAME_MAX*24 + JIT_HANDLER_MAX*32 = 0x50000 bytes, well under the ~2 MiB window. */
    public static final long JIT_TABLES = 0x03E0_0000L;

    static int  lastFromFreeList;      // 1 if the last alloc reused a freed block (GC evidence)
    static int  gcPressure;            // collections triggered by allocation pressure (Heap.alloc slow path)

    /** Base of core {@code c}'s arena. Core 0 = {@link #BASE}; secondaries carve 64 MiB slots from 256 MiB up. */
    static long arenaBase(int core)
    {
        return core == 0 ? BASE : 0x1000_0000L + (long) (core - 1) * 0x0400_0000L;
    }

    /** End of core {@code c}'s arena: core 0 runs up to the secondaries' base; each secondary has 64 MiB. */
    static long arenaLimit(int core)
    {
        return core == 0 ? 0x1000_0000L : arenaBase(core) + 0x0400_0000L;
    }

    /** Seed every core's bump pointer + free list. Call once, early in boot, before any {@code new}. */
    public static void init()
    {
        int c = 0;
        while (c < 4)
        {
            Magic.store64(PTR_CELL + c * 8L, arenaBase(c));
            Magic.store64(FREE_CELL + c * 8L, 0L);
            c += 1;
        }
        Magic.store64(CODE_PTR_CELL, CODE_BASE);
    }

    /**
     * Allocate a JIT code buffer from the low code arena (see {@link #CODE_BASE}). Kept separate from the data
     * heap so compiled code stays within the A64 {@code bl} reach of the image's VM helpers. Code is written in
     * full by the compiler, so no zeroing; {@link #publishCode} handles I-cache coherence. Core-0 only (the JIT
     * runs on core 0). No reclaim -- the whole boot's code is a few MiB, far under the 16 MiB arena.
     */
    public static long allocCode(int size)
    {
        int aligned = (size + 7) & -8;
        noteRequest(STATS, (long) aligned);            // what sizes the JIT actually asks for
        long reused = takeFreeCode(aligned);           // a swept method's buffer, before growing the arena
        if (reused != 0L)
        {
            codeReuseCount = codeReuseCount + 1L;
            return reused;
        }
        codeBumpCount = codeBumpCount + 1L;             // nothing fit: the arena grows. Against a large free
        codeBumpBytes = codeBumpBytes + (long) aligned; //   total this is fragmentation, not real demand.
        noteBumpCause((long) aligned, 1);               // ... and this says WHICH of the two it was
        long p = Magic.load64(CODE_PTR_CELL);
        if (p + aligned > CODE_LIMIT)
        {
            board.bcm2711.Uart.write(Magic.bytes("code arena OOM\n"));
            while (true)
            {
                Magic.wfe();                            // the sweep + free list are what keep this from firing
                                                        //   now that batches no longer rewind the arena
            }
        }
        Magic.store64(CODE_PTR_CELL, p + aligned);
        noteCodeBlock(p, aligned);
        return p;
    }

    // ----- code-block registry (metadata lifetime arc, increment 7) --------------------------------------
    /**
     * Every JIT code buffer, as {@code {start, size}} pairs in fixed scratch. The code arena is a plain bump
     * region with no headers — unlike the data heap, whose status word makes it walkable — so nothing can
     * enumerate compiled methods after the fact. Reclaiming code by reachability needs that enumeration
     * first, and answering the question that decides whether reclaiming is worth building at all: how much
     * of the code ever compiled is still reachable?
     *
     * <p>Lives above the code-root table, below the secondary cores' stacks, and outside the managed heap
     * for the same reason: the collector must not be able to reclaim its own bookkeeping.
     */
    public static final long CODE_BLOCKS     = 0x0354_0000L;
    public static final long CODE_BLOCKS_END = 0x0364_0000L;   // 1 MiB = 65,536 blocks
    public static long codeBlockN;
    /** 1 = the registry filled; a code sweep must not run, since the unrecorded blocks cannot be found. */
    public static int codeBlockOverflow;

    /** Registry entry flag (bit 0 of the size word; sizes are 8-aligned): this buffer has been swept and
     *  its bytes are available again. */
    public static final long CODE_FREE = 1L;

    /**
     * Reuse a swept code buffer of at least {@code aligned} bytes, splitting the remainder into its own
     * free entry when it is worth tracking. First fit over the registry, which doubles as the free list —
     * the code arena has no headers to thread a list through, and the registry already enumerates every
     * buffer. Returns 0 if nothing fits.
     *
     * <p>Splitting matters here for the same reason it did in the data heap: without it a 4 KiB freed
     * method satisfying a 200-byte one wastes the difference, and the arena fills with buffers far larger
     * than what occupies them.
     */
    private static long takeFreeCode(int aligned)
    {
        scanFreeBytes = 0L;                            // a scan that runs to the end has surveyed the whole
        scanFreeMax = 0L;                              //   list, which is exactly when the classification runs
        long i = 0;
        while (i < codeBlockN)
        {
            long e = CODE_BLOCKS + i * 16L;
            long sz = Magic.load64(e + 8L);
            if ((sz & CODE_FREE) != 0L)
            {
                long usable = sz & -8L;
                scanFreeBytes = scanFreeBytes + usable;
                if (usable > scanFreeMax)
                {
                    scanFreeMax = usable;
                }
                if (usable >= (long) aligned)
                {
                    long start = Magic.load64(e);
                    long rest = usable - (long) aligned;
                    Magic.store64(e + 8L, (long) aligned);          // allocated, exact size
                    if (rest >= 64L)                                // a remainder too small to hold any
                    {                                               //   method is left inside the block
                        noteCodeBlock(start + (long) aligned, (int) rest);
                        Magic.store64(CODE_BLOCKS + (codeBlockN - 1) * 16L + 8L, rest | CODE_FREE);
                    }
                    else
                    {
                        Magic.store64(e + 8L, usable);              // keep the slack with the allocation
                    }
                    return start;
                }
            }
            i += 1;
        }
        return 0L;
    }

    // ----- allocation-shape measurement -----------------------------------------------------------------
    /**
     * Request-size histograms and bump-failure classification, in fixed scratch above the coalescing index
     * map. Two questions this answers, both of which decide whether SIZE CLASSES would help:
     *
     * <p>(1) What sizes are actually asked for? Power-of-two classes round every request up, so their cost
     * is set by the size distribution — cheap if requests already cluster near class boundaries, expensive
     * if they sit just above one.
     *
     * <p>(2) When an allocation has to grow the arena, WHY? If the free list did not hold enough bytes at
     * all, the space simply was not there and no allocation policy invents it. If it held enough bytes but
     * no single block was big enough, that is external fragmentation — the failure size classes prevent by
     * construction. The two are indistinguishable in the arena total, and they point at opposite fixes.
     */
    public static final long STATS = 0x0374_0000L;
    private static final long STATS_DATA = STATS + 128L;      // 16 buckets each, 8 bytes per bucket

    /** Bump-path failures where the free list held FEWER bytes than the request: a genuine shortage. */
    public static long codeBumpNoSpace;
    public static long dataBumpNoSpace;
    /** Bump-path failures where the free list held ENOUGH bytes but no single block fit: fragmentation. */
    public static long codeBumpWrongShape;
    public static long dataBumpWrongShape;
    /** The same two, in BYTES. The arena's high-water is set by bytes, not by how many allocations took the
     *  bump path, so the counts alone cannot size the win: 287,329 events at 48 bytes and 287,329 events at
     *  8 KiB are the same count and two orders of magnitude apart in arena. `wrongShapeBytes` is the part a
     *  size-class or segregated-fit allocator could plausibly have served from the free list instead. */
    public static long codeNoSpaceBytes;
    public static long dataNoSpaceBytes;
    public static long codeWrongShapeBytes;
    public static long dataWrongShapeBytes;
    /** Totals seen by the most recent COMPLETE free-list scan, used to classify the failure that followed. */
    private static long scanFreeBytes;
    private static long scanFreeMax;
    private static long scanFreeMaxAddr;
    private static long scanFreeBlocks;

    /**
     * Adjacency sampling: at a large failure, would MERGING the largest free block with its free
     * neighbours have satisfied the request? The data heap coalesces adjacent dead blocks during the sweep
     * and never between collections, so a block 32 bytes short of the next request stays 32 bytes short
     * even when the block beside it is free too. This measures how often that is the whole story — and it
     * is the difference between a one-page fix (merge at free time) and building a compactor.
     *
     * <p>Sampled rather than exhaustive: the free list runs to tens of thousands of blocks and finding a
     * neighbour means scanning it, so this walks at most {@link #ADJ_HOPS} neighbours for at most
     * {@link #ADJ_MAX} failures.
     */
    private static final long ADJ_HOPS = 8L;
    private static final long ADJ_MAX = 32L;
    public static long adjSamples;
    public static long adjWouldFit;
    public static long adjMergedMax;
    public static long adjListLen;

    /**
     * Large-allocation failures, sampled at the moment they happen. The cumulative byte split says most of
     * the arena growth comes from a few thousand LARGE requests that found enough free bytes but no block
     * big enough — which is the one failure compaction fixes and size classes do not. Cumulative totals
     * cannot decide whether to build a compactor, though, because the data heap TRIMS its bump pointer
     * after each collection: re-bumping afterwards is expected and healthy, not waste.
     *
     * <p>So sample the state AT each large failure instead: the request, the free list's total and largest
     * block, how many blocks that total is spread across, and where the arena's top was. A failure with a
     * big free total, a small largest block, and a high top is compactable arena. A failure with a low top
     * is just a growing heap doing what it should.
     */
    private static final long LARGE_REQ = 16384L;
    /** Ring of the last 8 large failures: {size, freeTotal, freeMax, top} each. */
    private static final long LARGE_RING = STATS + 256L;
    private static long largeRingN;
    public static long largeFailCount;
    public static long largeFailBytes;
    /** The single worst sample: the failure whose free list held the most unusable bytes. */
    public static long worstFreeTotal;
    public static long worstFreeMax;
    public static long worstSize;
    public static long worstTop;

    /** The size the largest free block would reach if merged with the free blocks that follow it. */
    private static long mergedSizeOf(long start, long len, long head)
    {
        long hops = 0;
        while (hops < ADJ_HOPS)
        {
            long f = head;
            long grew = 0;
            while (f != 0L)
            {
                if (f == start + len)                       // the block immediately after this run is free
                {
                    len = len + Magic.load64(f + ObjectModel.STATUS_OFFSET);
                    grew = 1;
                    f = 0L;
                }
                else
                {
                    f = Magic.load64(f);
                }
            }
            if (grew == 0)
            {
                return len;
            }
            hops += 1L;
        }
        return len;
    }

    /** Start a fresh adjacency sample set, so every batch gets its own budget. */
    public static void resetAdjSampling()
    {
        adjSamples = 0L;
        adjWouldFit = 0L;
        adjMergedMax = 0L;
    }

    /** Sample whether merging would have satisfied this request. */
    private static void noteAdjacency(long size, long head)
    {
        adjSamples = adjSamples + 1L;
        adjListLen = scanFreeBlocks;
        long merged = mergedSizeOf(scanFreeMaxAddr, scanFreeMax, head);
        if (merged > adjMergedMax)
        {
            adjMergedMax = merged;
        }
        if (merged >= size)
        {
            adjWouldFit = adjWouldFit + 1L;                 // one page of allocator, not a compactor
        }
    }

    /** Record one large bump-path failure and its free-list state. */
    private static void noteLargeFail(long size, long top)
    {
        largeFailCount = largeFailCount + 1L;
        largeFailBytes = largeFailBytes + size;
        if (scanFreeBytes > worstFreeTotal)
        {
            worstFreeTotal = scanFreeBytes;
            worstFreeMax = scanFreeMax;
            worstSize = size;
            worstTop = top;
        }
        long e = LARGE_RING + (largeRingN & 7L) * 32L;
        Magic.store64(e, size);
        Magic.store64(e + 8L, scanFreeBytes);
        Magic.store64(e + 16L, scanFreeMax);
        Magic.store64(e + 24L, top);
        largeRingN = largeRingN + 1L;
    }

    /** Print the sampled large failures: the worst one, then the most recent few. */
    public static void printLargeFails()
    {
        board.bcm2711.Uart.write(Magic.bytes("  largeFail n="));
        VM.printDec((int) largeFailCount);
        board.bcm2711.Uart.write(Magic.bytes(" worst: req="));
        VM.printHex(worstSize);
        board.bcm2711.Uart.write(Magic.bytes(" free="));
        VM.printHex(worstFreeTotal);
        board.bcm2711.Uart.write(Magic.bytes(" max="));
        VM.printHex(worstFreeMax);
        board.bcm2711.Uart.write(Magic.bytes(" top="));
        VM.printHex(worstTop);
        board.bcm2711.Uart.putc(0x0A);
        // Would merging the largest free block with its free neighbours have satisfied the request? This is
        // the question that separates a one-page allocator fix from building a compactor.
        board.bcm2711.Uart.write(Magic.bytes("  adj samples="));
        VM.printDec((int) adjSamples);
        board.bcm2711.Uart.write(Magic.bytes(" wouldFitAfterMerge="));
        VM.printDec((int) adjWouldFit);
        board.bcm2711.Uart.write(Magic.bytes(" mergedMax="));
        VM.printHex(adjMergedMax);
        board.bcm2711.Uart.write(Magic.bytes(" listLen="));
        VM.printDec((int) adjListLen);
        board.bcm2711.Uart.putc(0x0A);
        long i = 0;
        while (i < 8L && i < largeRingN)
        {
            long e = LARGE_RING + ((largeRingN - 1L - i) & 7L) * 32L;
            board.bcm2711.Uart.write(Magic.bytes("    req="));
            VM.printHex(Magic.load64(e));
            board.bcm2711.Uart.write(Magic.bytes(" free="));
            VM.printHex(Magic.load64(e + 8L));
            board.bcm2711.Uart.write(Magic.bytes(" max="));
            VM.printHex(Magic.load64(e + 16L));
            board.bcm2711.Uart.write(Magic.bytes(" top="));
            VM.printHex(Magic.load64(e + 24L));
            board.bcm2711.Uart.putc(0x0A);
            i += 1L;
        }
    }

    /** Bucket a size by power of two: 0 = up to 16 B, 1 = up to 32, ... 15 = 256 KiB and above. */
    private static long bucketOf(long size)
    {
        long b = 0;
        long v = size >> 4;
        while (v != 0L && b < 15L)
        {
            b = b + 1L;
            v = v >> 1;
        }
        return b;
    }

    /** Count one request of {@code size} in the histogram at {@code base}. */
    private static void noteRequest(long base, long size)
    {
        long slot = base + bucketOf(size) * 8L;
        Magic.store64(slot, Magic.load64(slot) + 1L);
    }

    /** Classify a bump-path allocation against what the free list held when the scan failed. */
    private static void noteBumpCause(long size, int isCode)
    {
        if (scanFreeBytes >= size)
        {
            if (isCode != 0)
            {
                codeBumpWrongShape = codeBumpWrongShape + 1L;
                codeWrongShapeBytes = codeWrongShapeBytes + size;
            }
            else
            {
                dataBumpWrongShape = dataBumpWrongShape + 1L;
                dataWrongShapeBytes = dataWrongShapeBytes + size;
            }
        }
        else
        {
            if (isCode != 0)
            {
                codeBumpNoSpace = codeBumpNoSpace + 1L;
                codeNoSpaceBytes = codeNoSpaceBytes + size;
            }
            else
            {
                dataBumpNoSpace = dataBumpNoSpace + 1L;
                dataNoSpaceBytes = dataNoSpaceBytes + size;
            }
        }
    }

    /** Print a histogram's non-zero buckets as {@code <=16=N <=32=N ...}. */
    public static void printHist(long base)
    {
        long b = 0;
        while (b < 16L)
        {
            long n = Magic.load64(base + b * 8L);
            if (n != 0L)
            {
                board.bcm2711.Uart.putc(0x20);
                long label = 16L << (int) b;
                if (label >= 1024L)
                {
                    VM.printDec((int) (label >> 10));
                    board.bcm2711.Uart.putc(0x4B);          // ...K, so a bucket label stays short
                }
                else
                {
                    VM.printDec((int) label);
                }
                board.bcm2711.Uart.putc(0x3D);
                VM.printDec((int) n);
            }
            b += 1L;
        }
    }

    /** The code-request histogram base, for reporting. */
    public static long codeHist()
    {
        return STATS;
    }

    /** The data-request histogram base, for reporting. */
    public static long dataHist()
    {
        return STATS_DATA;
    }

    /** How many {@link #allocCode} calls were served from the free list, and how many had to grow the arena
     *  because nothing fit. A high bump count against a large free total is fragmentation, not demand. */
    public static long codeReuseCount;
    public static long codeBumpCount;
    /** Bytes requested by the allocations that had to bump — what fragmentation actually cost in arena. */
    public static long codeBumpBytes;

    /** Free-list survey, recomputed on demand: how many free blocks, how many bytes they hold, and the
     *  largest single one. Free bytes far above the largest block means the space is there but unusable —
     *  the shape that a compactor (or coalescing) fixes and that a bigger arena does not. */
    public static long codeFreeBlocks;
    public static long codeFreeBytes;
    public static long codeFreeMax;
    /** Free blocks under 256 bytes: too small for most methods, so effectively lost until merged. */
    public static long codeFreeTiny;

    /** Walk the registry and fill the survey fields. Cheap — the registry is a flat array of pairs. */
    public static void surveyCodeFree()
    {
        codeFreeBlocks = 0L;
        codeFreeBytes = 0L;
        codeFreeMax = 0L;
        codeFreeTiny = 0L;
        long i = 0;
        while (i < codeBlockN)
        {
            long sz = Magic.load64(CODE_BLOCKS + i * 16L + 8L);
            if ((sz & CODE_FREE) != 0L)
            {
                long usable = sz & -8L;
                codeFreeBlocks = codeFreeBlocks + 1L;
                codeFreeBytes = codeFreeBytes + usable;
                if (usable > codeFreeMax)
                {
                    codeFreeMax = usable;
                }
                if (usable < 256L)
                {
                    codeFreeTiny = codeFreeTiny + 1L;
                }
            }
            i += 1;
        }
    }

    // ----- coalescing adjacent free code (compaction arc, increment 2) ----------------------------------
    /**
     * Address → registry-index map, rebuilt each coalescing pass. The code arena has no headers, so unlike
     * the data heap it cannot be walked block by block; and the registry is in ALLOCATION order, with each
     * split appending its remainder at the end, so it is not in address order either. This map supplies
     * what the data heap gets from its status words: given the address a block starts at, which entry
     * describes it. 131,072 slots of 8 bytes, open-addressed — twice the registry's own capacity, so the
     * table never runs above half full.
     */
    private static final long CODE_INDEX = 0x0364_0000L;
    private static final long CODE_INDEX_SLOTS = 131072L;

    /** Free blocks merged into a neighbour by the last pass, and the bytes they carried. */
    public static long codeMergedBlocks;
    public static long codeMergedBytes;

    /** Scatter every live registry entry into {@link #CODE_INDEX}, keyed by its start address. */
    private static void indexCodeBlocks()
    {
        long z = CODE_INDEX;
        while (z < CODE_INDEX + CODE_INDEX_SLOTS * 8L)
        {
            Magic.store64(z, 0L);
            z += 8L;
        }
        long i = 0;
        while (i < codeBlockN)
        {
            long start = Magic.load64(CODE_BLOCKS + i * 16L);
            if (start != 0L)
            {
                long h = codeSlotOf(start);
                while (Magic.load64(CODE_INDEX + h * 8L) != 0L)   // linear probe; the table is half empty
                {
                    h = (h + 1L) & (CODE_INDEX_SLOTS - 1L);
                }
                Magic.store64(CODE_INDEX + h * 8L, i + 1L);       // +1 so that 0 can mean "empty"
            }
            i += 1;
        }
    }

    /** Hash an 8-aligned code address to a starting slot. */
    private static long codeSlotOf(long addr)
    {
        long h = addr >> 3;
        h = h ^ (h >> 15);
        h = h * 0x9E3779B1L;                              // knuth multiplicative, then take the high bits
        return (h >> 13) & (CODE_INDEX_SLOTS - 1L);
    }

    /** The registry index of the block starting at {@code addr}, or -1 if the map has no such block. */
    private static long lookupCodeBlock(long addr)
    {
        long h = codeSlotOf(addr);
        long v = Magic.load64(CODE_INDEX + h * 8L);
        while (v != 0L)
        {
            if (Magic.load64(CODE_BLOCKS + (v - 1L) * 16L) == addr)
            {
                return v - 1L;
            }
            h = (h + 1L) & (CODE_INDEX_SLOTS - 1L);
            v = Magic.load64(CODE_INDEX + h * 8L);
        }
        return -1L;
    }

    /**
     * Merge runs of adjacent free code blocks into one, the code-arena counterpart of the data sweep's
     * run merging. Without it, {@link #takeFreeCode}'s splitting has no inverse: every reuse can divide a
     * block and nothing ever puts the pieces back, so the arena fills with crumbs — measured at 88% of free
     * blocks under 256 bytes, while the allocations that had to grow the arena averaged 6,878 bytes each.
     *
     * <p>Walks the arena in ADDRESS order via {@link #CODE_INDEX} (blocks tile it contiguously, so the next
     * block always starts where this one ends), accumulating consecutive free blocks. The first entry of a
     * run takes the whole run's size; the others are marked dead (start 0) and removed by
     * {@link #compactCodeRegistry}. If the walk ever meets an address the registry does not describe it
     * stops rather than guessing — the merges already made are each individually sound.
     */
    public static void coalesceCodeFree()
    {
        codeMergedBlocks = 0L;
        codeMergedBytes = 0L;
        if (codeBlockN == 0L)
        {
            return;
        }
        indexCodeBlocks();
        long cursor = CODE_BASE;
        long end = Magic.load64(CODE_PTR_CELL);
        long runIdx = -1L;                                 // entry that will absorb the current free run
        while (cursor < end)
        {
            long i = lookupCodeBlock(cursor);
            if (i < 0L)
            {
                board.bcm2711.Uart.write(Magic.bytes("code coalesce: unmapped block\n"));
                runIdx = -1L;                              // an unknown block ends any run in progress
                break;
            }
            long sz = Magic.load64(CODE_BLOCKS + i * 16L + 8L);
            long usable = sz & -8L;
            if ((sz & CODE_FREE) != 0L && runIdx >= 0L)
            {
                mergeInto(runIdx, i, usable);              // adjacent free: fold this block into the run
            }
            else if ((sz & CODE_FREE) != 0L)
            {
                runIdx = i;                                // a free block starts a run
            }
            else
            {
                runIdx = -1L;                              // a live method ends it
            }
            cursor += usable;
        }
        compactCodeRegistry();
    }

    /** Fold block {@code i} of {@code usable} bytes into the run headed by {@code head}, and kill its entry. */
    private static void mergeInto(long head, long i, long usable)
    {
        long e = CODE_BLOCKS + head * 16L;
        Magic.store64(e + 8L, ((Magic.load64(e + 8L) & -8L) + usable) | CODE_FREE);
        Magic.store64(CODE_BLOCKS + i * 16L, 0L);          // dead entry: compaction drops it
        Magic.store64(CODE_BLOCKS + i * 16L + 8L, 0L);
        codeMergedBlocks = codeMergedBlocks + 1L;
        codeMergedBytes = codeMergedBytes + usable;
    }

    /** Drop the entries killed by merging, preserving the order of the rest. */
    private static void compactCodeRegistry()
    {
        long dst = 0;
        long src = 0;
        while (src < codeBlockN)
        {
            long start = Magic.load64(CODE_BLOCKS + src * 16L);
            if (start != 0L)
            {
                if (dst != src)
                {
                    Magic.store64(CODE_BLOCKS + dst * 16L, start);
                    Magic.store64(CODE_BLOCKS + dst * 16L + 8L, Magic.load64(CODE_BLOCKS + src * 16L + 8L));
                }
                dst += 1L;
            }
            src += 1L;
        }
        codeBlockN = dst;
    }

    /** Mark the registry entry for {@code start} free (the collector swept it). */
    public static void freeCodeBlock(long i)
    {
        long e = CODE_BLOCKS + i * 16L;
        Magic.store64(e + 8L, Magic.load64(e + 8L) | CODE_FREE);
    }

    private static void noteCodeBlock(long start, int size)
    {
        if (CODE_BLOCKS + codeBlockN * 16L >= CODE_BLOCKS_END)
        {
            codeBlockOverflow = 1;
            return;
        }
        Magic.store64(CODE_BLOCKS + codeBlockN * 16L, start);
        Magic.store64(CODE_BLOCKS + codeBlockN * 16L + 8L, (long) size);
        codeBlockN += 1;
    }

    /**
     * Make freshly-written code in {@code [start,end)} visible to instruction fetch:
     * clean each data-cache line to the point of unification, then invalidate the whole
     * instruction cache. Required before executing JIT'd code on real hardware — the Pi 4
     * boots with the caches enabled, so a bare {@code dsb;isb} leaves stale I-cache lines
     * over the just-written buffer and the CPU fetches garbage. QEMU models no I-cache, so
     * this is a no-op-shaped sequence there. (PLAN.md §D; M5.5c JIT publish.)
     */
    public static void publishCode(long start, long end)
    {
        long a = start & ~63L;                 // Cortex-A72 cache line = 64 bytes
        while (a < end)
        {
            Magic.dcCVAU(a);                   // clean the D-cache line to PoU
            a += 64L;
        }
        Magic.dsb();                           // the cleans reach unified memory
        Magic.icIALLU();                       // drop stale I-cache lines
        Magic.dsb();                           // the invalidate completes
        Magic.isb();                           // refetch past this point
    }

    /** Allocate {@code size} bytes: reuse a freed block if one fits, else bump; on core 0, an exhausted
     *  arena triggers a collection ({@link Magic#gc}) and one retry before halting out-of-memory. */
    public static long alloc(int size)
    {
        // The free-list unlink and the bump-pointer load..store below MUST NOT be preempted: the timer
        // scheduler switches tasks on this core, and a task switched in mid-alloc allocates the SAME
        // address (a lost bump) — overlapping objects and size-walk holes that break the GC's heap scan.
        // Save/restore the I-bit so a caller that already masked (e.g. the collector) stays masked.
        long daif = Magic.readDaif();
        Magic.disableIrq();
        long p = allocLocked(size);
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
        return p;
    }

    private static long allocLocked(int size)
    {
        int aligned = (size + 7) & -8;
        if (aligned < ObjectModel.HEADER_SIZE)
        {
            // Never carve a block smaller than the {TIB, status} header: an 8-byte block's status word
            // (at +8) lies OUTSIDE its own extent, so the NEXT allocation starts at +8 and its header
            // zeroing clobbers this block's status to 0 -- which breaks the GC's walk-by-size heap scan
            // (it stops at the first size-0 status and sweeps nothing beyond).
            aligned = ObjectModel.HEADER_SIZE;
        }
        noteRequest(STATS_DATA, (long) aligned);            // the object-size distribution, for size classes
        int core = (int) (Magic.readMPIDR() & 3L);          // this core's arena (low 2 bits of MPIDR)
        long freeCell = FREE_CELL + core * 8L;
        long ptrCell = PTR_CELL + core * 8L;
        int attempt = 0;
        while (attempt < 2)
        {
            long prev = 0L;
            long f = Magic.load64(freeCell);
            scanFreeBytes = 0L;
            scanFreeMax = 0L;
            scanFreeBlocks = 0L;
            while (f != 0L)                                 // first fit in this core's free list
            {
                long fsize = Magic.load64(f + ObjectModel.STATUS_OFFSET);
                scanFreeBytes = scanFreeBytes + fsize;
                scanFreeBlocks = scanFreeBlocks + 1L;
                if (fsize > scanFreeMax)
                {
                    scanFreeMax = fsize;
                    scanFreeMaxAddr = f;
                }
                if (fsize >= aligned)
                {
                    long next = Magic.load64(f);
                    if (prev == 0L)
                    {
                        Magic.store64(freeCell, next);
                    }
                    else
                    {
                        Magic.store64(prev, next);
                    }
                    lastFromFreeList = 1;
                    zeroPayload(f, aligned);                // Java requires 0/default fields+elements
                    // SPLIT the remainder off rather than handing back the whole block. Without this a
                    // 64 KiB free block servicing a 32-byte cons cell stays 64 KiB, and the arena's usable
                    // capacity collapses to "one allocation per freed block" -- invisible while the loader
                    // rewound the heap every batch (the free list was discarded and allocation was
                    // essentially pure bump), fatal the moment reclamation is by reachability. The remainder
                    // must itself be a legal block: at least a {TIB, status} header, so its status word lies
                    // inside its own extent.
                    long rest = fsize - (long) aligned;
                    if (rest >= (long) ObjectModel.HEADER_SIZE)
                    {
                        Magic.store64(f + ObjectModel.STATUS_OFFSET, (long) aligned);   // this block's size
                        addFree(f + (long) aligned, rest);                              // ... and the rest
                    }
                    return f;                               // status holds the size actually handed out
                }
                prev = f;
                f = Magic.load64(f);
            }
            long p = Magic.load64(ptrCell);
            if (p + aligned <= arenaLimit(core))
            {
                noteBumpCause((long) aligned, 0);            // shortage, or space of the wrong shape?
                if ((long) aligned >= LARGE_REQ)             // and if it was large, snapshot the moment: the
                {                                            //   cumulative totals cannot separate healthy
                    noteLargeFail((long) aligned, p);        //   re-bumping after a trim from real waste
                    if (scanFreeBytes >= (long) aligned && adjSamples < ADJ_MAX)
                    {
                        noteAdjacency((long) aligned, Magic.load64(freeCell));
                    }
                }
                Magic.store64(ptrCell, p + aligned);
                Magic.store64(p + ObjectModel.STATUS_OFFSET, aligned);   // record size for the GC
                lastFromFreeList = 0;
                zeroPayload(p, aligned);
                return p;
            }
            if (core != 0 || attempt == 1)
            {
                break;                                      // secondaries are never collected; core 0 tried once
            }
            gcPressure += 1;                                // arena full: collect (Magic.gc spills x19..x28 so
            Magic.gc();                                     //   callee-saved refs are on the scannable stack)
            attempt += 1;
        }
        board.bcm2711.Uart.write(Magic.bytes("heap OOM\n"));
        while (true)
        {
            Magic.wfe();                                    // out of memory even after a collection: halt
        }
    }

    /**
     * Zero an allocation's payload (past the {TIB, status} header), so freshly-allocated
     * objects and arrays honour Java's default initialization. Real hardware RAM comes up
     * with garbage; without this, uninitialized-but-assumed-zero fields (e.g. a compiler's
     * counter) hold junk and code that ran on QEMU (whose RAM starts zeroed) hangs on metal.
     */
    private static void zeroPayload(long base, int aligned)
    {
        Magic.store64(base + ObjectModel.TIB_OFFSET, 0L);   // Also zero offset 0 (the TIB slot). A RAW allocation
        // (TIB / itableDir / imap / a code buffer) that reads offset 0 before its caller sets it would otherwise get
        // garbage on a warm reboot (RAM isn't cleared on a guest-triggered reset) -> intermittent wild branches.
        // Offset 8 is STATUS (deterministically the block size, set by alloc), so it needs no zeroing.
        long z = base + ObjectModel.HEADER_SIZE;
        long end = base + aligned;
        while (z < end)
        {
            Magic.store64(z, 0L);
            z += 8L;
        }
    }

    /**
     * Allocate a RAW data buffer whose payload legally occupies its first words (on-metal Types, TIBs,
     * imaps, itable directories, statics blocks): the payload starts one {TIB, status} header past the
     * block base, so the header stays intact and the GC's walk-by-size heap scan stays sound — a raw
     * struct that stored e.g. {@code superType = 0} at +8 used to zero its own status word and stop the
     * sweep dead at that block. All references naturally hold the PAYLOAD address; {@code VM.markRange}
     * probes {@code w - 16} so a payload pointer marks its block, and the trace phase scans marked
     * blocks from +16 — exactly the payload — so refs inside these structs keep their targets alive.
     */
    public static long allocData(int size)
    {
        return alloc(size + ObjectModel.HEADER_SIZE) + ObjectModel.HEADER_SIZE;
    }

    /** Allocate an array of {@code length} elements of {@code elemSize} bytes. */
    public static long allocArray(int length, int elemSize)
    {
        long p = alloc(ObjectModel.ARRAY_BASE_OFFSET + length * elemSize);
        // The array's TIB slot holds its element size (1/2/4/8) — small, so it's distinguishable from an
        // object's TIB (a heap pointer), and it lets a generic System.arraycopy compute byte offsets. The
        // conservative GC never derefs this slot (it scans from +16), so a non-pointer here is safe.
        Magic.store64(p + ObjectModel.TIB_OFFSET, elemSize);
        Magic.store64(p + ObjectModel.ARRAY_LENGTH_OFFSET, length);
        return p;
    }

    /** Reset the current core's free list (start of a sweep — the GC runs on the collecting core). */
    static void resetFreeList()
    {
        Magic.store64(FREE_CELL + (int) (Magic.readMPIDR() & 3L) * 8L, 0L);
    }

    /** Add a reclaimed block to the current core's free list. */
    static void addFree(long addr, long size)
    {
        long freeCell = FREE_CELL + (int) (Magic.readMPIDR() & 3L) * 8L;
        Magic.store64(addr, Magic.load64(freeCell));             // next
        Magic.store64(addr + ObjectModel.STATUS_OFFSET, size);   // size
        Magic.store64(freeCell, addr);
    }
}
