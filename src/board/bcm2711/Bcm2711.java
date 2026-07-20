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

    // ----- VideoCore mailbox (property interface, channel 8) ----------------
    // Used to ask the firmware what the core clock actually is, so the mini-UART
    // baud divisor is computed rather than guessed (it varies by firmware/config).
    public static final long MBOX_BASE   = PERIPHERAL_BASE + 0x00_B880; // 0xFE00B880
    public static final long MBOX_READ   = MBOX_BASE + 0x00;
    public static final long MBOX_STATUS = MBOX_BASE + 0x18;
    public static final long MBOX_WRITE  = MBOX_BASE + 0x20;
    public static final int  MBOX_FULL   = 0x8000_0000;   // status: can't write
    public static final int  MBOX_EMPTY  = 0x4000_0000;   // status: nothing to read
    public static final int  MBOX_CH_PROP = 8;            // ARM -> VC property channel
    /** 16-byte-aligned scratch for the property buffer: above the image, below the heap. */
    public static final long MBOX_BUFFER = 0x000E_0000L;
    /** VC bus alias of ARM physical RAM (uncached view the firmware expects). */
    public static final long MBOX_BUS_ALIAS = 0xC000_0000L;
    /**
     * Ask for the <em>measured</em> rate, not {@code GET_CLOCK_RATE} (0x00030002).
     * That one reports the rate the firmware was <em>asked</em> for — on real
     * silicon it returned exactly our {@code core_freq=200} while the core was
     * really running at ~175 MHz, so the derived divisor was 15% off and garbled
     * everything. The measured tag reports what the hardware actually does.
     */
    public static final int  TAG_GET_CLOCK_RATE_MEASURED = 0x0003_0047;
    public static final int  CLOCK_ID_CORE = 4;           // the clock feeding the mini-UART

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
     * <em>Fallback</em> baud divisor for 115200, used only if the mailbox does not
     * report a core clock. mini-UART baud = core_clock / (8*(divisor+1)), so the
     * divisor depends entirely on the VPU core clock — and that is not something we
     * can assume: hardcoding it failed repeatedly on real silicon (270 for 250 MHz,
     * 541 for 500 MHz, then 216 for the 200 MHz idle, which worked on one SD card
     * and garbled on the next because a card carrying recovery files boots different
     * firmware). {@link Mailbox#coreClockHz()} now asks the firmware and
     * {@link Uart} computes the divisor; this constant is just the safety net.
     *
     * <p>179 matches the 166 MHz a real Pi 4 reported via the measured-rate tag
     * (166e6/(8*180) = 115,278 baud, +0.07%).
     */
    public static final int  BAUD_115200 = 179;
}
