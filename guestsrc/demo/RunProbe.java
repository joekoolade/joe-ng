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
        check("jar defined 8", r.defined == 8);
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

        exits(jar);
        threads(jar);
        stops(jar);
        http(jar, cls);
        System.out.println(failures == 0 ? "RunProbe PASS" : "RunProbe FAIL " + failures);
    }

    /** System.exit and Runtime.halt end the run, not the board: every arm after the first proves the VM is still up. */
    private static void exits(byte[] jar)
    {
        RunProgram.Result r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "exit" });
        show("exit", r);
        check("exit status", r.status == RunProgram.EXITED && r.exitStatus == 3 && "main".equals(r.exitThread));
        check("exit stops main", r.output.contains("quit: exit") && !r.output.contains("still running"));
        check("exit report", r.output.contains("[System.exit(3) called by thread \"main\""));

        r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "halt" });
        show("halt", r);
        check("halt status", r.status == RunProgram.EXITED && r.exitStatus == 4 && "Runtime.halt".equals(r.exitHow));
        check("halt report", r.output.contains("[Runtime.halt(4) called by thread \"main\""));
        check("halt stops main", !r.output.contains("still running"));

        r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "catch" });
        show("catch", r);
        check("catch (Exception) does not stop an exit", r.status == RunProgram.EXITED && r.exitStatus == 6
                && !r.output.contains("caught the exit") && !r.output.contains("still running"));

        r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "thread" });
        show("thread", r);
        check("thread exit recorded", r.status == RunProgram.EXITED && r.exitStatus == 5
                && "quitter".equals(r.exitThread));
        check("thread exit leaves main running", r.output.contains("main carries on"));
        check("thread exit is quiet", !r.output.contains("Exception in thread"));

        r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "hook" });
        show("hook", r);
        check("hook: exit 0", r.status == RunProgram.EXITED && r.exitStatus == 0);
        check("hook not run", r.output.contains("hook registered") && !r.output.contains("hook ran"));

        r = RunProgram.run("hello.jar", jar, "hello.Quit", new String[] { "hook" });
        show("hook again", r);
        check("hooks still register after an exit", r.output.contains("hook registered") && r.exitStatus == 0
                && r.status == RunProgram.EXITED);

        r = RunProgram.run("hello.jar", jar, null, new String[] { "after" });
        show("after the exits", r);
        check("a run after the exits", r.status == RunProgram.OK && r.output.contains("args 1 after"));
    }

    /** main on its own thread: a caller that stops waiting, two runs at once, and output routed by thread. */
    private static void threads(byte[] jar) throws Exception
    {
        RunProgram.Result a = RunProgram.start("hello.jar", jar, "hello.Slow", new String[] { "a", "1500" });
        check("a still running after 200 ms", !a.await(200L) && a.status == RunProgram.RUNNING);
        System.out.println("probe: printing while run a runs");
        RunProgram.Result b = RunProgram.start("hello.jar", jar, "hello.Slow", new String[] { "b", "300" });
        check("b finishes while a runs", b.await(0L) && b.status == RunProgram.OK && !a.done());
        show("b", b);
        check("b's output", b.output.contains("slow b start on thread main") && b.output.contains("slow b end"));
        check("b has none of a's output", !b.output.contains("slow a"));
        check("a finishes", a.await(0L) && a.status == RunProgram.OK);
        show("a", a);
        check("a's output", a.output.contains("slow a start") && a.output.contains("slow a end"));
        check("a has none of b's output", !a.output.contains("slow b"));
        check("a has none of the caller's output", !a.output.contains("probe: printing"));
        Thread.sleep(1500L);
        check("output after main returned", a.text().contains("slow a late, from a thread main left running")
                && b.text().contains("slow b late"));
        check("ids", b.id == a.id + 1);
    }

    /**
     * Stopping a run, each way a program can resist it. On QEMU the loops yield (no timer ticks there), which
     * changes nothing about what stops them: they ignore interrupts, so the stop must be forced.
     */
    private static void stops(byte[] jar) throws Exception
    {
        RunProgram.Result r = spin(jar, "spin");
        String what = RunProgram.stop(r, 300L);
        show("stop spin: " + what, r);
        check("spin: stopped, forced", r.done() && r.status == RunProgram.STOPPED && r.forced && !r.killed);
        check("spin: its finally ran", r.output.contains("spin: finally ran"));
        check("spin: report", r.output.contains("[stopped (forced);"));
        check("spin: its monitor is free", lockFree(Class.forName("hello.Spin")));

        r = spin(jar, "sleep");
        what = RunProgram.stop(r, 3000L);
        show("stop sleep: " + what, r);
        check("sleep: stopped by the interrupt alone", r.status == RunProgram.STOPPED && !r.forced
                && r.output.contains("spin: sleep interrupted"));

        r = spin(jar, "polite");
        what = RunProgram.stop(r, 3000L);
        show("stop polite: " + what, r);
        check("polite: ended on the interrupt", r.status == RunProgram.STOPPED && !r.forced
                && r.output.contains("polite, saw the interrupt"));

        r = spin(jar, "threads");
        what = RunProgram.stop(r, 300L);
        show("stop threads: " + what, r);
        check("threads: all stopped", r.status == RunProgram.STOPPED && r.forced && r.mark.liveThreads().isEmpty());
        check("threads: every finally ran", r.text().contains("thread 0 finally") && r.text().contains("thread 1 finally")
                && r.text().contains("thread 2 finally") && r.text().contains("spin: finally ran"));

        r = spin(jar, "jdk");
        what = RunProgram.stop(r, 300L);
        show("stop jdk: " + what, r);
        check("jdk: stopped", r.status == RunProgram.STOPPED && r.forced);

        r = spin(jar, "catch");
        what = RunProgram.stop(r, 300L);
        show("stop catch: " + what, r);
        check("catch: it caught the stop", r.text().contains("caught jdk.internal.misc.MetalRunExit$Stopped"));
        check("catch: ended anyway", r.done() && r.status == RunProgram.STOPPED && r.killed
                && r.mark.liveThreads().isEmpty());

        check("nothing left to stop", RunProgram.stop(r, 0L).contains("no live thread"));
        r = RunProgram.run("hello.jar", jar, null, new String[] { "after", "stops" });
        check("a run after the stops", r.status == RunProgram.OK && r.output.contains("args 2 after,stops"));
    }

    /** Start {@code hello.Spin <mode> yield} and wait until it has said it started. */
    private static RunProgram.Result spin(byte[] jar, String mode) throws Exception
    {
        RunProgram.Result r = RunProgram.start("hello.jar", jar, "hello.Spin", new String[] { mode, "yield" });
        int i = 0;
        while (!r.text().contains("spin: " + mode + " started") && i < 100)
        {
            Thread.sleep(50L);
            i = i + 1;
        }
        return r;
    }

    /** Whether another thread can take {@code lock}'s monitor within two seconds. */
    private static boolean lockFree(Object lock) throws Exception
    {
        boolean[] got = new boolean[1];
        Thread t = new Thread(() ->
        {
            synchronized (lock)
            {
                got[0] = true;
            }
        }, "lock-probe");
        t.start();
        t.join(2000L);
        return got[0];
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
        expect("bad wait", call("PUT /run/hello.jar?wait=soon", tok + "Content-Length: " + jar.length + "\r\n", jar),
                "400", "wait=");
        String slow = call("PUT /run/hello.jar?main=hello.Slow&args=h+1500&wait=0.2",
                tok + "Content-Length: " + jar.length + "\r\n", jar);
        expect("still running", slow, "202", "still running after");
        int at = slow.indexOf("GET /run/");
        String id = at < 0 ? "0" : slow.substring(at + 9, slow.indexOf(' ', at + 9)).trim();
        expect("follow, running", call("GET /run/" + id, null, null), "202", "slow h start");
        String done = call("GET /run/" + id, null, null);
        int tries = 0;
        while (done.startsWith("HTTP/1.0 202") && tries < 100)
        {
            Thread.sleep(200L);
            done = call("GET /run/" + id, null, null);
            tries = tries + 1;
        }
        expect("follow, finished", done, "200", "[main returned");
        check("follow has the whole output", done.contains("slow h start") && done.contains("slow h end"));
        Thread.sleep(1500L);
        expect("follow, after main", call("GET /run/" + id, null, null), "200", "slow h late");
        String spun = call("PUT /run/hello.jar?main=hello.Spin&args=spin+yield&wait=0.3",
                tok + "Content-Length: " + jar.length + "\r\n", jar);
        expect("spin running", spun, "202", "still running");
        int sat = spun.indexOf("GET /run/");
        String sid = sat < 0 ? "0" : spun.substring(sat + 9, spun.indexOf(' ', sat + 9)).trim();
        expect("stop, no token", call("POST /run/" + sid + "/stop", "Content-Length: 0\r\n", null), "401", null);
        expect("stop, bad grace", call("POST /run/" + sid + "/stop?grace=x", tok + "Content-Length: 0\r\n", null),
                "400", "grace=");
        expect("stop", call("POST /run/" + sid + "/stop?grace=0.2", tok + "Content-Length: 0\r\n", null), "200",
                "stopped (forced)");
        expect("stop again", call("POST /run/" + sid + "/stop", tok + "Content-Length: 0\r\n", null), "409",
                "nothing running");
        expect("stop, no such run", call("POST /run/99999/stop", tok + "Content-Length: 0\r\n", null), "404", null);
        expect("no such run", call("GET /run/99999", null, null), "404", "no run 99999");
        expect("not a run number", call("GET /run/abc", null, null), "404", null);
        String page = call("GET /run", null, null);
        expect("page", page, "200", "<h1>Run a program</h1>");
        check("page shows the last run's output", page.contains("spin: finally ran") && !page.contains("data-running"));
        check("page: a stopped run", page.contains("<td>stopped (forced)</td>"));
        check("page links the run", page.contains("<a href=\"/run/" + id + "\">#" + id + "</a>"));
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
