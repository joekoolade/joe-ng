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

import java.util.ArrayList;
import java.util.List;

/**
 * The end of a program run INSIDE this VM by another (the web server's {@code /run}, {@code demo/RunProgram}): its
 * own {@code System.exit}/{@code Runtime.halt}, which end that program and not the board, and a STOP from outside.
 *
 * <p>The runner calls {@link #begin} on the thread that will call {@code main}, and {@link #end} when main is over.
 * The mark is an {@code InheritableThreadLocal}, so every thread the program starts carries it too -- for as long as
 * that thread lives, after main has returned included -- and each of those threads joins its {@link Run} as it
 * starts ({@link #threadStarted}, from {@code Thread.metalThreadEntry}).
 *
 * <p><b>Exit.</b> {@code java.lang.Shutdown}'s {@code exit} and {@code halt} call {@link #checkExit}/{@link #checkHalt}
 * first: a marked thread records its status and leaves by {@link Exit}. An unmarked thread -- the server, or a
 * program launched from /etc/init -- goes on to halt as before. Nothing in {@code Shutdown} has changed state by
 * then, so the next program can still register hooks; a program's own shutdown hooks are NOT run (they share one
 * JVM-wide table with every other program's, and running it is the irreversible step this exists to avoid). Only
 * the thread that calls exit is ended.
 *
 * <p><b>Stop</b> ({@link Run#stop}), in two stages. First every thread of the run is interrupted, so one in
 * {@code sleep}/{@code wait}/{@code join} comes back, and a program that handles interrupts can finish on its own.
 * Then, after a grace period, each thread still alive is given {@link Stopped} to throw at its next STOP POLL: the
 * loop headers of the program's own methods, compiled with a poll because the program's classes were defined while
 * {@link #defining} was on (Baseline.emitStopPoll). A poll is an ordinary call-site throw at a bytecode boundary,
 * so the program's handlers run and its monitors are released as for any exception -- unlike a stop delivered at
 * whatever instruction a timer tick lands on, which is why {@code Thread.stop} is gone. A thread busy in JDK code
 * stops when it returns to the program's loop; one blocked in a JDK call that ignores interrupts does not.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. The VM side is {@code vm.VMNatives.runStop} and
 * {@code runDefining}.
 */
public final class MetalRunExit
{
    private MetalRunExit()
    {
    }

    /** VM native: give {@code t}'s task {@code e} to throw at its next stop poll. 1 when t has a live task. */
    private static native int stop0(Thread t, Throwable e);

    /** VM native: while on, classes this thread defines from bytes compile with stop polls. */
    private static native void defining0(boolean on);

    /** The run the current thread belongs to, inherited by the threads it starts; null outside a run. */
    private static final InheritableThreadLocal<Run> RUN = new InheritableThreadLocal<>();

    /** One run: its threads, the first exit any of them called, and whether it is being stopped. */
    public static final class Run
    {
        /** The thread that called {@link #begin}: the one that runs the program's main. */
        private final Thread owner;

        /** Every thread of the run that has started, owner first. */
        private final List<Thread> threads = new ArrayList<>();

        private boolean exited;

        private int status;

        private String thread;

        private String how;

        /** Non-null once a forced stop began: the error the run's threads throw at their polls. */
        private Stopped stopping;

        private boolean interrupted;

        private Run(Thread owner)
        {
            this.owner = owner;
            threads.add(owner);
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

        /** True once a thread of this run called {@code System.exit} or {@code Runtime.halt}. */
        public synchronized boolean exited()
        {
            return exited;
        }

        /** What the first exit called: "System.exit" or "Runtime.halt". */
        public synchronized String how()
        {
            return how;
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

        /** True once {@link #interrupt} or {@link #force} was called. */
        public synchronized boolean stopRequested()
        {
            return interrupted || stopping != null;
        }

        /** True once {@link #force} was called. */
        public synchronized boolean forced()
        {
            return stopping != null;
        }

        /** The run's threads that are still alive. */
        public synchronized List<Thread> liveThreads()
        {
            List<Thread> live = new ArrayList<>();
            for (Thread t : threads)
            {
                if (t.isAlive())
                {
                    live.add(t);
                }
            }
            return live;
        }

        /** Stage one: interrupt every live thread of the run. Returns how many. */
        public int interrupt()
        {
            List<Thread> live;
            synchronized (this)
            {
                interrupted = true;
                live = liveThreads();
            }
            for (Thread t : live)
            {
                t.interrupt();
            }
            return live.size();
        }

        /**
         * Stage two: every live thread of the run throws {@link Stopped} at its next stop poll -- and so does any
         * thread the run starts from now on. Returns how many threads were marked.
         */
        public int force()
        {
            List<Thread> live;
            Stopped err;
            synchronized (this)
            {
                if (stopping == null)
                {
                    stopping = new Stopped();
                }
                err = stopping;
                live = liveThreads();
            }
            int n = 0;
            for (Thread t : live)
            {
                n = n + stop0(t, err);
                t.interrupt();                          // a thread parked again since stage one comes back too
            }
            return n;
        }

        private void started(Thread t)
        {
            Stopped err;
            synchronized (this)
            {
                threads.add(t);
                err = stopping;
            }
            if (err != null)
            {
                stop0(t, err);                          // started by a thread that has not reached its poll yet
            }
        }
    }

    /** How a run's thread ends without it being a fault: an {@code Error}, so {@code catch (Exception)} misses it. */
    public abstract static class End extends Error
    {
        private static final long serialVersionUID = 1L;

        End(String message)
        {
            super(message, null, false, false);         // no trace: an exit or a stop is not a fault
        }
    }

    /** Thrown out of {@code System.exit}/{@code Runtime.halt} in a run's thread. */
    public static final class Exit extends End
    {
        private static final long serialVersionUID = 1L;

        private final int status;

        Exit(String how, int status)
        {
            super(how + "(" + status + ") ended the run");
            this.status = status;
        }

        public int status()
        {
            return status;
        }
    }

    /** Thrown at a stop poll in a run's thread once the run was stopped from outside. */
    public static final class Stopped extends End
    {
        private static final long serialVersionUID = 1L;

        Stopped()
        {
            super("the run was stopped");
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

    /** Called by {@code Thread.metalThreadEntry} as every thread starts: a run's thread joins its run. */
    public static void threadStarted()
    {
        Run r = RUN.get();
        if (r != null)
        {
            r.started(Thread.currentThread());
        }
    }

    /**
     * Around defining a run's classes: while on, the classes the CURRENT thread defines from bytes compile with stop
     * polls. The caller serializes defining (one thread at a time).
     */
    public static void defining(boolean on)
    {
        defining0(on);
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
