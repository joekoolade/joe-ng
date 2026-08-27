package demo;

/**
 * Named ONLY from a method that is itself reached only through {@code Method.invoke}. Nothing statically
 * reachable mentions this class, so RTA never marks it and the demand-load closure never contains it — the
 * call site naming {@link #tag()} is the one late link resolution has to close.
 */
public class RtaUnseen
{
    public static String tag()
    {
        return "unseen";
    }
}
