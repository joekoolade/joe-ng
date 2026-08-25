package demo;

/**
 * Does an int that OVERFLOWS stay a canonical (sign-extended) 32-bit value in its 64-bit register?
 *
 * <p>The baseline compiler's stated invariant is that ints live sign-extended in 64-bit registers
 * ({@code Baseline.iushr} depends on it), but {@code iadd}/{@code imul} emit plain 64-bit ADD/MUL, so an
 * overflowing int keeps its bits above 31. That is invisible to anything that masks — {@code Integer.toString},
 * {@code &}, a 32-bit compare — and fatal to {@code idiv}/{@code irem}, which are 64-bit SDIV: the operand is
 * a huge positive number instead of a negative int. {@code Math.floorMod(hash, n)} then returns a NEGATIVE
 * index, which is how {@code Map.copyOf} (via {@code ImmutableCollections.MapN.probe}) walked off its table.
 */
public class DivDemo
{
    public static void main(String[] args)
    {
        int a = 1 << 30;
        int b = a + a;                                     // overflows to INT_MIN
        System.out.print("b dec  =");
        System.out.println(Integer.toString(b));           // -2147483648
        System.out.print("b neg  =");
        System.out.println(Integer.toString(b < 0 ? 1 : 0));         // 1
        System.out.print("b rem  =");
        System.out.println(Integer.toString(b % 60));                // -8
        System.out.print("b fmod =");
        System.out.println(Integer.toString(Math.floorMod(b, 60)));  // 52

        int m = 65536 * 65536;                             // imul overflow -> 0
        System.out.print("m dec  =");
        System.out.println(Integer.toString(m));           // 0
        System.out.print("m rem  =");
        System.out.println(Integer.toString(m % 60));      // 0

        int h = hash("Manifest-Version");
        System.out.print("h dec  =");
        System.out.println(Integer.toString(h));           // 1003645754
        System.out.print("h fmod =");
        System.out.println(Integer.toString(Math.floorMod(h, 60)));  // 14
    }

    /** String.hashCode's recurrence, which overflows for any string of a few characters. */
    private static int hash(String s)
    {
        int h = 0;
        int i = 0;
        while (i < s.length())
        {
            h = 31 * h + s.charAt(i);
            i += 1;
        }
        return h;
    }
}
