/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-14
 */
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The three FIELD UPDATERS under contention: four threads each run 3000 compare-and-set increments on one int
 * field, one long field and one reference field (a boxed counter swapped for its successor), retrying on failure
 * as every CAS loop does. A CAS that is not atomic loses updates and the totals come out short. Then the int
 * updater's {@code set} over a NEGATIVE value written by compiled code (so the slot's high half is set), read
 * back by compiled code through a division that a stale high half would corrupt. Prints values only; the host
 * diff is the gate.
 */
public class UpdaterRaceProbe
{
    volatile int ic;
    volatile long lc;
    volatile Integer rc = Integer.valueOf(0);

    static final AtomicIntegerFieldUpdater<UpdaterRaceProbe> IC =
            AtomicIntegerFieldUpdater.newUpdater(UpdaterRaceProbe.class, "ic");
    static final AtomicLongFieldUpdater<UpdaterRaceProbe> LC =
            AtomicLongFieldUpdater.newUpdater(UpdaterRaceProbe.class, "lc");
    static final AtomicReferenceFieldUpdater<UpdaterRaceProbe, Integer> RC =
            AtomicReferenceFieldUpdater.newUpdater(UpdaterRaceProbe.class, Integer.class, "rc");

    public static void main(String[] args) throws Exception
    {
        UpdaterRaceProbe p = new UpdaterRaceProbe();
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t += 1)
        {
            ts[t] = new Thread(() ->
            {
                for (int k = 0; k < 3000; k += 1)
                {
                    int iv;
                    do
                    {
                        iv = p.ic;
                    }
                    while (!IC.compareAndSet(p, iv, iv + 1));
                    long lv;
                    do
                    {
                        lv = p.lc;
                    }
                    while (!LC.compareAndSet(p, lv, lv + 1));
                    Integer rv;
                    do
                    {
                        rv = p.rc;
                    }
                    while (!RC.compareAndSet(p, rv, Integer.valueOf(rv.intValue() + 1)));
                }
            });
            ts[t].start();
        }
        for (Thread t : ts)
        {
            t.join();
        }
        System.out.println("int updater total=" + p.ic + " (want 12000)");
        System.out.println("long updater total=" + p.lc + " (want 12000)");
        System.out.println("reference updater total=" + p.rc + " (want 12000)");

        p.ic = -1;                                  // compiled putfield: the slot is sign-extended, high half set
        IC.set(p, 10);
        System.out.println("set over negative: ic==10 " + (p.ic == 10) + " ic/5=" + (p.ic / 5) + " (long)ic="
                + (long) p.ic);
        System.out.println("cas over negative: " + IC.compareAndSet(p, 10, -3) + " " + IC.compareAndSet(p, -3, 9)
                + " ic/3=" + (p.ic / 3));
        System.out.println("UpdaterRaceProbe done");
    }
}
