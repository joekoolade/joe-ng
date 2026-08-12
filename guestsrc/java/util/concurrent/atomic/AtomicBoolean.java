package java.util.concurrent.atomic;

/**
 * JDK-free {@code AtomicBoolean}. joe-ng runs on a single unparked core, so the stock Unsafe/VarHandle
 * memory-ordering machinery collapses to plain field access -- {@code lazySet}/{@code set}/{@code get} are
 * ordinary read/writes, and {@code compareAndSet} is a non-racy test-and-set.
 */
public class AtomicBoolean
{
    private volatile boolean value;

    public AtomicBoolean()
    {
    }

    public AtomicBoolean(boolean initialValue)
    {
        value = initialValue;
    }

    public final boolean get()
    {
        return value;
    }

    public final void set(boolean newValue)
    {
        value = newValue;
    }

    public final void lazySet(boolean newValue)
    {
        value = newValue;
    }

    public final boolean getAndSet(boolean newValue)
    {
        boolean old = value;
        value = newValue;
        return old;
    }

    public final boolean compareAndSet(boolean expect, boolean update)
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
        return value ? "true" : "false";
    }
}
