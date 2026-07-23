package asm;

/**
 * The A64 instruction encodings, as pure integer arithmetic — the half of the
 * assembler that can live in both worlds (M5).
 *
 * <p>{@link A64} is the writer-side assembler: it validates operands and throws
 * with a helpful message when an encoding would be malformed. That validation is
 * exactly what we want while building an image on the seed JVM, and exactly what
 * cannot be compiled into the image — the messages need String concatenation,
 * which lowers to {@code invokedynamic} and has no runtime on bare metal.
 *
 * <p>So the encodings live here, free of validation, JDK types and exceptions,
 * and {@code A64} keeps the checks and delegates the arithmetic. The on-metal
 * JIT calls this class directly, which means the machine code it emits comes from
 * encoders verified bit-for-bit against the ARM ARM (via A64's tests) rather than
 * from hand-written hex literals — the difference between a checked encoding and
 * a typo that corrupts memory invisibly.
 *
 * <p>Operand conventions: register numbers are raw (31 means SP or XZR depending
 * on the instruction), load/store offsets are in <em>bytes</em> and scaled
 * internally, and branch displacements are in <em>words</em> (A64 is fixed-width,
 * and the JIT computes them that way). Callers are trusted; range checking is
 * {@link A64}'s job.
 */
public final class A64Enc
{
    private A64Enc() {}

    // ----- register aliases + condition codes (ARM-defined) ----------------
    /** Zero register / SP slot, encoding 31. */
    public static final int XZR = 31;
    public static final int EQ = 0;
    public static final int NE = 1;
    public static final int HS = 2;
    public static final int LO = 3;
    public static final int MI = 4;
    public static final int PL = 5;
    public static final int VS = 6;
    public static final int VC = 7;
    public static final int HI = 8;
    public static final int LS = 9;
    public static final int GE = 10;
    public static final int LT = 11;
    public static final int GT = 12;
    public static final int LE = 13;
    public static final int AL = 14;

    // ----- wide immediate moves --------------------------------------------
    /** {@code MOVZ Xd, #imm16, LSL #(hw*16)}. */
    public static int movz(int rd, int imm16, int hw)
    {
        return 0xD280_0000 | (hw << 21) | ((imm16 & 0xFFFF) << 5) | rd;
    }
    /** {@code MOVK Xd, #imm16, LSL #(hw*16)} — insert, keeping the other lanes. */
    public static int movk(int rd, int imm16, int hw)
    {
        return 0xF280_0000 | (hw << 21) | ((imm16 & 0xFFFF) << 5) | rd;
    }
    /** {@code MOV Xd, Xm} — alias of ORR Xd, XZR, Xm. */
    public static int movReg(int rd, int rm)
    {
        return 0xAA00_03E0 | (rm << 16) | rd;
    }

    // ----- add/subtract ----------------------------------------------------
    /** {@code ADD Xd, Xn, #imm12} (register 31 = SP here). */
    public static int addImm(int rd, int rn, int imm12)
    {
        return 0x9100_0000 | ((imm12 & 0xFFF) << 10) | (rn << 5) | rd;
    }
    /** {@code SUB Xd, Xn, #imm12} (register 31 = SP here). */
    public static int subImm(int rd, int rn, int imm12)
    {
        return 0xD100_0000 | ((imm12 & 0xFFF) << 10) | (rn << 5) | rd;
    }
    /** {@code MOV SP, Xn} — via ADD SP, Xn, #0 (reg 31 = SP in add-immediate). */
    public static int movToSp(int rn)
    {
        return addImm(31, rn, 0);
    }
    /** {@code MOV Xd, SP} — via ADD Xd, SP, #0. */
    public static int movFromSp(int rd)
    {
        return addImm(rd, 31, 0);
    }
    /** {@code ADD Xd, Xn, Xm}. */
    public static int addReg(int rd, int rn, int rm)
    {
        return 0x8B00_0000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code SUB Xd, Xn, Xm}. */
    public static int subReg(int rd, int rn, int rm)
    {
        return 0xCB00_0000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code MUL Xd, Xn, Xm} — alias of MADD Xd, Xn, Xm, XZR. */
    public static int mulReg(int rd, int rn, int rm)
    {
        return 0x9B00_7C00 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code MSUB Xd, Xn, Xm, Xa} — {@code Xd = Xa - Xn*Xm} (used to synthesize irem). */
    public static int msub(int rd, int rn, int rm, int ra)
    {
        return 0x9B00_8000 | (rm << 16) | (ra << 10) | (rn << 5) | rd;
    }

    // ----- compare (flag-setting subtract into XZR) -------------------------
    /** {@code CMP Xn, Xm} — alias of SUBS XZR, Xn, Xm. */
    public static int cmpReg(int rn, int rm)
    {
        return 0xEB00_0000 | (rm << 16) | (rn << 5) | 31;
    }
    /** {@code CMP Xn, #imm12} — alias of SUBS XZR, Xn, #imm12. */
    public static int cmpImm(int rn, int imm12)
    {
        return 0xF100_0000 | ((imm12 & 0xFFF) << 10) | (rn << 5) | 31;
    }

    // ----- load/store (immediate, unsigned offset) --------------------------
    /** size = log2(access bytes); opc 0 = store, 1 = zero-extending load. */
    static int ldst(int size, int opc, int rt, int rn, int byteOffset)
    {
        int base = (size << 30) | (0b111 << 27) | (0b01 << 24) | (opc << 22);
        return base | ((byteOffset >> size) << 10) | (rn << 5) | rt;
    }
    /** {@code STR Xt, [Xn, #off]} — 64-bit. */
    public static int strx(int rt, int rn, int off)
    {
        return ldst(3, 0, rt, rn, off);
    }
    /** {@code LDR Xt, [Xn, #off]} — 64-bit. */
    public static int ldrx(int rt, int rn, int off)
    {
        return ldst(3, 1, rt, rn, off);
    }
    /** {@code STR Wt, [Xn, #off]} — 32-bit. */
    public static int strw(int rt, int rn, int off)
    {
        return ldst(2, 0, rt, rn, off);
    }
    /** {@code LDRSW Xt, [Xn, #off]} — 32-bit, sign-extended to 64. */
    public static int ldrsw(int rt, int rn, int off)
    {
        return 0xB980_0000 | ((off >> 2) << 10) | (rn << 5) | rt;
    }
    /** {@code STRH Wt, [Xn, #off]} — store halfword (char/short elements). */
    public static int strh(int rt, int rn, int off)
    {
        return ldst(1, 0, rt, rn, off);
    }
    /** {@code LDRH Wt, [Xn, #off]} — load halfword, zero-extended (char is unsigned). */
    public static int ldrh(int rt, int rn, int off)
    {
        return ldst(1, 1, rt, rn, off);
    }

    // ----- branches (displacements in words) --------------------------------
    /** {@code B #wordOffset}. */
    public static int b(int wordOffset)
    {
        return 0x1400_0000 | (wordOffset & 0x03FF_FFFF);
    }
    /** {@code BL #wordOffset} — branch with link. */
    public static int bl(int wordOffset)
    {
        return 0x9400_0000 | (wordOffset & 0x03FF_FFFF);
    }
    /** {@code BLR Xn} — branch-with-link to a register (virtual dispatch). */
    public static int blr(int rn)
    {
        return 0xD63F_0000 | (rn << 5);
    }
    /** {@code RET} — return to LR (x30). */
    public static int ret()
    {
        return 0xD65F_0000 | (30 << 5);
    }
    /** {@code CBZ Xt, #wordOffset} — branch if the register is zero. */
    public static int cbz(int rt, int wordOffset)
    {
        return 0xB400_0000 | ((wordOffset & 0x7FFFF) << 5) | rt;
    }
    /** {@code B.cond #wordOffset}. */
    public static int bcond(int cond, int wordOffset)
    {
        return 0x5400_0000 | ((wordOffset & 0x7FFFF) << 5) | (cond & 0xF);
    }
    /** {@code CBNZ Xt, #wordOffset} — branch if the register is non-zero. */
    public static int cbnz(int rt, int wordOffset)
    {
        return 0xB500_0000 | ((wordOffset & 0x7FFFF) << 5) | rt;
    }
    /** Test-and-branch encoding shared by TBZ/TBNZ; displacement in words (imm14). */
    static int tbit(int base, int rt, int bit, int wordOffset)
    {
        return base | ((bit & 0x20) << 26) | ((bit & 0x1F) << 19)
             | ((wordOffset & 0x3FFF) << 5) | rt;
    }
    /** {@code TBZ Xt, #bit, #wordOffset} — branch if the bit is clear. */
    public static int tbz(int rt, int bit, int wordOffset)
    {
        return tbit(0x3600_0000, rt, bit, wordOffset);
    }
    /** {@code TBNZ Xt, #bit, #wordOffset} — branch if the bit is set. */
    public static int tbnz(int rt, int bit, int wordOffset)
    {
        return tbit(0x3700_0000, rt, bit, wordOffset);
    }
    /** {@code BR Xn} — branch to address in register. */
    public static int br(int rn)
    {
        return 0xD61F_0000 | (rn << 5);
    }
    /** {@code RET Xn} — return via a register. */
    public static int ret(int rn)
    {
        return 0xD65F_0000 | (rn << 5);
    }

    // ----- data-processing (shifted register), 64-bit ----------------------
    /** {@code ADD Xd, Xn, Xm, LSL #shift} — array element addressing (base + index<<scale). */
    public static int addRegLsl(int rd, int rn, int rm, int shift)
    {
        return 0x8B00_0000 | (rm << 16) | (shift << 10) | (rn << 5) | rd;
    }
    /** {@code AND Xd, Xn, Xm}. */
    public static int andReg(int rd, int rn, int rm)
    {
        return 0x8A00_0000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code ORR Xd, Xn, Xm}. */
    public static int orrReg(int rd, int rn, int rm)
    {
        return 0xAA00_0000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code EOR Xd, Xn, Xm}. */
    public static int eorReg(int rd, int rn, int rm)
    {
        return 0xCA00_0000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code SDIV Xd, Xn, Xm} — signed divide (÷0 yields 0, per the ARM ARM). */
    public static int sdivReg(int rd, int rn, int rm)
    {
        return 0x9AC0_0C00 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code LSL Xd, Xn, Xm} — logical shift left by a register (LSLV). */
    public static int lslv(int rd, int rn, int rm)
    {
        return 0x9AC0_2000 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code LSR Xd, Xn, Xm} — logical shift right by a register (LSRV). */
    public static int lsrv(int rd, int rn, int rm)
    {
        return 0x9AC0_2400 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code ASR Xd, Xn, Xm} — arithmetic shift right by a register (ASRV). */
    public static int asrv(int rd, int rn, int rm)
    {
        return 0x9AC0_2800 | (rm << 16) | (rn << 5) | rd;
    }
    /** {@code SXTB Xd, Wn} — sign-extend byte (i2b). */
    public static int sxtb(int rd, int rn)
    {
        return 0x9340_1C00 | (rn << 5) | rd;
    }
    /** {@code SXTH Xd, Wn} — sign-extend halfword (i2s). */
    public static int sxth(int rd, int rn)
    {
        return 0x9340_3C00 | (rn << 5) | rd;
    }
    /** {@code SXTW Xd, Wn} — sign-extend word 32→64 (i2l). */
    public static int sxtw(int rd, int rn)
    {
        return 0x9340_7C00 | (rn << 5) | rd;
    }
    /** {@code UXTH Wd, Wn} — zero-extend halfword (i2c). */
    public static int uxth(int rd, int rn)
    {
        return 0x5300_3C00 | (rn << 5) | rd;
    }
    /** {@code CSET Xd, cond} — Xd = 1 if cond else 0 (alias of CSINC Xd, XZR, XZR, !cond). */
    public static int cset(int rd, int cond)
    {
        return 0x9A80_0400 | (31 << 16) | ((cond ^ 1) << 12) | (31 << 5) | rd;
    }
    /** {@code CSINV Xd, Xn, Xm, cond} — Xd = cond ? Xn : ~Xm. */
    public static int csinv(int rd, int rn, int rm, int cond)
    {
        return 0xDA80_0000 | (rm << 16) | (cond << 12) | (rn << 5) | rd;
    }

    // ----- load/store (more sizes) -----------------------------------------
    /** {@code LDR Wt, [Xn, #off]} — 32-bit, zero-extended. */
    public static int ldrw(int rt, int rn, int off)
    {
        return ldst(2, 1, rt, rn, off);
    }
    /** {@code STRB Wt, [Xn, #off]} — store byte. */
    public static int strb(int rt, int rn, int off)
    {
        return ldst(0, 0, rt, rn, off);
    }
    /** {@code LDRB Wt, [Xn, #off]} — load byte, zero-extended. */
    public static int ldrb(int rt, int rn, int off)
    {
        return ldst(0, 1, rt, rn, off);
    }

    // ----- barriers / hints / misc -----------------------------------------
    /** {@code DSB SY} — data synchronization barrier. */
    public static int dsb()
    {
        return 0xD503_3F9F;
    }
    /** {@code ISB} — instruction synchronization barrier. */
    public static int isb()
    {
        return 0xD503_3FDF;
    }
    /** {@code WFE} — wait for event. */
    public static int wfe()
    {
        return 0xD503_205F;
    }
    /** {@code DC CVAU, Xt} — clean data cache line by VA to the point of unification. */
    public static int dcCvau(int rt)
    {
        return 0xD50B_7B20 | (rt & 0x1F);
    }
    /** {@code IC IALLU} — invalidate all instruction cache to the point of unification. */
    public static int icIallu()
    {
        return 0xD508_751F;
    }
    /** {@code ERET} — exception return. */
    public static int eret()
    {
        return 0xD69F_03E0;
    }

    /** Round up to a 16-byte boundary (AArch64 stack-pointer alignment). */
    public static int align16(int n)
    {
        return (n + 15) & ~15;
    }

    /** Little-endian byte serialization of an instruction-word stream (A64 is LE). */
    public static byte[] wordsToLittleEndian(int[] words)
    {
        byte[] b = new byte[words.length * 4];
        int i = 0;
        while (i < words.length)
        {
            int w = words[i];
            b[i * 4] = (byte) w;
            b[i * 4 + 1] = (byte) (w >>> 8);
            b[i * 4 + 2] = (byte) (w >>> 16);
            b[i * 4 + 3] = (byte) (w >>> 24);
            i += 1;
        }
        return b;
    }

    // ----- system-register moves (MRS/MSR) ---------------------------------
    // A system register is (op0,op1,CRn,CRm,op2); op0 is 2/3 and only its low bit
    // (o0) is encoded. sysReg packs the fixed field so msr/mrs just add L and Rt.
    // (A64 exposes these as a Sys record, whose synthesised equals/hashCode lower to
    // invokedynamic — unavailable on metal — so the shared path uses these ints.)
    public static int sysReg(int op0, int op1, int crn, int crm, int op2)
    {
        return ((op0 & 1) << 19) | ((op1 & 7) << 16) | ((crn & 0xF) << 12)
             | ((crm & 0xF) << 8) | ((op2 & 7) << 5);
    }
    /** {@code MSR sysreg, Xt} — write a general register into a system register. */
    public static int msr(int sysReg, int rt)
    {
        return 0xD510_0000 | sysReg | rt;
    }
    /** {@code MRS Xt, sysreg} — read a system register into a general register. */
    public static int mrs(int rt, int sysReg)
    {
        return 0xD510_0000 | (1 << 21) | sysReg | rt;
    }

    // Boot-path system registers (PLAN.md §5.1), packed for msr/mrs.
    public static final int CurrentEL   = sysReg(3, 0,  4, 2, 0);
    public static final int HCR_EL2     = sysReg(3, 4,  1, 1, 0);
    public static final int CPTR_EL2    = sysReg(3, 4,  1, 1, 2);
    public static final int CNTHCTL_EL2 = sysReg(3, 4, 14, 1, 0);
    public static final int CNTVOFF_EL2 = sysReg(3, 4, 14, 0, 3);
    public static final int SCTLR_EL1   = sysReg(3, 0,  1, 0, 0);
    public static final int CPACR_EL1   = sysReg(3, 0,  1, 0, 2);
    public static final int SPSR_EL2    = sysReg(3, 4,  4, 0, 0);
    public static final int ELR_EL2     = sysReg(3, 4,  4, 0, 1);
    public static final int VBAR_EL1    = sysReg(3, 0, 12, 0, 0);   // exception vector base
    public static final int ESR_EL1     = sysReg(3, 0,  5, 2, 0);   // exception syndrome
    public static final int ELR_EL1     = sysReg(3, 0,  4, 0, 1);   // faulting PC
    public static final int FAR_EL1     = sysReg(3, 0,  6, 0, 0);   // faulting address

    /** Compose a 64-bit immediate into up to four MOVZ/MOVK words in x{@code rd}. */
    public static int[] loadImm64(int rd, long value)
    {
        int[] tmp = new int[4];
        int n = 0;
        for (int hw = 0; hw < 4; hw++)
        {
            int lane = (int) ((value >>> (hw * 16)) & 0xFFFF);
            if (lane == 0 && n > 0)
            {
                continue;
            }
            if (lane == 0 && hw != 3 && value != 0)
            {
                continue;
            }
            tmp[n] = n == 0 ? movz(rd, lane, hw) : movk(rd, lane, hw);
            n++;
        }
        if (n == 0)
        {
            tmp[n] = movz(rd, 0, 0);
            n++;
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = tmp[i];
        }
        return out;
    }
}
