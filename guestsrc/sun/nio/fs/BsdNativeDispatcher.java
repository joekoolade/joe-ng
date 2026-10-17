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
        u.putReference(entry, u.objectFieldOffset(c, "opts"), bytes("ro"));   // the C writes "ro" for ISREADONLY
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
        String p = jdk.internal.misc.MetalRamfs.cstring(pathAddress);
        String n = jdk.internal.misc.MetalRamfs.normalize(p.startsWith("/") ? p : "/" + p);
        if (jdk.internal.misc.MetalRamfs.find(n) == 0L && !jdk.internal.misc.MetalRamfs.isDirectory(n))
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
        String dst = jdk.internal.misc.MetalRamfs.normalize(jdk.internal.misc.MetalRamfs.cstring(dstAddress));
        boolean exists = jdk.internal.misc.MetalRamfs.find(dst) != 0L || jdk.internal.misc.MetalRamfs.isDirectory(dst);
        throw new UnixException(exists ? UnixConstants.EEXIST : UnixConstants.EROFS);
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
        throw new UnixException(UnixConstants.EROFS);
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
        throw new UnixException(UnixConstants.EROFS);
    }

    // initialize field IDs
    private static void initIDs()
    {
                                                       // caches JNI field IDs for UnixMountEntry, nothing else
                                                       //   (BsdNativeDispatcher.c): empty is exactly right
    }

    // ---- joe-ng: the natives above are Java over the read-only RAMFS, semantics from
    // macosx/native/libnio/fs/BsdNativeDispatcher.c. There is ONE mount -- "ramfs" on "/", type ramfs, read-only,
    // device 1 (the st_dev stat0 reports) -- and UnixMountEntry's private fields are set by offset, as the C's
    // SetObjectField does. The per-thread position makes the getfsstat/fsstatEntry/endfsstat loop restartable.
    private static final ThreadLocal<int[]> FSSTAT_POS = ThreadLocal.withInitial(() -> new int[1]);

    private static byte[] bytes(String s)
    {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static {
         initIDs();
    }
}
