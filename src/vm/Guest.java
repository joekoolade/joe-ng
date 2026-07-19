package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} builds a real object on the metal: {@code new Guest()} calls
 * the image's {@code Heap.alloc}, the default constructor runs (an
 * {@code invokespecial} whose {@code Object.<init>} target is a no-op), and the
 * instance fields round-trip through {@code putfield}/{@code getfield}. The values
 * come from a static call chain (answer -&gt; outer -&gt; inner twice), and the final
 * {@code outer(11)} runs with a loaded field value still live on the stack.
 */
public class Guest
{
    int value;                   // instance fields at +16 / +24 (object model)
    int extra;

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
        Guest g = new Guest();   // new + invokespecial <init> (Object.<init> is a no-op)
        g.value = outer(10);     // putfield <- invokestatic chain (20)
        return g.value + outer(11);   // getfield (20) + invokestatic outer (22) -> 42 = '*'
    }
}
