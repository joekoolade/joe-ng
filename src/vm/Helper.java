package vm;

/**
 * A second class the loader loads on the metal. {@link Guest} calls it
 * ({@code Helper.scale}), instantiates it ({@code new Helper()}), reads/writes its
 * instance fields, and now dispatches a <em>virtual</em> method on it
 * ({@code h.sum()}) across the class boundary — so the loader must know Helper's
 * instance size, TIB, field layout, and vtable when compiling Guest. Like Guest it
 * is embedded as raw {@code .class} bytes, parsed and compiled on the metal in its
 * own context and registered for cross-class linking.
 */
public class Helper
{
    int a;                       // instance fields at +16 / +24, written by Guest
    int b;

    static int scale(int n)
    {
        return n * 2;            // leaf; Guest calls this across the class boundary
    }

    int sum()
    {
        return a + b;            // virtual method; Guest dispatches it on a Helper (h.sum())
    }
}
