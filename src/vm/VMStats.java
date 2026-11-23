/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-09
 */
package vm;

import magic.Address;
import compiler.Baseline;
import compiler.Symbols;
import magic.Magic;
import net.Link;
import net.NetStats;
import objectmodel.ObjectModel;

/**
 * The VM's run-time counters for guest code: {@code jdk.internal.misc.MetalStats.fill0(group, long[])} lands in
 * {@link #fill}, which writes one group's counters into the array IN A FIXED ORDER and answers how many it wrote.
 * MetalStats names each position; the two lists are kept in the same order, group by group, and a group grows
 * only at its end so an older reader still reads its own columns right.
 *
 * <p>Every value is a word read, with no lock: a counter another core is bumping may read one increment stale,
 * and the columns of one group can come from slightly different instants. That is what a statistics page means.
 * Nothing here allocates.
 */
public final class VMStats
{
    private VMStats()
    {
    }

    static final int GROUP_MEMORY = 0;
    static final int GROUP_GC = 1;
    static final int GROUP_LOCKS = 2;
    static final int GROUP_THREADS = 3;
    static final int GROUP_NET = 4;
    /** Group 100 + t: task t's row of the scheduler's table. */
    static final int GROUP_TASK = 100;

    // ----- counters kept here (each bumped where it happens) ----------------------------------------
    static long monEnters;          // monitorenter acquisitions (each recursion level counts)
    static long monContended;      // ... of which found the monitor held by another task and had to wait
    static long ctxSwitches;        // scheduler picks that moved a core to a DIFFERENT task
    static long gcPauseTicks;       // CNTPCT ticks spent inside collections, stop-the-world wait included
    static long gcLastTicks;        // ... in the last collection
    static long gcMaxTicks;         // ... in the longest one
    static long gcSkipped;          // collections abandoned because the world would not stop

    /** One collection's pause, measured by {@code VMGc.gcCollect} from entry until the world restarts. */
    static void gcPause(long ticks)
    {
        gcPauseTicks = gcPauseTicks + ticks;
        gcLastTicks = ticks;
        if (ticks > gcMaxTicks)
        {
            gcMaxTicks = ticks;
        }
    }

    /** Ticks of the generic timer as microseconds. */
    private static long ticksToUs(long t)
    {
        long f = Magic.readCNTFRQ_EL0();
        return (t / f) * 1000000L + (t % f) * 1000000L / f;
    }

    /**
     * Write group {@code group}'s counters into the guest {@code long[]} at {@code arr}; answer how many, or -1
     * for an unknown group or a task id outside the table. Writes nothing past the array's length.
     */
    static long fill(long group, long arr)
    {
        if (arr == 0L)
        {
            return -1L;
        }
        long len = Address.fromLong(arr).plus(ObjectModel.ARRAY_LENGTH_OFFSET).loadLong();
        int g = (int) group;
        if (g == GROUP_MEMORY)
        {
            return memory(arr, len);
        }
        if (g == GROUP_GC)
        {
            return gc(arr, len);
        }
        if (g == GROUP_LOCKS)
        {
            return locks(arr, len);
        }
        if (g == GROUP_THREADS)
        {
            return threads(arr, len);
        }
        if (g == GROUP_NET)
        {
            return net(arr, len);
        }
        if (g >= GROUP_TASK)
        {
            return task(g - GROUP_TASK, arr, len);
        }
        return -1L;
    }

    /** Store {@code v} at index {@code i} if the array has room; answer i + 1. */
    public static int put(long arr, long len, int i, long v)
    {
        if ((long) i < len)
        {
            Address.fromLong(arr).plus(ObjectModel.ARRAY_BASE_OFFSET + (long) i * 8L).store(v);
        }
        return i + 1;
    }

    private static long memory(long arr, long len)
    {
        int i = 0;
        i = put(arr, len, i, VMNatives.runtimeTotalMemory());               // heap reservation
        i = put(arr, len, i, VMNatives.runtimeFreeMemory());                // free (an upper bound on use)
        i = put(arr, len, i, VMGc.liveBytes);                              // live at the last collection
        i = put(arr, len, i, Heap.servedSinceGc);                          // allocated since it
        i = put(arr, len, i, Heap.LARGE_LIMIT - Heap.BASE);                // core 0's collected arena
        i = put(arr, len, i, VMGc.largeLive);                              // ... live in its large region
        i = put(arr, len, i, Heap.CODE_LIMIT - Heap.CODE_BASE);            // code arena size
        i = put(arr, len, i, Address.fromLong(Heap.CODE_PTR_CELL).loadLong() - Heap.CODE_BASE); // ... bump pointer now
        i = put(arr, len, i, VMGc.codeUsed);                               // ... in use at the last collection
        i = put(arr, len, i, peakCode());                                  // ... bump pointer's peak
        i = put(arr, len, i, ticksToUs(Magic.readCNTPCT_EL0()) / 1000L);   // uptime, ms
        return (long) i;
    }

    /**
     * The code arena's peak use in bytes. {@code Heap.codePeak} is the bump pointer's highest ADDRESS (0 until
     * the arena first grows), not a size, and it is sampled on one growth path only -- MEASURED 96 bytes below
     * the live bump pointer on the Pi -- so the answer is the larger of it and the pointer now.
     * ({@code VMGc.codeLive} is not reported: it can count a block twice, once as reachable and again as young
     * or pinned. {@code codeUsed} counts each block once.)
     */
    private static long peakCode()
    {
        long now = Address.fromLong(Heap.CODE_PTR_CELL).loadLong();
        long peak = Heap.codePeak;
        if (now > peak)
        {
            peak = now;
        }
        return peak - Heap.CODE_BASE;
    }

    private static long gc(long arr, long len)
    {
        int i = 0;
        i = put(arr, len, i, VMGc.collections);                            // collections, every cause
        i = put(arr, len, i, (long) Heap.gcPressure);                      // ... of which allocation-triggered
        i = put(arr, len, i, VM.reclaimed);                                // bytes freed by the last one
        i = put(arr, len, i, ticksToUs(gcPauseTicks));                     // total pause, us
        i = put(arr, len, i, ticksToUs(gcLastTicks));                      // last pause, us
        i = put(arr, len, i, ticksToUs(gcMaxTicks));                       // longest pause, us
        i = put(arr, len, i, VMGc.codeFreedTotal);                         // compiled code bytes swept, total
        i = put(arr, len, i, VMGc.sweepsTotal);                            // code sweeps
        i = put(arr, len, i, VMGc.overflows);                              // mark-stack overflows
        i = put(arr, len, i, VMGc.probes);                                 // words examined by the last one
        i = put(arr, len, i, gcSkipped);                                   // skipped: the world would not stop
        i = put(arr, len, i, VMGc.liveBytes);                              // live after the last one
        i = put(arr, len, i, ticksToUs(VMGc.phaseStopTicks));              // last: stopping the world, us
        i = put(arr, len, i, ticksToUs(VMGc.phaseRootTicks));              // ... root scan, us
        i = put(arr, len, i, ticksToUs(VMGc.phaseTraceTicks));             // ... trace, us
        i = put(arr, len, i, ticksToUs(VMGc.phaseSweepTicks));             // ... sweep, us
        i = put(arr, len, i, VMGc.rootProbes);                             // ... root words examined
        int c = 1;
        while (c < 4)
        {
            i = put(arr, len, i, Address.fromLong(Heap.PTR_CELL + c * 8L).loadLong() - Heap.arenaBase(c).toLong());   // core c's arena use
            c += 1;
        }
        c = 0;
        while (c < 4)
        {
            i = put(arr, len, i, VMGc.gcByCore[c]);                       // collections core c ran (S5a)
            c += 1;
        }
        i = put(arr, len, i, VMGc.gcBusy);                                 // ... asked for while another core collected
        i = put(arr, len, i, VMGc.arenaLive);                              // cores 1-3 arenas: live after the last
        i = put(arr, len, i, VMGc.arenaFreed);                             // ... freed by it
        i = put(arr, len, i, VMGc.arenaTrimmed);                           // ... handed back by lowering bump pointers
        i = put(arr, len, i, GcMapShadow.checks);                          // S2 shadow check (gcmaps=1): passes
        i = put(arr, len, i, GcMapShadow.framesWalked);                    // ... frames walked
        i = put(arr, len, i, GcMapShadow.framesMapped);                    // ... with a map site
        i = put(arr, len, i, GcMapShadow.mapErrors);                       // ... map errors
        int k = 0;
        while (k < GcMapShadow.NCAT)                                       // ... live-looking words, by where
        {
            i = put(arr, len, i, GcMapShadow.hits != null ? GcMapShadow.hits[k] : 0L);
            k += 1;
        }
        i = put(arr, len, i, GcMapShadow.missedLive);                      // ... MISSES: kept by no precise root
        i = put(arr, len, i, (long) StackRoots.on);                        // S5: precise stack roots on
        i = put(arr, len, i, StackRoots.walks);                            // ... stacks walked
        i = put(arr, len, i, StackRoots.frames);                           // ... frames
        i = put(arr, len, i, StackRoots.mapped);                           // ... typed by a map site
        i = put(arr, len, i, StackRoots.stubs);                            // ... stub frames typed by a descriptor
        i = put(arr, len, i, StackRoots.refs);                             // ... reference words marked
        i = put(arr, len, i, StackRoots.consFrames);                       // ... frames scanned conservatively
        i = put(arr, len, i, StackRoots.consTails);                        // ... stack tails scanned conservatively
        i = put(arr, len, i, StackRoots.forced);                           // ... tasks stopped outside a safepoint
        i = put(arr, len, i, StackRoots.idle);                             // ... idle tasks stopped mid-loop: scanned whole
        i = put(arr, len, i, StackRoots.consWords);                        // ... words scanned conservatively
        i = put(arr, len, i, HeapShadow.checks);                           // S6 heap shadow (gcheap=2): checks
        int hk = 0;
        while (hk < 7)
        {
            i = put(arr, len, i, HeapShadow.total(hk));                    // ... primary misses by source kind
            hk += 1;
        }
        i = put(arr, len, i, HeapShadow.secondaries);                      // ... secondary misses
        i = put(arr, len, i, HeapShadow.codeTotal);                        // ... code words only the rescan kept
        i = put(arr, len, i, HeapShadow.total(7));                         // ... payloads held in reference slots
        i = put(arr, len, i, HeapShadow.total(8));                         // ... S6d roots: statics slots
        i = put(arr, len, i, HeapShadow.total(9));                         // ... S6d roots: code-root entries
        i = put(arr, len, i, (long) PreciseTrace.on);                      // S6c: precise heap trace on
        i = put(arr, len, i, PreciseTrace.fallbacks);                      // ... blocks it scanned conservatively
        i = put(arr, len, i, VMGc.bitmapBuilds);                           // S6d-3: collections that built the bitmap
        i = put(arr, len, i, VMGc.exactMarks);                             // ... exact marks (no bitmap)
        i = put(arr, len, i, (long) VMGc.verifyExact);                     // ... gcverify=1
        i = put(arr, len, i, VMGc.exactBad);                               // ... exact words gcverify refused
        i = put(arr, len, i, (long) GenCensus.on);                         // S7-0: generational census on
        i = put(arr, len, i, GenCensus.collections);                       // ... collections it classified
        i = put(arr, len, i, GenCensus.youngAllocTotal);                   // ... young bytes, live + dead, summed
        i = put(arr, len, i, GenCensus.youngLiveTotal);                    // ... of which survived
        i = put(arr, len, i, GenCensus.youngLive);                         // ... last collection: young live
        i = put(arr, len, i, GenCensus.youngLive + GenCensus.youngDead);   // ... of young allocated
        i = put(arr, len, i, GenCensus.oldLive);                           // ... old live
        i = put(arr, len, i, GenCensus.oldToYoungMax);                     // ... most old->young edges
        i = put(arr, len, i, GenCensus.remsetMax);                         // ... most old blocks holding one
        i = put(arr, len, i, GenCensus.codeToYoungTotal);                  // ... code-root entries naming a young block
        i = put(arr, len, i, GenCensus.hashedYoungLiveTotal);              // ... hashed young survivors
        i = put(arr, len, i, VMNatives.identityHashes);                    // ... identity hashes taken
        i = put(arr, len, i, IdentityHash.liveNow());                      // S7-1: hash-table entries now
        i = put(arr, len, i, IdentityHash.liveMax);                        // ... most at once
        i = put(arr, len, i, IdentityHash.inserts);                        // ... entries made (first hashes)
        i = put(arr, len, i, IdentityHash.purged);                         // ... dropped by a collection
        i = put(arr, len, i, IdentityHash.moves);                          // ... re-keyed by a move
        i = put(arr, len, i, Symbols.WRITE_BARRIER ? 1L : 0L);             // S7-2: write barrier compiled in
        i = put(arr, len, i, (long) WriteBarrier.on);                      // ... gcbarrier=1 (cleared, counted)
        i = put(arr, len, i, WriteBarrier.loggedHeap);                     // ... heap objects logged, last cycle
        i = put(arr, len, i, WriteBarrier.loggedHeapMax);                  // ... most in one cycle
        i = put(arr, len, i, WriteBarrier.loggedImage);                    // ... image objects logged, last cycle
        i = put(arr, len, i, WriteBarrier.checked);                        // ... old->young edges checked (gcgen=1)
        i = put(arr, len, i, WriteBarrier.missesTotal);                    // ... whose source was NOT logged
        i = put(arr, len, i, WriteBarrier.missesToData);                   // ... of which into allocData blocks
        i = put(arr, len, i, WriteBarrier.fromStatics);                    // ... out of statics blocks (roots)
        i = put(arr, len, i, Heap.oldDataN);                               // S7-3: pretenured allocData blocks
        i = put(arr, len, i, Heap.oldDataBytes);                           // ... bytes
        i = put(arr, len, i, Heap.oldSiteN);                               // ... by site (mirrors, literals)
        i = put(arr, len, i, Heap.oldSiteBytes);                           // ... bytes
        i = put(arr, len, i, GenCensus.largeYoungTotal);                   // ... young large bytes, summed (gcgen=1)
        i = put(arr, len, i, GenCensus.largeYoungDeadTotal);               // ... dead at their first collection
        return (long) i;
    }

    private static long locks(long arr, long len)
    {
        int i = 0;
        i = put(arr, len, i, monEnters);                                   // monitorenter acquisitions
        i = put(arr, len, i, monContended);                                // ... that waited for another task
        i = put(arr, len, i, monitorsHeld());                              // monitors held now
        i = put(arr, len, i, (long) VM.MAX_MON);                            // monitor table size
        i = put(arr, len, i, (long) VM.loaderReleases);                    // loader-lock outermost holds
        i = put(arr, len, i, (long) VM.loaderWaits);                       // ... that found it held
        i = put(arr, len, i, (long) VM.loaderWaitYields);                  // ... yields those waits cost
        i = put(arr, len, i, (long) VM.nextSem);                           // semaphores allocated
        i = put(arr, len, i, (long) VM.NUM_SEM);                           // semaphore table size
        return (long) i;
    }

    /** Monitors held right now: the live slots of the side table. */
    private static long monitorsHeld()
    {
        if (VM.monObj == null)
        {
            return 0L;
        }
        long n = 0L;
        int s = 0;
        while (s < VM.MAX_MON)
        {
            if (!VM.monObj.get(s).isNull())
            {
                n += 1L;
            }
            s += 1;
        }
        return n;
    }

    private static long threads(long arr, long len)
    {
        int i = 0;
        i = put(arr, len, i, (long) VM.taskCount);                         // task slots used
        i = put(arr, len, i, (long) VM.MAX_TASKS);                         // task table size
        i = put(arr, len, i, VM.ticks);                                    // timer ticks serviced
        i = put(arr, len, i, ctxSwitches);                                 // context switches
        i = put(arr, len, i, (long) VM.curTask());                         // the calling task
        i = put(arr, len, i, (long) (1 - VM.noSafepoints));                // safepoints on (S3)
        i = put(arr, len, i, VMScheduler.yieldTaken);                      // yieldpoints that switched
        i = put(arr, len, i, ticksToUs(VMScheduler.ttsTicks / (VMScheduler.yieldTaken > 0L ? VMScheduler.yieldTaken : 1L)));
        i = put(arr, len, i, ticksToUs(VMScheduler.ttsMaxTicks));             // longest time-to-safepoint
        i = put(arr, len, i, VMScheduler.forcedPreempts);                  // preempted outside a safepoint
        i = put(arr, len, i, VMScheduler.idleSwitches);                    // idle task asked to yield at the interrupt
        i = put(arr, len, i, VMScheduler.yieldMasked);                     // polls left pending, IRQs masked
        i = put(arr, len, i, VMScheduler.yieldOther);                      // slow paths for another core
        i = put(arr, len, i, Baseline.yieldPollsEmitted);                  // yieldpoints JIT-compiled this boot
        i = put(arr, len, i, VMGc.stwStops);                               // stop-the-world waits
        i = put(arr, len, i, ticksToUs(VMGc.stwWaitTicks / (VMGc.stwStops > 0L ? VMGc.stwStops : 1L)));
        i = put(arr, len, i, ticksToUs(VMGc.stwWaitMaxTicks));                // longest stop-the-world wait
        long health = VM.boardHealthAddr != 0L ? Magic.call0(VM.boardHealthAddr) : 0L;
        i = put(arr, len, i, health & 0xFFFFL);                            // measured ARM clock, MHz (0 = no firmware)
        i = put(arr, len, i, (health >>> 16) & 0xFFFFL);                   // SoC temperature, C
        i = put(arr, len, i, health >>> 32);                               // firmware throttle flags
        return (long) i;
    }

    /** Task {@code t}'s row: state, priority, base priority, core running it (-1), idle, done, waitOn, affinity. */
    private static long task(int t, long arr, long len)
    {
        if (VM.taskState == null || t < 0 || t >= VM.taskCount)
        {
            return -1L;
        }
        int i = 0;
        i = put(arr, len, i, (long) VM.taskState[t]);
        i = put(arr, len, i, (long) VM.taskPrio[t]);
        i = put(arr, len, i, (long) VM.taskBasePrio[t]);
        i = put(arr, len, i, (long) runningOn(t));
        i = put(arr, len, i, (long) VM.taskIdle[t]);
        i = put(arr, len, i, (long) VM.taskDone[t]);
        i = put(arr, len, i, (long) VM.taskWaitOn[t]);
        i = put(arr, len, i, (long) VM.taskCore[t]);
        return (long) i;
    }

    /** The core running task {@code t} right now, or -1. */
    private static int runningOn(int t)
    {
        if (VM.coreTask == null || VM.taskState[t] != VM.TASK_RUNNING)
        {
            return -1;
        }
        int c = 0;
        while (c < VM.coreTask.length)
        {
            if (VM.coreTask[c] == t)
            {
                return c;
            }
            c += 1;
        }
        return -1;
    }

    /**
     * {@code MetalStats.taskThread0(t)}: task {@code t}'s java.lang.Thread, or null when it has none (a kernel
     * task, a core's idle loop) or {@code t} is out of range.
     */
    static long taskThread(long t)
    {
        if (VM.taskThreadObj == null || t < 0L || t >= (long) VM.taskCount)
        {
            return 0L;
        }
        return VM.taskThreadObj.get((int) t).toLong();
    }

    /** {@link NetStats} in its declaration order, then the link driver's counters ({@code netLinkStats}). */
    private static long net(long arr, long len)
    {
        int i = 0;
        i = put(arr, len, i, NetStats.ipInReceives);
        i = put(arr, len, i, NetStats.ipInHdrErrors);
        i = put(arr, len, i, NetStats.ipInAddrErrors);
        i = put(arr, len, i, NetStats.ipInUnknownProtos);
        i = put(arr, len, i, NetStats.ipInDiscards);
        i = put(arr, len, i, NetStats.ipInDelivers);
        i = put(arr, len, i, NetStats.ipReasmFails);
        i = put(arr, len, i, NetStats.ipOutRequests);
        i = put(arr, len, i, NetStats.icmpInMsgs);
        i = put(arr, len, i, NetStats.icmpOutMsgs);
        i = put(arr, len, i, NetStats.tcpActiveOpens);
        i = put(arr, len, i, NetStats.tcpPassiveOpens);
        i = put(arr, len, i, NetStats.tcpAttemptFails);
        i = put(arr, len, i, NetStats.tcpEstabResets);
        i = put(arr, len, i, NetStats.tcpInSegs);
        i = put(arr, len, i, NetStats.tcpOutSegs);
        i = put(arr, len, i, NetStats.tcpRetransSegs);
        i = put(arr, len, i, NetStats.tcpInErrs);
        i = put(arr, len, i, NetStats.tcpInCsumErrors);
        i = put(arr, len, i, NetStats.tcpOutRsts);
        i = put(arr, len, i, NetStats.tcpNoConn);
        i = put(arr, len, i, NetStats.tcpOfoQueue);
        i = put(arr, len, i, NetStats.tcpOfoDrop);
        i = put(arr, len, i, NetStats.tcpOfoMerge);
        i = put(arr, len, i, NetStats.tcpDupData);
        i = put(arr, len, i, NetStats.tcpPartialDup);
        i = put(arr, len, i, NetStats.tcpWndDrop);
        i = put(arr, len, i, NetStats.tcpDupAcksOut);
        i = put(arr, len, i, NetStats.tcpChallengeAcks);
        i = put(arr, len, i, NetStats.tcpRstIgnored);
        i = put(arr, len, i, NetStats.tcpWinUpdates);
        i = put(arr, len, i, NetStats.tcpSynAckRetrans);
        i = put(arr, len, i, NetStats.ms(NetStats.rxBusyTicks));
        i = put(arr, len, i, NetStats.ms(NetStats.rxSdioTicks));
        i = put(arr, len, i, NetStats.ms(NetStats.rxTcpTicks));
        i = put(arr, len, i, NetStats.tcpAppReads);
        i = put(arr, len, i, NetStats.ms(NetStats.tcpAppWaitTicks));
        i = put(arr, len, i, NetStats.ms(NetStats.tcpAppCopyTicks));
        i = put(arr, len, i, NetStats.udpInDatagrams);
        i = put(arr, len, i, NetStats.udpNoPorts);
        i = put(arr, len, i, NetStats.udpInErrors);
        i = put(arr, len, i, NetStats.udpInCsumErrors);
        i = put(arr, len, i, NetStats.udpRcvbufErrors);
        i = put(arr, len, i, NetStats.udpOutDatagrams);
        i = (int) Magic.call3(Link.hwLinkStatsAddr, arr, len, i);   // the link driver's own counters (WiFi)
        return (long) i;
    }
}
