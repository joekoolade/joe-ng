/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-29
 */
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

    /** The OBJECT-array pair. Stock declares nine base/scale pairs and this overlay declared eight: a
     *  reference element is an 8-byte pointer here (no compressed oops), and the payload still starts at 24.
     *  Referenced by {@code java/lang/runtime/Carriers$CarrierObject} and {@code java/util/LazyCollections}. */
    public static final long ARRAY_OBJECT_BASE_OFFSET;
    public static final int ARRAY_OBJECT_INDEX_SCALE;

    /** Reference width, stock's {@code theUnsafe.addressSize()}. Factual rather than a choice: joe-ng is
     *  AArch64 with direct 8-byte refs. Read by {@code java/util/zip/CRC32C} and the FFM layout classes. */
    public static final int ADDRESS_SIZE;

    /** Stock reads this from {@code UnsafeConstants}; AArch64 runs little-endian here, and
     *  {@link #isBigEndian} has always answered so. A compile-time constant, so javac folds every
     *  {@code BIG_ENDIAN ? ... : ...} in the byte-order helpers below to its little-endian arm. */
    private static final boolean BIG_ENDIAN = false;

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

        ARRAY_OBJECT_BASE_OFFSET = 24L;
        ARRAY_OBJECT_INDEX_SCALE = 8;
        ADDRESS_SIZE = 8;

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
        long off = fieldOffsetOfClass0(c, name.getBytes());
        if (off < 0L)
        {
            // Stock throws here (objectFieldOffset1 -> InternalError). Answering -1 was a SILENT wrong answer: the
            // caller's next getInt/putInt(o, -1) touched the object HEADER. ThreadLocalRandom did exactly that on
            // a Thread with no threadLocalRandomProbe field.
            throw new InternalError(name);
        }
        return off;
    }

    private static native long fieldOffsetOfClass0(Class<?> c, byte[] name);

    public boolean isBigEndian()
    {
        return false;
    }

    public byte getByte(Object o, long offset)
    {
        return (byte) Magic.load8(at(o, offset));
    }

    public int getInt(Object o, long offset)
    {
        return Magic.load32(at(o, offset));
    }

    public long getLong(Object o, long offset)
    {
        return Magic.load64(at(o, offset));
    }

    // Normal RAM is Normal-cacheable memory, so unaligned LDR/LDRW are permitted; no split needed.
    public int getIntUnaligned(Object o, long offset)
    {
        return Magic.load32(at(o, offset));
    }

    public long getLongUnaligned(Object o, long offset)
    {
        return Magic.load64(at(o, offset));
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

    /**
     * An INT-WIDTH compare-and-exchange that is correct for BOTH layouts, answering the WITNESSED value.
     *
     * <p>This exists for {@code arrayElementVarHandle}: a 4-byte ARRAY ELEMENT cannot be CASed with
     * {@code Magic.cas64}, which spans two of them. {@code casNarrow} already masks an element inside its
     * enclosing 8-byte word for widths 1 and 2 and is Pi-validated at every alignment in that window; width 4
     * is the same mechanism, and {@code casNarrow}'s javadoc carries the proof that it cannot straddle.
     *
     * <p>The callers below reach this ONLY for an array base, so the FIELD path keeps its original single
     * {@code cas64} byte for byte -- which matters because ForkJoinPool, CompletableFuture and AtomicInteger
     * drive their whole structure through it on every boot.
     */
    private int casInt(Object o, long offset, int expected, int x)
    {
        return (int) casNarrow(o, offset, expected, x, 4);
    }

    public boolean compareAndSetLong(Object o, long offset, long expected, long x)
    {
        return Magic.cas64(at(o, offset), expected, x);
    }

    public boolean compareAndSetInt(Object o, long offset, int expected, int x)
    {
        if (isArrayRef(o))
        {
            return casInt(o, offset, expected, x) == expected;   // 4-byte element; cas64 would span two
        }
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
        if (isArrayRef(o))
        {
            return casInt(o, offset, expected, x);           // casNarrow already answers the witnessed value
        }
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
        if (isArrayRef(o))
        {
            int e;
            do
            {
                e = getInt(o, offset);                   // the ELEMENT, not the enclosing word
            }
            while (casInt(o, offset, e, e + delta) != e);
            return e;
        }
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
        if (isArrayRef(o))
        {
            int e;
            do
            {
                e = getInt(o, offset);                   // the ELEMENT, not the enclosing word
            }
            while (casInt(o, offset, e, x) != e);
            return e;
        }
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
        if (isArrayRef(o))
        {
            int e;
            do
            {
                e = getInt(o, offset);                   // the ELEMENT, not the enclosing word
            }
            while (casInt(o, offset, e, e | mask) != e);
            return e;
        }
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
        if (isArrayRef(o))
        {
            int e;
            do
            {
                e = getInt(o, offset);                   // the ELEMENT, not the enclosing word
            }
            while (casInt(o, offset, e, e & mask) != e);
            return e;
        }
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
        if (isArrayRef(o))
        {
            int e;
            do
            {
                e = getInt(o, offset);                   // the ELEMENT, not the enclosing word
            }
            while (casInt(o, offset, e, e ^ mask) != e);
            return e;
        }
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

    /**
     * THE LAST 4-BYTE WRITE THAT SPANNED TWO ELEMENTS, and the one the accessor increment deliberately left:
     * its note read "making them ask the object would change a path ForkJoinPool and AtomicInteger run on
     * every boot, for a case nothing reaches, so the discriminator is available to a FOLLOW-UP". This is that
     * follow-up -- {@code arrayElementVarHandle} is what makes it reached.
     *
     * <p>FOUND BY A PROBE ARM THAT DUMPED THE NEIGHBOUR, not by reading. Plain {@code set} on an array-element
     * handle passed, because stock's generated body uses the {@code iastore} BYTECODE and never touches
     * {@code Unsafe} at all; only {@code setVolatile}/{@code setRelease}/{@code setOpaque} come through here,
     * and they delegate to this. A {@code store64} of a negative int writes its sign extension over the next
     * element: {@code setVolatile(a, 1, -4)} on {@code {10,20,30,40}} gave {@code 10,-4,-1,40}. An arm reading
     * back only the element it wrote would have passed.
     *
     * <p>Every other narrow put already asked the object -- {@code putByte}/{@code putShort}/{@code putChar}/
     * {@code putFloat} all route through {@code putBits}. This was the only one left, so the fix is to join
     * them rather than to invent anything.
     */
    public void putInt(Object o, long offset, int x)
    {
        if (isArrayRef(o))
        {
            putBits(o, offset, x, 4);          // a 4-byte element; store64 would span two of them
            return;
        }
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

    /**
     * CORRECT NOW, and this comment is kept because its PREMISE EXPIRED rather than because the code changed.
     * It used to read "KNOWN WRONG FOR EVERY PRIMITIVE ARRAY": {@code Class.getComponentType()} answered NULL
     * for a primitive array, so this fell through its {@code c == null} arm and returned 8 where
     * {@code byte[].class} should give 1. That {@code Class} defect was FIXED on 2026-09-30 by recovering the
     * element kind through the per-atype TIB IDENTITY trick -- exactly the fix this comment predicted -- and
     * {@code ComponentTypeProbe} measures all eight widths at 1/1/2/2/4/4/8/8 on silicon. A comment outliving
     * its premise is a trap this project records six times, which is why the correction is recorded here
     * rather than the text simply deleted: the next reader of {@code arrayElementVarHandle} needs to know this
     * method is load-bearing and sound, because that factory computes its shift from it.
     */
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

    // ================================================================================================
    // THE TWO LAYOUTS. Everything below this line exists because joe-ng addresses memory two different
    // ways behind ONE `(Object, long)` signature, and they disagree about WIDTH:
    //
    //   a FIELD          occupies a full 8-byte slot whatever its declared type (ObjectModel.fieldOffset
    //                    is 16 + slot*8), and the compiler reads/writes it with ldrx/strx -- so a `short`
    //                    field's slot holds the value across all 64 bits, sign-extended, and a 2-byte
    //                    store into its low half leaves a stale high half that `getfield` reads as a
    //                    DIFFERENT NUMBER.
    //   an ARRAY ELEMENT occupies its NATURAL width at 24 + index*scale -- so an 8-byte store there takes
    //                    its neighbours with it.
    //   a NULL base      is a raw absolute address (see `at`), i.e. natural width, like an element.
    //
    // STATED LIMITATION, because a null base has one MORE meaning than raw memory: the
    // staticFieldBase/staticFieldOffset PAIR answers {null, absolute address of a static's slot}, and a
    // static slot is 8 bytes like a field's. So a NARROW write through that pair leaves the slot's high half
    // stale, exactly as a natural-width write to an instance field would. The existing putInt/putLong keep
    // whole-slot semantics for a null base and are therefore right for it; these narrow forms are not. What
    // makes it unreachable rather than latent is measured: the only referrers of the narrow static path are
    // java/lang/invoke/VarHandleXxx$FieldStaticReadWrite, a denied package. Closing it needs the statics
    // region's own bounds (VM.staticsStart/End) as a third discriminator, which is machinery no measurement
    // asks for yet.
    //
    // A READ is unambiguous: the low `width` bytes of a field's slot ARE the value on a little-endian
    // machine, so byte-composition is exact for a field, an element and an absolute address alike.
    // A WRITE is not, and neither the offset nor its alignment can settle it -- offset 24 is both field
    // slot 1 and array element 0 -- so `isArrayRef` asks the OBJECT. That is the only discriminator there
    // is, and it is why this native exists.
    //
    // Consequence worth stating: a narrow read/write is composed from BYTE accesses, so it is not ATOMIC
    // the way a single ldrh/strh would be. Nothing in the *Volatile/*Acquire forms below can therefore
    // promise single-copy atomicity at 2-byte width; they promise ORDERING (a full barrier), which is what
    // their reached callers -- lazy holders and publish-once fields -- actually depend on.
    // ================================================================================================

    /** {@code 0} not an array, {@code 1} a PRIMITIVE array, {@code 2} a REFERENCE array -- read straight off
     *  the array Type's element slot, with no mirror involved. Two questions are answered from it: the WIDTH
     *  of a narrow access (any array means natural width) and stock's PRIMITIVE-array contract on the bulk
     *  moves. See {@code VMNatives.arrayKindOf}. */
    private static native long arrayKind0(Object o);

    /** True when {@code o} is an ARRAY (element-width access), false for a scalar object (8-byte slot).
     *  A null base is neither: {@link #at} has already made the offset absolute. */
    private static boolean isArrayRef(Object o)
    {
        return o != null && arrayKind0(o) != 0L;
    }

    /** Read {@code width} bytes little-endian, ZERO-extended. Exact for all three layouts; see above. */
    private static long getBits(Object o, long offset, int width)
    {
        long a = at(o, offset);
        if (width == 1)
        {
            return Magic.load8(a) & 0xFFL;
        }
        if (width == 2)
        {
            return (Magic.load8(a) & 0xFFL) | ((Magic.load8(a + 1) & 0xFFL) << 8);
        }
        if (width == 4)
        {
            return Magic.load32(a) & 0xFFFFFFFFL;
        }
        return Magic.load64(a);
    }

    /**
     * Write a narrow value: the WHOLE 8-byte slot for a field, {@code width} bytes for an element or an
     * absolute address. {@code v} must arrive already extended the way the compiler would leave it
     * (sign-extended for byte/short, zero-extended for boolean/char) -- which a Java widening of the
     * declared parameter type does for free at every call site below.
     */
    private static void putBits(Object o, long offset, long v, int width)
    {
        long a = at(o, offset);
        if (o != null && !isArrayRef(o))
        {
            Magic.store64(a, v);                 // a FIELD: one slot, exactly as putfield's strx leaves it
            return;
        }
        if (width == 8)
        {
            Magic.store64(a, v);
            return;
        }
        if (width == 4)
        {
            Magic.store32(a, (int) v);
            return;
        }
        Magic.store8(a, (int) v);
        if (width == 2)
        {
            Magic.store8(a + 1, (int) (v >>> 8));
        }
    }

    // ----- plain (Object, long) accessors, the widths this overlay had not declared -------------------

    public boolean getBoolean(Object o, long offset)
    {
        return getBits(o, offset, 1) != 0L;
    }

    public void putBoolean(Object o, long offset, boolean x)
    {
        putBits(o, offset, x ? 1L : 0L, 1);
    }

    public void putByte(Object o, long offset, byte x)
    {
        putBits(o, offset, x, 1);
    }

    public short getShort(Object o, long offset)
    {
        return (short) getBits(o, offset, 2);
    }

    public void putShort(Object o, long offset, short x)
    {
        putBits(o, offset, x, 2);
    }

    public char getChar(Object o, long offset)
    {
        return (char) getBits(o, offset, 2);
    }

    public void putChar(Object o, long offset, char x)
    {
        putBits(o, offset, x, 2);
    }

    /** A float lives in a GP register here as its raw bits ({@code Float.intBitsToFloat} is an identity
     *  native), so the conversion is real on a host JVM and a pass-through on metal -- the same source is
     *  correct in both worlds, which is what lets a host oracle check it. */
    public float getFloat(Object o, long offset)
    {
        return Float.intBitsToFloat((int) getBits(o, offset, 4));
    }

    public void putFloat(Object o, long offset, float x)
    {
        putBits(o, offset, Float.floatToRawIntBits(x), 4);
    }

    public double getDouble(Object o, long offset)
    {
        return Double.longBitsToDouble(Magic.load64(at(o, offset)));
    }

    /** The one narrow-ish width that needs no discriminator: a double is 8 bytes as a field AND as an
     *  element, so the slot write and the element write are the same instruction. */
    public void putDouble(Object o, long offset, double x)
    {
        Magic.store64(at(o, offset), Double.doubleToRawLongBits(x));
    }

    // ----- memory modes for those widths ------------------------------------------------------------
    // The *Volatile form is the root (one full barrier -- see fence0) and every Acquire/Release/Opaque
    // form is stock's own one-line delegation to it, copied rather than re-derived. joe-ng has no one-way
    // barrier to emit, so stronger-than-required is the only available answer and it is always correct.

    public boolean getBooleanVolatile(Object o, long offset)
    {
        boolean v = getBoolean(o, offset);
        fence0();
        return v;
    }

    public void putBooleanVolatile(Object o, long offset, boolean x)
    {
        fence0();
        putBoolean(o, offset, x);
    }

    public byte getByteVolatile(Object o, long offset)
    {
        byte v = getByte(o, offset);
        fence0();
        return v;
    }

    public void putByteVolatile(Object o, long offset, byte x)
    {
        fence0();
        putByte(o, offset, x);
    }

    public short getShortVolatile(Object o, long offset)
    {
        short v = getShort(o, offset);
        fence0();
        return v;
    }

    public void putShortVolatile(Object o, long offset, short x)
    {
        fence0();
        putShort(o, offset, x);
    }

    public char getCharVolatile(Object o, long offset)
    {
        char v = getChar(o, offset);
        fence0();
        return v;
    }

    public void putCharVolatile(Object o, long offset, char x)
    {
        fence0();
        putChar(o, offset, x);
    }

    public float getFloatVolatile(Object o, long offset)
    {
        float v = getFloat(o, offset);
        fence0();
        return v;
    }

    public void putFloatVolatile(Object o, long offset, float x)
    {
        fence0();
        putFloat(o, offset, x);
    }

    public double getDoubleVolatile(Object o, long offset)
    {
        double v = getDouble(o, offset);
        fence0();
        return v;
    }

    public void putDoubleVolatile(Object o, long offset, double x)
    {
        fence0();
        putDouble(o, offset, x);
    }

    // Stock's delegations (Unsafe.java: getXAcquire/getXOpaque -> getXVolatile, putXRelease/putXOpaque ->
    // putXVolatile), verbatim.
    public final boolean getBooleanAcquire(Object o, long offset)
    {
        return getBooleanVolatile(o, offset);
    }
 public final byte getByteAcquire(Object o, long offset)
    {
        return getByteVolatile(o, offset);
    }
 public final short getShortAcquire(Object o, long offset)
    {
        return getShortVolatile(o, offset);
    }
 public final char getCharAcquire(Object o, long offset)
    {
        return getCharVolatile(o, offset);
    }
 public final float getFloatAcquire(Object o, long offset)
    {
        return getFloatVolatile(o, offset);
    }
 public final double getDoubleAcquire(Object o, long offset)
    {
        return getDoubleVolatile(o, offset);
    }

    public final void putBooleanRelease(Object o, long offset, boolean x)
    {
        putBooleanVolatile(o, offset, x);
    }
 public final void putByteRelease(Object o, long offset, byte x)
    {
        putByteVolatile(o, offset, x);
    }
 public final void putShortRelease(Object o, long offset, short x)
    {
        putShortVolatile(o, offset, x);
    }
 public final void putCharRelease(Object o, long offset, char x)
    {
        putCharVolatile(o, offset, x);
    }
 public final void putFloatRelease(Object o, long offset, float x)
    {
        putFloatVolatile(o, offset, x);
    }
 public final void putDoubleRelease(Object o, long offset, double x)
    {
        putDoubleVolatile(o, offset, x);
    }

    public final boolean getBooleanOpaque(Object o, long offset)
    {
        return getBooleanVolatile(o, offset);
    }
 public final byte getByteOpaque(Object o, long offset)
    {
        return getByteVolatile(o, offset);
    }
 public final short getShortOpaque(Object o, long offset)
    {
        return getShortVolatile(o, offset);
    }
 public final char getCharOpaque(Object o, long offset)
    {
        return getCharVolatile(o, offset);
    }
 public final float getFloatOpaque(Object o, long offset)
    {
        return getFloatVolatile(o, offset);
    }
 public final double getDoubleOpaque(Object o, long offset)
    {
        return getDoubleVolatile(o, offset);
    }
 public final long getLongOpaque(Object o, long offset)
    {
        return getLongVolatile(o, offset);
    }
    public final Object  getReferenceOpaque(Object o, long offset)
    {
        return getReferenceVolatile(o, offset);
    }

    public final void putBooleanOpaque(Object o, long offset, boolean x)
    {
        putBooleanVolatile(o, offset, x);
    }
 public final void putByteOpaque(Object o, long offset, byte x)
    {
        putByteVolatile(o, offset, x);
    }
 public final void putShortOpaque(Object o, long offset, short x)
    {
        putShortVolatile(o, offset, x);
    }
 public final void putCharOpaque(Object o, long offset, char x)
    {
        putCharVolatile(o, offset, x);
    }
 public final void putFloatOpaque(Object o, long offset, float x)
    {
        putFloatVolatile(o, offset, x);
    }
 public final void putDoubleOpaque(Object o, long offset, double x)
    {
        putDoubleVolatile(o, offset, x);
    }
 public final void putLongOpaque(Object o, long offset, long x)
    {
        putLongVolatile(o, offset, x);
    }
    public final void putReferenceOpaque(Object o, long offset, Object x)
    {
        putReferenceVolatile(o, offset, x);
    }

    // ----- the absolute ("C heap") forms -------------------------------------------------------------
    // Stock's own bodies: getX(null, address). A null base makes `at` treat the offset as an ABSOLUTE
    // address, so these read and write RAW MEMORY at their natural width -- which is what every caller
    // (sun/nio/ch/NativeObject, KQueue, NativeSocketAddress, CRC32C) means by them. They do NOT allocate;
    // allocateMemory throws, so these serve memory the board already owns (MMIO, a firmware buffer).

 public byte getByte(long address)
    {
        return getByte(null, address);
    }
 public void putByte(long address, byte x)
    {
        putByte(null, address, x);
    }
 public short getShort(long address)
    {
        return getShort(null, address);
    }
 public void putShort(long address, short x)
    {
        putShort(null, address, x);
    }
 public char getChar(long address)
    {
        return getChar(null, address);
    }
 public void putChar(long address, char x)
    {
        putChar(null, address, x);
    }
 public int getInt(long address)
    {
        return getInt(null, address);
    }
 public void putInt(long address, int x)
    {
        putInt(null, address, x);
    }
 public long getLong(long address)
    {
        return getLong(null, address);
    }
 public void putLong(long address, long x)
    {
        putLong(null, address, x);
    }
 public float getFloat(long address)
    {
        return getFloat(null, address);
    }
 public void putFloat(long address, float x)
    {
        putFloat(null, address, x);
    }
 public double getDouble(long address)
    {
        return getDouble(null, address);
    }
 public void putDouble(long address, double x)
    {
        putDouble(null, address, x);
    }

    /** Stock branches on {@code ADDRESS_SIZE == 4}; it is 8 here, so this is the long arm. */
 public long getAddress(Object o, long offset)
    {
        return getLong(o, offset);
    }
 public void putAddress(Object o, long offset, long x)
    {
        putLong(o, offset, x);
    }
 public long getAddress(long address)
    {
        return getAddress(null, address);
    }
 public void putAddress(long address, long x)
    {
        putAddress(null, address, x);
    }

    /** Reference width. Factual: AArch64, direct 8-byte refs, no compressed oops. */
    public int addressSize()
    {
        return ADDRESS_SIZE;
    }

    /** Normal cacheable RAM permits unaligned LDR/STR on this core, which is what {@link #getIntUnaligned}
     *  already relies on. Read only by {@code java/nio/Bits}, to size off-heap alignment. */
    public final boolean unalignedAccess()
    {
        return true;
    }

    /** Stock's own fallback body when the intrinsic is unavailable (Unsafe.java: "fall back to storeFence"). */
    public final void storeStoreFence()
    {
        storeFence();
    }

    // ----- the unaligned family ----------------------------------------------------------------------
    // Stock branches on alignment and composes from smaller pieces when misaligned; joe-ng needs no such
    // split (Normal cacheable RAM permits unaligned LDR/STR), so the aligned arm serves every offset. What
    // IS taken from stock verbatim is the byte-order half: `convEndian` and its Xxx.reverseBytes calls.
    //
    // THE PUTS DELIBERATELY DO NOT ROUTE THROUGH putInt/putLong. Those write a whole 8-byte SLOT, which is
    // right for a field and wrong for the byte[] region every reached caller of the unaligned forms is
    // actually addressing (jdk/internal/classfile/impl/RawBytecodeHelper over bytecode, ScopedMemoryAccess
    // over a segment). putBits writes the natural width there and the slot for a field.

    public final char getCharUnaligned(Object o, long offset)
    {
        return getChar(o, offset);
    }

    public final char getCharUnaligned(Object o, long offset, boolean bigEndian)
    {
        return convEndian(bigEndian, getCharUnaligned(o, offset));
    }

    public final short getShortUnaligned(Object o, long offset)
    {
        return getShort(o, offset);
    }

    public final short getShortUnaligned(Object o, long offset, boolean bigEndian)
    {
        return convEndian(bigEndian, getShortUnaligned(o, offset));
    }

    public final int getIntUnaligned(Object o, long offset, boolean bigEndian)
    {
        return convEndian(bigEndian, getIntUnaligned(o, offset));
    }

    public final long getLongUnaligned(Object o, long offset, boolean bigEndian)
    {
        return convEndian(bigEndian, getLongUnaligned(o, offset));
    }

    public final void putCharUnaligned(Object o, long offset, char x)
    {
        putBits(o, offset, x, 2);
    }

    public final void putCharUnaligned(Object o, long offset, char x, boolean bigEndian)
    {
        putCharUnaligned(o, offset, convEndian(bigEndian, x));
    }

    public final void putShortUnaligned(Object o, long offset, short x)
    {
        putBits(o, offset, x, 2);
    }

    public final void putShortUnaligned(Object o, long offset, short x, boolean bigEndian)
    {
        putShortUnaligned(o, offset, convEndian(bigEndian, x));
    }

    public final void putIntUnaligned(Object o, long offset, int x)
    {
        putBits(o, offset, x, 4);
    }

    public final void putIntUnaligned(Object o, long offset, int x, boolean bigEndian)
    {
        putIntUnaligned(o, offset, convEndian(bigEndian, x));
    }

    public final void putLongUnaligned(Object o, long offset, long x)
    {
        putBits(o, offset, x, 8);
    }

    public final void putLongUnaligned(Object o, long offset, long x, boolean bigEndian)
    {
        putLongUnaligned(o, offset, convEndian(bigEndian, x));
    }

    // Stock's byte-order helpers (Unsafe.java), verbatim. BIG_ENDIAN is a compile-time false here, so javac
    // folds each of these to its little-endian arm and nothing is emitted for the other.
    private static char convEndian(boolean big, char n)
    {
        return big == BIG_ENDIAN ? n : Character.reverseBytes(n);
    }
    private static short convEndian(boolean big, short n)
    {
        return big == BIG_ENDIAN ? n : Short.reverseBytes(n);
    }
    private static int convEndian(boolean big, int n)
    {
        return big == BIG_ENDIAN ? n : Integer.reverseBytes(n);
    }
    private static long convEndian(boolean big, long n)
    {
        return big == BIG_ENDIAN ? n : Long.reverseBytes(n);
    }


    // ================================================================================================
    // NARROW-WIDTH ATOMICS.
    //
    // Everything in this section is the JDK 26 source's own code EXCEPT the two roots and the primitive
    // immediately below, and that exception is forced rather than chosen. Stock builds every narrow CAS out
    // of `getIntVolatile` + `weakCompareAndSetInt` on the enclosing FOUR-byte word (`offset & ~3`, masked).
    // joe-ng's int CAS is `Magic.cas64` over an EIGHT-byte slot, so taking those bodies verbatim would compare
    // and write eight bytes at a four-aligned address -- misaligned for the exclusive monitor, and spanning
    // whatever sits beside it. For a byte field holding 5 it would leave the slot at `0xFF..FF05`, which
    // `getfield` reads as -251. So `compareAndExchangeByte` and `compareAndExchangeShort` are joe-ng's, and
    // the ~140 members above them -- Char via Short, Boolean via Byte, Float via Int, Double via Long, every
    // compareAndSet/weakCompareAndSet/Acquire/Release delegation and every getAndAdd/getAndBitwise/getAndSet
    // loop -- are stock's, unchanged.
    //
    // STATED PRECONDITION, inherited rather than introduced: the Int, Long, Reference, Float and Double
    // atomics operate on the whole 8-byte SLOT, which is exact for an instance FIELD (ObjectModel gives each
    // one a full slot and the compiler keeps it canonically extended) and WRONG for an array element of scale
    // below 8 -- an `int[]` CAS would take its neighbour with it. That is the precondition the int forms have
    // carried since they were written; Float and Double join it because stock derives them from Int and Long
    // and this increment takes that derivation verbatim. MEASURED, so it is a statement about reach and not a
    // shrug: every referrer of those array cases is `java/lang/invoke/VarHandleXxx$Array`, a denied package.
    // The Byte/Short/Char/Boolean forms below are NOT subject to it -- they had to be written anyway, and
    // writing them width-aware cost nothing extra.
    // ================================================================================================

    /**
     * The width-aware narrow compare-and-exchange the whole byte/short/char/boolean family rests on. Answers
     * the WITNESSED word; the caller narrows it, exactly as stock's callers narrow stock's.
     *
     * <p>TWO LAYOUTS AGAIN, the same pair the accessor section describes:
     * <ul>
     *   <li>a FIELD -- or a null base -- occupies the whole 8-byte slot, so the VALUE is compared in the low
     *       {@code width} bytes and the WHOLE slot is replaced. Replacing the whole word is what makes a sign
     *       change work: a {@code short} field going -2 to 5 must leave the slot {@code 0x5}, not
     *       {@code 0xFFFFFFFFFFFF0005}, and masking only the low two bytes would leave the old extension
     *       behind for {@code getfield} to read.</li>
     *   <li>an ARRAY ELEMENT occupies {@code width} bytes, so the element's bits are masked inside the
     *       enclosing 8-byte word and everything else in that word is preserved.</li>
     * </ul>
     *
     * <p>THE 8-BYTE WINDOW CANNOT LEAVE THE ARRAY, which is what makes the masked form safe rather than
     * merely convenient. An array's payload starts at 24 -- 8-aligned -- and its allocation is
     * {@code align8(24 + length*scale)} ({@code ObjectModel.arraySize}), so the window enclosing any element
     * lies inside the array's own allocation: in its trailing padding at worst, never in the next object's
     * header.
     *
     * <p>AND THE ELEMENT CANNOT SPAN THE WINDOW, because stock's own {@code (offset & 3) == 3} guard forbids
     * the only offsets that could -- a 2-byte value at {@code offset & 7 == 7}. That guard is written about
     * stock's FOUR-byte word and happens to be exactly strong enough for an eight-byte one, which is why it
     * is kept verbatim below rather than widened.
     *
     * <p>WIDTH 4 NEEDS NO SUCH GUARD, and the reason is arithmetic rather than a borrowed check: a 4-byte
     * ELEMENT sits at {@code 24 + 4k} off an 8-aligned object, so {@code a & 7} is 0 or 4 and the shift is 0
     * or 32 -- a 32-bit mask at either shift lies entirely inside the 64-bit word. A 4-byte FIELD is at
     * {@code 16 + 8*slot}, 8-aligned, and takes the whole-slot branch above. So neither layout can straddle.
     *
     * <p>WIDTH 4 IS WHAT MAKES {@code arrayElementVarHandle} SAFE FOR {@code int[]} AND {@code float[]}, and
     * before it existed those were the one genuinely dangerous case in this file: the int atomics below CAS
     * the whole 8-byte slot, which is exact for a field and spans TWO elements of a 4-scale array. At an ODD
     * index that faults loudly (LDAXR wants 8-byte alignment); at an EVEN index it does NOT -- it compares a
     * sign-extended 32-bit expected value against two packed elements, so it answers a wrong {@code false}
     * when the neighbour is non-zero and CLOBBERS the neighbour when it is. Both are silent. That is why the
     * int atomics ask {@code isArrayRef} now.
     *
     * <p>THE LOOP IS REQUIRED RATHER THAN DEFENSIVE: LDAXR/STLXR may fail SPURIOUSLY -- an interrupt between
     * the load and the store clears the exclusive monitor -- so a single attempt would drop the update on
     * hardware in a way it never does under emulation.
     */
    private long casNarrow(Object o, long offset, long expected, long x, int width)
    {
        long vmask = 0xFFFFFFFFL;
        if (width == 1)
        {
            vmask = 0xFFL;
        }
        else if (width == 2)
        {
            vmask = 0xFFFFL;
        }
        long a = at(o, offset);
        if (!isArrayRef(o))
        {
            while (true)
            {
                long full = Magic.load64(a);
                if ((full & vmask) != (expected & vmask))
                {
                    return full;                         // witnessed; the caller narrows it
                }
                if (Magic.cas64(a, full, x))
                {
                    return expected;
                }
            }
        }
        long word = a & ~7L;
        int shift = (int) (a & 7L) << 3;
        long mask = vmask << shift;
        long maskedExpected = (expected & vmask) << shift;
        long maskedX = (x & vmask) << shift;
        while (true)
        {
            long full = Magic.load64(word);
            if ((full & mask) != maskedExpected)
            {
                return (full & mask) >>> shift;
            }
            if (Magic.cas64(word, full, (full & ~mask) | maskedX))
            {
                return expected;
            }
        }
    }

    public final byte compareAndExchangeByte(Object o, long offset, byte expected, byte x)
    {
        return (byte) casNarrow(o, offset, expected, x, 1);
    }

    public final short compareAndExchangeShort(Object o, long offset, short expected, short x)
    {
        // Stock's guard, verbatim -- and load-bearing here for a second reason: it is what keeps a 2-byte
        // element from spanning joe-ng's EIGHT-byte CAS window as well as stock's four-byte one.
        if ((offset & 3) == 3)
        {
            throw new IllegalArgumentException("Update spans the word, not supported");
        }
        return (short) casNarrow(o, offset, expected, x, 2);
    }

    // ----- the rest of the family is the JDK 26 source, unchanged ------------------------------------

    public final Object compareAndExchangeReferenceAcquire(Object o, long offset,
                                                           Object expected,
                                                           Object x)
    {
        return compareAndExchangeReference(o, offset, expected, x);
    }

    public final Object compareAndExchangeReferenceRelease(Object o, long offset,
                                                           Object expected,
                                                           Object x)
    {
        return compareAndExchangeReference(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetReferencePlain(Object o, long offset,
                                                         Object expected,
                                                         Object x)
    {
        return compareAndSetReference(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetReferenceAcquire(Object o, long offset,
                                                           Object expected,
                                                           Object x)
    {
        return compareAndSetReference(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetReferenceRelease(Object o, long offset,
                                                           Object expected,
                                                           Object x)
    {
        return compareAndSetReference(o, offset, expected, x);
    }

    public final int compareAndExchangeIntAcquire(Object o, long offset,
                                                         int expected,
                                                         int x)
    {
        return compareAndExchangeInt(o, offset, expected, x);
    }

    public final int compareAndExchangeIntRelease(Object o, long offset,
                                                         int expected,
                                                         int x)
    {
        return compareAndExchangeInt(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetIntPlain(Object o, long offset,
                                                   int expected,
                                                   int x)
    {
        return compareAndSetInt(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetIntAcquire(Object o, long offset,
                                                     int expected,
                                                     int x)
    {
        return compareAndSetInt(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetIntRelease(Object o, long offset,
                                                     int expected,
                                                     int x)
    {
        return compareAndSetInt(o, offset, expected, x);
    }

    public final boolean compareAndSetByte(Object o, long offset,
                                           byte expected,
                                           byte x)
    {
        return compareAndExchangeByte(o, offset, expected, x) == expected;
    }

    public final boolean weakCompareAndSetByte(Object o, long offset,
                                               byte expected,
                                               byte x)
    {
        return compareAndSetByte(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetByteAcquire(Object o, long offset,
                                                      byte expected,
                                                      byte x)
    {
        return weakCompareAndSetByte(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetByteRelease(Object o, long offset,
                                                      byte expected,
                                                      byte x)
    {
        return weakCompareAndSetByte(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetBytePlain(Object o, long offset,
                                                    byte expected,
                                                    byte x)
    {
        return weakCompareAndSetByte(o, offset, expected, x);
    }

    public final byte compareAndExchangeByteAcquire(Object o, long offset,
                                                    byte expected,
                                                    byte x)
    {
        return compareAndExchangeByte(o, offset, expected, x);
    }

    public final byte compareAndExchangeByteRelease(Object o, long offset,
                                                    byte expected,
                                                    byte x)
    {
        return compareAndExchangeByte(o, offset, expected, x);
    }

    public final boolean compareAndSetShort(Object o, long offset,
                                            short expected,
                                            short x)
    {
        return compareAndExchangeShort(o, offset, expected, x) == expected;
    }

    public final boolean weakCompareAndSetShort(Object o, long offset,
                                                short expected,
                                                short x)
    {
        return compareAndSetShort(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetShortAcquire(Object o, long offset,
                                                       short expected,
                                                       short x)
    {
        return weakCompareAndSetShort(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetShortRelease(Object o, long offset,
                                                       short expected,
                                                       short x)
    {
        return weakCompareAndSetShort(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetShortPlain(Object o, long offset,
                                                     short expected,
                                                     short x)
    {
        return weakCompareAndSetShort(o, offset, expected, x);
    }

    public final short compareAndExchangeShortAcquire(Object o, long offset,
                                                     short expected,
                                                     short x)
    {
        return compareAndExchangeShort(o, offset, expected, x);
    }

    public final short compareAndExchangeShortRelease(Object o, long offset,
                                                    short expected,
                                                    short x)
    {
        return compareAndExchangeShort(o, offset, expected, x);
    }

    private char s2c(short s)
    {
        return (char) s;
    }

    private short c2s(char s)
    {
        return (short) s;
    }

    public final boolean compareAndSetChar(Object o, long offset,
                                           char expected,
                                           char x)
    {
        return compareAndSetShort(o, offset, c2s(expected), c2s(x));
    }

    public final char compareAndExchangeChar(Object o, long offset,
                                             char expected,
                                             char x)
    {
        return s2c(compareAndExchangeShort(o, offset, c2s(expected), c2s(x)));
    }

    public final char compareAndExchangeCharAcquire(Object o, long offset,
                                            char expected,
                                            char x)
    {
        return s2c(compareAndExchangeShortAcquire(o, offset, c2s(expected), c2s(x)));
    }

    public final char compareAndExchangeCharRelease(Object o, long offset,
                                            char expected,
                                            char x)
    {
        return s2c(compareAndExchangeShortRelease(o, offset, c2s(expected), c2s(x)));
    }

    public final boolean weakCompareAndSetChar(Object o, long offset,
                                               char expected,
                                               char x)
    {
        return weakCompareAndSetShort(o, offset, c2s(expected), c2s(x));
    }

    public final boolean weakCompareAndSetCharAcquire(Object o, long offset,
                                                      char expected,
                                                      char x)
    {
        return weakCompareAndSetShortAcquire(o, offset, c2s(expected), c2s(x));
    }

    public final boolean weakCompareAndSetCharRelease(Object o, long offset,
                                                      char expected,
                                                      char x)
    {
        return weakCompareAndSetShortRelease(o, offset, c2s(expected), c2s(x));
    }

    public final boolean weakCompareAndSetCharPlain(Object o, long offset,
                                                    char expected,
                                                    char x)
    {
        return weakCompareAndSetShortPlain(o, offset, c2s(expected), c2s(x));
    }

    private boolean byte2bool(byte b)
    {
        return b != 0;
    }

    private byte bool2byte(boolean b)
    {
        return b ? (byte)1 : (byte)0;
    }

    public final boolean compareAndSetBoolean(Object o, long offset,
                                              boolean expected,
                                              boolean x)
    {
        return compareAndSetByte(o, offset, bool2byte(expected), bool2byte(x));
    }

    public final boolean compareAndExchangeBoolean(Object o, long offset,
                                                   boolean expected,
                                                   boolean x)
    {
        return byte2bool(compareAndExchangeByte(o, offset, bool2byte(expected), bool2byte(x)));
    }

    public final boolean compareAndExchangeBooleanAcquire(Object o, long offset,
                                                    boolean expected,
                                                    boolean x)
    {
        return byte2bool(compareAndExchangeByteAcquire(o, offset, bool2byte(expected), bool2byte(x)));
    }

    public final boolean compareAndExchangeBooleanRelease(Object o, long offset,
                                                       boolean expected,
                                                       boolean x)
    {
        return byte2bool(compareAndExchangeByteRelease(o, offset, bool2byte(expected), bool2byte(x)));
    }

    public final boolean weakCompareAndSetBoolean(Object o, long offset,
                                                  boolean expected,
                                                  boolean x)
    {
        return weakCompareAndSetByte(o, offset, bool2byte(expected), bool2byte(x));
    }

    public final boolean weakCompareAndSetBooleanAcquire(Object o, long offset,
                                                         boolean expected,
                                                         boolean x)
    {
        return weakCompareAndSetByteAcquire(o, offset, bool2byte(expected), bool2byte(x));
    }

    public final boolean weakCompareAndSetBooleanRelease(Object o, long offset,
                                                         boolean expected,
                                                         boolean x)
    {
        return weakCompareAndSetByteRelease(o, offset, bool2byte(expected), bool2byte(x));
    }

    public final boolean weakCompareAndSetBooleanPlain(Object o, long offset,
                                                       boolean expected,
                                                       boolean x)
    {
        return weakCompareAndSetBytePlain(o, offset, bool2byte(expected), bool2byte(x));
    }

    public final boolean compareAndSetFloat(Object o, long offset,
                                            float expected,
                                            float x)
    {
        return compareAndSetInt(o, offset,
                                 Float.floatToRawIntBits(expected),
                                 Float.floatToRawIntBits(x));
    }

    public final float compareAndExchangeFloat(Object o, long offset,
                                               float expected,
                                               float x)
    {
        int w = compareAndExchangeInt(o, offset,
                                      Float.floatToRawIntBits(expected),
                                      Float.floatToRawIntBits(x));
        return Float.intBitsToFloat(w);
    }

    public final float compareAndExchangeFloatAcquire(Object o, long offset,
                                                  float expected,
                                                  float x)
    {
        int w = compareAndExchangeIntAcquire(o, offset,
                                             Float.floatToRawIntBits(expected),
                                             Float.floatToRawIntBits(x));
        return Float.intBitsToFloat(w);
    }

    public final float compareAndExchangeFloatRelease(Object o, long offset,
                                                  float expected,
                                                  float x)
    {
        int w = compareAndExchangeIntRelease(o, offset,
                                             Float.floatToRawIntBits(expected),
                                             Float.floatToRawIntBits(x));
        return Float.intBitsToFloat(w);
    }

    public final boolean weakCompareAndSetFloatPlain(Object o, long offset,
                                                     float expected,
                                                     float x)
    {
        return weakCompareAndSetIntPlain(o, offset,
                                     Float.floatToRawIntBits(expected),
                                     Float.floatToRawIntBits(x));
    }

    public final boolean weakCompareAndSetFloatAcquire(Object o, long offset,
                                                       float expected,
                                                       float x)
    {
        return weakCompareAndSetIntAcquire(o, offset,
                                            Float.floatToRawIntBits(expected),
                                            Float.floatToRawIntBits(x));
    }

    public final boolean weakCompareAndSetFloatRelease(Object o, long offset,
                                                       float expected,
                                                       float x)
    {
        return weakCompareAndSetIntRelease(o, offset,
                                            Float.floatToRawIntBits(expected),
                                            Float.floatToRawIntBits(x));
    }

    public final boolean weakCompareAndSetFloat(Object o, long offset,
                                                float expected,
                                                float x)
    {
        return weakCompareAndSetInt(o, offset,
                                             Float.floatToRawIntBits(expected),
                                             Float.floatToRawIntBits(x));
    }

    public final boolean compareAndSetDouble(Object o, long offset,
                                             double expected,
                                             double x)
    {
        return compareAndSetLong(o, offset,
                                 Double.doubleToRawLongBits(expected),
                                 Double.doubleToRawLongBits(x));
    }

    public final double compareAndExchangeDouble(Object o, long offset,
                                                 double expected,
                                                 double x)
    {
        long w = compareAndExchangeLong(o, offset,
                                        Double.doubleToRawLongBits(expected),
                                        Double.doubleToRawLongBits(x));
        return Double.longBitsToDouble(w);
    }

    public final double compareAndExchangeDoubleAcquire(Object o, long offset,
                                                        double expected,
                                                        double x)
    {
        long w = compareAndExchangeLongAcquire(o, offset,
                                               Double.doubleToRawLongBits(expected),
                                               Double.doubleToRawLongBits(x));
        return Double.longBitsToDouble(w);
    }

    public final double compareAndExchangeDoubleRelease(Object o, long offset,
                                                        double expected,
                                                        double x)
    {
        long w = compareAndExchangeLongRelease(o, offset,
                                               Double.doubleToRawLongBits(expected),
                                               Double.doubleToRawLongBits(x));
        return Double.longBitsToDouble(w);
    }

    public final boolean weakCompareAndSetDoublePlain(Object o, long offset,
                                                      double expected,
                                                      double x)
    {
        return weakCompareAndSetLongPlain(o, offset,
                                     Double.doubleToRawLongBits(expected),
                                     Double.doubleToRawLongBits(x));
    }

    public final boolean weakCompareAndSetDoubleAcquire(Object o, long offset,
                                                        double expected,
                                                        double x)
    {
        return weakCompareAndSetLongAcquire(o, offset,
                                             Double.doubleToRawLongBits(expected),
                                             Double.doubleToRawLongBits(x));
    }

    public final boolean weakCompareAndSetDoubleRelease(Object o, long offset,
                                                        double expected,
                                                        double x)
    {
        return weakCompareAndSetLongRelease(o, offset,
                                             Double.doubleToRawLongBits(expected),
                                             Double.doubleToRawLongBits(x));
    }

    public final boolean weakCompareAndSetDouble(Object o, long offset,
                                                 double expected,
                                                 double x)
    {
        return weakCompareAndSetLong(o, offset,
                                              Double.doubleToRawLongBits(expected),
                                              Double.doubleToRawLongBits(x));
    }

    public final long compareAndExchangeLongAcquire(Object o, long offset,
                                                           long expected,
                                                           long x)
    {
        return compareAndExchangeLong(o, offset, expected, x);
    }

    public final long compareAndExchangeLongRelease(Object o, long offset,
                                                           long expected,
                                                           long x)
    {
        return compareAndExchangeLong(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetLongPlain(Object o, long offset,
                                                    long expected,
                                                    long x)
    {
        return compareAndSetLong(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetLongAcquire(Object o, long offset,
                                                      long expected,
                                                      long x)
    {
        return compareAndSetLong(o, offset, expected, x);
    }

    public final boolean weakCompareAndSetLongRelease(Object o, long offset,
                                                      long expected,
                                                      long x)
    {
        return compareAndSetLong(o, offset, expected, x);
    }

    public final int getAndAddIntRelease(Object o, long offset, int delta)
    {
        int v;
        do {
            v = getInt(o, offset);
        } while (!weakCompareAndSetIntRelease(o, offset, v, v + delta));
        return v;
    }

    public final int getAndAddIntAcquire(Object o, long offset, int delta)
    {
        int v;
        do {
            v = getIntAcquire(o, offset);
        } while (!weakCompareAndSetIntAcquire(o, offset, v, v + delta));
        return v;
    }

    public final long getAndAddLongRelease(Object o, long offset, long delta)
    {
        long v;
        do {
            v = getLong(o, offset);
        } while (!weakCompareAndSetLongRelease(o, offset, v, v + delta));
        return v;
    }

    public final long getAndAddLongAcquire(Object o, long offset, long delta)
    {
        long v;
        do {
            v = getLongAcquire(o, offset);
        } while (!weakCompareAndSetLongAcquire(o, offset, v, v + delta));
        return v;
    }

    public final byte getAndAddByte(Object o, long offset, byte delta)
    {
        byte v;
        do {
            v = getByteVolatile(o, offset);
        } while (!weakCompareAndSetByte(o, offset, v, (byte) (v + delta)));
        return v;
    }

    public final byte getAndAddByteRelease(Object o, long offset, byte delta)
    {
        byte v;
        do {
            v = getByte(o, offset);
        } while (!weakCompareAndSetByteRelease(o, offset, v, (byte) (v + delta)));
        return v;
    }

    public final byte getAndAddByteAcquire(Object o, long offset, byte delta)
    {
        byte v;
        do {
            v = getByteAcquire(o, offset);
        } while (!weakCompareAndSetByteAcquire(o, offset, v, (byte) (v + delta)));
        return v;
    }

    public final short getAndAddShort(Object o, long offset, short delta)
    {
        short v;
        do {
            v = getShortVolatile(o, offset);
        } while (!weakCompareAndSetShort(o, offset, v, (short) (v + delta)));
        return v;
    }

    public final short getAndAddShortRelease(Object o, long offset, short delta)
    {
        short v;
        do {
            v = getShort(o, offset);
        } while (!weakCompareAndSetShortRelease(o, offset, v, (short) (v + delta)));
        return v;
    }

    public final short getAndAddShortAcquire(Object o, long offset, short delta)
    {
        short v;
        do {
            v = getShortAcquire(o, offset);
        } while (!weakCompareAndSetShortAcquire(o, offset, v, (short) (v + delta)));
        return v;
    }

    public final char getAndAddChar(Object o, long offset, char delta)
    {
        return (char) getAndAddShort(o, offset, (short) delta);
    }

    public final char getAndAddCharRelease(Object o, long offset, char delta)
    {
        return (char) getAndAddShortRelease(o, offset, (short) delta);
    }

    public final char getAndAddCharAcquire(Object o, long offset, char delta)
    {
        return (char) getAndAddShortAcquire(o, offset, (short) delta);
    }

    public final float getAndAddFloat(Object o, long offset, float delta)
    {
        int expectedBits;
        float v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getIntVolatile(o, offset);
            v = Float.intBitsToFloat(expectedBits);
        } while (!weakCompareAndSetInt(o, offset,
                                                expectedBits, Float.floatToRawIntBits(v + delta)));
        return v;
    }

    public final float getAndAddFloatRelease(Object o, long offset, float delta)
    {
        int expectedBits;
        float v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getInt(o, offset);
            v = Float.intBitsToFloat(expectedBits);
        } while (!weakCompareAndSetIntRelease(o, offset,
                                               expectedBits, Float.floatToRawIntBits(v + delta)));
        return v;
    }

    public final float getAndAddFloatAcquire(Object o, long offset, float delta)
    {
        int expectedBits;
        float v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getIntAcquire(o, offset);
            v = Float.intBitsToFloat(expectedBits);
        } while (!weakCompareAndSetIntAcquire(o, offset,
                                               expectedBits, Float.floatToRawIntBits(v + delta)));
        return v;
    }

    public final double getAndAddDouble(Object o, long offset, double delta)
    {
        long expectedBits;
        double v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getLongVolatile(o, offset);
            v = Double.longBitsToDouble(expectedBits);
        } while (!weakCompareAndSetLong(o, offset,
                                                 expectedBits, Double.doubleToRawLongBits(v + delta)));
        return v;
    }

    public final double getAndAddDoubleRelease(Object o, long offset, double delta)
    {
        long expectedBits;
        double v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getLong(o, offset);
            v = Double.longBitsToDouble(expectedBits);
        } while (!weakCompareAndSetLongRelease(o, offset,
                                                expectedBits, Double.doubleToRawLongBits(v + delta)));
        return v;
    }

    public final double getAndAddDoubleAcquire(Object o, long offset, double delta)
    {
        long expectedBits;
        double v;
        do {
            // Load and CAS with the raw bits to avoid issues with NaNs and
            // possible bit conversion from signaling NaNs to quiet NaNs that
            // may result in the loop not terminating.
            expectedBits = getLongAcquire(o, offset);
            v = Double.longBitsToDouble(expectedBits);
        } while (!weakCompareAndSetLongAcquire(o, offset,
                                                expectedBits, Double.doubleToRawLongBits(v + delta)));
        return v;
    }

    public final int getAndSetIntRelease(Object o, long offset, int newValue)
    {
        int v;
        do {
            v = getInt(o, offset);
        } while (!weakCompareAndSetIntRelease(o, offset, v, newValue));
        return v;
    }

    public final int getAndSetIntAcquire(Object o, long offset, int newValue)
    {
        int v;
        do {
            v = getIntAcquire(o, offset);
        } while (!weakCompareAndSetIntAcquire(o, offset, v, newValue));
        return v;
    }

    public final long getAndSetLongRelease(Object o, long offset, long newValue)
    {
        long v;
        do {
            v = getLong(o, offset);
        } while (!weakCompareAndSetLongRelease(o, offset, v, newValue));
        return v;
    }

    public final long getAndSetLongAcquire(Object o, long offset, long newValue)
    {
        long v;
        do {
            v = getLongAcquire(o, offset);
        } while (!weakCompareAndSetLongAcquire(o, offset, v, newValue));
        return v;
    }

    public final Object getAndSetReferenceRelease(Object o, long offset, Object newValue)
    {
        Object v;
        do {
            v = getReference(o, offset);
        } while (!weakCompareAndSetReferenceRelease(o, offset, v, newValue));
        return v;
    }

    public final Object getAndSetReferenceAcquire(Object o, long offset, Object newValue)
    {
        Object v;
        do {
            v = getReferenceAcquire(o, offset);
        } while (!weakCompareAndSetReferenceAcquire(o, offset, v, newValue));
        return v;
    }

    public final byte getAndSetByte(Object o, long offset, byte newValue)
    {
        byte v;
        do {
            v = getByteVolatile(o, offset);
        } while (!weakCompareAndSetByte(o, offset, v, newValue));
        return v;
    }

    public final byte getAndSetByteRelease(Object o, long offset, byte newValue)
    {
        byte v;
        do {
            v = getByte(o, offset);
        } while (!weakCompareAndSetByteRelease(o, offset, v, newValue));
        return v;
    }

    public final byte getAndSetByteAcquire(Object o, long offset, byte newValue)
    {
        byte v;
        do {
            v = getByteAcquire(o, offset);
        } while (!weakCompareAndSetByteAcquire(o, offset, v, newValue));
        return v;
    }

    public final boolean getAndSetBoolean(Object o, long offset, boolean newValue)
    {
        return byte2bool(getAndSetByte(o, offset, bool2byte(newValue)));
    }

    public final boolean getAndSetBooleanRelease(Object o, long offset, boolean newValue)
    {
        return byte2bool(getAndSetByteRelease(o, offset, bool2byte(newValue)));
    }

    public final boolean getAndSetBooleanAcquire(Object o, long offset, boolean newValue)
    {
        return byte2bool(getAndSetByteAcquire(o, offset, bool2byte(newValue)));
    }

    public final short getAndSetShort(Object o, long offset, short newValue)
    {
        short v;
        do {
            v = getShortVolatile(o, offset);
        } while (!weakCompareAndSetShort(o, offset, v, newValue));
        return v;
    }

    public final short getAndSetShortRelease(Object o, long offset, short newValue)
    {
        short v;
        do {
            v = getShort(o, offset);
        } while (!weakCompareAndSetShortRelease(o, offset, v, newValue));
        return v;
    }

    public final short getAndSetShortAcquire(Object o, long offset, short newValue)
    {
        short v;
        do {
            v = getShortAcquire(o, offset);
        } while (!weakCompareAndSetShortAcquire(o, offset, v, newValue));
        return v;
    }

    public final char getAndSetChar(Object o, long offset, char newValue)
    {
        return s2c(getAndSetShort(o, offset, c2s(newValue)));
    }

    public final char getAndSetCharRelease(Object o, long offset, char newValue)
    {
        return s2c(getAndSetShortRelease(o, offset, c2s(newValue)));
    }

    public final char getAndSetCharAcquire(Object o, long offset, char newValue)
    {
        return s2c(getAndSetShortAcquire(o, offset, c2s(newValue)));
    }

    public final float getAndSetFloat(Object o, long offset, float newValue)
    {
        int v = getAndSetInt(o, offset, Float.floatToRawIntBits(newValue));
        return Float.intBitsToFloat(v);
    }

    public final float getAndSetFloatRelease(Object o, long offset, float newValue)
    {
        int v = getAndSetIntRelease(o, offset, Float.floatToRawIntBits(newValue));
        return Float.intBitsToFloat(v);
    }

    public final float getAndSetFloatAcquire(Object o, long offset, float newValue)
    {
        int v = getAndSetIntAcquire(o, offset, Float.floatToRawIntBits(newValue));
        return Float.intBitsToFloat(v);
    }

    public final double getAndSetDouble(Object o, long offset, double newValue)
    {
        long v = getAndSetLong(o, offset, Double.doubleToRawLongBits(newValue));
        return Double.longBitsToDouble(v);
    }

    public final double getAndSetDoubleRelease(Object o, long offset, double newValue)
    {
        long v = getAndSetLongRelease(o, offset, Double.doubleToRawLongBits(newValue));
        return Double.longBitsToDouble(v);
    }

    public final double getAndSetDoubleAcquire(Object o, long offset, double newValue)
    {
        long v = getAndSetLongAcquire(o, offset, Double.doubleToRawLongBits(newValue));
        return Double.longBitsToDouble(v);
    }

    public final boolean getAndBitwiseOrBoolean(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseOrByte(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseOrBooleanRelease(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseOrByteRelease(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseOrBooleanAcquire(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseOrByteAcquire(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseAndBoolean(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseAndByte(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseAndBooleanRelease(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseAndByteRelease(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseAndBooleanAcquire(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseAndByteAcquire(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseXorBoolean(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseXorByte(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseXorBooleanRelease(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseXorByteRelease(o, offset, bool2byte(mask)));
    }

    public final boolean getAndBitwiseXorBooleanAcquire(Object o, long offset, boolean mask)
    {
        return byte2bool(getAndBitwiseXorByteAcquire(o, offset, bool2byte(mask)));
    }

    public final byte getAndBitwiseOrByte(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByteVolatile(o, offset);
        } while (!weakCompareAndSetByte(o, offset,
                                                  current, (byte) (current | mask)));
        return current;
    }

    public final byte getAndBitwiseOrByteRelease(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteRelease(o, offset,
                                                 current, (byte) (current | mask)));
        return current;
    }

    public final byte getAndBitwiseOrByteAcquire(Object o, long offset, byte mask)
    {
        byte current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteAcquire(o, offset,
                                                 current, (byte) (current | mask)));
        return current;
    }

    public final byte getAndBitwiseAndByte(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByteVolatile(o, offset);
        } while (!weakCompareAndSetByte(o, offset,
                                                  current, (byte) (current & mask)));
        return current;
    }

    public final byte getAndBitwiseAndByteRelease(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteRelease(o, offset,
                                                 current, (byte) (current & mask)));
        return current;
    }

    public final byte getAndBitwiseAndByteAcquire(Object o, long offset, byte mask)
    {
        byte current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteAcquire(o, offset,
                                                 current, (byte) (current & mask)));
        return current;
    }

    public final byte getAndBitwiseXorByte(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByteVolatile(o, offset);
        } while (!weakCompareAndSetByte(o, offset,
                                                  current, (byte) (current ^ mask)));
        return current;
    }

    public final byte getAndBitwiseXorByteRelease(Object o, long offset, byte mask)
    {
        byte current;
        do {
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteRelease(o, offset,
                                                 current, (byte) (current ^ mask)));
        return current;
    }

    public final byte getAndBitwiseXorByteAcquire(Object o, long offset, byte mask)
    {
        byte current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getByte(o, offset);
        } while (!weakCompareAndSetByteAcquire(o, offset,
                                                 current, (byte) (current ^ mask)));
        return current;
    }

    public final char getAndBitwiseOrChar(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseOrShort(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseOrCharRelease(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseOrShortRelease(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseOrCharAcquire(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseOrShortAcquire(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseAndChar(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseAndShort(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseAndCharRelease(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseAndShortRelease(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseAndCharAcquire(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseAndShortAcquire(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseXorChar(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseXorShort(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseXorCharRelease(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseXorShortRelease(o, offset, c2s(mask)));
    }

    public final char getAndBitwiseXorCharAcquire(Object o, long offset, char mask)
    {
        return s2c(getAndBitwiseXorShortAcquire(o, offset, c2s(mask)));
    }

    public final short getAndBitwiseOrShort(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShortVolatile(o, offset);
        } while (!weakCompareAndSetShort(o, offset,
                                                current, (short) (current | mask)));
        return current;
    }

    public final short getAndBitwiseOrShortRelease(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortRelease(o, offset,
                                               current, (short) (current | mask)));
        return current;
    }

    public final short getAndBitwiseOrShortAcquire(Object o, long offset, short mask)
    {
        short current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortAcquire(o, offset,
                                               current, (short) (current | mask)));
        return current;
    }

    public final short getAndBitwiseAndShort(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShortVolatile(o, offset);
        } while (!weakCompareAndSetShort(o, offset,
                                                current, (short) (current & mask)));
        return current;
    }

    public final short getAndBitwiseAndShortRelease(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortRelease(o, offset,
                                               current, (short) (current & mask)));
        return current;
    }

    public final short getAndBitwiseAndShortAcquire(Object o, long offset, short mask)
    {
        short current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortAcquire(o, offset,
                                               current, (short) (current & mask)));
        return current;
    }

    public final short getAndBitwiseXorShort(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShortVolatile(o, offset);
        } while (!weakCompareAndSetShort(o, offset,
                                                current, (short) (current ^ mask)));
        return current;
    }

    public final short getAndBitwiseXorShortRelease(Object o, long offset, short mask)
    {
        short current;
        do {
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortRelease(o, offset,
                                               current, (short) (current ^ mask)));
        return current;
    }

    public final short getAndBitwiseXorShortAcquire(Object o, long offset, short mask)
    {
        short current;
        do {
            // Plain read, the value is a hint, the acquire CAS does the work
            current = getShort(o, offset);
        } while (!weakCompareAndSetShortAcquire(o, offset,
                                               current, (short) (current ^ mask)));
        return current;
    }

    // ----- bulk moves -------------------------------------------------------------------------------
    // Byte-granular, so the field/element width question does not arise INSIDE the region -- but a scalar
    // object has no byte-addressable region at all here (its fields are 8-byte slots), so a non-array base
    // is REFUSED rather than silently walking across slots. Every reached caller passes an array or an
    // absolute address: java/lang/StringUTF16 (byte[] to byte[]), jdk/internal/misc/ScopedMemoryAccess,
    // sun/nio/ch/PollSelectorImpl.

    public void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes)
    {
        copyMemoryChecks(srcBase, srcOffset, destBase, destOffset, bytes);
        long s = at(srcBase, srcOffset);
        long d = at(destBase, destOffset);
        if (d > s && d < s + bytes)
        {
            // Overlapping and moving UP: copy downward, or the tail would be overwritten before it is read.
            // Stock's copyMemory0 is a memmove, so this direction choice is part of the contract, not a nicety.
            long i = bytes;
            while (i > 0L)
            {
                i = i - 1L;
                Magic.store8(d + i, Magic.load8(s + i));
            }
            return;
        }
        long i = 0L;
        while (i < bytes)
        {
            Magic.store8(d + i, Magic.load8(s + i));
            i = i + 1L;
        }
    }

    public void copyMemory(long srcAddress, long destAddress, long bytes)
    {
        copyMemory(null, srcAddress, null, destAddress, bytes);
    }

    public void setMemory(Object o, long offset, long bytes, byte value)
    {
        setMemoryChecks(o, offset, bytes, value);
        long a = at(o, offset);
        long i = 0L;
        while (i < bytes)
        {
            Magic.store8(a + i, value);
            i = i + 1L;
        }
    }

    public void setMemory(long address, long bytes, byte value)
    {
        setMemory(null, address, bytes, value);
    }

    // Stock's OWN validation for the bulk moves (Unsafe.java copyMemoryChecks/setMemoryChecks and the
    // checkXxx family), verbatim -- which is how the host control turned out to be the authority here: it
    // showed stock ALREADY refuses a non-primitive-array base with IllegalArgumentException, for the same
    // reason joe-ng must (a scalar object has no byte-addressable region -- its fields are 8-byte slots, so a
    // byte-granular walk across them reads and writes padding as if it were data). Copying the check instead
    // of inventing one turned a divergence into a matching arm.
    //
    // This is a SEPARATE question from the one isArrayRef answers: these validate stock's CONTRACT, while
    // isArrayRef picks the WIDTH of a single narrow access. Different jobs, so different tests -- and only
    // this one may cost a mirror lookup, because a bulk move is not a per-access path.
    private RuntimeException invalidInput()
    {
        return new IllegalArgumentException();
    }

    private boolean is32BitClean(long value)
    {
        return value >>> 32 == 0;
    }

    private void checkSize(long size)
    {
        if (ADDRESS_SIZE == 4)
        {
            if (!is32BitClean(size))
            {
                throw invalidInput();
            }
        }
        else if (size < 0)
        {
            throw invalidInput();
        }
    }

    private void checkNativeAddress(long address)
    {
        if (ADDRESS_SIZE == 4)
        {
            if ((((address >> 32) + 1) & ~1) != 0)
            {
                throw invalidInput();
            }
        }
    }

    private void checkOffset(Object o, long offset)
    {
        if (ADDRESS_SIZE == 4)
        {
            if (!is32BitClean(offset))
            {
                throw invalidInput();
            }
        }
        else if (offset < 0)
        {
            throw invalidInput();
        }
    }

    private void checkPointer(Object o, long offset)
    {
        if (o == null)
        {
            checkNativeAddress(offset);
        }
        else
        {
            checkOffset(o, offset);
        }
    }

    /**
     * Stock is {@code checkPrimitiveArray(o.getClass())}, testing
     * {@code getComponentType() != null && isPrimitive()}. THAT CANNOT BE USED HERE, and the host control is
     * what proved it: {@code Class.getComponentType()} answers NULL for a PRIMITIVE array on this VM (an
     * array Type's element slot is 0 for a primitive element, and the element SIZE cannot recover which
     * primitive it is -- byte[] and boolean[] are both 1), so stock's own check refused every {@code byte[]}.
     * The array Type's element slot is the same fact without the mirror, which is what {@code arrayKind0}
     * reads. Same predicate, same exception, one indirection fewer.
     *
     * <p>PRE-EXISTING AND NOT FIXED HERE, because it is a {@code Class} defect rather than an
     * {@code Unsafe} one: {@code byte[].class.getComponentType()} being null also makes
     * {@link #arrayIndexScale} answer 8 for every primitive array (it falls through its
     * {@code c == null || !c.isPrimitive()} arm), which nothing has noticed only because the
     * {@code ARRAY_*_INDEX_SCALE} constants are assigned directly rather than computed from it. Closing it
     * means giving a primitive array Type a real element Type, which needs the per-atype TIB IDENTITY trick
     * {@code Class.getName} already uses for array names -- its own increment.
     */
    private void checkPrimitivePointer(Object o, long offset)
    {
        checkPointer(o, offset);
        if (o != null && arrayKind0(o) != 1L)
        {
            throw invalidInput();                            // not a primitive array: stock refuses it too
        }
    }

    private void copyMemoryChecks(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes)
    {
        checkSize(bytes);
        checkPrimitivePointer(srcBase, srcOffset);
        checkPrimitivePointer(destBase, destOffset);
    }

    private void setMemoryChecks(Object o, long offset, long bytes, byte value)
    {
        checkPrimitivePointer(o, offset);
        checkSize(bytes);
    }

    /**
     * Stock's native throws {@code ee} without declaring it. The unchecked cast is the standard erasure
     * trick and is exactly as safe: {@code T} erases to {@code Throwable}, so the emitted checkcast is one
     * that always succeeds. Reached from {@code jdk/internal/vm/ScopedValueContainer}.
     */
    public void throwException(Throwable ee)
    {
        sneakyThrow(ee);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T
    {
        throw (T) t;
    }

    // ----- RULE 3: no off-heap memory on bare metal, so these THROW rather than answer ---------------
    // There is no malloc under this VM: the heap is joe-ng's own bump/free-list allocator over a fixed
    // region and nothing hands out C memory. A plausible answer here is the worst available outcome --
    // allocateMemory returning some address would have every later access scribble on whatever lives there
    // -- so each one names itself the moment it is reached, which is what turns "implement everything" into
    // a worklist the program writes for itself.
    //
    // MEASURED, so the throw is a statement about reach and not a shrug: of the 237 members stock declares
    // and this overlay dropped, 182 are referenced ONLY by java/lang/invoke/VarHandle* -- a package this VM
    // denies and shims itself -- and every referrer of the ones below is a subsystem joe-ng deliberately
    // lacks (java/nio direct buffers, jdk/internal/foreign, sun/nio/ch channels).

    public long allocateMemory(long bytes)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.allocateMemory: no off-heap memory on bare metal");
    }

    public long reallocateMemory(long address, long bytes)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.reallocateMemory: no off-heap memory on bare metal");
    }

    public void freeMemory(long address)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.freeMemory: no off-heap memory on bare metal");
    }

    public void copySwapMemory(Object srcBase, long srcOffset, Object destBase, long destOffset,
                               long bytes, long elemSize)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.copySwapMemory: not implemented");
    }

    /** Stock reports whether the CPU has cache-line writeback (CLWB and friends). AArch64 has DC CVAC and
     *  joe-ng drives it directly ({@code Magic.dcCVAC}); what is missing is the mapped-file machinery this
     *  serves, so the truthful answer is that the FEATURE is absent, not that the instruction is. */
    public boolean isWritebackEnabled()
    {
        return false;
    }

    public void writebackMemory(long address, long length)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.writebackMemory: no mapped files on bare metal");
    }

    public void invokeCleaner(java.nio.ByteBuffer directBuffer)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.invokeCleaner: every ByteBuffer here is heap-backed");
    }

    /** Stock answers the OS page size. joe-ng maps flat 1:1 and has no pager, and this is read only to ALIGN
     *  off-heap allocations -- which throw -- so answering would be answering for nobody. */
    public int pageSize()
    {
        throw new InternalError("jdk.internal.misc.Unsafe.pageSize: no pager on bare metal");
    }

    public Object getUncompressedObject(long address)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.getUncompressedObject: VM-internal, not implemented");
    }

    /**
     * Allocate an instance WITHOUT running a constructor. Not a stub: it reuses the native the reflective
     * {@code Constructor.newInstance} path already runs on ({@code Loader.allocInstance} -- zeroed fields, the
     * class's own TIB in the header, and {@code ensureClinit} first, because creating an instance is a JVMS 5.5
     * active use). Registering the SAME address under a second declaring class is how {@code fence0} is
     * already shared.
     *
     * <p>STOCK'S REFUSALS ARE IN ITS NATIVE, so there is no Java body to copy and these four are written out:
     * an array class, a primitive, an interface and an ABSTRACT class all throw {@code InstantiationException},
     * which is the exception stock's own signature declares. The abstract one is the only case
     * {@code Loader.allocInstance} would otherwise serve -- it refuses an interface and an unregistered type by
     * answering 0, and answering a plausible instance of an abstract class is the silent wrong answer rule 3
     * exists to remove. ACC_ABSTRACT is spelt as its JVMS bit rather than through
     * {@code java.lang.reflect.Modifier}, which sits inside the denied reflection tree.
     *
     * <p>Referenced only by {@code java/lang/invoke/DirectMethodHandle}, a denied package -- so this closes
     * the last gap rather than enabling anything.
     */
    public Object allocateInstance(Class<?> cls) throws InstantiationException
    {
        if (cls == null)
        {
            throw new NullPointerException();
        }
        if (cls.isArray() || cls.isPrimitive() || cls.isInterface() || (cls.getModifiers() & 0x0400) != 0)
        {
            throw new InstantiationException(cls.getName());
        }
        Object o = allocInstance0(cls);
        if (o == null)
        {
            throw new InstantiationException(cls.getName());
        }
        return o;
    }

    /** The native {@code Constructor.allocInstance0} already uses; see {@link #allocateInstance}. */
    private static native Object allocInstance0(Class<?> c);

    /**
     * A NO-OP, and that is CORRECT here rather than a stub -- the same argument, and the same wording,
     * {@code MethodHandles.Lookup.ensureInitialized} already carries: joe-ng initializes a class on its
     * first ACTIVE USE (JVMS 5.5), so forcing an initializer a moment earlier changes nothing observable.
     *
     * <p>IT USED TO THROW, on the stated premise that it is "referenced only by java/lang/invoke, which is
     * denied". THAT PREMISE EXPIRED when stock {@code VarHandle} stopped being overlaid: its {@code <clinit>}
     * ends in {@code UNSAFE.ensureClassInitialized(VarHandleGuards.class)}, so the throw became the second
     * of the two reasons that initializer died. A comment recording WHY a stub throws is what let this be
     * noticed rather than re-derived.
     *
     * <p>The argument is IGNORED, and nothing is lost by that: pre-initializing {@code VarHandleGuards} buys
     * nothing here, because the guards are reached only from MethodHandle invocation, which this VM does not
     * do. (An earlier cut of this comment asserted that class was DENIED and its literal therefore null.
     * WRONG, and unchecked: the denial's allow-list carries the prefix {@code java/lang/invoke/VarHandle},
     * which prefix-MATCHES {@code VarHandleGuards} -- and the probe boot's {@code NULL CLASS LITERAL} count
     * of 0 says the literal resolves. A comment is a claim like any other.)
     */
    public void ensureClassInitialized(Class<?> c)
    {
    }

    public boolean shouldBeInitialized(Class<?> c)
    {
        throw new InternalError("jdk.internal.misc.Unsafe.shouldBeInitialized: not implemented");
    }

    /**
     * Stock's contract: block until an {@link #unpark}, an interrupt, or the time runs out. {@code time} is a
     * relative timeout in NANOSECONDS, or with {@code isAbsolute} a deadline in epoch MILLISECONDS; a relative
     * 0 means no timeout, and a non-positive relative time (or a past deadline) returns at once.
     *
     * <p>This used to ignore {@code time} and park until unparked -- so {@code parkNanos(1 s)} could block for
     * ever -- and to delegate to the {@code LockSupport} overlay, which is deleted: stock {@code LockSupport}
     * has no natives and runs on this method, so the timing belongs HERE.
     */
    public void park(boolean isAbsolute, long time)
    {
        if (isAbsolute)
        {
            long ms = time - System.currentTimeMillis();
            if (ms <= 0L)
            {
                return;
            }
            parkNanos0(ms > 9223372036854L ? 9223372036854775807L : ms * 1000000L);
            return;
        }
        if (time == 0L)
        {
            Magic.park();
            return;
        }
        if (time > 0L)
        {
            parkNanos0(time);
        }
    }

    /** A timed park, provided by the VM (see {@code VMScheduler.parkNanos}). */
    private static native void parkNanos0(long nanos);

    public void unpark(Object thread)
    {
        if (thread != null)
        {
            Magic.unpark(thread);
        }
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
