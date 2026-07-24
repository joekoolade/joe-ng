package board.bcm2711;

import magic.Magic;

/**
 * SoC reset via the BCM2711 power-management watchdog — the last step of the
 * self-hosting loop (PLAN.md §M5.5d): after joe-ng has written its reproduced
 * image back to {@code kernel8.img}, this resets the chip so the GPU firmware
 * reloads that (metal-written) image and boots it, which reproduces itself
 * again. "Drop the seed JVM" made literal.
 *
 * <p>The classic Raspberry Pi reset: arm the watchdog with a short timeout and
 * request a full reset, both guarded by the PM password, then spin until it
 * fires.
 */
public final class Reset
{
    private Reset() {}

    private static final long PM_BASE = 0xFE10_0000L;
    private static final long PM_RSTC = PM_BASE + 0x1C;
    private static final long PM_WDOG = PM_BASE + 0x24;
    private static final int  PM_PASSWORD = 0x5A00_0000;      // top byte of every PM write
    private static final int  PM_RSTC_WRCFG_CLR = 0xFFFF_FFCF;
    private static final int  PM_RSTC_FULL_RESET = 0x0000_0020;

    /** Reset the SoC; never returns (the firmware reloads {@code kernel8.img} and reboots). */
    public static void reboot()
    {
        Magic.store32(PM_WDOG, PM_PASSWORD | 10);            // ~10 watchdog ticks, then bite
        int r = Magic.load32(PM_RSTC) & PM_RSTC_WRCFG_CLR;
        Magic.store32(PM_RSTC, PM_PASSWORD | r | PM_RSTC_FULL_RESET);
        while (true)
        {
            Magic.wfe();                                     // wait for the watchdog to fire
        }
    }
}
