/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-21
 */

/**
 * Threads allocate far more garbage than a secondary core's 63 MiB arena holds, while keeping a set of long-lived
 * objects alive and linked to each other across threads -- so across arenas. Until frames/GC arc S5a, cores 1-3's
 * arenas were never collected (scanned whole as roots instead), and a thread that churned through one halted the VM
 * with {@code heap OOM}. Now a full arena collects, and every core's allocations are swept into its own free lists:
 * the churn must finish, and every survivor must still read back the values it was built with.
 */
public final class ArenaChurnProbe
{
    static final int THREADS = 3;
    static final int KEEP = 512;
    static final long CHURN_BYTES = 160L * 1024L * 1024L;     // per thread: ~2.5 arenas' worth of garbage

    /** A long-lived node: its own values, and a link to a node another thread made. */
    static final class Node
    {
        final int owner;
        final int index;
        final int[] payload;
        Node peer;

        Node(int owner, int index)
        {
            this.owner = owner;
            this.index = index;
            this.payload = new int[16];
            for (int i = 0; i < payload.length; i += 1)
            {
                payload[i] = owner * 1_000_000 + index * 100 + i;
            }
        }

        boolean intact(int o, int x)
        {
            if (owner != o || index != x)
            {
                return false;
            }
            for (int i = 0; i < payload.length; i += 1)
            {
                if (payload[i] != o * 1_000_000 + x * 100 + i)
                {
                    return false;
                }
            }
            return true;
        }
    }

    static final Node[][] kept = new Node[THREADS][];
    static final long[] churned = new long[THREADS];
    static final long[] sums = new long[THREADS];

    public static void main(String[] args) throws Throwable
    {
        Thread[] ts = new Thread[THREADS];
        for (int t = 0; t < THREADS; t += 1)
        {
            final int me = t;
            ts[t] = new Thread(() -> run(me));
        }
        for (Thread t : ts)
        {
            t.start();
        }
        for (Thread t : ts)
        {
            t.join();
        }
        // Link every node to one another thread made, then churn once more from main: the links cross arenas.
        for (int t = 0; t < THREADS; t += 1)
        {
            for (int i = 0; i < KEEP; i += 1)
            {
                kept[t][i].peer = kept[(t + 1) % THREADS][KEEP - 1 - i];
            }
        }
        long mainSum = churn(CHURN_BYTES / 2, 7);
        int bad = 0;
        for (int t = 0; t < THREADS; t += 1)
        {
            for (int i = 0; i < KEEP; i += 1)
            {
                Node n = kept[t][i];
                if (!n.intact(t, i) || !n.peer.intact((t + 1) % THREADS, KEEP - 1 - i))
                {
                    bad += 1;
                }
            }
        }
        for (int t = 0; t < THREADS; t += 1)
        {
            System.out.println("thread " + t + " churned " + (churned[t] >> 20) + " MiB sum " + sums[t]);
        }
        System.out.println("main churned sum " + mainSum);
        System.out.println("survivors checked " + (THREADS * KEEP) + " bad " + bad);
        System.out.println("ArenaChurnProbe done");
    }

    static void run(int me)
    {
        Node[] mine = new Node[KEEP];
        for (int i = 0; i < KEEP; i += 1)
        {
            mine[i] = new Node(me, i);
        }
        kept[me] = mine;
        sums[me] = churn(CHURN_BYTES, me);
        churned[me] = CHURN_BYTES;
    }

    /** Allocate {@code bytes} in short-lived arrays of varied sizes, keeping a small rolling window alive. */
    static long churn(long bytes, int seed)
    {
        Object[] window = new Object[64];
        long done = 0L;
        long sum = 0L;
        int k = seed;
        while (done < bytes)
        {
            int len = 64 + (k * 37) % 4000;
            byte[] b = new byte[len];
            b[0] = (byte) k;
            b[len - 1] = (byte) (k >> 3);
            window[k & 63] = b;
            byte[] old = (byte[]) window[(k + 17) & 63];
            if (old != null)
            {
                sum += old[0] + old[old.length - 1];
            }
            done += len;
            k += 1;
        }
        return sum;
    }
}
