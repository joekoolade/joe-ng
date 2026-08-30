/**
 * The narrowest end-to-end test of a JUnit assertion message: call Assertions.assertEquals directly and
 * report what comes back. Isolates JUnit's own message plumbing from the MetalJUnit runner around it.
 */
public class AssertMsgProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("assert message probe:");
        one("assertEquals(2,3) direct");
        reflective("assertEquals(2,3) via Method.invoke");
        // The BOOT RE-ENTERED repro: getStackTrace() from guest code (printStackTrace works; this path did not).
        System.out.println("stackTrace probe:");
        try
        {
            throw new IllegalStateException("boom");
        }
        catch (Throwable t)
        {
            StackTraceElement[] tr = t.getStackTrace();
            System.out.println("  getStackTrace null? " + (tr == null));
            if (tr != null)
            {
                System.out.println("  frames = " + tr.length);
                int i = 0;
                while (i < tr.length && i < 4)
                {
                    System.out.println("    at " + tr[i]);
                    i += 1;
                }
            }
        }
        System.out.println("survived");
    }

    /** The MetalJUnit path: same assertion, reached through reflection. */
    private static void reflective(String what)
    {
        try
        {
            java.lang.reflect.Method m = AssertMsgProbe.class.getDeclaredMethod("boom");
            m.setAccessible(true);
            m.invoke(new AssertMsgProbe());
            System.out.println("  " + what + " -> DID NOT THROW");
        }
        catch (Throwable t)
        {
            Throwable c = t;
            if (t instanceof java.lang.reflect.InvocationTargetException)
            {
                Throwable inner = ((java.lang.reflect.InvocationTargetException) t).getCause();
                System.out.println("  ITE.getCause() null? " + (inner == null));
                if (inner != null)
                {
                    c = inner;
                }
            }
            System.out.println("  " + what + " -> " + c.getClass().getName());
            System.out.println("  message = [" + c.getMessage() + "]");
        }
    }

    public void boom()
    {
        org.junit.jupiter.api.Assertions.assertEquals(2, 3);
    }

    private static void one(String what)
    {
        try
        {
            org.junit.jupiter.api.Assertions.assertEquals(2, 3);
            System.out.println("  " + what + " -> DID NOT THROW");
        }
        catch (Throwable t)
        {
            System.out.println("  " + what + " -> " + t.getClass().getName());
            String m;
            try
            {
                m = t.getMessage();
            }
            catch (Throwable inner)
            {
                m = "getMessage THREW " + inner.getClass().getName();
            }
            System.out.println("  message = [" + m + "]");
        }
    }
}
