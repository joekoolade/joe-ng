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

    /** Bare throw — no local handler, so the exception must unwind to the caller's catch. */
    public static int throwIt()
    {
        throw new MyExc();
    }

    /** Cross-method throw/catch: calls {@link #throwIt} (which throws), catching here — the
     *  metal writer's cross-method unwind target (a throw in one built method resumes in another). */
    public static int catchIt()
    {
        try
        {
            throwIt();
            return 0;
        }
        catch (MyExc e)
        {
            return 1;
        }
    }
}
