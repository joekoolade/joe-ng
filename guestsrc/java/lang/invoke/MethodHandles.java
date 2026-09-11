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

    /**
     * A SINGLETON rather than null now. It was null because the only caller ({@code MhUtil.findVarHandle})
     * ignores it -- but {@code jdk.internal.access.SharedSecrets.ensureClassInitialized} does
     * {@code lookup().ensureInitialized(c)}, and a null receiver there is an NPE inside java.base with no
     * useful frame. The instance costs one object for the life of the VM.
     */
    public static Lookup lookup()
    {
        return Lookup.INSTANCE;
    }

    /** Carries only what reached code calls; the overlay never dereferences a Lookup for MethodHandle work. */
    public static final class Lookup
    {
        static final Lookup INSTANCE = new Lookup();

        private Lookup()
        {
        }

        /**
         * A no-op returning {@code c}, and that is CORRECT here rather than a stub: joe-ng runs a class's
         * {@code <clinit>} on first ACTIVE USE, and every caller of this follows it immediately with a
         * {@code getstatic} on that same class -- which is an active use and triggers the initializer through
         * {@code noteInitNeeded}. Forcing it a moment earlier would change nothing observable.
         *
         * <p>Declared because stock {@code SharedSecrets} calls it before reading EVERY access shim, so
         * without it the first `getJavaXxxAccess()` traps -- and it catches only IllegalAccessException, which
         * a denylist trap is not.
         */
        public Class<?> ensureInitialized(Class<?> c)
        {
            return c;
        }

        /**
         * Bind a VarHandle to an instance field BY NAME, the same thing {@code MhUtil.findVarHandle} does.
         *
         * <p>Declared on Lookup itself because java.util.concurrent calls it DIRECTLY --
         * {@code ConcurrentLinkedQueue.<clinit>} does {@code l.findVarHandle(CLQ.class, "head", Node.class)}
         * -- whereas {@code java/net/Socket} reaches the same binding through MhUtil. Without it the
         * initializer traps, every handle stays null, and the first {@code offer()} NPEs in
         * {@code Node.<init>}, which is where the console launcher stopped.
         *
         * <p>The receiver and type Classes are IGNORED, as they are in MhUtil: this shim resolves the field's
         * offset from the TARGET OBJECT's class at access time (see the VarHandle overlay), so the declaring
         * class is not needed here and the field type is carried by the accessor that is called.
         */
        public VarHandle findVarHandle(Class<?> recv, String name, Class<?> type)
        {
            return VarHandle.ofField(name.getBytes());
        }
    }
}
