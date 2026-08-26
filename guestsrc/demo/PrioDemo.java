package demo;

/**
 * The priority scheduler seen from GUEST Java, through the stock {@code java.lang.Thread} API only —
 * {@code setPriority}, {@code getPriority}, {@code synchronized}, {@code join}. Nothing here is
 * joe-ng-specific.
 *
 * <p>Two things are checked. First the API round-trips, including the case that is easy to get wrong: a
 * priority set BEFORE {@code start()}, when the thread has no task for the VM to retarget yet.
 *
 * <p>Then the ordering, made deterministic on any number of cores by funnelling every thread through one
 * monitor. Main holds the lock while starting all six and waits for them to pile up on it, so when it
 * finally releases, all six are blocked on the same object and the only thing that can decide who goes
 * first is priority. Each thread then releases the monitor to the next, so the whole chain is ordered.
 * They are started in ASCENDING priority order, so FIFO handoff would print exactly the reverse.
 */
public class PrioDemo
{
    static final Object lock = new Object();
    static final StringBuilder order = new StringBuilder();
    static final int[] PRIOS = { 1, 3, 5, 6, 8, 10 };      // ascending: FIFO would finish in this order

    public static void main(String[] args) throws Exception
    {
        Thread[] t = new Thread[PRIOS.length];
        int i = 0;
        while (i < PRIOS.length)
        {
            t[i] = new Thread(new Step(PRIOS[i]));
            t[i].setPriority(PRIOS[i]);                    // BEFORE start(): no task exists yet
            if (t[i].getPriority() != PRIOS[i])
            {
                System.out.println("FAIL: getPriority before start = " + t[i].getPriority());
            }
            i += 1;
        }

        synchronized (lock)
        {
            i = 0;
            while (i < PRIOS.length)
            {
                t[i].start();
                i += 1;
            }
            Thread.sleep(50);                              // let all six reach the monitor and block on it
        }                                                  // released here: highest priority goes first

        i = 0;
        while (i < PRIOS.length)
        {
            t[i].join();
            i += 1;
        }

        System.out.println("finish order = " + order);
        System.out.println("want         = 10 8 6 5 3 1 ");
    }

    static class Step implements Runnable
    {
        private final int prio;

        Step(int prio)
        {
            this.prio = prio;
        }

        public void run()
        {
            synchronized (lock)                            // one at a time, whatever the core count
            {
                if (Thread.currentThread().getPriority() != prio)
                {
                    System.out.println("FAIL: running priority = " + Thread.currentThread().getPriority());
                }
                order.append(prio);
                order.append(' ');
            }
        }
    }
}
