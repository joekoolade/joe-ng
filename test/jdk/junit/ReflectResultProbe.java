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

import java.lang.reflect.Method;

/**
 * A reflective call's RESULT must survive a collection that lands before the caller holds it. Method.invoke used to
 * keep the callee's return value as a raw long until box() turned it into an Object -- across box()'s prologue
 * safepoint, where the precise stack walk (frames/GC arc S5) does not see a raw long -- so a collection started by
 * another thread at that moment freed a result nothing else referenced. Found as Class.getEnumConstants' values()
 * clone faulting its (Object[]) cast (frames/GC arc S6b). Here one thread churns garbage so collections keep coming
 * from another core while this one invokes a method returning a fresh array, and checks every result it gets back.
 */
public final class ReflectResultProbe
{
    static volatile boolean done;

    public static long[] make(long seed)
    {
        long[] a = new long[8];
        for (int i = 0; i < a.length; i += 1)
        {
            a[i] = seed * 31L + i;
        }
        return a;
    }

    public static void main(String[] args) throws Exception
    {
        Thread churn = new Thread(() ->
        {
            long sink = 0L;
            while (!done)
            {
                byte[] b = new byte[2048];
                sink += b.length;
            }
        });
        churn.start();
        Method m = ReflectResultProbe.class.getMethod("make", long.class);
        int bad = 0;
        int calls = 20000;
        for (int k = 0; k < calls; k += 1)
        {
            Object r = m.invoke(null, (long) k);
            if (!(r instanceof long[]))
            {
                bad += 1;
                continue;
            }
            long[] a = (long[]) r;
            for (int i = 0; i < a.length; i += 1)
            {
                if (a[i] != k * 31L + i)
                {
                    bad += 1;
                    break;
                }
            }
        }
        done = true;
        churn.join();
        System.out.println("reflective results checked " + calls + " bad " + bad);
        System.out.println("ReflectResultProbe done");
    }
}
