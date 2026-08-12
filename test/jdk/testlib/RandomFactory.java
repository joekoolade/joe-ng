package jdk.test.lib;

import java.util.Random;

/**
 * A minimal stand-in for the JDK test library's {@code jdk.test.lib.RandomFactory} -- the stock one derives a
 * reproducible seed from {@code jdk.test.lib.Utils} (system properties, streams), which is infeasible on metal.
 * The tests only need "a Random"; this returns one from joe-ng's {@link java.util.Random} overlay.
 */
public class RandomFactory
{
    public static Random getRandom()
    {
        return new Random();
    }
}
