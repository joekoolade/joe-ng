package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/Throwable}: the root of the mini exception hierarchy the JIT
 * synthesises for implicit exceptions (null-deref / array-bounds). Field-free — the metal only needs its
 * Type chain (for {@code instanceof} in catch dispatch), which comes from its TIB, not any instance state.
 * Compiled as a {@code java.base} patch so it carries the real name.
 *
 * <p>The message/cause constructors exist so STOCK exception/error subclasses (which are pulled from java.base
 * and call {@code super(message)}) resolve: e.g. {@code OutOfMemoryError(String)} -> ... -> {@code Error(String)}
 * -> {@code Throwable(String)}. Without them the {@code super} call is unresolved (target 0) and hits the
 * unresolved-call trap. Field-free, so the message is dropped — the metal catches by type, not text; add a
 * {@code detailMessage} field + {@code getMessage()} on demand if a reached path needs the text.
 */
public class Throwable
{
    // Stock Throwable/Error/Exception constructors read this JFR flag: `if (Throwable.jfrTracing)
    // ThrowableTracer.traceError(...)`. jdk/internal/event/ThrowableTracer is never compiled on metal (JFR is
    // off), so the guard MUST see `false` -- declared here so the cross-class getstatic resolves to a real
    // (default-0 = false) slot instead of a garbage offset that reads non-zero and takes the trace branch,
    // which then hits the unresolved-call trap. Never set true.
    static boolean jfrTracing;

    public Throwable()
    {
    }

    public Throwable(String message)
    {
    }

    public Throwable(String message, Throwable cause)
    {
    }

    public Throwable(Throwable cause)
    {
    }
}
