/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-28
 */

/**
 * {@code getStackTrace()} of an exception thrown deeper than eight frames. The VM kept only eight inline frame
 * slots ({@code Throwable.bt0..bt7}), so every trace was cut to its eight innermost frames -- a silent wrong answer
 * for any caller that walks the trace to find its own frame, and the reason the refused-connect trace in
 * CtorChainTraceProbe could not be compared with the host. Stock keeps up to {@code MaxJavaStackTraceDepth}
 * (1024) frames.
 *
 * <p>Each arm prints the frames down to this probe's {@code main} (a batch runs {@code main} reflectively, so
 * there is more below it) and the count. The re-capture arm calls {@code fillInStackTrace()} again from
 * {@code main} on an exception that was deep, which must SHRINK its trace, not keep the old frames.
 */
public final class DeepTraceProbe
{
    public static void main(String[] args)
    {
        IllegalStateException deep = null;
        try
        {
            rec(20);
        }
        catch (IllegalStateException e)
        {
            deep = e;
            print("thrown 20 calls deep", e);
        }
        try
        {
            rec(2);
        }
        catch (IllegalStateException e)
        {
            print("thrown 2 calls deep", e);
        }
        if (deep != null)
        {
            deep.fillInStackTrace();
            print("re-captured from main", deep);
        }
        System.out.println("DeepTraceProbe done");
    }

    private static int rec(int n)
    {
        if (n == 0)
        {
            throw new IllegalStateException("bottom");
        }
        return rec(n - 1) + 1;
    }

    private static void print(String what, Throwable t)
    {
        StackTraceElement[] st = t.getStackTrace();
        int recFrames = 0;
        int kept = 0;
        StringBuilder names = new StringBuilder();
        int i = 0;
        while (i < st.length)
        {
            String m = st[i].getClassName() + "." + st[i].getMethodName();
            kept += 1;
            if (m.equals("DeepTraceProbe.rec"))
            {
                recFrames += 1;
            }
            else
            {
                names.append(' ').append(m);
            }
            if (m.equals("DeepTraceProbe.main"))
            {
                break;                                   // a batch runs main reflectively: stop at our own
            }
            i += 1;
        }
        System.out.println(what + ": frames=" + kept + " rec=" + recFrames + " others:" + names);
    }
}
