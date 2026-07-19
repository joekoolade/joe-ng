package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} drives a <em>class hierarchy</em> loaded on the metal, gated
 * by {@code instanceof}: a {@link Pup} is a {@link Critter}'s subtype (so
 * {@code p instanceof Pup} is true) while a plain {@code Critter} is not
 * ({@code c2 instanceof Pup} is false) — both resolved on the metal by walking the
 * object's Type chain. Only if both checks agree does it read the inherited field
 * and dispatch {@code c.sound()} <em>virtually</em> onto Pup's override (which
 * itself uses the inherited field and the inherited {@code legs()}). A correct
 * {@code 42} means inheritance, flattened vtables, override dispatch, inherited
 * fields, and {@code instanceof} on loaded objects all work on the metal.
 */
public class Guest
{
    public static int answer()
    {
        Pup p = new Pup();           // new of a subclass (+ super() constructor)
        Critter c = p;               // widen to the superclass type
        if (!(c instanceof Pup))     // a Pup IS a Pup -> this stays false
        {
            return 0;
        }
        Critter c2 = new Critter();  // a plain Critter
        if (c2 instanceof Pup)       // a Critter is NOT a Pup -> this stays false
        {
            return 0;
        }
        c.base = 20;                 // write the inherited field (Critter.base) on the Pup
        return c.sound();            // virtual dispatch -> Pup.sound: 20 + legs(4) + 18 = 42 = '*'
    }
}
