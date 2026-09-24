/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-23
 */

/**
 * {@code Throwable.getStackTrace()} from GUEST code, arm by arm, against a host control.
 *
 * <p>WHY THIS EXISTS. The console launcher reports a discovery failure and then NPEs while REPORTING it:
 * {@code ExceptionUtils.pruneStackTrace} is {@code Arrays.asList(throwable.getStackTrace())}, and line 130
 * of that method is bytecode 14 -- {@code aload_0; getStackTrace; asList} -- so the array was NULL.
 * {@code Arrays$ArrayList.<init>} then {@code requireNonNull}s it. Stock's {@code getStackTrace()} NEVER
 * answers null (it answers a zero-length array when it has nothing), so this is a SILENT WRONG ANSWER on
 * the one path a program uses to find out what went wrong.
 *
 * <p>WHAT EACH ARM DISCRIMINATES, because "it printed something" is not a result here:
 * <ul>
 *   <li>{@code len} separates the three states a caller can be handed -- NULL (the bug), 0 (honest and
 *       harmless), and n (working). A probe that only checked for an exception would pass on all three.</li>
 *   <li>The CROSS-METHOD arm is the condition, not merely the shape: the VM fills {@code bt0..bt7} inside
 *       {@code VM.unwind}, so a same-method catch and a catch across a call are different code paths, and
 *       this file already records a bug that lived in exactly that difference for months.</li>
 *   <li>The frames are PRINTED rather than counted: a walk that mis-steps still returns plausible objects,
 *       and a null ELEMENT inside a non-null array is the failure {@code pruneStackTrace} would hit one
 *       line later at {@code getClassName()}.</li>
 *   <li>{@code setStackTrace} then {@code getStackTrace} pins the override path, which is the branch
 *       {@code getStackTrace} takes FIRST and which no other arm reaches.</li>
 *   <li>A FRESH, never-thrown throwable is the case stock answers with a zero-length array; this VM has
 *       nothing in {@code bt0..bt7} for it, so it is the arm most likely to differ and is stated as such.</li>
 * </ul>
 */
public final class TraceProbe
{
    public static void main(String[] args)
    {
        arm("thrown across a call", caught());
        arm("thrown and caught here", here());
        arm("never thrown", new RuntimeException("fresh"));

        RuntimeException over = new RuntimeException("override");
        StackTraceElement[] fake = new StackTraceElement[] {
            new StackTraceElement("A", "m", "A.java", 7)
        };
        over.setStackTrace(fake);
        arm("after setStackTrace", over);

        System.out.println("TraceProbe done");
    }

    private static void arm(String what, Throwable t)
    {
        StackTraceElement[] st = t.getStackTrace();
        System.out.print(what + ": ");
        if (st == null)
        {
            System.out.println("NULL  <-- stock never answers null");
            return;
        }
        System.out.println("len=" + st.length);
        int i = 0;
        while (i < st.length && i < 6)
        {
            StackTraceElement e = st[i];
            if (e == null)
            {
                System.out.println("    [" + i + "] NULL ELEMENT  <-- a non-null array of nulls");
            }
            else
            {
                System.out.println("    [" + i + "] " + e.getClassName() + "." + e.getMethodName()
                                   + ":" + e.getLineNumber());
            }
            i = i + 1;
        }
    }

    private static Throwable caught()
    {
        try
        {
            level1();
            return null;
        }
        catch (RuntimeException e)
        {
            return e;
        }
    }

    private static void level1()
    {
        level2();
    }

    private static void level2()
    {
        throw new RuntimeException("deep");
    }

    private static Throwable here()
    {
        try
        {
            throw new RuntimeException("local");
        }
        catch (RuntimeException e)
        {
            return e;
        }
    }
}
