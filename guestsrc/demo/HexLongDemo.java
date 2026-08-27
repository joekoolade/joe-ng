package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code Integer.toHexString/toOctalString/toBinaryString(int)} and
 * {@code Long.toString(long)} on metal. The radix ones all share {@code toUnsignedString0} +
 * {@code formatUnsignedInt} (indexing the loader-seeded {@code Integer.digits}); Long.toString goes
 * through the {@code DecimalDigits} long overloads. Prints each result.
 */
public class HexLongDemo
{
    public static void main(String[] args)
    {
        showHex(255);                                   // ff
        showHex(0xDEADBEEF);                            // deadbeef
        showHex(16);                                    // 10
        showOct(8);                                     // 10
        showOct(255);                                   // 377
        showOct(-1);                                    // 37777777777 (unsigned 32-bit)
        showBin(5);                                     // 101
        showBin(255);                                   // 11111111
        showBin(-2147483648);                           // 10000000000000000000000000000000
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

    private static void showOct(int v)
    {
        Magic.printStr("  Integer.toOctalString -> ");
        Magic.printStr(Integer.toOctalString(v));
        Magic.printStr("\n");
    }

    private static void showBin(int v)
    {
        Magic.printStr("  Integer.toBinaryString -> ");
        Magic.printStr(Integer.toBinaryString(v));
        Magic.printStr("\n");
    }

    private static void showLong(long v)
    {
        Magic.printStr("  Long.toString -> ");
        Magic.printStr(Long.toString(v));
        Magic.printStr("\n");
    }
}
