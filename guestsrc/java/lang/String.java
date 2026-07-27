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

    /** Index of the first {@code ch} (LATIN1), or -1. */
    public int indexOf(int ch)
    {
        int i = 0;
        while (i < value.length)
        {
            if ((value[i] & 0xFF) == ch)
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    /** Index of the first occurrence of {@code str}, or -1 (a plain LATIN1 substring search). */
    public int indexOf(String str)
    {
        int n = str.value.length;
        if (n == 0)
        {
            return 0;
        }
        int last = value.length - n;
        int i = 0;
        while (i <= last)
        {
            int j = 0;
            while (j < n && value[i + j] == str.value[j])
            {
                j = j + 1;
            }
            if (j == n)
            {
                return i;
            }
            i = i + 1;
        }
        return -1;
    }

    public boolean startsWith(String prefix)
    {
        int n = prefix.value.length;
        if (n > value.length)
        {
            return false;
        }
        int i = 0;
        while (i < n)
        {
            if (value[i] != prefix.value[i])
            {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    /** Lexicographic (LATIN1): first differing char's difference, else the length difference. */
    public int compareTo(String other)
    {
        int lim = value.length < other.value.length ? value.length : other.value.length;
        int i = 0;
        while (i < lim)
        {
            int a = value[i] & 0xFF;
            int b = other.value[i] & 0xFF;
            if (a != b)
            {
                return a - b;
            }
            i = i + 1;
        }
        return value.length - other.value.length;
    }

    public String substring(int begin)
    {
        return substring(begin, value.length);
    }

    /** {@code [begin, end)} as a fresh LATIN1 String (byte[]+coder). */
    public String substring(int begin, int end)
    {
        int len = end - begin;
        byte[] buf = new byte[len];
        int i = 0;
        while (i < len)
        {
            buf[i] = value[begin + i];
            i = i + 1;
        }
        return new String(buf, coder);
    }
}
