package java.lang;

/** Mini {@code java/lang/NoSuchFieldException} — thrown by {@link Class#getField}/{@code getDeclaredField} when
 *  no matching field exists (or none that is accessible for the {@code getField} public-only lookup). */
public class NoSuchFieldException extends ReflectiveOperationException
{
    public NoSuchFieldException()
    {
    }

    public NoSuchFieldException(String message)
    {
        super(message);
    }
}
