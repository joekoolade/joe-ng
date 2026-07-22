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

    /** 8-byte cell holding the bump pointer (free RAM below the heap). */
    public static final long PTR_CELL = 0x000F_0000L;
    /** Start of the allocation region (1 MiB; well above image@0x80000 and the stack). */
    public static final long BASE     = 0x0010_0000L;

    static long freeHead;              // free-list head, 0 = empty (nodes: [next @0][size @8])
    static int  lastFromFreeList;      // 1 if the last alloc reused a freed block (GC evidence)

    /** Seed the bump pointer. Call once, early in boot, before any {@code new}. */
    public static void init()
    {
        Magic.store64(PTR_CELL, BASE);
        freeHead = 0L;
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
        long prev = 0L;
        long f = freeHead;
        while (f != 0L)                                     // first fit
        {
            long fsize = Magic.load64(f + ObjectModel.STATUS_OFFSET);
            if (fsize >= aligned)
            {
                long next = Magic.load64(f);
                if (prev == 0L)
                {
                    freeHead = next;
                }
                else
                {
                    Magic.store64(prev, next);
                }
                lastFromFreeList = 1;
                return f;                                   // status already holds the block size
            }
            prev = f;
            f = Magic.load64(f);
        }
        long p = Magic.load64(PTR_CELL);
        Magic.store64(PTR_CELL, p + aligned);
        Magic.store64(p + ObjectModel.STATUS_OFFSET, aligned);   // record size for the GC
        lastFromFreeList = 0;
        return p;
    }

    /** Allocate an array of {@code length} elements of {@code elemSize} bytes. */
    public static long allocArray(int length, int elemSize)
    {
        long p = alloc(ObjectModel.ARRAY_BASE_OFFSET + length * elemSize);
        Magic.store64(p + ObjectModel.TIB_OFFSET, 0L);           // array TIBs come with typed GC
        Magic.store64(p + ObjectModel.ARRAY_LENGTH_OFFSET, length);
        return p;
    }

    /** Reset the free list (start of a sweep). */
    static void resetFreeList()
    {
        freeHead = 0L;
    }

    /** Add a reclaimed block to the free list. */
    static void addFree(long addr, long size)
    {
        Magic.store64(addr, freeHead);                           // next
        Magic.store64(addr + ObjectModel.STATUS_OFFSET, size);   // size
        freeHead = addr;
    }
}
