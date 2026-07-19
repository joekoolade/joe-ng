package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} builds a real object on the metal ({@code new Guest()} +
 * default {@code <init>}), fills a field via a static call chain, then calls
 * through the <em>interface</em> {@link Speaker}: {@code s.speak()} is an
 * {@code invokeinterface} that the loader resolves to Guest's own vtable slot and
 * dispatches through the instance's TIB. {@code speak()} in turn reads an instance
 * field and a {@code <clinit>}-initialized static. A correct {@code 42} means the
 * whole stack works — allocation, interface dispatch, fields, and static init.
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
        return n;                // leaf callee
    }

    static int outer(int n)
    {
        return inner(n) + inner(n);   // two calls; the second sees inner(n) still on the stack
    }

    public static int answer()
    {
        Guest g = new Guest();   // new (stores the TIB) + invokespecial <init>
        g.value = outer(11);     // putfield @16 <- invokestatic chain (22)
        Speaker s = g;           // widen to the interface type
        return s.speak();        // invokeinterface: resolve to the vtable slot -> 22 + 20 = 42 = '*'
    }
}
