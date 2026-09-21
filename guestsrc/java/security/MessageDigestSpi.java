/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-21
 */
package java.security;

import java.nio.ByteBuffer;

/**
 * The service-provider interface for {@link MessageDigest}.
 *
 * <p>OVERLAID rather than taken from stock for ONE reason, and it is a small one: stock's
 * {@code engineUpdate(ByteBuffer)} borrows a scratch array from {@code sun.security.jca.JCAUtil}, and
 * {@code sun/security/} is denied here (it is what the JAR-signature-verification closure hangs off). The
 * rest of this class is the stock shape and the stock contract; the buffer path simply allocates its own
 * scratch instead of pooling one.
 *
 * <p>The abstract set is stock's: {@code engineUpdate(byte)}, {@code engineUpdate(byte[],int,int)},
 * {@code engineDigest()} and {@code engineReset()} must be implemented; the rest have working defaults, so
 * an ordinary subclass written against the real JDK compiles and runs here unchanged.
 */
public abstract class MessageDigestSpi
{
    /**
     * {@return the digest length in bytes, or 0 if the implementation does not know it}
     * Stock leaves this concrete rather than abstract for backwards compatibility, and so does this.
     */
    protected int engineGetDigestLength()
    {
        return 0;
    }

    /** Feed one byte. */
    protected abstract void engineUpdate(byte input);

    /** Feed {@code input[offset..offset+len)}. */
    protected abstract void engineUpdate(byte[] input, int offset, int len);

    /**
     * Feed the buffer's remaining bytes, leaving its position at its limit.
     *
     * <p>The array-backed case hands the backing array straight to {@link #engineUpdate(byte[], int, int)}
     * rather than copying -- {@code arrayOffset() + position()} is where the remaining bytes actually start,
     * and getting that offset wrong digests the WRONG BYTES rather than failing.
     */
    protected void engineUpdate(ByteBuffer input)
    {
        if (!input.hasRemaining())
        {
            return;
        }
        if (input.hasArray())
        {
            byte[] b = input.array();
            int ofs = input.arrayOffset();
            int pos = input.position();
            int lim = input.limit();
            engineUpdate(b, ofs + pos, lim - pos);
            input.position(lim);
            return;
        }
        int len = input.remaining();
        int chunk = len < 4096 ? len : 4096;
        byte[] tmp = new byte[chunk];
        while (len > 0)
        {
            int n = len < chunk ? len : chunk;
            input.get(tmp, 0, n);
            engineUpdate(tmp, 0, n);
            len = len - n;
        }
    }

    /** {@return the finished digest} Resets this object. */
    protected abstract byte[] engineDigest();

    /**
     * Finish the digest into {@code buf[offset..offset+len)}.
     *
     * @return the number of bytes written
     * @throws DigestException if {@code len} is shorter than the digest, or the range is out of bounds
     */
    protected int engineDigest(byte[] buf, int offset, int len) throws DigestException
    {
        byte[] digest = engineDigest();
        if (len < digest.length)
        {
            throw new DigestException("partial digests not returned");
        }
        if (buf.length - offset < digest.length)
        {
            throw new DigestException("insufficient space in the output buffer to store the digest");
        }
        System.arraycopy(digest, 0, buf, offset, digest.length);
        return digest.length;
    }

    /** Discard all fed input. */
    protected abstract void engineReset();

    /**
     * {@return a copy of this object, if the implementation is {@link Cloneable}}
     *
     * @throws CloneNotSupportedException if it is not
     */
    @Override
    public Object clone() throws CloneNotSupportedException
    {
        if (this instanceof Cloneable)
        {
            return super.clone();
        }
        throw new CloneNotSupportedException();
    }
}
