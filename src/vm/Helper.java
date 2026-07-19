package vm;

/**
 * A second class the loader loads on the metal. {@link Guest} both calls it
 * ({@code Helper.scale}) and now <em>instantiates</em> it ({@code new Helper()})
 * and reads/writes its instance fields across the class boundary — so the loader
 * must know Helper's instance size, TIB, and field layout when compiling Guest.
 * Like Guest it is embedded as raw {@code .class} bytes, parsed and compiled on
 * the metal in its own context and registered for cross-class linking.
 */
public class Helper
{
    int a;                       // instance fields at +16 / +24, written/read by Guest
    int b;

    static int scale(int n)
    {
        return n * 2;            // leaf; Guest calls this across the class boundary
    }
}
