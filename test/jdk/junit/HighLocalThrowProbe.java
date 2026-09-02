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

    /** Seven reference args + the receiver = 8 operand entries, one past joe-ng's OP_MAX of 7. */
    void sink(Object a, Object b, Object c, Object d, Object e, Object f, Object g)
    {
    }

    /**
     * THE CONDITION the first probe lacked: {@code max_stack = 8}, one past {@code OP_MAX = 7}, so the method
     * compiles through the DEEP-SPILL path where operands live in memory rather than registers.
     *
     * <p>picocli's failing caller is {@code Interpreter.parse}, and its verifier attributes are
     * {@code stack=8, locals=15, args_size=7} -- deep by exactly one. The catch then hands the caught
     * exception to an instance helper, which is where the throw delivered {@code this}.
     */
    String runDeep()
    {
        Object o = "x";
        try
        {
            sink(o, o, o, o, o, o, o);              // 8 operand entries: past OP_MAX, deepStack is set
            throw new Boom("deep");
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
                return again.getMessage();
            }
        }
    }

    /** A: the deep stack ALONE -- no exception anywhere. */
    String deepOnly()
    {
        Object o = "x";
        sink(o, o, o, o, o, o, o);
        return "A-ok";
    }

    /** B: deep stack + throw/catch, but NOTHING called from the catch. */
    String deepCatch()
    {
        Object o = "x";
        try
        {
            sink(o, o, o, o, o, o, o);
            throw new Boom("B");
        }
        catch (Boom caught)
        {
            return caught.getMessage();
        }
    }

    /**
     * D: deep stack + a NESTED try/catch inside the catch, with NO call in the inner try. Separates "nested
     * handler" from "call from a handler" -- arm B already calls {@code getMessage()} from its catch and
     * passes, so a call alone is NOT the trigger.
     */
    String deepNested()
    {
        Object o = "x";
        try
        {
            sink(o, o, o, o, o, o, o);
            throw new Boom("D");
        }
        catch (Boom caught)
        {
            try
            {
                if (caught == null)
                {
                    throw new Boom("unreachable");
                }
                return caught.getMessage();
            }
            catch (RuntimeException inner)
            {
                return "inner:" + inner.getMessage();
            }
        }
    }

    /** C: deep stack + catch + a nested try whose body CALLS a helper -- the full picocli shape ({@link #runDeep}). */

    public static void main(String[] args)
    {
        HighLocalThrowProbe p = new HighLocalThrowProbe();
        // Bisect the condition in ONE boot: deep stack alone, then + catch, then + a call from the catch.
        System.out.println("A deepOnly  = " + p.deepOnly() + " (want A-ok)");
        System.out.println("B deepCatch = " + p.deepCatch() + " (want B)");
        System.out.println("D deepNested= " + p.deepNested() + " (want D)");
        System.out.println("result = " + p.run() + " (want boom:66)");
        // The deep-stack arm: if athrow delivers `this` here, the result is an Interpreter-shaped failure
        // rather than the message -- the picocli condition, reproduced in ten lines instead of a 700 s boot.
        System.out.println("deep   = " + p.runDeep() + " (want deep)");
        System.out.println("[probe done]");
    }
}
