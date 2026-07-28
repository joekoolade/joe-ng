package java.util;

/**
 * A JDK-free, mini {@code java/util/Collection}: the general element operations, sitting between {@link List}
 * and {@link java.lang.Iterable} to form a real 3-level interface chain (List extends Collection extends
 * Iterable). Exercises the transitive itable directory -- an {@code ArrayList} reaches Iterable only through
 * Collection through List, yet a call site typed to any of the three must dispatch. {@code iterator()} is
 * inherited from Iterable.
 */
public interface Collection extends Iterable
{
    boolean add(Object e);

    int size();

    boolean isEmpty();

    boolean contains(Object o);

    boolean remove(Object o);
}
