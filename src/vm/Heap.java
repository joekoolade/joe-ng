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

    // ----- the large-object region -----------------------------------------------------------------------
    /**
     * Core 0's arena is split: small objects below {@link #LARGE_BASE}, objects of {@link #LARGE_REQ} or
     * more above it, in blocks that are always a multiple of {@link #PAGE}.
     *
     * <p>Why a separate region rather than a quantum in the shared one — the previous attempt, which failed.
     * Rounding large REQUESTS to 4 KiB fixed nothing (2,500 failures became 2,392, for 44.8 MB of slack)
     * because supply stayed arbitrary: free blocks come from sweep-merged runs of adjacent dead blocks and
     * from split remainders, and in a region shared with arbitrary-size small objects both are arbitrary
     * sizes. The request landed on the 4 KiB lattice and the free blocks did not, so the near-miss simply
     * moved up one quantum — 69,632 needed, 69,600 available, 32 bytes short again.
     *
     * <p>In a region where EVERY block is a multiple of the page, merged runs and split remainders are page
     * multiples too. Demand and supply share one lattice, and exact reuse becomes structural instead of
     * coincidental. That is the property buddy allocators buy with alignment and this buys with segregation.
     */
    public static final long PAGE       = 4096L;
    public static final long LARGE_REQ  = 16384L;
    public static final long LARGE_BASE = 0x0C00_0000L;      // top 64 MiB of core 0's arena
    public static final long LARGE_LIMIT = 0x1000_0000L;
    /** Bump pointer and free-list head for the large region, beside the small region's own cells. */
    public static final long LARGE_PTR_CELL  = 0x03FF_0208L;
    public static final long LARGE_FREE_CELL = 0x03FF_0210L;
    /**
     * One bit per 8 bytes of the code arena, marking buffers that another buffer branches to DIRECTLY.
     *
     * <p>The collector learns that code is reachable by seeing a code address in a heap or stack word
     * ({@code tryMark}) or baked into code as a heap reference ({@code CODE_ROOTS}). Neither sees a
     * CODE->CODE edge: when {@code patchRelocsFrom} rewrites a {@code bl 0} into {@code bl target}, that
     * address exists only as a 26-bit displacement inside the caller's instruction stream, which nothing
     * scans. The cell that held it beforehand is then free to die, and the callee looks unreachable while
     * a live method still branches to it — measured as a fault executing zeros in a swept buffer.
     *
     * <p>Pinning rather than tracing the edge: a pinned buffer is never swept, which over-retains (the
     * callee outlives a caller that itself died) but cannot free something live. Tracing the edges properly
     * needs an owner-keyed table and a fixpoint over it; that is worth building only if the retention this
     * costs turns out to matter, which {@code codeLive} against {@code codeUsed} will show.
     */
    public static final long CODE_PIN_BITMAP = 0x0378_0000L;
    private static final long CODE_PIN_END   = 0x037C_0000L;   // 256 KiB = 1 bit per 8 B of a 16 MiB arena

    /**
     * One past the last byte of the code block containing {@code addr}, or 0 if no block does.
     *
     * <p>For bounding a pc -> method lookup. A compiled method's buffer IS a code block, so a pc lying in a
     * DIFFERENT block cannot belong to the method registered at {@code addr}, however close the two are.
     * Linear over the registry, which is fine: the only callers are fault reporting and stack traces.
     */
    public static long codeBlockEndAt(long addr)
    {
        long i = 0;
        while (i < codeBlockN)
        {
            long e = CODE_BLOCKS + i * 16L;
            long start = Magic.load64(e);
            long usable = Magic.load64(e + 8L) & -8L;
            if (start != 0L && addr >= start && addr < start + usable)
            {
                return start + usable;
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Bytes handed out since the last collection, and the volume at which to collect anyway.
     *
     * <p>Collections already happen mid-batch -- but only when an arena is EXHAUSTED, which is exactly why
     * the bump pointer ratchets: nothing asks for a collection until nothing is left. Triggering on
     * allocation VOLUME instead attacks the one quantity three earlier mechanisms could not touch. The code
     * trim, coalescing and the split floor all act at collection time or in placement policy, and the law
     * those three established is that **the high-water is set by demand BETWEEN collections** -- so no
     * collection-time mechanism reaches it. This one does, by collecting sooner.
     *
     * <p>Set to 0 to disable.
     */
    public static long allocSinceGc;
    private static final long GC_TRIGGER_BYTES = 16L * 1024L * 1024L;

    /** True if enough has been allocated since the last collection to be worth collecting on volume. */
    private static boolean volumeTrigger()
    {
        return GC_TRIGGER_BYTES != 0L && allocSinceGc >= GC_TRIGGER_BYTES;
    }

    /** Mark the code at {@code addr} as branched-to directly, so the sweep must keep it. */
    public static void pinCodeAt(long addr)
    {
        if (addr < CODE_BASE || addr >= CODE_LIMIT)
        {
            return;
        }
        long bit = (addr - CODE_BASE) >> 3;
        long w = CODE_PIN_BITMAP + ((bit >> 6) << 3);
        Magic.store64(w, Magic.load64(w) | (1L << (int) (bit & 63L)));
    }

    /** 1 if any address in {@code [start,start+size)} has been pinned. */
    public static int codePinnedIn(long start, long size)
    {
        long b = (start - CODE_BASE) >> 3;
        long bEnd = b + (size >> 3);
        while (b < bEnd)
        {
            if ((Magic.load64(CODE_PIN_BITMAP + ((b >> 6) << 3)) & (1L << (int) (b & 63L))) != 0L)
            {
                return 1;
            }
            b += 1L;
        }
        return 0;
    }

    /** Large allocations served from the free list vs forced to extend the region. */
    public static long largeReuse;
    public static long largeBump;

    /** Fixed scratch base for the JIT unwind tables (frame/local/handler). They must live OUTSIDE the managed
     *  heap [BASE, PTR): they are held only by VM static pointers for the whole run, but the mark-sweep GC
     *  reclaims dead blocks onto the free list and the demand-loader rewinds the bump pointer per batch -- either
     *  way a table allocated in the heap gets its memory reused by a later `new byte[]` (e.g. a loaded classfile
     *  copy) while addJitFrame keeps writing to it via the stale pointer, scribbling the live object. Sits in the
     *  free scratch window between {@code MARK_BITMAP}'s end (0x03E0_0000) and the heap cells (0x03FF_0000);
     *  needs 2*JIT_FRAME_MAX*24 + JIT_HANDLER_MAX*32 = 0x50000 bytes, well under the ~2 MiB window. */
    public static final long JIT_TABLES = 0x03E0_0000L;

    static int  lastFromFreeList;      // 1 if the last alloc reused a freed block (GC evidence)
    /** Collections triggered by allocation pressure, from EITHER arena's slow path. Both count: the demo
     *  that reports it is asking "did the program have to collect mid-computation?", and which region ran
     *  out is not part of that question. Missing the large path here read as "collections=0" during a demo
     *  whose own log showed eleven. */
    static int  gcPressure;

    /** Base of core {@code c}'s arena. Core 0 = {@link #BASE}; secondaries carve 64 MiB slots from 256 MiB up. */
    static long arenaBase(int core)
    {
        return core == 0 ? BASE : 0x1000_0000L + (long) (core - 1) * 0x0400_0000L;
    }

    /** End of core {@code c}'s arena: core 0 runs up to the secondaries' base; each secondary has 64 MiB. */
    static long arenaLimit(int core)
    {
        return core == 0 ? LARGE_BASE : arenaBase(core) + 0x0400_0000L;   // core 0's tail is the large region
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
        Magic.store64(LARGE_PTR_CELL, LARGE_BASE);
        Magic.store64(LARGE_FREE_CELL, 0L);
        long pz = CODE_PIN_BITMAP;
        while (pz < CODE_PIN_END)
        {
            Magic.store64(pz, 0L);                     // the pin bitmap persists across collections, so it
            pz += 8L;                                  //   is cleared exactly once, here
        }
        // STATS is raw scratch that noteRequest read-modify-writes, and nothing ever initialised it. QEMU
        // hands out zeroed RAM so it looked right there; the Pi's DRAM comes up ALL-ONES, so every bucket
        // started at -1 and each count read one low, with an untouched bucket printing `16=-1`. Invisible
        // until #149: STUB_TAB used to overlay these exact words, so the corruption masked the omission.
        // Covers both histograms (STATS, STATS+128) and LARGE_RING (STATS+256, 8 entries x 32 bytes).
        long gz = GROWTH_TAB;
        while (gz < GROWTH_TAB + GROWTH_BUCKETS * 8L)
        {
            Magic.store64(gz, 0L);
            gz += 8L;
        }
        long sz = STATS;
        while (sz < STATS + 640L)
        {
            Magic.store64(sz, 0L);
            sz += 8L;
        }
    }

    /**
     * Allocate from the large region. Page-quantised first fit with splitting; the remainder is a page
     * multiple by construction, so it can serve any later large request that fits it exactly. Freeing and
     * coalescing are the sweep's job ({@code VMGc} walks this region like the small one).
     */
    private static long allocLarge(long size)
    {
        long want = (size + PAGE - 1L) & -PAGE;
        if (volumeTrigger())                               // LARGE allocations are most of the volume, so
        {                                                  //   the trigger has to see them or it never
            allocSinceGc = 0L;                             //   fires during the churn and Lisp phases --
            gcPressure += 1;                               //   measured: hooking only the small path left
            Magic.gc();                                    //   collections and the high-water UNCHANGED
        }
        allocSinceGc = allocSinceGc + want;
        long prev = 0L;
        long f = Magic.load64(LARGE_FREE_CELL);
        while (f != 0L)
        {
            long fsize = Magic.load64(f + ObjectModel.STATUS_OFFSET);
            if (fsize >= want)
            {
                long next = Magic.load64(f);
                if (prev == 0L) { Magic.store64(LARGE_FREE_CELL, next); }
                else { Magic.store64(prev, next); }
                long rest = fsize - want;
                if (rest >= PAGE)                          // the remainder is a whole number of pages, so it
                {                                          //   stays on the same lattice as every request
                    Magic.store64(f + ObjectModel.STATUS_OFFSET, want);
                    addFreeLarge(f + want, rest);
                }
                largeReuse = largeReuse + 1L;
                zeroPayload(f, (int) Magic.load64(f + ObjectModel.STATUS_OFFSET));
                return f;
            }
            prev = f;
            f = Magic.load64(f);
        }
        long p = Magic.load64(LARGE_PTR_CELL);
        if (p + want > LARGE_LIMIT)
        {
            return 0L;                                     // caller collects and retries
        }
        Magic.store64(LARGE_PTR_CELL, p + want);
        Magic.store64(p + ObjectModel.STATUS_OFFSET, want);
        largeBump = largeBump + 1L;
        zeroPayload(p, (int) want);
        return p;
    }

    /** Push a run onto the large region's free list. */
    public static void addFreeLarge(long addr, long size)
    {
        Magic.store64(addr, Magic.load64(LARGE_FREE_CELL));
        Magic.store64(addr + ObjectModel.STATUS_OFFSET, size);
        Magic.store64(LARGE_FREE_CELL, addr);
    }

    /**
     * Allocate a JIT code buffer from the low code arena (see {@link #CODE_BASE}). Kept separate from the data
     * heap so compiled code stays within the A64 {@code bl} reach of the image's VM helpers. Code is written in
     * full by the compiler, so no zeroing; {@link #publishCode} handles I-cache coherence. Core-0 only (the JIT
     * runs on core 0). No reclaim -- the whole boot's code is a few MiB, far under the 16 MiB arena.
     */
    public static long allocCode(int size)
    {
        VM.loaderLock();                               // SMP: one bump pointer, and no atomic behind it
        long buf = allocCodeLocked(size);
        VM.loaderUnlock();
        return buf;
    }

    /** {@link #allocCode}'s body, run under the loader lock (which the JIT paths already hold). */
    private static long allocCodeLocked(int size)
    {
        int aligned = (size + 7) & -8;
        codeAllocSinceSweep = codeAllocSinceSweep + (long) aligned;
        long cp = Magic.load64(CODE_PTR_CELL);
        if (cp > codePeak)
        {
            codePeak = cp;                             // the arena's real high-water, sampled where it grows
        }
        noteRequest(STATS, (long) aligned);            // what sizes the JIT actually asks for
        long bb = CODE_BYTES + bucketOf((long) aligned) * 8L;
        Magic.store64(bb, Magic.load64(bb) + (long) aligned);   // ... and how many BYTES each class costs

        long reused = takeFreeCode(aligned);           // a swept method's buffer, before growing the arena
        if (reused != 0L)
        {
            codeReuseCount = codeReuseCount + 1L;
            return reused;
        }
        codeBumpCount = codeBumpCount + 1L;             // nothing fit: the arena grows. Against a large free
        codeBumpBytes = codeBumpBytes + (long) aligned; //   total this is fragmentation, not real demand.
        noteBumpCause((long) aligned, 1);               // ... and this says WHICH of the two it was
        noteGrowthAge();                                // ... and HOW LONG since a sweep -- see codeAllocSinceSweep
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
        Magic.store64(CODE_BLOCKS + (codeBlockN - 1) * 16L + 8L,
                      Magic.load64(CODE_BLOCKS + (codeBlockN - 1) * 16L + 8L) | CODE_YOUNG);
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
     * Registry flag: this buffer was allocated since the last collection, so the sweep must keep it.
     *
     * <p>{@link #allocCode} hands back a buffer that nothing references yet — the compiler fills it and
     * only afterwards stores its address into a cell, a vtable slot or a static. A collection triggered
     * inside that window (any heap allocation the compiler makes can call {@code Magic.gc}) sees a buffer
     * no root reaches, frees it, and zeroes it; the compiler then publishes the address and the first call
     * through it executes zeros. That is the fault this arc chased for ten cycles: a stub freed at
     * collection 7 and branched into at 26, with no live holder at the time because the address was still
     * in flight.
     *
     * <p>One collection of grace is enough and is the standard answer (allocate black): a buffer is exempt
     * for the collection that follows its allocation, by which time it is either published or genuinely
     * garbage.
     */
    public static final long CODE_YOUNG = 2L;

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
                    Magic.store64(e + 8L, (long) aligned | CODE_YOUNG);   // allocated, exact size
                    if (rest >= 64L)                                // a remainder too small to hold any
                    {                                               //   method is left inside the block
                        noteCodeBlock(start + (long) aligned, (int) rest);
                        Magic.store64(CODE_BLOCKS + (codeBlockN - 1) * 16L + 8L, rest | CODE_FREE);
                    }
                    else
                    {
                        Magic.store64(e + 8L, usable | CODE_YOUNG);   // keep the slack with the allocation
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
    /**
     * BYTES per code size-class, beside the existing COUNT histogram. Counts alone cannot say where the
     * arena's bytes are: 14,399 sixty-four-byte stubs and 8 half-megabyte buffers look nothing alike by
     * count and may be worlds apart by volume. Peak simultaneous liveness is a byte quantity, so if the
     * lever is "compile less per batch" this says what to compile less OF. Inside the STATS reservation
     * (1 KiB claimed, 512 bytes used), so the scratch map is unchanged.
     */
    private static final long CODE_BYTES = STATS + 512L;

    private static final long STATS_DATA = STATS + 128L;      // 16 buckets each, 8 bytes per bucket

    /** Bump-path failures where the free list held FEWER bytes than the request: a genuine shortage. */
    /**
     * Code bytes allocated since the last code sweep, and a histogram of that value SAMPLED AT EVERY ARENA
     * GROWTH EVENT.
     *
     * <p>This is the whole test for "collect during a batch". The arena grows when nothing on the free list
     * fits; a collection at that instant helps only if there is uncollected garbage to find. If growth
     * clusters at LOW values -- shortly after a sweep -- then nothing had died yet and collecting sooner
     * cannot help, which is the same wall the trim, coalescing, the split floor, the volume trigger and
     * compaction all hit. If it clusters HIGH, garbage accumulated before the arena was forced to grow, and
     * collecting sooner reclaims real space.
     *
     * <p>Buckets are powers of four from 16 KiB, so one line separates "just swept" from "long since swept".
     */
    /**
     * True running maximum of the code bump pointer -- the arena's ACTUAL peak, with no floor.
     *
     * <p>{@code Loader.codeHeapHigh} was reported as "high (max batch ever)" and is not one: it is seeded to
     * {@code codeHeapMark + CODE_ZERO_SPAN} (8 MiB) because it doubles as the upper bound of the rewind
     * path's re-zeroing loop, so it reads {@code mark + 8 MiB} whenever the real peak is below that -- which
     * it always was. Every "arena high-water unchanged" reading in this project's GC arcs came from that
     * constant, including the one headlined for #147 as byte-identical across QEMU and hardware. It was
     * identical because it is the same constant on both.
     *
     * <p>That field keeps its job. This one is the measurement.
     */
    public static long codePeak;

    public static long codeAllocSinceSweep;
    /** Set when the arena grows more than 1 MiB after a sweep -- the windows inc 1 could not rule out. */
    public static long lateGrowthSeen;
    public static final long GROWTH_BUCKETS = 8L;
    private static final long GROWTH_TAB = 0x0306_0000L - 128L;   // tail of the MBOX_BUFFER slot, unused

    /** Bucket the bytes-since-sweep at one growth event. */
    private static void noteGrowthAge()
    {
        long v = codeAllocSinceSweep >> 14;             // 16 KiB granularity
        long b = 0L;
        while (b + 1L < GROWTH_BUCKETS && v > 0L)
        {
            v = v >> 2;
            b += 1L;
        }
        Magic.store64(GROWTH_TAB + b * 8L, Magic.load64(GROWTH_TAB + b * 8L) + 1L);
        if (codeAllocSinceSweep > 0x100000L)
        {
            lateGrowthSeen = 1L;
        }
    }

    /** Print the growth-age histogram: how long since a sweep, each time the arena was forced to grow. */
    public static void printGrowthAges()
    {
        board.bcm2711.Uart.write(Magic.bytes("  growthAge(16K,64K,256K,1M,4M,16M,64M,+):"));
        long b = 0L;
        while (b < GROWTH_BUCKETS)
        {
            board.bcm2711.Uart.putc(0x20);
            VM.printDec((int) Magic.load64(GROWTH_TAB + b * 8L));
            b += 1L;
        }
        board.bcm2711.Uart.putc(0x0A);
        board.bcm2711.Uart.write(Magic.bytes("  codeBytes:"));
        long cb = 0L;
        while (cb < 16L)
        {
            long v = Magic.load64(CODE_BYTES + cb * 8L);
            if (v != 0L)
            {
                board.bcm2711.Uart.putc(0x20);
                VM.printDec((int) cb);
                board.bcm2711.Uart.putc(0x3D);
                VM.printHex(v);
            }
            cb += 1L;
        }
        board.bcm2711.Uart.putc(0x0A);
        board.bcm2711.Uart.write(Magic.bytes("  codeGarbage sweeps="));
        VM.printDec((int) VMGc.sweepsTotal);
        board.bcm2711.Uart.write(Magic.bytes(" zeroFreed="));
        VM.printDec((int) VMGc.sweepsZeroFreed);
        board.bcm2711.Uart.write(Magic.bytes(" freedTotal="));
        VM.printHex(VMGc.codeFreedTotal);
        board.bcm2711.Uart.write(Magic.bytes(" bumped="));
        VM.printHex(codeBumpBytes);
        board.bcm2711.Uart.write(Magic.bytes(" lateSweeps="));
        VM.printDec((int) VMGc.lateSweeps);
        board.bcm2711.Uart.write(Magic.bytes(" lateFreed="));
        VM.printHex(VMGc.lateFreed);
        board.bcm2711.Uart.putc(0x0A);
    }

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
    // (the threshold is LARGE_REQ above -- the same 16 KiB line that routes an allocation to the large
    //  region now also decides what counts as a "large failure" worth sampling, which is what we want:
    //  the instrument and the mechanism should not be able to disagree about what "large" means)
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

    /**
     * Record one large bump-path failure and its free-list state.
     *
     * <p>READS ZERO NOW, and that is the point. This sampler measured large requests failing to find a fit
     * in the SMALL heap — the 2,500 near-misses that motivated the large-object region. Core 0 no longer
     * routes such a request through that path at all, so the failure it samples cannot occur; a nonzero
     * count here would mean the region had been removed or bypassed. Kept as exactly that regression
     * detector, and because secondary cores (which have no large region) still take the path.
     */
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

    /** Rebuild the address->index map, so a caller can walk the arena in ADDRESS order (blocks tile it). */
    static void indexBlocksForWalk()
    {
        indexCodeBlocks();
    }

    /** Registry index of the block STARTING at {@code start}, or -1. Needs {@link #indexBlocksForWalk} first. */
    static long blockIndexAt(long start)
    {
        return lookupCodeBlock(start);
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
        codeAllocSinceSweep = 0L;                      // the sweep+merge just ran: the clock restarts here
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

    /**
     * 1 if {@code addr} falls inside a code block the sweep has freed, 0 if it is inside a live one, -1 if
     * no block covers it. Lets a caller assert the invariant that matters after a code sweep: every address
     * something still dispatches through must lie in an allocated buffer.
     */
    public static int codeBlockFreeAt(long addr)
    {
        long i = 0;
        while (i < codeBlockN)
        {
            long e = CODE_BLOCKS + i * 16L;
            long start = Magic.load64(e);
            long sz = Magic.load64(e + 8L);
            long usable = sz & -8L;
            if (start != 0L && addr >= start && addr < start + usable)
            {
                return (sz & CODE_FREE) != 0L ? 1 : 0;
            }
            i += 1;
        }
        return -1;
    }

    /** Mark the registry entry for {@code start} free (the collector swept it). */
    public static void freeCodeBlock(long i)
    {
        long e = CODE_BLOCKS + i * 16L;
        long sz = Magic.load64(e + 8L);
        Magic.store64(e + 8L, sz | CODE_FREE);
        CodeEdges.pruneRange(Magic.load64(e), Magic.load64(e) + (sz & -8L));   // edges FROM this block die here:
    }                                                                          //   the one moment the range is exact

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
        // Both maintenance ops are BY VIRTUAL ADDRESS, which is what makes them broadcast to the whole
        // Inner Shareable domain -- i.e. to the other three cores. This used to end in IC IALLU, which is
        // local to the calling PE: fine while only core 0 ever ran JIT'd code, fatal the moment another core
        // executes a method core 0 compiled. The code arena REUSES swept buffers, so the other core's
        // I-cache genuinely holds stale (or zeroed) lines for that exact address, and it faults with
        // ESR EC=0 -- an undefined instruction -- at the entry of a method that looks perfectly good in
        // memory. Found on the first Pi boot that scheduled guest threads across all four cores.
        long a = start & ~63L;                 // Cortex-A72 cache line = 64 bytes
        while (a < end)
        {
            Magic.dcCVAU(a);                   // clean the D-cache line to PoU (broadcast)
            a += 64L;
        }
        Magic.dsb();                           // the cleans reach unified memory, everywhere
        a = start & ~63L;
        while (a < end)
        {
            Magic.icIVAU(a);                   // drop that line from EVERY core's I-cache
            a += 64L;
        }
        Magic.dsb();                           // the invalidates complete
        Magic.isb();                           // this core refetches past this point (others: their ERET)
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
        if (core == 0 && (long) aligned >= LARGE_REQ)
        {
            long big = allocLarge((long) aligned);
            if (big != 0L)
            {
                return big;
            }
            gcPressure += 1;                                // an allocation-pressure collection like any
            Magic.gc();                                     //   other: count it, or the churn demo reports
            big = allocLarge((long) aligned);               //   "collections=0" while collecting elevenfold
            if (big != 0L)
            {
                return big;
            }
            board.bcm2711.Uart.write(Magic.bytes("large region OOM\n"));
            while (true)
            {
                Magic.wfe();
            }
        }
        long freeCell = FREE_CELL + core * 8L;
        long ptrCell = PTR_CELL + core * 8L;
        int attempt = 0;
        while (attempt < 2)
        {
            if (core == 0 && volumeTrigger())               // collect on VOLUME, not on running out: the
            {                                               //   ratchet exists because nothing asks for a
                allocSinceGc = 0L;                          //   collection until the arena is exhausted.
                gcPressure += 1;                            // BEFORE serving, never after: a block handed
                Magic.gc();                                 //   out and then collected in the same call is
            }                                               //   live only in a register the sweep may not
            long prev = 0L;                                 //   scan -- the caller would get freed memory

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
                allocSinceGc = allocSinceGc + (long) aligned;
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
        Magic.store64(LARGE_FREE_CELL, 0L);            // the large sweep rebuilds this list too, and old
    }                                                  //   entries are re-walked as dead blocks anyway

    /** Add a reclaimed block to the current core's free list. */
    static void addFree(long addr, long size)
    {
        long freeCell = FREE_CELL + (int) (Magic.readMPIDR() & 3L) * 8L;
        Magic.store64(addr, Magic.load64(freeCell));             // next
        Magic.store64(addr + ObjectModel.STATUS_OFFSET, size);   // size
        Magic.store64(freeCell, addr);
    }
}
