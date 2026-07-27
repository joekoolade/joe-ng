package demo;

import magic.Magic;

/**
 * Verifies the compiler's new float/double support end-to-end: arithmetic, i2f/f2i/i2d/d2i conversions,
 * and a float compare — all on a class demand-loaded and JIT-compiled on metal. Results are converted to
 * int and printed via string concat (so the numbers are visible). Float/double values live as raw bits in
 * GP registers; each op round-trips through the FP unit (fmov + fadd/fmul/scvtf/fcvtzs/...).
 */
public class FloatDemo
{
    public static void main()
    {
        float a = 3.5f;
        float b = 2.0f;
        int r1 = (int) (a * b + 1.0f);                  // 3.5*2 + 1 = 8.0 -> 8
        Magic.printStr("float 3.5*2+1 = " + r1 + "\n");

        double d = 10.0;
        int r2 = (int) (d / 4.0 * 2.0);                 // 10/4 * 2 = 5.0 -> 5
        Magic.printStr("double 10/4*2 = " + r2 + "\n");

        int n = 7;                                      // a variable, so (float) n is a real i2f (not folded)
        int r3 = (int) ((float) n / 2.0f * 10.0f);      // i2f 7 -> 7.0, /2 *10 = 35.0 -> 35
        Magic.printStr("i2f 7/2*10 = " + r3 + "\n");

        float x = 1.5f;
        float y = 2.5f;
        int cmp = (x < y) ? 1 : 0;                      // fcmp: 1.5 < 2.5 -> 1
        Magic.printStr("cmp 1.5<2.5 = " + cmp + "\n");
    }
}
