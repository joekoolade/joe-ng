package demo;

import magic.Magic;

/**
 * Verifies PROVIDED java.base natives: a demand-loaded class calls real {@code java.lang} native methods
 * ({@code Float.floatToRawIntBits}, {@code System.nanoTime}) that have no bytecode — the on-metal loader
 * wires each call to a VM-implemented helper ({@code Loader.nativeBuf}). This is how unmodified java.base
 * code reaches the runtime services it assumes.
 */
public class NativeDemo
{
    public static void main(String[] args)
    {
        int bits = Float.floatToRawIntBits(1.5f);       // native -> IEEE-754 bits of 1.5f = 0x3FC00000
        Magic.printStr("Float.floatToRawIntBits(1.5f) = " + bits + "\n");

        long t0 = System.nanoTime();                     // native (ARM generic timer)
        int spin = 0;
        while (spin < 300000)
        {
            spin = spin + 1;
        }
        long t1 = System.nanoTime();
        int advanced = (t1 > t0) ? 1 : 0;
        Magic.printStr("System.nanoTime advanced = " + advanced + "\n");

        // native: System.arraycopy — generic over element type (size from the array header).
        byte[] sb = new byte[5];
        sb[0] = 10; sb[1] = 20; sb[2] = 30; sb[3] = 40; sb[4] = 50;
        byte[] db = new byte[5];
        System.arraycopy(sb, 1, db, 0, 3);              // 20,30,40 -> db[0..2]
        Magic.printStr("arraycopy byte[]: " + db[0] + "," + db[1] + "," + db[2] + "\n");

        int[] si = new int[4];
        si[0] = 100; si[1] = 200; si[2] = 300; si[3] = 400;
        int[] di = new int[4];
        System.arraycopy(si, 1, di, 1, 3);              // 200,300,400 -> di[1..3] (element size 4)
        Magic.printStr("arraycopy int[]: " + di[1] + "," + di[2] + "," + di[3] + "\n");

        int[] c = new int[5];
        c[0] = 1; c[1] = 2; c[2] = 3; c[3] = 4; c[4] = 5;
        System.arraycopy(c, 0, c, 1, 4);                // overlapping shift-right -> 1,1,2,3,4
        Magic.printStr("arraycopy overlap: " + c[0] + "," + c[1] + "," + c[2] + "," + c[3] + "," + c[4] + "\n");
    }
}
