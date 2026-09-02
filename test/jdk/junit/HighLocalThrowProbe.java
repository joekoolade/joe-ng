/**
 * Reproduces the SHAPE of picocli's failing throw: a caught exception stored in a local slot PAST the
 * register window, then passed to an instance helper whose whole body is `aload_1; athrow`.
 *
 * <p>joe-ng maps JVM locals to callee-saved x19..x28 -- ten registers, so slots 0..9 -- and anything beyond
 * spills to the frame. picocli's `Interpreter` catches into local 12 and calls `maybeThrow(ex)`, and the throw
 * delivered `this` instead of `ex`.
 *
 * <p><b>IT PASSES, and that is the point of keeping it.</b> javac puts the catch variable in slot 24 here --
 * far past the register window -- and the value survives the overflow spill, the call boundary and the
 * rethrow intact. So the high-local hypothesis for the picocli bug is REFUTED, and this pins the shape that
 * works so the search moves on rather than circling back.
 *
 * <p>What this does NOT reproduce is picocli's other conditions: an enormous method with a DEEP operand stack
 * (past {@code OP_MAX = 7}, into the spill path), compiled LAZILY rather than in a batch. Reproducing the
 * shape is not reproducing the condition -- the mistake this file exists to record.
 */
public class HighLocalThrowProbe
{
    static class Boom extends RuntimeException
    {
        Boom(String m)
        {
            super(m);
        }
    }

    /** Exactly picocli's maybeThrow: an INSTANCE method whose body is aload_1; athrow. */
    void maybeThrow(Boom ex)
    {
        throw ex;
    }

    /** Eleven live locals before the catch, so the catch variable lands past x19..x28. */
    String run()
    {
        long a = 1, b = 2, c = 3, d = 4, e = 5, f = 6, g = 7, h = 8, i = 9, j = 10, k = 11;
        try
        {
            if (a + b + c + d + e + f + g + h + i + j + k > 0)
            {
                throw new Boom("boom");
            }
            return "no-throw";
        }
        catch (Boom caught)
        {
            try
            {
                maybeThrow(caught);
                return "not-rethrown";
            }
            catch (Boom again)
            {
                return again.getMessage() + ":" + (a + b + c + d + e + f + g + h + i + j + k);
            }
        }
    }

    public static void main(String[] args)
    {
        String r = new HighLocalThrowProbe().run();
        System.out.println("result = " + r + " (want boom:66)");
        System.out.println("[probe done]");
    }
}
