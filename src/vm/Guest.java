package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} now calls across methods, so the loader exercises
 * {@code invokestatic}: it resolves and compiles each callee on demand and links
 * a real {@code BL}. The chain answer -&gt; outer -&gt; inner (twice) also drives a
 * two-deep recursive compile and a call whose result is combined on the stack.
 */
public class Guest
{
    static int seed;             // a static field, backed by the loader's statics block

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
        seed = 21;               // putstatic
        return outer(seed);      // getstatic + invokestatic -> 21 + 21 = 42 = 0x2A = '*'
    }
}
