/**
 * The narrowest end-to-end test of a JUnit assertion message: call Assertions.assertEquals directly and
 * report what comes back. Isolates JUnit's own message plumbing from the MetalJUnit runner around it.
 */
public class AssertMsgProbe
{
    public static void main(String[] args) throws Exception
    {
        System.out.println("assert message probe:");
        one("assertEquals(2,3)");
        System.out.println("survived");
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
