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

    public String toString()
    {
        byte[] t = new byte[count];
        System.arraycopy(value, 0, t, 0, count);
        return new String(t, (byte) 0);
    }
}
