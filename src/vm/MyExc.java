package vm;

/**
 * A throwable for the exception demo. It extends a JDK class the writer does not
 * compile ({@code RuntimeException}); the writer treats {@code java/*} supers as
 * roots and the {@code super()} call as a no-op, so only MyExc's own Type is laid
 * out — enough for {@code catch (MyExc e)} to match by exact type.
 */
public final class MyExc extends RuntimeException
{
    /** Same-method throw/catch — the metal writer's exceptionSlot (athrow/inline-catch) target. */
    public static int probe()
    {
        try
        {
            throw new MyExc();
        }
        catch (MyExc e)
        {
            return 1;
        }
    }
}
