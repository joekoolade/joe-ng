/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-13
 */
package demo;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jdk.internal.misc.MetalBoard;

/**
 * {@code GET /boot}: the SD card's boot files, for updating them from a browser. It shows what is running and what
 * is staged, lists the boot partition, and drives the same endpoints {@code scripts/push.sh} does ({@link
 * BootUpdate}): upload a file as {@code <name>.new}, trial-boot, commit, discard, reboot.
 *
 * <p>Viewing needs no token; every button that changes something sends the one typed into the page. A browser
 * gives {@code crypto.subtle} only to secure origins, and this page is plain {@code http://} on the LAN, so the
 * upload sends no {@code X-Sha256}: the page shows the SHA-256 the Pi read back from the card instead, to compare
 * with {@code shasum -a 256} on the machine the file came from.
 */
final class BootPage
{
    private BootPage()
    {
    }

    private static final DateTimeFormatter MTIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String render(String now, ZoneId zone) throws IOException
    {
        String running = MetalBoard.buildId();
        List<String> staged = BootUpdate.stagedLive();
        String stagedKernel = null;
        boolean kernelStaged = staged.contains("kernel8.img");
        if (kernelStaged)
        {
            stagedKernel = MetalBoard.buildIdOf("/boot/kernel8.img" + BootUpdate.STAGED);
        }
        boolean tryboot = new File(BootUpdate.TRYBOOT).isFile();
        boolean onTrial = kernelStaged && running.equals(stagedKernel);
        boolean canCommit = !staged.isEmpty() && (!kernelStaged || onTrial);

        StringBuilder sb = new StringBuilder();
        sb.append(WebServerDemo.head("Boot files")).append("<h1>Boot files</h1>\n");
        sb.append("<p><small>").append(WebServerDemo.escape(now)).append("</small></p>\n");

        sb.append("<table>\n");
        row(sb, "running build", "<code>" + running + "</code>");
        String sk = "none";
        if (kernelStaged)
        {
            sk = stagedKernel == null ? "kernel8.img.new (no build id: not a joe-ng image?)"
                    : "<code>" + stagedKernel + "</code>";
        }
        row(sb, "staged kernel", sk);
        row(sb, "staged files", staged.isEmpty() ? "none" : WebServerDemo.escape(stagedList(staged)));
        row(sb, "tryboot.txt", tryboot ? "present" : "absent");
        sb.append("</table>\n");
        sb.append("<p><b>").append(state(staged, kernelStaged, onTrial)).append("</b></p>\n");

        sb.append("<h2>Upload</h2>\n");
        sb.append("<p>Token <input id=\"tok\" type=\"password\" size=\"34\" autocomplete=\"off\"></p>\n");
        sb.append("<p><input id=\"file\" type=\"file\"> as <select id=\"target\">");
        for (String n : targets())
        {
            sb.append("<option>").append(WebServerDemo.escape(n)).append("</option>");
        }
        sb.append("</select><code>.new</code> <button id=\"up\">Upload</button></p>\n");

        sb.append("<h2>Actions</h2>\n<p>");
        button(sb, "trial", "Trial boot", !staged.isEmpty());
        button(sb, "commit", "Commit", canCommit);
        button(sb, "discard", "Discard staged", !staged.isEmpty() || tryboot);
        button(sb, "reboot", "Reboot", true);
        sb.append("</p>\n<pre id=\"out\"></pre>\n");

        sb.append("<h2>Boot partition</h2>\n<table>\n");
        sb.append("<tr><th>name</th><th></th><th>bytes</th><th>modified</th><th>sha256</th></tr>\n");
        String[] names = new File("/boot").list();
        if (names != null)
        {
            Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
            for (String n : names)
            {
                fileRow(sb, n, zone);
            }
        }
        sb.append("</table>\n");
        sb.append("<script>\n").append(SCRIPT).append("</script>\n");
        sb.append(WebServerDemo.FOOT);
        return sb.toString();
    }

    /** What the buttons will do next, in one sentence. */
    private static String state(List<String> staged, boolean kernelStaged, boolean onTrial)
    {
        if (staged.isEmpty())
        {
            return "Nothing is staged. Upload a file to stage it as &lt;name&gt;.new; the live files do not change.";
        }
        if (onTrial)
        {
            return "This boot IS the staged kernel: Commit makes it permanent. A power cycle instead boots the old "
                    + "files.";
        }
        if (kernelStaged)
        {
            return "Staged, not yet trial-booted: Trial boot starts the staged files once. If they do not come up, "
                    + "a power cycle boots the old ones.";
        }
        return "Staged without a kernel: Trial boot to try them, or Commit to make them live now.";
    }

    private static String stagedList(List<String> staged)
    {
        StringBuilder sb = new StringBuilder();
        for (String n : staged)
        {
            if (sb.length() > 0)
            {
                sb.append(", ");
            }
            sb.append(n).append(BootUpdate.STAGED);
        }
        return sb.toString();
    }

    /** The names an upload may be staged as: the fixed boot files, then the armstubs on the card. */
    private static List<String> targets()
    {
        List<String> t = new ArrayList<>(Arrays.asList(BootUpdate.LIVE));
        String[] names = new File("/boot").list();
        if (names != null)
        {
            Arrays.sort(names);
            for (String n : names)
            {
                if (BootUpdate.isArmstub(n))
                {
                    t.add(n);
                }
            }
        }
        return t;
    }

    private static void row(StringBuilder sb, String k, String v)
    {
        sb.append("<tr><td>").append(k).append("</td><td>").append(v).append("</td></tr>\n");
    }

    private static void button(StringBuilder sb, String id, String label, boolean enabled)
    {
        sb.append("<button id=\"").append(id).append("\"").append(enabled ? "" : " disabled").append(">")
                .append(label).append("</button> ");
    }

    /** One top-level entry: hidden names are skipped; boot files and their staged twins are marked. */
    private static void fileRow(StringBuilder sb, String n, ZoneId zone)
    {
        if (n.startsWith("."))
        {
            return;
        }
        File f = new File("/boot/" + n);
        String role = "";
        if (BootUpdate.liveName(n) != null)
        {
            role = "staged";
        }
        else if (BootUpdate.liveName(n + BootUpdate.STAGED) != null)
        {
            role = "boot file";
        }
        else if (n.equals("tryboot.txt"))
        {
            role = "trial config";
        }
        String e = WebServerDemo.escape(n);
        boolean mark = !role.isEmpty();
        sb.append("<tr><td>").append(mark ? "<b>" + e + "</b>" : e).append(f.isDirectory() ? "/" : "")
                .append("</td><td>").append(role).append("</td><td class=\"n\">");
        if (f.isFile())
        {
            sb.append(WebServerDemo.grouped(f.length()));
        }
        sb.append("</td><td>").append(MTIME.format(Instant.ofEpochMilli(f.lastModified()).atZone(zone)))
                .append("</td><td>");
        if (f.isFile())
        {
            sb.append("<button class=\"sha\" data-name=\"").append(e).append("\">sha256</button>");
        }
        sb.append("</td></tr>\n");
    }

    /**
     * The page's behaviour: upload with progress (XMLHttpRequest, as fetch reports none), the four POSTs, the
     * per-file hash, and after a reset, polling {@code /version} until the Pi answers again. The token is kept in
     * sessionStorage, so a reload does not ask for it twice.
     */
    private static final String SCRIPT = """
            const $ = id => document.getElementById(id);
            const say = t => { $('out').textContent = t; };
            try { $('tok').value = sessionStorage.getItem('tok') || ''; } catch (e) {}
            $('tok').onchange = () => { try { sessionStorage.setItem('tok', $('tok').value); } catch (e) {} };
            $('file').onchange = () => {
              const f = $('file').files[0];
              if (!f) return;
              for (const o of $('target').options) if (o.value === f.name) $('target').value = f.name;
            };
            $('up').onclick = () => {
              const f = $('file').files[0];
              if (!f) { say('Choose a file first.'); return; }
              const name = $('target').value + '.new';
              const x = new XMLHttpRequest();
              x.open('PUT', '/boot/' + encodeURIComponent(name));
              x.setRequestHeader('X-Boot-Token', $('tok').value);
              x.upload.onprogress = e => {
                if (e.lengthComputable) say('Uploading ' + f.name + ' as ' + name + ': '
                    + Math.floor(100 * e.loaded / e.total) + '% (' + e.loaded + ' of ' + e.total + ' bytes)');
              };
              x.upload.onload = () => say('Sent. The Pi is reading ' + name + ' back from the card...');
              x.onload = () => say(x.status + ' ' + x.statusText + '\\n' + x.responseText
                  + (x.status === 200
                      ? '\\nCompare with: shasum -a 256 ' + f.name + '\\nReload to see it staged.' : ''));
              x.onerror = () => say('Upload failed: the connection broke.');
              say('Uploading ' + f.name + '...');
              x.send(f);
            };
            function post(path, ask, restarts) {
              if (ask && !confirm(ask)) return;
              say('POST ' + path + ' ...');
              fetch(path, { method: 'POST', headers: { 'X-Boot-Token': $('tok').value } })
                .then(r => r.text().then(t => {
                  say(r.status + ' ' + r.statusText + '\\n' + t);
                  if (r.ok && restarts) waitForPi(Date.now());
                  else if (r.ok) setTimeout(() => location.reload(), 1500);
                }))
                .catch(e => say('Failed: ' + e));
            }
            function waitForPi(t0) {
              setTimeout(() => {
                fetch('/version', { cache: 'no-store', signal: AbortSignal.timeout(4000) })
                  .then(r => r.text()).then(() => location.reload())
                  .catch(() => {
                    say('Waiting for the Pi to come back up: ' + Math.round((Date.now() - t0) / 1000) + ' s');
                    waitForPi(t0);
                  });
              }, 3000);
            }
            $('trial').onclick = () => post('/reboot?tryboot=1',
                'Reset into the staged files once? If they do not come up, a power cycle boots the old ones.', true);
            $('commit').onclick = () => post('/boot/commit', 'Make the staged files live?', false);
            $('discard').onclick = () => post('/boot/discard', 'Delete the staged files?', false);
            $('reboot').onclick = () => post('/reboot', 'Reset the Pi?', true);
            for (const b of document.querySelectorAll('button.sha')) {
              b.onclick = () => {
                b.disabled = true;
                b.textContent = 'hashing...';
                fetch('/boot/sha256/' + encodeURIComponent(b.dataset.name)).then(r => r.text())
                  .then(t => { b.parentNode.innerHTML = '<code>' + t.trim() + '</code>'; })
                  .catch(e => { b.textContent = 'failed'; });
              };
            }
            """;
}
