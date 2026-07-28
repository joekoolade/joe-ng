package java.util.function;

/** Mini functional interface: a boolean test, the target of a {@code filter} lambda. */
public interface Predicate
{
    boolean test(Object o);
}
