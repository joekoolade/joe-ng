/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-21
 */
package util;

/**
 * The {@code byte[]}-keyed sibling of {@link StrIntTable}: an insertion-ordered
 * {@code content -> int} table (PLAN.md §M5.5b). Same contract as {@code StrIntTable}
 * ({@link #get} returns -1 when absent, insertion order preserved for layout), so it
 * drops in wherever the writer keyed a table by name. Linear scan; symbol counts are tiny.
 */
public final class ByteKeyIntTable
{
    private byte[][] keys = new byte[8][];
    private int[] vals = new int[8];
    private int n;

    public int size()
    {
        return n;
    }
    public byte[] keyAt(int i)
    {
        return keys[i];
    }
    public int valAt(int i)
    {
        return vals[i];
    }

    private int indexOf(byte[] k)
    {
        for (int i = 0; i < n; i++)
        {
            if (Bytes.eq(keys[i], k))
            {
                return i;
            }
        }
        return -1;
    }

    public boolean containsKey(byte[] k)
    {
        return indexOf(k) >= 0;
    }

    /** The value for {@code k}, or -1 if absent (word offsets are always &ge; 0). */
    public int get(byte[] k)
    {
        int i = indexOf(k);
        return i >= 0 ? vals[i] : -1;
    }

    public void put(byte[] k, int v)
    {
        int i = indexOf(k);
        if (i >= 0)
        {
            vals[i] = v;
            return;
        }
        if (n == keys.length)
        {
            grow();
        }
        keys[n] = k;
        vals[n] = v;
        n += 1;
    }

    private void grow()
    {
        byte[][] nk = new byte[keys.length * 2][];
        int[] nv = new int[vals.length * 2];
        for (int i = 0; i < n; i++)
        {
            nk[i] = keys[i];
            nv[i] = vals[i];
        }
        keys = nk;
        vals = nv;
    }
}
