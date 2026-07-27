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

        String h = "hello";
        showBool("h.startsWith(\"hel\")", h.startsWith("hel"));            // 1
        showBool("h.startsWith(\"xyz\")", h.startsWith("xyz"));            // 0
        showBool("h.startsWith(\"hello!\")", h.startsWith("hello!"));      // 0 (prefix longer)
        showInt("h.compareTo(\"hello\")", h.compareTo("hello"));           // 0
        showInt("h.compareTo(\"apple\")", h.compareTo("apple"));           // 7  ('h'-'a')
        showInt("h.compareTo(\"world\")", h.compareTo("world"));           // -15 ('h'-'w')
        showInt("h.compareTo(\"hell\")", h.compareTo("hell"));             // 1  (length)

        showStr("\"  hi  \".trim()", "  hi  ".trim());                      // hi
        showStr("\"nospace\".trim()", "nospace".trim());                   // nospace
        showStr("\"   \".trim()", "[" + "   ".trim() + "]");               // []
        showStr("\"a.b.c\".replace('.','/')", "a.b.c".replace('.', '/'));  // a/b/c
        showStr("\"hello\".replace('l','L')", "hello".replace('l', 'L'));  // heLLo
        showStr("\"none\".replace('x','y')", "none".replace('x', 'y'));    // none

        showSplit("\"a,b,c\".split(\",\")", "a,b,c".split(","));           // [a, b, c]
        showSplit("\"a::b::c\".split(\"::\")", "a::b::c".split("::"));     // [a, b, c]
        showSplit("\"a,,b,,\".split(\",\")", "a,,b,,".split(","));         // [a, , b] (trailing empties dropped)
        showSplit("\"whole\".split(\",\")", "whole".split(","));          // [whole]

        showStr("join(\",\", \"a\",\"b\",\"c\")", String.join(",", "a", "b", "c"));      // a,b,c  (varargs)
        showStr("join(\"-\", split(\"a,b,c\"))", String.join("-", "a,b,c".split(",")));  // a-b-c  (round-trip)
        showStr("join(\"/\", \"one\")", String.join("/", "one"));                        // one    (single elem)
    }

    private static void showSplit(String label, String[] parts)
    {
        Magic.printStr("  Str.");
        Magic.printStr(label);
        Magic.printStr(" len=");
        Magic.printStr(Integer.toString(parts.length));
        Magic.printStr(" = [");
        int i = 0;
        while (i < parts.length)
        {
            if (i > 0) { Magic.printStr(", "); }
            Magic.printStr(parts[i]);
            i = i + 1;
        }
        Magic.printStr("]\n");
    }

    private static void showBool(String label, boolean v)
    {
        showInt(label, v ? 1 : 0);
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
