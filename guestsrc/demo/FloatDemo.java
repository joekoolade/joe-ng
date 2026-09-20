package demo;

import magic.Magic;

/**
 * Verifies the compiler's float/double support end-to-end: arithmetic, i2f/f2i/i2d/d2i conversions, and a
 * float compare — all on a class demand-loaded and JIT-compiled on metal. Results are converted to int and
 * printed via string concat (so the numbers are visible). Float/double values live as raw bits in GP
 * registers; each op round-trips through the FP unit (fmov + fadd/fmul/scvtf/fcvtzs/...).
 *
 * <p>EVERY CONVERSION ARM HERE USED TO BE POSITIVE (8, 5, 35), which is exactly why this demo could not see
 * that {@code f2i}/{@code d2i} produced a NON-CANONICAL int. Those lower to {@code FCVTZS} with a W
 * destination, and an AArch64 W-form write ZERO-extends bits 63:32, so {@code (int) -2.5f} arrived as
 * {@code 0x0000_0000_FFFF_FFFE} instead of a sign-extended -2. Consumers that sign-extend first (a compare,
 * {@code i2l}, {@code iadd}) repaired it by accident and PRINTED THE RIGHT ANSWER; {@code idiv},
 * {@code irem} and {@code ishr} read the whole register and answered from the unsigned value.
 *
 * <p>So the arms below do not stop at converting: each negative conversion is then DIVIDED, SHIFTED or
 * REMAINDERED, which is the only way the defect becomes visible. A demo that exercises a feature is not one
 * that exercises its edges.
 */
public class FloatDemo
{
    public static void main(String[] args)
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

        // ---- NEGATIVE conversions, then an operation that reads the WHOLE register ----
        int nf = (int) f(-2.5f);                        // f2i -> -2
        Magic.printStr("f2i -2.5 = " + nf + " (want -2)\n");                   // right even unfixed (concat sxtw's)
        Magic.printStr("f2i -2.5 / 2 = " + (nf / 2) + " (want -1)\n");         // unfixed: 2147483647
        Magic.printStr("f2i -2.5 >> 1 = " + (nf >> 1) + " (want -1)\n");       // unfixed: 2147483647
        Magic.printStr("f2i -2.5 % 3 = " + (nf % 3) + " (want -2)\n");         // unfixed: 2

        int nd = (int) d(-7.5);                         // d2i -> -7
        Magic.printStr("d2i -7.5 / 2 = " + (nd / 2) + " (want -3)\n");         // unfixed: 2147483644
        Magic.printStr("d2i -7.5 >> 2 = " + (nd >> 2) + " (want -2)\n");       // unfixed: 1073741822

        // idiv OVERFLOW: the single case JVMS 6.5 says wraps rather than throwing.
        //
        // PRINTING THE QUOTIENT DOES NOT TEST IT, which the negative control is what revealed: `scInt` takes
        // an `int` PARAMETER, so the callee's own i2l sign-extends the argument and the number comes out
        // right whether or not the divide canonicalised. Only a consumer that reads the WHOLE register --
        // here a shift -- can tell, so that is the arm that has teeth.
        int q = i(-2147483648) / i(-1);
        Magic.printStr("MIN/-1 = " + q + " (want -2147483648)\n");             // right even unfixed
        Magic.printStr("MIN/-1 >> 1 = " + (q >> 1) + " (want -1073741824)\n"); // unfixed: 1073741824
        Magic.printStr("MIN%-1 = " + (i(-2147483648) % i(-1)) + " (want 0)\n");

        // Saturation and NaN, which AArch64's FCVTZS already gets right -- controls, so a "fix" that
        // clamped or masked instead of sign-extending would fail here.
        Magic.printStr("f2i 1e30 = " + ((int) f(1.0e30f)) + " (want 2147483647)\n");
        Magic.printStr("f2i -1e30 = " + ((int) f(-1.0e30f)) + " (want -2147483648)\n");
        Magic.printStr("d2i NaN = " + ((int) d(d(0.0) / d(0.0))) + " (want 0)\n");
        Magic.printStr("f2i 2.5 / 2 = " + (((int) f(2.5f)) / 2) + " (want 1)\n");
    }

    // Opaque to javac's constant folding, so each conversion is a real runtime f2i/d2i/idiv.
    private static float f(float v)
    {
        return v;
    }

    private static double d(double v)
    {
        return v;
    }

    private static int i(int v)
    {
        return v;
    }
}
