package java.util.function;

/** Mini functional interface: a void side-effect over two values -- the (key, value) action of Map.forEach. */
public interface BiConsumer
{
    void accept(Object a, Object b);
}
