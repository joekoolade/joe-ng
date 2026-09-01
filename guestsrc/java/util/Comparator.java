package java.util;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * A JDK-free, mini {@code java/util/Comparator}: a functional interface (single abstract method
 * {@code compare}) so a {@code (a, b) -> ...} lambda targets it. Its SAM takes TWO Object args -- the first
 * two-arg reference SAM the lambda machinery drives (prior lambdas were zero-arg Runnable / one-arg IntOp).
 * {@code Collections.sort(List, Comparator)} calls it to order elements without needing them to be Comparable.
 * Generic ({@code Comparator<T>}) like the real one, so an UNBOUND instance method reference whose receiver is
 * the first SAM arg -- e.g. {@code String::compareTo} as a {@code Comparator<String>} -- typechecks; the erased
 * SAM stays {@code compare(Object,Object)}. Raw usage (lambdas, static method refs) is unaffected.
 */
public interface Comparator<T>
{
    int compare(T a, T b);

    /**
     * The stock combinators and factories. Declared because a name-winning overlay silently drops what it
     * does not declare -- the call then resolves NOWHERE and surfaces as a DENYLIST TRAP naming a denylist
     * this interface is not on. All eight were listed by {@code make overlaycheck}, referenced from JUnit's
     * test-descriptor ordering and picocli's option sorting.
     *
     * <p>{@code reversed()} is {@code compare(b, a)} rather than negating the result: negation is WRONG for a
     * comparator that returns Integer.MIN_VALUE, whose negation is itself.
     */
    default Comparator<T> reversed()
    {
        return (T a, T b) -> compare(b, a);
    }

    default Comparator<T> thenComparing(Comparator<? super T> other)
    {
        return (T a, T b) ->
        {
            int c = compare(a, b);
            return c != 0 ? c : other.compare(a, b);
        };
    }

    default <U extends Comparable<? super U>> Comparator<T> thenComparing(
            Function<? super T, ? extends U> keyExtractor)
    {
        return thenComparing(comparing(keyExtractor));
    }

    default <U> Comparator<T> thenComparing(Function<? super T, ? extends U> keyExtractor,
            Comparator<? super U> keyComparator)
    {
        return thenComparing(comparing(keyExtractor, keyComparator));
    }

    default Comparator<T> thenComparingInt(ToIntFunction<? super T> keyExtractor)
    {
        return thenComparing(comparingInt(keyExtractor));
    }

    static <T, U extends Comparable<? super U>> Comparator<T> comparing(
            Function<? super T, ? extends U> keyExtractor)
    {
        return (T a, T b) -> keyExtractor.apply(a).compareTo(keyExtractor.apply(b));
    }

    static <T, U> Comparator<T> comparing(Function<? super T, ? extends U> keyExtractor,
            Comparator<? super U> keyComparator)
    {
        return (T a, T b) -> keyComparator.compare(keyExtractor.apply(a), keyExtractor.apply(b));
    }

    static <T> Comparator<T> comparingInt(ToIntFunction<? super T> keyExtractor)
    {
        return (T a, T b) -> Integer.compare(keyExtractor.applyAsInt(a), keyExtractor.applyAsInt(b));
    }

    /** Orders by the elements' own {@link Comparable} order; throws NPE on a null element, as stock does. */
    static <T extends Comparable<? super T>> Comparator<T> naturalOrder()
    {
        return (T a, T b) -> a.compareTo(b);
    }

    static <T extends Comparable<? super T>> Comparator<T> reverseOrder()
    {
        return (T a, T b) -> b.compareTo(a);
    }
}
