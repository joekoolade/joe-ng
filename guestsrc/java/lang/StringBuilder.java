package java.lang;

/**
 * A real-shaped, JDK-free {@link java.lang.StringBuilder}: a {@code byte[]} + count, with the common
 * {@code append} overloads and {@code toString} (which builds a real {@link String} via the provided
 * {@code System.arraycopy} native). Fixed initial capacity (grown lazily) — enough for demand-loaded
 * demos. Compiled as a {@code java.base} patch.
 */
public final class StringBuilder implements Appendable
{
    private byte[] value;
    private int count;

    public StringBuilder()
    {
        value = new byte[64];
        count = 0;
    }

    /**
     * The stock constructors. {@code capacity} is honoured as a starting size (never below 16, so a caller
     * passing 0 does not force a grow on the first character); the {@code String}/{@code CharSequence} forms
     * seed the contents, as stock does.
     *
     * <p>Declared because a name-winning overlay silently drops what it does not declare, and the call then
     * resolves NOWHERE and surfaces as a DENYLIST TRAP. All three were listed by {@code make overlaycheck} --
     * {@code new StringBuilder(String)} in particular is one of the most common shapes in the whole library.
     */
    public StringBuilder(int capacity)
    {
        value = new byte[capacity < 16 ? 16 : capacity];
        count = 0;
    }

    public StringBuilder(String s)
    {
        value = new byte[(s == null ? 4 : s.length()) + 16];
        count = 0;
        append(s);
    }

    public StringBuilder(CharSequence cs)
    {
        value = new byte[(cs == null ? 4 : cs.length()) + 16];
        count = 0;
        append(cs);
    }

    private void put(int b)
    {
        if (count >= value.length)
        {
            byte[] nv = new byte[value.length * 2];
            System.arraycopy(value, 0, nv, 0, count);
            value = nv;
        }
        value[count] = (byte) b;
        count = count + 1;
    }

    public StringBuilder append(char c)
    {
        put(c);
        return this;
    }

    public StringBuilder append(String s)
    {
        if (s == null)
        {
            s = "null";
        }
        int n = s.length();
        int i = 0;
        while (i < n)
        {
            put(s.charAt(i));
            i = i + 1;
        }
        return this;
    }

    /** Append any object's string form. Reached by stock code that appends a K/V/Object -- e.g.
     *  {@code AbstractMap.toString} does {@code sb.append(key)}; without this overload the call hit a
     *  mismatched vtable slot and appended nothing (Map.toString printed "{=}"). */
    public StringBuilder append(Object o)
    {
        return append(String.valueOf(o));
    }

    public StringBuilder append(CharSequence cs)
    {
        return append(cs == null ? "null" : cs.toString());
    }

    /** {@code Appendable}'s three-arg form. Stock {@code Matcher.appendExpandedReplacement} reaches for it
     *  on the {@code $n} group path, and implementing {@code Appendable} obliges us to have it. */
    public StringBuilder append(CharSequence cs, int start, int end)
    {
        CharSequence s = cs == null ? "null" : cs;
        int i = start;
        while (i < end)
        {
            put(s.charAt(i));
            i = i + 1;
        }
        return this;
    }

    public StringBuilder append(long v)
    {
        return append(Long.toString(v));
    }

    public StringBuilder append(int v)
    {
        if (v == 0)
        {
            put('0');
            return this;
        }
        if (v < 0)
        {
            put('-');
            v = -v;
        }
        int start = count;
        while (v > 0)
        {
            put('0' + v % 10);
            v = v / 10;
        }
        int lo = start;
        int hi = count - 1;
        while (lo < hi)                                 // reverse the digits written in [start, count)
        {
            byte t = value[lo];
            value[lo] = value[hi];
            value[hi] = t;
            lo = lo + 1;
            hi = hi - 1;
        }
        return this;
    }

    public StringBuilder append(boolean b)
    {
        return append(b ? "true" : "false");
    }

    public int length()
    {
        return count;
    }

    /** Reverse the characters in place (bytes are Latin1 units; the demos build ASCII, so byte swap suffices). */
    public StringBuilder reverse()
    {
        int lo = 0;
        int hi = count - 1;
        while (lo < hi)
        {
            byte t = value[lo];
            value[lo] = value[hi];
            value[hi] = t;
            lo = lo + 1;
            hi = hi - 1;
        }
        return this;
    }

    /** {@code charAt}/{@code setLength}/{@code substring} -- the read and truncate surface stock callers use. */
    public char charAt(int index)
    {
        if (index < 0 || index >= count)
        {
            throw new StringIndexOutOfBoundsException(index);
        }
        return (char) (value[index] & 0xFF);
    }

    /**
     * Truncate or zero-extend to {@code newLength}, as stock. Growing pads with NUL rather than leaving the
     * old bytes exposed -- stock guarantees the new positions read as {@code \u0000}, and reusing whatever the
     * buffer happened to hold would leak previous contents into the string.
     */
    public void setLength(int newLength)
    {
        if (newLength < 0)
        {
            throw new StringIndexOutOfBoundsException(newLength);
        }
        while (value.length < newLength)
        {
            byte[] nv = new byte[value.length * 2];
            System.arraycopy(value, 0, nv, 0, count);
            value = nv;
        }
        for (int i = count; i < newLength; i++)
        {
            value[i] = 0;
        }
        count = newLength;
    }

    public String substring(int start)
    {
        return substring(start, count);
    }

    public String substring(int start, int end)
    {
        if (start < 0 || end > count || start > end)
        {
            throw new StringIndexOutOfBoundsException(start);
        }
        byte[] out = new byte[end - start];
        System.arraycopy(value, start, out, 0, end - start);
        return new String(out);
    }

    public StringBuilder append(char[] str)
    {
        return append(str, 0, str.length);
    }

    public StringBuilder append(char[] str, int offset, int len)
    {
        for (int i = 0; i < len; i++)
        {
            put(str[offset + i]);
        }
        return this;
    }

    /** Latin-1 buffer, so a code point above 0xFF cannot be represented; appended as '?' rather than truncated
     *  to a wrong character, which would be a silent corruption. */
    public StringBuilder appendCodePoint(int codePoint)
    {
        put(codePoint > 0xFF ? '?' : codePoint);
        return this;
    }

    /** Insert at {@code offset}, shifting the tail right. */
    public StringBuilder insert(int offset, String str)
    {
        if (offset < 0 || offset > count)
        {
            throw new StringIndexOutOfBoundsException(offset);
        }
        String tail = substring(offset, count);
        count = offset;
        append(str);
        append(tail);
        return this;
    }

    public StringBuilder deleteCharAt(int index)
    {
        if (index < 0 || index >= count)
        {
            throw new StringIndexOutOfBoundsException(index);
        }
        for (int i = index; i < count - 1; i++)
        {
            value[i] = value[i + 1];
        }
        count = count - 1;
        return this;
    }

    /** {@code delete(start,end)} -- the range form of {@link #deleteCharAt}. */
    public StringBuilder delete(int start, int end)
    {
        if (start < 0 || start > count || start > end)
        {
            throw new StringIndexOutOfBoundsException(start);
        }
        int to = end > count ? count : end;
        int n = to - start;
        for (int i = start; i < count - n; i++)
        {
            value[i] = value[i + n];
        }
        count = count - n;
        return this;
    }

    /** Latin-1 buffer, so a code point IS the character -- no surrogate pairing to undo. */
    public int codePointAt(int index)
    {
        return charAt(index);
    }

    public int codePointBefore(int index)
    {
        return charAt(index - 1);
    }

    public String toString()
    {
        byte[] t = new byte[count];
        System.arraycopy(value, 0, t, 0, count);
        return new String(t, (byte) 0);
    }
}
