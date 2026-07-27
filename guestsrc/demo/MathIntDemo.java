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
