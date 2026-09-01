package java.util.function;

import java.util.Objects;

/** Minimal generic {@code Predicate} (erased at runtime; raw usage still compiles). */
public interface Predicate<T>
{
    boolean test(T o);

    /**
     * The stock combinators. Short-circuiting is part of the contract, not an optimisation -- {@code and}
     * must not evaluate the second predicate when the first is false -- so these are written with {@code &&}
     * and {@code ||} rather than as eager boolean expressions.
     *
     * <p>Declared because a name-winning overlay silently drops what it does not declare; listed by
     * {@code make overlaycheck} as referenced from JUnit's discovery filters.
     */
    default Predicate<T> and(Predicate<? super T> other)
    {
        return (T t) -> test(t) && other.test(t);
    }

    default Predicate<T> or(Predicate<? super T> other)
    {
        return (T t) -> test(t) || other.test(t);
    }

    default Predicate<T> negate()
    {
        return (T t) -> !test(t);
    }

    static <T> Predicate<T> not(Predicate<? super T> target)
    {
        return (T t) -> !target.test(t);
    }

    /** Null-safe equality, as stock: {@code isEqual(null)} matches only null. */
    static <T> Predicate<T> isEqual(Object targetRef)
    {
        return targetRef == null
                ? (T t) -> t == null
                : (T t) -> targetRef.equals(t);
    }
}
