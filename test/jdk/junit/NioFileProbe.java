/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-17
 */
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code java.nio.file} over a read-only tree: Path operations, existence and kind, size, basic attributes,
 * directory listing and walking (sorted, so readdir order does not matter), real paths, and every mutating call
 * refused. READING a file's contents (through FileChannel) is NioReadProbe's. The argument is the tree's root: empty on the metal (the
 * baked RAMFS), and on a host a READ-ONLY mount of {@code ramfs/} (see FileProbe for the recipe), so the refusals
 * agree. Paths are printed relative to the root, exceptions by class only; of file contents only the first line of
 * /data/sample.txt is printed.
 */
public class NioFileProbe
{
    static String root = "";

    static Path p(String s)
    {
        return Path.of(root + s);
    }

    static String rel(Path x)
    {
        String s = x.toString();
        return s.startsWith(root) ? s.substring(root.length()) : s;
    }

    interface Arm
    {
        Object call() throws Exception;
    }

    static void arm(String what, Arm a)
    {
        try
        {
            System.out.println(what + " -> " + a.call());
        }
        catch (Exception e)
        {
            System.out.println(what + " -> " + e.getClass().getName());
        }
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            root = args[0];
        }
        Path sample = p("/data/sample.txt");
        System.out.println("fileName " + sample.getFileName() + " parent " + rel(sample.getParent())
                + " nameCount " + Path.of("/data/sample.txt").getNameCount());
        System.out.println("normalize " + Path.of("/data/./x/../sample.txt").normalize());
        System.out.println("relativize " + Path.of("/data").relativize(Path.of("/etc/motd")));
        System.out.println("resolve " + Path.of("/data").resolve("nums.txt"));
        System.out.println("absolute " + Path.of("/data").isAbsolute() + " " + Path.of("data").isAbsolute());

        arm("exists sample", () -> Files.exists(sample));
        arm("exists missing", () -> Files.exists(p("/data/missing.txt")));
        arm("notExists missing", () -> Files.notExists(p("/data/missing.txt")));
        arm("isDirectory /data", () -> Files.isDirectory(p("/data")));
        arm("isDirectory sample", () -> Files.isDirectory(sample));
        arm("isRegularFile sample", () -> Files.isRegularFile(sample));
        arm("isRegularFile /etc", () -> Files.isRegularFile(p("/etc")));
        arm("isReadable sample", () -> Files.isReadable(sample));
        arm("isWritable sample", () -> Files.isWritable(sample));
        arm("size sample", () -> Files.size(sample));
        arm("size missing", () -> Files.size(p("/data/missing.txt")));
        arm("attrs sample", () ->
        {
            BasicFileAttributes a = Files.readAttributes(sample, BasicFileAttributes.class);
            return "dir=" + a.isDirectory() + " regular=" + a.isRegularFile() + " link=" + a.isSymbolicLink()
                    + " size=" + a.size();
        });
        arm("attrs /data", () ->
        {
            BasicFileAttributes a = Files.readAttributes(p("/data"), BasicFileAttributes.class);
            return "dir=" + a.isDirectory() + " regular=" + a.isRegularFile();
        });
        arm("list /data", () ->
        {
            try (Stream<Path> s = Files.list(p("/data")))
            {
                return s.map(NioFileProbe::rel).sorted().collect(Collectors.toList());
            }
        });
        arm("directoryStream /etc", () ->
        {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p("/etc")))
            {
                for (Path x : ds)
                {
                    names.add(x.getFileName().toString());
                }
            }
            Collections.sort(names);
            return names.contains("motd") + " " + names.contains("init");
        });
        arm("walk /data", () ->
        {
            try (Stream<Path> s = Files.walk(p("/data")))
            {
                return s.map(NioFileProbe::rel).sorted().collect(Collectors.toList());
            }
        });
        arm("list a file", () ->
        {
            try (Stream<Path> s = Files.list(sample))
            {
                return s.count();
            }
        });
        arm("toRealPath", () -> rel(p("/data/../etc/motd").toRealPath()));
        arm("isSameFile", () -> Files.isSameFile(sample, p("/data/./sample.txt")));
        arm("write", () -> Files.writeString(p("/data/new.txt"), "x"));
        arm("createDirectory", () -> Files.createDirectory(p("/data/newdir")));
        arm("delete", () ->
        {
            Files.delete(sample);
            return "deleted";
        });
        arm("still there", () -> Files.exists(sample));
        System.out.println("NioFileProbe done");
    }
}
