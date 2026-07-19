package vm;

import board.bcm2711.Uart;
import magic.Magic;

/**
 * The runtime entry points, written as ordinary Java and compiled to A64 by our
 * own baseline compiler (the metacircular point — PLAN.md §1). The seed JVM
 * never runs these on metal; the writer parses this class's bytecode and
 * compiles it into {@code kernel8.img}.
 *
 * Methods are added here as the baseline compiler's bytecode coverage grows,
 * milestone by milestone (CLAUDE.md working agreements).
 */
public final class VM {
    private VM() {}

    /**
     * Park loop — the first method compiled from real Java bytecode by our own
     * compiler (M1c step 1). Compiles to {@code wfe; b .-4}, identical to the M0
     * hand-emitted spin image.
     */
    public static void spin() {
        while (true) {
            Magic.wfe();
        }
    }

    /**
     * M1c: the full first-light boot, compiled from this Java by our own baseline
     * compiler (the metacircular goal). Equivalent to the hand-emitted
     * {@code vm.EmitBoot}: drop EL2→EL1, enable FP, set a stack, bring up the AUX
     * mini-UART, print the boot message, then park.
     */
    public static void boot() {
        Magic.dropToEL1();
        Magic.writeCPACR_EL1(0x300000L);   // CPACR_EL1.FPEN = 0b11 (no FP trap)
        Magic.isb();
        Magic.writeSP(0x80000L);           // stack below the image (needed before any call)

        Heap.init();
        Uart.init();
        run();

        while (true) {
            Magic.wfe();
        }
    }

    /**
     * The program proper — a framed method (so operand values can spill across
     * calls). Prints the banner, then exercises the object model: allocate a
     * heap object, mutate its field, and print the result.
     */
    static void run() {
        Uart.write(Magic.bytes("hello from joe2\r\n"));   // real interned string literal (byte[])

        Cell c = new Cell(0x6A);           // 'j', set by the constructor (putfield)
        c.inc();                           // virtual dispatch through the TIB vtable -> 'k'
        Uart.putc(c.get());                // virtual dispatch: read the field back
        Uart.putc(0x0A);                   // newline

        byte[] a = new byte[3];            // runtime heap array (newarray/bastore)
        a[0] = 0x41; a[1] = 0x42; a[2] = 0x0A;   // "AB\n"
        Uart.write(a);
    }
}
