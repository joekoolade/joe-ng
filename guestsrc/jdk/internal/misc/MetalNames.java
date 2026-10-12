/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-11
 */
package jdk.internal.misc;

/**
 * A class, field, method or resource NAME as the bytes joe-ng's natives compare against: the class file's own
 * encoding, modified UTF-8 (JVMS 4.4.7) -- NUL as {@code C0 80}, every UTF-16 unit (surrogates included) as one to
 * three bytes.
 *
 * <p>Charset-free on purpose. The overlays used {@code name.getBytes()}, which goes through
 * {@code Charset.defaultCharset()} -- and once {@code Charset} runs STOCK, its own {@code <clinit>} builds
 * {@code LazyConstant}s whose initializer asks {@code Unsafe.objectFieldOffset} for a field offset, which encoded
 * the field name through the default charset that was still being initialized: an NPE in
 * {@code Charset.defaultCharset}. Stock {@code Unsafe} hands the {@code String} to a native and has no such
 * dependency; neither does this.
 */
public final class MetalNames
{
    private MetalNames()
    {
    }

    public static byte[] utf8(String s)
    {
        int n = s.length();
        int len = 0;
        int i = 0;
        while (i < n)
        {
            len += unitLength(s.charAt(i));
            i += 1;
        }
        byte[] out = new byte[len];
        int p = 0;
        i = 0;
        while (i < n)
        {
            char c = s.charAt(i);
            if (c != 0 && c < 0x80)
            {
                out[p] = (byte) c;
                p += 1;
            }
            else if (c < 0x800)
            {
                out[p] = (byte) (0xC0 | (c >> 6));
                out[p + 1] = (byte) (0x80 | (c & 0x3F));
                p += 2;
            }
            else
            {
                out[p] = (byte) (0xE0 | (c >> 12));
                out[p + 1] = (byte) (0x80 | ((c >> 6) & 0x3F));
                out[p + 2] = (byte) (0x80 | (c & 0x3F));
                p += 3;
            }
            i += 1;
        }
        return out;
    }

    private static int unitLength(char c)
    {
        if (c != 0 && c < 0x80)
        {
            return 1;
        }
        if (c < 0x800)
        {
            return 2;
        }
        return 3;
    }
}
