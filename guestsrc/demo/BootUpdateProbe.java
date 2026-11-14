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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;
import jdk.internal.misc.MetalBoard;

/**
 * The web server's boot-update endpoints without a network: each request is bytes fed through the same
 * {@link Http#read} and {@link BootUpdate#handle} the server runs, against the {@code /boot} card. QEMU has no NIC,
 * so this is how the endpoint logic is tested there; the Pi run drives the real sockets.
 *
 * <p>Ends with {@code bootupdate: PASS} or {@code FAIL}, then a manifest of the files it left for the host to check
 * ({@code fsck_msdos -n}, {@code shasum}). Argument: the token baked as {@code /etc/boot.token}.
 */
public class BootUpdateProbe
{
    private static int failures;

    private static String token;

    public static void main(String[] args) throws Exception
    {
        token = Files.readString(Path.of("/etc/boot.token")).trim();
        String id = MetalBoard.buildId();
        System.out.println("bu: running build " + id);
        cleanup();

        expect("GET /version", call("GET /version", null, null), "200", id);
        expect("no token", put("config.txt.new", "x".getBytes(), null, null), "401", null);
        expect("wrong token", put("config.txt.new", "x".getBytes(), "nope", null), "401", null);
        expect("bad name", put("evil.txt", "x".getBytes(), token, null), "400", "not a staged");
        expect("live name", put("kernel8.img", "x".getBytes(), token, null), "400", "not a staged");
        expect("traversal", put("../etc/x.new", "x".getBytes(), token, null), "400", "not a staged");
        expect("no length", call("PUT /boot/config.txt.new", headers(token, null, -1, false), "x".getBytes()),
                "411", null);

        byte[] cfg = (Files.readString(Path.of("/boot/config.txt")) + "# staged by BootUpdateProbe\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        expect("config", put("config.txt.new", cfg, token, sha(cfg)), "200", sha(cfg));

        byte[] fix = bytes(70_000, 7);
        expect("wrong sha", put("fixup4.dat.new", fix, token, sha("other".getBytes())), "400", "mismatch");
        check("wrong sha deleted", !new File("/boot/fixup4.dat.new").exists());

        expect("short body", call("PUT /boot/fixup4.dat.new", headers(token, null, 1000, false), bytes(10, 1)),
                "400", "after 10 of 1000");
        check("short body deleted", !new File("/boot/fixup4.dat.new").exists());

        byte[] stub = bytes(5000, 3);
        expect("armstub", put("armstub8-probe.bin.new", stub, token, sha(stub)), "200", sha(stub));

        byte[] wrongKernel = kernel(3_000_000, "0123456789abcdef");
        expect("other kernel", put("kernel8.img.new", wrongKernel, token, sha(wrongKernel)), "200",
                "build 0123456789abcdef");
        expect("commit refused", call("POST /boot/commit", headers(token, null, 0, false), new byte[0]),
                "409", "trial-boot it first");
        String pg = BootPage.render("now", java.time.ZoneId.of("UTC"));
        check("page: not yet trial-booted", pg.contains("Staged, not yet trial-booted"));
        check("page: commit disabled", pg.contains("<button id=\"commit\" disabled>"));
        check("page: staged kernel id", pg.contains("<code>0123456789abcdef</code>"));
        check("page: staged row", pg.contains("<b>kernel8.img.new</b></td><td>staged</td>"));
        expect("sha256 endpoint", call("GET /boot/sha256/config.txt.new", null, null), "200", sha(cfg));
        expect("sha256 missing", call("GET /boot/sha256/nope.bin", null, null), "404", null);
        expect("sha256 traversal", call("GET /boot/sha256/../etc/motd", null, null), "404", null);
        expect("buildid staged", call("GET /boot/buildid/kernel8.img.new", null, null), "200", "0123456789abcdef");
        expect("buildid no trailer", call("GET /boot/buildid/config.txt.new", null, null), "422", null);
        expect("buildid missing", call("GET /boot/buildid/nope.img", null, null), "404", null);
        expect("buildid traversal", call("GET /boot/buildid/../etc/motd", null, null), "404", null);

        byte[] k = kernel(3_000_000, id);
        String kr = call("PUT /boot/kernel8.img.new", headers(token, sha(k), k.length, true), k);
        expect("kernel 100-continue", kr, "100 Continue", null);
        expect("kernel", kr.substring(kr.indexOf("\r\n\r\n") + 4), "200", "build " + id);

        String pg2 = BootPage.render("now", java.time.ZoneId.of("UTC"));
        check("page: on trial", pg2.contains("This boot IS the staged kernel"));
        check("page: commit enabled", pg2.contains("<button id=\"commit\">"));
        String tb = call("POST /reboot?tryboot=1", headers(token, null, 0, false), new byte[0]);
        expect("tryboot", tb, "200", "kernel=kernel8.img.new");
        String t = Files.readString(Path.of("/boot/tryboot.txt"));
        check("tryboot kernel", t.contains("\nkernel=kernel8.img.new\n"));
        check("tryboot armstub", t.contains("\narmstub=armstub8-probe.bin.new\n"));
        check("tryboot from staged config", t.contains("# staged by BootUpdateProbe"));
        check("tryboot keeps arm_64bit", t.contains("\narm_64bit=1\n"));
        System.out.print(t);

        expect("commit", call("POST /boot/commit", headers(token, null, 0, false), new byte[0]), "200",
                "committed config.txt");
        check("kernel live", sha(Files.readAllBytes(Path.of("/boot/kernel8.img"))).equals(sha(k)));
        check("config live", sha(Files.readAllBytes(Path.of("/boot/config.txt"))).equals(sha(cfg)));
        check("armstub live", sha(Files.readAllBytes(Path.of("/boot/armstub8-probe.bin"))).equals(sha(stub)));
        check("staged gone", !new File("/boot/kernel8.img.new").exists() && !new File("/boot/config.txt.new").exists());
        check("tryboot gone", !new File("/boot/tryboot.txt").exists());
        check("trailer", id.equals(MetalBoard.buildIdOf("/boot/kernel8.img")));

        expect("stage again", put("start4.elf.new", stub, token, null), "200", sha(stub));
        expect("discard", call("POST /boot/discard", headers(token, null, 0, false), new byte[0]), "200",
                "deleted start4.elf.new");
        check("discarded", !new File("/boot/start4.elf.new").exists());
        expect("trial with nothing staged", call("POST /reboot?tryboot=1", headers(token, null, 0, false),
                new byte[0]), "409", "nothing is staged");
        String pg3 = BootPage.render("now", java.time.ZoneId.of("UTC"));
        check("page: nothing staged", pg3.contains("Nothing is staged"));
        check("page: trial disabled", pg3.contains("<button id=\"trial\" disabled>"));
        check("page: lists boot file", pg3.contains("<b>kernel8.img</b></td><td>boot file</td>"));
        check("page: armstub target", pg3.contains("<option>armstub8-joe.bin</option>"));
        expect("nothing to commit", call("POST /boot/commit", headers(token, null, 0, false), new byte[0]),
                "409", "nothing is staged");

        for (String n : new String[] { "kernel8.img", "config.txt", "armstub8-probe.bin" })
        {
            System.out.println("bu: file " + n + " " + sha(Files.readAllBytes(Path.of("/boot/" + n))));
        }
        System.out.println(failures == 0 ? "bootupdate: PASS" : "bootupdate: FAIL (" + failures + ")");
    }

    /** Leftovers from an earlier run of this probe on the same image. */
    private static void cleanup() throws Exception
    {
        for (String n : new String[] { "kernel8.img.new", "config.txt.new", "fixup4.dat.new", "start4.elf.new",
                "armstub8-probe.bin.new", "tryboot.txt" })
        {
            Files.deleteIfExists(Path.of("/boot/" + n));
        }
    }

    private static String put(String name, byte[] body, String tok, String sha) throws Exception
    {
        return call("PUT /boot/" + name, headers(tok, sha, body.length, false), body);
    }

    private static String headers(String tok, String sha, long len, boolean expect100)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Host: pi\r\n");
        if (tok != null)
        {
            sb.append("X-Boot-Token: ").append(tok).append("\r\n");
        }
        if (sha != null)
        {
            sb.append("X-Sha256: ").append(sha).append("\r\n");
        }
        if (len >= 0)
        {
            sb.append("Content-Length: ").append(len).append("\r\n");
        }
        if (expect100)
        {
            sb.append("Expect: 100-continue\r\n");
        }
        return sb.toString();
    }

    /** One request through Http.read and BootUpdate.handle; the whole response as text. */
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
        int what = BootUpdate.handle(r, out);
        if (what < 0)
        {
            return "(not handled)";
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static void expect(String what, String response, String status, String contains)
    {
        String first = response.indexOf("\r\n") >= 0 ? response.substring(0, response.indexOf("\r\n")) : response;
        boolean ok = first.contains(status) && (contains == null || response.contains(contains));
        if (!ok)
        {
            failures = failures + 1;
        }
        String body = response.indexOf("\r\n\r\n") >= 0 ? response.substring(response.lastIndexOf("\r\n\r\n") + 4)
                : "";
        System.out.println("bu: " + (ok ? "ok   " : "FAIL ") + what + ": " + first + " | " + body.trim()
                .replace("\n", " / "));
    }

    private static void check(String what, boolean ok)
    {
        if (!ok)
        {
            failures = failures + 1;
        }
        System.out.println("bu: " + (ok ? "ok   " : "FAIL ") + what);
    }

    /** {@code n} bytes of a seeded pseudo-random kernel body, then the 32-byte build-id trailer naming {@code id}. */
    private static byte[] kernel(int n, String id)
    {
        byte[] b = bytes(n, 11);
        byte[] t = ("joe-ng build id\n" + id).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, b, n - 32, 32);
        return b;
    }

    private static byte[] bytes(int n, long seed)
    {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static String sha(byte[] b) throws Exception
    {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h)
        {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
