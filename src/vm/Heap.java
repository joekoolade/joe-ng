package vm;

import magic.Magic;
import objectmodel.ObjectModel;

/**
 * The heap — a bump allocator with a conservative mark-sweep collector
 * ({@link VM#gcCollect}). Every object records its own allocation size in the
 * status word (low bit reserved as the GC mark), so the heap is walkable and the
 * collector can size and trace objects without per-type maps. Freed objects go on
 * a free list that {@link #alloc} reuses (first fit) before bumping.
 *
 * <p>The MMU is off (flat RAM); objects stay 8-byte aligned so unaligned 8-byte
 * accesses don't fault.
 */
public final class Heap
{
    private Heap() {}

    // PER-CORE ARENAS. Each core bump-allocates from its OWN region + free-list, so cores 1-3 can allocate
    // concurrently with core 0 (no shared-bump-pointer race), and core 0's between-batch demand-load heap
    // reclaim (Loader) only rewinds ITS arena — never under a secondary that's allocating. Core 0's arena is
    // the original heap (BASE, reclaimed); the secondaries only ever hold short-lived SMP-demo data, so their
    // 64 MiB arenas up high are plenty and are never GC'd. The MMU identity-maps the low 4 GiB (Pi 4 = 4 GiB).

    /** Base of the per-core bump-pointer cells: core c's cell is {@code PTR_CELL + c*8}. Core 0's stays at
     *  {@code PTR_CELL} so {@link VM#gcCollect} still reads it directly. */
    public static final long PTR_CELL  = 0x03FF_0000L;
    /** Base of the per-core free-list heads: core c's head is {@code FREE_CELL + c*8}. */
    public static final long FREE_CELL = 0x03FF_0100L;
    /** Start of CORE 0's allocation region (64 MiB) — above the ~29 MiB all-java.base image (from 0x80000)
     *  and the 48-56 MiB scratch band; grows up. {@link VM#gcCollect} walks BASE..PTR_CELL. */
    public static final long BASE      = 0x0400_0000L;

    // ----- JIT code arena (#43) -------------------------------------------------------------------------
    // Demand-loaded methods are compiled into their OWN low arena, NOT interleaved with the data heap. Data
    // (TIBs, 4KiB imaps, interned strings, per-batch scratch) outweighs code ~400x and marches the data heap
    // pointer to ~200MiB; if code buffers rode along there, a JIT'd bl to the image's VM-helper island (~0.5MiB)
    // would exceed the A64 +-128MiB reach (FAIL_BL_RANGE). Placed at 32-48MiB (above the ~28MiB image, below
    // the 48MiB SEC_STUB scratch), every code buffer stays within ~48MiB of the helpers and of each other. The
    // whole boot's code is only a few MiB, so this simple no-reclaim bump arena has ample room.
    public static final long CODE_BASE     = 0x0200_0000L;   // 32 MiB
    public static final long CODE_LIMIT    = 0x0300_0000L;   // 48 MiB (= VM.SEC_STUB) — overflow guard
    public static final long CODE_PTR_CELL = 0x03FF_0200L;   // code-arena bump pointer (near PTR_CELL/FREE_CELL)

    static int  lastFromFreeList;      // 1 if the last alloc reused a freed block (GC evidence)

    /** Base of core {@code c}'s arena. Core 0 = {@link #BASE}; secondaries carve 64 MiB slots from 256 MiB up. */
    static long arenaBase(int core)
    {
        return core == 0 ? BASE : 0x1000_0000L + (long) (core - 1) * 0x0400_0000L;
    }

    /** Seed every core's bump pointer + free list. Call once, early in boot, before any {@code new}. */
    public static void init()
    {
        int c = 0;
        while (c < 4)
        {
            Magic.store64(PTR_CELL + c * 8L, arenaBase(c));
            Magic.store64(FREE_CELL + c * 8L, 0L);
            c += 1;
        }
        Magic.store64(CODE_PTR_CELL, CODE_BASE);
    }

    /**
     * Allocate a JIT code buffer from the low code arena (see {@link #CODE_BASE}). Kept separate from the data
     * heap so compiled code stays within the A64 {@code bl} reach of the image's VM helpers. Code is written in
     * full by the compiler, so no zeroing; {@link #publishCode} handles I-cache coherence. Core-0 only (the JIT
     * runs on core 0). No reclaim -- the whole boot's code is a few MiB, far under the 16 MiB arena.
     */
    public static long allocCode(int size)
    {
        int aligned = (size + 7) & -8;
        long p = Magic.load64(CODE_PTR_CELL);
        Magic.store64(CODE_PTR_CELL, p + aligned);
        return p;
    }

    /**
     * Make freshly-written code in {@code [start,end)} visible to instruction fetch:
     * clean each data-cache line to the point of unification, then invalidate the whole
     * instruction cache. Required before executing JIT'd code on real hardware — the Pi 4
     * boots with the caches enabled, so a bare {@code dsb;isb} leaves stale I-cache lines
     * over the just-written buffer and the CPU fetches garbage. QEMU models no I-cache, so
     * this is a no-op-shaped sequence there. (PLAN.md §D; M5.5c JIT publish.)
     */
    public static void publishCode(long start, long end)
    {
        long a = start & ~63L;                 // Cortex-A72 cache line = 64 bytes
        while (a < end)
        {
            Magic.dcCVAU(a);                   // clean the D-cache line to PoU
            a += 64L;
        }
        Magic.dsb();                           // the cleans reach unified memory
        Magic.icIALLU();                       // drop stale I-cache lines
        Magic.dsb();                           // the invalidate completes
        Magic.isb();                           // refetch past this point
    }

    /** Allocate {@code size} bytes: reuse a freed block if one fits, else bump. */
    public static long alloc(int size)
    {
        int aligned = (size + 7) & -8;
        int core = (int) (Magic.readMPIDR() & 3L);          // this core's arena (low 2 bits of MPIDR)
        long freeCell = FREE_CELL + core * 8L;
        long ptrCell = PTR_CELL + core * 8L;
        long prev = 0L;
        long f = Magic.load64(freeCell);
        while (f != 0L)                                     // first fit in this core's free list
        {
            long fsize = Magic.load64(f + ObjectModel.STATUS_OFFSET);
            if (fsize >= aligned)
            {
                long next = Magic.load64(f);
                if (prev == 0L)
                {
                    Magic.store64(freeCell, next);
                }
                else
                {
                    Magic.store64(prev, next);
                }
                lastFromFreeList = 1;
                zeroPayload(f, aligned);                    // Java requires 0/default fields+elements
                return f;                                   // status already holds the block size
            }
            prev = f;
            f = Magic.load64(f);
        }
        long p = Magic.load64(ptrCell);
        Magic.store64(ptrCell, p + aligned);
        Magic.store64(p + ObjectModel.STATUS_OFFSET, aligned);   // record size for the GC
        lastFromFreeList = 0;
        zeroPayload(p, aligned);
        return p;
    }

    /**
     * Zero an allocation's payload (past the {TIB, status} header), so freshly-allocated
     * objects and arrays honour Java's default initialization. Real hardware RAM comes up
     * with garbage; without this, uninitialized-but-assumed-zero fields (e.g. a compiler's
     * counter) hold junk and code that ran on QEMU (whose RAM starts zeroed) hangs on metal.
     */
    private static void zeroPayload(long base, int aligned)
    {
        Magic.store64(base + ObjectModel.TIB_OFFSET, 0L);   // Also zero offset 0 (the TIB slot). A RAW allocation
        // (TIB / itableDir / imap / a code buffer) that reads offset 0 before its caller sets it would otherwise get
        // garbage on a warm reboot (RAM isn't cleared on a guest-triggered reset) -> intermittent wild branches.
        // Offset 8 is STATUS (deterministically the block size, set by alloc), so it needs no zeroing.
        long z = base + ObjectModel.HEADER_SIZE;
        long end = base + aligned;
        while (z < end)
        {
            Magic.store64(z, 0L);
            z += 8L;
        }
    }

    /** Allocate an array of {@code length} elements of {@code elemSize} bytes. */
    public static long allocArray(int length, int elemSize)
    {
        long p = alloc(ObjectModel.ARRAY_BASE_OFFSET + length * elemSize);
        // The array's TIB slot holds its element size (1/2/4/8) — small, so it's distinguishable from an
        // object's TIB (a heap pointer), and it lets a generic System.arraycopy compute byte offsets. The
        // conservative GC never derefs this slot (it scans from +16), so a non-pointer here is safe.
        Magic.store64(p + ObjectModel.TIB_OFFSET, elemSize);
        Magic.store64(p + ObjectModel.ARRAY_LENGTH_OFFSET, length);
        return p;
    }

    /** Reset the current core's free list (start of a sweep — the GC runs on the collecting core). */
    static void resetFreeList()
    {
        Magic.store64(FREE_CELL + (int) (Magic.readMPIDR() & 3L) * 8L, 0L);
    }

    /** Add a reclaimed block to the current core's free list. */
    static void addFree(long addr, long size)
    {
        long freeCell = FREE_CELL + (int) (Magic.readMPIDR() & 3L) * 8L;
        Magic.store64(addr, Magic.load64(freeCell));             // next
        Magic.store64(addr + ObjectModel.STATUS_OFFSET, size);   // size
        Magic.store64(freeCell, addr);
    }
}
