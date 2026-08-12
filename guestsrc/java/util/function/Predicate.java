package java.util.function;

/** Minimal generic {@code Predicate} (erased at runtime; raw usage still compiles). */
public interface Predicate<T>
{
    boolean test(T o);
}
