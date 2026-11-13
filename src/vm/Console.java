/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-25
 */
package vm;

import board.bcm2711.Uart;
import magic.Magic;

/**
 * Blocking line I/O over the interrupt-driven mini-UART ({@link Uart}): the driver owns the RX/TX rings and
 * the AUX interrupt (SPI 125); this class adds what needs the scheduler -- a reader that BLOCKS on
 * {@link #RX_SEM} until the ISR posts it, instead of spinning. Writes go straight to {@link Uart}, which
 * queues them for the TX interrupt.
 */
public final class Console
{
    private Console() {}

    static final int RX_SEM = 2;             // VM scheduler semaphore posted by VMScheduler.schedule on RX data
    static final long RX_REPOLL_MS = 10L;    // a blocked reader re-polls this often even if no interrupt comes

    /** Queue one byte for transmission. */
    static void putc(byte b)
    {
        Uart.putc(b & 0xFF);
    }

    /** Queue {@code len} bytes of {@code msg} from {@code off} for transmission. */
    static void write(byte[] msg, int off, int len)
    {
        int i = 0;
        while (i < len)
        {
            putc(msg[off + i]);
            i = i + 1;
        }
    }

    /** Queue a whole byte array for transmission. */
    static void write(byte[] msg)
    {
        write(msg, 0, msg.length);
    }

    /**
     * Block until a byte has been received, then return it (0..255). Parks on {@link #RX_SEM} when it can
     * -- the interrupt is live, a scheduler exists and this caller has IRQs on -- and spins on the driver
     * otherwise, which is the only option before the interrupt (or with IRQs masked) and still correct.
     * The park is BOUNDED ({@link #RX_REPOLL_MS}) and every wake re-polls the FIFO, so an interrupt that is
     * never delivered costs latency rather than a reader blocked for ever. Measured, not hypothetical: QEMU
     * raspi4b never raises SPI 125 (or the timer PPI) to this image -- GICD_ISPENDR stays 0 with the line
     * enabled, group 1 and IRQs unmasked -- so on QEMU this loop is what reads.
     */
    static int readChar()
    {
        while (true)
        {
            int b = Uart.poll();
            if (b >= 0)
            {
                return b;
            }
            if (Uart.interruptsLive() && VM.taskSp != null && (Magic.readDaif() & 0x80L) == 0L)
            {
                VMScheduler.semWaitTimeout(RX_SEM, RX_REPOLL_MS);   // the RX ISR posts; a stale token loops
            }
        }
    }

    // ----- canonical-mode line discipline (System.in / fd 0 under stdin=uart) -----------------------
    // What a tty's default (ICANON|ECHO|ICRNL) mode does, so a program reading System.in behaves as it would
    // at a terminal: echo, Backspace/DEL erase, Enter delivers the line ending in one '\n', Ctrl-D at the start
    // of a line is end-of-file. A read blocks until a line is complete, then hands it out in pieces.

    static final int LINE_CAP = 1024;
    static int stdinOn;                      // 1 = /etc/init stdin=uart (VM.launchInit); 0 = System.in is empty
    private static byte[] line;
    private static int lineLen;              // bytes in the current line
    private static int linePos;              // bytes of it already handed out
    private static boolean lastCr;           // previous key was CR: a following LF is the same Enter

    /**
     * Copy up to {@code len} bytes of the current line to {@code dst}, first blocking for a new line if the
     * last one is used up. Returns the count, or -1 at end-of-file (Ctrl-D on an empty line, or stdin off).
     */
    static int readCooked(long dst, int len)
    {
        if (stdinOn == 0)
        {
            return -1;
        }
        if (linePos >= lineLen && !fillLine())
        {
            return -1;
        }
        int n = lineLen - linePos;
        if (n > len)
        {
            n = len;
        }
        int i = 0;
        while (i < n)
        {
            Magic.store8(dst + i, line[linePos + i]);
            i += 1;
        }
        linePos += n;
        return n;
    }

    /** Bytes of the current line not yet read: what {@code available()} answers. */
    static int cookedAvailable()
    {
        return lineLen - linePos;
    }

    /** Edit one line at the console. False = end-of-file (Ctrl-D with nothing typed). */
    private static boolean fillLine()
    {
        if (line == null)
        {
            line = new byte[LINE_CAP];
        }
        lineLen = 0;
        linePos = 0;
        while (true)
        {
            int c = readChar();
            if (c == 0x0A && lastCr)                       // CRLF from the terminal: the CR already ended it
            {
                lastCr = false;
                continue;
            }
            lastCr = c == 0x0D;
            if (c == 0x0D || c == 0x0A)
            {
                line[lineLen] = 0x0A;
                lineLen += 1;
                putc((byte) 0x0A);
                return true;
            }
            if (c == 0x04)                                 // Ctrl-D: EOF on an empty line, else send what's typed
            {
                return lineLen > 0;
            }
            if (c == 0x08 || c == 0x7F)                    // Backspace / DEL: erase one byte on screen too
            {
                if (lineLen > 0)
                {
                    lineLen -= 1;
                    putc((byte) 0x08);
                    putc((byte) 0x20);
                    putc((byte) 0x08);
                }
                continue;
            }
            if (lineLen < LINE_CAP - 1)                    // keep one slot for the '\n'
            {
                line[lineLen] = (byte) c;
                lineLen += 1;
                putc((byte) c);
            }
        }
    }

    /**
     * Block reading a line into {@code buf} (up to {@code max} bytes), echoing each character, until a
     * CR or LF. Returns the number of bytes read (excluding the terminator).
     */
    static int readLine(byte[] buf, int max)
    {
        int n = 0;
        while (n < max)
        {
            int b = readChar();
            if (b == 0x0D || b == 0x0A)                    // CR / LF ends the line
            {
                break;
            }
            buf[n] = (byte) b;
            n = n + 1;
            putc((byte) b);                                // echo the keystroke
        }
        putc((byte) 0x0A);                                 // echo the newline
        return n;
    }
}
