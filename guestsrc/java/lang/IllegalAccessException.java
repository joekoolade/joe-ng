package java.lang;

/** Mini {@code java/lang/IllegalAccessException} — thrown by reflective {@code Field}/{@code Method}/
 *  {@code Constructor} access that violates Java member-access rules without {@code setAccessible(true)}. */
public class IllegalAccessException extends ReflectiveOperationException
{
    public IllegalAccessException()
    {
    }

    public IllegalAccessException(String message)
    {
        super(message);
    }
}
