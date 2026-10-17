/*
 * Copyright (c) 2008, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.fs;

/**
 * Bsd specific system calls.
 */

class BsdNativeDispatcher extends UnixNativeDispatcher {
    protected BsdNativeDispatcher() { }

   /**
    * struct fsstat_iter *getfsstat();
    */
    static long getfsstat() throws UnixException
    {
        return 1L;                                     // one iterator over the one mount
    }

   /**
    * int fsstatEntry(struct fsstat_iter * iter, UnixMountEntry entry);
    */
    static int fsstatEntry(long iter, UnixMountEntry entry) throws UnixException
    {
        if (iter != 1L)
        {
            throw new UnixException(9);                // EBADF
        }
        int[] pos = FSSTAT_POS.get();
        if (pos[0] > 0)
        {
            return -1;                                 // the C's "no more entries"
        }
        pos[0] = 1;
        jdk.internal.misc.Unsafe u = jdk.internal.misc.Unsafe.getUnsafe();
        Class<?> c = UnixMountEntry.class;
        u.putReference(entry, u.objectFieldOffset(c, "name"), bytes("ramfs"));
        u.putReference(entry, u.objectFieldOffset(c, "dir"), bytes("/"));
        u.putReference(entry, u.objectFieldOffset(c, "fstype"), bytes("ramfs"));
        u.putReference(entry, u.objectFieldOffset(c, "opts"), bytes(""));     // writable: the C writes "" (not ISREADONLY)
        u.putLong(entry, u.objectFieldOffset(c, "dev"), 1L);   // the st_dev stat0 reports
        return 0;
    }

   /**
    * void endfsstat(struct fsstat_iter * iter);
    */
    static void endfsstat(long iter) throws UnixException
    {
        FSSTAT_POS.get()[0] = 0;
    }

    /**
     * int statfs(const char *path, struct statfs *buf);
     * returns buf->f_mntonname (directory on which mounted)
     */
    static byte[] getmntonname(UnixPath path) throws UnixException {
        try (NativeBuffer pathBuffer = copyToNativeBuffer(path)) {
            return getmntonname0(pathBuffer.address());
        }
    }
    static byte[] getmntonname0(long pathAddress) throws UnixException
    {
        String n = path(pathAddress);
        if (!jdk.internal.misc.MetalRamfs.exists(n))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        return bytes("/");                             // statfs(): everything is on the one mount
    }

    /**
     * int clonefile(const char * src, const char * dst, int flags);
     */
    static int clonefile(UnixPath src, UnixPath dst, int flags)
        throws UnixException
    {
        try (NativeBuffer srcBuffer = copyToNativeBuffer(src);
            NativeBuffer dstBuffer = copyToNativeBuffer(dst)) {
            return clonefile0(srcBuffer.address(), dstBuffer.address(), flags);
        }
    }
    private static int clonefile0(long srcAddress, long dstAddress, int flags) throws UnixException
    {
        // clonefile(2): a copy of a file or a whole directory, sharing nothing afterwards; there are no links, so
        // CLONE_NOFOLLOW changes nothing, and every file is owned 0/0, so CLONE_NOOWNERCOPY changes nothing either.
        int r = jdk.internal.misc.MetalRamfs.clone(path(srcAddress), path(dstAddress));
        if (r == -jdk.internal.misc.MetalRamfs.EXIST)
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        if (r == -jdk.internal.misc.MetalRamfs.NOTDIR)
        {
            throw new UnixException(UnixConstants.ENOTDIR);
        }
        if (r < 0)
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        return 0;
    }

    /**
     * setattrlist(const char* path, struct attrlist* attrList, void* attrBuf,
     *             size_t attrBufSize, unsigned long options)
     */
    static void setattrlist(UnixPath path, int commonattr, long modTime,
                            long accTime, long createTime, long options)
        throws UnixException
    {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            setattrlist0(buffer.address(), commonattr, modTime, accTime,
                         createTime, options);
        }
    }
    private static void setattrlist0(long pathAddress, int commonattr, long modTime, long accTime, long createTime, long options) throws UnixException
    {
        String p = path(pathAddress);
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        long[] t = times(commonattr, modTime, accTime, st);
        jdk.internal.misc.MetalRamfs.setTimes(p, t[0], t[1]);
    }

    /**
     * fsetattrlist(int fd, struct attrlist* attrList, void* attrBuf,
     *              size_t attrBufSize, unsigned long options)
     */
    static void fsetattrlist(int fd, int commonattr, long modTime,
                             long accTime, long createTime, long options)
        throws UnixException
    {
        fsetattrlist0(fd, commonattr, modTime, accTime, createTime, options);
    }
    private static void fsetattrlist0(int fd, int commonattr, long modTime, long accTime, long createTime, long options) throws UnixException
    {
        long[] st = jdk.internal.misc.MetalRamfs.fstat(fd);
        if (st == null)
        {
            throw new UnixException(EBADF);
        }
        long[] t = times(commonattr, modTime, accTime, st);
        jdk.internal.misc.MetalRamfs.fsetTimes(fd, t[0], t[1]);
    }

    // initialize field IDs
    private static void initIDs()
    {
                                                       // caches JNI field IDs for UnixMountEntry, nothing else
                                                       //   (BsdNativeDispatcher.c): empty is exactly right
    }

    // ---- joe-ng: the natives above are Java over the VM's in-memory tree (jdk.internal.misc.MetalRamfs), semantics
    // from macosx/native/libnio/fs/BsdNativeDispatcher.c. There is ONE mount -- "ramfs" on "/", type ramfs, writable,
    // device 1 (the st_dev stat0 reports) -- and UnixMountEntry's private fields are set by offset, as the C's
    // SetObjectField does. The per-thread position makes the getfsstat/fsstatEntry/endfsstat loop restartable.
    private static final ThreadLocal<int[]> FSSTAT_POS = ThreadLocal.withInitial(() -> new int[1]);
    private static final int EBADF = 9;

    /** The NUL-terminated path at {@code addr}, absolute against the working directory and normalized. */
    private static String path(long addr)
    {
        String p = jdk.internal.misc.MetalRamfs.cstring(addr);
        if (!p.startsWith("/"))
        {
            String d = System.getProperty("user.dir");
            p = (d == null || d.isEmpty() ? "/" : d) + "/" + p;
        }
        return jdk.internal.misc.MetalRamfs.normalize(p);
    }

    /**
     * The {access, modification} times setattrlist leaves: each ATTR_CMN_ bit replaces its time (nanoseconds, as
     * initattrlist divides them). ATTR_CMN_CRTIME -- the only reason stock calls setattrlist -- THROWS: the tree
     * keeps no creation time (init() reports no birthtime), and dropping the request silently would be a wrong
     * answer.
     */
    private static long[] times(int commonattr, long modTime, long accTime, long[] st)
    {
        if ((commonattr & UnixConstants.ATTR_CMN_CRTIME) != 0)
        {
            throw new UnsupportedOperationException("joe-ng: BsdNativeDispatcher.setattrlist0 ATTR_CMN_CRTIME is not "
                    + "implemented (the tree keeps no creation time)");
        }
        long a = (commonattr & UnixConstants.ATTR_CMN_ACCTIME) != 0 ? accTime : st[jdk.internal.misc.MetalRamfs.ST_ATIME];
        long m = (commonattr & UnixConstants.ATTR_CMN_MODTIME) != 0 ? modTime : st[jdk.internal.misc.MetalRamfs.ST_MTIME];
        return new long[] { a, m };
    }

    private static byte[] bytes(String s)
    {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static {
         initIDs();
    }
}
