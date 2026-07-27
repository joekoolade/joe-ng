package java.util;

/**
 * A JDK-free, mini {@code java/util/List}: just the methods {@link ArrayList} implements and the demo calls
 * through the interface. The point is the dispatch -- a {@code List} reference to an {@code ArrayList} routes
 * {@code add}/{@code get}/{@code size}/{@code isEmpty} through {@code invokeinterface} + the itable, this time
 * with a multi-method itable and methods that take args / return values (unlike the zero-arg {@code Runnable}).
 * Standalone (no {@code Collection}/{@code Iterable} supertypes) -- only what the probe needs.
 */
public interface List
{
    boolean add(Object e);

    Object get(int index);

    int size();

    boolean isEmpty();
}
