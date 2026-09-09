package java.util.logging;

/**
 * A logging level, with the standard names and integer values.
 *
 * <p>NOT an overlay: {@code java.util.logging} lives in the {@code java.logging} MODULE, which joe-ng's image
 * does not carry at all, so there is no stock class here to shadow. The standing rule that {@code guestsrc/}
 * is for classes needing natives is about SHADOWING a stock class; this is a provision, not a replacement.
 *
 * <p>The integer values are stock's, because callers compare them: JUnit gates on {@code isLoggable}, and a
 * level whose value did not order correctly against the others would silently change which records survive.
 */
public class Level
{
    public static final Level OFF = new Level("OFF", Integer.MAX_VALUE);
    public static final Level SEVERE = new Level("SEVERE", 1000);
    public static final Level WARNING = new Level("WARNING", 900);
    public static final Level INFO = new Level("INFO", 800);
    public static final Level CONFIG = new Level("CONFIG", 700);
    public static final Level FINE = new Level("FINE", 500);
    public static final Level FINER = new Level("FINER", 400);
    public static final Level FINEST = new Level("FINEST", 300);
    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE);

    private final String name;
    private final int value;

    protected Level(String name, int value)
    {
        this.name = name;
        this.value = value;
    }

    public String getName()
    {
        return name;
    }

    public final int intValue()
    {
        return value;
    }

    public String toString()
    {
        return name;
    }

    public boolean equals(Object other)
    {
        if (!(other instanceof Level))
        {
            return false;
        }
        return ((Level) other).value == value;
    }

    public int hashCode()
    {
        return value;
    }
}
