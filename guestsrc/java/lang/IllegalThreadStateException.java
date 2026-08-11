package java.lang;

/** Mini {@code java/lang/IllegalThreadStateException} — thrown by Thread.join(Duration) on an unstarted thread. */
public class IllegalThreadStateException extends RuntimeException
{
    public IllegalThreadStateException()
    {
    }

    public IllegalThreadStateException(String message)
    {
        super(message);
    }
}
