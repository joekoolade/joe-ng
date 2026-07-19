package magic;

/**
 * VM magic: the privileged-op and raw-memory intrinsics that let boot code stay
 * Java (PLAN.md §2, §5.1). Each method here is a <em>marker</em> — the baseline
 * compiler recognizes calls to it and lowers them to specific A64 instructions
 * instead of emitting a real method call. They are never executed on the seed
 * JVM (the bodies throw), and they never exist as callable methods on the metal;
 * they are compile-time lowering hooks.
 *
 * This is what keeps "everything is Java" literally true for the boot path: the
 * control flow and structure come from ordinary Java bytecode, and each of these
 * calls becomes one (or a few) privileged instructions.
 */
public final class Magic
{
    private Magic() {}

    private static RuntimeException intrinsic()
    {
        return new UnsupportedOperationException("magic intrinsic — lowered by the compiler, not executed");
    }

    // ----- hints / parking -------------------------------------------------
    public static void wfe()
    {
        throw intrinsic();
    }
    public static void isb()
    {
        throw intrinsic();
    }
    /** Data synchronization barrier (full system) — publish stores before a fetch. */
    public static void dsb()
    {
        throw intrinsic();
    }

    /** Call freshly-written machine code at {@code addr} (no args); return x0.
     *  Used by the runtime class loader to run a method it just JIT-compiled. */
    public static long call0(long addr)
    {
        throw intrinsic();
    }

    /** Call JIT'd code at {@code addr} with two int args (in the loader's
     *  local-slot convention: slot0=x1, slot1=x2); return x0. */
    public static long call2(long addr, long a, long b)
    {
        throw intrinsic();
    }

    /**
     * Run a garbage collection. Lowered to a sequence that spills the callee-saved
     * registers (so live references held there become scannable on the stack) and
     * calls the conservative collector, then restores them.
     */
    public static void gc()
    {
        throw intrinsic();
    }

    // ----- exception-level control (EL2 -> EL1 drop) -----------------------
    /**
     * Drop from EL2 to EL1 in one privileged step (skipped if already at EL1).
     * A single intrinsic because the EL2→EL1 drop is self-referential — it must
     * set {@code ELR_EL2} to the address of the instruction right after itself,
     * which ordinary Java bytecode cannot name. The compiler owns that address.
     */
    public static void dropToEL1()
    {
        throw intrinsic();
    }

    public static long readCurrentEL()
    {
        throw intrinsic();
    }
    public static void writeHCR_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeCPTR_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeCNTHCTL_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeCNTVOFF_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeSCTLR_EL1(long v)
    {
        throw intrinsic();
    }
    public static void writeSPSR_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeELR_EL2(long v)
    {
        throw intrinsic();
    }
    public static void writeCPACR_EL1(long v)
    {
        throw intrinsic();
    }
    public static void eret()
    {
        throw intrinsic();
    }

    // ----- stack -----------------------------------------------------------
    public static void writeSP(long v)
    {
        throw intrinsic();
    }
    /** Read the current stack pointer (for the exception unwinder). */
    public static long readSP()
    {
        throw intrinsic();
    }

    /**
     * Resume execution at a handler: set SP, place {@code exc} in the handler's
     * operand-stack slot (x9), and branch to {@code pc}. Never returns — used by
     * the exception unwinder to transfer control to a catch block in a caller.
     */
    public static void resume(long pc, long sp, long exc)
    {
        throw intrinsic();
    }

    // ----- raw memory (MMIO) ----------------------------------------------
    public static void store32(long addr, int value)
    {
        throw intrinsic();
    }
    public static int  load32(long addr)
    {
        throw intrinsic();
    }
    public static void store8(long addr, int value)
    {
        throw intrinsic();
    }
    public static int  load8(long addr)
    {
        throw intrinsic();
    }
    public static void store64(long addr, long value)
    {
        throw intrinsic();
    }
    public static long load64(long addr)
    {
        throw intrinsic();
    }

    /**
     * Type adapter for string literals: a {@code String} constant is interned by
     * the compiler as a real heap-layout {@code byte[]} object in the image (ASCII
     * bytes), so {@code ldc "..."} already yields a {@code byte[]} reference. This
     * call is lowered to nothing — it only lets Java source name the bytes without
     * a {@code java.lang.String} class (which joe2 does not yet have).
     */
    public static byte[] bytes(String literal)
    {
        throw intrinsic();
    }
}
