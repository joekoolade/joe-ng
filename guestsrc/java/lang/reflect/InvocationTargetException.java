package java.lang.reflect;

/** Mini {@code java/lang/reflect/InvocationTargetException} — wraps an exception thrown by a reflectively
 *  invoked {@code Method}/{@code Constructor}. (The metal invoke path currently lets the target's exception
 *  propagate directly; kept for the {@code Method.invoke} signature and future wrapping.) */
public class InvocationTargetException extends ReflectiveOperationException
{
    private final Throwable target;

    public InvocationTargetException()
    {
        this.target = null;
    }

    public InvocationTargetException(Throwable target)
    {
        super(target);
        this.target = target;
    }

    public Throwable getTargetException()
    {
        return target;
    }

    public Throwable getCause()
    {
        return target;
    }
}
