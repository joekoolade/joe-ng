package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} drives a <em>class hierarchy</em> loaded on the metal:
 * {@code new Pup()} allocates a {@link Pup} (a subclass of {@link Critter}) at the
 * inherited size and runs {@code super()}; the inherited field {@code base} is
 * written through a {@code Critter} reference; then {@code c.sound()} dispatches
 * <em>virtually</em> on a {@code Critter}-typed reference and lands on Pup's
 * override — which itself reads the inherited field and calls the inherited
 * {@code legs()}. A correct {@code 42} means inheritance, flattened vtables,
 * override dispatch, and inherited fields all work on the metal.
 */
public class Guest
{
    public static int answer()
    {
        Pup p = new Pup();           // cross-class new of a subclass (+ super() constructor)
        Critter c = p;               // widen to the superclass type
        c.base = 20;                 // write the inherited field (Critter.base) on the Pup
        return c.sound();            // virtual dispatch -> Pup.sound: 20 + legs(4) + 18 = 42 = '*'
    }
}
