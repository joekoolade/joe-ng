package java.lang;

/**
 * A JDK-free, real-shaped {@code java/lang/NumberFormatException}: what real {@code Integer.parseInt}
 * throws on bad input, via the {@code forInputString(String,int)} factory it calls. The message is built
 * with string concat (which the JIT supports) and stored — no {@code String.format} (that's only on the
 * radix-out-of-range paths, never reached for radix 10). Compiled as a {@code java.base} patch.
 */
public class NumberFormatException extends IllegalArgumentException
{
    private String message;

    public NumberFormatException()
    {
    }

    /**
     * The stock {@code (String)} constructor. Its absence was the overlay trap one level down: adding
     * {@code Byte.parseByte}/{@code Short.parseShort} -- which throw it on an out-of-range value, as stock
     * does -- failed to COMPILE against this overlay. javac catching it is the lucky case; the same gap
     * reached from a stock class that already compiles surfaces on metal as a DENYLIST TRAP instead.
     */
    public NumberFormatException(String message)
    {
        this.message = message;
    }

    static NumberFormatException forInputString(String s, int radix)
    {
        NumberFormatException e = new NumberFormatException();
        e.message = "For input string: " + s;
        return e;
    }

    public String getMessage()
    {
        return message;
    }
}
