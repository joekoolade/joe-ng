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
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * The attribute side of {@code java.nio.file}: POSIX attributes (owner, group, permissions), the attribute-map form
 * ({@code Files.readAttributes(p, "posix:*")}), the owner, the file store (type and space), and the setters --
 * permissions, last-modified time, owner -- each read back, and one on a missing file. Owner, group, times and the
 * store's figures differ by machine, so only their KIND is printed (a non-null name, a non-negative number). The
 * argument is the tree's root: empty on the metal, on a host a fresh writable copy of {@code ramfs/} (see
 * FileProbe).
 */
public class NioAttrProbe
{
    static String root = "";

    static Path p(String s)
    {
        return Path.of(root + s);
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
        arm("posix permissions", () ->
        {
            PosixFileAttributes a = Files.readAttributes(sample, PosixFileAttributes.class);
            return PosixFilePermissions.toString(a.permissions()).replace('w', '-');
        });
        arm("posix owner named", () -> Files.readAttributes(sample, PosixFileAttributes.class).owner().getName() != null);
        arm("posix group named", () -> Files.readAttributes(sample, PosixFileAttributes.class).group().getName() != null);
        arm("getOwner named", () -> Files.getOwner(sample).getName() != null);
        arm("map size key", () ->
        {
            Map<String, Object> m = Files.readAttributes(sample, "posix:*");
            return m.get("size") + " has permissions=" + m.containsKey("permissions") + " has owner="
                    + m.containsKey("owner");
        });
        arm("lastModified non-negative", () -> Files.getLastModifiedTime(sample).toMillis() >= 0);
        arm("file store usable non-negative", () ->
        {
            FileStore fs = Files.getFileStore(sample);
            return (fs.getTotalSpace() >= 0) + " " + (fs.getUsableSpace() >= 0) + " readOnly=" + fs.isReadOnly();
        });
        arm("setPosixFilePermissions", () ->
        {
            Files.setPosixFilePermissions(sample, PosixFilePermissions.fromString("r--r-x---"));
            String now = PosixFilePermissions.toString(Files.getPosixFilePermissions(sample));
            Files.setPosixFilePermissions(sample, PosixFilePermissions.fromString("rw-r--r--"));
            return now;
        });
        arm("setLastModifiedTime", () ->
        {
            Files.setLastModifiedTime(sample, FileTime.fromMillis(1000L));
            return Files.getLastModifiedTime(sample).toMillis();
        });
        arm("setOwner", () ->
        {
            Files.setOwner(sample, Files.getOwner(sample));
            return "kept " + Files.getOwner(sample).getName().equals(
                    Files.readAttributes(sample, PosixFileAttributes.class).owner().getName());
        });
        arm("setPosixFilePermissions missing", () -> Files.setPosixFilePermissions(p("/data/missing.txt"),
                PosixFilePermissions.fromString("rw-r--r--")));
        System.out.println("NioAttrProbe done");
    }
}
