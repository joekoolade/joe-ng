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

        int caught = 0;
        try
        {
            int x = Math.addExact(2147483647, 1);           // MAX_VALUE + 1 -> overflow -> ArithmeticException
        }
        catch (Exception e)
        {
            caught = 1;
        }
        show("addExact(MAX,1) overflow caught", caught);    // 1
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
