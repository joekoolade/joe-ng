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

import asm.A64Enc;
import board.Board;
import board.Uart;
import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VM;
import vm.VMScheduler;

/**
 * The Arm Power State Coordination Interface, called by HVC: how a machine whose firmware (or hypervisor)
 * implements PSCI starts its secondary cores -- QEMU virt today. Reached through {@code VirtPlatform
 * .startSecondaries} ({@code board.Platform.OPS}).
 */
public final class Psci
{
    private Psci()
    {
    }

    /**
     * Start cores 1-3 at {@code entry} with PSCI CPU_ON (an HVC; QEMU virt implements PSCI for a guest with no EL2). Each
     * enters {@code VM.SEC_STUB} at EL1 with its MMU OFF, at a PHYSICAL address, x0 = its core id (the context id we
     * pass). The stub turns the MMU on and branches to {@code secondaryMain} ({@code entry}), which is a VIRTUAL address -- but the
     * instruction after the SCTLR write is fetched at the stub's physical address read as virtual, which the normal
     * map sends elsewhere (virtual 0x4300_0000 is RAM at 0x8300_0000). So the stub turns the MMU on with
     * {@link #buildBootTables}: the normal map plus an identity entry for the stub's own 2 MiB block -- the trick
     * writer.VirtPrelude plays for core 0. secondaryMain's enableMmuThisCore then moves the core to the real tables.
     */
    public static void startSecondaries(long entry)
    {
        buildBootTables();
        Address stub = Address.fromLong(VM.SEC_STUB);
        int w = 0;
        w = emitConst(stub, w, 3, entry);      // x3 = &secondaryMain (virtual: after the MMU is on)
        w = emitConst(stub, w, 2, (long) VM.SEC_STACK_HI << 16);
        w = emit(stub, w, A64Enc.lslImm(1, 0, 20));        // x1 = core * 1 MiB
        w = emit(stub, w, A64Enc.addReg(2, 2, 1));
        w = emit(stub, w, A64Enc.movToSp(2));              // SP = this core's stack top (virtual, used after)
        w = emitConst(stub, w, 4, 0x30_0000L);
        w = emit(stub, w, A64Enc.msr(A64Enc.CPACR_EL1, 4)); // CPACR_EL1.FPEN = 0b11, as the Pi's stub sets
        w = emit(stub, w, A64Enc.isb());
        w = emitConst(stub, w, 4, 0xFFL);
        w = emit(stub, w, A64Enc.msr(A64Enc.MAIR_EL1, 4)); // the attributes enableMmuThisCore uses
        w = emitConst(stub, w, 4, 0x2_0080_3520L);
        w = emit(stub, w, A64Enc.msr(A64Enc.TCR_EL1, 4));
        w = emitConst(stub, w, 4, Board.phys(VM.PT_BOOT));
        w = emit(stub, w, A64Enc.msr(A64Enc.TTBR0_EL1, 4));
        w = emit(stub, w, A64Enc.dsb());
        w = emit(stub, w, A64Enc.tlbiVmalle1());
        w = emit(stub, w, A64Enc.dsb());
        w = emit(stub, w, A64Enc.icIallu());
        w = emit(stub, w, A64Enc.dsb());
        w = emit(stub, w, A64Enc.isb());
        w = emitConst(stub, w, 4, 0x30D0_1805L);
        w = emit(stub, w, A64Enc.msr(A64Enc.SCTLR_EL1, 4));
        w = emit(stub, w, A64Enc.isb());
        w = emit(stub, w, A64Enc.br(3));                   // fetched through the identity entry -> secondaryMain(core)
        long end = VM.SEC_STUB + w * 4L;

        // The CPU_ON call itself: psci(core, entry) -> x0 = function id, x1 = target MPIDR (Aff0 = core on virt),
        // x2 = entry (physical), x3 = context id (arrives in the new core's x0). Returns the PSCI status (0 = ok).
        long psci = VM.SEC_STUB + 0x200L;
        Address call = Address.fromLong(psci);
        int c = 0;
        c = emit(call, c, A64Enc.movReg(3, 0));
        c = emit(call, c, A64Enc.movReg(2, 1));
        c = emit(call, c, A64Enc.movReg(1, 0));
        c = emitConst(call, c, 0, PSCI_CPU_ON);
        c = emit(call, c, A64Enc.hvc0());
        c = emit(call, c, A64Enc.ret());
        Heap.publishCode(Address.fromLong(VM.SEC_STUB), Address.fromLong(end));
        Heap.publishCode(call, Address.fromLong(psci + c * 4L));
        VMScheduler.cleanToPoC(VM.SEC_STUB, end);                         // the secondaries fetch the stub with the MMU off

        int core = 1;
        while (core <= 3)
        {
            long r = Magic.call2(psci, core, Board.phys(VM.SEC_STUB));
            if (r != 0L)
            {
                Uart.write(Magic.bytes("SMP: PSCI CPU_ON core "));
                VM.printDec(core);
                Uart.write(Magic.bytes(" failed, status "));
                VM.printDec((int) r);
                Uart.putc(0x0A);
            }
            core += 1;
        }
    }

    /** PSCI 0.2 CPU_ON, SMC64/HVC64 calling convention. */
    private static final long PSCI_CPU_ON = 0xC400_0003L;

    /**
     * The tables a secondary turns its MMU on with ({@code VM.PT_BOOT}): an L1 that shares three of the
     * normal L2 tables and points the fourth -- the one covering the stub's physical address read as virtual -- at
     * a copy carrying an identity entry for the stub's 2 MiB block. Built by the primary after buildPageTables.
     */
    private static void buildBootTables()
    {
        long stubPa = Board.phys(VM.SEC_STUB);
        int g = (int) (stubPa >>> 30);                     // which 1 GiB (stubPa < 4 GiB)
        Address l1 = Address.fromLong(VM.PT_BOOT);
        Address l2 = Address.fromLong(VM.PT_BOOT + 0x1000L);
        Address srcL2 = Address.fromLong(VM.PT_BASE + (g + 1) * 0x1000L);
        int i = 0;
        while (i < 4)
        {
            l1.plus(i * 8L).store(Address.fromLong(VM.PT_BASE).plus(i * 8L).loadLong());
            i += 1;
        }
        int j = 0;
        while (j < 512)
        {
            l2.plus(j * 8L).store(srcL2.plus(j * 8L).loadLong());
            j += 1;
        }
        l2.plus(((stubPa >>> 21) & 511L) * 8L).store((stubPa & ~0x1F_FFFFL) | 0x701L);   // identity, Normal WB
        l1.plus(g * 8L).store(Board.phys(VM.PT_BOOT + 0x1000L) | 3L);
        Magic.dsb();
        VMScheduler.cleanToPoC(VM.PT_BOOT, VM.PT_BOOT + 0x2000L);
    }

    private static int emit(Address at, int w, int insn)
    {
        at.plus(w * 4L).store(insn);
        return w + 1;
    }

    /** Emit MOVZ + MOVKs loading the 64-bit {@code v} into {@code rd}. */
    private static int emitConst(Address at, int w, int rd, long v)
    {
        int n = emit(at, w, A64Enc.movz(rd, (int) (v & 0xFFFFL), 0));
        int hw = 1;
        while (hw < 4)
        {
            int lane = (int) ((v >>> (16 * hw)) & 0xFFFFL);
            if (lane != 0)
            {
                n = emit(at, n, A64Enc.movk(rd, lane, hw));
            }
            hw += 1;
        }
        return n;
    }
}
