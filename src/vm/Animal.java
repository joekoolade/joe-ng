package vm;

/** Base class of a small hierarchy — {@code sound()} is a virtual method (vtable slot 0). */
public class Animal
{
    /** {@code new Dog().sound()} — the metal writer's cross-class new + virtual-dispatch target. */
    public static int dogSound()
    {
        return new Dog().sound();
    }

    public int sound()
    {
        return 0x3F;   // '?'
    }
}
