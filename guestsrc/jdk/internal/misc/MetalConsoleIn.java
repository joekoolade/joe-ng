/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-13
 */
package jdk.internal.misc;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@code System.in} over the serial console, installed by {@code Loader.seedSystemIn} when {@code /etc/init}
 * says {@code stdin=uart}. Without that flag {@code System.in} stays an empty stream: a program that reads it
 * to the end gets end-of-file at once instead of blocking on a console nobody may be typing at.
 *
 * <p>The VM side ({@code vm.Console}) is a terminal in canonical mode, as a tty's default line discipline is:
 * keystrokes are echoed, Backspace/DEL erase, Enter (CR, LF or CRLF) ends the line and is delivered as a single
 * {@code \n}, and a read returns once a whole line is ready. Ctrl-D at the start of a line is end-of-file for
 * that read; the next read blocks for a new line, as on a tty.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. Stock {@code System.initPhase1} would build
 * {@code new BufferedInputStream(new FileInputStream(FileDescriptor.in))}; the line discipline already buffers
 * a whole line on the VM side, so this stream is unbuffered and {@code FileDescriptor.in} ({@code MetalFileIo},
 * fd 0) reads through the same natives.
 */
public final class MetalConsoleIn extends InputStream
{
    public MetalConsoleIn()
    {
    }

    /**
     * VM native ({@code VMNatives.consoleRead}): block until a line is ready, then copy up to {@code len} of its
     * bytes into {@code b[off..]}. The count, or -1 at end-of-file -- always -1 when {@code stdin=uart} is off.
     */
    private static native int read0(byte[] b, int off, int len);

    /** VM native ({@code VMNatives.consoleAvailable}): bytes of the current line not yet read; never blocks. */
    private static native int available0();

    /** The console read behind both {@code System.in} and fd 0; bounds already checked by the caller. */
    public static int readConsole(byte[] b, int off, int len)
    {
        return read0(b, off, len);
    }

    @Override
    public int read() throws IOException
    {
        byte[] one = new byte[1];
        int n = read0(one, 0, 1);
        if (n <= 0)
        {
            return -1;
        }
        return one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
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
        return read0(b, off, len);
    }

    @Override
    public int available() throws IOException
    {
        return available0();
    }

    /** Closing the console would cut off every later reader; like stock's fd 0, it stays open. */
    @Override
    public void close()
    {
    }
}
