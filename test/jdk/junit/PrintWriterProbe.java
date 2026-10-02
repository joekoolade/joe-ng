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

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Wrapping {@code System.out} in a {@code PrintWriter} or an {@code OutputStreamWriter} -- the commonest way
 * Java code gets a Writer onto the console.
 *
 * <p>Stock JDK 26 {@code PrintWriter(OutputStream, boolean)} and {@code OutputStreamWriter(OutputStream)} both
 * ask {@code out instanceof PrintStream ps ? ps.charset() : Charset.defaultCharset()}, and
 * {@code PrintWriter.checkError()} forwards to {@code PrintStream.checkError()}. The {@code PrintStream} overlay
 * declared none of those, so a member a name-winning overlay omits CEASES TO EXIST and the call resolves
 * nowhere.
 *
 * <p>The CONTROL arms run FIRST and wrap a {@code ByteArrayOutputStream}, which is not a {@code PrintStream}, so
 * they take the {@code defaultCharset()} branch and never touch the gap. If they pass and the next arm halts,
 * the halt is isolated to the {@code PrintStream} branch rather than to {@code PrintWriter} itself.
 *
 * <p>Every arm reports a LENGTH and the text it read back from a capture buffer, so the probe asserts the bytes
 * ARRIVED -- a writer that accepted text and dropped it would look like a working call otherwise. The console
 * arms print a visible marker line; their arm line is about the call returning.
 */
public class PrintWriterProbe
{
    private static int failures;

    private static void check(String label, String got, String want)
    {
        got = got.replace("\n", "\\n");
        want = want.replace("\n", "\\n");
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

    public static void main(String[] args) throws Exception
    {
        // CONTROL: not a PrintStream, so stock takes Charset.defaultCharset() and never asks for charset().
        ByteArrayOutputStream b1 = new ByteArrayOutputStream();
        PrintWriter pw1 = new PrintWriter(b1, true);
        pw1.println("ctl");
        check("pw over BAOS", b1.toString() + "|" + b1.size(), "ctl\n|4");
        check("pw over BAOS checkError", String.valueOf(pw1.checkError()), "false");

        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
        Writer w2 = new OutputStreamWriter(b2);
        w2.write("osw");
        w2.flush();
        check("osw over BAOS", b2.toString() + "|" + b2.size(), "osw|3");

        // A PrintStream over a capture buffer: the PrintStream branch, with the bytes still inspectable.
        ByteArrayOutputStream b3 = new ByteArrayOutputStream();
        PrintStream ps3 = new PrintStream(b3, true);
        PrintWriter pw3 = new PrintWriter(ps3, true);
        pw3.println("via-ps");
        check("pw over PrintStream", b3.toString() + "|" + b3.size(), "via-ps\n|7");
        check("pw over PrintStream checkError", String.valueOf(pw3.checkError()), "false");

        ByteArrayOutputStream b4 = new ByteArrayOutputStream();
        PrintStream ps4 = new PrintStream(b4, true);
        Writer w4 = new OutputStreamWriter(ps4);
        w4.write("osw-ps");
        w4.flush();
        check("osw over PrintStream", b4.toString() + "|" + b4.size(), "osw-ps|6");

        // write(int) on a WRAPPING PrintStream must reach the wrapped stream, not the console.
        ByteArrayOutputStream b7 = new ByteArrayOutputStream();
        PrintStream ps7 = new PrintStream(b7, true);
        ps7.write('Q');
        ps7.write('R');
        check("ps.write(int) to wrapped", b7.toString() + "|" + b7.size(), "QR|2");

        // flush() must reach the wrapped stream: a BufferedOutputStream holds bytes until flushed.
        ByteArrayOutputStream b8 = new ByteArrayOutputStream();
        PrintStream ps8 = new PrintStream(new java.io.BufferedOutputStream(b8), false);
        ps8.print("buf");
        int before = b8.size();
        ps8.flush();
        check("ps.flush reaches wrapped", before + "->" + b8.size(), "0->3");

        // charset(): the default, and an explicit one honoured by the encoder (one byte for e-acute).
        check("ps charset default", new PrintStream(new ByteArrayOutputStream(), true).charset().name(), "UTF-8");
        ByteArrayOutputStream b9 = new ByteArrayOutputStream();
        PrintStream ps9 = new PrintStream(b9, true, java.nio.charset.StandardCharsets.ISO_8859_1);
        ps9.print("\u00e9");
        check("ps latin1 charset", ps9.charset().name() + " len=" + b9.size() + " b0=" + (b9.toByteArray()[0] & 0xFF),
                "ISO-8859-1 len=1 b0=233");
        ByteArrayOutputStream b10 = new ByteArrayOutputStream();
        PrintStream ps10 = new PrintStream(b10, true);
        ps10.print("\u00e9");
        check("ps utf8 default bytes", "len=" + b10.size() + " b0=" + (b10.toByteArray()[0] & 0xFF), "len=2 b0=195");
        PrintStream ps11 = new PrintStream(new ByteArrayOutputStream(), true, "UTF-8");
        check("ps ctor by name", ps11.charset().name(), "UTF-8");
        String bad;
        try
        {
            new PrintStream(new ByteArrayOutputStream(), true, "no-such-charset");
            bad = "no throw";
        }
        catch (java.io.UnsupportedEncodingException e)
        {
            bad = "UnsupportedEncodingException";
        }
        check("ps ctor bad name", bad, "UnsupportedEncodingException");

        // checkError: a wrapped stream that throws must set the flag (stock swallows the IOException).
        PrintStream ps12 = new PrintStream(new java.io.OutputStream()
        {
            public void write(int b) throws java.io.IOException
            {
                throw new java.io.IOException("boom");
            }
        }, true);
        ps12.print("x");
        check("ps checkError after throw", String.valueOf(ps12.checkError()), "true");
        check("System.out checkError", String.valueOf(System.out.checkError()), "false");

        // The idioms themselves, on the real console.
        PrintWriter pw5 = new PrintWriter(System.out, true);
        pw5.println("[marker] PrintWriter(System.out) printed this line");
        check("pw over System.out checkError", String.valueOf(pw5.checkError()), "false");

        Writer w6 = new OutputStreamWriter(System.out);
        w6.write("[marker] OutputStreamWriter(System.out) printed this line\n");
        w6.flush();
        check("osw over System.out", "returned", "returned");

        System.out.println("PrintWriterProbe done, failures=" + failures);
    }
}
