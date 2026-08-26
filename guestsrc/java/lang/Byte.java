package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Byte} overlay -- like {@link Short}, the stock {@code valueOf} reads a
 * nested {@code ByteCache} that never initializes on metal (the wrapper's {@code <clinit>} sets a native TYPE
 * and is blocked). Cache-free {@code valueOf}; no {@code <clinit>} (MIN/MAX inlined).
 */
public final class Byte extends Number implements Comparable<Byte>
{
    public static final byte MIN_VALUE = -128;
    public static final byte MAX_VALUE = 127;
    public static final int SIZE = 8;
    public static final int BYTES = SIZE / Byte.SIZE;

    private final byte value;

    public Byte(byte v)
    {
        this.value = v;
    }

    public static Byte valueOf(byte b)
    {
        return new Byte(b);
    }

    public byte byteValue()
    {
        return value;
    }

    public int intValue()
    {
        return value;
    }

    public long longValue()
    {
        return value;
    }

    public float floatValue()
    {
        return value;
    }

    public double doubleValue()
    {
        return value;
    }

    public static int compare(byte x, byte y)
    {
        return x - y;
    }

    /** Zero-extend a byte to an int (0..255). Reached by {@code String.hashCode} of a length>=2 string via
     *  {@code ArraysSupport.unsignedHashCode} (the vectorized-hash leaf) — stock {@code Byte} has it, so the
     *  JDK-free overlay must too or that path traps. */
    public static int toUnsignedInt(byte x)
    {
        return ((int) x) & 0xff;
    }

    /** Zero-extend a byte to a long (0..255). */
    public static long toUnsignedLong(byte x)
    {
        return ((long) x) & 0xffL;
    }

    public int compareTo(Byte other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Byte && ((Byte) o).value == value;
    }

    public int hashCode()
    {
        return value;
    }

    public String toString()
    {
        return Integer.toString(value);
    }
}
