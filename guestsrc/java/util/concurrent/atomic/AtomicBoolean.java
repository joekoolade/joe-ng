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

    /**
     * The VarHandle MEMORY-MODE accessors, as on {@code AtomicReferenceArray}: every mode maps to the plain
     * access, which is correct rather than approximate -- plain/opaque/acquire/release/volatile differ only in
     * the ORDERING they impose between accesses, and a single aligned word already has the atomicity. Declared
     * because a name-winning overlay drops what it does not declare and the call then traps.
     */
    public final boolean getPlain()
    {
        return get();
    }

    public final void setPlain(boolean newValue)
    {
        set(newValue);
    }

    public final boolean getOpaque()
    {
        return get();
    }

    public final void setOpaque(boolean newValue)
    {
        set(newValue);
    }

    public final boolean getAcquire()
    {
        return get();
    }

    public final void setRelease(boolean newValue)
    {
        set(newValue);
    }

    public final boolean weakCompareAndSetPlain(boolean expect, boolean update)
    {
        return compareAndSet(expect, update);
    }

    public final boolean compareAndExchange(boolean expect, boolean update)
    {
        boolean witness = get();
        if (witness == expect)
        {
            set(update);
        }
        return witness;
    }

    public String toString()
    {
        return value ? "true" : "false";
    }
}
