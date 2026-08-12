package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Boolean} overlay. The stock {@code valueOf} returns the {@code TRUE}/
 * {@code FALSE} singletons set in a {@code <clinit>} the loader blocks (the wrapper also assigns a native
 * TYPE), so those statics are null. This overlay boxes directly and has no {@code <clinit>}.
 */
public final class Boolean implements Comparable<Boolean>
{
    public static final Boolean TRUE = new Boolean(true);
    public static final Boolean FALSE = new Boolean(false);

    private final boolean value;

    public Boolean(boolean v)
    {
        this.value = v;
    }

    public static Boolean valueOf(boolean b)
    {
        return b ? TRUE : FALSE;
    }

    public boolean booleanValue()
    {
        return value;
    }

    public static int compare(boolean x, boolean y)
    {
        return (x == y) ? 0 : (x ? 1 : -1);
    }

    public int compareTo(Boolean other)
    {
        return compare(this.value, other.value);
    }

    public boolean equals(Object o)
    {
        return o instanceof Boolean && ((Boolean) o).value == value;
    }

    public int hashCode()
    {
        return value ? 1231 : 1237;
    }

    public String toString()
    {
        return value ? "true" : "false";
    }
}
