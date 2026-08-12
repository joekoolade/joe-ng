package java.util.concurrent.atomic;

import magic.Magic;

/** JDK-free {@code AtomicLongFieldUpdater} -- like {@link AtomicIntegerFieldUpdater} with 64-bit accesses. */
public class AtomicLongFieldUpdater<T>
{
    private final byte[] fname;

    protected AtomicLongFieldUpdater(String fieldName)
    {
        this.fname = fieldName.getBytes();
    }

    public static <U> AtomicLongFieldUpdater<U> newUpdater(Class tclass, String fieldName)
    {
        return new AtomicLongFieldUpdater<U>(fieldName);
    }

    private static native long fieldOffset0(byte[] fname, Object obj);

    public void set(T obj, long newValue)
    {
        Magic.store64(Magic.addrOf(obj) + fieldOffset0(fname, obj), newValue);
    }

    public void lazySet(T obj, long newValue)
    {
        set(obj, newValue);
    }

    public long get(T obj)
    {
        return Magic.load64(Magic.addrOf(obj) + fieldOffset0(fname, obj));
    }

    public boolean compareAndSet(T obj, long expect, long update)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        if (Magic.load64(a) == expect)
        {
            Magic.store64(a, update);
            return true;
        }
        return false;
    }
}
