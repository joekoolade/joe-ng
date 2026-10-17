/*
 * Copyright (c) 2000, 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;

import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;

class UnixFileDispatcherImpl extends FileDispatcher {
    private static final int MAP_INVALID = -1;
    private static final int MAP_RO = 0;
    private static final int MAP_RW = 1;
    private static final int MAP_PV = 2;

    static {
        IOUtil.load();
    }

    private static final JavaIOFileDescriptorAccess fdAccess =
            SharedSecrets.getJavaIOFileDescriptorAccess();

    UnixFileDispatcherImpl() {
    }

    int read(FileDescriptor fd, long address, int len) throws IOException {
        return read0(fd, address, len);
    }

    int pread(FileDescriptor fd, long address, int len, long position)
        throws IOException
    {
        return pread0(fd, address, len, position);
    }

    long readv(FileDescriptor fd, long address, int len) throws IOException {
        return readv0(fd, address, len);
    }

    int write(FileDescriptor fd, long address, int len) throws IOException {
        return write0(fd, address, len);
    }

    int pwrite(FileDescriptor fd, long address, int len, long position)
        throws IOException
    {
        return pwrite0(fd, address, len, position);
    }

    long writev(FileDescriptor fd, long address, int len)
        throws IOException
    {
        return writev0(fd, address, len);
    }

    long seek(FileDescriptor fd, long offset) throws IOException {
        return seek0(fd, offset);
    }

    int force(FileDescriptor fd, boolean metaData) throws IOException {
        return force0(fd, metaData);
    }

    int truncate(FileDescriptor fd, long size) throws IOException {
        return truncate0(fd, size);
    }

    long size(FileDescriptor fd) throws IOException {
        return size0(fd);
    }

    int available(FileDescriptor fd) throws IOException {
        return available0(fd);
    }

    boolean isOther(FileDescriptor fd) throws IOException {
        return isOther0(fd);
    }

    int lock(FileDescriptor fd, boolean blocking, long pos, long size,
             boolean shared) throws IOException
    {
        return lock0(fd, blocking, pos, size, shared);
    }

    void release(FileDescriptor fd, long pos, long size) throws IOException {
        release0(fd, pos, size);
    }

    void close(FileDescriptor fd) throws IOException {
        fdAccess.close(fd);
    }

    FileDescriptor duplicateForMapping(FileDescriptor fd) {
        // file descriptor not required for mapping operations; okay
        // to return invalid file descriptor.
        return new FileDescriptor();
    }

    boolean canTransferToDirectly(java.nio.channels.SelectableChannel sc) {
        return true;
    }

    boolean transferToDirectlyNeedsPositionLock() {
        return false;
    }

    boolean canTransferToFromOverlappedMap() {
        return true;
    }

    long allocationGranularity() {
        return allocationGranularity0();
    }

    long map(FileDescriptor fd, int prot, long position, long length,
             boolean isSync)
        throws IOException
    {
        return map0(fd, prot, position, length, isSync);
    }

    int unmap(long address, long length) {
        return unmap0(address, length);
    }

    int maxDirectTransferSize() {
        return Integer.MAX_VALUE;
    }

    long transferTo(FileDescriptor src, long position, long count,
                    FileDescriptor dst, boolean append) {
        return IOStatus.UNSUPPORTED;
    }

    long transferFrom(FileDescriptor src, FileDescriptor dst,
                      long position, long count, boolean append) {
        return IOStatus.UNSUPPORTED;
    }

    int setDirectIO(FileDescriptor fd, String path) {
        int result = -1;
        try {
            result = setDirect0(fd);
        } catch (IOException e) {
            throw new UnsupportedOperationException
                ("Error setting up DirectIO", e);
        }
        return result;
    }

    // -- Native methods --

    static int read0(FileDescriptor fd, long address, int len) throws IOException
    {
        int f = open(fd);
        int n = jdk.internal.misc.MetalRamfs.read(f, address, len);
        return n == 0 && len > 0 ? IOStatus.EOF : n;
    }

    static int pread0(FileDescriptor fd, long address, int len, long position) throws IOException
    {
        int f = open(fd);
        int n = jdk.internal.misc.MetalRamfs.pread(f, address, len, position);
        return n == 0 && len > 0 ? IOStatus.EOF : n;
    }

    static long readv0(FileDescriptor fd, long address, int len) throws IOException
    {
        int f = open(fd);
        jdk.internal.misc.Unsafe u = jdk.internal.misc.Unsafe.getUnsafe();
        long total = 0L;
        for (int i = 0; i < len; i++)                   // struct iovec { void *iov_base; size_t iov_len; }
        {
            long base = u.getLong(address + 16L * i);
            int want = (int) u.getLong(address + 16L * i + 8L);
            int n = jdk.internal.misc.MetalRamfs.read(f, base, want);
            total += n;
            if (n < want)
            {
                break;
            }
        }
        return total == 0L && len > 0 ? IOStatus.EOF : total;
    }

    static int write0(FileDescriptor fd, long address, int len) throws IOException
    {
        open(fd);
        throw new IOException("Bad file descriptor");     // every fd here is open read-only
    }

    static int pwrite0(FileDescriptor fd, long address, int len, long position) throws IOException
    {
        open(fd);
        throw new IOException("Bad file descriptor");
    }

    static long writev0(FileDescriptor fd, long address, int len) throws IOException
    {
        open(fd);
        throw new IOException("Bad file descriptor");
    }

    static int force0(FileDescriptor fd, boolean metaData) throws IOException
    {
        open(fd);
        return 0;                                      // fsync of a read-only fd succeeds; there is nothing to flush
    }

    static long seek0(FileDescriptor fd, long offset) throws IOException
    {
        int f = open(fd);
        if (offset < 0L)
        {
            return jdk.internal.misc.MetalRamfs.position(f);                    // the C's lseek(fd, 0, SEEK_CUR)
        }
        jdk.internal.misc.MetalRamfs.seek(f, offset);
        return offset;
    }

    static int truncate0(FileDescriptor fd, long size) throws IOException
    {
        open(fd);
        throw new IOException("Invalid argument");        // ftruncate on an fd not open for writing
    }

    static long size0(FileDescriptor fd) throws IOException
    {
        return jdk.internal.misc.MetalRamfs.fdSize(open(fd));
    }

    static int available0(FileDescriptor fd) throws IOException
    {
        int f = open(fd);
        long left = jdk.internal.misc.MetalRamfs.fdSize(f) - jdk.internal.misc.MetalRamfs.position(f);
        return left <= 0L ? 0 : (int) Math.min(left, Integer.MAX_VALUE);
    }

    static boolean isOther0(FileDescriptor fd) throws IOException
    {
        open(fd);
        return false;                                  // only regular files and directories exist here
    }

    static int lock0(FileDescriptor fd, boolean blocking, long pos, long size, boolean shared) throws IOException
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.lock0 is not implemented");
    }

    static void release0(FileDescriptor fd, long pos, long size) throws IOException
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.release0 is not implemented");
    }

    static void closeIntFD(int fd) throws IOException
    {
        if (!jdk.internal.misc.MetalRamfs.close(fd))
        {
            throw new IOException("Bad file descriptor");
        }
    }

    static long allocationGranularity0()
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.allocationGranularity0 is not implemented");
    }

    static long map0(FileDescriptor fd, int prot, long position, long length, boolean isSync) throws IOException
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.map0 is not implemented");
    }

    static int unmap0(long address, long length)
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.unmap0 is not implemented");
    }

    static int setDirect0(FileDescriptor fd) throws IOException
    {
        throw new UnsupportedOperationException("joe-ng: UnixFileDispatcherImpl.setDirect0 is not implemented");
    }

    // ---- joe-ng: the natives above are Java over the read-only RAMFS's descriptor table
    // (jdk.internal.misc.MetalRamfs), their semantics from unix/native/libnio/ch/UnixFileDispatcherImpl.c: a read at
    // the end is IOStatus.EOF, a write to a read-only fd is EBADF, seek0 with a negative offset reports the
    // position. Locking, mapping and O_DIRECT are not implemented and THROW naming themselves.

    /** The int fd of {@code fd}, as the C's fdval(); EBADF as stock's IOException when it is not open here. */
    private static int open(FileDescriptor fd) throws IOException
    {
        int f = IOUtil.fdVal(fd);
        if (jdk.internal.misc.MetalRamfs.fdPath(f) == null)
        {
            throw new IOException("Bad file descriptor");
        }
        return f;
    }
}
