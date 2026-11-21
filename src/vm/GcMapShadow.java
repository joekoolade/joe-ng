/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-20
 */
package vm;

import static vm.VM.*;

import board.Uart;
import magic.Address;
import magic.Magic;
import magic.Uninterruptible;

/**
 * The S2 shadow check (frames/GC arc, PLAN.md): at each collection, walk the stacks frame by frame with the
 * compiler's reference maps ({@code compiler.RefMaps}) and compare what the maps call a reference with what the
 * conservative root scan treats as one. The collector itself is unchanged -- this only reads and reports.
 *
 * <p>Every word the conservative scan would keep alive (a heap block base, or an {@code allocData} payload one
 * header past one) is classified by where it sits: a map REFERENCE slot (explained), a local or operand slot
 * the map types as a primitive / unboxed word / {@code ObjectReference} / dead, an operand slot the map does not
 * describe (a temporary of the bytecode being executed), another frame word, a frame with no map, or stack the
 * walk did not reach. Everything but the first is a reference a precise collector would not see -- the worklist
 * for S4. The reverse is checked too: a map REFERENCE slot holding a heap address that is NOT a block base is a
 * map error, which a precise collector would trust.
 *
 * <p>On only with {@code gcmaps=1} in {@code /etc/init}: it walks every frame and searches the frame tables per
 * frame, which a production collection should not pay for. Each new (site, slot) is printed once per boot with
 * its frame; every collection prints the totals.
 */
final class GcMapShadow implements Uninterruptible
{
    private GcMapShadow()
    {
    }

    /** {@code gcmaps=1}: run the check at every collection. */
    static int on;

    // What the conservative scan found, by where it sat (cumulative over the boot).
    static final int C_REF = 0;          // a map REFERENCE slot: the precise scan sees it too
    static final int C_LRAW = 1;         // a local the map types as a primitive or an unboxed word
    static final int C_LOREF = 2;        // (unused since S4: an ObjectReference slot is a reference)
    static final int C_LTOP = 3;         // a dead local (TOP): stale, nothing reads it
    static final int C_ORAW = 4;         // an operand below what the bytecode pops, typed primitive / word
    static final int C_OOREF = 5;        // (unused since S4)
    static final int C_OTOP = 6;         // ... dead
    static final int C_OTEMP = 7;        // a live operand the lowering pushed above the bytecode's stack: untyped
    static final int C_OTHER = 8;        // the out-args area (arguments past the eighth, for the frame's callee)
    static final int C_NOSITE = 9;       // a frame whose map has no site for its return address
    static final int C_NOMAP = 10;       // a frame with no map: a stub or trampoline, or a method the dataflow refused
    static final int C_UNWALKED = 11;    // stack the frame walk did not reach (above the top frame, below the last)
    static final int C_REGS = 12;        // a suspended task's saved registers
    static final int C_OABOVE = 13;      // a spill slot the call's spill did not write: stale
    static final int C_PAD = 14;         // a mapped frame's alignment word, past its spill and out-args: stale
    static final int C_BORROW = 15;      // a raw copy of a block a typed slot on the same stack holds: borrowed
    static final int NCAT = 16;

    static long[] hits;                  // per category
    static long[] payloadHits;           // ... of which an allocData payload pointer, not an object base
    static long mapErrors;               // a REFERENCE slot holding a heap address that is no block base
    static long framesWalked;
    static long framesMapped;
    static long refSlots;                // REFERENCE slots seen (non-null)
    static long tasksWalked;
    static long tasksSkipped;            // suspended mid-method (preempted): the top frame is at no call site
    static long checks;

    private static long[] seen;          // (return address, frame offset) keys already printed, open addressing
    /** The blocks the current stack holds in typed slots (a reference, or a data reference's block), open
     *  addressing; filled by a first walk of each stack, so a raw copy of one of them counts as borrowed. */
    private static long[] owners;
    private static final int OWNER_SLOTS = 4096;
    private static boolean collecting;
    private static final int SEEN_SLOTS = 8192;
    private static int printedThisPass;
    private static final int PRINT_PER_PASS = 40;
    private static long errorsPrinted;

    /** Arm the check (from /etc/init). Allocates its tables now, because a collection must not allocate. */
    static void arm()
    {
        hits = new long[NCAT];
        payloadHits = new long[NCAT];
        seen = new long[SEEN_SLOTS];
        owners = new long[OWNER_SLOTS];
        on = 1;
        Uart.write(Magic.bytes("gcmaps: shadow check on\n"));
    }

    /**
     * Run once per collection, after the block bitmap is built and before the sweep. {@code scanFrom} is the
     * collector's argument (the SP of {@code Magic.gc}'s 96-byte save area); {@code stackTop} the top of the
     * collecting task's stack.
     */
    static void check(long scanFrom, long stackTop)
    {
        checks = checks + 1L;
        printedThisPass = 0;
        // The collecting task. gcCollect's own frame sits just below scanFrom; its saved LR is the return
        // address into the method that called Magic.gc, whose frame starts above the 96-byte save area.
        long gcFs = frameSizeAt(Address.fromLong(VM.gcCollect));
        long ret = Address.fromLong(scanFrom - gcFs).loadLong();
        long sp = scanFrom + 96L;
        collectOwners(ret, sp, stackTop);
        unwalked(scanFrom, sp);
        walk(ret, sp, stackTop);
        int me = curTask();
        if (taskSp != null && taskState != null)
        {
            int t = 0;
            while (t < taskCount)
            {
                if (t != me && taskState[t] != TASK_EMPTY && taskState[t] != TASK_RUNNING && taskSp[t] != 0L)
                {
                    long top = stackTopOf(t);
                    if (top != 0L)
                    {
                        suspended(taskSp[t], top);
                    }
                }
                t += 1;
            }
        }
        summary();
    }

    /** Top of task {@code t}'s stack, or 0 when it cannot be named. */
    private static long stackTopOf(int t)
    {
        if (t == 0)
        {
            return STACK_TOP;
        }
        if (taskStackBase != null && taskStackBase[t] != 0L)
        {
            return taskStackBase[t] + 0x8000L;
        }
        if (taskIdle != null && taskIdle[t] != 0 && taskCore != null && taskCore[t] >= 1)
        {
            return ((long) SEC_STACK_HI << 16) + ((long) taskCore[t] << 20);
        }
        return 0L;
    }

    /** A suspended task: its saved context at {@code frame} (x0..x30, ELR at +248), its stack above. */
    private static void suspended(long frame, long top)
    {
        Address f = Address.fromLong(frame);
        long sp = frame + SCHED_FRAME;
        if (sp >= top || top - sp > 0x100000L)
        {
            tasksSkipped = tasksSkipped + 1L;
            return;
        }
        long pc = f.plus(248L).loadLong();
        if (frameSizeAt(Address.fromLong(pc)) != 0L)
        {
            // Preempted inside a framed method: the pc is no call site, and in a prologue [sp] is not yet its
            // saved LR. S3 moves every stop to a safepoint; until then the stack is counted, not walked.
            tasksSkipped = tasksSkipped + 1L;
            unwalked(sp, top);
            return;
        }
        tasksWalked = tasksWalked + 1L;
        collectOwners(f.plus(240L).loadLong(), sp, top);
        long w = frame;
        while (w < frame + 248L)
        {
            classifyLoose(w, C_REGS);
            w += 8L;
        }
        walk(f.plus(240L).loadLong(), sp, top);           // a frameless leaf (taskYield): its caller is in x30
    }

    /** The first walk of a stack: record every block its typed slots hold, and count nothing. */
    private static void collectOwners(long ret, long sp, long top)
    {
        int i = 0;
        while (i < OWNER_SLOTS)
        {
            owners[i] = 0L;
            i += 1;
        }
        collecting = true;
        walk(ret, sp, top);
        collecting = false;
    }

    private static void addOwner(long block)
    {
        int h = (int) ((block ^ (block >>> 17)) >>> 3) & (OWNER_SLOTS - 1);
        int probe = 0;
        while (probe < OWNER_SLOTS)
        {
            long o = owners[h];
            if (o == block)
            {
                return;
            }
            if (o == 0L)
            {
                owners[h] = block;
                return;
            }
            h = (h + 1) & (OWNER_SLOTS - 1);
            probe += 1;
        }
    }

    /** True if word {@code w} (of live kind {@code k}) names a block a typed slot of this stack holds. */
    private static boolean owned(long w, int k)
    {
        long block = k == 2 ? w - 16L : w;
        int h = (int) ((block ^ (block >>> 17)) >>> 3) & (OWNER_SLOTS - 1);
        int probe = 0;
        while (probe < OWNER_SLOTS)
        {
            long o = owners[h];
            if (o == block)
            {
                return true;
            }
            if (o == 0L)
            {
                return false;
            }
            h = (h + 1) & (OWNER_SLOTS - 1);
            probe += 1;
        }
        return false;
    }

    /** Walk frames from return address {@code ret} into a frame at {@code sp}, up to {@code top}. */
    private static void walk(long ret, long sp, long top)
    {
        int guard = 0;
        while (sp < top && guard < 4096)
        {
            long fs = frameSizeAt(Address.fromLong(ret - 4L));
            if (fs == 0L || sp + fs > top)
            {
                break;
            }
            frame(ret, sp, fs);
            if (!collecting)
            {
                framesWalked = framesWalked + 1L;
            }
            long next = Address.fromLong(sp).loadLong();
            sp = sp + fs;
            ret = next;
            guard += 1;
        }
        if (!collecting)
        {
            unwalked(sp, top);
        }
    }

    private static void unwalked(long from, long to)
    {
        long a = from;
        while (a < to)
        {
            classifyLoose(a, C_UNWALKED);
            a += 8L;
        }
    }

    /** Count a word that would be kept alive conservatively, in category {@code cat}. */
    private static void classifyLoose(long a, int cat)
    {
        long w = Address.fromLong(a).loadLong();
        int k = VMGc.liveKind(w);
        if (k != 0)
        {
            if (owned(w, k))
            {
                cat = C_BORROW;
            }
            hits[cat] = hits[cat] + 1L;
            if (k == 2)
            {
                payloadHits[cat] = payloadHits[cat] + 1L;
            }
        }
    }

    /** One frame: {@code ret} is the return address into it, {@code sp} its base, {@code fs} its size. */
    private static void frame(long ret, long sp, long fs)
    {
        Address pcA = Address.fromLong(ret - 4L);
        long ma = gcMapAt(pcA);
        long start = codeStartAt(pcA);
        if (ma <= 0L || start == 0L)
        {
            if (!collecting)
            {
                words(ret, sp, 8L, fs, C_NOMAP);
            }
            return;
        }
        Address map = Address.fromLong(ma);
        if (map.loadInt() != 0x50414D47)                       // RefMaps.MAGIC
        {
            if (collecting)
            {
                return;
            }
            mapError(ret, sp, 0L, ma);
            words(ret, sp, 8L, fs, C_NOMAP);
            return;
        }
        int nSites = map.plus(4L).loadInt();
        int nLoc = map.plus(8L).loadInt();
        int nStk = map.plus(12L).loadInt();
        long locBase = map.plus(16L).loadInt();
        long opBase = map.plus(20L).loadInt();
        int bitWords = map.plus(24L).loadInt();
        int nUnique = map.plus(28L).loadInt();
        long outBase = map.plus(32L).loadInt();
        long outEnd = outBase + map.plus(36L).loadInt() * 8L;
        int retWord = (int) ((ret - start) >> 2);
        Address sites = map.plus(40L);
        int lo = 0;
        int hi = nSites - 1;
        int found = -1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int r = sites.plus(mid * 8L).loadInt();
            if (r == retWord)
            {
                found = mid;
                break;
            }
            if (r < retWord)
            {
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }
        if (found < 0)
        {
            if (!collecting)
            {
                words(ret, sp, 8L, fs, C_NOSITE);
            }
            return;
        }
        if (collecting)
        {
            owners(sites, nSites, found, bitWords, nUnique, nLoc, nStk, locBase, opBase, sp);
            return;
        }
        framesMapped = framesMapped + 1L;
        int sw = sites.plus(found * 8L + 4L).loadInt();
        int idx = sw & 0xFFFF;
        int live = (sw >>> 16) & 0xFF;                           // operand slots the last spill wrote
        int depth = sw >>> 24;                                   // the bytecode's entering depth
        if (idx < 0 || idx >= nUnique)
        {
            mapError(ret, sp, 0L, idx);
            return;
        }
        Address bits = sites.plus(nSites * 8L + (long) idx * bitWords * 4L);
        long off = 8L;                                          // [sp] is the saved LR
        while (off < fs)
        {
            int cat;
            int t = -1;
            if (off >= outBase && off < outEnd)
            {
                cat = C_OTHER;                                  // out-args: a trampoline's callee has not copied them
            }
            else if (off >= locBase && off < locBase + nLoc * 8L)
            {
                t = slotType(bits, (int) ((off - locBase) >> 3));
                cat = t == 1 || t == 3 ? C_REF : (t == 2 ? C_LRAW : C_LTOP);
            }
            else if (off >= opBase && off < opBase + nStk * 8L)
            {
                int i = (int) ((off - opBase) >> 3);
                int typed = live < depth ? live : depth;
                if (i < typed)
                {
                    t = slotType(bits, nLoc + i);
                    cat = t == 1 || t == 3 ? C_REF : (t == 2 ? C_ORAW : C_OTOP);
                }
                else if (i < live)
                {
                    t = slotType(bits, nLoc + i);               // the lowering's own transient: typed only when
                    cat = t == 1 || t == 3 ? C_REF : C_OTEMP;   //   it said so (the concat builder), else untyped
                }
                else
                {
                    cat = C_OABOVE;                             // not spilled for this call: stale
                }
            }
            else
            {
                cat = C_PAD;                                    // the alignment word: this method never writes it
            }
            long w = Address.fromLong(sp + off).loadLong();
            int k = VMGc.liveKind(w);
            if ((t == 1 || t == 3) && w != 0L)
            {
                refSlots = refSlots + 1L;
                if (k != (t == 1 ? 1 : 2) && VMGc.inHeap(w))
                {
                    mapError(ret, sp, off, w);                  // a reference holding no object, a data reference
                }                                               //   no payload
            }
            if (k != 0)
            {
                if (cat != C_REF && cat != C_LTOP && cat != C_OTOP && cat != C_OABOVE && cat != C_PAD && owned(w, k))
                {
                    cat = C_BORROW;
                }
                hits[cat] = hits[cat] + 1L;
                if (k == 2)
                {
                    payloadHits[cat] = payloadHits[cat] + 1L;
                }
                if (cat != C_REF && cat != C_LTOP && cat != C_OTOP && cat != C_OABOVE && cat != C_PAD && cat != C_BORROW)
                {
                    report(ret, off, cat, w);
                }
            }
            off += 8L;
        }
    }

    /** The collecting walk's work for one mapped frame: every typed slot's block goes in {@link #owners}. */
    private static void owners(Address sites, int nSites, int found, int bitWords, int nUnique, int nLoc, int nStk,
            long locBase, long opBase, long sp)
    {
        int sw = sites.plus(found * 8L + 4L).loadInt();
        int idx = sw & 0xFFFF;
        int live = (sw >>> 16) & 0xFF;
        int depth = sw >>> 24;
        if (idx < 0 || idx >= nUnique)
        {
            return;
        }
        Address bits = sites.plus(nSites * 8L + (long) idx * bitWords * 4L);
        int s = 0;
        while (s < nLoc + live)
        {
            long off = s < nLoc ? locBase + s * 8L : opBase + (s - nLoc) * 8L;
            int t = slotType(bits, s);
            if (t == 1 || t == 3)
            {
                long w = Address.fromLong(sp + off).loadLong();
                int k = VMGc.liveKind(w);
                if (k == (t == 1 ? 1 : 2))
                {
                    addOwner(k == 2 ? w - 16L : w);
                }
            }
            s += 1;
        }
    }

    private static int slotType(Address bits, int s)
    {
        int bit = 2 * s;
        int word = bits.plus((long) (bit >> 5) * 4L).loadInt();
        return (word >>> (bit & 31)) & 3;
    }

    /** Count every live-looking word of an unmapped frame in {@code cat}, reporting each new one. */
    private static void words(long ret, long sp, long from, long fs, int cat)
    {
        long off = from;
        while (off < fs)
        {
            long w = Address.fromLong(sp + off).loadLong();
            int k = VMGc.liveKind(w);
            if (k != 0)
            {
                int c = owned(w, k) ? C_BORROW : cat;
                hits[c] = hits[c] + 1L;
                if (k == 2)
                {
                    payloadHits[c] = payloadHits[c] + 1L;
                }
                if (c != C_BORROW)
                {
                    report(ret, off, c, w);
                }
            }
            off += 8L;
        }
    }

    /** Print a (site, slot) the first time it holds a reference the maps do not describe. */
    private static void report(long ret, long off, int cat, long w)
    {
        long key = (ret << 16) ^ off;
        int h = (int) ((key ^ (key >>> 29)) & (SEEN_SLOTS - 1));
        int probe = 0;
        while (probe < SEEN_SLOTS)
        {
            long s = seen[h];
            if (s == key)
            {
                return;
            }
            if (s == 0L)
            {
                seen[h] = key;
                break;
            }
            h = (h + 1) & (SEEN_SLOTS - 1);
            probe += 1;
        }
        if (probe >= SEEN_SLOTS || printedThisPass >= PRINT_PER_PASS)
        {
            return;
        }
        printedThisPass += 1;
        Uart.write(Magic.bytes("gcmap+ "));
        Uart.write(catName(cat));
        if (VMGc.liveKind(w) == 2)
        {
            Uart.write(isBlob(w) ? Magic.bytes("/blob") : (isType(w) ? Magic.bytes("/type") : Magic.bytes("/payload")));
        }
        Uart.write(Magic.bytes(" off="));
        printHex(off);
        Uart.write(Magic.bytes(" w="));
        printHex(w);
        Uart.write(Magic.bytes(" at "));
        StackTrace.printFrameAt(Address.fromLong(ret - 4L));
        Uart.putc((byte) 0x0A);
    }

    /** True if {@code w} is a registered class's Type record. */
    private static boolean isType(long w)
    {
        if (ClassRegistry.clTab == null)
        {
            return false;
        }
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i] != null && ClassRegistry.clTab[i].type.toLong() == w)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** True if {@code w} is a class-file blob in the loader's blob table. */
    private static boolean isBlob(long w)
    {
        if (Loader.pdBase == null)
        {
            return false;
        }
        int i = 0;
        while (i < Loader.pdCount)
        {
            if (Loader.pdBase.get(i).toLong() == w)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    private static void mapError(long ret, long sp, long off, long w)
    {
        mapErrors = mapErrors + 1L;
        if (errorsPrinted >= 20L)
        {
            return;
        }
        errorsPrinted = errorsPrinted + 1L;
        Uart.write(Magic.bytes("gcmap MAP ERROR off="));
        printHex(off);
        Uart.write(Magic.bytes(" w="));
        printHex(w);
        Uart.write(Magic.bytes(" sp="));
        printHex(sp);
        Uart.write(Magic.bytes(" at "));
        StackTrace.printFrameAt(Address.fromLong(ret - 4L));
        Uart.putc((byte) 0x0A);
    }

    private static byte[] catName(int cat)
    {
        if (cat == C_REF)
        {
            return Magic.bytes("ref");
        }
        if (cat == C_LRAW)
        {
            return Magic.bytes("local-raw");
        }
        if (cat == C_LOREF)
        {
            return Magic.bytes("local-objref");
        }
        if (cat == C_LTOP)
        {
            return Magic.bytes("local-dead");
        }
        if (cat == C_ORAW)
        {
            return Magic.bytes("op-raw");
        }
        if (cat == C_OOREF)
        {
            return Magic.bytes("op-objref");
        }
        if (cat == C_OTOP)
        {
            return Magic.bytes("op-dead");
        }
        if (cat == C_OTEMP)
        {
            return Magic.bytes("op-temp");
        }
        if (cat == C_OTHER)
        {
            return Magic.bytes("out-args");
        }
        if (cat == C_PAD)
        {
            return Magic.bytes("frame-pad");
        }
        if (cat == C_BORROW)
        {
            return Magic.bytes("borrowed");
        }
        if (cat == C_NOSITE)
        {
            return Magic.bytes("no-site");
        }
        if (cat == C_NOMAP)
        {
            return Magic.bytes("no-map");
        }
        if (cat == C_UNWALKED)
        {
            return Magic.bytes("unwalked");
        }
        if (cat == C_OABOVE)
        {
            return Magic.bytes("op-above");
        }
        return Magic.bytes("regs");
    }

    /** One line per collection: cumulative counts. */
    private static void summary()
    {
        Uart.write(Magic.bytes("gcmap #"));
        printDec((int) checks);
        Uart.write(Magic.bytes(" frames="));
        printDec((int) framesWalked);
        Uart.write(Magic.bytes(" mapped="));
        printDec((int) framesMapped);
        Uart.write(Magic.bytes(" refslots="));
        printDec((int) refSlots);
        Uart.write(Magic.bytes(" tasks="));
        printDec((int) tasksWalked);
        Uart.write(Magic.bytes("/skip"));
        printDec((int) tasksSkipped);
        Uart.write(Magic.bytes(" mapErr="));
        printDec((int) mapErrors);
        int c = 0;
        while (c < NCAT)
        {
            Uart.putc((byte) 0x20);
            Uart.write(catName(c));
            Uart.putc((byte) 0x3D);
            printDec((int) hits[c]);
            if (payloadHits[c] != 0L)
            {
                Uart.write(Magic.bytes("(p"));
                printDec((int) payloadHits[c]);
                Uart.putc((byte) 0x29);
            }
            c += 1;
        }
        Uart.putc((byte) 0x0A);
    }
}
