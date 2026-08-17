package vm;

import magic.Magic;
import board.bcm2711.Uart;
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
        boolean changed = true;                       // trace: mark fields of marked objects to a fixpoint
        while (changed)
        {
            changed = false;
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
                    if ((st & 1L) != 0L && markRange(o + 16L, o + size))
                    {
                        changed = true;
                    }
                    o = o + size;
                }
            }
        }
        Heap.resetFreeList();                          // sweep
        reclaimed = 0L;
        long walked = 0L;
        long markedN = 0L;
        long freedN = 0L;
        long o = Heap.BASE;
        long stop = Magic.load64(Heap.PTR_CELL);
        long stoppedAt = 0L;
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
                    markedN = markedN + 1L;
                    Magic.store64(o + 8L, size);    // unmark (clear bit0)
                }
                else
                {
                    freedN = freedN + 1L;
                    Heap.addFree(o, size);
                    reclaimed = reclaimed + size;
                }
                o = o + size;
            }
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

    /** Mark every heap object pointed to by an 8-aligned word in [lo,hi). Returns true if any newly marked. */
    private static boolean markRange(long lo, long hi)
    {
        boolean any = false;
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
        if (w >= Heap.BASE && w < Magic.load64(Heap.PTR_CELL) && (w & 7L) == 0L && isBlockBase(w))
        {
            long st = Magic.load64(w + 8L);
            if ((st & 1L) == 0L)
            {
                Magic.store64(w + 8L, st + 1L);        // set mark bit
                return true;
            }
        }
        return false;
    }

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
