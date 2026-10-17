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
package jdk.internal.misc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import magic.Magic;

/**
 * The filesystem this VM has: the read-only file table the image writer bakes from the repo's {@code ramfs/} tree
 * ({@code BuildRuntimeImage.embedRamfs}). Each entry is a regular file keyed by its absolute path, sorted by path;
 * there are no directory entries, so a DIRECTORY is implicit -- {@code /} and every proper prefix of a file's path
 * that ends at a {@code /}. There are no links, modes or timestamps, and nothing can be written.
 *
 * <p>The questions a filesystem answers -- does it exist, is it a directory, how long, what is in it -- are
 * answered here in Java over that table, so every file API (java.io's {@code UnixFileSystem}, later java.nio.file)
 * asks the same place. Paths are compared as their UTF-8 bytes, which is how the writer stored them.
 */
public final class MetalRamfs
{
    private MetalRamfs()
    {
    }

    /** VM native ({@code VMNatives.ramfsTable}): {@code k == 0} the table's address, {@code k == 1} its count. */
    private static native long table0(long k);

    private static final int ENTRY = 32;

    /** The entry for regular file {@code path} (absolute, normalized), or 0. */
    public static long find(String path)
    {
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        long dir = table0(0L);
        int n = (int) table0(1L);
        for (int i = 0; i < n; i++)
        {
            long e = dir + (long) i * ENTRY;
            if (Magic.load64(e + 8L) == p.length && bytesAt(Magic.load64(e), p, p.length))
            {
                return e;
            }
        }
        return 0L;
    }

    /** True for {@code /} and for every directory a file's path passes through. */
    public static boolean isDirectory(String path)
    {
        if (path.equals("/"))
        {
            return true;
        }
        byte[] prefix = (path + "/").getBytes(StandardCharsets.UTF_8);
        long dir = table0(0L);
        int n = (int) table0(1L);
        for (int i = 0; i < n; i++)
        {
            long e = dir + (long) i * ENTRY;
            if (Magic.load64(e + 8L) > prefix.length && bytesAt(Magic.load64(e), prefix, prefix.length))
            {
                return true;
            }
        }
        return false;
    }

    /** The byte length of file entry {@code e}. */
    public static long size(long e)
    {
        return Magic.load64(e + 24L);
    }

    /** The names directly inside directory {@code path}, in path order, each once; null if it is not one. */
    public static String[] list(String path)
    {
        if (!isDirectory(path))
        {
            return null;
        }
        String prefix = path.equals("/") ? "/" : path + "/";
        byte[] pb = prefix.getBytes(StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        long dir = table0(0L);
        int n = (int) table0(1L);
        for (int i = 0; i < n; i++)
        {
            long e = dir + (long) i * ENTRY;
            int len = (int) Magic.load64(e + 8L);
            long name = Magic.load64(e);
            if (len > pb.length && bytesAt(name, pb, pb.length))
            {
                int end = pb.length;
                while (end < len && (Magic.load8(name + end) & 0xFF) != '/')
                {
                    end += 1;
                }
                String child = utf8(name + pb.length, end - pb.length);
                if (out.isEmpty() || !out.get(out.size() - 1).equals(child))
                {
                    out.add(child);                      // sorted table: a directory's files are adjacent
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /** Every byte the table stores. */
    public static long totalBytes()
    {
        long dir = table0(0L);
        int n = (int) table0(1L);
        long sum = 0L;
        for (int i = 0; i < n; i++)
        {
            sum += Magic.load64(dir + (long) i * ENTRY + 24L);
        }
        return sum;
    }

    // ---- the unix-dispatcher view (sun.nio.fs.UnixNativeDispatcher) ------------------------------------------

    /** The NUL-terminated UTF-8 string at native address {@code addr} (a NativeBuffer the dispatcher filled). */
    public static String cstring(long addr)
    {
        Unsafe u = Unsafe.getUnsafe();
        int n = 0;
        while (u.getByte(addr + n) != 0)
        {
            n += 1;
        }
        byte[] b = new byte[n];
        for (int k = 0; k < n; k++)
        {
            b[k] = u.getByte(addr + k);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** "." and ".." resolved lexically, duplicate and trailing slashes dropped; the table has no links. */
    public static String normalize(String path)
    {
        List<String> out = new ArrayList<>();
        for (String c : path.split("/"))
        {
            if (c.isEmpty() || c.equals("."))
            {
                continue;
            }
            if (c.equals(".."))
            {
                if (!out.isEmpty())
                {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(c);
        }
        return "/" + String.join("/", out);
    }

    /**
     * A stable inode number, unique per entry, as isSameFile's file key needs: a file is its table index + 1; a
     * directory is the index of the first file beneath it, scaled, plus its depth -- distinct because two
     * directories over the same first file are different prefixes of it, so differ in depth. 0 if absent.
     */
    public static long inode(String path)
    {
        long dir = table0(0L);
        int n = (int) table0(1L);
        long e = find(path);
        if (e != 0L)
        {
            return (e - dir) / ENTRY + 1L;
        }
        if (!isDirectory(path))
        {
            return 0L;
        }
        byte[] prefix = (path.equals("/") ? "/" : path + "/").getBytes(StandardCharsets.UTF_8);
        int depth = path.equals("/") ? 0 : path.split("/").length - 1;
        for (int i = 0; i < n; i++)
        {
            long f = dir + (long) i * ENTRY;
            if (Magic.load64(f + 8L) > prefix.length && bytesAt(Magic.load64(f), prefix, prefix.length))
            {
                return 1_000_000_000L + (long) i * 256L + depth;
            }
        }
        return 0L;
    }

    private static final java.util.Map<Long, String[]> DIRS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Long, int[]> DIR_POS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_DIR = new java.util.concurrent.atomic.AtomicLong(1);

    /** opendir: a handle over the directory's names, or 0 if it is not one. */
    public static long opendir(String path)
    {
        String[] names = list(path);
        if (names == null)
        {
            return 0L;
        }
        long h = NEXT_DIR.getAndIncrement();
        DIRS.put(h, names);
        DIR_POS.put(h, new int[1]);
        return h;
    }

    /** readdir: the next name as bytes, or null at the end (the C returns NULL then). */
    public static byte[] readdir(long h)
    {
        String[] names = DIRS.get(h);
        int[] pos = DIR_POS.get(h);
        if (names == null || pos[0] >= names.length)
        {
            return null;
        }
        String name = names[pos[0]];
        pos[0] += 1;
        return name.getBytes(StandardCharsets.UTF_8);
    }

    /** closedir: false if {@code h} is not open. */
    public static boolean closedir(long h)
    {
        DIR_POS.remove(h);
        return DIRS.remove(h) != null;
    }

    // ---- file descriptors: what open() hands out, and what read/pread/lseek/fstat/close take ---------------

    /** fds start here, clear of 0/1/2 and of any descriptor the VM's own consoles use. */
    private static final int FIRST_FD = 100;
    private static final java.util.Map<Integer, long[]> FDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, String> FD_PATHS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_FD =
            new java.util.concurrent.atomic.AtomicInteger(FIRST_FD);

    /** open(O_RDONLY): a new fd over {@code path} -- a file's entry, or 0 for a directory -- positioned at 0. */
    public static int open(String path)
    {
        int fd = NEXT_FD.getAndIncrement();
        FDS.put(fd, new long[] { find(path), 0L });
        FD_PATHS.put(fd, path);
        return fd;
    }

    /** close: false if {@code fd} is not open. */
    public static boolean close(int fd)
    {
        FD_PATHS.remove(fd);
        return FDS.remove(fd) != null;
    }

    /** The path {@code fd} was opened on, or null if it is not open. */
    public static String fdPath(int fd)
    {
        return FD_PATHS.get(fd);
    }

    /** True if {@code fd} is open on a directory (reading it is EISDIR). */
    public static boolean fdIsDirectory(int fd)
    {
        long[] f = FDS.get(fd);
        return f != null && f[0] == 0L;
    }

    /** The byte length of {@code fd}'s file, -1 if not open, 0 for a directory. */
    public static long fdSize(int fd)
    {
        long[] f = FDS.get(fd);
        if (f == null)
        {
            return -1L;
        }
        return f[0] == 0L ? 0L : size(f[0]);
    }

    /** {@code fd}'s position, -1 if not open. */
    public static long position(int fd)
    {
        long[] f = FDS.get(fd);
        return f == null ? -1L : f[1];
    }

    /** lseek(SEEK_SET): set {@code fd}'s position; false if not open. A position past the end is allowed. */
    public static boolean seek(int fd, long pos)
    {
        long[] f = FDS.get(fd);
        if (f == null)
        {
            return false;
        }
        f[1] = pos;
        return true;
    }

    /**
     * pread: up to {@code len} bytes of {@code fd}'s file from {@code pos} into native memory at {@code addr}; the
     * count, 0 at or past the end, -1 if {@code fd} is not open. The position is not moved.
     */
    public static int pread(int fd, long addr, int len, long pos)
    {
        long[] f = FDS.get(fd);
        if (f == null)
        {
            return -1;
        }
        long sz = f[0] == 0L ? 0L : size(f[0]);
        if (pos >= sz || len <= 0)
        {
            return 0;
        }
        int n = (int) Math.min((long) len, sz - pos);
        long src = Magic.load64(f[0] + 16L) + pos;
        Unsafe u = Unsafe.getUnsafe();
        for (int k = 0; k < n; k++)
        {
            u.putByte(addr + k, (byte) Magic.load8(src + k));
        }
        return n;
    }

    /** read: {@link #pread} at {@code fd}'s position, which then advances by the count. */
    public static int read(int fd, long addr, int len)
    {
        long[] f = FDS.get(fd);
        if (f == null)
        {
            return -1;
        }
        int n = pread(fd, addr, len, f[1]);
        if (n > 0)
        {
            f[1] += n;
        }
        return n;
    }

    private static boolean bytesAt(long addr, byte[] b, int n)
    {
        for (int k = 0; k < n; k++)
        {
            if ((Magic.load8(addr + k) & 0xFF) != (b[k] & 0xFF))
            {
                return false;
            }
        }
        return true;
    }

    private static String utf8(long addr, int n)
    {
        byte[] b = new byte[n];
        for (int k = 0; k < n; k++)
        {
            b[k] = (byte) Magic.load8(addr + k);
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
