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
    public static void main()
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
    }
}
