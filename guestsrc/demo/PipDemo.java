package demo;

/**
 * Priority inversion seen from GUEST Java — stock {@code Thread}, {@code setPriority} and
 * {@code synchronized}, nothing joe-ng-specific.
 *
 * <p>The textbook three-role scenario. LOW takes a monitor. MED, which outranks LOW and never touches the
 * monitor, burns CPU. HIGH, which outranks MED, wants the monitor. Without priority inheritance HIGH waits
 * for MED to finish — behind work it outranks, for as long as MED cares to run — and the roles finish
 * MED, HIGH, LOW. With it, LOW is lent HIGH's priority, outruns MED, releases, and drops back: HIGH, MED,
 * LOW. The first letter is the whole test; the blocked time is the same fact quantified.
 *
 * <p><b>Why there are four MED threads.</b> Inversion needs the three roles to CONTEND for a cpu. The
 * VM-internal version of this demo sidestepped that by running before the secondary cores joined the run
 * queue, so it was single-core by construction. A launched program has no such luxury — with four cores and
 * three threads LOW simply runs on an idle one and there is nothing to observe. So MED out-numbers the
 * cores: every core is busy with a thread that outranks LOW, and LOW makes progress only if it is boosted.
 *
 * <p><b>Why the roles hand off through {@link #gate} instead of just starting.</b> Two failure modes, both
 * of which produce a healthy-LOOKING result that tested nothing, and both of which were observed:
 * <ul>
 *   <li>A critical section measured in wall-clock from the moment LOW acquires the lock has already expired
 *       by the time the other five threads are created — HIGH then finds the lock free and the demo prints
 *       {@code LHM / 0ms}. So LOW takes the lock and BLOCKS, burning nothing, until everyone is in place.</li>
 *   <li>The signal to begin must come from HIGH, immediately before HIGH blocks on the lock. Under strict
 *       priority HIGH cannot be preempted between the two (LOW is 2, HIGH is 10), so the section provably
 *       starts with HIGH already contending.</li>
 * </ul>
 * Every wait here is a real {@code Object.wait} rather than a yield loop, because it must also work with NO
 * preemption: inside the boot suite the timer is stopped, and a spin at one priority level simply locks the
 * others out.
 */
public class PipDemo
{
    /** The contended resource: LOW holds it, HIGH wants it, MED never touches it. */
    static final Object lock = new Object();

    /** A SECOND monitor for the finish-order string, so bookkeeping never perturbs the contended one. */
    static final Object tally = new Object();

    /** The handoff monitor: LOW parks on it holding the lock; HIGH releases it just before contending. */
    static final Object gate = new Object();

    static final StringBuilder order = new StringBuilder();

    static final int MED_THREADS = 4;              // >= cores (see the class comment)
    static final int LOW_HOLD_MS = 60;             // LOW's critical section
    static final int MED_BURN_MS = 200;            // ... comfortably longer, so the two outcomes separate

    static final int PRIO_LOW = 2;
    static final int PRIO_MED = 5;
    static final int PRIO_HIGH = 10;

    static boolean lowHasLock;                     // LOW holds the monitor and is parked on the gate
    static boolean go;                             // HIGH is about to contend: LOW may start its section
    static boolean medRecorded;
    static long highBlockedMs = -1;

    public static void main(String[] args) throws Exception
    {
        Thread low = new Thread(new Low());
        low.setPriority(PRIO_LOW);
        synchronized (gate)
        {
            low.start();
            while (!lowHasLock)
            {
                gate.wait();                       // BLOCK, don't spin: LOW is below us and gets the cpu
            }
        }

        Thread[] med = new Thread[MED_THREADS];
        int i = 0;
        while (i < MED_THREADS)
        {
            med[i] = new Thread(new Med());
            med[i].setPriority(PRIO_MED);
            med[i].start();
            i += 1;
        }

        Thread high = new Thread(new High());
        high.setPriority(PRIO_HIGH);
        high.start();

        // Setup is over; from here main is only a spectator, and it must stop outranking the roles it is
        // waiting for. Thread.join() is a yield-POLL -- the joining task stays RUNNABLE and simply offers the
        // cpu -- so under strict priority a joiner above a joinee starves it forever. The boot flow's task
        // sits at the scheduler's PRIO_NORM, which is ABOVE Java priority 5, so joining the MED threads at
        // the default hangs on a single core: main yields, main is still the most urgent runnable task, main
        // gets the cpu straight back. Dropping to the floor is the fix, and it costs nothing -- main has
        // nothing left to do until every role has finished.
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

        high.join();
        i = 0;
        while (i < MED_THREADS)
        {
            med[i].join();
            i += 1;
        }
        low.join();

        System.out.println("priority inversion (guest Thread): finish " + order
                           + " (want HML, HML=inherited / MHL=inverted)  HIGH blocked " + highBlockedMs + "ms");
    }

    /**
     * Burn cpu for {@code ms}, yielding each time round. The yield is deliberate: a thread that never
     * yields can only lose the core to a timer tick, and QEMU delivers none — so on the emulator LOW would
     * run its whole critical section before HIGH ever woke, printing a healthy-looking result that tested
     * nothing. Yielding puts a scheduling decision in the loop, and then only the priority rule decides who
     * continues, identically on emulator and hardware.
     */
    static void burn(long ms)
    {
        long end = System.nanoTime() + ms * 1000000L;
        while (System.nanoTime() < end)
        {
            try
            {
                Thread.sleep(0);
            }
            catch (InterruptedException e)
            {
                return;
            }
        }
    }

    static void record(char c)
    {
        synchronized (tally)
        {
            order.append(c);
        }
    }

    /** MED is four threads but one ROLE: the first to finish stands for all of them in the order string. */
    static void recordMedOnce()
    {
        synchronized (tally)
        {
            if (!medRecorded)
            {
                medRecorded = true;
                order.append('M');
            }
        }
    }

    static class Low implements Runnable
    {
        public void run()
        {
            synchronized (lock)
            {
                synchronized (gate)
                {
                    lowHasLock = true;
                    gate.notifyAll();              // main may proceed to set up the contenders
                    try
                    {
                        while (!go)
                        {
                            gate.wait();           // hold the lock, burn nothing, until HIGH is at the door
                        }
                    }
                    catch (InterruptedException e)
                    {
                        return;
                    }
                }
                burn(LOW_HOLD_MS);                 // the critical section HIGH is stuck behind
            }
            burn(20);                              // ordinary work afterwards, back at LOW priority: the
            record('L');                           //   MEDs starve it, so LOW finishes last either way
        }
    }

    static class Med implements Runnable
    {
        public void run()
        {
            burn(MED_BURN_MS);
            recordMedOnce();
        }
    }

    static class High implements Runnable
    {
        public void run()
        {
            synchronized (gate)
            {
                go = true;                         // LOW's section starts HERE, and cannot start earlier:
                gate.notifyAll();                  //   we outrank LOW, so nothing runs between this and the
            }                                      //   blocking acquire below
            long t0 = System.nanoTime();
            synchronized (lock)                    // blocks: inheritance fires here, lending HIGH's
            {                                      //   priority to whoever holds the monitor
                highBlockedMs = (System.nanoTime() - t0) / 1000000L;
            }
            record('H');
        }
    }
}
