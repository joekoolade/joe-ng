package java.util;

/**
 * A JDK-free, mini {@code java/util/List}: just the methods {@link ArrayList} implements and the demo calls
 * through the interface. The point is the dispatch -- a {@code List} reference to an {@code ArrayList} routes
 * {@code add}/{@code get}/{@code size}/{@code isEmpty} through {@code invokeinterface} + the itable, this time
 * with a multi-method itable and methods that take args / return values (unlike the zero-arg {@code Runnable}).
 * Extends {@link Collection} (which extends {@code Iterable}), inheriting add/size/isEmpty/contains/
 * remove(Object)/iterator; List adds the positional operations. The 3-level chain rides the transitive
 * itable directory, so a call site typed to List, Collection, or Iterable all dispatch to the impl.
 */
public interface List extends Collection
{
    Object get(int index);

    int indexOf(Object o);

    Object remove(int index);
}
