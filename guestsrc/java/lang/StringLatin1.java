package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/StringLatin1}: the byte[]-backing helpers real {@link String}'s
 * LATIN1 path delegates to. Enough for the {@code Integer.toString} result path (which wraps a LATIN1 digit
 * buffer); {@code charAt}/{@code length} mirror the real accessors. Compiled as a {@code java.base} patch.
 */
final class StringLatin1
{
    /** A fresh LATIN1 backing array holding {@code val}'s bytes (the copy real newStringWithLatin1Bytes makes). */
    static byte[] newBytes(byte[] val)
    {
        byte[] out = new byte[val.length];
        int i = 0;
        while (i < val.length)
        {
            out[i] = val[i];
            i = i + 1;
        }
        return out;
    }

    static char charAt(byte[] value, int index)
    {
        return (char) (value[index] & 0xFF);
    }

    static int length(byte[] value)
    {
        return value.length;
    }
}
