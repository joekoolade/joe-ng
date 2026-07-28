package java.util;

/**
 * A JDK-free, mini {@code java/util/List}: just the methods {@link ArrayList} implements and the demo calls
 * through the interface. The point is the dispatch -- a {@code List} reference to an {@code ArrayList} routes
 * {@code add}/{@code get}/{@code size}/{@code isEmpty} through {@code invokeinterface} + the itable, this time
 * with a multi-method itable and methods that take args / return values (unlike the zero-arg {@code Runnable}).
 * Extends {@code Iterable} so an enhanced-for over a {@code List} reference works; otherwise only what the
 * probe needs (no {@code Collection}). {@code iterator()} is inherited from {@code Iterable}.
 */
public interface List extends Iterable
{
    boolean add(Object e);

    Object get(int index);

    int size();

    boolean isEmpty();

    int indexOf(Object o);

    boolean contains(Object o);

    Object remove(int index);

    boolean remove(Object o);
}
