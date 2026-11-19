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
package board.arm;

import board.Uart;
import magic.Address;

/**
 * The Arm PrimeCell PL011 UART, at a base address its board passes in: QEMU virt's console today (and the Pi 4's
 * UART0, should a board ever want it). The register half only; {@link board.Uart} holds the rings, locks and
 * interrupt logic. Runs with its FIFOs OFF (LCR_H.FEN = 0): every received character then raises RXRIS on its own,
 * the behaviour the ring code was written against.
 */
public final class Pl011
{
    private Pl011()
    {
    }

    private static final long DR = 0x00;               // data
    private static final long FR = 0x18;               // flags
    private static final long LCR_H = 0x2C;            // line control
    private static final long CR = 0x30;               // control
    private static final long IMSC = 0x38;             // interrupt mask set/clear
    private static final long ICR = 0x44;              // interrupt clear
    private static final int FR_RXFE = 0x10;           // receive FIFO empty
    private static final int FR_TXFF = 0x20;           // transmit FIFO full
    private static final int IMSC_RX = 0x50;           // RXIM | RTIM
    private static final int IMSC_TX = 0x20;           // TXIM

    /** 8N1, FIFOs off, interrupts masked. No baud divisor is set: QEMU ignores it (a real PL011 would need one). */
    public static void init(long base)
    {
        Address.fromLong(base + CR).store(0);
        Address.fromLong(base + LCR_H).store(0x60);                // WLEN = 8 bits, FEN = 0
        Address.fromLong(base + IMSC).store(0);
        Address.fromLong(base + ICR).store(0x7FF);
        Address.fromLong(base + CR).store(0x301);                  // UARTEN | TXE | RXE
        Uart.coreHz = 0;                                           // no VPU clock: boot prints "core 0MHz"
    }

    public static boolean txReady(long base)
    {
        return (Address.fromLong(base + FR).loadInt() & FR_TXFF) == 0;
    }

    public static boolean rxReady(long base)
    {
        return (Address.fromLong(base + FR).loadInt() & FR_RXFE) == 0;
    }

    public static int get(long base)
    {
        return Address.fromLong(base + DR).loadInt() & 0xFF;
    }

    public static void put(long base, int c)
    {
        Address.fromLong(base + DR).store(c & 0xFF);
    }

    /** {@link Uart#IRQ_RX} / {@link Uart#IRQ_TX} translated into IMSC bits. */
    public static void setIrq(long base, int want)
    {
        int imsc = 0;
        if ((want & Uart.IRQ_RX) != 0)
        {
            imsc = imsc | IMSC_RX;
        }
        if ((want & Uart.IRQ_TX) != 0)
        {
            imsc = imsc | IMSC_TX;
        }
        Address.fromLong(base + IMSC).store(imsc);
    }
}
