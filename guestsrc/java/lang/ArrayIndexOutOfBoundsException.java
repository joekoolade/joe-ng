package java.lang;

/** Mini {@code java/lang/ArrayIndexOutOfBoundsException} — the JIT synthesises one on a bad array index (VM.newAioobe). */
public class ArrayIndexOutOfBoundsException extends IndexOutOfBoundsException
{
    public ArrayIndexOutOfBoundsException()
    {
    }

    public ArrayIndexOutOfBoundsException(String s)
    {
        super(s);
    }

    public ArrayIndexOutOfBoundsException(int index)
    {
        super("Array index out of range: " + index);
    }
}
