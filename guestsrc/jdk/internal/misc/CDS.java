package jdk.internal.misc;

/**
 * A JDK-free {@code jdk.internal.misc.CDS} overlay (wins by name). Class-data sharing is a hosted-JVM feature
 * — a memory-mapped archive of pre-initialized class state — and every entry point into it is native. joe-ng
 * has no archive, so every query answers "not archived" and every hook is a no-op, which is exactly the
 * behaviour a hosted JVM has when CDS is off.
 *
 * <p>It is here because {@code java.util.jar.Attributes$Name.<clinit>} calls
 * {@code CDS.initializeFromArchive} before building its well-known {@code Name} constants — one native call
 * standing between the stock {@code Manifest} parser and running on metal.
 */
public final class CDS
{
    private CDS()
    {
    }

    /** No archived state exists, so the class's own initializer builds everything itself. */
    public static void initializeFromArchive(Class<?> c)
    {
    }

    public static boolean isDumpingArchive()
    {
        return false;
    }

    public static boolean isSharingEnabled()
    {
        return false;
    }

    public static boolean isDumpingClassList()
    {
        return false;
    }

    public static boolean isDumpingHeap()
    {
        return false;
    }

    public static long getRandomSeedForDumping()
    {
        return 0L;
    }

    public static void logLambdaFormInvoker(String line)
    {
    }
}
