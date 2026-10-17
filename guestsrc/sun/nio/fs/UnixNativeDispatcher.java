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
        int acc = flags & (UnixConstants.O_WRONLY | UnixConstants.O_RDWR);
        int how = 0;
        if (acc == UnixConstants.O_RDONLY || acc == UnixConstants.O_RDWR)
        {
            how |= jdk.internal.misc.MetalRamfs.O_READ;
        }
        if (acc == UnixConstants.O_WRONLY || acc == UnixConstants.O_RDWR)
        {
            how |= jdk.internal.misc.MetalRamfs.O_WRITE;
        }
        if ((flags & UnixConstants.O_CREAT) != 0)
        {
            how |= jdk.internal.misc.MetalRamfs.O_CREAT;
        }
        if ((flags & UnixConstants.O_EXCL) != 0)
        {
            how |= jdk.internal.misc.MetalRamfs.O_EXCL;
        }
        if ((flags & UnixConstants.O_TRUNC) != 0)
        {
            how |= jdk.internal.misc.MetalRamfs.O_TRUNC;
        }
        if ((flags & UnixConstants.O_APPEND) != 0)
        {
            how |= jdk.internal.misc.MetalRamfs.O_APPEND;
        }
        // O_NOFOLLOW, O_SYNC and O_DSYNC change nothing here: there are no links, and nothing is cached.
        return check(jdk.internal.misc.MetalRamfs.open(path(pathAddress), how, mode));
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
        check(jdk.internal.misc.MetalRamfs.link(path(existingAddress), path(newAddress)));
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
        check(jdk.internal.misc.MetalRamfs.unlink(path(pathAddress)));
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
        if (jdk.internal.misc.MetalRamfs.exists(path(pathAddress)))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnixException(UnixConstants.ENOTSUP);  // the tree holds files and directories only
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
        check(jdk.internal.misc.MetalRamfs.rename(path(fromAddress), path(toAddress)));
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
        check(jdk.internal.misc.MetalRamfs.mkdir(path(pathAddress), mode));
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
        check(jdk.internal.misc.MetalRamfs.rmdir(path(pathAddress)));
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
        if (!jdk.internal.misc.MetalRamfs.exists(p))
        {
            throw new UnixException(errno(jdk.internal.misc.MetalRamfs.lookupError(p)));
        }
        throw new UnixException(UnixConstants.EINVAL);   // nothing is a link
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
        if (!jdk.internal.misc.MetalRamfs.exists(p))
        {
            throw new UnixException(errno(jdk.internal.misc.MetalRamfs.lookupError(p)));
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
        if (jdk.internal.misc.MetalRamfs.exists(path(name2)))
        {
            throw new UnixException(UnixConstants.EEXIST);
        }
        throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.symlink0 is not implemented "
                + "(the tree has no symbolic links)");
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
        return statInto(path(pathAddress), attrs);
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
        int errno = statInto(path(pathAddress), attrs);  // no links, so lstat is stat
        if (errno != 0)
        {
            throw new UnixException(errno);
        }
    }

    /**
     * fstat(int filedes, struct stat* buf)
     */
    static void fstat(int fd, UnixFileAttributes attrs) throws UnixException {
        fstat0(fd, attrs);
    }
    private static void fstat0(int fd, UnixFileAttributes attrs) throws UnixException
    {
        long[] st = jdk.internal.misc.MetalRamfs.fstat(fd);
        if (st == null)
        {
            throw new UnixException(EBADF);
        }
        fill(attrs, st);
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
        return statInto(path(pathAddress), attrs);     // no links, so AT_SYMLINK_NOFOLLOW changes nothing
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
        check(jdk.internal.misc.MetalRamfs.chown(path(pathAddress), uid, gid));
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
        check(jdk.internal.misc.MetalRamfs.chown(path(pathAddress), uid, gid));  // no links: lchown is chown
    }

    /**
     * fchown(int filedes, uid_t owner, gid_t group)
     */
    static void fchown(int fd, int uid, int gid) throws UnixException {
        fchown0(fd, uid, gid);
    }
    static void fchown0(int fd, int uid, int gid) throws UnixException
    {
        check(jdk.internal.misc.MetalRamfs.fchown(fd, uid, gid));
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
        check(jdk.internal.misc.MetalRamfs.chmod(path(pathAddress), mode));
    }

    /**
     * fchmod(int fildes, mode_t mode)
     */
    static void fchmod(int fd, int mode) throws UnixException {
        fchmod0(fd, mode);
    }
    private static void fchmod0(int fd, int mode) throws UnixException
    {
        check(jdk.internal.misc.MetalRamfs.fchmod(fd, mode));
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
        if (fd != UnixConstants.AT_FDCWD)
        {
            throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.fchmodat0 relative to a directory fd "
                    + "is not implemented (init() reports no openat)");
        }
        check(jdk.internal.misc.MetalRamfs.chmod(path(pathAddress), mode));  // no links: NOFOLLOW changes nothing
    }


    /**
     * futimens(int fildes, const struct timespec times[2])
     */
    static void futimens(int fd, long times0, long times1) throws UnixException {
        futimens0(fd, times0, times1);
    }
    private static void futimens0(int fd, long times0, long times1) throws UnixException
    {
        check(jdk.internal.misc.MetalRamfs.fsetTimes(fd, times0, times1));  // access, modification: nanoseconds
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
        if (fd != UnixConstants.AT_FDCWD)
        {
            throw new UnsupportedOperationException("joe-ng: UnixNativeDispatcher.utimensat0 relative to a directory fd "
                    + "is not implemented (init() reports no openat)");
        }
        check(jdk.internal.misc.MetalRamfs.setTimes(path(pathAddress), times0, times1));
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
            throw new UnixException(jdk.internal.misc.MetalRamfs.exists(p) ? UnixConstants.ENOTDIR
                    : errno(jdk.internal.misc.MetalRamfs.lookupError(p)));
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
        return check(jdk.internal.misc.MetalRamfs.read(fildes, buf, nbyte));
    }

    /**
     * size_t writeint fildes, void* buf, size_t nbyte)
     */
    static int write(int fildes, long buf, int nbyte) throws UnixException {
        return write0(fildes, buf, nbyte);
    }
    private static int write0(int fildes, long buf, int nbyte) throws UnixException
    {
        return check(jdk.internal.misc.MetalRamfs.write(fildes, buf, nbyte));
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
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            return errno(jdk.internal.misc.MetalRamfs.lookupError(p));
        }
        // The VM's one user is root (uid 0), so read and write are always granted; execute needs a directory or
        // an execute bit somewhere in the mode, as access(2) answers for root.
        if ((amode & UnixConstants.X_OK) != 0 && st[jdk.internal.misc.MetalRamfs.ST_DIR] == 0L
                && (st[jdk.internal.misc.MetalRamfs.ST_MODE] & 0111) == 0L)
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
        if (uid != 0)
        {
            throw new UnixException(UnixConstants.ENOENT);  // the C's "not found"
        }
        return "root".getBytes(java.nio.charset.StandardCharsets.UTF_8);  // every file here is owned 0/0
    }

    /**
     * struct group *getgrgid(gid_t gid);
     *
     * @return  group->gr_name
     */
    static byte[] getgrgid(int gid) throws UnixException
    {
        if (gid != 0)
        {
            throw new UnixException(UnixConstants.ENOENT);
        }
        return "root".getBytes(java.nio.charset.StandardCharsets.UTF_8);  // gid 0's name: a DECISION (macOS says wheel)
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
        return "root".equals(jdk.internal.misc.MetalRamfs.cstring(nameAddress)) ? 0 : -1;  // -1 = not found, as the C
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
        return "root".equals(jdk.internal.misc.MetalRamfs.cstring(nameAddress)) ? 0 : -1;
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
        String p = path(pathAddress);
        if (!jdk.internal.misc.MetalRamfs.exists(p))
        {
            throw new UnixException(errno(jdk.internal.misc.MetalRamfs.lookupError(p)));
        }
        // DECISIONS: the tree has no block size, so a 4 KiB fragment; and it lives in the heap, which gives it no
        // capacity of its own to report -- the size is what is stored, and nothing is counted free rather than
        // inventing a figure.
        long frsize = 4096L;
        long blocks = (jdk.internal.misc.MetalRamfs.usedBytes() + frsize - 1L) / frsize;
        jdk.internal.misc.Unsafe u = jdk.internal.misc.Unsafe.getUnsafe();
        Class<?> c = UnixFileStoreAttributes.class;
        u.putLong(attrs, u.objectFieldOffset(c, "f_frsize"), frsize);
        u.putLong(attrs, u.objectFieldOffset(c, "f_blocks"), blocks);
        u.putLong(attrs, u.objectFieldOffset(c, "f_bfree"), 0L);
        u.putLong(attrs, u.objectFieldOffset(c, "f_bavail"), 0L);
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
        else if (errnum == EPERM)
        {
            m = "Operation not permitted";
        }
        else if (errnum == EBUSY)
        {
            m = "Resource busy";
        }
        else if (errnum == EFBIG)
        {
            m = "File too large";
        }
        else if (errnum == UnixConstants.ENOTSUP)
        {
            m = "Operation not supported";
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
    // ---- joe-ng: the natives above are Java over the VM's in-memory tree (jdk.internal.misc.MetalRamfs), their
    // semantics read from unix/native/libnio/fs/UnixNativeDispatcher.c. A failing call's code becomes the errno
    // the C would see; a native that is not implemented THROWS naming itself. Errno values are the baked
    // UnixConstants (the macOS table); the few it does not carry (EPERM, EBADF, EBUSY, EFBIG) have the same
    // number on every Unix.
    private static final int EPERM = 1;
    private static final int EBADF = 9;
    private static final int EBUSY = 16;
    private static final int EFBIG = 27;

    /** The errno for MetalRamfs code {@code code} (negative). */
    private static int errno(int code)
    {
        switch (code)
        {
            case -jdk.internal.misc.MetalRamfs.NOENT:
                return UnixConstants.ENOENT;
            case -jdk.internal.misc.MetalRamfs.EXIST:
                return UnixConstants.EEXIST;
            case -jdk.internal.misc.MetalRamfs.NOTDIR:
                return UnixConstants.ENOTDIR;
            case -jdk.internal.misc.MetalRamfs.ISDIR:
                return UnixConstants.EISDIR;
            case -jdk.internal.misc.MetalRamfs.NOTEMPTY:
                return UnixConstants.ENOTEMPTY;
            case -jdk.internal.misc.MetalRamfs.INVAL:
                return UnixConstants.EINVAL;
            case -jdk.internal.misc.MetalRamfs.BADF:
                return EBADF;
            case -jdk.internal.misc.MetalRamfs.PERM:
                return EPERM;
            case -jdk.internal.misc.MetalRamfs.BUSY:
                return EBUSY;
            case -jdk.internal.misc.MetalRamfs.FBIG:
                return EFBIG;
            default:
                throw new InternalError("joe-ng: MetalRamfs code " + code);
        }
    }

    /** {@code r} if it is not negative, else the UnixException its code maps to. */
    private static int check(int r) throws UnixException
    {
        if (r < 0)
        {
            throw new UnixException(errno(r));
        }
        return r;
    }

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

    /** stat() of {@code p} into {@code attrs}: 0, or the errno when it does not resolve. */
    private static int statInto(String p, UnixFileAttributes attrs)
    {
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            return errno(jdk.internal.misc.MetalRamfs.lookupError(p));
        }
        fill(attrs, st);
        return 0;
    }

    /**
     * A MetalRamfs stat array into {@code attrs}: kind and mode, the tree's inode on its one device (1), link
     * count, owner, size and the three times split into seconds and nanoseconds; birthtime unavailable. The fields
     * are private to UnixFileAttributes, so they are set by offset -- what the C's SetIntField/SetLongField do.
     */
    private static void fill(UnixFileAttributes attrs, long[] st)
    {
        boolean dir = st[jdk.internal.misc.MetalRamfs.ST_DIR] != 0L;
        int perm = (int) st[jdk.internal.misc.MetalRamfs.ST_MODE];
        jdk.internal.misc.Unsafe u = jdk.internal.misc.Unsafe.getUnsafe();
        Class<?> c = UnixFileAttributes.class;
        u.putInt(attrs, u.objectFieldOffset(c, "st_mode"), (dir ? UnixConstants.S_IFDIR : UnixConstants.S_IFREG) | perm);
        u.putLong(attrs, u.objectFieldOffset(c, "st_ino"), st[jdk.internal.misc.MetalRamfs.ST_INO]);
        u.putLong(attrs, u.objectFieldOffset(c, "st_dev"), 1L);
        u.putLong(attrs, u.objectFieldOffset(c, "st_rdev"), 0L);
        u.putInt(attrs, u.objectFieldOffset(c, "st_nlink"), (int) st[jdk.internal.misc.MetalRamfs.ST_NLINK]);
        u.putInt(attrs, u.objectFieldOffset(c, "st_uid"), (int) st[jdk.internal.misc.MetalRamfs.ST_UID]);
        u.putInt(attrs, u.objectFieldOffset(c, "st_gid"), (int) st[jdk.internal.misc.MetalRamfs.ST_GID]);
        u.putLong(attrs, u.objectFieldOffset(c, "st_size"), st[jdk.internal.misc.MetalRamfs.ST_SIZE]);
        putTime(u, attrs, "st_atime", st[jdk.internal.misc.MetalRamfs.ST_ATIME]);
        putTime(u, attrs, "st_mtime", st[jdk.internal.misc.MetalRamfs.ST_MTIME]);
        putTime(u, attrs, "st_ctime", st[jdk.internal.misc.MetalRamfs.ST_CTIME]);
        u.putBoolean(attrs, u.objectFieldOffset(c, "birthtime_available"), false);
    }

    private static void putTime(jdk.internal.misc.Unsafe u, UnixFileAttributes attrs, String field, long nanos)
    {
        Class<?> c = UnixFileAttributes.class;
        u.putLong(attrs, u.objectFieldOffset(c, field + "_sec"), Math.floorDiv(nanos, 1_000_000_000L));
        u.putLong(attrs, u.objectFieldOffset(c, field + "_nsec"), Math.floorMod(nanos, 1_000_000_000L));
    }

    static {
        jdk.internal.loader.BootLoader.loadLibrary("nio");
        capabilities = init();
    }
}
