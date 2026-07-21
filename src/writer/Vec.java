package writer;

/**
 * A minimal growable list backed by an {@code Object[]} — the writer's replacement for
 * {@code ArrayList<T>} as it moves off the JDK collections toward metal (PLAN.md §M5.5a).
 * Only what the image builder needs: append, indexed access, size, and a front-remove for
 * the discovery queue. Generics erase to {@code Object[]} + casts, which compile on metal.
 */
final class Vec<T>
{
    private Object[] items = new Object[8];
    private int n;

    int size()
    {
        return n;
    }
    boolean isEmpty()
    {
        return n == 0;
    }
    @SuppressWarnings("unchecked")
    T get(int i)
    {
        return (T) items[i];
    }
    void add(T x)
    {
        if (n == items.length)
        {
            Object[] bigger = new Object[items.length * 2];
            for (int i = 0; i < n; i++)
            {
                bigger[i] = items[i];
            }
            items = bigger;
        }
        items[n] = x;
        n += 1;
    }

    /** Remove and return the first element (the discovery queue's dequeue). */
    @SuppressWarnings("unchecked")
    T removeFirst()
    {
        T x = (T) items[0];
        for (int i = 1; i < n; i++)
        {
            items[i - 1] = items[i];
        }
        n -= 1;
        items[n] = null;
        return x;
    }
}
