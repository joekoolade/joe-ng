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
