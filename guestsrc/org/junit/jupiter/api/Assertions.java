package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;

/**
 * A minimal stand-in for JUnit 5's {@code Assertions}: the assertion methods the JoinWithDuration test uses.
 * Each throws {@code AssertionError} on failure (caught by the runner). {@code assertThrows} compares the
 * thrown exception's class by identity to the expected type (exact match -- sufficient for these tests).
 */
public class Assertions
{
    private Assertions()
    {
    }

    public static void assertTrue(boolean condition)
    {
        if (!condition)
        {
            throw new AssertionError("expected true");
        }
    }

    public static void assertTrue(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition)
    {
        if (condition)
        {
            throw new AssertionError("expected false");
        }
    }

    public static void fail()
    {
        throw new AssertionError("fail");
    }

    public static void assertThrows(Class expectedType, Executable executable)
    {
        try
        {
            executable.execute();
        }
        catch (Throwable actual)
        {
            // isInstance walks the exception's Type chain (subtypes count, per JUnit), and -- unlike a Class
            // identity `==` -- doesn't depend on X.class literals in different classes sharing a mirror.
            if (expectedType.isInstance(actual))
            {
                return;                                    // the expected (sub)type was thrown
            }
            throw new AssertionError("wrong exception thrown");
        }
        throw new AssertionError("expected exception not thrown");
    }
}
