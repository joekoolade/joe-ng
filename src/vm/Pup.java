package vm;

/**
 * A subclass the loader loads on the metal: {@code Pup extends Critter}. It
 * inherits Critter's {@code base} field and its {@code legs()} method (vtable slot
 * 1), and overrides {@code sound()} (slot 0) — the override reads the inherited
 * field and calls the inherited method. The loader flattens Pup's vtable against
 * Critter's registered one, so slot 0 holds Pup's override and slot 1 holds
 * Critter's {@code legs}. {@code new Pup()} allocates at the inherited size and
 * runs {@code super()}.
 */
public class Pup extends Critter
{
    @Override
    int sound()
    {
        return base + legs() + 18;   // inherited field (base) + inherited method (legs) + 18
    }
}
