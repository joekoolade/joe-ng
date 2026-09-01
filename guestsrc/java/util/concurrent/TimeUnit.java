package java.util.concurrent;

/**
 * A JDK-free, minimal {@code java.util.concurrent.TimeUnit}: enough of the unit constants + {@link #convert}
 * for the JoinWithDuration test's {@code millisTime()} ({@code MILLISECONDS.convert(nanos, NANOSECONDS)}).
 * A plain class (not the stock enum) -- the test uses only the constants and convert(), never enum features.
 */
public class TimeUnit
{
    private final long nanosPerUnit;
    private final String name;
    private final int ordinal;

    private TimeUnit(long nanosPerUnit, String name, int ordinal)
    {
        this.nanosPerUnit = nanosPerUnit;
        this.name = name;
        this.ordinal = ordinal;
    }

    public static final TimeUnit NANOSECONDS = new TimeUnit(1L, "NANOSECONDS", 0);
    public static final TimeUnit MICROSECONDS = new TimeUnit(1000L, "MICROSECONDS", 1);
    public static final TimeUnit MILLISECONDS = new TimeUnit(1000000L, "MILLISECONDS", 2);
    public static final TimeUnit SECONDS = new TimeUnit(1000000000L, "SECONDS", 3);
    public static final TimeUnit MINUTES = new TimeUnit(60L * 1000000000L, "MINUTES", 4);
    public static final TimeUnit HOURS = new TimeUnit(3600L * 1000000000L, "HOURS", 5);
    public static final TimeUnit DAYS = new TimeUnit(86400L * 1000000000L, "DAYS", 6);

    /**
     * {@code name()}/{@code ordinal()}/{@code values()} -- the enum surface, hand-written because this overlay
     * is a plain CLASS, not an enum: joe-ng has no enum machinery here, and the stock TimeUnit's own
     * {@code <clinit>} is unrunnable on metal.
     *
     * <p>They are declared because stock code calls them on an ordinary path -- JUnit's timeout support does
     * {@code unit.name()} to build its message -- and a name-winning overlay silently drops what it does not
     * declare, turning that into a DENYLIST TRAP. Ordinals follow the stock declaration order, so
     * {@code compareTo}-style use and {@code values()[i]} agree with the JDK.
     */
    public String name()
    {
        return name;
    }

    public String toString()
    {
        return name;
    }

    public int ordinal()
    {
        return ordinal;
    }

    public static TimeUnit[] values()
    {
        return new TimeUnit[] { NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS };
    }

    public static TimeUnit valueOf(String n)
    {
        TimeUnit[] all = values();
        for (int i = 0; i < all.length; i++)
        {
            if (all[i].name.equals(n))
            {
                return all[i];
            }
        }
        throw new IllegalArgumentException("No enum constant java.util.concurrent.TimeUnit." + n);
    }

    /** The remaining stock conversions, all expressed through {@link #convert}. */
    public long toNanos(long d)
    {
        return NANOSECONDS.convert(d, this);
    }

    public long toMicros(long d)
    {
        return MICROSECONDS.convert(d, this);
    }

    public long toSeconds(long d)
    {
        return SECONDS.convert(d, this);
    }

    public long toMinutes(long d)
    {
        return MINUTES.convert(d, this);
    }

    public long toHours(long d)
    {
        return HOURS.convert(d, this);
    }

    public long toDays(long d)
    {
        return DAYS.convert(d, this);
    }

    /** Convert {@code sourceDuration} (in {@code sourceUnit}) to THIS unit. */
    public long convert(long sourceDuration, TimeUnit sourceUnit)
    {
        return sourceDuration * sourceUnit.nanosPerUnit / this.nanosPerUnit;
    }

    /** Convert {@code duration} (in THIS unit) to milliseconds. */
    public long toMillis(long duration)
    {
        return MILLISECONDS.convert(duration, this);
    }
}
