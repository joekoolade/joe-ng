package board.bcm2711;

import magic.Magic;

/**
 * GPIO function-select and pull configuration, written as ordinary Java over {@code magic.Magic} MMIO
 * (PLAN.md WiFi §M0). Both operations are READ-MODIFY-WRITE on a shared register — a GPFSELn word holds
 * 10 pins, a PUP_PDN word holds 16 — so setting one pin must not clobber its neighbours. ({@code Uart.init}
 * currently does a blind full-word store to GPFSEL1, clobbering GPIO10-13/16-19; it is left as-is until the
 * M6 cleanup to avoid destabilising the boot, but new callers use this class.)
 *
 * <p>Source: BCM2711 ARM Peripherals, GPIO section (GPFSELn 3-bit function fields; the BCM2711-specific
 * GPIO_PUP_PDN_CNTRL_REGn 2-bit pull fields, whose 1=up/2=down encoding is reversed from the BCM2835). See
 * SOURCES.md. Written to bring up the CYW43455 WiFi SDIO pins (GPIO34-39, ALT3).
 */
public final class Gpio
{
    private Gpio() {}

    /**
     * Select alternate function {@code alt} (a 3-bit GPFSEL field value, e.g. {@link Bcm2711#ALT3}) for
     * {@code pin}, preserving the other pins in the same GPFSELn word.
     */
    public static void setAlt(int pin, int alt)
    {
        long reg = Bcm2711.GPIO_BASE + (long) (pin / 10) * 4L;   // 10 pins per register, 3 bits per pin
        int shift = (pin % 10) * 3;
        int v = Magic.load32(reg);
        v = (v & ~(0b111 << shift)) | ((alt & 0b111) << shift);
        Magic.store32(reg, v);
    }

    /**
     * Set the pull state ({@link Bcm2711#PULL_NONE}/{@link Bcm2711#PULL_UP}/{@link Bcm2711#PULL_DOWN}) for
     * {@code pin}, preserving the other pins in the same PUP_PDN word.
     */
    public static void setPull(int pin, int pull)
    {
        long reg = Bcm2711.GPIO_PUP_PDN_CNTRL_REG0 + (long) (pin / 16) * 4L;   // 16 pins per register, 2 bits per pin
        int shift = (pin % 16) * 2;
        int v = Magic.load32(reg);
        v = (v & ~(0b11 << shift)) | ((pull & 0b11) << shift);
        Magic.store32(reg, v);
    }
}
