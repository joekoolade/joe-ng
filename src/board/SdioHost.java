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
package board;

/**
 * An SDIO host controller, as an SDIO chip driver sees it: bring up the bus and select the card, move CCCR and
 * function registers a byte at a time (CMD52) or in blocks (CMD53), and steer the card interrupt. The WiFi chip
 * driver ({@code board.cyw43.Cyw43}) talks to its chip only through one of these, so it names no board.
 *
 * <p><b>A subclass per board.</b> The board's platform class makes its host -- the Pi's is
 * {@code board.bcm2711.Bcm2711SdioHost}, over the legacy Arasan EMMC block -- and hands it to the chip driver
 * before bring-up. These are instance methods rather than {@link Platform#OPS}, because a CMD53 takes six
 * arguments and runs on every received frame: an ordinary virtual call carries them all.
 */
public abstract class SdioHost
{
    /** Bring up the host and enumerate the card (CMD5/3/7, 4-bit bus): 0, or a negative step that stalled. */
    public abstract int init();

    /** The host's clock/reset control register, for diagnostics after {@link #setClock}. */
    public abstract int control1();

    /** Raise the bus clock to {@code hz} (after enumeration). */
    public abstract void setClock(int hz);

    /** Read one byte from {@code func} register {@code addr}; -1 on error. */
    public abstract int cmd52Read(int func, int addr);

    /** Write one byte {@code data} to {@code func} register {@code addr}; false on error. */
    public abstract boolean cmd52Write(int func, int addr, int data);

    /** Read {@code blocks} x {@code blkSize} bytes from {@code func}/{@code addr} into memory at {@code dst}. */
    public abstract boolean cmd53Read(int func, int addr, boolean incr, long dst, int blocks, int blkSize);

    /** Write {@code blocks} x {@code blkSize} bytes from memory at {@code src} to {@code func}/{@code addr}. */
    public abstract boolean cmd53Write(int func, int addr, boolean incr, long src, int blocks, int blkSize);

    /** Let the card interrupt reach the interrupt controller (and only it, of the host's interrupts). */
    public abstract void enableCardInt();

    /** Stop the card interrupt driving its line (it is level-triggered). */
    public abstract void maskCardInt();

    /** Re-arm the card interrupt after {@link #maskCardInt}. */
    public abstract void unmaskCardInt();

    /** Clear the host's latched card-interrupt status. */
    public abstract void clearCardInt();
}
