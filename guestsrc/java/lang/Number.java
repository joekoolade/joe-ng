package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Number}: {@link Integer}'s superclass. It exists only so the vtable
 * chain Integer -> Number -> {@link Object} propagates Object's hashCode/equals slots down to Integer's
 * overrides — which is how a HashMap keyed by boxed Integers dispatches key.hashCode()/equals() into
 * Integer's real implementations. Real Number's abstract accessors (intValue, ...) aren't needed here.
 * Compiled as a {@code java.base} patch so it carries the real name.
 */
public abstract class Number
{
    public Number()
    {
    }
}
