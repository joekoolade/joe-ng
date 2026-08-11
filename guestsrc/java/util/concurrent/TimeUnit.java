package java.util.concurrent;

/**
 * A JDK-free, minimal {@code java.util.concurrent.TimeUnit}: enough of the unit constants + {@link #convert}
 * for the JoinWithDuration test's {@code millisTime()} ({@code MILLISECONDS.convert(nanos, NANOSECONDS)}).
 * A plain class (not the stock enum) -- the test uses only the constants and convert(), never enum features.
 */
public class TimeUnit
{
    private final long nanosPerUnit;

    private TimeUnit(long nanosPerUnit)
    {
        this.nanosPerUnit = nanosPerUnit;
    }

    public static final TimeUnit NANOSECONDS = new TimeUnit(1L);
    public static final TimeUnit MICROSECONDS = new TimeUnit(1000L);
    public static final TimeUnit MILLISECONDS = new TimeUnit(1000000L);
    public static final TimeUnit SECONDS = new TimeUnit(1000000000L);

    /** Convert {@code sourceDuration} (in {@code sourceUnit}) to THIS unit. */
    public long convert(long sourceDuration, TimeUnit sourceUnit)
    {
        return sourceDuration * sourceUnit.nanosPerUnit / this.nanosPerUnit;
    }
}
