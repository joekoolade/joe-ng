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
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The java.io STREAMS over the VM's writable tree: FileInputStream (a file from the image, a file nio wrote, one nio
 * deleted, skip/available, its channel's position and size), FileOutputStream (create, truncate, append, a write
 * to the console through FileDescriptor.out), RandomAccessFile (write, seek, read back, length, setLength both
 * ways and where it leaves the pointer, a read-only file refusing a write), and the C's error messages -- a missing
 * file, a directory, a missing parent, a negative seek, a closed stream. Paths print relative to the root,
 * exceptions by class and message with the root removed. The argument is the tree's root: empty on the metal, on a
 * host a fresh writable copy of {@code ramfs/} with an empty {@code tmp/} (see FileProbe).
 */
public class IoStreamProbe
{
    static String root = "";

    static String f(String p)
    {
        return root + p;
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
            String m = e.getMessage();
            System.out.println(what + " -> " + e.getClass().getName()
                    + (m == null ? "" : ": " + m.replace(root, "")));
        }
    }

    static String text(String p) throws IOException
    {
        try (FileInputStream in = new FileInputStream(f(p)))
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length > 0)
        {
            root = args[0];
        }
        arm("read an image file", () -> text("/etc/motd").trim());
        arm("skip and available", () ->
        {
            try (FileInputStream in = new FileInputStream(f("/data/sample.txt")))
            {
                long s = in.skip(4);
                int a = in.available();
                int b = in.read();
                return s + " " + a + " " + (char) b + " pos=" + in.getChannel().position() + " size="
                        + in.getChannel().size();
            }
        });
        arm("read what nio wrote", () ->
        {
            Files.writeString(Path.of(f("/data/n.txt")), "from nio");
            return text("/data/n.txt");
        });
        arm("read what nio deleted", () ->
        {
            Files.delete(Path.of(f("/data/n.txt")));
            return text("/data/n.txt");
        });
        arm("missing file", () -> new FileInputStream(f("/data/missing.txt")));
        arm("a directory", () -> new FileInputStream(f("/data")));
        arm("output create", () ->
        {
            try (FileOutputStream out = new FileOutputStream(f("/data/o.txt")))
            {
                out.write("first".getBytes(StandardCharsets.UTF_8));
                out.write('!');
            }
            return text("/data/o.txt") + " " + new File(f("/data/o.txt")).length();
        });
        arm("output truncates", () ->
        {
            try (FileOutputStream out = new FileOutputStream(f("/data/o.txt")))
            {
                out.write("2nd".getBytes(StandardCharsets.UTF_8));
            }
            return text("/data/o.txt");
        });
        arm("output appends", () ->
        {
            try (FileOutputStream out = new FileOutputStream(f("/data/o.txt"), true))
            {
                out.write("+more".getBytes(StandardCharsets.UTF_8), 0, 5);
            }
            return text("/data/o.txt");
        });
        arm("output over an image file", () ->
        {
            String old = text("/data/nums.txt");
            try (FileOutputStream out = new FileOutputStream(f("/data/nums.txt")))
            {
                out.write("7\n".getBytes(StandardCharsets.UTF_8));
            }
            String now = text("/data/nums.txt").trim();
            try (FileOutputStream out = new FileOutputStream(f("/data/nums.txt")))
            {
                out.write(old.getBytes(StandardCharsets.UTF_8));
            }
            return now + " restored " + text("/data/nums.txt").equals(old);
        });
        arm("output to a directory", () -> new FileOutputStream(f("/data")));
        arm("output with a missing parent", () -> new FileOutputStream(f("/nodir/x.txt")));
        arm("write after close", () ->
        {
            FileOutputStream out = new FileOutputStream(f("/data/o.txt"));
            out.close();
            out.write(1);
            return "wrote";
        });
        arm("console through FileDescriptor.out", () ->
        {
            PrintStream ps = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
            ps.println("  (printed through fd 1)");
            return "ok";
        });
        arm("random access", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "rw"))
            {
                raf.write("0123456789".getBytes(StandardCharsets.UTF_8));
                raf.seek(3);
                raf.write('X');
                long ptr = raf.getFilePointer();
                raf.seek(0);
                byte[] all = new byte[(int) raf.length()];
                raf.readFully(all);
                return new String(all, StandardCharsets.UTF_8) + " ptr=" + ptr + " len=" + raf.length();
            }
        });
        arm("setLength shorter", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "rw"))
            {
                raf.seek(8);
                raf.setLength(5);
                return "len=" + raf.length() + " ptr=" + raf.getFilePointer();
            }
        });
        arm("setLength longer", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "rw"))
            {
                raf.seek(2);
                raf.setLength(8);
                raf.seek(0);
                byte[] all = new byte[8];
                raf.readFully(all);
                return "len=" + raf.length() + " " + Arrays.toString(all);
            }
        });
        arm("read past the end", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "r"))
            {
                raf.seek(100);
                return raf.read() + " ptr=" + raf.getFilePointer();
            }
        });
        arm("negative seek", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "r"))
            {
                raf.seek(-1);
                return "seeked";
            }
        });
        arm("read-only refuses a write", () ->
        {
            try (RandomAccessFile raf = new RandomAccessFile(f("/data/r.bin"), "r"))
            {
                raf.write(1);
                return "wrote";
            }
        });
        arm("read-only of a missing file", () -> new RandomAccessFile(f("/data/missing.bin"), "r"));
        arm("cleanup", () ->
        {
            boolean a = new File(f("/data/o.txt")).delete();
            boolean b = new File(f("/data/r.bin")).delete();
            String[] left = new File(f("/data")).list();
            Arrays.sort(left);
            return a + " " + b + " " + Arrays.toString(left);
        });
        System.out.println("IoStreamProbe done");
    }
}
