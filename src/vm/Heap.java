package vm;

import magic.Magic;
import objectmodel.ObjectModel;

/**
 * The heap — a bump allocator, written in Java (metacircular: the allocator the
 * compiled {@code new} calls is itself compiled Java). No free/GC yet; that is
 * M6. The MMU is off (flat RAM), so we just hand out ascending addresses from a
 * fixed region above the image and stack.
 *
 * <p>A single 8-byte cell at {@link #PTR_CELL} holds the next-free pointer;
 * {@link #init()} seeds it (called once from {@code VM.boot} before any
 * allocation). Objects are not zeroed — the object model's status word is unused
 * until ~M6 and fields are set by constructors.
 */
public final class Heap {
    private Heap() {}

    /** 8-byte cell holding the bump pointer (free RAM below the heap). */
    public static final long PTR_CELL = 0x000F_0000L;
    /** Start of the allocation region (1 MiB; well above image@0x80000 and the stack). */
    public static final long BASE     = 0x0010_0000L;

    /** Seed the bump pointer. Call once, early in boot, before any {@code new}. */
    public static void init() {
        Magic.store64(PTR_CELL, BASE);
    }

    /** Allocate {@code size} bytes, rounding the bump up to keep objects 8-aligned
     *  (the MMU is off, so unaligned 8-byte accesses would fault). */
    public static long alloc(int size) {
        long p = Magic.load64(PTR_CELL);
        int aligned = (size + 7) & -8;
        Magic.store64(PTR_CELL, p + aligned);
        return p;
    }

    /** Allocate an array of {@code length} elements of {@code elemSize} bytes and
     *  write its header (null TIB for now, then the length). Returns the reference. */
    public static long allocArray(int length, int elemSize) {
        long p = alloc(ObjectModel.ARRAY_BASE_OFFSET + length * elemSize);
        Magic.store64(p + ObjectModel.TIB_OFFSET, 0L);           // array TIBs come with GC/instanceof
        Magic.store64(p + ObjectModel.ARRAY_LENGTH_OFFSET, length);
        return p;
    }
}
