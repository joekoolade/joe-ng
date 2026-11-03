/*
 * Copyright (c) 2000, 2025, Oracle and/or its affiliates. All rights reserved.
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

/**
 * Allows different platforms to call different native methods
 * for read and write operations.
 */

class SocketDispatcher extends UnixDispatcher {
    SocketDispatcher() { }

    /**
     * Reads up to len bytes from a socket with special handling for "connection
     * reset".
     *
     * @throws sun.net.ConnectionResetException if connection reset is detected
     * @throws IOException if another I/O error occurs
     */
    @Override
    int read(FileDescriptor fd, long address, int len) throws IOException {
        // joe-ng: read0 is a VM native, and a VM native cannot throw. It answers an error with the NEGATED errno
        // read(2) would have set (net.Tcp.read), and this throws what SocketDispatcher.c throws for it:
        // ConnectionResetException for ECONNRESET/EPIPE, else convertReturnVal's IOException.
        int n = read0(fd, address, len);
        if (n < IOStatus.UNSUPPORTED_CASE) {
            throw ioFailure(-n, true);
        }
        return n;
    }

    /**
     * Scattering read from a socket into len buffers with special handling for
     * "connection reset".
     *
     * @throws sun.net.ConnectionResetException if connection reset is detected
     * @throws IOException if another I/O error occurs
     */
    @Override
    long readv(FileDescriptor fd, long address, int len) throws IOException {
        return readv0(fd, address, len);
    }

    @Override
    int write(FileDescriptor fd, long address, int len) throws IOException {
        // joe-ng: as read -- write0 answers an error with the negated errno, and stock (convertReturnVal) throws
        // an IOException for it. Returning it instead let NioSocketImpl.write add it to its offset.
        int n = write0(fd, address, len);
        if (n < IOStatus.UNSUPPORTED_CASE) {
            throw ioFailure(-n, false);
        }
        return n;
    }

    // joe-ng: the exception SocketDispatcher.c / IOUtil.c convertReturnVal throws for errno, with the Linux
    // strerror text as the message (JNU_ThrowIOExceptionWithLastError).
    private static IOException ioFailure(int errno, boolean reading) {
        if (reading && (errno == 104 || errno == 32)) {
            return new sun.net.ConnectionResetException("Connection reset");
        }
        switch (errno) {
            case 104: return new IOException("Connection reset by peer");
            case 32:  return new IOException("Broken pipe");
            case 107: return new IOException("Transport endpoint is not connected");
            case 9:   return new IOException("Bad file descriptor");
            default:  return new IOException("errno " + errno);
        }
    }

    @Override
    long writev(FileDescriptor fd, long address, int len) throws IOException {
        return writev0(fd, address, len);
    }

    // -- Native methods --

    private static native int read0(FileDescriptor fd, long address, int len)
        throws IOException;

    private static native long readv0(FileDescriptor fd, long address, int len)
        throws IOException;

    private static native int write0(FileDescriptor fd, long address, int len)
        throws IOException;

    private static native long writev0(FileDescriptor fd, long address, int len)
        throws IOException;

    static {
        IOUtil.load();
    }
}
