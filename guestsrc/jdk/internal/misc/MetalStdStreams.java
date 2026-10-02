/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-02
 */
package jdk.internal.misc;

import java.io.OutputStream;
import java.io.PrintStream;
import magic.Magic;

/**
 * Builds {@code System.out} and {@code System.err} as STOCK {@link PrintStream}s, for
 * {@code Loader.seedSystemStreams}.
 *
 * <p>Stock {@code System.initPhase1} wraps {@code new FileOutputStream(FileDescriptor.out)}, whose sink is the
 * native {@code writeBytes}. joe-ng has a UART and no file descriptors, so the byte sink is {@link Uart}
 * below -- and everything above it, the {@code PrintStream} with its {@code BufferedWriter},
 * {@code OutputStreamWriter} and {@code synchronized} methods, is stock java.base, unmodified.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. It supplies what the platform's initialization would have
 * built, as {@code MetalJavaLangAccess} supplies the access shim.
 *
 * <p>No {@code BufferedOutputStream} under the stream, deliberately, where stock puts a 128-byte one. The VM
 * prints its own diagnostics straight to the UART, and a buffered guest stream would hold a program's last
 * line back while a fault report printed ahead of it -- reordering the one log this VM is debugged from.
 * Stock {@code PrintStream} already pushes every write through to {@code out} ({@code implWrite} flushes its
 * text buffers on each call), so unbuffered here means every print reaches the wire before it returns.
 */
public final class MetalStdStreams
{
    private MetalStdStreams()
    {
    }

    /** A new stock PrintStream over the UART: autoflush, the default charset (UTF-8). */
    public static PrintStream newStream()
    {
        return new PrintStream(new Uart(), true);
    }

    /**
     * The byte sink: raw bytes to the UART, never re-encoded -- {@code Magic.printStr} writes each byte
     * through {@code Uart.putc}, which is where {@code \n} becomes CRLF for every line the boot prints.
     * Nothing is buffered, so {@code flush} has nothing to do, and the UART cannot fail, so nothing throws.
     */
    static final class Uart extends OutputStream
    {
        @Override
        public void write(int b)
        {
            byte[] one = new byte[1];
            one[0] = (byte) b;
            Magic.printStr(one);
        }

        @Override
        public void write(byte[] b, int off, int len)
        {
            if (off == 0 && len == b.length)
            {
                Magic.printStr(b);
                return;
            }
            byte[] slice = new byte[len];
            System.arraycopy(b, off, slice, 0, len);
            Magic.printStr(slice);
        }

        /** Closing the UART would silence the console for the rest of the boot. */
        @Override
        public void close()
        {
        }
    }
}
