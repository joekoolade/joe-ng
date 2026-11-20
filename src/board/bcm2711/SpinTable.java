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

import asm.A64Enc;
import magic.Address;
import magic.Magic;
import vm.Heap;
import vm.VM;
import vm.VMScheduler;

/**
 * How the Pi 4 starts its secondary cores: the firmware's armstub parks cores 1-3 at EL2 in a WFE loop, each
 * watching its spin slot (0xE0/0xE8/0xF0); writing an entry address there and SEV releases it. Reached through
 * {@code Bcm2711Platform.startSecondaries} ({@code board.Platform.OPS}).
 */
public final class SpinTable
{
    private SpinTable()
    {
    }

    /**
     * Start cores 1-3 at {@code entry} ({@code VM.secondaryMain(int core)}): point the firmware's spin slots at a
     * stub that drops each parked secondary to EL1, and wake them.
     */
    public static void start(long entry)
    {
        // Stub (runs on each secondary at EL2): x0 = MPIDR & 3 (core id, becomes secondaryMain's arg),
        // set the per-core EL1 stack + a sane EL1 SCTLR, then drop EL2 -> EL1 (mirroring EmitBoot) and
        // ERET straight into secondaryMain(core). x0 survives the ERET.
        long s = VM.SEC_STUB;
        int lo = (int) (entry & 0xFFFFL);
        int mid = (int) ((entry >>> 16) & 0xFFFFL);
        int hi = (int) ((entry >>> 32) & 0xFFFFL);
        Address stub = Address.fromLong(s);                // 32-bit instruction-word stores below
        int w = 0;
        stub.plus(w * 4L).store(A64Enc.mrs(0, A64Enc.MPIDR_EL1));      w += 1;
        stub.plus(w * 4L).store(A64Enc.movz(9, 3, 0));                w += 1;   // x9 = 3
        stub.plus(w * 4L).store(A64Enc.andReg(0, 0, 9));             w += 1;   // x0 = core id
        stub.plus(w * 4L).store(A64Enc.lslImm(1, 0, 20));           w += 1;   // x1 = core * 1MiB
        stub.plus(w * 4L).store(A64Enc.movz(2, VM.SEC_STACK_HI, 1));    w += 1;   // x2 = 0x0200_0000
        stub.plus(w * 4L).store(A64Enc.addReg(2, 2, 1));           w += 1;   // x2 = per-core stack top
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.SP_EL1, 2));      w += 1;   // SP_EL1 = stack top
        stub.plus(w * 4L).store(A64Enc.movz(3, 0x0800, 0));         w += 1;
        stub.plus(w * 4L).store(A64Enc.movk(3, 0x30D0, 1));         w += 1;   // x3 = 0x30D0_0800
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.SCTLR_EL1, 3));   w += 1;   // EL1 SCTLR (MMU off, RES1)
        stub.plus(w * 4L).store(A64Enc.movz(3, 0x8000, 1));         w += 1;
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.HCR_EL2, 3));     w += 1;   // HCR_EL2.RW = 1
        stub.plus(w * 4L).store(A64Enc.movz(3, 0x33FF, 0));         w += 1;
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.CPTR_EL2, 3));    w += 1;   // don't trap FP to EL2
        // ... NOR to EL1: CPACR_EL1.FPEN = 0b11, as the boot core sets it (EmitBoot, VM.boot). Its reset value traps
        // FP/SIMD at EL1, and without this every FP instruction a thread ran on cores 1-3 raised an InternalError
        // (TRAP ec=0x07) -- latent while nothing doing FP math ran there; a /run benchmark's first double divide on
        // a secondary found it (2026-11-14). QEMU does not trap, so only the Pi shows it.
        stub.plus(w * 4L).store(A64Enc.movz(3, 0x0030, 1));         w += 1;   // x3 = 0x30_0000
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.CPACR_EL1, 3));   w += 1;   // CPACR_EL1.FPEN = 0b11
        stub.plus(w * 4L).store(A64Enc.movz(3, 0x03C5, 0));         w += 1;
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.SPSR_EL2, 3));    w += 1;   // target = EL1h, DAIF masked
        stub.plus(w * 4L).store(A64Enc.movz(3, lo, 0));             w += 1;
        stub.plus(w * 4L).store(A64Enc.movk(3, mid, 1));            w += 1;
        stub.plus(w * 4L).store(A64Enc.movk(3, hi, 2));            w += 1;   // x3 = &secondaryMain
        stub.plus(w * 4L).store(A64Enc.msr(A64Enc.ELR_EL2, 3));     w += 1;
        stub.plus(w * 4L).store(A64Enc.eret());                   w += 1;   // -> secondaryMain(core) at EL1
        Heap.publishCode(Address.fromLong(s), Address.fromLong(s + w * 4L));
        VMScheduler.cleanToPoC(s, s + w * 4L);                         // secondaries fetch the stub uncached (MMU off) ...

        Address.fromLong(0x00E0L).store(stub);             // spin_cpu1 -> stub
        Address.fromLong(0x00E8L).store(stub);             // spin_cpu2 -> stub
        Address.fromLong(0x00F0L).store(stub);             // spin_cpu3 -> stub
        VMScheduler.cleanToPoC(0x00C0L, 0x0100L);                      // ... and read the spin slots uncached: push both to PoC
        Magic.sev();                                       // wake the WFE-parked cores
    }
}
