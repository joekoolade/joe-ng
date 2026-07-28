package java.util.function;

/** Mini functional interface: combine two values into one -- the accumulator of a {@code reduce} fold. */
public interface BinaryOperator
{
    Object apply(Object a, Object b);
}
