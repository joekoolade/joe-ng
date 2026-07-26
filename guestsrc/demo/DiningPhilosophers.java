package demo;

import java.util.concurrent.Semaphore;

/**
 * The classic dining-philosophers concurrency demo — the program joe-ng loads on the metal. The writer
 * embeds only its raw {@code .class} bytes; at runtime {@code vm/Loader.loadAndRun} parses it, pulls in
 * every class it references (java/lang/Thread, java/lang/Runnable, java/util/concurrent/Semaphore,
 * demo/Philosopher) from the embedded mini {@code java.base} in {@code classDir}, JIT-compiles them to
 * A64, and runs {@code main} — which spawns five philosopher tasks that the scheduler then preempts.
 *
 * <p>Written in plain, idiomatic Java against {@code java.lang.Thread}/{@code Runnable} and
 * {@code java.util.concurrent.Semaphore}. Kept JDK-free-compilable (no String concat / lambdas) so the
 * baseline compiler can handle it.
 */
public class DiningPhilosophers
{
    static final int N = 5;

    public static void main()
    {
        Semaphore[] forks = new Semaphore[N];
        int i = 0;
        while (i < N)
        {
            forks[i] = new Semaphore(1);         // each fork starts available (1 permit)
            i = i + 1;
        }
        i = 0;
        while (i < N)
        {
            Semaphore left = forks[i];
            Semaphore right = forks[(i + 1) % N];
            Philosopher p = new Philosopher(i, left, right);
            Thread t = new Thread(p);
            t.start();                            // hands p.run() to joe-ng's scheduler
            i = i + 1;
        }
    }
}
