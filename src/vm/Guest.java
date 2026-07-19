package vm;

/**
 * A class joe2 has never seen at build time. The writer embeds only its raw
 * {@code .class} bytes (never compiles it); at runtime the on-metal {@link Loader}
 * parses those bytes, compiles {@code answer()} to A64, and executes it. This is
 * M4: runtime class loading on bare metal (PLAN.md §4).
 *
 * <p>{@code answer()} works entirely across the class boundary: it {@code new}s a
 * {@link Helper} (a different loaded class — allocated at Helper's size, its TIB
 * and constructor linked through the registry), writes Helper's instance fields
 * (one from a cross-class static call, one from a {@code <clinit>} static), then
 * dispatches a <em>virtual</em> method on it — {@code h.sum()} loads Helper's TIB
 * from the object and calls Helper's slot. A correct {@code 42} means cross-class
 * new, fields, calls, virtual dispatch, and static init all work on the metal.
 */
public class Guest
{
    static int bias = 20;        // set by <clinit> (non-final, so not inlined at the use site)

    static int inner(int n)
    {
        return n;                // intra-class leaf callee
    }

    public static int answer()
    {
        Helper h = new Helper();             // cross-class new + Helper.<init>
        h.a = Helper.scale(inner(11));       // cross-class putfield @16 <- cross-class call (22)
        h.b = bias;                          // cross-class putfield @24 <- getstatic bias (20)
        return h.sum();                      // cross-class invokevirtual (Helper.sum) -> 42 = '*'
    }
}
