/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */
package vm;

import static vm.VM.*;

import board.Uart;
import magic.Address;
import magic.Magic;
import magic.Uninterruptible;

/**
 * Precise stack roots (frames/GC arc S5, PLAN.md): every task's stack is walked frame by frame, and only the words
 * a frame's reference map types as a reference are marked -- what Jikes RVM's GC map iterators do for its
 * baseline frames. A stub frame is typed by a descriptor: the method its index names for a resolve trampoline,
 * a mask fixed at build time for a thunk ({@link StubFrames}).
 *
 * <p>Where nothing can say which words are references, the walk falls back to scanning them conservatively, and
 * counts it: a frame with no map or no site for its return address; the rest of a stack past a frame the tables do
 * not know (a fault taken mid-instruction, or an unregistered stub); a task preempted outside a safepoint by a forced
 * switch; a stub frame whose descriptor cannot be found. Since frames/GC arc S6d-2 a core's idle task yields at a
 * safepoint like any other (it used to be switched wherever the interrupt found it), stub arguments are typed by
 * their exact kinds, and a fresh task's x0 and the run trampoline's Runnable are exact roots: over the whole virt
 * probe list every one of these counters is 0. The fallback stays, so the walk is never wrong where a corner is not
 * described; the counters say how much it is still needed.
 *
 * <p>Return addresses and other code pointers on a stack keep compiled code alive, and code is never moved, so each
 * live stack range is still scanned for CODE pointers only ({@link VMGc#markCodeInRange}): no heap block is marked
 * by that scan.
 *
 * <p>On by default; {@code gcroots=0} in {@code /etc/init} restores the conservative root scan (an A/B switch).
 * Task stacks are heap blocks typed as a byte array, so the trace never scans them as data either way: they are
 * roots only, walked here or (with {@code gcroots=0}) scanned from their saved SP by the collector.
 */
final class StackRoots implements Uninterruptible
{
    private StackRoots()
    {
    }

    /** 1 = precise stack roots (the default); 0 = the conservative scan ({@code gcroots=0}). */
    static int on = 1;

    // Cumulative over the boot, for /gc and the summary line.
    static long walks;               // stacks walked
    static long frames;              // frames walked
    static long mapped;              // ... typed by a RefMaps site
    static long stubs;               // ... stub frames typed by a descriptor
    static long untypedStubs;        // ... stub frames whose descriptor could not be found (all args maybe refs)
    static long refs;                // non-null reference words marked by type
    static long consFrames;          // frames scanned conservatively: no map, or no site for the return address
    static long consTails;           // stack tails scanned conservatively: a frame the tables do not know
    static long forced;              // suspended tasks stopped outside a safepoint, scanned whole
    static long idle;                // core idle tasks stopped outside a safepoint, scanned whole
    static long fresh;               // tasks that have not run yet: x0 only
    static long runTramps;           // spawned threads' bottom frames: the Runnable at [sp]
    static long stubWords;           // untyped stub-frame words (no descriptor), probed at w AND w-16
    static long consWords;           // words scanned conservatively by any of the above
    static long last;                // consWords at the start of the last collection

    /**
     * The minor collection's passes (frames/GC arc S7-4a): 1 = PIN, every word only a guess names pins the nursery
     * block it falls in, exact slots are skipped; 2 = FORWARD, every exact slot is rewritten to its copy and guesses
     * are skipped. 0 = an ordinary mark.
     */
    static int minor;

    private static long[] seen;      // return addresses already reported, open addressing
    private static final int SEEN_SLOTS = 1024;
    private static int printed;
    private static final int PRINT_MAX = 60;

    /** Allocate the walk's tables (a collection must not allocate). */
    static void init()
    {
        FrameMaps.init();
        if (seen == null)
        {
            seen = new long[SEEN_SLOTS];
        }
    }

    /**
     * Mark every stack's references. {@code scanFrom} is the collector's argument (the SP of {@code Magic.gc}'s
     * 96-byte save area, which holds only the frameless boot entry's register locals -- all primitives -- and the
     * LR); {@code stackTop} the collecting task's stack top.
     */
    static void markStacks(long scanFrom, long stackTop)
    {
        last = consWords;
        if (minor == 0)
        {
            // Only a FULL collection's sweep frees code, so a cached frame lookup stays right until the next full
            // walk starts: every entry came from a live frame. A minor phase keeps the cache -- clearing it made
            // each of its two passes re-run the linear frame-table searches for every frame (S7-4b).
            FrameMaps.beginCollection();
        }
        long gcFs = frameSizeAt(Address.fromLong(VM.gcCollect));
        long ret = Address.fromLong(scanFrom - gcFs).loadLong();
        if (minor == 0)
        {
            VMGc.markCodeInRange(scanFrom, stackTop);
        }
        walk(ret, scanFrom + 96L, stackTop);
        int me = curTask();
        if (taskSp == null || taskState == null)
        {
            return;
        }
        int t = 0;
        while (t < taskCount)
        {
            if (t != me && taskState[t] != TASK_EMPTY && taskSp[t] != 0L)
            {
                suspended(t);
            }
            if (minor == 0 && taskStackBase != null && taskStackBase[t] != 0L && taskState[t] != TASK_EMPTY)
            {
                // The stack BLOCK itself: taskStackBase is a long[], so nothing typed keeps a live task's stack
                // allocated -- an exact root here does (frames/GC arc S6b).
                VMGc.markRoot(taskStackBase[t]);
            }
            t += 1;
        }
    }

    /**
     * A task that is not running here: its saved context (x0..x30, ELR at +248) at {@code taskSp[t]}, its stack
     * above. A RUNNING task is one parked on another core by the stop-the-world, its context saved by gcPark.
     */
    private static void suspended(int t)
    {
        long frame = taskSp[t];
        long top = stackTopOf(t);
        if (top == 0L || frame >= top || top - frame > 0x100000L)
        {
            if (taskStackBase != null && taskStackBase[t] != 0L)
            {
                long stk = taskStackBase[t];               // a saved SP outside its own stack: scan the whole block
                conservative(stk + 24L, stk + 0x8000L);
                consTails += 1L;
            }
            return;
        }
        long sp = frame + SCHED_FRAME;
        if (minor == 0)
        {
            VMGc.markCodeInRange(frame, top);
        }
        if (sp >= top)
        {
            fresh += 1L;                                    // never ran: its x0 is the entry's receiver (a Runnable), an
            slot(frame);                                    //   int id or 0 (VMScheduler.spawnWith): an object or
            return;                                         //   no heap address at all
        }
        if (taskSafe != null && taskSafe[t] != 0)
        {
            // Switched at a safepoint: the svc in VMScheduler.taskYield, a frameless leaf, so its caller's return
            // address is the saved x30 -- a call site with a map -- and no register holds a live reference.
            walk(Address.fromLong(frame).plus(240L).loadLong(), sp, top);
            return;
        }
        if (taskIdle != null && taskIdle[t] != 0)
        {
            // A core's idle task switched by an interrupt wherever it was: only with safepoints=0 since S6d-2 (it
            // is asked to yield like any task, and never forced). "It holds no reference" was REFUTED by the shadow
            // check (a MISS on an idle stack), so its stack is scanned whole, as before S5.
            idle += 1L;
            report(Address.fromLong(frame).plus(248L).loadLong() + 4L, Magic.bytes("idle-unsafe"), top - frame);
            conservative(frame, top);
            return;
        }
        forced += 1L;                                       // preempted mid-method: registers and frames unknown
        conservative(frame, top);
    }

    /** Top of task {@code t}'s stack, or 0 when it cannot be named. */
    static long stackTopOf(int t)
    {
        if (t == 0)
        {
            return STACK_TOP;
        }
        if (taskStackBase != null && taskStackBase[t] != 0L)
        {
            return (taskStackBase[t] + 0x8000L) & ~0xFL;
        }
        if (taskIdle != null && taskIdle[t] != 0 && taskCore != null && taskCore[t] >= 1)
        {
            return ((long) SEC_STACK_HI << 16) + ((long) taskCore[t] << 20);
        }
        return 0L;
    }

    /** Walk frames from return address {@code ret} into the frame at {@code sp}, up to {@code top}. */
    private static void walk(long ret, long sp, long top)
    {
        walks += 1L;
        int guard = 0;
        while (sp < top && guard < 4096)
        {
            if (ret == 0L)
            {
                tail(ret, sp, top);                         // a kernel task's entry frame returns to 0: the bottom
                return;
            }
            if (runTrampAddr != 0L && ret > runTrampAddr && ret < runTrampAddr + 96L)
            {
                // The run trampoline: a spawned thread's bottom frame. 16 bytes, its Runnable at [sp] (not an LR),
                // and nothing above it.
                frames += 1L;
                runTramps += 1L;
                slot(sp);
                return;
            }
            FrameMaps.lookup(ret - 4L);
            long fs = FrameMaps.fSize;
            if (fs == 0L || sp + fs > top)
            {
                tail(ret, sp, top);
                return;
            }
            frames += 1L;
            long ma = FrameMaps.fMap;
            if (ma > 0L && FrameMaps.fStart != 0L)
            {
                if (FrameMaps.site(ma, FrameMaps.fStart, ret))
                {
                    mapped += 1L;
                    markMapped(sp);
                }
                else
                {
                    consFrames += 1L;
                    report(ret, Magic.bytes("no-site"), fs);
                    conservative(sp + 8L, sp + fs);
                }
            }
            else if (StubFrames.isStubDesc(ma))
            {
                stubs += 1L;
                markStub(ma, sp, fs, top);
            }
            else
            {
                consFrames += 1L;
                report(ret, Magic.bytes("no-map"), fs);
                conservative(sp + 8L, sp + fs);
            }
            long next = Address.fromLong(sp).loadLong();
            sp = sp + fs;
            ret = next;
            guard += 1;
        }
    }

    /** The walk cannot continue past {@code ret}: scan what is left of the stack conservatively. */
    private static void tail(long ret, long sp, long top)
    {
        if (sp >= top)
        {
            return;
        }
        consTails += 1L;
        report(ret, Magic.bytes("tail"), top - sp);
        conservative(sp, top);
    }

    /** A frame at a mapped call site: its locals, and the operand slots the call's spill wrote. */
    private static void markMapped(long sp)
    {
        int s = 0;
        while (s < FrameMaps.hNLoc)
        {
            markTyped(FrameMaps.slotType(s), sp + FrameMaps.hLocBase + s * 8L);
            s += 1;
        }
        int live = FrameMaps.sLive;
        if (live > FrameMaps.hNStk)
        {
            live = FrameMaps.hNStk;
        }
        int i = 0;
        while (i < live)
        {
            markTyped(FrameMaps.slotType(FrameMaps.hNLoc + i), sp + FrameMaps.hOpBase + i * 8L);
            i += 1;
        }
    }

    private static void markTyped(int t, long a)
    {
        if (t == 1)
        {
            slot(a);
        }
        else if (minor == 1 && (t == 2 || t == 3))
        {
            // A RAW slot (an Address, Word or long) or a data reference may hold a young object's address copied
            // out of a typed one -- VMConcat's builder, a reflective argument buffer. Nothing can rewrite it, so
            // what it names stays put: an ambiguous root pins, as in any mostly-copying collector.
            Nursery.pinWord(Address.fromLong(a).loadLong());
        }
        else if (t == 3 && minor == 0)
        {
            long w = Address.fromLong(a).loadLong();
            if (w != 0L)
            {
                refs += 1L;
                VMGc.markRoot(w - 16L);                     // a data reference: the block is one header earlier
            }
        }
    }

    /** A stub frame, typed by its descriptor {@code d}. */
    private static void markStub(long d, long sp, long fs, long top)
    {
        int kind = StubFrames.stubKind(d);
        if (kind == StubFrames.K_MASK)
        {
            long mask = StubFrames.stubPayload(d);
            int i = 1;
            while (i < StubFrames.MASK_OVERFLOW_BIT0 && i * 8L < fs)
            {
                if (((mask >>> i) & 1L) != 0L)
                {
                    markObject(sp + i * 8L);
                }
                else if (minor == 1)
                {
                    Nursery.pinWord(Address.fromLong(sp + i * 8L).loadLong());
                }
                i += 1;
            }
            if ((mask & StubFrames.MASK_OVERFLOW) != 0L)
            {
                long p = Address.fromLong(sp + StubFrames.MASK_OVERFLOW_SLOT * 8L).loadLong();
                if (p > sp && p < top)                      // the caller's out-args, above this frame
                {
                    int j = 0;
                    while (j < 30 && p + j * 8L + 8L <= top)
                    {
                        if (((mask >>> (StubFrames.MASK_OVERFLOW_BIT0 + j)) & 1L) != 0L)
                        {
                            markObject(p + j * 8L);
                        }
                        j += 1;
                    }
                }
            }
            return;
        }
        if (fs != StubFrames.STUB_FRAME)
        {
            consFrames += 1L;                               // a trampoline kind with a frame it does not have
            conservative(sp + 8L, sp + fs);
            return;
        }
        FrameMaps.trampArgs(kind, Address.fromLong(sp + StubFrames.STUB_ID_OFF).loadLong());
        if (FrameMaps.tUntyped)
        {
            untypedStubs += 1L;
        }
        long mask = FrameMaps.tMask;
        long data = FrameMaps.tData;
        boolean untyped = FrameMaps.tUntyped;
        int n = FrameMaps.tN;
        int regs = n > 16 ? 15 : n;                         // past 16 arguments, x15 is a pointer to the rest
        int r = 0;
        while (r < regs)
        {
            argWord(sp + StubFrames.STUB_SAVE0 + r * 8L, r, mask, data, untyped);
            r += 1;
        }
        if (n > 16)
        {
            long p = Address.fromLong(sp + StubFrames.STUB_SAVE0 + 15L * 8L).loadLong();
            if (p > sp && p + (n - 15) * 8L <= top)         // the caller's out-args, above this frame
            {
                int i = 15;
                while (i < n && i < 64)
                {
                    argWord(p + (i - 15) * 8L, i, mask, data, untyped);
                    i += 1;
                }
            }
        }
    }

    /** Saved argument {@code r} at {@code a}: an object reference, a data reference, or -- the descriptor unknown --
     *  either, probed at {@code w} and {@code w-16} as the conservative scan would ({@link #stubWords}). */
    private static void argWord(long a, int r, long mask, long data, boolean untyped)
    {
        long w = Address.fromLong(a).loadLong();
        if (w == 0L)
        {
            return;
        }
        if (untyped)
        {
            if (minor == 1)
            {
                Nursery.pinWord(w);                         // a guess: whatever it names stays put
            }
            else if (minor == 0)
            {
                refs += 1L;
                stubWords += 1L;
                VMGc.markRootMaybe(w);                      // not known to be a reference: the bitmap decides
                VMGc.markRootMaybe(w - 16L);
            }
        }
        else if (((mask >>> r) & 1L) != 0L)
        {
            slot(a);
        }
        else if (minor == 1)
        {
            Nursery.pinWord(w);                             // a raw argument may be a copied young address
        }
        else if (minor == 0 && ((data >>> r) & 1L) != 0L)
        {
            refs += 1L;
            VMGc.markRoot(w - 16L);                         // a data reference: the block is one header earlier
        }
    }

    /** A word a thunk's build-time mask types as an object reference. */
    private static void markObject(long a)
    {
        slot(a);
    }

    /** An exact object-reference slot at {@code a}: marked, or (minor collection) forwarded in place. */
    private static void slot(long a)
    {
        if (minor == 2)
        {
            Nursery.fwdSlot(a);
            return;
        }
        if (minor == 1)
        {
            return;
        }
        long w = Address.fromLong(a).loadLong();
        if (w != 0L)
        {
            refs += 1L;
            VMGc.markRoot(w);
        }
    }

    private static void conservative(long lo, long hi)
    {
        if (minor == 1)
        {
            Nursery.pinRange(lo, hi);
            return;
        }
        if (minor == 2)
        {
            return;
        }
        if (hi > lo)
        {
            consWords += (hi - lo) >> 3;
            VMGc.markRootRange(lo, hi);
        }
    }

    /** Name a return address the first time the walk has to fall back at it. */
    private static void report(long ret, byte[] why, long bytes)
    {
        if (seen == null || gcLog == 0 && GcMapShadow.on == 0)
        {
            return;
        }
        int h = (int) ((ret ^ (ret >>> 17)) >>> 2) & (SEEN_SLOTS - 1);
        int probe = 0;
        while (probe < SEEN_SLOTS)
        {
            long s = seen[h];
            if (s == ret)
            {
                return;
            }
            if (s == 0L)
            {
                seen[h] = ret;
                break;
            }
            h = (h + 1) & (SEEN_SLOTS - 1);
            probe += 1;
        }
        if (printed >= PRINT_MAX)
        {
            return;
        }
        printed += 1;
        Uart.write(Magic.bytes("gcroots: conservative "));
        Uart.write(why);
        Uart.write(Magic.bytes(" bytes="));
        printHex(bytes);
        Uart.write(Magic.bytes(" at "));
        if (ret == 0L)
        {
            Uart.write(Magic.bytes("<bottom>"));
        }
        else
        {
            StackTrace.printFrameAt(Address.fromLong(ret - 4L));
        }
        Uart.putc((byte) 0x0A);
    }

    /** One line per collection when the shadow check is on: what the walk typed, and what it could not. */
    static void summary()
    {
        Uart.write(Magic.bytes("gcroots: walks="));
        printDec((int) walks);
        Uart.write(Magic.bytes(" frames="));
        printDec((int) frames);
        Uart.write(Magic.bytes(" mapped="));
        printDec((int) mapped);
        Uart.write(Magic.bytes(" stubs="));
        printDec((int) stubs);
        Uart.write(Magic.bytes("/untyped"));
        printDec((int) untypedStubs);
        Uart.write(Magic.bytes(" refs="));
        printDec((int) refs);
        Uart.write(Magic.bytes(" consFrames="));
        printDec((int) consFrames);
        Uart.write(Magic.bytes(" tails="));
        printDec((int) consTails);
        Uart.write(Magic.bytes(" forced="));
        printDec((int) forced);
        Uart.write(Magic.bytes(" idle="));
        printDec((int) idle);
        Uart.write(Magic.bytes(" fresh="));
        printDec((int) fresh);
        Uart.write(Magic.bytes(" runTramps="));
        printDec((int) runTramps);
        Uart.write(Magic.bytes(" stubWords="));
        printDec((int) stubWords);
        Uart.write(Magic.bytes(" heapFallbacks="));
        printDec((int) PreciseTrace.fallbacks);
        Uart.write(Magic.bytes(" bitmapBuilds="));
        printDec((int) VMGc.bitmapBuilds);
        Uart.write(Magic.bytes(" exactBad="));
        printDec((int) VMGc.exactBad);
        Uart.write(Magic.bytes(" consWords="));
        printDec((int) consWords);
        Uart.write(Magic.bytes(" (+"));
        printDec((int) (consWords - last));
        Uart.write(Magic.bytes(") lookups="));
        printDec((int) FrameMaps.lookups);
        Uart.write(Magic.bytes("/miss"));
        printDec((int) FrameMaps.misses);
        Uart.putc((byte) 0x0A);
    }
}
