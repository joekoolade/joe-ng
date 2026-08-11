package java.lang;

/** Mini {@code java/lang/Exception} — catch-target of the demo (catches both NPE and AIOOBE). */
public class Exception extends Throwable
{
    public Exception()
    {
    }

    public Exception(String message)
    {
        super(message);
    }

    public Exception(String message, Throwable cause)
    {
        super(message, cause);
    }

    public Exception(Throwable cause)
    {
        super(cause);
    }
}
