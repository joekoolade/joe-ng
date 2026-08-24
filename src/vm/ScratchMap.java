package vm;

import magic.Magic;
import board.bcm2711.Uart;

/**
 * The fixed-scratch memory map, and a boot-time assertion that no two regions overlap.
 *
 * <p><b>Why this exists.</b> The band above the image (0x0300_0000 upward) holds two dozen tables whose
 * addresses are hand-picked hex constants, each declared next to the code that uses it and nowhere else.
 * Nothing has ever checked them against each other, and in one session that cost four bugs:
 *
 * <ul>
 *   <li>{@code STUB_TAB} placed at 0x0374_0000 (#145) landed on {@code Heap.STATS}, {@code VMGc.STALE_TAB}
 *       and {@code VMGc.FREED_RANGES} -- three tables at once, corrupting the allocation histograms and the
 *       swept-range log that makes a code fault readable.
 *   <li>Its replacement was first tried at 0x0380_0000, which LOOKS like the free hole below
 *       {@code MARK_BITMAP} but is where {@code VM.SEC_STACK_HI} puts core 1's stack, growing down.
 *   <li>{@code Heap.STATS} turned out never to have been initialised at all -- invisible for as long as a
 *       neighbour was scribbling it, and only observable on hardware, whose DRAM comes up all-ones.
 *   <li>{@code CodeCompact.PLAN_TAB} was placed exactly on top of {@code CodeEdges.TAB}, so the compaction
 *       plan overwrote the edge census with block addresses.
 * </ul>
 *
 * <p><b>None of them faulted.</b> Every one produced plausible-looking wrong numbers that survived until
 * something was compared against a baseline. That is the argument for a check rather than more care: these
 * are compile-time constants, so an overlap is either always present or never, and one boot finds it.
 *
 * <p><b>Adding a fixed-scratch region means adding an entry here.</b> The check can only see what it is
 * told about; an unregistered table is exactly as invisible as before. Regions are recorded as
 * RESERVATIONS (typically to the next region's start) rather than current usage, so a new table dropped
 * into the slack of an existing claim is caught rather than silently tolerated until it grows.
 */
final class ScratchMap
{
    private static final int MAX = 48;
    private static long[] lo;
    private static long[] hi;
    private static int n;

    private static void add(long start, long end)
    {
        if (n >= MAX)
        {
            return;
        }
        lo[n] = start;
        hi[n] = end;
        n += 1;
    }

    /** Every fixed-scratch reservation above the image, in address order. */
    private static void populate()
    {
        lo = new long[MAX];
        hi = new long[MAX];
        n = 0;
        add(VM.SEC_STUB, VM.PT_BASE);                       // secondary boot stub
        add(VM.PT_BASE, VM.LOCK_ADDR);                      // page tables (1 L1 + 4 L2)
        add(VM.LOCK_ADDR, VM.PC_VBAR);                      // shared HW-spinlock word
        add(VM.PC_VBAR, VM.CORE_FLAGS);                     // secondaries' IRQ vectors
        add(VM.CORE_FLAGS, board.bcm2711.Bcm2711.MBOX_BUFFER);
        add(board.bcm2711.Bcm2711.MBOX_BUFFER, Loader.STUB_TAB);
        add(Loader.STUB_TAB, CodeEdges.TAB);                // deferral-stub table
        add(CodeEdges.TAB, CodeEdges.TAB_END);              // code->code edge census
        add(CodeCompact.PLAN_TAB, Loader.CODE_ROOTS);       // compaction relocation plan
        add(Loader.CODE_ROOTS, Loader.CODE_ROOTS_END);
        add(VMGc.CODE_BITMAP, Heap.CODE_BLOCKS);            // code-reachability bitmap
        add(Heap.CODE_BLOCKS, Heap.CODE_BLOCKS_END);        // code block registry
        add(Heap.CODE_BLOCKS_END, Heap.STATS);              // CODE_INDEX (address->index hash)
        add(Heap.STATS, 0x0374_0400L);                      // allocation histograms + LARGE_RING
        add(0x0374_0400L, 0x0376_0000L);                    // VMGc.STALE_TAB
        add(0x0376_0000L, Heap.CODE_PIN_BITMAP);            // VMGc.FREED_RANGES
        add(Heap.CODE_PIN_BITMAP, 0x037C_0000L);            // code pin bitmap
        add(0x037C_0000L, 0x0380_0000L);                    // VMGc.SWEPT_LOG
        add(0x0380_0000L, VMGc.MARK_BITMAP);                // cores 1-3 stacks (VM.SEC_STACK_HI, grow DOWN)
        add(VMGc.MARK_BITMAP, Heap.JIT_TABLES);             // heap block-start bitmap (3 MiB)
        add(Heap.JIT_TABLES, VMGc.MARK_STACK);              // JIT frame/handler/local tables
        add(VMGc.MARK_STACK, Heap.PTR_CELL);                // GC mark stack
        add(Heap.PTR_CELL, 0x0400_0000L);                   // bump-pointer / free-list cells
    }

    /**
     * Halt if any two reservations overlap, or if one is malformed. A compile-time-constant error, so this
     * either fires on every boot or never -- printing and spinning is the right response, not a warning
     * that scrolls past.
     */
    static void check()
    {
        populate();
        int i = 0;
        while (i < n)
        {
            if (hi[i] <= lo[i])
            {
                fail(i, i);
            }
            int j = i + 1;
            while (j < n)
            {
                if (lo[j] < hi[i] && lo[i] < hi[j])
                {
                    fail(i, j);
                }
                j += 1;
            }
            i += 1;
        }
    }

    /** Name the two ranges and stop. Addresses are unambiguous -- grep the constant to find the owner. */
    private static void fail(int a, int b)
    {
        Uart.write(Magic.bytes("SCRATCH MAP OVERLAP "));
        VM.printHex(lo[a]);
        Uart.putc(0x2D);                                    // '-'
        VM.printHex(hi[a]);
        Uart.write(Magic.bytes(" vs "));
        VM.printHex(lo[b]);
        Uart.putc(0x2D);
        VM.printHex(hi[b]);
        Uart.putc(0x0A);
        for (;;)
        {
            Magic.wfe();
        }
    }
}
