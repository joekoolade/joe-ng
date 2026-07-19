package asm;

import java.util.List;

/**
 * Bit-for-bit encoding tests against known-good A64 words from the ARM
 * Architecture Reference Manual (ARMv8-A). No test framework — joe2 avoids host
 * dependencies — just a tiny assert harness. A single wrong bit here corrupts
 * memory or hangs the board invisibly (PLAN.md §6), so every encoding the
 * writer/compiler relies on must appear below.
 *
 * Run: {@code java asm.A64Test}
 */
public final class A64Test {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // ---- hints (C6.2, HINT space) --------------------------------------
        eq("NOP",  0xD503201F, A64.nop());
        eq("YIELD",0xD503203F, A64.yield());
        eq("WFE",  0xD503205F, A64.wfe());
        eq("WFI",  0xD503207F, A64.wfi());
        eq("SEV",  0xD503209F, A64.sev());
        eq("SEVL", 0xD50320BF, A64.sevl());

        // ---- unconditional branch (immediate) ------------------------------
        eq("B .",     0x14000000, A64.b(0));      // branch to self
        eq("B .-4",   0x17FFFFFF, A64.b(-4));     // spin: back one instruction
        eq("B .+4",   0x14000001, A64.b(4));
        eq("B .+256", 0x14000040, A64.b(256));
        eq("BL .",    0x94000000, A64.bl(0));
        eq("BL .-4",  0x97FFFFFF, A64.bl(-4));

        // ---- branch/return (register) --------------------------------------
        eq("RET (x30)", 0xD65F03C0, A64.ret());
        eq("RET x0",    0xD65F0000, A64.ret(0));
        eq("BR x1",     0xD61F0020, A64.br(1));

        // ---- wide moves (64-bit) -------------------------------------------
        eq("MOVZ x0,#0",        0xD2800000, A64.movz(0, 0, 0));
        eq("MOVZ x0,#1",        0xD2800020, A64.movz(0, 1, 0));
        eq("MOVZ x0,#0xFFFF",   0xD29FFFE0, A64.movz(0, 0xFFFF, 0));
        eq("MOVZ x1,#1,LSL#16", 0xD2A00021, A64.movz(1, 1, 1));
        eq("MOVK x0,#1,LSL#48", 0xF2E00020, A64.movk(0, 1, 3));
        eq("MOVN x0,#0",        0x92800000, A64.movn(0, 0, 0));

        // ---- loadImm64 composition -----------------------------------------
        // 0x80000 = 0x0008_0000 -> MOVZ x0,#0x8,LSL#16 (single word).
        List<Integer> ld = A64.loadImm64(0, 0x80000L);
        eq("loadImm64(0x80000).size==1", 1, ld.size());
        eq("loadImm64(0x80000)[0]", 0xD2A00100, ld.get(0));
        // 0 -> single MOVZ #0.
        eq("loadImm64(0).size==1", 1, A64.loadImm64(3, 0).size());
        eq("loadImm64(0)[0]", 0xD2800003, A64.loadImm64(3, 0).get(0));
        // full 64-bit value touches all four lanes.
        eq("loadImm64(0x1122...).size==4", 4, A64.loadImm64(0, 0x1122_3344_5566_7788L).size());

        // ---- little-endian serialization -----------------------------------
        byte[] b = A64.wordsToLittleEndian(new int[]{0xD503205F, 0x17FFFFFF});
        eqBytes("spin loop bytes",
                new byte[]{0x5F, 0x20, 0x03, (byte) 0xD5, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x17},
                b);

        // ---- system register moves (MRS/MSR) -------------------------------
        eq("MRS x0,MPIDR_EL1", 0xD53800A0, A64.mrs(0, A64.MPIDR_EL1));
        eq("MRS x0,CurrentEL", 0xD5384200, A64.mrs(0, A64.CurrentEL));
        eq("MSR HCR_EL2,x0",   0xD51C1100, A64.msr(A64.HCR_EL2, 0));
        eq("MSR SCTLR_EL1,x0", 0xD5181000, A64.msr(A64.SCTLR_EL1, 0));
        eq("MSR CPACR_EL1,x0", 0xD5181040, A64.msr(A64.CPACR_EL1, 0));
        eq("ERET",             0xD69F03E0, A64.eret());

        // ---- barriers ------------------------------------------------------
        eq("DSB SY", 0xD5033F9F, A64.dsb());
        eq("DMB SY", 0xD5033FBF, A64.dmb());
        eq("ISB",    0xD5033FDF, A64.isb());

        // ---- load/store (unsigned offset) ----------------------------------
        eq("STR  w0,[x1]",   0xB9000020, A64.strw(0, 1, 0));
        eq("STR  w1,[x0]",   0xB9000001, A64.strw(1, 0, 0));
        eq("LDR  w0,[x1]",   0xB9400020, A64.ldrw(0, 1, 0));
        eq("STR  x0,[x1]",   0xF9000020, A64.strx(0, 1, 0));
        eq("LDR  x0,[x1]",   0xF9400020, A64.ldrx(0, 1, 0));
        eq("STRB w0,[x1]",   0x39000020, A64.strb(0, 1, 0));
        eq("LDRB w0,[x1]",   0x39400020, A64.ldrb(0, 1, 0));
        eq("STR  w0,[x1,#4]",0xB9000420, A64.strw(0, 1, 4));   // imm12 scaled by 4

        // ---- add/sub immediate + moves -------------------------------------
        eq("ADD x0,x1,#0",  0x91000020, A64.addImm(0, 1, 0));
        eq("ADD x1,x1,#1",  0x91000421, A64.addImm(1, 1, 1));
        eq("SUB x2,x2,#1",  0xD1000442, A64.subImm(2, 2, 1));
        eq("MOV SP,x0",     0x9100001F, A64.movToSp(0));
        eq("MOV x0,x1",     0xAA0103E0, A64.movReg(0, 1));

        // ---- conditional / compare / test branches -------------------------
        eq("B.EQ .",     0x54000000, A64.bcond(A64.EQ, 0));
        eq("B.NE .+4",   0x54000021, A64.bcond(A64.NE, 4));
        eq("CBZ x2,.",   0xB4000002, A64.cbz(2, 0));
        eq("CBNZ x0,.",  0xB5000000, A64.cbnz(0, 0));
        eq("TBZ x0,#0,.",0x36000000, A64.tbz(0, 0, 0));
        eq("TBZ x3,#5,.",0x36280003, A64.tbz(3, 5, 0));
        eq("TBNZ x0,#40,.",0xB7400000, A64.tbnz(0, 40, 0));

        // ---- range checks throw --------------------------------------------
        throwsIAE("B unaligned",     () -> A64.b(3));
        throwsIAE("MOVZ bad hw",     () -> A64.movz(0, 0, 4));
        throwsIAE("MOVZ imm too big",() -> A64.movz(0, 0x10000, 0));
        throwsIAE("STR bad align",   () -> A64.strw(0, 1, 2));
        throwsIAE("ADD imm too big", () -> A64.addImm(0, 1, 0x1000));

        System.out.printf("%n%d checks, %d failures%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    private static void eq(String name, int expected, int actual) {
        checks++;
        if (expected != actual) {
            failures++;
            System.out.printf("FAIL %-24s expected 0x%08X got 0x%08X%n", name, expected, actual);
        }
    }

    private static void eq(String name, int expected, long actual) { eq(name, expected, (int) actual); }

    private static void eqBytes(String name, byte[] expected, byte[] actual) {
        checks++;
        if (!java.util.Arrays.equals(expected, actual)) {
            failures++;
            System.out.printf("FAIL %-24s expected %s got %s%n", name,
                    hex(expected), hex(actual));
        }
    }

    private static void throwsIAE(String name, Runnable r) {
        checks++;
        try {
            r.run();
            failures++;
            System.out.printf("FAIL %-24s expected IllegalArgumentException%n", name);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }
}
