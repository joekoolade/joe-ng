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
     * Off by default, like {@code RECLAIM_CODE_BY_GC} was. Moving code is the first change in this arc that
     * can corrupt a RUNNING VM, so enabling it is a configuration change rather than a rebuild-and-pray.
     */
    static final boolean COMPACT_CODE = false;

    /**
     * A/B switch for the TIB-slot class, left OFF because it was MEASURED worthless: at a fixed plan point
     * it explained 171 of 144,158 unexplained references -- 0.12%. TIB vtables are not where the references
     * are.
     *
     * <p><b>DANGER, and the reason this cannot simply be turned on.</b> {@link #explained} means "a slot we
     * would rewrite", and {@link #move} rewrites ONLY census edges. Explaining a class without also
     * rewriting it unpins blocks whose references then dangle -- #146, caused deliberately. Enumeration and
     * rewriting must land in the same increment, for every class.
     */
    static final boolean ENUM_TIB = false;

    /** A/B switch for the dispatch-cell class, so its contribution is measured against the FIXED harness. */
    static final boolean ENUM_CELL = false;

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
    /** newBase sentinels: 0 = free block, LIVE = live and unplaced, IMMOVABLE = live and pinned. */
    private static final long LIVE = -1L;
    private static final long IMMOVABLE = -2L;

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

    /**
     * Conservative hits that a PRECISE enumeration can name and would rewrite, versus those it cannot.
     *
     * <p>This is how "is the enumeration complete?" becomes a counted quantity rather than an assertion --
     * the same move as {@code UNMAPPED} and {@code DANGLING}. The conservative scan finds every candidate
     * slot; enumeration has to explain them. Only an UNEXPLAINED slot pins its block, so `unexplainedRefs`
     * falling to 0 is exactly the condition under which the mover becomes generally useful.
     */
    static long explainedRefs;
    static long unexplainedRefs;

    /**
     * Composition of the conservative hits -- the measurement that says whether a tighter reference test can
     * pay. A hit is only a plausible live reference if it is 4-aligned (A64 code addresses always are) AND
     * lands inside an ALLOCATED block (a value pointing into swept or unregistered space cannot be followed).
     * Those two filters are sound with no judgement required. The entry/interior split is the interesting
     * one: every table that holds a code address -- STUB_TAB, TIB slots, dispatch cells, rgTab -- holds an
     * ENTRY; only return addresses point into the middle of a method, and those live on the stack.
     */
    static long refsUnaligned;
    static long refsNoBlock;
    static long refsDead;
    static long refsEntry;
    static long refsInterior;

    /** Live blocks something OTHER than a census edge might point at -- they must not move. */
    static long immovable;
    /** Live blocks the safe rule permits moving, and the bytes that would recover. */
    static long movable;
    static long safeRecovered;
    /** Set by {@link #move}: edges rewritten, and edges that failed to verify afterwards (must be 0). */
    static long edgesPatched;
    static long verifyFailed;

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
        markImmovable();                                 // pin anything a non-edge reference points at
        assign();                                        // ... then slide only what is left
        verifyEdges();
        countStackRefs(VMGc.lastScanFrom, VM.STACK_TOP);
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
        Magic.store64(e + 8L, LIVE);                     // marked live; assign() fills the real destination
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
        if (nb == 0L)
        {
            return 0L;                                   // free block: nothing lives here
        }
        if (nb == IMMOVABLE)
        {
            return addr;                                 // pinned: stays exactly where it is
        }
        return nb + (addr - start);
    }

    /**
     * Slide every movable block down and rewrite the census edges. Gated on {@link #COMPACT_CODE}.
     *
     * <p>Order matters: bytes first (ascending -- a destination is always BELOW its source, and the block
     * that used to occupy it has already been vacated), then edges, then the registry, then the bump
     * pointer, then an I-cache publish over the disturbed span. Verification is last and halts on failure,
     * because a mis-rewritten branch is not something to discover later.
     */
    static void move()
    {
        if (COMPACT_CODE == false)
        {
            return;
        }
        plan();
        if (planOk == 0L || movable == 0L)
        {
            return;
        }
        copyBlocks();
        patchEdges();
        rewriteHolders();   // enumeration and rewriting land together, always
        long i = 0L;
        while (i < planN)                                // registry: a block's start is now its destination
        {
            long nb = Magic.load64(PLAN_TAB + i * 16L + 8L);
            if (nb != 0L && nb != IMMOVABLE)
            {
                long k = Heap.blockIndexAt(Magic.load64(PLAN_TAB + i * 16L));
                if (k >= 0L)
                {
                    Magic.store64(Heap.CODE_BLOCKS + k * 16L, nb);
                }
                Heap.pinCodeAt(nb);                      // re-pin at the new address (stale bits are safe)
            }
            i += 1L;
        }
        Magic.store64(Heap.CODE_PTR_CELL, arenaTop - safeRecovered);
        Heap.publishCode(Heap.CODE_BASE, arenaTop);
        verifyAfter();
        if (verifyFailed != 0L)
        {
            Uart.write(Magic.bytes("COMPACT VERIFY FAILED n="));
            VM.printDec((int) verifyFailed);
            Uart.putc(0x0A);
            for (;;)
            {
                Magic.wfe();
            }
        }
    }

    /** Copy each movable block's bytes to its destination, in address order (dst is always below src). */
    private static void copyBlocks()
    {
        long i = 0L;
        while (i < planN)
        {
            long e = PLAN_TAB + i * 16L;
            long start = Magic.load64(e);
            long nb = Magic.load64(e + 8L);
            long end = i + 1L < planN ? Magic.load64(PLAN_TAB + (i + 1L) * 16L) : arenaTop;
            if (nb != 0L && nb != IMMOVABLE && nb != start)
            {
                long p = 0L;
                while (start + p < end)
                {
                    Magic.store64(nb + p, Magic.load64(start + p));
                    p += 8L;
                }
            }
            i += 1L;
        }
    }

    /** Rewrite every live edge at its NEW site to reach its NEW target, and update the census in place. */
    private static void patchEdges()
    {
        edgesPatched = 0L;
        long i = 0L;
        while (i < CodeEdges.n)
        {
            long e = CodeEdges.TAB + i * 16L;
            long site = Magic.load64(e);
            long want = Magic.load64(e + 8L);
            if (site != 0L && want != 0L)
            {
                rewrite(newAddrOf(site), newAddrOf(want), e);
            }
            i += 1L;
        }
    }

    /** Re-encode the branch now sitting at {@code ns} to reach {@code nt}, preserving bl vs b. */
    private static void rewrite(long ns, long nt, long e)
    {
        if (ns == 0L || nt == 0L)
        {
            return;
        }
        int w = Magic.load32(ns);
        int op = w >>> 26;
        int off = (int) ((nt - ns) >> 2);
        if (op == 0x25)
        {
            Magic.store32(ns, asm.A64Enc.bl(off));
        }
        else if (op == 0x05)
        {
            Magic.store32(ns, asm.A64Enc.b(off));
        }
        Magic.store64(e, ns);
        Magic.store64(e + 8L, nt);
        edgesPatched += 1L;
    }

    /** Target of the bl/b at {@code a}, or 0 if that word is not one. Mirrors {@code CodeEdges.decodeTarget}. */
    private static long branchTargetAt(long a)
    {
        int w = Magic.load32(a);
        int op = w >>> 26;
        if (op != 0x25 && op != 0x05)
        {
            return 0L;
        }
        int imm = w & 0x03FFFFFF;
        if ((imm & 0x02000000) != 0)
        {
            imm = imm | 0xFC000000;
        }
        return a + (long) imm * 4L;
    }

    /** After the move the census holds NEW addresses -- every live site must decode to its recorded target. */
    private static void verifyAfter()
    {
        verifyFailed = 0L;
        long i = 0L;
        while (i < CodeEdges.n)
        {
            long site = Magic.load64(CodeEdges.TAB + i * 16L);
            long want = Magic.load64(CodeEdges.TAB + i * 16L + 8L);
            if (site != 0L && want != 0L && branchTargetAt(site) != want)
            {
                verifyFailed += 1L;
            }
            i += 1L;
        }
    }

    /**
     * Mark every live block that a word ANYWHERE outside the code arena appears to point at.
     *
     * <p>This is the rule that makes the mover sound without a precise pointer map: a block may move only
     * if the ONLY references to it are census edges, which we can rewrite exactly. Anything else -- a TIB
     * vtable slot, a dispatch cell, an itable entry, a registry row, a JIT unwind range, a return address
     * on the stack -- pins it where it is. The scan is CONSERVATIVE, so a `long` field that merely looks
     * like a code address also pins a block; that costs recovery, never correctness.
     *
     * <p><b>The ranges below are the safety argument.</b> A code pointer living somewhere not scanned here
     * would let a referenced block move, and the reference would then point at whatever landed in its place
     * -- the #146 failure, caused deliberately. Do not enable {@link #COMPACT_CODE} without re-deriving this
     * list against every writer of a code address.
     */
    private static void markImmovable()
    {
        immovable = 0L;
        explainedRefs = 0L;
        unexplainedRefs = 0L;
        refsUnaligned = 0L;
        refsNoBlock = 0L;
        refsDead = 0L;
        refsEntry = 0L;
        refsInterior = 0L;
        scanRange(Heap.BASE, Magic.load64(Heap.PTR_CELL));            // data heap: TIBs, cells, itables
        scanRange(Heap.LARGE_BASE, Magic.load64(Heap.LARGE_PTR_CELL));// large-object region
        scanRange(VM.staticsStart, VM.staticsEnd);                    // image statics
        scanRange(VMGc.lastScanFrom, VM.STACK_TOP);                   // stack: return addresses
        scanRange(Heap.JIT_TABLES, Heap.JIT_TABLES + 0x50000L);       // JIT frame/handler/local tables
        scanRange(Loader.CODE_ROOTS, Loader.CODE_ROOTS_END);          // per-buffer baked heap roots
        scanRange(Loader.STUB_TAB, CodeEdges.TAB);                    // deferral-stub table
    }

    /**
     * 1 if {@code slot} belongs to a structure whose layout we know well enough to rewrite in place.
     *
     * <p>Enumerated so far: {@code Loader.STUB_TAB}, whose rows are {@code {buf, idx}} at a 16-byte stride --
     * a code address lives at every offset 0 (mod 16) below {@code stubTabN}. Roughly 10k deferral stubs are
     * registered there, which is why it is first.
     *
     * <p>Still unenumerated, and therefore still pinning: TIB vtable slots, phase-A dispatch cells, itable
     * entries, {@code rgTab}/{@code clinitEntry} rows, JIT unwind ranges, and return addresses on the stack.
     * The stack is the one class that can never be enumerated precisely -- those blocks stay pinned by
     * construction, which is fine: hardware measured only 3 such references.
     */
    private static int explained(long slot)
    {
        if (slot >= Loader.STUB_TAB && slot < Loader.STUB_TAB + Loader.stubTabN * 16L)
        {
            return (slot - Loader.STUB_TAB) % 16L == 0L ? 1 : 0;
        }
        if (ENUM_CELL && explainedCell(slot) != 0)
        {
            return 1;
        }
        return ENUM_TIB ? explainedTib(slot) : 0;
    }

    /**
     * 1 if {@code slot} is a lazy method's dispatch CELL -- one 8-byte heap word holding first the deferral
     * stub, then the compiled buffer after first call ({@code Loader.lazyCellAt}). Entry pointers, one per
     * lazy method, and {@link #rewriteHolders} patches them, so explaining them is sound.
     *
     * <p>Linear over {@code lzN} per arena-pointing word, which is the honest cost of finding out whether
     * this class is worth optimising for. If it pays, the lookup becomes a sorted table.
     */
    private static int explainedCell(long slot)
    {
        int n = Loader.lazyCount();
        int i = 0;
        while (i < n)
        {
            if (Loader.lazyCellAt(i) == slot)
            {
                return 1;
            }
            i += 1;
        }
        return 0;
    }

    /**
     * Rewrite every ENUMERATED holder to its block's new address. Must cover exactly the classes
     * {@link #explained} claims, or a block gets unpinned whose reference then dangles -- #146 on purpose.
     * Called from {@link #move} after the bytes have been copied.
     */
    private static void rewriteHolders()
    {
        long k = 0L;
        while (k < Loader.stubTabN)                      // STUB_TAB rows: {buf, idx} at a 16-byte stride
        {
            long e = Loader.STUB_TAB + k * 16L;
            long na = newAddrOf(Magic.load64(e));
            if (na != 0L)
            {
                Magic.store64(e, na);
            }
            k += 1L;
        }
        int n = Loader.lazyCount();
        int i = 0;
        while (i < n)
        {
            long cell = Loader.lazyCellAt(i);
            if (cell != 0L)
            {
                long na = newAddrOf(Magic.load64(cell));
                if (na != 0L)
                {
                    Magic.store64(cell, na);
                }
            }
            i += 1;
        }
    }

    /** Total enumerable TIB vtable slots -- 0 means the registry is empty when the report runs. */
    private static long tibSlots()
    {
        long t = 0L;
        int n = Loader.classRegCount();
        int i = 0;
        while (i < n)
        {
            RVMClass c = Loader.classRegAt(i);
            if (c != null && c.tib != 0L)
            {
                t += c.vtCount;
            }
            i += 1;
        }
        return t;
    }

    /**
     * 1 if {@code slot} is a vtable entry in some registered class's TIB.
     *
     * <p>TIB layout is {@code [0] Type, [1..] vtable}, so slot k of a class with {@code vtCount} virtuals
     * lives at {@code tib + 8 + k*8}. Every one holds a code address and every one is rewritable in place,
     * which makes this the second-largest tractable class after {@code STUB_TAB}.
     *
     * <p>Linear over the class registry (~160 records) per arena-pointing word. That is ~24M comparisons a
     * report, which the measurement can afford; a sorted range table would be the optimisation if the mover
     * ever runs for real.
     */
    private static int explainedTib(long slot)
    {
        int n = Loader.classRegCount();
        int i = 0;
        while (i < n)
        {
            RVMClass c = Loader.classRegAt(i);
            if (c != null && c.tib != 0L && slot >= c.tib + 8L && slot < c.tib + 8L + (long) c.vtCount * 8L)
            {
                return 1;
            }
            i += 1;
        }
        return 0;
    }

    /**
     * Decide what one arena-valued word at {@code slot} actually is, and pin only if it could be a live
     * reference. The two filters applied before pinning are sound without judgement: a non-4-aligned value
     * is not an A64 code address, and a value in a free or unregistered block points at nothing that can be
     * called. Entry and interior hits still pin -- the split is recorded so the next tightening can be
     * argued from data instead of intuition.
     */
    private static void classify(long slot, long w)
    {
        if ((w & 3L) != 0L)
        {
            refsUnaligned += 1L;                         // not an instruction address: cannot be a reference
            return;
        }
        long k = indexOf(w);
        if (k < 0L)
        {
            refsNoBlock += 1L;                           // inside no registered block
            return;
        }
        if (Magic.load64(PLAN_TAB + k * 16L + 8L) == 0L)
        {
            refsDead += 1L;                              // inside a block the sweep freed
            return;
        }
        if (Magic.load64(PLAN_TAB + k * 16L) == w)
        {
            refsEntry += 1L;
        }
        else
        {
            refsInterior += 1L;
        }
        if (explained(slot) != 0)
        {
            explainedRefs += 1L;                         // a slot we can name and would rewrite: no pin
            return;
        }
        unexplainedRefs += 1L;
        pin(w);
    }

    /** Pin every live block a word in {@code [lo,hi)} points into. */
    private static void scanRange(long lo, long hi)
    {
        if (lo == 0L || hi <= lo)
        {
            return;
        }
        long p = lo;
        while (p < hi)
        {
            long w = Magic.load64(p);
            if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
            {
                classify(p, w);
            }
            p += 8L;
        }
    }

    /** Mark the live block containing {@code addr} immovable (newBase sentinel -1). */
    private static void pin(long addr)
    {
        long k = indexOf(addr);
        if (k < 0L)
        {
            return;
        }
        long e = PLAN_TAB + k * 16L;
        long nb = Magic.load64(e + 8L);
        if (nb == LIVE)                                  // live and not yet pinned
        {
            Magic.store64(e + 8L, IMMOVABLE);
            immovable += 1L;
        }
    }

    /** Index of the plan entry whose block contains {@code addr}, or -1. */
    private static long indexOf(long addr)
    {
        long lo = 0L;
        long hi = planN;
        if (planN == 0L)
        {
            return -1L;
        }
        while (lo + 1L < hi)
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
        long start = Magic.load64(PLAN_TAB + lo * 16L);
        long end = lo + 1L < planN ? Magic.load64(PLAN_TAB + (lo + 1L) * 16L) : arenaTop;
        return addr >= start && addr < end ? lo : -1L;
    }

    /**
     * Second pass: give every MOVABLE block the address it slides down to. Immovable blocks anchor the
     * destination cursor where they sit, so a movable block only ever moves into free space genuinely below
     * it and never over a pinned neighbour.
     */
    private static void assign()
    {
        movable = 0L;
        long dst = Heap.CODE_BASE;
        long i = 0L;
        while (i < planN)
        {
            long e = PLAN_TAB + i * 16L;
            long start = Magic.load64(e);
            long end = i + 1L < planN ? Magic.load64(PLAN_TAB + (i + 1L) * 16L) : arenaTop;
            long nb = Magic.load64(e + 8L);
            if (nb == IMMOVABLE)
            {
                dst = end;                               // pinned: anchors the cursor past itself
            }
            else if (nb == LIVE)
            {
                Magic.store64(e + 8L, dst);
                if (dst != start)
                {
                    movable += 1L;
                }
                dst += end - start;
            }
            i += 1L;
        }
        safeRecovered = arenaTop - dst;
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
        Uart.write(Magic.bytes(" pinned="));
        VM.printDec((int) immovable);
        Uart.write(Magic.bytes(" MOVABLE="));
        VM.printDec((int) movable);
        Uart.write(Magic.bytes(" safeRecover="));
        VM.printHex(safeRecovered);
        Uart.write(Magic.bytes(" refsOk="));
        VM.printDec((int) explainedRefs);
        Uart.write(Magic.bytes(" refsUNKNOWN="));
        VM.printDec((int) unexplainedRefs);
        Uart.write(Magic.bytes(" unal="));
        VM.printDec((int) refsUnaligned);
        Uart.write(Magic.bytes(" noblk="));
        VM.printDec((int) refsNoBlock);
        Uart.write(Magic.bytes(" dead="));
        VM.printDec((int) refsDead);
        Uart.write(Magic.bytes(" entry="));
        VM.printDec((int) refsEntry);
        Uart.write(Magic.bytes(" interior="));
        VM.printDec((int) refsInterior);
        Uart.write(Magic.bytes(" lazyN="));
        VM.printDec(Loader.lazyCount());
        Uart.write(Magic.bytes(" classes="));
        VM.printDec(Loader.classRegCount());
        Uart.write(Magic.bytes(" tibSlots="));
        VM.printDec((int) tibSlots());
        Uart.putc(0x0A);
    }
}
