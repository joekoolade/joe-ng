/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-27
 */
package java.lang;

/**
 * A real-shaped, JDK-free {@link java.lang.StringBuilder}: a {@code byte[]} + count, with the common
 * {@code append} overloads and {@code toString} (which builds a real {@link String} via the provided
 * {@code System.arraycopy} native). Fixed initial capacity (grown lazily) — enough for demand-loaded
 * demos. Compiled as a {@code java.base} patch.
 */
public final class StringBuilder implements Appendable, CharSequence
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

    /**
     * {@code CharSequence.subSequence}, which is the only member of that interface this class did not
     * already have -- {@code length}, {@code charAt} and {@code toString} were here all along.
     *
     * <p>Declaring the interface is the point, not this method. An overlay WINS the name, so a stock
     * interface it omits ceases to exist for this class: nothing that declares a {@code CharSequence}
     * parameter can bind to a StringBuilder. That is the same trap as this class dropping
     * {@code Appendable}, which broke {@code String.replaceAll} because stock {@code Matcher} declares its
     * sink as {@code Appendable} -- and it was found the same way both times, except that this time
     * {@code make overlaycheck}'s supertype diff reported it at BUILD TIME instead of a library NPE-ing
     * somewhere unrelated.
     *
     * <p>Returns the {@link String} from {@code substring}, which is a {@code CharSequence} -- the same
     * bounds checks apply, so an out-of-range request throws where stock throws.
     */
    public CharSequence subSequence(int start, int end)
    {
        return substring(start, end);
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

    // ----- the members stock declares that this overlay had dropped -----
    //
    // EVERY ONE OF THESE WAS REFERENCED BY SOMETHING WE SHIP AND RESOLVED NOWHERE. An overlay wins the
    // name, so a stock member it omits CEASES TO EXIST: the call surfaces as a VIRTUALRESOLVE FAILED and
    // then a DENYLIST TRAP naming a list this class is not on. `repeat` is how it showed here --
    // BigDecimal.toPlainString builds its zero run with it, so the stock jtreg ToPlainStringTests died in
    // java.math with no hint that the gap was in StringBuilder. They land TOGETHER rather than one per
    // boot, which is the recorded cost of taking this family a member at a time.

    /**
     * {@code repeat(codePoint, count)} -- JDK 21. A NEGATIVE count is rejected, and zero is a legal no-op:
     * stock throws only for count < 0, and answering an empty run for a negative one would be a plausible
     * wrong answer rather than a visible failure.
     */
    public StringBuilder repeat(int codePoint, int count)
    {
        if (count < 0)
        {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        int i = 0;
        while (i < count)
        {
            appendCodePoint(codePoint);
            i = i + 1;
        }
        return this;
    }

    /** {@code repeat(cs, count)} -- a null CharSequence repeats the four characters {@code null}, as
     *  stock's own {@code String.valueOf} contract requires and as {@link #append(CharSequence)} does. */
    public StringBuilder repeat(CharSequence cs, int count)
    {
        if (count < 0)
        {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        String s = cs == null ? "null" : cs.toString();
        int i = 0;
        while (i < count)
        {
            append(s);
            i = i + 1;
        }
        return this;
    }

    /**
     * {@code replace(start, end, str)} -- delete the range, then insert at its start.
     *
     * <p>Written as delete-then-insert rather than as a copy loop because the two halves are already
     * bounds-checked here; open-coding a shift would be a third place to get the end clamp wrong, and
     * stock clamps {@code end} to the length exactly as {@link #delete} does.
     */
    public StringBuilder replace(int start, int end, String str)
    {
        delete(start, end);
        return insert(start, str);
    }

    public void setCharAt(int index, char ch)
    {
        if (index < 0 || index >= count)
        {
            throw new StringIndexOutOfBoundsException(index);
        }
        value[index] = (byte) ch;
    }

    /** {@code ensureCapacity} is ADVISORY: {@link #put} grows the buffer on demand, so a caller that asks
     *  for room is asking for fewer copies and never for a guarantee. Growing here honours the hint. */
    public void ensureCapacity(int minimumCapacity)
    {
        if (minimumCapacity > value.length)
        {
            byte[] bigger = new byte[minimumCapacity];
            System.arraycopy(value, 0, bigger, 0, count);
            value = bigger;
        }
    }

    /** Latin-1 buffer, so every character is one code point and the offset is the index -- the same
     *  reasoning {@link #codePointAt} records. Bounds are stock's: the WALK may end at {@code count}. */
    public int offsetByCodePoints(int index, int codePointOffset)
    {
        int to = index + codePointOffset;
        if (index < 0 || index > count || to < 0 || to > count)
        {
            throw new StringIndexOutOfBoundsException(index);
        }
        return to;
    }

    public StringBuilder insert(int offset, char c)
    {
        return insert(offset, String.valueOf(c));
    }

    public StringBuilder insert(int offset, long v)
    {
        return insert(offset, Long.toString(v));
    }

    public StringBuilder insert(int offset, char[] str)
    {
        return insert(offset, new String(str));
    }

    /** {@code append(float)} -- through {@code Float.toString}, which this VM resolves BY NAME at run time
     *  (the shortest decimal that round-trips). A float is NOT a widened double here: this file already
     *  records that {@code Float.toString(0.1f)} is {@code "0.1"} where the widened double is
     *  {@code "0.10000000149011612"}, so the float formatter is the only correct one. */
    public StringBuilder append(float f)
    {
        return append(Float.toString(f));
    }

    /**
     * {@code append(StringBuffer)} -- the descriptor is what makes this a separate member, so it has to be
     * declared even though the body is {@link #append(CharSequence)}'s.
     *
     * <p>CHECKED rather than assumed: {@code java/lang/StringBuffer} is NOT overlaid here, so the parameter
     * type is the STOCK class, demand-loadable like the rest of {@code java/}. It is a CharSequence, so the
     * widening is stock's own and no conversion is invented.
     */
    public StringBuilder append(StringBuffer sb)
    {
        return append((CharSequence) sb);
    }
}
