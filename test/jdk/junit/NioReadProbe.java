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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * READING a file through {@code java.nio.file}: bytes, a string, lines (a list and a stream), an InputStream, a
 * BufferedReader, a SeekableByteChannel (position, size, a positional re-read) and a FileChannel (a heap and a direct
 * buffer, read past the end), a read of a missing file, a channel opened for writing (which must not truncate), and a read by an
 * already-interrupted thread, which must close the channel with ClosedByInterruptException (the interruptible-channel
 * protocol: Thread.blockedOn / interrupt). The argument
 * is the tree's root: empty on the metal (the image's tree), on a host a fresh writable copy of {@code ramfs/} (see
 * FileProbe). Of file contents only the first line of /data/sample.txt and a few of its bytes are printed.
 */
public class NioReadProbe
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
        arm("readAllBytes sample", () -> Files.readAllBytes(sample).length);
        arm("readString first line", () -> Files.readString(sample).split("\n")[0]);
        arm("readAllLines nums", () -> Files.readAllLines(p("/data/nums.txt")).size());
        arm("lines sample count", () ->
        {
            try (Stream<String> s = Files.lines(sample))
            {
                return s.count();
            }
        });
        arm("newInputStream", () ->
        {
            try (InputStream in = Files.newInputStream(sample))
            {
                byte[] b = new byte[3];
                int n = in.read(b);
                return n + " " + new String(b, 0, n, StandardCharsets.UTF_8);
            }
        });
        arm("newBufferedReader", () ->
        {
            try (BufferedReader r = Files.newBufferedReader(sample))
            {
                return r.readLine();
            }
        });
        arm("read missing", () -> Files.readAllBytes(p("/data/missing.txt")).length);
        arm("seekable channel", () ->
        {
            try (SeekableByteChannel ch = Files.newByteChannel(sample))
            {
                ByteBuffer b = ByteBuffer.allocate(4);
                int n = ch.read(b);
                long pos = ch.position();
                ch.position(4);
                ByteBuffer c = ByteBuffer.allocate(5);
                ch.read(c);
                return n + " pos=" + pos + " size=" + ch.size() + " at4=" + new String(c.array(), StandardCharsets.UTF_8);
            }
        });
        arm("file channel direct", () ->
        {
            try (FileChannel ch = FileChannel.open(sample, StandardOpenOption.READ))
            {
                ByteBuffer d = ByteBuffer.allocateDirect(200);
                int total = 0;
                int n;
                while ((n = ch.read(d)) > 0)
                {
                    total += n;
                }
                return total + " then " + n + " size=" + ch.size();
            }
        });
        arm("file channel positional", () ->
        {
            try (FileChannel ch = FileChannel.open(sample, StandardOpenOption.READ))
            {
                ByteBuffer b = ByteBuffer.allocate(3);
                int n = ch.read(b, 10);
                return n + " " + new String(b.array(), StandardCharsets.UTF_8) + " pos=" + ch.position()
                        + " past end=" + ch.read(ByteBuffer.allocate(4), 1000);
            }
        });
        arm("channel for write", () ->
        {
            try (FileChannel ch = FileChannel.open(sample, StandardOpenOption.WRITE))
            {
                return "opened size=" + ch.size();
            }
        });
        arm("read while interrupted", () ->
        {
            FileChannel ch = FileChannel.open(sample, StandardOpenOption.READ);
            Thread.currentThread().interrupt();
            try
            {
                ch.read(ByteBuffer.allocate(4));
                return "read without exception";
            }
            catch (java.nio.channels.ClosedByInterruptException e)
            {
                return "ClosedByInterruptException open=" + ch.isOpen() + " interrupted=" + Thread.interrupted();
            }
        });
        arm("interrupt cleared", () -> Thread.currentThread().isInterrupted());
        System.out.println("NioReadProbe done");
    }
}
