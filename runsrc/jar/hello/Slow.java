/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package hello;

/**
 * A run that takes a while, for {@code main=hello.Slow args=<tag> <millis>}: every line carries the tag, so two runs
 * at once can be told apart. Main sleeps for the time given; a thread it starts prints once more AFTER main has
 * returned, which is still the run's output.
 */
public class Slow
{
    public static void main(String[] args) throws Exception
    {
        String tag = args.length > 0 ? args[0] : "x";
        long ms = args.length > 1 ? Long.parseLong(args[1]) : 1000L;
        System.out.println("slow " + tag + " start on thread " + Thread.currentThread().getName());
        Thread late = new Thread(() ->
        {
            try
            {
                Thread.sleep(ms + 500L);
            }
            catch (InterruptedException e)
            {
                return;
            }
            System.out.println("slow " + tag + " late, from a thread main left running");
        }, "late-" + tag);
        late.start();
        Thread.sleep(ms);
        System.err.println("slow " + tag + " end");
    }
}
