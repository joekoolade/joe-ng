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
package jdk.internal.misc;

/**
 * {@code System.exit} and {@code Runtime.halt} for a program run INSIDE this VM by another (the web server's
 * {@code /run}, {@code demo/RunProgram}): they end that program, not the board.
 *
 * <p>The runner calls {@link #begin} on the thread that will call {@code main}, and {@link #end} when main is over.
 * The mark is an {@code InheritableThreadLocal}, so every thread the program starts carries it too -- for as long as
 * that thread lives, after main has returned included. {@code java.lang.Shutdown}'s {@code exit} and {@code halt}
 * call {@link #check} first: a marked thread records its status on the {@link Run} and leaves by {@link Exit}, an
 * {@code Error} that unwinds it like any throw. An unmarked thread -- the server itself, or a program launched from
 * /etc/init -- goes on to halt as before.
 *
 * <p>Nothing in {@code Shutdown} has changed state at that point: no hook has run and {@code VM.shutdown()} has not
 * been called, so the next program can still register hooks and exit in its turn. A program's own shutdown hooks are
 * NOT run: they are registered with every other program's in one JVM-wide table, and running that table is the
 * irreversible step this exists to avoid.
 *
 * <p>Only the thread that calls exit is ended. When that is main, the run is over. When it is another of the
 * program's threads, main is not stopped -- there is no safe way to stop a thread -- but the run reports the exit.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class.
 */
public final class MetalRunExit
{
    private MetalRunExit()
    {
    }

    /** The run the current thread belongs to, inherited by the threads it starts; null outside a run. */
    private static final InheritableThreadLocal<Run> RUN = new InheritableThreadLocal<>();

    /** One run: the status of the first exit any of its threads called. */
    public static final class Run
    {
        /** The thread that called {@link #begin}: the one that runs the program's main. */
        private final Thread owner;

        private boolean exited;

        private int status;

        private String thread;

        private String how;

        private Run(Thread owner)
        {
            this.owner = owner;
        }

        private synchronized void exit(String how, int status, String thread)
        {
            if (!exited)
            {
                exited = true;
                this.how = how;
                this.status = status;
                this.thread = thread;
            }
        }

        /** What the first exit called: "System.exit" or "Runtime.halt". */
        public synchronized String how()
        {
            return how;
        }

        /** True once a thread of this run called {@code System.exit} or {@code Runtime.halt}. */
        public synchronized boolean exited()
        {
            return exited;
        }

        /** The status that first exit passed. */
        public synchronized int status()
        {
            return status;
        }

        /** The name of the thread that called it: "main" for the one that ran main, "" for an unnamed one. */
        public synchronized String thread()
        {
            return thread;
        }
    }

    /**
     * Thrown out of {@code System.exit}/{@code Runtime.halt} in a run's thread. An {@code Error}, so the program's
     * {@code catch (Exception e)} does not swallow it; no stack trace, as an exit is not a fault.
     */
    public static final class Exit extends Error
    {
        private static final long serialVersionUID = 1L;

        private final int status;

        Exit(String how, int status)
        {
            super(how + "(" + status + ") ended the run", null, false, false);
            this.status = status;
        }

        public int status()
        {
            return status;
        }
    }

    /** Mark the current thread, and the threads it will start, as one run. */
    public static Run begin()
    {
        Run r = new Run(Thread.currentThread());
        RUN.set(r);
        return r;
    }

    /** Unmark the current thread. The threads the run started stay marked. */
    public static void end()
    {
        RUN.remove();
    }

    /** Called first by {@code Shutdown.exit}: see {@link #check}. */
    public static void checkExit(int status)
    {
        check("System.exit", status);
    }

    /** Called first by {@code Shutdown.halt}, which {@code Runtime.halt} reaches directly: see {@link #check}. */
    public static void checkHalt(int status)
    {
        check("Runtime.halt", status);
    }

    /**
     * In a run's thread, record {@code status} and throw {@link Exit}; otherwise return, and the board halts.
     */
    private static void check(String how, int status)
    {
        Run r = RUN.get();
        if (r == null)
        {
            return;
        }
        Thread t = Thread.currentThread();
        String name = t == r.owner ? "main" : t.getName();
        r.exit(how, status, name == null ? "" : name);
        throw new Exit(how, status);
    }
}
