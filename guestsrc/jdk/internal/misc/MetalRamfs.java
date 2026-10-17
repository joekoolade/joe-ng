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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import magic.Magic;

/**
 * The filesystem this VM has: one writable tree in memory, seeded from the file table the image writer bakes from
 * the repo's {@code ramfs/} tree ({@code BuildRuntimeImage.embedRamfs}). The table holds regular files keyed by
 * absolute path; its directories are implicit (every proper prefix of a path ending at a {@code /}). On first use
 * the table becomes a tree of {@link MetalFsNode}s -- files reading their content IN PLACE from the image until
 * they are first written -- plus an empty {@code /tmp}, where {@code java.io.tmpdir} points. Anything can then be
 * created, written, renamed, linked or removed. Nothing persists: the tree lives in the heap and a reboot starts
 * again from the image.
 *
 * <p>The questions a filesystem answers are answered here in Java, so every file API (java.io's
 * {@code UnixFileSystem}, java.nio.file's {@code UnixNativeDispatcher}, sun.nio.ch's {@code UnixFileDispatcherImpl})
 * asks the same place. Paths are absolute and normalized by the caller ({@link #normalize}); there are no symbolic
 * links. A failing call returns a NEGATIVE error code ({@link #NOENT} ...), which each caller maps to its own
 * errno table. One lock guards the tree and the descriptor tables.
 *
 * <p>Decisions where the image says nothing: baked files are mode 0644 and directories 0755 (what a checkout
 * gives them under the usual umask), owned 0/0 with every time 0; {@code /tmp} is 01777; new files and
 * directories take the requested mode under a umask of 022.
 */
public final class MetalRamfs
{
    private MetalRamfs()
    {
    }

    /** VM native ({@code VMNatives.ramfsTable}): {@code k == 0} the table's address, {@code k == 1} its count. */
    private static native long table0(long k);

    private static final int ENTRY = 32;
    private static final int UMASK = 022;

    // ---- error codes: returned negated; each caller maps them to its own errno ----------------------------
    public static final int NOENT = 1;
    public static final int EXIST = 2;
    public static final int NOTDIR = 3;
    public static final int ISDIR = 4;
    public static final int NOTEMPTY = 5;
    public static final int INVAL = 6;
    public static final int BADF = 7;
    public static final int PERM = 8;
    public static final int BUSY = 9;
    public static final int FBIG = 10;

    // ---- open flags --------------------------------------------------------------------------------------------
    public static final int O_READ = 1;
    public static final int O_WRITE = 2;
    public static final int O_CREAT = 4;
    public static final int O_EXCL = 8;
    public static final int O_TRUNC = 16;
    public static final int O_APPEND = 32;

    // ---- stat(): the indexes of the array it returns -------------------------------------------------------
    public static final int ST_DIR = 0;                  // 1 for a directory, 0 for a regular file
    public static final int ST_MODE = 1;                 // permission bits
    public static final int ST_INO = 2;
    public static final int ST_NLINK = 3;
    public static final int ST_UID = 4;
    public static final int ST_GID = 5;
    public static final int ST_SIZE = 6;
    public static final int ST_ATIME = 7;                // nanoseconds since the epoch
    public static final int ST_MTIME = 8;
    public static final int ST_CTIME = 9;
    private static final int ST_LEN = 10;

    private static final Object LOCK = new Object();
    private static MetalFsNode root;
    private static long nextIno = 1L;

    /** An open file: what it is open on, where, and how. */
    private static final class Fd
    {
        MetalFsNode node;
        String path;
        long pos;
        boolean read;
        boolean write;
        boolean append;
    }

    /** fds start here, clear of 0/1/2 and of any descriptor the VM's own consoles use. */
    private static final int FIRST_FD = 100;
    private static final Map<Integer, Fd> FDS = new HashMap<>();
    private static int nextFd = FIRST_FD;

    private static final Map<Long, String[]> DIRS = new HashMap<>();
    private static final Map<Long, int[]> DIR_POS = new HashMap<>();
    private static long nextDir = 1L;

    // ---- the tree ----------------------------------------------------------------------------------------------

    private static long now()
    {
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static MetalFsNode newNode(boolean dir, int mode, long time)
    {
        MetalFsNode n = new MetalFsNode(dir, nextIno, mode, time);
        nextIno += 1L;
        return n;
    }

    /** The tree, built from the baked table on first use. Callers hold {@code LOCK}. */
    private static MetalFsNode root()
    {
        if (root != null)
        {
            return root;
        }
        MetalFsNode r = newNode(true, 0755, 0L);
        long dir = table0(0L);
        int n = (int) table0(1L);
        for (int i = 0; i < n; i++)
        {
            long e = dir + (long) i * ENTRY;
            String p = utf8(Magic.load64(e), (int) Magic.load64(e + 8L));
            String[] parts = p.substring(1).split("/");
            MetalFsNode d = r;
            for (int k = 0; k < parts.length - 1; k++)
            {
                MetalFsNode next = d.kids.get(parts[k]);
                if (next == null)
                {
                    next = newNode(true, 0755, 0L);
                    d.kids.put(parts[k], next);
                }
                d = next;
            }
            MetalFsNode f = newNode(false, 0644, 0L);
            f.baked = e;
            f.size = Magic.load64(e + 24L);
            d.kids.put(parts[parts.length - 1], f);
        }
        if (!r.kids.containsKey("tmp"))
        {
            r.kids.put("tmp", newNode(true, 01777, 0L));
        }
        root = r;
        return r;
    }

    /** The node at {@code p}, or null. */
    private static MetalFsNode lookup(String p)
    {
        MetalFsNode n = root();
        if (p.equals("/"))
        {
            return n;
        }
        for (String c : p.substring(1).split("/"))
        {
            if (!n.dir)
            {
                return null;
            }
            n = n.kids.get(c);
            if (n == null)
            {
                return null;
            }
        }
        return n;
    }

    /** Why {@code p} does not resolve: NOTDIR if a file stands where a directory must, else NOENT. */
    private static int missing(String p)
    {
        MetalFsNode n = root();
        String[] parts = p.substring(1).split("/");
        for (int k = 0; k < parts.length; k++)
        {
            if (!n.dir)
            {
                return -NOTDIR;
            }
            n = n.kids.get(parts[k]);
            if (n == null)
            {
                return -NOENT;
            }
        }
        return -NOENT;
    }

    private static String parentPath(String p)
    {
        int slash = p.lastIndexOf('/');
        return slash == 0 ? "/" : p.substring(0, slash);
    }

    private static String leaf(String p)
    {
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** The directory {@code p} would be created in, or a negative code. Callers hold {@code LOCK}. */
    private static Object parentFor(String p)
    {
        if (p.equals("/"))
        {
            return Integer.valueOf(-EXIST);
        }
        String pp = parentPath(p);
        MetalFsNode d = lookup(pp);
        if (d == null)
        {
            return Integer.valueOf(missing(pp));
        }
        if (!d.dir)
        {
            return Integer.valueOf(-NOTDIR);
        }
        return d;
    }

    private static void touch(MetalFsNode n)
    {
        long t = now();
        n.mtime = t;
        n.ctime = t;
    }

    private static int dirLinks(MetalFsNode d)
    {
        int links = 2;
        for (MetalFsNode k : d.kids.values())
        {
            if (k.dir)
            {
                links += 1;
            }
        }
        return links;
    }

    private static long[] statOf(MetalFsNode n)
    {
        long[] s = new long[ST_LEN];
        s[ST_DIR] = n.dir ? 1L : 0L;
        s[ST_MODE] = n.mode;
        s[ST_INO] = n.ino;
        s[ST_NLINK] = n.dir ? dirLinks(n) : n.nlink;
        s[ST_UID] = n.uid;
        s[ST_GID] = n.gid;
        s[ST_SIZE] = n.dir ? 0L : n.size;
        s[ST_ATIME] = n.atime;
        s[ST_MTIME] = n.mtime;
        s[ST_CTIME] = n.ctime;
        return s;
    }

    // ---- questions by path ------------------------------------------------------------------------------

    /** True if {@code p} names a file or a directory. */
    public static boolean exists(String p)
    {
        synchronized (LOCK)
        {
            return lookup(p) != null;
        }
    }

    /** True if {@code p} names a directory. */
    public static boolean isDirectory(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            return n != null && n.dir;
        }
    }

    /** stat(): the {@code ST_*} array, or null if {@code p} does not resolve ({@link #lookupError} says why). */
    public static long[] stat(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            return n == null ? null : statOf(n);
        }
    }

    /** The negative code for a path that does not resolve: NOTDIR through a file, else NOENT. */
    public static int lookupError(String p)
    {
        synchronized (LOCK)
        {
            return missing(p);
        }
    }

    /** The names directly inside directory {@code p}, sorted, or null if it is not one. */
    public static String[] list(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null || !n.dir)
            {
                return null;
            }
            return n.kids.keySet().toArray(new String[0]);
        }
    }

    /** Every byte the tree's files hold, each file counted once however many names it has. */
    public static long usedBytes()
    {
        synchronized (LOCK)
        {
            return used(root(), new java.util.IdentityHashMap<>());
        }
    }

    private static long used(MetalFsNode d, Map<MetalFsNode, Boolean> seen)
    {
        long sum = 0L;
        for (MetalFsNode k : d.kids.values())
        {
            if (k.dir)
            {
                sum += used(k, seen);
            }
            else if (seen.put(k, Boolean.TRUE) == null)
            {
                sum += k.size;
            }
        }
        return sum;
    }

    // ---- changes by path ------------------------------------------------------------------------------

    /** mkdir(): 0 or a negative code. */
    public static int mkdir(String p, int mode)
    {
        synchronized (LOCK)
        {
            if (lookup(p) != null)
            {
                return -EXIST;
            }
            Object d = parentFor(p);
            if (d instanceof Integer)
            {
                return (Integer) d;
            }
            MetalFsNode parent = (MetalFsNode) d;
            parent.kids.put(leaf(p), newNode(true, mode & ~UMASK & 07777, now()));
            touch(parent);
            return 0;
        }
    }

    /** rmdir(): 0 or a negative code. */
    public static int rmdir(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                return missing(p);
            }
            if (!n.dir)
            {
                return -NOTDIR;
            }
            if (p.equals("/"))
            {
                return -BUSY;
            }
            if (!n.kids.isEmpty())
            {
                return -NOTEMPTY;
            }
            MetalFsNode parent = lookup(parentPath(p));
            parent.kids.remove(leaf(p));
            touch(parent);
            return 0;
        }
    }

    /** unlink(): 0 or a negative code. A directory is PERM, as macOS answers; an open file stays readable. */
    public static int unlink(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                return missing(p);
            }
            if (n.dir)
            {
                return -PERM;
            }
            MetalFsNode parent = lookup(parentPath(p));
            parent.kids.remove(leaf(p));
            n.nlink -= 1;
            n.ctime = now();
            touch(parent);
            return 0;
        }
    }

    /** rename(): 0 or a negative code, with POSIX's rules for an existing target. */
    public static int rename(String from, String to)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(from);
            if (n == null)
            {
                return missing(from);
            }
            if (from.equals("/") || to.equals("/"))
            {
                return -BUSY;
            }
            Object d = parentFor(to);
            if (d instanceof Integer)
            {
                return (Integer) d;
            }
            MetalFsNode target = lookup(to);
            if (target == n)
            {
                return 0;                                // the same file: rename does nothing
            }
            if (n.dir && to.startsWith(from + "/"))
            {
                return -INVAL;                           // a directory into its own subtree
            }
            if (target != null)
            {
                if (n.dir && !target.dir)
                {
                    return -NOTDIR;
                }
                if (!n.dir && target.dir)
                {
                    return -ISDIR;
                }
                if (target.dir && !target.kids.isEmpty())
                {
                    return -NOTEMPTY;
                }
                if (!target.dir)
                {
                    target.nlink -= 1;
                }
            }
            MetalFsNode oldParent = lookup(parentPath(from));
            MetalFsNode newParent = (MetalFsNode) d;
            oldParent.kids.remove(leaf(from));
            newParent.kids.put(leaf(to), n);
            touch(oldParent);
            touch(newParent);
            n.ctime = now();
            return 0;
        }
    }

    /** link(): a second name for a file; 0 or a negative code. A directory is PERM. */
    public static int link(String existing, String newName)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(existing);
            if (n == null)
            {
                return missing(existing);
            }
            if (n.dir)
            {
                return -PERM;
            }
            if (lookup(newName) != null)
            {
                return -EXIST;
            }
            Object d = parentFor(newName);
            if (d instanceof Integer)
            {
                return (Integer) d;
            }
            MetalFsNode parent = (MetalFsNode) d;
            parent.kids.put(leaf(newName), n);
            n.nlink += 1;
            n.ctime = now();
            touch(parent);
            return 0;
        }
    }

    /** clonefile(): a copy of a file or a whole directory, sharing nothing; 0 or a negative code. */
    public static int clone(String src, String dst)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(src);
            if (n == null)
            {
                return missing(src);
            }
            if (lookup(dst) != null)
            {
                return -EXIST;
            }
            Object d = parentFor(dst);
            if (d instanceof Integer)
            {
                return (Integer) d;
            }
            MetalFsNode parent = (MetalFsNode) d;
            parent.kids.put(leaf(dst), deepCopy(n));
            touch(parent);
            return 0;
        }
    }

    private static MetalFsNode deepCopy(MetalFsNode n)
    {
        MetalFsNode c = n.copy(nextIno);
        nextIno += 1L;
        if (n.dir)
        {
            for (Map.Entry<String, MetalFsNode> k : n.kids.entrySet())
            {
                c.kids.put(k.getKey(), deepCopy(k.getValue()));
            }
        }
        return c;
    }

    /** chmod(): 0 or a negative code. */
    public static int chmod(String p, int mode)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                return missing(p);
            }
            n.mode = mode & 07777;
            n.ctime = now();
            return 0;
        }
    }

    /** chown(): -1 leaves an id as it is, as chown(2) does; 0 or a negative code. */
    public static int chown(String p, int uid, int gid)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                return missing(p);
            }
            setOwner(n, uid, gid);
            return 0;
        }
    }

    /** utimensat(): the access and modification times in nanoseconds; 0 or a negative code. */
    public static int setTimes(String p, long atime, long mtime)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                return missing(p);
            }
            n.atime = atime;
            n.mtime = mtime;
            n.ctime = now();
            return 0;
        }
    }

    private static void setOwner(MetalFsNode n, int uid, int gid)
    {
        if (uid != -1)
        {
            n.uid = uid;
        }
        if (gid != -1)
        {
            n.gid = gid;
        }
        n.ctime = now();
    }

    // ---- directory streams ------------------------------------------------------------------------------

    /** opendir: a handle over a snapshot of the directory's names, or 0 if it is not one. */
    public static long opendir(String p)
    {
        synchronized (LOCK)
        {
            MetalFsNode n = lookup(p);
            if (n == null || !n.dir)
            {
                return 0L;
            }
            long h = nextDir;
            nextDir += 1L;
            DIRS.put(h, n.kids.keySet().toArray(new String[0]));
            DIR_POS.put(h, new int[1]);
            return h;
        }
    }

    /** readdir: the next name as bytes, or null at the end (the C returns NULL then). */
    public static byte[] readdir(long h)
    {
        synchronized (LOCK)
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
    }

    /** closedir: false if {@code h} is not open. */
    public static boolean closedir(long h)
    {
        synchronized (LOCK)
        {
            DIR_POS.remove(h);
            return DIRS.remove(h) != null;
        }
    }

    // ---- file descriptors ---------------------------------------------------------------------------------

    /**
     * open(): an fd (from 100) or a negative code. {@code flags} are the {@code O_*} above; {@code mode} is used
     * only by a create. A directory opens for reading only (writing it is ISDIR, as open(2) says).
     */
    public static int open(String p, int flags, int mode)
    {
        synchronized (LOCK)
        {
            boolean write = (flags & O_WRITE) != 0;
            MetalFsNode n = lookup(p);
            if (n == null)
            {
                if ((flags & O_CREAT) == 0)
                {
                    return missing(p);
                }
                Object d = parentFor(p);
                if (d instanceof Integer)
                {
                    return (Integer) d;
                }
                MetalFsNode parent = (MetalFsNode) d;
                n = newNode(false, mode & ~UMASK & 07777, now());
                parent.kids.put(leaf(p), n);
                touch(parent);
            }
            else
            {
                if ((flags & O_CREAT) != 0 && (flags & O_EXCL) != 0)
                {
                    return -EXIST;
                }
                if (n.dir && write)
                {
                    return -ISDIR;
                }
                if (write && (flags & O_TRUNC) != 0 && n.size != 0L)
                {
                    n.setSize(0L);
                    touch(n);
                }
            }
            Fd f = new Fd();
            f.node = n;
            f.path = p;
            f.read = (flags & O_READ) != 0;
            f.write = write;
            f.append = (flags & O_APPEND) != 0;
            int fd = nextFd;
            nextFd += 1;
            FDS.put(fd, f);
            return fd;
        }
    }

    /** close: false if {@code fd} is not open. */
    public static boolean close(int fd)
    {
        synchronized (LOCK)
        {
            return FDS.remove(fd) != null;
        }
    }

    /** True if {@code fd} is open here. */
    public static boolean isOpen(int fd)
    {
        synchronized (LOCK)
        {
            return FDS.containsKey(fd);
        }
    }

    /** The path {@code fd} was opened on, or null if it is not open. */
    public static String fdPath(int fd)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            return f == null ? null : f.path;
        }
    }

    /** fstat(): the {@code ST_*} array of what {@code fd} is open on, or null if it is not open. */
    public static long[] fstat(int fd)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            return f == null ? null : statOf(f.node);
        }
    }

    /** True if {@code fd} is open on a directory (reading it is EISDIR). */
    public static boolean fdIsDirectory(int fd)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            return f != null && f.node.dir;
        }
    }

    /** The byte length of {@code fd}'s file, -1 if not open, 0 for a directory. */
    public static long fdSize(int fd)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -1L;
            }
            return f.node.dir ? 0L : f.node.size;
        }
    }

    /** {@code fd}'s position, -1 if not open. */
    public static long position(int fd)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            return f == null ? -1L : f.pos;
        }
    }

    /** lseek(SEEK_SET): false if not open. A position past the end is allowed; a write there leaves a zero gap. */
    public static boolean seek(int fd, long pos)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return false;
            }
            f.pos = pos;
            return true;
        }
    }

    /** read: {@link #pread} at {@code fd}'s position, which then advances by the count. */
    public static int read(int fd, long addr, int len)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            int n = pread(fd, addr, len, f.pos);
            if (n > 0)
            {
                f.pos += n;
            }
            return n;
        }
    }

    /**
     * pread: up to {@code len} bytes from {@code pos} into native memory at {@code addr}; the count, 0 at or past
     * the end, or a negative code (BADF if not open for reading, ISDIR on a directory). The position is not moved.
     */
    public static int pread(int fd, long addr, int len, long pos)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null || !f.read)
            {
                return -BADF;
            }
            MetalFsNode node = f.node;
            if (node.dir)
            {
                return -ISDIR;
            }
            if (pos >= node.size || len <= 0)
            {
                return 0;
            }
            int n = (int) Math.min((long) len, node.size - pos);
            Unsafe u = Unsafe.getUnsafe();
            if (node.data != null)
            {
                byte[] d = node.data;
                for (int k = 0; k < n; k++)
                {
                    u.putByte(addr + k, d[(int) pos + k]);
                }
            }
            else
            {
                long src = Magic.load64(node.baked + 16L) + pos;
                for (int k = 0; k < n; k++)
                {
                    u.putByte(addr + k, (byte) Magic.load8(src + k));
                }
            }
            return n;
        }
    }

    /** write: {@link #pwrite} at {@code fd}'s position -- the end, for O_APPEND -- which then advances. */
    public static int write(int fd, long addr, int len)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            if (f.append)
            {
                f.pos = f.node.size;
            }
            int n = pwrite(fd, addr, len, f.pos);
            if (n > 0)
            {
                f.pos += n;
            }
            return n;
        }
    }

    /**
     * pwrite: {@code len} bytes from native memory at {@code addr} to {@code pos}, extending the file (a gap past the
     * old end reads as zero); the count or a negative code (BADF if not open for writing, FBIG past 2 GiB).
     */
    public static int pwrite(int fd, long addr, int len, long pos)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null || !f.write)
            {
                return -BADF;
            }
            if (len <= 0)
            {
                return 0;
            }
            long end = pos + len;
            if (pos < 0L || end > Integer.MAX_VALUE - 8L)
            {
                return pos < 0L ? -INVAL : -FBIG;
            }
            MetalFsNode node = f.node;
            if (end > node.size)
            {
                node.setSize(end);
            }
            else
            {
                node.ensureCapacity((int) node.size);
            }
            Unsafe u = Unsafe.getUnsafe();
            byte[] d = node.data;
            for (int k = 0; k < len; k++)
            {
                d[(int) pos + k] = u.getByte(addr + k);
            }
            touch(node);
            return len;
        }
    }

    /** ftruncate: 0, or INVAL if {@code fd} is not open for writing (or the size is negative), BADF if not open. */
    public static int truncate(int fd, long size)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            if (!f.write || size < 0L)
            {
                return -INVAL;
            }
            if (size > Integer.MAX_VALUE - 8L)
            {
                return -FBIG;
            }
            f.node.setSize(size);
            touch(f.node);
            return 0;
        }
    }

    /** fchmod(): 0 or BADF. */
    public static int fchmod(int fd, int mode)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            f.node.mode = mode & 07777;
            f.node.ctime = now();
            return 0;
        }
    }

    /** fchown(): 0 or BADF; -1 leaves an id as it is. */
    public static int fchown(int fd, int uid, int gid)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            setOwner(f.node, uid, gid);
            return 0;
        }
    }

    /** futimens(): the access and modification times in nanoseconds; 0 or BADF. */
    public static int fsetTimes(int fd, long atime, long mtime)
    {
        synchronized (LOCK)
        {
            Fd f = FDS.get(fd);
            if (f == null)
            {
                return -BADF;
            }
            f.node.atime = atime;
            f.node.mtime = mtime;
            f.node.ctime = now();
            return 0;
        }
    }

    /**
     * fcopyfile(COPYFILE_DATA): the rest of {@code src} from its position onto {@code dst} at its position, both
     * positions advancing; 0 or a negative code.
     */
    public static int copyData(int dst, int src)
    {
        synchronized (LOCK)
        {
            Fd s = FDS.get(src);
            Fd d = FDS.get(dst);
            if (s == null || d == null || !s.read || !d.write)
            {
                return -BADF;
            }
            if (s.node.dir || d.node.dir)
            {
                return -ISDIR;
            }
            long n = s.node.size - s.pos;
            if (n <= 0L)
            {
                return 0;
            }
            if (d.append)
            {
                d.pos = d.node.size;
            }
            long end = d.pos + n;
            if (end > Integer.MAX_VALUE - 8L)
            {
                return -FBIG;
            }
            byte[] tmp = new byte[(int) n];
            for (int k = 0; k < (int) n; k++)
            {
                tmp[k] = (byte) s.node.byteAt(s.pos + k);
            }
            if (end > d.node.size)
            {
                d.node.setSize(end);
            }
            else
            {
                d.node.ensureCapacity((int) d.node.size);
            }
            System.arraycopy(tmp, 0, d.node.data, (int) d.pos, (int) n);
            s.pos += n;
            d.pos = end;
            touch(d.node);
            return 0;
        }
    }

    // ---- strings ------------------------------------------------------------------------------------------

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

    /** "." and ".." resolved lexically, duplicate and trailing slashes dropped; the tree has no links. */
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
