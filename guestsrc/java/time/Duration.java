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

    /**
     * {@code getSeconds()}/{@code getNano()} -- the SECONDS-PLUS-NANO-OF-SECOND view stock callers read.
     *
     * <p>THEY ARE HERE BECAUSE STOCK {@code java.util.concurrent.TimeUnit} READS THEM, and that class is no
     * longer overlaid: {@code TimeUnit.convert(Duration)} opens {@code duration.getSeconds()} /
     * {@code duration.getNano()}. An overlay WINS the name, so a member it does not declare ceases to exist
     * and that call would resolve nowhere -- the trap this project has paid for eleven times, and the one
     * the deletion of the TimeUnit overlay would otherwise have armed.
     *
     * <p>THE DIVISION FLOORS RATHER THAN TRUNCATING, and that is the whole of the care here. Stock's
     * {@code getNano()} is the nano-of-second and is ALWAYS in 0..999,999,999, so a negative duration
     * borrows from the seconds: {@code ofNanos(-1)} is {@code getSeconds() == -1} with
     * {@code getNano() == 999999999}, NOT {@code 0} and {@code -1}. Java's {@code /} truncates toward zero,
     * so the plain expression is wrong for exactly the negative half -- the same shape as the saturation
     * defect that retired the TimeUnit overlay, right on every value anyone tries casually.
     *
     * <p>STATED LIMIT, because it is this overlay's and not TimeUnit's: a Duration here is ONE {@code long}
     * of total nanoseconds, where stock carries seconds and nanos separately. So the pair is EXACT over
     * everything this class can represent (|d| &lt; 2^63 ns, ~292 years) and this class simply cannot hold a
     * duration beyond that -- {@code ofSeconds(Long.MAX_VALUE)} overflows on construction, before these
     * accessors are reached.
     */
    public long getSeconds()
    {
        long s = nanos / 1000000000L;
        if (nanos < 0 && s * 1000000000L != nanos)
        {
            s -= 1;
        }
        return s;
    }

    public int getNano()
    {
        return (int) (nanos - getSeconds() * 1000000000L);
    }
}
