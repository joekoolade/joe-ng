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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * The board itself: this image's build id, the firmware's one-shot tryboot flag, and reset. What the web server's
 * boot-update endpoints need beyond the {@code /boot} file tree.
 *
 * <p>NOT AN OVERLAY -- it shadows no stock class. The VM side is {@code vm.VMNatives.board*}.
 */
public final class MetalBoard
{
    private MetalBoard()
    {
    }

    /** VM native: the id {@code BuildRuntimeImage} stamped into this image. */
    private static native long buildId0();

    /** VM native: firmware SET_REBOOT_FLAGS; 0 when the firmware answered. */
    private static native int setRebootFlags0(int flags);

    /** VM native: drain the console and reset the SoC. Never returns. */
    private static native void reboot0();

    /** The trailer's first 16 bytes, as {@code writer.BuildRuntimeImage.TRAILER_MAGIC}. */
    private static final String TRAILER_MAGIC = "joe-ng build id\n";

    /** Bytes of the trailer at the end of a {@code kernel8.img}: the magic, then 16 hex digits. */
    private static final int TRAILER_BYTES = 32;

    /** This image's build id: 16 lowercase hex digits. */
    public static String buildId()
    {
        return hex16(buildId0());
    }

    /**
     * The build id in the trailer of the image file at {@code path} (a staged {@code /boot/kernel8.new}, say),
     * or null when the file is too short or carries no trailer -- an image from before build ids, or not an
     * image at all.
     */
    public static String buildIdOf(String path) throws IOException
    {
        try (RandomAccessFile f = new RandomAccessFile(path, "r"))
        {
            long len = f.length();
            if (len < TRAILER_BYTES)
            {
                return null;
            }
            byte[] t = new byte[TRAILER_BYTES];
            f.seek(len - TRAILER_BYTES);
            f.readFully(t);
            String s = new String(t, StandardCharsets.ISO_8859_1);
            if (!s.startsWith(TRAILER_MAGIC))
            {
                return null;
            }
            String id = s.substring(TRAILER_MAGIC.length());
            int i = 0;
            while (i < id.length())
            {
                char c = id.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
                {
                    return null;
                }
                i = i + 1;
            }
            return id;
        }
    }

    /**
     * Make the next reset a trial boot: the firmware reads {@code tryboot.txt} in place of {@code config.txt},
     * once. Throws if the firmware does not answer (QEMU's mailbox does not implement the tag).
     */
    public static void setTryboot() throws IOException
    {
        if (setRebootFlags0(1) != 0)
        {
            throw new IOException("firmware did not answer SET_REBOOT_FLAGS");
        }
    }

    /** Reset the board. Never returns. */
    public static void reboot()
    {
        reboot0();
        while (true)
        {
            Thread.onSpinWait();
        }
    }

    private static String hex16(long v)
    {
        String h = Long.toHexString(v);
        StringBuilder sb = new StringBuilder();
        int i = h.length();
        while (i < 16)
        {
            sb.append('0');
            i = i + 1;
        }
        return sb.append(h).toString();
    }
}
