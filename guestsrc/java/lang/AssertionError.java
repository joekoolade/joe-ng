package java.lang;

/** Mini {@code java/lang/AssertionError} — thrown by the JUnit-lite assertions on failure. */
public class AssertionError extends Error
{
    public AssertionError()
    {
    }

    public AssertionError(String message)
    {
        super(message);
    }
}
