/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-17
 */
package vm;

import magic.Address;
import magic.Magic;
import magic.ObjectReference;
import magic.Uninterruptible;
import board.Uart;
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
final class VMGc implements Uninterruptible
{

    /**
     * Conservative mark-sweep, entered via {@code Magic.gc()} (which spills the
     * callee-saved registers so live references there are on the stack). Roots are
     * the stack [{@code scanFrom}, STACK_TOP) and the statics region; anything
     * transitively reachable survives, everything else is swept onto the free list.
     * Object sizes come from the status word — no per-type maps, and objects aren't
     * moved (so no precise stack maps are needed). May over-retain (false roots).
     */
    /** SP at the last collection -- the stack bottom compaction would have to work from. */
    static long lastScanFrom;

    /**
     * The last collection's phases, in generic-timer ticks, for /gc: stopping the world, the root scan (which
     * includes cores 1-3's arenas, scanned whole as roots up to their bump pointers), the trace, the sweep.
     */
    static long phaseStopTicks;
    static long phaseRootTicks;
    static long phaseTraceTicks;
    static long phaseSweepTicks;

    static void gcCollect(long scanFrom)
    {
        // ANY CORE MAY COLLECT (frames/GC arc S5a): cores 1-3's arenas are collected now, so a core whose arena is
        // full collects instead of halting. One collection at a time: a core that finds another collecting
        // returns at once, and its allocation waits for that collection (Heap.waitForCollection) and retries.
        if (!takeGcLock())
        {
            gcBusy = gcBusy + 1L;
            return;
        }
        gcCore = (int) (Magic.readMPIDR() & 3L);
        lastScanFrom = scanFrom;
        long gcT0 = Magic.readCNTPCT_EL0();           // the pause /gc reports: entry to the world restarting
        long daif = Magic.readDaif();                 // no preemption mid-collection: a switched-in task
        Magic.disableIrq();                           //   would allocate into the half-swept heap
        if (!stopTheWorld())                          // ... and no MUTATION on another core, for the same reason
        {
            // The world would not stop, so SKIP the collection. Marking against a running mutator sweeps
            // objects that core still holds, and the dangling reference surfaces arbitrarily far away -- on a
            // Pi it was a dispatch branching to a HEAP address (`unclaimed pc=0x0C01FE50`). The allocator
            // retries once and then halts with a loud "heap OOM", which is a strictly better failure than a
            // corrupted heap. This is what the comment above stopTheWorld always claimed happened.
            VMStats.gcSkipped = VMStats.gcSkipped + 1L;
            releaseGcLock();
            if ((daif & 0x80L) == 0L)
            {
                Magic.enableIrq();
            }
            return;
        }
        long tStopped = Magic.readCNTPCT_EL0();
        gcByCore[gcCore] = gcByCore[gcCore] + 1L;
        phaseStopTicks = tStopped - gcT0;             // the last collection's phases, for /gc
        long fullProbes = probes;                     // the last FULL collection's precision metrics: a minor-only
        long fullNomap = nomap;                       //   collection puts them back (it never sets rootProbes)
        probes = 0L;                                  // precision metrics for this collection (gcLog)
        nomap = 0L;
        markSp = MARK_STACK;                          // the trace worklist starts empty
        censusReset();                                // S6 census (gcheap=1): roots count as HC_ROOT
        if (HeapShadow.on != 0)
        {
            HeapShadow.begin();                       // gcheap=2: the trace runs precisely first
        }
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
                    taskThreadObj.set(t, ObjectReference.nullReference());
                    taskState[t] = TASK_EMPTY;
                    reaped += 1;
                }
                t += 1;
            }
        }
        if (STALE_PROBES)
        {
            recheckStaleSlots();                      // ... before this collection changes anything
        }
        Address cz = Address.fromLong(CODE_BITMAP);   // clear the code-reachability bitmap for this pass
        Address czEnd = Address.fromLong(CODE_BITMAP_END);
        while (cz.LT(czEnd))
        {
            cz.store(0L);
            cz = cz.plus(8);
        }
        // The block-start bitmap: exact block bases for CONSERVATIVE probes only (frames/GC arc S6d-3). An exact word
        // -- a typed slot, a map-typed stack word, a statics or code-root entry by kind -- is marked without it; a
        // conservative probe builds it on first need (isBlockBase), so a collection with no fallback never walks
        // the heap for it. gcverify=1 builds it every collection and checks each exact mark against it.
        bitmapBuilt = 0;
        if (verifyExact != 0)
        {
            ensureBitmap();
        }
        long stackTop = STACK_TOP;                    // boot task: SP runs down from the image stack top
        int me = curTask();
        if (taskStackBase != null && me != 0)
        {
            if (taskStackBase[me] != 0L)
            {
                stackTop = (taskStackBase[me] + 0x8000L) & ~0xFL;   // a spawned task: its stack is a heap object
            }
            else if (taskIdle != null && taskIdle[me] != 0 && taskCore[me] >= 1)
            {
                // A PER-CORE IDLE TASK -- the THIRD stack kind, and the one the two arms above miss. Its
                // stack is neither the image stack nor a heap block: it is the fixed SEC-band stack the
                // secondary boot stub set up. Left at STACK_TOP the range is markRange(0x038x_xxxx,
                // 0x80000), lo ABOVE hi, and markRange's `while (lo < hi)` then runs ZERO times -- so the
                // collector's own frames are scanned NOT AT ALL, which is a stronger statement than the
                // "whatever those two addresses bracket" this was first written up as.
                stackTop = secStackTop(taskCore[me]);
                idleGc = idleGc + 1;
            }
        }
        scanSrc = 0L;
        if (Nursery.on != 0)
        {
            Nursery.collect(scanFrom, stackTop);      // S7-4a: every collection evacuates the nurseries first
            if (Nursery.minorOnly != 0 && Nursery.on == 1)
            {
                probes = fullProbes;                  // lastProbes/roots/heap/nomap describe the last full trace;
                nomap = fullNomap;                    //   left zeroed, `heap=` printed probes - rootProbes < 0
                finishMinor(gcT0, daif);              // ... and one the nursery asked for ends there
                return;
            }
            Nursery.fulls = Nursery.fulls + 1L;
            WriteBarrier.clearHeap();                 // the trace re-logs what still names a resident
            Nursery.fullTrace = 1;
        }
        GenCensus.source(GenCensus.SRC_ROOT);         // gcgen=1: what follows are root edges
        if (StackRoots.on != 0)
        {
            // PRECISE STACK ROOTS (frames/GC arc S5): every task's stack, walked frame by frame and marked by its
            // reference maps -- the collecting task, the boot task, the parked cores' tasks and every suspended one.
            StackRoots.markStacks(scanFrom, stackTop);
        }
        else
        {
            conservativeStacks(scanFrom, stackTop, me);   // gcroots=0: the scan every collection made before S5
        }
        if (PreciseTrace.on != 0 && staticsMap != 0L)
        {
            PreciseTrace.markStatics(staticsStart, staticsEnd, staticsMap);   // S6d: by the writer's kind maps
        }
        else
        {
            markRange(Address.fromLong(staticsStart), Address.fromLong(staticsEnd));
        }
        staticsScans = staticsScans + 1L;             // did the root scan for statics actually run this pass?
        if (Nursery.fullTrace != 0)
        {
            Nursery.markResidents();                  // S7-4a: blocks pinned in the nursery are live, and roots
        }
        // Cores 1-3's arenas were ROOT RANGES here, scanned whole and never collected. Since S5a they are heap like
        // core 0's: marked through the trace, swept into their own cores' free lists (sweepArena).
        rootProbes = probes;                          // split the metric: everything above is ROOT scanning
        long tRoots = Magic.readCNTPCT_EL0();
        phaseRootTicks = tRoots - tStopped;
        drainMarkStack();                             // trace: scan each newly marked block exactly ONCE
        hcCat = HC_ROOT;
        GenCensus.source(GenCensus.SRC_ROOT);
        markGcMaps();                                 // JIT reference maps live as long as their frame entry
        hcCat = HC_ROOT;
        GenCensus.source(GenCensus.SRC_CODE);         // gcgen=1: addresses baked into compiled code
        codeOnly = markCodeRoots();                   // ... then the addresses only compiled code holds
        drainMarkStack();
        if (markOverflow != 0)
        {
            overflows = overflows + 1L;
            traceFixpoint();                          // stack ran out: fall back to re-scanning the heap
        }
        censusPrint();
        if (HeapShadow.on != 0)
        {
            HeapShadow.rescanAll();                   // ... then conservatively: what only that keeps is a MISS
            drainMarkStack();
            if (markOverflow != 0)
            {
                overflows = overflows + 1L;
                traceFixpoint();
            }
            HeapShadow.end();
        }
        if (GcMapShadow.on != 0)
        {
            // The shadow check, after the trace: every word a conservative scan of the stacks would keep, by where
            // it sits -- and whether the precise walk + trace kept its block. One it did not is reported as a MISS
            // and marked here, so a check run reports every miss instead of crashing on the first.
            GcMapShadow.check(scanFrom, stackTop);
            StackRoots.summary();
            drainMarkStack();
            if (markOverflow != 0)
            {
                overflows = overflows + 1L;
                traceFixpoint();
            }
        }
        if (GenCensus.on != 0)
        {
            GenCensus.afterTrace();                   // gcgen=1: young/old, before the sweep frees anything
        }
        if (WriteBarrier.on != 0)
        {
            WriteBarrier.afterTrace();                // gcbarrier=1: count the logged objects, clear for the next cycle
        }
        IdentityHash.purge();                         // S7-1: a hashed block the sweep frees loses its entry first
        Nursery.fullTrace = 0;
        afterTrace(scanFrom, stackTop, gcT0, tRoots, reaped, daif);
    }

    /** A minor-only collection (S7-4a): the nurseries are evacuated; restart the world. */
    private static void finishMinor(long gcT0, long daif)
    {
        startTheWorld();
        releaseGcLock();
        Nursery.minorPause(Magic.readCNTPCT_EL0() - gcT0);
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();
        }
    }

    /**
     * The conservative stack roots ({@code gcroots=0}): every stack scanned whole from its SP, as before S5. Task
     * stacks are typed byte arrays now, so the trace no longer reaches them as data -- each is scanned here from
     * its saved SP instead.
     */
    private static void conservativeStacks(long scanFrom, long stackTop, int me)
    {
        markRange(Address.fromLong(scanFrom), Address.fromLong(stackTop));
        // THE BOOT TASK'S STACK IS A ROOT EVEN WHEN ANOTHER TASK IS COLLECTING. Task 0's is the IMAGE stack and
        // a heap object for nobody (a spawned task's is a heap block, scanned by the loop at the end of this
        // method since S5 typed it as a byte array the trace skips). Scanned only when task 0 happened to be the one
        // that called Magic.gc, so a collection triggered by a spawned thread swept everything live only in
        // main's frames -- a local holding a Thread, say, whose header is then reused and whose next virtual
        // call reads a garbage TIB.
        if (me != 0)
        {
            long bootSp = taskSp != null ? taskSp[0] : 0L;
            if (bootSp != 0L && bootSp < STACK_TOP)
            {
                markRange(Address.fromLong(bootSp), Address.fromLong(STACK_TOP));
            }
        }
        // AND EVERY OTHER CORE'S IDLE STACK, for exactly the reason above it. Those are fixed SEC-band
        // stacks, heap objects for NOBODY, so -- like task 0's -- no trace reaches them however the
        // collection was triggered. This is the boot-task hole one stack kind further out; adding a root
        // can only RETAIN more, never sweep more, which is why it is cheap to be safe here.
        //
        // The saved SPs are trustworthy BY CONSTRUCTION: stopTheWorld has already parked every scheduling
        // core, and gcPark writes taskSp[coreTask[core]] BEFORE it publishes its generation, so a parked
        // core's idle task has a current SP. The collector's OWN core is excluded (idle == me) because the
        // arm above already brackets it from the live SP rather than a stale saved one.
        if (coreIdle != null && taskSp != null)
        {
            int ic = 1;                               // core 0 has no idle task: coreIdle[0] stays -1
            while (ic < 4)
            {
                int idle = coreIdle[ic];
                // A saved SP of 0 is the core's own registration window -- smpSchedulerMain sets coreIdle
                // before the flow has ever switched out -- so it is not counted at all rather than counted
                // as a skip: that would make idleRoots < idleSeen mean two different things.
                if (idle > 0 && idle != me && taskSp[idle] != 0L)
                {
                    long top = secStackTop(ic);
                    long sp = taskSp[idle];
                    idleSeen = idleSeen + 1;
                    // IN-BAND OR NOT AT ALL. A stale or recycled slot would otherwise hand markRange an
                    // arbitrary range, which is the failure this whole fix is about; the counters print as
                    // idleRoots=N/M so a skip is VISIBLE rather than silent.
                    if (sp < top && sp > top - 0x100000L)
                    {
                        if (markRange(Address.fromLong(sp), Address.fromLong(top)))
                        {
                            idleMarked = idleMarked + 1;   // ... and did the range actually RETAIN anything
                        }
                        idleRoots = idleRoots + 1;
                    }
                }
                ic += 1;
            }
        }
        if (taskSp != null && taskStackBase != null && taskState != null)
        {
            int t = 1;
            while (t < taskCount)
            {
                long stk = taskStackBase[t];
                if (t != me && stk != 0L && taskState[t] != TASK_EMPTY)
                {
                    long top = (stk + 0x8000L) & ~0xFL;
                    long lo = taskSp[t];
                    if (lo < stk + 24L || lo >= top)
                    {
                        lo = stk + 24L;                   // no saved SP inside the stack: all of it
                    }
                    markRange(Address.fromLong(lo), Address.fromLong(top));
                }
                t += 1;
            }
        }
    }

    /** The rest of a collection, after the trace: code liveness, the sweep, the counters. */
    private static void afterTrace(long scanFrom, long stackTop, long gcT0, long tRoots, int reaped, long daif)
    {
        // Code liveness is measured — and unreachable code SWEPT — only once the trace is complete. This
        // used to run before the overflow fallback, so an overflowed pass swept code against a half-finished
        // mark: any method reachable only through an object the fixpoint had yet to discover was freed while
        // live, and (since #134) zeroed, so the next call to it executes zeros.
        measureCodeLiveness();                        // how much compiled code is still reachable at all
        if (sweepCode != 0 && STALE_PROBES)
        {
            // Off by default: each pass rescans the stack, the statics and the JIT tables per collection,
            // which roughly doubles a suite run. Turn on to investigate a fault that lands in swept code.
            Loader.verifyCells();
            findStaleCodeHolders(Address.fromLong(Heap.BASE), Address.fromLong(Heap.PTR_CELL).loadAddress());
            findStaleCodeHolders(Address.fromLong(Heap.LARGE_BASE),
                                 Address.fromLong(Heap.LARGE_PTR_CELL).loadAddress());
            findStaleCodeInRaw(scanFrom, stackTop, 1L);                     // the stack (the CURRENT task's)
            findStaleCodeInRaw(staticsStart, staticsEnd, 2L);               // image statics
            findStaleCodeInRaw(VM.jitFrameTable.toLong(), VM.jitFrameTable.plus(VM.jitFrameCount * 24L).toLong(), 3L);
            findStaleCodeInRaw(VM.jitHandlerTable.toLong(), VM.jitHandlerTable.plus(VM.jitHandlerCount * 32L).toLong(),
                               4L);
        }
                                                      // (counted AFTER the ordinary trace, so the count is
                                                      //  exactly "what nothing else kept alive")
        long tTraced = Magic.readCNTPCT_EL0();
        phaseTraceTicks = tTraced - tRoots;
        collections = collections + 1L;
        Heap.allocSinceGc = 0L;                        // ANY collection resets the volume trigger, or a
        Heap.servedSinceGc = 0L;                       //   (and Runtime.freeMemory's since-collection count)
                                                       //   boundary collection leaves it armed and refires
        Heap.resetFreeList();                          // sweep
        reclaimed = 0L;
        liveBytes = 0L;
        long walked = 0L;
        long markedN = 0L;
        long freedN = 0L;
        Address o = Address.fromLong(Heap.BASE);
        Address ptrCell = Address.fromLong(Heap.PTR_CELL);
        Address stop = liveEndOf(ptrCell.loadAddress());      // everything above the highest survivor is
        trimmed = ptrCell.loadLong() - stop.toLong();         //   garbage in one contiguous run: give it
        reclaimed = reclaimed + trimmed;                      //   back by lowering the bump pointer, not
        ptrCell.store(stop);                                  //   by threading it onto the free list
        long stoppedAt = 0L;
        Address runStart = Address.zero();             // a run of adjacent dead blocks, merged when it ends
        long runSize = 0L;
        while (o.LT(stop))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(stop) || o.plus(size).LE(o))
            {
                stoppedAt = o.toLong();                // corrupt / out-of-bounds: stop
                o = stop;
            }
            else
            {
                walked = walked + 1L;
                if ((st & 1L) != 0L)
                {
                    if (runSize != 0L)                     // a survivor ends the run of dead blocks
                    {
                        Heap.addFreeTo(0, runStart, runSize);
                        runStart = Address.zero();
                        runSize = 0L;
                    }
                    markedN = markedN + 1L;
                    liveBytes = liveBytes + size;   // what SURVIVED: the number that says whether a high heap
                    o.plus(8).store(size);          //   water mark is retention or just uncollected garbage
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
                o = o.plus(size);
            }
        }
        if (runSize != 0L)                             // the heap ended on dead blocks
        {
            Heap.addFreeTo(0, runStart, runSize);
        }
        sweepLargeRegion();
        int ac = 1;
        while (ac < 4)
        {
            sweepArena(ac);                           // cores 1-3's arenas, into their own free lists
            ac += 1;
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
        phaseSweepTicks = Magic.readCNTPCT_EL0() - tTraced;
        startTheWorld();                              // the heap is consistent again: let the other cores run
        releaseGcLock();
        VMStats.gcPause(Magic.readCNTPCT_EL0() - gcT0);
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();                        // restore: only unmask if the caller had IRQs on
        }
    }

    /**
     * Stop the world: every core but this one parks before the mark begins. The collector moves nothing, so
     * a concurrent READER would be harmless -- but a concurrent MUTATOR is not: it allocates into the arena
     * being swept, and it can publish the only reference to an object into a place this pass has already
     * scanned, so the object is swept while live. Cores park in {@link VMScheduler#pickNext} (their timer
     * tick or their idle yield), with the interrupted task's SP already saved to its stack -- which is how
     * the trace still sees everything they were holding.
     *
     * <p>A core that never reaches the scheduler cannot be parked; rather than mark against a running
     * mutator we give up after ~1 s, count it in {@code stwTimeouts} and say so, because a silent
     * corruption here surfaces arbitrarily far away.
     */
    private static boolean stopTheWorld()
    {
        if (coreSched == null || gcParked == null || scheduling() == 0)
        {
            return true;                              // no other core is scheduling: the world is already stopped
        }
        // Deliberately NOT `smpSched == 0`: stopSmpScheduling clears that flag after waiting only ~1 s for the
        // secondaries to drain, so a straggler still inside smpSchedulerMain leaves it 0 while still mutating.
        // coreSched[] is per-core and says what is actually true.
        gcGen = gcGen + 1L;                           // this collection's generation ...
        Magic.dsb();                                  // ... PUBLISHED BEFORE gcStop: a core that saw the stop
        gcStop = 1;                                   //     but the old generation would park uncounted
        Magic.dsb();
        long stw0 = Magic.readCNTPCT_EL0();
        if (noSafepoints == 0 && yieldReqAt != null)
        {
            // S3: ask every other core to reach a yieldpoint now, instead of waiting up to a quantum for its tick.
            // A yieldpoint switches through pickNext, which parks the core there (gcPark).
            int rc = 0;
            while (rc < 4)
            {
                if (rc != gcCore && coreSched[rc] != 0)
                {
                    VMScheduler.requestYield(rc);
                }
                rc += 1;
            }
        }
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0();
        // Wait for the cores that are actually SCHEDULING, not a hardcoded three. A core that has left the
        // run queue (smpStop drained it, or it never joined -- "smp sched: 3 of 4") has no timer and no yield
        // point, so it can never park: waiting for it times out EVERY collection, for ever.
        while (Magic.readCNTPCT_EL0() < deadline && unparked() != 0)
        {
        }
        if (unparked() != 0)
        {
            stwTimeouts = stwTimeouts + 1L;
            Uart.write(Magic.bytes("\nGC: STW TIMEOUT -- collecting core "));
            printDec(gcCore);
            Uart.write(Magic.bytes(", unparked core"));
            int c = 0;
            while (c < 4)
            {
                if (c != gcCore && coreSched[c] != 0 && gcParked[c] != gcGen)
                {
                    Uart.putc(0x20);
                    printDec(c);
                    Uart.write(Magic.bytes(" task "));
                    printDec(coreTask[c]);
                    Uart.write(Magic.bytes(" parkedGen="));
                    printDec((int) gcParked[c]);
                    Uart.write(Magic.bytes(" gen="));
                    printDec((int) gcGen);
                    stwLate[c] = Magic.readCNTPCT_EL0();   // it says where it was when it next switches
                }
                c += 1;
            }
            Uart.write(Magic.bytes(" loaderOwner="));
            printDec(loaderOwner);
            Uart.write(Magic.bytes(" (collection SKIPPED)\n"));
            gcStop = 0;                               // release whoever DID park: we are not collecting
            Magic.dsb();
            Magic.sev();
            return false;
        }
        long waited = Magic.readCNTPCT_EL0() - stw0;
        stwWaitTicks += waited;
        stwStops += 1L;
        if (waited > stwWaitMaxTicks)
        {
            stwWaitMaxTicks = waited;
        }
        return true;
    }

    /** Collections a core asked for while another core was collecting (it waited for that one instead). */
    static long gcBusy;
    /** Collections by the core that ran them: core 0 by volume, cores 1-3 when their arena filled. */
    static long[] gcByCore = new long[4];

    /** Take the collector lock, or return false at once if another core holds it. */
    private static boolean takeGcLock()
    {
        while (true)
        {
            if (Address.fromLong(GC_LOCK).loadLong() != 0L)
            {
                return false;
            }
            if (Magic.cas64(GC_LOCK, 0L, 1L))         // LL/SC: may fail spuriously, so re-read and retry
            {
                Magic.dsb();
                return true;
            }
        }
    }

    private static void releaseGcLock()
    {
        Magic.dsb();
        Address.fromLong(GC_LOCK).store(0L);
        Magic.dsb();
        Magic.sev();
    }

    /** True while some core is collecting -- for an allocation that found the collector lock taken. */
    static boolean collecting()
    {
        return Address.fromLong(GC_LOCK).loadLong() != 0L;
    }

    /** Stop-the-world waits that stopped the world: how many, their total and the longest (CNTPCT ticks). */
    static long stwStops;
    static long stwWaitTicks;
    static long stwWaitMaxTicks;

    /** Cores scheduling from the shared run queue that have not parked for THIS collection -- compared
     *  against the generation, so a core parked for an earlier one does not count. */
    private static int unparked()
    {
        int n = 0;
        int c = 0;
        while (c < 4)
        {
            if (c != gcCore && coreSched[c] != 0 && gcParked[c] != gcGen)
            {
                n += 1;
            }
            c += 1;
        }
        return n;
    }


    /** Cores other than the collector currently scheduling from the shared run queue (core 0 always is). */
    private static int scheduling()
    {
        int n = 0;
        int c = 0;
        while (c < 4)
        {
            if (c != gcCore && coreSched[c] != 0)
            {
                n += 1;
            }
            c += 1;
        }
        return n;
    }

    /** Release the parked cores: the heap is consistent again. */
    private static void startTheWorld()
    {
        if (gcStop == 0)
        {
            return;
        }
        gcStop = 0;
        Magic.dsb();
        Magic.sev();
    }

    /** Candidate words examined by the LAST collection. Conservative scanning probes every payload word of
     *  every marked block; type metadata lets whole payloads be skipped, and this counter is the evidence.
     *  Read by {@link Loader#launch} so a launched program reports it without the {@code gcLog} flag. */
    static long probes;

    /** Lowest address a TIB or Type pointer can have (the image loads at 0x80000); below it a header word
     *  is a raw array's element size or an untyped block's zero, never a pointer to dereference. */
    static final long META_LOW  = 0x0008_0000L;
    /** One past the highest: the end of core 3's arena slot. A class loaded on a secondary core (a {@code /run}
     *  program's, a server task's) has its TIB and Type in THAT core's arena; this bound used to stop at core 0's
     *  heap ({@code Heap.LARGE_LIMIT}), so every instance of such a class was scanned conservatively as an
     *  unrecognised header -- safe, but invisible on QEMU, where the probes load on core 0 (found by the S6 heap
     *  shadow on the Pi). Anything outside is garbage -> stay conservative. */
    static final long META_HIGH = Heap.LARGE_LIMIT + 3L * Heap.ARENA_SLOT;

    /**
     * Scan one marked block's payload for references, using the block's own TYPE METADATA where it has any.
     * By default the block goes to {@link PreciseTrace} (frames/GC arc S6c). What follows is the conservative trace
     * {@code gcprecise=0} keeps: three shapes live in this heap and the header word at +0 tells them apart: 0 or a
     * layout word ({@code ObjectModel.LAYOUT_TAG}: a Type, TIB, itable, statics block) = a raw {@link Heap#allocData}
     * struct, scanned whole; {@code <= MAX_RAW_ARRAY_TIB} = a raw array whose header holds its ELEMENT SIZE; anything
     * else = a pointer to a TIB whose slot 0 is the Type. Arrays by element size, scalars by the reference map
     * (every slot that may hold a pointer, raw ones included), every word at {@code w} and {@code w-16}.
     */
    private static boolean scanBlock(Address o, long size)
    {
        scanSrc = o.toLong();                          // S7-4a: the block whose words follow (Nursery.fullEdge)
        if (GenCensus.on != 0)
        {
            GenCensus.sourceBlock(o.toLong());
        }
        if (PreciseTrace.on != 0 || HeapShadow.precise != 0)
        {
            PreciseTrace.scanBlock(o, size);           // frames/GC arc S6c: the precise heap trace (gcheap=2's first
            return false;                              //   phase runs it too, so the shadow measures what it loses)
        }
        long tibw = o.plus(ObjectModel.TIB_OFFSET).loadLong();
        if (tibw != 0L && tibw <= (long) ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            census(HC_RAW_ARRAY, size, tibw >= 8L ? size : 0L);
            return scanArray(o, size, tibw);           // raw array: the header word IS the element size
        }
        if (tibw >= META_LOW && tibw < META_HIGH && (tibw & 7L) == 0L)
        {
            // THE HEADER IS A REFERENCE TOO: a TIB built on the metal is an allocData block (tibw its payload), and an
            // object must keep its own TIB -- and through it the Type -- alive. Nothing did: a metal array TIB is held
            // only by the per-launch ref-array cache and the code immediates of the method that created the array, so
            // an array outliving both would keep a freed TIB (frames/GC arc S6b; by reading -- not yet seen to fail).
            boolean ignoredTib = tryMark(tibw - 16L);
            Address tib = Address.fromLong(tibw);
            long type = tib.plus(ObjectModel.TIB_TYPE_SLOT * ObjectModel.WORD).loadLong();
            if (type >= META_LOW && type < META_HIGH && (type & 7L) == 0L)
            {
                Address t = Address.fromLong(type);
                long isz = t.plus(ObjectModel.TYPE_INSTANCE_SIZE_OFFSET).loadLong();
                if ((isz & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
                {
                    long es = isz & 0xFFFFL;
                    if (es >= 8L)
                    {
                        census(t.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadLong() != 0L ? HC_REF_ARRAY
                               : HC_WORD_ARRAY, size, size);
                    }
                    else
                    {
                        census(HC_NARROW_ARRAY, size, 0L);
                    }
                    return scanArray(o, size, es);   // typed array: element size in the tag
                }
                long map = t.plus(ObjectModel.TYPE_REFMAP_OFFSET).loadLong();
                if ((map & 1L) != 0L)                           // bit 0 = the class's map was computed
                {
                    census(HC_MAPPED, size, 0L);
                    return scanMapped(o, size, map,
                                      t.plus(ObjectModel.TYPE_REFMAP_OFFSET + 8).loadLong());
                }
                census(HC_UNMAPPED, size, size);
            }
            else
            {
                census(HC_OTHER, size, size);
            }
        }
        else
        {
            boolean data = tibw == 0L || (tibw & ObjectModel.LAYOUT_TAG_MASK) == ObjectModel.LAYOUT_TAG;   // + a layout word
            census(data ? HC_DATA : HC_OTHER, size, size);
        }
        nomap = nomap + 1L;                                        // the remaining conservative surface
        return markRange(o.plus(ObjectModel.HEADER_SIZE), o.plus(size));   // unmapped scalar / untyped block
    }

    // ---- frames/GC arc S6 census (gcheap=1): what the trace scans, by block shape, and what each shape marks.
    static final int HC_ROOT = 0;           // root scans (stacks, statics, code immediates, maps)
    static final int HC_MAPPED = 1;         // scalar with a reference map
    static final int HC_REF_ARRAY = 2;      // typed word array whose Type names an element Type (Object[] etc.)
    static final int HC_WORD_ARRAY = 3;     // typed word array with no element Type (long[], double[])
    static final int HC_NARROW_ARRAY = 4;   // typed array of sub-word elements: never scanned
    static final int HC_RAW_ARRAY = 5;      // raw array (header word = element size)
    static final int HC_DATA = 6;           // untyped allocData struct (header word 0)
    static final int HC_UNMAPPED = 7;       // typed scalar whose Type has no map
    static final int HC_OTHER = 8;          // a header word that is none of the above
    static final int HC_N = 9;
    /** 1 = keep the census ({@code gcheap=1}). */
    static int heapCensus;
    private static int hcCat;
    private static int hcDataProbe;         // 1 while tryMark is probing w-16
    private static long[] hcBlocks;
    private static long[] hcBytes;
    private static long[] hcScanned;        // bytes probed word by word
    private static long[] hcMarks;          // blocks newly marked by a word this shape held
    private static long[] hcMarksData;      // ... of which through the w-16 data-reference probe

    static void initCensus()
    {
        hcBlocks = new long[HC_N];
        hcBytes = new long[HC_N];
        hcScanned = new long[HC_N];
        hcMarks = new long[HC_N];
        hcMarksData = new long[HC_N];
    }

    static void census(int cat, long size, long scanned)
    {
        hcCat = cat;
        if (heapCensus != 0)
        {
            hcBlocks[cat] = hcBlocks[cat] + 1L;
            hcBytes[cat] = hcBytes[cat] + size;
            hcScanned[cat] = hcScanned[cat] + scanned;
        }
    }

    private static void censusReset()
    {
        hcCat = HC_ROOT;
        if (heapCensus != 0)
        {
            int i = 0;
            while (i < HC_N)
            {
                hcBlocks[i] = 0L;
                hcBytes[i] = 0L;
                hcScanned[i] = 0L;
                hcMarks[i] = 0L;
                hcMarksData[i] = 0L;
                i += 1;
            }
        }
    }

    /** One line per collection: per shape, blocks / KB / KB scanned word by word / new marks (via w-16). */
    private static void censusPrint()
    {
        if (heapCensus == 0)
        {
            return;
        }
        Uart.write(Magic.bytes("gcheap:"));
        int i = 0;
        while (i < HC_N)
        {
            Uart.putc((byte) 0x20);
            Uart.write(i == HC_ROOT ? Magic.bytes("root") : i == HC_MAPPED ? Magic.bytes("mapped")
                       : i == HC_REF_ARRAY ? Magic.bytes("refarr") : i == HC_WORD_ARRAY ? Magic.bytes("wordarr")
                       : i == HC_NARROW_ARRAY ? Magic.bytes("narrow") : i == HC_RAW_ARRAY ? Magic.bytes("rawarr")
                       : i == HC_DATA ? Magic.bytes("data") : i == HC_UNMAPPED ? Magic.bytes("unmapped")
                       : Magic.bytes("other"));
            Uart.putc((byte) 0x3D);
            printDec((int) hcBlocks[i]);
            Uart.putc((byte) 0x2F);
            printDec((int) (hcBytes[i] >> 10));
            Uart.putc((byte) 0x2F);
            printDec((int) (hcScanned[i] >> 10));
            Uart.putc((byte) 0x2F);
            printDec((int) hcMarks[i]);
            Uart.putc((byte) 0x28);
            printDec((int) hcMarksData[i]);
            Uart.putc((byte) 0x29);
            i += 1;
        }
        Uart.putc((byte) 0x0A);
        if (StackRoots.on != 0 && GcMapShadow.on == 0)
        {
            StackRoots.summary();                      // the root fallbacks too (frames/GC arc S6d-2)
        }
    }

    /**
     * An array block. Elements narrower than a word CANNOT hold a reference, so a {@code byte[]}/{@code
     * char[]}/{@code int[]} payload is skipped entirely instead of being probed word by word (a 64 KiB
     * byte[] cost 8192 probes per trace round). Word-wide elements are scanned over the ELEMENT range only.
     * Element size alone decides: a {@code long[]} is scanned like an {@code Object[]}, because an array
     * Type's element-Type slot is 0 both for a primitive element and for a reference element the loader
     * could not resolve — sound over precise until that distinction is verified.
     */
    private static boolean scanArray(Address o, long size, long elemSize)
    {
        if (elemSize < (long) ObjectModel.WORD)
        {
            return false;
        }
        Address end = o.plus(ObjectModel.ARRAY_BASE_OFFSET)
                 .plus(o.plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong() * (long) ObjectModel.WORD);
        if (end.GT(o.plus(size)) || end.LT(o))
        {
            end = o.plus(size);                        // a garbled length word: trust the block size instead
        }
        return markRange(o.plus(ObjectModel.ARRAY_BASE_OFFSET), end);
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
    private static boolean scanMapped(Address o, long size, long w0, long w1)
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
                long w = o.plus(ObjectModel.HEADER_SIZE).plus(i * 8L).loadLong();
                if (tryMark(w))
                {
                    any = true;
                }
                hcDataProbe = 1;
                if (tryMark(w - 16L))
                {
                    any = true;
                }
                hcDataProbe = 0;
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
    /** Mark each JIT body's reference map (a heap int[] the JIT local table names -- see Loader.gcMapOf). The
     *  table is outside the heap, so nothing else keeps a map alive; a swept body's entry is dropped with it. */
    private static void markGcMaps()
    {
        if (VM.jitLocalTable.isZero())
        {
            return;
        }
        long i = 0L;
        while (i < VM.jitLocalCount)
        {
            long m = VM.jitLocalTable.plus(i * 24L + 16L).loadLong();
            if (m > 0L)                                // a map array; a stub frame's entry holds a descriptor
            {
                exactFrom = 0L;
                tryMarkExact(m - ObjectModel.ARRAY_BASE_OFFSET);
            }
            i += 1L;
        }
        drainMarkStack();
    }

    private static long markCodeRoots()
    {
        long found = 0L;
        Address p = Address.fromLong(Loader.CODE_ROOTS);
        Address end = p.plus(Loader.codeRootN * Loader.CODE_ROOT_ENTRY);
        while (p.LT(end))
        {
            long e = p.loadLong();                     // {addr | data, owner}: the owner is for sweeping, not marking
            long w = e & -8L;
            if (PreciseTrace.on != 0)
            {
                // S6d: the entry says what it is -- an object (marked at w) or an allocData payload (one header lower)
                exactFrom = 0L;
                if (tryMarkExact((e & 1L) != 0L ? w - 16L : w))
                {
                    found = found + 1L;
                }
            }
            else
            {
                if (tryMark(w))
                {
                    found = found + 1L;
                }
                if (tryMark(w - 16L))                  // an allocData payload address: mark its block base
                {
                    found = found + 1L;
                }
            }
            p = p.plus(Loader.CODE_ROOT_ENTRY);
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
    private static Address liveEndOf(Address top)
    {
        return liveEndIn(Address.fromLong(Heap.BASE), top);
    }

    /** {@link #liveEndOf} for the region starting at {@code base}. */
    private static Address liveEndIn(Address base, Address top)
    {
        Address end = base;
        Address o = base;
        while (o.LT(top))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(top) || o.plus(size).LE(o))
            {
                return top;                            // corrupt: no trim, and the sweep reports stopAt
            }
            if ((st & 1L) != 0L)
            {
                end = o.plus(size);
            }
            o = o.plus(size);
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

    /** Idle-task stacks scanned as roots, cumulative / idle tasks found. Printed as {@code idleRoots=N/M}
     *  by {@code Loader.reportGc}: N is what says the new root actually FIRES (an instrument that cannot
     *  fire looks exactly like a condition that never happens), and N &lt; M is what says an idle task's
     *  saved SP was out of its core's band and the range was skipped rather than silently guessed at. */
    static int idleRoots;   // int, not long: bounded by 3 per collection
    static int idleSeen;

    /** Of those ranges, the ones that marked something no other root reached. SCANNED and RETAINED are
     *  different claims and are counted apart: an idle stack holds only smpSchedulerMain's own primitive
     *  locals today, so this reading zero is the expected one and says the fix currently changes no
     *  outcome -- it closes a hole rather than repairing an observed loss. It moves the day a guest
     *  reference reaches one of those frames, which is the whole reason the hole is worth closing. */
    static int idleMarked;

    /**
     * Collections that ran ON a per-core idle task -- the case whose stack-top bracket was wrong. Expected
     * ZERO on any boot today, and the arm above it is therefore DEFENCE rather than a live fix, which is
     * said plainly because "it changed nothing" and "it works" are different claims. THREE independent
     * reasons, each read out of the code rather than assumed:
     * <ul>
     *   <li>{@link VMScheduler#smpSchedulerMain}'s loop is {@code taskYield()} + {@code pauseMs1()}, which
     *       allocates nothing -- and gcCollect is only ever reached from an allocation or Magic.gc.</li>
     *   <li>{@code Heap.alloc} says it outright: {@code if (core != 0 || attempt == 1) break; // secondaries
     *       are never collected}. Each secondary has its own arena, which the sweep never touches.</li>
     *   <li>And if one somehow did, {@link #stopTheWorld} would refuse it: {@code unparked()} counts cores
     *       1..3 that have not parked for THIS generation, WITHOUT excluding the collecting core, so a
     *       secondary collecting itself counts as unparked, times out after ~1 s, and the collection is
     *       SKIPPED before the bracket is ever reached.</li>
     * </ul>
     * Printed per boot so the claim keeps being MEASURED rather than resting on that reading: the day
     * anything on the idle path allocates, this figure moves instead of the hole quietly reopening.
     */
    static int idleGc;

    /**
     * The trace worklist: a block is pushed the moment it is marked ({@link #tryMark}), and scanned once
     * when popped. Scanning pushes whatever it discovers, so the drain ends exactly when the reachable set
     * is closed — where the old fixpoint re-walked the entire heap and re-scanned every marked block on
     * every round until a round changed nothing. That multiplier was the collector's dominant cost: with
     * three rounds, each live {@code Object[]} registry table was probed three times.
     */
    /** Re-scan every marked block in {@code [from,to)} — the overflow path's half of the same fix. */
    private static void scanMarkedIn(Address from, Address to)
    {
        Address o = from;
        while (o.LT(to))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(to) || o.plus(size).LE(o))
            {
                o = to;         // corrupt / out-of-bounds: stop the walk instead of dereferencing garbage
            }
            else
            {
                if ((st & 1L) != 0L)
                {
                    boolean ignored = scanBlock(o, size);
                }
                o = o.plus(size);
            }
        }
    }

    /**
     * Mark CODE reachable from a range of raw memory, without marking anything on the heap. Written to test
     * whether the image's TIBs (which sit outside the statics root scan) hold the stub address that goes
     * missing. They do not — scanning the whole image [0x80000, staticsEnd) changed nothing — so the call
     * is removed and the helper kept, since the next region to suspect will want exactly this shape.
     */
    static void markCodeInRange(long from, long to)
    {
        Address p = Address.fromLong(from & -8L);
        Address end = Address.fromLong(to);
        while (p.LT(end))
        {
            long w = p.loadLong();
            if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT && (w & 3L) == 0L)
            {
                long bit = (w - Heap.CODE_BASE) >> 3;
                Address cw = Address.fromLong(CODE_BITMAP + ((bit >> 6) << 3));
                cw.store(cw.loadLong() | (1L << (int) (bit & 63L)));
            }
            p = p.plus(8);
        }
    }

    /** One past the last byte of whichever region {@code o} lives in. */
    private static Address regionTopOf(Address o)
    {
        return Address.fromLong(regionTopFor(o.toLong()));
    }

    private static void drainMarkStack()
    {
        while (markSp > MARK_STACK)
        {
            markSp = markSp - 8L;
            Address o = Address.fromLong(markSp).loadAddress();   // pushed by tryMark: a verified block base
            long size = o.plus(8).loadLong() & -8L;
            // Bound by the region the object is IN. This guard used to read Heap.PTR_CELL unconditionally,
            // which is the SMALL region's top: once large objects lived above it, every one of them was
            // marked and then silently never scanned, so anything reachable only through a large object was
            // collected while live. That is a whole-heap correctness bug hiding behind a sanity check.
            if (size != 0L && o.plus(size).LE(regionTopOf(o)) && o.plus(size).GT(o))
            {                                          // same guard the heap walk uses: never scan past a
                boolean ignored = scanBlock(o, size);  //   corrupt size. Pushes are the real output here;
            }                                          //   the returned flag only matters to the fallback.
        }
        scanSrc = 0L;
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
            scanMarkedIn(Address.fromLong(Heap.BASE), Address.fromLong(Heap.PTR_CELL).loadAddress());
            scanMarkedIn(Address.fromLong(Heap.LARGE_BASE), Address.fromLong(Heap.LARGE_PTR_CELL).loadAddress());
            int c = 1;
            while (c < 4)
            {
                scanMarkedIn(Heap.arenaBase(c), Address.fromLong(Heap.PTR_CELL + c * 8L).loadAddress());
                c += 1;
            }
            drainMarkStack();                          // anything this pass queued, scanned before re-walking
            if (markOverflow == 0)
            {
                return;                                // the queue kept up: nothing left to discover
            }
        }
    }

    /** Trace worklist: one 8-byte entry per marked block, in the scratch window above the JIT unwind tables
     *  and below the heap cells at {@code 0x03FF0000}. Outside the managed heap on purpose — the collector
     *  must not allocate while collecting.
     *
     *  <p>WAS 0x03E5_0000, i.e. {@code JIT_TABLES + 0x50000} -- correct when the JIT caps were 4096 and the
     *  three tables came to exactly 0x50000. Raising them to 16384 took the tables to 0x140000, so the mark
     *  stack sat 0xF0000 INSIDE them and every deep collection wrote heap pointers over the local and
     *  handler tables. Measured: a local entry reading {lo=0x060A3758, hi=0x060A37C0} where its frame twin
     *  held a valid code range. Now placed above {@code JIT_TABLES + VM.JIT_TABLES_BYTES}, and ScratchMap
     *  registers the tables by their DERIVED size so a future cap change trips the check instead. */
    static final long MARK_STACK     = 0x03F4_0000L;
    static final long MARK_STACK_END = 0x03FF_0000L;
    /** Capacity in entries (~90k after the move above): still more than an order of magnitude above the few
     *  thousand blocks a real collection marks, and {@code markOverflow}'s fixpoint fallback finishes the
     *  trace correctly if it ever fills -- a safety net rather than a regime. */
    static final long MARK_STACK_SLOTS = (MARK_STACK_END - MARK_STACK) / 8L;

    private static long markSp;        // next free worklist slot
    private static int  markOverflow;  // 1 = the queue filled; the fixpoint fallback finishes the trace

    /**
     * The TOP of a secondary core's fixed EL1 stack. The secondary boot stub computes it as
     * {@code movz x2, SEC_STACK_HI, lsl 16} then {@code add x2, x2, core << 20} -- 0x0380_0000 plus one
     * MiB per core, growing DOWN -- and this is DERIVED from that same constant rather than written out,
     * because the two drifting apart gives a root range over the wrong memory and is silent in both
     * directions (too low retains nothing it should, too high traces neighbouring scratch as if it were
     * stack). {@code ScratchMap} reserves the whole 0x0380_0000 band for these.
     */
    private static long secStackTop(int core)
    {
        return ((long) SEC_STACK_HI << 16) + ((long) core << 20);
    }

    /** A root word whose kind is known -- a stack map or descriptor types it as a reference: mark its block
     *  without the block-start bitmap ({@link #tryMarkExact}). For {@link StackRoots}. */
    static void markRoot(long w)
    {
        exactFrom = Magic.readLR();
        probes = probes + 1L;
        boolean ignored = tryMarkExact(w);
    }

    /** A root word that MAY be a reference (a stub frame with no descriptor, a shadow check's rescue): mark it only
     *  if the block-start bitmap says it is a block base. */
    static void markRootMaybe(long w)
    {
        probes = probes + 1L;
        boolean ignored = tryMark(w);
    }

    /** A range of root words with no type: the conservative scan. For {@link StackRoots}'s fallbacks. */
    static void markRootRange(long lo, long hi)
    {
        boolean ignored = markRange(Address.fromLong(lo), Address.fromLong(hi));
    }

    /** True if block {@code b} carries this collection's mark. For the shadow check. */
    static boolean isMarked(long b)
    {
        return (Address.fromLong(b).plus(8).loadLong() & 1L) != 0L;
    }

    /** {@link #markRange} for {@link PreciseTrace}'s conservative fallback. */
    static void markWords(Address lo, Address hi)
    {
        boolean ignored = markRange(lo, hi);
    }

    /** Mark every heap object pointed to by an 8-aligned word in [lo,hi). Returns true if any newly marked. */
    private static boolean markRange(Address lo, Address hi)
    {
        boolean any = false;
        if (hi.GT(lo))
        {
            probes = probes + ((hi.toLong() - lo.toLong()) >> 3);
        }
        while (lo.LT(hi))
        {
            long w = lo.loadLong();
            if (tryMark(w))
            {
                any = true;
            }
            hcDataProbe = 1;
            if (tryMark(w - 16L))                      // a raw-data payload pointer (Heap.allocData) sits one
            {                                          //   header past its block base: probe that base too
                any = true;
            }
            hcDataProbe = 0;
            lo = lo.plus(8);
        }
        return any;
    }

    /** Mark {@code w} if it IS an unmarked heap block base; true if newly marked. Verified against the
     *  block-start bitmap ({@link #buildBlockBitmap}) — a status-word heuristic alone would sometimes
     *  accept an object INTERIOR and the {@code +1} mark write would corrupt real data (a stored pointer
     *  became odd and faulted the next dispatch). With the bitmap, mark writes only ever touch genuine
     *  status words. The CONSERVATIVE mark: a word that may or may not be a reference. A word whose kind is
     *  known goes to {@link #tryMarkExact}, which needs no bitmap (frames/GC arc S6d-3). */
    private static boolean tryMark(long w)
    {
        if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
        {
            // A pointer INTO the code arena: record the hit so the sweep can tell which compiled methods are
            // still reachable.
            long bit = (w - Heap.CODE_BASE) >> 3;
            Address cw = Address.fromLong(CODE_BITMAP + ((bit >> 6) << 3));
            long had = cw.loadLong();
            long mask = 1L << (int) (bit & 63L);
            if (HeapShadow.rescan != 0 && (had & mask) == 0L)
            {
                HeapShadow.missedCode(w);              // gcheap=2: code only the conservative rescan reached
            }
            cw.store(had | mask);
            return false;                              // code is not a heap block; nothing to mark or trace
        }
        // A heap pointer is in the small region, the large region, or (since S5a) one of cores 1-3's arenas.
        // Getting this wrong in the permissive direction only over-retains; getting it wrong in the strict
        // direction sweeps live objects, so every region MUST be included the moment anything is allocated there.
        long regionTop = regionTopFor(w);
        if (regionTop != 0L && (w & 7L) == 0L && isBlockBase(w)
            && (Address.fromLong(w).plus(8).loadLong() & Heap.FREE_FLAG) == 0L)   // a block base, and not a free one
        {
            return markBlock(w, regionTop);
        }
        return false;
    }

    /** Set the mark on block {@code w} (in a region ending at {@code regionTop}) and queue it, after one last check
     *  that its status word describes a block that fits; true if newly marked. */
    private static boolean markBlock(long w, long regionTop)
    {
        Address block = Address.fromLong(w);       // in a heap region, aligned, a block base
        long st = block.plus(8).loadLong();
        // GUARD: the block bitmap (a conservative probe) or a type (an exact word) said this address is a block
        // base. Verify it before writing to it --
        // setting a mark bit on a word that is NOT a block turns data into data+1, which is exactly the
        // "blob 0x1" the loader reports much later, with nothing left to say where it came from. Check
        // here, where the address and its caller are still in hand.
        long sz = st & -8L;
        if (sz < 16L || w + sz > regionTop)
        {
            if (falseBaseSeen == 0)
            {
                falseBaseSeen = 1;
                Uart.write(Magic.bytes("\nFALSE BLOCK BASE addr="));
                printHex(w);
                Uart.write(Magic.bytes(" status="));
                printHex(st);
                Uart.write(Magic.bytes(" region="));
                printHex(w >= Heap.LARGE_LIMIT ? 2L : (w >= Heap.LARGE_BASE ? 1L : 0L));
                Uart.write(Magic.bytes(" top="));
                printHex(regionTop);
                Uart.write(Magic.bytes(" largeTop="));
                printHex(Address.fromLong(Heap.LARGE_PTR_CELL).loadLong());
                Uart.putc(0x0A);
                Uart.write(Magic.bytes("  marked from "));
                StackTrace.printFrameAt(Address.fromLong(Magic.readLR())); // which scan handed us this word
                Uart.putc(0x0A);
                if (exactFrom != 0L)
                {
                    Uart.write(Magic.bytes("  an EXACT mark, from "));
                    StackTrace.printFrameAt(Address.fromLong(exactFrom - 4L));
                    Uart.putc(0x0A);
                }
            }
            return false;                          // refuse to mark it: not a block
        }
        if (GenCensus.on != 0)
        {
            GenCensus.edge(w);                         // gcgen=1: every edge, to a marked block or not
        }
        if ((st & 1L) == 0L)
        {
            if (HeapShadow.rescan != 0)
            {
                block.plus(8).store(st + 3L);      // mark bit + "marked by the heap shadow's rescan"
                HeapShadow.missed(w);
            }
            else
            {
                block.plus(8).store(st + 1L);      // set mark bit
            }
            if (heapCensus != 0)
            {
                hcMarks[hcCat] = hcMarks[hcCat] + 1L;
                if (hcDataProbe != 0)
                {
                    hcMarksData[hcCat] = hcMarksData[hcCat] + 1L;
                }
            }
            if (markSp < MARK_STACK_END)           // ... and queue it for scanning exactly once
            {
                Address.fromLong(markSp).store(block);
                markSp = markSp + 8L;
            }
            else
            {
                markOverflow = 1;                  // queue full: the fixpoint fallback will catch up
            }
            return true;
        }
        return false;
    }

    /** Mark {@code w}, a word a precise trace knows is a reference (0, or a block base): {@link #tryMarkExact}. */
    static void markExact(long w)
    {
        exactFrom = Magic.readLR();
        boolean ignored = tryMarkExact(w);
    }

    /**
     * Mark {@code w}, which a type says is a reference -- 0 or a block base, never an interior pointer -- WITHOUT the
     * block-start bitmap (frames/GC arc S6d-3). What still guards the mark write is cheap: the word must lie in a
     * heap region, be 8-aligned, and name a status word whose size fits the region. With {@code gcverify=1} the
     * bitmap is built anyway and a word it does not call a block base is reported and refused: the measurement that
     * says exact words really are exact.
     */
    private static boolean tryMarkExact(long w)
    {
        long from = exactFrom;                         // who asked, for a report; cleared for the next caller
        exactFrom = 0L;
        if (w == 0L)
        {
            return false;
        }
        if (Nursery.fullTrace != 0 && Nursery.contains(w))
        {
            Nursery.fullEdge(w);                       // S7-4a: a resident -- not this region's to mark
            return false;
        }
        if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT)
        {
            return tryMark(w);                         // a code address: the code bitmap, no heap block
        }
        long regionTop = regionTopFor(w);
        if (regionTop == 0L || (w & 7L) != 0L)
        {
            return false;
        }
        exactMarks = exactMarks + 1L;
        boolean free = (Address.fromLong(w).plus(8).loadLong() & Heap.FREE_FLAG) != 0L;   // a dangling reference
        if (free || verifyExact != 0 && !isBlockBase(w))
        {
            exactBad = exactBad + 1L;
            if (exactBadPrinted < 8)
            {
                exactBadPrinted += 1;
                Uart.write(Magic.bytes(free ? "\nGCVERIFY: an exact word names a FREE block w="
                                            : "\nGCVERIFY: an exact word is not a block base w="));
                printHex(w);
                Uart.write(Magic.bytes(" status="));
                printHex(Address.fromLong(w).plus(8).loadLong());
                Uart.write(Magic.bytes(" header="));
                printHex(Address.fromLong(w).loadLong());
                Uart.write(Magic.bytes(" w-16 base="));
                printDec(isBlockBase(w - 16L) ? 1 : 0);
                if (exactSrc > 0L)
                {
                    Uart.write(Magic.bytes(" in block "));
                    printHex(exactSrc);
                    Uart.write(Magic.bytes(" layout "));
                    printHex(Address.fromLong(exactSrc).loadLong());
                    long ssz = Address.fromLong(exactSrc).plus(8).loadLong() & -8L;
                    Uart.write(Magic.bytes(" size "));
                    printHex(ssz);
                    Uart.write(Magic.bytes("\n  words:"));
                    long q = 16L;
                    while (q < ssz && q < 16L + 8L * 24L)
                    {
                        Uart.putc(0x20);
                        printHex(Address.fromLong(exactSrc).plus(q).loadLong());
                        q += 8L;
                    }
                    long b = w & -8L;
                    long steps = 0L;
                    while (b > Heap.BASE && steps < 65536L && !isBlockBase(b))
                    {
                        b -= 8L;
                        steps += 1L;
                    }
                    Uart.write(Magic.bytes("\n  inside block "));
                    printHex(b);
                    Uart.write(Magic.bytes(" header "));
                    printHex(Address.fromLong(b).loadLong());
                    Uart.write(Magic.bytes(" status "));
                    printHex(Address.fromLong(b).plus(8).loadLong());
                }
                else if (exactSrc < 0L)
                {
                    Uart.write(Magic.bytes(" in statics slot "));
                    printHex(-exactSrc);
                }
                Uart.write(Magic.bytes("\n  marked from "));
                if (from != 0L)
                {
                    StackTrace.printFrameAt(Address.fromLong(from - 4L));
                }
                else
                {
                    Uart.write(Magic.bytes("<the code-root table or a JIT map entry>"));
                }
                Uart.putc(0x0A);
            }
            return false;
        }
        exactFrom = from;                              // markBlock's FALSE BLOCK BASE report names it
        boolean marked = markBlock(w, regionTop);
        exactFrom = 0L;
        return marked;
    }

    /** The caller of {@link #markRoot}/{@link #markExact}, for a gcverify report; 0 for a direct exact mark. */
    private static long exactFrom;
    /** For a gcverify report: the allocData struct {@link PreciseTrace} is reading, or minus a statics slot. */
    static long exactSrc;
    /** The heap block whose words the trace is reading (0 for a root): a source the nursery remembers (S7-4a). */
    static long scanSrc;

    /** {@code gcverify=1}: build the block-start bitmap every collection and check every exact mark against it. */
    static int verifyExact;
    /** Exact marks attempted (words in a heap region), and those gcverify found were no block base. */
    static long exactMarks;
    static long exactBad;
    private static int exactBadPrinted;
    /** 1 once this collection has built the block-start bitmap; how many collections built it, over the boot. */
    private static int bitmapBuilt;
    static long bitmapBuilds;

    /** Build the block-start bitmap if this collection has not yet: a conservative probe needs it. */
    private static void ensureBitmap()
    {
        if (bitmapBuilt == 0)
        {
            bitmapBuilt = 1;
            bitmapBuilds = bitmapBuilds + 1L;
            buildBlockBitmap(Address.fromLong(Heap.PTR_CELL).loadAddress());
        }
    }

    /** One probe of the heap shadow's conservative rescan ({@link HeapShadow#missed} hears of a new mark). */
    static void markRescan(long w)
    {
        probes = probes + 1L;
        boolean ignored = tryMark(w);
    }

    /** One bit per 8 bytes of the 32 MiB code arena (512 KiB), recording every word seen pointing into it.
     *  A compiled method is reachable iff any bit inside its extent is set — the code-side equivalent of a
     *  mark bit, kept out of line because the arena has no headers to hold one. At {@link Heap#IMAGE_CEILING},
     *  below the scratch band, since the arena moved and doubled. */
    static final long CODE_BITMAP = 0x02F0_0000L;
    static final long CODE_BITMAP_END = 0x02F8_0000L;

    /** Bytes of compiled code still reachable at the last collection, and the total ever compiled. The ratio
     *  decides whether reclaiming code is worth building: if nearly all of it is live, a code collector buys
     *  nothing and the arena simply has to be big enough. */
    static long codeLive;
    static long codeUsed;
    /** Bytes of unreachable compiled code the last collection returned to the code free list. */
    static long codeFreed;

    /**
     * Whether code SWEEPS ACTUALLY FIND ANYTHING, which is the second half of the collect-during-a-batch
     * question. Inc 1 showed 75% of arena growth happens within 64 KiB of a sweep -- no room to collect
     * sooner. These answer the remaining 12%: at windows where growth DID happen long after a sweep, did
     * the next sweep free anything? {@code lateFreed} is the bytes it found; if that stays near zero, the
     * garbage a mid-batch collection would look for does not exist, and the lever is closed.
     */
    static long codeFreedTotal;
    static long sweepsTotal;
    static long sweepsZeroFreed;
    static long lateFreed;
    static long lateSweeps;
    /** Lowest and highest-end swept address this collection, so the I-cache maintenance over the zeroed
     *  buffers can be one pass instead of one per block: {@link Heap#publishCode} invalidates the WHOLE
     *  instruction cache each call, so calling it per method would be quadratic in nothing useful. */
    private static long zeroLo;
    private static long zeroHi;
    /** 1 = actually sweep unreachable code, not merely count it. Off until the code rewind is retired:
     *  while the rewind runs, a batch's code is discarded wholesale and sweeping it would be busywork. */
    static int sweepCode;
    /** No code below this address is ever swept — the boot vector table, the scheduler's switch stubs and
     *  the run trampoline are reached from hardware registers and stub-internal branches the collector
     *  cannot see. Set to the loader's code watermark, the same line the rewind never crossed. */
    static long codeSweepFloor;

    /** How many collections exhausted the mark stack and needed the fixpoint fallback. */
    public static long overflows;

    /** One-shot latch for the false-block-base report, so a recurring case does not flood the UART. */
    private static int falseBaseSeen;

    /** Walk the code-block registry and total the blocks with a bit set. Runs after the trace has drained,
     *  so every reachable code pointer has been seen. */
    /** This sweep's freed code ranges, and a scan for anything still pointing into one. */
    private static final long FREED_RANGES = 0x0376_0000L;
    private static long freedN2;

    /**
     * Every code range ever swept, kept for the life of the boot: {start, end, collection}. When a fault
     * lands in the code arena the reporter looks the PC up here, so "executing zeros at 0x0200EE50" becomes
     * "that buffer was freed at collection N" — which says whether a swept buffer is the story at all, and
     * points at the emit site that still held its address. 1 MiB = 43,690 entries; recording stops rather
     * than wrapping, since the earliest sweeps are the ones a later fault is most likely to blame.
     */
    private static final long SWEPT_LOG = 0x037C_0000L;
    private static final long SWEPT_LOG_END = 0x0380_0000L;
    public static long sweptLogN;
    public static long collections;

    /**
     * Report whether {@code pc} lies in a code buffer some collection swept, and when. Called by the fault
     * reporter: a PC in a swept range means the collector freed code that was still being dispatched, which
     * is a different bug from a wild branch and needs to be told apart from one.
     */
    public static void reportSweptPc(long pc)
    {
        // The instruction word at the pc is the discriminator: 0 means we are executing memory the sweep
        // zeroed, anything else means the space was reused and the fault is not what it looks like. The
        // block's CURRENT state and pin bit say whether the collector could have taken it at all.
        Uart.write(Magic.bytes("  insn at pc = "));
        printHex(Address.fromLong(pc).loadLong() & 0xFFFFFFFFL);
        Uart.write(Magic.bytes("  pinned="));
        printHex((long) Heap.codePinnedIn(Address.fromLong(pc & -8L), 8L));
        Uart.write(Magic.bytes("  block now "));
        int st = Heap.codeBlockFreeAt(Address.fromLong(pc));
        if (st < 0)
        {
            Uart.write(Magic.bytes("UNREGISTERED\n"));
        }
        else if (st == 1)
        {
            Uart.write(Magic.bytes("FREE\n"));
        }
        else
        {
            Uart.write(Magic.bytes("ALLOCATED\n"));
        }
        long i = 0;
        while (i < sweptLogN)
        {
            Address e = Address.fromLong(SWEPT_LOG + i * 32L);
            long start = e.loadLong();
            long end = e.plus(8).loadLong();
            if (pc >= start && pc < end)
            {
                Uart.write(Magic.bytes("  PC IS IN SWEPT CODE: buffer "));
                printHex(start);
                Uart.putc(0x2D);
                printHex(end);
                Uart.write(Magic.bytes(" freed at collection "));
                VM.printDec((int) e.plus(16).loadLong());
                Uart.write(Magic.bytes(" of "));
                VM.printDec((int) collections);
                long sidx = e.plus(24).loadLong();
                if (sidx >= 0L)
                {
                    Uart.write(Magic.bytes("\n  it was the deferral stub for "));
                    LazyCompiler.printLazyName(sidx);        // the method whose stub this was
                }
                Uart.putc(0x0A);
                return;
            }
            i += 1L;
        }
        Uart.write(Magic.bytes("  PC is NOT in any swept buffer ("));
        VM.printDec((int) sweptLogN);
        Uart.write(Magic.bytes(" ranges logged)\n"));
    }

    /** 1 if {@code w} lands in a range this sweep just freed. */
    private static int inFreedRange(long w)
    {
        long i = 0;
        while (i < freedN2)
        {
            Address r = Address.fromLong(FREED_RANGES + i * 16L);
            if (w >= r.loadLong() && w < r.plus(8).loadLong())
            {
                return 1;
            }
            i += 1L;
        }
        return 0;
    }

    /**
     * Walk the heap for words pointing into code this sweep just freed, and name the first holder. If the
     * collector freed a method something still dispatches through, the holder is the object whose scanning
     * failed to keep it alive — which is the fact the fault, minutes later in an unnamed buffer, cannot say.
     */
    /**
     * The same question asked of raw memory rather than of heap blocks: does any word in {@code [from,to)}
     * point INTO a range this sweep just freed? The heap-walking version only ever looked at heap objects,
     * which is why it came up empty — an interior code address (a return address, a saved handler PC, a
     * table keyed by machine address) lives on the stack or in the loader's scratch tables, not in an object.
     */
    private static void findStaleCodeInRaw(long from, long to, long tag)
    {
        Address p = Address.fromLong(from & -8L);
        Address end = Address.fromLong(to);
        while (p.LT(end) && freedN2 > 0L)
        {
            long w = p.loadLong();
            if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT && inFreedRange(w) != 0
                    && w != zeroLo && w != zeroHi)
            {
                // Skip the collector's OWN span bounds. zeroLo/zeroHi are statics holding the low and high
                // address of the code this very sweep zeroed, so they point into freed code by definition.
                // Every one of the ~20 "stale slots" this probe reported was one of them: the instrument
                // was scanning its own bookkeeping and reporting itself.
                Uart.write(Magic.bytes("\nSTALE CODE PTR in raw region tag="));
                printHex(tag);
                Uart.write(Magic.bytes(" at="));
                printHex(p.toLong());
                Uart.write(Magic.bytes(" target="));
                printHex(w);
                Uart.write(Magic.bytes(" stub="));
                printHex(LazyCompiler.stubIdxAt(Address.fromLong(w)));
                Uart.putc(0x0A);
                rawStaleSeen = rawStaleSeen + 1L;      // report and CONTINUE: halting here hides whether the
                if (staleTabN < 64L)                   //   fix downstream actually works. Remember the slot
                {                                      //   so the NEXT collection can ask whether it is
                    Address s = Address.fromLong(STALE_TAB + staleTabN * 16L);   // still there.
                    s.store(p);
                    s.plus(8).store(w);
                    staleTabN = staleTabN + 1L;
                }
                return;
            }
            p = p.plus(8);
        }
    }

    private static void findStaleCodeHolders(Address from, Address to)
    {
        Address o = from;
        while (o.LT(to) && freedN2 > 0L)
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(to) || o.plus(size).LE(o))
            {
                o = to;
            }
            else if ((st & 1L) == 0L)
            {
                o = o.plus(size);                      // dead holder: a dead cell pointing at dead code is
            }                                          //   exactly what reclamation is supposed to produce
            else
            {
                Address z = o.plus(16);
                while (z.LT(o.plus(size)))
                {
                    long w = z.loadLong();
                    if (w >= Heap.CODE_BASE && w < Heap.CODE_LIMIT && inFreedRange(w) != 0)
                    {
                        Uart.write(Magic.bytes("\nSTALE CODE PTR holder="));
                        printHex(o.toLong());
                        Uart.write(Magic.bytes(" at+"));
                        printHex(z.toLong() - o.toLong());
                        Uart.write(Magic.bytes(" tib="));
                        printHex(o.loadLong());
                        Uart.write(Magic.bytes(" size="));
                        printHex(size);
                        Uart.write(Magic.bytes(" target="));
                        printHex(w);
                        Uart.write(Magic.bytes("\n  freed method: "));
                        StackTrace.printFrameAt(Address.fromLong(w));
                        Uart.putc(0x0A);
                        while (true) { Magic.wfe(); }
                    }
                    z = z.plus(8);
                }
                o = o.plus(size);
            }
        }
    }

    private static long checkedFrees;
    private static long staticsScans;
    public static long rawStaleSeen;
    /**
     * Slots caught holding a pointer into just-freed code, carried to the NEXT collection to answer the
     * question the count alone cannot: is this a transient slot that gets rewritten before anyone reads it,
     * or a dangling pointer sitting in memory waiting to be called? {slotAddr, target} pairs.
     */
    private static final long STALE_TAB = 0x0374_0400L;
    private static long staleTabN;
    /** Slots that STILL held the same freed pointer one collection later — the ones that are not benign. */
    public static long stalePersisted;
    /** Slots that had been overwritten by then — transient, and harmless. */
    public static long staleRewritten;

    /** Re-examine last collection's stale slots before this one disturbs anything. */
    private static void recheckStaleSlots()
    {
        long i = 0;
        while (i < staleTabN)
        {
            Address s = Address.fromLong(STALE_TAB + i * 16L);
            Address slot = s.loadAddress();
            Address was = s.plus(8).loadAddress();
            if (slot.loadAddress().EQ(was))
            {
                stalePersisted = stalePersisted + 1L;  // still pointing at code the collector freed
                if (stalePersisted <= 4L)              // name the first few: which slot, and what it was
                {
                    Uart.write(Magic.bytes("  PERSISTED STALE slot="));
                    printHex(slot);
                    Uart.write(Magic.bytes(" target="));
                    printHex(was);
                    Uart.write(Magic.bytes(" nowFree="));
                    printHex((long) Heap.codeBlockFreeAt(was));
                    Uart.write(Magic.bytes(" stubIdx="));
                    printHex(LazyCompiler.stubIdxAt(was));
                    Uart.putc(0x0A);
                    reportSweptPc(was.toLong());       // ... and which buffer it was, from the swept log
                }
            }
            else
            {
                staleRewritten = staleRewritten + 1L;  // republished before anyone could call it
            }
            i += 1L;
        }
        staleTabN = 0L;
    }
    /** Enable the stale-code-pointer probes. Expensive; see the call site. */
    static final boolean STALE_PROBES = false;

    private static void measureCodeLiveness()
    {
        freedN2 = 0L;
        checkedFrees = 0L;
        codeLive = 0L;
        codeUsed = 0L;
        codeFreedTotal = codeFreedTotal + codeFreed;   // roll up the PREVIOUS pass before clearing
        sweepsTotal = sweepsTotal + 1L;
        if (codeFreed == 0L)
        {
            sweepsZeroFreed = sweepsZeroFreed + 1L;
        }
        if (Heap.lateGrowthSeen != 0L)                 // this window contained growth long after a sweep
        {
            lateFreed = lateFreed + codeFreed;
            lateSweeps = lateSweeps + 1L;
            Heap.lateGrowthSeen = 0L;
        }
        codeFreed = 0L;
        zeroLo = 0L;
        zeroHi = 0L;
        long i = 0;
        while (i < Heap.codeBlockN)
        {
            Address e = Address.fromLong(Heap.CODE_BLOCKS + i * 16L);
            long start = e.loadLong();
            long sz = e.plus(8).loadLong();            // status word: size plus the FREE/YOUNG flags
            long size = sz & -8L;                      // ... and the size alone, which is what every
            if ((sz & Heap.CODE_FREE) != 0L)           //   length calculation below must use
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
                long bw = Address.fromLong(CODE_BITMAP + ((b >> 6) << 3)).loadLong();
                if ((bw & (1L << (int) (b & 63L))) != 0L)
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
            if ((sz & Heap.CODE_YOUNG) != 0L)
            {
                // Allocated since the last collection: its address may still be in flight, held only in a
                // register or a half-built structure. Keep it this pass and clear the flag, so the next
                // collection judges it on reachability like anything else.
                e.plus(8).store(sz & ~Heap.CODE_YOUNG);
                codeLive = codeLive + size;
                hit = 1;
            }
            if (hit == 0 && Heap.codePinnedIn(Address.fromLong(start), size) != 0)
            {
                codeLive = codeLive + size;            // branched to directly by another buffer: not free
                hit = 1;
            }
            if (hit == 0 && sweepCode != 0 && start >= codeSweepFloor)
            {
                // Unreachable compiled code. Sweeping it needs the JIT unwind entries keyed to its address
                // range dropped in the same breath: they outlive the method otherwise and would answer for
                // whatever is compiled at that address next -- the aliasing dropJitTablesAbove prevents
                // under the batch rewind.
                if (SWEPT_LOG + sweptLogN * 32L + 32L <= SWEPT_LOG_END)
                {
                    Address log = Address.fromLong(SWEPT_LOG + sweptLogN * 32L);
                    log.store(start);
                    log.plus(8).store(start + size);
                    log.plus(16).store(collections);
                    log.plus(24).store(LazyCompiler.stubIdxAt(Address.fromLong(start)));
                    sweptLogN = sweptLogN + 1L;         // ... and WHICH stub, if it is one
                }
                if (freedN2 < 32L)                     // remember this sweep's freed ranges, so the probe
                {                                      //   below can ask who still points into one
                    Address r = Address.fromLong(FREED_RANGES + freedN2 * 16L);
                    r.store(start);
                    r.plus(8).store(start + size);
                    freedN2 = freedN2 + 1L;
                }
                VM.dropJitTablesIn(Address.fromLong(start), Address.fromLong(start + size));
                Loader.dropCodeRootsIn(start, start + size);   // its baked-in addresses die with it
                zeroBuffer(start, size);                       // stale instructions are worse than zeros
                Heap.freeCodeBlock(i);
                codeFreed = codeFreed + size;
            }
            i += 1;
        }
        if (sweepCode != 0)
        {
            // Put the pieces back. takeFreeCode splits on every reuse and nothing was ever its inverse, so
            // the free list decayed into crumbs; merging adjacent free blocks here is what the data sweep's
            // run merging does for the heap, and it runs after the sweep so this pass sees every block the
            // collection freed.
            Heap.coalesceCodeFree();
        }
        if (zeroHi != 0L)
        {
            // One I-cache pass over the whole swept span. Heap.publishCode invalidates the entire
            // instruction cache per call, so per-buffer publishing would repeat that thousands of times to
            // no benefit; the span may also cover live methods, which costs those a refetch and nothing else.
            Heap.publishCode(Address.fromLong(zeroLo), Address.fromLong(zeroHi));
        }
    }

    /**
     * Zero a swept code buffer, so a dangling pointer into it meets zeros rather than a previous method's
     * instructions. The batch rewind used to give this for free — it zeroed everything above the code
     * watermark — and losing it was the one property increment 11 did not carry over.
     *
     * <p>It narrows a window rather than closing a hole: the buffer is unreachable by the time it is swept,
     * and reuse overwrites it with real code soon after. What it buys is the FAILURE MODE. A stale code
     * pointer that survives a collection lands on {@code 0x00000000}, which decodes as UDF and traps into
     * the fault reporter naming the address; without it the same bug branches into the middle of whatever
     * method used to live there and runs it, which is unbounded and looks like anything at all.
     *
     * <p>Deliberately not conditional on the sweep being right. If the collector ever frees a method that is
     * still live, this converts a run that limps into one that stops at the instruction that did it — which
     * is what the arc wants while code reclamation is young.
     */
    private static void zeroBuffer(long start, long size)
    {
        Address z = Address.fromLong(start);
        long end = start + size;
        Address zEnd = Address.fromLong(end);
        while (z.LT(zEnd))
        {
            z.store(0L);
            z = z.plus(8);
        }
        if (zeroHi == 0L || start < zeroLo)
        {
            zeroLo = start;
        }
        if (end > zeroHi)
        {
            zeroHi = end;
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
     *  the secondary stacks and below the heap cells). Built by the first conservative probe of a collection
     *  ({@link #ensureBitmap}; every collection with gcverify=1) -- since frames/GC arc S6d-3 an exact mark does not
     *  need it, and a collection with no fallback never builds it. */
    static final long MARK_BITMAP = 0x03B0_0000L;

    /** Walk the heap by status sizes (sound — every allocation carries an intact header) and record each block
     *  base in the bitmap, so conservative probes can be verified exactly. Free blocks are bases too: a probe
     *  refuses them by {@link Heap#FREE_FLAG}. */
    private static void buildBlockBitmap(Address stop)
    {
        Address z = Address.fromLong(MARK_BITMAP);
        Address zEnd = z.plus(0x30_0000);
        while (z.LT(zEnd))
        {
            z.store(0L);
            z = z.plus(8);
        }
        markBlockBases(Address.fromLong(Heap.BASE), stop, MARK_BITMAP, Heap.BASE);
        markBlockBases(Address.fromLong(Heap.LARGE_BASE), Address.fromLong(Heap.LARGE_PTR_CELL).loadAddress(), MARK_BITMAP,
                       Heap.BASE);
        int c = 1;
        while (c < 4)
        {
            // Each secondary arena's bitmap sits in its own top 1 MiB (Heap.arenaBitmap); clear only the part that
            // covers what the arena has used -- one bitmap byte per 64 heap bytes.
            Address base = Heap.arenaBase(c);
            Address top = Address.fromLong(Heap.PTR_CELL + c * 8L).loadAddress();
            Address bz = Address.fromLong(Heap.arenaBitmap(c));
            Address bzEnd = bz.plus(((top.toLong() - base.toLong()) >> 6) + 8L);
            while (bz.LT(bzEnd))
            {
                bz.store(0L);
                bz = bz.plus(8);
            }
            markBlockBases(base, top, Heap.arenaBitmap(c), base.toLong());
            c += 1;
        }
    }

    /** Survivors, freed blocks and bytes of cores 1-3's arenas over the last collection (for /gc and gcLog). */
    static long arenaLive;
    static long arenaFreed;
    static long arenaTrimmed;

    /**
     * Sweep secondary core {@code c}'s arena (frames/GC arc S5a) the way the small heap is swept: unmark survivors,
     * coalesce each run of dead blocks into core {@code c}'s OWN free lists -- only core {@code c} allocates from
     * them, and it is parked -- and lower its bump pointer past the last survivor.
     */
    private static void sweepArena(int c)
    {
        if (c == 1)
        {
            arenaLive = 0L;
            arenaFreed = 0L;
            arenaTrimmed = 0L;
        }
        Heap.clearCoreFreeLists(c);
        Address base = Heap.arenaBase(c);
        Address ptrCell = Address.fromLong(Heap.PTR_CELL + c * 8L);
        Address stop = liveEndIn(base, ptrCell.loadAddress());
        long trim = ptrCell.loadLong() - stop.toLong();
        arenaTrimmed = arenaTrimmed + trim;
        reclaimed = reclaimed + trim;
        ptrCell.store(stop);
        Address o = base;
        Address runStart = Address.zero();
        long runSize = 0L;
        while (o.LT(stop))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(stop) || o.plus(size).LE(o))
            {
                o = stop;                              // corrupt: stop (the small heap's guard)
            }
            else
            {
                if ((st & 1L) != 0L)
                {
                    if (runSize != 0L)
                    {
                        Heap.addFreeTo(c, runStart, runSize);
                        runStart = Address.zero();
                        runSize = 0L;
                    }
                    arenaLive = arenaLive + size;
                    liveBytes = liveBytes + size;
                    o.plus(8).store(size);             // clear the mark
                }
                else
                {
                    arenaFreed = arenaFreed + size;
                    reclaimed = reclaimed + size;
                    if (runSize == 0L)
                    {
                        runStart = o;
                    }
                    runSize = runSize + size;
                }
                o = o.plus(size);
            }
        }
        if (runSize != 0L)
        {
            Heap.addFreeTo(c, runStart, runSize);
        }
    }

    /**
     * Sweep the large region. Same walk as the small heap — every block carries its size — but dead runs go
     * back on the large free list, where they stay page multiples and so remain exactly reusable by any
     * later large request. Coalescing matters here for the same reason it does below, with one difference:
     * a merged run of page multiples is still a page multiple, so merging cannot produce a size that no
     * request can land on. That is the whole point of segregating them.
     */
    private static void sweepLargeRegion()
    {
        long liveBefore = liveBytes;                   // the small sweep has already added its own
        Address o = Address.fromLong(Heap.LARGE_BASE);
        Address top = Address.fromLong(Heap.LARGE_PTR_CELL).loadAddress();
        Address runStart = Address.zero();
        long runSize = 0L;
        while (o.LT(top))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(top) || o.plus(size).LE(o))
            {
                o = top;                               // corrupt: stop, as the small sweep does
            }
            else
            {
                if ((st & 1L) != 0L)
                {
                    if (runSize != 0L)
                    {
                        Heap.addFreeLarge(runStart, runSize);
                        runStart = Address.zero();
                        runSize = 0L;
                    }
                    liveBytes = liveBytes + size;
                    o.plus(8).store(size);             // clear the mark
                }
                else
                {
                    reclaimed = reclaimed + size;
                    if (runSize == 0L)
                    {
                        runStart = o;
                    }
                    runSize = runSize + size;
                }
                o = o.plus(size);
            }
        }
        if (runSize != 0L)
        {
            // The region ended on dead blocks. Hand that tail back by lowering the bump pointer instead of
            // threading it onto the free list: the bump only ever rose before this, which is why a Pi run
            // drove the region to 64.0 MB -- its entire reservation -- during Lisp, with no headroom left
            // and allocLarge's second failure being a halt. Everything below the highest survivor stays on
            // the free list as before; only the trailing run is returned.
            //
            // Increment 10 measured a trailing-run trim recovering NOTHING for the code arena, because live
            // methods are scattered by construction. This region is a different shape -- a handful of large
            // objects rather than thousands of small ones -- so the trim is measured here rather than
            // assumed: largeTrimmed says what it actually returns.
            Address.fromLong(Heap.LARGE_PTR_CELL).store(runStart);
            largeTrimmed = largeTrimmed + runSize;
        }
        largeLive = liveBytes - liveBefore;            // this region's live set alone
    }

    /** Bytes returned to the large region by lowering its bump pointer, and the live set it kept. */
    public static long largeTrimmed;
    public static long largeLive;

    /** Record the base of every block in {@code [from,to)}. Both regions are walkable the same way: every
     *  block, allocated or free, carries its size in its status word. */
    private static void markBlockBases(Address from, Address to, long bitmap, long origin)
    {
        Address o = from;
        while (o.LT(to))
        {
            long st = o.plus(8).loadLong();
            long size = st & -8L;
            if (size == 0L || o.plus(size).GT(to) || o.plus(size).LE(o))
            {
                o = to;                                // corrupt: stop (matches the sweep's guard)
            }
            else
            {
                long idx = (o.toLong() - origin) >> 3;
                Address w = Address.fromLong(bitmap + ((idx >> 6) << 3));
                w.store(w.loadLong() | (1L << (int) (idx & 63L)));
                o = o.plus(size);
            }
        }
    }

    /** Whether {@code w} lies in the small or the large heap region (allocated part). */
    static boolean inHeap(long w)
    {
        return regionTopFor(w) != 0L;
    }

    /**
     * The allocated top of the heap region holding {@code w}, or 0 when no region does: core 0's small heap, the
     * large region, or one of cores 1-3's arenas (collected since S5a). Getting this wrong in the strict
     * direction sweeps live objects, so every region anything is allocated in must be here.
     */
    static long regionTopFor(long w)
    {
        if (w < Heap.BASE)
        {
            return 0L;
        }
        if (w < Heap.LARGE_LIMIT)
        {
            long top = w < Heap.LARGE_BASE ? Address.fromLong(Heap.PTR_CELL).loadLong()
                                           : Address.fromLong(Heap.LARGE_PTR_CELL).loadLong();
            return w < top ? top : 0L;
        }
        long c = ((w - Heap.LARGE_LIMIT) / Heap.ARENA_SLOT) + 1L;
        if (c > 3L)
        {
            return 0L;
        }
        long top = Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong();
        return w < top ? top : 0L;
    }

    /** What the conservative scan would make of {@code w}: 1 = a block base, 2 = an allocData payload one header
     *  past a block base, 0 = neither. The same test {@link #markRange} applies, without marking. Valid between
     *  {@link #buildBlockBitmap} and the sweep. */
    static int liveKind(long w)
    {
        if ((w & 7L) != 0L)
        {
            return 0;
        }
        if (inHeap(w) && isBlockBase(w))
        {
            return 1;
        }
        if (inHeap(w - 16L) && isBlockBase(w - 16L))
        {
            return 2;
        }
        return 0;
    }

    private static boolean isBlockBase(long w)
    {
        ensureBitmap();
        long bitmap = MARK_BITMAP;
        long origin = Heap.BASE;
        if (w >= Heap.LARGE_LIMIT)
        {
            int c = (int) ((w - Heap.LARGE_LIMIT) / Heap.ARENA_SLOT) + 1;   // callers have checked regionTopFor
            bitmap = Heap.arenaBitmap(c);
            origin = Heap.arenaBase(c).toLong();
        }
        long idx = (w - origin) >> 3;
        Address word = Address.fromLong(bitmap + ((idx >> 6) << 3));
        return (word.loadLong() & (1L << (int) (idx & 63L))) != 0L;
    }}
