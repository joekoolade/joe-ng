package vm;

import asm.A64Enc;
import board.bcm2711.Bcm2711;
import board.bcm2711.Emmc;
import board.bcm2711.Fat32;
import board.bcm2711.Gic;
import board.bcm2711.Reset;
import board.bcm2711.Uart;
import classfile.ClassReader;
import compiler.Baseline;
import magic.Magic;
import objectmodel.ObjectModel;

/**
 * The runtime entry points, written as ordinary Java and compiled to A64 by our
 * own baseline compiler (the metacircular point — PLAN.md §1). The seed JVM
 * never runs these on metal; the writer parses this class's bytecode and
 * compiles it into {@code kernel8.img}.
 *
 * Methods are added here as the baseline compiler's bytecode coverage grows,
 * milestone by milestone (CLAUDE.md working agreements).
 */
public final class VM
{
    private VM() {}

    /**
     * Park loop — the first method compiled from real Java bytecode by our own
     * compiler (M1c step 1). Compiles to {@code wfe; b .-4}, identical to the M0
     * hand-emitted spin image.
     */
    public static void spin()
    {
        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * M1c: the full first-light boot, compiled from this Java by our own baseline
     * compiler (the metacircular goal). Equivalent to the hand-emitted
     * {@code vm.EmitBoot}: drop EL2→EL1, enable FP, set a stack, bring up the AUX
     * mini-UART, print the boot message, then park.
     */
    public static void boot()
    {
        // Firmware enters here at EL2 (CurrentEL bits[3:2] = 0b10, value 0x8). If we RE-enter at EL1, we did not
        // come from a reset -- execution wild-branched back to the image entry (a corrupted return address /
        // vtable-or-itable slot resolving to 0x80000). Silently re-running boot then looks like an endless reboot
        // loop with no diagnostic. Catch it: reset SP (the wild branch may have trashed it), name the branch
        // source from x30, and halt. The UART is already up from the first boot, so the message renders.
        if (Magic.readCurrentEL() != 0x8L)
        {
            long src = Magic.readLR();
            Magic.writeSP(0x80000L);
            Uart.write(Magic.bytes("\n*** BOOT RE-ENTERED at EL1: wild branch to the image entry (not a reset). x30=0x"));
            printHex(src);
            Uart.write(Magic.bytes("\n    branch source: "));
            Loader.reportMethodAt(src);
            Uart.write(Magic.bytes("\n    Halting (was an endless silent reboot loop). ***\n"));
            while (true)
            {
                Magic.wfe();
            }
        }
        Magic.dropToEL1();
        Magic.writeCPACR_EL1(0x300000L);   // CPACR_EL1.FPEN = 0b11 (no FP trap)
        Magic.isb();
        Magic.writeSP(0x80000L);           // stack below the image (needed before any call)

        Heap.init();
        Uart.init();
        installFaultVectors();             // turn a CPU fault into a printed report, not a silent hang
        initClasses();                     // run static initializers (writer-generated body)
        run();

        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * Install a minimal EL1 exception vector table so a CPU fault prints a report instead
     * of hanging silently (the failure mode when the boot path faults with no vectors set).
     * Each of the 16 architectural entries branches to {@link #reportFault}; a 2 KiB-aligned
     * Heap buffer holds the table, published for instruction fetch before {@code VBAR_EL1}
     * points at it. Diagnostic aid — harmless when nothing faults.
     */
    static void installFaultVectors()
    {
        // Never taken (reportFaultAddr is the writer-stashed address, always nonzero); the
        // dead call makes reportFault reachable so the writer compiles it and fills the static.
        if (reportFaultAddr == 0L)
        {
            reportFault();
        }
        if (denylistTrapAddr == 0L)                        // same trick: force denylistTrap compiled (#43)
        {
            denylistTrap();
        }
        if (throwFromFaultAddr == 0L)                      // force throwFromFault compiled (fault -> exception)
        {
            throwFromFault(0L);
        }
        // The table MUST live outside the demand-load data heap: the Loader's between-batch reclaim rewinds
        // core 0's arena back to BASE, and a later allocation would overwrite a heap-resident vector table --
        // then the next timer IRQ vectors into clobbered/zeroed memory and the CPU spins on undefined
        // instructions (looked like a "reset" during the long regex/split compile). The JIT code arena is
        // never reclaimed, so allocate there (same rationale as the #43 code arena for JIT buffers).
        long raw = Heap.allocCode(0x1000);
        long table = (raw + 0x7FFL) & ~0x7FFL;             // VBAR_EL1 requires 2 KiB alignment
        int i = 0;
        while (i < 16)
        {
            long entry = table + i * 0x80L;                // 16 entries, 0x80 bytes apart
            if (i == 0 || i == 4)                          // synchronous exception (Current EL SP0 / SPx): the sync
            {                                              // faults (data/instruction abort) -> a Java exception.
                Magic.store32(entry, A64Enc.movFromSp(0)); // mov x0, sp (the faulting SP -- raw, no frame here)
                Magic.store32(entry + 4L, A64Enc.bl((int) ((throwFromFaultAddr - (entry + 4L)) / 4L)));  // throwFromFault(sp)
            }
            else
            {
                Magic.store32(entry, A64Enc.b((int) ((reportFaultAddr - entry) / 4L)));   // IRQ/FIQ/SError -> report+halt
            }
            i += 1;
        }
        Heap.publishCode(table, table + 0x800L);
        vbarBase = table;                                  // kept so startTimerTick can install the IRQ entry
        Magic.writeVBAR_EL1(table);
        Magic.isb();                                       // the new vector base takes effect
    }

    static long vbarBase;              // base of the installed EL1 vector table
    static long timerReload;           // CNTP_TVAL reload for a periodic tick

    /**
     * EL1 IRQ handler — reached (context saved) from the IRQ vector for a taken interrupt. Acknowledge
     * it at the GIC; if it is the physical-timer PPI, count the tick and re-arm the timer; end it.
     */
    static void irqHandler()
    {
        int id = Gic.acknowledge();                        // GICC_IAR: which INTID the CPU interface is signalling
        if (id == Gic.PPI_CNTPNS)                          // non-secure EL1 physical timer PPI (INTID 30)
        {
            ticks = ticks + 1L;
            Magic.writeCNTP_TVAL_EL0(timerReload);         // re-arm (deasserts the level line until it counts down)
        }
        Gic.end(id);                                       // GICC_EOIR: drop priority so the next one can fire
    }

    /**
     * Route IRQs through a context-saving stub to {@link #irqHandler}, bring up the GIC, arm the EL1
     * physical timer for a ~1 ms periodic tick, and unmask IRQs. {@link #installFaultVectors} first.
     */
    static void startTimerTick()
    {
        if (irqHandlerAddr == 0L)                          // dead call: make irqHandler compiled + stashed
        {
            irqHandler();
        }
        // A stub: save x0..x30, BL the Java handler, restore, ERET back to the interrupted code.
        long raw = Heap.alloc(0x400);
        // 16-byte-aligned code, but PAST the {TIB, status} header: aligning raw itself would land on
        // raw+8 (the size header) when raw is only 8-aligned, and the stub's first instruction would
        // clobber it -- leaving a heap block the GC sweep reads as a bogus size and walks off of.
        long stub = (raw + (long) ObjectModel.HEADER_SIZE + 0xFL) & ~0xFL;
        int w = 0;
        Magic.store32(stub + w * 4L, A64Enc.subImm(31, 31, 256));
        w += 1;
        int r = 0;
        while (r <= 30)
        {
            Magic.store32(stub + w * 4L, A64Enc.strx(r, 31, r * 8));
            w += 1;
            r += 1;
        }
        long blAddr = stub + w * 4L;
        Magic.store32(blAddr, A64Enc.bl((int) ((irqHandlerAddr - blAddr) / 4L)));
        w += 1;
        r = 0;
        while (r <= 30)
        {
            Magic.store32(stub + w * 4L, A64Enc.ldrx(r, 31, r * 8));
            w += 1;
            r += 1;
        }
        Magic.store32(stub + w * 4L, A64Enc.addImm(31, 31, 256));
        w += 1;
        Magic.store32(stub + w * 4L, A64Enc.eret());
        w += 1;
        Heap.publishCode(stub, stub + w * 4L);
        // Route both the IRQ (entry 5, +0x280) and FIQ (entry 6, +0x300) vectors of "Current EL with
        // SPx" to the stub -- QEMU's single-security-state GICv2 may signal group-0 as FIQ.
        long e5 = vbarBase + 5L * 0x80L;
        Magic.store32(e5, A64Enc.b((int) ((stub - e5) / 4L)));
        long e6 = vbarBase + 6L * 0x80L;
        Magic.store32(e6, A64Enc.b((int) ((stub - e6) / 4L)));
        Heap.publishCode(e5, e6 + 4L);
        Magic.isb();

        // Bring up the GIC and enable the non-secure physical-timer PPI (INTID 30). With the GIC
        // selected (enable_gic=1), the timer wires straight to it as PPI 30; the ARM-local router is
        // bypassed. The firmware armstub must have placed the PPIs in group 1 for this to deliver.
        Gic.init(Gic.PPI_CNTPNS);
        timerReload = Magic.readCNTFRQ_EL0() / 1000L;      // ~1 ms
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);                        // enable the timer (imask=0)
        Magic.enableIrq();                                 // unmask IRQs at EL1
    }

    /** Stop the periodic tick: disable the timer and re-mask IRQs (leaves the GIC/vectors installed). */
    static void stopTimerTick()
    {
        Magic.writeCNTP_CTL_EL0(0);                        // disable the timer
        Magic.disableIrq();                                // mask IRQ + FIQ at EL1
    }

    // ===== SMP: wake the secondary cores out of the armstub spin table ======================
    // The custom armstub leaves cores 1-3 in a WFE spin loop, each reading a per-core release slot in
    // its own spin table (spin_cpu1/2/3 at physical 0xE0/0xE8/0xF0). Writing a jump address there and
    // sending an event (SEV) releases that core: it branches to the address we wrote. We build a tiny
    // secondary boot stub, point all three slots at it, and each woken core records itself in a flag
    // word then parks -- proving all four cores run our code. (Per-core stacks + scheduling come next.)
    // SMP toggle. Back ON now that the heap is per-core-arena'd ({@link Heap}): each core bump-allocates from
    // its own region, so cores 1-3 allocate concurrently without racing core 0's bump pointer, and core 0's
    // between-batch demand-load heap reclaim only rewinds ITS arena. (It was briefly off during embed-all
    // bring-up, when a single shared bump heap made the reclaim unsafe under the secondaries.)
    static final boolean SMP_ENABLED = true;
    static final long CORE_FLAGS = 0x0304_0000L;          // coreUp[core] lives at CORE_FLAGS + core*8 (above the image)
    // Fixed runtime scratch, relocated to the 48-64 MiB band. The embedded image now carries ALL of
    // java.base (~29 MiB from 0x80000), so the old 7.4 MiB cluster fell INSIDE the image; these addresses
    // sit above it (below the 64 MiB heap). The MMU identity-maps the low 4 GiB, so all are valid RAM.
    static final long SEC_STUB   = 0x0300_0000L;          // secondary boot stub (fixed scratch, not GC'd)
    static final int  SEC_STACK_HI = 0x0380;              // per-core stack base 0x0380_0000 + core*1MiB
    static final long PT_BASE    = 0x0301_0000L;          // page tables: 1 L1 + 4 L2 tables (20 KiB, fixed)
    static final long LOCK_ADDR  = 0x0302_0000L;          // shared HW-spinlock word (cacheable RAM)
    static final long PC_VBAR    = 0x0303_0000L;          // secondaries' shared IRQ vector table (2 KiB aligned)

    static long[] pcTicks;                                // per-core timer-tick count (proof each core's timer fires)
    static long[] pcTaskSp;                               // saved SP of each core's two tasks: index = core*2 + slot
    static int[]  pcCur;                                  // which slot (0/1) each core is currently running
    static long   pcReload;                               // CNTP_TVAL reload (~10 ms)
    static long   pcScheduleAddr;                         // VM.pcSchedule(J)J -- the per-core switcher (writer-stashed)
    static long   pcTask1Addr;                            // VM.pcTask1(I)V -- each core's second task (writer-stashed)
    static int    pcGo2;                                  // release the secondaries into the per-core scheduling demo
    static int    pcStop;                                 // ... and stop it

    /**
     * A secondary core's per-core preemptive context switch, run in the shared switch stub on whichever
     * core the timer fired on. {@code curSp} is the interrupted task's saved-context frame; we read MPIDR
     * to find the core (each touches only ITS own slots -- no lock needed), ack + re-arm ITS timer, then
     * round-robin between that core's two tasks. Returns the next task's frame for the stub to ERET into.
     */
    static long pcSchedule(long curSp)
    {
        int core = (int) (Magic.readMPIDR() & 3L);
        int id = Gic.acknowledge();
        if (id == Gic.PPI_CNTPNS)
        {
            pcTicks[core] = pcTicks[core] + 1L;
            Magic.writeCNTP_TVAL_EL0(pcReload);           // re-arm this core's timer
        }
        Gic.end(id);
        if (pcStop != 0)
        {
            Magic.writeCNTP_CTL_EL0(0);                    // demo over: stop the timer, resume current so it can exit
            return curSp;
        }
        int slot = pcCur[core];
        pcTaskSp[core * 2 + slot] = curSp;                 // save the task we interrupted
        slot = 1 - slot;                                   // round-robin to this core's other task
        pcCur[core] = slot;
        return pcTaskSp[core * 2 + slot];
    }

    /** Each core's second task: print its char ('A'+id) at a visible rate; parks when the demo stops. */
    static void pcTask1(int id)
    {
        while (pcStop == 0)
        {
            Uart.putc((byte) (0x41 + id));                 // preempted back to task 0 by the timer
            schedPause();
        }
        while (true)
        {
            Magic.wfe();                                   // a later tick switches to task 0, which exits
        }
    }

    /**
     * A secondary core's first task: bring up ITS (banked) GIC PPI 30 + CPU interface, point VBAR at the
     * shared switch stub's vector table, arm ITS CNTP timer, and run as task 0 (printing 'A'+core*2). Its
     * own timer IRQ preempts it every ~10 ms into {@link #pcSchedule}, round-robining with task 1
     * ({@link #pcTask1}, char 'A'+core*2+1) -- so this core alternates between two tasks under its own timer.
     */
    static void pcCoreMain(int core)
    {
        Gic.init(Gic.PPI_CNTPNS);                          // per-core banked PPI 30 + this core's GICC/PMR
        Magic.writeVBAR_EL1(PC_VBAR);
        Magic.isb();
        pcCur[core] = 0;                                   // this running flow is the core's task 0
        Magic.writeCNTP_TVAL_EL0(pcReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();
        while (pcStop == 0)                                // task 0: our own timer preempts us into task 1
        {
            Uart.putc((byte) (0x41 + core * 2));
            schedPause();
        }
        Magic.writeCNTP_CTL_EL0(0);
        Magic.disableIrq();
    }

    /** Build a task-1 initial context frame on a fresh stack, entering {@link #pcTask1}(id). Primary-only. */
    static long pcMakeTask(int id)
    {
        long stk = Heap.alloc(0x8000);
        long top = (stk + 0x8000L) & ~0xFL;                // 16-byte aligned; grows down
        long frame = top - SCHED_FRAME;
        int r = 0;
        while (r <= 30)
        {
            Magic.store64(frame + r * 8L, 0L);
            r += 1;
        }
        Magic.store64(frame + 0L, (long) id);              // x0 = id (this task's char index)
        Magic.store64(frame + 248L, pcTask1Addr);          // ELR_EL1 = pcTask1 entry
        Magic.store64(frame + 256L, 0x5L);                 // SPSR_EL1 = EL1h, IRQs on
        return frame;
    }

    /** Build the secondaries' shared per-core context-switch stub + vector + task-1 frames (primary, once). */
    static void pcSetup()
    {
        if (pcScheduleAddr == 0L) { pcScheduleAddr = pcSchedule(0L); }  // dead calls: compile + stash
        if (pcTask1Addr == 0L) { pcTask1(0); }
        pcTicks = new long[4];
        pcTaskSp = new long[8];
        pcCur = new int[4];
        pcReload = Magic.readCNTFRQ_EL0() / 100L;          // ~10 ms

        long stub = buildSwitchStub(pcScheduleAddr, false);   // shared context-switch stub (reused from M7)
        int i = 0;                                          // vector table: entry 5 (IRQ) -> switch stub, else -> fault
        while (i < 16)
        {
            long entry = PC_VBAR + i * 0x80L;
            long target = (i == 5) ? stub : reportFaultAddr;
            Magic.store32(entry, A64Enc.b((int) ((target - entry) / 4L)));
            i += 1;
        }
        Heap.publishCode(PC_VBAR, PC_VBAR + 0x800L);

        int core = 1;                                       // pre-build each secondary's task-1 frame (no alloc race)
        while (core <= 3)
        {
            pcTaskSp[core * 2 + 1] = pcMakeTask(core * 2 + 1);
            core += 1;
        }
    }

    /**
     * Build a 4 KiB-granule identity map (VA=PA) of the low 4 GiB into the page tables at {@link #PT_BASE}:
     * one L1 table of 4 entries, each pointing to an L2 table of 512 * 2 MiB block descriptors. RAM (below
     * 0xFC00_0000) is Normal Inner-Shareable Write-Back cacheable (attr idx 0); the peripheral window
     * (>= 0xFC00_0000: UART/mailbox regs, ARM-local, GIC) is Device-nGnRnE (attr idx 1). Built once by the
     * primary before any core enables its MMU.
     */
    static void buildPageTables()
    {
        int i = 0;
        while (i < 4)
        {
            long l2 = PT_BASE + (i + 1) * 0x1000L;
            Magic.store64(PT_BASE + i * 8L, l2 | 3L);      // L1 table descriptor -> L2 table
            int j = 0;
            while (j < 512)
            {
                long a = ((long) i << 30) + ((long) j << 21);   // i*1GiB + j*2MiB (block base, 2MiB aligned)
                long desc;
                if (a >= 0xFC00_0000L)
                {
                    desc = a | 0x405L;                     // block | AF | AttrIdx=1 (Device)
                }
                else
                {
                    desc = a | 0x701L;                     // block | AF | SH=inner | AttrIdx=0 (Normal WB)
                }
                Magic.store64(l2 + j * 8L, desc);
                j = j + 1;
            }
            i = i + 1;
        }
        Magic.dsb();                                       // tables visible to every core's table walk
    }

    /**
     * Enable the identity-mapped MMU + caches on the current core (each core calls this): set the memory
     * attributes and translation control, point TTBR0 at the shared tables, flush the TLB, then turn on
     * SCTLR.M/C/I. After this, RAM is cacheable and coherent so HW atomics (LDXR/STXR) work.
     */
    static void enableMmuThisCore()
    {
        Magic.writeMAIR_EL1(0xFFL);                        // idx0 = Normal WB RW-alloc (0xFF); idx1 = Device (0x00)
        Magic.writeTCR_EL1(0x2_0080_3520L);               // T0SZ=32, 4KiB, IRGN/ORGN=WB, SH0=inner, EPD1, IPS=40-bit
        Magic.writeTTBR0_EL1(PT_BASE);
        Magic.dsb();
        Magic.tlbiAll();
        Magic.dsb();
        Magic.isb();
        Magic.writeSCTLR_EL1(0x30D0_1805L);               // EmitBoot base 0x30D00800 | M(1) | C(4) | I(0x1000)
        Magic.isb();
    }

    /** Clean the D-cache lines spanning [start,end) to the point of coherence, then barrier -- so an
     *  uncached agent (a secondary core with its MMU still off) sees what the primary just wrote. */
    static void cleanToPoC(long start, long end)
    {
        long a = start & ~63L;                             // Cortex-A72 cache line = 64 bytes
        while (a < end)
        {
            Magic.dcCVAC(a);
            a = a + 64L;
        }
        Magic.dsb();
    }

    /**
     * Secondary-core entry (reached by the boot stub at EL1, on a per-core stack, with its MMU still off).
     * Enable the identity-mapped MMU first -- so this core is cache-coherent with the others before it
     * touches any shared memory -- then report in, wait for GO, and join the shared work queue. Then park.
     */
    static void secondaryMain(int core)
    {
        enableMmuThisCore();                               // caches on: coherent with the primary + the lock
        Magic.store64(CORE_FLAGS + core * 8L, core);       // report in (the primary counts these)
        Magic.dsb();
        while (Magic.load64(CORE_FLAGS) == 0L)             // wait for the primary's GO (slot 0)
        {
        }
        smpWork(core);                                     // pull jobs from the shared queue under the lock
        while (pcGo2 == 0)                                 // wait for the per-core-timer demo to start
        {
        }
        pcCoreMain(core);                                  // this core runs its own preemptive timer
        while (true)                                       // done: park
        {
            Magic.wfe();
        }
    }

    // ----- SMP mutual exclusion: a hardware spinlock -----------------------------------------------
    // Now that the MMU maps RAM as Normal Inner-Shareable cacheable, the exclusive monitor works, so a
    // real LDAXR/STLXR test-and-set spinlock (Magic.spinLock/spinUnlock over the word at LOCK_ADDR)
    // gives mutual exclusion across all four cores. That the demo below distributes 24 jobs with no
    // duplicates is the proof the atomics -- and therefore the cache-coherent MMU map -- are working.

    static int smpJob;                                     // shared job counter (the "run queue")
    static final int SMP_NJOBS = 24;

    /**
     * The SMP work loop each core runs: take the next job from the shared counter under the spinlock (so
     * no two cores get the same one), and print which core ran which job. The whole "grab + print" is the
     * critical section, so the output lines are clean and the jobs are distributed across the cores.
     */
    static void smpWork(int core)
    {
        while (true)
        {
            Magic.spinLock(LOCK_ADDR);                     // short critical section: just grab a job number
            int n = smpJob;
            if (n >= SMP_NJOBS)
            {
                Magic.spinUnlock(LOCK_ADDR);
                return;
            }
            smpJob = n + 1;
            Magic.spinUnlock(LOCK_ADDR);                   // release BEFORE the slow UART write, so the lock's
                                                           // cache line is free and other cores get a turn
            long cnt = CORE_FLAGS + 0x40L + core * 8L;     // this core's own job count (no contention)
            Magic.store64(cnt, Magic.load64(cnt) + 1L);
            Uart.putc((byte) (0x30 + core));               // core id: interleaves like the concurrent demo
        }
    }

    static void bringUpSecondaries()
    {
        if (secondaryMainAddr == 0L) { secondaryMain(0); } // dead call: compile + stash secondaryMain
        Magic.store32(LOCK_ADDR, 0);                       // HW spinlock word = free (cacheable RAM, MMU on)
        smpJob = 0;
        Magic.store64(CORE_FLAGS + 0L, 0L);                // GO = 0 (released after we report cores up)
        Magic.store64(CORE_FLAGS + 8L, 0L);                // clear the report flags for cores 1..3
        Magic.store64(CORE_FLAGS + 16L, 0L);
        Magic.store64(CORE_FLAGS + 24L, 0L);
        Magic.store64(CORE_FLAGS + 0x40L, 0L);             // clear per-core job counts (0x40 + core*8)
        Magic.store64(CORE_FLAGS + 0x48L, 0L);
        Magic.store64(CORE_FLAGS + 0x50L, 0L);
        Magic.store64(CORE_FLAGS + 0x58L, 0L);

        if (!SMP_ENABLED)                                  // leave cores 1-3 parked: single-core heap for reclaim
        {
            Uart.write(Magic.bytes("SMP: 1 of 4 cores up (SMP disabled)\n"));
            return;
        }

        // Stub (runs on each secondary at EL2): x0 = MPIDR & 3 (core id, becomes secondaryMain's arg),
        // set the per-core EL1 stack + a sane EL1 SCTLR, then drop EL2 -> EL1 (mirroring EmitBoot) and
        // ERET straight into secondaryMain(core). x0 survives the ERET.
        long s = SEC_STUB;
        int lo = (int) (secondaryMainAddr & 0xFFFFL);
        int mid = (int) ((secondaryMainAddr >>> 16) & 0xFFFFL);
        int hi = (int) ((secondaryMainAddr >>> 32) & 0xFFFFL);
        int w = 0;
        Magic.store32(s + w * 4L, A64Enc.mrs(0, A64Enc.MPIDR_EL1));      w += 1;
        Magic.store32(s + w * 4L, A64Enc.movz(9, 3, 0));                w += 1;   // x9 = 3
        Magic.store32(s + w * 4L, A64Enc.andReg(0, 0, 9));             w += 1;   // x0 = core id
        Magic.store32(s + w * 4L, A64Enc.lslImm(1, 0, 20));           w += 1;   // x1 = core * 1MiB
        Magic.store32(s + w * 4L, A64Enc.movz(2, SEC_STACK_HI, 1));    w += 1;   // x2 = 0x0200_0000
        Magic.store32(s + w * 4L, A64Enc.addReg(2, 2, 1));           w += 1;   // x2 = per-core stack top
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.SP_EL1, 2));      w += 1;   // SP_EL1 = stack top
        Magic.store32(s + w * 4L, A64Enc.movz(3, 0x0800, 0));         w += 1;
        Magic.store32(s + w * 4L, A64Enc.movk(3, 0x30D0, 1));         w += 1;   // x3 = 0x30D0_0800
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.SCTLR_EL1, 3));   w += 1;   // EL1 SCTLR (MMU off, RES1)
        Magic.store32(s + w * 4L, A64Enc.movz(3, 0x8000, 1));         w += 1;
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.HCR_EL2, 3));     w += 1;   // HCR_EL2.RW = 1
        Magic.store32(s + w * 4L, A64Enc.movz(3, 0x33FF, 0));         w += 1;
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.CPTR_EL2, 3));    w += 1;   // don't trap FP to EL2
        Magic.store32(s + w * 4L, A64Enc.movz(3, 0x03C5, 0));         w += 1;
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.SPSR_EL2, 3));    w += 1;   // target = EL1h, DAIF masked
        Magic.store32(s + w * 4L, A64Enc.movz(3, lo, 0));             w += 1;
        Magic.store32(s + w * 4L, A64Enc.movk(3, mid, 1));            w += 1;
        Magic.store32(s + w * 4L, A64Enc.movk(3, hi, 2));            w += 1;   // x3 = &secondaryMain
        Magic.store32(s + w * 4L, A64Enc.msr(A64Enc.ELR_EL2, 3));     w += 1;
        Magic.store32(s + w * 4L, A64Enc.eret());                   w += 1;   // -> secondaryMain(core) at EL1
        Heap.publishCode(s, s + w * 4L);
        cleanToPoC(s, s + w * 4L);                         // secondaries fetch the stub uncached (MMU off) ...

        Magic.store64(0x00E0L, s);                         // spin_cpu1 -> stub
        Magic.store64(0x00E8L, s);                         // spin_cpu2 -> stub
        Magic.store64(0x00F0L, s);                         // spin_cpu3 -> stub
        cleanToPoC(0x00C0L, 0x0100L);                      // ... and read the spin slots uncached: push both to PoC
        Magic.sev();                                       // wake the WFE-parked cores

        int up = 0;                                        // wait (<=0.5 s) for them to report in
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 2L;
        while (up < 3 && Magic.readCNTPCT_EL0() < deadline)
        {
            up = 0;
            if (Magic.load64(CORE_FLAGS + 8L) != 0L)  { up = up + 1; }
            if (Magic.load64(CORE_FLAGS + 16L) != 0L) { up = up + 1; }
            if (Magic.load64(CORE_FLAGS + 24L) != 0L) { up = up + 1; }
        }
        Uart.write(Magic.bytes("SMP: "));
        printDec(up + 1);                                  // + the primary
        Uart.write(Magic.bytes(" of 4 cores up\n"));
    }

    // ===== M7: preemptive scheduling on the timer tick =====================================
    // A task table (parallel arrays) with per-task state. Task 0 is the boot flow; spawn() adds more,
    // each on its own heap stack. The periodic timer IRQ vectors to a context-switch stub that saves
    // the full interrupted context (x0..x30, ELR_EL1, SPSR_EL1) onto the current task's stack, calls
    // schedule() -- which wakes due sleepers and picks the next READY task round-robin -- then restores
    // THAT task's context and ERETs into it. A task's saved SP is its whole context, so a switch is a
    // stack swap. sleep(ms) marks a task SLEEPING so the scheduler skips it until its deadline.

    static final long SCHED_FRAME = 272L;   // 31 GP regs + ELR + SPSR, 16-byte aligned (34 * 8)
    static final int  MAX_TASKS = 40;       // boot + demo/philosopher tasks + nested-thread tests (slots aren't reused)
    static final int  NUM_SEM = 16;         // reserved 0..4 (M7/console/wifi) + dynamically-allocated forks
    public static final int  WIFI_SEM = 4;  // posted by the SDIO RX ISR when a WiFi frame arrives
    static final int  SEM_RESERVED = 5;     // dynamic semaphores (forks) allocate at/after this index
    static int nextSem = SEM_RESERVED;      // next free semaphore index (newSem hands these out)
    static long runTrampAddr;               // Loader-built stub: invokeinterface Runnable.run() on x0, then taskExit
    static final int  TASK_EMPTY = 0;
    static final int  TASK_READY = 1;
    static final int  TASK_SLEEPING = 2;    // waiting on a CNTPCT deadline (sleep)
    static final int  TASK_BLOCKED = 3;     // waiting on a semaphore (an event another task/ISR posts)

    // The task table (parallel arrays, index = task id). Task 0 is the boot flow; spawn() adds more.
    static long[] taskSp;                   // saved context frame (SP) — the task's whole context
    static long[] taskStackBase;            // its heap stack's object base (a GC root keeps the stack alive)
    static int[]  taskState;                // TASK_READY / TASK_SLEEPING / TASK_BLOCKED / TASK_EMPTY
    static long[] taskWake;                 // CNTPCT deadline at which a sleeping task becomes ready again
    static int[]  taskWaitOn;               // for a BLOCKED task: the semaphore index it is waiting on (-1 = an object monitor)
    static long[] taskWaitObj;              // for a BLOCKED object-monitor waiter: the object it Object.wait()s on (0 = none)
    static int[]  taskDone;                 // 1 once a task ran taskExit() (its run() returned) — Thread.join() polls this
    static long[] taskThreadObj;            // M4: the guest java/lang/Thread of each task (0 until known/lazily wrapped)
    static int[]  taskInterrupted;          // Thread.interrupt() flag per task (1 = interrupted; sleep/join observe it)
    static int[]  taskPermit;               // LockSupport permit per task (1 = a pending unpark; park consumes it)
    static long[] taskMonWait;              // for a task BLOCKED on monitorenter: the object it is trying to lock (0 = none)
    static int[]  semCount;                 // counting-semaphore values
    static int    taskCount;                // number of live task slots
    static int    curTask;                  // the task currently running

    // Object monitors (real, ownership-tracking + recursive). A side table indexed by locked object; a slot is
    // live while its object is held (monCount >= 1) and freed on the final monitorExit, so it holds only the
    // currently-held monitors. monitorenter/exit and Object.wait/notify + Thread.holdsLock all go through it.
    static final int MAX_MON = 64;
    static long[] monObj;                   // the locked object (0 = free slot)
    static int[]  monOwner;                 // owning task id
    static int[]  monCount;                 // recursion depth


    /**
     * The heart of the switcher, shared by the timer path ({@link #schedule}) and the yield path
     * ({@link #yieldPick}): save the interrupted task's frame pointer, wake any sleeper whose deadline
     * has passed, then round-robin to the next READY task and return its saved frame (the stub loads it
     * as SP before restoring). Task 0 never sleeps, so one task is always ready. Runs with IRQs masked.
     */
    static long pickNext(long curSp)
    {
        taskSp[curTask] = curSp;                            // save the interrupted/yielding task
        long now = Magic.readCNTPCT_EL0();
        int i = 0;
        while (i < taskCount)                              // wake expired sleepers + timed-out blocked waiters
        {
            if (taskState[i] == TASK_SLEEPING && now >= taskWake[i])
            {
                taskState[i] = TASK_READY;
                taskWake[i] = 0L;                          // clear so a later BLOCKED wait isn't deadline-woken
            }
            else if (taskState[i] == TASK_BLOCKED && taskWake[i] != 0L && now >= taskWake[i])
            {
                taskState[i] = TASK_READY;                 // semWaitTimeout deadline passed: wake to return false
                taskWake[i] = 0L;
            }
            i = i + 1;
        }
        int n = curTask;
        int k = 0;
        while (k < taskCount)                              // pick the next READY task, round-robin
        {
            n = n + 1;
            if (n >= taskCount) { n = 0; }
            if (taskState[n] == TASK_READY)
            {
                curTask = n;
                return taskSp[n];
            }
            k = k + 1;
        }
        return curSp;                                      // nothing else ready: resume the current task
    }

    /**
     * IRQ path (called by the IRQ context-switch stub): acknowledge the interrupt and act on it, then
     * pick the next task. Handles the periodic timer (PPI 30) and the mini-UART RX (SPI 125): the UART
     * ISR drains received bytes into the ring buffer and posts UART_SEM to wake the blocked reader.
     */
    static long schedule(long curSp)
    {
        int id = Gic.acknowledge();                        // GICC_IAR
        if (id == Gic.PPI_CNTPNS)                          // periodic timer
        {
            ticks = ticks + 1L;
            Magic.writeCNTP_TVAL_EL0(timerReload);         // re-arm the next tick
            Gic.end(id);
            return pickNext(curSp);
        }
        if (id == Bcm2711.AUX_SPI)                         // mini-UART (shared RX/TX) -> the Console device
        {
            if (Console.onIrq())                           // drains RX+TX; true if RX bytes arrived
            {
                semPostRaw(Console.RX_SEM);                // wake a blocked reader (ISR-safe post)
            }
            Gic.end(id);
            return pickNext(curSp);
        }
        if (id == Bcm2711.SDIO_SPI)                        // WiFi SDIO card interrupt -> the CYW43 driver
        {
            if (board.cyw43.Cyw43.onIrq())                 // ISR: the chip has an F2 frame for us
            {
                semPostRaw(WIFI_SEM);
            }
            Gic.end(id);
            return pickNext(curSp);
        }
        if (id != 0x3FF) { Gic.end(id); }                 // other/real INTID: EOI, don't switch
        return curSp;
    }

    /** SVC (yield) path: no timer to service, just pick the next task. Called by the SVC handler stub. */
    static long yieldPick(long curSp)
    {
        return pickNext(curSp);
    }

    /** Voluntarily give up the CPU now: trap to the SVC handler, which switches to the next READY task. */
    static void taskYield()
    {
        Magic.svc();
    }

    /**
     * Sleep at least {@code ms}: mark this task SLEEPING with a wake deadline, then yield repeatedly.
     * The scheduler skips a SLEEPING task and only flips it back to READY once its deadline passes, so
     * this parks the task without busy-waiting (the yield saves our context; we aren't rescheduled until
     * woken). Uses yield (not WFE) so it works whether or not the timer is delivering.
     */
    static void sleep(long ms)
    {
        int me = curTask;
        long freq = Magic.readCNTFRQ_EL0();
        if (ms > 9223372036854775807L / freq)              // freq*ms would overflow: never time out, only interrupt
        {                                                  //   (Thread.sleep(Long.MAX_VALUE) waits until interrupted)
            taskWake[me] = 9223372036854775807L;
        }
        else
        {
            taskWake[me] = Magic.readCNTPCT_EL0() + freq * ms / 1000L;
        }
        taskState[me] = TASK_SLEEPING;
        while (taskState[me] == TASK_SLEEPING)
        {
            if (taskInterrupted[me] != 0)                  // Thread.interrupt(): wake early (Thread.sleep then throws)
            {
                taskState[me] = TASK_READY;
                return;
            }
            taskYield();
        }
    }

    /** {@code Thread.interrupt()}: set the interrupt flag and wake the task if it is sleeping or blocked. */
    static void interrupt(long threadObj)
    {
        int tid = threadTaskOf(threadObj);
        if (tid < 0)
        {
            return;
        }
        Magic.disableIrq();
        taskInterrupted[tid] = 1;
        if (taskState[tid] == TASK_SLEEPING || taskState[tid] == TASK_BLOCKED)
        {
            taskState[tid] = TASK_READY;                   // let it observe the interrupt (sleep returns / wait wakes)
        }
        Magic.enableIrq();
    }

    /** {@code Thread.isInterrupted()}: the interrupt flag of {@code threadObj} (does NOT clear it). */
    static int isInterrupted(long threadObj)
    {
        int tid = threadTaskOf(threadObj);
        return (tid >= 0 && taskInterrupted[tid] != 0) ? 1 : 0;
    }

    /** Read + CLEAR the current task's interrupt flag — Thread.sleep uses this to throw InterruptedException once. */
    static int checkClearInterrupt()
    {
        int r = taskInterrupted[curTask];
        taskInterrupted[curTask] = 0;
        return r;
    }

    /** {@code Thread.isAlive()}: 1 if {@code threadObj} has been started and its run() has not yet returned. */
    static int isAlive(long threadObj)
    {
        int tid = threadTaskOf(threadObj);
        return (tid >= 0 && taskDone[tid] == 0) ? 1 : 0;
    }

    /**
     * {@code Thread.join(Duration)} core: wait up to {@code millis} for {@code threadObj} to terminate. Returns
     * 3 = not started (NEW -> IllegalThreadStateException), 1 = terminated (true), 2 = interrupted (clears the
     * flag -> InterruptedException), 0 = timed out (false). {@code millis<=0} does not wait.
     */
    static int joinTimed(long threadObj, long millis)
    {
        int tid = threadTaskOf(threadObj);
        if (tid < 0)
        {
            return 3;                                      // NEW / never started
        }
        if (taskDone[tid] != 0)
        {
            return 1;                                      // already TERMINATED
        }
        if (millis <= 0L)
        {
            return 0;                                      // no wait, not done
        }
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * millis / 1000L;
        while (Magic.readCNTPCT_EL0() < deadline)
        {
            if (taskInterrupted[curTask] != 0)
            {
                taskInterrupted[curTask] = 0;              // join clears the interrupt status when it throws
                return 2;
            }
            if (taskDone[tid] != 0)
            {
                return 1;
            }
            taskYield();
        }
        return (taskDone[tid] != 0) ? 1 : 0;               // final check at the deadline
    }

    /** {@code LockSupport.park()}: block the current task until a permit is available (an {@link #unpark}). */
    static void park()
    {
        Magic.disableIrq();
        int me = curTask;
        while (taskPermit[me] == 0)
        {
            taskWaitOn[me] = -3;                           // a park waiter
            taskState[me] = TASK_BLOCKED;
            Magic.enableIrq();
            taskYield();
            Magic.disableIrq();
        }
        taskPermit[me] = 0;                                // consume the permit
        Magic.enableIrq();
    }

    /** {@code LockSupport.unpark(t)}: make a permit available for {@code threadObj} and wake it if parked. */
    static void unpark(long threadObj)
    {
        int tid = threadTaskOf(threadObj);
        if (tid < 0)
        {
            return;
        }
        Magic.disableIrq();
        taskPermit[tid] = 1;
        if (taskState[tid] == TASK_BLOCKED && taskWaitOn[tid] == -3)
        {
            taskState[tid] = TASK_READY;
        }
        Magic.enableIrq();
    }

    /**
     * Wait on counting semaphore {@code s}: consume a token if one is available, else block this task
     * (TASK_BLOCKED) until another task or an ISR {@link #semPost}s it, then re-check. IRQs are masked
     * across the test-and-block so a post can't slip in between (lost-wakeup race); yield/SVC still works
     * with IRQs masked, and each task resumes with its own PSTATE, so masking here is safe.
     */
    public static void semWait(int s)
    {
        Magic.disableIrq();
        taskWake[curTask] = 0L;                            // no deadline: pickNext must not spuriously wake us
        while (semCount[s] <= 0)
        {
            taskState[curTask] = TASK_BLOCKED;
            taskWaitOn[curTask] = s;
            Magic.enableIrq();
            taskYield();                                   // blocked: the scheduler skips us until posted
            Magic.disableIrq();
        }
        semCount[s] = semCount[s] - 1;
        Magic.enableIrq();
    }

    /**
     * Like {@link #semWait} but bounded: consume a token if one arrives within {@code ms} milliseconds and
     * return true, else wake at the CNTPCT deadline and return false. Implemented by blocking on the
     * semaphore <em>and</em> recording a deadline in {@code taskWake}; {@link #pickNext} wakes the task when
     * a post arrives (via {@link #semPostRaw}) or the deadline passes. Used by the WiFi RX loops so a lost
     * frame times out instead of hanging forever (the polling loops it replaces had per-attempt timeouts).
     */
    public static boolean semWaitTimeout(int s, long ms)
    {
        long deadline = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * ms / 1000L;
        Magic.disableIrq();
        while (semCount[s] <= 0)
        {
            if (Magic.readCNTPCT_EL0() >= deadline)
            {
                taskWake[curTask] = 0L;
                Magic.enableIrq();
                return false;                              // timed out with no token
            }
            taskState[curTask] = TASK_BLOCKED;
            taskWaitOn[curTask] = s;
            taskWake[curTask] = deadline;                  // also become ready at the deadline
            Magic.enableIrq();
            taskYield();
            Magic.disableIrq();
        }
        taskWake[curTask] = 0L;                            // got a token: drop the deadline marker
        semCount[s] = semCount[s] - 1;
        Magic.enableIrq();
        return true;
    }

    /** The core of {@link #semPost}, without touching IRQ masking — safe to call from an ISR. */
    static void semPostRaw(int s)
    {
        semCount[s] = semCount[s] + 1;
        int i = 0;
        while (i < taskCount)
        {
            if (taskState[i] == TASK_BLOCKED && taskWaitOn[i] == s)
            {
                taskState[i] = TASK_READY;                 // woken; it re-checks the count when it runs
                i = taskCount;                             // wake just one waiter
            }
            else
            {
                i = i + 1;
            }
        }
    }

    /** Post (signal) semaphore {@code s} from task context: add a token and wake one waiter. */
    static void semPost(int s)
    {
        Magic.disableIrq();
        semPostRaw(s);
        Magic.enableIrq();
    }

    // ----- Object monitors: java.lang.Object.wait/notify/notifyAll. The monitor itself is a no-op lock on this
    // mostly-cooperative scheduler (monitorenter/exit are no-ops); wait/notify give the task hand-off. wait()
    // parks the caller on the object (TASK_BLOCKED, taskWaitOn=-1 so no semPost wakes it) until another task
    // notify()s the SAME object; masking IRQs across the test-and-block avoids a lost wakeup like semWait.

    /** Index of {@code obj}'s live monitor slot, or -1. */
    private static int monSlotOf(long obj)
    {
        int i = 0;
        while (i < MAX_MON)
        {
            if (monObj[i] == obj)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Claim a free monitor slot for {@code obj} (halts if more than MAX_MON are held at once). */
    private static int monAlloc(long obj)
    {
        int i = 0;
        while (i < MAX_MON)
        {
            if (monObj[i] == 0L)
            {
                monObj[i] = obj;
                return i;
            }
            i += 1;
        }
        Uart.write(Magic.bytes("\nMONITOR TABLE FULL\n"));
        while (true)
        {
            Magic.wfe();
        }
    }

    /** Wake ONE task blocked in monitorenter for {@code obj}. */
    private static void wakeMonWaiter(long obj)
    {
        int i = 0;
        while (i < taskCount)
        {
            if (taskState[i] == TASK_BLOCKED && taskMonWait[i] == obj)
            {
                taskState[i] = TASK_READY;
                i = taskCount;                             // just one
            }
            else
            {
                i = i + 1;
            }
        }
    }

    /** monitorenter {@code obj}: acquire its monitor -- recursive for the owner, blocking while another task holds it. */
    static void monEnter(long obj)
    {
        if (obj == 0L)
        {
            return;                                        // the JIT null-checked before the call
        }
        Magic.disableIrq();
        int s = monSlotOf(obj);
        while (s >= 0 && monOwner[s] != curTask)           // held by another task: block until it is released
        {
            taskMonWait[curTask] = obj;
            taskWaitOn[curTask] = -2;                       // a monitor-acquire waiter
            taskState[curTask] = TASK_BLOCKED;
            Magic.enableIrq();
            taskYield();
            Magic.disableIrq();
            s = monSlotOf(obj);
        }
        taskMonWait[curTask] = 0L;
        if (s < 0)                                         // unowned: take a fresh slot
        {
            s = monAlloc(obj);
            monOwner[s] = curTask;
            monCount[s] = 0;
        }
        monCount[s] += 1;                                  // acquire / recurse
        Magic.enableIrq();
    }

    /** monitorexit {@code obj}: release one level; on the final release free the slot and wake one acquire-waiter. */
    static void monExit(long obj)
    {
        if (obj == 0L)
        {
            return;
        }
        Magic.disableIrq();
        int s = monSlotOf(obj);
        if (s >= 0)
        {
            monCount[s] -= 1;
            if (monCount[s] <= 0)
            {
                monObj[s] = 0L;
                monOwner[s] = -1;
                monCount[s] = 0;
                wakeMonWaiter(obj);
            }
        }
        Magic.enableIrq();
    }

    /** {@code Thread.holdsLock(obj)}: 1 if the current task owns {@code obj}'s monitor, else 0. */
    static int holdsLock(long obj)
    {
        Magic.disableIrq();
        int s = monSlotOf(obj);
        int r = (s >= 0 && monOwner[s] == curTask) ? 1 : 0;
        Magic.enableIrq();
        return r;
    }

    /**
     * {@code obj.wait(ms)} (ms<=0 = wait forever): block the current task until another task notifies {@code obj}.
     * Per Java rules the caller holds {@code obj}'s monitor; wait RELEASES it (remembering the recursion depth) so
     * a notifier can enter the same monitor, then REACQUIRES it before returning.
     */
    static void objWait(long obj, long ms)
    {
        Magic.disableIrq();
        int me = curTask;
        int s = monSlotOf(obj);                            // release the monitor we hold (if any)
        int saved = (s >= 0 && monOwner[s] == me) ? monCount[s] : 0;
        if (saved > 0)
        {
            monObj[s] = 0L;
            monOwner[s] = -1;
            monCount[s] = 0;
            wakeMonWaiter(obj);
        }
        taskWaitObj[me] = obj;
        taskWaitOn[me] = -1;                               // an object monitor, not a semaphore
        if (ms > 0L)
        {
            taskWake[me] = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() * ms / 1000L;   // also wake at the deadline
        }
        else
        {
            taskWake[me] = 0L;                             // no deadline: only notify wakes us
        }
        taskState[me] = TASK_BLOCKED;
        Magic.enableIrq();
        taskYield();                                       // parked until objNotify/objNotifyAll flips us READY
        taskWaitObj[me] = 0L;
        taskWake[me] = 0L;
        if (saved > 0)                                     // reacquire the monitor (blocks if the notifier still holds it)
        {
            monEnter(obj);
            monCount[monSlotOf(obj)] = saved;              // restore the recursion depth
        }
    }

    /** {@code obj.notify()}: wake ONE task waiting on {@code obj}. */
    static void objNotify(long obj)
    {
        Magic.disableIrq();
        int i = 0;
        while (i < taskCount)
        {
            if (taskState[i] == TASK_BLOCKED && taskWaitObj[i] == obj)
            {
                taskState[i] = TASK_READY;
                i = taskCount;                             // just one
            }
            else
            {
                i = i + 1;
            }
        }
        Magic.enableIrq();
    }

    /** {@code obj.notifyAll()}: wake EVERY task waiting on {@code obj}. */
    static void objNotifyAll(long obj)
    {
        Magic.disableIrq();
        int i = 0;
        while (i < taskCount)
        {
            if (taskState[i] == TASK_BLOCKED && taskWaitObj[i] == obj)
            {
                taskState[i] = TASK_READY;
            }
            i = i + 1;
        }
        Magic.enableIrq();
    }

    /** The task id whose java/lang/Thread object is {@code threadObj}, or -1 if none is live. */
    static int threadTaskOf(long threadObj)
    {
        int i = 0;
        while (i < taskCount)
        {
            if (taskThreadObj[i] == threadObj)
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /** {@code thread.join()}: block until the task running {@code threadObj}'s run() has returned (taskExit). */
    static void threadJoin(long threadObj)
    {
        int tid = threadTaskOf(threadObj);
        if (tid < 0)
        {
            return;                                        // not a live spawned thread: nothing to wait for
        }
        while (taskDone[tid] == 0)
        {
            taskYield();                                   // yield-poll: each yield also drives pickNext's wakeups
        }
    }

    /**
     * {@code thread.getStackTrace()} -> a {@code StackTraceElement[]} for {@code threadObj}'s stack. If it is the
     * CALLING thread, walk from the call-site {@code pc}/{@code sp} the JIT captured (the top frame is well-formed).
     * Otherwise the thread is parked in {@link #taskYield}: its context is the saved switch frame -- start from the
     * saved ELR/SP, handing {@link Loader#buildTrace} the saved x30 so it can step over the leaf {@code taskYield}
     * frame. Returns 0-length if the thread isn't a live task.
     */
    static long threadStackTrace(long threadObj, long pc, long sp)
    {
        int tid = threadTaskOf(threadObj);
        if (tid == curTask || tid < 0)                     // the calling thread (or unknown): walk from here
        {
            return Loader.buildTrace(pc, sp, 0L);
        }
        if (taskDone[tid] == 1)                            // a terminated thread has no stack (matches the JDK: a
        {                                                  // getStackTrace() on a finished thread returns length 0)
            return Loader.buildTrace(0L, 0L, 0L);
        }
        long elr = Magic.load64(taskSp[tid] + 248L);       // saved ELR: the yielded PC (inside taskYield)
        long ssp = taskSp[tid] + SCHED_FRAME;              // SP at the yield
        long x30 = Magic.load64(taskSp[tid] + 240L);       // saved x30: caller-of-taskYield (steps over the leaf)
        return Loader.buildTrace(elr, ssp, x30);
    }

    static long threadStackTraceAddr;  // VM.threadStackTrace(JJJ)J — a StackTraceElement[] for a Thread's stack

    /** Thread.getAllStackTraces() helper: a {@code Thread[]} of every live task's java/lang/Thread object (skips
     *  tasks with no Thread object and exited tasks). The guest overlay pairs each with its getStackTrace(). */
    static long allThreads()
    {
        int count = 0;
        int i = 0;
        while (i < taskCount)
        {
            if (taskThreadObj[i] != 0L && taskDone[i] == 0)
            {
                count += 1;
            }
            i += 1;
        }
        long arr = Heap.allocArray(count, 8);              // Thread[count] (null TIB: iterated by index)
        int j = 0;
        i = 0;
        while (i < taskCount)
        {
            if (taskThreadObj[i] != 0L && taskDone[i] == 0)
            {
                Magic.store64(arr + 24L + j * 8L, taskThreadObj[i]);
                j += 1;
            }
            i += 1;
        }
        return arr;
    }

    static long allThreadsAddr;        // VM.allThreads()J — a Thread[] of every live task's Thread object

    /** Demo task 1: print 'A', then voluntarily yield() the CPU (cooperative — works without the timer). */
    static void taskA()
    {
        while (true)
        {
            Uart.putc(0x41);
            taskYield();
        }
    }

    /** Demo task 2 (producer): print 'B', post semaphore 0 to wake the consumer, then sleep ~40 ms. */
    static void taskB()
    {
        while (true)
        {
            Uart.putc(0x42);
            semPost(0);
            sleep(40L);
        }
    }

    /** Demo task 3 (consumer): block on semaphore 0 until the producer posts, then print 'C'. */
    static void taskC()
    {
        while (true)
        {
            semWait(0);
            Uart.putc(0x43);
        }
    }

    /**
     * Demo task 4 (interactive shell over the {@link Console} device): read a line (blocking on the RX
     * interrupt, echoing as you type), then write it back with a prefix. All I/O goes through Console --
     * this task never touches the UART, blocks while idle, and its writes leave via the TX interrupt.
     */
    static void taskR()
    {
        byte[] line = new byte[64];
        while (true)
        {
            Console.write(Magic.bytes("> "));
            int n = Console.readLine(line, 64);
            Console.write(Magic.bytes("you said: "));
            Console.write(line, 0, n);
            Console.putc((byte) 0x0A);
        }
    }

    /** ~5 ms pause (counter-based, survives preemption) so the main task prints at a visible rate. */
    static void schedPause()
    {
        long end = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 200L;
        while (Magic.readCNTPCT_EL0() < end)
        {
        }
    }

    /** Create a READY task running {@code entry} on a fresh 32 KiB stack; returns its task id. */
    /** A terminated task slot (ran taskExit -> taskDone) whose stack + slot can be recycled, or -1 if none. */
    static int reuseSlot()
    {
        int s = 0;
        while (s < taskCount)
        {
            if (taskDone[s] == 1)                          // taskExit ran: never scheduled again, slot is free
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    static int spawn(long entry)
    {
        int id;
        Magic.disableIrq();
        if (taskCount < MAX_TASKS)
        {
            id = taskCount;                                // fast path: a fresh slot (unchanged for <=MAX_TASKS threads)
            taskCount = id + 1;
        }
        else
        {
            id = reuseSlot();                              // table full: recycle a terminated task's slot + stack
            while (id < 0)
            {
                Magic.enableIrq();                         // nothing done yet: let a task run to taskExit, then retry
                taskYield();
                Magic.disableIrq();
                id = reuseSlot();
            }
        }
        taskDone[id] = 0;                                  // claim: no longer reusable, and
        taskState[id] = TASK_BLOCKED;                      //   not runnable until fully set up below
        Magic.enableIrq();
        long stk = taskStackBase[id];
        if (stk == 0L)
        {
            stk = Heap.alloc(0x8000);
            taskStackBase[id] = stk;                       // object base: keeps the stack GC-reachable
        }
        long top = (stk + 0x8000L) & ~0xFL;                // 16-byte aligned top; stack grows down
        long frame = top - SCHED_FRAME;                    // synthetic initial context frame
        int r = 0;
        while (r <= 30)
        {
            Magic.store64(frame + r * 8L, 0L);             // x0..x30 = 0
            r += 1;
        }
        Magic.store64(frame + 248L, entry);                // ELR_EL1 = task entry PC
        Magic.store64(frame + 256L, 0x5L);                 // SPSR_EL1 = EL1h (M=0b0101), DAIF clear -> IRQs on
        taskSp[id] = frame;
        taskWake[id] = 0L;                                 // reset per-task state (matters when recycling a slot)
        taskWaitOn[id] = 0;
        taskWaitObj[id] = 0L;
        taskThreadObj[id] = 0L;
        taskInterrupted[id] = 0;
        taskPermit[id] = 0;
        taskMonWait[id] = 0L;
        taskState[id] = TASK_READY;                        // now runnable
        return id;
    }

    /** Like {@link #spawn}, but the task starts with {@code arg0} in x0 (so it can enter an instance method). */
    static int spawnArg(long entry, long arg0)
    {
        int id = spawn(entry);
        Magic.store64(taskSp[id] + 0L, arg0);              // x0 slot of the initial frame = receiver
        return id;
    }

    /**
     * Start a task that runs the {@code java/lang/Runnable} at {@code runnable}: it enters the Loader-built
     * run-trampoline ({@link #runTrampAddr}) with the receiver in x0, which invokeinterface-dispatches
     * {@code run()} and, when it returns, calls {@link #taskExit}. Called from JIT'd guest code via
     * {@code magic.spawn} (the mini {@code java.lang.Thread.start()}).
     */
    static void startThread(long runnable)
    {
        int id = spawnArg(runTrampAddr, runnable);
        taskThreadObj[id] = runnable;                      // guest Thread.start() spawns the Thread ITSELF (M4):
    }                                                      //   record it so currentThread() returns that object

    /** Allocate a fresh counting semaphore initialised to {@code initial}; returns its index (a "fork"). */
    static int newSem(int initial)
    {
        int s = nextSem;
        nextSem = s + 1;
        semCount[s] = initial;
        return s;
    }

    /** End the current task: park it BLOCKED on a never-posted semaphore so the scheduler skips it forever. */
    static void taskExit()
    {
        Magic.disableIrq();
        taskDone[curTask] = 1;                             // Thread.join() waiters observe this
        taskState[curTask] = TASK_BLOCKED;
        taskWaitOn[curTask] = 3;                           // the dead park semaphore (never posted)
        Magic.enableIrq();
        while (true)
        {
            taskYield();                                   // never rescheduled; yields the CPU permanently
        }
    }

    /** Emit one philosopher status line: {@code P<who> <verb>} (formatting kept image-side — no concat on metal). */
    static void philReport(int who, int state)
    {
        Uart.putc((byte) 0x50);                            // 'P'
        Uart.putc((byte) (0x30 + who));                    // philosopher id (single digit)
        Uart.putc((byte) 0x20);
        if (state == 0)      { Uart.write(Magic.bytes("thinks\n")); }
        else if (state == 1) { Uart.write(Magic.bytes("hungry\n")); }
        else if (state == 2) { Uart.write(Magic.bytes("EATS\n")); }
        else                 { Uart.write(Magic.bytes("done\n")); }
    }

    // ----- invokedynamic string-concat: a growable byte[] builder, driven by the JIT'd concat lowering.
    // A builder is a heap holder { value@16 = byte[] buf, count@24 = length so far }. The compiler emits
    // scStart, then scChar/scInt per recipe literal/arg, then scEnd -> a trimmed byte[] (wrapped in a
    // java/lang/String by the JIT). Kept image-side so the intrinsic bottoms out in one place.

    /** Begin a concat: a fresh builder over a 64-byte byte[]. */
    static long scStart()
    {
        long buf = Heap.allocArray(64, 1);
        long sb = Heap.alloc(32);
        Magic.store64(sb + 16L, buf);
        Magic.store64(sb + 24L, 0L);
        return sb;
    }

    /** Grow a builder's backing byte[] to twice {@code cap}, copying {@code count} bytes; returns the new buf. */
    static long scGrow(long sb, long buf, long count, long cap)
    {
        long nbuf = Heap.allocArray((int) (cap * 2L), 1);
        long i = 0L;
        while (i < count)
        {
            Magic.store8(nbuf + 24L + i, (byte) Magic.load8(buf + 24L + i));
            i = i + 1L;
        }
        Magic.store64(sb + 16L, nbuf);
        return nbuf;
    }

    /** Append one byte {@code c} to the builder. */
    static void scChar(long sb, int c)
    {
        long buf = Magic.load64(sb + 16L);
        long count = Magic.load64(sb + 24L);
        long cap = Magic.load64(buf + 16L);                // byte[] length (ARRAY_LENGTH_OFFSET)
        if (count >= cap)
        {
            buf = scGrow(sb, buf, count, cap);
        }
        Magic.store8(buf + 24L + count, (byte) c);         // ARRAY_BASE_OFFSET = 24
        Magic.store64(sb + 24L, count + 1L);
    }

    /** Append {@code v} in decimal to the builder. */
    static void scInt(long sb, int v)
    {
        if (v == 0)
        {
            scChar(sb, 0x30);
            return;
        }
        if (v < 0)
        {
            scChar(sb, 0x2D);                              // '-' (Integer.MIN_VALUE not special-cased)
            v = -v;
        }
        byte[] tmp = new byte[12];
        int n = 0;
        while (v > 0)
        {
            tmp[n] = (byte) (0x30 + v % 10);
            n = n + 1;
            v = v / 10;
        }
        while (n > 0)
        {
            n = n - 1;
            scChar(sb, tmp[n]);
        }
    }

    /** Finish a concat: a fresh byte[] trimmed to the builder's length. */
    // The current batch's [B array TIB (Loader-set after each loadAll; 0 between batches). scEnd types its
    // result with it so a concat-built String's value is a REAL typed byte[] -- stock code checkcasts/clones
    // String.value (Arrays.copyOf -> "[B".clone -> checkcast "[B" inside getBytes), and VM.checkCast rejects
    // raw (elem-size-header) arrays, which used to halt the first System.out print of a concat string.
    static long byteArrayTibCache;

    static long scEnd(long sb)
    {
        long buf = Magic.load64(sb + 16L);
        long count = Magic.load64(sb + 24L);
        long out = Heap.allocArray((int) count, 1);
        if (byteArrayTibCache != 0L)
        {
            Magic.store64(out, byteArrayTibCache);
        }
        long i = 0L;
        while (i < count)
        {
            Magic.store8(out + 24L + i, (byte) Magic.load8(buf + 24L + i));
            i = i + 1L;
        }
        return out;
    }

    /**
     * The byte[] behind a "string" ref: a raw byte[] (array TIB = 0) is itself; a mini java/lang/String
     * (TIB != 0) yields its {@code value} field (offset 16). Lets literals (interned byte[]) and concat
     * results (String objects) be used interchangeably wherever a string is expected.
     */
    static long strBytes(long ref)
    {
        // A byte[] is itself; a java/lang/String yields its value field (@16). A byte[]'s header @0 is either a
        // small raw element size (VM-internal buffers) or a pointer to an array TIB whose Type is tagged; a
        // String's @0 is a class TIB (Type not tagged).
        long tib = Magic.load64(ref + 0L);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return ref;                                 // raw byte[] (element size in @0)
        }
        if ((Magic.load64(Magic.load64(tib)) & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG)
        {
            return ref;                                 // typed byte[] (TIB[0]=array Type, Type[0] tagged)
        }
        return Magic.load64(ref + 16L);                 // a String object -> value field
    }

    /** Append a String/byte[] {@code ref}'s bytes to the concat builder. */
    static void scStr(long sb, long ref)
    {
        long arr = strBytes(ref);
        long len = Magic.load64(arr + 16L);
        long i = 0L;
        while (i < len)
        {
            scChar(sb, Magic.load8(arr + 24L + i));
            i = i + 1L;
        }
    }

    /** Append {@code v} in decimal to the concat builder. */
    static void scLong(long sb, long v)
    {
        if (v == 0L)
        {
            scChar(sb, 0x30);
            return;
        }
        if (v < 0L)
        {
            scChar(sb, 0x2D);                              // '-' (Long.MIN_VALUE not special-cased)
            v = -v;
        }
        byte[] tmp = new byte[24];
        int n = 0;
        while (v > 0L)
        {
            tmp[n] = (byte) (0x30 + (int) (v % 10L));
            n = n + 1;
            v = v / 10L;
        }
        while (n > 0)
        {
            n = n - 1;
            scChar(sb, tmp[n]);
        }
    }

    // ----- provided java.base natives (called by loaded guest code via Loader.nativeBuf) -----

    /**
     * {@code java/lang/System.nanoTime()} — a monotonic clock in ns, from the ARM generic timer. Scaled
     * multiply-FIRST (via a seconds/remainder split so {@code ticks*1e9} can't overflow): dividing
     * {@code 1e9/freq} first truncates badly at non-power-of-two frequencies (e.g. a real Pi 4's 54 MHz ->
     * 18 instead of 18.52, ~2.8% slow, so a 1 ms sleep mis-measures as 0 ms).
     */
    static long nanoTime()
    {
        long ticks = Magic.readCNTPCT_EL0();
        long freq = Magic.readCNTFRQ_EL0();
        return ticks / freq * 1000000000L + ticks % freq * 1000000000L / freq;
    }

    /**
     * Busy-wait at least {@code us} microseconds on the generic timer (CNTPCT). Unlike {@link #sleep} this
     * does NOT yield, so it is valid before the scheduler exists — which is where device bring-up runs
     * (WiFi/SDIO settle delays). Unlike {@code Emmc}'s iteration-count spins it is wall-clock accurate
     * regardless of CPU/cache state.
     */
    public static void delayUs(long us)
    {
        long end = Magic.readCNTPCT_EL0() + (Magic.readCNTFRQ_EL0() * us) / 1000000L;
        while (Magic.readCNTPCT_EL0() < end)
        {
        }
    }

    /** Busy-wait at least {@code ms} milliseconds (see {@link #delayUs}). */
    public static void delayMs(long ms)
    {
        delayUs(ms * 1000L);
    }

    /** {@code java/lang/System.currentTimeMillis()} — ms since boot (no wall clock on bare metal). */
    static long currentTimeMillis()
    {
        return Magic.readCNTPCT_EL0() / (Magic.readCNTFRQ_EL0() / 1000L);
    }

    /**
     * Identity native for the *ToRawBits / bitsTo* conversions ({@code Float.floatToRawIntBits},
     * {@code Float.intBitsToFloat}, {@code Double.doubleToRawLongBits}, {@code Double.longBitsToDouble}):
     * joe-ng already holds floats/doubles as raw bits in GP registers, so these are pass-throughs.
     */
    static long identity(long x)
    {
        return x;
    }

    /**
     * {@code java/lang/System.arraycopy(src, srcPos, dst, dstPos, len)} — the most-used java.base native.
     * The element size comes from the source array's header (TIB slot), so it's generic over element type;
     * the copy is byte-wise and overlap-safe (like {@code memmove}, as {@code arraycopy} requires).
     */
    /** Element size (bytes) of an array: a raw array keeps it in @0; a typed array keeps it in its array Type's tag. */
    static long elemSize(long arr)
    {
        long tib = Magic.load64(arr + 0L);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return tib;                                    // raw array: @0 is the element size
        }
        return Magic.load64(Magic.load64(tib)) & 0xFFFFL;  // typed: arr@0=TIB, TIB[0]=Type, Type[0]=tag|elemSize
    }

    static void arraycopy(long src, int srcPos, long dst, int dstPos, int len)
    {
        long es = elemSize(src);                            // element size (bytes), raw header or typed array Type
        long n = (long) len * es;
        long from = src + 24L + (long) srcPos * es;        // ARRAY_BASE_OFFSET = 24
        long to = dst + 24L + (long) dstPos * es;
        if (to <= from)                                    // forward copy (no clobber when dst <= src)
        {
            long i = 0L;
            while (i < n)
            {
                Magic.store8(to + i, (byte) Magic.load8(from + i));
                i = i + 1L;
            }
        }
        else                                               // backward copy (overlap: dst after src)
        {
            long i = n;
            while (i > 0L)
            {
                i = i - 1L;
                Magic.store8(to + i, (byte) Magic.load8(from + i));
            }
        }
    }

    /**
     * Allocate a mini {@code java/lang/NullPointerException} — the JIT calls this on a null deref, then
     * routes the object through the normal athrow/unwind. The Loader supplies its TIB (its Type chain is
     * what {@code catch} dispatch walks); the object is otherwise field-free.
     */
    static long newNpe()
    {
        return Loader.newNpe();
    }

    /** Allocate a mini {@code java/lang/ArrayIndexOutOfBoundsException} — the JIT calls this on a bad index. */
    static long newAioobe()
    {
        return Loader.newAioobe();
    }

    /** Allocate a mini {@code java/lang/ArithmeticException} — the JIT calls this on an integer / or % by zero
     *  (AArch64 SDIV/UDIV don't trap, so the compiler emits an explicit divisor-zero check). */
    static long newArith()
    {
        return Loader.newArith();
    }

    /** Allocate a mini {@code java/lang/ArrayStoreException} — the JIT calls this when an {@code aastore}
     *  stores a value not assignable to the array's element type. */
    static long newAse()
    {
        return Loader.newArrayStoreException();
    }

    /**
     * {@code aastore} type check: may {@code value} be stored into reference {@code array}? 1 = yes (null, an
     * untyped/raw array, a primitive-element array, or {@code value} is an instance of the array's element
     * type), 0 = no (the JIT then throws {@link #newAse}). Mirrors the JVM's covariant array-store check.
     */
    static int arrayStoreOk(long array, long value)
    {
        if (value == 0L)
        {
            return 1;                                      // null stores are always allowed
        }
        long tib = Magic.load64(array);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 1;                                      // untyped/raw array (no element Type to check against)
        }
        long elemType = Magic.load64(Magic.load64(tib) + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
        if (elemType == 0L)
        {
            return 1;                                      // primitive-element array (shouldn't reach aastore) / unknown
        }
        return instanceOf(value, elemType);                // 1 if value's Type <: element Type, else 0
    }

    /** {@code java/lang/InternalError} — the fault handler's catch-all for an unexpected hardware trap. */
    static long newInternalError()
    {
        return Loader.newInternalError();
    }

    /** {@code Object.getClass()} (intrinsified): the Class mirror for the object's Type (header→TIB→Type→Class). */
    static long getClassOf(long obj)
    {
        return Loader.getClassOf(obj);
    }

    /** Print a "string" (a mini java/lang/String or a raw byte[]): write its bytes to the UART. */
    static void printStr(long ref)
    {
        long arr = strBytes(ref);
        long len = Magic.load64(arr + 16L);
        long i = 0L;
        while (i < len)
        {
            Uart.putc((byte) Magic.load8(arr + 24L + i));
            i = i + 1L;
        }
    }

    /**
     * Build a runtime context-switch stub on the heap: save the interrupted context (x0..x30, ELR_EL1,
     * SPSR_EL1) to the current stack, {@code BL pickAddr} to choose the next task, then restore that
     * task's context and {@code ERET} into it. When {@code svcCheck}, it first reads ESR_EL1 and, unless
     * this is an SVC (EC=0x15), branches to {@link #reportFault} -- so the shared EL1 synchronous vector
     * still reports real faults. Returns the stub's entry address.
     */
    static long buildSwitchStub(long pickAddr, boolean svcCheck)
    {
        // The stub is referenced ONLY by the raw EL1 vector table (e4/e5/e6), which the conservative GC never
        // scans and the demand-load heap reclaim rewinds -- so a data-heap stub gets freed/zeroed out from under
        // the vectors, and the next svc/timer-IRQ branches into dead memory (the "reset" during the long regex
        // compile: a GC mid-compile freed it). Allocate in the never-reclaimed, never-GC'd JIT code arena so the
        // vectors stay valid for the whole run. (Core-0 only: both callers -- pcSetup, installSchedVectors -- are
        // primary-only, so the non-atomic allocCode bump is race-free.)
        long raw = Heap.allocCode(0x400);
        long stub = (raw + (long) ObjectModel.HEADER_SIZE + 0xFL) & ~0xFL;   // 16-byte-aligned code start
        int w = 0;
        Magic.store32(stub + w * 4L, A64Enc.subImm(31, 31, (int) SCHED_FRAME)); w += 1;   // sub sp, #272
        int r = 0;
        while (r <= 30)                                    // save x0..x30
        {
            Magic.store32(stub + w * 4L, A64Enc.strx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(stub + w * 4L, A64Enc.mrs(9, A64Enc.ELR_EL1));   w += 1;
        Magic.store32(stub + w * 4L, A64Enc.strx(9, 31, 248));         w += 1;   // save ELR_EL1
        Magic.store32(stub + w * 4L, A64Enc.mrs(9, A64Enc.SPSR_EL1));  w += 1;
        Magic.store32(stub + w * 4L, A64Enc.strx(9, 31, 256));         w += 1;   // save SPSR_EL1
        if (svcCheck)
        {
            Magic.store32(stub + w * 4L, A64Enc.mrs(9, A64Enc.ESR_EL1)); w += 1;
            Magic.store32(stub + w * 4L, A64Enc.lsrImm(9, 9, 26));       w += 1; // x9 = ESR.EC
            Magic.store32(stub + w * 4L, A64Enc.cmpImm(9, 0x15));        w += 1; // SVC from AArch64?
            Magic.store32(stub + w * 4L, A64Enc.bcond(0, 3));           w += 1;  // b.eq +3: SVC -> skip the 2-insn fault path
            // else (a real fault): hand it to throwFromFault(faultingSp) -> a Java exception at the faulting PC.
            // The stub already did `sub sp, #SCHED_FRAME` + saved the regs, so undo that to recover the faulting SP.
            long faultAt = stub + w * 4L;
            Magic.store32(faultAt, A64Enc.addImm(0, 31, (int) SCHED_FRAME));   w += 1;   // x0 = faulting SP
            long blFault = stub + w * 4L;
            Magic.store32(blFault, A64Enc.bl((int) ((throwFromFaultAddr - blFault) / 4L))); w += 1;   // throwFromFault(sp)
        }
        Magic.store32(stub + w * 4L, A64Enc.movFromSp(0));            w += 1;    // mov x0, sp (curSp)
        long blAddr = stub + w * 4L;
        Magic.store32(blAddr, A64Enc.bl((int) ((pickAddr - blAddr) / 4L)));      // x0 = pick(x0)
        w += 1;
        Magic.store32(stub + w * 4L, A64Enc.movToSp(0));             w += 1;    // mov sp, x0 (next task)
        Magic.store32(stub + w * 4L, A64Enc.ldrx(9, 31, 248));       w += 1;
        Magic.store32(stub + w * 4L, A64Enc.msr(A64Enc.ELR_EL1, 9)); w += 1;    // restore ELR_EL1
        Magic.store32(stub + w * 4L, A64Enc.ldrx(9, 31, 256));       w += 1;
        Magic.store32(stub + w * 4L, A64Enc.msr(A64Enc.SPSR_EL1, 9)); w += 1;   // restore SPSR_EL1
        r = 0;
        while (r <= 30)                                    // restore x0..x30 (x9 last)
        {
            Magic.store32(stub + w * 4L, A64Enc.ldrx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(stub + w * 4L, A64Enc.addImm(31, 31, (int) SCHED_FRAME)); w += 1;   // add sp, #272
        Magic.store32(stub + w * 4L, A64Enc.eret());                w += 1;
        Heap.publishCode(stub, stub + w * 4L);
        return stub;
    }

    /**
     * Install the timer context-switch stub at the IRQ/FIQ vectors and the SVC (yield) stub at the EL1
     * synchronous vector, create taskA/taskB with their own stacks, bring up the GIC + timer, and unmask
     * IRQs. On the first tick (or first yield) the running boot flow becomes task 0.
     */
    /**
     * (Re)build the timer + SVC context-switch stubs and install them at the EL1 IRQ/FIQ/synchronous
     * vectors. The stubs are heap buffers referenced only by the raw vector table, so a {@link Magic#gc}
     * frees them; call this again after any GC (before relying on the scheduler) to rebuild them.
     */
    static void installSchedVectors()
    {
        long irqStub = buildSwitchStub(scheduleAddr, false);
        long svcStub = buildSwitchStub(yieldPickAddr, true);
        long e4 = vbarBase + 4L * 0x80L;                   // synchronous (Current EL, SPx) -> SVC/yield
        Magic.store32(e4, A64Enc.b((int) ((svcStub - e4) / 4L)));
        long e5 = vbarBase + 5L * 0x80L;                   // IRQ (Current EL, SPx)
        Magic.store32(e5, A64Enc.b((int) ((irqStub - e5) / 4L)));
        long e6 = vbarBase + 6L * 0x80L;                   // FIQ
        Magic.store32(e6, A64Enc.b((int) ((irqStub - e6) / 4L)));
        Heap.publishCode(e4, e6 + 4L);
        Magic.isb();
    }

    static void startScheduler()
    {
        if (scheduleAddr == 0L) { scheduleAddr = schedule(0L); }    // dead calls: make schedule/yieldPick/
        if (yieldPickAddr == 0L) { yieldPickAddr = yieldPick(0L); } // taskA/taskB/taskC compiled + stashed
        if (taskAAddr == 0L) { taskA(); }
        if (taskBAddr == 0L) { taskB(); }
        if (taskCAddr == 0L) { taskC(); }
        if (taskRAddr == 0L) { taskR(); }
        // Dead calls: the mini java.base runtime reaches these only via writer-stashed addresses (from
        // JIT'd guest code), so force the writer to compile them. Guarded on their stashed addr, so they
        // never actually run on metal (the addr is non-zero there).
        if (startThreadAddr == 0L) { startThread(0L); }
        if (objWaitAddr == 0L) { objWait(0L, 0L); }                  // Object.wait/notify/notifyAll + Thread.join
        if (objNotifyAddr == 0L) { objNotify(0L); }                  // (JIT'd guest reaches these via stashed addrs)
        if (objNotifyAllAddr == 0L) { objNotifyAll(0L); }
        if (monEnterAddr == 0L) { monEnter(0L); }                    // monitorenter/exit + Thread.holdsLock
        if (monExitAddr == 0L) { monExit(0L); }
        if (holdsLockAddr == 0L) { int u = holdsLock(0L); }
        if (interruptAddr == 0L) { interrupt(0L); }                  // Thread.interrupt/isInterrupted/isAlive
        if (isInterruptedAddr == 0L) { int u = isInterrupted(0L); }
        if (checkIntrAddr == 0L) { int u = checkClearInterrupt(); }
        if (isAliveAddr == 0L) { int u = isAlive(0L); }
        if (joinTimedAddr == 0L) { int u = joinTimed(0L, 0L); }      // Thread.join(Duration) + LockSupport
        if (parkAddr == 0L) { park(); }
        if (unparkAddr == 0L) { unpark(0L); }
        if (threadJoinAddr == 0L) { threadJoin(0L); }
        if (threadStackTraceAddr == 0L) { long u = threadStackTrace(0L, 0L, 0L); }   // Thread.getStackTrace()
        if (allThreadsAddr == 0L) { long u = allThreads(); }                         // Thread.getAllStackTraces()
        if (newSemAddr == 0L) { int u = newSem(0); }
        if (philReportAddr == 0L) { philReport(0, 0); }
        if (taskExitAddr == 0L) { taskExit(); }
        if (scStartAddr == 0L) { long u = scStart(); }        // string-concat helpers (JIT'd concat only)
        if (scCharAddr == 0L) { scChar(0L, 0); }
        if (scIntAddr == 0L) { scInt(0L, 0); }
        if (scEndAddr == 0L) { long u = scEnd(0L); }
        if (scStrAddr == 0L) { scStr(0L, 0L); }
        if (scLongAddr == 0L) { scLong(0L, 0L); }
        if (printStrAddr == 0L) { printStr(0L); }
        if (nanoTimeAddr == 0L) { long u = nanoTime(); }              // provided java.base natives (guest-called)
        if (currentTimeMillisAddr == 0L) { long u = currentTimeMillis(); }
        if (identityAddr == 0L) { long u = identity(0L); }
        if (arraycopyAddr == 0L) { arraycopy(0L, 0, 0L, 0, 0); }
        if (newNpeAddr == 0L) { long u = newNpe(); }                  // implicit-exception ctors (JIT'd checks)
        if (newAioobeAddr == 0L) { long u = newAioobe(); }
        if (newAseAddr == 0L) { long u = newAse(); }                  // ArrayStoreException (aastore mismatch)
        if (arrayStoreOkAddr == 0L) { int u = arrayStoreOk(0L, 0L); } // aastore covariant check
        if (newArithAddr == 0L) { long u = newArith(); }
        if (getClassAddr == 0L) { long u = getClassOf(0L); }          // Object.getClass() intrinsic
        if (arrayCloneAddr == 0L) { long u = arrayClone(0L); }        // [T.clone() intrinsic
        if (newReflectArrayAddr == 0L) { long u = newReflectArray(0L, 0L); } // reflect/Array.newInstance0
        if (componentTypeAddr == 0L) { long u = componentTypeOf(0L); }       // Class.getComponentType0
        if (printStackTraceAddr == 0L) { printStackTrace(0L); }       // Throwable.printStackTrace0() native
        if (fileOpenAddr == 0L) { long u = fileOpen(0L); }            // FileInputStream.open0() native (M3 RAMFS)
        if (dnsResolveAddr == 0L) { int u = dnsResolve(0L); }         // java.net.InetAddress.resolve0() native (M3)
        if (vhFieldOffsetAddr == 0L) { long u = vhFieldOffset(0L, 0L); }      // VarHandle.fieldOffset0 native (M3)
        if (fieldModsAddr == 0L) { int u = fieldMods(0L, 0L); }               // Class.fieldMods0 native (reflection)
        if (fieldTypeCharAddr == 0L) { int u = fieldTypeChar(0L, 0L); }       // Class.fieldTypeChar0 native
        if (classAtPcAddr == 0L) { long u = classAtPc(0L); }                  // getCallerClass native
        if (sockSocket0Addr == 0L) { int u = sockSocket0(0L, 0L, 0L, 0L); }   // M3 socket natives (dead calls,
        if (sockConnect0Addr == 0L) { int u = sockConnect0(0L, 0L, 0L, 0L); } // never run: the writer pre-stashes
        if (sockRead0Addr == 0L) { int u = sockRead0(0L, 0L, 0L); }           // each address, so these only force
        if (sockWrite0Addr == 0L) { int u = sockWrite0(0L, 0L, 0L); }         // compilation of the helper)
        if (sockClose0Addr == 0L) { sockClose0(0L); }
        if (sockAvailableAddr == 0L) { int u = sockAvailable(0L); }
        if (fdValAddr == 0L) { int u = fdVal(0L); }
        if (setFdValAddr == 0L) { setFdVal(0L, 0L); }
        if (sockNoopAddr == 0L) { sockNoop(); }
        if (sockZeroAddr == 0L) { long u = sockZero(); }
        if (classNameAddr == 0L) { long u = classNameOf(0L); }        // Class.getName0() native (M4)
        if (forNameAddr == 0L) { long u = forName(0L); }              // Class.forName0() native (reflection M1)
        if (defineClassAddr == 0L) { long u = defineClass(0L, 0L, 0L, 0L); } // ClassLoader.defineClass0 (M3)
        if (classModifiersAddr == 0L) { long u = classModifiers(0L); } // Class.getModifiers() native (reflection M1)
        if (methodResolveAddr == 0L) { int u = methodResolve(0L, 0L); } // Method.methodResolve0 native (reflection M2)
        if (methodInfoAddr == 0L) { int u = methodInfo(0L, 0L, 0L); }   // Method.methodInfo0 native (reflection M2)
        if (constructorResolveAddr == 0L) { int u = constructorResolve(0L, 0L); } // Constructor.ctorResolve0 (M2)
        if (allocInstanceAddr == 0L) { long u = allocInstance(0L); }    // Constructor.allocInstance0 native (M2)
        if (superclassAddr == 0L) { long u = superclassOf(0L); }      // Class.superclass0() native (M4)
        if (currentThreadAddr == 0L) { long u = currentThreadObj(); } // Thread.currentThread0() native (M4)

        installSchedVectors();

        taskSp = new long[MAX_TASKS];
        taskStackBase = new long[MAX_TASKS];
        taskThreadObj = new long[MAX_TASKS];
        taskState = new int[MAX_TASKS];
        taskWake = new long[MAX_TASKS];
        taskWaitOn = new int[MAX_TASKS];
        taskWaitObj = new long[MAX_TASKS];
        taskDone = new int[MAX_TASKS];
        taskMonWait = new long[MAX_TASKS];
        taskInterrupted = new int[MAX_TASKS];
        taskPermit = new int[MAX_TASKS];
        monObj = new long[MAX_MON];
        monOwner = new int[MAX_MON];
        monCount = new int[MAX_MON];
        semCount = new int[NUM_SEM];
        taskState[0] = TASK_READY;                          // task 0 = the boot flow (SP saved on tick 1)
        taskCount = 1;
        curTask = 0;
        spawn(taskAAddr);                                  // task 1 (yield)
        spawn(taskBAddr);                                  // task 2 (producer: posts sem 0)
        spawn(taskCAddr);                                  // task 3 (consumer: blocks on sem 0)
        spawn(taskRAddr);                                  // task 4 (UART reader: blocks on UART_SEM)

        Gic.init(Gic.PPI_CNTPNS);
        timerReload = Magic.readCNTFRQ_EL0() / 100L;       // ~10 ms scheduling quantum
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                 // preemption starts here
    }

    /**
     * Minimal scheduler for the {@code WIFI_ONLY} fast-cycle path: allocate the task table, install the
     * context-switch vectors, and arm the GIC timer + IRQs with ONLY task 0 (no demo tasks). IRQ-driven WiFi
     * RX needs the switch machinery (`semWaitTimeout` → `taskYield`) and a live timer/IRQ, which the old
     * wifi-only path (a bare `bringUp`) never set up. This is the {@link #startScheduler} essentials minus the
     * demo-task spawns and the loader/JIT dead-call stashing (those features are dead-code-eliminated here).
     */
    static void startWifiScheduler()
    {
        if (scheduleAddr == 0L) { scheduleAddr = schedule(0L); }   // dead calls: force schedule/yieldPick
        if (yieldPickAddr == 0L) { yieldPickAddr = yieldPick(0L); } // compiled + stashed for the vector stubs
        installSchedVectors();
        taskSp = new long[MAX_TASKS];
        taskStackBase = new long[MAX_TASKS];
        taskThreadObj = new long[MAX_TASKS];
        taskState = new int[MAX_TASKS];
        taskWake = new long[MAX_TASKS];
        taskWaitOn = new int[MAX_TASKS];
        taskWaitObj = new long[MAX_TASKS];
        taskDone = new int[MAX_TASKS];
        taskMonWait = new long[MAX_TASKS];
        taskInterrupted = new int[MAX_TASKS];
        taskPermit = new int[MAX_TASKS];
        monObj = new long[MAX_MON];
        monOwner = new int[MAX_MON];
        monCount = new int[MAX_MON];
        semCount = new int[NUM_SEM];
        taskState[0] = TASK_READY;                          // task 0 = the WiFi boot flow
        taskCount = 1;
        curTask = 0;
        Gic.init(Gic.PPI_CNTPNS);
        timerReload = Magic.readCNTFRQ_EL0() / 100L;        // ~10 ms tick (deadline wakes for semWaitTimeout)
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                  // IRQs on: SDIO SPI 158 + timer
    }

    /**
     * EL1 exception handler (reached by a branch from every vector entry): print the syndrome,
     * faulting PC and fault address, then park. Does not return — this is a last-resort report.
     */
    /**
     * {@code Throwable.printStackTrace0()} native (self in x0): print the throwable's class + the frames captured
     * into its inline backtrace (bt0..bt7 @ self+16) by {@link #unwind} at throw time. Names each frame's method
     * via {@link Loader#printFrameAt} (demand-compiled methods / {@code <clinit>}s; image code shows "image/native").
     */
    /**
     * {@code [T.clone()} intrinsic: a shallow copy of any array. Copies the whole block body (length word +
     * elements, from the status-word size — every allocation records it) and carries the TIB over, so raw
     * (elem-size) and typed (array-Type TIB) arrays clone alike. Element values copy verbatim (shallow).
     */
    static long arrayClone(long ref)
    {
        if (ref <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        long size = Magic.load64(ref + 8L) & -8L;          // block size from the status word
        long copy = Heap.alloc((int) size);
        Magic.store64(copy, Magic.load64(ref));            // same TIB (raw elem size or typed array TIB)
        long i = 16L;
        while (i < size)
        {
            Magic.store64(copy + i, Magic.load64(ref + i));
            i += 8L;
        }
        return copy;
    }

    /**
     * {@code java.lang.reflect.Array.newInstance0(Class, int)} native: a {@code length}-element reference array
     * (8-byte elements) TYPED as {@code [L<component>;} — its TIB is {@code Loader.refArrayTib(componentType)},
     * the SAME interned array-TIB that {@code new component[]} / {@code instanceof component[]} use, so a caller
     * that {@code instanceof}-checks the result (e.g. {@code toArray(T[])} tests) matches. Backs the temp/work
     * arrays TimSort/ComparableTimSort/Arrays.copyOf allocate reflectively. Falls back to an untyped raw array
     * if the component's Type isn't resolvable (still fine for fill-and-return uses).
     */
    static long newReflectArray(long componentMirror, long length)
    {
        if (length < 0L)
        {
            return 0L;                                     // boot force-compile passes 0; guest checks negative first
        }
        long arr = Heap.allocArray((int) length, 8);       // 8-byte reference elements (raw header first)
        if (componentMirror > 0x1000L)
        {
            long compType = Magic.load64(componentMirror + 16L);   // Class mirror -> its Type (@16)
            if (compType != 0L)
            {
                Magic.store64(arr, Loader.refArrayTib(compType));  // typed [L<component>; TIB (interned per element)
            }
        }
        return arr;
    }

    /**
     * {@code Class.getComponentType0(Class)} native: for an array Class, the Class mirror of its element type
     * (read from the array Type's element slot); for a non-array Class, 0. Lets
     * {@code a.getClass().getComponentType()} feed {@link #newReflectArray} the right element Type.
     */
    static long componentTypeOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        long type = Magic.load64(mirror + 16L);            // mirror -> Type (@16)
        if (type == 0L)
        {
            return 0L;
        }
        long instSize = Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET);
        if ((instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) != ObjectModel.ARRAY_TYPE_TAG)
        {
            return 0L;                                     // not an array Type
        }
        long elemType = Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
        return elemType == 0L ? 0L : Loader.classMirror(elemType);   // primitive-element arrays have 0 elem Type
    }

    /**
     * M4: {@code Class.getName0(Class)} native — the mirror's Type ({@code @16}) -> a fresh guest String of
     * the class's dotted binary name (built by {@code Loader.classNameString} from the registry name bytes).
     */
    static long classNameOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no-op
        }
        return Loader.classNameString(Magic.load64(mirror + 16L));
    }

    /**
     * Reflection: {@code Class.forName0(byte[])} native — resolve a binary class name (raw ASCII, dots) to its
     * Class mirror, incrementally loading the class into the live program if needed. 0 => guest throws
     * {@code ClassNotFoundException}. Boot force-compile passes a 0 array (guarded in {@code forNameMirror}).
     */
    static long forName(long nameArr)
    {
        return Loader.forNameMirror(nameArr);
    }

    /**
     * Reflection M3: {@code ClassLoader.defineClass0(name, byte[], off, len)} native — materialize a class from
     * the SUPPLIED classfile bytes into the live program and return its Class mirror. 0 => the guest throws
     * {@code ClassFormatError}/returns null. The {@code name} arg is advisory (the loader uses the classfile's
     * own this_class); boot force-compile passes a 0 array (guarded in {@code defineFromBytes}).
     */
    static long defineClass(long nameArr, long byteArr, long off, long len)
    {
        long type = Loader.defineFromBytes(byteArr, (int) off, (int) len);
        return type == 0L ? 0L : Loader.classMirror(type);
    }

    /**
     * Reflection: {@code Class.getModifiers()} native — the class's Java language modifiers. For a nested class
     * these come from the enclosing class's {@code InnerClasses} attribute (so a {@code private} inner reports
     * {@code private}); the VM-internal {@code ACC_SUPER} (0x20) bit is stripped either way.
     */
    static long classModifiers(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;                                     // boot force-compile passes 0
        }
        return (long) Loader.classModifiersOf(Magic.load64(mirror + 16L));
    }

    /** Reflection: {@code Method.methodResolve0(Class,byte[])} -> method-registry index of the named method, or -1. */
    static int methodResolve(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;                                     // boot force-compile passes 0
        }
        return Loader.methodResolve(Magic.load64(mirrorRef + 16L), nameArrRef);
    }

    /** Reflection: {@code Method.methodInfo0(int,byte[],long[])} -> fills param chars + {buf,access,retChar}; count. */
    static int methodInfo(long rgIndex, long paramCharsRef, long outRef)
    {
        if (paramCharsRef <= 0x1000L || outRef <= 0x1000L)
        {
            return 0;                                      // boot force-compile passes 0
        }
        return Loader.methodInfo((int) rgIndex, paramCharsRef, outRef);
    }

    /** Reflection: {@code Constructor.ctorResolve0(Class,int)} -> registry index of the <init> with that arity, or -1. */
    static int constructorResolve(long mirrorRef, long paramCount)
    {
        if (mirrorRef <= 0x1000L)
        {
            return -1;                                     // boot force-compile passes 0
        }
        return Loader.constructorResolve(Magic.load64(mirrorRef + 16L), (int) paramCount);
    }

    /** Reflection: {@code Constructor.allocInstance0(Class)} -> a fresh zeroed instance (TIB set), or 0. */
    static long allocInstance(long mirrorRef)
    {
        if (mirrorRef <= 0x1000L)
        {
            return 0L;                                     // boot force-compile passes 0
        }
        return Loader.allocInstance(Magic.load64(mirrorRef + 16L));
    }

    /**
     * M4: {@code Class.superclass0(Class)} native — the mirror's Type's {@code superType} ({@code @8}) ->
     * its (cached) mirror, or 0 for {@code java/lang/Object}/unloaded.
     */
    static long superclassOf(long mirror)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        long st = Magic.load64(Magic.load64(mirror + 16L) + 8L);
        return st == 0L ? 0L : Loader.classMirror(st);
    }

    /**
     * M4: {@code Thread.currentThread0()} native — the calling task's guest Thread. Tasks started via
     * {@code Thread.start()} recorded their Thread in {@link #startThread}; a task the VM created without
     * one (the boot task) gets a bare Thread lazily wrapped around it (cached, so the answer is stable).
     */
    static long currentThreadObj()
    {
        long t = taskThreadObj[curTask];
        if (t == 0L)
        {
            t = Loader.allocThreadObj();                   // 0 if java/lang/Thread isn't in the loaded batch
            taskThreadObj[curTask] = t;
        }
        return t;
    }

    /**
     * M3 RAMFS: resolve a guest {@code java/lang/String} path to its embedded file-table entry
     * ({nameAddr, nameLen, bytesAddr, bytesLen}), or 0 if absent. The native behind the guest
     * {@code java/io/FileInputStream.open0(String)} overlay (wired in {@code Loader.nativeBuf});
     * the overlay then reads the content directly via {@code Magic.load8/load64} on the entry.
     */
    /** Find a RAMFS file by a raw path {@code byte[]} (for VM/driver code with no guest String); returns the
     *  directory entry addr {nameAddr, nameLen@8, bytesAddr@16, bytesLen@24}, or 0. */
    public static long fileFind(byte[] path)
    {
        if (fileDir == 0L)
        {
            return 0L;
        }
        int len = path.length;
        int i = 0;
        while (i < (int) fileCount)
        {
            long e = fileDir + i * 32L;
            if (Magic.load64(e + 8L) == (long) len)
            {
                long na = Magic.load64(e);
                int k = 0;
                while (k < len && (Magic.load8(na + k) & 0xFF) == (path[k] & 0xFF))
                {
                    k += 1;
                }
                if (k == len)
                {
                    return e;
                }
            }
            i += 1;
        }
        return 0L;
    }

    /** Manifest parser (like Cyw43's wifi.conf reader): find a {@code key=value} line in {@code conf} (length
     *  {@code flen}) and copy the value (to CR/LF/NUL/end) into {@code dst}; returns its length (0 if absent). */
    static int manifestValue(long conf, int flen, byte[] key, long dst, int max)
    {
        int i = 0;
        while (i < flen)
        {
            int k = 0;
            int j = i;
            while (k < key.length && j < flen && (Magic.load8(conf + j) & 0xFF) == (key[k] & 0xFF))
            {
                k += 1;
                j += 1;
            }
            if (k == key.length && j < flen && (Magic.load8(conf + j) & 0xFF) == 0x3D)   // "key="
            {
                j += 1;
                int n = 0;
                while (j < flen && n < max)
                {
                    int c = Magic.load8(conf + j) & 0xFF;
                    if (c == 0x0A || c == 0x0D || c == 0)
                    {
                        break;
                    }
                    Magic.store8(dst + n, c);
                    n += 1;
                    j += 1;
                }
                return n;
            }
            while (i < flen && (Magic.load8(conf + i) & 0xFF) != 0x0A)   // skip to the next line
            {
                i += 1;
            }
            i += 1;
        }
        return 0;
    }

    /** Copy {@code len} bytes from a raw heap address into a fresh {@code byte[]}. */
    static byte[] heapBytes(long addr, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = (byte) Magic.load8(addr + i);
            i += 1;
        }
        return b;
    }

    /**
     * OS-like program launch. If {@code /etc/init} (RAMFS) names a program — {@code main=<class>}, optional
     * {@code args=<space-separated>} — run its {@code main(String[])} and return true; the image then behaves
     * like a JVM running one application rather than a demo script. Returns false when no manifest is present
     * (the boot falls through to the demo suite, transitional).
     */
    static boolean launchInit()
    {
        long e = fileFind(Magic.bytes("/etc/init"));
        if (e == 0L)
        {
            return false;
        }
        long conf = Magic.load64(e + 16L);
        int flen = (int) Magic.load64(e + 24L);
        long nm = Heap.allocData(128);
        int nl = manifestValue(conf, flen, Magic.bytes("main"), nm, 120);
        if (nl == 0)
        {
            return false;
        }
        byte[] mainClass = heapBytes(nm, nl);
        long al = Heap.allocData(256);
        int alen = manifestValue(conf, flen, Magic.bytes("args"), al, 250);
        byte[] argsLine = heapBytes(al, alen);              // raw space-separated args; argv built after loadAll
        Uart.write(Magic.bytes("launch "));
        Uart.write(mainClass);
        Uart.putc(0x0A);
        Loader.launch(mainClass, argsLine);
        return true;
    }

    /** True if the /etc/init manifest requests networking ({@code net=1}) -- the OS then brings the WiFi
     *  interface up (join + DHCP) before launching, so the program's java.net finds an established link. */
    static boolean manifestNet()
    {
        long e = fileFind(Magic.bytes("/etc/init"));
        if (e == 0L)
        {
            return false;
        }
        long conf = Magic.load64(e + 16L);
        int flen = (int) Magic.load64(e + 24L);
        long v = Heap.allocData(16);
        int n = manifestValue(conf, flen, Magic.bytes("net"), v, 8);
        return n >= 1 && (Magic.load8(v) & 0xFF) == 0x31;   // "1"
    }

    static long fileOpen(long nameRef)
    {
        if (nameRef <= 0x1000L || fileDir == 0L)
        {
            return 0L;                                     // boot-time force-compile passes 0; no RAMFS -> 0
        }
        long arr = strBytes(nameRef);                      // String -> its value byte[] (len@+16, data@+24)
        long len = Magic.load64(arr + 16L);
        int i = 0;
        while (i < (int) fileCount)
        {
            long e = fileDir + i * 32L;
            if (Magic.load64(e + 8L) == len)
            {
                long na = Magic.load64(e);                 // path bytes
                int k = 0;
                while (k < (int) len && Magic.load8(na + k) == Magic.load8(arr + 24L + k))
                {
                    k += 1;
                }
                if (k == (int) len)
                {
                    return e;
                }
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Resolve a guest {@code byte[]} hostname to an IPv4 address, returned as a big-endian int (a.b.c.d ->
     * (a&lt;&lt;24)|(b&lt;&lt;16)|(c&lt;&lt;8)|d), 0 on failure. Backs the overlay {@code java.net.InetAddress.resolve0}
     * with the WiFi DNS resolver; the socket layer reads this int straight out of the InetAddress.
     */
    static int dnsResolve(long hostArrRef)
    {
        if (hostArrRef <= 0x1000L)                         // boot-time force-compile passes 0
        {
            return 0;
        }
        int hlen = (int) Magic.load64(hostArrRef + 16L);   // guest byte[] length
        byte[] host = heapBytes(hostArrRef + 24L, hlen);
        long ipOut = Heap.allocData(4);
        if (!board.cyw43.Cyw43.dnsResolve(host, ipOut))
        {
            return 0;
        }
        return ((Magic.load8(ipOut) & 0xFF) << 24) | ((Magic.load8(ipOut + 1) & 0xFF) << 16)
                | ((Magic.load8(ipOut + 2) & 0xFF) << 8) | (Magic.load8(ipOut + 3) & 0xFF);
    }

    // ----- M3 socket natives: stock java.net / sun.nio.ch over net.Tcp. A FileDescriptor's fd int (first
    //       field, offset 16) holds the net.Tcp connection handle. Every helper is STATIC (matching the JDK
    //       26 natives) and reached via Loader.nativeBuf with the loader arg convention (slot k = x(1+k));
    //       args come in as raw longs (refs/ints). -----

    /** The net.Tcp handle stored in a FileDescriptor's fd field (offset 16 = first instance field). */
    private static int fdIndex(long fdRef)
    {
        return (int) Magic.load64(fdRef + 16L);
    }

    /** VarHandle overlay: byte offset of instance field named by the guest {@code byte[]} within {@code obj}'s
     *  class -> {@code java/lang/invoke/VarHandle.fieldOffset0(byte[],Object)J}. */
    static long vhFieldOffset(long fnameArrRef, long objRef)
    {
        if (fnameArrRef <= 0x1000L || objRef <= 0x1000L)     // boot-time force-compile passes 0
        {
            return -1L;
        }
        int fnLen = (int) Magic.load64(fnameArrRef + 16L);   // guest byte[] length
        long fnBase = fnameArrRef + 24L;                     // guest byte[] data
        long tib = Magic.load64(objRef);                     // obj header TIB
        return Loader.vhFieldOffset(fnBase, fnLen, tib);
    }

    /** Reflection: {@code Class.fieldMods0(Class,byte[])} -> the named own instance field's access_flags, or -1. */
    static int fieldMods(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;
        }
        long typeAddr = Magic.load64(mirrorRef + 16L);       // Class.typeAddr
        int fnLen = (int) Magic.load64(nameArrRef + 16L);
        long fnBase = nameArrRef + 24L;
        return Loader.fieldMods(typeAddr, fnBase, fnLen);
    }

    /** Reflection: {@code Class.fieldTypeChar0(Class,byte[])} -> the field's descriptor first char, or -1. */
    static int fieldTypeChar(long mirrorRef, long nameArrRef)
    {
        if (mirrorRef <= 0x1000L || nameArrRef <= 0x1000L)
        {
            return -1;
        }
        long typeAddr = Magic.load64(mirrorRef + 16L);
        int fnLen = (int) Magic.load64(nameArrRef + 16L);
        long fnBase = nameArrRef + 24L;
        return Loader.fieldTypeChar(typeAddr, fnBase, fnLen);
    }

    /** getCallerClass: the Class mirror of the JIT'd method containing machine PC {@code pc} (a saved LR). */
    static long classAtPc(long pc)
    {
        if (pc <= 0x1000L)
        {
            return 0L;
        }
        return Loader.classMirrorAtPc(pc);
    }

    /** Net.socket0(preferIPv6, stream, reuse, fastLoopback) -> a fresh net.Tcp fd (the flags are ignored). */
    static int sockSocket0(long a, long b, long c, long d)
    {
        return net.Tcp.alloc();
    }

    /** Net.connect0(preferIPv6, FileDescriptor fd, InetAddress remote, int port) -> 1 on success, 0 else. */
    static int sockConnect0(long preferIPv6, long fdRef, long inetRef, long port)
    {
        int ipBe = (int) Magic.load64(inetRef + 16L);   // InetAddress.addr (first field, big-endian IPv4)
        return net.Tcp.connect(fdIndex(fdRef), ipBe, (int) port);
    }

    /** SocketDispatcher.read0(fd, address, len) -> bytes read into {@code address}, or -1 at EOF. */
    static int sockRead0(long fdRef, long address, long len)
    {
        return net.Tcp.read(fdIndex(fdRef), address, 0, (int) len);
    }

    /** SocketDispatcher.write0(fd, address, len) -> bytes written from {@code address}. */
    static int sockWrite0(long fdRef, long address, long len)
    {
        return net.Tcp.write(fdIndex(fdRef), address, 0, (int) len);
    }

    /** UnixDispatcher.close0(fd). */
    static void sockClose0(long fdRef)
    {
        net.Tcp.close(fdIndex(fdRef));
    }

    /** Net.available(fd) -> bytes buffered for a non-blocking read. */
    static int sockAvailable(long fdRef)
    {
        return net.Tcp.available(fdIndex(fdRef));
    }

    /** IOUtil.fdVal(fd) -> the fd int (the net.Tcp handle). */
    static int fdVal(long fdRef)
    {
        return (int) Magic.load64(fdRef + 16L);
    }

    /** IOUtil.setfdVal(fd, value). */
    static void setFdVal(long fdRef, long value)
    {
        Magic.store64(fdRef + 16L, value);
    }

    /** Shared no-op for the void socket natives (UnixDispatcher.init/preClose0, IOUtil.initIDs,
     *  NativeThread.init) -- never reached on the blocking happy path. */
    static void sockNoop()
    {
    }

    /** Shared 0 for the socket natives we stub: Net.localPort / getIntOption0 (SO_LINGER=0 -> close skips
     *  shutdown) / localInetAddress (null wildcard), NativeThread.current0. */
    static long sockZero()
    {
        return 0L;
    }

    static void printStackTrace(long self)
    {
        if (self <= 0x1000L)
        {
            return;                                        // the boot-time force-compile calls this with 0; no-op
        }
        Uart.putc(0x0A);
        long tib = Magic.load64(self);
        if (tib > 0x1000L)
        {
            Loader.printClassName(Magic.load64(tib));      // TIB[0] = Type -> the exception's class name
        }
        long msg = Magic.load64(self + 80L);               // Throwable.detailMessage (after the 8-slot backtrace)
        if (msg > 0x1000L)
        {
            Uart.write(Magic.bytes(": "));
            printStr(msg);
        }
        Uart.putc(0x0A);
        int i = 0;
        while (i < 8)
        {
            long fpc = Magic.load64(self + 16L + i * 8L);
            if (fpc == 0L)
            {
                break;
            }
            Uart.write(Magic.bytes("  at "));
            Loader.printFrameAt(fpc);
            Uart.putc(0x0A);
            i += 1;
        }
    }

    static void reportFault()
    {
        long rcv = Magic.readX0();                         // FIRST ops: capture the faulting blr's receiver (x0) and
        long lr = Magic.readLR();                          // return addr (x30) before anything clobbers them
        long esr = Magic.readESR_EL1();
        long elr = Magic.readELR_EL1();
        long far = Magic.readFAR_EL1();
        long el = Magic.readCurrentEL();
        Uart.write(Magic.bytes("\nFAULT el="));
        printHex(el);
        Uart.write(Magic.bytes(" esr="));
        printHex(esr);
        Uart.write(Magic.bytes(" elr="));
        printHex(elr);
        Uart.write(Magic.bytes(" far="));
        printHex(far);
        Uart.write(Magic.bytes(" lr="));
        printHex(lr);
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("  faulting method (lr): "));   // #43: name the demand-compiled method at lr
        Loader.reportMethodAt(lr);
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("  receiver x0="));             // #43: the wild-branch receiver + its TIB/Type
        printHex(rcv);
        if (rcv > 0x1000L && rcv < 0x40000000L)                // plausible heap object: dump TIB and Type
        {
            long tib = Magic.load64(rcv);
            Uart.write(Magic.bytes(" tib="));
            printHex(tib);
            if (tib > 0x1000L && tib < 0x40000000L)
            {
                long type = Magic.load64(tib);                 // TIB[0] = Type
                Uart.write(Magic.bytes(" type="));
                printHex(type);
                Loader.reportClassOfType(type);
            }
        }
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * Turn a hardware fault into a Java exception thrown at the faulting instruction. Reached from the EL1
     * synchronous vector's non-SVC path with {@code sp} = the FAULTING stack pointer (the vector stub undoes
     * its own frame). Reads ESR/ELR (still holding the fault); an address trap (data/instruction abort)
     * becomes a {@link Loader#newNpe NullPointerException}; any other unexpected trap (alignment, undefined
     * instruction, MSR trap, ...) becomes a {@link Loader#newInternalError InternalError} after logging the raw
     * EC/ELR/FAR. {@link #unwind} then throws it at ELR -- so a catch (or an uncaught stack trace) sees it like
     * any thrown exception. The one fault we can't convert is an unloaded exception class (a TIB of 0 would just
     * re-fault inside unwind); that falls back to the raw {@link #reportFault} print + halt. A taken exception
     * masks IRQs; since we resume by branch (not ERET), unmask them first so the scheduler/WiFi keep ticking if
     * the exception is caught.
     */
    static void throwFromFault(long sp)
    {
        long esr = Magic.readESR_EL1();
        long elr = Magic.readELR_EL1();
        if (faultDepth != 0)                                            // a fault DURING the unwind of a prior fault:
        {                                                              // turning THIS one into an exception + unwinding
            reportNestedFault(esr, elr, Magic.readFAR_EL1());          // would re-fault forever (the silent reboot loop
        }                                                              // seen on QEMU) -- report both faults + halt.
        faultDepth = 1;                                                // cleared in unwind once a handler is resumed
        fault0Esr = esr;
        fault0Elr = elr;
        fault0Far = Magic.readFAR_EL1();
        long ec = (esr >> 26) & 0x3FL;
        long exc = 0L;
        if (ec == 0x24L || ec == 0x25L || ec == 0x20L || ec == 0x21L)   // data / instruction abort = address trap
        {
            exc = newNpe();
        }
        else                                                           // any other unexpected trap (alignment,
        {                                                              // undefined instruction, MSR trap, ...):
            Uart.write(Magic.bytes("TRAP ec="));                       // an InternalError (a VirtualMachineError).
            printHex(ec);                                              // print the raw fault first so the EC/FAR
            Uart.write(Magic.bytes(" elr="));                          // aren't lost even though we now throw.
            printHex(elr);
            Uart.write(Magic.bytes(" far="));
            printHex(Magic.readFAR_EL1());
            Uart.putc(0x0A);
            exc = newInternalError();
        }
        if (exc <= 0x1000L || Magic.load64(exc) == 0L)                  // exception class not loaded (TIB 0): can't
        {                                                              // throw without re-faulting inside unwind ->
            reportFault();                                             // print the raw fault + halt (never returns)
        }
        Magic.enableIrq();                                             // resumed via branch, not ERET: re-unmask IRQs
        unwind(exc, elr, sp);                                          // throw at the faulting instruction (never returns)
    }

    static long throwFromFaultAddr;    // VM.throwFromFault(J)V — hardware fault -> Java exception (address trap -> NPE)
    static int  faultDepth;            // 1 while a hardware fault is being turned into a Java exception + unwound
    static long fault0Esr, fault0Elr, fault0Far;   // the FIRST fault's syndrome, kept for the nested-fault report

    /** A second CPU fault fired while {@link #throwFromFault} was still turning the FIRST one into a Java
     *  exception (the unwind itself re-faulted -- a bad frame-table entry, a wild handler PC, an unmapped
     *  address). Continuing would loop the fault vector forever and the board silently resets. Report BOTH
     *  faults (the original is the real bug; the nested one shows where the unwind broke) and halt. */
    static void reportNestedFault(long esr, long elr, long far)
    {
        Uart.write(Magic.bytes("\nNESTED FAULT (unwind re-faulted; halting to avoid a reboot loop)\n  original: esr="));
        printHex(fault0Esr);
        Uart.write(Magic.bytes(" elr="));
        printHex(fault0Elr);
        Uart.write(Magic.bytes(" far="));
        printHex(fault0Far);
        Uart.write(Magic.bytes("\n    at "));
        Loader.reportMethodAt(fault0Elr);
        Uart.write(Magic.bytes("\n  nested:   esr="));
        printHex(esr);
        Uart.write(Magic.bytes(" elr="));
        printHex(elr);
        Uart.write(Magic.bytes(" far="));
        printHex(far);
        Uart.write(Magic.bytes("\n    at "));
        Loader.reportMethodAt(elr);
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * Trap for a call into a DENYLISTED (pruned, never-loaded) class — see {@code Loader.isDenylisted}.
     * patchRelocs points every unresolved cross-class call here instead of leaving a {@code bl 0} wild branch.
     * A well-formed metal program never reaches it (denylisted subtrees are cold); if it fires, the halt +
     * message is a deterministic signal that a denylist prefix was too broad (un-denylist that subtree).
     */
    static void denylistTrap()
    {
        long lr = Magic.readLR();                              // FIRST op: x30 = caller's return addr (the bl site + 4)
        long mysp = Magic.readSP();                            // denylistTrap's own frame base (SP is stable post-prologue)
        Uart.write(Magic.bytes("\nDENYLIST TRAP: call into a pruned (metal-absent) class -- see Loader.isDenylisted\n"));
        int k = Loader.trapIndexFor(lr);                       // #43: match against the TRAPWIRE table printed at patch time
        Uart.write(Magic.bytes("  fired TRAPWIRE index="));
        printDec(k);
        Uart.write(Magic.bytes(" (lr="));
        printHex(lr);
        Uart.write(Magic.bytes(")"));
        // Real call-stack backtrace (was: reportMethodAt's 5 nearest-in-memory methods, which only line 1 -- the
        // caller -- was right; the rest were unrelated methods laid out nearby). Recover the caller's SP as
        // denylistTrap's SP + its own frame size, then walk saved LRs like captureTrace/VM.unwind.
        long cpc = lr - 4L;                                    // the denied bl site in the caller
        long csp = mysp + frameSizeAt(denylistTrapAddr);       // caller's SP (denylistTrap's SP + its frame)
        int depth = 0;
        while (depth < 12 && cpc > 0x1000L)
        {
            Uart.write(Magic.bytes("\n    at "));
            Loader.printFrameAt(cpc);
            long fs = frameSizeAt(cpc);
            if (fs == 0L)
            {
                break;                                         // top of the JIT/image stack
            }
            cpc = Magic.load64(csp) - 4L;                      // caller's return address (the call site)
            csp += fs;
            depth += 1;
        }
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }

    /** Print {@code v} as {@code 0x} + 16 hex digits over the UART. */
    public static void printHex(long v)
    {
        Uart.putc(0x30);                                   // '0'
        Uart.putc(0x78);                                   // 'x'
        int shift = 60;
        while (shift >= 0)
        {
            int nib = (int) ((v >> shift) & 0xFL);
            Uart.putc(nib < 10 ? 0x30 + nib : 0x41 + nib - 10);   // 0-9, A-F
            shift -= 4;
        }
    }

    /**
     * Report an on-metal JIT compile failure (the {@link compiler.Symbols} fail() seam, metal side) over
     * the UART so an unsupported bytecode/intrinsic in a loaded class is NAMED rather than a silent hang.
     * reason = the Symbols.FAIL_* code; for FAIL_OPCODE a = the opcode, b = the bytecode position.
     */
    static void jitFail(int reason, int a, int b)
    {
        Uart.write(Magic.bytes("\nJIT unsupported: reason="));
        printDec(reason);
        Uart.write(Magic.bytes(" a="));
        printHex(a & 0xFFFFFFFFL);
        Uart.write(Magic.bytes(" b="));
        printDec(b);
        Uart.putc(0x0A);
    }

    /**
     * Runs every used class's {@code <clinit>} once, eagerly, before the program.
     * The body is empty here — the boot-image writer replaces it with a sequence
     * of calls to the discovered static initializers (closed-world eager init).
     */
    static void initClasses()
    {
    }

    /**
     * {@code instanceof} support: is {@code ref}'s class {@code targetType} or a
     * subclass? Walks the object's Type up its superclass chain (TIB→Type→super).
     * {@code ref}/{@code targetType} are raw addresses (references are direct
     * pointers, Types are laid out by the writer). The compiler synthesizes calls
     * to this for the {@code instanceof} bytecode.
     */
    // Walks the superclass chain; at each class, a match on the class's own Type or on
    // any interface in its itable directory (terminated by a 0 interfaceType) counts. So
    // this answers class instanceof and interface instanceof — including interfaces
    // inherited from a superclass, since each class's directory is checked on the way
    // up. Not yet: super-interfaces (a directory lists directly-declared interfaces, so
    // `x instanceof Base` where x implements `Greeter extends Base` is missed).
    static int instanceOf(long ref, long targetType)
    {
        if (ref == 0L)
        {
            return 0;    // null is never an instance
        }
        long tib = Magic.load64(ref);                  // header→TIB (@0)
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 0;    // a raw array (element size in @0, no Type): not an instance of any class/interface type
        }
        long type = Magic.load64(tib);                 // TIB→Type (@0); for a typed array, the array Type
        return typeAssignable(type, targetType) ? 1 : 0;
    }

    /** True if an array Type (its instanceSize slot carries the tag). */
    private static boolean isArrayType(long type)
    {
        return (Magic.load64(type) & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG;
    }

    /**
     * Is a value of Type {@code type} assignable to {@code targetType}? Walks {@code type}'s superclass chain
     * (matching the class Type or any interface in each level's itable directory), with a reference-array
     * covariance prefix: {@code String[]} is an {@code Object[]} iff {@code String} is an {@code Object}. Both
     * primitive-element arrays are invariant (only the exact-match in the walk succeeds). {@code arr instanceof
     * Object} works through the array Type's super = Object.
     */
    private static boolean typeAssignable(long type, long targetType)
    {
        if (type != targetType && targetType != 0L && isArrayType(type) && isArrayType(targetType))
        {
            long elemType = Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
            long elemTarget = Magic.load64(targetType + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
            if (elemType != 0L && elemTarget != 0L)    // both reference-element arrays: covariant on the element
            {
                return typeAssignable(elemType, elemTarget);
            }
            // a primitive element on either side is invariant: only the exact-match below can succeed
        }
        while (type != 0L)
        {
            if (type == targetType)
            {
                return true;
            }
            long dir = Magic.load64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET);
            if (dir != 0L)
            {
                long entry = dir;
                long iface = Magic.load64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
                while (iface != 0L)                    // 0 interfaceType terminates the directory
                {
                    if (iface == targetType)
                    {
                        return true;
                    }
                    entry += ObjectModel.ITABLE_ENTRY_SIZE;
                    iface = Magic.load64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
                }
            }
            type = Magic.load64(type + ObjectModel.TYPE_SUPER_OFFSET);
        }
        return false;
    }

    /** {@code checkcast} support: return {@code ref} if the cast holds, else halt
     *  (no exceptions yet). Null always passes. */
    static long checkCast(long ref, long targetType)
    {
        if (targetType == 0L)
        {
            return ref;    // unresolved target (e.g. an array type "[B" — arrays carry no Type): the class-file
                           // verifier already proved this cast holds, so trust it rather than walk a raw array
        }
        if (ref != 0L && instanceOf(ref, targetType) == 0)
        {
            while (true)
            {
                Magic.wfe();
            }
        }
        return ref;
    }

    // ----- exception unwinding --------------------------------------------
    // Addresses/counts of the handler and frame tables, filled by the writer.
    static long handlerTable, handlerCount;   // entries: {machineStart, machineEnd, handler, catchType}
    static long frameTable, frameCount;       // entries: {codeStart, codeEnd, frameSize}
    static long imageSymTable, imageSymCount; // stack-trace symbols: {codeStart, codeEnd, nameAddr, srcAddr, lineAddr}

    // A second frame table for methods JIT-compiled at runtime: their code isn't in
    // the image, so the writer can't describe them. The loader appends one entry per
    // compiled method (codeStart, codeEnd, frameSize) as it emits. Same triple
    // layout as frameTable; frameSizeAt consults both, so unwinding can pop a JIT'd
    // frame just like a compiled one.
    static long jitFrameTable, jitFrameCount;
    static long jitLocalTable, jitLocalCount;   // parallel {codeStart, codeEnd, regLocals} — unwind's pre-try local restore
    static long unwindLocBuf;                   // 16-slot scratch: reconstructs the handler's callee-saved locals during unwind

    /** Point the JIT unwind tables (frame/local/handler) at their fixed scratch region if not yet set. These
     *  tables persist for the whole run behind these static pointers, but every JIT frame keeps writing to them,
     *  so they must NOT live in the managed heap: the mark-sweep GC reclaims dead blocks onto the free list and
     *  the demand-loader rewinds the bump pointer per batch, either of which lets a later large allocation (e.g.
     *  a loaded classfile's byte[] copy) reuse the memory while addJitFrame scribbles it through the stale
     *  pointer -- which corrupted loaded classfiles (a big class's cp bytes) mid-compile. See Heap.JIT_TABLES. */
    static void ensureJitTables()
    {
        if (jitFrameTable == 0L)
        {
            // Fixed scratch addresses OUTSIDE the managed heap -- see Heap.JIT_TABLES for why these can't be
            // Heap.allocData'd (GC free-list / per-batch rewind would reuse the memory under the live pointer).
            jitFrameTable = Heap.JIT_TABLES;                        // JIT_FRAME_MAX*24 = 0x18000
            jitLocalTable = Heap.JIT_TABLES + JIT_FRAME_MAX * 24L;  // +0x18000
            jitHandlerTable = Heap.JIT_TABLES + JIT_FRAME_MAX * 48L; // +0x30000 ; handler = JIT_HANDLER_MAX*32 = 0x20000
        }
    }

    /** Record a JIT'd method's machine-PC range, frame size, and callee-saved local count, so unwind can pop it
     *  and restore its handler's pre-try locals (x19..x(19+regLocals-1), saved at [SP+8..]). */
    static void addJitFrame(long codeStart, long codeEnd, long frameSize, long regLocals)
    {
        ensureJitTables();
        if (jitFrameCount < JIT_FRAME_MAX)
        {
            long e = jitFrameTable + jitFrameCount * 24L;
            Magic.store64(e, codeStart);
            Magic.store64(e + 8L, codeEnd);
            Magic.store64(e + 16L, frameSize);
            long le = jitLocalTable + jitLocalCount * 24L;           // same pc-range, parallel table (frameSizeIn reads it)
            Magic.store64(le, codeStart);
            Magic.store64(le + 8L, codeEnd);
            Magic.store64(le + 16L, regLocals);
            jitFrameCount = jitFrameCount + 1L;
            jitLocalCount = jitLocalCount + 1L;
        }
    }

    /** Callee-saved local count of the JIT'd method covering machine PC {@code pc} (0 = none / image method). */
    static long jitRegLocalsAt(long pc)
    {
        return frameSizeIn(jitLocalTable, jitLocalCount, pc);        // 3rd word = regLocals
    }
    static final int JIT_FRAME_MAX = 4096;     // one BATCH's framed methods must fit (compacted at each
                                               //   rewind); 512 overflowed on the ~170-class Lisp closure and
                                               //   the unwinder could not size the dropped frames

    /**
     * Drop JIT frame/handler entries for code at/above {@code codeMark} — called by the Loader's batch
     * rewind, which just reclaimed that code. Stale entries would ALIAS the next batch's code (the arena
     * reuses the same addresses), so an unwind could match a dead batch's frame size/handler for a live
     * PC; they also filled the fixed tables across batches (the jitUnwindReady probe regressed to 'n').
     * Entries below the mark (pre-reclaim permanent code) are kept.
     */
    static void dropJitTablesAbove(long codeMark)
    {
        jitFrameCount = compactTable(jitFrameTable, jitFrameCount, 24L, codeMark);
        jitLocalCount = compactTable(jitLocalTable, jitLocalCount, 24L, codeMark);
        jitHandlerCount = compactTable(jitHandlerTable, jitHandlerCount, 32L, codeMark);
    }

    /** Keep only entries whose first word (code start) is below {@code codeMark}; the kept count. */
    private static long compactTable(long table, long count, long entryBytes, long codeMark)
    {
        if (table == 0L)
        {
            return 0L;
        }
        long kept = 0L;
        long i = 0L;
        while (i < count)
        {
            long src = table + i * entryBytes;
            if (Magic.load64(src) < codeMark)
            {
                long dst = table + kept * entryBytes;
                long b = 0L;
                while (b < entryBytes)
                {
                    Magic.store64(dst + b, Magic.load64(src + b));
                    b += 8L;
                }
                kept += 1L;
            }
            i += 1L;
        }
        return kept;
    }

    // A jit handler table paralleling the image handlerTable, so a metal-built/JIT'd method's
    // try/catch is findable during a cross-method unwind. Entries {machStart, machEnd, handler,
    // catchType} (32 bytes), same layout as handlerTable; findHandler consults both.
    static long jitHandlerTable, jitHandlerCount;
    static final int JIT_HANDLER_MAX = 4096;   // same sizing rule as JIT_FRAME_MAX

    /** Record a JIT'd method's try/catch range so a cross-method unwind can resume into it. */
    static void addJitHandler(long machStart, long machEnd, long handler, long catchType)
    {
        ensureJitTables();
        if (jitHandlerCount < JIT_HANDLER_MAX)
        {
            long e = jitHandlerTable + jitHandlerCount * 32L;
            Magic.store64(e, machStart);
            Magic.store64(e + 8L, machEnd);
            Magic.store64(e + 16L, handler);
            Magic.store64(e + 24L, catchType);
            jitHandlerCount = jitHandlerCount + 1L;
        }
    }

    /**
     * Unwind the stack looking for a handler for {@code exc}, starting at machine
     * PC {@code pc} with stack pointer {@code sp}. At each frame: if a try/catch
     * covers the PC and the type matches, resume there; otherwise pop the frame
     * (via its frame-table size, reading the saved LR at [sp]) and continue in the
     * caller. Halts if the exception reaches the top uncaught. (Callee-saved locals
     * are not restored during the walk — a handler must not read pre-try locals.)
     */
    static int unwindLog;                                  // #43: when != 0, log the first few exception throws
    static int unwindLogged;

    /**
     * Record the throw-site frame chain into {@code exc}'s inline backtrace (Throwable.bt0..bt7 @ exc+16..+72),
     * first throw only (a re-throw / cross-method unwind won't overwrite). {@code pc} is a code address in the
     * throwing method, {@code sp} its stack pointer. The metal JIT calls this at every {@code athrow} (via the
     * CAPTURE_TRACE helper) so {@code printStackTrace()} has frames even for a same-method inline catch;
     * {@link #unwind} also calls it (idempotent) for the uncaught path. Walks saved LRs with {@link #frameSizeAt}.
     */
    static void captureTrace(long exc, long pc, long sp)
    {
        if (exc <= 0x1000L || Magic.load64(exc + 16L) != 0L)   // boot force-compile passes 0; already captured -> keep
        {
            return;
        }
        long cpc = pc;
        long csp = sp;
        int n = 0;
        while (n < 8 && cpc > 0x1000L)
        {
            Magic.store64(exc + 16L + n * 8L, cpc);
            n += 1;
            long cfs = frameSizeAt(cpc);
            if (cfs == 0L)
            {
                break;                                         // top of the JIT/image stack
            }
            cpc = Magic.load64(csp) - 4L;                      // caller's return address (the call site)
            csp += cfs;
        }
        if (n < 8)
        {
            Magic.store64(exc + 16L + n * 8L, 0L);             // 0-terminate the backtrace
        }
    }

    static void unwind(long exc, long pc, long sp)
    {
        if (unwindLog != 0 && unwindLogged < 24)            // #43: name the FIRST exceptions thrown (root NPE first)
        {
            unwindLogged += 1;
            Uart.write(Magic.bytes("\n  THROW exc="));
            printHex(exc);
            if (exc > 0x1000L && exc < 0x40000000L)
            {
                long tib = Magic.load64(exc);
                if (tib > 0x1000L && tib < 0x40000000L) { Loader.reportClassOfType(Magic.load64(tib)); }
            }
            Uart.write(Magic.bytes(" at 0x"));
            printHex(pc);
            Loader.reportMethodAt(pc);
            Uart.putc(0x0A);
        }
        captureTrace(exc, pc, sp);                     // fill exc's backtrace if not already captured at the throw site
        if (unwindLocBuf == 0L)
        {
            unwindLocBuf = Heap.allocData(16 * 8);     // 16 callee-saved local slots, reused across unwinds
        }
        // Seed with the register snapshot AT THE THROW: this method's OWN prologue saved the throwing frame's
        // x19..x28 (10 slots) at [our_sp + 8 + k*8]. That captures every handler local a shallow-regLocals callee
        // preserved but never re-saved (its value flowed through untouched into our prologue's save). Slots that
        // an intervening frame DID save get overwritten below as we pop that frame.
        long mysp = Magic.readSP();
        long ls = 0L;
        while (ls < 16L)
        {
            if (ls < 10L)
            {
                Magic.store64(unwindLocBuf + ls * 8L, Magic.load64(mysp + 8L + ls * 8L));
            }
            else
            {
                Magic.store64(unwindLocBuf + ls * 8L, 0L);
            }
            ls += 1L;
        }
        while (true)
        {
            long h = findHandler(pc, exc);
            if (h != 0L)
            {
                // unwindLocBuf now holds the handler's live locals: each frame we popped saved its CALLER's
                // x19.. registers, and the last frame popped (the one the handler called into) saved the handler's
                // own locals -- so a catch/finally that reads a local set before the try sees the live value.
                faultDepth = 0;                                            // fault resolved by a handler: a later fault
                                                                           //   (incl. one inside the handler) is FRESH,
                                                                           //   not a nested unwind fault
                Magic.resume(h, sp, exc, jitRegLocalsAt(pc), unwindLocBuf);   // never returns
            }
            long fs = frameSizeAt(pc);
            if (fs == 0L)
            {
                // No frame entry for this pc: the exception reached the TOP uncaught (past main/boot). A valid
                // Throwable here is an EXPECTED uncaught exception (e.g. a JDK test throwing to signal failure) --
                // report it like a JVM ("Exception in thread \"main\" <class>: <message>"), not a VM error. Only
                // an absent/invalid exception object means a real frame-table gap (overflowed/unregistered method).
                long xt = Magic.load64(exc);
                if (xt > 0x1000L)
                {
                    Uart.write(Magic.bytes("\nException in thread \"main\" "));
                    Loader.printClassName(Magic.load64(xt));
                    long msg = Magic.load64(exc + 80L);          // Throwable.detailMessage (after the 8-slot backtrace)
                    if (msg > 0x1000L)
                    {
                        Uart.write(Magic.bytes(": "));
                        printStr(msg);
                    }
                    Uart.putc(0x0A);
                }
                else
                {
                    Uart.write(Magic.bytes("\nUNWIND LOST pc="));   // no valid exception object -> a genuine
                    printHex(pc);                                    //   frame-table gap, not an uncaught throw
                    Uart.write(Magic.bytes(" exc="));
                    printHex(exc);
                    Uart.putc(0x0A);
                }
                // print the captured stack trace (method + SourceFile + line) as printStackTrace does.
                int fi = 0;
                while (fi < 8)
                {
                    long fpc = Magic.load64(exc + 16L + fi * 8L);
                    if (fpc == 0L)
                    {
                        break;
                    }
                    Uart.write(Magic.bytes("  at "));
                    Loader.printFrameAt(fpc);
                    Uart.putc(0x0A);
                    fi += 1;
                }
                while (true)
                {
                    Magic.wfe();    // uncaught at the top
                }
            }
            // Overwrite ONLY the slots this frame saved (its caller's x19.. at [sp+8+k*8]); leave higher slots to
            // the seed / deeper frames. When the caller turns out to be the handler, these ARE its pre-try locals;
            // the frame the handler called into is popped last, so it wins for the slots it saved.
            long nrl = jitRegLocalsAt(pc);
            long k2 = 0L;
            while (k2 < nrl && k2 < 16L)
            {
                Magic.store64(unwindLocBuf + k2 * 8L, Magic.load64(sp + 8L + k2 * 8L));
                k2 += 1L;
            }
            pc = Magic.load64(sp) - 4L;             // the call site (return address - one instruction)
            sp = sp + fs;                           // pop this frame
        }
    }

    private static long findHandler(long pc, long exc)
    {
        long h = findHandlerIn(handlerTable, handlerCount, pc, exc);   // image methods
        if (h != 0L)
        {
            return h;
        }
        return findHandlerIn(jitHandlerTable, jitHandlerCount, pc, exc);   // metal-built / JIT'd methods
    }

    private static long findHandlerIn(long table, long count, long pc, long exc)
    {
        long i = 0L;
        while (i < count)
        {
            long e = table + i * 32L;
            if (pc >= Magic.load64(e) && pc < Magic.load64(e + 8L))
            {
                long catchType = Magic.load64(e + 24L);
                if (catchType == 0L || instanceOf(exc, catchType) != 0)
                {
                    return Magic.load64(e + 16L);
                }
            }
            i = i + 1L;
        }
        return 0L;
    }

    /** Frame size covering machine PC {@code pc}, from either table (0 = none). Package-visible for the self-check. */
    static long frameSizeAt(long pc)
    {
        long fs = frameSizeIn(frameTable, frameCount, pc);        // image methods
        if (fs != 0L)
        {
            return fs;
        }
        return frameSizeIn(jitFrameTable, jitFrameCount, pc);     // runtime JIT'd methods
    }

    /** Frame size of the {codeStart,codeEnd,frameSize} entry covering {@code pc}, or 0. */
    private static long frameSizeIn(long table, long count, long pc)
    {
        long i = 0L;
        while (i < count)
        {
            long e = table + i * 24L;
            if (pc >= Magic.load64(e) && pc < Magic.load64(e + 8L))
            {
                return Magic.load64(e + 16L);
            }
            i = i + 1L;
        }
        return 0L;
    }

    // ----- garbage collection (conservative mark-sweep) --------------------
    static final long STACK_TOP = 0x80000L;   // SP init; the stack grows down from here
    static long staticsStart, staticsEnd;     // image statics region, filled by the writer

    /**
     * Conservative mark-sweep, entered via {@code Magic.gc()} (which spills the
     * callee-saved registers so live references there are on the stack). Roots are
     * the stack [{@code scanFrom}, STACK_TOP) and the statics region; anything
     * transitively reachable survives, everything else is swept onto the free list.
     * Object sizes come from the status word — no per-type maps, and objects aren't
     * moved (so no precise stack maps are needed). May over-retain (false roots).
     */
    static void gcCollect(long scanFrom)
    {
        long daif = Magic.readDaif();                 // no preemption mid-collection: a switched-in task
        Magic.disableIrq();                           //   would allocate into the half-swept heap
        // Stale-root hygiene: reap dead-parked tasks BEFORE marking. A task that ran taskExit (BLOCKED on
        // the reserved dead semaphore) can never be rescheduled -- pickNext skips BLOCKED tasks -- so its
        // 32 KB heap stack, saved context, and guest Thread object are garbage; as roots they retained
        // everything their final frames happened to reference. Clearing the table entries makes the stack
        // object unreachable, so THIS collection sweeps it.
        int reaped = 0;
        if (taskState != null)
        {
            int t = 1;                                // task 0 is the boot flow; it never exits
            while (t < taskCount)
            {
                if (taskState[t] == TASK_BLOCKED && taskWaitOn[t] == 3 && taskStackBase[t] != 0L)
                {
                    taskStackBase[t] = 0L;
                    taskSp[t] = 0L;
                    taskThreadObj[t] = 0L;
                    taskState[t] = TASK_EMPTY;
                    reaped += 1;
                }
                t += 1;
            }
        }
        buildBlockBitmap(Magic.load64(Heap.PTR_CELL));     // pre-pass: exact block bases for the probes
        long stackTop = STACK_TOP;                    // boot task: SP runs down from the image stack top
        if (taskStackBase != null && curTask != 0 && taskStackBase[curTask] != 0L)
        {
            stackTop = taskStackBase[curTask] + 0x8000L;   // a spawned task: its stack is a heap object
        }
        markRange(scanFrom, stackTop);
        markRange(staticsStart, staticsEnd);
        int sc = 1;                                   // secondary cores' arenas are ROOT RANGES (never
        while (sc < 4)                                //   collected, but their tasks hold refs into core 0's
        {                                             //   heap — e.g. spawned Runnable receivers)
            markRange(Heap.arenaBase(sc), Magic.load64(Heap.PTR_CELL + sc * 8L));
            sc += 1;
        }
        boolean changed = true;                       // trace: mark fields of marked objects to a fixpoint
        while (changed)
        {
            changed = false;
            long o = Heap.BASE;
            long top = Magic.load64(Heap.PTR_CELL);
            while (o < top)
            {
                long st = Magic.load64(o + 8L);
                long size = st & -8L;
                if (size == 0L || o + size > top || o + size <= o)
                {
                    o = top;    // corrupt / out-of-bounds: stop the walk instead of dereferencing garbage
                }
                else
                {
                    if ((st & 1L) != 0L && markRange(o + 16L, o + size))
                    {
                        changed = true;
                    }
                    o = o + size;
                }
            }
        }
        Heap.resetFreeList();                          // sweep
        reclaimed = 0L;
        long walked = 0L;
        long markedN = 0L;
        long freedN = 0L;
        long o = Heap.BASE;
        long stop = Magic.load64(Heap.PTR_CELL);
        long stoppedAt = 0L;
        while (o < stop)
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L || o + size > stop || o + size <= o)
            {
                stoppedAt = o;                         // corrupt / out-of-bounds: stop
                o = stop;
            }
            else
            {
                walked = walked + 1L;
                if ((st & 1L) != 0L)
                {
                    markedN = markedN + 1L;
                    Magic.store64(o + 8L, size);    // unmark (clear bit0)
                }
                else
                {
                    freedN = freedN + 1L;
                    Heap.addFree(o, size);
                    reclaimed = reclaimed + size;
                }
                o = o + size;
            }
        }
        if (gcLog != 0)
        {
            Uart.write(Magic.bytes("  [gc walked="));
            printHex(walked);
            Uart.write(Magic.bytes(" marked="));
            printHex(markedN);
            Uart.write(Magic.bytes(" freed="));
            printHex(freedN);
            Uart.write(Magic.bytes(" bytes="));
            printHex(reclaimed);
            Uart.write(Magic.bytes(" reaped="));
            printHex(reaped);                          // dead-parked tasks whose stacks this collection freed
            Uart.write(Magic.bytes(" stopAt="));
            printHex(stoppedAt);                       // non-zero = the size walk hit a corrupt status
            Uart.write(Magic.bytes("]\n"));
        }
        if ((daif & 0x80L) == 0L)
        {
            Magic.enableIrq();                        // restore: only unmask if the caller had IRQs on
        }
    }

    static int gcLog;        // print per-collection stats (diagnostic)

    static long reclaimed;   // bytes freed by the last collection

    // ----- runtime class loading (M4) --------------------------------------
    static long guestBytes, guestLen;   // raw Guest.class blob, filled by the writer
    static long greeterBytes, greeterLen; // raw Greeter.class blob (an interface Guest loads)
    static long alphaBytes, alphaLen;   // raw Alpha.class blob (implements Greeter at vtable slot 0)
    static long betaBytes, betaLen;     // raw Beta.class blob (implements Greeter at vtable slot 1)
    static long myExcBytes, myExcLen;   // raw MyExc.class blob (a throwable Guest catches)
    static long mathBytes, mathLen;     // raw java.base java/lang/Math.class blob
    // The mini java.base + the demand-loaded program (pulled from classDir on the metal by the Loader).
    static long runnableBytes, runnableLen;         // java/lang/Runnable
    static long threadBytes, threadLen;             // java/lang/Thread
    static long semBytes, semLen;                   // java/util/concurrent/Semaphore
    static long philosopherBytes, philosopherLen;   // demo/Philosopher (a Runnable)
    static long philBytes, philLen;     // demo/DiningPhilosophers.class blob (the demand-loaded program root)
    static long stringBytes, stringLen;             // java/lang/String (result of string concat, M-B slice 1)
    static long concatDemoBytes, concatDemoLen;     // demo/ConcatDemo (the invokedynamic-concat program)
    static long lambdaDemoBytes, lambdaDemoLen;     // demo/LambdaDemo (the invokedynamic-lambda program, 1c)
    static long intOpBytes, intOpLen;               // demo/IntOp (a SAM-with-arg functional interface, 1d)
    static long integerBytes, integerLen;           // java/lang/Integer — a real, unmodified java.base class
    static long floatDemoBytes, floatDemoLen;       // demo/FloatDemo (verifies float/double support)
    static long nativeDemoBytes, nativeDemoLen;     // demo/NativeDemo (verifies provided java.base natives)
    static long stringBuilderBytes, stringBuilderLen; // java/lang/StringBuilder (real-shaped)
    static long strDemoBytes, strDemoLen;           // demo/StrDemo (verifies String + StringBuilder)
    // Mini exception hierarchy + the implicit-exception demo (null-deref NPE / array-bounds AIOOBE).
    static long throwableBytes, throwableLen;       // java/lang/Throwable
    static long exceptionBytes, exceptionLen;       // java/lang/Exception
    static long runtimeExcBytes, runtimeExcLen;     // java/lang/RuntimeException
    static long npeBytes, npeLen;                   // java/lang/NullPointerException
    static long ioobeBytes, ioobeLen;               // java/lang/IndexOutOfBoundsException
    static long aioobeBytes, aioobeLen;             // java/lang/ArrayIndexOutOfBoundsException
    static long excDemoBytes, excDemoLen;           // demo/ExcDemo
    // Mini collections.
    static long arrayListBytes, arrayListLen;       // java/util/ArrayList
    static long listBytes, listLen;                 // java/util/List (interface ArrayList implements)
    static long iterableBytes, iterableLen;         // java/lang/Iterable (List extends it)
    static long iteratorBytes, iteratorLen;         // java/util/Iterator
    static long arrayListIteratorBytes, arrayListIteratorLen;   // java/util/ArrayListIterator
    static long linkedListBytes, linkedListLen;     // java/util/LinkedList (second List impl)
    static long linkedListNodeBytes, linkedListNodeLen;         // java/util/LinkedListNode
    static long linkedListIteratorBytes, linkedListIteratorLen; // java/util/LinkedListIterator
    static long mapBytes, mapLen;                    // java/util/Map (interface HashMap implements)
    static long collectionBytes, collectionLen;      // java/util/Collection (List extends it)
    static long collectionsBytes, collectionsLen;    // java/util/Collections (static sort)
    static long comparableBytes, comparableLen;      // java/lang/Comparable (String implements it)
    static long numBytes, numLen;                    // demo/Num (a second Comparable type for the generic sort)
    static long comparatorBytes, comparatorLen;      // java/util/Comparator (functional iface; lambda target)
    static long orderBytes, orderLen;                // demo/Order (bound-instance-method-ref receiver)
    static long factoryBytes, factoryLen;            // demo/Factory (constructor-ref functional iface)
    static long predicateBytes, predicateLen;        // java/util/function/Predicate (filter)
    static long functionBytes, functionLen;          // java/util/function/Function (map)
    static long consumerBytes, consumerLen;          // java/util/function/Consumer (forEach)
    static long streamBytes, streamLen;              // demo/Stream (mini pipeline)
    static long binaryOpBytes, binaryOpLen;          // java/util/function/BinaryOperator (reduce accumulator)
    static long biConsumerBytes, biConsumerLen;      // java/util/function/BiConsumer (Map.forEach action)
    static long listDemoBytes, listDemoLen;         // demo/ListDemo
    static long objectBytes, objectLen;             // java/lang/Object (root: hashCode/equals slots for HashMap)
    static long hashMapBytes, hashMapLen;           // java/util/HashMap
    static long mapDemoBytes, mapDemoLen;           // demo/MapDemo
    static long longBytes, longLen;                 // java/lang/Long — a real, unmodified java.base class (probe)
    // Dep/native surface for real Integer.parseInt: mini Character.digit + the NumberFormatException hierarchy.
    static long characterBytes, characterLen;       // java/lang/Character (digit)
    static long illegalArgBytes, illegalArgLen;     // java/lang/IllegalArgumentException
    static long numberFmtBytes, numberFmtLen;       // java/lang/NumberFormatException
    static long parseAllDemoBytes, parseAllDemoLen;  // demo/ParseAllDemo (real Integer via reachable loadAll)
    // Real Integer.toString surface: mini StringLatin1 + DecimalDigits + the demo (String gained byte[]+coder).
    static long stringLatin1Bytes, stringLatin1Len; // java/lang/StringLatin1
    static long decimalDigitsBytes, decimalDigitsLen; // jdk/internal/util/DecimalDigits
    static long toStringDemoBytes, toStringDemoLen; // demo/ToStringDemo
    static long hexLongDemoBytes, hexLongDemoLen;   // demo/HexLongDemo (Integer.toHexString + Long.toString)
    static long longMoreDemoBytes, longMoreDemoLen; // demo/LongMoreDemo (Long.parseLong + Long.toHexString)
    static long arithExcBytes, arithExcLen;         // java/lang/ArithmeticException (Math.addExact overflow)
    static long mathIntDemoBytes, mathIntDemoLen;   // demo/MathIntDemo (floorDiv/floorMod/addExact)
    static long objectsBytes, objectsLen;           // java/util/Objects — a real, unmodified java.base class
    static long objectsDemoBytes, objectsDemoLen;   // demo/ObjectsDemo
    static long arraysBytes, arraysLen;             // java/util/Arrays — a real, unmodified java.base class
    static long arraysSupportBytes, arraysSupportLen; // jdk/internal/util/ArraysSupport (mini mismatch)
    static long arraysDemoBytes, arraysDemoLen;     // demo/ArraysDemo
    static long numberBytes, numberLen;             // java/lang/Number (Integer's super, for the vtable chain)
    static long integerCacheBytes, integerCacheLen; // java/lang/Integer$IntegerCache (statics read 0, clinit skipped)
    static long boxingDemoBytes, boxingDemoLen;     // demo/BoxingDemo (Integer.valueOf boxing via HashMap)
    static long strOpsDemoBytes, strOpsDemoLen;     // demo/StrOpsDemo (String indexOf/substring)
    static long fileDemoBytes, fileDemoLen;         // demo/FileDemo (M3: FileInputStream over the RAMFS)
    static long reflectDemoBytes, reflectDemoLen;   // demo/ReflectDemo (M4: Thread + Class reflection)
    static long wordCountBytes, wordCountLen;       // demo/WordCount (real-program milestone: main(String[]))
    static long gcDemoBytes, gcDemoLen;             // demo/GcDemo (GC milestone: churn >> arena size)
    static long lispDemoBytes, lispDemoLen;         // demo/LispDemo (long-running Lisp interpreter)
    static long charsetDemoBytes, charsetDemoLen;   // demo/CharsetDemo (new String(byte[]) / getBytes)
    // ----- self-build input: the compile-reachable class set, name-indexed (M5.5c step 2) -----
    static long classDir;               // directory of {nameAddr, nameLen, bytesAddr, bytesLen} entries
    static long classCount;             // number of directory entries
    // ----- M3: embedded read-only RAMFS -- file table, same directory shape as the class table -----
    static long fileDir;                // directory of {nameAddr, nameLen, bytesAddr, bytesLen} entries
    static long fileCount;              // number of files
    // Addresses of the runtime helpers the shared baseline compiler calls, stashed by
    // the writer so the on-metal JIT (via MetalSymbols) can BL them. Indexed to match
    // the ids in compiler/Symbols: heapAlloc=0, allocArray=1, gcCollect=2, instanceOf=3,
    // checkCast=4, unwind=5.
    static long heapAlloc;              // Heap.alloc(I)J, so on-metal `new` can BL it
    static long allocArray;            // Heap.allocArray(II)J
    static long gcCollect;             // VM.gcCollect(J)V
    static long instanceOfAddr;        // VM.instanceOf(JJ)I
    static long checkCastAddr;         // VM.checkCast(JJ)J
    static long unwindAddr;            // VM.unwind(JJJ)V
    static long captureTraceAddr;      // VM.captureTrace(JJJ)V — throw-site backtrace for printStackTrace()
    // Scheduler helpers the JIT-loaded mini java.base runtime BLs (Symbols ids 6..11).
    static long startThreadAddr;       // VM.startThread(J)V
    static long objWaitAddr;           // VM.objWait(JJ)V     — Object.wait
    static long objNotifyAddr;         // VM.objNotify(J)V    — Object.notify
    static long objNotifyAllAddr;      // VM.objNotifyAll(J)V — Object.notifyAll
    static long monEnterAddr;          // VM.monEnter(J)V     — monitorenter
    static long monExitAddr;           // VM.monExit(J)V      — monitorexit
    static long holdsLockAddr;         // VM.holdsLock(J)I    — Thread.holdsLock
    static long interruptAddr;         // VM.interrupt(J)V    — Thread.interrupt
    static long isInterruptedAddr;     // VM.isInterrupted(J)I— Thread.isInterrupted
    static long checkIntrAddr;         // VM.checkClearInterrupt()I — Thread.sleep interruption check
    static long isAliveAddr;           // VM.isAlive(J)I      — Thread.isAlive
    static long joinTimedAddr;         // VM.joinTimed(JJ)I   — Thread.join(Duration)
    static long parkAddr;              // VM.park()V          — LockSupport.park
    static long unparkAddr;            // VM.unpark(J)V       — LockSupport.unpark
    static long threadJoinAddr;        // VM.threadJoin(J)V   — Thread.join
    static long semWaitAddr;           // VM.semWait(I)V
    static long semPostAddr;           // VM.semPost(I)V
    static long sleepAddr;             // VM.sleep(J)V
    static long newSemAddr;            // VM.newSem(I)I
    static long philReportAddr;        // VM.philReport(II)V
    static long taskExitAddr;          // VM.taskExit()V — the run-trampoline's tail (loader-emitted BL)
    // invokedynamic string-concat helpers (JIT'd concat lowering BLs these).
    static long scStartAddr;           // VM.scStart()J
    static long scCharAddr;            // VM.scChar(JI)V
    static long scIntAddr;             // VM.scInt(JI)V
    static long scEndAddr;             // VM.scEnd(J)J
    static long scStrAddr;             // VM.scStr(JJ)V   — append a String/byte[] (slice 1b)
    static long scLongAddr;            // VM.scLong(JJ)V  — append a long in decimal (slice 1b)
    static long printStrAddr;          // VM.printStr(J)V
    static long denylistTrapAddr;      // VM.denylistTrap()V — patchRelocs points calls into pruned classes here (#43)
    // Provided java.base natives the on-metal Loader wires guest calls to (Loader.nativeBuf).
    static long nanoTimeAddr;          // VM.nanoTime()J
    static long currentTimeMillisAddr; // VM.currentTimeMillis()J
    static long identityAddr;          // VM.identity(J)J — the *Bits* pass-throughs
    static long arraycopyAddr;         // VM.arraycopy(JIJII)V — System.arraycopy
    // Implicit-exception constructors the JIT calls on a failed null/bounds check (writer-stashed).
    static long newNpeAddr;            // VM.newNpe()J    — a java/lang/NullPointerException
    static long newAioobeAddr;         // VM.newAioobe()J — a java/lang/ArrayIndexOutOfBoundsException
    static long newArithAddr;          // VM.newArith()J  — a java/lang/ArithmeticException (divide by zero)
    static long newAseAddr;            // VM.newAse()J    — a java/lang/ArrayStoreException (aastore mismatch)
    static long arrayStoreOkAddr;      // VM.arrayStoreOk(JJ)I — aastore covariant type check
    static long printStackTraceAddr;   // VM.printStackTrace(J)V — Throwable.printStackTrace0() native (self in x0)
    static long fileOpenAddr;          // VM.fileOpen(J)J — FileInputStream.open0(String) native (M3 RAMFS)
    static long dnsResolveAddr;        // VM.dnsResolve(J)I — java.net.InetAddress.resolve0(byte[]) native (M3)
    static long vhFieldOffsetAddr;     // VM.vhFieldOffset(JJ)J — java.lang.invoke.VarHandle.fieldOffset0 (M3)
    static long fieldModsAddr;         // VM.fieldMods(JJ)I — Class.fieldMods0 (reflection)
    static long fieldTypeCharAddr;     // VM.fieldTypeChar(JJ)I — Class.fieldTypeChar0 (reflection)
    static long classAtPcAddr;         // VM.classAtPc(J)J — getCallerClass for the field-updater access check
    static long sockSocket0Addr;       // M3 socket natives (stock sun.nio.ch over net.Tcp)
    static long sockConnect0Addr;
    static long sockRead0Addr;
    static long sockWrite0Addr;
    static long sockClose0Addr;
    static long sockAvailableAddr;
    static long fdValAddr;
    static long setFdValAddr;
    static long sockNoopAddr;
    static long sockZeroAddr;
    static long classNameAddr;         // VM.classNameOf(J)J — Class.getName0(Class) native (M4)
    static long forNameAddr;           // VM.forName(J)J — Class.forName0(byte[]) native (reflection arc M1)
    static long defineClassAddr;       // VM.defineClass(JJJJ)J — ClassLoader.defineClass0 native (reflection M3)
    static long classModifiersAddr;    // VM.classModifiers(J)I — Class.getModifiers() native (reflection M1)
    static long methodResolveAddr;     // VM.methodResolve(JJ)I — Method.methodResolve0 (reflection M2)
    static long methodInfoAddr;        // VM.methodInfo(JJJ)I — Method.methodInfo0 (reflection M2)
    static long constructorResolveAddr;// VM.constructorResolve(JJ)I — Constructor.ctorResolve0 (reflection M2)
    static long allocInstanceAddr;     // VM.allocInstance(J)J — Constructor.allocInstance0 (reflection M2)
    static long superclassAddr;        // VM.superclassOf(J)J — Class.superclass0(Class) native (M4)
    static long currentThreadAddr;     // VM.currentThreadObj()J — Thread.currentThread0() native (M4)
    static long getClassAddr;          // VM.getClassOf(J)J — Object.getClass() intrinsic
    static long arrayCloneAddr;        // VM.arrayClone(J)J — [T.clone() intrinsic (no vtable on array TIBs)
    static long newReflectArrayAddr;   // VM.newReflectArray(JJ)J — reflect/Array.newInstance0 (typed ref array)
    static long componentTypeAddr;     // VM.componentTypeOf(J)J — Class.getComponentType0 (array element mirror)
    static long reportFaultAddr;       // VM.reportFault()V — the exception-vector handler's address
    static long irqHandlerAddr;        // VM.irqHandler()V — the IRQ-vector handler's address (writer-stashed)
    static long scheduleAddr;          // VM.schedule(J)J — the timer-path switcher (writer-stashed)
    static long yieldPickAddr;         // VM.yieldPick(J)J — the SVC/yield-path switcher (writer-stashed)
    static long taskAAddr;             // VM.taskA()V — demo task entry (writer-stashed)
    static long taskBAddr;             // VM.taskB()V — demo task entry (writer-stashed)
    static long taskCAddr;             // VM.taskC()V — demo task entry (writer-stashed)
    static long taskRAddr;             // VM.taskR()V — UART reader task entry (writer-stashed)
    static long secondaryMainAddr;     // VM.secondaryMain(I)V — secondary-core entry (writer-stashed)
    static long ticks;                 // periodic timer interrupts serviced so far

    /** Mark every heap object pointed to by an 8-aligned word in [lo,hi). Returns true if any newly marked. */
    private static boolean markRange(long lo, long hi)
    {
        boolean any = false;
        while (lo < hi)
        {
            long w = Magic.load64(lo);
            if (tryMark(w))
            {
                any = true;
            }
            if (tryMark(w - 16L))                      // a raw-data payload pointer (Heap.allocData) sits one
            {                                          //   header past its block base: probe that base too
                any = true;
            }
            lo = lo + 8L;
        }
        return any;
    }

    /** Mark {@code w} if it IS an unmarked heap block base; true if newly marked. Verified against the
     *  block-start bitmap ({@link #buildBlockBitmap}) — a status-word heuristic alone would sometimes
     *  accept an object INTERIOR and the {@code +1} mark write would corrupt real data (a stored pointer
     *  became odd and faulted the next dispatch). With the bitmap, mark writes only ever touch genuine
     *  status words. */
    private static boolean tryMark(long w)
    {
        if (w >= Heap.BASE && w < Magic.load64(Heap.PTR_CELL) && (w & 7L) == 0L && isBlockBase(w))
        {
            long st = Magic.load64(w + 8L);
            if ((st & 1L) == 0L)
            {
                Magic.store64(w + 8L, st + 1L);        // set mark bit
                return true;
            }
        }
        return false;
    }

    /** Block-start bitmap: 1 bit per 8 bytes of core 0's arena (3 MiB at a fixed scratch address, above
     *  the secondary stacks and below the heap cells). Rebuilt by each collection's pre-pass. */
    static final long MARK_BITMAP = 0x03B0_0000L;

    /** Pre-pass: walk the heap by status sizes (sound — every allocation carries an intact header) and
     *  record each block base in the bitmap, so conservative probes can be verified exactly. */
    private static void buildBlockBitmap(long stop)
    {
        long z = MARK_BITMAP;
        long zEnd = MARK_BITMAP + 0x30_0000L;
        while (z < zEnd)
        {
            Magic.store64(z, 0L);
            z += 8L;
        }
        long o = Heap.BASE;
        while (o < stop)
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L || o + size > stop || o + size <= o)
            {
                o = stop;                              // corrupt: stop (matches the sweep's guard)
            }
            else
            {
                long idx = (o - Heap.BASE) >> 3;
                long w = MARK_BITMAP + ((idx >> 6) << 3);
                Magic.store64(w, Magic.load64(w) | (1L << (int) (idx & 63L)));
                o += size;
            }
        }
    }

    private static boolean isBlockBase(long w)
    {
        long idx = (w - Heap.BASE) >> 3;
        long word = MARK_BITMAP + ((idx >> 6) << 3);
        return (Magic.load64(word) & (1L << (int) (idx & 63L))) != 0L;
    }

    /**
     * The program proper — a framed method (so operand values can spill across
     * calls). Prints the banner, then exercises the object model: allocate a
     * heap object, mutate its field, and print the result.
     */
    /** Print {@code v} (0..9999) in decimal, no leading zeros. Uses only / and * (no irem). */
    public static void printDec(int v)
    {
        int th = v / 1000;
        int hu = (v - th * 1000) / 100;
        int te = (v - th * 1000 - hu * 100) / 10;
        int on = v - th * 1000 - hu * 100 - te * 10;
        if (th > 0)
        {
            Uart.putc(0x30 + th);
        }
        if (th > 0 || hu > 0)
        {
            Uart.putc(0x30 + hu);
        }
        if (th > 0 || hu > 0 || te > 0)
        {
            Uart.putc(0x30 + te);
        }
        Uart.putc(0x30 + on);
    }

    /** Print an unsigned decimal (full range), most-significant digit first. */
    private static void printUnsigned(long v)
    {
        if (v >= 10L)
        {
            printUnsigned(v / 10L);
        }
        Uart.putc((int) (0x30L + v % 10L));
    }

    /** Print a signed 32-bit decimal (full range; -v as long avoids MIN_VALUE overflow). */
    private static void printSigned(int v)
    {
        if (v < 0)
        {
            Uart.putc(0x2D);                            // '-'
            printUnsigned(-((long) v));
        }
        else
        {
            printUnsigned(v);
        }
    }

    /** Print one real Integer.parseInt result: the input string, the parsed int, PASS/FAIL vs {@code expect}. */
    private static void parseShow(byte[] ascii, int expect)
    {
        int r = Loader.runParseInt(ascii);
        Uart.write(Magic.bytes("  parseInt(\""));
        Uart.write(ascii);
        Uart.write(Magic.bytes("\") = "));
        printSigned(r);
        Uart.write(r == expect ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL\n"));
    }

    /** Print one real-java.base probe line: label = 0x<low32> PASS/FAIL vs the JDK-known {@code expect}. */
    private static void probeShow(byte[] label, long result, int expect)
    {
        Uart.write(Magic.bytes("  "));
        Uart.write(label);
        Uart.write(Magic.bytes(" = "));
        printHex(result & 0xFFFFFFFFL);
        Uart.write(Loader.probeFound == 0 ? Magic.bytes("  MISSING\n")
                 : ((int) result) == expect ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL\n"));
    }

    // M5 (metal self-build / byte-for-byte fixpoint) is DEPRECATED: the host BuildRuntimeImage writer is the
    // only image producer now (stock-java.base pivot). The self-build + fixpoint + SD-persist tail of run() is
    // retired -- gated off here rather than deleted, pending the embedding rework. See the plan file.
    private static final boolean SELF_BUILD = false;
    // NON-final on purpose: the writer's BFS compiles the WiFi subsystem (guarded call in run()) into the
    // image regardless. Now live (M1), but the RUN is additionally gated on real hardware in run() via
    // Uart.coreHz: QEMU's mailbox reports ~0 for the measured core clock (baud falls back, "core 0MHz"),
    // a real Pi 4 reports ~166 MHz — so the WiFi driver only pokes the SDIO controller on real silicon,
    // never QEMU (whose 0xFE300000 is the SD card).
    static boolean WIFI_ENABLED = true;
    // true = boot straight to WiFi, skipping SMP/scheduler/all demos (fast flash cycles). false = the full
    // boot (demos + scheduler + SMP), with WiFi run at the end on real hardware (WIFI_ENABLED gate).
    static final boolean WIFI_ONLY = false;

    static void run()
    {
        Uart.write(Magic.bytes("hello from joe-ng\n"));     // putc turns \n into \r\n
        Uart.write(Magic.bytes("core "));                 // the clock we calibrated the baud to
        printDec(Uart.coreHz / 1000000);                  // MHz (0 = mailbox gave no answer)
        Uart.write(Magic.bytes("MHz\n"));

        // Enable the identity-mapped MMU now -- after the mailbox (the one DMA path) has run with the MMU
        // off, and before anything that needs cacheable/coherent RAM (SMP + the HW spinlock). RAM becomes
        // Normal cacheable, MMIO stays Device; every secondary core enables it too (see secondaryMain).
        buildPageTables();
        enableMmuThisCore();
        Uart.write(Magic.bytes("mmu on\n"));

        // TEMP (WiFi iteration): skip SMP + the whole demo suite and go straight to WiFi, for fast flash
        // cycles. static-final so the demos are dead-code-eliminated (smaller/faster image). Unlike the old
        // wifi-only path, IRQ-driven RX needs a scheduler, so stand up a MINIMAL one (task 0 only, no demo
        // tasks) before bringUp. Set WIFI_ONLY false to restore the full boot before merging to main.
        if (WIFI_ONLY)
        {
            if (WIFI_ENABLED && Uart.coreHz > 10000000)
            {
                startWifiScheduler();                      // task table + switch vectors + timer + IRQs
                board.bcm2711.Wifi.bringUp();
            }
            else
            {
                Uart.write(Magic.bytes("(wifi-only: not real hardware -> skipped)\n"));
            }
            return;
        }

        // OS networking service: if the manifest program needs the network (net=1) and we're on real HW,
        // bring the WiFi interface UP (join + DHCP + ARP, publishing net.Ip) as an OS service BEFORE the
        // launch -- so a program's java.net.Socket finds an established link. Connectivity only, no demo.
        if (Uart.coreHz > 10000000 && manifestNet())
        {
            startWifiScheduler();                          // scheduler + IRQs (IRQ-driven RX + blocking sockets)
            board.cyw43.Cyw43.runDemo = false;             // stop the bring-up after connectivity, no HTTP demo
            board.bcm2711.Wifi.bringUp();
        }

        // A launched program may spawn threads (Thread.start / Object.wait / Thread.join), which need the
        // switch machinery + timer. The WiFi path already started it; otherwise stand up the minimal
        // scheduler (task 0 + vectors + timer, no demo tasks) before the launch so threaded mains work.
        if (taskSp == null)
        {
            startWifiScheduler();
        }

        // OS-like program launch: /etc/init (RAMFS) names the main() program this image runs. If present,
        // run it and stop -- the image behaves like a JVM running one application, not a demo script. Falls
        // through to the demo suite when no manifest is present (transitional).
        if (launchInit())
        {
            return;
        }

        // Self-hosting generation counter (M5.5d demo): a scratch SD sector survives across reboots,
        // so each time joe-ng reproduces + persists itself and reboots into the image it wrote, this
        // climbs -- visible proof the metal-written image is what booted, not the seed's.
        int gen = -1;
        if (Emmc.init() == 0)
        {
            gen = SelfBuild.readGeneration();
            Uart.write(Magic.bytes("generation "));
            printDec(gen);
            Uart.putc(0x0A);
        }

        // SMP: release cores 1-3 from the armstub spin table; each reports in and then waits for GO.
        bringUpSecondaries();
        // Per-core scheduling: all four cores pull jobs from a shared run queue, coordinated by a real
        // hardware spinlock (LDAXR/STLXR, working now that the MMU maps RAM cacheable/coherent). Each
        // each digit is one core taking one job under the hardware spinlock (LDAXR/STLXR, working now
        // that RAM is cacheable/coherent). The lock guarantees no job is taken twice; the per-core tally
        // printed after shows the 24 jobs were shared across the four A72s.
        Uart.write(Magic.bytes("smp jobs (digit = core that ran it): "));
        Magic.store64(CORE_FLAGS + 0L, 1L);                // GO
        Magic.dsb();
        smpWork(0);                                        // the primary is core 0
        // let stragglers on the other cores finish the last few jobs before we move on
        long q = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 20L;
        while (Magic.readCNTPCT_EL0() < q)
        {
        }
        Uart.write(Magic.bytes("\njobs/core: "));
        int jc = 0;
        while (jc < 4)
        {
            Uart.putc((byte) 0x63);                        // 'c'
            Uart.putc((byte) (0x30 + jc));
            Uart.putc((byte) 0x3D);                        // '='
            printDec((int) Magic.load64(CORE_FLAGS + 0x40L + jc * 8L));
            Uart.putc((byte) 0x20);
            jc = jc + 1;
        }
        Uart.putc(0x0A);

        // Per-core preemptive scheduling: each secondary brings up its OWN (banked) GIC PPI 30 + CNTP timer,
        // then round-robins TWO tasks under its own timer -- core c alternates task 0 (char 'A'+2c) and task 1
        // ('A'+2c+1) in pcSchedule (a real context switch, reusing the M7 switch stub). So cores 1-3 print
        // C/D, E/F, G/H interleaved -- every char appearing proves each core preempts between its own tasks.
        // Runs ~0.5 s; ticks/core then confirms each core's timer fired. Needs armstub8-joe.bin (group-1
        // PPIs); QEMU won't deliver to secondaries, so each core runs only task 0 (C/E/G) and ticks stay 0.
        pcSetup();
        Uart.write(Magic.bytes("per-core tasks (cores 1-3, 2 tasks each, ~0.5s): "));
        pcGo2 = 1;                                          // release the secondaries into pcCoreMain
        Magic.dsb();
        long pw = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 2L;
        while (Magic.readCNTPCT_EL0() < pw)
        {
        }
        pcStop = 1;                                         // stop them
        Magic.dsb();
        long pw2 = Magic.readCNTPCT_EL0() + Magic.readCNTFRQ_EL0() / 20L;
        while (Magic.readCNTPCT_EL0() < pw2)
        {
        }
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("ticks/core: "));
        int pi = 1;
        while (pi < 4)
        {
            Uart.putc((byte) 0x63);
            Uart.putc((byte) (0x30 + pi));
            Uart.putc((byte) 0x3D);
            printDec((int) pcTicks[pi]);
            Uart.putc((byte) 0x20);
            pi = pi + 1;
        }
        Uart.putc(0x0A);

        // M7: scheduling on the GIC-400 tick, exercising every task state. taskA prints 'A' then
        // yield()s (cooperative SVC switch -- shows in QEMU too); taskB (producer) prints 'B', posts
        // semaphore 0, then sleep(40ms) (SLEEPING); taskC (consumer) blocks on semaphore 0 (BLOCKED)
        // and prints 'C' only when B posts it -- so every 'B' is followed by a 'C'. The boot flow is
        // task 0, printing '.' and yield()ing. The ~10 ms timer also preempts on real HW. Bounded,
        // then re-mask so the self-build fixpoint below runs undisturbed.
        Uart.write(Magic.bytes("sched (.=main A=yield B=post->C blocked): "));
        startScheduler();

        long t0 = Magic.readCNTPCT_EL0();
        while (Magic.readCNTPCT_EL0() < t0 + Magic.readCNTFRQ_EL0() / 4L)   // ~250 ms
        {
            Uart.putc(0x2E);                   // '.' from the boot flow (task 0)
            schedPause();
            taskYield();                           // cooperatively hand the CPU to the next ready task
        }
        stopTimerTick();                       // disable timer + mask IRQs (freezes the other tasks)
        Uart.write(Magic.bytes("\nsched: "));
        printDec((int) ticks);
        Uart.write(Magic.bytes(" preemptions\n"));

        // (The interactive Console-device shell phase was removed — it needed keyboard input over the
        // UART and just idled the boot for 4 s in non-interactive runs. The scheduler/Console code stays;
        // the philosophers phase below re-arms the timer and resets the task table for its own run.)

        Cell c = new Cell(0x6A);           // 'j', set by the constructor (putfield)
        c.inc();                           // virtual dispatch through the TIB vtable -> 'k'
        Uart.putc(c.get());                // virtual dispatch: read the field back
        Uart.putc(0x0A);                   // newline

        byte[] a = new byte[3];            // runtime heap array (newarray/bastore)
        a[0] = 0x41;
        a[1] = 0x42;
        a[2] = 0x0A;   // "AB\n"
        Uart.write(a);

        Counter.bump();                    // static field in the image statics area
        Counter.bump();
        Counter.bump();
        Uart.putc(0x30 + Counter.get());   // '3'  (getstatic/putstatic)
        Uart.putc(0x0A);

        Uart.putc(Config.mark);            // '7' — set by Config's <clinit> at boot
        Uart.putc(0x0A);

        // class hierarchy: virtual dispatch on the static supertype hits the override
        Animal dog = new Dog();
        Uart.putc(dog.sound());            // 'W' — Dog overrides Animal.sound (same vtable slot)
        Animal animal = new Animal();
        Uart.putc(animal.sound());         // '?' — base implementation
        Uart.putc(0x0A);

        // instanceof / checkcast (subclass walk over the Type chain)
        Uart.putc(dog instanceof Dog ? 0x59 : 0x4E);       // 'Y' — a Dog is a Dog
        Uart.putc(animal instanceof Dog ? 0x59 : 0x4E);    // 'N' — an Animal is not a Dog
        Dog cast = (Dog) dog;                              // checkcast succeeds
        Uart.putc(cast.sound());                           // 'W'
        Uart.putc(0x0A);

        // interface dispatch via itables (invokeinterface)
        Speaker s1 = new Robot();
        Speaker s2 = new Phone();
        Uart.putc(s1.speak());                             // 'R'
        Uart.putc(s2.speak());                             // 'P'
        Uart.putc(0x0A);

        // exceptions: throw and catch by type in the same method
        try
        {
            throw new MyExc();
        }
        catch (MyExc e)
        {
            Uart.putc(0x45);                               // 'E' — caught
        }
        Uart.putc(0x0A);

        // cross-method: thrower() throws, catcher() (its caller) catches
        catcher();                                         // -> 'U'
        Uart.putc(0x0A);

        // GC: allocate garbage, collect, then show a freed block is reused
        gcGarbage();
        Magic.gc();
        Cell fresh = new Cell(0x2A);                       // should come from the free list
        Uart.putc(Heap.lastFromFreeList != 0 ? 0x52 : 0x4E); // 'R' reused / 'N' fresh bump
        Uart.putc(0x0A);

        // M4: parse+compile+run a class embedded only as raw bytes, on the metal
        Uart.putc(Loader.loadGuest());                     // '*' from Guest.answer(), JIT'd at runtime
        Uart.putc(Loader.loadMath());                      // 'M' from java.base java.lang.Math.max(0x4D,0x21)
        Uart.putc(0x0A);

        // M4 capstone: demand-load and RUN a whole program. demo/DiningPhilosophers is embedded only as
        // raw bytes; loadAndRun parses it, pulls java/lang/Thread, java/lang/Runnable,
        // java/util/concurrent/Semaphore and demo/Philosopher from the embedded mini java.base (classDir)
        // as it references them (logged "  load <class>"), JITs them, and runs main -- which spawns five
        // philosopher tasks. We JIT + spawn with IRQs still masked, then re-arm the scheduler so its own
        // timer preempts them: they think/eat/contend for fork semaphores for ~2 s. Deadlock is avoided by
        // fork ordering. Then stop the tick so the self-build fixpoint below runs undisturbed.
        Uart.write(Magic.bytes("dining philosophers (demand-loaded from embedded java.base):\n"));
        installSchedVectors();                             // rebuild the switch stubs (the GC demo freed them)
        taskCount = 1;                                     // fresh scheduler table: just task 0 (the boot flow)
        curTask = 0;
        taskState[0] = TASK_READY;
        Loader.loadAndRun();                               // JIT + spawn the philosopher tasks (IRQs masked)
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                 // preemption starts; the philosopher tasks run now
        long d0 = Magic.readCNTPCT_EL0();
        while (Magic.readCNTPCT_EL0() < d0 + Magic.readCNTFRQ_EL0() * 2L)   // ~2 s window
        {
            schedPause();
            taskYield();                                   // let the philosophers run; we're task 0
        }
        stopTimerTick();
        Uart.putc(0x0A);

        // Philosophers (the one demo with persistent scheduler tasks on the heap) is done; from here on it is
        // safe to reclaim the demand-load heap between batches so it stays within the A64 bl reach.
        Loader.armHeapReclaim();

        // M-B slice 1: invokedynamic string concat. demo/ConcatDemo uses "a"+b, which javac lowers to
        // invokedynamic StringConcatFactory.makeConcatWithConstants. The metal JIT intrinsifies it into a
        // byte[] build wrapped in a mini java/lang/String (demand-loaded from classDir), then prints it.
        Uart.write(Magic.bytes("invokedynamic string concat (demand-loaded):\n"));
        Loader.loadConcat();

        // M-B slice 1c: invokedynamic lambdas. demo/LambdaDemo's () -> ... sites lower to
        // invokedynamic LambdaMetafactory.metafactory; the metal JIT synthesises a lambda class per site
        // (captured fields + an itable thunk into the lambda body), so r.run() dispatches into the body.
        Uart.write(Magic.bytes("invokedynamic lambdas (demand-loaded):\n"));
        Loader.loadLambda();

        // Experiment: compile + run methods from a REAL, unmodified java.base class (java/lang/Integer).
        // First two pure methods (should just work), then a full-class load to see where the reach ends.
        Uart.write(Magic.bytes("real java.base (unmodified java/lang/Integer):\n"));
        Uart.write(Magic.bytes("  bitCount(0x0F0F0F0F)="));
        printDec(Loader.intBitCount(0x0F0F0F0F));                 // expect 16
        Uart.write(Magic.bytes("  reverse(0x00000001)="));
        printHex(Loader.intReverse(1) & 0xFFFFFFFFL);            // expect 0x80000000
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("  dependency+native surface of java/lang/Integer:\n"));
        Loader.loadIntegerFull();                                // static scan: classes Integer calls into

        // Float/double support: a demand-loaded class doing float+double arithmetic, conversions, compare.
        Uart.write(Magic.bytes("float/double (demand-loaded):\n"));
        Loader.loadFloat();

        // Provided java.base natives: a demand-loaded class calls real java.lang native methods (no
        // bytecode) that the loader wires to VM helpers.
        Uart.write(Magic.bytes("java.base natives (demand-loaded):\n"));
        Loader.loadNative();

        // Real-shaped String + StringBuilder: build a string with an append-chain, then call String
        // methods on it (length/charAt/equals/hashCode). String literals are now real String objects.
        Uart.write(Magic.bytes("String + StringBuilder (demand-loaded):\n"));
        Loader.loadStr();

        // Implicit (JVM-synthesised) exceptions: the JIT emits null/bounds checks that throw a real mini
        // exception object; catch clauses catch it (main-local and via cross-method unwind).
        Uart.write(Magic.bytes("implicit exceptions (demand-loaded):\n"));
        Loader.loadExc();

        // Mini collections: a real-shaped java/util/ArrayList (Object[] + grow via arraycopy).
        Uart.write(Magic.bytes("java/util/ArrayList (demand-loaded):\n"));
        Loader.loadList();

        // java/util/HashMap: String keys hashed/compared via their real hashCode/equals, dispatched
        // through the mini java/lang/Object root's vtable slots.
        Uart.write(Magic.bytes("java/util/HashMap (demand-loaded):\n"));
        Loader.loadMap();

        // Real-java.base probe: compile + run a battery of UNMODIFIED OpenJDK numeric methods (Integer/Long/
        // Math), each in isolation (transitively pulling same-class callees), checked against JDK-known
        // results. Pure/leaf methods that touch no static state -- the frontier of "real java.base on metal".
        Uart.write(Magic.bytes("real java.base numeric probe (unmodified Integer/Long/Math):\n"));
        long ib = integerBytes;
        int il = (int) integerLen;
        probeShow(Magic.bytes("Integer.bitCount(0xF0F0F0F0)"),        Loader.probeStatic(ib, il, Magic.bytes("bitCount"),              Magic.bytes("(I)I"),  0xF0F0F0F0L, 0L), 16);
        probeShow(Magic.bytes("Integer.numberOfLeadingZeros(1)"),     Loader.probeStatic(ib, il, Magic.bytes("numberOfLeadingZeros"),  Magic.bytes("(I)I"),  1L, 0L), 31);
        probeShow(Magic.bytes("Integer.numberOfTrailingZeros(0x10000)"), Loader.probeStatic(ib, il, Magic.bytes("numberOfTrailingZeros"), Magic.bytes("(I)I"), 0x10000L, 0L), 16);
        probeShow(Magic.bytes("Integer.reverseBytes(0x12345678)"),    Loader.probeStatic(ib, il, Magic.bytes("reverseBytes"),          Magic.bytes("(I)I"),  0x12345678L, 0L), 0x78563412);
        probeShow(Magic.bytes("Integer.reverse(1)"),                  Loader.probeStatic(ib, il, Magic.bytes("reverse"),               Magic.bytes("(I)I"),  1L, 0L), 0x80000000);
        probeShow(Magic.bytes("Integer.highestOneBit(100)"),          Loader.probeStatic(ib, il, Magic.bytes("highestOneBit"),         Magic.bytes("(I)I"),  100L, 0L), 64);
        probeShow(Magic.bytes("Integer.signum(-5)"),                  Loader.probeStatic(ib, il, Magic.bytes("signum"),                Magic.bytes("(I)I"),  -5L, 0L), -1);
        probeShow(Magic.bytes("Integer.rotateLeft(1,4)"),             Loader.probeStatic(ib, il, Magic.bytes("rotateLeft"),            Magic.bytes("(II)I"), 1L, 4L), 16);
        probeShow(Magic.bytes("Integer.compare(3,7)"),                Loader.probeStatic(ib, il, Magic.bytes("compare"),               Magic.bytes("(II)I"), 3L, 7L), -1);
        probeShow(Magic.bytes("Long.bitCount(0xFF)"),                 Loader.probeStatic(longBytes, (int) longLen, Magic.bytes("bitCount"), Magic.bytes("(J)I"), 0xFFL, 0L), 8);
        probeShow(Magic.bytes("Math.abs(-42)"),                       Loader.probeStatic(mathBytes, (int) mathLen, Magic.bytes("abs"),      Magic.bytes("(I)I"), -42L, 0L), 42);

        // Close the dep/native wall: run the UNMODIFIED JDK Integer.parseInt(String,int) end-to-end, against
        // a mini dep surface (Character.digit + NumberFormatException hierarchy) -- real String parsing on metal.
        Uart.write(Magic.bytes("real Integer.parseInt (unmodified JDK + mini deps):\n"));
        Loader.loadParseInt();
        parseShow(Magic.bytes("0"), 0);
        parseShow(Magic.bytes("42"), 42);
        parseShow(Magic.bytes("-7"), -7);
        parseShow(Magic.bytes("12345"), 12345);
        parseShow(Magic.bytes("1000000"), 1000000);
        parseShow(Magic.bytes("2147483647"), 2147483647);
        parseShow(Magic.bytes("-2147483648"), -2147483648);

        // Reachable loadAll: load the real Integer through the NORMAL closure path (not isolated compile) and
        // run parseInt -- loadAll now compiles only the methods the entry reaches, so real Integer's
        // unreachable methods (toString/format) don't drag in unbuilt deps.
        Uart.write(Magic.bytes("real Integer via reachable loadAll:\n"));
        Loader.loadIntegerReachable();

        // Real Integer.toString: the produce-a-String direction -- real toString builds its result via
        // DecimalDigits + the real byte[]+coder String constructor.
        Uart.write(Magic.bytes("real Integer.toString (unmodified JDK + mini deps):\n"));
        Loader.loadIntegerToString();

        // Real Integer.toHexString (formatUnsignedInt + the loader-seeded Integer.digits) and Long.toString
        // (the DecimalDigits long overloads).
        Uart.write(Magic.bytes("real Integer.toHexString + Long.toString (unmodified JDK):\n"));
        Loader.loadHexLong();

        // Real Long.parseLong + Long.toHexString.
        Uart.write(Magic.bytes("real Long.parseLong + Long.toHexString (unmodified JDK):\n"));
        Loader.loadLongMore();

        // Real integer Math: floorDiv/floorMod (pure) + addExact (real ArithmeticException on overflow).
        Uart.write(Magic.bytes("real Math floorDiv/floorMod/addExact (unmodified JDK):\n"));
        Loader.loadMathInt();

        // Real java.util.Objects: equals/hashCode via the Object root's vtable, requireNonNull's NPE.
        Uart.write(Magic.bytes("real java.util.Objects (unmodified JDK):\n"));
        Loader.loadObjects();

        // Real java.util.Arrays: fill/equals/binarySearch on int[].
        Uart.write(Magic.bytes("real java.util.Arrays (unmodified JDK):\n"));
        Loader.loadArrays();

        // Real Integer.valueOf autoboxing: boxed Integer keys in a HashMap (real hashCode/equals dispatch).
        Uart.write(Magic.bytes("real Integer.valueOf boxing via HashMap (unmodified JDK):\n"));
        Loader.loadBoxing();

        // String indexOf/substring on the real-shaped mini String.
        Uart.write(Magic.bytes("String indexOf/substring (demand-loaded):\n"));
        Loader.loadStrOps();

        // M3: java.io -- the guest FileInputStream overlay reading the embedded read-only RAMFS.
        Uart.write(Magic.bytes("java.io FileInputStream (embedded RAMFS):\n"));
        Loader.loadFileIo();

        // M4: Thread identity (currentThread/getName) + Class reflection (getName/isInstance/...).
        Uart.write(Magic.bytes("Thread + Class reflection (M4):\n"));
        Loader.loadReflect();

        // The real-program milestone: ordinary stock-Java WordCount from main(String[]) -- must match
        // the host JDK's output byte-for-byte on the same input file.
        Uart.write(Magic.bytes("WordCount (a real Java program, main(String[])):\n"));
        Loader.loadWordCount();

        // The charset closure: stock new String(byte[]) + getBytes() via the UTF-8 fast path.
        Uart.write(Magic.bytes("charset: new String(byte[]) / getBytes() (stock, UTF-8 fast path):\n"));
        Loader.loadCharset();

        // The GC milestone: churn far beyond the arena size -- completes only if allocation pressure
        // triggers collections (Heap.alloc -> Magic.gc) and the freed blocks are reused.
        Uart.write(Magic.bytes("GC under allocation pressure (churn >> heap):\n"));
        Loader.loadGcDemo();
        Loader.printCodeArena();                           // code-arena rewind evidence: cur far below high

        // The long-running-program milestone: a Lisp interpreter whose churn forces collections
        // mid-computation -- every evaluation afterwards must still be correct.
        Uart.write(Magic.bytes("Lisp interpreter (long-running, stock java.base):\n"));
        Loader.loadLisp();

        // The runs above JIT-compiled framed methods and registered their frames.
        // Prove VM.unwind can now size a JIT'd frame: pick a real registered entry
        // and check frameSizeAt finds it in range and rejects a PC just past it.
        Uart.putc(jitUnwindReady() ? 0x46 : 0x6E);         // 'F' frame found / 'n' not
        Uart.putc(0x0A);

        // WiFi (CYW43455) bring-up -- the real-hardware finale, after the full feature showcase above.
        // IRQ-driven RX needs the context-switch machinery + a live timer + IRQs, all of which the demo tail
        // tore down (stopTimerTick after the philosophers; the GC/JIT churn also freed the switch stubs). So
        // re-arm a MINIMAL scheduler -- just task 0 (this boot flow), no A/B/C demo tasks -- and turn IRQs
        // back on, so the WiFi task can block in semWaitTimeout and be woken by the SDIO card interrupt (SPI
        // 158) instead of busy-polling. Guarded by the non-final WIFI_ENABLED flag (so the writer still
        // compiles the subsystem in) and HW-gated on coreHz: QEMU reports 0 and skips (no CYW43 there; its
        // 0xFE300000 is the SD card).
        if (WIFI_ENABLED && board.bcm2711.Uart.coreHz > 10000000)
        {
            installSchedVectors();                         // rebuild the switch stubs the GC/JIT demos freed
            taskCount = 1;                                 // fresh table: only task 0 -- no demo tasks to spew
            curTask = 0;
            taskState[0] = TASK_READY;
            Magic.writeCNTP_TVAL_EL0(timerReload);
            Magic.writeCNTP_CTL_EL0(1);                    // re-arm the periodic timer tick
            Magic.enableIrq();                             // IRQs on: SDIO SPI 158 + timer deadline wakes
            board.bcm2711.Wifi.bringUp();
            stopTimerTick();                               // WiFi done: disable the timer + mask IRQs before parking
        }

        // --- M5 self-build / fixpoint / SD-persist RETIRED. The deprecated metal-writer verification +
        //     image-reproduction engine moved verbatim to vm/SelfBuild.java (kept as reference, like EmitBoot);
        //     the host writer.BuildRuntimeImage is the source of truth. SELF_BUILD is permanently false, so
        //     SelfBuild.demo never runs -- flip it only to exercise the retired self-hosting path by hand. ---
        if (SELF_BUILD)
        {
            SelfBuild.demo(gen);
        }
        else
        {
            Uart.write(Magic.bytes("(self-build retired; host writer only)\n"));
        }
    }

    /** Scan the class table for the name at [nameAddr,nameLen); return its class bytes address, or 0. */
    /** Locate a class's raw bytes by name via BINARY SEARCH over the name-sorted directory (entries are 32B
     *  {nameAddr, nameLen, bytesAddr, bytesLen}). The whole stock java.base is embedded, so this must scale. */
    static long findClass(long nameAddr, long nameLen)   // package-private: also used by SelfBuild.classTableReady
    {
        long lo = 0L;
        long hi = classCount - 1L;
        while (lo <= hi)
        {
            long mid = (lo + hi) >> 1;
            long e = classDir + mid * 32L;
            int cmp = nameCompare(Magic.load64(e), Magic.load64(e + 8L), nameAddr, nameLen);
            if (cmp == 0)
            {
                return Magic.load64(e + 16L);          // bytesAddr
            }
            if (cmp < 0)
            {
                lo = mid + 1L;                         // entry name < target -> search right
            }
            else
            {
                hi = mid - 1L;
            }
        }
        return 0L;
    }

    /** Byte-lexicographic compare of class name {@code a} vs {@code b} (ASCII; matches the host String sort). */
    private static int nameCompare(long aAddr, long aLen, long bAddr, long bLen)
    {
        long n = aLen < bLen ? aLen : bLen;
        long i = 0L;
        while (i < n)
        {
            int d = (Magic.load8(aAddr + i) & 0xFF) - (Magic.load8(bAddr + i) & 0xFF);
            if (d != 0)
            {
                return d;
            }
            i = i + 1L;
        }
        return (int) (aLen - bLen);
    }

    /** Class-directory lookup for the on-metal {@link Loader}: bytes address for [namePtr,len), or 0. */
    static long dirBytes(long namePtr, long len)
    {
        return findClass(namePtr, len);
    }

    /** Companion to {@link #dirBytes}: the embedded class's byte length for [namePtr,len), or 0. */
    static long dirLen(long namePtr, long len)
    {
        long i = 0L;
        while (i < classCount)
        {
            long e = classDir + i * 32L;
            if (Magic.load64(e + 8L) == len && bytesEqual(Magic.load64(e), namePtr, len))
            {
                return Magic.load64(e + 24L);
            }
            i = i + 1L;
        }
        return 0L;
    }

    /** Whether {@code len} bytes at {@code a} equal those at {@code b}. */
    private static boolean bytesEqual(long a, long b, long len)
    {
        long i = 0L;
        while (i < len)
        {
            if (Magic.load8(a + i) != Magic.load8(b + i))
            {
                return false;
            }
            i = i + 1L;
        }
        return true;
    }

    /** True if frameSizeAt resolves a real JIT'd frame but not a PC outside it. */
    private static boolean jitUnwindReady()
    {
        if (jitFrameCount == 0L)
        {
            return false;                                  // no framed JIT'd method ran
        }
        // The LAST entry: code buffers allocate in ascending order, so no frame is ever registered past
        // its end — the "just past" negative check can't collide with a NEIGHBORING method that happens
        // to share the same frame size (probing the FIRST entry did, and the answer flipped with layout).
        long e = jitFrameTable + (jitFrameCount - 1L) * 24L;
        long start = Magic.load64(e);
        long end = Magic.load64(e + 8L);
        long size = Magic.load64(e + 16L);
        return frameSizeAt(start) == size                  // inside -> its frame size
               && frameSizeAt(end) != size;                // just past the end -> not this frame
    }

    /** Allocate objects that become unreachable, giving the collector something to reclaim. */
    private static void gcGarbage()
    {
        int i = 0;
        while (i < 8)
        {
            Cell junk = new Cell(i);                       // dead as soon as the next iteration overwrites it
            i = i + 1;
        }
    }

    private static void thrower()
    {
        throw new MyExc();
    }

    private static void catcher()
    {
        try
        {
            thrower();                                     // throws; unwinds into this frame
        }
        catch (MyExc e)
        {
            Uart.putc(0x55);                               // 'U' — caught after unwinding
        }
    }
}
