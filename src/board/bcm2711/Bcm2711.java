package board.bcm2711;

/**
 * BCM2711 (Raspberry Pi 4) MMIO register addresses — the subset the M1 boot
 * path needs: GPIO alt-function + the AUX mini-UART (UART1), our first console
 * (PLAN.md §3, CLAUDE.md target facts). Addresses use the ARM-side peripheral
 * base {@code 0xFE000000} (the "low peripheral" view the 64-bit kernel sees).
 *
 * Source: BCM2711 ARM Peripherals (mini-UART/AUX and GPIO sections). See
 * SOURCES.md. These are plain constants; the actual MMIO access is magic
 * {@code Address} loads/stores lowered by the compiler — for M1 they are
 * emitted directly by {@code vm.EmitBoot}.
 */
public final class Bcm2711
{
    private Bcm2711() {}

    /** ARM-side peripheral base (low-peripheral mode). */
    public static final long PERIPHERAL_BASE = 0xFE00_0000L;

    // ----- GPIO ------------------------------------------------------------
    public static final long GPIO_BASE   = PERIPHERAL_BASE + 0x20_0000; // 0xFE200000
    /** Function select for GPIO10..19 (3 bits/pin). TXD1/RXD1 are GPIO14/15. */
    public static final long GPFSEL1     = GPIO_BASE + 0x04;            // 0xFE200004
    /** Pull up/down control for GPIO0..15 (2 bits/pin) — BCM2711-specific. */
    public static final long GPIO_PUP_PDN_CNTRL_REG0 = GPIO_BASE + 0xE4; // 0xFE2000E4

    /** ALT5 selects the mini-UART on GPIO14/15. Field value per pin is 0b010. */
    public static final int  ALT5        = 0b010;

    // ----- AUX / mini-UART (UART1) -----------------------------------------
    public static final long AUX_BASE    = PERIPHERAL_BASE + 0x21_5000; // 0xFE215000
    public static final long AUX_ENABLES     = AUX_BASE + 0x04;         // enable mini-UART (bit0)
    public static final long AUX_MU_IO_REG   = AUX_BASE + 0x40;         // tx/rx data
    public static final long AUX_MU_IER_REG  = AUX_BASE + 0x44;         // interrupt enable
    public static final long AUX_MU_IIR_REG  = AUX_BASE + 0x48;         // interrupt id / FIFO clear
    public static final long AUX_MU_LCR_REG  = AUX_BASE + 0x4C;         // line control (8-bit)
    public static final long AUX_MU_MCR_REG  = AUX_BASE + 0x50;         // modem control
    public static final long AUX_MU_LSR_REG  = AUX_BASE + 0x54;         // line status
    public static final long AUX_MU_CNTL_REG = AUX_BASE + 0x60;         // extra control (tx/rx enable)
    public static final long AUX_MU_BAUD_REG = AUX_BASE + 0x68;         // baud rate divisor

    /** AUX_MU_LSR bit5: transmit FIFO can accept at least one byte. */
    public static final int  LSR_TX_EMPTY = 5;

    /**
     * Baud divisor for 115200. mini-UART baud = core_clock / (8*(divisor+1)).
     * Pinned to a 500 MHz core clock via {@code core_freq=500} in config.txt:
     * 500e6/(8*115200) - 1 ≈ 541 → 115313 baud (+0.1%). On real Pi 4 silicon the
     * mini-UART clock tracks the 500 MHz VPU core, not the 250 MHz the Pi 3 recipe
     * assumes — divisor 270 (250 MHz) came out ~2× too fast and garbled. QEMU
     * ignores the divisor. If silicon is still garbled, the core clock differs
     * again — try 270 (250 MHz) or other divisors (see scripts/flash.md).
     */
    public static final int  BAUD_115200 = 541;
}
