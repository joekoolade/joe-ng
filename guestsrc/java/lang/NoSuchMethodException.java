package java.lang;

/** Mini {@code java/lang/NoSuchMethodException} — thrown by {@link Class#getDeclaredMethod}/{@code getMethod}
 *  and {@code getDeclaredConstructor} when no matching member exists. */
public class NoSuchMethodException extends ReflectiveOperationException
{
    public NoSuchMethodException()
    {
    }

    public NoSuchMethodException(String message)
    {
        super(message);
    }
}
