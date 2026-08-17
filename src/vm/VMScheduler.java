package vm;

import asm.A64Enc;
import board.bcm2711.Bcm2711;
import board.bcm2711.Gic;
import board.bcm2711.Uart;
import magic.Magic;
import static vm.VM.*;   // shared state stays in VM: the *Addr helper statics, the task/monitor/semaphore tables,
                         // TASK_* constants, MAX_TASKS/NUM_SEM/MAX_MON, WIFI_SEM, SCHED_FRAME, the SMP/pc fields,
                         // vbarBase/ticks/timerReload/runTramp, and the staying helpers (installSchedVectors,
                         // buildSwitchStub, printDec, ...) -- reached unqualified via this import.

/**
 * The concurrency subsystem extracted from VM.java (SMP secondary-core bring-up + MMU/page-tables, the hardware
 * spinlock's SMP work loop, M7 preemptive scheduling on the timer tick, and java.lang.Object monitors +
 * java.lang.Thread / LockSupport / Semaphore support). Reached from JIT'd guest code and the IRQ/SVC vectors ONLY
 * via the VM.*Addr statics (the writer's stashHelper fills them; installSchedVectors/buildSwitchStub, which stay
 * in VM, bake those addresses into the vector stubs), so nothing binds these methods by the class name except the
 * writer table (updated to vm/VMScheduler) and VM's force-compile roots. All shared state stays in VM.
 */
public final class VMScheduler
{
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
}
