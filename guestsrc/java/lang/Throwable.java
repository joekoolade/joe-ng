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

    // The detail message. MUST come AFTER bt0..bt7 (VM.unwind / VM.printStackTrace hardcode the backtrace at
    // obj+16..+72); this lands at obj+80, which VM.printStackTrace reads to append ": <message>".
    private String detailMessage;

    public Throwable()
    {
    }

    public Throwable(String message)
    {
        detailMessage = message;
    }

    public Throwable(String message, Throwable cause)
    {
        detailMessage = message;
        this.cause = cause;
    }

    public Throwable(Throwable cause)
    {
        detailMessage = cause == null ? null : cause.toString();
        this.cause = cause;
    }

    /**
     * The cause, and the two methods that reach it.
     *
     * <p>The constructors above USED to take a cause and silently drop it, with neither accessor declared --
     * so `initCause` was not in the vtable at all. Stock code that chains exceptions then dispatched through
     * an empty slot: `org.opentest4j.AssertionFailedError.<init>` calls `initCause`, which is why every JUnit
     * assertion failure died before its message could be read. javac reports this at build time
     * ("no virtual method initCause... in java/lang/AssertionError") and it had been scrolling past in the
     * bake-stub lines for some time.
     *
     * <p>`cause == this` is stock's "not yet initialised" sentinel, kept so getCause() answers null for an
     * exception that never had one.
     */
    public Throwable initCause(Throwable cause)
    {
        this.cause = cause;
        return this;
    }

    public Throwable getCause()
    {
        return cause == this ? null : cause;
    }

    /**
     * The stack trace, materialised from the inline backtrace {@link #printStackTrace} already uses.
     *
     * <p>joe-ng captures the backtrace at THROW time rather than at construction (so propagation allocates
     * nothing), which means an exception built but not yet thrown reports an EMPTY trace. That is what stock
     * code that walks the array wants -- it loops and finds nothing -- rather than a null, which is what the
     * absence of this method used to produce: the call landed on an empty vtable slot and surfaced as a bare
     * ArrayIndexOutOfBoundsException from the dispatch guard, three frames from anything relevant.
     */
    public StackTraceElement[] getStackTrace()
    {
        if (stackTrace != null)
        {
            return stackTrace;
        }
        return stackTrace0(this);
    }

    /** Replace the trace. Stock library code does this to trim frames (JUnit's assertion builder, for one). */
    public void setStackTrace(StackTraceElement[] trace)
    {
        stackTrace = trace;
    }

    /** VM native ({@code Loader.nativeBuf} -> {@code VM.throwableTrace}): inline backtrace -> StackTraceElement[]. */
    private static native StackTraceElement[] stackTrace0(Throwable t);

    /** The detail message string, or {@code null}. */
    /**
     * Suppressed exceptions. javac lowers every try-with-resources into a call to {@code addSuppressed},
     * so without these a guest t-w-r whose body AND close() both throw has no method to resolve — this is
     * part of the language, not just of the Throwable API.
     */
    // Declared here, i.e. after detailMessage: VM.unwind / VM.printStackTrace hardcode the backtrace at
    // obj+16..+72 and the message at obj+80, so nothing may be inserted ahead of them.
    private Throwable cause = this;                  // stock's "not yet initialised" sentinel

    private Throwable[] suppressed;
    private int suppressedCount;

    // Declared LAST on purpose: VM.unwind / VM.printStackTrace hardcode bt0..bt7 at obj+16..+72 and
    // detailMessage at +80, so any new field must land after those.
    private StackTraceElement[] stackTrace;   // non-null only once setStackTrace has overridden the inline one

    public final void addSuppressed(Throwable exception)
    {
        if (exception == this)
        {
            throw new IllegalArgumentException("self-suppression");
        }
        if (exception == null)
        {
            throw new NullPointerException();
        }
        if (suppressed == null)
        {
            suppressed = new Throwable[4];
        }
        if (suppressedCount == suppressed.length)
        {
            Throwable[] bigger = new Throwable[suppressed.length * 2];
            int k = 0;
            while (k < suppressedCount)
            {
                bigger[k] = suppressed[k];
                k = k + 1;
            }
            suppressed = bigger;
        }
        suppressed[suppressedCount] = exception;
        suppressedCount = suppressedCount + 1;
    }

    public final Throwable[] getSuppressed()
    {
        Throwable[] out = new Throwable[suppressedCount];
        int k = 0;
        while (k < suppressedCount)
        {
            out[k] = suppressed[k];
            k = k + 1;
        }
        return out;
    }

    public String getMessage()
    {
        return detailMessage;
    }

    /** Print this throwable's class and captured stack frames to the UART (metal-friendly printStackTrace). */
    public void printStackTrace()
    {
        printStackTrace0(this);
    }

    /** {@code printStackTrace(PrintStream)}: the metal has one sink (the UART), so the stream is ignored. */
    public void printStackTrace(java.io.PrintStream s)
    {
        printStackTrace0(this);
    }

    /** VM native (wired in {@code Loader.nativeBuf}): formats the captured backtrace of {@code t}. STATIC so the
     *  call is an {@code invokestatic} resolved via nativeBuf -- a private INSTANCE native would be dispatched
     *  through an (empty) vtable slot and trip the metal's null-vtable guard. */
    private static native void printStackTrace0(Throwable t);
}
