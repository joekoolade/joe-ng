package compiler;

import magic.Magic;

/**
 * Small Java methods used as compiler fixtures — javac produces their bytecode,
 * our baseline compiler lowers it, and {@code CompilerTest} pins the result.
 * Kept out of {@code vm.VM} (the real runtime) so tests can grow freely.
 */
public final class Fixtures {
    private Fixtures() {}

    /** One MMIO word store with constant operands. */
    public static void pokeWord() { Magic.store32(0xFE215004L, 1); }

    /** One system-register write with a constant value. */
    public static void writeReg() { Magic.writeHCR_EL2(0x80000000L); }

    /** A leaf method with a parameter and a return value — exercises the frame. */
    public static int addOne(int x) { return x + 1; }

    /** Array element load (baload: base + index<<0). */
    public static int arrElem0(byte[] a) { return a[0]; }
    /** Array length (ldr at ARRAY_LENGTH_OFFSET). */
    public static int arrLen(int[] a) { return a.length; }

    /** Ternary — leaves a value on the operand stack across a branch merge. */
    public static int tern(int x) { return x != 0 ? 0x41 : 0x42; }
}
