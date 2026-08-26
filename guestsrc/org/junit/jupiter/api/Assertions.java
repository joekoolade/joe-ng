package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;

/**
 * A minimal stand-in for JUnit 5's {@code Assertions}: the assertion methods the stock jtreg JUnit tests
 * joe-ng runs actually call.
 * Each throws {@code AssertionError} on failure, which the hand-written runner catches and reports.
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

    public static void fail(String message)
    {
        throw new AssertionError(message);
    }

    /**
     * Runs {@code executable} and RETURNS the thrown exception, because callers routinely bind it
     * ({@code IOException e = assertThrows(...)}) and then assert on its message.
     */
    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable)
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
                return (T) actual;                         // the expected (sub)type was thrown
            }
            throw new AssertionError("wrong exception thrown: " + actual);
        }
        throw new AssertionError("expected exception not thrown");
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable,
                                                       String message)
    {
        return assertThrows(expectedType, executable);
    }

    // ----- equality ---------------------------------------------------------------------------------
    // Overloaded per primitive width rather than boxed, so a failing int comparison does not depend on
    // Integer.valueOf caching or on autoboxing reaching equals() at all.

    public static void assertEquals(long expected, long actual)
    {
        if (expected != actual)
        {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    public static void assertEquals(long expected, long actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual)
    {
        if (!eq(expected, actual))
        {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message)
    {
        if (!eq(expected, actual))
        {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual)
    {
        if (eq(unexpected, actual))
        {
            throw new AssertionError("expected not " + unexpected);
        }
    }

    private static boolean eq(Object a, Object b)
    {
        if (a == b)
        {
            return true;
        }
        if (a == null || b == null)
        {
            return false;
        }
        return a.equals(b);
    }

    // ----- identity and nullity ---------------------------------------------------------------------

    public static void assertSame(Object expected, Object actual)
    {
        if (expected != actual)
        {
            throw new AssertionError("expected the same object");
        }
    }

    public static void assertSame(Object expected, Object actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message);
        }
    }

    public static void assertNotSame(Object unexpected, Object actual)
    {
        if (unexpected == actual)
        {
            throw new AssertionError("expected a different object");
        }
    }

    public static void assertNull(Object actual)
    {
        if (actual != null)
        {
            throw new AssertionError("expected null but was " + actual);
        }
    }

    public static void assertNull(Object actual, String message)
    {
        if (actual != null)
        {
            throw new AssertionError(message + ": expected null but was " + actual);
        }
    }

    public static void assertNotNull(Object actual)
    {
        if (actual == null)
        {
            throw new AssertionError("expected non-null");
        }
    }

    public static void assertNotNull(Object actual, String message)
    {
        if (actual == null)
        {
            throw new AssertionError(message);
        }
    }

    public static <T> T assertInstanceOf(Class<T> expectedType, Object actual)
    {
        if (!expectedType.isInstance(actual))
        {
            throw new AssertionError("not an instance of the expected type");
        }
        return (T) actual;
    }

    // ----- arrays -----------------------------------------------------------------------------------
    // Element-by-element, reporting the first differing index: "arrays differ" alone is not a diagnosis.

    public static void assertArrayEquals(byte[] expected, byte[] actual)
    {
        assertArrayEquals(expected, actual, "byte[]");
    }

    public static void assertArrayEquals(byte[] expected, byte[] actual, String message)
    {
        if (expected == actual)
        {
            return;
        }
        if (expected == null || actual == null)
        {
            throw new AssertionError(message + ": one array is null");
        }
        if (expected.length != actual.length)
        {
            throw new AssertionError(message + ": expected length " + expected.length
                                     + " but was " + actual.length);
        }
        int i = 0;
        while (i < expected.length)
        {
            if (expected[i] != actual[i])
            {
                throw new AssertionError(message + ": differ at [" + i + "], expected " + expected[i]
                                         + " but was " + actual[i]);
            }
            i += 1;
        }
    }

    public static void assertArrayEquals(int[] expected, int[] actual)
    {
        if (expected == actual)
        {
            return;
        }
        if (expected == null || actual == null || expected.length != actual.length)
        {
            throw new AssertionError("int[] arrays differ");
        }
        int i = 0;
        while (i < expected.length)
        {
            if (expected[i] != actual[i])
            {
                throw new AssertionError("int[] differ at [" + i + "], expected " + expected[i]
                                         + " but was " + actual[i]);
            }
            i += 1;
        }
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual)
    {
        if (expected == actual)
        {
            return;
        }
        if (expected == null || actual == null || expected.length != actual.length)
        {
            throw new AssertionError("Object[] arrays differ");
        }
        int i = 0;
        while (i < expected.length)
        {
            if (!eq(expected[i], actual[i]))
            {
                throw new AssertionError("Object[] differ at [" + i + "]");
            }
            i += 1;
        }
    }

    // ----- misc -------------------------------------------------------------------------------------

    public static void assertDoesNotThrow(Executable executable)
    {
        try
        {
            executable.execute();
        }
        catch (Throwable t)
        {
            throw new AssertionError("unexpected exception: " + t);
        }
    }
}
