package java.util.concurrent;

import magic.Magic;

/**
 * A minimal, JDK-free {@link java.util.concurrent.Semaphore}: a thin handle over one of joe-ng's
 * blocking counting semaphores (allocated by {@code magic.newSem}; {@code acquire}/{@code release} map
 * to the scheduler's {@code semWait}/{@code semPost}). A blocked {@code acquire} parks the task, so the
 * dining philosophers demonstrate real contention and scheduling. Embedded + loaded like the rest of
 * the mini {@code java.base}.
 */
public class Semaphore
{
    private int id;

    public Semaphore(int permits)
    {
        id = Magic.newSem(permits);
    }

    /** Acquire a permit, blocking (yielding) this task until one is available. */
    public void acquire()
    {
        Magic.semWait(id);
    }

    /** Release a permit, waking one waiter. */
    public void release()
    {
        Magic.semPost(id);
    }
}
