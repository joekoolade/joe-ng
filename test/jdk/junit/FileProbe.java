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
 * {@code java.io.File} over a read-only tree: existence, file versus directory, length, listing (sorted, so a
 * filesystem's readdir order does not matter), canonical paths through "." and "..", access checks, and every
 * mutating call refused. The argument is the tree's root: empty on the metal (the baked RAMFS), and on a host a
 * READ-ONLY copy of {@code ramfs/} so the refusals agree. Paths are printed relative to the root; no file's
 * contents are printed except the first line of /data/sample.txt.
 *
 * <p>The host control needs a filesystem that is really read-only (EROFS): with files that are merely
 * {@code chmod a-w}, the owner can still chmod and utime them, so {@code setWritable}/{@code setLastModified}
 * answer true. On macOS:
 * <pre>
 *   mkdir -p T/tree &amp;&amp; cp -R ramfs/etc ramfs/data ramfs/lib ramfs/conf ramfs/plugins T/tree/
 *   hdiutil create -srcfolder T/tree -format UDRO -ov T/fp.dmg
 *   hdiutil attach -readonly -mountpoint /private/tmp/fpmnt T/fp.dmg
 *   java -cp out FileProbe /private/tmp/fpmnt; hdiutil detach /private/tmp/fpmnt
 * </pre>
 * (the mount point must not pass through a symlink such as {@code /tmp}, or the canonical paths differ).
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

        System.out.println("mkdir " + f("/data/newdir").mkdir());
        System.out.println("delete " + f("/data/sample.txt").delete());
        System.out.println("renameTo " + f("/data/sample.txt").renameTo(f("/data/renamed.txt")));
        System.out.println("setLastModified " + f("/data/sample.txt").setLastModified(1000L));
        System.out.println("setWritable " + f("/data/sample.txt").setWritable(true));
        try
        {
            System.out.println("createNewFile " + f("/data/new.txt").createNewFile());
        }
        catch (IOException e)
        {
            System.out.println("createNewFile -> IOException");
        }
        System.out.println("createNewFile existing " + f("/data/sample.txt").createNewFile());
        System.out.println("still there " + f("/data/sample.txt").exists() + " absent " + !f("/data/new.txt").exists());

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
