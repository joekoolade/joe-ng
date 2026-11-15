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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Run a {@code .jar} or {@code .class} on the board from the web server, and show what it printed
 * ({@link RunProgram} does the running):
 *
 * <ul>
 * <li>{@code PUT /run/<file>.jar} or {@code PUT /run/<file>.class}: the body is the program, sent by the browser
 *     or e.g. {@code curl -T Hello.class -H "X-Boot-Token: ..." "http://<pi>/run/Hello.class?args=a+b"}.</li>
 * <li>{@code POST /run?url=<http(s) url>}: the board downloads the program itself, then runs it.</li>
 * <li>{@code GET /run/<n>}: run n's output so far (no token: the page shows the same).</li>
 * <li>{@code GET /run}: a page to do either from a browser, with the last run's output and this boot's runs.</li>
 * </ul>
 *
 * <p>Both runs take {@code args=} (space-separated, URL-encoded), {@code main=} (a class to run instead of the
 * jar's {@code Main-Class}) and {@code wait=} (seconds to wait for {@code main}, default {@value #WAIT_DEFAULT_S},
 * at most {@value #WAIT_MAX_S}) in the query, and answer {@code text/plain}: everything the program printed to
 * {@code System.out} and {@code System.err}, then a status line. 200 when {@code main} finished (returned, threw,
 * or called {@code System.exit}, which ends the run and not the board); 202 when it is still running after the
 * wait -- it goes on, on its own thread, and {@code GET /run/<n>} has its output -- 422 when the program was refused
 * before {@code main}, 502 when a download failed. Running a program is full control of the board, so both need
 * the {@code X-Boot-Token} the boot-file endpoints take.
 *
 * <p>The server answers one connection at a time, but not for longer than the wait: a run that is still going no
 * longer holds it.
 */
final class RunPage
{
    private RunPage()
    {
    }

    private static final String RUN = "/run";

    /** The largest program accepted, uploaded or downloaded. */
    static final int MAX_PROGRAM = 32 * 1024 * 1024;

    /** Connect and read timeouts for a download. */
    private static final int DOWNLOAD_TIMEOUT_MS = 30_000;

    /** Seconds a run request waits for main when it gives no {@code wait=}, and the most it may ask for. */
    static final int WAIT_DEFAULT_S = 30;

    static final int WAIT_MAX_S = 600;

    /** How many runs the page lists, and {@code GET /run/<n>} can still answer. */
    private static final int HISTORY = 20;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One run, for the page. */
    private static final class Run
    {
        final String when;

        final String what;

        final String args;

        final RunProgram.Result result;

        Run(String when, String what, String args, RunProgram.Result result)
        {
            this.when = when;
            this.what = what;
            this.args = args;
            this.result = result;
        }
    }

    /** This boot's runs, newest first. */
    private static final List<Run> RUNS = new ArrayList<>();

    /** Answer {@code req} if it is a {@code /run} request; false when it is not (the caller serves it). */
    static boolean handle(Http.Request req, OutputStream out, ZoneId zone, String now) throws IOException
    {
        String m = req.method;
        String p = req.path;
        if ((p.equals(RUN) || p.equals(RUN + "/")) && m.equals("GET"))
        {
            Http.respond(out, "200 OK", "text/html; charset=utf-8", render(now).getBytes(StandardCharsets.UTF_8));
            return true;
        }
        if (p.startsWith(RUN + "/") && m.equals("GET"))
        {
            show(p.substring(RUN.length() + 1), out);
            return true;
        }
        boolean upload = p.startsWith(RUN + "/") && m.equals("PUT");
        boolean fetch = p.equals(RUN) && m.equals("POST");
        if (!upload && !fetch)
        {
            return false;
        }
        if (!BootUpdate.authorized(req, out, "programs from the network"))
        {
            return true;
        }
        String args = param(req, "args");
        String main = param(req, "main");
        String name;
        byte[] program;
        if (upload)
        {
            name = p.substring(RUN.length() + 1);
            program = readBody(req, out);
            if (program == null)
            {
                return true;
            }
        }
        else
        {
            String url = param(req, "url");
            if (url.isEmpty())
            {
                Http.text(out, "400 Bad Request", "POST /run needs url=<http or https url of a .jar or .class>");
                return true;
            }
            name = url;
            try
            {
                program = download(url);
            }
            catch (Exception e)
            {
                System.out.println("run: download " + url + " failed: " + e);
                Http.text(out, "502 Bad Gateway", "download failed: " + e);
                return true;
            }
        }
        if (!name.endsWith(".jar") && !name.endsWith(".class") && !upload)
        {
            name = name + (looksLikeJar(program) ? ".jar" : ".class");
        }
        long waitMs = waitMs(param(req, "wait"));
        if (waitMs < 0L)
        {
            Http.text(out, "400 Bad Request", "wait= is seconds, 0 to " + WAIT_MAX_S);
            return true;
        }
        System.out.println("run: " + name + ", " + program.length + " bytes");
        RunProgram.Result r = RunProgram.start(name, program, main, split(args));
        record(new Run(WHEN.format(ZonedDateTime.now(zone)), name, args, r));
        boolean finished = waitMs == 0L ? r.done() : r.await(waitMs);
        answer(r, finished, out);
        return true;
    }

    /** {@code wait=} in milliseconds: the default when blank, -1 when it is not a number of seconds in range. */
    static long waitMs(String wait)
    {
        if (wait.isEmpty())
        {
            return WAIT_DEFAULT_S * 1000L;
        }
        try
        {
            double s = Double.parseDouble(wait);
            if (!(s >= 0.0) || s > WAIT_MAX_S)
            {
                return -1L;
            }
            return (long) (s * 1000.0);
        }
        catch (NumberFormatException e)
        {
            return -1L;
        }
    }

    /** {@code GET /run/<n>}: that run's output so far, 200 once main has finished and 202 while it runs. */
    private static void show(String n, OutputStream out) throws IOException
    {
        RunProgram.Result r = null;
        try
        {
            int id = Integer.parseInt(n);
            for (Run run : RUNS)
            {
                if (run.result.id == id)
                {
                    r = run.result;
                }
            }
        }
        catch (NumberFormatException e)
        {
            r = null;
        }
        if (r == null)
        {
            Http.text(out, "404 Not Found", "no run " + n + " (this boot's last " + HISTORY + " runs are kept)");
            return;
        }
        answer(r, r.done(), out);
    }

    /** The run's output: all of it once main has finished, else what it printed so far and how to follow it. */
    private static void answer(RunProgram.Result r, boolean finished, OutputStream out) throws IOException
    {
        String status;
        String body;
        if (finished)
        {
            status = r.status == RunProgram.REFUSED ? "422 Unprocessable Content" : "200 OK";
            body = r.text();                        // live: a thread main left running may print after the report
        }
        else
        {
            status = "202 Accepted";
            body = r.text() + "\n[run #" + r.id + " still running after " + r.elapsedMs() / 1000L
                    + " s: GET /run/" + r.id + " for its output]\n";
        }
        Http.respond(out, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    /** A query parameter, URL-decoded; "" when absent. */
    static String param(Http.Request req, String name)
    {
        for (String part : req.query.split("&"))
        {
            if (part.startsWith(name + "="))
            {
                return URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** {@code args} split on runs of spaces; no arguments for a blank string. */
    static String[] split(String args)
    {
        String t = args.trim();
        return t.isEmpty() ? new String[0] : t.split(" +");
    }

    private static boolean looksLikeJar(byte[] b)
    {
        return b.length >= 2 && b[0] == 'P' && b[1] == 'K';
    }

    /** The request body, all {@code Content-Length} bytes of it; null after answering when it cannot be read. */
    private static byte[] readBody(Http.Request req, OutputStream out) throws IOException
    {
        long len = req.contentLength();
        if (len < 0)
        {
            Http.text(out, "411 Length Required", "send the program with a Content-Length");
            return null;
        }
        if (len > MAX_PROGRAM)
        {
            Http.text(out, "413 Content Too Large", "a program is at most " + MAX_PROGRAM + " bytes");
            return null;
        }
        byte[] b = new byte[(int) len];
        InputStream in = req.body();
        int have = 0;
        while (have < b.length)
        {
            int r = in.read(b, have, b.length - have);
            if (r < 0)
            {
                break;
            }
            have = have + r;
        }
        if (have < b.length)
        {
            Http.text(out, "400 Bad Request", "the body ended after " + have + " of " + len + " bytes");
            return null;
        }
        return b;
    }

    /** GET {@code url} (http or https), following no redirects; the body, at most {@link #MAX_PROGRAM} bytes. */
    static byte[] download(String url) throws Exception
    {
        URI uri = new URI(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https"))
        {
            throw new IOException("only http and https urls are fetched, not " + url);
        }
        long t0 = System.nanoTime();
        URLConnection c = uri.toURL().openConnection();
        c.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
        c.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
        if (c instanceof HttpURLConnection h)
        {
            int code = h.getResponseCode();
            if (code != 200)
            {
                throw new IOException("HTTP " + code + " " + h.getResponseMessage());
            }
        }
        InputStream in = c.getInputStream();
        try
        {
            byte[] b = in.readNBytes(MAX_PROGRAM + 1);
            if (b.length > MAX_PROGRAM)
            {
                throw new IOException("more than " + MAX_PROGRAM + " bytes");
            }
            System.out.println("run: downloaded " + url + ", " + b.length + " bytes in "
                    + (System.nanoTime() - t0) / 1_000_000L + " ms");
            return b;
        }
        finally
        {
            in.close();
        }
    }

    private static void record(Run run)
    {
        RUNS.add(0, run);
        if (RUNS.size() > HISTORY)
        {
            RUNS.remove(RUNS.size() - 1);
        }
    }

    private static String statusText(RunProgram.Result r)
    {
        if (r.status == RunProgram.RUNNING)
        {
            return "running";
        }
        if (r.status == RunProgram.OK)
        {
            return "returned";
        }
        if (r.status == RunProgram.EXITED)
        {
            return "exit " + r.exitStatus;
        }
        return r.status == RunProgram.THREW ? "threw" : "not run";
    }

    /** The page: the two forms, the last run's output (also where a run from the page shows), and the history. */
    static String render(String now)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(WebServerDemo.head("Run a program")).append("<h1>Run a program</h1>\n");
        sb.append("<p><small>").append(WebServerDemo.escape(now)).append("</small></p>\n");
        sb.append("<p>Run a <code>.jar</code> (its <code>Main-Class</code>) or a <code>.class</code> on the board. ")
                .append("The output below is what it printed to <code>System.out</code> and <code>System.err</code>. ")
                .append("Each class name loads once per boot: running the same file again re-runs it, a changed ")
                .append("one needs new class names or a reboot.</p>\n");
        sb.append("<p>Token <input id=\"tok\" type=\"password\" size=\"34\" autocomplete=\"off\"></p>\n");
        sb.append("<p>Arguments <input id=\"args\" size=\"40\"> Main class <input id=\"main\" size=\"24\" ")
                .append("placeholder=\"from the manifest\"></p>\n");
        sb.append("<h2>Upload</h2>\n<p><input id=\"file\" type=\"file\" accept=\".jar,.class\"> ")
                .append("<button id=\"up\">Upload and run</button></p>\n");
        sb.append("<h2>Download</h2>\n<p><input id=\"url\" size=\"50\" ")
                .append("placeholder=\"http://host:8000/app.jar\"> <button id=\"get\">Download and run</button></p>\n");
        sb.append("<h2>Output</h2>\n<p id=\"st\">");
        Run last = RUNS.isEmpty() ? null : RUNS.get(0);
        if (last == null)
        {
            sb.append("Nothing has run yet this boot.</p>\n<pre id=\"out\"></pre>\n");
        }
        else
        {
            sb.append(WebServerDemo.escape("#" + last.result.id + " " + last.when + " " + last.what + " " + last.args
                    + ": " + statusText(last.result))).append("</p>\n");
            sb.append("<pre id=\"out\"").append(last.result.done() ? "" : " data-running=\"" + last.result.id + "\"")
                    .append(">").append(WebServerDemo.escape(last.result.text())).append("</pre>\n");
        }
        sb.append("<h2>This boot's runs</h2>\n<table>\n");
        sb.append("<tr><th>run</th><th>time</th><th>program</th><th>main class</th><th>args</th><th>result</th>")
                .append("<th>define ms</th><th>run ms</th></tr>\n");
        for (Run r : RUNS)
        {
            sb.append("<tr><td><a href=\"/run/").append(r.result.id).append("\">#").append(r.result.id)
                    .append("</a></td><td>").append(r.when).append("</td><td>").append(WebServerDemo.escape(r.what))
                    .append("</td><td>").append(r.result.mainClass == null ? "" : WebServerDemo.escape(r.result.mainClass))
                    .append("</td><td>").append(WebServerDemo.escape(r.args))
                    .append("</td><td>").append(statusText(r.result))
                    .append("</td><td class=\"n\">").append(r.result.defineMs)
                    .append("</td><td class=\"n\">").append(r.result.done() ? r.result.runMs : r.result.elapsedMs())
                    .append("</td></tr>\n");
        }
        sb.append("</table>\n");
        sb.append("<style>pre{background:#f4f4f4;padding:.6em;white-space:pre-wrap;overflow-wrap:anywhere}</style>\n");
        sb.append("<script>\n").append(SCRIPT).append("</script>\n");
        sb.append(WebServerDemo.FOOT);
        return sb.toString();
    }

    /**
     * The page's behaviour: the upload (XMLHttpRequest, for its progress) and the download request, each showing
     * the reply -- the program's output -- in place. They wait 5 s for main; a run still going after that (202) is
     * followed through {@code GET /run/<n>} once a second until it finishes, as is one the page was loaded during.
     * The token shares sessionStorage with the /boot page.
     */
    private static final String SCRIPT = """
            const $ = id => document.getElementById(id);
            const st = t => { $('st').textContent = t; };
            try { $('tok').value = sessionStorage.getItem('tok') || ''; } catch (e) {}
            $('tok').onchange = () => { try { sessionStorage.setItem('tok', $('tok').value); } catch (e) {} };
            function query(extra) {
              let q = 'args=' + encodeURIComponent($('args').value);
              if ($('main').value.trim()) q += '&main=' + encodeURIComponent($('main').value.trim());
              return q + '&wait=5' + extra;
            }
            const secs = t0 => Math.round((Date.now() - t0) / 100) / 10;
            function follow(id, what, t0) {
              setTimeout(() => {
                fetch('/run/' + id, { cache: 'no-store' })
                  .then(r => r.text().then(t => {
                    $('out').textContent = t;
                    if (r.status === 202) { st(what + ': still running, ' + secs(t0) + ' s'); follow(id, what, t0); }
                    else st(what + ': ' + r.status + ' ' + r.statusText + ' after ' + secs(t0) + ' s');
                  }))
                  .catch(() => follow(id, what, t0));
              }, 1000);
            }
            function shown(x, what, t0) {
              $('out').textContent = x.responseText;
              const m = x.status === 202 ? /run #(\\d+) still running/.exec(x.responseText) : null;
              if (m) { st(what + ': still running, ' + secs(t0) + ' s'); follow(m[1], what, t0); }
              else st(what + ': ' + x.status + ' ' + x.statusText + ' after ' + secs(t0) + ' s');
            }
            if ($('out').dataset.running) follow($('out').dataset.running, 'run #' + $('out').dataset.running, Date.now());
            function busy(on) { $('up').disabled = on; $('get').disabled = on; }
            $('up').onclick = () => {
              const f = $('file').files[0];
              if (!f) { st('Choose a .jar or .class first.'); return; }
              const x = new XMLHttpRequest();
              const t0 = Date.now();
              x.open('PUT', '/run/' + encodeURIComponent(f.name) + '?' + query(''));
              x.setRequestHeader('X-Boot-Token', $('tok').value);
              x.upload.onprogress = e => {
                if (e.lengthComputable) st('Uploading ' + f.name + ': ' + Math.floor(100 * e.loaded / e.total) + '%');
              };
              x.upload.onload = () => st('Running ' + f.name + '...');
              x.onload = () => { busy(false); shown(x, f.name, t0); };
              x.onerror = () => { busy(false); st('Failed: the connection broke.'); };
              busy(true);
              $('out').textContent = '';
              x.send(f);
            };
            $('get').onclick = () => {
              const u = $('url').value.trim();
              if (!u) { st('Give the url of a .jar or .class.'); return; }
              const x = new XMLHttpRequest();
              const t0 = Date.now();
              x.open('POST', '/run?' + query('&url=' + encodeURIComponent(u)));
              x.setRequestHeader('X-Boot-Token', $('tok').value);
              x.onload = () => { busy(false); shown(x, u, t0); };
              x.onerror = () => { busy(false); st('Failed: the connection broke.'); };
              busy(true);
              $('out').textContent = '';
              st('The board is downloading and running ' + u + '...');
              x.send();
            };
            """;
}
