/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Created: 2026-10-23
 *
 * SPDX-License-Identifier: EPL-2.0
 */
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The messages of a failed {@code checkcast} and a failed {@code aastore}, from four threads at once. Each thread
 * fails casts and stores with its OWN pair of classes, so a message naming another thread's classes -- or none --
 * means the exception was described from shared state rather than from the operation that failed.
 *
 * <p>A cast message is compared up to the classes it names: stock goes on to say which module and loader each
 * class is in, which this VM does not model. A store message is the stored value's class name, as on stock.
 */
public class ExcMessageRaceProbe
{
    private static final int THREADS = 4;
    private static final int ROUNDS = 3000;

    static final Object[] VALUES = { 7, 7L, "s", 'c' };
    static final String[] NAMES = { "java.lang.Integer", "java.lang.Long", "java.lang.String", "java.lang.Character" };

    static volatile Object sink;

    /** Cast {@code v} to a class it is not, chosen per thread so each thread's message is distinct. */
    static void failCast(int id, Object v)
    {
        if (id == 0)
        {
            sink = (Double) v;
        }
        else if (id == 1)
        {
            sink = (Short) v;
        }
        else if (id == 2)
        {
            sink = (Byte) v;
        }
        else
        {
            sink = (Float) v;
        }
    }

    static final String[] TARGETS = { "java.lang.Double", "java.lang.Short", "java.lang.Byte", "java.lang.Float" };

    public static void main(String[] args) throws Exception
    {
        final AtomicInteger castOk = new AtomicInteger();
        final AtomicInteger castBad = new AtomicInteger();
        final AtomicInteger storeOk = new AtomicInteger();
        final AtomicInteger storeBad = new AtomicInteger();
        final String[] firstBad = new String[THREADS];
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < THREADS; t++)
        {
            final int id = t;
            Thread th = new Thread(() ->
            {
                Object v = VALUES[id];
                String want = "class " + NAMES[id] + " cannot be cast to class " + TARGETS[id];
                Object[] arr = new Number[1];
                if (id == 0 || id == 1)
                {
                    arr = new String[1];             // Integer/Long into String[]
                }
                for (int r = 0; r < ROUNDS; r++)
                {
                    try
                    {
                        failCast(id, v);
                        castBad.incrementAndGet();
                    }
                    catch (ClassCastException e)
                    {
                        String m = e.getMessage();
                        if (m != null && (m.equals(want) || m.startsWith(want + " (")))
                        {
                            castOk.incrementAndGet();
                        }
                        else
                        {
                            castBad.incrementAndGet();
                            if (firstBad[id] == null)
                            {
                                firstBad[id] = "cast " + id + ": " + m;
                            }
                        }
                    }
                    try
                    {
                        arr[0] = v;
                        storeBad.incrementAndGet();
                    }
                    catch (ArrayStoreException e)
                    {
                        String m = e.getMessage();
                        if (NAMES[id].equals(m))
                        {
                            storeOk.incrementAndGet();
                        }
                        else
                        {
                            storeBad.incrementAndGet();
                            if (firstBad[id] == null)
                            {
                                firstBad[id] = "store " + id + ": " + m;
                            }
                        }
                    }
                }
            });
            ts.add(th);
        }
        for (Thread th : ts)
        {
            th.start();
        }
        for (Thread th : ts)
        {
            th.join();
        }
        System.out.println("casts: right = " + castOk.get() + " (want " + (THREADS * ROUNDS) + "), wrong = " + castBad.get());
        System.out.println("stores: right = " + storeOk.get() + " (want " + (THREADS * ROUNDS) + "), wrong = " + storeBad.get());
        for (int t = 0; t < THREADS; t++)
        {
            if (firstBad[t] != null)
            {
                System.out.println("first wrong, thread " + t + ": " + firstBad[t]);
            }
        }
        System.out.println("ExcMessageRaceProbe done");
    }
}
