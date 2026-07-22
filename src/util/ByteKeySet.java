package util;

/**
 * The {@code byte[]}-keyed sibling of {@link StrSet}: an insertion-ordered, de-duplicating
 * set whose identity is byte *content* (PLAN.md §M5.5b). Insertion order drives image
 * layout order, and {@link #add} reports whether the element was new — same contract as
 * {@code StrSet}, so it drops in wherever the writer keyed a set by name. Linear scan;
 * a self-build's symbol count is small.
 */
public final class ByteKeySet
{
    private byte[][] items = new byte[8][];
    private int n;

    public int size()
    {
        return n;
    }
    public byte[] at(int i)
    {
        return items[i];
    }

    public boolean contains(byte[] s)
    {
        for (int i = 0; i < n; i++)
        {
            if (Bytes.eq(items[i], s))
            {
                return true;
            }
        }
        return false;
    }

    /** Add {@code s} if absent; returns true iff it was newly added. */
    public boolean add(byte[] s)
    {
        if (contains(s))
        {
            return false;
        }
        if (n == items.length)
        {
            byte[][] bigger = new byte[items.length * 2][];
            for (int i = 0; i < n; i++)
            {
                bigger[i] = items[i];
            }
            items = bigger;
        }
        items[n] = s;
        n += 1;
        return true;
    }
}
