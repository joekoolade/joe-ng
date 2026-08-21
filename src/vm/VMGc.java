package vm;

import magic.Magic;
import board.bcm2711.Uart;
import objectmodel.ObjectModel;
import static vm.VM.*;   // GC roots + shared state stay in VM: the task table (stale-root reaping),
                         // staticsStart/End, STACK_TOP, gcLog/reclaimed, and printHex — reached by simple name.

/**
 * Conservative mark-sweep garbage collector, extracted verbatim from VM.java to shrink it. Entered via
 * {@code Magic.gc()} (the compiler spills x19..x28 so callee-saved refs are on the scannable stack), it
 * reaps dead-parked tasks, builds a block-start bitmap, marks from the roots (stack, statics, secondary
 * arenas) to a fixpoint, then sweeps unmarked blocks onto {@link Heap}'s free list. Objects are not moved
 * (no precise stack maps); may over-retain via false roots. All mutable state it touches (the task table,
 * the statics bounds, {@code gcLog}/{@code reclaimed}) stays in {@link VM} and is reached via
 * {@code import static}; only the collector code lives here. The {@code gcCollect} address the JIT BLs is
 * still the {@code vm/VM.gcCollect} field (writer-stashed); this class only owns the method body.
 */
final class VMGc
{

    /**
     * Conservative mark-sweep, entered via {@code Magic.gc()} (which spills the
     * callee-saved registers so live references there are on the stack). Roots are
     * the stack [{@code scanFrom}, STACK_TOP) and the statics region; anything
     * transitively reachable survives, everything else is swept onto the free list.
     * Object sizes come from the status word — no per-type maps, and objects aren't
     * moved (so no precise stack maps are needed). May over-retain (false roots).
     */
    static void gcCollect(long scanFrom)
    {
        long daif = Magic.readDaif();                 // no preemption mid-collection: a switched-in task
        Magic.disableIrq();                           //   would allocate into the half-swept heap
        probes = 0L;                                  // precision metrics for this collection (gcLog)
        nomap = 0L;
        markSp = MARK_STACK;                          // the trace worklist starts empty
        markOverflow = 0;
        // Stale-root hygiene: reap dead-parked tasks BEFORE marking. A task that ran taskExit (BLOCKED on
        // the reserved dead semaphore) can never be rescheduled -- pickNext skips BLOCKED tasks -- so its
        // 32 KB heap stack, saved context, and guest Thread object are garbage; as roots they retained
        // everything their final frames happened to reference. Clearing the table entries makes the stack
        // object unreachable, so THIS collection sweeps it.
        int reaped = 0;
        if (taskState != null)
        {
            int t = 1;                                // task 0 is the boot flow; it never exits
            while (t < taskCount)
            {
                if (taskState[t] == TASK_BLOCKED && taskWaitOn[t] == 3 && taskStackBase[t] != 0L)
                {
                    taskStackBase[t] = 0L;
                    taskSp[t] = 0L;
                    taskThreadObj[t] = 0L;
                    taskState[t] = TASK_EMPTY;
                    reaped += 1;
                }
                t += 1;
            }
        }
        long cz = CODE_BITMAP;                        // clear the code-reachability bitmap for this pass
        while (cz < CODE_BITMAP_END)
        {
            Magic.store64(cz, 0L);
            cz += 8L;
        }
        buildBlockBitmap(Magic.load64(Heap.PTR_CELL));     // pre-pass: exact block bases for the probes
        long stackTop = STACK_TOP;                    // boot task: SP runs down from the image stack top
        if (taskStackBase != null && curTask != 0 && taskStackBase[curTask] != 0L)
        {
            stackTop = taskStackBase[curTask] + 0x8000L;   // a spawned task: its stack is a heap object
        }
        markRange(scanFrom, stackTop);
        markRange(staticsStart, staticsEnd);
        int sc = 1;                                   // secondary cores' arenas are ROOT RANGES (never
        while (sc < 4)                                //   collected, but their tasks hold refs into core 0's
        {                                             //   heap — e.g. spawned Runnable receivers)
            markRange(Heap.arenaBase(sc), Magic.load64(Heap.PTR_CELL + sc * 8L));
            sc += 1;
        }
        rootProbes = probes;                          // split the metric: everything above is ROOT scanning
        drainMarkStack();                             // trace: scan each newly marked block exactly ONCE
        codeOnly = markCodeRoots();                   // ... then the addresses only compiled code holds
        drainMarkStack();
        measureCodeLiveness();                        // how much compiled code is still reachable at all                             // (counted AFTER the ordinary trace, so the count is
                                                      //  exactly "what nothing else kept alive")
        if (markOverflow != 0)
        {
            traceFixpoint();                          // stack ran out: fall back to re-scanning the heap
        }
        Heap.resetFreeList();                          // sweep
        reclaimed = 0L;
        liveBytes = 0L;
        long walked = 0L;
        long markedN = 0L;
        long freedN = 0L;
        long o = Heap.BASE;
        long stop = liveEndOf(Magic.load64(Heap.PTR_CELL));   // everything above the highest survivor is
        trimmed = Magic.load64(Heap.PTR_CELL) - stop;         //   garbage in one contiguous run: give it
        reclaimed = reclaimed + trimmed;                      //   back by lowering the bump pointer, not
        Magic.store64(Heap.PTR_CELL, stop);                   //   by threading it onto the free list
        long stoppedAt = 0L;
        long runStart = 0L;                            // a run of adjacent dead blocks, merged when it ends
        long runSize = 0L;
        while (o < stop)
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L || o + size > stop || o + size <= o)
            {
                stoppedAt = o;                         // corrupt / out-of-bounds: stop
                o = stop;
            }
            else
            {
                walked = walked + 1L;
                if ((st & 1L) != 0L)
                {
                    if (runSize != 0L)                     // a survivor ends the run of dead blocks
                    {
                        Heap.addFree(runStart, runSize);
                        runStart = 0L;
                        runSize = 0L;
                    }
                    markedN = markedN + 1L;
                    liveBytes = liveBytes + size;   // what SURVIVED: the number that says whether a high heap
                    Magic.store64(o + 8L, size);    //   water mark is retention or just uncollected garbage
                }
                else
                {
                    freedN = freedN + 1L;
                    reclaimed = reclaimed + size;
                    if (runSize == 0L)                     // COALESCE: adjacent dead blocks become ONE free
                    {                                      //   block. Splitting (Heap.allocLocked) without
                        runStart = o;                      //   this would grind the heap into fragments too
                    }                                      //   small to serve anything, since the sweep
                    runSize = runSize + size;              //   rebuilt the list one dead block at a time.
                }
                o = o + size;
            }
        }
        if (runSize != 0L)                             // the heap ended on dead blocks
        {
            Heap.addFree(runStart, runSize);
        }
        if (gcLog != 0)
        {
            Uart.write(Magic.bytes("  [gc walked="));
            printHex(walked);
            Uart.write(Magic.bytes(" marked="));
            printHex(markedN);
            Uart.write(Magic.bytes(" freed="));
            printHex(freedN);
            Uart.write(Magic.bytes(" bytes="));
            printHex(reclaimed);
            Uart.write(Magic.bytes(" probes="));
            printHex(probes);                          // candidate words examined: the precision metric
            Uart.write(Magic.bytes(" roots="));
            printHex(rootProbes);                      // ... of which the (irreducibly conservative) roots
            Uart.write(Magic.bytes(" heap="));
            printHex(probes - rootProbes);             // ... and the TRACE side, which type metadata shrinks
            Uart.write(Magic.bytes(" codeFreed="));
            printHex(codeFreed);                       // unreachable code returned to the free list
            Uart.write(Magic.bytes(" codeLive="));
            printHex(codeLive);                        // reachable compiled code ...
            Uart.putc(0x2F);
            printHex(codeUsed);                        // ... of the total ever compiled
            Uart.write(Magic.bytes(" trimmed="));
            printHex(trimmed);                         // handed back by lowering the bump pointer
            Uart.write(Magic.bytes(" codeOnly="));
            printHex(codeOnly);                        // blocks only a code immediate kept alive
            Uart.write(Magic.bytes(" live="));
            printHex(liveBytes);                       // survivors: retention vs uncollected garbage
            Uart.write(Magic.bytes(" nomap="));
            printHex(nomap);                           // blocks still scanned conservatively
            Uart.write(Magic.bytes(" reaped="));
            printHex(reaped);                          // dead-parked tasks whose stacks this collection freed
            Uart.write(Magic.bytes(" stopAt="));
            printHex(stoppedAt);                       // non-zero = the size walk hit a corrupt status
            Uart.write(Magic.bytes("]\n"));
        }
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();                        // restore: only unmask if the caller had IRQs on
        }
    }

    /** Candidate words examined by the LAST collection. Conservative scanning probes every payload word of
     *  every marked block; type metadata lets whole payloads be skipped, and this counter is the evidence.
     *  Read by {@link Loader#launch} so a launched program reports it without the {@code gcLog} flag. */
    static long probes;

    /** Lowest address a TIB or Type pointer can have (the image loads at 0x80000); below it a header word
     *  is a raw array's element size or an untyped block's zero, never a pointer to dereference. */
    private static final long META_LOW  = 0x0008_0000L;
    /** One past the highest: core 0's arena ends where core 1's begins ({@code Heap.arenaLimit(0)}), and
     *  TIBs only ever live in the image or in core 0's heap. Anything else is garbage -> stay conservative. */
    private static final long META_HIGH = 0x1000_0000L;

    /**
     * Scan one marked block's payload for references, using the block's own TYPE METADATA where it has any.
     * Three shapes live in this heap and the header word at +0 tells them apart: 0 = a raw {@link
     * Heap#allocData} struct (Type, TIB, itable, statics block, classfile copy) with no type at all;
     * {@code <= MAX_RAW_ARRAY_TIB} = a raw array whose header holds its ELEMENT SIZE; anything else = a
     * pointer to a TIB whose slot 0 is the Type. Only arrays are precise in this increment — a scalar
     * object's per-field reference map lands in the next one, so scalars stay conservative.
     */
    private static boolean scanBlock(long o, long size)
    {
        long tibw = Magic.load64(o + ObjectModel.TIB_OFFSET);
        if (tibw != 0L && tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return scanArray(o, size, tibw);           // raw array: the header word IS the element size
        }
        if (tibw >= META_LOW && tibw < META_HIGH && (tibw & 7L) == 0L)
        {
            long type = Magic.load64(tibw + ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD);
            if (type >= META_LOW && type < META_HIGH && (type & 7L) == 0L)
            {
                long isz = Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET);
                if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
                {
                    return scanArray(o, size, isz & 0xFFFFL);   // typed array: element size in the tag
                }
                long map = Magic.load64(type + ObjectModel.TYPE_REFMAP_OFFSET);
                if ((map & 1L) != 0L)                           // bit 0 = the class's map was computed
                {
                    return scanMapped(o, size, map,
                                      Magic.load64(type + ObjectModel.TYPE_REFMAP_OFFSET + 8L));
                }
            }
        }
        nomap = nomap + 1L;                                        // the remaining conservative surface
        return markRange(o + ObjectModel.HEADER_SIZE, o + size);   // unmapped scalar / untyped block
    }

    /**
     * An array block. Elements narrower than a word CANNOT hold a reference, so a {@code byte[]}/{@code
     * char[]}/{@code int[]} payload is skipped entirely instead of being probed word by word (a 64 KiB
     * byte[] cost 8192 probes per trace round). Word-wide elements are scanned over the ELEMENT range only.
     * Element size alone decides: a {@code long[]} is scanned like an {@code Object[]}, because an array
     * Type's element-Type slot is 0 both for a primitive element and for a reference element the loader
     * could not resolve — sound over precise until that distinction is verified.
     */
    private static boolean scanArray(long o, long size, long elemSize)
    {
        if (elemSize < (long) ObjectModel.WORD)
        {
            return false;
        }
        long end = o + ObjectModel.ARRAY_BASE_OFFSET
                 + Magic.load64(o + ObjectModel.ARRAY_LENGTH_OFFSET) * (long) ObjectModel.WORD;
        if (end > o + size || end < o)
        {
            end = o + size;                            // a garbled length word: trust the block size instead
        }
        return markRange(o + ObjectModel.ARRAY_BASE_OFFSET, end);
    }

    /**
     * A scalar object whose class published a reference map: probe ONLY the slots the map names, skipping
     * every {@code int}/{@code char}/{@code boolean}/{@code float}/{@code double} field. That is where the
     * precision comes from — an {@code int} field holding a size, an offset or a hash that happens to land
     * in [Heap.BASE, heapTop) used to retain a dead object, and now cannot.
     *
     * <p>Bits describe slots, not bytes: bit {@code 1+i} covers the field at {@code +16 + i*8}. Bits past
     * the block's own slot count are ignored, so a first-fit block bigger than its object is safe, and the
     * {@code w-16} probe is kept because a mapped {@code long} field routinely holds an {@link
     * Heap#allocData} PAYLOAD address whose block base sits one header earlier.
     */
    private static boolean scanMapped(long o, long size, long w0, long w1)
    {
        boolean any = false;
        long slots = (size - (long) ObjectModel.HEADER_SIZE) >> 3;
        long i = 0;
        while (i < slots && i <= (long) ObjectModel.TYPE_REFMAP_MAX_SLOT)
        {
            long bit = i < 63L ? (w0 >>> (int) (i + 1L)) : (w1 >>> (int) (i - 63L));
            if ((bit & 1L) != 0L)
            {
                probes = probes + 1L;
                long w = Magic.load64(o + (long) ObjectModel.HEADER_SIZE + i * 8L);
                if (tryMark(w))
                {
                    any = true;
                }
                if (tryMark(w - 16L))
                {
                    any = true;
                }
            }
            i += 1;
        }
        return any;
    }

    /** Marked blocks the last collection had to scan conservatively — no Type, or a Type with no map. The
     *  metric increment 3 (a kind tag for raw {@code allocData} structs) is aimed at. */
    static long nomap;

    /**
     * Mark the heap addresses compiled code carries as {@code MOVZ}/{@code MOVK} immediates, which no scan
     * of memory can find (see {@link Loader#CODE_ROOTS}). Runs AFTER the ordinary trace has drained, so a
     * newly-marked entry is one the rest of the reachability graph did NOT cover — the return value is the
     * count of those, i.e. the number of blocks that survive *only* because code points at them.
     */
    private static long markCodeRoots()
    {
        long found = 0L;
        long p = Loader.CODE_ROOTS;
        long end = Loader.CODE_ROOTS + Loader.codeRootN * Loader.CODE_ROOT_ENTRY;
        while (p < end)
        {
            long w = Magic.load64(p);                  // {addr, owner}: the owner is for sweeping, not marking
            if (tryMark(w))
            {
                found = found + 1L;
            }
            if (tryMark(w - 16L))                      // an allocData payload address: mark its block base
            {
                found = found + 1L;
            }
            p = p + Loader.CODE_ROOT_ENTRY;
        }
        return found;
    }

    /**
     * The address just past the highest MARKED block — everything above it is garbage in one contiguous
     * run, so the collector can hand it back by lowering the bump pointer instead of threading thousands of
     * dead blocks onto the free list. This is what keeps the heap near its live size once the batch rewind
     * is gone: without it the pointer only ever rises, the swept heap grows without bound, and every later
     * collection walks more of it.
     *
     * <p>Returns {@code top} unchanged if the walk meets a corrupt size — trimming on a walk that could not
     * be completed would hand back memory that is still live. The guard mirrors the sweep's own.
     */
    private static long liveEndOf(long top)
    {
        long end = Heap.BASE;
        long o = Heap.BASE;
        while (o < top)
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L || o + size > top || o + size <= o)
            {
                return top;                            // corrupt: no trim, and the sweep reports stopAt
            }
            if ((st & 1L) != 0L)
            {
                end = o + size;
            }
            o = o + size;
        }
        return end;
    }

    /** Bytes the last collection returned by lowering the bump pointer rather than by freeing blocks. */
    static long trimmed;

    /** Blocks the last collection kept alive ONLY because a code immediate referenced them. Zero means the
     *  dangling-metadata hazard is theoretical for that workload; non-zero means the batch rewind has been
     *  covering for it. */
    static long codeOnly;

    /** Bytes that SURVIVED the last collection — the live set. It separates the two readings of a high heap
     *  water mark: a large live set is genuine retention (metadata the program can still reach), a small one
     *  means the mark is only garbage accumulated between collections, which collecting more often removes. */
    static long liveBytes;

    /** Of {@link #probes}, the part spent on ROOTS (stack, statics, secondary arenas). That half is
     *  irreducibly conservative without stack maps; {@code probes - rootProbes} is the TRACE side, which is
     *  the only part type metadata can shrink — and the only honest way to report this arc's effect, since
     *  a full-java.base image's statics region dwarfs the heap walk. */
    static long rootProbes;

    /**
     * The trace worklist: a block is pushed the moment it is marked ({@link #tryMark}), and scanned once
     * when popped. Scanning pushes whatever it discovers, so the drain ends exactly when the reachable set
     * is closed — where the old fixpoint re-walked the entire heap and re-scanned every marked block on
     * every round until a round changed nothing. That multiplier was the collector's dominant cost: with
     * three rounds, each live {@code Object[]} registry table was probed three times.
     */
    private static void drainMarkStack()
    {
        while (markSp > MARK_STACK)
        {
            markSp = markSp - 8L;
            long o = Magic.load64(markSp);
            long size = Magic.load64(o + 8L) & -8L;
            if (size != 0L && o + size <= Magic.load64(Heap.PTR_CELL) && o + size > o)
            {                                          // same guard the heap walk uses: never scan past a
                boolean ignored = scanBlock(o, size);  //   corrupt size. Pushes are the real output here;
            }                                          //   the returned flag only matters to the fallback.
        }
    }

    /**
     * The pre-worklist algorithm, kept as the overflow path: re-walk the heap scanning every marked block,
     * repeating until a pass marks nothing new. Correct on its own (it is what shipped before), just slower,
     * so a queue overflow costs speed and never correctness. It only triggers if a single collection marks
     * more than {@link #MARK_STACK_SLOTS} blocks — far beyond anything seen (a full NetDemo boot marks a few
     * thousand) — and the queue keeps filling underneath it, so later rounds still get the fast path.
     */
    private static void traceFixpoint()
    {
        while (true)
        {
            markOverflow = 0;                          // a pass that never overflows has closed the set
            markSp = MARK_STACK;
            long o = Heap.BASE;
            long top = Magic.load64(Heap.PTR_CELL);
            while (o < top)
            {
                long st = Magic.load64(o + 8L);
                long size = st & -8L;
                if (size == 0L || o + size > top || o + size <= o)
                {
                    o = top;    // corrupt / out-of-bounds: stop the walk instead of dereferencing garbage
                }
                else
                {
                    if ((st & 1L) != 0L)
                    {
                        boolean ignored = scanBlock(o, size);
                    }
                    o = o + size;
                }
            }
            drainMarkStack();                          // anything this pass queued, scanned before re-walking
            if (markOverflow == 0)
            {
                return;                                // the queue kept up: nothing left to discover
            }
        }
    }

    /** Trace worklist: one 8-byte entry per marked block, in the scratch window above the JIT unwind tables
     *  ({@code JIT_TABLES} + 0x50000) and below the heap cells at {@code 0x03FF0000}. Outside the managed
     *  heap on purpose — the collector must not allocate while collecting. */
    static final long MARK_STACK     = 0x03E5_0000L;
    static final long MARK_STACK_END = 0x03FF_0000L;
    /** Capacity in entries (~213k): three orders of magnitude above the few thousand blocks a real
     *  collection marks, so the overflow path is a safety net rather than a regime. */
    static final long MARK_STACK_SLOTS = (MARK_STACK_END - MARK_STACK) / 8L;

    private static long markSp;        // next free worklist slot
    private static int  markOverflow;  // 1 = the queue filled; the fixpoint fallback finishes the trace

    /** Mark every heap object pointed to by an 8-aligned word in [lo,hi). Returns true if any newly marked. */
    private static boolean markRange(long lo, long hi)
    {
        boolean any = false;
        if (hi > lo)
        {
            probes = probes + ((hi - lo) >> 3);
        }
        while (lo < hi)
        {
            long w = Magic.load64(lo);
            if (tryMark(w))
            {
                any = true;
            }
            if (tryMark(w - 16L))                      // a raw-data payload pointer (Heap.allocData) sits one
            {                                          //   header past its block base: probe that base too
                any = true;
            }
            lo = lo + 8L;
        }
        return any;
    }

    /** Mark {@code w} if it IS an unmarked heap block base; true if newly marked. Verified against the
     *  block-start bitmap ({@link #buildBlockBitmap}) — a status-word heuristic alone would sometimes
     *  accept an object INTERIOR and the {@code +1} mark write would corrupt real data (a stored pointer
     *  became odd and faulted the next dispatch). With the bitmap, mark writes only ever touch genuine
     *  status words. */
    private static boolean tryMark(long w)
    {
        if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
        {
            long bit = (w - Heap.CODE_BASE) >> 3;       // a pointer INTO the code arena: record the hit so
            long cw = CODE_BITMAP + ((bit >> 6) << 3);  //   the sweep can tell which compiled methods are
            Magic.store64(cw, Magic.load64(cw) | (1L << (int) (bit & 63L)));   // still reachable
            return false;                               // code is not a heap block; nothing to mark or trace
        }
        if (w >= Heap.BASE && w < Magic.load64(Heap.PTR_CELL) && (w & 7L) == 0L && isBlockBase(w))
        {
            long st = Magic.load64(w + 8L);
            if ((st & 1L) == 0L)
            {
                Magic.store64(w + 8L, st + 1L);        // set mark bit
                if (markSp < MARK_STACK_END)           // ... and queue it for scanning exactly once
                {
                    Magic.store64(markSp, w);
                    markSp = markSp + 8L;
                }
                else
                {
                    markOverflow = 1;                  // queue full: the fixpoint fallback will catch up
                }
                return true;
            }
        }
        return false;
    }

    /** One bit per 8 bytes of the 16 MiB code arena (256 KiB), recording every word seen pointing into it.
     *  A compiled method is reachable iff any bit inside its extent is set — the code-side equivalent of a
     *  mark bit, kept out of line because the arena has no headers to hold one. */
    static final long CODE_BITMAP = 0x0350_0000L;
    private static final long CODE_BITMAP_END = 0x0354_0000L;

    /** Bytes of compiled code still reachable at the last collection, and the total ever compiled. The ratio
     *  decides whether reclaiming code is worth building: if nearly all of it is live, a code collector buys
     *  nothing and the arena simply has to be big enough. */
    static long codeLive;
    static long codeUsed;
    /** Bytes of unreachable compiled code the last collection returned to the code free list. */
    static long codeFreed;
    /** 1 = actually sweep unreachable code, not merely count it. Off until the code rewind is retired:
     *  while the rewind runs, a batch's code is discarded wholesale and sweeping it would be busywork. */
    static int sweepCode;
    /** No code below this address is ever swept — the boot vector table, the scheduler's switch stubs and
     *  the run trampoline are reached from hardware registers and stub-internal branches the collector
     *  cannot see. Set to the loader's code watermark, the same line the rewind never crossed. */
    static long codeSweepFloor;

    /** Walk the code-block registry and total the blocks with a bit set. Runs after the trace has drained,
     *  so every reachable code pointer has been seen. */
    private static void measureCodeLiveness()
    {
        codeLive = 0L;
        codeUsed = 0L;
        codeFreed = 0L;
        long i = 0;
        while (i < Heap.codeBlockN)
        {
            long e = Heap.CODE_BLOCKS + i * 16L;
            long start = Magic.load64(e);
            long size = Magic.load64(e + 8L);
            if ((size & Heap.CODE_FREE) != 0L)
            {
                i += 1;                                // already swept: not live, not in use
                continue;
            }
            codeUsed = codeUsed + size;
            long b = (start - Heap.CODE_BASE) >> 3;
            long bEnd = b + (size >> 3);
            int hit = 0;
            while (b < bEnd)
            {
                if ((Magic.load64(CODE_BITMAP + ((b >> 6) << 3)) & (1L << (int) (b & 63L))) != 0L)
                {
                    codeLive = codeLive + size;
                    hit = 1;
                    b = bEnd;                          // one hit makes the whole method live
                }
                else
                {
                    b += 1;
                }
            }
            if (hit == 0 && sweepCode != 0 && start >= codeSweepFloor)
            {
                // Unreachable compiled code. Sweeping it needs the JIT unwind entries keyed to its address
                // range dropped in the same breath: they outlive the method otherwise and would answer for
                // whatever is compiled at that address next -- the aliasing dropJitTablesAbove prevents
                // under the batch rewind.
                VM.dropJitTablesIn(start, start + size);
                Loader.dropCodeRootsIn(start, start + size);   // its baked-in addresses die with it
                Heap.freeCodeBlock(i);
                codeFreed = codeFreed + size;
            }
            i += 1;
        }
    }

    // A code-arena trim once lived here: lower CODE_PTR_CELL past the trailing run of swept buffers, the
    // way the data heap's sweep trims its own. It was removed after hardware measurement (PLAN.md,
    // increment 10). On QEMU it looked like a small win, 6.71 -> 5.46 MB; on a real Pi it reclaimed
    // NOTHING -- codeTrim read 0 at every collection and the arena stayed pinned at 6.64 MB against
    // 1.75 MB live. A trailing-run trim needs garbage to cluster at the top, and live methods in the code
    // arena are scattered by construction: every batch leaves a few survivors among hundreds of dead, so
    // one of them sits near the top and pins everything beneath it. Closing that gap needs compaction --
    // moving code and patching branch targets, TIB slots, phase-A cells and the long fields the refMap J
    // rule keeps scannable -- not a sweep-time pointer move.

    /** Block-start bitmap: 1 bit per 8 bytes of core 0's arena (3 MiB at a fixed scratch address, above
     *  the secondary stacks and below the heap cells). Rebuilt by each collection's pre-pass. */
    static final long MARK_BITMAP = 0x03B0_0000L;

    /** Pre-pass: walk the heap by status sizes (sound — every allocation carries an intact header) and
     *  record each block base in the bitmap, so conservative probes can be verified exactly. */
    private static void buildBlockBitmap(long stop)
    {
        long z = MARK_BITMAP;
        long zEnd = MARK_BITMAP + 0x30_0000L;
        while (z < zEnd)
        {
            Magic.store64(z, 0L);
            z += 8L;
        }
        long o = Heap.BASE;
        while (o < stop)
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L || o + size > stop || o + size <= o)
            {
                o = stop;                              // corrupt: stop (matches the sweep's guard)
            }
            else
            {
                long idx = (o - Heap.BASE) >> 3;
                long w = MARK_BITMAP + ((idx >> 6) << 3);
                Magic.store64(w, Magic.load64(w) | (1L << (int) (idx & 63L)));
                o += size;
            }
        }
    }

    private static boolean isBlockBase(long w)
    {
        long idx = (w - Heap.BASE) >> 3;
        long word = MARK_BITMAP + ((idx >> 6) << 3);
        return (Magic.load64(word) & (1L << (int) (idx & 63L))) != 0L;
    }}
