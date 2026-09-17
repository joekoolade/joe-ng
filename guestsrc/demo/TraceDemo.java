/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-10
 */
package demo;

/**
 * Stack-trace smoke test. c() &larr; b() &larr; a() &larr; main(); c() catches its own NullPointerException and
 * printStackTrace()s it -- exercising the SAME-METHOD (inline) catch path, whose backtrace is now recorded at
 * the throw site (CAPTURE_TRACE) rather than only on cross-method unwind. The trace resolves each frame to
 * owner/Class.method(SourceFile.java:line) from the demand-compiled line tables.
 */
public class TraceDemo
{
    public static void main(String[] args)
    {
        a();
    }

    static void a()
    {
        b();
    }

    static void b()
    {
        c();
    }

    static void c()
    {
        try
        {
            String s = null;
            int x = s.length();   // NullPointerException
        }
        catch (Exception e)
        {
            e.printStackTrace();  // same-method catch -> printStackTrace() must still show frames
        }
    }
}
