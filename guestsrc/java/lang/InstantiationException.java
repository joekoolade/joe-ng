package java.lang;

/** Mini {@code java/lang/InstantiationException} — thrown by {@code Constructor.newInstance} when the instance
 *  cannot be allocated. */
public class InstantiationException extends ReflectiveOperationException
{
    public InstantiationException()
    {
    }

    public InstantiationException(String message)
    {
        super(message);
    }
}
