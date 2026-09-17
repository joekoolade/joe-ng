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

/** JDK-free {@code AtomicReference} -- plain field access on joe-ng's single core (see {@link AtomicBoolean}). */
public class AtomicReference<V>
{
    private volatile V value;

    public AtomicReference()
    {
    }

    public AtomicReference(V initialValue)
    {
        value = initialValue;
    }

    public final V get()
    {
        return value;
    }

    public final void set(V newValue)
    {
        value = newValue;
    }

    public final void lazySet(V newValue)
    {
        value = newValue;
    }

    public final V getAndSet(V newValue)
    {
        V old = value;
        value = newValue;
        return old;
    }

    public final boolean compareAndSet(V expect, V update)
    {
        if (value == expect)
        {
            value = update;
            return true;
        }
        return false;
    }

    public String toString()
    {
        return String.valueOf(value);
    }
}
