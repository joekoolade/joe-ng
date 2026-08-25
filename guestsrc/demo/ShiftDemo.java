package demo;

/**
 * Int shift COUNTS are masked to 5 bits ({@code s & 0x1f}), as the JVM specifies. AArch64's 64-bit shift
 * instructions use 6 bits instead, so a count of 32 shifted an int clean out of its register and answered 0
 * where Java answers the value unchanged.
 *
 * <p>That is invisible for the ordinary constant shift and exactly wrong for the rotate idiom
 * {@code (x << n) | (x >>> (32 - n))} when {@code n} is 0 — which is how hashing code is written.
 * {@code Integer.rotateLeft(x, 32)} is the case with teeth: it must return {@code x}.
 *
 * <p>Long shifts mask to 6 bits, which is what the instruction already does, so they are here as a control.
 */
public class ShiftDemo
{
    public static void main(String[] args)
    {
        int x = 0x12345678;
        int n32 = 32;                                  // in a local, so javac cannot fold the shift
        int n33 = 33;
        int zero = 0;

        System.out.println("x         = " + Integer.toHexString(x));
        System.out.println("x << 32   = " + Integer.toHexString(x << n32));      // 12345678 (was 0)
        System.out.println("x >>> 32  = " + Integer.toHexString(x >>> n32));     // 12345678 (was 0)
        System.out.println("x >> 32   = " + Integer.toHexString(x >> n32));      // 12345678 (was 0)
        System.out.println("x << 33   = " + Integer.toHexString(x << n33));      // 2468acf0
        System.out.println("x << 0    = " + Integer.toHexString(x << zero));     // 12345678

        int neg = -1;
        System.out.println("-1 >>> 32 = " + Integer.toHexString(neg >>> n32));   // ffffffff
        System.out.println("-1 >>> 0  = " + Integer.toHexString(neg >>> zero));  // ffffffff
        System.out.println("-1 >> 32  = " + Integer.toHexString(neg >> n32));    // ffffffff

        // The idiom this actually protects: a rotate whose distance lands on a multiple of 32.
        System.out.println("rotl(x,0)  = " + Integer.toHexString(Integer.rotateLeft(x, 0)));    // 12345678
        System.out.println("rotl(x,32) = " + Integer.toHexString(Integer.rotateLeft(x, 32)));   // 12345678 (was 0)
        System.out.println("rotl(x,8)  = " + Integer.toHexString(Integer.rotateLeft(x, 8)));    // 34567812
        System.out.println("rotr(x,32) = " + Integer.toHexString(Integer.rotateRight(x, 32)));  // 12345678

        // Control: long shifts already mask to 6 bits, and 64 must behave as 0.
        long lx = 0x123456789ABCDEF0L;
        int n64 = 64;
        System.out.println("lx << 64  = " + Long.toHexString(lx << n64));        // 123456789abcdef0
        System.out.println("lx >>> 64 = " + Long.toHexString(lx >>> n64));       // 123456789abcdef0
        System.out.println("lx << 4   = " + Long.toHexString(lx << 4));          // 23456789abcdef00
    }
}
