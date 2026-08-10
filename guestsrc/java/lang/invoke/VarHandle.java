package java.lang.invoke;

import magic.Magic;

/**
 * Minimal name-winning {@code java.lang.invoke.VarHandle} overlay for joe-ng. Stock {@code VarHandle} is the
 * front of the whole MethodHandle/invoke runtime (intrinsified in HotSpot, denied on metal). A handful of
 * JDK classes -- notably {@code java.net.Socket} -- use a VarHandle purely as an atomic field accessor:
 * {@code STATE.getAndBitwiseOr(this, mask)} and {@code IN/OUT.compareAndSet(this, null, stream)}. On a
 * single-threaded metal core those are just plain field read/modify/writes.
 *
 * <p>This overlay carries the field NAME and resolves its byte offset from the TARGET object's class at call
 * time (via {@link #fieldOffset0}, backed by the loader's field registry), then does the access with
 * {@link Magic}. The signature-polymorphic call sites (e.g. {@code getAndBitwiseOr:(Ljava/net/Socket;I)I})
 * are resolved to these methods by name in {@code Loader.vtableSlotOf} (VarHandle's op names are unique).
 */
public final class VarHandle
{
    byte[] fname;   // the field this handle addresses (set by MhUtil.findVarHandle)

    /** Factory for the MhUtil overlay (which lives in a different package): bind a handle to a field name. */
    public static VarHandle ofField(byte[] fieldName)
    {
        VarHandle vh = new VarHandle();
        vh.fname = fieldName;
        return vh;
    }

    /** Byte offset of instance field {@code fname} within {@code obj}'s class -> {@code VM.vhFieldOffset}. */
    private static native long fieldOffset0(byte[] fname, Object obj);

    /** Atomic OR of {@code mask} into the int field; returns the previous value. */
    public int getAndBitwiseOr(Object obj, int mask)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        int old = Magic.load32(a);
        Magic.store32(a, old | mask);
        return old;
    }

    /** If the reference field == {@code expected}, set it to {@code x} and return true; else false. */
    public boolean compareAndSet(Object obj, Object expected, Object x)
    {
        long a = Magic.addrOf(obj) + fieldOffset0(fname, obj);
        long e = (expected == null) ? 0L : Magic.addrOf(expected);
        if (Magic.load64(a) == e)
        {
            Magic.store64(a, (x == null) ? 0L : Magic.addrOf(x));
            return true;
        }
        return false;
    }
}
