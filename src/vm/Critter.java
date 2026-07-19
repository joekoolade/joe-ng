package vm;

/**
 * A superclass the loader loads on the metal, so it can exercise <em>class
 * hierarchies</em>: {@link Pup} extends it. Critter has an instance field Pup
 * inherits, a virtual method Pup overrides ({@code sound}), and one Pup inherits
 * unchanged ({@code legs}). Embedded as raw {@code .class} bytes; the loader
 * compiles it and registers its flattened vtable (sound=slot 0, legs=slot 1) and
 * field layout for Pup to build on.
 */
public class Critter
{
    int base;                    // instance field at +16; Pup inherits and uses it

    int sound()
    {
        return base;             // slot 0 — Pup overrides this
    }

    int legs()
    {
        return 4;                // slot 1 — Pup inherits this unchanged
    }
}
