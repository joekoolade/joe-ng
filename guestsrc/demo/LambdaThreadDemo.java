package demo;

/**
 * {@code new Thread(lambda).start()} — a Runnable that is a LAMBDA rather than a named class.
 *
 * <p>Every existing thread demo (SmpDemo, PrioDemo, PipDemo) passes a real class, so the lambda shape has
 * never been exercised: the spawned task enters the run-trampoline, which dispatches {@code run()} through
 * the receiver's itable, and a lambda's itable is SYNTHESISED by {@code buildLambdaTib} rather than built
 * from a classfile. Kept tiny on purpose — a fault here has an empty stack (a fresh task) and names nothing,
 * so the smallest possible reproduction is worth much more than a big one.
 */
public class LambdaThreadDemo
{
    static volatile int ran;

    public static void main(String[] args) throws Exception
    {
        Thread t = new Thread(() -> { ran = 42; });
        t.start();
        t.join();
        System.out.println("  lambda thread ran = " + ran + " (want 42)");

        // A capturing lambda too: its thunk carries a bound argument, a different thunk shape.
        final int base = 100;
        Thread c = new Thread(() -> { ran = base + 5; });
        c.start();
        c.join();
        System.out.println("  capturing lambda ran = " + ran + " (want 105)");

        // The shape SleepSanity actually has: the thread is created inside a method reached ONLY through
        // Method.invoke, so its body compiles after the batch that would have pulled what the lambda needs.
        java.lang.reflect.Method m = LambdaThreadDemo.class.getDeclaredMethod("viaReflection");
        m.setAccessible(true);
        Object r = m.invoke(null);
        System.out.println("  reflective lambda thread = " + r + " (want 7)");
    }

    /** Reached only reflectively -- RTA never walks this body. */
    static String viaReflection() throws Exception
    {
        Thread t = new Thread(() -> { ran = 7; });
        t.setDaemon(true);
        t.start();
        t.join();
        return "" + ran;
    }
}
