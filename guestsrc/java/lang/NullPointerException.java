package java.lang;

/** Mini {@code java/lang/NullPointerException} — the JIT synthesises one on a null deref (VM.newNpe). */
public class NullPointerException extends RuntimeException
{
    public NullPointerException()
    {
    }
}
