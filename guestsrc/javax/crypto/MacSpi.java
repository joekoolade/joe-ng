/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-09-22
 */
package javax.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;

/**
 * The service-provider interface for {@link Mac}.
 *
 * <p>OVERLAID for the same small reason {@code java.security.MessageDigestSpi} is: stock's
 * {@code engineUpdate(ByteBuffer)} borrows a scratch array from {@code sun.security.jca.JCAUtil}, and
 * {@code sun/security/} is denied here -- it is what the JAR-signature-verification closure hangs off. The
 * rest is the stock shape and the stock contract; the buffer path allocates its own scratch instead of
 * pooling one.
 *
 * <p>The abstract set is stock's, so a subclass written against the real JDK compiles and runs here
 * unchanged.
 */
public abstract class MacSpi
{
    /** {@return the MAC length in bytes} */
    protected abstract int engineGetMacLength();

    /**
     * Initialize with a key and optional parameters.
     *
     * @throws InvalidKeyException                if the key is unsuitable
     * @throws InvalidAlgorithmParameterException if the parameters are
     */
    protected abstract void engineInit(Key key, AlgorithmParameterSpec params)
            throws InvalidKeyException, InvalidAlgorithmParameterException;

    /** Feed one byte. */
    protected abstract void engineUpdate(byte input);

    /** Feed {@code input[offset..offset+len)}. */
    protected abstract void engineUpdate(byte[] input, int offset, int len);

    /**
     * Feed the buffer's remaining bytes, leaving its position at its limit.
     *
     * <p>The array-backed case hands the backing array straight to {@link #engineUpdate(byte[], int, int)}
     * rather than copying -- {@code arrayOffset() + position()} is where the remaining bytes actually start,
     * and getting that offset wrong authenticates the WRONG BYTES rather than failing.
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

    /** {@return the finished MAC} Resets this object to the state it had just after {@code engineInit}. */
    protected abstract byte[] engineDoFinal();

    /** Discard any fed input, keeping the key. */
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
