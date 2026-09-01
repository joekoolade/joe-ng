package java.util.function;

/** Minimal generic {@code Function}. */
public interface Function<T, R>
{
    R apply(T o);

    /**
     * The stock combinators. Declared because a name-winning overlay silently drops what it does not
     * declare -- the call then resolves NOWHERE and surfaces as a DENYLIST TRAP naming a denylist this
     * interface is not on. Listed by {@code make overlaycheck}.
     */
    default <V> Function<T, V> andThen(Function<? super R, ? extends V> after)
    {
        return (T t) -> after.apply(apply(t));
    }

    default <V> Function<V, R> compose(Function<? super V, ? extends T> before)
    {
        return (V v) -> apply(before.apply(v));
    }

    static <T> Function<T, T> identity()
    {
        return (T t) -> t;
    }
}
