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
package java.time;

/**
 * A JDK-free, minimal {@code java.time.Duration}: a signed length of time stored as total nanoseconds (enough
 * for the ranges the tests use). Only the factory methods and conversions the JoinWithDuration test needs.
 */
public final class Duration
{
    private final long nanos;

    private Duration(long nanos)
    {
        this.nanos = nanos;
    }

    public static Duration ofNanos(long n)
    {
        return new Duration(n);
    }

    public static Duration ofMillis(long ms)
    {
        return new Duration(ms * 1000000L);
    }

    public static Duration ofSeconds(long s)
    {
        return new Duration(s * 1000000000L);
    }

    public static Duration ofMinutes(long m)
    {
        return new Duration(m * 60000000000L);
    }

    public long toNanos()
    {
        return nanos;
    }

    public long toMillis()
    {
        return nanos / 1000000L;
    }
}
