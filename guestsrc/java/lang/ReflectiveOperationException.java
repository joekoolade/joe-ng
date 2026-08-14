package java.lang;

/** Mini {@code java/lang/ReflectiveOperationException} — common supertype of the reflection checked
 *  exceptions ({@link ClassNotFoundException}, {@code NoSuchFieldException}, ...). Kept so reflection code that
 *  catches the family supertype works on metal. */
public class ReflectiveOperationException extends Exception
{
    public ReflectiveOperationException()
    {
    }

    public ReflectiveOperationException(String message)
    {
        super(message);
    }

    public ReflectiveOperationException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public ReflectiveOperationException(Throwable cause)
    {
        super(cause);
    }
}
