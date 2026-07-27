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

    /** Strip leading/trailing chars {@code <= ' '} (the real {@code trim} bound), returning a fresh String. */
    public String trim()
    {
        int len = value.length;
        int st = 0;
        while (st < len && (value[st] & 0xFF) <= ' ')
        {
            st = st + 1;
        }
        while (st < len && (value[len - 1] & 0xFF) <= ' ')
        {
            len = len - 1;
        }
        if (st == 0 && len == value.length)
        {
            return this;
        }
        return substring(st, len);
    }

    /** Every {@code oldChar} replaced by {@code newChar} (LATIN1); {@code this} if none occur. */
    public String replace(char oldChar, char newChar)
    {
        int i = 0;
        while (i < value.length && (value[i] & 0xFF) != oldChar)
        {
            i = i + 1;
        }
        if (i == value.length)
        {
            return this;                                // no occurrence -> unchanged
        }
        byte[] buf = new byte[value.length];
        int j = 0;
        while (j < value.length)
        {
            int c = value[j] & 0xFF;
            buf[j] = (byte) (c == oldChar ? newChar : c);
            j = j + 1;
        }
        return new String(buf, coder);
    }

    /** True if {@code delim} (LATIN1) matches this string starting at {@code off}. */
    private boolean regionMatches(int off, String delim, int len)
    {
        int j = 0;
        while (j < len)
        {
            if (value[off + j] != delim.value[j])
            {
                return false;
            }
            j = j + 1;
        }
        return true;
    }

    /**
     * Split around the literal delimiter {@code delim} (NOT a regex -- the common plain-string case), with
     * trailing empty strings removed (the real {@code split(regex)} = limit-0 semantics). Returns a fresh
     * {@code String[]}.
     */
    public String[] split(String delim)
    {
        int dlen = delim.value.length;
        int max = value.length - dlen;
        int count = 0;                                  // pass 1: count non-overlapping occurrences
        int i = 0;
        while (i <= max)
        {
            if (regionMatches(i, delim, dlen))
            {
                count = count + 1;
                i = i + dlen;
            }
            else
            {
                i = i + 1;
            }
        }
        String[] parts = new String[count + 1];         // pass 2: fill segments
        int idx = 0;
        int start = 0;
        i = 0;
        while (i <= max)
        {
            if (regionMatches(i, delim, dlen))
            {
                parts[idx] = substring(start, i);
                idx = idx + 1;
                i = i + dlen;
                start = i;
            }
            else
            {
                i = i + 1;
            }
        }
        parts[idx] = substring(start, value.length);
        idx = idx + 1;
        while (idx > 0 && parts[idx - 1].value.length == 0)   // drop trailing empties (limit 0)
        {
            idx = idx - 1;
        }
        if (idx == parts.length)
        {
            return parts;
        }
        String[] result = new String[idx];
        int k = 0;
        while (k < idx)
        {
            result[k] = parts[k];
            k = k + 1;
        }
        return result;
    }

    /**
     * Join {@code elements} with {@code delimiter} between each (the real {@code String.join}, but over our
     * mini {@code String} rather than {@code CharSequence}). Varargs, so it takes both a literal element list
     * and a {@code String[]} -- e.g. round-trips a {@link #split}. Static, so it builds a LATIN1 result directly.
     */
    public static String join(String delimiter, String... elements)
    {
        int n = elements.length;
        if (n == 0)
        {
            return "";
        }
        int total = delimiter.value.length * (n - 1);
        int i = 0;
        while (i < n)
        {
            total = total + elements[i].value.length;
            i = i + 1;
        }
        byte[] buf = new byte[total];
        int pos = 0;
        i = 0;
        while (i < n)
        {
            if (i > 0)
            {
                int d = 0;
                while (d < delimiter.value.length)
                {
                    buf[pos] = delimiter.value[d];
                    pos = pos + 1;
                    d = d + 1;
                }
            }
            byte[] ev = elements[i].value;
            int k = 0;
            while (k < ev.length)
            {
                buf[pos] = ev[k];
                pos = pos + 1;
                k = k + 1;
            }
            i = i + 1;
        }
        return new String(buf, (byte) 0);               // LATIN1
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
