package java.util.function;

/** Minimal generic {@code BinaryOperator}. */
public interface BinaryOperator<T>
{
    T apply(T a, T b);
}
