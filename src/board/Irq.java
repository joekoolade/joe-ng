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

import magic.Magic;

/**
 * The board's interrupt controller, as the VM sees it: acknowledge, end, enable / mask a device's interrupt, bring
 * up the tick, and hand a device interrupt to its driver. Each operation goes through the board's platform class
 * ({@link Platform#OPS}): both ARM boards drive the shared {@code board.arm.Gic} at their own bases, and a PC would
 * drive its APIC behind the same calls. Interrupt numbers are the controller's own (a GIC INTID).
 *
 * <p><b>Device interrupts are the board's table.</b> Which drivers a board has, and which interrupt each raises,
 * is the platform's {@code irqDevice} -- so the VM's interrupt path names no driver, and an image carries only its
 * own board's. The console UART is the exception: {@link Uart} is board-neutral, so the VM services it itself.
 */
public final class Irq implements magic.Uninterruptible
{
    private Irq()
    {
    }

    /** The board's interrupt operations ({@link Platform#OPS}); filled by the writer, never written at run time. */
    public static long hwInitTimerAddr;   // irqInitTimer()J: enable this core's tick interrupt; answers its number
    public static long hwAckAddr;         // irqAck()J: the pending interrupt, or NONE
    public static long hwEndAddr;         // irqEnd(J)V
    public static long hwEnableAddr;      // irqEnable(J)V: route a device interrupt to core 0 and enable it
    public static long hwMaskAddr;        // irqMask(J)V
    public static long hwUnmaskAddr;      // irqUnmask(J)V
    public static long hwDeviceAddr;      // irqDevice(J)J: run the device's handler; DEV_* below

    /** {@link #acknowledge}'s answer when nothing is pending (a spurious interrupt). */
    public static final int NONE = -1;

    /** {@link #device}'s answers: not a device interrupt of this board's, handled, handled + wake the net RX task. */
    public static final long DEV_UNKNOWN = -1L;
    public static final long DEV_DONE = 0L;
    public static final long DEV_WAKE_NET = 1L;

    /** The tick's interrupt number, set by {@link #initTimer} (the same on every core). */
    public static int timer;

    /** Enable THIS core's tick interrupt and its controller interface. Each core calls it before unmasking IRQs. */
    public static void initTimer()
    {
        timer = (int) Magic.call0(hwInitTimerAddr);
    }

    /** Take the pending interrupt: its number, or {@link #NONE}. Every number but NONE must be {@link #end}ed. */
    public static int acknowledge()
    {
        return (int) Magic.call0(hwAckAddr);
    }

    /** End interrupt {@code id}, as {@link #acknowledge} answered it; {@link #NONE} is ignored. */
    public static void end(int id)
    {
        if (id != NONE)
        {
            long u = Magic.call2(hwEndAddr, id, 0L);
        }
    }

    /** Route device interrupt {@code id} to core 0 and enable it. */
    public static void enable(int id)
    {
        long u = Magic.call2(hwEnableAddr, id, 0L);
    }

    /** Stop interrupt {@code id} being delivered (a level interrupt an ISR hands to a task). */
    public static void mask(int id)
    {
        long u = Magic.call2(hwMaskAddr, id, 0L);
    }

    /** Deliver interrupt {@code id} again after {@link #mask}. */
    public static void unmask(int id)
    {
        long u = Magic.call2(hwUnmaskAddr, id, 0L);
    }

    /** Hand interrupt {@code id} to the board's driver for it: {@link #DEV_UNKNOWN}, {@link #DEV_DONE} or {@link #DEV_WAKE_NET}. */
    public static long device(int id)
    {
        return Magic.call2(hwDeviceAddr, id, 0L);
    }
}
