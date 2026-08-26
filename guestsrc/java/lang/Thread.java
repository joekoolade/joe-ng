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
    Object[] tlKeys;            // @32 — ThreadLocal keys for this thread's thread-local map (null until first put)
    Object[] tlVals;            // @40 — parallel values
    int tlN;                    // @48 — number of entries
    private int priority;       // @56 — the Java 1..10 value setPriority was given; 0 = never set

    /** The lowest priority a thread may have. */
    public static final int MIN_PRIORITY = 1;
    /** The default. */
    public static final int NORM_PRIORITY = 5;
    /** The highest priority a thread may have. */
    public static final int MAX_PRIORITY = 10;

    /** No-arg ctor: a Thread subclass overrides run() (its own body is the task); there is no separate target. */
    public Thread()
    {
    }

    public Thread(Runnable r)
    {
        target = r;
    }

    public Thread(Runnable r, String threadName)
    {
        target = r;
        name = threadName;
    }

    /**
     * Full constructor. {@code group} and {@code stackSize} are ignored (no thread groups; stacks are fixed).
     * If {@code inheritThreadLocals}, copy the creating thread's INHERITABLE thread-locals into this new thread,
     * each transformed by {@link ThreadLocal#childValue} -- so an {@link InheritableThreadLocal} flows to children.
     */
    public Thread(ThreadGroup group, Runnable r, String threadName, long stackSize, boolean inheritThreadLocals)
    {
        target = r;
        name = threadName;
        if (inheritThreadLocals)
        {
            Thread parent = currentThread();
            int i = 0;
            while (i < parent.tlN)
            {
                ThreadLocal key = (ThreadLocal) parent.tlKeys[i];
                if (key.inheritable())
                {
                    tlPut(key, key.childValue(parent.tlVals[i]));
                }
                i += 1;
            }
        }
    }

    /** Every thread belongs to the one flat group (no hierarchy on joe-ng). */
    public ThreadGroup getThreadGroup()
    {
        return ThreadGroup.SYSTEM;
    }

    // ----- thread-local map (used by java.lang.ThreadLocal; package-private) -----

    int tlIndex(Object key)
    {
        int i = 0;
        while (i < tlN)
        {
            if (tlKeys[i] == key)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    Object tlValueAt(int i)
    {
        return tlVals[i];
    }

    void tlPut(Object key, Object val)
    {
        int i = tlIndex(key);
        if (i >= 0)
        {
            tlVals[i] = val;
            return;
        }
        if (tlKeys == null)
        {
            tlKeys = new Object[16];
            tlVals = new Object[16];
        }
        tlKeys[tlN] = key;
        tlVals[tlN] = val;
        tlN += 1;
    }

    void tlRemove(Object key)
    {
        int i = tlIndex(key);
        if (i >= 0)
        {
            tlN -= 1;
            tlKeys[i] = tlKeys[tlN];
            tlVals[i] = tlVals[tlN];
            tlKeys[tlN] = null;
            tlVals[tlN] = null;
        }
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
        if (priority != 0)
        {
            Magic.setprio(this, vmPriority(priority));   // apply what was set before this thread had a task
        }
    }

    /**
     * java.lang.Thread schedules on 1..10; the VM schedules on 0..1024. Map linearly, so MIN_PRIORITY lands
     * exactly on the VM floor and MAX_PRIORITY exactly on its ceiling. The VM scale is the finer one and is
     * reachable directly through {@code magic.Magic.setprio} for code that wants all 1025 levels.
     */
    private static int vmPriority(int javaPriority)
    {
        return (javaPriority - MIN_PRIORITY) * 1024 / (MAX_PRIORITY - MIN_PRIORITY);
    }

    /**
     * Set this thread's priority. Scheduling is STRICT: a runnable thread of higher priority always runs
     * first, so a busy high-priority thread starves lower ones. Setting it before {@code start()} is
     * remembered and applied when the thread is started.
     */
    public final void setPriority(int newPriority)
    {
        if (newPriority < MIN_PRIORITY || newPriority > MAX_PRIORITY)
        {
            throw new IllegalArgumentException();
        }
        priority = newPriority;
        Magic.setprio(this, vmPriority(newPriority));    // a no-op while this thread has no task yet
    }

    /** This thread's priority on the 1..10 scale. Unset threads report what they will actually be given. */
    public final int getPriority()
    {
        if (priority != 0)
        {
            return priority;
        }
        return MIN_PRIORITY + (Magic.getprio(this) * (MAX_PRIORITY - MIN_PRIORITY) + 512) / 1024;
    }

    /** The Thread of the calling task (the started Thread itself, or a lazy wrapper for VM-created tasks). */
    public static Thread currentThread()
    {
        return currentThread0();
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.currentThreadObj}): the current task's Thread. */
    private static native Thread currentThread0();

    /**
     * joe-ng has no virtual threads -- every task is a platform thread. Must exist as a real method so an
     * {@code invokevirtual isVirtual()} resolves to a proper vtable slot: {@code sun.nio.ch.NativeThread
     * .current()} calls {@code Thread.currentThread().isVirtual()}, and a missing method would dispatch
     * through a bogus slot into garbage code (a data-abort fault).
     */
    public boolean isVirtual()
    {
        return false;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String threadName)
    {
        name = threadName;
    }

    /** Sleep the current task at least {@code ms} milliseconds (yields; never busy-waits). Interruptible. */
    public static void sleep(long ms) throws InterruptedException
    {
        if (ms < 0)
        {
            throw new IllegalArgumentException("timeout value is negative");
        }
        Magic.sleepMs(ms);
        if (Magic.wasintr())                               // Thread.interrupt() fired during/before the sleep
        {
            throw new InterruptedException();
        }
    }

    /** Sleep {@code millis} ms + {@code nanos} ns (rounded up to a whole ms). Interruptible. */
    public static void sleep(long millis, int nanos) throws InterruptedException
    {
        if (millis < 0)
        {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999)
        {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        if (nanos > 0 && millis < 9223372036854775807L)
        {
            millis += 1;
        }
        sleep(millis);
    }

    /** Sleep for {@code duration}. A negative duration returns immediately; null throws NPE. Interruptible. */
    public static void sleep(java.time.Duration duration) throws InterruptedException
    {
        long nanos = duration.toNanos();                   // NPE if duration is null
        if (nanos < 0)
        {
            return;                                        // negative duration: no sleep (matches the JDK)
        }
        long millis = nanos / 1000000L;
        int nanoRem = (int) (nanos - millis * 1000000L);
        sleep(millis, nanoRem);
    }

    /** True if this thread has been started and its run() has not yet returned. */
    public boolean isAlive()
    {
        return Magic.isalive(this);
    }

    /** Set this thread's interrupt flag (and wake it if it is sleeping/blocked). */
    public void interrupt()
    {
        Magic.intr(this);
    }

    /** This thread's interrupt flag (does not clear it). */
    public boolean isInterrupted()
    {
        return Magic.isintr(this);
    }

    /** Tests + CLEARS the current thread's interrupt status (static). */
    public static boolean interrupted()
    {
        return Magic.wasintr();
    }

    /** No-op: joe-ng has no daemon/non-daemon distinction (the boot task keeps the VM alive). */
    public void setDaemon(boolean on)
    {
    }

    /** Print the current thread's stack trace (matches the JDK: a throwaway exception's trace). */
    public static void dumpStack()
    {
        new Exception("Stack trace").printStackTrace();
    }

    /** Block the calling task until THIS thread's run() has returned. */
    public final void join() throws InterruptedException
    {
        Magic.tjoin(this);
    }

    /**
     * Wait at most {@code duration} for this thread to terminate. Returns true if it terminated. Throws NPE if
     * {@code duration} is null, IllegalThreadStateException if this thread has not been started, and
     * InterruptedException (clearing the interrupt status) if the caller is interrupted while waiting.
     */
    public final boolean join(java.time.Duration duration) throws InterruptedException
    {
        if (duration == null)
        {
            throw new NullPointerException();
        }
        int r = Magic.joinms(this, duration.toMillis());
        if (r == 3)
        {
            throw new IllegalThreadStateException("thread not started");
        }
        if (r == 2)
        {
            throw new InterruptedException();
        }
        return r == 1;
    }

    /** A snapshot of this thread's stack (this thread if it is the caller, else its saved/blocked context). */
    public StackTraceElement[] getStackTrace()
    {
        return Magic.stacktr(this);
    }

    /** True if the current thread holds the monitor lock on {@code obj}. Throws NPE if {@code obj} is null. */
    public static boolean holdsLock(Object obj)
    {
        if (obj == null)
        {
            throw new NullPointerException();
        }
        return Magic.hldlock(obj);
    }

    /** A map of every live thread to its current stack trace. */
    public static java.util.Map<Thread, StackTraceElement[]> getAllStackTraces()
    {
        Thread[] ts = Magic.allthr();
        java.util.HashMap<Thread, StackTraceElement[]> m = new java.util.HashMap<Thread, StackTraceElement[]>();
        int i = 0;
        while (i < ts.length)
        {
            Thread t = ts[i];
            m.put(t, t.getStackTrace());
            i += 1;
        }
        return m;
    }
}
