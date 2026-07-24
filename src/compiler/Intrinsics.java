package compiler;

/**
 * Integer ids for the {@code magic/Magic} intrinsics, shared between the two
 * halves of intrinsic handling: symbol resolution (name+descriptor &rarr; id,
 * done per world behind {@link Symbols#intrinsicId}) and the compiler's dispatch
 * (id &rarr; A64, in {@code BaselineCompiler.lowerIntrinsic}).
 *
 * <p>The point of the id is to keep the dispatch off {@code String} keys: the
 * writer can afford a {@code String} switch, but the metal has neither a
 * {@code String} switch nor {@code ldc}-string, so the shared side must branch on
 * a plain {@code int} (M5.4.4 b.4).
 */
public final class Intrinsics
{
    private Intrinsics() {}

    public static final int WFE = 0;
    public static final int ISB = 1;
    public static final int DSB = 2;
    public static final int GC = 3;
    public static final int CALL0 = 4;
    public static final int CALL2 = 5;
    public static final int ERET = 6;
    public static final int DROP_TO_EL1 = 7;
    public static final int WRITE_HCR_EL2 = 8;
    public static final int WRITE_CPTR_EL2 = 9;
    public static final int WRITE_CNTHCTL_EL2 = 10;
    public static final int WRITE_CNTVOFF_EL2 = 11;
    public static final int WRITE_SCTLR_EL1 = 12;
    public static final int WRITE_SPSR_EL2 = 13;
    public static final int WRITE_ELR_EL2 = 14;
    public static final int WRITE_CPACR_EL1 = 15;
    public static final int WRITE_SP = 16;
    public static final int READ_SP = 17;
    public static final int RESUME = 18;
    public static final int STORE32 = 19;
    public static final int STORE8 = 20;
    public static final int STORE64 = 21;
    public static final int LOAD32 = 22;
    public static final int LOAD8 = 23;
    public static final int LOAD64 = 24;
    public static final int BYTES = 25;
    public static final int DC_CVAU = 26;   // clean D-cache line to PoU (JIT publish)
    public static final int IC_IALLU = 27;  // invalidate all I-cache to PoU (JIT publish)
    public static final int WRITE_VBAR_EL1 = 28;  // install EL1 exception vectors
    public static final int READ_ESR_EL1 = 29;    // exception syndrome / PC / fault address
    public static final int READ_ELR_EL1 = 30;
    public static final int READ_FAR_EL1 = 31;
    public static final int READ_CURRENT_EL = 32;   // which exception level we are running at
    public static final int READ_CNTFRQ_EL0 = 33;   // generic-timer frequency
    public static final int READ_CNTPCT_EL0 = 34;   // generic-timer physical count
    public static final int WRITE_CNTP_TVAL_EL0 = 35; // arm the EL1 physical timer countdown
    public static final int WRITE_CNTP_CTL_EL0 = 36;  // enable/mask the EL1 physical timer
    public static final int ENABLE_IRQ = 37;        // MSR DAIFClr, #2 (unmask IRQ)
}
