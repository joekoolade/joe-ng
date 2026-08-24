package vm;

import magic.Magic;
import board.bcm2711.Uart;

/**
 * The code-arena relocation PLAN -- what compaction would move, and whether every reference to it could be
 * rewritten. **Nothing moves.**
 *
 * <p>This is the increment before the mover, for the same reason {@link CodeEdges} came before trusting the
 * edge set: moving code is the first change in this arc that can corrupt a running VM, and the question
 * "could we rewrite every reference?" is answerable without taking the risk. If some class of reference
 * cannot be mapped, that is a fact worth having BEFORE the bytes move, not after -- a compactor that misses
 * one reference produces exactly the #146 failure (a live caller branching to where a buffer used to be),
 * and that took five wrong theories to find the first time.
 *
 * <p><b>The plan.</b> Blocks tile the arena contiguously, so an address-ordered walk visits every one. Live
 * blocks slide down to close the gaps left by free ones; each gets a new base, recorded here in address
 * order (so the table is sorted by construction and a lookup is a binary search). Free blocks get 0.
 *
 * <p><b>What it checks.</b> Three reference populations, in increasing order of difficulty:
 * <ul>
 *   <li>{@code edgesMappable}/{@code edgesUnmappable} -- every LIVE census edge: both its site and its target
 *       must fall inside a block the plan knows about. An unmappable edge is a branch the mover could not fix.
 *   <li>{@code stackRefs} -- words on the stack that point into a block that would MOVE. These are return
 *       addresses, and they are the hardest category: nothing declares them, the collector only scans the
 *       stack conservatively, and a wrong rewrite corrupts a live frame. A non-zero count here is not a bug,
 *       it is the size of the problem the mover has to solve.
 *   <li>{@code recovered} -- bytes the arena top would drop by, which is what the whole exercise is for.
 * </ul>
 *
 * <p>Read {@code recovered} against the law recorded in PLAN.md: the arena's high-water is set by demand
 * BETWEEN collections, so bytes recovered AT a collection do not automatically lower the peak. This measures
 * what compaction reclaims, not what it saves.
 */
final class CodeCompact
{
    /**
     * {start, newBase} per walked block, in ADDRESS order. 256 KiB between {@link CodeEdges#TAB_END} and
     * {@code Loader.CODE_ROOTS}; 16,384 entries covers the observed peak of ~14,305 blocks.
     *
     * <p>This was first placed at 0x030A_0000 -- **exactly on top of CodeEdges.TAB** -- and the plan pass
     * overwrote the census with {start,newBase} pairs, so sites became block addresses whose words are not
     * branches. It showed up as `ok=731 -> 18, reused=0 -> 3118` against an isolation build, NOT as a fault.
     * Fourth occurrence of this bug class in one session (STUB_TAB over STATS/STALE_TAB/FREED_RANGES, a
     * near-miss into core 1's stack at 0x0380_0000, STATS left uninitialised because a neighbour scribbled
     * it). Check a candidate against EVERY table in the 0x0300_0000+ band, not just the nearest one.
     */
    static final long PLAN_TAB = 0x030C_0000L;        // package-visible so ScratchMap can register it
    private static final long PLAN_CAP = 16384L;

    /** Entries written by the last {@link #plan}. */
    static long planN;
    /** 0 if the walk met an address the registry does not describe, or overflowed the table. */
    static long planOk;

    static long blocksLive;
    static long blocksFree;
    static long blocksMoved;
    static long bytesLive;
    static long bytesFree;
    /** Bytes the arena top would drop by. */
    static long recovered;
    /** Largest distance any one block would move. */
    static long maxDelta;

    static long edgesMappable;
    static long edgesUnmappable;
    static long stackRefs;

    /** Arena top at plan time -- the end of the last walked block. */
    private static long arenaTop;

    /**
     * Walk the arena in address order and assign every live block the base it would slide down to.
     * Free blocks get newBase 0, which {@link #newAddrOf} reports as unmapped.
     */
    static void plan()
    {
        Heap.indexBlocksForWalk();
        planN = 0L;
        planOk = 1L;
        blocksLive = 0L;
        blocksFree = 0L;
        blocksMoved = 0L;
        bytesLive = 0L;
        bytesFree = 0L;
        maxDelta = 0L;
        arenaTop = Magic.load64(Heap.CODE_PTR_CELL);
        long cursor = Heap.CODE_BASE;
        long dst = Heap.CODE_BASE;
        while (cursor < arenaTop)
        {
            long i = Heap.blockIndexAt(cursor);
            if (i < 0L || planN >= PLAN_CAP)
            {
                planOk = 0L;                             // unmapped address, or more blocks than the table holds
                break;
            }
            long usable = Magic.load64(Heap.CODE_BLOCKS + i * 16L + 8L);
            dst = record(cursor, usable, dst);
            cursor += usable & -8L;
        }
        recovered = arenaTop - dst;
    }

    /** Record one walked block and return the next destination cursor. Split out to stay under the local cap. */
    private static long record(long start, long sz, long dst)
    {
        long usable = sz & -8L;
        long e = PLAN_TAB + planN * 16L;
        Magic.store64(e, start);
        if ((sz & Heap.CODE_FREE) != 0L)
        {
            Magic.store64(e + 8L, 0L);
            blocksFree += 1L;
            bytesFree += usable;
            planN += 1L;
            return dst;
        }
        Magic.store64(e + 8L, dst);
        if (dst != start)
        {
            blocksMoved += 1L;
            if (start - dst > maxDelta)
            {
                maxDelta = start - dst;
            }
        }
        blocksLive += 1L;
        bytesLive += usable;
        planN += 1L;
        return dst + usable;
    }

    /**
     * Where {@code addr} would end up, or 0 if the plan does not cover it (no block, or a free one).
     * Binary search: the table is in address order by construction, and blocks tile the arena, so a block's
     * extent runs to the next entry's start.
     */
    private static long newAddrOf(long addr)
    {
        long lo = 0L;
        long hi = planN;
        while (lo + 1L < hi)                             // greatest k with start[k] <= addr
        {
            long mid = (lo + hi) >> 1;
            if (Magic.load64(PLAN_TAB + mid * 16L) <= addr)
            {
                lo = mid;
            }
            else
            {
                hi = mid;
            }
        }
        if (planN == 0L)
        {
            return 0L;
        }
        long start = Magic.load64(PLAN_TAB + lo * 16L);
        long end = lo + 1L < planN ? Magic.load64(PLAN_TAB + (lo + 1L) * 16L) : arenaTop;
        if (addr < start || addr >= end)
        {
            return 0L;
        }
        long nb = Magic.load64(PLAN_TAB + lo * 16L + 8L);
        return nb == 0L ? 0L : nb + (addr - start);
    }

    /** Every live census edge must have both ends inside a block the plan covers. */
    private static void verifyEdges()
    {
        edgesMappable = 0L;
        edgesUnmappable = 0L;
        long i = 0L;
        while (i < CodeEdges.n)
        {
            long site = Magic.load64(CodeEdges.TAB + i * 16L);
            long want = Magic.load64(CodeEdges.TAB + i * 16L + 8L);
            if (site != 0L && want != 0L)
            {
                if (newAddrOf(site) != 0L && newAddrOf(want) != 0L)
                {
                    edgesMappable += 1L;
                }
                else
                {
                    edgesUnmappable += 1L;
                }
            }
            i += 1L;
        }
    }

    /**
     * Conservatively count words in {@code [lo,hi)} that point into a block the plan would MOVE -- return
     * addresses the mover would have to rewrite. Uses the SP from the last collection, which is exactly when
     * compaction would run.
     */
    private static void countStackRefs(long lo, long hi)
    {
        stackRefs = 0L;
        if (lo == 0L || lo >= hi)
        {
            return;
        }
        long p = lo;
        while (p < hi)
        {
            long w = Magic.load64(p);
            if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
            {
                long nw = newAddrOf(w);
                if (nw != 0L && nw != w)
                {
                    stackRefs += 1L;
                }
            }
            p += 8L;
        }
    }

    /** One line: what compaction would move, what it would recover, and whether every reference maps. */
    static void report()
    {
        plan();
        verifyEdges();
        countStackRefs(VMGc.lastScanFrom, VM.STACK_TOP);
        Uart.write(Magic.bytes("  compactPlan ok="));
        VM.printDec((int) planOk);
        Uart.write(Magic.bytes(" live="));
        VM.printDec((int) blocksLive);
        Uart.write(Magic.bytes(" free="));
        VM.printDec((int) blocksFree);
        Uart.write(Magic.bytes(" moved="));
        VM.printDec((int) blocksMoved);
        Uart.write(Magic.bytes(" recovered="));
        VM.printHex(recovered);
        Uart.write(Magic.bytes(" maxDelta="));
        VM.printHex(maxDelta);
        Uart.write(Magic.bytes(" edgesOk="));
        VM.printDec((int) edgesMappable);
        Uart.write(Magic.bytes(" UNMAPPED="));
        VM.printDec((int) edgesUnmappable);
        Uart.write(Magic.bytes(" stackRefs="));
        VM.printDec((int) stackRefs);
        Uart.putc(0x0A);
    }
}
