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
package jdk.internal.misc;

import java.util.TreeMap;
import magic.Magic;

/**
 * One file or directory of the tree {@link MetalRamfs} serves. A file's content starts as the image's -- the baked
 * table entry, read in place -- and is copied into a heap array the first time it is written (copy-on-write), so a
 * file nobody writes costs nothing. A directory holds its entries by name. Every field is guarded by
 * {@code MetalRamfs}'s lock.
 */
final class MetalFsNode
{
    final boolean dir;
    final long ino;
    int mode;                                            // permission bits (07777)
    int uid;
    int gid;
    int nlink;                                           // a file's hard links; a directory's is computed
    long atime;                                          // nanoseconds since the epoch
    long mtime;
    long ctime;
    int device;                                          // MetalRamfs.DEV_* for a character device, else 0
    long baked;                                          // the table entry while the content is the image's, else 0
    byte[] data;                                         // the content once written; capacity may exceed size
    long size;
    TreeMap<String, MetalFsNode> kids;                   // a directory's entries
    MetalFat32.Entry fat;                                // on the card's boot partition: what it is there
    boolean mount;                                       // the /boot mount point itself
    boolean mountPending;                                // ... not yet read from the card

    MetalFsNode(boolean dir, long ino, int mode, long time)
    {
        this.dir = dir;
        this.ino = ino;
        this.mode = mode;
        this.nlink = 1;
        this.atime = time;
        this.mtime = time;
        this.ctime = time;
        if (dir)
        {
            kids = new TreeMap<>();
        }
    }

    /** The byte at {@code pos}, which is below {@code size}. Not for a card file: MetalRamfs reads those in bulk. */
    int byteAt(long pos)
    {
        if (data != null)
        {
            return data[(int) pos] & 0xFF;
        }
        if (fat != null)
        {
            throw new IllegalStateException("joe-ng: byteAt on /boot file " + fat.name);
        }
        return Magic.load8(Magic.load64(baked + 16L) + pos) & 0xFF;
    }

    /** Make the content a heap array of at least {@code cap} bytes, copying the image's on the first write. */
    void ensureCapacity(int cap)
    {
        if (data == null)
        {
            byte[] d = new byte[Math.max(cap, (int) size)];
            for (int k = 0; k < (int) size; k++)
            {
                d[k] = (byte) byteAt(k);
            }
            data = d;
            baked = 0L;
            return;
        }
        if (cap > data.length)
        {
            int grown = (int) Math.min((long) Integer.MAX_VALUE - 8L, Math.max((long) cap, 2L * data.length));
            data = java.util.Arrays.copyOf(data, grown);
        }
    }

    /** Shrink or extend to {@code n} bytes; bytes past the old end read as zero. */
    void setSize(long n)
    {
        if (n < size)
        {
            ensureCapacity((int) size);
            java.util.Arrays.fill(data, (int) n, (int) size, (byte) 0);
        }
        else if (n > size)
        {
            ensureCapacity((int) n);
        }
        size = n;
    }

    /** A copy for clonefile: the same content, mode and times, a new inode; a directory's entries are the caller's. */
    MetalFsNode copy(long newIno)
    {
        MetalFsNode c = new MetalFsNode(dir, newIno, mode, mtime);
        c.uid = uid;
        c.gid = gid;
        c.atime = atime;
        c.baked = baked;
        c.size = size;
        if (data != null)
        {
            c.data = java.util.Arrays.copyOf(data, (int) size);
        }
        return c;
    }
}
