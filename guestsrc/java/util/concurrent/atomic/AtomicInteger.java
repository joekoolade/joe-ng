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

/** JDK-free {@code AtomicInteger} -- plain field access on joe-ng's single core (see {@link AtomicBoolean}). */
public class AtomicInteger extends Number
{
    private volatile int value;

    public AtomicInteger()
    {
    }

    public AtomicInteger(int initialValue)
    {
        value = initialValue;
    }

    public final int get()
    {
        return value;
    }

    public final void set(int newValue)
    {
        value = newValue;
    }

    public final void lazySet(int newValue)
    {
        value = newValue;
    }

    public final int getAndSet(int newValue)
    {
        int old = value;
        value = newValue;
        return old;
    }

    public final boolean compareAndSet(int expect, int update)
    {
        if (value == expect)
        {
            value = update;
            return true;
        }
        return false;
    }

    public final int getAndIncrement()
    {
        return value++;
    }

    public final int getAndDecrement()
    {
        return value--;
    }

    public final int incrementAndGet()
    {
        return ++value;
    }

    public final int decrementAndGet()
    {
        return --value;
    }

    public final int getAndAdd(int delta)
    {
        int old = value;
        value += delta;
        return old;
    }

    public final int addAndGet(int delta)
    {
        value += delta;
        return value;
    }

    public int intValue()
    {
        return value;
    }

    public long longValue()
    {
        return value;
    }

    public float floatValue()
    {
        return value;
    }

    public double doubleValue()
    {
        return value;
    }

    public String toString()
    {
        return Integer.toString(value);
    }
}
