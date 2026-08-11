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
