package board.bcm2711;

import magic.Magic;
import vm.VM;

/**
 * WiFi (CYW43455) bring-up orchestrator — the top of the driver stack this milestone arc is building
 * (PLAN.md WiFi). {@link #bringUp()} is invoked from {@code VM.run} behind the non-final
 * {@code VM.WIFI_ENABLED} flag: the writer's reachability BFS compiles this and everything it touches
 * ({@link Sdio}, {@link Gpio}, {@link Mailbox}) into the image, but nothing runs until M1 flips the flag
 * (and adds chip detection) — QEMU has no CYW43455, and its {@code 0xFE300000} block is the SD card, so
 * poking it there would be wrong.
 *
 * <p>M0 delivers only the infrastructure this method drives (SDIO host + enumeration + mailbox/GPIO
 * enablers); M1 extends {@link #bringUp()} with firmware upload and SDPCM (see the plan).
 */
public final class Wifi
{
    private Wifi() {}

    /** Power the chip, bring up the SDIO host, enumerate + probe. Logs each step over the UART (real-HW only). */
    public static void bringUp()
    {
        Uart.write(Magic.bytes("wifi: bring-up\n"));

        // WL_ON power lives on the VideoCore GPIO-expander (exact pin VERIFY in M1). Assert it, settle.
        // (Left as a mailbox call the moment the pin is confirmed; harmless if the firmware already powers it.)
        int emmcHz = Mailbox.getClockRate(Bcm2711.CLOCK_ID_EMMC);
        Uart.write(Magic.bytes("  emmc clock "));
        VM.printDec(emmcHz / 1000000);
        Uart.write(Magic.bytes(" MHz\n"));

        // M1a: enumerate + read the chip ID through the backplane (proves the SDIO link end to end).
        int chipId = board.cyw43.Cyw43.probeChip();
        if (chipId == 0)
        {
            Uart.write(Magic.bytes("wifi: probe failed (see step above)\n"));
            return;
        }
        // M1b continues here: firmware + NVRAM upload, release the chip's ARM, F2 ready, then SDPCM.
    }
}
