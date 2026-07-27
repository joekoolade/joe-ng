package java.lang;

/**
 * A real-shaped, JDK-free {@link java.lang.String}: a {@code byte[] value} (LATIN1/ASCII — no coder) plus
 * the methods real code (and now the mini {@link StringBuilder}) actually calls. Kept {@code value} the
 * sole/first field (offset 16), which the JIT's {@code newStringFromBytes} / interned string literals /
 * {@code VM.strBytes} all assume. Compiled as a {@code java.base} patch so it carries the real name;
 * embedded + loaded like the rest of the mini java.base.
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

    public char charAt(int i)
    {
        return (char) (value[i] & 0xFF);
    }

    public boolean isEmpty()
    {
        return value.length == 0;
    }

    public int hashCode()
    {
        int h = 0;
        int i = 0;
        while (i < value.length)
        {
            h = 31 * h + (value[i] & 0xFF);         // the real String.hashCode recurrence (ASCII)
            i = i + 1;
        }
        return h;
    }

    public boolean equals(Object o)
    {
        if (!(o instanceof String))
        {
            return false;
        }
        String s = (String) o;
        if (s.value.length != value.length)
        {
            return false;
        }
        int i = 0;
        while (i < value.length)
        {
            if (value[i] != s.value[i])
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }
}
