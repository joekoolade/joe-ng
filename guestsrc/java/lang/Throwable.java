package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/Throwable}: the root of the mini exception hierarchy the JIT
 * synthesises for implicit exceptions (null-deref / array-bounds). Field-free — the metal only needs its
 * Type chain (for {@code instanceof} in catch dispatch), which comes from its TIB, not any instance state.
 * Compiled as a {@code java.base} patch so it carries the real name.
 */
public class Throwable
{
    public Throwable()
    {
    }
}
