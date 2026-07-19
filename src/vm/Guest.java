package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} builds a real object on the metal ({@code new Guest()} +
 * default {@code <init>}), fills a field via a static call chain, then calls a
 * <em>virtual</em> method on it: {@code g.compute()} dispatches through the
 * instance's TIB vtable (built by the loader), and {@code compute()} in turn reads
 * an instance field and a {@code <clinit>}-initialized static. A correct {@code 42}
 * means the whole stack works — allocation, dispatch, fields, and static init.
 */
public class Guest
{
    int value;                   // instance field at +16 (object model)
    static int bias = 20;        // set by <clinit> (non-final, so not inlined at the use site)

    int compute()
    {
        return value + bias;     // virtual: getfield (this.value) + getstatic (bias)
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
        return g.compute();      // invokevirtual: TIB vtable dispatch -> 22 + 20 = 42 = '*'
    }
}
