/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-02
 */
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.nio.charset.UnsupportedCharsetException;

/**
 * A catch clause naming a class this VM cannot load must not catch anything else.
 *
 * <p>The on-metal JIT registered such a handler with catch type {@code typeOfClass(cp)}, which is 0 for an
 * unregistered class -- and 0 is the encoding of CATCH-ALL. So {@code catch (NoSuchFileException e)}
 * (under the DENIED java/nio/file/ prefix here) caught every exception that unwound into it, silently running the wrong handler.
 *
 * <p>The throws come from a CALLEE ({@link #boom}), so the handler is found by {@code VMUnwind.findHandlerIn}
 * through the JIT handler table -- the path the defect was in. The same-method arm covers the inline test,
 * whose miss falls back to that same table. Every arm prints which handler ran; the host oracle is a stock JVM
 * running this file.
 */
public class CatchDeniedProbe
{
    private static int failures;

    private static void check(String label, String got, String want)
    {
        if (got.equals(want))
        {
            System.out.println("ok   " + label + " = " + got);
        }
        else
        {
            failures++;
            System.out.println("FAIL " + label + " = " + got + " (want " + want + ")");
        }
    }

    private static void boom() throws IOException
    {
        throw new IllegalStateException("boom");
    }

    private static String crossMethod()
    {
        try
        {
            boom();
            return "no throw";
        }
        catch (NoSuchFileException e)
        {
            return "WRONG: the NoSuchFileException handler";
        }
        catch (IllegalStateException e)
        {
            return "IllegalStateException handler";
        }
        catch (IOException e)
        {
            return "WRONG: the IOException handler";
        }
    }

    private static String sameMethod(int n) throws IOException
    {
        try
        {
            if (n > 0)
            {
                throw new IllegalStateException("inline");
            }
            boom();
            return "no throw";
        }
        catch (NoSuchFileException e)
        {
            return "WRONG: the NoSuchFileException handler";
        }
        catch (RuntimeException e)
        {
            return "RuntimeException handler";
        }
    }

    private static String escapes()
    {
        try
        {
            return inner();
        }
        catch (IllegalStateException e)
        {
            return "escaped to the caller";
        }
    }

    private static String inner()
    {
        try
        {
            boom();
            return "no throw";
        }
        catch (NoSuchFileException e)
        {
            return "WRONG: caught by the NoSuchFileException handler";
        }
        catch (IOException e)
        {
            return "WRONG: the IOException handler";
        }
    }

    private static String withFinally()
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            try
            {
                boom();
            }
            catch (NoSuchFileException e)
            {
                sb.append("WRONG ");
            }
            finally
            {
                sb.append("finally ");
            }
        }
        catch (IllegalStateException | IOException e)
        {
            sb.append("outer");
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception
    {
        check("cross-method, denied catch first", crossMethod(), "IllegalStateException handler");
        check("same-method inline throw", sameMethod(1), "RuntimeException handler");
        check("same-method via callee", sameMethod(0), "RuntimeException handler");
        check("no matching handler: escapes", escapes(), "escaped to the caller");
        check("finally still runs, then outer", withFinally(), "finally outer");

        String fn;
        try
        {
            Charset.forName("no-such-charset");
            fn = "no throw";
        }
        catch (UnsupportedCharsetException e)
        {
            fn = "UnsupportedCharsetException " + e.getCharsetName();
        }
        check("Charset.forName unknown", fn, "UnsupportedCharsetException no-such-charset");

        String gb;
        try
        {
            "x".getBytes("no-such-charset");
            gb = "no throw";
        }
        catch (UnsupportedEncodingException e)
        {
            gb = "UnsupportedEncodingException " + e.getMessage();
        }
        check("String.getBytes unknown", gb, "UnsupportedEncodingException no-such-charset");

        String nn;
        try
        {
            Charset.forName(null);
            nn = "no throw";
        }
        catch (IllegalArgumentException e)
        {
            nn = e.getClass().getName();
        }
        check("Charset.forName(null)", nn, "java.lang.IllegalArgumentException");

        System.out.println("CatchDeniedProbe done, failures=" + failures);
    }
}
