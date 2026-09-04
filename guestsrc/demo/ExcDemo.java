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
    public static void main(String[] args)
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

        // JVMS athrow: a NULL operand throws NullPointerException, not the null. Both shapes, because they
        // take different paths: caught in the SAME method (the inline handler search) and thrown by a CALLEE
        // (the cross-method unwind). Before the check the null reached the unwinder as the thrown object and
        // the VM reported `BAD THROW exc=0x0` -- an internal diagnostic where a catch block should have run.
        int nullSame = 0;
        try
        {
            RuntimeException r = null;
            throw r;                                    // aload; athrow with null
        }
        catch (NullPointerException e)
        {
            nullSame = 1;
        }
        Magic.printStr("athrow null same-method NPE=" + nullSame + "\n");

        int nullDeep = 0;
        try
        {
            throwNull();                                // null athrow in a CALLEE -> unwinds to here
        }
        catch (NullPointerException e)
        {
            nullDeep = 1;
        }
        Magic.printStr("athrow null cross-method NPE=" + nullDeep + "\n");

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

    /** athrow with a null operand, in a method that does NOT catch it -- forces the cross-method unwind. */
    private static void throwNull()
    {
        RuntimeException r = null;
        throw r;
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
        throw new IllegalArgumentException("bad arg 42");   // explicit `new` (RTA flags it instantiated -> its
    }                                                       // inherited Throwable.printStackTrace gets compiled)
}
