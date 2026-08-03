package java.lang;

import magic.Magic;

/**
 * A minimal, JDK-free {@link java.lang.Thread}: wraps a {@link Runnable} and, on {@link #start()}, hands
 * ITSELF to joe-ng's scheduler via the {@code magic.spawn} intrinsic ({@code VM.startThread}) — Thread
 * implements {@link Runnable} ({@link #run()} delegates to the target), so the spawned receiver is the
 * Thread object and the VM records it as the new task's thread ({@code taskThreadObj}). That makes
 * {@link #currentThread()} return the exact Thread the code started (M4); for tasks the VM created
 * without a Thread (the boot task), {@code VM.currentThreadObj} lazily wraps the task in a bare Thread.
 * Compiled as a {@code java.base} patch so it carries the real {@code java/lang/Thread} name. No String
 * concat, no lambdas, no {@code synchronized} — so the baseline compiler can compile it.
 */
public class Thread implements Runnable
{
    private Runnable target;    // @16 — what run() delegates to
    private String name;        // @24

    public Thread(Runnable r)
    {
        target = r;
    }

    public Thread(Runnable r, String threadName)
    {
        target = r;
        name = threadName;
    }

    /** The spawned task's body: the run-trampoline invokeinterface-dispatches this, which runs the target. */
    public void run()
    {
        if (target != null)
        {
            target.run();
        }
    }

    /** Start a fresh task running {@code this.run()} (preempted by the timer like any joe-ng task). */
    public void start()
    {
        Magic.spawn(this);
    }

    /** The Thread of the calling task (the started Thread itself, or a lazy wrapper for VM-created tasks). */
    public static Thread currentThread()
    {
        return currentThread0();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.currentThreadObj}): the current task's Thread. */
    private static native Thread currentThread0();

    public String getName()
    {
        return name;
    }

    public void setName(String threadName)
    {
        name = threadName;
    }

    /** Sleep the current task at least {@code ms} milliseconds (yields; never busy-waits). */
    public static void sleep(long ms)
    {
        Magic.sleepMs(ms);
    }
}
