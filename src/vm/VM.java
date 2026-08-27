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
        ScratchMap.check();                // halt loudly if two fixed-scratch tables overlap -- four bugs in
                                           //   one session came from hand-picked addresses colliding
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










    // ----- SMP mutual exclusion: a hardware spinlock -----------------------------------------------
    // Now that the MMU maps RAM as Normal Inner-Shareable cacheable, the exclusive monitor works, so a
    // real LDAXR/STLXR test-and-set spinlock (Magic.spinLock/spinUnlock over the word at LOCK_ADDR)
    // gives mutual exclusion across all four cores. That the demo below distributes 24 jobs with no
    // duplicates is the proof the atomics -- and therefore the cache-coherent MMU map -- are working.

    static int smpJob;                                     // shared job counter (the "run queue")
    static final int SMP_NJOBS = 24;



    // ===== M7: preemptive scheduling on the timer tick =====================================
    // A task table (parallel arrays) with per-task state. Task 0 is the boot flow; VMScheduler.spawn() adds more,
    // each on its own heap stack. The periodic timer IRQ vectors to a context-switch stub that saves
    // the full interrupted context (x0..x30, ELR_EL1, SPSR_EL1) onto the current task's stack, calls
    // VMScheduler.schedule() -- which wakes due sleepers and picks the next READY task round-robin -- then restores
    // THAT task's context and ERETs into it. A task's saved SP is its whole context, so a switch is a
    // stack swap. VMScheduler.sleep(ms) marks a task SLEEPING so the scheduler skips it until its deadline.

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
    static final int  TASK_RUNNING = 4;     // picked by a core RIGHT NOW: no other core may pick it (SMP)

    // ----- priorities -------------------------------------------------------------------------------
    // 0..1024, HIGHER IS MORE URGENT (the java.lang.Thread convention, where MIN_PRIORITY < MAX_PRIORITY).
    // Scheduling is STRICT: pickNext always takes the highest-priority runnable task, and only rotates
    // round-robin among tasks of EQUAL priority. That is the defining property of a priority scheduler and
    // it also means a busy high-priority task STARVES every lower one -- by design, not by accident. There
    // is no ageing or decay; if a workload needs fairness across priorities it must yield or block.
    // Each task carries TWO priorities. taskBasePrio is what it asked for; taskPrio is what the scheduler
    // uses, which is the base RAISED to the priority of the most urgent task waiting on a monitor this task
    // holds (priority inheritance). Without that lending, a low-priority monitor holder can be preempted by
    // an unrelated middle-priority task and never release, so a high-priority waiter is blocked behind work
    // it outranks -- unbounded priority inversion, the Mars Pathfinder failure.
    static final int  PRIO_MIN  = 0;        // lowest: runs only when nothing else can
    static final int  PRIO_NORM = 512;      // the default every task starts at
    static final int  PRIO_MAX  = 1024;     // highest: preempts everything below it

    /** Clamp {@code p} into {@link #PRIO_MIN}..{@link #PRIO_MAX}. */
    static int clampPrio(int p)
    {
        if (p < PRIO_MIN)
        {
            return PRIO_MIN;
        }
        if (p > PRIO_MAX)
        {
            return PRIO_MAX;
        }
        return p;
    }

    // The task table (parallel arrays, index = task id). Task 0 is the boot flow; VMScheduler.spawn() adds more.
    static long[] taskSp;                   // saved context frame (SP) — the task's whole context
    static long[] taskStackBase;            // its heap stack's object base (a GC root keeps the stack alive)
    static int[]  taskState;                // TASK_READY / TASK_SLEEPING / TASK_BLOCKED / TASK_EMPTY
    static long[] taskWake;                 // CNTPCT deadline at which a sleeping task becomes ready again
    static int[]  taskWaitOn;               // for a BLOCKED task: the semaphore index it is waiting on (-1 = an object monitor)
    static long[] taskWaitObj;              // for a BLOCKED object-monitor waiter: the object it Object.wait()s on (0 = none)
    static int[]  taskDone;                 // 1 once a task ran VMScheduler.taskExit() (its run() returned) — Thread.join() polls this
    static long[] taskThreadObj;            // M4: the guest java/lang/Thread of each task (0 until known/lazily wrapped)
    static int[]  taskInterrupted;          // Thread.interrupt() flag per task (1 = interrupted; sleep/join observe it)
    static int[]  taskPermit;               // LockSupport permit per task (1 = a pending unpark; park consumes it)
    static long[] taskMonWait;              // for a task BLOCKED on monitorenter: the object it is trying to lock (0 = none)
    static int[]  semCount;                 // counting-semaphore values
    static int    taskCount;                // number of live task slots

    // ----- SMP scheduling: one run queue, four cores ------------------------------------------------
    // The task table above is SHARED by every core, so "the task currently running" is per-CORE, not a
    // single global. A task is picked by exactly one core at a time (TASK_RUNNING marks it taken, under
    // SCHED_LOCK) and may run on a different core each time it is scheduled -- its whole context lives in
    // its own heap stack, and the MMU identity-maps all of RAM coherently, so migration is free.
    static int[]  coreTask;                 // index = core: the task that core is running (see curTask())
    static int[]  taskCore;                 // per-task affinity: -1 = any core, else the core it is pinned to
    static int[]  taskIdle;                 // 1 = this task is a core's idle loop (never picked as real work)
    static int[]  taskPrio;                 // EFFECTIVE priority the scheduler uses (base, or an inherited boost)
    static int[]  taskBasePrio;             // the priority the task actually asked for (what getPriority reports)
    static int[]  coreIdle;                 // index = core: that core's idle task id (-1 = none, i.e. core 0)
    static int    smpSched;                 // 1 once the secondaries have joined the shared run queue
    static int    schedGo;                  // released by startSmpScheduling(): secondaries enter the scheduler
    static int    smpStop;                  // 1 = drain: the secondaries take no more work and leave the queue
    static int    smpDemo;                  // 1 = the demo-suite boot: secondaries run the two SMP set pieces first
    static int[]  smpRan;                   // index = core: steps of the SMP threading demo that ran there
    static int[]  prioSteps;                // index = demo task: steps it completed (priority demo)
    static int[]  prioOrder;                // finish order of the priority demo's tasks
    static int    prioDone;                 // how many have finished so far (index into prioOrder)
    static int[]  coreSched;                // index = core: 1 once that core is scheduling from the run queue
    static long   smpTaskAddr;              // VMScheduler.smpTask()V -- the "which core ran me" demo task
    static long   prioTaskAddr;             // VMScheduler.prioTask(I)V -- the priority demo's task
    static long   setPrioAddr;              // VMScheduler.setPriority(JI)V -- Thread.setPriority (guest-called)
    static long   getPrioAddr;              // VMScheduler.getPriority(J)I  -- Thread.getPriority (guest-called)
    // Stop-the-world. The collector runs on core 0 and moves nothing, but it sweeps -- so no other core may
    // MUTATE the heap while it marks. Core 0 raises gcStop; every other core parks in the scheduler (the one
    // place every core passes through, on its own timer tick or its idle yield) with its context already
    // saved to its task's stack, so the collector can trace it. gcParked[c] = 1 while core c is parked.
    static int    gcStop;                   // 1 = every core but the collector must park in pickNext
    static int[]  gcParked;                 // index = core: 1 while that core is parked for the collection
    static long   stwTimeouts;              // collections that started with a core still unparked (a bug signal)
    /** The scheduler's cross-core lock (a {@link Magic#spinLock} word). Held only with IRQs masked, and only
     *  across task-table updates -- never across a UART write or an allocation. A different cache line from
     *  {@link #LOCK_ADDR} so the SMP job-queue demo and the scheduler don't false-share. */
    static final long SCHED_LOCK = 0x0302_0040L;

    // The LOADER lock. The on-metal JIT keeps its whole compile context in statics (methods are capped at
    // ten register locals, so the loader threads its state through fields), and the code arena is one bump
    // pointer -- so two cores compiling at once would interleave into each other's context and hand out the
    // same buffer twice. It is a MUTEX, not a spinlock: the holder can be preempted, and a waiter that spun
    // would keep the core the holder needs. Ownership is by TASK, not by core, because a compiling task can
    // migrate mid-compile; it is recursive because a <clinit> run inside a compile re-enters the loader.
    static int    loaderOwner = -1;         // task id holding the loader lock (-1 = free)
    static int    loaderDepth;              // ... and how many nested acquisitions deep it is

    /** Take the loader lock (recursively, if this task already holds it), yielding while another task has it. */
    static void loaderLock()
    {
        if (smpSched == 0)
        {
            return;                                     // one core in the table: no other compiler to race
        }
        int me = curTask();
        if (loaderOwner == me)
        {
            loaderDepth = loaderDepth + 1;              // re-entry: a <clinit> compiling inside a compile
            return;
        }
        while (true)
        {
            long daif = VMScheduler.schedLock();
            if (loaderOwner < 0)
            {
                loaderOwner = me;
                loaderDepth = 1;
                VMScheduler.schedUnlock(daif);
                return;
            }
            VMScheduler.schedUnlock(daif);
            VMScheduler.taskYield();                    // let the holder run -- it may be on THIS core
        }
    }

    /** Release one level of the loader lock. */
    static void loaderUnlock()
    {
        if (smpSched == 0 || loaderOwner != curTask())
        {
            return;
        }
        loaderDepth = loaderDepth - 1;
        if (loaderDepth <= 0)
        {
            long daif = VMScheduler.schedLock();
            loaderDepth = 0;
            loaderOwner = -1;
            VMScheduler.schedUnlock(daif);
        }
    }

    /** The task this core is running. Per-core: the run queue is shared, the current task is not. */
    static int curTask()
    {
        if (coreTask == null)
        {
            return 0;                       // before the scheduler exists there is only the boot flow
        }
        return coreTask[(int) (Magic.readMPIDR() & 3L)];
    }

    /** Make {@code t} the task this core is running. */
    static void setCurTask(int t)
    {
        coreTask[(int) (Magic.readMPIDR() & 3L)] = t;
    }

    /**
     * Allocate the shared task/monitor/semaphore tables plus the per-core scheduler state, and install the
     * running boot flow as task 0. Task 0 is PINNED to core 0: it runs on the image stack and owns the
     * hardware bring-up, so it must not migrate. Every other task defaults to "any core" -- the run queue
     * is shared, and a task's whole context is its own heap stack, so which core resumes it is free.
     */
    static void allocTaskTables()
    {
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
        taskCore = new int[MAX_TASKS];
        taskIdle = new int[MAX_TASKS];
        taskPrio = new int[MAX_TASKS];
        taskBasePrio = new int[MAX_TASKS];
        monObj = new long[MAX_MON];
        monOwner = new int[MAX_MON];
        monCount = new int[MAX_MON];
        semCount = new int[NUM_SEM];
        coreTask = new int[4];
        coreIdle = new int[4];
        coreSched = new int[4];
        gcParked = new int[4];
        // Initialise every element explicitly. Heap.allocArray does NOT zero its elements (a block off the
        // free list carries whatever the dead object left), and on real hardware DRAM starts full of
        // firmware leftovers -- so "a fresh int[] reads as zeroes" is a QEMU accident, not a guarantee.
        int t = 0;
        while (t < MAX_TASKS)
        {
            taskCore[t] = -1;                               // no affinity: any core may pick it up
            taskIdle[t] = 0;                                // ... and it is real work, not a core's idle loop
            taskPrio[t] = PRIO_NORM;                        // ... at the default priority,
            taskBasePrio[t] = PRIO_NORM;                    // ... with no inherited boost on top
            t += 1;
        }
        int c = 0;
        while (c < 4)
        {
            coreIdle[c] = -1;                               // no idle task until a core joins the run queue
            coreTask[c] = 0;
            coreSched[c] = 0;
            gcParked[c] = 0;
            c += 1;
        }
        // The scheduler's lock word is raw scratch RAM, not a Java field: nothing zeroed it, and spinLock
        // spins while it is non-zero. Leave it and the first schedLock() on hardware never returns.
        Magic.store32(SCHED_LOCK, 0);                       // 0 = free
        taskState[0] = TASK_READY;
        taskCore[0] = 0;                                    // the boot flow never leaves core 0
        taskCount = 1;
        coreSched[0] = 1;                                   // core 0 is always scheduling; 1-3 join later
    }


    /**
     * Priority demo, deliberately SINGLE-CORE -- the secondaries are not on the run queue yet, and with four
     * cores and three tasks everything runs at once and priority proves nothing. Three tasks are spawned LOW
     * first, then MED, then HIGH, so a FIFO or round-robin scheduler would finish them in SPAWN order; under
     * strict priority they must finish HIGH, MED, LOW. None of them yields voluntarily, so only the priority
     * rule decides who makes progress.
     *
     * <p>Task 0 drops itself to the floor for the duration, which is the starvation property on display
     * rather than a trick: it makes no progress at all until every higher-priority task has finished.
     */
    static void prioDemo()
    {
        installSchedVectors();                             // rebuild the switch stubs (the GC demo freed them)
        resetTaskTable();
        prioSteps = new int[3];
        prioOrder = new int[3];
        int z = 0;
        while (z < 3)
        {
            prioSteps[z] = 0;                              // allocArray does not zero: say so explicitly
            prioOrder[z] = -1;
            z += 1;
        }
        prioDone = 0;
        Uart.write(Magic.bytes("priority (0-1024, higher first; spawned LOW first): "));
        int lo = VMScheduler.spawnArg(prioTaskAddr, 0L);   // IRQs are still masked here, so no task can run
        VMScheduler.setTaskPriority(lo, 100);              //   before all three are set up
        int md = VMScheduler.spawnArg(prioTaskAddr, 1L);
        VMScheduler.setTaskPriority(md, 700);
        int hi = VMScheduler.spawnArg(prioTaskAddr, 2L);
        VMScheduler.setTaskPriority(hi, PRIO_MAX);
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                 // preemption live: nobody has to cooperate
        setTaskPrioAndWait();
        stopTimerTick();
        Uart.write(Magic.bytes("finish "));
        int i = 0;
        while (i < 3)
        {
            int id = prioOrder[i];
            if (id == 2)      { Uart.putc((byte) 0x48); }  // 'H'
            else if (id == 1) { Uart.putc((byte) 0x4D); }  // 'M'
            else if (id == 0) { Uart.putc((byte) 0x4C); }  // 'L'
            else              { Uart.putc((byte) 0x3F); }  // '?' -- did not finish
            i += 1;
        }
        Uart.write(Magic.bytes(" (want HML)  steps L/M/H = "));
        printDec(prioSteps[0]);
        Uart.putc((byte) 0x2F);
        printDec(prioSteps[1]);
        Uart.putc((byte) 0x2F);
        printDec(prioSteps[2]);
        Uart.putc(0x0A);
        resetTaskTable();
    }

    /** Step aside to the priority floor and come back only once the three demo tasks are done (1 s cap). */
    static void setTaskPrioAndWait()
    {
        VMScheduler.setTaskPriority(0, PRIO_MIN);          // yields: we do not run again until they finish
        long d0 = Magic.readCNTPCT_EL0();
        while (prioDone < 3 && Magic.readCNTPCT_EL0() < d0 + Magic.readCNTFRQ_EL0())
        {
            VMScheduler.taskYield();                       // safety net if something never finishes
        }
        VMScheduler.setTaskPriority(0, PRIO_NORM);
    }

    /**
     * SMP threading demo: ONE run queue, four cores. Cores 1-3 join the queue (each as an idle task pinned
     * to itself), then six ordinary spawned tasks -- unpinned, so whichever core asks next claims one --
     * step and yield for ~0.5 s. Every step tallies the core it ran on, so the printed line is the evidence
     * that the tasks were spread across all four A72s and migrated between them. On QEMU the secondaries
     * get no timer IRQ, but their idle loop yields through the same {@code pickNext}, so work still
     * distributes there (with fewer steps, since only voluntary yields switch).
     */
    static void smpThreadsDemo()
    {
        if (!SMP_ENABLED)
        {
            return;
        }
        installSchedVectors();                             // rebuild the switch stubs (the GC demo freed them)
        resetTaskTable();                                  // fresh table: task 0 (us), then the demo tasks
        smpRan = new int[4];
        int z = 0;
        while (z < 4)
        {
            smpRan[z] = 0;                                  // allocArray does not zero: say so explicitly
            z += 1;
        }
        Uart.write(Magic.bytes("smp threads (one run queue, four cores):\n"));
        VMScheduler.startSmpScheduling();
        int t = 0;
        while (t < 6)
        {
            VMScheduler.spawn(smpTaskAddr);                // unpinned: any core may run these
            t += 1;
        }
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                 // core 0 preempts too -- here it is just another core
        long d0 = Magic.readCNTPCT_EL0();
        while (Magic.readCNTPCT_EL0() < d0 + Magic.readCNTFRQ_EL0() / 2L)   // ~0.5 s
        {
            VMScheduler.taskYield();                       // task 0 keeps offering core 0 to the queue
        }
        stopTimerTick();
        VMScheduler.stopSmpScheduling();                   // the secondaries leave the table before we reset it
        Uart.write(Magic.bytes("steps/core: "));
        int c = 0;
        while (c < 4)
        {
            Uart.putc((byte) 0x63);                        // 'c'
            Uart.putc((byte) (0x30 + c));
            Uart.putc((byte) 0x3D);                        // '='
            printDec(smpRan[c]);
            Uart.putc((byte) 0x20);
            c += 1;
        }
        Uart.putc(0x0A);
        resetTaskTable();
    }

    /**
     * Drop every task but the boot flow -- what the demo phases that rebuild the scheduler from scratch do
     * between runs. Only valid while the secondaries are OUT of the run queue: each holds an idle task slot
     * of its own, and renumbering the table under a running core would hand it another task's stack.
     */
    static void resetTaskTable()
    {
        taskCount = 1;
        taskState[0] = TASK_READY;
        int c = 0;
        while (c < 4)
        {
            coreTask[c] = 0;
            coreIdle[c] = -1;
            c += 1;
        }
    }

    // Object monitors (real, ownership-tracking + recursive). A side table indexed by locked object; a slot is
    // live while its object is held (monCount >= 1) and freed on the final monitorExit, so it holds only the
    // currently-held monitors. monitorenter/exit and Object.wait/notify + Thread.holdsLock all go through it.
    static final int MAX_MON = 64;
    static long[] monObj;                   // the locked object (0 = free slot)
    static int[]  monOwner;                 // owning task id
    static int[]  monCount;                 // recursion depth


















    // ----- Object monitors: java.lang.Object.wait/notify/notifyAll. The monitor itself is a no-op lock on this
    // mostly-cooperative scheduler (monitorenter/exit are no-ops); wait/notify give the task hand-off. wait()
    // parks the caller on the object (TASK_BLOCKED, taskWaitOn=-1 so no semPost wakes it) until another task
    // notify()s the SAME object; masking IRQs across the test-and-block avoids a lost wakeup like semWait.













    static long threadStackTraceAddr;  // VM.threadStackTrace(JJJ)J — a StackTraceElement[] for a Thread's stack


    static long allThreadsAddr;        // VM.allThreads()J — a Thread[] of every live task's Thread object













    // ----- invokedynamic string-concat: a growable byte[] builder, driven by the JIT'd concat lowering.
    // A builder is a heap holder { value@16 = byte[] buf, count@24 = length so far }. The compiler emits
    // scStart, then scChar/scInt per recipe literal/arg, then scEnd -> a trimmed byte[] (wrapped in a
    // java/lang/String by the JIT). Kept image-side so the intrinsic bottoms out in one place.

    // The current batch's [B array TIB (Loader-set after each loadAll; 0 between batches). VMConcat.scEnd types
    // its result with it so a concat-built String's value is a REAL typed byte[] -- stock code checkcasts/clones
    // String.value (Arrays.copyOf -> "[B".clone -> checkcast "[B" inside getBytes), and VM.checkCast rejects raw
    // (elem-size-header) arrays, which used to halt the first System.out print of a concat string.
    static long byteArrayTibCache;

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

    // ----- provided java.base natives (called by loaded guest code via Loader.nativeBuf) -----


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

    /** Allocate a mini {@code java/lang/ClassCastException} — the JIT calls this on a failed {@code checkcast}. */
    static long newCce()
    {
        return Loader.newCce();
    }

    /**
     * {@code checkcast} predicate: 1 if the cast holds (the JIT falls through), 0 if it must throw a
     * {@link #newCce ClassCastException}. Exactly {@link #checkCast}'s logic with the halt replaced by a
     * return, because a VM helper cannot throw on the caller's behalf -- it has its own frame, so the
     * handler search would start in the wrong place. The JIT branches on this and throws INLINE instead,
     * which is what puts the caller's pc/sp in front of the unwinder.
     */
    static int castOk(long ref, long targetType)
    {
        if (targetType == 0L)
        {
            return 1;      // unresolved target (e.g. an array type "[B"): trust the class-file verifier
        }
        if (ref == 0L)
        {
            return 1;      // null casts to anything
        }
        if (instanceOf(ref, targetType) != 0)
        {
            return 1;
        }
        if (Magic.load64(ref) <= ObjectModel.MAX_RAW_ARRAY_TIB && isArrayType(targetType))
        {
            return 1;      // a RAW array (no Type node) cast to an array class: trust the verifier, as checkCast does
        }
        return 0;
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
    /**
     * {@code NEW_UNRESOLVED}: a `new` of a class the loader could not resolve was executed. Delegates to the
     * loader, which holds the site table and can name the class; it does not return.
     */
    static void newUnresolved(long site)
    {
        long lr = Magic.readLR();                      // FIRST op: x30 = the `new` site + 4 (denylistTrap's idiom)
        if (site < 0L)
        {
            return;                                    // boot force-compile probe: make the helper reachable only
        }
        Loader.reportUnresolvedNew(site, lr - 4L);
    }

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
        if (scheduleAddr == 0L) { scheduleAddr = VMScheduler.schedule(0L); }    // dead calls: make schedule/yieldPick/
        if (yieldPickAddr == 0L) { yieldPickAddr = VMScheduler.yieldPick(0L); } // taskA/taskB/taskC compiled + stashed
        if (taskAAddr == 0L) { VMScheduler.taskA(); }
        if (taskBAddr == 0L) { VMScheduler.taskB(); }
        if (taskCAddr == 0L) { VMScheduler.taskC(); }
        if (taskRAddr == 0L) { VMScheduler.taskR(); }
        if (smpTaskAddr == 0L) { VMScheduler.smpTask(); }                      // the SMP threading demo's task
        if (prioTaskAddr == 0L) { VMScheduler.prioTask(0); }                   // the priority demo's task
        if (setPrioAddr == 0L) { VMScheduler.setPriority(0L, 0); }             // Thread.setPriority/getPriority
        if (getPrioAddr == 0L) { int u = VMScheduler.getPriority(0L); }        //   (JIT'd guest reaches these by addr)
        // Dead calls: the mini java.base runtime reaches these only via writer-stashed addresses (from
        // JIT'd guest code), so force the writer to compile them. Guarded on their stashed addr, so they
        // never actually run on metal (the addr is non-zero there).
        if (startThreadAddr == 0L) { VMScheduler.startThread(0L); }
        if (objWaitAddr == 0L) { VMScheduler.objWait(0L, 0L); }                  // Object.wait/notify/notifyAll + Thread.join
        if (objNotifyAddr == 0L) { VMScheduler.objNotify(0L); }                  // (JIT'd guest reaches these via stashed addrs)
        if (objNotifyAllAddr == 0L) { VMScheduler.objNotifyAll(0L); }
        if (monEnterAddr == 0L) { VMScheduler.monEnter(0L); }                    // monitorenter/exit + Thread.holdsLock
        if (monExitAddr == 0L) { VMScheduler.monExit(0L); }
        if (holdsLockAddr == 0L) { int u = VMScheduler.holdsLock(0L); }
        if (interruptAddr == 0L) { VMScheduler.interrupt(0L); }                  // Thread.interrupt/isInterrupted/isAlive
        if (isInterruptedAddr == 0L) { int u = VMScheduler.isInterrupted(0L); }
        if (checkIntrAddr == 0L) { int u = VMScheduler.checkClearInterrupt(); }
        if (isAliveAddr == 0L) { int u = VMScheduler.isAlive(0L); }
        if (joinTimedAddr == 0L) { int u = VMScheduler.joinTimed(0L, 0L); }      // Thread.join(Duration) + LockSupport
        if (parkAddr == 0L) { VMScheduler.park(); }
        if (unparkAddr == 0L) { VMScheduler.unpark(0L); }
        if (threadJoinAddr == 0L) { VMScheduler.threadJoin(0L); }
        if (threadStackTraceAddr == 0L) { long u = VMScheduler.threadStackTrace(0L, 0L, 0L); }   // Thread.getStackTrace()
        if (allThreadsAddr == 0L) { long u = VMScheduler.allThreads(); }                         // Thread.getAllStackTraces()
        if (newSemAddr == 0L) { int u = VMScheduler.newSem(0); }
        if (philReportAddr == 0L) { VMScheduler.philReport(0, 0); }
        if (taskExitAddr == 0L) { VMScheduler.taskExit(); }
        if (scStartAddr == 0L) { long u = VMConcat.scStart(); }        // string-concat helpers (JIT'd concat only)
        if (scCharAddr == 0L) { VMConcat.scChar(0L, 0); }
        if (scIntAddr == 0L) { VMConcat.scInt(0L, 0); }
        if (scEndAddr == 0L) { long u = VMConcat.scEnd(0L); }
        if (scStrAddr == 0L) { VMConcat.scStr(0L, 0L); }
        if (scLongAddr == 0L) { VMConcat.scLong(0L, 0L); }
        if (printStrAddr == 0L) { printStr(0L); }
        if (nanoTimeAddr == 0L) { long u = VMNatives.nanoTime(); }              // provided java.base natives (guest-called)
        if (currentTimeMillisAddr == 0L) { long u = VMNatives.currentTimeMillis(); }
        if (identityAddr == 0L) { long u = VMNatives.identity(0L); }
        if (arraycopyAddr == 0L) { VMNatives.arraycopy(0L, 0, 0L, 0, 0); }
        if (newNpeAddr == 0L) { long u = newNpe(); }                  // implicit-exception ctors (JIT'd checks)
        if (newAioobeAddr == 0L) { long u = newAioobe(); }
        if (newAseAddr == 0L) { long u = newAse(); }                  // ArrayStoreException (aastore mismatch)
        if (arrayStoreOkAddr == 0L) { int u = arrayStoreOk(0L, 0L); } // aastore covariant check
        if (newCceAddr == 0L) { long u = newCce(); }                  // ClassCastException (failed checkcast)
        if (castOkAddr == 0L) { int u = castOk(0L, 0L); }             // checkcast predicate
        if (newUnresolvedAddr == 0L) { newUnresolved(-1L); }          // `new` of an unresolvable class (halts)
        if (newArithAddr == 0L) { long u = newArith(); }
        if (getClassAddr == 0L) { long u = getClassOf(0L); }          // Object.getClass() intrinsic
        if (arrayCloneAddr == 0L) { long u = VMNatives.arrayClone(0L); }        // [T.clone() intrinsic
        if (newReflectArrayAddr == 0L) { long u = VMNatives.newReflectArray(0L, 0L); } // reflect/Array.newInstance0
        if (componentTypeAddr == 0L) { long u = VMNatives.componentTypeOf(0L); }       // Class.getComponentType0
        if (printStackTraceAddr == 0L) { VMNatives.printStackTrace(0L); }       // Throwable.printStackTrace0() native
        if (fileOpenAddr == 0L) { long u = VMNatives.fileOpen(0L); }            // FileInputStream.open0() native (M3 RAMFS)
        if (dnsResolveAddr == 0L) { int u = VMNatives.dnsResolve(0L); }         // java.net.InetAddress.resolve0() native (M3)
        if (vhFieldOffsetAddr == 0L) { long u = VMNatives.vhFieldOffset(0L, 0L); }      // VarHandle.fieldOffset0 native (M3)
        if (fieldModsAddr == 0L) { int u = VMNatives.fieldMods(0L, 0L); }               // Class.fieldMods0 native (reflection)
        if (fieldTypeCharAddr == 0L) { int u = VMNatives.fieldTypeChar(0L, 0L); }       // Class.fieldTypeChar0 native
        if (classAtPcAddr == 0L) { long u = VMNatives.classAtPc(0L); }                  // getCallerClass native
        if (sockSocket0Addr == 0L) { int u = VMNatives.sockSocket0(0L, 0L, 0L, 0L); }   // M3 socket natives (dead calls,
        if (sockConnect0Addr == 0L) { int u = VMNatives.sockConnect0(0L, 0L, 0L, 0L); } // never run: the writer pre-stashes
        if (sockRead0Addr == 0L) { int u = VMNatives.sockRead0(0L, 0L, 0L); }           // each address, so these only force
        if (sockWrite0Addr == 0L) { int u = VMNatives.sockWrite0(0L, 0L, 0L); }         // compilation of the helper)
        if (sockClose0Addr == 0L) { VMNatives.sockClose0(0L); }
        if (sockAvailableAddr == 0L) { int u = VMNatives.sockAvailable(0L); }
        if (fdValAddr == 0L) { int u = VMNatives.fdVal(0L); }
        if (setFdValAddr == 0L) { VMNatives.setFdVal(0L, 0L); }
        if (sockNoopAddr == 0L) { VMNatives.sockNoop(); }
        if (sockZeroAddr == 0L) { long u = VMNatives.sockZero(); }
        if (classNameAddr == 0L) { long u = VMNatives.classNameOf(0L); }        // Class.getName0() native (M4)
        if (forNameAddr == 0L) { long u = VMNatives.forName(0L); }              // Class.forName0() native (reflection M1)
        if (defineClassAddr == 0L) { long u = VMNatives.defineClass(0L, 0L, 0L, 0L); } // ClassLoader.defineClass0 (M3)
        if (classModifiersAddr == 0L) { long u = VMNatives.classModifiers(0L); } // Class.getModifiers() native (reflection M1)
        if (methodResolveAddr == 0L) { int u = VMNatives.methodResolve(0L, 0L); } // Method.methodResolve0 native (reflection M2)
        if (methodInfoAddr == 0L) { int u = VMNatives.methodInfo(0L, 0L, 0L); }   // Method.methodInfo0 native (reflection M2)
        if (constructorResolveAddr == 0L) { int u = VMNatives.constructorResolve(0L, 0L); } // Constructor.ctorResolve0 (M2)
        if (allocInstanceAddr == 0L) { long u = VMNatives.allocInstance(0L); }    // Constructor.allocInstance0 native (M2)
        if (superclassAddr == 0L) { long u = VMNatives.superclassOf(0L); }      // Class.superclass0() native (M4)
        if (currentThreadAddr == 0L) { long u = VMNatives.currentThreadObj(); } // Thread.currentThread0() native (M4)

        installSchedVectors();

        allocTaskTables();                                  // task 0 = the boot flow (SP saved on tick 1)
        VMScheduler.spawn(taskAAddr);                                  // task 1 (yield)
        VMScheduler.spawn(taskBAddr);                                  // task 2 (producer: posts sem 0)
        VMScheduler.spawn(taskCAddr);                                  // task 3 (consumer: blocks on sem 0)
        VMScheduler.spawn(taskRAddr);                                  // task 4 (UART reader: blocks on UART_SEM)

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
        if (scheduleAddr == 0L) { scheduleAddr = VMScheduler.schedule(0L); }   // dead calls: force schedule/yieldPick
        if (yieldPickAddr == 0L) { yieldPickAddr = VMScheduler.yieldPick(0L); } // compiled + stashed for the vector stubs
        installSchedVectors();
        allocTaskTables();                                  // task 0 = the WiFi boot flow
        Gic.init(Gic.PPI_CNTPNS);
        timerReload = Magic.readCNTFRQ_EL0() / 100L;        // ~10 ms tick (deadline wakes for semWaitTimeout)
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                  // IRQs on: SDIO SPI 158 + timer
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
        openClasspath(conf, flen);                          // classpath=<jar>: classes the image lacks come from it
        Uart.write(Magic.bytes("launch "));
        Uart.write(mainClass);
        Uart.putc(0x0A);
        Loader.launch(mainClass, argsLine);
        return true;
    }

    /**
     * Open the {@code classpath=<path>} jar named by the init manifest, if any. From here on any class the
     * writer-baked directory lacks is looked for in the jar ({@link VM#dirBytes}), so a program can ship as an
     * ordinary jar on the RAMFS instead of being embedded in the image.
     */
    private static void openClasspath(long conf, int flen)
    {
        long cp = Heap.allocData(256);
        int n = manifestValue(conf, flen, Magic.bytes("classpath"), cp, 250);
        if (n == 0)
        {
            return;
        }
        byte[] path = heapBytes(cp, n);
        boolean ok = JarFs.open(path);
        Uart.write(Magic.bytes("classpath "));
        Uart.write(path);
        if (ok)
        {
            Uart.write(Magic.bytes(" entries="));
            printDec(JarFs.count());
        }
        else
        {
            Uart.write(Magic.bytes(" UNREADABLE"));
        }
        Uart.putc(0x0A);
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

    /**
     * Whether a launched program gets the other three cores: {@code /etc/init}'s {@code smp=} line, default
     * ON. A program's threads then run on all four A72s instead of time-slicing core 0. {@code smp=0} is the
     * escape hatch for a program that wants the single-core scheduler back (and there must BE a manifest --
     * with none, the demo suite below drives SMP itself).
     */
    static boolean launchSmp()
    {
        if (!SMP_ENABLED)
        {
            return false;
        }
        long e = fileFind(Magic.bytes("/etc/init"));
        if (e == 0L)
        {
            return false;                                  // no manifest: the demo suite runs its own SMP phases
        }
        long conf = Magic.load64(e + 16L);
        int flen = (int) Magic.load64(e + 24L);
        long v = Heap.allocData(16);
        int n = manifestValue(conf, flen, Magic.bytes("smp"), v, 8);
        return n < 1 || (Magic.load8(v) & 0xFF) != 0x30;    // absent = on; "0" = off
    }



    // ----- M3 socket natives: stock java.net / sun.nio.ch over net.Tcp. A FileDescriptor's fd int (first
    //       field, offset 16) holds the net.Tcp connection handle. Every helper is STATIC (matching the JDK
    //       26 natives) and reached via Loader.nativeBuf with the loader arg convention (slot k = x(1+k));
    //       args come in as raw longs (refs/ints). -----

















    static void reportFault()
    {
        long rcv = Magic.readX0();                         // FIRST ops: capture the faulting blr's receiver (x0) and
        long lr = Magic.readLR();                          // return addr (x30) before anything clobbers them
        Uart.lock();                                       // and never release: this core halts at the end of the
                                                           //   report, so its trace prints whole, not interleaved
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
        Uart.write(Magic.bytes("  at elr: "));                 // the faulting instruction's own method: the
        Loader.printFrameAt(elr);                              //   image symbol table names writer-compiled
        Uart.putc(0x0A);                                       //   code, which reportMethodAt (JIT-only) cannot
        Uart.write(Magic.bytes("  at lr:  "));                 //   -- without this a fault inside the loader
        Loader.printFrameAt(lr);                               //   reads as "(no registered method)" and the
        Uart.putc(0x0A);                                       //   only clue is a raw address
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
            if (elr >= Heap.CODE_BASE && elr < Heap.CODE_LIMIT)
            {
                // An undefined instruction inside the code arena is almost always "we are executing a buffer
                // the sweep zeroed". Report it HERE: once this becomes a Java exception and unwinds, the
                // block state and swept-log history are no longer meaningful.
                VMGc.reportSweptPc(elr);
                Loader.printFrameAt(elr);
                Uart.putc(0x0A);
            }
            exc = newInternalError();
        }
        if (exc <= 0x1000L || Magic.load64(exc) == 0L)                  // exception class not loaded (TIB 0): can't
        {                                                              // throw without re-faulting inside unwind ->
            reportFaultStack(elr, sp);                                 // ... but say WHERE first: the leaf alone
            reportFault();                                             // print the raw fault + halt (never returns)
        }
        Magic.enableIrq();                                             // resumed via branch, not ERET: re-unmask IRQs
        VMUnwind.unwind(exc, elr, sp);                                          // throw at the faulting instruction (never returns)
    }

    static long throwFromFaultAddr;    // VM.throwFromFault(J)V — hardware fault -> Java exception (address trap -> NPE)
    static long lazyCompileAddr;       // Loader.lazyCompile(I)J — M8 1b compile-on-first-call trampoline target
    static long utf16GetBytesAddr;     // baked stock java/lang/StringUTF16.getBytes([BII[BI)V — M8 static-state probe target
    static long formatUnsignedIntAddr; // baked stock java/lang/Integer.formatUnsignedInt(II[BI)V — M8 object-statics probe target
    static long integerIntValueAddr;   // baked stock java/lang/Integer.intValue()I — reads a baked object's field directly
    static long integerCacheSlotAddr;  // address OF the java/lang/Integer$IntegerCache.cache static slot (M8 scalar-objects probe)
    static long integerValueOfAddr;    // baked stock java/lang/Integer.valueOf(I)Ljava/lang/Integer; — M8 bake-stubs probe target
    static long integerEqualsAddr;     // baked stock Integer.equals(Object) — invokevirtual intValue() through the arg's TIB
    static long longEqualsAddr;        // baked stock Long.equals(Object) — ditto via longValue() on a baked-only-class TIB
    static long longLongValueAddr;     // baked stock Long.longValue()J — the rooted virtual-dispatch target
    static long longCacheSlotAddr;     // address OF the java/lang/Long$LongCache.cache static slot
    static long stringValueOfBoolAddr; // baked stock String.valueOf(Z) — returns an interned baked String object
    static long stringLengthAddr;      // baked stock String.length()I
    static long stringCharAtAddr;      // baked stock String.charAt(I)C
    static long stringCoderAddr;       // baked stock String.coder()B — length()'s invokevirtual target
    static long stringIsLatin1Addr;    // baked stock String.isLatin1()Z — charAt()'s invokevirtual target
    static long integerToStringAddr;   // baked stock Integer.toString(I) — builds a real String on the metal heap
    static long longToStringAddr;      // baked stock Long.toString(J)
    static long integerToHexStringAddr;// baked stock Integer.toHexString(I)
    static long stringEqualsAddr;      // baked stock String.equals(Object) — content compare
    static long bakedTable;            // M8 endgame: baked-method LINK table {classUtf8, nameUtf8, descUtf8, code}*
    static long bakedCount;            // ... its entry count (Loader.bakedBuf scans it at lazy-compile time)
    static long vtSigTable;            // M8 unification: per-baked-class writer vtable signatures {classUtf8, slots, count, 0}*
    static long vtSigCount;            // ... its entry count (Loader.checkVtParity verifies slot parity at load)

    /** Print a java/lang/String (baked or metal-heap) via the baked stock length()/charAt(). */
    private static void printJavaString(long str)
    {
        long[] s = new long[8];
        long b = Magic.addrOf(s) + 24L;                    // array elements (header 16 + length 8)
        Magic.store64(b, str);
        long n = Magic.callN(stringLengthAddr, b);
        for (int i = 0; i < (int) n; i++)
        {
            Magic.store64(b, str);
            Magic.store64(b + 8L, i);
            Uart.putc((int) Magic.callN(stringCharAtAddr, b));
        }
    }

    static long bakeStubTable;         // M8 object links: {classUtf8, nameUtf8, descUtf8, memo}* per bake stub
    static long bakeStubCount;
    static long primArrayTibs;         // M8 array unification: 8-long table, baked prim-array TIB per atype (4..11)
    static long refArrayTibs;          // M8 ref arrays: {elementType, tib} pair table (baked single-dim ref arrays)
    static long refArrayTibCount;
    static long ifaceIdNext;           // O(1) interface checks: next unassigned interface ID (writer used 1..N)

    /** M8 object links: a writer-baked RESOLVE stub was called — the method the host writer could
     *  not compile is genuinely needed now. Resolve it through the on-metal loader (demand-load the
     *  class against the SHARED statics/Types/vtables/itables, find its callable buffer), memoize
     *  in the stub table, and return it for the stub's tail-branch. The lazy fringe of the baked
     *  world, closed by the unification arc. */
    static long bakeResolve(int idx)
    {
        long e = bakeStubTable + (long) idx * 32L;
        long memo = Magic.load64(e + 24L);
        if (memo != 0L)
        {
            return memo;
        }
        loaderLock();                                   // demand-loads a class: one compiler at a time
        long buf = Loader.resolveBakeStub(Magic.load64(e), Magic.load64(e + 8L), Magic.load64(e + 16L));
        Magic.store64(e + 24L, buf);
        loaderUnlock();
        return buf;
    }
    static int  faultDepth;            // 1 while a hardware fault is being turned into a Java exception + unwound
    static long fault0Esr, fault0Elr, fault0Far;   // the FIRST fault's syndrome, kept for the nested-fault report

    /** A second CPU fault fired while {@link #throwFromFault} was still turning the FIRST one into a Java
     *  exception (the unwind itself re-faulted -- a bad frame-table entry, a wild handler PC, an unmapped
     *  address). Continuing would loop the fault vector forever and the board silently resets. Report BOTH
     *  faults (the original is the real bug; the nested one shows where the unwind broke) and halt. */
    static void reportNestedFault(long esr, long elr, long far)
    {
        Uart.lock();                                       // halts at the end: hold the console for the whole report
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

    /**
     * Name every frame on the faulting stack, the same walk {@link #denylistTrap} uses (recover the caller's
     * return address from the frame's saved LR, step by the writer-recorded frame size). A fault whose
     * exception cannot be constructed -- the batch never loaded {@code NullPointerException}, so there is
     * nothing to throw -- would otherwise report only the leaf method and a raw address, which is how a wild
     * pointer inside a two-line helper like {@code Loader.u1} stays unexplained.
     */
    static void reportFaultStack(long pc, long sp)
    {
        if (pc >= Heap.CODE_BASE && pc < Heap.CODE_LIMIT)
        {
            VMGc.reportSweptPc(pc);                        // did the collector free the buffer we are in?
        }
        Uart.write(Magic.bytes("\n  loader was compiling: "));
        Loader.printCurrentClass();
        long cpc = pc;
        long csp = sp;
        int depth = 0;
        while (depth < 12 && cpc > 0x1000L)
        {
            Uart.write(Magic.bytes("\n    at "));
            Loader.printFrameAt(cpc);
            long fs = frameSizeAt(cpc);
            if (fs == 0L)
            {
                Uart.write(Magic.bytes("  <frameless: caller unknown>"));
                break;                                     // a leaf keeps its return address in x30, which the
            }                                              //   sync vector's own bl has already overwritten
            cpc = Magic.load64(csp) - 4L;                  // caller's return address = the call site
            csp += fs;
            depth += 1;
        }
        Uart.putc(0x0A);
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
                if (typeAssignable(elemType, elemTarget))
                {
                    return true;
                }
                // Every reference element widens to Object, so S[] is always an Object[]. The walk above
                // cannot show that when S is an INTERFACE: an interface Type is a chain dead end (depth -1,
                // no display, no superclass link), so it never reaches Object. Map.Entry[] -> Object[] is
                // the live case -- AbstractCollection.toArray(T[]) casts exactly that, which is how
                // Map.copyOf(...) (java.util.jar.Attributes$Name's initializer) reached a checkCast spin.
                return elemTarget == Loader.objectTypeAddr();
            }
            // a primitive element on either side is invariant: only the exact-match below can succeed
        }
        // O(1) display fast path: class/array targets carry a depth + display, so the whole chain
        // question is one bounds check + one compare (S is a T iff S.display[T.depth] == T).
        // Interface targets carry depth -1 and NO display -- their answers live in itable dirs,
        // handled by the walk below; a Type without a display (lambda, self-build) also falls back.
        if (targetType != 0L)
        {
            long tDisp = Magic.load64(targetType + ObjectModel.TYPE_DISPLAY_OFFSET);
            long disp = Magic.load64(type + ObjectModel.TYPE_DISPLAY_OFFSET);
            if (tDisp != 0L && disp != 0L)
            {
                long tDepth = Magic.load64(targetType + ObjectModel.TYPE_DEPTH_OFFSET);
                if (Magic.load64(type + ObjectModel.TYPE_DEPTH_OFFSET) < tDepth)
                {
                    return false;
                }
                return Magic.load64(disp + tDepth * 8L) == targetType;
            }
            // O(1) interface fast path: a NUMBERED interface target (depth -1, ID 1..127) against a
            // receiver with a COMPUTED doesImplement bitmap is one bit test. Unnumbered interfaces
            // and marker-less receivers fall through to the itable-dir walk below.
            if (tDisp == 0L && Magic.load64(targetType + ObjectModel.TYPE_DEPTH_OFFSET) == -1L)
            {
                long id = Magic.load64(targetType + ObjectModel.TYPE_IMPLEMENTS_OFFSET);
                long b0 = Magic.load64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET);
                if (id > 0L && id < 128L && (b0 & 1L) != 0L)
                {
                    if (id < 64L)
                    {
                        return ((b0 >>> (int) id) & 1L) != 0L;
                    }
                    long b1 = Magic.load64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L);
                    return ((b1 >>> (int) (id - 64L)) & 1L) != 0L;
                }
            }
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
            if (Magic.load64(ref) <= ObjectModel.MAX_RAW_ARRAY_TIB && isArrayType(targetType))
            {
                // A RAW array (writer/boot alloc: element size in @0, no Type node) cast to an array class:
                // there is no Type to walk, so trust the verifier-proved cast — the mirror of instanceOf's
                // conservative 0 for raw arrays. Reached when a baked method's array result (e.g. the value
                // of Integer.toString's String) flows into loader-compiled code that casts it.
                return ref;
            }
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

    /**
     * Drop the JIT unwind entries whose code lies in {@code [lo,hi)} — a method the collector just swept.
     * Their entries are keyed by machine-address range, so leaving them would make them answer for whatever
     * is compiled at that address next: a stack walk would find a frame size or a catch handler belonging to
     * a method that no longer exists. The batch rewind avoided this by dropping everything above the code
     * mark at once; reclaiming individual methods needs the same hygiene per range.
     */
    static void dropJitTablesIn(long lo, long hi)
    {
        jitFrameCount = compactTableOutside(jitFrameTable, jitFrameCount, 24L, lo, hi);
        jitLocalCount = compactTableOutside(jitLocalTable, jitLocalCount, 24L, lo, hi);
        jitHandlerCount = compactTableOutside(jitHandlerTable, jitHandlerCount, 32L, lo, hi);
    }

    /** Keep only entries whose code RANGE falls outside {@code [lo,hi)}; the kept count. */
    private static long compactTableOutside(long table, long count, long entryBytes, long lo, long hi)
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
            long start = Magic.load64(src);
            long end = Magic.load64(src + 8L);         // every one of these tables is {codeStart, codeEnd, ...}
            // Drop an entry whose RANGE OVERLAPS the swept block, not merely one that STARTS inside it. Filtering
            // on start alone let an entry whose end reached into freed code survive; it then answered for pcs
            // belonging to whatever was compiled there next, handing the unwinder a wrong frame size. The bound
            // is exclusive at both ends, so an entry that merely ABUTS the freed block (end == lo) is kept.
            if (end <= lo || start >= hi)
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
    static long stringBytes, stringLen;             // java/lang/String (result of string concat, M-B slice 1)
    static long intOpBytes, intOpLen;               // demo/IntOp (a SAM-with-arg functional interface, 1d)
    static long integerBytes, integerLen;           // java/lang/Integer — a real, unmodified java.base class
    static long stringBuilderBytes, stringBuilderLen; // java/lang/StringBuilder (real-shaped)
    // Mini exception hierarchy + the implicit-exception demo (null-deref NPE / array-bounds AIOOBE).
    static long throwableBytes, throwableLen;       // java/lang/Throwable
    static long exceptionBytes, exceptionLen;       // java/lang/Exception
    static long runtimeExcBytes, runtimeExcLen;     // java/lang/RuntimeException
    static long npeBytes, npeLen;                   // java/lang/NullPointerException
    static long ioobeBytes, ioobeLen;               // java/lang/IndexOutOfBoundsException
    static long aioobeBytes, aioobeLen;             // java/lang/ArrayIndexOutOfBoundsException
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
    static long objectBytes, objectLen;             // java/lang/Object (root: hashCode/equals slots for HashMap)
    static long hashMapBytes, hashMapLen;           // java/util/HashMap
    static long longBytes, longLen;                 // java/lang/Long — a real, unmodified java.base class (probe)
    // Dep/native surface for real Integer.parseInt: mini Character.digit + the NumberFormatException hierarchy.
    static long characterBytes, characterLen;       // java/lang/Character (digit)
    static long illegalArgBytes, illegalArgLen;     // java/lang/IllegalArgumentException
    static long numberFmtBytes, numberFmtLen;       // java/lang/NumberFormatException
    // Real Integer.toString surface: mini StringLatin1 + DecimalDigits + the demo (String gained byte[]+coder).
    static long stringLatin1Bytes, stringLatin1Len; // java/lang/StringLatin1
    static long decimalDigitsBytes, decimalDigitsLen; // jdk/internal/util/DecimalDigits
    static long arithExcBytes, arithExcLen;         // java/lang/ArithmeticException (Math.addExact overflow)
    static long objectsBytes, objectsLen;           // java/util/Objects — a real, unmodified java.base class
    static long arraysBytes, arraysLen;             // java/util/Arrays — a real, unmodified java.base class
    static long arraysSupportBytes, arraysSupportLen; // jdk/internal/util/ArraysSupport (mini mismatch)
    static long numberBytes, numberLen;             // java/lang/Number (Integer's super, for the vtable chain)
    static long integerCacheBytes, integerCacheLen; // java/lang/Integer$IntegerCache (statics read 0, clinit skipped)
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
    static long newCceAddr;            // VM.newCce()J    — a java/lang/ClassCastException (failed checkcast)
    static long castOkAddr;            // VM.castOk(JJ)I  — checkcast predicate (1 = holds, 0 = throw)
    static long newUnresolvedAddr;     // VM.newUnresolved(J)V — an executed `new` of an unresolvable class
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


    /**
     * The program proper — a framed method (so operand values can spill across
     * calls). Prints the banner, then exercises the object model: allocate a
     * heap object, mutate its field, and print the result.
     */
    /** Print {@code v} (0..9999) in decimal, no leading zeros. Uses only / and * (no irem). */
    public static void printDec(int v)
    {
        // Any number of digits. The previous version hardcoded thousands/hundreds/tens/ones, so a value of
        // 10000 or more printed its leading part as ONE character, since the leading "digit" was the whole
        // thousands count: 16384 came out as "@384" ('@' is '0'+16), 10150 as ":150", 11115 as ";115".
        // Every counter that outgrew four digits misreported silently. Two Pi runs showed exactly that in
        // the large-region reuse counter and were dismissed as UART corruption -- on the strength of this
        // same fix, which had been made on one branch and not merged into the other.
        if (v < 0)
        {
            Uart.putc(0x2D);
            v = -v;
        }
        int div = 1;
        while (v / div >= 10)
        {
            div = div * 10;
        }
        while (div > 0)
        {
            Uart.putc(0x30 + (v / div) % 10);
            div = div / 10;
        }
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

    // M8 full bootstrap (first probe): does the writer bake a STOCK java.base class INTO the boot image
    // (compiled, callable directly) rather than only demand-loading it as a raw blob? When on, VM.run reaches
    // bootstrapProbe -> java/lang/Math.max, so ImageBuilder's reachable-closure BFS should compile Math.max
    // into the image (resolved via the ClassRegistry, which already holds Math). Default OFF -> the call is a
    // compile-time dead branch, bootstrapProbe is unreachable, Math is not compile-reached, image unchanged.
    static final boolean BOOTSTRAP_PROBE = true;

    /** First step toward the full bootstrap (loader-uses-java.base): call a pure stock java.base method
     *  (java/lang/Math.max) that the WRITER compiled into the image, and print the result -- proving stock
     *  java.base can be baked into the boot image and called directly (not demand-loaded). */
    static void bootstrapProbe()
    {
        Uart.write(Magic.bytes("bootstrap (stock java.base baked into the image, called directly):\n"));
        Uart.write(Magic.bytes("  Math.max(0x4D,0x21)="));
        Uart.putc(Math.max(0x4D, 0x21));                  // 'M'
        Uart.write(Magic.bytes("  Math.min(0x5A,0x42)="));
        Uart.putc(Math.min(0x5A, 0x42));                  // 'B'
        Uart.write(Magic.bytes("  Math.abs(-0x21)="));
        Uart.putc(Math.abs(-0x21));                       // '!'
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("  Integer.bitCount(0xFF)="));
        printDec(Integer.bitCount(0xFF));                 // 8
        Uart.write(Magic.bytes(" numberOfLeadingZeros(1)="));
        printDec(Integer.numberOfLeadingZeros(1));        // 31
        Uart.write(Magic.bytes(" reverse(1)>>>24="));
        printDec(Integer.reverse(1) >>> 24);              // 0x80 = 128
        Uart.putc(0x0A);

        // Static state (seed-JVM <clinit> snapshot): stock StringUTF16.getBytes starts its copy loop at
        // srcBegin + (1 >> LO_BYTE_SHIFT) -- and LO_BYTE_SHIFT is set only by StringUTF16.<clinit>, which
        // the writer can't run (it calls the native isBigEndian()). The writer instead snapshots the seed
        // JVM's value (8, little-endian) into the image statics. Un-snapshotted the slot reads 0, the loop
        // starts one byte high, and it copies the (zero) high bytes -- so four correct characters here
        // prove the baked method read the snapshotted static. Called via the writer-stashed address
        // (Magic.callN) because javac can't name the package-private java/lang/StringUTF16.
        Uart.write(Magic.bytes("  StringUTF16.getBytes(\"JOE!\" as UTF-16)="));
        byte[] u16 = new byte[8];                         // "JOE!", UTF-16LE, written by hand
        u16[0] = (byte) 'J';
        u16[2] = (byte) 'O';
        u16[4] = (byte) 'E';
        u16[6] = (byte) '!';
        byte[] ch = new byte[4];
        long[] slots = new long[8];
        long argBase = Magic.addrOf(slots) + 24L;         // array elements (header 16 + length 8)
        Magic.store64(argBase,       Magic.addrOf(u16));  // value
        Magic.store64(argBase + 8L,  0L);                 // srcBegin
        Magic.store64(argBase + 16L, 4L);                 // srcEnd
        Magic.store64(argBase + 24L, Magic.addrOf(ch));   // dst
        Magic.store64(argBase + 32L, 0L);                 // dstBegin
        Magic.callN(utf16GetBytesAddr, argBase);
        Uart.write(ch);
        boolean snapOk = ch[0] == 'J' && ch[1] == 'O' && ch[2] == 'E' && ch[3] == '!';
        Uart.write(snapOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (static not snapshotted?)\n"));

        // OBJECT statics (deep snapshot): stock Integer.formatUnsignedInt indexes Integer.digits --
        // a byte[] static that exists only after <clinit> builds it. The writer bakes the seed JVM's
        // array into the image as an array object and points the slot at it. Un-baked the slot is 0
        // (null), and since this VM emits no null checks the baload reads low RAM instead of the
        // digit table -- so four correct hex digits (with letters, i.e. entries past '9') prove the
        // baked ARRAY was read, not just a primitive slot.
        Uart.write(Magic.bytes("  Integer.formatUnsignedInt(0xCAFE)="));
        byte[] hex = new byte[4];
        Magic.store64(argBase,       0xCAFEL);            // val
        Magic.store64(argBase + 8L,  4L);                 // shift (hex)
        Magic.store64(argBase + 16L, Magic.addrOf(hex));  // buf
        Magic.store64(argBase + 24L, 4L);                 // len
        Magic.callN(formatUnsignedIntAddr, argBase);
        Uart.write(hex);
        boolean objOk = hex[0] == 'c' && hex[1] == 'a' && hex[2] == 'f' && hex[3] == 'e';
        Uart.write(objOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (object static not baked?)\n"));

        // SCALAR objects (deep object graph): Integer$IntegerCache.cache is a 256-entry Integer[]
        // built only by <clinit> (which reads system properties -- hopeless at build time). The
        // writer force-bakes it (BAKE_STATICS): the reference ARRAY plus 256 Integer OBJECTS, each
        // with its value field at the model's slot, all pointer-linked in the image. The probe
        // walks it raw -- slot -> array -> element 170 (the Integer for 170-128 = 42) -- and hands
        // the object to stock Integer.intValue(), a baked getfield accessor called directly. '*'
        // proves array elements point at real baked objects whose fields hold the seed's values.
        Uart.write(Magic.bytes("  IntegerCache.cache[170].intValue()="));
        long cacheArr = Magic.load64(integerCacheSlotAddr);   // the static slot -> baked Integer[]
        long obj170 = cacheArr == 0 ? 0 : Magic.load64(cacheArr + 24L + 170L * 8L);  // elements at +24
        Magic.store64(argBase, obj170);                   // receiver
        long iv = obj170 == 0 ? 0 : Magic.callN(integerIntValueAddr, argBase);
        Uart.putc((int) iv);                              // '*'
        boolean scalarOk = cacheArr != 0 && obj170 != 0 && iv == 42;
        Uart.write(scalarOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (scalar objects not baked?)\n"));

        // valueOf itself (bake stubs): baking stock Integer.valueOf drags in `new Integer`, and with
        // it every Integer virtual -- whose closures hit natives and opcodes the host writer can't
        // compile. Those bake as trap stubs now (never called on this path). valueOf(42) must return
        // the SAME baked object probe 3 indexed out of the cache; valueOf(200) takes the new-path and
        // allocates a fresh Integer on the METAL heap -- whose TIB must equal the baked object's TIB,
        // i.e. image-baked and runtime-allocated objects share one real class.
        Uart.write(Magic.bytes("  Integer.valueOf(42)==cache[170], valueOf(200).intValue()="));
        Magic.store64(argBase, 42L);
        long va = Magic.callN(integerValueOfAddr, argBase);
        Magic.store64(argBase, 200L);
        long vc = Magic.callN(integerValueOfAddr, argBase);
        Magic.store64(argBase, vc);
        long vv = Magic.callN(integerIntValueAddr, argBase);
        printDec((int) vv);                               // 200
        boolean vOk = va != 0 && va == obj170 && vv == 200
                   && Magic.load64(va) != 0 && Magic.load64(va) == Magic.load64(vc);
        Uart.write(vOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (valueOf not baked?)\n"));

        // REAL VTABLES: stock Integer.equals runs `instanceof Integer` (a Type walk over the baked
        // object's TIB[0] chain), checkcast, and a genuine invokevirtual intValue() THROUGH the
        // argument's TIB -- virtual dispatch on an image-baked object. And the Long half: nothing
        // in the compiled closure ever `new`s a Long, so the baked LongCache Longs carry a TIB only
        // because every baked scalar's class now joins the TIB layout (stub-safe vtable pull);
        // Long.equals dispatches longValue() through it.
        Uart.write(Magic.bytes("  Integer.equals(42,42|43)="));
        Magic.store64(argBase, obj170);                   // receiver: baked Integer 42
        Magic.store64(argBase + 8L, va);                  // arg: valueOf(42) -> the same baked object
        long eqSame = Magic.callN(integerEqualsAddr, argBase);
        long obj171 = cacheArr == 0 ? 0 : Magic.load64(cacheArr + 24L + 171L * 8L);
        Magic.store64(argBase + 8L, obj171);              // arg: baked Integer 43
        long eqDiff = Magic.callN(integerEqualsAddr, argBase);
        printDec((int) eqSame);
        Uart.putc(',');
        printDec((int) eqDiff);
        Uart.write(Magic.bytes("  Long.equals(42L,42L)="));
        long lCacheArr = Magic.load64(longCacheSlotAddr); // the static slot -> baked Long[]
        long lobj170 = lCacheArr == 0 ? 0 : Magic.load64(lCacheArr + 24L + 170L * 8L);
        Magic.store64(argBase, lobj170);                  // receiver: baked Long 42
        Magic.store64(argBase + 8L, lobj170);             // arg: the same baked Long
        long leq = lobj170 == 0 ? 0 : Magic.callN(longEqualsAddr, argBase);
        printDec((int) leq);
        boolean vtOk = eqSame == 1 && eqDiff == 0 && lobj170 != 0 && leq == 1;
        Uart.write(vtOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (baked vtables?)\n"));

        // STRINGS: stock String.valueOf(boolean) returns the interned "true"/"false" literal. An
        // `ldc` String in a bake-domain method now lowers to a real baked java/lang/String OBJECT
        // (stock layout -- value byte[] + coder, itself a baked graph), not the vm-side raw byte[].
        // length() and charAt() (virtual targets coder()/isLatin1() rooted; COMPACT_STRINGS
        // snapshotted from the auto-deferred String.<clinit>) read the chars back on the metal.
        Uart.write(Magic.bytes("  String.valueOf(true)="));
        Magic.store64(argBase, 1L);
        long strTrue = Magic.callN(stringValueOfBoolAddr, argBase);
        Magic.store64(argBase, strTrue);
        long strLen = Magic.callN(stringLengthAddr, argBase);
        for (int sp = 0; sp < (int) strLen; sp++)
        {
            Magic.store64(argBase, strTrue);
            Magic.store64(argBase + 8L, sp);
            Uart.putc((int) Magic.callN(stringCharAtAddr, argBase));
        }
        Magic.store64(argBase, 0L);
        long strFalse = Magic.callN(stringValueOfBoolAddr, argBase);
        boolean strOk = strTrue != 0 && strLen == 4 && strFalse != 0 && strFalse != strTrue;
        Uart.write(strOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (baked strings?)\n"));

        // WIDENED bake: Integer/Long.toString build REAL Strings on the metal heap -- the guest
        // DecimalDigits overlay computes the digits (the registry resolves the metal-friendly
        // overlay, not the Unsafe-table stock), stock newStringWithLatin1Bytes wraps them through
        // the private String(byte[],byte) constructor -- and String.equals compares content: two
        // DISTINCT heap Strings from the same value are equal, different values are not.
        Uart.write(Magic.bytes("  Integer.toString(-2026)="));
        Magic.store64(argBase, -2026L);
        long tsNeg = Magic.callN(integerToStringAddr, argBase);
        printJavaString(tsNeg);
        Uart.write(Magic.bytes(" Long.toString(1<<40)="));
        Magic.store64(argBase, 1L << 40);
        long tsLong = Magic.callN(longToStringAddr, argBase);
        printJavaString(tsLong);
        Uart.write(Magic.bytes(" toHex(0xBEEF)="));
        Magic.store64(argBase, 0xBEEFL);
        long tsHex = Magic.callN(integerToHexStringAddr, argBase);
        printJavaString(tsHex);
        Magic.store64(argBase, 42L);
        long s42a = Magic.callN(integerToStringAddr, argBase);
        Magic.store64(argBase, 42L);
        long s42b = Magic.callN(integerToStringAddr, argBase);
        Magic.store64(argBase, s42a);
        Magic.store64(argBase + 8L, s42b);
        long seq = Magic.callN(stringEqualsAddr, argBase);
        Magic.store64(argBase, s42a);
        Magic.store64(argBase + 8L, tsHex);
        long sne = Magic.callN(stringEqualsAddr, argBase);
        Uart.write(Magic.bytes(" equals="));
        printDec((int) seq);
        Uart.putc(',');
        printDec((int) sne);
        boolean widenOk = s42a != s42b && seq == 1 && sne == 0;
        Uart.write(widenOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (widened bake?)\n"));

        // ITABLES: interface instanceof. A boxed Integer held as Object must satisfy `instanceof
        // Comparable` -- answered from Integer's itable DIRECTORY (interface targets match dir
        // keys, not the super chain), which now includes instanceof-only interfaces. A plain
        // Object must not. Cross-world: java/lang/Comparable joins the adoption table, so the
        // loader's Comparable adopts the writer's Type node at boot (the typeadopt line) and the
        // same discrimination holds for linked code on loader receivers.
        Uart.write(Magic.bytes("  Integer instanceof Comparable, Object="));
        Object cmpProbe = Integer.valueOf(7);
        boolean isCmp = cmpProbe instanceof Comparable;
        Object plain = new Object();
        boolean notCmp = plain instanceof Comparable;
        printDec(isCmp ? 1 : 0);
        Uart.putc(0x2C);
        printDec(notCmp ? 1 : 0);
        boolean ifOk = isCmp && !notCmp;
        Uart.write(ifOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (interface itables?)\n"));

        // INVOKEINTERFACE (writer world): dispatch compareTo on the boxed 7 through Integer's
        // itable -- the inline directory search keys on the (shared) Comparable Type and indexes
        // the per-interface slot, landing in the rooted compareTo bridge (checkcast + invokevirtual
        // to the typed compareTo). This exercises the itable CONTENT that the findImpl chain fix
        // made buildable. Cross-world linking of invokeinterface methods stays excluded until the
        // loader adopts per-interface itable slots (it currently indexes by a global method index).
        Uart.write(Magic.bytes("  Comparable.compareTo(7:9,7:7)="));
        Comparable cmpI = (Comparable) cmpProbe;
        int c79 = (int) cmpI.compareTo(Integer.valueOf(9));
        int c77 = (int) cmpI.compareTo(Integer.valueOf(7));
        Uart.putc(c79 < 0 ? 0x3C : c79 > 0 ? 0x3E : 0x3D);   // '<' / '>' / '=' (printDec is unsigned)
        Uart.putc(0x2C);
        printDec(c77);
        boolean iiOk = c79 < 0 && c77 == 0;
        Uart.write(iiOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (writer itable dispatch?)\n"));

        // 12) M8 array-Type unification: writer-compiled newarray now TAGS the allocation with the
        // baked canonical primitive-array TIB, and instanceof against an array class resolves the
        // baked array Type -- so array type-checks discriminate in the writer world (raw arrays used
        // to answer false for everything). The same baked nodes are ADOPTED by the loader
        // (VM.primArrayTibs), making byte[] ONE class across both worlds.
        Uart.write(Magic.bytes("  new byte[2] instanceof byte[],int[],Object="));
        Object ba = new byte[2];
        Object ia = new int[2];
        boolean abOk = ba instanceof byte[];
        boolean aiNo = ba instanceof int[];
        boolean aoOk = ba instanceof Object;
        boolean aiOk = ia instanceof int[];
        printDec(abOk ? 1 : 0);
        Uart.putc(0x2C);
        printDec(aiNo ? 1 : 0);
        Uart.putc(0x2C);
        printDec(aoOk ? 1 : 0);
        boolean arrOk = abOk && !aiNo && aoOk && aiOk;
        Uart.write(arrOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (array Types not baked/tagged?)\n"));

        // 13) M8 ref-array unification: anewarray tags with the baked ref-array TIB for the element
        // class, and "[L<cls>;" type-check targets resolve baked ref-array Types. The covariance
        // walk runs on the element Types (Integer[] IS a Number[], is NOT a Long[]). The same
        // {elementType, tib} table is ADOPTED by the loader -- Integer[] is ONE class both worlds.
        Uart.write(Magic.bytes("  new Integer[2] instanceof Integer[],Number[],Long[]="));
        Object ra = new Integer[2];
        boolean r1 = ra instanceof Integer[];
        boolean r2 = ra instanceof Number[];             // covariant up
        boolean r3 = ra instanceof Long[];               // sibling: false
        boolean r4 = ra instanceof Object;
        printDec(r1 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(r2 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(r3 ? 1 : 0);
        boolean refArrOk = r1 && r2 && !r3 && r4;
        Uart.write(refArrOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (ref-array Types not baked/tagged?)\n"));

        // 14) Nested arrays: "[[..." descs chain-collect down to their base element, so int[][]'s
        // Type carries the canonical "[I" element node and Integer[][]'s carries "[LInteger;" --
        // the covariance walk then discriminates nested arrays and covaries on the element arrays
        // (Integer[][] IS a Number[][] because Integer[] IS a Number[]). new int[2][] lowers to
        // anewarray "[I" (single-dim create of the outer array; multianewarray stays unsupported).
        Uart.write(Magic.bytes("  new int[2][] instanceof int[][],long[][]; Integer[][] as Number[][]="));
        Object na = new int[2][];
        Object nb = new Integer[2][];
        boolean m1 = na instanceof int[][];
        boolean m2 = na instanceof long[][];             // sibling: false
        boolean m3 = nb instanceof Number[][];           // nested covariance
        boolean m4 = na instanceof Object;
        printDec(m1 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(m2 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(m3 ? 1 : 0);
        boolean nestOk = m1 && !m2 && m3 && m4;
        Uart.write(nestOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (nested array Types not baked/tagged?)\n"));

        // 15) O(1) type checks: Types now carry {depth, display}; instanceof a CLASS target is one
        // bounds check + one compare (S.display[T.depth] == T) instead of the linear super walk.
        // Integer sits at depth 2 (Object -> Number -> Integer), so these hits read display[1] and
        // display[2]; the Long-as-Integer miss is the same-depth sibling compare. Interfaces keep
        // the itable-dir walk (probe 11 covers that path).
        Uart.write(Magic.bytes("  Integer(5) instanceof Number,Integer; Long(7) as Integer="));
        Object dp = Integer.valueOf(5);
        Object dq = Long.valueOf(7);
        boolean d1 = dp instanceof Number;
        boolean d2 = dp instanceof Integer;
        boolean d3 = dq instanceof Integer;              // sibling at the same depth: false
        printDec(d1 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(d2 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(d3 ? 1 : 0);
        boolean dispOk = d1 && d2 && !d3;
        Uart.write(dispOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (display broken?)\n"));

        // 16) O(1) interface checks: interface Types carry a global ID and class/array Types a
        // doesImplement bitmap -- instanceof a numbered interface is one bit test (the itable-dir
        // walk remains only for unnumbered targets). Runnable gets a Type+ID by being named here;
        // Integer does not implement it (bit clear), and an int[]'s bitmap is empty-but-computed.
        Uart.write(Magic.bytes("  Integer instanceof Comparable,Runnable; int[] as Comparable="));
        Object ep = Integer.valueOf(6);
        Object ea = new int[1];
        boolean e1 = ep instanceof Comparable;
        boolean e2 = ep instanceof Runnable;
        boolean e3 = ea instanceof Comparable;
        printDec(e1 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(e2 ? 1 : 0);
        Uart.putc(0x2C);
        printDec(e3 ? 1 : 0);
        boolean bmOk = e1 && !e2 && !e3;
        Uart.write(bmOk ? Magic.bytes("  PASS\n") : Magic.bytes("  FAIL (doesImplement bitmap broken?)\n"));
    }

    static void run()
    {
        Uart.write(Magic.bytes("hello from joe-ng\n"));     // putc turns \n into \r\n
        Uart.write(Magic.bytes("core "));                 // the clock we calibrated the baud to
        printDec(Uart.coreHz / 1000000);                  // MHz (0 = mailbox gave no answer)
        Uart.write(Magic.bytes("MHz\n"));

        // Enable the identity-mapped MMU now -- after the mailbox (the one DMA path) has run with the MMU
        // off, and before anything that needs cacheable/coherent RAM (SMP + the HW spinlock). RAM becomes
        // Normal cacheable, MMIO stays Device; every secondary core enables it too (see secondaryMain).
        VMScheduler.buildPageTables();
        VMScheduler.enableMmuThisCore();
        Uart.write(Magic.bytes("mmu on\n"));

        if (BOOTSTRAP_PROBE)                              // M8 full-bootstrap probe (off by default)
        {
            bootstrapProbe();
        }

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

        // SMP: release cores 1-3 and put them on the SHARED run queue before the program starts, so a
        // launched main()'s threads are scheduled across all four A72s rather than time-slicing core 0.
        // (Ahead of launchInit, unlike the demo suite's own SMP phases below, which the launch never reaches.)
        if (launchSmp())
        {
            VMScheduler.bringUpSecondaries();
            VMScheduler.startSmpScheduling();
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
        smpDemo = 1;                                       // ... and runs the two set pieces below before scheduling
        VMScheduler.bringUpSecondaries();
        // Per-core scheduling: all four cores pull jobs from a shared run queue, coordinated by a real
        // hardware spinlock (LDAXR/STLXR, working now that the MMU maps RAM cacheable/coherent). Each
        // each digit is one core taking one job under the hardware spinlock (LDAXR/STLXR, working now
        // that RAM is cacheable/coherent). The lock guarantees no job is taken twice; the per-core tally
        // printed after shows the 24 jobs were shared across the four A72s.
        Uart.write(Magic.bytes("smp jobs (digit = core that ran it): "));
        Magic.store64(CORE_FLAGS + 0L, 1L);                // GO
        Magic.dsb();
        VMScheduler.smpWork(0);                                        // the primary is core 0
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
        VMScheduler.pcSetup();
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
        // semaphore 0, then VMScheduler.sleep(40ms) (SLEEPING); taskC (consumer) blocks on semaphore 0 (BLOCKED)
        // and prints 'C' only when B posts it -- so every 'B' is followed by a 'C'. The boot flow is
        // task 0, printing '.' and yield()ing. The ~10 ms timer also preempts on real HW. Bounded,
        // then re-mask so the self-build fixpoint below runs undisturbed.
        Uart.write(Magic.bytes("sched (.=main A=yield B=post->C blocked): "));
        startScheduler();

        long t0 = Magic.readCNTPCT_EL0();
        while (Magic.readCNTPCT_EL0() < t0 + Magic.readCNTFRQ_EL0() / 4L)   // ~250 ms
        {
            Uart.putc(0x2E);                   // '.' from the boot flow (task 0)
            VMScheduler.schedPause();
            VMScheduler.taskYield();                           // cooperatively hand the CPU to the next ready task
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
        resetTaskTable();                                  // fresh scheduler table: just task 0 (the boot flow)
        Loader.launch(Magic.bytes("demo/DiningPhilosophers"), Magic.bytes(""));                               // JIT + spawn the philosopher tasks (IRQs masked)
        Magic.writeCNTP_TVAL_EL0(timerReload);
        Magic.writeCNTP_CTL_EL0(1);
        Magic.enableIrq();                                 // preemption starts; the philosopher tasks run now
        long d0 = Magic.readCNTPCT_EL0();
        while (Magic.readCNTPCT_EL0() < d0 + Magic.readCNTFRQ_EL0() * 2L)   // ~2 s window
        {
            VMScheduler.schedPause();
            VMScheduler.taskYield();                                   // let the philosophers run; we're task 0
        }
        stopTimerTick();
        Uart.putc(0x0A);

        prioDemo();
        Loader.launch(Magic.bytes("demo/PipDemo"), Magic.bytes(""));
        smpThreadsDemo();

        // Philosophers (the one demo with persistent scheduler tasks on the heap) is done; from here on it is
        // safe to reclaim the demand-load heap between batches so it stays within the A64 bl reach.
        Loader.armHeapReclaim();

        // M-B slice 1: invokedynamic string concat. demo/ConcatDemo uses "a"+b, which javac lowers to
        // invokedynamic StringConcatFactory.makeConcatWithConstants. The metal JIT intrinsifies it into a
        // byte[] build wrapped in a mini java/lang/String (demand-loaded from classDir), then prints it.
        Uart.write(Magic.bytes("invokedynamic string concat (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/ConcatDemo"), Magic.bytes(""));

        // M-B slice 1c: invokedynamic lambdas. demo/LambdaDemo's () -> ... sites lower to
        // invokedynamic LambdaMetafactory.metafactory; the metal JIT synthesises a lambda class per site
        // (captured fields + an itable thunk into the lambda body), so r.run() dispatches into the body.
        Uart.write(Magic.bytes("invokedynamic lambdas (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/LambdaDemo"), Magic.bytes(""));

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
        Loader.launch(Magic.bytes("demo/FloatDemo"), Magic.bytes(""));

        // Provided java.base natives: a demand-loaded class calls real java.lang native methods (no
        // bytecode) that the loader wires to VM helpers.
        Uart.write(Magic.bytes("java.base natives (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/NativeDemo"), Magic.bytes(""));

        // Real-shaped String + StringBuilder: build a string with an append-chain, then call String
        // methods on it (length/charAt/equals/hashCode). String literals are now real String objects.
        Uart.write(Magic.bytes("String + StringBuilder (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/StrDemo"), Magic.bytes(""));

        // Implicit (JVM-synthesised) exceptions: the JIT emits null/bounds checks that throw a real mini
        // exception object; catch clauses catch it (main-local and via cross-method unwind).
        Uart.write(Magic.bytes("implicit exceptions (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/ExcDemo"), Magic.bytes(""));

        // Mini collections: a real-shaped java/util/ArrayList (Object[] + grow via arraycopy).
        Uart.write(Magic.bytes("java/util/ArrayList (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/ListDemo"), Magic.bytes(""));

        // java/util/HashMap: String keys hashed/compared via their real hashCode/equals, dispatched
        // through the mini java/lang/Object root's vtable slots.
        Uart.write(Magic.bytes("java/util/HashMap (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/MapDemo"), Magic.bytes(""));

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
        Loader.launch(Magic.bytes("demo/ParseAllDemo"), Magic.bytes(""));

        // Real Integer.toString: the produce-a-String direction -- real toString builds its result via
        // DecimalDigits + the real byte[]+coder String constructor.
        Uart.write(Magic.bytes("real Integer.toString (unmodified JDK + mini deps):\n"));
        Loader.launch(Magic.bytes("demo/ToStringDemo"), Magic.bytes(""));

        // Real Integer.toHexString (formatUnsignedInt + the loader-seeded Integer.digits) and Long.toString
        // (the DecimalDigits long overloads).
        Uart.write(Magic.bytes("real Integer.toHexString + Long.toString (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/HexLongDemo"), Magic.bytes(""));

        // Real Long.parseLong + Long.toHexString.
        Uart.write(Magic.bytes("real Long.parseLong + Long.toHexString (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/LongMoreDemo"), Magic.bytes(""));

        // Real integer Math: floorDiv/floorMod (pure) + addExact (real ArithmeticException on overflow).
        Uart.write(Magic.bytes("real Math floorDiv/floorMod/addExact (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/MathIntDemo"), Magic.bytes(""));

        // Real java.util.Objects: equals/hashCode via the Object root's vtable, requireNonNull's NPE.
        Uart.write(Magic.bytes("real java.util.Objects (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/ObjectsDemo"), Magic.bytes(""));

        // Real java.util.Arrays: fill/equals/binarySearch on int[].
        Uart.write(Magic.bytes("real java.util.Arrays (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/ArraysDemo"), Magic.bytes(""));

        // Real Integer.valueOf autoboxing: boxed Integer keys in a HashMap (real hashCode/equals dispatch).
        Uart.write(Magic.bytes("real Integer.valueOf boxing via HashMap (unmodified JDK):\n"));
        Loader.launch(Magic.bytes("demo/BoxingDemo"), Magic.bytes(""));

        // String indexOf/substring on the real-shaped mini String.
        Uart.write(Magic.bytes("String indexOf/substring (demand-loaded):\n"));
        Loader.launch(Magic.bytes("demo/StrOpsDemo"), Magic.bytes(""));

        // M3: java.io -- the guest FileInputStream overlay reading the embedded read-only RAMFS.
        Uart.write(Magic.bytes("java.io FileInputStream (embedded RAMFS):\n"));
        Loader.launch(Magic.bytes("demo/FileDemo"), Magic.bytes(""));

        // M4: Thread identity (currentThread/getName) + Class reflection (getName/isInstance/...).
        Uart.write(Magic.bytes("Thread + Class reflection (M4):\n"));
        Loader.launch(Magic.bytes("demo/ReflectDemo"), Magic.bytes(""));

        // The real-program milestone: ordinary stock-Java WordCount from main(String[]) -- must match
        // the host JDK's output byte-for-byte on the same input file.
        Uart.write(Magic.bytes("WordCount (a real Java program, main(String[])):\n"));
        Loader.launch(Magic.bytes("demo/WordCount"), Magic.bytes("/data/sample.txt 3"));

        // The charset closure: stock new String(byte[]) + getBytes() via the UTF-8 fast path.
        Uart.write(Magic.bytes("charset: new String(byte[]) / getBytes() (stock, UTF-8 fast path):\n"));
        Loader.launch(Magic.bytes("demo/CharsetDemo"), Magic.bytes(""));

        // The GC milestone: churn far beyond the arena size -- completes only if allocation pressure
        // triggers collections (Heap.alloc -> Magic.gc) and the freed blocks are reused.
        Uart.write(Magic.bytes("GC under allocation pressure (churn >> heap):\n"));
        Loader.launch(Magic.bytes("demo/GcDemo"), Magic.bytes(""));
        Loader.printCodeArena();                           // code-arena rewind evidence: cur far below high

        // The long-running-program milestone: a Lisp interpreter whose churn forces collections
        // mid-computation -- every evaluation afterwards must still be correct.
        Uart.write(Magic.bytes("Lisp interpreter (long-running, stock java.base):\n"));
        Loader.launch(Magic.bytes("demo/LispDemo"), Magic.bytes("/data/prog.lisp 600"));

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
            resetTaskTable();                              // fresh table: only task 0 -- no demo tasks to spew
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

    /** Class-directory lookup for the on-metal {@link Loader}: bytes address for [namePtr,len), or 0. The
     *  writer-baked directory answers first; a class it doesn't hold falls through to the classpath jar
     *  ({@code /etc/init}'s {@code classpath=}), so jar classes resolve everywhere embedded ones do. */
    static long dirBytes(long namePtr, long len)
    {
        long b = findClass(namePtr, len);
        if (b == 0L)
        {
            b = JarFs.classBytes(namePtr, len);
        }
        return b;
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
        return JarFs.classLen(namePtr, len);           // not embedded: the classpath jar may still hold it
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
