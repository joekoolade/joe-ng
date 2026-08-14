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
        if (newArithAddr == 0L) { long u = newArith(); }
        if (getClassAddr == 0L) { long u = getClassOf(0L); }          // Object.getClass() intrinsic
        if (arrayCloneAddr == 0L) { long u = arrayClone(0L); }        // [T.clone() intrinsic
        if (newReflectArrayAddr == 0L) { long u = newReflectArray(0L, 0L); } // reflect/Array.newInstance0
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
     * {@code java.lang.reflect.Array.newInstance0(Class, int)} native: a raw {@code length}-element reference
     * array (8-byte elements), UNTYPED (elem-size TIB, no {@code [L<component>;} array-Type). Backs the temp/
     * work arrays TimSort/ComparableTimSort/Arrays.copyOf allocate reflectively. The component mirror is
     * ignored (typed reflective arrays need runtime array-Type construction; deferred).
     */
    static long newReflectArray(long componentMirror, long length)
    {
        if (length < 0L)
        {
            return 0L;                                     // boot force-compile passes 0; guest checks negative first
        }
        return Heap.allocArray((int) length, 8);           // 8-byte reference elements (raw array header)
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

    /** Record a JIT'd method's machine-PC range, frame size, and callee-saved local count, so unwind can pop it
     *  and restore its handler's pre-try locals (x19..x(19+regLocals-1), saved at [SP+8..]). */
    static void addJitFrame(long codeStart, long codeEnd, long frameSize, long regLocals)
    {
        if (jitFrameTable == 0L)
        {
            jitFrameTable = Heap.allocData(JIT_FRAME_MAX * 24);      // JIT_FRAME_MAX * 24 bytes
            jitLocalTable = Heap.allocData(JIT_FRAME_MAX * 24);
        }
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
        if (jitHandlerTable == 0L)
        {
            jitHandlerTable = Heap.allocData(JIT_HANDLER_MAX * 32);
        }
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
    static long newReflectArrayAddr;   // VM.newReflectArray(JJ)J — reflect/Array.newInstance0 (untyped ref array)
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
            gen = readGeneration();
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

        // --- M5 self-build / fixpoint / SD-persist retired (see SELF_BUILD above). Demos ran above; the
        //     tail below is the deprecated metal-writer verification + reproduction, no longer run. ---
        if (!SELF_BUILD)
        {
            Uart.write(Magic.bytes("(self-build retired; host writer only)\n"));
            return;
        }

        // M5.5c step 2: the writer embedded the compile-reachable class set as a
        // name-indexed table. Prove the metal writer's input path: look each class up
        // by its own stored name and confirm it resolves back to itself with intact
        // classfile magic — the self-build reads its sources from the image alone.
        Uart.putc(classTableReady() ? 0x43 : 0x78);        // 'C' class table OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 1b: the metal class model answers the writer's class-graph queries
        // over that table via the shared ClassReader. Prove its leaf queries on metal
        // against known classes (Dog's super, Cell's field count, Config's <clinit>).
        Uart.putc(classModelReady() ? 0x4B : 0x78);        // 'K' class model OK / 'x' broken
        Uart.putc(0x0A);

        // The class model's superclass-chain walks: vtable flattening (super-first +
        // override-in-place), interface methods, allInterfaces, findImpl — the multi-class
        // recursion the writer's TIB/itable layout needs. Verified on metal against known
        // hierarchy facts (Dog overrides Animal.sound in the same slot; Robot implements
        // Speaker; Cell's two virtuals).
        Uart.putc(chainWalksReady() ? 0x56 : 0x78);        // 'V' chain walks OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3: the metal writer's relocating compile. Drive the shared Baseline
        // core over a real method with MetalWriterSymbols — which, unlike the JIT's
        // MetalSymbols, emits placeholders and *records* relocation sites for later layout.
        Uart.putc(relocatingCompileReady() ? 0x42 : 0x78); // 'B' relocating compile OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b: the metal layout engine. Discover a call closure, place each method
        // in a Heap buffer, compile at its base, and patch the BL sites to their callees'
        // bases -- then *execute* the built code. The built putc prints '~' before the 'L'.
        boolean built = selfBuildClosureAndRun();          // prints '~' from metal-built code
        Uart.putc(built ? 0x4C : 0x78);                    // 'L' layout engine OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: the first non-call relocation kind. Build Counter.bump/get, lay out
        // a statics slot, patch their getstatic/putstatic address loads to it, then run
        // bump() x3 and get() -- which must return 3.
        Uart.putc(selfBuildStaticsAndRun() ? 0x53 : 0x78); // 'S' static-field reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: object allocation. Build Cell.make = `new Cell(v).value`, lay out
        // Cell's Type + TIB (via MetalClassModel), patch the `new`'s TIB load and the
        // Heap.alloc helper call, then run make(0x37) -> 0x37.
        Uart.putc(selfBuildNewAndRun() ? 0x4F : 0x78);     // 'O' object/new reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: instanceof. Build Cell.selfCheck (= new Cell(0) instanceof Cell),
        // patch the `type` Type-address load and the VM.instanceOf helper, then run it -> 1.
        Uart.putc(selfBuildInstanceofAndRun() ? 0x54 : 0x78);  // 'T' type/instanceof reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: string literal. Build Cell.tag (= Magic.bytes("Z")[0]), intern "Z"
        // as a byte[] and patch the ldc-string load to it, then run tag() -> 'Z'.
        Uart.putc(selfBuildStringAndRun() ? 0x67 : 0x78);  // 'g' string reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: invokevirtual. Build Cell.viaVirtual (= new Cell(v); c.get()) plus
        // Cell's vtable methods, fill the TIB vtable, and dispatch get() through it -> 0x37.
        Uart.putc(selfBuildVirtualAndRun() ? 0x44 : 0x78); // 'D' virtual dispatch reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: invokeinterface. Build Robot.probe (= new Robot(); s.speak()), lay
        // out Speaker's interface Type + Robot's itable directory, and dispatch speak() -> 'R'.
        Uart.putc(selfBuildInterfaceAndRun() ? 0x69 : 0x78);  // 'i' interface dispatch reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: throw/catch (exceptionSlot). Build MyExc.probe (= try { throw new
        // MyExc(); } catch (MyExc e) { return 1; }) and run it -> 1 (same-method, inline catch).
        Uart.putc(selfBuildExceptionAndRun() ? 0x65 : 0x78);  // 'e' exception reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: cross-class discovery. Build Cell.readCounter (calls Counter.bump/get
        // in another class, sharing the Counter.count static) by BFS, and run it -> 1.
        Uart.putc(selfBuildCrossAndRun() ? 0x58 : 0x78);   // 'X' cross-class discovery OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3: cross-class new + virtual. Build Animal.dogSound (= new Dog().sound(),
        // Dog in another class, dispatched through its TIB vtable) and run it -> 'W'.
        Uart.putc(selfBuildDynAndRun() ? 0x79 : 0x78);     // 'y' cross-class new/virtual OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3: cross-class interface. Build Cell.viaSpeaker (= new Robot(); s.speak(),
        // Robot + Speaker in other classes) and dispatch through the itable -> 'R'.
        Uart.putc(selfBuildCrossIfaceAndRun() ? 0x4A : 0x78);  // 'J' cross-class interface OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3 capstone: the metal writer builds Guest.answer's whole closure (every
        // reloc kind, five classes) and runs it -> 42. '!' on success.
        Uart.putc(selfBuildAnswerAndRun() ? 0x21 : 0x78);  // '!' full-closure capstone OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 4 (essence): recompile VM.instanceOf on metal and assert it is byte-identical
        // to the image's own copy at instanceOfAddr -- the self-build fixpoint, one method wide.
        Uart.putc(selfFixpointInstanceOf() ? 0x3D : 0x78); // '=' metal bytes == image bytes / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 4: the fixpoint on a relocation-bearing method -- checkCast's call to
        // instanceOf is patched to the image's own address; the result must byte-match the image.
        Uart.putc(selfFixpointCheckCast() ? 0x2B : 0x78);  // '+' relocated metal bytes == image / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 3b.4: eager static init. Build Cell.readConfig (reads Config.mark), discover
        // + run Config.<clinit> first, so it reads 0x37 (not the zeroed default).
        Uart.putc(buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("readConfig"), Magic.bytes("()I")) == 0x37
                  ? 0x40 : 0x78);                          // '@' eager <clinit> init OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c cross-method unwind. Build MyExc.catchIt (calls throwIt, which throws with no local
        // handler); the throw must unwind out of throwIt into catchIt's catch via the jit frame +
        // handler tables registered for the metal-built closure -> 1.
        Uart.putc(buildClosure(Magic.bytes("vm/MyExc"), Magic.bytes("catchIt"), Magic.bytes("()I")) == 1
                  ? 0x75 : 0x78);                          // 'u' cross-method unwind OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.4: runtime-load blobs folded into the writer's input. Guest (+ Greeter/Alpha/
        // Beta/MyExc) are now in the class table, so the metal writer builds Guest.answer's whole
        // closure -- every reloc kind across five formerly JIT-only classes -- and runs it -> 42.
        Uart.putc(buildClosure(Magic.bytes("vm/Guest"), Magic.bytes("answer"), Magic.bytes("()I")) == 42
                  ? 0x47 : 0x78);                          // 'G' metal-built Guest.answer OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 4: whole-image code-region layout fixpoint. Reproduce the seed ImageBuilder's
        // method discovery + layout order from vm/VM.boot, then assert every anchored method lands at
        // its own address in the running image -- proving the metal writer reconstructs the exact
        // 0x80000-relative placement of the code region it booted from.
        Uart.putc(fixpointCodeLayout() ? 0x5A : 0x78);     // 'Z' code-region layout reproduced / 'x' off
        Uart.putc(0x0A);

        // M5.5c step 4: whole-image data-region layout fixpoint. Reproduce the seed ImageBuilder's
        // layout of every region after the code -- Types, TIBs, interned strings, statics, itables,
        // unwind frame/handler tables, blobs, class table -- and assert each boundary + count lands
        // where the running image stashed it, proving the metal writer reconstructs the whole image map.
        Uart.putc(fixpointDataLayout() ? 0x48 : 0x78);     // 'H' data-region layout reproduced / 'x' off
        Uart.putc(0x0A);

        // M5.5c step 4: whole code-region content fixpoint. Compile every one of the 485 boot-closure
        // methods at its image base with all relocations resolved to image addresses, and assert each
        // is byte-identical to the running image -- the metal writer reproduces the exact machine code
        // it is executing, across the whole code region.
        Uart.putc(fixpointCode() ? 0x24 : 0x78);           // '$' code content byte-identical / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 4 CAPSTONE -- the whole-image self-build fixpoint. Every immutable region is now
        // byte-identical to the running image: all 485 methods' code ('$' above), and every data region
        // after it -- Type records, TIB vtables, itables, interned string byte[]s, unwind frame/handler
        // tables, blobs, and the class table. (The mutable statics data segment is excluded: runtime has
        // written it -- Config.<clinit>, counters, freeHead; its layout + writer-stashed values are
        // covered by 'H'.) joe-ng has reconstructed, on bare metal, the exact image it is running.
        Uart.write(fixpointDataContent() ? Magic.bytes("FIX") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 1: materialise the clean reproduced image (image') into a heap buffer -- the exact
        // kernel8.img the seed would emit -- ready to persist to storage. Verifies its immutable regions
        // match the running image and its statics segment is reset to the as-written values.
        Uart.write(fixpointMaterialize() ? Magic.bytes("IMG") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 2: EMMC single-sector read. Bring up the SD controller + card (auto-detecting
        // EMMC2 on real hardware vs EMMC under QEMU), read block 0, and check the boot-sector signature
        // 0xAA55 at byte 510 -- present on any partitioned/FAT card, so it works on the test SD and a
        // real card alike. Proves the driver can read the medium it will persist the image to.
        Uart.write(sdReadOk() ? Magic.bytes("SD") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 2b: EMMC single-sector write. Write a known pattern to a scratch block, read it
        // back, and verify the round-trip -- proving the driver can mutate the card it will persist to.
        Uart.write(sdWriteOk() ? Magic.bytes("WR") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 3: FAT32 write. Mount the boot partition, find KERNEL8.IMG, overwrite its whole
        // cluster chain with a known pattern and read it back byte-identical -- the file-level write
        // path the self-build will use to persist image'.
        Uart.write(fatWriteOk() ? Magic.bytes("FAT") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 4 -- THE SELF-HOSTING LOOP. Reproduce image', write it over the SD card's
        // KERNEL8.IMG, verify the readback, then reset the SoC: the firmware reloads the image joe-ng
        // just wrote and boots it, and it reproduces itself again. "Drop the seed JVM" made literal.
        if (persistImage())
        {
            writeGeneration(gen + 1);                        // survives the reboot in a scratch SD sector
            Uart.write(Magic.bytes("PST -> generation "));
            printDec(gen + 1);
            Uart.write(Magic.bytes(", rebooting into the self-written image\n"));
            Reset.reboot();                                  // never returns
        }
        Uart.write(Magic.bytes("x\n"));                      // no SD card / size mismatch: skip the reboot
    }

    private static final long GEN_SECTOR = 1L;               // scratch sector in the MBR gap (before the partition)
    private static final int GEN_MAGIC = 0x216E_6567;        // "gen!" little-endian, tags an initialised counter

    /** The self-hosting generation from the scratch sector (0 if uninitialised, -1 if no card). */
    private static int readGeneration()
    {
        long buf = Heap.allocData(512);
        if (!Emmc.readBlock(GEN_SECTOR, buf))
        {
            return -1;
        }
        if (Magic.load32(buf) != GEN_MAGIC)
        {
            return 0;                                        // never written: this is generation 0
        }
        return Magic.load32(buf + 4L);
    }

    /** Persist {@code gen} into the scratch sector (magic + count). */
    private static boolean writeGeneration(int gen)
    {
        long buf = Heap.allocData(512);                          // Heap.alloc zeroes the rest of the sector
        Magic.store32(buf, GEN_MAGIC);
        Magic.store32(buf + 4L, gen);
        return Emmc.writeBlock(GEN_SECTOR, buf);
    }

    /**
     * Materialise image' and write it over the SD card's KERNEL8.IMG (the file the firmware boots),
     * verifying the readback. Returns true only if the medium now holds the reproduced image. Skips
     * (false) when there is no card or the on-card file differs in size from the reproduction (so a
     * plain dev boot with no matching SD does not reboot).
     */
    private static boolean persistImage()
    {
        discoverImage();
        layoutDataRegions();
        long len = imageEndWord() * 4L;
        if (Emmc.init() != 0 || !Fat32.mount() || !Fat32.findKernel() || Fat32.kernelSize() != len)
        {
            return false;
        }
        long buf = materializeImage();
        long wlen = (len + 511L) / 512L * 512L;              // whole sectors
        long chk = Heap.allocData((int) wlen);
        if (!Fat32.writeKernel(buf, wlen) || !Fat32.readKernel(chk, wlen))
        {
            return false;
        }
        long i = 0L;
        while (i < len)                                      // the written file must read back identical
        {
            if ((Magic.load32(chk + i) & 0xFFFFFFFFL) != (Magic.load32(buf + i) & 0xFFFFFFFFL))
            {
                return false;
            }
            i += 4L;
        }
        return true;
    }

    /** Whether KERNEL8.IMG can be located and its cluster chain rewritten + read back byte-identical. */
    private static boolean fatWriteOk()
    {
        if (Emmc.init() != 0 || !Fat32.mount() || !Fat32.findKernel())
        {
            return false;
        }
        long nbytes = (Fat32.kernelSize() + 511L) / 512L * 512L;   // whole sectors of the file
        long w = Heap.allocData((int) nbytes);
        long r = Heap.allocData((int) nbytes);
        long i = 0L;
        while (i < nbytes)
        {
            Magic.store32(w + i, (int) (0xFA700000L + i));         // a recognizable per-word pattern
            i += 4L;
        }
        if (!Fat32.writeKernel(w, nbytes) || !Fat32.readKernel(r, nbytes))
        {
            return false;
        }
        i = 0L;
        while (i < nbytes)
        {
            if ((Magic.load32(r + i) & 0xFFFFFFFFL) != ((0xFA700000L + i) & 0xFFFFFFFFL))
            {
                return false;
            }
            i += 4L;
        }
        return true;
    }

    /** Whether the EMMC driver initialises and reads block 0 with a valid boot-sector signature. */
    private static boolean sdReadOk()
    {
        long sd = Heap.allocData(512);
        return Emmc.init() == 0
            && Emmc.readBlock(0L, sd)
            && Magic.load8(sd + 510L) == 0x55
            && Magic.load8(sd + 511L) == 0xAA;
    }

    /** Whether a written scratch block reads back byte-identical (single-sector write round-trip). */
    private static boolean sdWriteOk()
    {
        long w = Heap.allocData(512);
        long r = Heap.allocData(512);
        int i = 0;
        while (i < 128)                                    // fill with a recognizable pattern
        {
            Magic.store32(w + i * 4L, 0x5EED1234 + i);
            i += 1;
        }
        if (Emmc.init() != 0 || !Emmc.writeBlock(4096L, w) || !Emmc.readBlock(4096L, r))
        {
            return false;                                  // block 4096 = a scratch sector well past the boot area
        }
        i = 0;
        while (i < 128)
        {
            if (Magic.load32(r + i * 4L) != 0x5EED1234 + i)
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** Whether every immutable data region is byte-identical to the running image (mutable statics excluded). */
    private static boolean fixpointDataContent()
    {
        discoverImage();
        layoutDataRegions();
        return firstDataMismatch() < 0;
    }

    // ----- M5.5d slice 1: materialise the clean reproduced image (image') into a heap buffer -----

    /** Total word length of the image, through the end of the class-table bytes. */
    private static int imageEndWord()
    {
        int cc = (int) classCount;
        int cur = dClassDirStart + cc * (4 * (ObjectModel.WORD / 4));
        int i = 0;
        while (i < cc)
        {
            long e = classDir + i * 32L;
            cur += align8W((int) Magic.load64(e + 8L)) + align8W((int) Magic.load64(e + 24L));
            i += 1;
        }
        return cur;
    }

    /**
     * Build the clean reproduced image {@code image'} into a fresh heap buffer: the immutable regions
     * (code, Types, TIBs, itables, strings, unwind tables, blobs, class table) are byte-identical to the
     * running image and copied from it (proven by the FIX fixpoint); the mutable statics data segment is
     * (re)written to its *as-written* values -- zero except the writer-stashed addresses/counts -- rather
     * than the runtime-mutated values the live copy now holds. The result is exactly what the seed would
     * have emitted as {@code kernel8.img}, ready to persist to storage (M5.5d). Requires discovery + layout.
     */
    private static long materializeImage()
    {
        int endW = imageEndWord();
        long buf = Heap.allocData((endW * 4 + 511) & ~511);      // padded to a whole sector (zeroed tail)
        int w = 0;
        while (w < endW)                                     // copy the whole running image (byte-identical)
        {
            Magic.store32(buf + w * 4L, Magic.load32(dAddr(w)));
            w += 1;
        }
        int si = 0;
        while (si < drStatN)                                 // overwrite each static slot with its clean value
        {
            long val = staticValue(drStatCls[si], drStatName[si]);
            long slot = buf + (dStaticsStart + si * (ObjectModel.WORD / 4)) * 4L;
            Magic.store32(slot, (int) val);
            Magic.store32(slot + 4L, (int) (val >>> 32));
            si += 1;
        }
        return buf;
    }

    /** Whether {@code buf}'s words [lo,hi) equal the running image's. */
    private static boolean regionMatches(long buf, int lo, int hi)
    {
        int w = lo;
        while (w < hi)
        {
            if ((Magic.load32(buf + w * 4L) & 0xFFFFFFFFL) != (Magic.load32(dAddr(w)) & 0xFFFFFFFFL))
            {
                return false;
            }
            w += 1;
        }
        return true;
    }

    /**
     * M5.5d slice 1: build image' and verify it is a clean reproduction -- its immutable regions match
     * the running image byte-for-byte, and its statics data segment is *reset* to the as-written values
     * (proven by Config.mark: 0 in image', 0x37 in the live image where its {@code <clinit>} ran).
     */
    private static boolean fixpointMaterialize()
    {
        discoverImage();
        layoutDataRegions();
        long buf = materializeImage();
        if (!regionMatches(buf, 0, dStaticsStart) || !regionMatches(buf, dStaticsEnd, imageEndWord()))
        {
            return false;                                    // immutable regions must be byte-identical
        }
        int markW = (int) ((statImgAddr(Magic.bytes("vm/Config"), Magic.bytes("mark")) - 0x8_0000L) / 4L);
        return Magic.load32(buf + markW * 4L) == 0          // image' has the clean (zero) static
            && Magic.load32(dAddr(markW)) == 0x37;          // the live image has the runtime-set value
    }

    /** Whether every stashed data-region boundary + unwind count matches the metal writer's layout. */
    private static boolean fixpointDataLayout()
    {
        discoverImage();
        layoutDataRegions();
        return dAddr(dStaticsStart) == staticsStart
            && dAddr(dStaticsEnd) == staticsEnd
            && dAddr(dFrameStart) == frameTable
            && dAddr(dHandlerStart) == handlerTable
            && dAddr(dBlobStart) == guestBytes
            && dAddr(dClassDirStart) == classDir
            && drFrameCount == frameCount
            && drHandlerCount == handlerCount;
    }

    /** Whether every stashed method-address anchor lands at the metal writer's derived offset. */
    private static boolean fixpointCodeLayout()
    {
        discoverImage();
        return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("reportFault"), Magic.bytes("()V")) == reportFaultAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V")) == gcCollect
            && imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J")) == heapAlloc
            && imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J")) == allocArray
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I")) == instanceOfAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J")) == checkCastAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V")) == unwindAddr;
    }

    // ----- M5.5c step 3b.2: object allocation (new -> tib + Type/TIB region) -----

    private static byte[][] nbClass;   // distinct classes needing a laid-out Type/TIB (new or type-tested)
    private static long[] nbTibAddr;   // ... its TIB address
    private static long[] nbTypeAddr;  // ... its Type address
    private static int nbCount;

    private static byte[][] ifIface;   // distinct interfaces referenced by invokeinterface
    private static long[] ifTypeAddr;  // ... its (interface) Type address
    private static int ifCount;

    private static long excSlot;       // the closure's in-flight-exception word (athrow/catch)

    /**
     * Build {@code Cell.make} (= {@code new Cell(v).value}) into a Heap buffer, lay out
     * Cell's Type + TIB via {@link MetalClassModel}, patch the `new`'s TIB address load and
     * the {@code Heap.alloc} helper call, then run {@code make(0x37)} — which must return 0x37.
     */
    private static boolean selfBuildNewAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("make");
        clDesc[0] = Magic.bytes("(I)I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long makeEntry = codeBuf + clWordOff[0] * 4L;
        long r = Magic.call2(makeEntry, 0x37L, 0L);        // make(0x37)
        return ok && (int) r == 0x37;
    }

    /**
     * Build {@code Cell.selfCheck} (= {@code new Cell(0) instanceof Cell ? 1 : 0}) into a Heap
     * buffer, patch the `new`, the `instanceof` Type load, and the {@code VM.instanceOf} helper
     * call, then run it — which must return 1 (a Cell is a Cell).
     */
    private static boolean selfBuildInstanceofAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("selfCheck");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // selfCheck()
        return ok && (int) r == 1;
    }

    /** Lay out the Types/TIBs (and interface Types + itables) the closure needs. */
    private static void layoutClassRegions(long codeBuf)
    {
        nbClass = new byte[16][];
        nbTibAddr = new long[16];
        nbTypeAddr = new long[16];
        nbCount = 0;
        ifIface = new byte[16][];
        ifTypeAddr = new long[16];
        ifCount = 0;
        // pass 1: interface Types (needed as directory keys + interfaceType targets)
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int k = 0;
            while (k < sym.ifCount())
            {
                addInterfaceType(sym.ifClassOff(k));
                k += 1;
            }
            m += 1;
        }
        // pass 2: class Types + TIBs (+ itable directory for implementors)
        m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int t = 0;
            while (t < sym.tibCount())
            {
                addClassRegion(sym.tibClassOff(t), codeBuf);
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                addClassRegion(sym.typeClassOff(y), codeBuf);
                y += 1;
            }
            m += 1;
        }
    }

    /** Build an interface's Type ({@code {0,0,0}} — not instantiated) once, if new. */
    private static void addInterfaceType(int classOff)
    {
        if (findInterface(classOff) >= 0)
        {
            return;
        }
        ifIface[ifCount] = utf8Copy(classOff);
        ifTypeAddr[ifCount] = Heap.allocData(ObjectModel.TYPE_SIZE);   // zeroed: instanceSize/super/itableDir = 0
        ifCount += 1;
    }

    /** Index of the interface whose name equals the Utf8 at {@code classOff} in {@code cB}, or -1. */
    private static int findInterface(int classOff)
    {
        int j = 0;
        while (j < ifCount)
        {
            if (utf8Eq(cB, classOff, ifIface[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Build {@code classOff}'s Type + TIB (vtable filled from placed methods) once, if new. */
    private static void addClassRegion(int classOff, long codeBuf)
    {
        if (findTibClass(classOff) >= 0)
        {
            return;
        }
        byte[] name = utf8Copy(classOff);
        long type = Heap.allocData(ObjectModel.TYPE_SIZE);
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET,
                      ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(name)));
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, 0L);       // Cell's super is Object (a root)
        int vsize = MetalClassModel.vtableSize(name);                  // builds the vtable scratch
        long tib = Heap.allocData(ObjectModel.tibSize(vsize));
        Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT), type);
        int slot = 0;
        while (slot < vsize)                                           // fill each placed vtable method's address
        {
            int j = findPlacedBytes(MetalClassModel.vtableSlotName(slot), MetalClassModel.vtableSlotDesc(slot));
            if (j >= 0)
            {
                Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)),
                              codeBuf + clWordOff[j] * 4L);
            }
            slot += 1;
        }
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, buildItableDir(name, codeBuf));
        nbClass[nbCount] = name;
        nbTypeAddr[nbCount] = type;
        nbTibAddr[nbCount] = tib;
        nbCount += 1;
    }

    /** Build {@code clsName}'s itable directory over the referenced interfaces it implements (0 if none). */
    private static long buildItableDir(byte[] clsName, long codeBuf)
    {
        int impls = 0;
        int k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                impls += 1;
            }
            k += 1;
        }
        if (impls == 0)
        {
            return 0L;
        }
        long dir = Heap.allocData((impls + 1) * ObjectModel.ITABLE_ENTRY_SIZE);   // +1 zeroed sentinel
        int e = 0;
        k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                long entry = dir + e * ObjectModel.ITABLE_ENTRY_SIZE;
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET, ifTypeAddr[k]);
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET, buildItable(ifIface[k], codeBuf));
                e += 1;
            }
            k += 1;
        }
        return dir;
    }

    /** Build a class's itable for {@code iface}: each interface method's slot → the placed impl address. */
    private static long buildItable(byte[] iface, long codeBuf)
    {
        int n = MetalClassModel.interfaceMethodCount(iface);
        long itab = Heap.allocData(n * ObjectModel.WORD);
        int slot = 0;
        while (slot < n)
        {
            byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, slot);
            byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, slot);
            int j = findPlacedBytes(mName, mDesc);
            if (j >= 0)
            {
                Magic.store64(itab + slot * ObjectModel.WORD, codeBuf + clWordOff[j] * 4L);
            }
            slot += 1;
        }
        return itab;
    }

    /** Index of the placed method whose (name,desc) equal the byte arrays given, or -1. */
    private static int findPlacedBytes(byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < clCount)
        {
            if (bytesEqual(clName[j], name) && bytesEqual(clDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Whether two heap byte arrays are equal in length and content. */
    private static boolean bytesEqual(byte[] a, byte[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        int i = 0;
        while (i < a.length)
        {
            if (a[i] != b[i])
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** Index of the laid-out class whose name equals the Utf8 at {@code classOff} in {@code cB}, or -1. */
    private static int findTibClass(int classOff)
    {
        int j = 0;
        while (j < nbCount)
        {
            if (utf8Eq(cB, classOff, nbClass[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Patch each method's calls, runtime-helper calls, and `new` TIB loads, then write it out. */
    private static boolean patchNewAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));
                }
                c += 1;
            }
            int h = 0;
            while (h < sym.helperCount())
            {
                int site = sym.helperSiteWord(h);
                long siteAbs = buf + (baseOff + site) * 4L;
                long rel = (helperAddr(sym.helperId(h)) - siteAbs) / 4L;   // BL to the image helper
                words[site] = A64Enc.bl((int) rel);
                h += 1;
            }
            int t = 0;
            while (t < sym.tibCount())
            {
                int k = findTibClass(sym.tibClassOff(t));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.tibSiteWord(t), sym.tibReg(t), nbTibAddr[k]);
                }
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                int k = findTibClass(sym.typeClassOff(y));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), nbTypeAddr[k]);
                }
                y += 1;
            }
            int s = 0;
            while (s < sym.strCount())
            {
                long arr = internLiteral(sym.strUtf8Off(s));   // lay the literal out as a byte[]
                patchAddrWords(words, sym.strSiteWord(s), sym.strReg(s), arr);
                s += 1;
            }
            int f = 0;
            while (f < sym.ifCount())
            {
                int k = findInterface(sym.ifClassOff(f));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), ifTypeAddr[k]);
                }
                f += 1;
            }
            int x = 0;
            while (x < sym.excCount())
            {
                patchAddrWords(words, sym.excSiteWord(x), sym.excReg(x), excSlot);  // the shared exc slot
                x += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    /** Intern the Utf8 literal at {@code cB[utf8Off]} as a heap {@code byte[]} (as {@code Loader.internString}). */
    private static long internLiteral(int utf8Off)
    {
        int len = ClassReader.u2(cB, utf8Off);
        long arr = Heap.allocArray(len, 1);
        int i = 0;
        while (i < len)
        {
            Magic.store8(arr + ObjectModel.ARRAY_BASE_OFFSET + i, ClassReader.u1(cB, utf8Off + 2 + i));
            i += 1;
        }
        return arr;
    }

    /**
     * Build {@code Cell.tag} (= {@code Magic.bytes("Z")[0]}) into a Heap buffer, intern the "Z"
     * literal as a byte[] and patch the ldc-string address load to it, then run it → {@code 'Z'}.
     */
    private static boolean selfBuildStringAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("tag");
        clDesc[0] = Magic.bytes("()I");
        clCount = 1;

        int body = findMethodBody(clName[0], clDesc[0]);
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, 0L);
        clSym[0] = sym;
        clWords[0] = words;
        clSize[0] = words.length;
        clWordOff[0] = 0;

        long codeBuf = Heap.allocData(words.length * 4);
        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + words.length * 4L);

        long r = Magic.call0(codeBuf);                     // tag()
        return ok && (int) r == 0x5A;                      // 'Z'
    }

    /**
     * Build {@code Cell.viaVirtual} (= {@code new Cell(v); c.get()}) plus Cell's vtable methods
     * ({@code get}, {@code inc}) into a Heap buffer, fill Cell's TIB vtable with their addresses,
     * then run {@code viaVirtual(0x37)} — which dispatches {@code get()} through the TIB → 0x37.
     */
    private static boolean selfBuildVirtualAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("viaVirtual");
        clDesc[0] = Magic.bytes("(I)I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clName[2] = Magic.bytes("get");         // Cell's vtable methods, placed so the TIB can
        clDesc[2] = Magic.bytes("()I");         // point its vtable slots at them
        clName[3] = Magic.bytes("inc");
        clDesc[3] = Magic.bytes("()V");
        clCount = 4;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call2(codeBuf + clWordOff[0] * 4L, 0x37L, 0L);  // viaVirtual(0x37)
        return ok && (int) r == 0x37;
    }

    /**
     * Build {@code Robot.probe} (= {@code Speaker s = new Robot(); s.speak()}) plus Robot's
     * {@code <init>}/{@code speak} into a Heap buffer, lay out Speaker's interface Type and
     * Robot's Type/TIB + itable directory, patch the interfaceType load, then run {@code probe()}
     * — which dispatches {@code speak()} through Robot's itable → {@code 'R'}.
     */
    private static boolean selfBuildInterfaceAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Robot"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("probe");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("()V");
        clName[2] = Magic.bytes("speak");   // the itable's impl for Speaker.speak
        clDesc[2] = Magic.bytes("()I");
        clCount = 3;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // probe()
        return ok && (int) r == 0x52;                       // 'R'
    }

    /**
     * Build {@code MyExc.probe} (= {@code try { throw new MyExc(); } catch (MyExc e) { return 1; }})
     * into a Heap buffer, lay out MyExc's Type/TIB and an exception slot, patch the `new`, the
     * catch-type load, the exception-slot loads, and the instanceOf helper, then run it → 1. The
     * throw/catch is same-method, so it resolves inline (no cross-method unwind).
     */
    private static boolean selfBuildExceptionAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/MyExc"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("probe");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("()V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        excSlot = Heap.allocData(8);                            // the closure's in-flight-exception word
        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // probe()
        return ok && (int) r == 1;
    }

    /** Absolute image address of the runtime helper with the given {@code compiler.Symbols} id. */
    private static long helperAddr(int id)
    {
        if (id == 0)
        {
            return heapAlloc;          // HEAP_ALLOC
        }
        if (id == 1)
        {
            return allocArray;         // HEAP_ALLOC_ARRAY
        }
        if (id == 2)
        {
            return gcCollect;          // GC_COLLECT
        }
        if (id == 3)
        {
            return instanceOfAddr;     // INSTANCE_OF
        }
        if (id == 4)
        {
            return checkCastAddr;      // CHECK_CAST
        }
        return unwindAddr;             // UNWIND
    }

    // ----- M5.5c step 3b.2: cross-class discovery (BFS over calls; per-method class context) -----

    private static byte[][] lcName;    // loaded-class cache: name / bytes / cp offsets / tags / afterCp
    private static byte[][] lcBytes;
    private static int[][] lcOff;
    private static int[][] lcTag;
    private static int[] lcAfterCp;
    private static int lcCount;

    private static byte[][] gmClsName; // discovered methods: owning class name (for matching)
    private static int[] gmClsIdx;     // ... its class-cache index (for context)
    private static byte[][] gmName;
    private static byte[][] gmDesc;
    private static int[] gmSize;
    private static int[] gmWordOff;
    private static int[][] gmWords;
    private static MetalWriterSymbols[] gmSym;
    private static int[] gmFrameSize;  // per method: frame size + machine handler ranges (cross-method unwind)
    private static int[] gmHN;
    private static int[][] gmHStart;
    private static int[][] gmHEnd;
    private static int[][] gmHandler;
    private static int[][] gmHCatch;   // catch-type Class cp index per handler
    private static int gmCount;

    /** Cross-class calls + statics: {@code Cell.readCounter} → {@code Counter.bump/get} → 1. */
    private static boolean selfBuildCrossAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("readCounter"), Magic.bytes("()I")) == 1;
    }

    /** Cross-class new + virtual: {@code Animal.dogSound} = {@code new Dog().sound()} → 'W'. */
    private static boolean selfBuildDynAndRun()
    {
        return buildClosure(Magic.bytes("vm/Animal"), Magic.bytes("dogSound"), Magic.bytes("()I")) == 0x57;
    }

    /** Cross-class interface: {@code Cell.viaSpeaker} = {@code new Robot(); s.speak()} → 'R'. */
    private static boolean selfBuildCrossIfaceAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("viaSpeaker"), Magic.bytes("()I")) == 0x52;
    }

    /**
     * The capstone: build {@code Cell.capstone}'s whole closure — spanning Cell, Robot, Speaker,
     * Dog, Animal, MyExc and Counter, exercising every relocation kind at once (new,
     * invokevirtual, invokeinterface, instanceof, ldc-string, cross-class call, throw/catch,
     * cross-class static) — and run it → 262. (Guest.answer would be ideal but Guest/Alpha/Beta/
     * Greeter are runtime-load blobs, not in the compile-reachable class table the writer reads.)
     */
    private static boolean selfBuildAnswerAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("capstone"), Magic.bytes("()I")) == 262;
    }

    /**
     * The self-build fixpoint in miniature (step 4 essence): recompile a method the seed writer
     * put in the running image — {@code VM.instanceOf}, stashed at {@code instanceOfAddr} and
     * relocation-free — on metal, and assert it is <em>byte-identical</em> to the image's own
     * copy. Byte-equal ⇒ joe-ng's metal writer reproduces the exact machine code it is running.
     */
    private static boolean selfFixpointInstanceOf()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/VM"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        int body = findMethodBody(Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, instanceOfAddr);   // compile at its real image base

        // It must be relocation-free (else its bytes depend on layout we're not reproducing here).
        if (sym.callCount() != 0 || sym.helperCount() != 0 || sym.staticCount() != 0
                || sym.tibCount() != 0 || sym.typeCount() != 0 || sym.strCount() != 0
                || sym.ifCount() != 0 || sym.excCount() != 0)
        {
            return false;
        }
        return fixpointEquals(words, instanceOfAddr);
    }

    /**
     * The fixpoint extended to a <em>relocation-bearing</em> method: {@code VM.checkCast} (stashed
     * at {@code checkCastAddr}) has exactly one reloc — a call to {@code VM.instanceOf} (stashed at
     * {@code instanceOfAddr}). Recompile it on metal, patch that call to the image's own instanceOf
     * address, and assert byte-equality with the image — the metal writer relocates as the seed did.
     */
    private static boolean selfFixpointCheckCast()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/VM"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        int body = findMethodBody(Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, checkCastAddr);    // compile at its real image base

        // Exactly one relocation: a call to instanceOf; nothing else.
        if (sym.callCount() != 1 || !sym.callNameIs(0, Magic.bytes("instanceOf"))
                || sym.helperCount() != 0 || sym.staticCount() != 0 || sym.tibCount() != 0
                || sym.typeCount() != 0 || sym.strCount() != 0 || sym.ifCount() != 0 || sym.excCount() != 0)
        {
            return false;
        }
        int site = sym.callSiteWord(0);
        long siteAbs = checkCastAddr + site * 4L;
        words[site] = A64Enc.bl((int) ((instanceOfAddr - siteAbs) / 4L));   // BL to the image's instanceOf

        return fixpointEquals(words, checkCastAddr);
    }

    /** Whether {@code words} are byte-identical to the running image starting at {@code imgAddr}. */
    private static boolean fixpointEquals(int[] words, long imgAddr)
    {
        int w = 0;
        while (w < words.length)                            // mask to 32 bits: int[] sign-extends, load32 zero-extends
        {
            if ((words[w] & 0xFFFFFFFFL) != (Magic.load32(imgAddr + w * 4L) & 0xFFFFFFFFL))
            {
                return false;
            }
            w += 1;
        }
        return true;
    }

    /**
     * Discover, build, and run a method's closure across classes: BFS over recorded calls and
     * (for {@code new}) the instantiated classes' vtable methods; lay each class's Type/TIB out;
     * resolve every cross-class call, static, helper, and TIB load; then execute the entry and
     * return its result (a negative sentinel on a build failure).
     */
    private static long buildClosure(byte[] entryCls, byte[] entryName, byte[] entryDesc)
    {
        lcName = new byte[32][];
        lcBytes = new byte[32][];
        lcOff = new int[32][];
        lcTag = new int[32][];
        lcAfterCp = new int[32];
        lcCount = 0;
        gmClsName = new byte[64][];
        gmClsIdx = new int[64];
        gmName = new byte[64][];
        gmDesc = new byte[64][];
        gmSize = new int[64];
        gmWordOff = new int[64];
        gmWords = new int[64][];
        gmSym = new MetalWriterSymbols[64];
        gmFrameSize = new int[64];
        gmHN = new int[64];
        gmHStart = new int[64][];
        gmHEnd = new int[64][];
        gmHandler = new int[64][];
        gmHCatch = new int[64][];
        gmCount = 0;

        enqueueMethod(entryCls, entryName, entryDesc);

        int i = 0;
        while (i < gmCount)
        {
            setClassContext(gmClsIdx[i]);
            int body = findMethodBody(gmName[i], gmDesc[i]);
            if (body < 0)
            {
                return -1L;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            gmSym[i] = sym;
            gmWords[i] = words;
            gmSize[i] = words.length;
            gmFrameSize[i] = fFrameSize;                    // capture frame + handlers for unwind
            gmHN[i] = fHN;
            gmHStart[i] = new int[fHN];
            gmHEnd[i] = new int[fHN];
            gmHandler[i] = new int[fHN];
            gmHCatch[i] = new int[fHN];
            int h = 0;
            while (h < fHN)
            {
                gmHStart[i][h] = fHStart[h];
                gmHEnd[i][h] = fHEnd[h];
                gmHandler[i][h] = fHandler[h];
                gmHCatch[i][h] = fHCatch[h];
                h += 1;
            }
            discoverFrom(sym);
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < gmCount)
        {
            gmWordOff[p] = cur;
            cur += gmSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        collectStaticsG();
        long staticsBuf = Heap.allocData(stCount * 8);
        int s = 0;
        while (s < stCount)
        {
            long slot = staticsBuf + s * 8L;
            Magic.store64(slot, 0L);
            stAddr[s] = slot;
            s += 1;
        }

        excSlot = Heap.allocData(8);                            // the closure's in-flight-exception word
        layoutClassRegionsG(codeBuf);
        boolean ok = patchCrossAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);
        registerFramesAndHandlers(codeBuf);                 // so cross-method throw/catch can unwind

        runGeneratedInitClasses(codeBuf);                   // eager static init before the entry runs

        int entry = findMethodG(entryCls, entryName, entryDesc);
        long r = Magic.call0(codeBuf + gmWordOff[entry] * 4L);
        return ok ? r : -2L;
    }

    /**
     * Generate the closure's {@code initClasses} method on metal — a synthetic frame that saves
     * {@code LR}, {@code BL}s each discovered {@code <clinit>} in discovery order, restores, and
     * returns — then call it once. This reproduces the seed writer's synthetic eager-init method
     * ({@code ImageBuilder.generateInitClasses}) as real A64 the metal writer emits itself, rather
     * than a Java-side call loop, so the on-metal build matches the seed's shape (toward the
     * self-build fixpoint). A closure with no {@code <clinit>} generates nothing.
     */
    private static void runGeneratedInitClasses(long codeBuf)
    {
        byte[] clinit = Magic.bytes("<clinit>");
        int n = 0;
        int ci = 0;
        while (ci < gmCount)
        {
            if (bytesEqual(gmName[ci], clinit))
            {
                n += 1;
            }
            ci += 1;
        }
        if (n == 0)
        {
            return;
        }
        int total = 2 + n + 3;                              // prologue(2) + n BLs + epilogue(3)
        long initBuf = Heap.allocData(total * 4);
        int frame = A64Enc.align16(8);                      // LR only
        int w = 0;
        Magic.store32(initBuf + w * 4L, A64Enc.subImm(31, 31, frame));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.strx(30, 31, 0));
        w += 1;
        ci = 0;
        while (ci < gmCount)
        {
            if (bytesEqual(gmName[ci], clinit))
            {
                long here = initBuf + w * 4L;
                Magic.store32(here, A64Enc.bl((int) ((codeBuf + gmWordOff[ci] * 4L - here) >> 2)));
                w += 1;
            }
            ci += 1;
        }
        Magic.store32(initBuf + w * 4L, A64Enc.ldrx(30, 31, 0));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.addImm(31, 31, frame));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.ret());
        w += 1;
        Heap.publishCode(initBuf, initBuf + total * 4L);
        long unused = Magic.call0(initBuf);                 // bound to a local (no pop2 in Baseline)
    }

    // ----- M5.5c step 4: whole-image code-region fixpoint (discovery + sizing order) -----
    // Reproduce the seed ImageBuilder's method discovery + layout order from vm/VM.boot, so each
    // method lands at the same 0x80000-relative word offset it occupies in the running image.
    // Validated against the seven stashed method-address anchors: if every derived offset matches
    // the image's own address, the code-region ordering has been reproduced byte-for-byte.
    private static byte[][] imClsName;   // per discovered method: class name / cache idx / name / desc
    private static int[] imCls;
    private static byte[][] imName;
    private static byte[][] imDesc;
    private static int[] imSize;         // ... its compiled word count (placement-independent)
    private static int imN;
    private static byte[][] usedCls;     // classes whose <clinit> has been scheduled (eager-init dedup)
    private static int usedN;
    private static byte[][] tibSeenCls;  // classes whose vtable has been expanded (tibClasses dedup)
    private static int tibSeenN;
    private static int clinitCount;      // discovered <clinit>s (the initClasses method's BL count)
    // data-region sets, collected during discovery in the seed's order (for the region layout)
    private static byte[][] drStr;       // interned string literals (content-deduped)
    private static int drStrN;
    private static byte[][] drTypeRef;   // instanceof/checkcast/interface/catch Type classes
    private static int drTypeRefN;
    private static byte[][] drUsedIf;    // invokeinterface target interfaces
    private static int drUsedIfN;
    private static byte[][] drStatCls;   // distinct static fields: owner class
    private static byte[][] drStatName;  // ... and field name
    private static int drStatN;
    private static int drFrameCount;     // methods with a frame (unwind frame-table entries)
    private static int drHandlerCount;   // total try/catch handlers (unwind handler-table entries)
    private static byte[][] typeClasses; // Types region: instantiated + type-ref classes + their supers
    private static int typeClassesN;
    // computed 0x80000-relative WORD offsets of each region boundary (see layoutDataRegions)
    private static int dTypesStart, dTibStart, dStrStart, dStaticsStart, dStaticsEnd;
    private static int dItStart, dFrameStart, dHandlerStart, dBlobStart, dClassDirStart;
    private static int[] dTibOff;        // parallel to tibSeenCls: each TIB's 0x80000-relative word offset
    private static int[] dStrOff;        // parallel to drStr: each interned byte[]'s word offset
    private static int[] dItDirOff;      // parallel to tibSeenCls: itable-directory word offset, or -1 (no itables)
    static final int BLOB_COUNT = 74;    // ...+ Predicate + Function + Consumer + Stream + BinaryOperator + BiConsumer
    private static int[] dBlobOff;       // each embedded blob's word offset, in addBlob order
    // per-method frame + handler info (parallel to im*), for the unwind-table content
    private static int[] imFrameSize;
    private static int[] imHNa;
    private static int[][] imHStartA;
    private static int[][] imHEndA;
    private static int[][] imHandlerA;
    private static byte[][][] imHCatchCls;   // per handler: catch class name bytes, or null (finally)

    private static int imFind(byte[] cls, byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < imN)
        {
            if (bytesEqual(imClsName[j], cls) && bytesEqual(imName[j], name) && bytesEqual(imDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Enqueue a method in discovery order; dedup-at-enqueue == the seed's FIFO dedup-at-dequeue. */
    private static void imEnqueue(byte[] cls, byte[] name, byte[] desc)
    {
        if (bytesEqual(cls, Magic.bytes("vm/VM")) && bytesEqual(name, Magic.bytes("initClasses")))
        {
            return;    // initClasses is generated, not discovered — the seed places it last (see discoverImage)
        }
        if (imFind(cls, name, desc) >= 0)
        {
            return;
        }
        imClsName[imN] = cls;
        imCls[imN] = loadClass(cls);
        imName[imN] = name;
        imDesc[imN] = desc;
        imN += 1;
    }

    private static boolean usedAdd(byte[] cls)
    {
        int j = 0;
        while (j < usedN)
        {
            if (bytesEqual(usedCls[j], cls))
            {
                return false;
            }
            j += 1;
        }
        usedCls[usedN] = cls;
        usedN += 1;
        return true;
    }

    /** The seed's use(): on a class's first use, schedule its {@code <clinit>} (eager init). */
    private static void useClinit(byte[] cls)
    {
        if (usedAdd(cls) && MetalClassModel.hasClinit(cls))
        {
            clinitCount += 1;
            imEnqueue(cls, Magic.bytes("<clinit>"), Magic.bytes("()V"));
        }
    }

    private static boolean tibSeenAdd(byte[] cls)
    {
        int j = 0;
        while (j < tibSeenN)
        {
            if (bytesEqual(tibSeenCls[j], cls))
            {
                return false;
            }
            j += 1;
        }
        tibSeenCls[tibSeenN] = cls;
        tibSeenN += 1;
        return true;
    }

    /** 0x80000-relative address of discovered method (cls,name,desc), or 0 if not discovered. */
    private static long imAddrOf(byte[] cls, byte[] name, byte[] desc)
    {
        int j = imFind(cls, name, desc);
        if (j < 0)
        {
            return 0L;
        }
        long off = 0L;
        int i = 0;
        while (i < j)
        {
            off += imSize[i];
            i += 1;
        }
        return 0x8_0000L + off * 4L;                         // CodeBuffer.LOAD_ADDRESS (the image base)
    }

    /** Discover + size the whole {@code boot} closure in the seed's exact order (no relocation, no run). */
    private static void discoverImage()
    {
        lcName = new byte[256][];
        lcBytes = new byte[256][];
        lcOff = new int[256][];
        lcTag = new int[256][];
        lcAfterCp = new int[256];
        lcCount = 0;
        imClsName = new byte[2048][];
        imCls = new int[2048];
        imName = new byte[2048][];
        imDesc = new byte[2048][];
        imSize = new int[2048];
        imFrameSize = new int[2048];
        imHNa = new int[2048];
        imHStartA = new int[2048][];
        imHEndA = new int[2048][];
        imHandlerA = new int[2048][];
        imHCatchCls = new byte[2048][][];
        imN = 0;
        usedCls = new byte[512][];
        usedN = 0;
        tibSeenCls = new byte[128][];
        tibSeenN = 0;
        clinitCount = 0;
        drStr = new byte[2048][];
        drStrN = 0;
        drTypeRef = new byte[256][];
        drTypeRefN = 0;
        drUsedIf = new byte[128][];
        drUsedIfN = 0;
        drStatCls = new byte[1024][];
        drStatName = new byte[1024][];
        drStatN = 0;
        drFrameCount = 0;
        drHandlerCount = 0;

        imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("boot"), Magic.bytes("()V"));
        int i = 0;
        while (i < imN)
        {
            setClassContext(imCls[i]);
            int body = findMethodBody(imName[i], imDesc[i]);
            boolean isEntry = i == 0;                        // boot is the frameless entry (as the seed)
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            imSize[i] = compileInto(body, sym, 0L, isEntry).length;
            imFrameSize[i] = fFrameSize;                      // capture frame + handler info for the unwind tables
            imHNa[i] = fHN;
            imHStartA[i] = new int[fHN];
            imHEndA[i] = new int[fHN];
            imHandlerA[i] = new int[fHN];
            imHCatchCls[i] = new byte[fHN][];
            int hh = 0;
            while (hh < fHN)
            {
                imHStartA[i][hh] = fHStart[hh];
                imHEndA[i][hh] = fHEnd[hh];
                imHandlerA[i][hh] = fHandler[hh];
                imHCatchCls[i][hh] = fHCatch[hh] == 0 ? null : utf8Copy(ClassReader.classNameOff(cB, cOff, fHCatch[hh]));
                hh += 1;
            }
            discoverImageFrom(sym, imClsName[i]);
            i += 1;
        }
        // initClasses is placed last, its body generated: SUB/STR + one BL per discovered <clinit> + LDR/ADD/RET.
        imClsName[imN] = Magic.bytes("vm/VM");
        imCls[imN] = loadClass(Magic.bytes("vm/VM"));
        imName[imN] = Magic.bytes("initClasses");
        imDesc[imN] = Magic.bytes("()V");
        imSize[imN] = 2 + clinitCount + 3;
        imFrameSize[imN] = A64Enc.align16(8);               // initClasses frame (LR save), no handlers
        imHNa[imN] = 0;
        imHStartA[imN] = new int[0];
        imHEndA[imN] = new int[0];
        imHandlerA[imN] = new int[0];
        imHCatchCls[imN] = new byte[0][];
        imN += 1;
        drFrameCount += 1;                                   // initClasses has a frame (LR save) too
    }

    /** Enqueue the runtime-helper method the writer synthesised a {@code BL} to (matches WriterSymbols.HELPER_KEY). */
    private static void imEnqueueHelper(int id)
    {
        if (id == 0)
        {
            imEnqueue(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J"));
        }
        else if (id == 1)
        {
            imEnqueue(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J"));
        }
        else if (id == 2)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V"));
        }
        else if (id == 3)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        }
        else if (id == 4)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        }
        else
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V"));
        }
    }

    /** Append method's callees, eager {@code <clinit>}s, and new classes' vtable methods, in the seed's order. */
    private static void discoverImageFrom(MetalWriterSymbols sym, byte[] ownerCls)
    {
        // 1: callees + synthesised helper calls, merged by emission order (the seed unifies both in
        //    one callSites list; the metal writer splits them, so re-merge by ascending word index).
        int ci = 0;
        int hi = 0;
        int nc = sym.callCount();
        int nh = sym.helperCount();
        while (ci < nc || hi < nh)
        {
            boolean takeCall;
            if (ci >= nc)
            {
                takeCall = false;
            }
            else if (hi >= nh)
            {
                takeCall = true;
            }
            else
            {
                takeCall = sym.callSiteWord(ci) < sym.helperSiteWord(hi);
            }
            if (takeCall)
            {
                imEnqueue(utf8Copy(sym.callClassOff(ci)), utf8Copy(sym.callNameOff(ci)), utf8Copy(sym.callDescOff(ci)));
                ci += 1;
            }
            else
            {
                imEnqueueHelper(sym.helperId(hi));
                hi += 1;
            }
        }
        // 2: data-region sets, in the seed's per-method order (strings, type refs, interface refs,
        //    handler catch classes, unwind counts) -- these feed the Types/strings/itable/unwind layout.
        int sr = 0;
        while (sr < sym.strCount())
        {
            drAddStr(utf8Copy(sym.strUtf8Off(sr)));
            sr += 1;
        }
        int ty = 0;
        while (ty < sym.typeCount())
        {
            drAddTypeRef(utf8Copy(sym.typeClassOff(ty)));
            ty += 1;
        }
        int inf = 0;
        while (inf < sym.ifCount())
        {
            byte[] ic = utf8Copy(sym.ifClassOff(inf));
            drAddTypeRef(ic);
            drAddUsedIf(ic);
            inf += 1;
        }
        int hc = 0;
        while (hc < fHN)                                      // catch classes are type-checked (VM.instanceOf)
        {
            if (fHCatch[hc] != 0)
            {
                drAddTypeRef(utf8Copy(ClassReader.classNameOff(cB, cOff, fHCatch[hc])));
            }
            hc += 1;
        }
        if (fFrameSize > 0)
        {
            drFrameCount += 1;
        }
        drHandlerCount += fHN;

        int t = 0;
        while (t < sym.tibCount())                           // 3: each new'd class's <clinit>
        {
            useClinit(utf8Copy(sym.tibClassOff(t)));
            t += 1;
        }
        // 4: statics region -- real static fields (getstatic/putstatic) and the shared $exception
        //    slot (athrow/catch), merged by emission order as the seed's single staticRefs list is,
        //    plus each field owner's <clinit>.
        int si = 0;
        int xi = 0;
        int ns = sym.staticCount();
        int nx = sym.excCount();
        while (si < ns || xi < nx)
        {
            boolean takeStatic;
            if (si >= ns)
            {
                takeStatic = false;
            }
            else if (xi >= nx)
            {
                takeStatic = true;
            }
            else
            {
                takeStatic = sym.staticSiteWord(si) < sym.excSiteWord(xi);
            }
            if (takeStatic)
            {
                drAddStat(utf8Copy(sym.staticClassOff(si)), utf8Copy(sym.staticNameOff(si)));
                useClinit(utf8Copy(sym.staticClassOff(si)));
                si += 1;
            }
            else
            {
                drAddStat(Magic.bytes("vm/VM"), Magic.bytes("$exception"));
                xi += 1;
            }
        }
        useClinit(ownerCls);                                 // 5: the method's own class <clinit>
        t = 0;
        while (t < sym.tibCount())                           // 6: a newly instantiated class's vtable methods
        {
            byte[] tc = utf8Copy(sym.tibClassOff(t));
            if (tibSeenAdd(tc))
            {
                int vs = MetalClassModel.vtableSize(tc);
                int sl = 0;
                while (sl < vs)
                {
                    imEnqueue(MetalClassModel.vtableSlotOwner(sl), MetalClassModel.vtableSlotName(sl),
                              MetalClassModel.vtableSlotDesc(sl));
                    sl += 1;
                }
            }
            t += 1;
        }
    }

    private static void drAddStr(byte[] s)
    {
        int i = 0;
        while (i < drStrN)
        {
            if (bytesEqual(drStr[i], s))
            {
                return;
            }
            i += 1;
        }
        drStr[drStrN] = s;
        drStrN += 1;
    }

    private static void drAddTypeRef(byte[] c)
    {
        int i = 0;
        while (i < drTypeRefN)
        {
            if (bytesEqual(drTypeRef[i], c))
            {
                return;
            }
            i += 1;
        }
        drTypeRef[drTypeRefN] = c;
        drTypeRefN += 1;
    }

    private static void drAddUsedIf(byte[] c)
    {
        int i = 0;
        while (i < drUsedIfN)
        {
            if (bytesEqual(drUsedIf[i], c))
            {
                return;
            }
            i += 1;
        }
        drUsedIf[drUsedIfN] = c;
        drUsedIfN += 1;
    }

    private static void drAddStat(byte[] cls, byte[] nm)
    {
        int i = 0;
        while (i < drStatN)
        {
            if (bytesEqual(drStatCls[i], cls) && bytesEqual(drStatName[i], nm))
            {
                return;
            }
            i += 1;
        }
        drStatCls[drStatN] = cls;
        drStatName[drStatN] = nm;
        drStatN += 1;
    }

    /** The seed's addTypeClass: add {@code cls} and each non-root superclass up the chain (dedup). */
    private static void addTypeClass(byte[] cls)
    {
        while (cls != null && !MetalClassModel.isRoot(cls) && typeClassAdd(cls))
        {
            cls = MetalClassModel.superName(cls);
        }
    }

    private static boolean typeClassAdd(byte[] cls)
    {
        int i = 0;
        while (i < typeClassesN)
        {
            if (bytesEqual(typeClasses[i], cls))
            {
                return false;
            }
            i += 1;
        }
        typeClasses[typeClassesN] = cls;
        typeClassesN += 1;
        return true;
    }

    /**
     * Reproduce the seed ImageBuilder's data-region layout after the code region, computing each
     * region's 0x80000-relative WORD offset: [Types][TIBs][strings][statics][itables][frame table]
     * [handler table][blobs][class table]. Requires {@link #discoverImage} to have run (it fills the
     * sets this consumes). Validated against the stashed region anchors.
     */
    private static void layoutDataRegions()
    {
        int cur = 0;
        int i = 0;
        while (i < imN)                                      // code region (all methods + initClasses)
        {
            cur += imSize[i];
            i += 1;
        }
        cur += cur % 2;                                      // pad to 8 bytes before the data regions

        // Types: instantiated classes + type-ref classes, each with its whole superclass chain.
        typeClasses = new byte[256][];
        typeClassesN = 0;
        int t = 0;
        while (t < tibSeenN)
        {
            addTypeClass(tibSeenCls[t]);
            t += 1;
        }
        t = 0;
        while (t < drTypeRefN)
        {
            addTypeClass(drTypeRef[t]);
            t += 1;
        }
        dTypesStart = cur;
        cur += typeClassesN * (ObjectModel.TYPE_SIZE / 4);

        dTibStart = cur;                                     // TIBs: one per instantiated class
        dTibOff = new int[tibSeenN];
        t = 0;
        while (t < tibSeenN)
        {
            dTibOff[t] = cur;
            cur += ObjectModel.tibSize(MetalClassModel.vtableSize(tibSeenCls[t])) / 4;
            t += 1;
        }

        dStrStart = cur;                                     // interned string byte[] literals
        dStrOff = new int[drStrN];
        int s = 0;
        while (s < drStrN)
        {
            dStrOff[s] = cur;
            cur += (ObjectModel.ARRAY_BASE_OFFSET + ((drStr[s].length + 7) & ~7)) / 4;
            s += 1;
        }

        dStaticsStart = cur;                                 // one 8-byte slot per distinct static field
        cur += drStatN * (ObjectModel.WORD / 4);
        dStaticsEnd = cur;

        dItStart = cur;                                      // itable directories + itables per class
        dItDirOff = new int[tibSeenN];
        t = 0;
        while (t < tibSeenN)
        {
            int impls = 0;
            int u = 0;
            while (u < drUsedIfN)
            {
                if (MetalClassModel.implementsInterface(tibSeenCls[t], drUsedIf[u]))
                {
                    impls += 1;
                }
                u += 1;
            }
            if (impls > 0)
            {
                dItDirOff[t] = cur;
                cur += (impls + 1) * (ObjectModel.ITABLE_ENTRY_SIZE / 4);   // +1 zeroed sentinel
                u = 0;
                while (u < drUsedIfN)
                {
                    if (MetalClassModel.implementsInterface(tibSeenCls[t], drUsedIf[u]))
                    {
                        cur += MetalClassModel.interfaceMethodCount(drUsedIf[u]) * (ObjectModel.WORD / 4);
                    }
                    u += 1;
                }
            }
            else
            {
                dItDirOff[t] = -1;
            }
            t += 1;
        }

        dFrameStart = cur;                                   // unwind: frame entries (6 words each)
        cur += drFrameCount * 6;
        dHandlerStart = cur;                                 // unwind: handler entries (8 words each)
        cur += drHandlerCount * 8;

        dBlobStart = cur;                                    // embedded raw .class blobs, 8-byte aligned
        dBlobOff = new int[BLOB_COUNT];
        int bb = 0;
        while (bb < BLOB_COUNT)
        {
            dBlobOff[bb] = cur;
            cur += align8W(MetalClassModel.bytesOf(blobClass(bb)).length);
            bb += 1;
        }

        dClassDirStart = cur;                                // class table directory (names + bytes follow)
    }

    /** Image words an 8-byte-aligned run of {@code len} bytes occupies (companion to the seed's align8Words). */
    private static int align8W(int len)
    {
        return ((len + 7) & ~7) / 4;
    }

    private static long dAddr(int word)
    {
        return 0x8_0000L + word * 4L;
    }

    // ----- image-address resolvers for each relocation kind (over the reproduced layout) -----

    private static int findByteArr(byte[][] arr, int n, byte[] want)
    {
        int i = 0;
        while (i < n)
        {
            if (bytesEqual(arr[i], want))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Image Type address of {@code cls} (a class or interface — both live in the Types region). */
    private static long typeImgAddr(byte[] cls)
    {
        int i = findByteArr(typeClasses, typeClassesN, cls);
        return i < 0 ? 0L : dAddr(dTypesStart + i * (ObjectModel.TYPE_SIZE / 4));
    }

    private static long tibImgAddr(byte[] cls)
    {
        int j = findByteArr(tibSeenCls, tibSeenN, cls);
        return j < 0 ? 0L : dAddr(dTibOff[j]);
    }

    private static long strImgAddr(byte[] s)
    {
        int k = findByteArr(drStr, drStrN, s);
        return k < 0 ? 0L : dAddr(dStrOff[k]);
    }

    private static long statImgAddr(byte[] cls, byte[] nm)
    {
        int i = 0;
        while (i < drStatN)
        {
            if (bytesEqual(drStatCls[i], cls) && bytesEqual(drStatName[i], nm))
            {
                return dAddr(dStaticsStart + i * (ObjectModel.WORD / 4));
            }
            i += 1;
        }
        return 0L;
    }

    /** Image address of the runtime helper with id {@code id} (matches imEnqueueHelper's method keys). */
    private static long helperImgAddr(int id)
    {
        if (id == 0)
        {
            return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J"));
        }
        if (id == 1)
        {
            return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J"));
        }
        if (id == 2)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V"));
        }
        if (id == 3)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        }
        if (id == 4)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        }
        return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V"));
    }

    /** Patch every relocation site in {@code sym}'s just-compiled {@code words} to its image address. */
    private static void patchImageRelocs(MetalWriterSymbols sym, int[] words, long base)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            int site = sym.callSiteWord(c);
            long target = imAddrOf(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)), utf8Copy(sym.callDescOff(c)));
            words[site] = A64Enc.bl((int) ((target - (base + site * 4L)) / 4L));
            c += 1;
        }
        int h = 0;
        while (h < sym.helperCount())
        {
            int site = sym.helperSiteWord(h);
            long target = helperImgAddr(sym.helperId(h));
            words[site] = A64Enc.bl((int) ((target - (base + site * 4L)) / 4L));
            h += 1;
        }
        int b = 0;
        while (b < sym.tibCount())
        {
            patchAddrWords(words, sym.tibSiteWord(b), sym.tibReg(b), tibImgAddr(utf8Copy(sym.tibClassOff(b))));
            b += 1;
        }
        int y = 0;
        while (y < sym.typeCount())
        {
            patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), typeImgAddr(utf8Copy(sym.typeClassOff(y))));
            y += 1;
        }
        int f = 0;
        while (f < sym.ifCount())
        {
            patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), typeImgAddr(utf8Copy(sym.ifClassOff(f))));
            f += 1;
        }
        int st = 0;
        while (st < sym.staticCount())
        {
            patchAddrWords(words, sym.staticSiteWord(st), sym.staticReg(st),
                           statImgAddr(utf8Copy(sym.staticClassOff(st)), utf8Copy(sym.staticNameOff(st))));
            st += 1;
        }
        int sg = 0;
        while (sg < sym.strCount())
        {
            patchAddrWords(words, sym.strSiteWord(sg), sym.strReg(sg), strImgAddr(utf8Copy(sym.strUtf8Off(sg))));
            sg += 1;
        }
        int x = 0;
        while (x < sym.excCount())
        {
            patchAddrWords(words, sym.excSiteWord(x), sym.excReg(x),
                           statImgAddr(Magic.bytes("vm/VM"), Magic.bytes("$exception")));
            x += 1;
        }
        int pc = 0;
        while (pc < sym.pcCount())
        {
            patchAddrWords(words, sym.pcSiteWord(pc), sym.pcReg(pc), base + sym.pcTarget(pc) * 4L);
            pc += 1;
        }
    }

    /** The generated initClasses method's words at image {@code base}: BL each {@code <clinit>} (in
     *  discovery order) to its image address, framed exactly as {@link #runGeneratedInitClasses}. */
    private static int[] genInitClassesWords(long base)
    {
        byte[] clinit = Magic.bytes("<clinit>");
        int frame = A64Enc.align16(8);
        int[] words = new int[2 + clinitCount + 3];
        words[0] = A64Enc.subImm(31, 31, frame);
        words[1] = A64Enc.strx(30, 31, 0);
        int wi = 2;
        int j = 0;
        while (j < imN)
        {
            if (bytesEqual(imName[j], clinit))
            {
                long target = imAddrOf(imClsName[j], clinit, Magic.bytes("()V"));
                words[wi] = A64Enc.bl((int) ((target - (base + wi * 4L)) / 4L));
                wi += 1;
            }
            j += 1;
        }
        words[wi] = A64Enc.ldrx(30, 31, 0);
        words[wi + 1] = A64Enc.addImm(31, 31, frame);
        words[wi + 2] = A64Enc.ret();
        return words;
    }

    /** Whole code-region content fixpoint: compile every method at its image base with all relocations
     *  resolved to image addresses, and word-compare against the running image. */
    private static boolean fixpointCode()
    {
        discoverImage();
        layoutDataRegions();
        byte[] initName = Magic.bytes("initClasses");
        byte[] vmName = Magic.bytes("vm/VM");
        int idx = 0;
        while (idx < imN)
        {
            long base = imAddrOf(imClsName[idx], imName[idx], imDesc[idx]);
            int[] words;
            if (bytesEqual(imName[idx], initName) && bytesEqual(imClsName[idx], vmName))
            {
                words = genInitClassesWords(base);
            }
            else
            {
                setClassContext(imCls[idx]);
                int body = findMethodBody(imName[idx], imDesc[idx]);
                MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
                words = compileInto(body, sym, base, idx == 0);
                patchImageRelocs(sym, words, base);
            }
            if (!fixpointEquals(words, base))
            {
                return false;
            }
            idx += 1;
        }
        return true;
    }

    // ----- data-region content materialise + compare (Types/itables/TIBs/strings/statics/unwind/blobs/classtable) -----

    /** Compare a 32-bit {@code val} against the image word at 0x80000-relative {@code word}; the word on
     *  mismatch, else -1. */
    private static int chkW(int word, long val)
    {
        if ((val & 0xFFFFFFFFL) != (Magic.load32(dAddr(word)) & 0xFFFFFFFFL))
        {
            return word;
        }
        return -1;
    }

    /** Compare a 64-bit {@code val} (two words) against the image; first mismatching word, or -1. */
    private static int chkLong(int word, long val)
    {
        int r = chkW(word, val);
        if (r >= 0)
        {
            return r;
        }
        return chkW(word + 1, val >>> 32);
    }

    /** Compare {@code b} packed (little-endian, 8-byte-aligned) against the image at {@code word}; -1 if equal. */
    private static int chkBytes(int word, byte[] b)
    {
        int total = ((b.length + 7) & ~7) / 4;
        int p = 0;
        while (p < total)
        {
            long packed = 0L;
            int q = 0;
            while (q < 4)
            {
                int bi = p * 4 + q;
                if (bi < b.length)
                {
                    packed |= ((long) (b[bi] & 0xFF)) << (q * 8);
                }
                q += 1;
            }
            int r = chkW(word + p, packed);
            if (r >= 0)
            {
                return r;
            }
            p += 1;
        }
        return -1;
    }

    private static byte[] blobClass(int b)
    {
        if (b == 0) { return Magic.bytes("vm/Guest"); }
        if (b == 1) { return Magic.bytes("vm/Greeter"); }
        if (b == 2) { return Magic.bytes("vm/Alpha"); }
        if (b == 3) { return Magic.bytes("vm/Beta"); }
        if (b == 4) { return Magic.bytes("vm/MyExc"); }
        if (b == 5) { return Magic.bytes("java/lang/Math"); }
        if (b == 6) { return Magic.bytes("java/lang/Runnable"); }
        if (b == 7) { return Magic.bytes("java/lang/Thread"); }
        if (b == 8) { return Magic.bytes("java/util/concurrent/Semaphore"); }
        if (b == 9) { return Magic.bytes("demo/Philosopher"); }
        if (b == 10) { return Magic.bytes("demo/DiningPhilosophers"); }
        if (b == 11) { return Magic.bytes("java/lang/String"); }
        if (b == 12) { return Magic.bytes("demo/ConcatDemo"); }
        if (b == 13) { return Magic.bytes("demo/LambdaDemo"); }
        if (b == 14) { return Magic.bytes("demo/IntOp"); }
        if (b == 15) { return Magic.bytes("java/lang/Integer"); }
        if (b == 16) { return Magic.bytes("demo/FloatDemo"); }
        if (b == 17) { return Magic.bytes("demo/NativeDemo"); }
        if (b == 18) { return Magic.bytes("java/lang/StringBuilder"); }
        if (b == 19) { return Magic.bytes("demo/StrDemo"); }
        if (b == 20) { return Magic.bytes("java/lang/Throwable"); }
        if (b == 21) { return Magic.bytes("java/lang/Exception"); }
        if (b == 22) { return Magic.bytes("java/lang/RuntimeException"); }
        if (b == 23) { return Magic.bytes("java/lang/NullPointerException"); }
        if (b == 24) { return Magic.bytes("java/lang/IndexOutOfBoundsException"); }
        if (b == 25) { return Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"); }
        if (b == 26) { return Magic.bytes("demo/ExcDemo"); }
        if (b == 27) { return Magic.bytes("java/util/ArrayList"); }
        if (b == 28) { return Magic.bytes("demo/ListDemo"); }
        if (b == 29) { return Magic.bytes("java/lang/Object"); }
        if (b == 30) { return Magic.bytes("java/util/HashMap"); }
        if (b == 31) { return Magic.bytes("demo/MapDemo"); }
        if (b == 32) { return Magic.bytes("java/lang/Long"); }
        if (b == 33) { return Magic.bytes("java/lang/Character"); }
        if (b == 34) { return Magic.bytes("java/lang/IllegalArgumentException"); }
        if (b == 35) { return Magic.bytes("java/lang/NumberFormatException"); }
        if (b == 36) { return Magic.bytes("demo/ParseAllDemo"); }
        if (b == 37) { return Magic.bytes("java/lang/StringLatin1"); }
        if (b == 38) { return Magic.bytes("jdk/internal/util/DecimalDigits"); }
        if (b == 39) { return Magic.bytes("demo/ToStringDemo"); }
        if (b == 40) { return Magic.bytes("demo/HexLongDemo"); }
        if (b == 41) { return Magic.bytes("demo/LongMoreDemo"); }
        if (b == 42) { return Magic.bytes("java/lang/ArithmeticException"); }
        if (b == 43) { return Magic.bytes("demo/MathIntDemo"); }
        if (b == 44) { return Magic.bytes("java/util/Objects"); }
        if (b == 45) { return Magic.bytes("demo/ObjectsDemo"); }
        if (b == 46) { return Magic.bytes("java/util/Arrays"); }
        if (b == 47) { return Magic.bytes("jdk/internal/util/ArraysSupport"); }
        if (b == 48) { return Magic.bytes("demo/ArraysDemo"); }
        if (b == 49) { return Magic.bytes("java/lang/Number"); }
        if (b == 50) { return Magic.bytes("java/lang/Integer$IntegerCache"); }
        if (b == 51) { return Magic.bytes("demo/BoxingDemo"); }
        if (b == 52) { return Magic.bytes("demo/StrOpsDemo"); }
        if (b == 53) { return Magic.bytes("java/util/List"); }
        if (b == 54) { return Magic.bytes("java/lang/Iterable"); }
        if (b == 55) { return Magic.bytes("java/util/Iterator"); }
        if (b == 56) { return Magic.bytes("java/util/ArrayListIterator"); }
        if (b == 57) { return Magic.bytes("java/util/LinkedList"); }
        if (b == 58) { return Magic.bytes("java/util/LinkedListNode"); }
        if (b == 59) { return Magic.bytes("java/util/LinkedListIterator"); }
        if (b == 60) { return Magic.bytes("java/util/Map"); }
        if (b == 61) { return Magic.bytes("java/util/Collection"); }
        if (b == 62) { return Magic.bytes("java/util/Collections"); }
        if (b == 63) { return Magic.bytes("java/lang/Comparable"); }
        if (b == 64) { return Magic.bytes("demo/Num"); }
        if (b == 65) { return Magic.bytes("java/util/Comparator"); }
        if (b == 66) { return Magic.bytes("demo/Order"); }
        if (b == 67) { return Magic.bytes("demo/Factory"); }
        if (b == 68) { return Magic.bytes("java/util/function/Predicate"); }
        if (b == 69) { return Magic.bytes("java/util/function/Function"); }
        if (b == 70) { return Magic.bytes("java/util/function/Consumer"); }
        if (b == 71) { return Magic.bytes("demo/Stream"); }
        if (b == 72) { return Magic.bytes("java/util/function/BinaryOperator"); }
        return Magic.bytes("java/util/function/BiConsumer");
    }

    /** The writer-stashed value of static {@code vm/VM.name}, or 0 for a runtime-init / $exception slot. */
    private static long staticValue(byte[] cls, byte[] nm)
    {
        if (!bytesEqual(cls, Magic.bytes("vm/VM")))
        {
            return 0L;
        }
        if (bytesEqual(nm, Magic.bytes("frameTable"))) { return dAddr(dFrameStart); }
        if (bytesEqual(nm, Magic.bytes("frameCount"))) { return drFrameCount; }
        if (bytesEqual(nm, Magic.bytes("handlerTable"))) { return dAddr(dHandlerStart); }
        if (bytesEqual(nm, Magic.bytes("handlerCount"))) { return drHandlerCount; }
        if (bytesEqual(nm, Magic.bytes("staticsStart"))) { return dAddr(dStaticsStart); }
        if (bytesEqual(nm, Magic.bytes("staticsEnd"))) { return dAddr(dStaticsEnd); }
        if (bytesEqual(nm, Magic.bytes("classDir"))) { return dAddr(dClassDirStart); }
        if (bytesEqual(nm, Magic.bytes("classCount"))) { return classCount; }
        if (bytesEqual(nm, Magic.bytes("heapAlloc"))) { return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J")); }
        if (bytesEqual(nm, Magic.bytes("allocArray"))) { return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J")); }
        if (bytesEqual(nm, Magic.bytes("gcCollect"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("instanceOfAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I")); }
        if (bytesEqual(nm, Magic.bytes("checkCastAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J")); }
        if (bytesEqual(nm, Magic.bytes("unwindAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V")); }
        if (bytesEqual(nm, Magic.bytes("reportFaultAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("reportFault"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("throwFromFaultAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("throwFromFault"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("irqHandlerAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("irqHandler"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("scheduleAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("schedule"), Magic.bytes("(J)J")); }
        if (bytesEqual(nm, Magic.bytes("yieldPickAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("yieldPick"), Magic.bytes("(J)J")); }
        if (bytesEqual(nm, Magic.bytes("taskAAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("taskA"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("taskBAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("taskB"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("taskCAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("taskC"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("taskRAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("taskR"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("secondaryMainAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("secondaryMain"), Magic.bytes("(I)V")); }
        if (bytesEqual(nm, Magic.bytes("pcScheduleAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("pcSchedule"), Magic.bytes("(J)J")); }
        if (bytesEqual(nm, Magic.bytes("pcTask1Addr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("pcTask1"), Magic.bytes("(I)V")); }
        if (bytesEqual(nm, Magic.bytes("startThreadAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("startThread"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("objWaitAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("objWait"), Magic.bytes("(JJ)V")); }
        if (bytesEqual(nm, Magic.bytes("objNotifyAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("objNotify"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("objNotifyAllAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("objNotifyAll"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("monEnterAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("monEnter"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("monExitAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("monExit"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("holdsLockAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("holdsLock"), Magic.bytes("(J)I")); }
        if (bytesEqual(nm, Magic.bytes("interruptAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("interrupt"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("isInterruptedAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("isInterrupted"), Magic.bytes("(J)I")); }
        if (bytesEqual(nm, Magic.bytes("checkIntrAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkClearInterrupt"), Magic.bytes("()I")); }
        if (bytesEqual(nm, Magic.bytes("isAliveAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("isAlive"), Magic.bytes("(J)I")); }
        if (bytesEqual(nm, Magic.bytes("joinTimedAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("joinTimed"), Magic.bytes("(JJ)I")); }
        if (bytesEqual(nm, Magic.bytes("parkAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("park"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("unparkAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unpark"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("threadJoinAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("threadJoin"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("threadStackTraceAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("threadStackTrace"), Magic.bytes("(JJJ)J")); }
        if (bytesEqual(nm, Magic.bytes("allThreadsAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("allThreads"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("semWaitAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("semWait"), Magic.bytes("(I)V")); }
        if (bytesEqual(nm, Magic.bytes("semPostAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("semPost"), Magic.bytes("(I)V")); }
        if (bytesEqual(nm, Magic.bytes("sleepAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("sleep"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("newSemAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("newSem"), Magic.bytes("(I)I")); }
        if (bytesEqual(nm, Magic.bytes("philReportAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("philReport"), Magic.bytes("(II)V")); }
        if (bytesEqual(nm, Magic.bytes("taskExitAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("taskExit"), Magic.bytes("()V")); }
        if (bytesEqual(nm, Magic.bytes("scStartAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scStart"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("scCharAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scChar"), Magic.bytes("(JI)V")); }
        if (bytesEqual(nm, Magic.bytes("scIntAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scInt"), Magic.bytes("(JI)V")); }
        if (bytesEqual(nm, Magic.bytes("scEndAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scEnd"), Magic.bytes("(J)J")); }
        if (bytesEqual(nm, Magic.bytes("scStrAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scStr"), Magic.bytes("(JJ)V")); }
        if (bytesEqual(nm, Magic.bytes("scLongAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("scLong"), Magic.bytes("(JJ)V")); }
        if (bytesEqual(nm, Magic.bytes("printStrAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("printStr"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("nanoTimeAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("nanoTime"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("currentTimeMillisAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("currentTimeMillis"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("identityAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("identity"), Magic.bytes("(J)J")); }
        if (bytesEqual(nm, Magic.bytes("arraycopyAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("arraycopy"), Magic.bytes("(JIJII)V")); }
        if (bytesEqual(nm, Magic.bytes("newNpeAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("newNpe"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("newAioobeAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("newAioobe"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("newArithAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("newArith"), Magic.bytes("()J")); }
        if (bytesEqual(nm, Magic.bytes("printStackTraceAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("printStackTrace"), Magic.bytes("(J)V")); }
        long blobV = blobStatic(nm);
        return blobV;
    }

    /** Blob address/length statics ({@code guestBytes}/{@code guestLen}/...), or 0 if {@code nm} is none. */
    private static long blobStatic(byte[] nm)
    {
        int b = 0;
        while (b < BLOB_COUNT)
        {
            byte[] c = blobClass(b);
            if (bytesEqual(nm, blobAddrName(b)))
            {
                return dAddr(dBlobOff[b]);
            }
            if (bytesEqual(nm, blobLenName(b)))
            {
                return MetalClassModel.bytesOf(c).length;
            }
            b += 1;
        }
        return 0L;
    }

    private static byte[] blobAddrName(int b)
    {
        if (b == 0) { return Magic.bytes("guestBytes"); }
        if (b == 1) { return Magic.bytes("greeterBytes"); }
        if (b == 2) { return Magic.bytes("alphaBytes"); }
        if (b == 3) { return Magic.bytes("betaBytes"); }
        if (b == 4) { return Magic.bytes("myExcBytes"); }
        if (b == 5) { return Magic.bytes("mathBytes"); }
        if (b == 6) { return Magic.bytes("runnableBytes"); }
        if (b == 7) { return Magic.bytes("threadBytes"); }
        if (b == 8) { return Magic.bytes("semBytes"); }
        if (b == 9) { return Magic.bytes("philosopherBytes"); }
        if (b == 10) { return Magic.bytes("philBytes"); }
        if (b == 11) { return Magic.bytes("stringBytes"); }
        if (b == 12) { return Magic.bytes("concatDemoBytes"); }
        if (b == 13) { return Magic.bytes("lambdaDemoBytes"); }
        if (b == 14) { return Magic.bytes("intOpBytes"); }
        if (b == 15) { return Magic.bytes("integerBytes"); }
        if (b == 16) { return Magic.bytes("floatDemoBytes"); }
        if (b == 17) { return Magic.bytes("nativeDemoBytes"); }
        if (b == 18) { return Magic.bytes("stringBuilderBytes"); }
        if (b == 19) { return Magic.bytes("strDemoBytes"); }
        if (b == 20) { return Magic.bytes("throwableBytes"); }
        if (b == 21) { return Magic.bytes("exceptionBytes"); }
        if (b == 22) { return Magic.bytes("runtimeExcBytes"); }
        if (b == 23) { return Magic.bytes("npeBytes"); }
        if (b == 24) { return Magic.bytes("ioobeBytes"); }
        if (b == 25) { return Magic.bytes("aioobeBytes"); }
        if (b == 26) { return Magic.bytes("excDemoBytes"); }
        if (b == 27) { return Magic.bytes("arrayListBytes"); }
        if (b == 28) { return Magic.bytes("listDemoBytes"); }
        if (b == 29) { return Magic.bytes("objectBytes"); }
        if (b == 30) { return Magic.bytes("hashMapBytes"); }
        if (b == 31) { return Magic.bytes("mapDemoBytes"); }
        if (b == 32) { return Magic.bytes("longBytes"); }
        if (b == 33) { return Magic.bytes("characterBytes"); }
        if (b == 34) { return Magic.bytes("illegalArgBytes"); }
        if (b == 35) { return Magic.bytes("numberFmtBytes"); }
        if (b == 36) { return Magic.bytes("parseAllDemoBytes"); }
        if (b == 37) { return Magic.bytes("stringLatin1Bytes"); }
        if (b == 38) { return Magic.bytes("decimalDigitsBytes"); }
        if (b == 39) { return Magic.bytes("toStringDemoBytes"); }
        if (b == 40) { return Magic.bytes("hexLongDemoBytes"); }
        if (b == 41) { return Magic.bytes("longMoreDemoBytes"); }
        if (b == 42) { return Magic.bytes("arithExcBytes"); }
        if (b == 43) { return Magic.bytes("mathIntDemoBytes"); }
        if (b == 44) { return Magic.bytes("objectsBytes"); }
        if (b == 45) { return Magic.bytes("objectsDemoBytes"); }
        if (b == 46) { return Magic.bytes("arraysBytes"); }
        if (b == 47) { return Magic.bytes("arraysSupportBytes"); }
        if (b == 48) { return Magic.bytes("arraysDemoBytes"); }
        if (b == 49) { return Magic.bytes("numberBytes"); }
        if (b == 50) { return Magic.bytes("integerCacheBytes"); }
        if (b == 51) { return Magic.bytes("boxingDemoBytes"); }
        if (b == 52) { return Magic.bytes("strOpsDemoBytes"); }
        if (b == 53) { return Magic.bytes("listBytes"); }
        if (b == 54) { return Magic.bytes("iterableBytes"); }
        if (b == 55) { return Magic.bytes("iteratorBytes"); }
        if (b == 56) { return Magic.bytes("arrayListIteratorBytes"); }
        if (b == 57) { return Magic.bytes("linkedListBytes"); }
        if (b == 58) { return Magic.bytes("linkedListNodeBytes"); }
        if (b == 59) { return Magic.bytes("linkedListIteratorBytes"); }
        if (b == 60) { return Magic.bytes("mapBytes"); }
        if (b == 61) { return Magic.bytes("collectionBytes"); }
        if (b == 62) { return Magic.bytes("collectionsBytes"); }
        if (b == 63) { return Magic.bytes("comparableBytes"); }
        if (b == 64) { return Magic.bytes("numBytes"); }
        if (b == 65) { return Magic.bytes("comparatorBytes"); }
        if (b == 66) { return Magic.bytes("orderBytes"); }
        if (b == 67) { return Magic.bytes("factoryBytes"); }
        if (b == 68) { return Magic.bytes("predicateBytes"); }
        if (b == 69) { return Magic.bytes("functionBytes"); }
        if (b == 70) { return Magic.bytes("consumerBytes"); }
        if (b == 71) { return Magic.bytes("streamBytes"); }
        if (b == 72) { return Magic.bytes("binaryOpBytes"); }
        return Magic.bytes("biConsumerBytes");
    }

    private static byte[] blobLenName(int b)
    {
        if (b == 0) { return Magic.bytes("guestLen"); }
        if (b == 1) { return Magic.bytes("greeterLen"); }
        if (b == 2) { return Magic.bytes("alphaLen"); }
        if (b == 3) { return Magic.bytes("betaLen"); }
        if (b == 4) { return Magic.bytes("myExcLen"); }
        if (b == 5) { return Magic.bytes("mathLen"); }
        if (b == 6) { return Magic.bytes("runnableLen"); }
        if (b == 7) { return Magic.bytes("threadLen"); }
        if (b == 8) { return Magic.bytes("semLen"); }
        if (b == 9) { return Magic.bytes("philosopherLen"); }
        if (b == 10) { return Magic.bytes("philLen"); }
        if (b == 11) { return Magic.bytes("stringLen"); }
        if (b == 12) { return Magic.bytes("concatDemoLen"); }
        if (b == 13) { return Magic.bytes("lambdaDemoLen"); }
        if (b == 14) { return Magic.bytes("intOpLen"); }
        if (b == 15) { return Magic.bytes("integerLen"); }
        if (b == 16) { return Magic.bytes("floatDemoLen"); }
        if (b == 17) { return Magic.bytes("nativeDemoLen"); }
        if (b == 18) { return Magic.bytes("stringBuilderLen"); }
        if (b == 19) { return Magic.bytes("strDemoLen"); }
        if (b == 20) { return Magic.bytes("throwableLen"); }
        if (b == 21) { return Magic.bytes("exceptionLen"); }
        if (b == 22) { return Magic.bytes("runtimeExcLen"); }
        if (b == 23) { return Magic.bytes("npeLen"); }
        if (b == 24) { return Magic.bytes("ioobeLen"); }
        if (b == 25) { return Magic.bytes("aioobeLen"); }
        if (b == 26) { return Magic.bytes("excDemoLen"); }
        if (b == 27) { return Magic.bytes("arrayListLen"); }
        if (b == 28) { return Magic.bytes("listDemoLen"); }
        if (b == 29) { return Magic.bytes("objectLen"); }
        if (b == 30) { return Magic.bytes("hashMapLen"); }
        if (b == 31) { return Magic.bytes("mapDemoLen"); }
        if (b == 32) { return Magic.bytes("longLen"); }
        if (b == 33) { return Magic.bytes("characterLen"); }
        if (b == 34) { return Magic.bytes("illegalArgLen"); }
        if (b == 35) { return Magic.bytes("numberFmtLen"); }
        if (b == 36) { return Magic.bytes("parseAllDemoLen"); }
        if (b == 37) { return Magic.bytes("stringLatin1Len"); }
        if (b == 38) { return Magic.bytes("decimalDigitsLen"); }
        if (b == 39) { return Magic.bytes("toStringDemoLen"); }
        if (b == 40) { return Magic.bytes("hexLongDemoLen"); }
        if (b == 41) { return Magic.bytes("longMoreDemoLen"); }
        if (b == 42) { return Magic.bytes("arithExcLen"); }
        if (b == 43) { return Magic.bytes("mathIntDemoLen"); }
        if (b == 44) { return Magic.bytes("objectsLen"); }
        if (b == 45) { return Magic.bytes("objectsDemoLen"); }
        if (b == 46) { return Magic.bytes("arraysLen"); }
        if (b == 47) { return Magic.bytes("arraysSupportLen"); }
        if (b == 48) { return Magic.bytes("arraysDemoLen"); }
        if (b == 49) { return Magic.bytes("numberLen"); }
        if (b == 50) { return Magic.bytes("integerCacheLen"); }
        if (b == 51) { return Magic.bytes("boxingDemoLen"); }
        if (b == 52) { return Magic.bytes("strOpsDemoLen"); }
        if (b == 53) { return Magic.bytes("listLen"); }
        if (b == 54) { return Magic.bytes("iterableLen"); }
        if (b == 55) { return Magic.bytes("iteratorLen"); }
        if (b == 56) { return Magic.bytes("arrayListIteratorLen"); }
        if (b == 57) { return Magic.bytes("linkedListLen"); }
        if (b == 58) { return Magic.bytes("linkedListNodeLen"); }
        if (b == 59) { return Magic.bytes("linkedListIteratorLen"); }
        if (b == 60) { return Magic.bytes("mapLen"); }
        if (b == 61) { return Magic.bytes("collectionLen"); }
        if (b == 62) { return Magic.bytes("collectionsLen"); }
        if (b == 63) { return Magic.bytes("comparableLen"); }
        if (b == 64) { return Magic.bytes("numLen"); }
        if (b == 65) { return Magic.bytes("comparatorLen"); }
        if (b == 66) { return Magic.bytes("orderLen"); }
        if (b == 67) { return Magic.bytes("factoryLen"); }
        if (b == 68) { return Magic.bytes("predicateLen"); }
        if (b == 69) { return Magic.bytes("functionLen"); }
        if (b == 70) { return Magic.bytes("consumerLen"); }
        if (b == 71) { return Magic.bytes("streamLen"); }
        if (b == 72) { return Magic.bytes("binaryOpLen"); }
        return Magic.bytes("biConsumerLen");
    }

    /** First 0x80000-relative word where the reproduced data regions differ from the image, or -1 if identical. */
    private static int firstDataMismatch()
    {
        int wordSlot = ObjectModel.WORD / 4;
        // ----- Types: { instanceSize, superType, itableDir } -----
        int i = 0;
        while (i < typeClassesN)
        {
            byte[] cls = typeClasses[i];
            int tw = dTypesStart + i * (ObjectModel.TYPE_SIZE / 4);
            int r = chkLong(tw + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET / 4,
                            ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(cls)));
            if (r >= 0) { return r; }
            byte[] sup = MetalClassModel.superName(cls);
            long superAddr = sup == null || MetalClassModel.isRoot(sup) ? 0L : typeImgAddr(sup);
            r = chkLong(tw + ObjectModel.TYPE_SUPER_OFFSET / 4, superAddr);
            if (r >= 0) { return r; }
            int j = findByteArr(tibSeenCls, tibSeenN, cls);
            long dir = j >= 0 && dItDirOff[j] >= 0 ? dAddr(dItDirOff[j]) : 0L;
            r = chkLong(tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, dir);
            if (r >= 0) { return r; }
            i += 1;
        }
        // ----- itable directories + itables -----
        int t = 0;
        while (t < tibSeenN)
        {
            if (dItDirOff[t] >= 0)
            {
                int r = chkItable(t);
                if (r >= 0) { return r; }
            }
            t += 1;
        }
        // ----- TIBs: [Type][vtable code addresses] -----
        int jt = 0;
        while (jt < tibSeenN)
        {
            byte[] cls = tibSeenCls[jt];
            int tw = dTibOff[jt];
            int r = chkLong(tw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, typeImgAddr(cls));
            if (r >= 0) { return r; }
            int vs = MetalClassModel.vtableSize(cls);
            int slot = 0;
            while (slot < vs)
            {
                long a = imAddrOf(MetalClassModel.vtableSlotOwner(slot), MetalClassModel.vtableSlotName(slot),
                                  MetalClassModel.vtableSlotDesc(slot));
                r = chkLong(tw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, a);
                if (r >= 0) { return r; }
                slot += 1;
            }
            jt += 1;
        }
        // ----- interned string byte[] objects: [null TIB][status][length][bytes] -----
        int k = 0;
        while (k < drStrN)
        {
            byte[] sb = drStr[k];
            int sw = dStrOff[k];
            int r = chkLong(sw + ObjectModel.TIB_OFFSET / 4, 0L);
            if (r >= 0) { return r; }
            r = chkLong(sw + ObjectModel.STATUS_OFFSET / 4, 0L);
            if (r >= 0) { return r; }
            r = chkLong(sw + ObjectModel.ARRAY_LENGTH_OFFSET / 4, sb.length);
            if (r >= 0) { return r; }
            r = chkBytes(sw + ObjectModel.ARRAY_BASE_OFFSET / 4, sb);
            if (r >= 0) { return r; }
            k += 1;
        }
        // NOTE: the statics region is the program's mutable data segment -- the running image has
        // mutated it (Config.<clinit> wrote mark, counters incremented, freeHead updated, ...), so its
        // byte content is not comparable against a live system. Its layout and the immutable writer-
        // stashed values (staticsStart/frameTable/classDir/helper addrs/...) are validated by the 'H'
        // marker; staticValue() records how they were written for completeness.
        // ----- unwind frame table: { codeStart, codeEnd, frameSize } in method order -----
        int fw = dFrameStart;
        int m = 0;
        while (m < imN)
        {
            if (imFrameSize[m] > 0)
            {
                long base = imAddrOf(imClsName[m], imName[m], imDesc[m]);
                int r = chkLong(fw, base);
                if (r >= 0) { return r; }
                r = chkLong(fw + 2, base + imSize[m] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(fw + 4, imFrameSize[m]);
                if (r >= 0) { return r; }
                fw += 6;
            }
            m += 1;
        }
        // ----- unwind handler table: { start, end, handler, catchType } -----
        int hw = dHandlerStart;
        m = 0;
        while (m < imN)
        {
            long base = imAddrOf(imClsName[m], imName[m], imDesc[m]);
            int h = 0;
            while (h < imHNa[m])
            {
                int r = chkLong(hw, base + imHStartA[m][h] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(hw + 2, base + imHEndA[m][h] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(hw + 4, base + imHandlerA[m][h] * 4L);
                if (r >= 0) { return r; }
                byte[] cc = imHCatchCls[m][h];
                r = chkLong(hw + 6, cc == null ? 0L : typeImgAddr(cc));
                if (r >= 0) { return r; }
                hw += 8;
                h += 1;
            }
            m += 1;
        }
        // ----- blobs: raw .class bytes -----
        int b = 0;
        while (b < BLOB_COUNT)
        {
            int r = chkBytes(dBlobOff[b], MetalClassModel.bytesOf(blobClass(b)));
            if (r >= 0) { return r; }
            b += 1;
        }
        // ----- class table: directory {nameAddr,nameLen,bytesAddr,bytesLen} -----
        int cc2 = (int) classCount;
        int cur = dClassDirStart + cc2 * (4 * wordSlot);
        int ci = 0;
        while (ci < cc2)
        {
            long e = classDir + ci * 32L;                    // read the embedded directory entry
            int nameLen = (int) Magic.load64(e + 8L);
            int bytesLen = (int) Magic.load64(e + 24L);
            int nameW = cur;
            cur += align8W(nameLen);
            int bytesW = cur;
            cur += align8W(bytesLen);
            int de = dClassDirStart + ci * (4 * wordSlot);
            int r = chkLong(de, dAddr(nameW));
            if (r >= 0) { return r; }
            r = chkLong(de + 2, nameLen);
            if (r >= 0) { return r; }
            r = chkLong(de + 4, dAddr(bytesW));
            if (r >= 0) { return r; }
            r = chkLong(de + 6, bytesLen);
            if (r >= 0) { return r; }
            ci += 1;
        }
        return -1;
    }

    /** Compare {@code tibSeenCls[t]}'s itable directory + itables against the image; first bad word or -1. */
    private static int chkItable(int t)
    {
        byte[] c = tibSeenCls[t];
        // ordered implemented interfaces (usedInterfaces order)
        int impls = 0;
        int u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                impls += 1;
            }
            u += 1;
        }
        int dir = dItDirOff[t];
        int itbase = dir + (impls + 1) * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
        // directory entries { interfaceType, itable } + zeroed sentinel
        int e = 0;
        int off = itbase;
        u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                int entry = dir + e * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
                int r = chkLong(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET / 4, typeImgAddr(drUsedIf[u]));
                if (r >= 0) { return r; }
                r = chkLong(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET / 4, dAddr(off));
                if (r >= 0) { return r; }
                off += MetalClassModel.interfaceMethodCount(drUsedIf[u]) * (ObjectModel.WORD / 4);
                e += 1;
            }
            u += 1;
        }
        int sent = dir + impls * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
        int r = chkLong(sent + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET / 4, 0L);
        if (r >= 0) { return r; }
        r = chkLong(sent + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET / 4, 0L);
        if (r >= 0) { return r; }
        // itables: each interface method -> the class's impl address
        off = itbase;
        u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                byte[] iface = drUsedIf[u];
                int n = MetalClassModel.interfaceMethodCount(iface);
                int s = 0;
                while (s < n)
                {
                    byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, s);
                    byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, s);
                    int vslot = MetalClassModel.vtableSlot(c, mName, mDesc);
                    long a = imAddrOf(MetalClassModel.vtableSlotOwner(vslot), mName, mDesc);
                    r = chkLong(off + s * (ObjectModel.WORD / 4), a);
                    if (r >= 0) { return r; }
                    s += 1;
                }
                off += n * (ObjectModel.WORD / 4);
            }
            u += 1;
        }
        return -1;
    }

    /** Enqueue a compiled method's callees (calls) and, for each {@code new}, the instantiated
     *  class's vtable methods (so its TIB can be filled). Runs with {@code cB} = the method's class. */
    private static void discoverFrom(MetalWriterSymbols sym)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            enqueueMethod(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)),
                          utf8Copy(sym.callDescOff(c)));
            c += 1;
        }
        int t = 0;
        while (t < sym.tibCount())
        {
            byte[] tc = utf8Copy(sym.tibClassOff(t));
            int vs = MetalClassModel.vtableSize(tc);           // builds the vtable scratch for tc
            int slot = 0;
            while (slot < vs)
            {
                byte[] owner = MetalClassModel.vtableSlotOwner(slot);
                if (!MetalClassModel.isRoot(owner))
                {
                    enqueueMethod(owner, MetalClassModel.vtableSlotName(slot), MetalClassModel.vtableSlotDesc(slot));
                }
                slot += 1;
            }
            enqueueClinit(tc);
            t += 1;
        }
        int su = 0;
        while (su < sym.staticCount())                          // a used class's <clinit> (eager init)
        {
            enqueueClinit(utf8Copy(sym.staticClassOff(su)));
            su += 1;
        }
    }

    /** Enqueue {@code cls}'s {@code <clinit>} if it has one (closed-world eager static init). */
    private static void enqueueClinit(byte[] cls)
    {
        if (MetalClassModel.hasClinit(cls))
        {
            enqueueMethod(cls, Magic.bytes("<clinit>"), Magic.bytes("()V"));
        }
    }

    /** Register every placed method's frame + try/catch ranges into the jit unwind tables, so a
     *  throw in one metal-built method can unwind into another's catch (cross-method unwind). */
    private static void registerFramesAndHandlers(long codeBuf)
    {
        int m = 0;
        while (m < gmCount)
        {
            long base = codeBuf + gmWordOff[m] * 4L;
            long end = base + gmSize[m] * 4L;
            addJitFrame(base, end, gmFrameSize[m], 0);     // metal-writer path: no pre-try local restore (0)
            setClassContext(gmClsIdx[m]);                  // resolve catch-type cp in this method's class
            int h = 0;
            while (h < gmHN[m])
            {
                long ms = base + gmHStart[m][h] * 4L;
                long me = base + gmHEnd[m][h] * 4L;
                long hh = base + gmHandler[m][h] * 4L;
                long ct = typeAddrOfClassCp(gmHCatch[m][h]);
                addJitHandler(ms, me, hh, ct);
                h += 1;
            }
            m += 1;
        }
    }

    /** Type address for the catch-type Class cp entry {@code cp} in the current class context
     *  ({@code 0} = catch-all/finally, or an unresolved type — matches any exception). */
    private static long typeAddrOfClassCp(int cp)
    {
        if (cp == 0)
        {
            return 0L;
        }
        byte[] name = utf8Copy(ClassReader.classNameOff(cB, cOff, cp));
        int k = findTibClassBytes(name);
        if (k >= 0)
        {
            return nbTypeAddr[k];
        }
        int ki = findInterfaceBytes(name);
        if (ki >= 0)
        {
            return ifTypeAddr[ki];
        }
        return 0L;
    }

    /** Lay out interface Types, then class Types/TIBs (+ itable directories), across the closure. */
    private static void layoutClassRegionsG(long codeBuf)
    {
        nbClass = new byte[32][];
        nbTypeAddr = new long[32];
        nbTibAddr = new long[32];
        nbCount = 0;
        ifIface = new byte[32][];
        ifTypeAddr = new long[32];
        ifCount = 0;
        int m = 0;
        while (m < gmCount)                                // pass 1: interface Types
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int k = 0;
            while (k < sym.ifCount())
            {
                addInterfaceTypeG(utf8Copy(sym.ifClassOff(k)));
                k += 1;
            }
            m += 1;
        }
        m = 0;
        while (m < gmCount)                                // pass 2: class Types/TIBs + itable dirs
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int t = 0;
            while (t < sym.tibCount())
            {
                addClassRegionG(utf8Copy(sym.tibClassOff(t)), codeBuf);
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                addClassRegionG(utf8Copy(sym.typeClassOff(y)), codeBuf);
                y += 1;
            }
            m += 1;
        }
    }

    /** Build an interface's Type ({@code {0,0,0}}) once, if new. */
    private static void addInterfaceTypeG(byte[] name)
    {
        if (findInterfaceBytes(name) >= 0)
        {
            return;
        }
        ifIface[ifCount] = name;
        ifTypeAddr[ifCount] = Heap.allocData(ObjectModel.TYPE_SIZE);   // zeroed
        ifCount += 1;
    }

    /** Index of the laid-out interface with name {@code name}, or -1. */
    private static int findInterfaceBytes(byte[] name)
    {
        int j = 0;
        while (j < ifCount)
        {
            if (bytesEqual(ifIface[j], name))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Build {@code name}'s Type + TIB (vtable filled from placed methods across classes) once. */
    private static void addClassRegionG(byte[] name, long codeBuf)
    {
        if (findTibClassBytes(name) >= 0 || findInterfaceBytes(name) >= 0)
        {
            return;   // already laid out (or an interface, whose Type is built in pass 1)
        }
        long type = Heap.allocData(ObjectModel.TYPE_SIZE);
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET,
                      ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(name)));
        // superType: 0 for a root super (chain ends), else the laid-out super's Type. Heap.alloc
        // does not zero the header region (where superType@8 lives), so this MUST be written — a
        // stale non-zero value sends instanceOf's super-chain walk off into garbage.
        byte[] sup = MetalClassModel.superName(name);
        int si = sup == null || MetalClassModel.isRoot(sup) ? -1 : findTibClassBytes(sup);
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, si >= 0 ? nbTypeAddr[si] : 0L);
        int vs = MetalClassModel.vtableSize(name);
        long tib = Heap.allocData(ObjectModel.tibSize(vs));
        Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT), type);
        int slot = 0;
        while (slot < vs)
        {
            int j = findMethodG(MetalClassModel.vtableSlotOwner(slot), MetalClassModel.vtableSlotName(slot),
                                MetalClassModel.vtableSlotDesc(slot));
            if (j >= 0)
            {
                Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)),
                              codeBuf + gmWordOff[j] * 4L);
            }
            slot += 1;
        }
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, buildItableDirG(name, codeBuf));
        nbClass[nbCount] = name;
        nbTypeAddr[nbCount] = type;
        nbTibAddr[nbCount] = tib;
        nbCount += 1;
    }

    /** Build {@code clsName}'s itable directory over the referenced interfaces it implements (0 if none). */
    private static long buildItableDirG(byte[] clsName, long codeBuf)
    {
        int impls = 0;
        int k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                impls += 1;
            }
            k += 1;
        }
        if (impls == 0)
        {
            return 0L;
        }
        long dir = Heap.allocData((impls + 1) * ObjectModel.ITABLE_ENTRY_SIZE);   // +1 zeroed sentinel
        int e = 0;
        k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                long entry = dir + e * ObjectModel.ITABLE_ENTRY_SIZE;
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET, ifTypeAddr[k]);
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET, buildItableG(clsName, ifIface[k], codeBuf));
                e += 1;
            }
            k += 1;
        }
        return dir;
    }

    /** Build {@code clsName}'s itable for {@code iface}: each interface method's slot → its impl (via the vtable). */
    private static long buildItableG(byte[] clsName, byte[] iface, long codeBuf)
    {
        int n = MetalClassModel.interfaceMethodCount(iface);
        long itab = Heap.allocData(n * ObjectModel.WORD);
        int slot = 0;
        while (slot < n)
        {
            byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, slot);
            byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, slot);
            int vslot = MetalClassModel.vtableSlot(clsName, mName, mDesc);   // the class's impl lives in its vtable
            if (vslot >= 0)
            {
                int j = findMethodG(MetalClassModel.vtableSlotOwner(vslot), mName, mDesc);
                if (j >= 0)
                {
                    Magic.store64(itab + slot * ObjectModel.WORD, codeBuf + gmWordOff[j] * 4L);
                }
            }
            slot += 1;
        }
        return itab;
    }

    /** Index of the laid-out class whose name equals {@code name}, or -1. */
    private static int findTibClassBytes(byte[] name)
    {
        int j = 0;
        while (j < nbCount)
        {
            if (bytesEqual(nbClass[j], name))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Point the {@code cB/cOff/cTag/cAfterCp} cursor at the cached class {@code ci}. */
    private static void setClassContext(int ci)
    {
        cB = lcBytes[ci];
        cOff = lcOff[ci];
        cTag = lcTag[ci];
        cAfterCp = lcAfterCp[ci];
    }

    /** Load + parse a class once, caching it; returns its cache index. */
    private static int loadClass(byte[] name)
    {
        int i = 0;
        while (i < lcCount)
        {
            if (bytesEqual(lcName[i], name))
            {
                return i;
            }
            i += 1;
        }
        byte[] b = MetalClassModel.bytesOf(name);
        int[] off = new int[ClassReader.cpCount(b)];
        int[] tag = new int[off.length];
        lcAfterCp[lcCount] = ClassReader.constantPool(b, off, tag);
        lcName[lcCount] = name;
        lcBytes[lcCount] = b;
        lcOff[lcCount] = off;
        lcTag[lcCount] = tag;
        return lcCount++;
    }

    /** Enqueue a method (class,name,desc) for the closure if not already present. */
    private static void enqueueMethod(byte[] clsName, byte[] name, byte[] desc)
    {
        if (findMethodG(clsName, name, desc) >= 0)
        {
            return;
        }
        gmClsName[gmCount] = clsName;
        gmClsIdx[gmCount] = loadClass(clsName);
        gmName[gmCount] = name;
        gmDesc[gmCount] = desc;
        gmCount += 1;
    }

    /** Index of the discovered method matching (class,name,desc), or -1. */
    private static int findMethodG(byte[] clsName, byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < gmCount)
        {
            if (bytesEqual(gmClsName[j], clsName) && bytesEqual(gmName[j], name) && bytesEqual(gmDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Gather the distinct statics referenced across all discovered methods (by class+field content). */
    private static void collectStaticsG()
    {
        stClass = new byte[16][];
        stName = new byte[16][];
        stAddr = new long[16];
        stCount = 0;
        int m = 0;
        while (m < gmCount)
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int c = 0;
            while (c < sym.staticCount())
            {
                int classOff = sym.staticClassOff(c);
                int nameOff = sym.staticNameOff(c);
                if (findStatic(classOff, nameOff) < 0)
                {
                    stClass[stCount] = utf8Copy(classOff);
                    stName[stCount] = utf8Copy(nameOff);
                    stCount += 1;
                }
                c += 1;
            }
            m += 1;
        }
    }

    /** Patch every discovered method's cross-class calls, statics, and helpers, then write it out. */
    private static boolean patchCrossAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < gmCount)
        {
            setClassContext(gmClsIdx[m]);                  // reloc identities are offsets into this class
            MetalWriterSymbols sym = gmSym[m];
            int[] words = gmWords[m];
            int baseOff = gmWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findMethodG(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)),
                                    utf8Copy(sym.callDescOff(c)));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(gmWordOff[j] - (baseOff + site));   // cross-class BL (same buffer)
                }
                c += 1;
            }
            int h = 0;
            while (h < sym.helperCount())
            {
                int site = sym.helperSiteWord(h);
                long siteAbs = buf + (baseOff + site) * 4L;
                long rel = (helperAddr(sym.helperId(h)) - siteAbs) / 4L;
                words[site] = A64Enc.bl((int) rel);
                h += 1;
            }
            int t = 0;
            while (t < sym.staticCount())
            {
                int k = findStatic(sym.staticClassOff(t), sym.staticNameOff(t));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.staticSiteWord(t), sym.staticReg(t), stAddr[k]);
                }
                t += 1;
            }
            int b = 0;
            while (b < sym.tibCount())
            {
                int k = findTibClassBytes(utf8Copy(sym.tibClassOff(b)));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.tibSiteWord(b), sym.tibReg(b), nbTibAddr[k]);
                }
                b += 1;
            }
            int y = 0;
            while (y < sym.typeCount())                     // instanceof/checkcast: class Type or interface Type
            {
                byte[] tn = utf8Copy(sym.typeClassOff(y));
                int k = findTibClassBytes(tn);
                if (k >= 0)
                {
                    patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), nbTypeAddr[k]);
                }
                else
                {
                    int ki = findInterfaceBytes(tn);
                    if (ki < 0)
                    {
                        ok = false;
                    }
                    else
                    {
                        patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), ifTypeAddr[ki]);
                    }
                }
                y += 1;
            }
            int st = 0;
            while (st < sym.strCount())
            {
                patchAddrWords(words, sym.strSiteWord(st), sym.strReg(st), internLiteral(sym.strUtf8Off(st)));
                st += 1;
            }
            int f = 0;
            while (f < sym.ifCount())
            {
                int k = findInterfaceBytes(utf8Copy(sym.ifClassOff(f)));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), ifTypeAddr[k]);
                }
                f += 1;
            }
            int xc = 0;
            while (xc < sym.excCount())
            {
                patchAddrWords(words, sym.excSiteWord(xc), sym.excReg(xc), excSlot);
                xc += 1;
            }
            int pcx = 0;
            while (pcx < sym.pcCount())                        // athrow self-PC: relocate to its final address
            {
                long selfPc = buf + (baseOff + sym.pcTarget(pcx)) * 4L;
                patchAddrWords(words, sym.pcSiteWord(pcx), sym.pcReg(pcx), selfPc);
                pcx += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    // ----- M5.5c step 3b: metal layout engine (build + execute a call closure) -----
    // The class context + method table are static (like Loader's registries) so the helpers
    // stay low-arity — the baseline compiler allows only 7 operand-stack slots (OP_MAX).

    private static byte[] cB;       // current class bytes
    private static int[] cOff;      // ... its constant-pool offset table
    private static int[] cTag;      // ... and tags
    private static int cAfterCp;    // offset just past the constant pool

    private static byte[][] clName; // placed methods: name / descriptor identity bytes
    private static byte[][] clDesc;
    private static int[] clSize;    // ... A64 word count
    private static int[] clWordOff; // ... assigned word offset in the buffer
    private static int[][] clWords; // ... compiled words
    private static MetalWriterSymbols[] clSym;  // ... recorded relocations
    private static int clCount;

    private static int fDescOff;    // descriptor Utf8 offset of the last findMethodBody hit
    private static boolean fStatic; // ... and whether it is static (frame/ABI)

    /**
     * Build the call closure of {@code Uart.putc} into a {@link Heap} buffer — discover,
     * place, compile-at-base, patch the BL sites — then run it: the metal writer's layout
     * engine producing working code. The built {@code putc} prints {@code '~'}. Returns
     * whether every recorded call resolved to a placed method.
     */
    private static boolean selfBuildClosureAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("board/bcm2711/Uart"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("putc");
        clDesc[0] = Magic.bytes("(I)V");
        clCount = 1;

        // discover + compile (base 0; pure-call word counts are placement-independent).
        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            discoverCallees(sym);
            i += 1;
        }

        // place contiguously and allocate the buffer.
        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long buf = Heap.allocData(cur * 4);

        // patch each recorded call to its callee's base, then write the words to the buffer.
        boolean ok = patchAndWrite(buf);
        Heap.publishCode(buf, buf + cur * 4L);             // I-cache maintenance before executing built code

        long entry = buf + clWordOff[0] * 4L;
        long unused = Magic.call2(entry, 0x7EL, 0L);       // run the built putc('~'); ignore the void return
        return ok;
    }

    /** Enqueue any callee of {@code sym} not already in the method table. */
    private static void discoverCallees(MetalWriterSymbols sym)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            int nameOff = sym.callNameOff(c);
            int descOff = sym.callDescOff(c);
            if (findPlaced(nameOff, descOff) < 0)
            {
                clName[clCount] = utf8Copy(nameOff);
                clDesc[clCount] = utf8Copy(descOff);
                clCount += 1;
            }
            c += 1;
        }
    }

    /** Patch every method's call sites to their callees' bases and write the words to {@code buf}. */
    private static boolean patchAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));   // relative BL
                }
                c += 1;
            }
            int w = 0;
            while (w < words.length)
            {
                long addr = buf + (baseOff + w) * 4L;
                Magic.store32(addr, words[w]);
                w += 1;
            }
            m += 1;
        }
        return ok;
    }

    /** The Code-attribute body offset of {@code name+desc} in {@link #cB}; sets {@link #fDescOff}/{@link #fStatic}. */
    private static int findMethodBody(byte[] name, byte[] desc)
    {
        byte[] codeAttr = Magic.bytes("Code");
        int p = ClassReader.methodsStart(cB, cAfterCp);
        int n = ClassReader.u2(cB, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            boolean isStatic = (ClassReader.u2(cB, p) & 0x0008) != 0;
            int nameOff = cOff[ClassReader.u2(cB, p + 2)];
            int descOff = cOff[ClassReader.u2(cB, p + 4)];
            int attrs = p + 6;
            if (utf8Eq(cB, nameOff, name) && utf8Eq(cB, descOff, desc))
            {
                fDescOff = descOff;
                fStatic = isStatic;
                return findCodeBody(attrs, codeAttr);
            }
            p = ClassReader.skipAttributes(cB, attrs);
            i += 1;
        }
        return -1;
    }

    /** The Code-attribute body offset among the attribute table at {@code attrs}, or -1. */
    private static int findCodeBody(int attrs, byte[] codeAttr)
    {
        int ac = ClassReader.u2(cB, attrs);
        int q = attrs + 2;
        int a = 0;
        while (a < ac)
        {
            int anOff = cOff[ClassReader.u2(cB, q)];
            int alen = ClassReader.u4(cB, q + 2);
            int bodyOff = q + 6;
            if (utf8Eq(cB, anOff, codeAttr))
            {
                return bodyOff;
            }
            q = bodyOff + alen;
            a += 1;
        }
        return -1;
    }

    /** Compile the method whose Code-attribute body is at {@code body} into A64 words at {@code base}. */
    private static int[] compileInto(int body, MetalWriterSymbols sym, long base)
    {
        return compileInto(body, sym, base, false);
    }

    private static int[] compileInto(int body, MetalWriterSymbols sym, long base, boolean isEntry)
    {
        int maxLocals = ClassReader.u2(cB, body + 2);      // after max_stack(2)
        int codeLen = ClassReader.u4(cB, body + 4);
        int codeStart = body + 8;
        byte[] code = new byte[codeLen];
        int k = 0;
        while (k < codeLen)
        {
            code[k] = (byte) ClassReader.u1(cB, codeStart + k);
            k += 1;
        }
        Baseline bl = new Baseline(cB, cOff, cTag, sym);
        setExceptions(bl, codeStart + codeLen);            // exception_table follows the bytecode
        int[] words = bl.compileBody(code, fDescOff, fStatic, maxLocals, base, isEntry);
        // Capture the frame size + machine handler ranges for cross-method unwind registration.
        fFrameSize = bl.frameSize();
        fHN = bl.handlerCount();
        int h = 0;
        while (h < fHN)
        {
            fHStart[h] = bl.handlerStartWord(h);
            fHEnd[h] = bl.handlerEndWord(h);
            fHandler[h] = bl.handlerWord(h);               // fHCatch[h] set by setExceptions
            h += 1;
        }
        return words;
    }

    private static int fFrameSize;                         // last-compiled method's frame + handlers
    private static int fHN;
    private static final int[] fHStart = new int[16];
    private static final int[] fHEnd = new int[16];
    private static final int[] fHandler = new int[16];
    private static final int[] fHCatch = new int[16];      // catch-type Class cp index per handler

    /** Read the exception_table at {@code ex} and install it on {@code bl}. */
    private static void setExceptions(Baseline bl, int ex)
    {
        int en = ClassReader.u2(cB, ex);
        int[] es = new int[en];
        int[] ee = new int[en];
        int[] eh = new int[en];
        int[] ec = new int[en];
        int j = 0;
        while (j < en)
        {
            int e = ex + 2 + j * 8;
            es[j] = ClassReader.u2(cB, e);
            ee[j] = ClassReader.u2(cB, e + 2);
            eh[j] = ClassReader.u2(cB, e + 4);
            ec[j] = ClassReader.u2(cB, e + 6);
            fHCatch[j] = ec[j];
            j += 1;
        }
        bl.setExceptionTable(es, ee, eh, ec, en);
    }

    /** Index of the placed method whose (name,desc) equal the Utf8 at {@code nameOff/descOff} in {@link #cB}, or -1. */
    private static int findPlaced(int nameOff, int descOff)
    {
        int j = 0;
        while (j < clCount)
        {
            if (utf8Eq(cB, nameOff, clName[j]) && utf8Eq(cB, descOff, clDesc[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Copy the Utf8 entry at {@code cB[off]} onto a fresh heap byte array. */
    private static byte[] utf8Copy(int off)
    {
        int len = ClassReader.u2(cB, off);
        byte[] out = new byte[len];
        int j = 0;
        while (j < len)
        {
            out[j] = (byte) ClassReader.u1(cB, off + 2 + j);
            j += 1;
        }
        return out;
    }

    // ----- M5.5c step 3b.2: static-field relocations + a statics region -----

    private static byte[][] stClass; // distinct statics: owner class-name / field-name identity
    private static byte[][] stName;
    private static long[] stAddr;    // ... its assigned 8-byte slot address
    private static int stCount;

    /**
     * Build {@code Counter.bump}/{@code get} into a Heap buffer, lay out a slot for the
     * shared static {@code count}, patch their getstatic/putstatic address loads to it, then
     * run {@code bump()} three times and {@code get()} — which must return 3.
     */
    private static boolean selfBuildStaticsAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Counter"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("bump");
        clDesc[0] = Magic.bytes("()V");
        clName[1] = Magic.bytes("get");
        clDesc[1] = Magic.bytes("()I");
        clCount = 2;

        // compile each at base 0 (no calls to discover here).
        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        // place methods contiguously.
        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.allocData(cur * 4);

        // collect distinct statics and give each a zeroed 8-byte slot.
        collectStatics();
        long staticsBuf = Heap.allocData(stCount * 8);
        int s = 0;
        while (s < stCount)
        {
            long slot = staticsBuf + s * 8L;
            Magic.store64(slot, 0L);                        // Heap.alloc doesn't zero; count starts 0
            stAddr[s] = slot;
            s += 1;
        }

        boolean ok = patchStaticsAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);     // I-cache maintenance before executing built code

        // execute: three bumps then a read.
        long bumpEntry = codeBuf + clWordOff[0] * 4L;
        long getEntry = codeBuf + clWordOff[1] * 4L;
        long u = Magic.call0(bumpEntry);
        u = Magic.call0(bumpEntry);
        u = Magic.call0(bumpEntry);
        int got = (int) Magic.call0(getEntry);
        return ok && got == 3;
    }

    /** Gather the distinct statics referenced across all placed methods (by class+field content). */
    private static void collectStatics()
    {
        stClass = new byte[16][];
        stName = new byte[16][];
        stAddr = new long[16];
        stCount = 0;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int c = 0;
            while (c < sym.staticCount())
            {
                int classOff = sym.staticClassOff(c);
                int nameOff = sym.staticNameOff(c);
                if (findStatic(classOff, nameOff) < 0)
                {
                    stClass[stCount] = utf8Copy(classOff);
                    stName[stCount] = utf8Copy(nameOff);
                    stCount += 1;
                }
                c += 1;
            }
            m += 1;
        }
    }

    /** Index of the static whose (class,field) equal the Utf8 at {@code classOff/nameOff} in {@code cB}, or -1. */
    private static int findStatic(int classOff, int nameOff)
    {
        int j = 0;
        while (j < stCount)
        {
            if (utf8Eq(cB, classOff, stClass[j]) && utf8Eq(cB, nameOff, stName[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Patch every method's calls + static address loads, then write the words to {@code buf}. */
    private static boolean patchStaticsAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));
                }
                c += 1;
            }
            int t = 0;
            while (t < sym.staticCount())
            {
                int classOff = sym.staticClassOff(t);
                int nameOff = sym.staticNameOff(t);
                int k = findStatic(classOff, nameOff);
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.staticSiteWord(t), sym.staticReg(t), stAddr[k]);
                }
                t += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    /** Fill a 2-word address-load placeholder at {@code site} with MOVZ/MOVK of {@code addr} (as CodeBuffer.patchAddr). */
    private static void patchAddrWords(int[] words, int site, int reg, long addr)
    {
        words[site] = A64Enc.movz(reg, (int) (addr & 0xFFFFL), 0);
        words[site + 1] = A64Enc.movk(reg, (int) ((addr >>> 16) & 0xFFFFL), 1);
    }

    /** Store a method's words into {@code buf} at word offset {@code baseOff}. */
    private static void writeWords(long buf, int baseOff, int[] words)
    {
        int w = 0;
        while (w < words.length)
        {
            long addr = buf + (baseOff + w) * 4L;
            Magic.store32(addr, words[w]);
            w += 1;
        }
    }

    /**
     * Drive the shared {@link Baseline} core over {@code Uart.write([B)V} with a relocating
     * {@link MetalWriterSymbols}, exactly as {@code Loader} drives the JIT, and check it
     * produced a placeholder {@code bl} plus a recorded call to {@code putc} — the metal
     * writer's compile-into-a-buffer capability, before layout wires the sites up.
     */
    private static boolean relocatingCompileReady()
    {
        byte[] b = MetalClassModel.bytesOf(Magic.bytes("board/bcm2711/Uart"));
        int[] off = new int[ClassReader.cpCount(b)];
        int[] tag = new int[off.length];
        int afterCp = ClassReader.constantPool(b, off, tag);
        byte[] wname = Magic.bytes("write");
        byte[] wdesc = Magic.bytes("([B)V");
        byte[] code = Magic.bytes("Code");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            boolean isStatic = (ClassReader.u2(b, p) & 0x0008) != 0;
            int nameOff = off[ClassReader.u2(b, p + 2)];
            int descOff = off[ClassReader.u2(b, p + 4)];
            int attrs = p + 6;
            if (utf8Eq(b, nameOff, wname) && utf8Eq(b, descOff, wdesc))
            {
                int ac = ClassReader.u2(b, attrs);
                int q = attrs + 2;
                int a = 0;
                while (a < ac)
                {
                    int anOff = off[ClassReader.u2(b, q)];
                    int alen = ClassReader.u4(b, q + 2);
                    int body = q + 6;
                    if (utf8Eq(b, anOff, code))
                    {
                        return compileWriteAndCheck(b, off, tag, body, descOff, isStatic);
                    }
                    q = body + alen;
                    a += 1;
                }
            }
            p = ClassReader.skipAttributes(b, attrs);
            i += 1;
        }
        return false;
    }

    /** Compile the method whose Code-attribute body starts at {@code body}, then assert the reloc. */
    private static boolean compileWriteAndCheck(byte[] b, int[] off, int[] tag, int body,
                                                int descOff, boolean isStatic)
    {
        int maxLocals = ClassReader.u2(b, body + 2);       // after max_stack(2)
        int codeLen = ClassReader.u4(b, body + 4);
        int codeStart = body + 8;
        byte[] code = new byte[codeLen];
        int k = 0;
        while (k < codeLen)
        {
            code[k] = (byte) ClassReader.u1(b, codeStart + k);
            k += 1;
        }
        int ex = codeStart + codeLen;                      // exception_table follows the bytecode
        int en = ClassReader.u2(b, ex);
        int[] es = new int[en];
        int[] ee = new int[en];
        int[] eh = new int[en];
        int[] ec = new int[en];
        int j = 0;
        while (j < en)
        {
            int e = ex + 2 + j * 8;
            es[j] = ClassReader.u2(b, e);
            ee[j] = ClassReader.u2(b, e + 2);
            eh[j] = ClassReader.u2(b, e + 4);
            ec[j] = ClassReader.u2(b, e + 6);
            j += 1;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(b, off);
        Baseline bl = new Baseline(b, off, tag, sym);
        bl.setExceptionTable(es, ee, eh, ec, en);
        int[] words = bl.compileBody(code, descOff, isStatic, maxLocals, 0x80000L, false);
        return !sym.failed()
               && sym.callCount() == 1                     // the loop's single putc call
               && words[sym.callSiteWord(0)] == A64Enc.bl(0)  // placeholder in place, site in range
               && sym.callNameIs(0, Magic.bytes("putc"));   // callee identity resolved from the cp
    }

    /** Whether the Utf8 entry at {@code b[off]} equals the plain bytes {@code want}. */
    private static boolean utf8Eq(byte[] b, int off, byte[] want)
    {
        int len = ClassReader.u2(b, off);
        if (len != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < len)
        {
            if (ClassReader.u1(b, off + 2 + j) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** The metal class model's superclass-chain walks agree with the known hierarchy. */
    private static boolean chainWalksReady()
    {
        byte[] dog = Magic.bytes("vm/Dog");
        byte[] animal = Magic.bytes("vm/Animal");
        byte[] cell = Magic.bytes("vm/Cell");
        byte[] robot = Magic.bytes("vm/Robot");
        byte[] speaker = Magic.bytes("vm/Speaker");
        byte[] sound = Magic.bytes("sound");
        byte[] speak = Magic.bytes("speak");
        byte[] get = Magic.bytes("get");
        byte[] inc = Magic.bytes("inc");
        byte[] retI = Magic.bytes("()I");
        byte[] retV = Magic.bytes("()V");
        return MetalClassModel.vtableSize(dog) == 1                          // Dog overrides, no new slot
               && MetalClassModel.vtableSize(animal) == 1
               && MetalClassModel.vtableSize(cell) == 2                      // get, inc
               && MetalClassModel.vtableSlot(dog, sound, retI) == 0
               && MetalClassModel.vtableSlot(animal, sound, retI) == 0       // override shares the slot
               && MetalClassModel.vtableOwnerIs(dog, 0, dog)                 // owner becomes Dog
               && MetalClassModel.vtableOwnerIs(animal, 0, animal)
               && MetalClassModel.vtableSlot(cell, get, retI) >= 0
               && MetalClassModel.vtableSlot(cell, inc, retV) >= 0
               && MetalClassModel.interfaceMethodCount(speaker) == 1
               && MetalClassModel.interfaceMethodSlot(speaker, speak, retI) == 0
               && MetalClassModel.implementsInterface(robot, speaker)
               && !MetalClassModel.implementsInterface(dog, speaker)
               && MetalClassModel.findImplIs(robot, speak, retI, robot)
               && MetalClassModel.findImplIs(dog, sound, retI, dog);
    }

    /** The metal class model's leaf queries agree with the known shapes of embedded classes. */
    private static boolean classModelReady()
    {
        return MetalClassModel.superIs(Magic.bytes("vm/Dog"), Magic.bytes("vm/Animal"))
               && MetalClassModel.instanceFieldCount(Magic.bytes("vm/Cell")) == 1
               && MetalClassModel.hasClinit(Magic.bytes("vm/Config"))
               && !MetalClassModel.hasClinit(Magic.bytes("vm/Cell"))
               && !MetalClassModel.isRoot(Magic.bytes("vm/Dog"))
               && MetalClassModel.isRoot(Magic.bytes("java/lang/Object"));
    }

    /**
     * Every class in the embedded table looks up by its own name to its own bytes, and
     * those bytes start with the classfile magic {@code 0xCAFEBABE}. Proves the metal
     * self-build can resolve its input classes by name from the image (M5.5c step 2).
     */
    private static boolean classTableReady()
    {
        if (classCount == 0L)
        {
            return false;                                  // no table embedded
        }
        long i = 0L;
        while (i < classCount)
        {
            long e = classDir + i * 32L;                   // 4 longs per directory entry
            long nameAddr = Magic.load64(e);
            long nameLen = Magic.load64(e + 8L);
            long bytesAddr = Magic.load64(e + 16L);
            if (findClass(nameAddr, nameLen) != bytesAddr)
            {
                return false;                              // name did not resolve to its own bytes
            }
            if (Magic.load8(bytesAddr) != 0xCA || Magic.load8(bytesAddr + 1L) != 0xFE
                    || Magic.load8(bytesAddr + 2L) != 0xBA || Magic.load8(bytesAddr + 3L) != 0xBE)
            {
                return false;                              // corrupt class bytes
            }
            i = i + 1L;
        }
        return true;
    }

    /** Scan the class table for the name at [nameAddr,nameLen); return its class bytes address, or 0. */
    /** Locate a class's raw bytes by name via BINARY SEARCH over the name-sorted directory (entries are 32B
     *  {nameAddr, nameLen, bytesAddr, bytesLen}). The whole stock java.base is embedded, so this must scale. */
    private static long findClass(long nameAddr, long nameLen)
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
