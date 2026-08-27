package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK {@code Long.parseLong(String)} and {@code Long.toHexString(long)} on metal.
 * parseLong shares parseInt's mini deps (String.charAt/length, Character.digit, NumberFormatException);
 * toHexString goes through {@code Long.numberOfLeadingZeros} + {@code Math.max} + {@code formatUnsignedLong0}
 * (indexing the loader-seeded {@code Integer.digits}) + {@code newStringWithLatin1Bytes}. Prints each result.
 */
public class LongMoreDemo
{
    public static void main(String[] args)
    {
        showParse("12345");                             // 12345
        showParse("-9999999999");                       // -9999999999 (> 32 bits)
        showParse("9223372036854775807");               // Long.MAX_VALUE
        showHex(255L);                                  // ff
        showHex(-1L);                                   // ffffffffffffffff (unsigned 64-bit)
        showHex(4886718345L);                           // 123456789
    }

    private static void showParse(String s)
    {
        long v = Long.parseLong(s);                     // real, unmodified Long.parseLong
        Magic.printStr("  Long.parseLong -> ");
        Magic.printStr(Long.toString(v));               // round-trip back to decimal
        Magic.printStr("\n");
    }

    private static void showHex(long v)
    {
        Magic.printStr("  Long.toHexString -> ");
        Magic.printStr(Long.toHexString(v));
        Magic.printStr("\n");
    }
}
