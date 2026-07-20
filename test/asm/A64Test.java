package asm;

import harness.T;

import java.util.List;

/**
 * Bit-for-bit encoding tests against known-good A64 words from the ARM
 * Architecture Reference Manual (ARMv8-A), via the dependency-free {@link T}
 * harness. A single wrong bit here corrupts memory or hangs the board invisibly
 * (PLAN.md §6), so every encoding the writer/compiler relies on must appear below.
 *
 * Run: {@code java asm.A64Test}
 */
public final class A64Test
{

    public static void main(String[] args)
    {
        // ---- hints (C6.2, HINT space) --------------------------------------
        T.eqWord("NOP",  0xD503201F, A64.nop());
        T.eqWord("YIELD",0xD503203F, A64.yield());
        T.eqWord("WFE",  0xD503205F, A64.wfe());
        T.eqWord("WFI",  0xD503207F, A64.wfi());
        T.eqWord("SEV",  0xD503209F, A64.sev());
        T.eqWord("SEVL", 0xD50320BF, A64.sevl());

        // ---- unconditional branch (immediate) ------------------------------
        T.eqWord("B .",     0x14000000, A64.b(0));      // branch to self
        T.eqWord("B .-4",   0x17FFFFFF, A64.b(-4));     // spin: back one instruction
        T.eqWord("B .+4",   0x14000001, A64.b(4));
        T.eqWord("B .+256", 0x14000040, A64.b(256));
        T.eqWord("BL .",    0x94000000, A64.bl(0));
        T.eqWord("BL .-4",  0x97FFFFFF, A64.bl(-4));

        // ---- branch/return (register) --------------------------------------
        T.eqWord("RET (x30)", 0xD65F03C0, A64.ret());
        T.eqWord("RET x0",    0xD65F0000, A64.ret(0));
        T.eqWord("BR x1",     0xD61F0020, A64.br(1));
        T.eqWord("BLR x16",   0xD63F0200, A64.blr(16));

        // ---- wide moves (64-bit) -------------------------------------------
        T.eqWord("MOVZ x0,#0",        0xD2800000, A64.movz(0, 0, 0));
        T.eqWord("MOVZ x0,#1",        0xD2800020, A64.movz(0, 1, 0));
        T.eqWord("MOVZ x0,#0xFFFF",   0xD29FFFE0, A64.movz(0, 0xFFFF, 0));
        T.eqWord("MOVZ x1,#1,LSL#16", 0xD2A00021, A64.movz(1, 1, 1));
        T.eqWord("MOVK x0,#1,LSL#48", 0xF2E00020, A64.movk(0, 1, 3));
        T.eqWord("MOVN x0,#0",        0x92800000, A64.movn(0, 0, 0));

        // ---- loadImm64 composition -----------------------------------------
        // 0x80000 = 0x0008_0000 -> MOVZ x0,#0x8,LSL#16 (single word).
        int[] ld = A64.loadImm64(0, 0x80000L);
        T.eq("loadImm64(0x80000).size==1", 1, ld.length);
        T.eqWord("loadImm64(0x80000)[0]", 0xD2A00100, ld[0]);
        // 0 -> single MOVZ #0.
        T.eq("loadImm64(0).size==1", 1, A64.loadImm64(3, 0).length);
        T.eqWord("loadImm64(0)[0]", 0xD2800003, A64.loadImm64(3, 0)[0]);
        // full 64-bit value touches all four lanes.
        T.eq("loadImm64(0x1122...).size==4", 4, A64.loadImm64(0, 0x1122_3344_5566_7788L).length);

        // ---- little-endian serialization -----------------------------------
        byte[] b = A64.wordsToLittleEndian(new int[] {0xD503205F, 0x17FFFFFF});
        T.eqBytes("spin loop bytes",
                  new byte[] {0x5F, 0x20, 0x03, (byte) 0xD5, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x17},
                  b);

        // ---- system register moves (MRS/MSR) -------------------------------
        T.eqWord("MRS x0,MPIDR_EL1", 0xD53800A0, A64.mrs(0, A64.MPIDR_EL1));
        T.eqWord("MRS x0,CurrentEL", 0xD5384200, A64.mrs(0, A64.CurrentEL));
        T.eqWord("MSR HCR_EL2,x0",   0xD51C1100, A64.msr(A64.HCR_EL2, 0));
        T.eqWord("MSR SCTLR_EL1,x0", 0xD5181000, A64.msr(A64.SCTLR_EL1, 0));
        T.eqWord("MSR CPACR_EL1,x0", 0xD5181040, A64.msr(A64.CPACR_EL1, 0));
        T.eqWord("ERET",             0xD69F03E0, A64.eret());

        // ---- barriers ------------------------------------------------------
        T.eqWord("DSB SY", 0xD5033F9F, A64.dsb());
        T.eqWord("DMB SY", 0xD5033FBF, A64.dmb());
        T.eqWord("ISB",    0xD5033FDF, A64.isb());

        // ---- load/store (unsigned offset) ----------------------------------
        T.eqWord("STR  w0,[x1]",   0xB9000020, A64.strw(0, 1, 0));
        T.eqWord("STR  w1,[x0]",   0xB9000001, A64.strw(1, 0, 0));
        T.eqWord("LDR  w0,[x1]",   0xB9400020, A64.ldrw(0, 1, 0));
        T.eqWord("LDRSW x0,[x1]",  0xB9800020, A64.ldrsw(0, 1, 0));
        T.eqWord("STR  x0,[x1]",   0xF9000020, A64.strx(0, 1, 0));
        T.eqWord("LDR  x0,[x1]",   0xF9400020, A64.ldrx(0, 1, 0));
        T.eqWord("STRB w0,[x1]",   0x39000020, A64.strb(0, 1, 0));
        T.eqWord("LDRB w0,[x1]",   0x39400020, A64.ldrb(0, 1, 0));
        T.eqWord("STRH w0,[x1]",   0x79000020, A64.strh(0, 1, 0));
        T.eqWord("LDRH w0,[x1]",   0x79400020, A64.ldrh(0, 1, 0));
        T.eqWord("LDRH w0,[x1,#2]", 0x79400420, A64.ldrh(0, 1, 2));  // imm12 scaled by 2
        T.throwsIAE("LDRH odd offset", () -> A64.ldrh(0, 1, 1));      // must be halfword-aligned
        T.eqWord("STR  w0,[x1,#4]",0xB9000420, A64.strw(0, 1, 4));   // imm12 scaled by 4

        // ---- add/sub immediate + moves -------------------------------------
        T.eqWord("ADD x0,x1,#0",  0x91000020, A64.addImm(0, 1, 0));
        T.eqWord("ADD x1,x1,#1",  0x91000421, A64.addImm(1, 1, 1));
        T.eqWord("SUB x2,x2,#1",  0xD1000442, A64.subImm(2, 2, 1));
        T.eqWord("MOV SP,x0",     0x9100001F, A64.movToSp(0));
        T.eqWord("MOV x0,x1",     0xAA0103E0, A64.movReg(0, 1));

        // ---- data-processing (shifted register) ----------------------------
        T.eqWord("ADD x0,x1,x2", 0x8B020020, A64.addReg(0, 1, 2));
        T.eqWord("ADD x0,x1,x2,LSL#2", 0x8B020820, A64.addRegLsl(0, 1, 2, 2));
        T.eqWord("SUB x0,x1,x2", 0xCB020020, A64.subReg(0, 1, 2));
        T.eqWord("AND x0,x1,x2", 0x8A020020, A64.andReg(0, 1, 2));
        T.eqWord("ORR x0,x1,x2", 0xAA020020, A64.orrReg(0, 1, 2));
        T.eqWord("EOR x0,x1,x2", 0xCA020020, A64.eorReg(0, 1, 2));
        T.eqWord("MUL x0,x1,x2", 0x9B027C20, A64.mulReg(0, 1, 2));
        T.eqWord("LSL x0,x1,x2", 0x9AC22020, A64.lslv(0, 1, 2));
        T.eqWord("LSR x0,x1,x2", 0x9AC22420, A64.lsrv(0, 1, 2));
        T.eqWord("ASR x0,x1,x2", 0x9AC22820, A64.asrv(0, 1, 2));
        T.eqWord("CMP x1,x2",    0xEB02003F, A64.cmpReg(1, 2));
        T.eqWord("CMP x0,#0",    0xF100001F, A64.cmpImm(0, 0));
        T.eqWord("SXTB x0,w0",   0x93401C00, A64.sxtb(0, 0));
        T.eqWord("SXTH x0,w0",   0x93403C00, A64.sxth(0, 0));
        T.eqWord("UXTH w0,w0",   0x53003C00, A64.uxth(0, 0));
        T.eqWord("CSET x0,EQ",   0x9A9F17E0, A64.cset(0, A64.EQ));
        T.eqWord("CSINV x0,x0,xzr,GE", 0xDA9FA000, A64.csinv(0, 0, 31, A64.GE));

        // ---- conditional / compare / test branches -------------------------
        T.eqWord("B.EQ .",     0x54000000, A64.bcond(A64.EQ, 0));
        T.eqWord("B.NE .+4",   0x54000021, A64.bcond(A64.NE, 4));
        T.eqWord("CBZ x2,.",   0xB4000002, A64.cbz(2, 0));
        T.eqWord("CBNZ x0,.",  0xB5000000, A64.cbnz(0, 0));
        T.eqWord("TBZ x0,#0,.",0x36000000, A64.tbz(0, 0, 0));
        T.eqWord("TBZ x3,#5,.",0x36280003, A64.tbz(3, 5, 0));
        T.eqWord("TBNZ x0,#40,.",0xB7400000, A64.tbnz(0, 40, 0));

        // ---- range checks throw --------------------------------------------
        T.throwsIAE("B unaligned",     () -> A64.b(3));
        T.throwsIAE("MOVZ bad hw",     () -> A64.movz(0, 0, 4));
        T.throwsIAE("MOVZ imm too big",() -> A64.movz(0, 0x10000, 0));
        T.throwsIAE("STR bad align",   () -> A64.strw(0, 1, 2));
        T.throwsIAE("ADD imm too big", () -> A64.addImm(0, 1, 0x1000));

        T.summary("A64");
    }
}
