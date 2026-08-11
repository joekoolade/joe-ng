package demo;

/**
 * Cooperative-scheduler demo of Thread.isAlive / interrupt / interruptible sleep (the unmodified JDK IsAlive
 * test busy-loops, which needs the preemptive timer -- real HW only; this yields via sleep so QEMU runs it).
 */
public class IsAliveDemo
{
    public static void main(String[] args) throws Exception
    {
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    while (true)
                    {
                        Thread.sleep(50);
                    }
                }
                catch (InterruptedException e)
                {
                    System.out.println("worker interrupted");
                }
            }
        });
        System.out.println("alive before start=" + t.isAlive());   // false
        t.start();
        System.out.println("alive after start=" + t.isAlive());    // true
        Thread.sleep(120);                                         // let the worker run + sleep a few times
        System.out.println("interrupting");
        t.interrupt();
        t.join();
        System.out.println("alive after join=" + t.isAlive());     // false
        System.out.println("done");
    }
}
