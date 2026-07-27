package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code Integer.toString(int)} on metal: it computes the digit count
 * ({@code DecimalDigits.stringSize}), writes the digits into a {@code byte[]}
 * ({@code DecimalDigits.uncheckedGetCharsLatin1}), and wraps it as a real byte[]+coder
 * {@link String} ({@code newStringWithLatin1Bytes}). Prints each result — the round-trip of an int back
 * to its decimal String, produced by real java.base code.
 */
public class ToStringDemo
{
    public static void main()
    {
        show(0);
        show(42);
        show(-7);
        show(12345);
        show(-2147483648);
    }

    private static void show(int v)
    {
        String s = Integer.toString(v);                 // real, unmodified Integer.toString
        Magic.printStr("  Integer.toString -> ");
        Magic.printStr(s);
        Magic.printStr("\n");
    }
}
