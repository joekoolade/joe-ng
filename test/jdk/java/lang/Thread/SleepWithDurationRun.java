/*
 * A hand-written runner for the UNMODIFIED SleepWithDuration JUnit test. joe-ng cannot host the JUnit engine
 * (reflection / annotations / ServiceLoader), so this main() invokes each @Test method directly (same
 * unnamed package -> package-private access). The test methods run byte-for-byte; only the framework
 * scaffolding is replaced. Each method passes if it returns without throwing.
 */
public class SleepWithDurationRun
{
    interface Body
    {
        void run() throws Throwable;
    }

    static SleepWithDuration t = new SleepWithDuration();
    static int fails = 0;

    static void run(String name, Body b)
    {
        System.out.println(name);
        try
        {
            b.run();
            System.out.println("  ok");
        }
        catch (Throwable e)
        {
            System.out.println("  FAIL " + e.getMessage());
            fails += 1;
        }
    }

    public static void main(String[] args)
    {
        run("testSleep", () -> t.testSleep());
        run("testSleepWithInterruptStatusSet", () -> t.testSleepWithInterruptStatusSet());
        run("testInterruptSleep", () -> t.testInterruptSleep());
        if (fails == 0)
        {
            System.out.println("ALL PASSED");
        }
        else
        {
            System.out.println("FAILURES=" + fails);
        }
    }
}
