package demo;

import java.util.function.IntSupplier;

/**
 * A lambda created inside a method whose OPERAND STACK IS DEEP.
 *
 * <p>The JIT keeps up to {@code OP_MAX = 7} operands in registers and spills the rest to frame memory
 * ({@code deepStack}). {@code lowerLambda} used to refuse that mode outright -- it read each capture with a
 * raw {@code OP_BASE + slot} register number, which is only valid while every operand is resident -- so a
 * lambda in a deep method halted the compile with {@code JIT unsupported: reason=0 a=0xBA b=3}. That is what
 * stopped the console launcher, whose stream-chaining {@code ClasspathScannerLoader.getInstance()} goes deep.
 *
 * <p>The shape matters more than the values: EIGHT operands must already be on the stack when the lambda is
 * constructed, because seven or fewer stay in registers and compile the shallow way -- a probe that did not
 * pass OP_MAX would test nothing. Both arms capture SEVERAL locals, since the bug was in how captures are
 * read, and a zero-capture lambda would read nothing at all.
 */
public class DeepLambdaDemo
{
    static int sum(int a, int b, int c, int d, int e, int f, int g, int h, IntSupplier s, int i)
    {
        return a + b + c + d + e + f + g + h + s.getAsInt() + i;
    }

    public static void main(String[] args)
    {
        int p = 100;
        int q = 20;
        int r = 3;
        // Eight ints are live on the operand stack when the lambda is built -- past OP_MAX, so this method
        // compiles in deepStack mode and the captures come from frame memory rather than registers.
        int total = sum(1, 2, 3, 4, 5, 6, 7, 8, () -> p + q + r, 9);
        System.out.println("deep lambda total = " + total + " (want 168)");

        // Again with different captures, so a stale register would show as a WRONG number rather than a crash.
        int u = 1000;
        int v = 200;
        int w = 30;
        int t2 = sum(9, 8, 7, 6, 5, 4, 3, 2, () -> u + v + w, 1);
        System.out.println("deep lambda total2= " + t2 + " (want 1275)");

        // A deep stack whose lambda captures an OBJECT: a wrong slot here is a bad reference, not a bad int.
        String s = "deep";
        int t3 = sum(1, 1, 1, 1, 1, 1, 1, 1, () -> s.length(), 1);
        System.out.println("deep lambda objcap= " + t3 + " (want 13)");

        System.out.println("DeepLambdaDemo done");
    }
}
