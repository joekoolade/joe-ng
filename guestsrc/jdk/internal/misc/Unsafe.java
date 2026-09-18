package jdk.internal.misc;

import magic.Magic;

/**
 * Bare-metal reimplementation of the {@code jdk.internal.misc.Unsafe} intrinsics java.base leans on for
 * off-header memory access. Stock {@code Unsafe} is all JNI/JVM-intrinsic natives; on metal the semantics
 * are trivial because a reference IS the object's raw heap address (no handles, no compressed oops) and our
 * array payload begins at a fixed offset. So {@code Unsafe.getX(obj, offset)} is just
 * {@code Magic.loadX(Magic.addrOf(obj) + offset)}.
 *
 * <p>The {@code ARRAY_*_BASE_OFFSET}/{@code ARRAY_*_INDEX_SCALE} constants mirror {@code ObjectModel}: every
 * array's element payload starts at byte {@code 24} (2-word header + length word), and the scale is the
 * element width. They are assigned in {@code <clinit>} (not as constant initialisers) so a real
 * {@code putstatic} lands — cross-class {@code getstatic} from {@code jdk.internal.util.ArraysSupport} reads
 * them at run time; a {@code ConstantValue} attribute would leave the slots zero.
 *
 * <p>Only the subset java.base actually reaches on metal is provided; a call to any other native
 * {@code Unsafe} method is named by {@code jitFail} over the UART and added here on demand.
 */
public final class Unsafe
{
    private static final Unsafe theUnsafe;

    // Element payload starts at ObjectModel.ARRAY_BASE_OFFSET (24); scale is the element width.
    public static final long ARRAY_BOOLEAN_BASE_OFFSET;
    public static final long ARRAY_BYTE_BASE_OFFSET;
    public static final long ARRAY_CHAR_BASE_OFFSET;
    public static final long ARRAY_SHORT_BASE_OFFSET;
    public static final long ARRAY_INT_BASE_OFFSET;
    public static final long ARRAY_LONG_BASE_OFFSET;
    public static final long ARRAY_FLOAT_BASE_OFFSET;
    public static final long ARRAY_DOUBLE_BASE_OFFSET;

    public static final int ARRAY_BOOLEAN_INDEX_SCALE;
    public static final int ARRAY_BYTE_INDEX_SCALE;
    public static final int ARRAY_CHAR_INDEX_SCALE;
    public static final int ARRAY_SHORT_INDEX_SCALE;
    public static final int ARRAY_INT_INDEX_SCALE;
    public static final int ARRAY_LONG_INDEX_SCALE;
    public static final int ARRAY_FLOAT_INDEX_SCALE;
    public static final int ARRAY_DOUBLE_INDEX_SCALE;

    static
    {
        ARRAY_BOOLEAN_BASE_OFFSET = 24L;
        ARRAY_BYTE_BASE_OFFSET = 24L;
        ARRAY_CHAR_BASE_OFFSET = 24L;
        ARRAY_SHORT_BASE_OFFSET = 24L;
        ARRAY_INT_BASE_OFFSET = 24L;
        ARRAY_LONG_BASE_OFFSET = 24L;
        ARRAY_FLOAT_BASE_OFFSET = 24L;
        ARRAY_DOUBLE_BASE_OFFSET = 24L;

        ARRAY_BOOLEAN_INDEX_SCALE = 1;
        ARRAY_BYTE_INDEX_SCALE = 1;
        ARRAY_CHAR_INDEX_SCALE = 2;
        ARRAY_SHORT_INDEX_SCALE = 2;
        ARRAY_INT_INDEX_SCALE = 4;
        ARRAY_LONG_INDEX_SCALE = 8;
        ARRAY_FLOAT_INDEX_SCALE = 4;
        ARRAY_DOUBLE_INDEX_SCALE = 8;

        theUnsafe = new Unsafe();
    }

    private Unsafe()
    {
    }

    public static Unsafe getUnsafe()
    {
        return theUnsafe;
    }

    /** AArch64 is little-endian. */
    /**
     * Byte offset of an instance field, resolved from the CLASS rather than from a live object.
     *
     * <p>Reached first from {@code java/io/File.<clinit>}, and behind that by the ForkJoinPool /
     * CompletableFuture family, which uses Unsafe offsets rather than VarHandles by deliberate design (see
     * ForkJoinPool's own comment: "to avoid initialization dependencies"). The VM resolves it out of the same
     * instance-field registry every ordinary {@code getfield} uses, at the same {@code 16 + slot*8} layout, so
     * an offset obtained here and a normal field access address the same memory by construction.
     */
    /**
     * The {@code Field}-taking overload, which is a DIFFERENT method from the {@code (Class,String)} one
     * below and resolves separately -- an overlay declaring only one of them drops the other entirely.
     *
     * <p>Reached from {@code java/io/ObjectStreamClass$FieldReflector.<init>}, i.e. from describing the
     * serializable fields of any class; ForkJoinPool uses the {@code (Class,String)} form instead, so a
     * survey scoped to ForkJoinPool does not predict this one.
     */
    public long objectFieldOffset(java.lang.reflect.Field f)
    {
        return objectFieldOffset(f.getDeclaringClass(), f.getName());
    }

    public long objectFieldOffset(Class<?> c, String name)
    {
        return fieldOffsetOfClass0(c, name.getBytes());
    }

    private static native long fieldOffsetOfClass0(Class<?> c, byte[] name);

    public boolean isBigEndian()
    {
        return false;
    }

    public byte getByte(Object o, long offset)
    {
        return (byte) Magic.load8(Magic.addrOf(o) + offset);
    }

    public int getInt(Object o, long offset)
    {
        return Magic.load32(Magic.addrOf(o) + offset);
    }

    public long getLong(Object o, long offset)
    {
        return Magic.load64(Magic.addrOf(o) + offset);
    }

    // Normal RAM is Normal-cacheable memory, so unaligned LDR/LDRW are permitted; no split needed.
    public int getIntUnaligned(Object o, long offset)
    {
        return Magic.load32(Magic.addrOf(o) + offset);
    }

    public long getLongUnaligned(Object o, long offset)
    {
        return Magic.load64(Magic.addrOf(o) + offset);
    }

    // ------------------------------------------------------------------------------------------------
    // Atomics. Every one rests on Magic.cas64 (LDAXR/STLXR + CLREX), and through them ForkJoinPool and
    // CompletableFuture, which drive their whole structure through Unsafe rather than VarHandles by
    // deliberate design ("to avoid initialization dependencies", ForkJoinPool's own comment).
    //
    // WIDTH, and it is the one thing that could be silently wrong here: an INSTANCE FIELD in joe-ng occupies
    // a full 8-byte slot (16 + slot*8) whatever its declared type, and an int is stored SIGN-EXTENDED into
    // it, so comparing and swapping the whole slot with cas64 is exact for int fields as well as long and
    // reference ones. That is NOT true of an int[] ELEMENT, which is 4 bytes wide (ARRAY_INT_INDEX_SCALE);
    // a cas64 there would span two elements. No reached caller does that -- ForkJoinPool CASes int/long
    // FIELDS and Object[] ELEMENTS (scale 8) -- and the day one does, it needs a real 32-bit CAS rather than
    // this method quietly corrupting its neighbour.
    // ------------------------------------------------------------------------------------------------

    /** Address of the field/element at {@code offset} in {@code o}; a null {@code o} makes offset absolute
     *  (that is how staticFieldBase/staticFieldOffset pair up). */
    private static long at(Object o, long offset)
    {
        return o == null ? offset : Magic.addrOf(o) + offset;
    }

    public boolean compareAndSetLong(Object o, long offset, long expected, long x)
    {
        return Magic.cas64(at(o, offset), expected, x);
    }

    public boolean compareAndSetInt(Object o, long offset, int expected, int x)
    {
        return Magic.cas64(at(o, offset), expected, x);   // sign-extended in an 8-byte slot; see above
    }

    public boolean compareAndSetReference(Object o, long offset, Object expected, Object x)
    {
        return Magic.cas64(at(o, offset), Magic.addrOf(expected), Magic.addrOf(x));
    }

    /** Stock's weak forms may fail SPURIOUSLY and callers always retry; an LL/SC CAS may too, so these are
     *  the same method rather than a stronger one pretending to be weaker. */
    public boolean weakCompareAndSetReference(Object o, long offset, Object expected, Object x)
    {
        return compareAndSetReference(o, offset, expected, x);
    }

    public boolean weakCompareAndSetInt(Object o, long offset, int expected, int x)
    {
        return compareAndSetInt(o, offset, expected, x);
    }

    public boolean weakCompareAndSetLong(Object o, long offset, long expected, long x)
    {
        return compareAndSetLong(o, offset, expected, x);
    }

    /** Compare-and-EXCHANGE: answers the WITNESSED value, not a boolean. Re-read on failure rather than
     *  returning `expected`, which would tell the caller its CAS failed against the value it already had. */
    public long compareAndExchangeLong(Object o, long offset, long expected, long x)
    {
        long a = at(o, offset);
        return Magic.cas64(a, expected, x) ? expected : Magic.load64(a);
    }

    public int compareAndExchangeInt(Object o, long offset, int expected, int x)
    {
        long a = at(o, offset);
        return Magic.cas64(a, expected, x) ? expected : (int) Magic.load64(a);
    }

    public Object compareAndExchangeReference(Object o, long offset, Object expected, Object x)
    {
        long a = at(o, offset);
        if (Magic.cas64(a, Magic.addrOf(expected), Magic.addrOf(x)))
        {
            return expected;
        }
        return Magic.fromAddr(Magic.load64(a));
    }

    // The read-modify-write family. Each LOOPS, which is required rather than defensive: an LL/SC CAS may
    // fail spuriously (an interrupt between the load and the store clears the monitor), so a single attempt
    // would drop the update on hardware in a way it never does under emulation.
    //
    // THE INT FORMS OPERATE ON AN 8-BYTE SLOT, AND THAT IS A PRECONDITION ON THE CALLER, not a detail.
    // `Magic` exposes cas64 and nothing narrower, so every int variant here reads and writes the FULL WORD
    // at `offset`. That is exact for an INSTANCE FIELD -- ObjectModel gives each one its own 8-byte slot and
    // the compiler keeps an int sign-extended in it (Baseline.canonInt) -- and it is WRONG for an int ARRAY
    // ELEMENT, whose scale is 4 (see ARRAY_INT_INDEX_SCALE): a 64-bit access there would take the
    // neighbouring element with it, at an address the exclusive monitor is not even aligned for. Nothing
    // reached does that -- AtomicIntegerArray deliberately uses plain int[] access rather than Unsafe, and
    // the callers of these are field-offset callers (ForkJoinTask.status, AtomicInteger.value). Stated
    // because the existing methods below have depended on it silently since they were written, and a
    // narrow-slot caller would not fail, it would corrupt its neighbour.
    public long getAndAddLong(Object o, long offset, long delta)
    {
        long a = at(o, offset);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v + delta));
        return v;
    }

    public int getAndAddInt(Object o, long offset, int delta)
    {
        long a = at(o, offset);
        int v;
        do
        {
            v = (int) Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v + delta));
        return v;
    }

    public long getAndSetLong(Object o, long offset, long x)
    {
        long a = at(o, offset);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, x));
        return v;
    }

    public int getAndSetInt(Object o, long offset, int x)
    {
        long a = at(o, offset);
        int v;
        do
        {
            v = (int) Magic.load64(a);
        }
        while (!Magic.cas64(a, v, x));
        return v;
    }

    public Object getAndSetReference(Object o, long offset, Object x)
    {
        long a = at(o, offset);
        long nx = Magic.addrOf(x);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, nx));
        return Magic.fromAddr(v);
    }

    // ------------------------------------------------------------------------------------------------
    // getAndBitwise{Or,And,Xor}{Int,Long}, each with its Acquire and Release variants.
    //
    // ADDED AS A FAMILY RATHER THAN ONE METHOD, deliberately. `getAndBitwiseOrLong` shipped alone and its
    // sibling `getAndBitwiseOrInt` then CEASED TO EXIST for every caller -- an overlay wins the name, so a
    // member it omits resolves nowhere and surfaces as a DENYLIST TRAP blaming a list this class is not on.
    // That trap has cost this project eleven debugging sessions, and `make overlaycheck-deep` lists all
    // eighteen of these as referenced by stock java.base. Adding the one that traps is how the family costs
    // a boot each time.
    //
    // THE FAMILY WAS BLOCKED FOR AN ARC BY SOMETHING THAT HAS NOTHING TO DO WITH THESE METHODS, and that is
    // worth recording here because it is where the next reader will look. Adding all eighteen aborted the
    // demo suite in `demo/DefaultIfaceDemo`, bisected to +1 pass / +18 fail. These members were only ever
    // the PERTURBATION that moved batch composition; the defect was in the LOADER.
    //
    // A METADATA-ONLY CLASS'S `<init>` HAD NEITHER A DEFERRAL STUB NOR A CELL -- it fell between both
    // dispatch tables, so the call site bound to nothing and the object came back with every field 0. The
    // FIX IS THE STUB, on the route every other method kind already takes (the method's own registered
    // buffer, this VM's `Method::_from_compiled_entry`), delivered by making `<init>` defer like everything
    // else. An earlier attempt gave constructors a phase-A CELL instead and had to be reverted: the cell
    // tier is consulted BEFORE the registry, so it did not fill a gap behind it, it SHADOWED it, and every
    // constructor stopped being sized, emitted or registered at all. That regressed the launcher to an
    // `arg[3]` NPE on hardware. Two mechanisms for one binding is the thing to avoid here, not a third.
    //
    // ACQUIRE AND RELEASE SHARE THE PLAIN BODY, and that is correct by being STRONGER rather than by being
    // equal: `Magic.cas64` is LDAXR/STLXR, which is already acquire-on-load and release-on-store, so the
    // plain form carries at least the ordering either variant asks for. joe-ng has no one-way barrier
    // intrinsic to emit a weaker form with, and a weaker form we cannot emit could only be wrong invisibly
    // -- the same reasoning the memory-mode accessors below and VarHandle.setRelease already record.
    // ------------------------------------------------------------------------------------------------

    public int getAndBitwiseOrInt(Object o, long offset, int mask)
    {
        long a = at(o, offset);
        int v;
        do
        {
            v = (int) Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v | mask));
        return v;
    }

    public int getAndBitwiseOrIntAcquire(Object o, long offset, int mask)
    {
        return getAndBitwiseOrInt(o, offset, mask);
    }

    public int getAndBitwiseOrIntRelease(Object o, long offset, int mask)
    {
        return getAndBitwiseOrInt(o, offset, mask);
    }

    public int getAndBitwiseAndInt(Object o, long offset, int mask)
    {
        long a = at(o, offset);
        int v;
        do
        {
            v = (int) Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v & mask));
        return v;
    }

    public int getAndBitwiseAndIntAcquire(Object o, long offset, int mask)
    {
        return getAndBitwiseAndInt(o, offset, mask);
    }

    public int getAndBitwiseAndIntRelease(Object o, long offset, int mask)
    {
        return getAndBitwiseAndInt(o, offset, mask);
    }

    public int getAndBitwiseXorInt(Object o, long offset, int mask)
    {
        long a = at(o, offset);
        int v;
        do
        {
            v = (int) Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v ^ mask));
        return v;
    }

    public int getAndBitwiseXorIntAcquire(Object o, long offset, int mask)
    {
        return getAndBitwiseXorInt(o, offset, mask);
    }

    public int getAndBitwiseXorIntRelease(Object o, long offset, int mask)
    {
        return getAndBitwiseXorInt(o, offset, mask);
    }

    public long getAndBitwiseOrLong(Object o, long offset, long mask)
    {
        long a = at(o, offset);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v | mask));
        return v;
    }

    public long getAndBitwiseOrLongAcquire(Object o, long offset, long mask)
    {
        return getAndBitwiseOrLong(o, offset, mask);
    }

    public long getAndBitwiseOrLongRelease(Object o, long offset, long mask)
    {
        return getAndBitwiseOrLong(o, offset, mask);
    }

    public long getAndBitwiseAndLong(Object o, long offset, long mask)
    {
        long a = at(o, offset);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v & mask));
        return v;
    }

    public long getAndBitwiseAndLongAcquire(Object o, long offset, long mask)
    {
        return getAndBitwiseAndLong(o, offset, mask);
    }

    public long getAndBitwiseAndLongRelease(Object o, long offset, long mask)
    {
        return getAndBitwiseAndLong(o, offset, mask);
    }

    public long getAndBitwiseXorLong(Object o, long offset, long mask)
    {
        long a = at(o, offset);
        long v;
        do
        {
            v = Magic.load64(a);
        }
        while (!Magic.cas64(a, v, v ^ mask));
        return v;
    }

    public long getAndBitwiseXorLongAcquire(Object o, long offset, long mask)
    {
        return getAndBitwiseXorLong(o, offset, mask);
    }

    public long getAndBitwiseXorLongRelease(Object o, long offset, long mask)
    {
        return getAndBitwiseXorLong(o, offset, mask);
    }

    // ------------------------------------------------------------------------------------------------
    // Plain and memory-mode accessors. joe-ng has no one-way barrier intrinsic, so every ordered mode takes
    // the SAME full fence: stronger than required is always correct, and a one-way form we cannot emit could
    // only be wrong invisibly. Plain forms take none. (Same reasoning as VarHandle.setRelease and the
    // Unsafe fences already wired.)
    // ------------------------------------------------------------------------------------------------

    public Object getReference(Object o, long offset)
    {
        return Magic.fromAddr(Magic.load64(at(o, offset)));
    }

    public void putReference(Object o, long offset, Object x)
    {
        Magic.store64(at(o, offset), Magic.addrOf(x));
    }

    public Object getReferenceAcquire(Object o, long offset)
    {
        Object v = getReference(o, offset);
        fence0();
        return v;
    }

    public Object getReferenceVolatile(Object o, long offset)
    {
        return getReferenceAcquire(o, offset);
    }

    public void putReferenceRelease(Object o, long offset, Object x)
    {
        fence0();
        putReference(o, offset, x);
    }

    public void putReferenceVolatile(Object o, long offset, Object x)
    {
        putReferenceRelease(o, offset, x);
    }

    public void putInt(Object o, long offset, int x)
    {
        Magic.store64(at(o, offset), x);
    }

    public int getIntAcquire(Object o, long offset)
    {
        int v = getInt(o, offset);
        fence0();
        return v;
    }

    public int getIntVolatile(Object o, long offset)
    {
        return getIntAcquire(o, offset);
    }

    /** OPAQUE asks only for atomicity and coherence, not ordering -- an aligned single access already gives
     *  both here, so no fence is needed and adding one would be a cost with no meaning. */
    public int getIntOpaque(Object o, long offset)
    {
        return getInt(o, offset);
    }

    public void putIntOpaque(Object o, long offset, int x)
    {
        putInt(o, offset, x);
    }

    public void putIntRelease(Object o, long offset, int x)
    {
        fence0();
        putInt(o, offset, x);
    }

    public void putIntVolatile(Object o, long offset, int x)
    {
        putIntRelease(o, offset, x);
    }

    public long getLongAcquire(Object o, long offset)
    {
        long v = getLong(o, offset);
        fence0();
        return v;
    }

    public long getLongVolatile(Object o, long offset)
    {
        return getLongAcquire(o, offset);
    }

    public void putLong(Object o, long offset, long x)
    {
        Magic.store64(at(o, offset), x);
    }

    public void putLongRelease(Object o, long offset, long x)
    {
        fence0();
        putLong(o, offset, x);
    }

    public void putLongVolatile(Object o, long offset, long x)
    {
        putLongRelease(o, offset, x);
    }

    public void storeFence()
    {
        fence0();
    }

    public void loadFence()
    {
        fence0();
    }

    public void fullFence()
    {
        fence0();
    }

    /** One full {@code dsb}; see VarHandle.fence0 for why every mode shares it. */
    private static native void fence0();

    // ------------------------------------------------------------------------------------------------
    // Layout queries.
    // ------------------------------------------------------------------------------------------------

    /** Element payload offset -- the same {@code 24} every array in this VM uses ({@code ObjectModel}), so it
     *  does not depend on the component type. */
    /** Returns LONG, while its sibling {@link #arrayIndexScale} returns int -- stock's own asymmetry
     *  (Unsafe.java:1212 vs 1271). They are different descriptors and resolve separately, so guessing
     *  symmetry here left `()J` unresolved at a call site that named it exactly. */
    public long arrayBaseOffset(Class<?> arrayClass)
    {
        return 24L;
    }

    public int arrayIndexScale(Class<?> arrayClass)
    {
        Class<?> c = arrayClass == null ? null : arrayClass.getComponentType();
        if (c == null || !c.isPrimitive())
        {
            return 8;                               // a reference element IS an 8-byte pointer here
        }
        String n = c.getName();
        if (n.equals("byte") || n.equals("boolean")) { return 1; }
        if (n.equals("char") || n.equals("short"))   { return 2; }
        if (n.equals("int") || n.equals("float"))    { return 4; }
        return 8;                                    // long, double
    }

    /**
     * A static field's "offset" and "base" -- stock splits an access into the two, and they are only ever
     * used TOGETHER, so any pair addressing the right word is correct. joe-ng keeps statics in a per-class
     * block at an absolute address, so the whole address goes in the OFFSET and the base is null: {@link #at}
     * then treats the offset as absolute, which is exactly the stock contract for a null base.
     */
    public long staticFieldOffset(java.lang.reflect.Field f)
    {
        return staticFieldAddr0(f.getDeclaringClass(), f.getName().getBytes());
    }

    public Object staticFieldBase(java.lang.reflect.Field f)
    {
        return null;
    }

    private static native long staticFieldAddr0(Class<?> c, byte[] name);

    public void park(boolean isAbsolute, long time)
    {
        java.util.concurrent.locks.LockSupport.park();
    }

    public void unpark(Object thread)
    {
        java.util.concurrent.locks.LockSupport.unpark((Thread) thread);
    }

    /**
     * Allocate a primitive array (stock uses a native intrinsic; on metal that native + its {@code
     * componentType.isPrimitive()}/{@code Byte.TYPE} checks are what threw). The only java.base path that
     * reaches this on metal is {@code StringConcatHelper.newArray} (String.replace and concat fallbacks),
     * which always allocates a {@code byte[]} -- JDK 9+ stores BOTH latin1 and utf16 Strings as {@code byte[]}.
     * So the {@code componentType} is byte here; returning {@code new byte[length]} (zeroed, i.e. defined
     * rather than truly uninitialised) is correct for every reachable caller. Add other element types on
     * demand if {@code jitFail}/an exception names one.
     */
    public Object allocateUninitializedArray(Class componentType, int length)
    {
        return new byte[length];
    }
}
