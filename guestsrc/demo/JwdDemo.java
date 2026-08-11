package demo;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/** join(Duration) + park/unpark + async-interrupt-during-join — the cooperative-friendly slice of JoinWithDuration. */
public class JwdDemo
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("A: join(Duration) on unstarted thread -> IllegalThreadStateException");
        Thread t0 = new Thread(new Runnable() { public void run() { } });
        try
        {
            t0.join(Duration.ofMillis(0));
            System.out.println("  no throw (BAD)");
        }
        catch (IllegalThreadStateException e)
        {
            System.out.println("  ITSE ok");
        }

        System.out.println("B: park a thread (LockSupport::park method ref)");
        Thread t = new Thread(LockSupport::park);
        t.start();
        System.out.println("  started alive=" + t.isAlive());

        System.out.println("C: join(100ms) times out");
        boolean r = t.join(Duration.ofMillis(100));
        System.out.println("  join=" + r);

        System.out.println("D: unpark + join");
        LockSupport.unpark(t);
        t.join();
        System.out.println("  after unpark alive=" + t.isAlive());

        System.out.println("E: async interrupt during join(Duration)");
        Thread mainT = Thread.currentThread();
        Thread waker = new Thread(() ->
        {
            try
            {
                Thread.sleep(1000);
                mainT.interrupt();
            }
            catch (Exception e)
            {
            }
        });
        waker.start();
        Thread parked = new Thread(LockSupport::park);
        parked.start();
        boolean iCaught = false;
        try
        {
            parked.join(Duration.ofMinutes(1));
        }
        catch (InterruptedException e)
        {
            iCaught = true;
        }
        finally
        {
            LockSupport.unpark(parked);
            parked.join();
            waker.join();
        }
        // Exercises the unwind's callee-saved-local reconstruction: `parked` and `waker` are set before the
        // try and read in the catch/finally after a cross-method InterruptedException unwind.
        System.out.println("  iCaught=" + iCaught + " parkedIntr=" + parked.isInterrupted());
        System.out.println("done");
    }
}
