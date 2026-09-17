/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-25
 */
package java.util.zip;

/**
 * A JDK-free {@code java.util.zip.Adler32} overlay (wins by name). The stock class is native (an intrinsic),
 * so this delegates to {@link zip.Adler32} — the same implementation {@link zip.Deflate} uses for the zlib
 * trailer, so a stream's trailer and an application's {@code Adler32} can never disagree.
 */
public class Adler32 implements Checksum
{
    private int adler = zip.Adler32.initial();

    public Adler32()
    {
    }

    @Override
    public void update(int b)
    {
        adler = zip.Adler32.updateByte(adler, b);
    }

    /** Overridden rather than inherited: {@code Checksum}'s version is a default method. */
    @Override
    public void update(byte[] b)
    {
        update(b, 0, b.length);
    }

    @Override
    public void update(byte[] b, int off, int len)
    {
        Deflater.checkBounds(b, off, len);
        adler = zip.Adler32.update(adler, b, off, len);
    }

    @Override
    public long getValue()
    {
        return adler & 0xFFFFFFFFL;
    }

    @Override
    public void reset()
    {
        adler = zip.Adler32.initial();
    }
}
