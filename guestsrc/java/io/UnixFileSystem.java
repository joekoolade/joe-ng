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

    // joe-ng: every native below is implemented over the read-only file table (jdk.internal.misc.MetalRamfs),
    // its semantics read from unix/native/libjava/UnixFileSystem_md.c. Where the C makes a syscall that fails on
    // a read-only filesystem (EROFS), the answer here is what the C returns for that failure.

    /** JDK_Canonicalize, without symlinks to resolve (the table has none): "." and ".." resolved lexically. */
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

    /** stat(): a table entry is a regular file; "/" and every implicit directory a directory. */
    private int getBooleanAttributes0(File f)
    {
        String p = f.getPath();
        if (jdk.internal.misc.MetalRamfs.find(p) != 0L)
        {
            return BA_EXISTS | BA_REGULAR;
        }
        if (jdk.internal.misc.MetalRamfs.isDirectory(p))
        {
            return BA_EXISTS | BA_DIRECTORY;
        }
        return 0;
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
     * access(): READ whenever it exists; WRITE never (EROFS); EXECUTE -- search -- for a directory only: the
     * table carries no modes, and a file in it is data, not a program.
     */
    private boolean checkAccess0(File f, int access)
    {
        int ba = getBooleanAttributes0(f);
        if ((ba & BA_EXISTS) == 0)
        {
            return false;
        }
        if (access == ACCESS_READ)
        {
            return true;
        }
        if (access == ACCESS_EXECUTE)
        {
            return (ba & BA_DIRECTORY) != 0;
        }
        return false;
    }

    @Override
    public long getLastModifiedTime(File f) {
        return getLastModifiedTime0(getFileForSysCalls(f));
    }
    /** 0 -- the API's own "unknown": the image carries no timestamps, so there is no time to report. */
    private long getLastModifiedTime0(File f)
    {
        return 0L;
    }

    @Override
    public long getLength(File f) {
        return getLength0(getFileForSysCalls(f));
    }
    /** stat().st_size of a file; 0 for a directory, which occupies nothing here; 0 when absent, as in C. */
    private long getLength0(File f)
    {
        long e = jdk.internal.misc.MetalRamfs.find(f.getPath());
        return e == 0L ? 0L : jdk.internal.misc.MetalRamfs.size(e);
    }

    @Override
    public boolean setPermission(File f, int access, boolean enable, boolean owneronly) {
        return setPermission0(getFileForSysCalls(f), access, enable, owneronly);
    }
    /** chmod() fails with EROFS: false, as the C returns. */
    private boolean setPermission0(File f, int access, boolean enable, boolean owneronly)
    {
        return false;
    }

    /* -- File operations -- */

    @Override
    public boolean createFileExclusively(String path) throws IOException {
        return createFileExclusively0(path);
    }
    /**
     * open(O_CREAT|O_EXCL): "/" always exists (false), an existing file is EEXIST (false), and any other path is
     * EROFS, which the C turns into an IOException with the errno text.
     */
    private boolean createFileExclusively0(String path) throws IOException
    {
        if (path.equals("/") || jdk.internal.misc.MetalRamfs.find(path) != 0L
                || jdk.internal.misc.MetalRamfs.isDirectory(path))
                {
            return false;
        }
        throw new IOException("Read-only file system");
    }

    @Override
    public boolean delete(File f) {
        return delete0(getFileForSysCalls(f));
    }
    /** remove() fails (EROFS, or ENOENT): false. */
    private boolean delete0(File f)
    {
        return false;
    }

    @Override
    public String[] list(File f) {
        return list0(getFileForSysCalls(f));
    }
    /** opendir()/readdir() without "." and "..": the names in the directory, or null if it is not one. */
    private String[] list0(File f)
    {
        return jdk.internal.misc.MetalRamfs.list(f.getPath());
    }

    @Override
    public boolean createDirectory(File f) {
        return createDirectory0(getFileForSysCalls(f));
    }
    /** mkdir() fails (EROFS, or EEXIST): false. */
    private boolean createDirectory0(File f)
    {
        return false;
    }

    @Override
    public boolean rename(File f1, File f2) {
        return rename0(getFileForSysCalls(f1), getFileForSysCalls(f2));
    }
    /** rename() fails with EROFS: false. */
    private boolean rename0(File f1, File f2)
    {
        return false;
    }

    @Override
    public boolean setLastModifiedTime(File f, long time) {
        return setLastModifiedTime0(getFileForSysCalls(f), time);
    }
    /** utimes() fails with EROFS: false. */
    private boolean setLastModifiedTime0(File f, long time)
    {
        return false;
    }

    @Override
    public boolean setReadOnly(File f) {
        return setReadOnly0(getFileForSysCalls(f));
    }
    /** chmod() fails with EROFS: false, though the file is in fact read-only already. */
    private boolean setReadOnly0(File f)
    {
        return false;
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
    /** statvfs(): TOTAL is the bytes the table holds; nothing is FREE or USABLE on a read-only filesystem. */
    private long getSpace0(File f, int t)
    {
        if ((getBooleanAttributes0(f) & BA_EXISTS) == 0)
        {
            return 0L;
        }
        return t == SPACE_TOTAL ? jdk.internal.misc.MetalRamfs.totalBytes() : 0L;
    }

    /* -- Basic infrastructure -- */

    /** pathconf(_PC_NAME_MAX): the table sets no limit, so Linux's NAME_MAX -- the platform java.base here is. */
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
