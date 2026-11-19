/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-19
 */
package board.bcm2711;

import board.Uart;
import magic.Address;

/**
 * The BCM2711 mini-UART (AUX / UART1): the register half of the Pi's console. {@link board.Uart} holds the rings,
 * locks and interrupt logic and reaches these through {@link Bcm2711Platform}'s console operations.
 */
public final class MiniUart
{
    private MiniUart()
    {
    }

    /** Bring up the mini-UART: GPIO14/15 → ALT5, 8-bit, clear FIFOs, enable tx/rx. */
    public static void init()
    {
        Address.fromLong(Bcm2711.AUX_ENABLES).store(1);
        Address.fromLong(Bcm2711.AUX_MU_CNTL_REG).store(0);
        Address.fromLong(Bcm2711.AUX_MU_IER_REG).store(0);
        Address.fromLong(Bcm2711.AUX_MU_LCR_REG).store(3);
        Address.fromLong(Bcm2711.AUX_MU_MCR_REG).store(0);
        Address.fromLong(Bcm2711.AUX_MU_IIR_REG).store(0xC6);
        Address.fromLong(Bcm2711.AUX_MU_BAUD_REG).store(baudDivisor());
        Address.fromLong(Bcm2711.GPFSEL1).store((Bcm2711.ALT5 << 12) | (Bcm2711.ALT5 << 15));
        Address.fromLong(Bcm2711.GPIO_PUP_PDN_CNTRL_REG0).store(0);
        Address.fromLong(Bcm2711.AUX_MU_CNTL_REG).store(3);
    }

    /**
     * Baud divisor for 115200, computed from the core clock the firmware reports:
     * mini-UART baud = core_clock / (8*(divisor+1)), so divisor = hz/921600 - 1.
     * The core clock is not something we can assume — it differed across firmware
     * builds and even SD cards on the same board — so we ask rather than guess. If
     * the mailbox does not answer (or reports an implausible rate) we fall back to
     * the compiled-in {@link Bcm2711#BAUD_115200}.
     */
    private static int baudDivisor()
    {
        int hz = Mailbox.coreClockHz();
        Uart.coreHz = hz;                                // kept so boot can report it
        if (hz < 10_000_000 || hz > 1_500_000_000)
        {
            return Bcm2711.BAUD_115200;
        }
        return hz / (8 * 115200) - 1;
    }

    /** LSR bit5: the TX FIFO can take a byte. */
    public static boolean txReady()
    {
        return (Address.fromLong(Bcm2711.AUX_MU_LSR_REG).loadInt() & (1 << Bcm2711.LSR_TX_EMPTY)) != 0;
    }

    /** LSR bit0: a received byte is waiting. */
    public static boolean rxReady()
    {
        return (Address.fromLong(Bcm2711.AUX_MU_LSR_REG).loadInt() & (1 << Bcm2711.LSR_RX_READY)) != 0;
    }

    public static int get()
    {
        return Address.fromLong(Bcm2711.AUX_MU_IO_REG).loadInt() & 0xFF;
    }

    public static void put(int c)
    {
        Address.fromLong(Bcm2711.AUX_MU_IO_REG).store((byte) c);
    }

    /** AUX_MU_IER: its RX/TX enable bits are {@link Uart#IRQ_RX}/{@link Uart#IRQ_TX} exactly. */
    public static void setIrq(int want)
    {
        Address.fromLong(Bcm2711.AUX_MU_IER_REG).store(want);
    }
}
