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
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Writes to {@code /boot} -- the SD card's FAT32 partition -- in the shapes that break a FAT driver, each checked by
 * reading it back, all under {@code /boot/wt} (removed first if a previous run left it). Then prints
 * {@code wt: <sha256> <size> <path>} for every file left, which the host compares with the image after
 * {@code fsck_msdos -n} says it is clean.
 *
 * <p>The shapes: a fragmented free list; a file grown and shrunk across cluster boundaries; a 10 MB file in 64 KB
 * writes; replace-by-rename with a larger and with a smaller file; a directory grown past its first cluster; long
 * names that collide on their {@code ~N} short name; unlink and reuse; a directory moved to another parent; a file
 * unlinked while open; moves and copies between {@code /boot} and {@code /tmp}.
 *
 * <p>{@code root=<dir>} runs the same shapes under another directory -- the host control, against the image mounted
 * by macOS's own FAT driver.
 */
public final class BootWriteDemo
{
    private BootWriteDemo()
    {
    }

    private static int failures;
    private static boolean host;
    private static Path WT = Path.of("/boot/wt");

    public static void main(String[] args) throws Exception
    {
        String root = "/boot";
        boolean list = false;
        for (String a : args)
        {
            if (a.startsWith("root="))
            {
                root = a.substring(5);                   // the host control: a mounted copy of the image
                host = true;
            }
            list = list || a.equals("list");
        }
        WT = Path.of(root, "wt");
        String[] top = new File(root).list();
        if (top == null || top.length == 0)
        {
            System.out.println("wt: /boot is empty -- " + jdk.internal.misc.MetalRamfs.bootError());
            return;
        }
        long t0 = System.nanoTime();
        if (Files.exists(WT))
        {
            removeTree(WT);
            System.out.println("wt: removed the previous run's /boot/wt");
        }
        Files.createDirectory(WT);

        fragmented();
        growShrink();
        big();
        replaceByRename();
        manyNames();
        collidingNames();
        unlinkReuse();
        moveDirectory();
        unlinkWhileOpen();
        acrossDevices();
        refusals();

        System.out.println("wt: shapes done in " + (System.nanoTime() - t0) / 1_000_000L + " ms, " + failures
                + " failure(s)");
        List<String> lines = new ArrayList<>();
        manifest(WT.toFile(), "/wt", lines);
        java.util.Collections.sort(lines, (x, y) -> path(x).compareTo(path(y)));
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        for (String l : lines)
        {
            all.update((l + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (list)
            {
                System.out.println("wt: " + l);
            }
        }
        System.out.println("wt: manifest sha256 " + HexFormat.of().formatHex(all.digest()) + " over " + lines.size()
                + " files");
        System.out.println(failures == 0 ? "wt: PASS" : "wt: FAIL");
    }

    // ---- shapes ---------------------------------------------------------------------------------------------

    /** Twenty files, every other one removed, then one file that must thread through the holes. */
    private static void fragmented() throws Exception
    {
        Path d = Files.createDirectory(WT.resolve("frag"));
        for (int i = 0; i < 20; i++)
        {
            Files.write(d.resolve("f" + i + ".bin"), pattern(i, 1500 + i));
        }
        for (int i = 0; i < 20; i += 2)
        {
            Files.delete(d.resolve("f" + i + ".bin"));
        }
        byte[] fill = pattern(99, 40000);
        Files.write(d.resolve("fill.bin"), fill);
        check("fragmented fill", Arrays.equals(Files.readAllBytes(d.resolve("fill.bin")), fill));
        for (int i = 1; i < 20; i += 2)
        {
            byte[] back = Files.readAllBytes(d.resolve("f" + i + ".bin"));
            check("fragmented f" + i, Arrays.equals(back, pattern(i, 1500 + i)));
        }
    }

    /** One file grown and shrunk across cluster boundaries, through RandomAccessFile, checked at every step. */
    private static void growShrink() throws Exception
    {
        File f = WT.resolve("grow.bin").toFile();
        byte[] model = new byte[0];
        int[] sizes = {1, 511, 512, 513, 4095, 4096, 4097, 70000, 4097, 4096, 1, 0, 10000, 33000};
        try (RandomAccessFile r = new RandomAccessFile(f, "rw"))
        {
            for (int step = 0; step < sizes.length; step++)
            {
                int n = sizes[step];
                if (n > model.length)
                {
                    byte[] add = pattern(step, n - model.length);
                    r.seek(model.length);
                    r.write(add);
                    byte[] m = Arrays.copyOf(model, n);
                    System.arraycopy(add, 0, m, model.length, add.length);
                    model = m;
                }
                else
                {
                    r.setLength(n);
                    model = Arrays.copyOf(model, n);
                }
                check("grow/shrink to " + n + " (length)", r.length() == n && f.length() == n);
                byte[] back = new byte[n];
                r.seek(0);
                r.readFully(back);
                check("grow/shrink to " + n + " (content)", Arrays.equals(back, model));
            }
            // overwrite in the middle across a sector and a cluster edge, then a gap past the end reads as zeros
            byte[] mid = pattern(77, 3000);
            r.seek(1000);
            r.write(mid);
            System.arraycopy(mid, 0, model, 1000, mid.length);
            r.seek(40000);
            r.write(new byte[] {1, 2, 3});
            byte[] m = Arrays.copyOf(model, 40003);
            m[40000] = 1;
            m[40001] = 2;
            m[40002] = 3;
            model = m;
            byte[] back = new byte[model.length];
            r.seek(0);
            r.readFully(back);
            check("overwrite + zero gap", Arrays.equals(back, model));
        }
    }

    /** 10 MB in 64 KB writes through FileOutputStream, read back by digest. */
    private static void big() throws Exception
    {
        File f = WT.resolve("big10m.bin").toFile();
        MessageDigest w = MessageDigest.getInstance("SHA-256");
        long t = System.nanoTime();
        try (OutputStream out = new FileOutputStream(f))
        {
            for (int i = 0; i < 160; i++)
            {
                byte[] chunk = pattern(1000 + i, 65536);
                w.update(chunk);
                out.write(chunk);
            }
        }
        long wms = Math.max(1L, (System.nanoTime() - t) / 1_000_000L);
        String want = HexFormat.of().formatHex(w.digest());
        t = System.nanoTime();
        String got = sha(f);
        long rms = Math.max(1L, (System.nanoTime() - t) / 1_000_000L);
        System.out.println("wt: 10 MB written in " + wms + " ms (" + 10485760L / wms + " KB/s), read in " + rms
                + " ms");
        check("10 MB digest", want.equals(got) && f.length() == 10485760L);
    }

    /** A larger and a smaller file each replace an existing one by Files.move(REPLACE_EXISTING). */
    private static void replaceByRename() throws Exception
    {
        // ATOMIC_MOVE is rename(2) over the target -- on the card, the one-sector switch of the target's entry.
        // REPLACE_EXISTING alone is NOT: java.nio deletes the target first and then renames into the gap.
        Path a = WT.resolve("kernel8.img");
        Files.write(a, pattern(1, 100000));
        Files.write(WT.resolve("kernel8.new"), pattern(2, 300000));
        Files.move(WT.resolve("kernel8.new"), a, StandardCopyOption.ATOMIC_MOVE);
        check("atomic replace with larger", Arrays.equals(Files.readAllBytes(a), pattern(2, 300000))
                && !Files.exists(WT.resolve("kernel8.new")));
        Path c = WT.resolve("Start4.ELF");
        Files.write(c, pattern(6, 200000));
        Files.write(WT.resolve("start4.new"), pattern(7, 20000));
        File newer = WT.resolve("start4.new").toFile();
        check("File.renameTo over a file", newer.renameTo(WT.resolve("start4.elf").toFile()));
        List<String> names = names(WT);
        // a replacing rename keeps the target's on-disk spelling here (as Linux's vfat does); macOS's takes the new one
        check("atomic replace with smaller: one name", names.contains("Start4.ELF") != names.contains("start4.elf"));
        check("atomic replace with smaller", Arrays.equals(Files.readAllBytes(c), pattern(7, 20000)));
        Path b = WT.resolve("Config.TXT");
        Files.write(b, pattern(3, 300000));
        Files.write(WT.resolve("config.new"), pattern(4, 50000));
        Files.move(WT.resolve("config.new"), WT.resolve("config.txt"), StandardCopyOption.REPLACE_EXISTING);
        names = names(WT);
        check("delete-then-rename takes the new spelling",
                names.contains("config.txt") && !names.contains("Config.TXT"));
        check("replace with smaller", Arrays.equals(Files.readAllBytes(b), pattern(4, 50000)));
        // a plain rename to a new name, which needs a long name
        Files.move(a, WT.resolve("kernel8-previous.img"));
        check("rename to a long name", Files.exists(WT.resolve("kernel8-previous.img")) && !Files.exists(a));
    }

    /** 300 long names in one directory: it must grow past its first cluster (each name is five slots). */
    private static void manyNames() throws Exception
    {
        Path d = Files.createDirectory(WT.resolve("many"));
        for (int i = 0; i < 300; i++)
        {
            Files.write(d.resolve(String.format("a rather long file name number %04d.dat", i)), pattern(i, i % 7));
        }
        check("300 names listed", names(d).size() == 300);
        check("name 0299 reads", Arrays.equals(Files.readAllBytes(d.resolve("a rather long file name number 0299.dat")),
                pattern(299, 299 % 7)));
    }

    /** Long names whose short basis is the same, plus exact 8.3 names in each case. */
    private static void collidingNames() throws Exception
    {
        Path d = Files.createDirectory(WT.resolve("names"));
        String[] names = {"Long File Name 1.txt", "Long File Name 2.txt", "Long File Name 3.txt", "LongFi~1.txt",
            "lower.txt", "UPPER.TXT", "MixedCase.Txt", "x", "dots.in.the.name", ".hidden", "spaces in it",
            "été café.txt", "abcdefghijklm", "abcdefghijklmn.op"};
        for (int i = 0; i < names.length; i++)
        {
            Files.write(d.resolve(names[i]), (names[i] + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        List<String> listed = names(d);
        for (String n : names)
        {
            check("name '" + n + "'", listed.contains(n) && new String(Files.readAllBytes(d.resolve(n)),
                    java.nio.charset.StandardCharsets.UTF_8).equals(n + "\n"));
        }
        check("lookup is case-insensitive", Files.exists(d.resolve("LONG FILE NAME 2.TXT")));
        if (host)
        {
            return;                                      // macOS's driver takes ':' (it is '/' in Finder)
        }
        try
        {
            Files.write(d.resolve("bad:name"), new byte[1]);
            check("a ':' in a name is refused", false);
        }
        catch (IOException e)
        {
            check("a ':' in a name is refused", true);
        }
    }

    /** Remove a few files and write new ones into the space they freed. */
    private static void unlinkReuse() throws Exception
    {
        Path d = Files.createDirectory(WT.resolve("reuse"));
        for (int i = 0; i < 6; i++)
        {
            Files.write(d.resolve("old" + i), pattern(i, 20000));
        }
        for (int i = 0; i < 6; i++)
        {
            Files.delete(d.resolve("old" + i));
        }
        for (int i = 0; i < 4; i++)
        {
            Files.write(d.resolve("new" + i), pattern(50 + i, 30000));
        }
        boolean ok = true;
        for (int i = 0; i < 4; i++)
        {
            ok = ok && Arrays.equals(Files.readAllBytes(d.resolve("new" + i)), pattern(50 + i, 30000));
        }
        check("unlink + reuse", ok && names(d).size() == 4);
    }

    /** A directory with contents moved under another parent; its ".." must follow. */
    private static void moveDirectory() throws Exception
    {
        Path from = Files.createDirectories(WT.resolve("d1/sub/deeper"));
        Files.write(from.resolve("leaf.txt"), pattern(5, 1234));
        Files.createDirectory(WT.resolve("d2"));
        Files.move(WT.resolve("d1/sub"), WT.resolve("d2/moved-sub"));
        check("directory move", Arrays.equals(Files.readAllBytes(WT.resolve("d2/moved-sub/deeper/leaf.txt")),
                pattern(5, 1234)) && !Files.exists(WT.resolve("d1/sub")));
        Files.delete(WT.resolve("d1"));
        try
        {
            Files.delete(WT.resolve("d2"));
            check("rmdir of a non-empty directory is refused", false);
        }
        catch (java.nio.file.DirectoryNotEmptyException e)
        {
            check("rmdir of a non-empty directory is refused", true);
        }
    }

    /** A file deleted while open stays readable to the end; its clusters are freed at the close. */
    private static void unlinkWhileOpen() throws Exception
    {
        Path p = WT.resolve("doomed.bin");
        byte[] want = pattern(8, 50000);
        Files.write(p, want);
        byte[] got = new byte[want.length];
        try (InputStream in = new FileInputStream(p.toFile()))
        {
            int n = in.read(got, 0, 1000);
            Files.delete(p);
            Files.write(WT.resolve("after-doomed.bin"), pattern(9, 50000));   // must not take its clusters
            while (n < got.length)
            {
                int k = in.read(got, n, got.length - n);
                if (k < 0)
                {
                    break;
                }
                n += k;
            }
        }
        check("unlink while open", Arrays.equals(got, want) && !Files.exists(p));
        Files.delete(WT.resolve("after-doomed.bin"));
    }

    /** Between /tmp and /boot a move is a copy and a delete (EXDEV underneath). */
    private static void acrossDevices() throws Exception
    {
        Path tmp = Path.of("/tmp/xdev.bin");
        Files.write(tmp, pattern(11, 9000));
        Files.move(tmp, WT.resolve("from-tmp.bin"));
        check("move /tmp -> /boot", !Files.exists(tmp)
                && Arrays.equals(Files.readAllBytes(WT.resolve("from-tmp.bin")), pattern(11, 9000)));
        Files.copy(WT.resolve("from-tmp.bin"), Path.of("/tmp/back.bin"));
        check("copy /boot -> /tmp", Arrays.equals(Files.readAllBytes(Path.of("/tmp/back.bin")), pattern(11, 9000)));
        Files.copy(Path.of("/tmp/back.bin"), WT.resolve("copied.bin"));
        check("copy /tmp -> /boot", Arrays.equals(Files.readAllBytes(WT.resolve("copied.bin")), pattern(11, 9000)));
        Files.delete(Path.of("/tmp/back.bin"));
    }

    /** FAT keeps no modes and no hard links. */
    private static void refusals() throws Exception
    {
        try
        {
            Files.createLink(WT.resolve("hard"), WT.resolve("copied.bin"));
            check("link refused", false);
        }
        catch (IOException e)
        {
            check("link refused", true);
        }
        long when = 1_700_000_000_000L;
        Files.setLastModifiedTime(WT.resolve("copied.bin"), java.nio.file.attribute.FileTime.fromMillis(when));
        check("mtime set", Files.getLastModifiedTime(WT.resolve("copied.bin")).toMillis() == when);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static void check(String what, boolean ok)
    {
        if (!ok)
        {
            failures += 1;
            System.out.println("wt: FAILED " + what);
        }
    }

    /** {@code n} bytes that differ by seed and position (and are not periodic in a sector). */
    static byte[] pattern(int seed, int n)
    {
        byte[] b = new byte[n];
        int x = seed * 0x9E3779B1 + 1;
        for (int i = 0; i < n; i++)
        {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            b[i] = (byte) x;
        }
        return b;
    }

    private static void removeTree(Path p) throws IOException
    {
        if (Files.isDirectory(p))
        {
            try (java.util.stream.Stream<Path> s = Files.list(p))
            {
                for (Path k : s.toList())
                {
                    removeTree(k);
                }
            }
        }
        Files.delete(p);
    }

    /** The names in {@code d} without the AppleDouble ({@code ._*}) and {@code .DS_Store} files macOS adds. */
    private static List<String> names(Path d)
    {
        List<String> out = new ArrayList<>();
        for (String n : d.toFile().list())
        {
            if (!n.startsWith("._") && !n.equals(".DS_Store"))
            {
                out.add(n);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static void manifest(File d, String rel, List<String> out) throws Exception
    {
        for (String n : names(d.toPath()))
        {
            File f = new File(d, n);
            if (f.isDirectory())
            {
                manifest(f, rel + "/" + n, out);
            }
            else
            {
                out.add(sha(f) + " " + f.length() + " " + rel + "/" + n);
            }
        }
    }

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
