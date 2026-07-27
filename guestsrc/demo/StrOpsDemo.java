package demo;

import magic.Magic;

/**
 * String search + slice on metal: {@code indexOf(String)}, {@code indexOf(char)}, {@code substring(int)},
 * {@code substring(int,int)} on the real-shaped mini {@link String} (LATIN1). Chained too (substring of an
 * indexOf result). Int results printed via real {@code Integer.toString}.
 */
public class StrOpsDemo
{
    public static void main()
    {
        String s = "hello world";
        showInt("indexOf(\"world\")", s.indexOf("world"));                 // 6
        showInt("indexOf(\"lo\")", s.indexOf("lo"));                       // 3
        showInt("indexOf(\"xyz\")", s.indexOf("xyz"));                     // -1
        showInt("indexOf('o')", s.indexOf('o'));                           // 4
        showStr("substring(6)", s.substring(6));                           // world
        showStr("substring(0,5)", s.substring(0, 5));                      // hello
        showStr("substring(indexOf(' ')+1)", s.substring(s.indexOf(' ') + 1));   // world
    }

    private static void showStr(String label, String v)
    {
        Magic.printStr("  Str.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(v);
        Magic.printStr("\n");
    }

    private static void showInt(String label, int v)
    {
        showStr(label, Integer.toString(v));
    }
}
