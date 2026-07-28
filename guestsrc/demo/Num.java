package demo;

/**
 * A mini Integer-like value type: wraps an {@code int} and implements {@link Comparable} so the SAME generic
 * {@code Collections.sort} that sorts Strings also sorts these -- proving the sort is type-agnostic (dispatches
 * through each element type's own {@code compareTo(Object)} bridge), not accidentally String-only. (A distinct
 * demo type rather than the real embedded {@code java.lang.Integer}, whose full method set loadList would have
 * to compile.)
 */
public final class Num implements Comparable<Num>
{
    private final int value;

    public Num(int value)
    {
        this.value = value;
    }

    public int value()
    {
        return value;
    }

    public int compareTo(Num other)
    {
        if (value < other.value)
        {
            return -1;
        }
        if (value > other.value)
        {
            return 1;
        }
        return 0;
    }
}
