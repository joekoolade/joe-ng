/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-16
 */
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * {@code java.io.File} over the VM's writable tree: existence, file versus directory, length, listing (sorted, so
 * a filesystem's readdir order does not matter), canonical paths through "." and "..", access checks, and the
 * mutators -- mkdir/mkdirs, createNewFile, renameTo, setLastModified, setExecutable, setReadOnly, delete -- on
 * scratch paths, so the baked files are left as they were. The argument is the tree's root: empty on the metal
 * (the image's tree), and on a host a FRESH WRITABLE copy of {@code ramfs/} with an empty {@code tmp/}, as the VM
 * has. Paths are printed relative to the root; no file's contents are printed except the first line of
 * /data/sample.txt. Nothing prints a write-permission answer after a chmod: the VM's user is root, a host's is not.
 *
 * <p>The host control, on macOS:
 * <pre>
 *   rm -rf /private/tmp/fpw &amp;&amp; mkdir -p /private/tmp/fpw/tmp
 *   cp -R ramfs/etc ramfs/data ramfs/lib ramfs/conf ramfs/plugins /private/tmp/fpw/
 *   java -cp out FileProbe /private/tmp/fpw
 * </pre>
 * (the root must not pass through a symlink such as {@code /tmp}, or the canonical paths differ).
 */
public class FileProbe
{
    static String root = "";

    static File f(String p)
    {
        return new File(root + p);
    }

    static String rel(String p)
    {
        return p.startsWith(root) ? p.substring(root.length()) : p;
    }

    static void describe(String p)
    {
        File x = f(p);
        System.out.println(p + ": exists=" + x.exists() + " file=" + x.isFile() + " dir=" + x.isDirectory()
                + (x.isFile() ? " length=" + x.length() : "") + " read=" + x.canRead() + " write=" + x.canWrite()
                + " hidden=" + x.isHidden());
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            root = args[0];
        }
        describe("/");
        describe("/data");
        describe("/data/sample.txt");
        describe("/data/nums.txt");
        describe("/etc/motd");
        describe("/etc");
        describe("/data/missing.txt");
        describe("/dat");
        describe("/data/sample.txt/x");

        String[] top = f("/").list();
        Arrays.sort(top);
        System.out.println("list / " + Arrays.toString(top));
        String[] data = f("/data").list();
        Arrays.sort(data);
        System.out.println("list /data " + Arrays.toString(data));
        System.out.println("list of a file " + f("/data/sample.txt").list());
        System.out.println("list of nothing " + f("/nothing").list());
        File[] files = f("/data").listFiles(File::isFile);
        Arrays.sort(files);
        StringBuilder sb = new StringBuilder();
        for (File x : files)
        {
            sb.append(rel(x.getPath())).append(' ');
        }
        System.out.println("listFiles(isFile) /data " + sb.toString().trim());

        System.out.println("canonical " + rel(f("/data/./../etc/motd").getCanonicalPath()));
        System.out.println("canonical dir " + rel(f("/data/").getCanonicalPath()));
        System.out.println("absolute " + rel(f("/data/sample.txt").getAbsolutePath()));
        System.out.println("parent " + rel(f("/data/sample.txt").getParent()) + " name " + f("/data/sample.txt").getName());

        File dir = f("/data/newdir");
        System.out.println("mkdir " + dir.mkdir() + " again " + dir.mkdir() + " isDirectory " + dir.isDirectory());
        File a = f("/data/newdir/a.txt");
        System.out.println("createNewFile " + a.createNewFile() + " again " + a.createNewFile() + " length "
                + a.length() + " isFile " + a.isFile());
        try
        {
            System.out.println("createNewFile in a missing directory " + f("/nodir/x.txt").createNewFile());
        }
        catch (IOException e)
        {
            System.out.println("createNewFile in a missing directory -> IOException");
        }
        File b = f("/data/newdir/b.txt");
        System.out.println("renameTo " + a.renameTo(b) + " old " + a.exists() + " new " + b.exists());
        System.out.println("setLastModified " + b.setLastModified(1000L) + " lastModified " + b.lastModified());
        System.out.println("setExecutable " + b.setExecutable(true) + " canExecute " + b.canExecute());
        System.out.println("setReadOnly " + b.setReadOnly());
        String[] inDir = dir.list();
        System.out.println("list newdir " + Arrays.toString(inDir));
        System.out.println("delete non-empty dir " + dir.delete());
        System.out.println("delete file " + b.delete() + " delete dir " + dir.delete() + " gone " + !dir.exists());
        System.out.println("delete missing " + f("/data/missing.txt").delete());
        System.out.println("setLastModified missing " + f("/data/missing.txt").setLastModified(1000L));
        System.out.println("mkdirs " + f("/data/x/y/z").mkdirs() + " " + f("/data/x/y").isDirectory());
        System.out.println("createNewFile existing " + f("/data/sample.txt").createNewFile());
        System.out.println("still there " + f("/data/sample.txt").exists());

        long len = f("/data/sample.txt").length();
        try (FileInputStream in = new FileInputStream(f("/data/sample.txt")))
        {
            byte[] all = in.readAllBytes();
            System.out.println("read " + all.length + " bytes, equals length " + (all.length == len));
            String first = new String(all, "UTF-8").split("\n")[0];
            System.out.println("first line " + first);
        }
        System.out.println("FileProbe done");
    }
}
