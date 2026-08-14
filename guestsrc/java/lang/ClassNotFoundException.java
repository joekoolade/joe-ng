package java.lang;

/** Mini {@code java/lang/ClassNotFoundException} — thrown by {@link Class#forName(String)} when the named
 *  class cannot be located (not embedded, or an invalid binary name). Real hierarchy: extends
 *  {@link ReflectiveOperationException} extends {@link Exception}. */
public class ClassNotFoundException extends ReflectiveOperationException
{
    public ClassNotFoundException()
    {
    }

    public ClassNotFoundException(String message)
    {
        super(message);
    }

    public ClassNotFoundException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
