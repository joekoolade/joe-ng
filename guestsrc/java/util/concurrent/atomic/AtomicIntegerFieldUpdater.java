package java.util.concurrent.atomic;

import magic.Magic;

/**
 * JDK-free {@code AtomicIntegerFieldUpdater}. Instead of the stock Unsafe field-offset reflection, it carries
 * the field NAME and resolves its byte offset from the TARGET object's class at call time -- the exact
 * mechanism the {@link java.lang.invoke.VarHandle} overlay uses ({@link #fieldOffset0} -> {@code
 * VM.vhFieldOffset}). Single core, so the access is a plain load/store. {@code newUpdater} takes a raw
 * {@code Class} (joe-ng's {@code Class} overlay is non-generic) but still infers the updater's type parameter.
 */
public class AtomicIntegerFieldUpdater<T>
{
    private final byte[] fname;

    protected AtomicIntegerFieldUpdater(String fieldName)
    {
        this.fname = fieldName.getBytes();
    }

    public static <U> AtomicIntegerFieldUpdater<U> newUpdater(Class tclass, String fieldName)
    {
        long callerPc = Magic.readLR();                    // FIRST: return address into the caller (getCallerClass)
        FieldUpdaterCheck.validate(tclass, fieldName, callerPc, 'I');
        return new AtomicIntegerFieldUpdater<U>(fieldName);
    }

    /** Byte offset of {@code fname} within {@code obj}'s class -> {@code VM.vhFieldOffset} (loader field registry). */
    private static native long fieldOffset0(byte[] fname, Object obj);

    public void set(T obj, int newValue)
    {
        Magic.store32(Magic.addrOf(obj) + fieldOffset0(fname, obj), newValue);
    }

    public void lazySet(T obj, int newValue)
    {
        set(obj, newValue);
    }

    public int get(T obj)
    {
        return Magic.load32(Magic.addrOf(obj) + fieldOffset0(fname, obj));
    }

    public boolean compareAndSet(T obj, int expect, int update)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        if (Magic.load32(a) == expect)
        {
            Magic.store32(a, update);
            return true;
        }
        return false;
    }
}
