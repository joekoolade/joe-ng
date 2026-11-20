/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-18
 */
package board;

/**
 * Board-wide facts the VM reads, all fixed when the image is built. There is no run-time test of which board this
 * is: the writer's {@code --board} option binds the board's platform class ({@link Platform}) and bakes its
 * constants ({@link #ramPhys}); everything board-specific is reached through that class.
 *
 * <p><b>QEMU {@code virt}</b> ({@code -M virt -cpu cortex-a72}, or {@code -accel hvf -cpu host}) puts RAM at
 * {@link #VIRT_RAM_PHYS} instead of 0, so the image the Pi runs at 0x80000 loads at 0x4008_0000. Rather than move
 * the hundreds of fixed addresses the VM is built around, the MMU moves the memory: the writer's start-up routine
 * ({@code writer.VirtPrelude}) maps virtual {@code va} to physical {@code va + VIRT_RAM_PHYS} before any Java code
 * runs, and every address the VM uses stays what it is on the Pi. Only the code that hands a PHYSICAL address to
 * the hardware -- a page-table descriptor, TTBR0 -- adds the offset. The machine's devices (GIC at 0x0800_0000,
 * PL011 UART at 0x0900_0000) are mapped at {@code pa + VIRT_DEV_VA}; the Pi's 0xFC00_0000+ window is left
 * UNMAPPED there, so a Pi-only driver reached on {@code virt} faults by name instead of writing somewhere.
 */
public final class Board
{
    private Board()
    {
    }

    /**
     * The fixed scratch every board's drivers place their device buffers in, {@code [DRIVER_SCRATCH,
     * DRIVER_SCRATCH_END)}: the Pi's mailbox property buffer, virt's virtio-rng queue page. The VM's fixed-scratch
     * map ({@code vm.ScratchMap}) reserves this range and names no board's buffer; a driver that needs a fixed
     * buffer takes it from here.
     */
    public static final long DRIVER_SCRATCH = 0x0305_0000L;
    public static final long DRIVER_SCRATCH_END = 0x0305_9000L;

    /** Physical base of RAM on {@code virt}; virtual 0 maps here. */
    public static final long VIRT_RAM_PHYS = 0x4000_0000L;

    /** Device window on {@code virt}: physical {@code [0x0800_0000, 0x0C00_0000)} is at virtual {@code pa + this}. */
    public static final long VIRT_DEV_VA = 0xF000_0000L;
    public static final long VIRT_DEV_LO = 0xF800_0000L;   // first virtual address of the device window
    public static final long VIRT_DEV_HI = 0xFC00_0000L;   // ... and the end (the Pi's own window starts here)

    /** QEMU virt's GICv2 distributor and CPU interface (virtual addresses). */
    public static final long VIRT_GICD = 0x0800_0000L + VIRT_DEV_VA;
    public static final long VIRT_GICC = 0x0801_0000L + VIRT_DEV_VA;

    /** QEMU virt's PL011 UART (virtual address) and its interrupt: SPI 1 = INTID 33. */
    public static final long VIRT_PL011 = 0x0900_0000L + VIRT_DEV_VA;
    public static final int VIRT_UART_SPI = 33;

    /**
     * Where RAM's virtual 0 is physically: the platform class's {@code RAM_PHYS} (0 on the Pi, {@link #VIRT_RAM_PHYS}
     * on virt). Filled by the writer (never written at run time).
     */
    public static long ramPhys;

    /** The physical address of virtual RAM address {@code va}: what TTBR0 and a table descriptor must hold. */
    public static long phys(long va)
    {
        return va + ramPhys;
    }
}
