package demo;

import java.util.Map;

/**
 * Demo of the programmatic stack-trace API (Thread.getStackTrace / getAllStackTraces + StackTraceElement).
 * main spawns a Worker (run -> wa -> wb) that hands off and parks in lock2.wait(); main then walks the parked
 * worker's stack directly AND via getAllStackTraces(), printing each frame's method name (bottom = run).
 */
public class StackTraceDemo
{
    static final Object lock1 = new Object();
    static final Object lock2 = new Object();
    static boolean ready = false;
    static Thread worker;

    public static void main(String[] args) throws Exception
    {
        worker = new Worker();
        worker.start();
        synchronized (lock1)
        {
            while (!ready)
            {
                lock1.wait();
            }
        }
        System.out.println("worker.getStackTrace():");
        print(worker.getStackTrace());

        System.out.println("via getAllStackTraces():");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet())
        {
            if (e.getKey() == worker)
            {
                print(e.getValue());
            }
        }
    }

    static void print(StackTraceElement[] st)
    {
        for (int i = 0; i < st.length; i++)
        {
            System.out.println("  " + st[i].getMethodName());
        }
    }

    static class Worker extends Thread
    {
        public void run()
        {
            wa();
        }

        void wa()
        {
            wb();
        }

        void wb()
        {
            synchronized (lock1)
            {
                ready = true;
                lock1.notifyAll();
            }
            synchronized (lock2)
            {
                while (true)
                {
                    try
                    {
                        lock2.wait();
                    }
                    catch (InterruptedException e)
                    {
                    }
                }
            }
        }
    }
}
