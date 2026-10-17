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
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * WRITING through {@code java.nio.file}: create, append, truncate, a positional write past the end (the gap must
 * read as zero), an overwrite in place, a write over a file that came from the image (copy-on-write: the bytes
 * change, the other files do not), copy (plain, replacing, with attributes), move of a file and of a directory
 * tree, a hard link (one file, two names), delete of a file still open (the channel keeps reading it), a file and a
 * directory created with owner-only modes and one under the umask, DELETE_ON_CLOSE, a recursive delete, java.io
 * seeing what nio wrote,
 * and the refusals a writable filesystem still gives -- an existing CREATE_NEW target, a missing parent, a directory
 * opened for writing, a non-empty directory deleted or replaced. Every scratch file is removed at the end and
 * /data listed, so the tree is left as it was found. The argument is the tree's root: empty on the metal, on a host
 * a fresh writable copy of {@code ramfs/} with an empty {@code tmp/} (see FileProbe). Paths print relative to the
 * root, exceptions by class only.
 *
 * <p>Files.createTempFile is NOT here: its names come from a SecureRandom, which this VM refuses to run unseeded,
 * and QEMU has no hardware RNG -- the arms above take the same create-with-mode path under fixed names instead.
 */
public class NioWriteProbe
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

    static String sorted(Path dir) throws IOException
    {
        try (Stream<Path> s = Files.list(dir))
        {
            return s.map(NioWriteProbe::rel).sorted().collect(Collectors.toList()).toString();
        }
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            root = args[0];
        }
        Path w = p("/data/w.txt");
        Path sample = p("/data/sample.txt");
        arm("create", () -> rel(Files.writeString(w, "hello")) + " size " + Files.size(w) + " " + Files.readString(w));
        arm("append", () ->
        {
            Files.writeString(w, " world", StandardOpenOption.APPEND);
            return Files.readString(w);
        });
        arm("truncate existing", () ->
        {
            Files.writeString(w, "bye", StandardOpenOption.TRUNCATE_EXISTING);
            return Files.readString(w) + " size " + Files.size(w);
        });
        arm("create new over existing", () -> Files.writeString(w, "x", StandardOpenOption.CREATE_NEW));
        arm("missing parent", () -> Files.writeString(p("/nodir/x.txt"), "x"));
        arm("directory for writing", () -> Files.writeString(p("/data"), "x"));
        arm("java.io sees it", () ->
        {
            File f = new File(root + "/data/w.txt");
            return f.exists() + " " + f.isFile() + " " + f.length();
        });
        arm("positional write past end", () ->
        {
            try (FileChannel ch = FileChannel.open(p("/data/gap.bin"), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, StandardOpenOption.READ))
            {
                int n = ch.write(ByteBuffer.wrap(new byte[] { 'A', 'B' }), 10);
                ByteBuffer all = ByteBuffer.allocate(16);
                ch.read(all, 0);
                byte[] b = Arrays.copyOf(all.array(), (int) ch.size());
                return n + " size=" + ch.size() + " pos=" + ch.position() + " " + Arrays.toString(b);
            }
        });
        arm("channel truncate", () ->
        {
            try (FileChannel ch = FileChannel.open(p("/data/gap.bin"), StandardOpenOption.WRITE))
            {
                ch.position(11);
                ch.truncate(4);
                return "size=" + ch.size() + " pos=" + ch.position();
            }
        });
        arm("overwrite in place", () ->
        {
            Files.writeString(w, "abcdefgh");
            try (SeekableByteChannel ch = Files.newByteChannel(w, StandardOpenOption.WRITE))
            {
                ch.position(2);
                ch.write(ByteBuffer.wrap("XY".getBytes(StandardCharsets.UTF_8)));
                return "pos=" + ch.position() + " " + Files.readString(w);
            }
        });
        arm("overwrite an image file", () ->
        {
            byte[] before = Files.readAllBytes(sample);
            Path nums = p("/data/nums.txt");
            String old = Files.readString(nums);
            Files.writeString(nums, "42\n");
            String now = Files.readString(nums);
            Files.writeString(nums, old);
            return now.trim() + " restored " + Files.readString(nums).equals(old) + " sample unchanged "
                    + Arrays.equals(before, Files.readAllBytes(sample));
        });
        arm("buffered writer", () ->
        {
            try (BufferedWriter bw = Files.newBufferedWriter(p("/data/lines.txt")))
            {
                bw.write("one");
                bw.newLine();
                bw.write("two");
                bw.newLine();
            }
            List<String> lines = Files.readAllLines(p("/data/lines.txt"));
            return lines + " " + Files.size(p("/data/lines.txt"));
        });
        arm("copy", () ->
        {
            Path c = Files.copy(sample, p("/data/copy.txt"));
            return rel(c) + " same bytes " + Arrays.equals(Files.readAllBytes(sample), Files.readAllBytes(c))
                    + " same file " + Files.isSameFile(sample, c);
        });
        arm("copy over existing", () -> Files.copy(sample, p("/data/copy.txt")));
        arm("copy replacing", () ->
        {
            Files.copy(w, p("/data/copy.txt"), StandardCopyOption.REPLACE_EXISTING);
            return Files.readString(p("/data/copy.txt"));
        });
        arm("copy attributes", () ->
        {
            Files.setLastModifiedTime(w, FileTime.fromMillis(5000L));
            Path c = Files.copy(w, p("/data/copy2.txt"), StandardCopyOption.COPY_ATTRIBUTES);
            return Files.getLastModifiedTime(c).toMillis() + " " + Files.readString(c);
        });
        arm("move file", () ->
        {
            Files.move(p("/data/copy2.txt"), p("/data/moved.txt"));
            return Files.exists(p("/data/copy2.txt")) + " " + Files.readString(p("/data/moved.txt"));
        });
        arm("move directory tree", () ->
        {
            Files.createDirectories(p("/data/d1/d2"));
            Files.writeString(p("/data/d1/d2/f.txt"), "deep");
            Files.move(p("/data/d1"), p("/data/m1"));
            return Files.exists(p("/data/d1")) + " " + Files.readString(p("/data/m1/d2/f.txt")) + " "
                    + sorted(p("/data/m1"));
        });
        arm("move onto non-empty directory", () ->
        {
            Files.createDirectory(p("/data/d3"));
            return Files.move(p("/data/d3"), p("/data/m1"), StandardCopyOption.REPLACE_EXISTING);
        });
        arm("delete non-empty directory", () ->
        {
            Files.delete(p("/data/m1"));
            return "deleted";
        });
        arm("hard link", () ->
        {
            Path l = Files.createLink(p("/data/link.txt"), w);
            Files.writeString(l, "via link");
            return Files.isSameFile(l, w) + " " + Files.readString(w) + " nlink=" + Files.getAttribute(w, "unix:nlink");
        });
        arm("delete while open", () ->
        {
            try (FileChannel ch = FileChannel.open(p("/data/link.txt"), StandardOpenOption.READ))
            {
                Files.delete(p("/data/link.txt"));
                ByteBuffer b = ByteBuffer.allocate(8);
                int n = ch.read(b);
                return Files.exists(p("/data/link.txt")) + " read " + n + " "
                        + new String(b.array(), 0, n, StandardCharsets.UTF_8) + " nlink="
                        + Files.getAttribute(w, "unix:nlink");
            }
        });
        arm("owner-only file", () ->
        {
            Path t = Files.createFile(p("/tmp/own.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            String r = rel(t.getParent()) + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(t))
                    + " size " + Files.size(t);
            Files.delete(t);
            return r;
        });
        arm("owner-only directory", () ->
        {
            Path t = Files.createDirectory(p("/tmp/owndir"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            String r = Files.isDirectory(t) + " " + PosixFilePermissions.toString(Files.getPosixFilePermissions(t));
            Files.delete(t);
            return r;
        });
        arm("umask applies", () ->
        {
            Path t = Files.createFile(p("/tmp/um.txt"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-")));
            String r = PosixFilePermissions.toString(Files.getPosixFilePermissions(t));
            Files.delete(t);
            return r;
        });
        arm("delete on close", () ->
        {
            Path d = p("/data/doc.txt");
            try (SeekableByteChannel ch = Files.newByteChannel(d, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE))
            {
                ch.write(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
            }
            return Files.exists(d);
        });
        arm("recursive delete", () ->
        {
            Files.walkFileTree(p("/data/m1"), new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException
                {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return Files.exists(p("/data/m1"));
        });
        arm("cleanup", () ->
        {
            for (String s : new String[] { "/data/w.txt", "/data/gap.bin", "/data/lines.txt", "/data/copy.txt",
                    "/data/moved.txt", "/data/d3" })
            {
                Files.delete(p(s));
            }
            return sorted(p("/data")) + " tmp " + sorted(p("/tmp"));
        });
        System.out.println("NioWriteProbe done");
    }
}
