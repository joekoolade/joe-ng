package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} builds a real object on the metal ({@code new Guest()} +
 * default {@code <init>}), fills a field via a <em>cross-class</em> static call
 * ({@code Helper.scale}, a method in a different loaded class linked through the
 * registry), then calls through the <em>interface</em> {@link Speaker}:
 * {@code s.speak()} is an {@code invokeinterface} the loader resolves to Guest's
 * own vtable slot and dispatches through the instance's TIB. A correct {@code 42}
 * means the whole stack works — cross-class linking, allocation, interface
 * dispatch, fields, and static init.
 */
public class Guest implements Speaker
{
    int value;                   // instance field at +16 (object model)
    static int bias = 20;        // set by <clinit> (non-final, so not inlined at the use site)

    public int speak()
    {
        return value + bias;     // implements Speaker.speak: getfield + getstatic
    }

    static int inner(int n)
    {
        return n;                // intra-class leaf callee
    }

    public static int answer()
    {
        Guest g = new Guest();   // new (stores the TIB) + invokespecial <init>
        g.value = Helper.scale(inner(11));   // cross-class call: Helper.scale(11) = 22
        Speaker s = g;           // widen to the interface type
        return s.speak();        // invokeinterface: resolve to the vtable slot -> 22 + 20 = 42 = '*'
    }
}
