package demo;

import magic.Magic;

/**
 * SMP proof from GUEST Java: four {@code java.lang.Thread}s, each stepping and recording which core the
 * step actually ran on (MPIDR's low two bits), under a shared monitor. With one run queue over four cores
 * every counter moves and the total is threads*steps; on a single-core scheduler only core 0's does.
 *
 * <p>Deliberately ordinary Java -- {@code new Thread(Runnable)}, {@code start}, {@code synchronized},
 * {@code join} -- so what it exercises is the scheduler, the cross-core monitor and the JIT's loader lock,
 * not any SMP-specific API. The only unusual call is {@link Magic#mpidr()}, which is how a thread can
 * say where it is (the short name is what the on-metal JIT's magic table can match).
 */
public class SmpDemo
{
    static final int THREADS = 4;
    static final int STEPS = 200;
    static final int[] perCore = new int[4];

    public static void main(String[] args) throws Exception
    {
        Thread[] t = new Thread[THREADS];
        int i = 0;
        while (i < THREADS)
        {
            t[i] = new Thread(new Step());
            t[i].start();
            i += 1;
        }
        i = 0;
        while (i < THREADS)
        {
            t[i].join();
            i += 1;
        }
        int total = 0;
        int c = 0;
        while (c < 4)
        {
            System.out.println("core " + c + " steps " + perCore[c]);
            total += perCore[c];
            c += 1;
        }
        System.out.println("total " + total + " of " + (THREADS * STEPS));
    }

    static class Step implements Runnable
    {
        public void run()
        {
            int n = 0;
            while (n < STEPS)
            {
                int core = (int) (Magic.mpidr() & 3L);
                synchronized (perCore)                     // a monitor genuinely contended ACROSS cores
                {
                    perCore[core] = perCore[core] + 1;
                }
                n += 1;
                try
                {
                    Thread.sleep(0);                       // hand the core back: the queue re-places us
                }
                catch (InterruptedException e)
                {
                    return;
                }
            }
        }
    }
}
