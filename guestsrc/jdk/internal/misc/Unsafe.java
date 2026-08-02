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
