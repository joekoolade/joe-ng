package vm;

/** Base class of a small hierarchy — {@code sound()} is a virtual method (vtable slot 0). */
public class Animal
{
    public int sound()
    {
        return 0x3F;   // '?'
    }
}
