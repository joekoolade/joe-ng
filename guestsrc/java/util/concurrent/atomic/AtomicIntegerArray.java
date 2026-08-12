package java.util.concurrent.atomic;

/** JDK-free {@code AtomicIntegerArray} -- an int[] with plain element access (single core; see AtomicBoolean). */
public class AtomicIntegerArray
{
    private final int[] array;

    public AtomicIntegerArray(int length)
    {
        array = new int[length];
    }

    public final int length()
    {
        return array.length;
    }

    public final int get(int i)
    {
        return array[i];
    }

    public final void set(int i, int newValue)
    {
        array[i] = newValue;
    }

    public final void lazySet(int i, int newValue)
    {
        array[i] = newValue;
    }

    public final int getAndSet(int i, int newValue)
    {
        int old = array[i];
        array[i] = newValue;
        return old;
    }

    public final boolean compareAndSet(int i, int expect, int update)
    {
        if (array[i] == expect)
        {
            array[i] = update;
            return true;
        }
        return false;
    }
}
