/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-15
 */
package demo;

import jdk.internal.misc.MetalStats;
import jdk.internal.misc.MetalBoard;
import magic.Address;

/**
 * Does the scheduler's timer tick reach a launched program? Busy-spins (no yield, no block) for half a second and
 * reports the VM's timer-tick count before and after. On the Pi the tick (~10 ms) arrives; on QEMU it was recorded
 * as never arriving, which is why a stop-the-world there cannot stop a core running a loop.
 */
public class TickProbe
{
    public static void main(String[] args)
    {
        int ticksAt = indexOf("timer ticks");
        long t0 = MetalStats.read(MetalStats.THREADS)[ticksAt];
        long end = System.nanoTime() + 500_000_000L;
        long spins = 0L;
        while (System.nanoTime() < end)
        {
            spins = spins + 1L;
        }
        long t1 = MetalStats.read(MetalStats.THREADS)[ticksAt];
        System.out.println("TickProbe: ticks " + t0 + " -> " + t1 + " over a 500 ms spin (" + spins + " spins)");
        System.out.println(t1 - t0 >= 10 ? "TickProbe TICKS" : "TickProbe NO TICKS");
        // The GIC as this core sees it: on the Pi the GIC-400 (distributor 0xFF841000, CPU interface 0xFF842000);
        // on QEMU virt its GICv2 (physical 0x08000000/0x08010000, mapped at +0xF0000000). The Pi addresses are
        // unmapped on virt, and reading them there faulted.
        boolean virt = MetalBoard.board().equals("virt");
        long gicd = virt ? 0xF800_0000L : 0xFF84_1000L;
        long gicc = virt ? 0xF801_0000L : 0xFF84_2000L;
        int typer = Address.fromLong(gicd + 0x004L).loadInt();
        System.out.println("TickProbe: GICD_TYPER=0x" + Integer.toHexString(typer) + " (SecurityExtn="
                + ((typer >> 10) & 1) + ") GICD_CTLR=0x" + Integer.toHexString(Address.fromLong(gicd).loadInt())
                + " GICC_CTLR=0x" + Integer.toHexString(Address.fromLong(gicc).loadInt())
                + " IGROUPR0=0x" + Integer.toHexString(Address.fromLong(gicd + 0x080L).loadInt())
                + " ISENABLER0=0x" + Integer.toHexString(Address.fromLong(gicd + 0x100L).loadInt())
                + " ISPENDR0=0x" + Integer.toHexString(Address.fromLong(gicd + 0x200L).loadInt()));
        long linger = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        if (linger > 0L)
        {
            System.out.println("TickProbe: lingering " + linger + " s for inspection");
            try
            {
                Thread.sleep(linger * 1000L);
            }
            catch (InterruptedException e)
            {
                return;
            }
        }
    }

    private static int indexOf(String name)
    {
        String[] names = MetalStats.THREADS_NAMES;
        for (int i = 0; i < names.length; i++)
        {
            if (names[i].equals(name))
            {
                return i;
            }
        }
        throw new IllegalStateException("no stat " + name);
    }
}
