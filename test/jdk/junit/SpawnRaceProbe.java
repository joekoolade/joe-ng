/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-27
 */

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread asks who it is the moment it starts. The VM used to publish a new task as runnable BEFORE writing its
 * receiver and its Thread object, so another core could run it first: the run-trampoline then saw a null receiver
 * (a branch to address 0) or currentThread() found no Thread and minted a different one. Hundreds of back-to-back
 * starts give the window many chances on four cores; every thread must find itself.
 */
public final class SpawnRaceProbe
{
    static final AtomicInteger ran = new AtomicInteger();
    static final AtomicInteger wrongSelf = new AtomicInteger();

    public static void main(String[] args) throws Throwable
    {
        int rounds = 0;
        while (rounds < 50)
        {
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i += 1)
            {
                Thread[] self = new Thread[1];
                Thread t = new Thread(() ->
                {
                    if (Thread.currentThread() != self[0])
                    {
                        wrongSelf.incrementAndGet();
                    }
                    ran.incrementAndGet();
                });
                self[0] = t;
                ts[i] = t;
            }
            for (Thread t : ts)
            {
                t.start();
            }
            for (Thread t : ts)
            {
                t.join();
            }
            rounds += 1;
        }
        System.out.println("ran=" + ran.get() + " wrongSelf=" + wrongSelf.get());
        System.out.println("SpawnRaceProbe done");
    }
}
