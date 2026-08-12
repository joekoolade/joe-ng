package java.util.concurrent.atomic;

/** JDK-free {@code AtomicLong} -- plain field access on joe-ng's single core (see {@link AtomicBoolean}). */
public class AtomicLong extends Number
{
    private volatile long value;

    public AtomicLong()
    {
    }

    public AtomicLong(long initialValue)
    {
        value = initialValue;
    }

    public final long get()
    {
        return value;
    }

    public final void set(long newValue)
    {
        value = newValue;
    }

    public final void lazySet(long newValue)
    {
        value = newValue;
    }

    public final long getAndSet(long newValue)
    {
        long old = value;
        value = newValue;
        return old;
    }

    public final boolean compareAndSet(long expect, long update)
    {
        if (value == expect)
        {
            value = update;
            return true;
        }
        return false;
    }

    public final long getAndIncrement()
    {
        return value++;
    }

    public final long incrementAndGet()
    {
        return ++value;
    }

    public final long getAndAdd(long delta)
    {
        long old = value;
        value += delta;
        return old;
    }

    public final long addAndGet(long delta)
    {
        value += delta;
        return value;
    }

    public int intValue()
    {
        return (int) value;
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
        return Long.toString(value);
    }
}
