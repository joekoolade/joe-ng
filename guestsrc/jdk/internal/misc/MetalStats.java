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
package jdk.internal.misc;

/**
 * The VM's run-time counters (heap, collector, locks, scheduler, network), as named columns. Each group is read
 * in one native call ({@code vm.VMStats.fill}), which writes the group's counters in a fixed order; the
 * {@code *_NAMES} arrays here name those positions in the SAME order and must change together with VMStats.
 * A group only ever grows at its end.
 */
public final class MetalStats
{
    private MetalStats()
    {
    }

    public static final int MEMORY = 0;
    public static final int GC = 1;
    public static final int LOCKS = 2;
    public static final int THREADS = 3;
    public static final int NET = 4;

    public static final String[] MEMORY_NAMES = {
        "heap reservation (bytes)",
        "heap free (bytes, upper bound on use)",
        "live at last collection (bytes)",
        "allocated since last collection (bytes)",
        "core 0 collected arena (bytes)",
        "live in the large-object region (bytes)",
        "code arena size (bytes)",
        "code arena in use now, to the bump pointer (bytes)",
        "compiled code in use at last collection (bytes)",
        "code arena peak, to the bump pointer (bytes)",
        "uptime (ms)",
    };

    public static final String[] GC_NAMES = {
        "collections",
        "... triggered by allocation",
        "freed by the last collection (bytes)",
        "total pause (us)",
        "last pause (us)",
        "longest pause (us)",
        "compiled code swept, total (bytes)",
        "code sweeps",
        "mark-stack overflows",
        "words examined by the last collection",
        "skipped: the world would not stop",
        "live after the last collection (bytes)",
        "last collection: stopping the world (us)",
        "last collection: root scan (us)",
        "last collection: trace (us)",
        "last collection: sweep (us)",
        "last collection: root words examined",
        "core 1 arena in use, never collected (bytes)",
        "core 2 arena in use, never collected (bytes)",
        "core 3 arena in use, never collected (bytes)",
    };

    public static final String[] LOCKS_NAMES = {
        "monitor acquisitions",
        "... contended (waited for another thread)",
        "monitors held now",
        "monitor table size",
        "class-loader lock holds",
        "... that found it held",
        "... yields spent waiting",
        "semaphores allocated",
        "semaphore table size",
    };

    public static final String[] THREADS_NAMES = {
        "task slots used",
        "task table size",
        "timer ticks",
        "context switches",
        "this request's task",
    };

    public static final String[] NET_NAMES = {
        "Ip InReceives", "Ip InHdrErrors", "Ip InAddrErrors", "Ip InUnknownProtos", "Ip InDiscards",
        "Ip InDelivers", "Ip ReasmFails", "Ip OutRequests",
        "Icmp InMsgs", "Icmp OutMsgs",
        "Tcp ActiveOpens", "Tcp PassiveOpens", "Tcp AttemptFails", "Tcp EstabResets", "Tcp InSegs", "Tcp OutSegs",
        "Tcp RetransSegs", "Tcp InErrs", "Tcp InCsumErrors", "Tcp OutRsts", "Tcp NoConn",
        "TcpExt OfoQueue", "TcpExt OfoDrop", "TcpExt OfoMerge", "TcpExt DupData", "TcpExt PartialDup",
        "TcpExt WndDrop", "TcpExt DupAcksOut", "TcpExt ChallengeAcks", "TcpExt RstIgnored", "TcpExt WinUpdates",
        "TcpExt SynAckRetrans",
        "Rx task busy ms", "Rx task SDIO read ms", "Rx task TCP input ms",
        "Tcp app reads", "Tcp app read wait ms", "Tcp app read copy ms",
        "Udp InDatagrams", "Udp NoPorts", "Udp InErrors", "Udp InCsumErrors", "Udp RcvbufErrors",
        "Udp OutDatagrams",
        "WiFi firmware events", "WiFi tx queued", "WiFi tx forced past window", "WiFi tx dropped",
    };

    /** Columns of a task row: see {@link #task}. */
    public static final int TASK_STATE = 0;
    public static final int TASK_PRIO = 1;
    public static final int TASK_BASE_PRIO = 2;
    public static final int TASK_ON_CORE = 3;
    public static final int TASK_IDLE = 4;
    public static final int TASK_DONE = 5;
    public static final int TASK_WAIT_ON = 6;
    public static final int TASK_AFFINITY = 7;
    private static final int TASK_COLUMNS = 8;

    /** The scheduler's state names, indexed by {@code TASK_STATE}. */
    public static final String[] STATE_NAMES = { "empty", "ready", "sleeping", "blocked", "running" };

    /** Group {@code group}'s counters, one per entry of its {@code *_NAMES}. */
    public static long[] read(int group)
    {
        long[] v = new long[names(group).length];
        fill0(group, v);
        return v;
    }

    /** The column names of {@code group}. */
    public static String[] names(int group)
    {
        if (group == MEMORY)
        {
            return MEMORY_NAMES;
        }
        if (group == GC)
        {
            return GC_NAMES;
        }
        if (group == LOCKS)
        {
            return LOCKS_NAMES;
        }
        if (group == THREADS)
        {
            return THREADS_NAMES;
        }
        if (group == NET)
        {
            return NET_NAMES;
        }
        throw new IllegalArgumentException("no stats group " + group);
    }

    /** Task {@code t}'s row (see the {@code TASK_*} columns), or null when there is no task {@code t}. */
    public static long[] task(int t)
    {
        long[] v = new long[TASK_COLUMNS];
        if (fill0(100 + t, v) < 0)
        {
            return null;
        }
        return v;
    }

    /** Task {@code t}'s Thread, or null for a task with none (a kernel task, a core's idle loop). */
    public static Thread taskThread(int t)
    {
        return taskThread0(t);
    }

    /** VM native ({@code VMStats.fill}): write group {@code group} into {@code out}; the count, or -1. */
    private static native long fill0(int group, long[] out);

    /** VM native ({@code VMStats.taskThread}). */
    private static native Thread taskThread0(int t);
}
