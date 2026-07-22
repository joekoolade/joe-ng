package util;

/**
 * A minimal growable {@code int[]} — the primitive-keyed sibling of {@link Vec}, so
 * code that accumulates machine words or offsets never boxes through
 * {@code Vec<Integer>} (which drags {@code java.lang.Integer} into the image). Only
 * what the writer needs: append, indexed access, size, and a snapshot to {@code int[]}.
 */
public final class IntVec
{
    private int[] items = new int[8];
    private int n;

    public int size()
    {
        return n;
    }
    public int get(int i)
    {
        return items[i];
    }
    public void add(int x)
    {
        if (n == items.length)
        {
            int[] bigger = new int[items.length * 2];
            for (int i = 0; i < n; i++)
            {
                bigger[i] = items[i];
            }
            items = bigger;
        }
        items[n] = x;
        n += 1;
    }

    /** A tight copy of the {@code size()} live elements. */
    public int[] toArray()
    {
        int[] a = new int[n];
        for (int i = 0; i < n; i++)
        {
            a[i] = items[i];
        }
        return a;
    }
}
