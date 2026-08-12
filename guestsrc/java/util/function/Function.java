package java.util.function;

/** Minimal generic {@code Function}. */
public interface Function<T, R>
{
    R apply(T o);
}
