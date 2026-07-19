package board.bcm2711;

import magic.Magic;

/**
 * Mini-UART (AUX / UART1) console driver, written as ordinary Java and compiled
 * to A64 by our baseline compiler. Splitting the boot's UART logic into real
 * methods (called across classes from {@code vm.VM}) is the M2 exercise: genuine
 * {@code BL} calls with arguments and returns, laid out and relocated by the
 * writer (PLAN.md §4 M2).
 */
public final class Uart {
    private Uart() {}

    /** Bring up the mini-UART: GPIO14/15 → ALT5, 8-bit, clear FIFOs, enable tx/rx. */
    public static void init() {
        Magic.store32(Bcm2711.AUX_ENABLES, 1);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IER_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_LCR_REG, 3);
        Magic.store32(Bcm2711.AUX_MU_MCR_REG, 0);
        Magic.store32(Bcm2711.AUX_MU_IIR_REG, 0xC6);
        Magic.store32(Bcm2711.AUX_MU_BAUD_REG, Bcm2711.BAUD_115200);
        Magic.store32(Bcm2711.GPFSEL1, (Bcm2711.ALT5 << 12) | (Bcm2711.ALT5 << 15));
        Magic.store32(Bcm2711.GPIO_PUP_PDN_CNTRL_REG0, 0);
        Magic.store32(Bcm2711.AUX_MU_CNTL_REG, 3);
    }

    /** Write one byte, spinning until the TX FIFO can accept it (LSR bit5). */
    public static void putc(int c) {
        while ((Magic.load32(Bcm2711.AUX_MU_LSR_REG) & 0x20) == 0) {
        }
        Magic.store8(Bcm2711.AUX_MU_IO_REG, c);
    }

    /** Write {@code n} bytes starting at absolute address {@code p}. */
    public static void puts(long p, int n) {
        while (n != 0) {
            putc(Magic.load8(p));
            p = p + 1L;
            n = n - 1;
        }
    }
}
