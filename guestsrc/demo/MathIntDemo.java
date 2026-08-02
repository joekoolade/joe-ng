package demo;

import magic.Magic;

/**
 * Runs the UNMODIFIED JDK integer {@code Math} methods on metal: {@code floorDiv}/{@code floorMod} (pure
 * arithmetic, incl. the negative-operand rounding real code relies on) and {@code addExact} (which throws a
 * real {@code ArithmeticException} on overflow — caught here via cross-method unwind). Results printed via
 * real {@code Integer.toString}.
 */
public class MathIntDemo
{
    public static void main()
    {
        show("floorDiv(7,3)", Math.floorDiv(7, 3));         // 2
        show("floorDiv(-7,3)", Math.floorDiv(-7, 3));       // -3 (floors toward -inf, not 0)
        show("floorMod(-7,3)", Math.floorMod(-7, 3));       // 2  (sign follows divisor)
        show("floorMod(7,-3)", Math.floorMod(7, -3));       // -2
        show("addExact(100,23)", Math.addExact(100, 23));   // 123
        show("addExact(-5,-8)", Math.addExact(-5, -8));     // -13
        show("multiplyExact(1000,1000)", Math.multiplyExact(1000, 1000));   // 1000000
        show("multiplyExact(-7,8)", Math.multiplyExact(-7, 8));             // -56
        show("subtractExact(10,3)", Math.subtractExact(10, 3));            // 7
        show("negateExact(42)", Math.negateExact(42));                     // -42

        show("addExact(MAX,1) overflow", caught(0));                        // 1
        show("multiplyExact(MAX,2) overflow", caught(1));                   // 1
        show("subtractExact(MIN,1) overflow", caught(2));                   // 1
        show("negateExact(MIN) overflow", caught(3));                      // 1

        // Deep operand stack (#43): these call sites push 8/10 args -> operand depth exceeds the compiler's 7
        // registers (OP_MAX), so main() compiles via the register-window SPILL path. Weighted sums detect any
        // arg mis-ordering or spill/reload corruption. Runtime verification that deep codegen is correct.
        show("deep8(1..8)", deep8(1, 2, 3, 4, 5, 6, 7, 8));                 // 204 = sum k*k, k=1..8
        show("deep10(1..10)", deep10(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));       // 385 = sum k*k, k=1..10
        show("deepExpr(3)", deepExpr(3));                                   // 1224 (many partial products summed)
    }

    private static int deep8(int a, int b, int c, int d, int e, int f, int g, int h)
    {
        return a * 1 + b * 2 + c * 3 + d * 4 + e * 5 + f * 6 + g * 7 + h * 8;
    }

    private static int deep10(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j)
    {
        return a * 1 + b * 2 + c * 3 + d * 4 + e * 5 + f * 6 + g * 7 + h * 8 + i * 9 + j * 10;
    }

    /** A single expression keeping many partial products live at once (in-method deep stack). */
    private static int deepExpr(int x)
    {
        return ((x + 1) * (x + 2)) + ((x + 3) * (x + 4)) + ((x + 5) * (x + 6)) + ((x + 7) * (x + 8))
             + ((x + 9) * (x + 10)) + ((x + 11) * (x + 12)) + ((x + 13) * (x + 14)) + ((x + 15) * (x + 16));
    }

    /** Run the exact-op that overflows for {@code which} and report whether its ArithmeticException was caught. */
    private static int caught(int which)
    {
        try
        {
            if (which == 0) { Math.addExact(2147483647, 1); }
            else if (which == 1) { Math.multiplyExact(2147483647, 2); }
            else if (which == 2) { Math.subtractExact(-2147483648, 1); }
            else { Math.negateExact(-2147483648); }
        }
        catch (Exception e)
        {
            return 1;
        }
        return 0;
    }

    private static void show(String label, int v)
    {
        Magic.printStr("  Math.");
        Magic.printStr(label);
        Magic.printStr(" = ");
        Magic.printStr(Integer.toString(v));
        Magic.printStr("\n");
    }
}
