package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Boolean} overlay. The stock {@code valueOf} returns the {@code TRUE}/
 * {@code FALSE} singletons set in a {@code <clinit>} the loader blocks (the wrapper also assigns a native
 * TYPE), so those statics are null. This overlay boxes directly and has no {@code <clinit>}.
 */
public final class Boolean implements Comparable<Boolean>
{

    /**
     * {@code boolean.class}. javac compiles a primitive class literal to {@code getstatic Boolean.TYPE}, so this field
     * is what makes it work -- and a name-winning overlay silently drops it unless it is declared here.
     *
     * <p>Deliberately NOT {@code final} and deliberately UNINITIALIZED: the VM fills it in
     * ({@code Loader.seedPrimitiveTypes}) because the writer cannot bake it -- the seed JVM's value is a host
     * {@code java.lang.Class} with no image representation. An initializer would also run in {@code <clinit>}
     * AFTER the seeding and null it back out.
     */
    public static Class<Boolean> TYPE;
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

    /**
     * {@code true} iff the system property {@code name} exists and equals "true", ignoring case -- the stock
     * contract, including returning false (never throwing) for a null, empty or absent name.
     *
     * <p>Present because a NAME-WINNING OVERLAY SILENTLY DROPS whatever it does not declare, and the call then
     * resolves nowhere and ends in a denylist trap. That is how JUnit's picocli died here: its
     * {@code loadClosureClass} opens with {@code Boolean.getBoolean("...disable.closures")}. Stock library code
     * reaches these "small" statics on ordinary paths, and the same trap has now been paid for
     * StringBuilder/Appendable, Class.getPrimitiveClass, the wrappers' TYPE, Throwable.initCause and
     * Character.toString.
     */
    public static boolean getBoolean(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        String v = System.getProperty(name);
        return parseBoolean(v);
    }

    /** {@code true} iff {@code s} equals "true" ignoring case; false for null, per the stock contract. */
    public static boolean parseBoolean(String s)
    {
        return s != null && s.equalsIgnoreCase("true");
    }

    public static String toString(boolean b)
    {
        return b ? "true" : "false";
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
