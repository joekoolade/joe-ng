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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * {@code /boot} -- the microSD card's FAT32 boot partition, mounted read-only -- through stock java.io and
 * java.nio.file. Every file gets a line {@code <sha256> <size> <path>}; the lines, sorted by path, are hashed
 * into one MANIFEST digest -- and a CONTENT digest without macOS's own folders -- to compare with the host's,
 * and the top-level files' lines are printed (every line with {@code list}). Then a read rate, a case-insensitive
 * lookup, java.nio reads, and that writes are refused.
 *
 * <p>Files larger than 4 MiB get {@code -} for the digest unless {@code all} is an argument, except
 * {@code kernel8.img}, which is always hashed. The host side, from the mounted partition:
 * <pre>
 *   find . -type f | sed 's|^\.||' | LC_ALL=C sort | while IFS= read -r f; do ... printf "%s %s %s\n" ...; done
 * </pre>
 */
public final class BootFsDemo
{
    private BootFsDemo()
    {
    }

    private static final long HASH_LIMIT = 4L << 20;

    private static boolean all;
    private static final java.util.List<String> LINES = new java.util.ArrayList<>();
    private static int files;
    private static int dirs;
    private static long bytes;

    public static void main(String[] args) throws Exception
    {
        java.util.List<String> a = java.util.Arrays.asList(args);
        all = a.contains("all");
        boolean listAll = a.contains("list");
        File boot = new File("/boot");
        long t0 = System.nanoTime();
        String[] top = boot.list();
        long t1 = System.nanoTime();
        System.out.println("boot: mount + list " + (t1 - t0) / 1_000_000L + " ms, " + (top == null ? -1 : top.length)
                + " entries at the top");
        if (top == null || top.length == 0)
        {
            System.out.println("boot: empty -- " + jdk.internal.misc.MetalRamfs.bootError());
            return;
        }
        walk(boot, "");
        java.util.Collections.sort(LINES, (x, y) -> path(x).compareTo(path(y)));
        MessageDigest manifest = MessageDigest.getInstance("SHA-256");
        MessageDigest content = MessageDigest.getInstance("SHA-256");
        int contentLines = 0;
        for (String line : LINES)
        {
            byte[] lb = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            manifest.update(lb);
            if (!macMetadata(path(line)))
            {
                content.update(lb);
                contentLines += 1;
            }
            if (listAll || path(line).indexOf('/', 1) < 0)
            {
                System.out.println("boot: " + line);
            }
        }
        System.out.println("boot: " + files + " files, " + dirs + " directories, " + bytes + " bytes hashed");
        System.out.println("boot: manifest sha256 " + HexFormat.of().formatHex(manifest.digest()) + " over "
                + LINES.size() + " lines");
        System.out.println("boot: content sha256 " + HexFormat.of().formatHex(content.digest()) + " over "
                + contentLines + " lines (without macOS's .Spotlight-V100, .fseventsd, .Trashes)");

        File k = new File("/boot/kernel8.img");
        if (k.isFile())
        {
            byte[] buf = new byte[64 * 1024];
            long r0 = System.nanoTime();
            long n = 0L;
            try (InputStream in = new FileInputStream(k))
            {
                int got = in.read(buf);
                while (got > 0)
                {
                    n += got;
                    got = in.read(buf);
                }
            }
            long ms = Math.max(1L, (System.nanoTime() - r0) / 1_000_000L);
            System.out.println("boot: read kernel8.img " + n + " bytes in " + ms + " ms (" + n / ms + " KB/s)");
        }

        System.out.println("boot: CONFIG.TXT exists (case-insensitive) = " + new File("/boot/CONFIG.TXT").exists());
        Path cfg = Path.of("/boot/config.txt");
        if (Files.exists(cfg))
        {
            String first = Files.readAllLines(cfg).stream().filter(l -> !l.isBlank()).findFirst().orElse("");
            System.out.println("boot: nio config.txt " + Files.size(cfg) + " bytes, first line: " + first);
        }
        try (Stream<Path> s = Files.list(Path.of("/boot")))
        {
            System.out.println("boot: nio Files.list count = " + s.count());
        }
        try (FileOutputStream out = new FileOutputStream("/boot/should-not-exist.txt"))
        {
            System.out.println("boot: WRONG -- a write under /boot was allowed");
        }
        catch (IOException e)
        {
            System.out.println("boot: write refused: " + e.getMessage());
        }
        try
        {
            Files.delete(cfg);
            System.out.println("boot: WRONG -- a delete under /boot was allowed");
        }
        catch (IOException e)
        {
            System.out.println("boot: delete refused: " + e);
        }
    }

    private static void walk(File d, String rel) throws Exception
    {
        String[] names = d.list();
        if (names == null)
        {
            return;
        }
        java.util.Arrays.sort(names);
        for (String name : names)
        {
            File f = new File(d, name);
            String p = rel + "/" + name;
            if (f.isDirectory())
            {
                dirs += 1;
                walk(f, p);
                continue;
            }
            files += 1;
            long size = f.length();
            String sha = "-";
            if (all || size <= HASH_LIMIT || p.equals("/kernel8.img"))
            {
                sha = sha(f);
                bytes += size;
            }
            LINES.add(sha + " " + size + " " + p);
        }
    }

    /**
     * The folders macOS keeps on a card it mounts. Spotlight rewrites its index even while the card sits idle on a
     * Mac, so a host digest taken before an eject does not match the card after it; the CONTENT digest leaves them
     * out.
     */
    private static boolean macMetadata(String p)
    {
        return p.startsWith("/.Spotlight-V100/") || p.startsWith("/.fseventsd/") || p.startsWith("/.Trashes/");
    }

    /** The path of a {@code <sha> <size> <path>} line; a path may contain spaces. */
    private static String path(String line)
    {
        int a = line.indexOf(' ');
        return line.substring(line.indexOf(' ', a + 1) + 1);
    }

    private static String sha(File f) throws Exception
    {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(f))
        {
            int n = in.read(buf);
            while (n > 0)
            {
                md.update(buf, 0, n);
                n = in.read(buf);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
