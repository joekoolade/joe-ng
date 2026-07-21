package util;

/**
 * An insertion-ordered {@code String -> int} table backed by parallel arrays and a
 * linear scan — the writer's replacement for {@code LinkedHashMap<String,Integer>} as
 * it moves off the JDK collections toward running on metal (PLAN.md §M5.5a). Insertion
 * order is preserved (arrays), which the image layout relies on; sizes are tiny (dozens
 * of methods/classes), so a linear scan is fine. Once the writer runs on metal the
 * {@code String} keys become Utf8 offsets (§M5.5b); the shape stays the same.
 */
public final class StrIntTable
{
    private String[] keys = new String[8];
    private int[] vals = new int[8];
    private int n;

    public int size()
    {
        return n;
    }
    public String keyAt(int i)
    {
        return keys[i];
    }
    public int valAt(int i)
    {
        return vals[i];
    }

    private int indexOf(String k)
    {
        for (int i = 0; i < n; i++)
        {
            if (keys[i].equals(k))
            {
                return i;
            }
        }
        return -1;
    }

    public boolean containsKey(String k)
    {
        return indexOf(k) >= 0;
    }

    /** The value for {@code k}, or -1 if absent (word offsets are always &ge; 0). */
    public int get(String k)
    {
        int i = indexOf(k);
        return i >= 0 ? vals[i] : -1;
    }

    public void put(String k, int v)
    {
        int i = indexOf(k);
        if (i >= 0)
        {
            vals[i] = v;
            return;
        }
        if (n == keys.length)
        {
            grow();
        }
        keys[n] = k;
        vals[n] = v;
        n += 1;
    }

    private void grow()
    {
        String[] nk = new String[keys.length * 2];
        int[] nv = new int[vals.length * 2];
        for (int i = 0; i < n; i++)
        {
            nk[i] = keys[i];
            nv[i] = vals[i];
        }
        keys = nk;
        vals = nv;
    }
}
