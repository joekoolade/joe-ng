package demo;

import magic.Magic;

/**
 * Verifies implicit (JVM-synthesised) exceptions in demand-loaded code: the JIT now emits null-checks
 * (deref of null -> {@code NullPointerException}) and array-bounds checks (bad index ->
 * {@code ArrayIndexOutOfBoundsException}), throwing a real mini exception object that catch clauses
 * (matched via {@code instanceof} on the type chain) catch. Covers a main-local catch and a
 * cross-method unwind (a bounds check deep inside {@code String.charAt}, caught here).
 */
public class ExcDemo
{
    public static void main()
    {
        int npe = 0;
        String n = null;
        try
        {
            int len = n.length();                       // invokevirtual on null -> NPE (main-local)
        }
        catch (Exception e)
        {
            npe = 1;
        }
        Magic.printStr("npe caught=" + npe + "\n");

        int aioobe = 0;
        int[] a = new int[3];
        try
        {
            int x = a[5];                               // iaload out of bounds -> AIOOBE (main-local)
        }
        catch (Exception e)
        {
            aioobe = 1;
        }
        Magic.printStr("array aioobe caught=" + aioobe + "\n");

        int deep = 0;
        String s = "hi";
        try
        {
            char c = s.charAt(99);                      // bounds check INSIDE String.charAt -> AIOOBE, unwinds here
        }
        catch (Exception e)
        {
            deep = 1;
        }
        Magic.printStr("charAt aioobe caught=" + deep + "\n");

        Magic.printStr("after: still running\n");       // control returned normally from every catch

        // printStackTrace(): throw a few frames deep, catch, and print the backtrace captured at throw time by
        // VM.unwind (Throwable.bt0..bt7 -> Loader.printFrameAt). Expect level3 (throw) <- level2 <- level1 <- main.
        try
        {
            level1();
        }
        catch (Exception e)
        {
            Magic.printStr("printStackTrace:");
            e.printStackTrace();
        }
    }

    private static void level1()
    {
        level2();
    }

    private static void level2()
    {
        level3();
    }

    private static void level3()
    {
        throw new IllegalArgumentException();           // explicit `new` (RTA flags it instantiated -> its
    }                                                   // inherited Throwable.printStackTrace gets compiled)
}
