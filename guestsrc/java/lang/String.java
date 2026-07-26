package java.lang;

/**
 * joe-ng's minimal, JDK-free {@link java.lang.String} — enough to be the result type of string
 * concatenation. A single {@code byte[] value} field (kept first, so it sits at the fixed object offset
 * 16 the JIT's {@code newStringFromBytes} and {@code VM.printStr} assume). Compiled as a {@code java.base}
 * patch so it carries the real {@code java/lang/String} name, embedded as a blob, and loaded on the metal
 * before any concat compiles (the concat's {@code newStringFromBytes} needs its TIB). Real
 * {@code java.lang.String} is thousands of lines with natives + its own invokedynamic — this stands in.
 */
public final class String
{
    private final byte[] value;

    public String(byte[] v)
    {
        value = v;
    }

    public int length()
    {
        return value.length;
    }

    public byte byteAt(int i)
    {
        return value[i];
    }
}
