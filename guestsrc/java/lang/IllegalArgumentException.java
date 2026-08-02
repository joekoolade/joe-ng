package java.lang;

/** Mini {@code java/lang/IllegalArgumentException} — {@link NumberFormatException}'s superclass. */
public class IllegalArgumentException extends RuntimeException
{
    public IllegalArgumentException()
    {
    }

    public IllegalArgumentException(String message)
    {
        super(message);
    }
}
