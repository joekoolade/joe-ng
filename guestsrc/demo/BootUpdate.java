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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import jdk.internal.misc.MetalBoard;

/**
 * Updating the Pi's boot files over the network, with the firmware's tryboot as the safety net. The endpoints:
 *
 * <ul>
 * <li>{@code GET /version}: this image's build id.</li>
 * <li>{@code PUT /boot/<name>.new}: stage one boot file. {@code <name>} is one of {@link #LIVE} or an
 *     {@code armstub8*.bin}. The body is written to the card, read back, and its SHA-256 answered; an
 *     {@code X-Sha256} header that disagrees deletes the file and answers 400.</li>
 * <li>{@code POST /reboot?tryboot=1}: write {@code tryboot.txt} (the staged or live config, pointed at the staged
 *     files), set the firmware's one-shot tryboot flag, and reset. Plain {@code POST /reboot} just resets.</li>
 * <li>{@code POST /boot/commit}: on the trial boot, rename each staged file over its live one, the config last,
 *     and delete {@code tryboot.txt}. Refused while a staged kernel is not the one running.</li>
 * <li>{@code POST /boot/discard}: delete the staged files and {@code tryboot.txt}.</li>
 * <li>{@code GET /boot/sha256/<name>}: a file's SHA-256, for the {@link BootPage} table.</li>
 * </ul>
 *
 * <p>Nothing live changes before commit, so a staged kernel that never comes up costs a power cycle: the flag is
 * one-shot, and the next boot reads the untouched {@code config.txt}. Every request that changes state carries
 * {@code X-Boot-Token}, compared with {@code /etc/boot.token}; with no token baked, those requests are refused.
 */
final class BootUpdate
{
    private BootUpdate()
    {
    }

    /** What the server does once the response is out. */
    static final int NONE = 0;

    static final int REBOOT = 1;

    /** The boot files besides the armstubs, in commit order: the config is renamed LAST. */
    static final String[] LIVE = { "kernel8.img", "start4.elf", "fixup4.dat", "config.txt" };

    static final String STAGED = ".new";

    private static final String BOOT = "/boot/";

    static final String TRYBOOT = "/boot/tryboot.txt";

    /** {@code GET /boot/sha256/<name>}: one top-level boot-partition file's SHA-256, read from the card. */
    private static final String SHA_PATH = "/boot/sha256/";

    /** The largest body accepted. A kernel is ~40 MB. */
    private static final long MAX_BODY = 128L * 1024 * 1024;

    /** Bytes gathered before each write to the card: every write rewrites FAT and directory sectors. */
    private static final int CHUNK = 64 * 1024;

    private static byte[] token;

    private static boolean tokenRead;

    /**
     * Answer {@code req} if it is one of the boot-update endpoints. Returns -1 when it is not (the caller serves
     * it), else {@link #NONE} or {@link #REBOOT}.
     */
    static int handle(Http.Request req, OutputStream out) throws IOException
    {
        String m = req.method;
        String p = req.path;
        if (p.equals("/version") && m.equals("GET"))
        {
            Http.text(out, "200 OK", MetalBoard.buildId());
            return NONE;
        }
        if (p.startsWith(SHA_PATH) && m.equals("GET"))
        {
            hash(p.substring(SHA_PATH.length()), out);
            return NONE;
        }
        boolean upload = p.startsWith(BOOT) && m.equals("PUT");
        boolean reboot = p.equals("/reboot") && m.equals("POST");
        boolean commit = p.equals("/boot/commit") && m.equals("POST");
        boolean discard = p.equals("/boot/discard") && m.equals("POST");
        if (!upload && !reboot && !commit && !discard)
        {
            return -1;
        }
        if (!authorized(req, out))
        {
            return NONE;
        }
        if (upload)
        {
            upload(req, out);
            return NONE;
        }
        if (commit)
        {
            commit(out);
            return NONE;
        }
        if (discard)
        {
            discard(out);
            return NONE;
        }
        if (req.queryHas("tryboot", "1"))
        {
            if (stagedLive().isEmpty())
            {
                Http.text(out, "409 Conflict", "nothing is staged");
                return NONE;
            }
            String cfg;
            try
            {
                cfg = writeTryboot();
                MetalBoard.setTryboot();
            }
            catch (IOException e)
            {
                System.out.println("boot: trial boot failed: " + e);
                Http.text(out, "500 Internal Server Error", "trial boot not started: " + e.getMessage());
                return NONE;
            }
            System.out.println("boot: tryboot.txt written, flag set; resetting");
            Http.text(out, "200 OK", "trial boot\n" + cfg);
        }
        else
        {
            System.out.println("boot: resetting");
            Http.text(out, "200 OK", "rebooting");
        }
        return REBOOT;
    }

    /** True when the request's token matches; otherwise answers 403 (no token baked) or 401 and returns false. */
    private static boolean authorized(Http.Request req, OutputStream out) throws IOException
    {
        byte[] want = token();
        if (want == null)
        {
            Http.text(out, "403 Forbidden", "boot updates are disabled: this image has no /etc/boot.token");
            return false;
        }
        String got = req.header("x-boot-token");
        byte[] g = got == null ? new byte[0] : got.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(want, g))
        {
            System.out.println("boot: bad token");
            Http.text(out, "401 Unauthorized", "bad or missing X-Boot-Token");
            return false;
        }
        return true;
    }

    /** {@code /etc/boot.token}, trimmed; null when absent or empty. Read once. */
    private static byte[] token()
    {
        if (!tokenRead)
        {
            tokenRead = true;
            try
            {
                String t = Files.readString(Path.of("/etc/boot.token")).trim();
                if (!t.isEmpty())
                {
                    token = t.getBytes(StandardCharsets.UTF_8);
                }
            }
            catch (IOException e)
            {
                token = null;
            }
        }
        return token;
    }

    /** The live boot file {@code name} may be staged over, or null when it is not a boot file. */
    static String liveName(String staged)
    {
        if (!staged.endsWith(STAGED))
        {
            return null;
        }
        String live = staged.substring(0, staged.length() - STAGED.length());
        for (String n : LIVE)
        {
            if (n.equals(live))
            {
                return live;
            }
        }
        if (isArmstub(live))
        {
            return live;
        }
        return null;
    }

    /** {@code armstub8<letters, digits, '-' or '_'>.bin}: scripts/sdcard.sh's armstub/*.bin. */
    static boolean isArmstub(String n)
    {
        if (!n.startsWith("armstub8") || !n.endsWith(".bin") || n.length() > 64)
        {
            return false;
        }
        int i = "armstub8".length();
        int end = n.length() - ".bin".length();
        while (i < end)
        {
            char c = n.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!ok)
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    /** Answer the SHA-256 of {@code /boot/<name>}; no token, as it changes nothing. */
    private static void hash(String name, OutputStream out) throws IOException
    {
        File f = new File(BOOT + name);
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.startsWith(".") || !f.isFile())
        {
            Http.text(out, "404 Not Found", "no file /boot/" + name);
            return;
        }
        long t0 = System.currentTimeMillis();
        String sha = sha256(f.getPath());
        System.out.println("boot: sha256 " + name + " " + f.length() + " bytes in " + (System.currentTimeMillis() - t0)
                + " ms");
        Http.text(out, "200 OK", sha);
    }

    /** Stream the body to {@code /boot/<name>.new}, read it back, and answer its SHA-256. */
    private static void upload(Http.Request req, OutputStream out) throws IOException
    {
        String name = req.path.substring(BOOT.length());
        if (name.indexOf('/') >= 0 || liveName(name) == null)
        {
            Http.text(out, "400 Bad Request", "not a staged boot file name: " + name
                    + " (kernel8.img.new, config.txt.new, start4.elf.new, fixup4.dat.new, armstub8*.bin.new)");
            return;
        }
        if (req.header("transfer-encoding") != null)
        {
            Http.text(out, "411 Length Required", "send Content-Length, not a chunked body");
            return;
        }
        long len = req.contentLength();
        if (len < 0L)
        {
            Http.text(out, "411 Length Required", "Content-Length is required");
            return;
        }
        if (len > MAX_BODY)
        {
            Http.text(out, "413 Content Too Large", "at most " + MAX_BODY + " bytes");
            return;
        }
        String expect = req.header("expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue"))
        {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        String path = BOOT + name;
        long t0 = System.currentTimeMillis();
        long got;
        try
        {
            got = receive(req.body(), path);
        }
        catch (IOException e)
        {
            new File(path).delete();
            System.out.println("boot: PUT " + name + " failed: " + e);
            Http.text(out, "507 Insufficient Storage", "writing " + name + " failed: " + e.getMessage());
            return;
        }
        long t1 = System.currentTimeMillis();
        if (got != len)
        {
            new File(path).delete();
            System.out.println("boot: PUT " + name + " short: " + got + " of " + len + " bytes");
            Http.text(out, "400 Bad Request", "body ended after " + got + " of " + len + " bytes; deleted");
            return;
        }
        String sha = sha256(path);
        long t2 = System.currentTimeMillis();
        System.out.println("boot: PUT " + name + " " + len + " bytes, received+written in " + (t1 - t0)
                + " ms, read back in " + (t2 - t1) + " ms, sha256 " + sha);
        String want = req.header("x-sha256");
        if (want != null && !want.trim().equalsIgnoreCase(sha))
        {
            new File(path).delete();
            Http.text(out, "400 Bad Request", "sha256 mismatch: sent " + want.trim() + ", card has " + sha
                    + "; deleted");
            return;
        }
        String reply = "sha256 " + sha + "\nbytes " + len;
        if (name.equals("kernel8.img" + STAGED))
        {
            reply = reply + "\nbuild " + MetalBoard.buildIdOf(path);
        }
        Http.text(out, "200 OK", reply);
    }

    /** Copy {@code body} to {@code path} in {@link #CHUNK}-byte writes; the number of bytes copied. */
    private static long receive(InputStream body, String path) throws IOException
    {
        byte[] buf = new byte[CHUNK];
        long total = 0L;
        try (FileOutputStream f = new FileOutputStream(path))
        {
            while (true)
            {
                int n = 0;
                while (n < buf.length)
                {
                    int r = body.read(buf, n, buf.length - n);
                    if (r < 0)
                    {
                        break;
                    }
                    n = n + r;
                }
                if (n == 0)
                {
                    break;
                }
                f.write(buf, 0, n);
                total = total + n;
                if (n < buf.length)
                {
                    break;
                }
            }
        }
        return total;
    }

    /** SHA-256 of the file at {@code path}, read back from the card, as lowercase hex. */
    static String sha256(String path) throws IOException
    {
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IOException(e);
        }
        byte[] buf = new byte[CHUNK];
        try (FileInputStream in = new FileInputStream(path))
        {
            while (true)
            {
                int r = in.read(buf);
                if (r < 0)
                {
                    break;
                }
                md.update(buf, 0, r);
            }
        }
        byte[] h = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : h)
        {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** The live names whose staged twin exists, in commit order (armstubs first, the config last). */
    static List<String> stagedLive()
    {
        List<String> names = new ArrayList<>();
        String[] all = new File("/boot").list();
        if (all != null)
        {
            for (String n : all)
            {
                String live = liveName(n);
                if (live != null && isArmstub(live))
                {
                    names.add(live);
                }
            }
        }
        for (String n : LIVE)
        {
            if (new File(BOOT + n + STAGED).isFile())
            {
                names.add(n);
            }
        }
        return names;
    }

    /**
     * Write {@code tryboot.txt}: the staged config (else the live one) with {@code kernel=}, {@code armstub=},
     * {@code start_file=} and {@code fixup_file=} pointed at the staged files. Returns what was written.
     */
    private static String writeTryboot() throws IOException
    {
        List<String> staged = stagedLive();
        if (staged.isEmpty())
        {
            throw new IOException("nothing is staged");
        }
        String src = staged.contains("config.txt") ? "config.txt" + STAGED : "config.txt";
        List<String> lines = Files.readAllLines(Path.of(BOOT + src), StandardCharsets.ISO_8859_1);
        String kernel = staged.contains("kernel8.img") ? "kernel8.img" + STAGED : null;
        String start = staged.contains("start4.elf") ? "start4.elf" + STAGED : null;
        String fixup = staged.contains("fixup4.dat") ? "fixup4.dat" + STAGED : null;
        String stub = null;
        for (String n : staged)
        {
            if (isArmstub(n))
            {
                stub = n + STAGED;
            }
        }
        boolean[] done = new boolean[4];
        StringBuilder sb = new StringBuilder();
        sb.append("# Written by joe-ng's web server for a trial boot of the staged files.\n");
        for (String line : lines)
        {
            String t = line.trim();
            String set = null;
            if (kernel != null && t.startsWith("kernel="))
            {
                set = "kernel=" + kernel;
                done[0] = true;
            }
            else if (stub != null && t.startsWith("armstub="))
            {
                set = "armstub=" + stub;
                done[1] = true;
            }
            else if (start != null && t.startsWith("start_file="))
            {
                set = "start_file=" + start;
                done[2] = true;
            }
            else if (fixup != null && t.startsWith("fixup_file="))
            {
                set = "fixup_file=" + fixup;
                done[3] = true;
            }
            sb.append(set != null ? set : line).append('\n');
        }
        String[] keys = { "kernel=", "armstub=", "start_file=", "fixup_file=" };
        String[] vals = { kernel, stub, start, fixup };
        boolean header = false;
        int i = 0;
        while (i < keys.length)
        {
            if (vals[i] != null && !done[i])
            {
                if (!header)
                {
                    sb.append("[all]\n");
                    header = true;
                }
                sb.append(keys[i]).append(vals[i]).append('\n');
            }
            i = i + 1;
        }
        String cfg = sb.toString();
        Files.writeString(Path.of(TRYBOOT), cfg, StandardCharsets.ISO_8859_1);
        return cfg;
    }

    /** Rename each staged file over its live one, the config last; then delete {@code tryboot.txt}. */
    private static void commit(OutputStream out) throws IOException
    {
        List<String> staged = stagedLive();
        if (staged.isEmpty())
        {
            Http.text(out, "409 Conflict", "nothing is staged");
            return;
        }
        if (staged.contains("kernel8.img"))
        {
            String running = MetalBoard.buildId();
            String want = MetalBoard.buildIdOf(BOOT + "kernel8.img" + STAGED);
            if (!running.equals(want))
            {
                Http.text(out, "409 Conflict", "running build " + running + ", but the staged kernel is build " + want
                        + ": trial-boot it first (POST /reboot?tryboot=1)");
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String n : staged)
        {
            // ATOMIC_MOVE is rename(2): ONE directory-entry write switches the name to the new clusters. A plain
            // REPLACE_EXISTING deletes the target first, leaving a moment with no kernel8.img on the card.
            Files.move(Path.of(BOOT + n + STAGED), Path.of(BOOT + n), StandardCopyOption.ATOMIC_MOVE);
            System.out.println("boot: committed " + n);
            sb.append("committed ").append(n).append('\n');
        }
        Files.deleteIfExists(Path.of(TRYBOOT));
        Http.text(out, "200 OK", sb.toString().trim());
    }

    /** Delete every staged file and {@code tryboot.txt}. */
    private static void discard(OutputStream out) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        for (String n : stagedLive())
        {
            Files.delete(Path.of(BOOT + n + STAGED));
            sb.append("deleted ").append(n).append(STAGED).append('\n');
        }
        if (Files.deleteIfExists(Path.of(TRYBOOT)))
        {
            sb.append("deleted tryboot.txt\n");
        }
        System.out.println("boot: discarded staged files");
        Http.text(out, "200 OK", sb.length() == 0 ? "nothing staged" : sb.toString().trim());
    }
}
