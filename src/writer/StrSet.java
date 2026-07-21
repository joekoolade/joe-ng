package writer;

/**
 * An insertion-ordered, de-duplicating set of {@code String}s backed by an array and a
 * linear scan — the writer's replacement for {@code LinkedHashSet<String>} as it moves
 * off the JDK collections toward metal (PLAN.md §M5.5a). Insertion order is preserved
 * (it drives layout order), and {@link #add} reports whether the element was new, as the
 * JDK set does. Sizes are tiny, so the linear scan is fine.
 */
final class StrSet
{
    private String[] items = new String[8];
    private int n;

    int size()
    {
        return n;
    }
    String at(int i)
    {
        return items[i];
    }

    boolean contains(String s)
    {
        for (int i = 0; i < n; i++)
        {
            if (items[i].equals(s))
            {
                return true;
            }
        }
        return false;
    }

    /** Add {@code s} if absent; returns true iff it was newly added. */
    boolean add(String s)
    {
        if (contains(s))
        {
            return false;
        }
        if (n == items.length)
        {
            String[] bigger = new String[items.length * 2];
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
