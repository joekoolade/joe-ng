package java.util.function;

/** Minimal generic {@code BiConsumer}. */
public interface BiConsumer<T, U>
{
    void accept(T a, U b);
}
