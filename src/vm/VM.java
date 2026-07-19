package vm;

import magic.Magic;

/**
 * The runtime entry points, written as ordinary Java and compiled to A64 by our
 * own baseline compiler (the metacircular point — PLAN.md §1). The seed JVM
 * never runs these on metal; the writer parses this class's bytecode and
 * compiles it into {@code kernel8.img}.
 *
 * Methods are added here as the baseline compiler's bytecode coverage grows,
 * milestone by milestone (CLAUDE.md working agreements).
 */
public final class VM {
    private VM() {}

    /**
     * Park loop — the first method compiled from real Java bytecode by our own
     * compiler (M1c step 1). Compiles to {@code wfe; b .-4}, identical to the M0
     * hand-emitted spin image.
     */
    public static void spin() {
        while (true) {
            Magic.wfe();
        }
    }

    /**
     * M1c: the full first-light boot, compiled from this Java by our own baseline
     * compiler (the metacircular goal). Equivalent to the hand-emitted
     * {@code vm.EmitBoot}: drop EL2→EL1, enable FP, set a stack, bring up the AUX
     * mini-UART, print the boot message, then park.
     */
    public static void boot() {
        Magic.dropToEL1();
        Magic.writeCPACR_EL1(0x300000L);   // CPACR_EL1.FPEN = 0b11 (no FP trap)
        Magic.isb();
        Magic.writeSP(0x80000L);           // stack below the image

        // mini-UART (AUX / UART1) bring-up — constant MMIO stores
        Magic.store32(0xFE215004L, 1);      // AUX_ENABLES: enable mini-UART
        Magic.store32(0xFE215060L, 0);      // CNTL: tx/rx off during config
        Magic.store32(0xFE215044L, 0);      // IER
        Magic.store32(0xFE21504CL, 3);      // LCR: 8-bit
        Magic.store32(0xFE215050L, 0);      // MCR
        Magic.store32(0xFE215048L, 0xC6);   // IIR: clear FIFOs
        Magic.store32(0xFE215068L, 541);    // BAUD: ~115200
        Magic.store32(0xFE200004L, 0x12000);// GPFSEL1: GPIO14/15 -> ALT5
        Magic.store32(0xFE2000E4L, 0);      // pull none on GPIO0..15
        Magic.store32(0xFE215060L, 3);      // CNTL: tx + rx on

        // print the message, polling the TX FIFO (LSR bit5)
        long p = Magic.message();
        int n = Magic.messageLen();
        while (n != 0) {
            int c = Magic.load8(p);
            while ((Magic.load32(0xFE215054L) & 0x20) == 0) {
            }
            Magic.store8(0xFE215040L, c);
            p = p + 1L;
            n = n - 1;
        }

        while (true) {
            Magic.wfe();
        }
    }
}
