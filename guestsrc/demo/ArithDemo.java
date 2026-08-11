package demo;

/** Divide-by-zero smoke test: an uncaught integer / by zero must throw ArithmeticException (AArch64 SDIV
 *  returns 0 and never traps, so the JIT emits an explicit divisor-zero check). */
public class ArithDemo
{
    public static void main(String[] args)
    {
        int a = 5;
        int b = args.length;      // 0 (non-constant, so javac keeps the idiv)
        int x = a / b;            // ArithmeticException: / by zero
        System.out.println(x);
    }
}
