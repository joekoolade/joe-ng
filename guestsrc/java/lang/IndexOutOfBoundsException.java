package java.lang;

/** Mini {@code java/lang/IndexOutOfBoundsException} — super of {@link ArrayIndexOutOfBoundsException}. */
public class IndexOutOfBoundsException extends RuntimeException
{
    public IndexOutOfBoundsException()
    {
    }

    public IndexOutOfBoundsException(String s)
    {
        super(s);
    }

    public IndexOutOfBoundsException(int index)
    {
        super("Index out of range: " + index);
    }
}
