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
