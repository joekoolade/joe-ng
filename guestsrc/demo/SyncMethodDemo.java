package demo;

/**
 * ACC_SYNCHRONIZED on a METHOD (JVMS 2.11.10). A synchronized method carries no monitorenter/monitorexit
 * bytecode -- javac emits those only for a synchronized BLOCK -- so the monitor is the VM's to take. joe-ng
 * did not take it, which meant {@code synchronized} on a method gave NO mutual exclusion at all, silently,
 * on a VM that schedules four cores off one run queue. java.base alone has 475 instance-synchronized
 * methods (Hashtable, Vector, StringBuffer, Properties).
 *
 * <p>{@code Thread.holdsLock} is what makes the first three arms direct rather than statistical: it answers
 * whether the monitor is held, so they fail on the unfixed VM single-threaded, with no race required.
 *
 * <p>A STATIC synchronized method locks the class's own {@code Class} object instead, and arms 7-11 are
 * about that. The one with teeth is the MIXED race: a {@code static synchronized} method run against a
 * {@code synchronized (SyncMethodDemo.class)} BLOCK doing the same read-modify-write. A VM that locks
 * *something* for a static method passes every holdsLock arm and still loses updates here, because the two
 * forms have to name the SAME mirror. Arms 9-10 are its cheap twin: the class monitor and an instance's
 * monitor must be DISTINCT, so neither form can be quietly aliased to the other.
 *
 * <p>NEGATIVE CONTROL. With {@code Baseline.emitSyncEnter}/{@code emitSyncExit} disabled, arms 1-3 and 7
 * report {@code holds=0} where they want 1, and both race arms lose updates ({@code total} below
 * {@code want}). With the fix in, every arm is exact. The release arms matter as much as the acquire: a
 * monitor leaked on an exception would trade a missing lock for a PERMANENT DEADLOCK, which is worse than
 * the bug being fixed.
 */
public class SyncMethodDemo
{
    private int counter;
    private boolean heldInside;
    private boolean heldInNested;

    /** The plain case: the monitor must be held for the whole body. */
    private synchronized void bump()
    {
        heldInside = Thread.holdsLock(this);
        counter += 1;
    }

    /** RECURSIVE acquire: a synchronized method calling another on the same receiver must not self-deadlock. */
    private synchronized void nested()
    {
        heldInNested = Thread.holdsLock(this);
        bump();
    }

    /** ABRUPT completion: the monitor must be released as the exception leaves the frame. */
    private synchronized void boom()
    {
        throw new IllegalStateException("boom");
    }

    /** A synchronized method that RETURNS A VALUE -- the release is emitted before the value moves to x0. */
    private synchronized int valued(int x)
    {
        return x + counter;
    }

    // ---- STATIC synchronized: the monitor is the class's own Class object (JVMS 2.11.10) ----
    private static boolean staticHeldClass;      // ... holdsLock(SyncMethodDemo.class) inside the method
    private static boolean staticHeldInstance;   // ... and holdsLock(someInstance), which must be FALSE
    private static boolean staticHeldNested;

    private static synchronized void staticBump(SyncMethodDemo probe)
    {
        staticHeldClass = Thread.holdsLock(SyncMethodDemo.class);
        staticHeldInstance = Thread.holdsLock(probe);   // a static method locks the CLASS, never an instance
        staticCounter += 1;
    }

    private static int staticCounter;

    /** RECURSIVE acquire of the class monitor. */
    private static synchronized void staticNested(SyncMethodDemo probe)
    {
        staticHeldNested = Thread.holdsLock(SyncMethodDemo.class);
        staticBump(probe);
    }

    /** ABRUPT completion out of a STATIC synchronized method: the class monitor must be released. */
    private static synchronized void staticBoom()
    {
        throw new IllegalStateException("static boom");
    }

    // ---- the race arm: read-modify-write with a yield in the middle ----
    private int shared;

    private synchronized void raceStep()
    {
        int t = shared;
        try
        {
            Thread.sleep(1);            // force an interleave: without the monitor this loses updates
        }
        catch (InterruptedException e)
        {
        }
        shared = t + 1;
    }

    // THE MIXED RACE. `sharedStatic` is stepped two ways: half the threads through a STATIC SYNCHRONIZED
    // METHOD, half through a `synchronized (SyncMethodDemo.class)` BLOCK. Both forms lock the class's Class
    // object, so they must serialise against EACH OTHER -- which they only do if the implicit monitor is the
    // very mirror the class literal names. A VM that locked any other object would pass every holdsLock arm
    // above and lose updates here.
    private static int sharedStatic;

    private static synchronized void staticRaceStep()
    {
        int t = sharedStatic;
        try
        {
            Thread.sleep(1);
        }
        catch (InterruptedException e)
        {
        }
        sharedStatic = t + 1;
    }

    /** The same read-modify-write, through the class literal a programmer would write by hand. */
    private static void blockRaceStep()
    {
        synchronized (SyncMethodDemo.class)
        {
            int t = sharedStatic;
            try
            {
                Thread.sleep(1);
            }
            catch (InterruptedException e)
            {
            }
            sharedStatic = t + 1;
        }
    }

    private static final int THREADS = 4;
    private static final int STEPS = 5;

    public static void main(String[] args) throws Exception
    {
        SyncMethodDemo d = new SyncMethodDemo();

        // 1. the monitor is genuinely held INSIDE a synchronized method ...
        d.bump();
        System.out.println("held inside       = " + (d.heldInside ? 1 : 0) + " (want 1)");
        // 2. ... and released on normal return.
        System.out.println("held after return = " + (Thread.holdsLock(d) ? 1 : 0) + " (want 0)");

        // 3. recursive acquire, then released once at each level.
        d.nested();
        System.out.println("held in nested    = " + (d.heldInNested ? 1 : 0) + " (want 1)");
        System.out.println("held after nested = " + (Thread.holdsLock(d) ? 1 : 0) + " (want 0)");

        // 4. abrupt completion releases. A leak here is a deadlock, not a wrong number.
        try
        {
            d.boom();
            System.out.println("boom              = no exception (WRONG)");
        }
        catch (IllegalStateException e)
        {
            System.out.println("boom              = IllegalStateException");
        }
        System.out.println("held after throw  = " + (Thread.holdsLock(d) ? 1 : 0) + " (want 0)");

        // 5. a value-returning synchronized method still returns its value.
        System.out.println("valued(40)        = " + d.valued(40) + " (want 42)");
        System.out.println("held after valued = " + (Thread.holdsLock(d) ? 1 : 0) + " (want 0)");

        // 6. THE RACE. Four threads, five read-modify-writes each, a sleep inside the critical section.
        //    Exact only if the method's monitor actually serialises them.
        Thread[] ts = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++)
        {
            ts[i] = new Thread(new Stepper(d));
        }
        for (int i = 0; i < THREADS; i++)
        {
            ts[i].start();
        }
        for (int i = 0; i < THREADS; i++)
        {
            ts[i].join();
        }
        System.out.println("race total        = " + d.shared + " (want " + (THREADS * STEPS) + ")");

        // 7. STATIC synchronized: the monitor is the CLASS object, held for the whole body ...
        staticBump(d);
        System.out.println("static ran        = " + staticCounter + " (want 1)");
        System.out.println("static held class = " + (staticHeldClass ? 1 : 0) + " (want 1)");
        // 8. ... and released on normal return.
        System.out.println("static after ret  = " + (Thread.holdsLock(SyncMethodDemo.class) ? 1 : 0) + " (want 0)");

        // 9-10. The two monitors are DISTINCT. A static method must not be holding some instance's monitor,
        //       and an instance method must not be holding the class's -- either aliasing would make the
        //       mixed race below pass for the wrong reason.
        System.out.println("static held inst  = " + (staticHeldInstance ? 1 : 0) + " (want 0)");
        d.bump();
        System.out.println("inst held class   = " + (Thread.holdsLock(SyncMethodDemo.class) ? 1 : 0) + " (want 0)");

        // 11. recursive acquire of the CLASS monitor, released once per level.
        staticNested(d);
        System.out.println("static in nested  = " + (staticHeldNested ? 1 : 0) + " (want 1)");
        System.out.println("static aft nested = " + (Thread.holdsLock(SyncMethodDemo.class) ? 1 : 0) + " (want 0)");

        // 12. abrupt completion out of a static synchronized method releases the class monitor.
        try
        {
            staticBoom();
            System.out.println("static boom       = no exception (WRONG)");
        }
        catch (IllegalStateException e)
        {
            System.out.println("static boom       = IllegalStateException");
        }
        System.out.println("static aft throw  = " + (Thread.holdsLock(SyncMethodDemo.class) ? 1 : 0) + " (want 0)");

        // 12b. CONTROL for the mixed race below: does a synchronized BLOCK on the class literal itself
        //      register as holding that class's monitor? If this reports 0 the fault is in holdsLock or in
        //      class-literal identity, NOT in the implicit monitor -- which is a different bug in a
        //      different place, and the mixed race alone cannot tell the two apart.
        synchronized (SyncMethodDemo.class)
        {
            System.out.println("block holds class = " + (Thread.holdsLock(SyncMethodDemo.class) ? 1 : 0) + " (want 1)");
        }

        // 13. THE MIXED RACE -- the arm that separates "locks something" from "locks the RIGHT object".
        //     Half the threads go through the static synchronized method, half through a synchronized block
        //     on the class literal. Exact only if both name the same mirror.
        Thread[] ms = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++)
        {
            ms[i] = new Thread(new MixedStepper(i % 2 == 0));
        }
        for (int i = 0; i < THREADS; i++)
        {
            ms[i].start();
        }
        for (int i = 0; i < THREADS; i++)
        {
            ms[i].join();
        }
        System.out.println("mixed total       = " + sharedStatic + " (want " + (THREADS * STEPS) + ")");
    }

    /** Half these step through the STATIC SYNCHRONIZED METHOD, half through a synchronized BLOCK on the
     *  class literal. Both must contend for the one class monitor. */
    static final class MixedStepper implements Runnable
    {
        private final boolean viaMethod;

        MixedStepper(boolean viaMethod)
        {
            this.viaMethod = viaMethod;
        }

        public void run()
        {
            for (int i = 0; i < STEPS; i++)
            {
                if (viaMethod)
                {
                    staticRaceStep();
                }
                else
                {
                    blockRaceStep();
                }
            }
        }
    }

    /** A named class rather than a lambda: this demo is about the monitor, not the indy machinery. */
    static final class Stepper implements Runnable
    {
        private final SyncMethodDemo target;

        Stepper(SyncMethodDemo target)
        {
            this.target = target;
        }

        public void run()
        {
            for (int i = 0; i < STEPS; i++)
            {
                target.raceStep();
            }
        }
    }
}
