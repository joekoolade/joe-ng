package java.lang;

/**
 * A real-shaped, JDK-free {@link java.lang.String}: a {@code byte[] value} + a {@code byte coder} (the real
 * String's two fields; LATIN1 = 0, all our strings are LATIN1/ASCII), plus the methods real code (and the
 * mini {@link StringBuilder}) actually calls, and the real byte[]+coder constructor / factory that real
 * {@code Integer.toString} builds its result with. {@code value} stays the FIRST field (offset 16), which
 * the JIT's {@code newStringFromBytes} / interned literals / {@code VM.strBytes} all assume; {@code coder}
 * is the second (offset 24, zero = LATIN1). Compiled as a {@code java.base} patch so it carries the real name.
 */
public final class String
{
    private final byte[] value;
    private final byte coder;

    public String(byte[] v)
    {
        value = v;
        coder = 0;                                      // LATIN1
    }

    /** The real {@code String(byte[], byte)} constructor: {@code newStringWithLatin1Bytes} / toString use it. */
    public String(byte[] v, byte c)
    {
        value = v;
        coder = c;
    }

    /** The real factory {@code Integer.toString} calls to wrap its LATIN1 digit buffer as a String. */
    static String newStringWithLatin1Bytes(byte[] v)
    {
        return new String(StringLatin1.newBytes(v), (byte) 0);
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
