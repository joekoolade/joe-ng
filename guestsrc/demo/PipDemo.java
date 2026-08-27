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
 */
public class PipDemo
{
    /** The contended resource: LOW holds it, HIGH wants it, MED never touches it. */
    static final Object lock = new Object();

    /** A SECOND monitor for the finish-order string, so bookkeeping never perturbs the contended one. */
    static final Object tally = new Object();

    static final StringBuilder order = new StringBuilder();

    static final int MED_THREADS = 4;              // >= cores (see the class comment)
    static final int LOW_HOLD_MS = 60;             // LOW's critical section
    static final int MED_BURN_MS = 200;            // ... comfortably longer, so the two outcomes separate

    static final int PRIO_LOW = 2;
    static final int PRIO_MED = 5;
    static final int PRIO_HIGH = 10;

    static boolean lowHasLock;
    static boolean medRecorded;
    static long highBlockedMs = -1;

    public static void main(String[] args) throws Exception
    {
        Thread low = new Thread(new Low());
        low.setPriority(PRIO_LOW);
        low.start();

        while (!lowHasLock)                        // sleep, not spin: main must not be a fourth cpu hog
        {
            Thread.sleep(1);
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
                lowHasLock = true;
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
            long t0 = System.nanoTime();
            synchronized (lock)                    // blocks: this is where inheritance fires, lending
            {                                      //   HIGH's priority to whoever holds the monitor
                highBlockedMs = (System.nanoTime() - t0) / 1000000L;
            }
            record('H');
        }
    }
}
