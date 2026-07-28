package java.util.function;

/** Mini functional interface: a void side-effect, the target of a {@code forEach} lambda. */
public interface Consumer
{
    void accept(Object o);
}
