package demo;

import java.util.concurrent.Semaphore;
import magic.Magic;

/**
 * One dining philosopher, running as its own joe-ng task. It thinks, gets hungry, picks up two forks
 * (blocking {@link Semaphore}s), eats, then puts them down — three times, then reports done. Deadlock is
 * avoided the classic way: even-numbered philosophers take their left fork first, odd-numbered take
 * their right first, so no cyclic wait can form.
 *
 * <p>{@code magic.report}/{@code magic.sleepMs} are joe-ng intrinsics the loader lowers to VM helpers
 * (status printing + a yielding sleep). No String concat — formatting lives image-side in VM.philReport.
 */
public class Philosopher implements Runnable
{
    private int id;
    private Semaphore left;
    private Semaphore right;

    public Philosopher(int id, Semaphore left, Semaphore right)
    {
        this.id = id;
        this.left = left;
        this.right = right;
    }

    public void run()
    {
        int k = 0;
        while (k < 3)
        {
            Magic.report(id, 0);                 // thinking
            Magic.sleepMs(20L);
            Magic.report(id, 1);                 // hungry
            if (id % 2 == 0)
            {
                left.acquire();                  // even: left fork first
                right.acquire();
            }
            else
            {
                right.acquire();                 // odd: right fork first (breaks the cycle)
                left.acquire();
            }
            Magic.report(id, 2);                 // eating
            Magic.sleepMs(30L);
            left.release();
            right.release();
            k = k + 1;
        }
        Magic.report(id, 3);                     // done
    }
}
