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
    public static final long MBOX_BUFFER = 0x0305_0000L;   // ~48 MiB — above the all-java.base image (from 0x80000)
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
    public static final int  TAG_GET_CLOCK_RATE = 0x0003_0002;   // requested (not measured) rate
    public static final int  TAG_SET_GPIO_STATE = 0x0003_8041;   // drive a firmware GPIO-expander pin (128+n)
    public static final int  CLOCK_ID_CORE = 4;           // the clock feeding the mini-UART
    public static final int  CLOCK_ID_EMMC = 1;           // the clock feeding the SD/SDIO (EMMC) controller
    public static final int  EXPANDER_GPIO_BASE = 128;    // firmware GPIO-expander pins start here

    // ----- GPIO ------------------------------------------------------------
    public static final long GPIO_BASE   = PERIPHERAL_BASE + 0x20_0000; // 0xFE200000
    /** Function select for GPIO10..19 (3 bits/pin). TXD1/RXD1 are GPIO14/15. */
    public static final long GPFSEL1     = GPIO_BASE + 0x04;            // 0xFE200004
    /** Pull up/down control for GPIO0..15 (2 bits/pin) — BCM2711-specific. Register n (16 pins each) is
     *  at {@code GPIO_PUP_PDN_CNTRL_REG0 + n*4}; {@link Gpio#setPull} computes it from the pin. */
    public static final long GPIO_PUP_PDN_CNTRL_REG0 = GPIO_BASE + 0xE4; // 0xFE2000E4

    // GPFSELn (function select, 10 pins each, 3 bits/pin) is at GPIO_BASE + n*4; Gpio.setAlt derives it.
    // Alt-function field values (3-bit GPFSEL encoding), NOT the same as the ALTn ordinal:
    public static final int  ALT0        = 0b100;
    public static final int  ALT1        = 0b101;
    public static final int  ALT2        = 0b110;
    /** ALT3 — the WiFi (CYW43455) SDIO function on GPIO34..39 (CLK/CMD/DAT0..3). */
    public static final int  ALT3        = 0b111;
    public static final int  ALT4        = 0b011;
    /** ALT5 selects the mini-UART on GPIO14/15. Field value per pin is 0b010. */
    public static final int  ALT5        = 0b010;
    public static final int  GPIO_INPUT  = 0b000;
    public static final int  GPIO_OUTPUT = 0b001;

    // BCM2711 pull encoding (NOTE: reversed from BCM2835 — 1=up, 2=down).
    public static final int  PULL_NONE   = 0b00;
    public static final int  PULL_UP     = 0b01;
    public static final int  PULL_DOWN   = 0b10;

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
    /** AUX_MU_LSR bit0: the receive FIFO holds at least one byte. */
    public static final int  LSR_RX_READY = 0;
    /** AUX_MU_IER bit0: raise an interrupt when the receive FIFO has data. */
    public static final int  IER_RX_ENABLE = 0x01;
    /** AUX_MU_IER bit1: raise an interrupt when the transmit FIFO can accept a byte. */
    public static final int  IER_TX_ENABLE = 0x02;
    /**
     * The mini-UART's interrupt is the AUX interrupt = VideoCore peripheral IRQ 29 (peripherals
     * Table 102), which the GIC-400 exposes as SPI ID 96+29 = 125 (Figure 7). A group-1 SPI, so
     * reachable from non-secure EL1 once the armstub has group-1'd the SPIs.
     */
    public static final int  AUX_SPI = 125;

    /**
     * The Arasan SDHCI controller (0xFE300000, the WiFi SDIO — {@code mmcnr} in the BCM2711 device tree) is
     * VideoCore peripheral IRQ 62, which the GIC-400 exposes as SPI 96+62 = 158. Used for IRQ-driven WiFi RX
     * (the CYW43 raises the SDIO card interrupt when it has an F2 frame for the host).
     */
    public static final int  SDIO_SPI = 158;

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
    // The EMMC/SDHCI register block lives in board.bcm2711.Emmc (it auto-detects the controller base).

    // ----- ARM local (per-core) interrupt router --------------------------
    // Reference only — NOT the active path. The BCM2711 per-core block can route the ARM generic
    // timers straight to a core's IRQ/FIQ, but ONLY when the legacy interrupt controller is selected.
    // With the GIC selected (the default), the timer wires to the GIC as PPI 30 and this router is
    // bypassed (IRQ_SOURCE reads 0). We take the timer through the GIC — see board.bcm2711.Gic.
    public static final long ARM_LOCAL_BASE      = 0xFF80_0000L;
    public static final long CORE0_TIMER_IRQCNTL = ARM_LOCAL_BASE + 0x40;  // route: bit1 = CNTPNS -> IRQ
    public static final long CORE0_IRQ_SOURCE    = ARM_LOCAL_BASE + 0x60;  // pending: bit1 = CNTPNS
    public static final int  CNTPNS_IRQ = 1 << 1;   // non-secure EL1 physical timer (CNTP_EL0)
}
