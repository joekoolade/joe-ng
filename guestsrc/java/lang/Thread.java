package java.lang;

import magic.Magic;

/**
 * A minimal, JDK-free {@link java.lang.Thread}: wraps a {@link Runnable} and, on {@link #start()}, hands
 * it to joe-ng's scheduler via the {@code magic.spawn} intrinsic (which the on-metal loader lowers to a
 * BL into {@code vm/VM.startThread}). Compiled as a {@code java.base} patch so it carries the real
 * {@code java/lang/Thread} name, then embedded as a raw blob and loaded at runtime. No String concat,
 * no lambdas, no {@code synchronized} — so our baseline compiler (no invokedynamic) can compile it.
 */
public class Thread
{
    private Runnable target;

    public Thread(Runnable r)
    {
        target = r;
    }

    /** Start a fresh task running {@code target.run()} (preempted by the timer like any joe-ng task). */
    public void start()
    {
        Magic.spawn(target);
    }

    /** Sleep the current task at least {@code ms} milliseconds (yields; never busy-waits). */
    public static void sleep(long ms)
    {
        Magic.sleepMs(ms);
    }
}
