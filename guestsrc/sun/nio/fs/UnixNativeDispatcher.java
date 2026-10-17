/*
 * Copyright (c) 2008, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.util.function.Function;

/**
 * Unix system and library calls.
 */

class UnixNativeDispatcher {
    protected UnixNativeDispatcher() { }

    // returns a NativeBuffer containing the given path
    static NativeBuffer copyToNativeBuffer(UnixPath path) {
        byte[] cstr = path.getByteArrayForSysCalls();
        int size = cstr.length + 1;
        NativeBuffer buffer = NativeBuffers.getNativeBufferFromCache(size);
        if (buffer == null) {
            buffer = NativeBuffers.allocNativeBuffer(size);
        } else {
            // buffer already contains the path
            if (buffer.owner() == path)
                return buffer;
        }
        NativeBuffers.copyCStringToNativeBuffer(cstr, buffer);
        buffer.setOwner(path);
        return buffer;
    }

    /**
     * char *getcwd(char *buf, size_t size);
     */
    static byte[] getcwd()
    {
        String d = System.getProperty("user.dir");
        return (d == null || d.isEmpty() ? "/" : d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * int dup(int filedes)
     */
    static int dup(int filedes) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.dup is not implemented");
    }

    /**
     * int open(const char* path, int oflag, mode_t mode)
     */
    static int open(UnixPath path, int flags, int mode) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return open0(buffer.address(), flags, mode);
        }
    }
    private static int open0(long pathAddress, int flags, int mode) throws UnixException
    {
        String p = path(pathAddress);
        boolean writes = (flags & (UnixConstants.O_WRONLY | UnixConstants.O_RDWR | UnixConstants.O_TRUNC
                | UnixConstants.O_APPEND)) != 0;
        if (!exists(p))
        {
            throw new UnixException((flags & UnixConstants.O_CREAT) != 0 ? UnixConstants.EROFS
                    : UnixConstants.ENOENT);
        }
        if ((flags & UnixConstants.O_CREAT) != 0 && (flags & UnixConstants.O_EXCL) != 0)
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        if (writes)
        {
            throw new UnixException(UnixConstants.EROFS);
        }
        return jdk.internal.misc.MetalRamfs.open(p);   // O_RDONLY: a file or a directory, positioned at 0
    }

    /**
     * int openat(int dfd, const char* path, int oflag, mode_t mode)
     */
    static int openat(int dfd, byte[] path, int flags, int mode) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(path)) {
            return openat0(dfd, buffer.address(), flags, mode);
        }
    }
    private static int openat0(int dfd, long pathAddress, int flags, int mode) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.openat0 is not implemented");
    }

    /**
     * close(int filedes). If fd is -1 this is a no-op.
     */
    static void close(int fd) throws UnixException {
        if (fd != -1) {
            close0(fd);
        }
    }
    private static void close0(int fd) throws UnixException
    {
        if (!jdk.internal.misc.MetalRamfs.close(fd))
        {
            throw new UnixException(EBADF);
        }
    }

    /**
     * close(fd). If close fails then the given exception supplier function is
     * invoked to produce an exception to throw. If the function returns null
     * then no exception is thrown. If close fails and the exception supplier
     * function is null, then no exception is thrown.
     */
    static <X extends Throwable>
    void close(int fd, Function<UnixException, X> mapper) throws X {
        try {
            close(fd);
        } catch (UnixException e) {
            if (mapper != null) {
                X ex = mapper.apply(e);
                if (ex != null) throw ex;
            }
        }
    }

    /**
     * void rewind(FILE* stream);
     */
    static void rewind(long stream) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.rewind is not implemented");
    }

    /**
     * ssize_t getline(char **lineptr, size_t *n, FILE *stream);
     */
    static int getlinelen(long stream) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.getlinelen is not implemented");
    }

    /**
     * link(const char* existing, const char* new)
     */
    static void link(UnixPath existing, UnixPath newfile) throws UnixException {
        try (NativeBuffer existingBuffer = copyToNativeBuffer(existing);
             NativeBuffer newBuffer = copyToNativeBuffer(newfile)) {
            link0(existingBuffer.address(), newBuffer.address());
        }
    }
    private static void link0(long existingAddress, long newAddress) throws UnixException
    {
        String p = path(newAddress);
        if (exists(p))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * unlink(const char* path)
     */
    static void unlink(UnixPath path) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            unlink0(buffer.address());
        }
    }
    private static void unlink0(long pathAddress) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * unlinkat(int dfd, const char* path, int flag)
     */
    static void unlinkat(int dfd, byte[] path, int flag) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(path)) {
            unlinkat0(dfd, buffer.address(), flag);
        }
    }
    private static void unlinkat0(int dfd, long pathAddress, int flag) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.unlinkat0 is not implemented");
    }

    /**
     * mknod(const char* path, mode_t mode, dev_t dev)
     */
    static void mknod(UnixPath path, int mode, long dev) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            mknod0(buffer.address(), mode, dev);
        }
    }
    private static void mknod0(long pathAddress, int mode, long dev) throws UnixException
    {
        String p = path(pathAddress);
        if (exists(p))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     *  rename(const char* old, const char* new)
     */
    static void rename(UnixPath from, UnixPath to) throws UnixException {
        try (NativeBuffer fromBuffer = copyToNativeBuffer(from);
             NativeBuffer toBuffer = copyToNativeBuffer(to)) {
            rename0(fromBuffer.address(), toBuffer.address());
        }
    }
    private static void rename0(long fromAddress, long toAddress) throws UnixException
    {
        String p = path(fromAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     *  renameat(int fromfd, const char* old, int tofd, const char* new)
     */
    static void renameat(int fromfd, byte[] from, int tofd, byte[] to) throws UnixException {
        try (NativeBuffer fromBuffer = NativeBuffers.asNativeBuffer(from);
             NativeBuffer toBuffer = NativeBuffers.asNativeBuffer(to)) {
            renameat0(fromfd, fromBuffer.address(), tofd, toBuffer.address());
        }
    }
    private static void renameat0(int fromfd, long fromAddress, int tofd, long toAddress) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.renameat0 is not implemented");
    }

    /**
     * mkdir(const char* path, mode_t mode)
     */
    static void mkdir(UnixPath path, int mode) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            mkdir0(buffer.address(), mode);
        }
    }
    private static void mkdir0(long pathAddress, int mode) throws UnixException
    {
        String p = path(pathAddress);
        if (exists(p))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * rmdir(const char* path)
     */
    static void rmdir(UnixPath path) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            rmdir0(buffer.address());
        }
    }
    private static void rmdir0(long pathAddress) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * readlink(const char* path, char* buf, size_t bufsize)
     *
     * @return  link target
     */
    static byte[] readlink(UnixPath path) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return readlink0(buffer.address());
        }
    }
    private static byte[] readlink0(long pathAddress) throws UnixException
    {
        String p = path(pathAddress);
        throw new UnixException(exists(p) ? UnixConstants.EINVAL : UnixConstants.ENOENT);  // nothing is a link
    }

    /**
     * realpath(const char* path, char* resolved_name)
     *
     * @return  resolved path
     */
    static byte[] realpath(UnixPath path) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return realpath0(buffer.address());
        }
    }
    private static byte[] realpath0(long pathAddress) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        return p.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * symlink(const char* name1, const char* name2)
     */
    static void symlink(byte[] name1, UnixPath name2) throws UnixException {
        try (NativeBuffer targetBuffer = NativeBuffers.asNativeBuffer(name1);
             NativeBuffer linkBuffer = copyToNativeBuffer(name2)) {
            symlink0(targetBuffer.address(), linkBuffer.address());
        }
    }
    private static void symlink0(long name1, long name2) throws UnixException
    {
        String p = path(name2);
        if (exists(p))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * stat(const char* path, struct stat* buf)
     */
    static void stat(UnixPath path, UnixFileAttributes attrs) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            int errno = stat0(buffer.address(), attrs);
            if (errno != 0) {
                throw new UnixException(errno);
            }
        }
    }
    private static int stat0(long pathAddress, UnixFileAttributes attrs)
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            return UnixConstants.ENOENT;
        }
        fill(attrs, p);
        return 0;
    }

    // Variant of stat() returning errno instead of throwing UnixException
    static int stat2(UnixPath path, UnixFileAttributes attrs) {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return stat0(buffer.address(), attrs);
        }
    }

    /**
     * lstat(const char* path, struct stat* buf)
     */
    static void lstat(UnixPath path, UnixFileAttributes attrs) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            lstat0(buffer.address(), attrs);
        }
    }
    private static void lstat0(long pathAddress, UnixFileAttributes attrs) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        fill(attrs, p);                                // no links, so lstat is stat
    }

    /**
     * fstat(int filedes, struct stat* buf)
     */
    static void fstat(int fd, UnixFileAttributes attrs) throws UnixException {
        fstat0(fd, attrs);
    }
    private static void fstat0(int fd, UnixFileAttributes attrs) throws UnixException
    {
        String p = jdk.internal.misc.MetalRamfs.fdPath(fd);
        if (p == null)
        {
            throw new UnixException(EBADF);
        }
        fill(attrs, p);
    }

    /**
     * fstatat(int filedes,const char* path,  struct stat* buf, int flag)
     */
    static void fstatat(int dfd, UnixPath path, int flag, UnixFileAttributes attrs)
        throws UnixException
    {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            int errno = fstatat0(dfd, buffer.address(), flag, attrs);
            if (errno != 0) {
                throw new UnixException(errno);
            }
        }
    }
    private static int fstatat0(int dfd, long pathAddress, int flag, UnixFileAttributes attrs)
    {
        if (dfd != UnixConstants.AT_FDCWD)
        {
            throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fstatat0 relative to a directory fd "
                    + "is not implemented (init() reports no openat)");
        }
        String p = path(pathAddress);                // no links, so AT_SYMLINK_NOFOLLOW changes nothing
        if (!exists(p))
        {
            return UnixConstants.ENOENT;
        }
        fill(attrs, p);
        return 0;
    }

    // Variant of fstatat() returning errno instead of throwing UnixException
    static int fstatat2(int dfd, UnixPath path, int flag, UnixFileAttributes attrs) {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return fstatat0(dfd, buffer.address(), flag, attrs);
        }
    }

    /**
     * chown(const char* path, uid_t owner, gid_t group)
     */
    static void chown(UnixPath path, int uid, int gid) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            chown0(buffer.address(), uid, gid);
        }
    }
    private static void chown0(long pathAddress, int uid, int gid) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * lchown(const char* path, uid_t owner, gid_t group)
     */
    static void lchown(UnixPath path, int uid, int gid) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            lchown0(buffer.address(), uid, gid);
        }
    }
    private static void lchown0(long pathAddress, int uid, int gid) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * fchown(int filedes, uid_t owner, gid_t group)
     */
    static void fchown(int fd, int uid, int gid) throws UnixException {
        fchown0(fd, uid, gid);
    }
    static void fchown0(int fd, int uid, int gid) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fchown0 is not implemented");
    }

    /**
     * chmod(const char* path, mode_t mode)
     */
    static void chmod(UnixPath path, int mode) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            chmod0(buffer.address(), mode);
        }
    }
    private static void chmod0(long pathAddress, int mode) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * fchmod(int fildes, mode_t mode)
     */
    static void fchmod(int fd, int mode) throws UnixException {
        fchmod0(fd, mode);
    }
    private static void fchmod0(int fd, int mode) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fchmod0 is not implemented");
    }

    /**
     * fchmodat(int fd, const char *path, mode_t mode, int flag)
     */
    static void fchmodat(int fd, UnixPath path, int mode, int flag)
        throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            fchmodat0(fd, buffer.address(), mode, flag);
        }
    }
    private static void fchmodat0(int fd, long pathAddress, int mode, int flag) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fchmodat0 is not implemented");
    }

    /**
     * futimens(int fildes, const struct timespec times[2])
     */
    static void futimens(int fd, long times0, long times1) throws UnixException {
        futimens0(fd, times0, times1);
    }
    private static void futimens0(int fd, long times0, long times1) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.futimens0 is not implemented");
    }

    /**
     * utimensat(int fd, const char* path,
     *           const struct timeval times[2], int flags)
     */
    static void utimensat(int fd, UnixPath path, long times0, long times1, int flags)
        throws UnixException
    {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            utimensat0(fd, buffer.address(), times0, times1, flags);
        }
    }
    private static void utimensat0(int fd, long pathAddress, long times0, long times1, int flags) throws UnixException
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        throw new UnixException(UnixConstants.EROFS);
    }

    /**
     * DIR *opendir(const char* dirname)
     */
    static long opendir(UnixPath path) throws UnixException {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return opendir0(buffer.address());
        }
    }
    private static long opendir0(long pathAddress) throws UnixException
    {
        String p = path(pathAddress);
        long h = jdk.internal.misc.MetalRamfs.opendir(p);
        if (h == 0L)
        {
            throw new UnixException(exists(p) ? UnixConstants.ENOTDIR : UnixConstants.ENOENT);
        }
        return h;
    }

    /**
     * DIR* fdopendir(int filedes)
     */
    static long fdopendir(int dfd) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fdopendir is not implemented");
    }


    /**
     * closedir(DIR* dirp)
     */
    static void closedir(long dir) throws UnixException
    {
        if (!jdk.internal.misc.MetalRamfs.closedir(dir))
        {
            throw new UnixException(EBADF);
        }
    }

    /**
     * struct dirent* readdir(DIR *dirp)
     *
     * @return  dirent->d_name
     */
    static byte[] readdir(long dir) throws UnixException {
        return readdir0(dir);
    }
    static byte[] readdir0(long dir) throws UnixException
    {
        return jdk.internal.misc.MetalRamfs.readdir(dir);
    }

    /**
     * size_t read(int fildes, void* buf, size_t nbyte)
     */
    static int read(int fildes, long buf, int nbyte) throws UnixException {
        return read0(fildes, buf, nbyte);
    }
    private static int read0(int fildes, long buf, int nbyte) throws UnixException
    {
        if (jdk.internal.misc.MetalRamfs.fdPath(fildes) == null)
        {
            throw new UnixException(EBADF);
        }
        if (jdk.internal.misc.MetalRamfs.fdIsDirectory(fildes))
        {
            throw new UnixException(UnixConstants.EISDIR);
        }
        return jdk.internal.misc.MetalRamfs.read(fildes, buf, nbyte);
    }

    /**
     * size_t writeint fildes, void* buf, size_t nbyte)
     */
    static int write(int fildes, long buf, int nbyte) throws UnixException {
        return write0(fildes, buf, nbyte);
    }
    private static int write0(int fildes, long buf, int nbyte) throws UnixException
    {
        throw new UnixException(EBADF);                // every fd here is open read-only
    }

    /**
     * access(const char* path, int amode);
     */
    static int access(UnixPath path, int amode) {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            return access0(buffer.address(), amode);
        }
    }
    private static int access0(long pathAddress, int amode)
    {
        String p = path(pathAddress);
        if (!exists(p))
        {
            return UnixConstants.ENOENT;
        }
        if ((amode & UnixConstants.W_OK) != 0)
        {
            return UnixConstants.EROFS;
        }
        if ((amode & UnixConstants.X_OK) != 0 && !jdk.internal.misc.MetalRamfs.isDirectory(p))
        {
            return UnixConstants.EACCES;
        }
        return 0;
    }

    /**
     * struct passwd *getpwuid(uid_t uid);
     *
     * @return  passwd->pw_name
     */
    static byte[] getpwuid(int uid) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.getpwuid is not implemented");
    }

    /**
     * struct group *getgrgid(gid_t gid);
     *
     * @return  group->gr_name
     */
    static byte[] getgrgid(int gid) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.getgrgid is not implemented");
    }

    /**
     * struct passwd *getpwnam(const char *name);
     *
     * @return  passwd->pw_uid
     */
    static int getpwnam(String name) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(Util.toBytes(name))) {
            return getpwnam0(buffer.address());
        }
    }
    private static int getpwnam0(long nameAddress) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.getpwnam0 is not implemented");
    }

    /**
     * struct group *getgrnam(const char *name);
     *
     * @return  group->gr_name
     */
    static int getgrnam(String name) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(Util.toBytes(name))) {
            return getgrnam0(buffer.address());
        }
    }
    private static int getgrnam0(long nameAddress) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.getgrnam0 is not implemented");
    }

    /**
     * statvfs(const char* path, struct statvfs *buf)
     */
    static void statvfs(UnixPath path, UnixFileStoreAttributes attrs)
        throws UnixException
    {
        try (NativeBuffer buffer = copyToNativeBuffer(path)) {
            statvfs0(buffer.address(), attrs);
        }
    }
    private static void statvfs0(long pathAddress, UnixFileStoreAttributes attrs) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.statvfs0 is not implemented");
    }

    /**
     * char* strerror(int errnum)
     */
    static byte[] strerror(int errnum)
    {
        String m;
        if (errnum == UnixConstants.ENOENT)
        {
            m = "No such file or directory";
        }
        else if (errnum == UnixConstants.EROFS)
        {
            m = "Read-only file system";
        }
        else if (errnum == UnixConstants.EEXIST)
        {
            m = "File exists";
        }
        else if (errnum == UnixConstants.ENOTDIR)
        {
            m = "Not a directory";
        }
        else if (errnum == UnixConstants.EISDIR)
        {
            m = "Is a directory";
        }
        else if (errnum == UnixConstants.EACCES)
        {
            m = "Permission denied";
        }
        else if (errnum == UnixConstants.EINVAL)
        {
            m = "Invalid argument";
        }
        else if (errnum == UnixConstants.ENOTEMPTY)
        {
            m = "Directory not empty";
        }
        else if (errnum == EBADF)
        {
            m = "Bad file descriptor";
        }
        else
        {
            m = "Unknown error: " + errnum;
        }
        return m.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * ssize_t fgetxattr(int filedes, const char *name, void *value, size_t size);
     */
    static int fgetxattr(int filedes, byte[] name, long valueAddress, int valueLen)
        throws UnixException
    {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(name)) {
            return fgetxattr0(filedes, buffer.address(), valueAddress, valueLen);
        }
    }

    private static int fgetxattr0(int filedes, long nameAddress, long valueAddress, int valueLen) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fgetxattr0 is not implemented");
    }

    /**
     *  fsetxattr(int filedes, const char *name, const void *value, size_t size, int flags);
     */
    static void fsetxattr(int filedes, byte[] name, long valueAddress, int valueLen)
        throws UnixException
    {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(name)) {
            fsetxattr0(filedes, buffer.address(), valueAddress, valueLen);
        }
    }

    private static void fsetxattr0(int filedes, long nameAddress, long valueAddress, int valueLen) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fsetxattr0 is not implemented");
    }

    /**
     * fremovexattr(int filedes, const char *name);
     */
    static void fremovexattr(int filedes, byte[] name) throws UnixException {
        try (NativeBuffer buffer = NativeBuffers.asNativeBuffer(name)) {
            fremovexattr0(filedes, buffer.address());
        }
    }

    private static void fremovexattr0(int filedes, long nameAddress) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fremovexattr0 is not implemented");
    }

    /**
     * size_t flistxattr(int filedes, const char *list, size_t size)
     */
    static int flistxattr(int filedes, long listAddress, int size) throws UnixException
    {
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.flistxattr is not implemented");
    }

    /**
     * Capabilities
     */
    private static final int SUPPORTS_OPENAT        = 1 << 1;  // syscalls
    private static final int SUPPORTS_XATTR         = 1 << 3;
    private static final int SUPPORTS_BIRTHTIME     = 1 << 16; // other features
    private static final int capabilities;

    /**
     * Supports openat and other *at calls.
     */
    static boolean openatSupported() {
        return (capabilities & SUPPORTS_OPENAT) != 0;
    }

    /**
     * Supports file birth (creation) time attribute
     */
    static boolean birthtimeSupported() {
        return (capabilities & SUPPORTS_BIRTHTIME) != 0;
    }

    /**
     * Supports extended attributes
     */
    static boolean xattrSupported() {
        return (capabilities & SUPPORTS_XATTR) != 0;
    }

    /**
     * Supports fchmodat with AT_SYMLINK_NOFOLLOW flag
     */
    static boolean fchmodatNoFollowSupported() {
        return fchmodatNoFollowSupported0();
    }
    private static boolean fchmodatNoFollowSupported0()
    {
        return false;                                  // no fchmodat here at all
    }

    private static int init()
    {
        return 0;                                      // no openat/fstatat/fdopendir, xattrs or birthtime
    }
    // ---- joe-ng: the natives above are Java over the read-only RAMFS (jdk.internal.misc.MetalRamfs), their
    // semantics read from unix/native/libnio/fs/UnixNativeDispatcher.c. A mutator fails with the errno a
    // read-only filesystem gives -- ENOENT for an absent path, EEXIST for a create over an existing one, EROFS
    // otherwise -- and a native that is not implemented THROWS naming itself. Errno values are the baked
    // UnixConstants (the macOS table); EBADF is 9 on every Unix and is not in it.
    private static final int EBADF = 9;

    /** The NUL-terminated path at {@code addr}, made absolute against the working directory and normalized. */
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

    private static boolean exists(String p)
    {
        return jdk.internal.misc.MetalRamfs.find(p) != 0L || jdk.internal.misc.MetalRamfs.isDirectory(p);
    }

    /**
     * stat() into {@code attrs}: a file S_IFREG|0444 with its length, a directory S_IFDIR|0555; one device, the
     * table's inode, owner 0/0, every time 0 (the image carries none), birthtime unavailable. The fields are
     * private to UnixFileAttributes, so they are set by offset -- what the C's SetIntField/SetLongField do.
     */
    private static void fill(UnixFileAttributes attrs, String p)
    {
        boolean dir = jdk.internal.misc.MetalRamfs.find(p) == 0L;
        long e = jdk.internal.misc.MetalRamfs.find(p);
        jdk.internal.misc.Unsafe u = jdk.internal.misc.Unsafe.getUnsafe();
        Class<?> c = UnixFileAttributes.class;
        u.putInt(attrs, u.objectFieldOffset(c, "st_mode"),
                dir ? (UnixConstants.S_IFDIR | 0555) : (UnixConstants.S_IFREG | 0444));
        u.putLong(attrs, u.objectFieldOffset(c, "st_ino"), jdk.internal.misc.MetalRamfs.inode(p));
        u.putLong(attrs, u.objectFieldOffset(c, "st_dev"), 1L);
        u.putLong(attrs, u.objectFieldOffset(c, "st_rdev"), 0L);
        u.putInt(attrs, u.objectFieldOffset(c, "st_nlink"), dir ? 2 : 1);
        u.putInt(attrs, u.objectFieldOffset(c, "st_uid"), 0);
        u.putInt(attrs, u.objectFieldOffset(c, "st_gid"), 0);
        u.putLong(attrs, u.objectFieldOffset(c, "st_size"), dir ? 0L : jdk.internal.misc.MetalRamfs.size(e));
        u.putBoolean(attrs, u.objectFieldOffset(c, "birthtime_available"), false);
    }

    static {
        jdk.internal.loader.BootLoader.loadLibrary("nio");
        capabilities = init();
    }
}
