package demo;

/**
 * The diagnostic this exists to prove: a {@code new} whose class the loader cannot resolve HALTS and names
 * both the class and the site, instead of quietly handing back an object carrying an unrelated class's TIB.
 *
 * <p>{@code java.nio.charset.MalformedInputException} is denylisted (the charset coder fallback is never
 * taken on metal), so it is never registered — the same condition that arises when a class is genuinely
 * missing. Reaching this {@code new} used to produce a {@code MalformedInputException}-shaped hole: an
 * instance of {@code UnresolvedNewDemo}, which would pass the wrong {@code instanceof} and dispatch into the
 * wrong vtable.
 *
 * <p>This demo does NOT return — it is expected to halt, so it is manifest-only and never part of the boot
 * suite. The pass condition is the message, not a clean exit.
 */
public class UnresolvedNewDemo
{
    public static void main(String[] args)
    {
        System.out.println("about to new a denylisted class");
        Object o = new java.nio.charset.MalformedInputException(1);
        System.out.println("UNREACHABLE: got " + o);
    }
}
