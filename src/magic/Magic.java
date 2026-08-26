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
    /** {@code SVC #0} — supervisor call; traps to the EL1 sync vector (used to yield the CPU). */
    public static void svc()
    {
        throw intrinsic();
    }
    /** {@code SEV} — send event; wakes other cores parked in WFE (used to release secondaries). */
    public static void sev()
    {
        throw intrinsic();
    }
    /** {@code MSR MAIR_EL1} — set the memory-attribute indirection register. */
    public static void writeMAIR_EL1(long v)
    {
        throw intrinsic();
    }
    /** {@code MSR TCR_EL1} — set the translation-control register. */
    public static void writeTCR_EL1(long v)
    {
        throw intrinsic();
    }
    /** {@code MSR TTBR0_EL1} — set the translation-table base register. */
    public static void writeTTBR0_EL1(long v)
    {
        throw intrinsic();
    }
    /** {@code TLBI VMALLE1} — invalidate all EL1 stage-1 TLB entries. */
    public static void tlbiAll()
    {
        throw intrinsic();
    }
    /** Acquire the spinlock at {@code addr} (a shared 32-bit word); spins until held. */
    public static void spinLock(long addr)
    {
        throw intrinsic();
    }
    /** Release the spinlock at {@code addr}. */
    public static void spinUnlock(long addr)
    {
        throw intrinsic();
    }
    /** Data synchronization barrier (full system) — publish stores before a fetch. */
    public static void dsb()
    {
        throw intrinsic();
    }

    /** {@code DC CVAU, addr} — clean the data-cache line at {@code addr} to the point of unification. */
    public static void dcCVAU(long addr)
    {
        throw intrinsic();
    }

    /** {@code DC CVAC, addr} — clean the data-cache line at {@code addr} to the point of coherence
     *  (so an uncached agent -- another core with its MMU still off, or DMA -- sees the write). */
    public static void dcCVAC(long addr)
    {
        throw intrinsic();
    }

    /** {@code DC CIVAC, addr} — clean AND invalidate the data-cache line at {@code addr} to the point of
     *  coherence: pushes any dirty write out, then drops the stale cached copy so a subsequent read sees what
     *  an uncached agent (DMA, or the mailbox firmware via the bus alias) wrote. Use before reading a reply. */
    public static void dcCIVAC(long addr)
    {
        throw intrinsic();
    }

    /** {@code IC IALLU} — invalidate the whole instruction cache to the point of unification. */
    /**
     * {@code IC IVAU} — invalidate the instruction-cache line covering {@code addr}, to the point of
     * unification, ACROSS EVERY CORE. Cache maintenance by virtual address is broadcast to the Inner
     * Shareable domain; {@link #icIALLU()} is local to the calling core and so cannot publish code that
     * another core will execute.
     */
    public static void icIVAU(long addr)
    {
        throw intrinsic();
    }

    public static void icIALLU()
    {
        throw intrinsic();
    }

    /** Call freshly-written machine code at {@code addr} (no args); return x0.
     *  Used by the runtime class loader to run a method it just JIT-compiled. */
    public static long call0(long addr)
    {
        throw intrinsic();
    }

    /** Call JIT'd code at {@code addr} with two int args in the standard argument
     *  registers (x0, x1) — JIT'd methods follow the same convention as compiled
     *  ones (PLAN.md §M5.2); return x0. */
    public static long call2(long addr, long a, long b)
    {
        throw intrinsic();
    }

    /**
     * Call JIT'd code at {@code addr} with up to 8 register arguments loaded from {@code argsPtr}: x0 &lt;-
     * [argsPtr+0], x1 &lt;- [argsPtr+8], ... x7 &lt;- [argsPtr+56]; return x0. The callee reads only the args
     * its arity uses (extra loaded registers are ignored). The general N-arg call behind reflective
     * {@code Method.invoke}/{@code Constructor.newInstance}; the caller lays out the 8-long buffer per the
     * target's descriptor (receiver + marshalled args, zero-padded).
     */
    public static long callN(long addr, long argsPtr)
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

    /** {@code MRS MPIDR_EL1} — this core's affinity; low 2 bits are the core id on BCM2711. */
    public static long readMPIDR()
    {
        throw intrinsic();
    }

    /**
     * The same {@code MRS MPIDR_EL1} as {@link #readMPIDR()}, under a name short enough for the on-metal
     * JIT's magic table (it packs a name into a long, so eight characters is the ceiling). This is the one
     * a demand-loaded guest class can call to ask which core it is running on.
     */
    public static long mpidr()
    {
        throw intrinsic();
    }

    // ----- scheduler ops for JIT-loaded guest code (the mini java.base runtime) -----
    /** Start a task running {@code r}'s {@code run()} (java/lang/Runnable) on its own stack. */
    public static void spawn(Object r)
    {
        throw intrinsic();
    }

    /** Blocking down/acquire on counting semaphore {@code s}. */
    public static void semWait(int s)
    {
        throw intrinsic();
    }

    /** up/release on counting semaphore {@code s}, waking one waiter. */
    public static void semPost(int s)
    {
        throw intrinsic();
    }

    /** Sleep this task at least {@code ms} milliseconds (yields; does not busy-wait). */
    public static void sleepMs(long ms)
    {
        throw intrinsic();
    }
    /** {@code o.wait(ms)} (ms<=0 = forever) -> VM.objWait: park the current task on {@code o} until notified. */
    public static void mwait(Object o, long ms)
    {
        throw intrinsic();
    }
    /** {@code o.notify()} -> VM.objNotify: wake one task waiting on {@code o}. */
    public static void mnotify(Object o)
    {
        throw intrinsic();
    }
    /** {@code o.notifyAll()} -> VM.objNotifyAll: wake every task waiting on {@code o}. */
    public static void mnotall(Object o)
    {
        throw intrinsic();
    }
    /** {@code t.join()} -> VM.threadJoin: block until the task running Thread {@code t} has exited. */
    public static void tjoin(Object t)
    {
        throw intrinsic();
    }
    /** {@code t.getStackTrace()} -> VM.threadStackTrace: a StackTraceElement[] for Thread {@code t}'s stack.
     *  Lowered with the caller's PC+SP appended so a self-trace can walk from the call site. */
    public static StackTraceElement[] stacktr(Object t)
    {
        throw intrinsic();
    }
    /** {@code Thread.getAllStackTraces()} helper -> VM.allThreads: every live task's java/lang/Thread object. */
    public static Thread[] allthr()
    {
        throw intrinsic();
    }
    /** {@code Thread.holdsLock(o)} -> VM.holdsLock: true if the current task owns {@code o}'s monitor. */
    public static boolean hldlock(Object o)
    {
        throw intrinsic();
    }
    /** {@code Thread.interrupt()} -> VM.interrupt: set {@code t}'s interrupt flag + wake it if sleeping/blocked. */
    public static void intr(Object t)
    {
        throw intrinsic();
    }
    /** {@code Thread.isInterrupted()} -> VM.isInterrupted: {@code t}'s interrupt flag (does not clear it). */
    public static boolean isintr(Object t)
    {
        throw intrinsic();
    }
    /** Read + clear the current task's interrupt flag (Thread.sleep uses this to throw InterruptedException once). */
    public static boolean wasintr()
    {
        throw intrinsic();
    }
    /** {@code Thread.isAlive()} -> VM.isAlive: true if {@code t} has started and not yet terminated. */
    public static boolean isalive(Object t)
    {
        throw intrinsic();
    }
    /** {@code Thread.join(Duration)} -> VM.joinTimed: wait up to {@code millis}; 3=not started 1=done 2=intr 0=timeout. */
    public static int joinms(Object t, long millis)
    {
        throw intrinsic();
    }
    /** {@code LockSupport.park()} -> VM.park: block the current task until a permit (unpark) is available. */
    public static void park()
    {
        throw intrinsic();
    }
    /** {@code LockSupport.unpark(t)} -> VM.unpark: make a permit available for {@code t} and wake it if parked. */
    public static void unpark(Object t)
    {
        throw intrinsic();
    }

    /** Allocate a fresh counting semaphore initialised to {@code initial}; returns its index. */
    public static int newSem(int initial)
    {
        throw intrinsic();
    }

    /** Emit a formatted philosopher status line (formatting stays image-side; no String concat on metal). */
    public static void report(int who, int state)
    {
        throw intrinsic();
    }

    /** Print a mini {@code java/lang/String}'s bytes to the UART (its {@code value} byte[] at offset 16). */
    public static void printStr(Object s)
    {
        throw intrinsic();
    }

    /** {@code MSR VBAR_EL1, v} — install the EL1 exception vector table base (2 KiB-aligned). */
    public static void writeVBAR_EL1(long v)
    {
        throw intrinsic();
    }
    /** {@code MRS ESR_EL1} — the syndrome of the current exception (EC in bits 31:26). */
    public static long readESR_EL1()
    {
        throw intrinsic();
    }
    /** {@code MRS ELR_EL1} — the PC the exception was taken from. */
    public static long readELR_EL1()
    {
        throw intrinsic();
    }
    /** {@code MRS FAR_EL1} — the faulting virtual address (for aborts). */
    public static long readFAR_EL1()
    {
        throw intrinsic();
    }
    /** {@code MRS CNTFRQ_EL0} — the generic-timer tick frequency in Hz. */
    public static long readCNTFRQ_EL0()
    {
        throw intrinsic();
    }
    /** {@code MRS CNTPCT_EL0} — the free-running physical counter. */
    public static long readCNTPCT_EL0()
    {
        throw intrinsic();
    }
    /** {@code MSR CNTP_TVAL_EL0, v} — arm the EL1 physical timer to fire in {@code v} ticks. */
    public static void writeCNTP_TVAL_EL0(long v)
    {
        throw intrinsic();
    }
    /** {@code MSR CNTP_CTL_EL0, v} — control the EL1 physical timer (bit0 enable, bit1 imask). */
    public static void writeCNTP_CTL_EL0(long v)
    {
        throw intrinsic();
    }
    /** {@code MSR DAIFClr, #2} — unmask IRQs at the current EL. */
    public static void enableIrq()
    {
        throw intrinsic();
    }
    /** {@code MRS DAIF} -- the current interrupt-mask state (bit7 = I). */
    public static long readDaif()
    {
        throw intrinsic();
    }
    /** {@code MSR DAIFSet, #3} -- mask IRQ and FIQ. */
    public static void disableIrq()
    {
        throw intrinsic();
    }
    /** {@code MRS CNTP_CTL_EL0} -- physical-timer control (bit0 enable, bit1 imask, bit2 istatus). */
    public static long readCNTP_CTL_EL0()
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
    /** Read x30 (link register) = the caller's return address. Must be the FIRST body op of the callee (before
     *  any nested call clobbers x30) to be meaningful. Used by the denylist trap to identify its call site. */
    public static long readLR()
    {
        throw intrinsic();
    }
    /** Read x0 = the faulting call's receiver (for a wild-branch via blr). Must be the FIRST body op of a fault
     *  handler entered via B (not BL), before x0 is clobbered. */
    public static long readX0()
    {
        throw intrinsic();
    }

    /**
     * Resume execution at a handler: set SP, place {@code exc} in the handler's
     * operand-stack slot (x9), and branch to {@code pc}. Never returns — used by
     * the exception unwinder to transfer control to a catch block in a caller.
     * {@code locBuf} points at a 16-slot buffer holding the handler's reconstructed
     * callee-saved locals (x19..); the first {@code regLocals} are reloaded from it.
     */
    public static void resume(long pc, long sp, long exc, long regLocals, long locBuf)
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
     * Reinterpret an object reference as its raw heap address. In joe-ng a reference IS the
     * object's address (no handles, no compressed oops), so this lowers to nothing — it only
     * lets Java source (e.g. the {@code jdk.internal.misc.Unsafe} substitute) name the pointer
     * as a {@code long} for {@code load*}/{@code store*} at a field/element offset.
     */
    public static long addrOf(Object o)
    {
        throw intrinsic();
    }

    /**
     * Reinterpret a raw heap address as an object reference — the inverse of {@link #addrOf}. Since a reference
     * IS the object's address in joe-ng, this lowers to nothing; it lets Java source (e.g. reflective
     * {@code Field.get} of a reference field) turn a {@code load64}'d field slot back into an {@code Object}.
     */
    public static Object fromAddr(long a)
    {
        throw intrinsic();
    }

    /**
     * Type adapter for string literals: a {@code String} constant is interned by
     * the compiler as a real heap-layout {@code byte[]} object in the image (ASCII
     * bytes), so {@code ldc "..."} already yields a {@code byte[]} reference. This
     * call is lowered to nothing — it only lets Java source name the bytes without
     * a {@code java.lang.String} class (which joe-ng does not yet have).
     */
    public static byte[] bytes(String literal)
    {
        throw intrinsic();
    }
}
