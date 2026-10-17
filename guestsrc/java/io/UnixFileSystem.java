/*
 * Copyright (c) 1998, 2025, Oracle and/or its affiliates. All rights reserved.
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

package java.io;

import java.util.Properties;
import jdk.internal.util.StaticProperty;

final class UnixFileSystem extends FileSystem {
    private final char slash;
    private final char colon;
    private final String userDir;

    private String getPathForSysCalls(String path) {
        return path.isEmpty() ? getCWD().getPath() : path;
    }

    private File getFileForSysCalls(File file) {
        return file.getPath().isEmpty() ? getCWD() : file;
    }

    UnixFileSystem() {
        Properties props = System.getProperties();
        slash = props.getProperty("file.separator").charAt(0);
        colon = props.getProperty("path.separator").charAt(0);
        userDir = StaticProperty.userDir();
    }

    /* -- Normalization and construction -- */

    @Override
    public char getSeparator() {
        return slash;
    }

    @Override
    public char getPathSeparator() {
        return colon;
    }

    /* A normal Unix pathname contains no duplicate slashes and does not end
       with a slash.  It may be the empty string. */

    /**
     * Normalize the given pathname, starting at the given
     * offset; everything before off is already normal, and there's at least
     * one duplicate or trailing slash to be removed
     */
    private String normalize(String pathname, int off) {
        int n = pathname.length();
        while ((n > off) && (pathname.charAt(n - 1) == '/')) n--;
        if (n == 0) return "/";
        if (n == off) return pathname.substring(0, off);

        StringBuilder sb = new StringBuilder(n);
        if (off > 0) sb.append(pathname, 0, off);
        char prevChar = 0;
        for (int i = off; i < n; i++) {
            char c = pathname.charAt(i);
            if ((prevChar == '/') && (c == '/')) continue;
            sb.append(c);
            prevChar = c;
        }
        return sb.toString();
    }

    /* Check that the given pathname is normal.  If not, invoke the real
       normalizer on the part of the pathname that requires normalization.
       This way we iterate through the whole pathname string only once. */
    @Override
    public String normalize(String pathname) {
        int doubleSlash = pathname.indexOf("//");
        if (doubleSlash >= 0) {
            return normalize(pathname, doubleSlash);
        }
        if (pathname.endsWith("/")) {
            return normalize(pathname, pathname.length() - 1);
        }
        return pathname;
    }

    @Override
    public int prefixLength(String pathname) {
        return pathname.startsWith("/") ? 1 : 0;
    }

    // Remove trailing '/' if present and there are at least two characters
    private static String trimSeparator(String s) {
        int len = s.length();
        if (len > 1 && s.charAt(len - 1) == '/')
            return s.substring(0, len - 1);
        return s;
    }

    @Override
    public String resolve(String parent, String child) {
        if (child.isEmpty()) return parent;
        if (child.charAt(0) == '/') {
            if (parent.equals("/")) return child;
            return trimSeparator(parent + child);
        }
        if (parent.equals("/")) return trimSeparator(parent + child);
        return trimSeparator(parent + '/' + child);
    }

    @Override
    public String getDefaultParent() {
        return "/";
    }

    @Override
    public String fromURIPath(String path) {
        String p = path;
        if (p.endsWith("/") && (p.length() > 1)) {
            // "/foo/" --> "/foo", but "/" --> "/"
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }


    /* -- Path operations -- */

    @Override
    public boolean isAbsolute(File f) {
        return (f.getPrefixLength() != 0);
    }

    @Override
    public boolean isInvalid(File f) {
        return f.getPath().indexOf('\u0000') >= 0;
    }

    @Override
    public String resolve(File f) {
        if (isAbsolute(f)) return f.getPath();
        return resolve(userDir, f.getPath());
    }

    @Override
    public String canonicalize(String path) throws IOException {
        return canonicalize0(getPathForSysCalls(path));
    }

    // joe-ng: every native below is implemented over the VM's in-memory tree (jdk.internal.misc.MetalRamfs), its
    // semantics read from unix/native/libjava/UnixFileSystem_md.c: each answers what the C returns for the
    // syscall's result.

    /** A path as the kernel would resolve it: against the working directory, "." and ".." lexically. */
    private String abs(String path)
    {
        String p = path.startsWith("/") ? path : resolve(userDir, path);
        return jdk.internal.misc.MetalRamfs.normalize(p);
    }

    /** JDK_Canonicalize, without symlinks to resolve (the tree has none): "." and ".." resolved lexically. */
    private String canonicalize0(String path) throws IOException
    {
        String[] parts = path.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : parts)
        {
            if (s.isEmpty() || s.equals("."))
            {
                continue;
            }
            if (s.equals(".."))
            {
                if (!out.isEmpty())
                {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(s);
        }
        return "/" + String.join("/", out);
    }

    /* -- Attribute accessors -- */

    /** stat(): BA_EXISTS with BA_DIRECTORY or BA_REGULAR, the only two kinds the tree holds; 0 when absent. */
    private int getBooleanAttributes0(File f)
    {
        long[] st = jdk.internal.misc.MetalRamfs.stat(abs(f.getPath()));
        if (st == null)
        {
            return 0;
        }
        return BA_EXISTS | (st[jdk.internal.misc.MetalRamfs.ST_DIR] != 0L ? BA_DIRECTORY : BA_REGULAR);
    }

    @Override
    public int getBooleanAttributes(File f) {
        int rv = getBooleanAttributes0(getFileForSysCalls(f));
        return rv | isHidden(f);
    }

    @Override
    public boolean hasBooleanAttributes(File f, int attributes) {
        int rv = getBooleanAttributes0(getFileForSysCalls(f));
        if ((attributes & BA_HIDDEN) != 0) {
            rv |= isHidden(f);
        }
        return (rv & attributes) == attributes;
    }

    private static int isHidden(File f) {
        return f.getName().startsWith(".") ? BA_HIDDEN : 0;
    }

    @Override
    public boolean checkAccess(File f, int access) {
        return checkAccess0(getFileForSysCalls(f), access);
    }
    /**
     * access() as root, the VM's one user: READ and WRITE whenever it exists; EXECUTE for a directory, or a file with
     * an execute bit anywhere in its mode.
     */
    private boolean checkAccess0(File f, int access)
    {
        long[] st = jdk.internal.misc.MetalRamfs.stat(abs(f.getPath()));
        if (st == null)
        {
            return false;
        }
        if (access == ACCESS_EXECUTE)
        {
            return st[jdk.internal.misc.MetalRamfs.ST_DIR] != 0L || (st[jdk.internal.misc.MetalRamfs.ST_MODE] & 0111) != 0L;
        }
        return true;                                   // the VM's one user is root: read and write are granted
    }

    @Override
    public long getLastModifiedTime(File f) {
        return getLastModifiedTime0(getFileForSysCalls(f));
    }
    /** stat().st_mtime in milliseconds; 0 when absent, as in C. A file from the image carries time 0. */
    private long getLastModifiedTime0(File f)
    {
        long[] st = jdk.internal.misc.MetalRamfs.stat(abs(f.getPath()));
        if (st == null)
        {
            return 0L;
        }
        return Math.floorDiv(st[jdk.internal.misc.MetalRamfs.ST_MTIME], 1_000_000L);  // sec * 1000 + nsec / 10^6
    }

    @Override
    public long getLength(File f) {
        return getLength0(getFileForSysCalls(f));
    }
    /** stat().st_size of a file; 0 for a directory, which occupies nothing here; 0 when absent, as in C. */
    private long getLength0(File f)
    {
        long[] st = jdk.internal.misc.MetalRamfs.stat(abs(f.getPath()));
        return st == null ? 0L : st[jdk.internal.misc.MetalRamfs.ST_SIZE];
    }

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        return setPermission0(getFileForSysCalls(f), access, enable, owneronly);
    }
    /** The mode with the owner's (or everyone's) bit for {@code access} set or cleared, through chmod(). */
    private boolean setPermission0(File f, int access, boolean enable, boolean owneronly)
    {
        int amode;
        if (access == ACCESS_READ)
        {
            amode = owneronly ? 0400 : 0444;
        }
        else if (access == ACCESS_WRITE)
        {
            amode = owneronly ? 0200 : 0222;
        }
        else
        {
            amode = owneronly ? 0100 : 0111;
        }
        String p = abs(f.getPath());
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            return false;
        }
        int mode = (int) st[jdk.internal.misc.MetalRamfs.ST_MODE];
        mode = enable ? (mode | amode) : (mode & ~amode);
        return jdk.internal.misc.MetalRamfs.chmod(p, mode) == 0;
    }

    /* -- File operations -- */

    @Override
    public boolean createFileExclusively(String path) throws IOException {
        return createFileExclusively0(path);
    }
    /**
     * open(O_RDWR|O_CREAT|O_EXCL, 0666) and close: "/" always exists (false), an existing path is EEXIST (false), and
     * any other failure is an IOException with the errno text, as the C throws.
     */
    private boolean createFileExclusively0(String path) throws IOException
    {
        String p = abs(path);
        if (p.equals("/"))
        {
            return false;                              // the root directory always exists
        }
        int fd = jdk.internal.misc.MetalRamfs.open(p, jdk.internal.misc.MetalRamfs.O_READ
                | jdk.internal.misc.MetalRamfs.O_WRITE | jdk.internal.misc.MetalRamfs.O_CREAT
                | jdk.internal.misc.MetalRamfs.O_EXCL, 0666);
        if (fd == -jdk.internal.misc.MetalRamfs.EXIST)
        {
            return false;
        }
        if (fd < 0)
        {
            throw new IOException(fd == -jdk.internal.misc.MetalRamfs.NOTDIR ? "Not a directory"
                    : "No such file or directory");
        }
        jdk.internal.misc.MetalRamfs.close(fd);
        return true;
    }

    @Override
    public boolean delete(File f) {
        return delete0(getFileForSysCalls(f));
    }
    /** remove(): rmdir for a directory, unlink otherwise; false when it fails. */
    private boolean delete0(File f)
    {
        String p = abs(f.getPath());
        if (jdk.internal.misc.MetalRamfs.isDirectory(p))
        {
            return jdk.internal.misc.MetalRamfs.rmdir(p) == 0;
        }
        return jdk.internal.misc.MetalRamfs.unlink(p) == 0;
    }

    @Override
    public String[] list(File f) {
        return list0(getFileForSysCalls(f));
    }
    /** opendir()/readdir() without "." and "..": the names in the directory, or null if it is not one. */
    private String[] list0(File f)
    {
        return jdk.internal.misc.MetalRamfs.list(abs(f.getPath()));
    }

    @Override
    public boolean createDirectory(File f) {
        return createDirectory0(getFileForSysCalls(f));
    }
    /** mkdir(path, 0777); false when it fails. */
    private boolean createDirectory0(File f)
    {
        return jdk.internal.misc.MetalRamfs.mkdir(abs(f.getPath()), 0777) == 0;
    }

    @Override
    public boolean rename(File f1, File f2) {
        return rename0(getFileForSysCalls(f1), getFileForSysCalls(f2));
    }
    /** rename(); false when it fails. */
    private boolean rename0(File f1, File f2)
    {
        return jdk.internal.misc.MetalRamfs.rename(abs(f1.getPath()), abs(f2.getPath())) == 0;
    }

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        return setLastModifiedTime0(getFileForSysCalls(f), time);
    }
    /** utimes(): the modification time from {@code time} (milliseconds), the access time as it was. */
    private boolean setLastModifiedTime0(File f, long time)
    {
        String p = abs(f.getPath());
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            return false;
        }
        // utimes(): the access time kept to the microsecond, the modification time set from milliseconds
        long atime = Math.floorDiv(st[jdk.internal.misc.MetalRamfs.ST_ATIME], 1000L) * 1000L;
        return jdk.internal.misc.MetalRamfs.setTimes(p, atime, time * 1_000_000L) == 0;
    }

    @Override
    public boolean setReadOnly(File f) {
        return setReadOnly0(getFileForSysCalls(f));
    }
    /** chmod() clearing every write bit. */
    private boolean setReadOnly0(File f)
    {
        String p = abs(f.getPath());
        long[] st = jdk.internal.misc.MetalRamfs.stat(p);
        if (st == null)
        {
            return false;
        }
        return jdk.internal.misc.MetalRamfs.chmod(p, (int) st[jdk.internal.misc.MetalRamfs.ST_MODE] & ~0222) == 0;
    }

    /* -- Filesystem interface -- */

    @Override
    public File[] listRoots() {
        return new File[] { new File("/") };
    }

    /* -- Disk usage -- */

    @Override
    public long getSpace(File f, int t) {
        return getSpace0(getFileForSysCalls(f), t);
    }
    /** statvfs(): TOTAL is f_frsize * f_blocks, FREE and USABLE f_bfree and f_bavail -- see statvfs0's decisions. */
    private long getSpace0(File f, int t)
    {
        if (jdk.internal.misc.MetalRamfs.stat(abs(f.getPath())) == null)
        {
            return 0L;
        }
        // statvfs() as UnixNativeDispatcher.statvfs0 answers it: the bytes stored in 4 KiB blocks, none free
        long frsize = 4096L;
        long blocks = (jdk.internal.misc.MetalRamfs.usedBytes() + frsize - 1L) / frsize;
        return t == SPACE_TOTAL ? frsize * blocks : 0L;
    }

    /* -- Basic infrastructure -- */

    /** pathconf(_PC_NAME_MAX): the tree sets no limit, so Linux's NAME_MAX -- the platform java.base here is. */
    private long getNameMax0(String path)
    {
        return 255L;
    }

    @Override
    public int getNameMax(String path) {
        long nameMax = getNameMax0(getPathForSysCalls(path));
        if (nameMax > Integer.MAX_VALUE) {
            nameMax = Integer.MAX_VALUE;
        }
        return (int)nameMax;
    }

    @Override
    public int compare(File f1, File f2) {
        return f1.getPath().compareTo(f2.getPath());
    }

    @Override
    public int hashCode(File f) {
        return f.getPath().hashCode() ^ 1234321;
    }

    private static native void initIDs();

    static {
        initIDs();
    }
}
