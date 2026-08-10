package java.lang.invoke;

/**
 * Minimal name-winning {@code java.lang.invoke.MethodHandles} overlay for joe-ng. The full class fronts the
 * MethodHandle runtime (denied on metal). The only use on the socket path is
 * {@code java.net.Socket.<clinit>}: {@code MethodHandles.lookup()} whose result is handed to
 * {@code MhUtil.findVarHandle} (see the MhUtil overlay), which ignores it -- so {@code lookup()} can return
 * null and {@link Lookup} need only exist as a type.
 */
public final class MethodHandles
{
    private MethodHandles()
    {
    }

    public static Lookup lookup()
    {
        return null;   // ignored by the MhUtil.findVarHandle overlay (which binds by field name only)
    }

    /** Marker type: the overlay never dereferences a Lookup. */
    public static final class Lookup
    {
        private Lookup()
        {
        }
    }
}
