package java.util;

/**
 * A JDK-free, mini {@code java/util/Map}: the methods {@link HashMap} implements and the demo drives through
 * the interface. {@code keySet()}/{@code values()} return a {@link List} snapshot of the live entries (real
 * Map returns a Set/Collection view; a fresh List is enough for the iteration probe and lets the demo drive
 * it with the same List/Iterator machinery). Holding the returned collection as {@code List} keeps its
 * enhanced-for at {@code invokeinterface List.iterator} -- an interface ArrayList's itable directory has.
 */
public interface Map
{
    Object put(Object key, Object value);

    Object get(Object key);

    boolean containsKey(Object key);

    Object remove(Object key);

    Object getOrDefault(Object key, Object defaultValue);

    int size();

    List keySet();

    List values();
}
