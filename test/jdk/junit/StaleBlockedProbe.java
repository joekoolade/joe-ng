/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-26
 */

import java.util.concurrent.locks.LockSupport;

/**
 * A wait that ENDS while its task is still marked blocked must not strand the task. The scheduler resumes a
 * task that cannot continue rather than idle core 0, so a timed park re-tests its deadline as a busy-wait; a
 * deadline that passes between the scheduler's wake scan and that re-test exits the park with the task still
 * BLOCKED. The task then runs on until it next yields -- here in Thread.join, with more READY threads than
 * cores -- and was never scheduled again. Twenty rounds of (short timed park, then join a burst of yielding
 * threads) make the window near-certain to be hit; the hang this pins first appeared as SemaphoreProbe batched
 * after the Executor, Lock and Park probes.
 */
public final class StaleBlockedProbe
{
    static volatile int work;

    public static void main(String[] args) throws Throwable
    {
        int rounds = 0;
        while (rounds < 20)
        {
            LockSupport.parkNanos(100_000L);
            Thread[] ts = new Thread[6];
            for (int i = 0; i < ts.length; i += 1)
            {
                ts[i] = new Thread(() ->
                {
                    for (int k = 0; k < 40; k += 1)
                    {
                        work += 1;
                        Thread.yield();
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts)
            {
                t.join();
            }
            rounds += 1;
        }
        System.out.println("rounds=" + rounds);
        System.out.println("StaleBlockedProbe done");
    }
}
