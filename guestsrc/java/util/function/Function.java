package java.util.function;

/** Mini functional interface: a value transform, the target of a {@code map} lambda. */
public interface Function
{
    Object apply(Object o);
}
