package java.lang;

import magic.Magic;

/**
 * Minimal thread group: joe-ng has a flat scheduler with no group hierarchy, so a single shared group holds
 * every live thread. It exists so Thread's 5-arg constructor and {@link Thread#getThreadGroup()} have a type
 * to name, and so {@code activeCount()}/{@code enumerate(Thread[])} can report the live threads the VM tracks.
 */
public class ThreadGroup
{
    /** The one group every thread belongs to (there is no hierarchy). */
    static final ThreadGroup SYSTEM = new ThreadGroup();

    private String name;

    ThreadGroup()
    {
        this.name = "main";
    }

    /**
     * Named groups are accepted and flattened into the one group: the name is remembered so
     * {@code getName()} is truthful, but grouping has no scheduling effect here. Library code constructs a
     * group to label threads, which this supports; code that expects a group to ISOLATE threads would be
     * disappointed, and there is none on metal.
     */
    public ThreadGroup(String name)
    {
        this.name = name;
    }

    public final String getName()
    {
        return name;
    }

    /** Always false, matching {@link Thread#isDaemon()}: joe-ng has no daemon/non-daemon distinction. */
    public final boolean isDaemon()
    {
        return false;
    }

    public final void setDaemon(boolean daemon)
    {
    }

    public final ThreadGroup getParent()
    {
        return null;                                    // flat: the one group has no parent
    }

    /** Number of live threads (every thread is in this one group). */
    public int activeCount()
    {
        return Magic.allthr().length;
    }

    /**
     * Copy the live threads into {@code list} (up to its length) and return how many were copied. Matches
     * {@link java.lang.ThreadGroup#enumerate(Thread[])} for the flat, single-group case.
     */
    public int enumerate(Thread[] list)
    {
        Thread[] all = Magic.allthr();
        int n = all.length;
        if (n > list.length)
        {
            n = list.length;
        }
        int i = 0;
        while (i < n)
        {
            list[i] = all[i];
            i += 1;
        }
        return n;
    }
}
