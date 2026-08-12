package java.util.concurrent.atomic;

import magic.Magic;

/**
 * JDK-free {@code AtomicReferenceFieldUpdater} -- like {@link AtomicIntegerFieldUpdater} for a reference field:
 * it stores the referent's address into the target's field slot. {@code get} is unsupported (there is no
 * long-&gt;reference intrinsic on metal; reading such a field is done directly, not through the updater).
 */
public class AtomicReferenceFieldUpdater<T, V>
{
    private final byte[] fname;

    protected AtomicReferenceFieldUpdater(String fieldName)
    {
        this.fname = fieldName.getBytes();
    }

    public static <U, W> AtomicReferenceFieldUpdater<U, W> newUpdater(Class tclass, Class vclass, String fieldName)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        FieldUpdaterCheck.validate(tclass, fieldName, callerPc, 'L');
        return new AtomicReferenceFieldUpdater<U, W>(fieldName);
    }

    private static native long fieldOffset0(byte[] fname, Object obj);

    public void set(T obj, V newValue)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        Magic.store64(a, (newValue == null) ? 0L : Magic.addrOf(newValue));
    }

    public void lazySet(T obj, V newValue)
    {
        set(obj, newValue);
    }

    public boolean compareAndSet(T obj, V expect, V update)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        long e = (expect == null) ? 0L : Magic.addrOf(expect);
        if (Magic.load64(a) == e)
        {
            Magic.store64(a, (update == null) ? 0L : Magic.addrOf(update));
            return true;
        }
        return false;
    }
}
