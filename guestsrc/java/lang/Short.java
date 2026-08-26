package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Short} overlay. The stock class's {@code valueOf} reads a nested
 * {@code ShortCache} array, and its {@code <clinit>} sets {@code TYPE = Class.getPrimitiveClass("short")} (a
 * native) so the loader blocks it -- leaving the cache null (NPE). This overlay drops the cache
 * ({@code valueOf} just boxes) and has no {@code <clinit>} (MIN/MAX are inlined constants).
 */
public final class Short extends Number implements Comparable<Short>
{
    public static final short MIN_VALUE = -32768;
    public static final short MAX_VALUE = 32767;
    public static final int SIZE = 16;
    public static final int BYTES = SIZE / Byte.SIZE;

    private final short value;

    public Short(short v)
    {
        this.value = v;
    }

    public static Short valueOf(short s)
    {
        return new Short(s);
    }

    public short shortValue()
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

    public static int compare(short x, short y)
    {
        return x - y;
    }

    public int compareTo(Short other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Short && ((Short) o).value == value;
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
