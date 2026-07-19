package vm;

import magic.Magic;

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

    /** Allocate {@code size} bytes (already header-inclusive and aligned). */
    public static long alloc(int size) {
        long p = Magic.load64(PTR_CELL);
        Magic.store64(PTR_CELL, p + size);
        return p;
    }
}
