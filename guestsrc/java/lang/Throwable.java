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

    // Inline backtrace: up to 8 frame code-addresses (0-terminated), captured at THROW time by {@code VM.unwind}
    // (which already walks the frame chain to search for a handler). Kept as inline longs -- NOT a {@code long[]}
    // -- so the VM fills them without allocating an array during exception propagation. {@code printStackTrace()}
    // names each PC's method over the UART via {@code Loader.reportMethodAt}, reusing the unwind frame-walk. These
    // are the first instance fields, so a Throwable's backtrace lives at object offsets 16..72 (header is 16).
    long bt0, bt1, bt2, bt3, bt4, bt5, bt6, bt7;

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

    /** Print this throwable's class and captured stack frames to the UART (metal-friendly printStackTrace). */
    public void printStackTrace()
    {
        printStackTrace0(this);
    }

    /** VM native (wired in {@code Loader.nativeBuf}): formats the captured backtrace of {@code t}. STATIC so the
     *  call is an {@code invokestatic} resolved via nativeBuf -- a private INSTANCE native would be dispatched
     *  through an (empty) vtable slot and trip the metal's null-vtable guard. */
    private static native void printStackTrace0(Throwable t);
}
