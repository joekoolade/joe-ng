package demo;

import magic.Magic;

/**
 * Loads the UNMODIFIED JDK {@code java/lang/Integer} through the normal closure/loadAll path (not the
 * isolated single-method compile) and calls {@code Integer.parseInt} — proving loadAll compiles only the
 * methods reachable from this entry. If it compiled every Integer method, its unreachable ones (toString,
 * the String.format paths, ...) would drag in unbuilt deps and choke; reachability prunes them.
 */
public class ParseAllDemo
{
    public static void main()
    {
        show("42", Integer.parseInt("42"));
        show("12345", Integer.parseInt("12345"));
        show("-7", Integer.parseInt("-7"));
        show("2147483647", Integer.parseInt("2147483647"));
    }

    private static void show(String label, int v)
    {
        Magic.printStr("  parseInt(\"" + label + "\") = " + v + "\n");
    }
}
