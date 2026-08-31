package demo;

/**
 * Methods with more arguments than there are argument registers.
 *
 * <p>x0..x15 carry arguments, so a 17-parameter method had nowhere to put the 17th and the JIT refused it
 * outright ({@code JIT unsupported: reason=11}). Past {@code MAX_ARG_REGS} the last register changes meaning:
 * x0..x14 carry arguments 0..14 and x15 points at the rest, staged in the caller's own frame.
 *
 * <p>Each arm pins a different part of that:
 * <ul>
 *   <li>{@code sum20} -- a static call, 20 arguments, all past the boundary reached by value;
 *   <li>{@code tally17} -- an INSTANCE call, so the receiver occupies x0 and the boundary falls one argument
 *       earlier than the parameter count suggests;
 *   <li>{@code wide} -- a bare {@code long} in the overflow region. It is ONE argument register but TWO local
 *       slots, and getting that stepping wrong shifts every later parameter (a bug this VM has had before).
 * </ul>
 * The expected values are deliberately position-sensitive: a permuted or duplicated argument changes them.
 */
public class ManyArgsDemo
{
    public static void main(String[] args)
    {
        System.out.println("sum20 = " + sum20(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                                              11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
                           + " (want 210)");

        // Weighted, so ORDER matters: a swapped pair changes the answer.
        System.out.println("weighted20 = " + weighted20(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                                                        11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
                           + " (want 2870)");

        ManyArgsDemo d = new ManyArgsDemo();
        d.base = 1000;
        System.out.println("tally17 = " + d.tally17(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                                                    11, 12, 13, 14, 15, 16, 17)
                           + " (want 1153)");

        System.out.println("wide = " + wide(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                                            11, 12, 13, 14, 15, 7000000000L, 17, 18)
                           + " (want 7000000155)");
    }

    int base;

    static int sum20(int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
                     int a11, int a12, int a13, int a14, int a15, int a16, int a17, int a18, int a19, int a20)
    {
        return a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
             + a11 + a12 + a13 + a14 + a15 + a16 + a17 + a18 + a19 + a20;
    }

    /** Each argument weighted by its POSITION: sum(k*k) for k=1..20 = 2870. */
    static int weighted20(int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
                          int a11, int a12, int a13, int a14, int a15, int a16, int a17, int a18, int a19,
                          int a20)
    {
        return 1 * a1 + 2 * a2 + 3 * a3 + 4 * a4 + 5 * a5 + 6 * a6 + 7 * a7 + 8 * a8 + 9 * a9 + 10 * a10
             + 11 * a11 + 12 * a12 + 13 * a13 + 14 * a14 + 15 * a15 + 16 * a16 + 17 * a17 + 18 * a18
             + 19 * a19 + 20 * a20;
    }

    /** Instance: the receiver takes x0, so 17 parameters is 18 arguments. */
    int tally17(int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
                int a11, int a12, int a13, int a14, int a15, int a16, int a17)
    {
        return base + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
             + a11 + a12 + a13 + a14 + a15 + a16 + a17;
    }

    /** A bare long past the boundary: one argument register, TWO local slots. */
    static long wide(int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int a10,
                     int a11, int a12, int a13, int a14, int a15, long big, int a17, int a18)
    {
        return big + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
             + a11 + a12 + a13 + a14 + a15 + a17 + a18;
    }
}
