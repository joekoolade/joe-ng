package java.util.concurrent.atomic;

/** JDK-free {@code AtomicReferenceArray} -- an Object[] with plain element access (single core; see AtomicBoolean). */
public class AtomicReferenceArray<E>
{
    private final Object[] array;

    public AtomicReferenceArray(int length)
    {
        array = new Object[length];
    }

    public final int length()
    {
        return array.length;
    }

    @SuppressWarnings("unchecked")
    public final E get(int i)
    {
        return (E) array[i];
    }

    public final void set(int i, E newValue)
    {
        array[i] = newValue;
    }

    public final void lazySet(int i, E newValue)
    {
        array[i] = newValue;
    }

    @SuppressWarnings("unchecked")
    public final E getAndSet(int i, E newValue)
    {
        E old = (E) array[i];
        array[i] = newValue;
        return old;
    }

    public final boolean compareAndSet(int i, E expect, E update)
    {
        if (array[i] == expect)
        {
            array[i] = update;
            return true;
        }
        return false;
    }
}
