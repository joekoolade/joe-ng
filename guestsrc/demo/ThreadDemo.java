package demo;

/**
 * M-A regression demo: a no-arg Thread subclass (overridden run() dispatched via spawn), an Object-monitor
 * wait/notifyAll handshake, and Thread.join(). main blocks in lock.wait() until the worker sets a value and
 * lock.notifyAll()s, then join()s it. Prints "worker r=42" then "joined" when all three work.
 */
public class ThreadDemo
{
    static final Object lock = new Object();
    static final int[] r = new int[1];
    static boolean ready = false;

    public static void main(String[] args) throws Exception
    {
        Worker w = new Worker();
        w.start();
        synchronized (lock)
        {
            while (!ready)
            {
                lock.wait();
            }
        }
        System.out.println("worker r=42");
        w.join();
        System.out.println("joined");
    }

    static class Worker extends Thread
    {
        public void run()
        {
            r[0] = 42;
            synchronized (lock)
            {
                ready = true;
                lock.notifyAll();
            }
        }
    }
}
