package demo;

/**
 * A tiny stateful comparator source: its instance method {@link #cmp} orders strings, scaled by a {@code sign}
 * held in a field. Used to demo a BOUND instance method reference -- {@code someOrder::cmp} captures the
 * {@code Order} object as the SAM receiver, so the sort direction comes from the captured object's state.
 */
public final class Order
{
    private final int sign;                             // +1 ascending, -1 descending

    public Order(int sign)
    {
        this.sign = sign;
    }

    public int cmp(Object a, Object b)
    {
        return sign * ((String) a).compareTo((String) b);
    }
}
