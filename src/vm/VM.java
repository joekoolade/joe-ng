package vm;

import board.bcm2711.Uart;
import magic.Magic;
import objectmodel.ObjectModel;

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
        initClasses();                     // run static initializers (writer-generated body)
        run();

        while (true) {
            Magic.wfe();
        }
    }

    /**
     * Runs every used class's {@code <clinit>} once, eagerly, before the program.
     * The body is empty here — the boot-image writer replaces it with a sequence
     * of calls to the discovered static initializers (closed-world eager init).
     */
    static void initClasses() {
    }

    /**
     * {@code instanceof} support: is {@code ref}'s class {@code targetType} or a
     * subclass? Walks the object's Type up its superclass chain (TIB→Type→super).
     * {@code ref}/{@code targetType} are raw addresses (references are direct
     * pointers, Types are laid out by the writer). The compiler synthesizes calls
     * to this for the {@code instanceof} bytecode.
     */
    static int instanceOf(long ref, long targetType) {
        if (ref == 0L) return 0;                       // null is never an instance
        long type = Magic.load64(Magic.load64(ref));   // header→TIB (@0), TIB→Type (@0)
        while (type != 0L) {
            if (type == targetType) return 1;
            type = Magic.load64(type + ObjectModel.TYPE_SUPER_OFFSET);
        }
        return 0;
    }

    /** {@code checkcast} support: return {@code ref} if the cast holds, else halt
     *  (no exceptions yet). Null always passes. */
    static long checkCast(long ref, long targetType) {
        if (ref != 0L && instanceOf(ref, targetType) == 0) {
            while (true) { Magic.wfe(); }
        }
        return ref;
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

        Counter.bump();                    // static field in the image statics area
        Counter.bump();
        Counter.bump();
        Uart.putc(0x30 + Counter.get());   // '3'  (getstatic/putstatic)
        Uart.putc(0x0A);

        Uart.putc(Config.mark);            // '7' — set by Config's <clinit> at boot
        Uart.putc(0x0A);

        // class hierarchy: virtual dispatch on the static supertype hits the override
        Animal dog = new Dog();
        Uart.putc(dog.sound());            // 'W' — Dog overrides Animal.sound (same vtable slot)
        Animal animal = new Animal();
        Uart.putc(animal.sound());         // '?' — base implementation
        Uart.putc(0x0A);

        // instanceof / checkcast (subclass walk over the Type chain)
        Uart.putc(dog instanceof Dog ? 0x59 : 0x4E);       // 'Y' — a Dog is a Dog
        Uart.putc(animal instanceof Dog ? 0x59 : 0x4E);    // 'N' — an Animal is not a Dog
        Dog cast = (Dog) dog;                              // checkcast succeeds
        Uart.putc(cast.sound());                           // 'W'
        Uart.putc(0x0A);

        // interface dispatch via itables (invokeinterface)
        Speaker s1 = new Robot();
        Speaker s2 = new Phone();
        Uart.putc(s1.speak());                             // 'R'
        Uart.putc(s2.speak());                             // 'P'
        Uart.putc(0x0A);

        // exceptions: throw and catch by type in the same method
        try {
            throw new MyExc();
        } catch (MyExc e) {
            Uart.putc(0x45);                               // 'E' — caught
        }
        Uart.putc(0x0A);
    }
}
