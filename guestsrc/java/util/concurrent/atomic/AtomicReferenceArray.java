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

    /**
     * The VarHandle MEMORY-MODE accessors (opaque / acquire-release / volatile / weak CAS).
     *
     * <p>Present because a name-winning overlay silently drops what it does not declare, and the call then
     * walks the super chain to {@code java/lang/Object}, fails, and surfaces as a DENYLIST TRAP -- which is
     * how JUnit's console launcher died on {@code getOpaque(I)}. This is the same trap already paid for
     * StringBuilder/Appendable, Class.getPrimitiveClass, the wrappers' TYPE, Throwable.initCause,
     * Character.toString, Boolean.getBoolean and the Collections family, so the whole family is declared here
     * rather than one method per failing boot.
     *
     * <p>Every mode maps to the plain access, which is CORRECT rather than approximate: opaque, acquire,
     * release and volatile all differ from plain only in the ORDERING they impose between accesses, and each
     * is weaker than or equal to the sequential consistency a single element access already has here. Element
     * reads and writes are single aligned words, so atomicity holds regardless. The weak CAS forms are allowed
     * to fail spuriously and simply never do.
     */
    @SuppressWarnings("unchecked")
    public final E getOpaque(int i)
    {
        return (E) array[i];
    }

    public final void setOpaque(int i, E newValue)
    {
        array[i] = newValue;
    }

    @SuppressWarnings("unchecked")
    public final E getAcquire(int i)
    {
        return (E) array[i];
    }

    public final void setRelease(int i, E newValue)
    {
        array[i] = newValue;
    }

    @SuppressWarnings("unchecked")
    public final E getPlain(int i)
    {
        return (E) array[i];
    }

    public final void setPlain(int i, E newValue)
    {
        array[i] = newValue;
    }

    public final boolean weakCompareAndSetPlain(int i, E expect, E update)
    {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetVolatile(int i, E expect, E update)
    {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetAcquire(int i, E expect, E update)
    {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetRelease(int i, E expect, E update)
    {
        return compareAndSet(i, expect, update);
    }

    @SuppressWarnings("unchecked")
    public final E compareAndExchange(int i, E expect, E update)
    {
        E witness = (E) array[i];
        if (witness == expect)
        {
            array[i] = update;
        }
        return witness;
    }

    public final E compareAndExchangeAcquire(int i, E expect, E update)
    {
        return compareAndExchange(i, expect, update);
    }

    public final E compareAndExchangeRelease(int i, E expect, E update)
    {
        return compareAndExchange(i, expect, update);
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; i < array.length; i++)
        {
            if (i > 0)
            {
                b.append(", ");
            }
            b.append(array[i]);
        }
        b.append(']');
        return b.toString();
    }
}
