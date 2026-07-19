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
 * instance fields round-trip through {@code putfield}/{@code getfield}. The value
 * for {@code extra} comes from {@code bias}, a static the loader initializes by
 * running {@code <clinit>} first — so a correct {@code 42} proves the initializer
 * ran (an uninitialized {@code bias} would leave {@code 20}).
 */
public class Guest
{
    int value;                   // instance fields at +16 / +24 (object model)
    int extra;
    static int bias = 22;        // set by <clinit> (non-final, so not inlined at the use site)

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
        g.value = outer(10);     // putfield @16 <- invokestatic chain (20)
        g.extra = bias;          // putfield @24 <- getstatic bias (22, from <clinit>)
        return g.value + g.extra;   // getfield @16 + getfield @24 -> 42 = '*'
    }
}
