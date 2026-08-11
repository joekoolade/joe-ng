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
