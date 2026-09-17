/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-08-11
 */
package java.util.concurrent.atomic;

/** JDK-free {@code AtomicLongArray} -- a long[] with plain element access (single core; see AtomicBoolean). */
public class AtomicLongArray
{
    private final long[] array;

    public AtomicLongArray(int length)
    {
        array = new long[length];
    }

    public final int length()
    {
        return array.length;
    }

    public final long get(int i)
    {
        return array[i];
    }

    public final void set(int i, long newValue)
    {
        array[i] = newValue;
    }

    public final void lazySet(int i, long newValue)
    {
        array[i] = newValue;
    }

    public final long getAndSet(int i, long newValue)
    {
        long old = array[i];
        array[i] = newValue;
        return old;
    }

    public final boolean compareAndSet(int i, long expect, long update)
    {
        if (array[i] == expect)
        {
            array[i] = update;
            return true;
        }
        return false;
    }
}
