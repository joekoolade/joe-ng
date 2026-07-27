package java.lang;

/**
 * A JDK-free, minimal {@code java/lang/Character}: just {@code digit(char, int)} — the one method real
 * {@code Integer.parseInt} calls per input character. ASCII/Latin1 digit logic done directly (the real one
 * routes through the big {@code CharacterData} tables); enough for parsing decimal/hex on metal. Compiled
 * as a {@code java.base} patch so it carries the real name.
 */
public final class Character
{
    public static int digit(char ch, int radix)
    {
        int d = -1;
        if (ch >= '0' && ch <= '9')
        {
            d = ch - '0';
        }
        else if (ch >= 'a' && ch <= 'z')
        {
            d = ch - 'a' + 10;
        }
        else if (ch >= 'A' && ch <= 'Z')
        {
            d = ch - 'A' + 10;
        }
        if (d >= radix)
        {
            return -1;
        }
        return d;
    }
}
