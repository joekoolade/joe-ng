package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code Integer.toHexString(int)} and {@code Long.toString(long)} on metal.
 * toHexString goes through {@code numberOfLeadingZeros} + {@code Math.max} + {@code formatUnsignedInt}
 * (which indexes the real {@code Integer.digits} table, seeded by the loader) into a byte[]+coder String;
 * Long.toString goes through the {@code DecimalDigits} long overloads. Prints each result.
 */
public class HexLongDemo
{
    public static void main()
    {
        showHex(255);                                   // ff
        showHex(0xDEADBEEF);                            // deadbeef
        showHex(16);                                    // 10
        showLong(0L);                                   // 0
        showLong(42L);                                  // 42
        showLong(-42L);                                 // -42
        showLong(9999999999L);                          // 9999999999 (> 32 bits)
        showLong(-9223372036854775808L);                // -9223372036854775808 (Long.MIN_VALUE)
    }

    private static void showHex(int v)
    {
        Magic.printStr("  Integer.toHexString -> ");
        Magic.printStr(Integer.toHexString(v));
        Magic.printStr("\n");
    }

    private static void showLong(long v)
    {
        Magic.printStr("  Long.toString -> ");
        Magic.printStr(Long.toString(v));
        Magic.printStr("\n");
    }
}
