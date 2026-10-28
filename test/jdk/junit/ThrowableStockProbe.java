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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * {@code java.lang.Throwable}'s stock protocol, now that the overlay is the JDK 26 source. The hand-written
 * overlay differed from stock in ways each arm here reads: {@code initCause} never refused a second cause,
 * {@code printStackTrace(PrintWriter)} ignored the writer, a false {@code writableStackTrace} or an overridden
 * {@code fillInStackTrace} still got a trace at the throw, and an exception the VM raises itself (an implicit NPE)
 * could not record a suppressed exception. Frames are printed by name only down to this probe's {@code main}, so
 * a batch (which runs {@code main} reflectively) prints the same as a solo boot.
 */
public final class ThrowableStockProbe
{
    static final class Cheap extends RuntimeException
    {
        Cheap(String m)
        {
            super(m);
        }

        @Override
        public synchronized Throwable fillInStackTrace()
        {
            return this;
        }
    }

    static final class Res implements AutoCloseable
    {
        @Override
        public void close() throws IOException
        {
            throw new IOException("close failed");
        }
    }

    private interface Arm
    {
        Object run() throws Throwable;
    }

    private static void arm(String what, Arm a)
    {
        String r;
        try
        {
            r = String.valueOf(a.run());
        }
        catch (Throwable t)
        {
            r = "THREW " + t;
        }
        System.out.println(what + " = " + r);
    }

    private static int len(String s)
    {
        return s.length();
    }

    private static NullPointerException implicitNpe()
    {
        try
        {
            len(null);
        }
        catch (NullPointerException e)
        {
            return e;
        }
        return null;
    }

    public static void main(String[] args)
    {
        arm("implicit NPE getCause", () -> implicitNpe().getCause());
        arm("implicit NPE initCause", () -> implicitNpe().initCause(new IllegalStateException("c")).getCause());
        arm("initCause twice", () ->
        {
            RuntimeException e = new RuntimeException("x");
            e.initCause(new IllegalStateException("first"));
            e.initCause(new IllegalStateException("second"));
            return "no throw";
        });
        arm("implicit NPE addSuppressed", () ->
        {
            NullPointerException e = implicitNpe();
            e.addSuppressed(new IllegalStateException("s"));
            return e.getSuppressed().length + " " + e.getSuppressed()[0].getMessage();
        });
        arm("try-with-resources over an implicit NPE", () ->
        {
            try (Res r = new Res())
            {
                len(null);
                return "no throw";
            }
            catch (NullPointerException e)
            {
                Throwable[] s = e.getSuppressed();
                return s.length + " " + (s.length > 0 ? s[0] : "-");
            }
        });
        arm("implicit NPE has frames", () ->
        {
            StackTraceElement[] st = implicitNpe().getStackTrace();
            return st.length > 0 ? st[0].getMethodName() + " < " + st[1].getMethodName() : "none";
        });
        arm("writableStackTrace=false, thrown", () ->
        {
            try
            {
                throw new RuntimeException("quiet", null, false, false)
                {
                };
            }
            catch (RuntimeException e)
            {
                e.setStackTrace(new StackTraceElement[] { new StackTraceElement("A", "b", "A.java", 1) });
                return e.getStackTrace().length + " " + e.getSuppressed().length;
            }
        });
        arm("fillInStackTrace overridden, thrown", () ->
        {
            try
            {
                throw new Cheap("cheap");
            }
            catch (Cheap e)
            {
                return e.getStackTrace().length;
            }
        });
        arm("setStackTrace then get", () ->
        {
            RuntimeException e = new RuntimeException("s");
            e.setStackTrace(new StackTraceElement[] { new StackTraceElement("A", "b", "A.java", 7) });
            StackTraceElement[] st = e.getStackTrace();
            st[0] = null;                                 // a copy: must not change the next read
            return e.getStackTrace().length + " " + e.getStackTrace()[0];
        });
        arm("setStackTrace null element", () ->
        {
            new RuntimeException().setStackTrace(new StackTraceElement[] { null });
            return "no throw";
        });
        arm("refill drops setStackTrace", () ->
        {
            RuntimeException e = new RuntimeException("s");
            e.setStackTrace(new StackTraceElement[] { new StackTraceElement("A", "b", "A.java", 7) });
            e.fillInStackTrace();
            return e.getStackTrace()[0].getMethodName();
        });
        arm("printStackTrace(PrintWriter)", () ->
        {
            RuntimeException outer = new RuntimeException("outer", new IllegalStateException("inner"));
            outer.addSuppressed(new IOException("side"));
            StringWriter sw = new StringWriter();
            outer.printStackTrace(new PrintWriter(sw));
            StringBuilder b = new StringBuilder();
            for (String line : sw.toString().split("\n"))
            {
                if (!line.startsWith("\tat ") && !line.startsWith("\t\tat ") && !line.contains("... "))
                {
                    b.append(" | ").append(line);
                }
            }
            return b.toString();
        });
        arm("toString / getLocalizedMessage", () ->
        {
            Throwable t = new Throwable(new IllegalStateException("why"));
            return t + " / " + t.getLocalizedMessage() + " / " + new Error().getMessage();
        });
        arm("addSuppressed self and null", () ->
        {
            RuntimeException e = new RuntimeException("e");
            String a;
            try
            {
                e.addSuppressed(e);
                a = "no throw";
            }
            catch (IllegalArgumentException x)
            {
                a = x.getMessage();
            }
            String b;
            try
            {
                e.addSuppressed(null);
                b = "no throw";
            }
            catch (NullPointerException x)
            {
                b = x.getMessage();
            }
            return a + " / " + b;
        });
        System.out.println("ThrowableStockProbe done");
    }
}
