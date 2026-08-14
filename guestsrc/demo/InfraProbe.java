package demo;

/**
 * Smoke test for joe-ng's JDK-test reporting infrastructure — the shared paths nearly every jtreg
 * {@code @run main} test depends on: {@code System.out.printf}/Formatter conversions, a caught
 * {@code Throwable.printStackTrace}, and an uncaught exception propagating out of {@code main} (how a test
 * signals failure — the VM reports it JVM-style as {@code Exception in thread "main"}). Run as a manifest main.
 */
public class InfraProbe
{
    public static void main(String[] args)
    {
        // printf: flags/width/precision + %n %% %d(int/long) %x %X %o %b %c %s
        System.out.printf("summary : %nPassed = %d, failed = %d%n", 3, 0);
        System.out.printf("ints    : d=%d longd=%d hex=%08x HEX=%X oct=%o%n", 7, 42L, 255, 255, 8);
        System.out.printf("misc    : bool=%b chr=%c str=%-6s|prec=%.3s%n", true, 65, "hi", "truncated");

        // caught printStackTrace
        try
        {
            throw new IllegalStateException("caught + printed");
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }

        // uncaught -> the VM prints "Exception in thread \"main\" <class>: <message>" + the trace
        throw new RuntimeException("uncaught from main -> reported like a JVM");
    }
}
