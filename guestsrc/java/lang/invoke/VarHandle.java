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

    // ---- the REFERENCE accessors, which java.util.concurrent uses constantly -----------------------------
    //
    // ConcurrentLinkedQueue drives its whole structure through a VarHandle: `ITEM.set(this, item)` in
    // Node.<init>, NEXT.set, HEAD/TAIL.compareAndSet, weakCompareAndSet and setRelease. Without them the
    // call resolves NOWHERE and surfaces as a DENYLIST TRAP with an EMPTY callee and `TRAPWIRE index=-1`,
    // blaming a denylist ConcurrentLinkedQueue is not on -- which is how the console launcher stopped, in
    // Node.<init> at ConcurrentLinkedQueue.java:193.
    //
    // THESE ARE REFERENCE-TYPED, AND THAT IS A REAL LIMIT, stated rather than hidden. The call sites are
    // signature-polymorphic and `Loader.vtableSlotOf` resolves them BY NAME ALONE, so ONE `set` serves every
    // `set` call site whatever its descriptor: a VarHandle over an INT field would arrive here with an int in
    // the argument register and be stored as if it were a reference. Nothing reached so far does that (the
    // int case in this VM goes through getAndBitwiseOr, which is typed), but a primitive-typed set/get is the
    // thing to fix -- by resolving on the descriptor -- before trusting this more widely.

    /** Plain reference store. */
    public void set(Object obj, Object x)
    {
        Magic.store64(Magic.addrOf(obj) + fieldOffset0(fname, obj), (x == null) ? 0L : Magic.addrOf(x));
    }

    /** Plain reference read. */
    public Object get(Object obj)
    {
        return Magic.fromAddr(Magic.load64(Magic.addrOf(obj) + fieldOffset0(fname, obj)));
    }

    /**
     * Release store: the write must not be reordered before earlier ones. A FULL barrier is used rather than
     * a one-way release -- stronger than required is always correct, and this VM has no release-store
     * intrinsic, so a one-way form here could only be wrong invisibly.
     */
    public void setRelease(Object obj, Object x)
    {
        fence0();
        set(obj, x);
    }

    /**
     * VM native -> {@code VMNatives.unsafeFence}: one full {@code dsb}.
     *
     * <p>A NATIVE rather than {@code Magic.dsb()}, which the metal JIT does not lower from guest code
     * ({@code JIT unsupported: reason=5} -- an unsupported intrinsic id). It reuses the address the Unsafe
     * fences already resolve to, so this adds a dispatch entry and no new helper. Full rather than one-way:
     * stronger than required is always correct, and this VM has no release-store intrinsic, so a one-way form
     * could only be wrong invisibly.
     */
    private static native void fence0();

    /**
     * As {@link #compareAndSet}. Stock allows a weak CAS to fail SPURIOUSLY, so callers always retry in a
     * loop; answering with the strong form is within that contract and simply never fails spuriously.
     */
    public boolean weakCompareAndSet(Object obj, Object expected, Object x)
    {
        return compareAndSet(obj, expected, x);
    }
}
