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

import magic.Magic;
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
final class VMStats
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
        long len = Magic.load64(arr + ObjectModel.ARRAY_LENGTH_OFFSET);
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
    private static int put(long arr, long len, int i, long v)
    {
        if ((long) i < len)
        {
            Magic.store64(arr + ObjectModel.ARRAY_BASE_OFFSET + (long) i * 8L, v);
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
        i = put(arr, len, i, Magic.load64(Heap.CODE_PTR_CELL) - Heap.CODE_BASE); // ... bump pointer now
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
        long now = Magic.load64(Heap.CODE_PTR_CELL);
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
            if (VM.monObj[s] != 0L)
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
        return VM.taskThreadObj[(int) t];
    }

    /** {@link NetStats} in its declaration order, then the WiFi driver's counters. */
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
        i = put(arr, len, i, NetStats.udpInDatagrams);
        i = put(arr, len, i, NetStats.udpNoPorts);
        i = put(arr, len, i, NetStats.udpInErrors);
        i = put(arr, len, i, NetStats.udpInCsumErrors);
        i = put(arr, len, i, NetStats.udpRcvbufErrors);
        i = put(arr, len, i, NetStats.udpOutDatagrams);
        i = put(arr, len, i, board.cyw43.Cyw43.rxEvents);                  // WiFi: firmware events received
        i = put(arr, len, i, board.cyw43.Cyw43.txQueued);                  // ... frames that waited to send
        i = put(arr, len, i, board.cyw43.Cyw43.txForced);                  // ... sent past the credit window
        i = put(arr, len, i, board.cyw43.Cyw43.txDropped);                 // ... dropped, queue full
        return (long) i;
    }
}
