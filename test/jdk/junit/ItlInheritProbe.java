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
import java.util.function.Function;

/**
 * An {@code InheritableThreadLocal} flows into a thread made with EVERY public {@code Thread} constructor, except the
 * 5-arg one asked not to ({@code inheritThreadLocals == false}). The overlay's three commonest constructors --
 * {@code Thread()}, {@code Thread(Runnable)}, {@code Thread(Runnable, String)} -- used to skip the copy, so the child
 * read null: found when {@code System.exit} in a {@code /run} program's thread halted the board instead of ending
 * the run (jdk.internal.misc.MetalRunExit marks a run with one). Also: {@code childValue} transforms the value, a
 * child's set does not reach the parent, and a grandchild inherits through its parent.
 */
public class ItlInheritProbe
{
    static final InheritableThreadLocal<String> ITL = new InheritableThreadLocal<>();

    static final InheritableThreadLocal<String> UPPER = new InheritableThreadLocal<>()
    {
        @Override
        protected String childValue(String parent)
        {
            return parent == null ? null : parent.toUpperCase();
        }
    };

    static final ThreadLocal<String> PLAIN = new ThreadLocal<>();

    static volatile String seen;

    public static void main(String[] args) throws Exception
    {
        ITL.set("parent");
        UPPER.set("value");
        PLAIN.set("not inherited");
        Runnable read = () -> seen = ITL.get() + " " + UPPER.get() + " " + PLAIN.get();

        show("Thread()", r -> new Thread()
        {
            @Override
            public void run()
            {
                r.run();
            }
        });
        show("Thread(Runnable)", r -> new Thread(r));
        show("Thread(Runnable, String)", r -> new Thread(r, "t"));
        show("Thread(String) subclass", r -> new Thread("t")
        {
            @Override
            public void run()
            {
                r.run();
            }
        });
        show("Thread(ThreadGroup, Runnable)", r -> new Thread(null, r));
        show("Thread(ThreadGroup, Runnable, String)", r -> new Thread(null, r, "t"));
        show("Thread(ThreadGroup, Runnable, String, long)", r -> new Thread(null, r, "t", 0L));
        show("Thread(..., inheritThreadLocals=true)", r -> new Thread(null, r, "t", 0L, true));
        show("Thread(..., inheritThreadLocals=false)", r -> new Thread(null, r, "t", 0L, false));

        Thread child = new Thread(() ->
        {
            ITL.set("child's own");
            Thread grandchild = new Thread(read);
            grandchild.start();
            join(grandchild);
        });
        child.start();
        join(child);
        System.out.println("grandchild sees: " + seen);
        System.out.println("parent still sees: " + ITL.get());
    }

    static void show(String ctor, Function<Runnable, Thread> make) throws Exception
    {
        seen = "(did not run)";
        Thread t = make.apply(() -> seen = ITL.get() + " " + UPPER.get() + " " + PLAIN.get());
        t.start();
        join(t);
        System.out.println(ctor + ": " + seen);
    }

    static void join(Thread t)
    {
        try
        {
            t.join();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }
}
