/*
 * A hand-written runner for the UNMODIFIED JoinWithDuration JUnit test. joe-ng cannot host the JUnit engine
 * (reflection / annotations / ServiceLoader), so instead of a @Test-discovery launcher this main() invokes
 * each @Test method directly (same unnamed package -> package-private access). The test METHODS run byte-for-
 * byte; only the framework scaffolding is replaced. Each method passes if it returns without throwing.
 */
public class JoinWithDurationRun
{
    interface Body
    {
        void run() throws Throwable;
    }

    static JoinWithDuration t = new JoinWithDuration();
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
            System.out.println("  FAIL");
            fails += 1;
        }
    }

    public static void main(String[] args)
    {
        run("testJoinOnUnstartedThread", () -> t.testJoinOnUnstartedThread());
        run("testJoinOnRunningThread", () -> t.testJoinOnRunningThread());
        run("testJoinOnTerminatingThread", () -> t.testJoinOnTerminatingThread());
        run("testJoinOnTerminatedThread", () -> t.testJoinOnTerminatedThread());
        run("testJoinWithInterruptStatusSet", () -> t.testJoinWithInterruptStatusSet());
        run("testInterruptJoin", () -> t.testInterruptJoin());
        run("testJoinSelf", () -> t.testJoinSelf());
        run("testJoinNull", () -> t.testJoinNull());
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
