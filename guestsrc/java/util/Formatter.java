package java.util;

/**
 * Bare-metal stub of {@code java.util.Formatter}. Stock {@code String.format} is {@code new Formatter()
 * .format(fmt, args).toString()}, and it is reached on never-taken ERROR/message paths of otherwise-used stock
 * methods (e.g. {@code Integer.parseInt}, {@code jdk.internal.util.Preconditions.outOfBoundsMessage}). Pulling
 * the real Formatter drags in Locale, Calendar, java.time, java.util.regex, ResourceBundle and the whole
 * printf machinery. This stub keeps only the three members {@code String.format} links against; because the
 * format path is compiled but never executed on metal, the trivial bodies are never actually run.
 */
public final class Formatter
{
    public Formatter()
    {
    }

    public Formatter format(String fmt, Object... args)
    {
        return this;
    }

    public String toString()
    {
        return "";
    }
}
