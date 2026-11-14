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
package demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probe for running a program handed over as bytes ({@link RunProgram}). QEMU has no network, so the bytes come
 * from the RAMFS ({@code /run/hello.jar}, {@code /run/Hello.class}, built from {@code runsrc/}). The shapes: a jar
 * whose classes refer to each other in both directions, its own interface and a capturing lambda; running the same
 * jar twice; a lone class file; changed bytes under a name already loaded; bytes that are not a program; a second
 * entry point that throws out of main. Then the {@code /run} endpoints ({@link RunPage}) over byte streams: the token,
 * query decoding, the status codes, and the page. The download itself needs a network, so only its refusals are here.
 */
public class RunProbe
{
    private static int failures;

    public static void main(String[] args) throws Exception
    {
        byte[] jar = Files.readAllBytes(Path.of("/run/hello.jar"));
        byte[] cls = Files.readAllBytes(Path.of("/run/Hello.class"));

        RunProgram.Result r = RunProgram.run("hello.jar", jar, null, new String[] { "a", "b" });
        show("jar", r);
        check("jar ran", r.status == RunProgram.OK);
        check("jar main class", "hello.Main".equals(r.mainClass));
        check("jar defined 5", r.defined == 5);
        check("jar greeting", r.output.contains("hello from an uploaded jar"));
        check("jar args", r.output.contains("args 2 a,b"));
        check("jar interface + lambda", r.output.contains("square 3 area 9 scaled 36"));
        check("jar back-reference", r.output.contains("util 42 calls 1"));
        check("jar throw across classes", r.output.contains("caught thrown in Util: deep"));
        check("jar stderr captured", r.output.contains("to stderr"));

        r = RunProgram.run("hello.jar", jar, null, new String[0]);
        show("jar again", r);
        check("rerun ran", r.status == RunProgram.OK);
        check("rerun defined 0", r.defined == 0);
        check("rerun statics kept", r.output.contains("util 42 calls 2"));

        r = RunProgram.run("Hello.class", cls, null, new String[] { "x", "y" });
        show("class", r);
        check("class ran", r.status == RunProgram.OK);
        check("class output", r.output.contains("hello from a class file, args: x y"));

        byte[] changed = new byte[cls.length + 1];
        System.arraycopy(cls, 0, changed, 0, cls.length);
        r = RunProgram.run("Hello.class", changed, null, new String[0]);
        show("changed class", r);
        check("changed refused", r.status == RunProgram.REFUSED && r.output.contains("different bytes"));

        r = RunProgram.run("junk.class", "not a class".getBytes(), null, new String[0]);
        show("junk", r);
        check("junk refused", r.status == RunProgram.REFUSED && r.output.contains("not a class file"));

        r = RunProgram.run("hello.jar", jar, "hello.Nope", new String[0]);
        show("no such main", r);
        check("missing main refused", r.status == RunProgram.REFUSED && r.output.contains("no class hello.Nope"));

        r = RunProgram.run("hello.jar", jar, "hello.Boom", new String[0]);
        show("throwing main", r);
        check("throw status", r.status == RunProgram.THREW && "hello.Boom".equals(r.mainClass));
        check("throw output before", r.output.contains("boom is about to throw"));
        check("throw trace captured", r.output.contains("IllegalStateException: thrown in Util: from Boom.main"));

        http(jar, cls);
        System.out.println(failures == 0 ? "RunProbe PASS" : "RunProbe FAIL " + failures);
    }

    private static void http(byte[] jar, byte[] cls) throws Exception
    {
        String token = Files.readString(Path.of("/etc/boot.token")).trim();
        String tok = "X-Boot-Token: " + token + "\r\n";
        expect("no token", call("PUT /run/hello.jar", "Content-Length: " + jar.length + "\r\n", jar), "401", null);
        expect("wrong token", call("PUT /run/hello.jar", "X-Boot-Token: nope\r\nContent-Length: " + jar.length
                + "\r\n", jar), "401", null);
        expect("put jar", call("PUT /run/hello.jar?args=a%2Cb+c", tok + "Content-Length: " + jar.length + "\r\n", jar),
                "200", "args 2 a,b,c");
        expect("put class", call("PUT /run/Hello.class?args=one%20two", tok + "Content-Length: " + cls.length + "\r\n",
                cls), "200", "args: one two");
        expect("put main=", call("PUT /run/hello.jar?main=hello.Boom", tok + "Content-Length: " + jar.length + "\r\n",
                jar), "200", "main threw");
        expect("put junk", call("PUT /run/x.class", tok + "Content-Length: 3\r\n", "abc".getBytes()), "422",
                "not a class file");
        expect("no length", call("PUT /run/x.class", tok, "abc".getBytes()), "411", null);
        expect("short body", call("PUT /run/x.class", tok + "Content-Length: 100\r\n", "abc".getBytes()), "400",
                "after 3 of 100");
        expect("post no url", call("POST /run", tok + "Content-Length: 0\r\n", null), "400", "needs url=");
        expect("post ftp", call("POST /run?url=ftp%3A%2F%2Fh%2Fa.jar", tok + "Content-Length: 0\r\n", null), "502",
                "only http and https");
        expect("other path", call("GET /nope", null, null), "(not handled)", null);
        String page = call("GET /run", null, null);
        expect("page", page, "200", "<h1>Run a program</h1>");
        check("page shows the last run's output", page.contains("not run: not a class file (no CAFEBABE)"));
        check("page history", page.contains("<td>Hello.class</td><td>Hello</td><td>one two</td><td>returned</td>"));
    }

    private static String call(String requestLine, String headers, byte[] body) throws Exception
    {
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write((requestLine + " HTTP/1.1\r\n" + (headers == null ? "" : headers) + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        if (body != null)
        {
            req.write(body);
        }
        Http.Request r = Http.read(new ByteArrayInputStream(req.toByteArray()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!RunPage.handle(r, out, java.time.ZoneId.of("UTC"), "now"))
        {
            return "(not handled)";
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void expect(String what, String response, String status, String contains)
    {
        String first = response.indexOf("\r\n") >= 0 ? response.substring(0, response.indexOf("\r\n")) : response;
        boolean ok = first.contains(status) && (contains == null || response.contains(contains));
        if (!ok)
        {
            failures = failures + 1;
        }
        String body = response.indexOf("\r\n\r\n") >= 0 ? response.substring(response.indexOf("\r\n\r\n") + 4) : "";
        String tail = body.trim();
        int nl = tail.lastIndexOf('\n');
        System.out.println((ok ? "ok   " : "FAIL ") + what + ": " + first + " | " + (nl >= 0 ? tail.substring(nl + 1) : tail));
    }

    private static void show(String what, RunProgram.Result r)
    {
        System.out.println("--- " + what + ": status " + r.status + " main " + r.mainClass + " defined " + r.defined);
        System.out.print(r.output);
        System.out.println("---");
    }

    private static void check(String what, boolean ok)
    {
        if (!ok)
        {
            failures = failures + 1;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
