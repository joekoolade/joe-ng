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
        "core 1 arena in use (bytes)",
        "core 2 arena in use (bytes)",
        "core 3 arena in use (bytes)",
        "collections run by core 0",
        "collections run by core 1",
        "collections run by core 2",
        "collections run by core 3",
        "... asked for while another core was collecting",
        "cores 1-3 arenas: live after the last collection (bytes)",
        "... freed by the last collection (bytes)",
        "... handed back by lowering their bump pointers (bytes)",
        "reference-map shadow checks (gcmaps=1/2)",
        "... frames walked",
        "... frames with a map site",
        "... MAP ERRORS (a reference slot holding no object, a data reference no payload)",
        "conservative hits: map reference or data reference (explained)",
        "... local typed raw / Address (hidden)",
        "... (unused since S4: an ObjectReference is a reference)",
        "... dead local (stale)",
        "... operand typed raw (hidden)",
        "... (unused since S4)",
        "... dead operand (stale)",
        "... operand transient of the lowering (hidden)",
        "... out-args area (for a trampoline's callee)",
        "... frame with no site",
        "... frame with no map (stubs)",
        "... stack the walk did not reach",
        "... a suspended task's saved registers",
        "... spill slot not written for the call (stale)",
        "... frame alignment word, never written (stale)",
        "... raw copy of a block a typed slot on the same stack holds (borrowed)",
        "... stub frame word its descriptor does not type",
        "... MISSES: kept by no precise root (would have been freed)",
        "precise stack roots (S5; gcroots=0 turns them off)",
        "... stacks walked",
        "... frames walked",
        "... frames typed by a map site",
        "... stub frames typed by a descriptor",
        "... reference words marked",
        "... frames scanned conservatively (no map or no site)",
        "... stack tails scanned conservatively (an unknown frame)",
        "... tasks stopped outside a safepoint, scanned whole",
        "... idle tasks stopped mid-loop, scanned whole",
        "... words scanned conservatively",
        "heap shadow checks (S6; gcheap=2)",
        "... blocks only a conservative word kept: from a raw slot (long, Address)",
        "... from a scalar with no map or kinds",
        "... from a word array with no element Type (long[], AddressArray)",
        "... from a raw array",
        "... from an allocData struct",
        "... from an unrecognised header",
        "... from a reference read as a data reference (w-16: conservative false positives)",
        "... reached only through those (secondary)",
        "... compiled-code words only a conservative word kept (raw longs, long[] tables)",
        "... data payloads held in a reference slot (lost by a precise trace)",
        "... blocks only a conservative read of an image statics slot kept (S6d)",
        "... blocks only a conservative read of a code-root entry kept (S6d)",
        "precise heap trace on (S6c; gcprecise=0 = conservative)",
        "... blocks it scanned conservatively (no kinds, unrecognised header)",
        "collections that built the block-start bitmap (S6d-3: only conservative probes need it)",
        "... exact marks made without it",
        "... checked against it every collection (gcverify=1)",
        "... EXACT WORDS THAT WERE NOT A BLOCK BASE (gcverify=1; refused)",
        "generational census on (S7-0; gcgen=1)",
        "... collections classified",
        "... young bytes (allocated since the previous collection), summed",
        "... of which survived their first collection",
        "... last collection: young bytes that survived",
        "... last collection: young bytes",
        "... last collection: old bytes live",
        "... most old->young references at one collection",
        "... most old blocks holding one (remembered set)",
        "... code-root entries naming a young block, summed (could not move)",
        "... young survivors whose identity hash was taken, summed",
        "identity hashes taken (System.identityHashCode, Object.hashCode)",
        "identity-hash table entries now (S7-1: hashed heap objects)",
        "... most at once",
        "... entries made (first hashes)",
        "... dropped by a collection (object died)",
        "... re-keyed by a move (hash kept)",
        "write barrier compiled in (S7-2)",
        "... log bitmap cleared and counted every collection (gcbarrier=1)",
        "... heap objects logged, last cycle",
        "... most in one cycle",
        "... image objects logged, last cycle",
        "... old->young references checked against it (gcgen=1)",
        "... WHOSE SOURCE WAS NOT LOGGED (a store the barrier missed)",
        "... of which into allocData blocks (pretenured since S7-3: none expected)",
        "... out of statics blocks (roots of a minor collection: putstatic has no barrier)",
        "pretenured allocData blocks (S7-3: Types, TIBs, statics, buffers)",
        "... their bytes",
        "pretenured by site (Class mirrors, string literals: code immediates)",
        "... their bytes",
        "young large-object bytes, summed (16 KiB or more: not pretenured, never copied; gcgen=1)",
        "... dead at their first collection",
        "nursery (S7-4a; gcnursery=1, 2 = verify)",
        "... KB per core",
        "... minor collections",
        "... of which went on as full collections",
        "... bytes allocated young",
        "... bytes copied out (promoted)",
        "... objects copied out",
        "... bytes copied by the last one",
        "... blocks pinned (named by a conservative word)",
        "... copies that found no mature space (stayed)",
        "... remembered objects scanned by the last one",
        "... most in one",
        "... minor-only pauses, summed (us)",
        "... longest minor-only pause (us)",
        "... REFERENCES INTO EVACUATED SPACE (gcnursery=2)",
        "... logged blocks with no precise layout",
        "... minor phases, summed (us)",
        "... ... pin pass (us)",
        "... ... forward pass: stacks (us)",
        "... ... statics and JIT maps (us)",
        "... ... remembered set (us)",
        "... ... drain (us)",
        "... ... identity re-key and gap rebuild (us)",
        "... ... of the pin pass: block-start lookups (us)",
        "... ... ... blocks they walked",
        "... ... remembered set: dirty 32 KiB windows scanned",
        "... ... ... logged objects scanned",
        "... ... ... their reference slots",
        "... ... ... aastore elements forwarded from the slot log",
        "... the slowest pin pass (us)",
        "... the slowest minor phase (us)",
        "... ... its pin pass (us)",
        "... ... its forward pass (us)",
        "... ... its statics and JIT maps (us)",
        "... ... its remembered set (us)",
        "... ... its drain (us)",
        "... ... its re-key and rebuild (us)",
        "... ... bytes it copied",
        "... mature allocations for the copies, summed (us)",
        "... ... copies served from an exact-size list",
        "... ... ... their allocation time (us)",
        "... ... copies served from the general list",
        "... ... ... their allocation time (us)",
        "... ... copies served by bumping the arena",
        "... ... ... their allocation time (us)",
        "... ... general-list blocks walked for copies",
        "... ... size classes probed for copies",
        "frame lookups by stack walks, all collections",
        "... cache misses (each a frame-table search)",
        "... time resolving misses (us)",
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
        "safepoints on (timer requests a switch, taken at a yieldpoint)",
        "yieldpoints that switched",
        "time to safepoint, average us",
        "time to safepoint, longest us",
        "preempted outside a safepoint (no poll for a quantum)",
        "idle core asked to yield at the interrupt",
        "polls left pending with IRQs masked",
        "poll slow paths for another core's request",
        "yieldpoints JIT-compiled this boot",
        "stop-the-world waits",
        "stop-the-world wait, average us",
        "stop-the-world wait, longest us",
        "CPU clock, measured by the firmware (MHz; 0 = none)",
        "SoC temperature (C)",
        "firmware throttle flags (0x1 under-voltage, 0x2 capped, 0x4 throttled, 0x8 soft temp limit; <<16 since boot)",
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
        "WiFi tx queue wait ms", "WiFi tx queue wait max us", "WiFi tx flushes",
        "WiFi tx waits >= 100 ms",
        "WiFi tx waits >= 100 ms across a collection",
        "WiFi join ms", "WiFi join EAPOL msg1 received", "WiFi join EAPOL msg2 sent",
        "WiFi join EAPOL kept from the link wait", "WiFi join attempts",
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
