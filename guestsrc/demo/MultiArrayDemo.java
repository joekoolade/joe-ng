package demo;

/**
 * {@code multianewarray} (0xC5, JVMS 6.5) -- the last opcode a class file may legally contain that this JIT
 * could not compile. {@code new int[a][b]} simply did not work; javac emits this for every multidimensional
 * array creation, and java.base has 16 of them (every one with a dimensions operand of 2).
 *
 * <p>WHICH ARMS ACTUALLY DISCRIMINATE, measured rather than assumed -- a control that recursed on the type's
 * RANK instead of on the dimensions operand moved EXACTLY ONE line of this output:
 * <ul>
 * <li>{@code new short[a][b][]} -- dimensions FEWER than the type's rank, so the innermost level allocated
 *     holds references that must stay NULL. This is the arm the control failed ({@code null = 0}), and the
 *     only one; it is what makes the demo worth a boot.</li>
 * <li>{@code new int[2][-1]} must throw, and throwing on the SECOND count is what proves every dimension is
 *     checked before anything is allocated -- the order JVMS 6.5 specifies.</li>
 * <li>The per-level {@code getClass().getName()} arms, which is where a wrong TIB would show: lengths and
 *     values come back right even if every level were typed {@code Object[]}.</li>
 * </ul>
 *
 * <p>AND WHICH DO NOT, stated so nobody reads more into them than is there: the ELEMENT SIZE arms are weak.
 * Getting it too LARGE merely over-allocates and every read and write still lands correctly, so
 * {@code byte[2][4]} passes either way; only an under-sized allocation would corrupt, and that is not what
 * a plausible mistake here looks like. They are kept as shape coverage, not as a control.
 */
public class MultiArrayDemo
{
    /** Opaque to javac's constant folding, so each count is a real runtime value. */
    private static int n(int v)
    {
        return v;
    }

    private static String arm(String what, Runnable r)
    {
        try
        {
            r.run();
            return what + " = no exception (WRONG)";
        }
        catch (NegativeArraySizeException e)
        {
            return what + " = NegativeArraySizeException";
        }
        catch (Throwable t)
        {
            return what + " = " + t.getClass().getName() + " (WRONG)";
        }
    }

    public static void main(String[] args)
    {
        // int[][] : lengths, element round-trip, and the TYPE at both levels.
        int[][] a = new int[n(2)][n(3)];
        a[1][2] = 42;
        a[0][0] = 7;
        System.out.println("int[2][3] len      = " + a.length + "/" + a[0].length + " (want 2/3)");
        System.out.println("int[2][3] values   = " + a[1][2] + "/" + a[0][0] + " (want 42/7)");
        System.out.println("int[2][3] outer    = " + a.getClass().getName() + " (want [[I)");
        System.out.println("int[2][3] inner    = " + a[0].getClass().getName() + " (want [I)");
        System.out.println("int[2][3] rows sep = " + (a[0] != a[1] ? 1 : 0) + " (want 1)");

        // byte[][] : a one-byte element type. The LENGTHS are right either way -- the inner TYPE is not.
        byte[][] b = new byte[n(2)][n(4)];
        b[1][3] = (byte) -5;
        System.out.println("byte[2][4] inner   = " + b[0].getClass().getName() + " (want [B)");
        System.out.println("byte[2][4] isByte  = " + (b[0] instanceof byte[] ? 1 : 0) + " (want 1)");
        System.out.println("byte[2][4] value   = " + b[1][3] + " (want -5)");

        // String[][] : a reference element type at the innermost level.
        String[][] s = new String[n(2)][n(2)];
        s[1][1] = "x";
        System.out.println("String[2][2] inner = " + s[0].getClass().getName() + " (want [Ljava.lang.String;)");
        System.out.println("String[2][2] null  = " + (s[0][0] == null ? 1 : 0) + " (want 1)");
        System.out.println("String[2][2] set   = " + s[1][1] + " (want x)");

        // FEWER DIMENSIONS THAN THE RANK: [[[S with a dimensions operand of 2.
        short[][][] t = new short[n(2)][n(3)][];
        System.out.println("short[2][3][] len  = " + t.length + "/" + t[0].length + " (want 2/3)");
        System.out.println("short[2][3][] null = " + (t[0][0] == null ? 1 : 0) + " (want 1)");
        System.out.println("short[2][3][] mid  = " + t[0].getClass().getName() + " (want [[S)");

        // A zero outer dimension allocates nothing below it, and must not fault.
        int[][] z = new int[n(0)][n(5)];
        System.out.println("int[0][5] len      = " + z.length + " (want 0)");
        int[][] zi = new int[n(3)][n(0)];
        System.out.println("int[3][0] len      = " + zi.length + "/" + zi[0].length + " (want 3/0)");

        // NEGATIVE counts. The second arm is the one with teeth: it proves the check reaches EVERY
        // dimension, not just the outermost, and that it happens before anything is allocated.
        System.out.println(arm("int[-1][2]         ", () -> { int[][] x = new int[n(-1)][n(2)]; }));
        System.out.println(arm("int[2][-1]         ", () -> { int[][] x = new int[n(2)][n(-1)]; }));
        System.out.println(arm("byte[-1][-1]       ", () -> { byte[][] x = new byte[n(-1)][n(-1)]; }));

        // Three dimensions, fully allocated -- the recursion runs one level deeper than anything java.base
        // asks for, so a 2-only implementation fails here rather than shipping untested.
        int[][][] d3 = new int[n(2)][n(2)][n(2)];
        d3[1][1][1] = 9;
        System.out.println("int[2][2][2] deep  = " + d3[1][1][1] + " (want 9)");
        System.out.println("int[2][2][2] inner = " + d3[0][0].getClass().getName() + " (want [I)");
    }
}
