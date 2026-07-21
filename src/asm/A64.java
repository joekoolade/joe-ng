package asm;


/**
 * AArch64 (A64) instruction encoder — emits raw 32-bit instruction words, not
 * assembly text. There is no external assembler in joe-ng; this is it.
 *
 * Every encoding here is verified bit-for-bit against the ARM Architecture
 * Reference Manual (ARMv8-A, A64 instruction set) in {@code asm.A64Test}. A
 * mis-lowered instruction corrupts memory or hangs the board invisibly, so no
 * encoding is used until its test passes (see PLAN.md §6, SOURCES.md).
 *
 * <p>A64 is a fixed-width 32-bit ISA; the image is little-endian, so word
 * {@code 0xAABBCCDD} is stored as bytes {@code DD CC BB AA} (see
 * {@link #wordsToLittleEndian}).
 *
 * <p>This class is used in two contexts (the metacircular point): on the seed
 * JVM inside the boot-image writer, and later compiled into the image as part
 * of the runtime JIT. Same source, two homes — so it stays plain Java with no
 * host dependencies.
 */
public final class A64
{

    // ----- register aliases -------------------------------------------------
    /** Link register. */
    public static final int LR = 30;
    /** Zero register / stack pointer slot (context-dependent), encoding 31. */
    public static final int XZR = 31;

    private A64() {}

    // =======================================================================
    // Hint instructions (NOP / WFE / WFI / SEV ...)
    //
    // C6.2 — encoded as the "HINT" space: 1101 0101 0000 0011 0010 CRm op2 1 1111
    // Base 0xD503_201F, with (CRm:op2) selecting the hint. NOP = CRm=0,op2=0.
    // =======================================================================
    private static final int HINT_BASE = 0xD503_201F;

    private static int hint(int crmOp2)
    {
        // crmOp2 occupies bits [11:5] (CRm at [11:8], op2 at [7:5]).
        return HINT_BASE | ((crmOp2 & 0x7F) << 5);
    }

    /** {@code NOP} — no operation. */
    public static int nop()
    {
        return hint(0b0000_000);    // 0xD503201F
    }
    /** {@code YIELD}. */
    public static int yield()
    {
        return hint(0b0000_001);    // 0xD503203F
    }
    /** {@code WFE} — wait for event (used to park secondary cores). */
    public static int wfe()
    {
        return A64Enc.wfe();        // 0xD503205F
    }
    /** {@code WFI} — wait for interrupt. */
    public static int wfi()
    {
        return hint(0b0000_011);    // 0xD503207F
    }
    /** {@code SEV} — send event (wake parked cores). */
    public static int sev()
    {
        return hint(0b0000_100);    // 0xD503209F
    }
    /** {@code SEVL} — send event local. */
    public static int sevl()
    {
        return hint(0b0000_101);    // 0xD50320BF
    }

    // =======================================================================
    // Unconditional branch (immediate) — C6.2.26
    //   0 00101 imm26      B     (base 0x1400_0000)
    //   1 00101 imm26      BL    (base 0x9400_0000)
    // imm26 = (byteOffset >> 2), PC-relative to THIS instruction, signed.
    // =======================================================================
    private static final int B_BASE  = 0x1400_0000;
    private static final int BL_BASE = 0x9400_0000;

    /**
     * {@code B #byteOffset} — PC-relative unconditional branch. {@code byteOffset}
     * is measured from this instruction and must be a multiple of 4 within
     * ±128 MiB. {@code b(0)} is a branch-to-self spin.
     */
    public static int b(int byteOffset)
    {
        return B_BASE  | imm26(byteOffset);
    }

    /** {@code BL #byteOffset} — branch with link (return address in LR). */
    public static int bl(int byteOffset)
    {
        return BL_BASE | imm26(byteOffset);
    }

    private static int imm26(int byteOffset)
    {
        if ((byteOffset & 0b11) != 0)
        {
            throw new IllegalArgumentException("branch offset not 4-byte aligned: " + byteOffset);
        }
        int imm = byteOffset >> 2;
        if (imm < -(1 << 25) || imm >= (1 << 25))
        {
            throw new IllegalArgumentException("branch offset out of ±128MiB range: " + byteOffset);
        }
        return imm & 0x03FF_FFFF;
    }

    // =======================================================================
    // Unconditional branch (register) — C6.2.36 / C6.2.37
    //   BR  Xn : 1101011 0 0 00 11111 0000 00 Rn 00000   (base 0xD61F_0000)
    //   RET Xn : 1101011 0 0 10 11111 0000 00 Rn 00000   (base 0xD65F_0000)
    // =======================================================================
    /** {@code BR Xn} — branch to address in register. */
    public static int br(int rn)
    {
        return A64Enc.br(reg(rn));
    }
    /** {@code BLR Xn} — branch-with-link to address in register (virtual dispatch). */
    public static int blr(int rn)
    {
        return A64Enc.blr(reg(rn));
    }
    /** {@code RET Xn} — return; RET with no operand uses LR (x30). */
    public static int ret(int rn)
    {
        return A64Enc.ret(reg(rn));
    }
    /** {@code RET} — return to LR (x30). */
    public static int ret()
    {
        return ret(LR);
    }

    // =======================================================================
    // Wide immediate moves — C6.2.191/192/190
    //   MOVN : sf 00 100101 hw imm16 Rd  (64-bit base 0x9280_0000)
    //   MOVZ : sf 10 100101 hw imm16 Rd  (64-bit base 0xD280_0000)
    //   MOVK : sf 11 100101 hw imm16 Rd  (64-bit base 0xF280_0000)
    // hw selects the 16-bit lane: shift = hw*16 (hw in 0..3 for 64-bit).
    // =======================================================================
    private static final int MOVN_BASE = 0x9280_0000;
    private static final int MOVZ_BASE = 0xD280_0000;
    private static final int MOVK_BASE = 0xF280_0000;

    /** {@code MOVZ Xd, #imm16, LSL #(hw*16)} — move zero-extended 16-bit immediate. */
    public static int movz(int rd, int imm16, int hw)
    {
        return MOVZ_BASE | movImm(rd, imm16, hw);
    }
    /** {@code MOVK Xd, #imm16, LSL #(hw*16)} — keep other bits, insert 16-bit field. */
    public static int movk(int rd, int imm16, int hw)
    {
        return MOVK_BASE | movImm(rd, imm16, hw);
    }
    /** {@code MOVN Xd, #imm16, LSL #(hw*16)} — move NOT of the 16-bit immediate. */
    public static int movn(int rd, int imm16, int hw)
    {
        return MOVN_BASE | movImm(rd, imm16, hw);
    }

    private static int movImm(int rd, int imm16, int hw)
    {
        if (hw < 0 || hw > 3)
        {
            throw new IllegalArgumentException("hw must be 0..3 (64-bit): " + hw);
        }
        if ((imm16 & ~0xFFFF) != 0)
        {
            throw new IllegalArgumentException("imm16 out of range: " + imm16);
        }
        return (hw << 21) | (imm16 << 5) | reg(rd);   // lanes; base added by caller
    }

    // =======================================================================
    // System register move (MRS/MSR register) — C5.2 / C6.2
    //   [31:21]=1101 0101 001, [20]=L (1=MRS read, 0=MSR write), [19]=o0,
    //   [18:16]=op1, [15:12]=CRn, [11:8]=CRm, [7:5]=op2, [4:0]=Rt.
    // A system register is (op0,op1,CRn,CRm,op2); op0 is 2 or 3, encoded as
    // o0 = op0 & 1. Verified against MPIDR_EL1 = 0xD53800A0 (A64Test).
    // =======================================================================

    /** System register identity as (op0,op1,CRn,CRm,op2). */
    public record Sys(int op0, int op1, int crn, int crm, int op2) {}

    // Boot-path system registers (PLAN.md §5.1). S<op0>_<op1>_C<n>_C<m>_<op2>.
    public static final Sys CurrentEL   = new Sys(3, 0,  4, 2, 0);
    public static final Sys MPIDR_EL1   = new Sys(3, 0,  0, 0, 5);
    public static final Sys HCR_EL2     = new Sys(3, 4,  1, 1, 0);
    public static final Sys CPTR_EL2    = new Sys(3, 4,  1, 1, 2);
    public static final Sys CNTHCTL_EL2 = new Sys(3, 4, 14, 1, 0);
    public static final Sys CNTVOFF_EL2 = new Sys(3, 4, 14, 0, 3);
    public static final Sys SCTLR_EL1   = new Sys(3, 0,  1, 0, 0);
    public static final Sys CPACR_EL1   = new Sys(3, 0,  1, 0, 2);
    public static final Sys SPSR_EL2    = new Sys(3, 4,  4, 0, 0);
    public static final Sys ELR_EL2     = new Sys(3, 4,  4, 0, 1);
    public static final Sys VBAR_EL1    = new Sys(3, 0, 12, 0, 0);

    private static int sysMove(boolean read, Sys s, int rt)
    {
        int packed = A64Enc.sysReg(s.op0(), s.op1(), s.crn(), s.crm(), s.op2());
        return read ? A64Enc.mrs(reg(rt), packed) : A64Enc.msr(packed, reg(rt));
    }

    /** {@code MRS Xt, sysreg} — read a system register into a general register. */
    public static int mrs(int rt, Sys s)
    {
        return sysMove(true,  s, rt);
    }
    /** {@code MSR sysreg, Xt} — write a general register into a system register. */
    public static int msr(Sys s, int rt)
    {
        return sysMove(false, s, rt);
    }

    /** {@code ERET} — exception return (the EL2→EL1 drop). */
    public static int eret()
    {
        return A64Enc.eret();
    }

    // =======================================================================
    // Barriers — C6.2. Base 0xD5033000; [11:8]=CRm(option), [7:5]=opc.
    // DSB opc=100, DMB opc=101, ISB opc=110. option SY=0b1111.
    // =======================================================================
    private static final int BARRIER = 0xD503_3000 | 0x1F; // Rt field = 11111
    /** Full-system option for barriers. */
    public static final int SY = 0b1111;
    /** {@code DSB} — data synchronization barrier (default SY). */
    public static int dsb(int option)
    {
        return BARRIER | (option << 8) | (0b100 << 5);
    }
    public static int dsb()
    {
        return dsb(SY);    // 0xD5033F9F
    }
    /** {@code DMB} — data memory barrier (MMIO ordering). */
    public static int dmb(int option)
    {
        return BARRIER | (option << 8) | (0b101 << 5);
    }
    public static int dmb()
    {
        return dmb(SY);    // 0xD5033FBF
    }
    /** {@code ISB} — instruction synchronization barrier. */
    public static int isb()
    {
        return A64Enc.isb();    // 0xD5033FDF
    }

    // =======================================================================
    // Load/store (immediate, unsigned offset) — C6. Base by size/opc:
    //   size: 0=byte 1=half 2=word 3=dword ; opc: 0=store 1=load(zero-ext).
    //   imm12 is the byte offset scaled down by the access size.
    // =======================================================================
    private static int ldst(int size, int opc, int rt, int rn, int byteOffset)
    {
        int scale = size;                         // log2(access bytes)
        if ((byteOffset & ((1 << scale) - 1)) != 0)
        {
            throw new IllegalArgumentException("offset not aligned to access size: " + byteOffset);
        }
        int imm12 = byteOffset >> scale;
        if (imm12 < 0 || imm12 > 0xFFF)
        {
            throw new IllegalArgumentException("ldst offset out of unsigned range: " + byteOffset);
        }
        // imm12 is already scaled; A64Enc rescales, so hand it the byte offset.
        return A64Enc.ldst(size, opc, reg(rt), reg(rn), imm12 << scale);
    }
    /** {@code STR Wt, [Xn, #off]} — store 32-bit. */
    public static int strw(int rt, int rn, int off)
    {
        return ldst(2, 0, rt, rn, off);
    }
    /** {@code LDR Wt, [Xn, #off]} — load 32-bit (zero-extended). */
    public static int ldrw(int rt, int rn, int off)
    {
        return ldst(2, 1, rt, rn, off);
    }
    /** {@code LDRSW Xt, [Xn, #off]} — load 32-bit sign-extended to 64 (for signed int arrays). */
    public static int ldrsw(int rt, int rn, int off)
    {
        if ((off & 3) != 0)
        {
            throw new IllegalArgumentException("offset not word-aligned: " + off);
        }
        return A64Enc.ldrsw(reg(rt), reg(rn), off);
    }
    /** {@code STR Xt, [Xn, #off]} — store 64-bit. */
    public static int strx(int rt, int rn, int off)
    {
        return ldst(3, 0, rt, rn, off);
    }
    /** {@code LDR Xt, [Xn, #off]} — load 64-bit. */
    public static int ldrx(int rt, int rn, int off)
    {
        return ldst(3, 1, rt, rn, off);
    }
    /** {@code STRB Wt, [Xn, #off]} — store byte. */
    public static int strb(int rt, int rn, int off)
    {
        return ldst(0, 0, rt, rn, off);
    }
    /** {@code LDRB Wt, [Xn, #off]} — load byte (zero-extended). */
    public static int ldrb(int rt, int rn, int off)
    {
        return ldst(0, 1, rt, rn, off);
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

    // =======================================================================
    // Add/subtract (immediate) — C6. 64-bit, no shift. Reg 31 = SP here.
    //   ADD base 0x91000000, SUB base 0xD1000000.
    // =======================================================================
    private static int addSubImm(int base, int rd, int rn, int imm12)
    {
        if (imm12 < 0 || imm12 > 0xFFF)
        {
            throw new IllegalArgumentException("add/sub imm12 out of range: " + imm12);
        }
        return base | (imm12 << 10) | (reg(rn) << 5) | reg(rd);
    }
    /** {@code ADD Xd, Xn, #imm12}. */
    public static int addImm(int rd, int rn, int imm12)
    {
        return addSubImm(0x9100_0000, rd, rn, imm12);
    }
    /** {@code SUB Xd, Xn, #imm12}. */
    public static int subImm(int rd, int rn, int imm12)
    {
        return addSubImm(0xD100_0000, rd, rn, imm12);
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

    /** {@code MOV Xd, Xm} — alias of ORR Xd, XZR, Xm. */
    public static int movReg(int rd, int rm)
    {
        return A64Enc.movReg(reg(rd), reg(rm));
    }

    // =======================================================================
    // Data-processing (shifted register), 64-bit, no shift — C6.
    // =======================================================================
    /** {@code ADD Xd, Xn, Xm}. */
    public static int addReg(int rd, int rn, int rm)
    {
        return addRegLsl(rd, rn, rm, 0);
    }
    /** {@code ADD Xd, Xn, Xm, LSL #shift} — for array element addressing (base + index<<scale). */
    public static int addRegLsl(int rd, int rn, int rm, int shift)
    {
        if (shift < 0 || shift > 63)
        {
            throw new IllegalArgumentException("bad shift: " + shift);
        }
        return A64Enc.addRegLsl(reg(rd), reg(rn), reg(rm), shift);
    }
    /** {@code SUB Xd, Xn, Xm}. */
    public static int subReg(int rd, int rn, int rm)
    {
        return A64Enc.subReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code AND Xd, Xn, Xm}. */
    public static int andReg(int rd, int rn, int rm)
    {
        return A64Enc.andReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code ORR Xd, Xn, Xm}. */
    public static int orrReg(int rd, int rn, int rm)
    {
        return A64Enc.orrReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code EOR Xd, Xn, Xm}. */
    public static int eorReg(int rd, int rn, int rm)
    {
        return A64Enc.eorReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code MUL Xd, Xn, Xm} — alias of MADD Xd, Xn, Xm, XZR. */
    public static int mulReg(int rd, int rn, int rm)
    {
        return A64Enc.mulReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code SDIV Xd, Xn, Xm} — signed divide (divide-by-zero yields 0, per the ARM ARM). */
    public static int sdivReg(int rd, int rn, int rm)
    {
        return A64Enc.sdivReg(reg(rd), reg(rn), reg(rm));
    }
    /** {@code LSL Xd, Xn, Xm} — logical shift left by a register (LSLV). */
    public static int lslv(int rd, int rn, int rm)
    {
        return A64Enc.lslv(reg(rd), reg(rn), reg(rm));
    }
    /** {@code LSR Xd, Xn, Xm} — logical shift right by a register (LSRV). */
    public static int lsrv(int rd, int rn, int rm)
    {
        return A64Enc.lsrv(reg(rd), reg(rn), reg(rm));
    }
    /** {@code ASR Xd, Xn, Xm} — arithmetic shift right by a register (ASRV). */
    public static int asrv(int rd, int rn, int rm)
    {
        return A64Enc.asrv(reg(rd), reg(rn), reg(rm));
    }
    /** {@code CMP Xn, Xm} — alias of SUBS XZR, Xn, Xm (sets flags). */
    public static int cmpReg(int rn, int rm)
    {
        return A64Enc.cmpReg(reg(rn), reg(rm));
    }
    /** {@code SXTB Xd, Wn} — sign-extend byte (i2b). */
    public static int sxtb(int rd, int rn)
    {
        return A64Enc.sxtb(reg(rd), reg(rn));
    }
    /** {@code SXTH Xd, Wn} — sign-extend halfword (i2s). */
    public static int sxth(int rd, int rn)
    {
        return A64Enc.sxth(reg(rd), reg(rn));
    }
    /** {@code UXTH Wd, Wn} — zero-extend halfword (i2c). */
    public static int uxth(int rd, int rn)
    {
        return A64Enc.uxth(reg(rd), reg(rn));
    }

    /** {@code CSET Xd, cond} — Xd = 1 if cond else 0 (alias of CSINC Xd, XZR, XZR, !cond). */
    public static int cset(int rd, int cond)
    {
        return A64Enc.cset(reg(rd), cond);
    }
    /** {@code CSINV Xd, Xn, Xm, cond} — Xd = cond ? Xn : ~Xm. */
    public static int csinv(int rd, int rn, int rm, int cond)
    {
        return A64Enc.csinv(reg(rd), reg(rn), reg(rm), cond);
    }
    /** {@code CMP Xn, #imm12} — alias of SUBS XZR, Xn, #imm12. */
    public static int cmpImm(int rn, int imm12)
    {
        if (imm12 < 0 || imm12 > 0xFFF)
        {
            throw new IllegalArgumentException("cmp imm12 out of range: " + imm12);
        }
        return A64Enc.cmpImm(reg(rn), imm12);
    }

    // =======================================================================
    // Conditional / compare-and-branch / test-and-branch — C6.2
    // =======================================================================
    /** Condition codes for {@link #bcond}. */
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

    /** {@code B.cond #byteOffset} — conditional branch. */
    public static int bcond(int cond, int byteOffset)
    {
        return A64Enc.bcond(cond, imm19(byteOffset));
    }
    /** {@code CBZ Xt, #byteOffset} — branch if register is zero (64-bit). */
    public static int cbz(int rt, int byteOffset)
    {
        return A64Enc.cbz(reg(rt), imm19(byteOffset));
    }
    /** {@code CBNZ Xt, #byteOffset} — branch if register is non-zero (64-bit). */
    public static int cbnz(int rt, int byteOffset)
    {
        return A64Enc.cbnz(reg(rt), imm19(byteOffset));
    }

    /** {@code TBZ Xt, #bit, #byteOffset} — branch if bit clear. */
    public static int tbz(int rt, int bit, int byteOffset)
    {
        return tbit(0x3600_0000, rt, bit, byteOffset);
    }
    /** {@code TBNZ Xt, #bit, #byteOffset} — branch if bit set. */
    public static int tbnz(int rt, int bit, int byteOffset)
    {
        return tbit(0x3700_0000, rt, bit, byteOffset);
    }

    private static int tbit(int base, int rt, int bit, int byteOffset)
    {
        if (bit < 0 || bit > 63)
        {
            throw new IllegalArgumentException("bad bit index: " + bit);
        }
        return A64Enc.tbit(base, reg(rt), bit, imm14(byteOffset));
    }

    private static int imm19(int byteOffset)
    {
        return immBranch(byteOffset, 19);
    }
    private static int imm14(int byteOffset)
    {
        return immBranch(byteOffset, 14);
    }

    private static int immBranch(int byteOffset, int bits)
    {
        if ((byteOffset & 0b11) != 0)
        {
            throw new IllegalArgumentException("branch offset not 4-byte aligned: " + byteOffset);
        }
        int imm = byteOffset >> 2;
        int lim = 1 << (bits - 1);
        if (imm < -lim || imm >= lim)
        {
            throw new IllegalArgumentException("branch offset out of range: " + byteOffset);
        }
        return imm & ((1 << bits) - 1);
    }

    /** Round up to a 16-byte boundary (AArch64 stack-pointer alignment). */
    public static int align16(int n)
    {
        return A64Enc.align16(n);
    }

    // ----- helpers ----------------------------------------------------------
    private static int reg(int r)
    {
        if (r < 0 || r > 31)
        {
            throw new IllegalArgumentException("bad register x" + r);
        }
        return r;
    }

    /** Convert a 64-bit immediate into up to four MOVZ/MOVK words in x{@code rd}. */
    public static int[] loadImm64(int rd, long value)
    {
        return A64Enc.loadImm64(reg(rd), value);
    }

    /** Little-endian byte serialization of an instruction-word stream (A64 = LE). */
    public static byte[] wordsToLittleEndian(int[] words)
    {
        byte[] b = new byte[words.length * 4];
        for (int i = 0; i < words.length; i++)
        {
            int w = words[i];
            b[i * 4]     = (byte) (w);
            b[i * 4 + 1] = (byte) (w >>> 8);
            b[i * 4 + 2] = (byte) (w >>> 16);
            b[i * 4 + 3] = (byte) (w >>> 24);
        }
        return b;
    }
}
