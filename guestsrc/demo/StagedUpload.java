/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-11-14
 */
package demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;

/**
 * One upload to the card, with its three stages overlapped: the calling thread reads the request body, a writer
 * thread writes each piece to the file and reads it straight back from the card, and a hasher thread takes the
 * SHA-256 of what came back. Done one after another they add up (MEASURED on the Pi for a 39 MB kernel: network
 * ~19 s, card write ~23 s, read-back 5 s, SHA-256 18.5 s); overlapped, the slowest stage sets the pace.
 *
 * <p>The check is the same as reading the finished file back: the digest is of bytes the CARD returned
 * ({@code MetalFat32} keeps no data cache), not of bytes received. Pieces are {@link #PIECE} bytes because each
 * write to a FAT32 file also rewrites the FAT, the FSInfo sector and the directory entry -- at 64 KiB that was
 * ~600 rounds of single-sector writes per kernel.
 */
final class StagedUpload
{
    /** Bytes per piece: one write and one read-back on the card. */
    static final int PIECE = 1024 * 1024;

    /** Pieces in flight; the memory an upload holds is this many times {@link #PIECE}. */
    private static final int POOL = 6;

    /** What an upload did: bytes stored, the card's SHA-256, and where the time went. */
    static final class Result
    {
        final long bytes;

        final String sha256;

        final String timing;

        Result(long bytes, String sha256, String timing)
        {
            this.bytes = bytes;
            this.sha256 = sha256;
            this.timing = timing;
        }
    }

    /** A buffer and how much of it is filled. */
    private static final class Piece
    {
        final byte[] b = new byte[PIECE];

        int n;
    }

    /** A blocking hand-off between two stages; {@link #take} answers null once it is closed and empty. */
    private static final class Pipe
    {
        private final ArrayDeque<Piece> q = new ArrayDeque<>();

        private boolean closed;

        synchronized void put(Piece p)
        {
            q.addLast(p);
            notifyAll();
        }

        synchronized Piece take() throws InterruptedException
        {
            while (q.isEmpty() && !closed)
            {
                wait();
            }
            return q.pollFirst();
        }

        synchronized void close()
        {
            closed = true;
            notifyAll();
        }
    }

    private final Pipe free = new Pipe();

    private final Pipe toWrite = new Pipe();

    private final Pipe toHash = new Pipe();

    /** The first failure in any stage; every stage stops when it is set. */
    private volatile IOException failure;

    private long netNs;

    private long writeNs;

    private long readNs;

    private long hashNs;

    private StagedUpload()
    {
    }

    /**
     * Copy up to {@code len} bytes of {@code body} into a new file at {@code path} and hash them as read back from
     * the card. The result's byte count is short when the body ended early; the caller decides what that means.
     * Throws the first write, read or digest failure.
     */
    static Result run(InputStream body, String path, long len) throws IOException
    {
        return new StagedUpload().go(body, path, len);
    }

    private Result go(InputStream body, String path, long len) throws IOException
    {
        int i = 0;
        while (i < POOL)
        {
            free.put(new Piece());
            i = i + 1;
        }
        MessageDigest md;
        try
        {
            md = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IOException(e);
        }
        long t0 = System.nanoTime();
        long[] stored = new long[1];
        try (RandomAccessFile f = new RandomAccessFile(path, "rw"))
        {
            f.setLength(0L);
            Thread writer = new Thread(() -> writeStage(f, stored), "boot-write");
            Thread hasher = new Thread(() -> hashStage(md), "boot-hash");
            writer.start();
            hasher.start();
            long got = 0L;
            try
            {
                got = receive(body, len);
            }
            catch (IOException e)
            {
                fail(e);                                  // the socket failed: stop the other two as well
            }
            finally
            {
                toWrite.close();                          // in this order: the writer drains into the hasher
                join(writer);
                toHash.close();
                join(hasher);
            }
            if (failure != null)
            {
                throw failure;
            }
            if (stored[0] != got)
            {
                throw new IOException("stored " + stored[0] + " of " + got + " bytes received");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            String timing = "staged in " + ms + " ms, overlapped: network " + netNs / 1_000_000L + " ms, card write "
                    + writeNs / 1_000_000L + " ms, read back " + readNs / 1_000_000L + " ms, sha256 "
                    + hashNs / 1_000_000L + " ms";
            return new Result(got, hex(md.digest()), timing);
        }
    }

    /** The calling thread's stage: fill pieces from the body and pass them on; the bytes read. */
    private long receive(InputStream body, long len) throws IOException
    {
        long total = 0L;
        while (total < len && failure == null)
        {
            Piece p = takeOrStop(free);
            if (p == null)
            {
                break;
            }
            int want = (int) Math.min((long) PIECE, len - total);
            int n = 0;
            long a = System.nanoTime();
            while (n < want)
            {
                int r = body.read(p.b, n, want - n);
                if (r < 0)
                {
                    break;
                }
                n = n + r;
            }
            netNs = netNs + (System.nanoTime() - a);
            p.n = n;
            total = total + n;
            if (n > 0)
            {
                toWrite.put(p);
            }
            if (n < want)
            {
                break;
            }
        }
        return total;
    }

    /** Write each piece, then read the same bytes back from the card into it, and pass it to the hasher. */
    private void writeStage(RandomAccessFile f, long[] stored)
    {
        long pos = 0L;
        try
        {
            while (true)
            {
                Piece p = toWrite.take();
                if (p == null || failure != null)
                {
                    break;
                }
                long a = System.nanoTime();
                f.seek(pos);
                f.write(p.b, 0, p.n);
                long b = System.nanoTime();
                f.seek(pos);
                f.readFully(p.b, 0, p.n);
                long c = System.nanoTime();
                writeNs = writeNs + (b - a);
                readNs = readNs + (c - b);
                pos = pos + p.n;
                stored[0] = pos;
                toHash.put(p);
            }
        }
        catch (IOException e)
        {
            fail(e);
        }
        catch (InterruptedException e)
        {
            fail(new IOException("writer interrupted"));
        }
    }

    /** Digest each read-back piece, then return it to the pool. */
    private void hashStage(MessageDigest md)
    {
        try
        {
            while (true)
            {
                Piece p = toHash.take();
                if (p == null)
                {
                    break;
                }
                long a = System.nanoTime();
                md.update(p.b, 0, p.n);
                hashNs = hashNs + (System.nanoTime() - a);
                free.put(p);
            }
        }
        catch (InterruptedException e)
        {
            fail(new IOException("hasher interrupted"));
        }
    }

    /** Record the first failure and wake every stage, so each one stops. */
    private void fail(IOException e)
    {
        synchronized (this)
        {
            if (failure == null)
            {
                failure = e;
            }
        }
        free.close();
        toWrite.close();
        toHash.close();
    }

    private Piece takeOrStop(Pipe p) throws IOException
    {
        try
        {
            return p.take();
        }
        catch (InterruptedException e)
        {
            throw new IOException("upload interrupted");
        }
    }

    private static void join(Thread t) throws IOException
    {
        try
        {
            t.join();
        }
        catch (InterruptedException e)
        {
            throw new IOException("upload interrupted");
        }
    }

    private static String hex(byte[] h)
    {
        StringBuilder sb = new StringBuilder();
        for (byte b : h)
        {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
