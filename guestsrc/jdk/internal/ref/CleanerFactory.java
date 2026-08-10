package jdk.internal.ref;

import java.lang.ref.Cleaner;

/** Name-winning overlay: hands out the one synchronous {@link Cleaner} (see the Cleaner overlay). */
public final class CleanerFactory
{
    private static final Cleaner COMMON = Cleaner.create();

    private CleanerFactory()
    {
    }

    public static Cleaner cleaner()
    {
        return COMMON;
    }
}
