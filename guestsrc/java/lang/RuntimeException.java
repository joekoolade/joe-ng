package java.lang;

/** Mini {@code java/lang/RuntimeException} — super of the implicit exceptions the JIT throws. */
public class RuntimeException extends Exception
{
    public RuntimeException()
    {
    }

    public RuntimeException(String message)
    {
        super(message);
    }
}
