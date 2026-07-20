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
}
