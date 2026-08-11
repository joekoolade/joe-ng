package java.lang;

/** Mini {@code java/lang/Error} — super of AssertionError (the JUnit-lite assertions throw it). */
public class Error extends Throwable
{
    public Error()
    {
    }

    public Error(String message)
    {
        super(message);
    }

    public Error(String message, Throwable cause)
    {
        super(message, cause);
    }
}
