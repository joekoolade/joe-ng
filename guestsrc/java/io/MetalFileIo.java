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
package java.io;

import jdk.internal.access.JavaIOFileDescriptorAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.MetalRamfs;
import magic.Magic;

/**
 * What {@code share/native/libjava/io_util.c} and {@code unix/native/libjava/io_util_md.c} do for the java.io
 * streams -- FileInputStream, FileOutputStream, RandomAccessFile -- in Java over the VM's tree
 * ({@link MetalRamfs}). Every stream holds a {@link FileDescriptor}; its int fd is a tree descriptor (from 100), or
 * 0/1/2 for the console. Errors are the C's: "Stream Closed" for an fd of -1, a FileNotFoundException of
 * {@code path (reason)} for an open, and an IOException with the errno's text otherwise.
 *
 * <p>The console, where the C would read or write the process's own descriptors: fds 1 and 2 write to the UART,
 * as {@code System.out}/{@code err} do; fd 0 reads as an empty stream, as {@code System.in} does -- the board has no
 * input console.
 */
final class MetalFileIo
{
    private MetalFileIo()
    {
    }

    private static final JavaIOFileDescriptorAccess FDA = SharedSecrets.getJavaIOFileDescriptorAccess();

    /** fileOpen(): open {@code name} with MetalRamfs flags {@code how} into {@code fdo}; a directory is EISDIR. */
    static void open(FileDescriptor fdo, String name, int how) throws FileNotFoundException
    {
        String p = name.startsWith("/") ? name : System.getProperty("user.dir") + "/" + name;
        p = MetalRamfs.normalize(p);
        int fd = MetalRamfs.open(p, how, 0666);
        if (fd >= 0 && MetalRamfs.fdIsDirectory(fd))
        {
            MetalRamfs.close(fd);                      // handleOpen: fstat says directory -> close, EISDIR
            fd = -MetalRamfs.ISDIR;
        }
        if (fd < 0)
        {
            throw new FileNotFoundException(name + " (" + MetalRamfs.strerror(fd) + ")");
        }
        FDA.set(fdo, fd);
    }

    /** The int fd, or "Stream Closed" for -1, as the C's getFD check. */
    static int fd(FileDescriptor fdo) throws IOException
    {
        int fd = fdo == null ? -1 : FDA.get(fdo);
        if (fd == -1)
        {
            throw new IOException("Stream Closed");
        }
        return fd;
    }

    /** {@code r} if not negative, else JNU_ThrowIOExceptionWithLastError's IOException: the errno's text. */
    private static int check(int r) throws IOException
    {
        if (r < 0)
        {
            throw new IOException(MetalRamfs.strerror(r));
        }
        return r;
    }

    /** readSingle(): the byte, or -1 at the end. */
    static int readSingle(FileDescriptor fdo) throws IOException
    {
        byte[] one = new byte[1];
        int n = readBytes(fdo, one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    /** readBytes(): the count, or -1 at the end; the C's null and bounds checks first. */
    static int readBytes(FileDescriptor fdo, byte[] b, int off, int len) throws IOException
    {
        if (b == null)
        {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || b.length - off < len)
        {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0)
        {
            return 0;
        }
        int fd = fd(fdo);
        if (fd == 0)
        {
            return -1;                                 // no input console: an empty stream, as System.in is
        }
        int n = check(MetalRamfs.readArray(fd, b, off, len));
        return n == 0 ? -1 : n;
    }

    /** writeSingle(). */
    static void writeSingle(FileDescriptor fdo, int b) throws IOException
    {
        byte[] one = new byte[1];
        one[0] = (byte) b;
        writeBytes(fdo, one, 0, 1);
    }

    /** writeBytes(): the C's null and bounds checks, then every byte. */
    static void writeBytes(FileDescriptor fdo, byte[] b, int off, int len) throws IOException
    {
        if (b == null)
        {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || b.length - off < len)
        {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0)
        {
            return;
        }
        int fd = fd(fdo);
        if (fd == 1 || fd == 2)
        {
            Magic.printStr(java.util.Arrays.copyOfRange(b, off, off + len));   // the UART, as System.out/err
            return;
        }
        check(MetalRamfs.writeArray(fd, b, off, len));
    }

    /** lseek(fd, 0, SEEK_CUR). */
    static long position(FileDescriptor fdo) throws IOException
    {
        int fd = fd(fdo);
        long pos = MetalRamfs.position(fd);
        if (pos < 0L)
        {
            throw new IOException(MetalRamfs.strerror(-MetalRamfs.BADF));
        }
        return pos;
    }

    /** lseek(fd, pos, SEEK_SET); a negative result is EINVAL, as lseek(2) answers. */
    static void seek(FileDescriptor fdo, long pos) throws IOException
    {
        int fd = fd(fdo);
        if (pos < 0L)
        {
            throw new IOException(MetalRamfs.strerror(-MetalRamfs.INVAL));
        }
        if (!MetalRamfs.seek(fd, pos))
        {
            throw new IOException(MetalRamfs.strerror(-MetalRamfs.BADF));
        }
    }

    /** handleGetLength(): fstat's st_size. */
    static long length(FileDescriptor fdo) throws IOException
    {
        int fd = fd(fdo);
        long n = MetalRamfs.fdSize(fd);
        if (n < 0L)
        {
            throw new IOException(MetalRamfs.strerror(-MetalRamfs.BADF));
        }
        return n;
    }

    /** handleSetLength(): ftruncate. */
    static void setLength(FileDescriptor fdo, long n) throws IOException
    {
        check(MetalRamfs.truncate(fd(fdo), n));
    }

    /** IO_IsRegularFile: fstat says S_IFREG; the console and anything not open are not. */
    static boolean isRegularFile(FileDescriptor fdo)
    {
        int fd = fdo == null ? -1 : FDA.get(fdo);
        long[] st = MetalRamfs.fstat(fd);
        return st != null && st[MetalRamfs.ST_DIR] == 0L;
    }

    /** close(fd) for FileCleanable: 0/1/2 are left open, as FileDescriptor.close0 leaves them. */
    static void close(int fd) throws IOException
    {
        if (fd == -1 || (fd >= 0 && fd <= 2))
        {
            return;
        }
        if (!MetalRamfs.close(fd))
        {
            throw new IOException(MetalRamfs.strerror(-MetalRamfs.BADF));
        }
    }
}
