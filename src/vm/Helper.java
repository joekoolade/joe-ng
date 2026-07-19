package vm;

/**
 * A second class the loader loads on the metal, so {@link Guest} can make a
 * <em>cross-class</em> call. Like Guest it is embedded as raw {@code .class} bytes
 * (never compiled at build time); at runtime the loader parses and compiles it in
 * its own context, registers its methods, and links Guest's {@code invokestatic
 * Helper.scale} to the compiled buffer — the first linking across two loaded
 * classes on bare metal.
 */
public class Helper
{
    static int scale(int n)
    {
        return n * 2;            // leaf; Guest calls this across the class boundary
    }
}
