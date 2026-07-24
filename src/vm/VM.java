package vm;

import asm.A64Enc;
import board.bcm2711.Emmc;
import board.bcm2711.Fat32;
import board.bcm2711.Reset;
import board.bcm2711.Uart;
import classfile.ClassReader;
import compiler.Baseline;
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
public final class VM
{
    private VM() {}

    /**
     * Park loop — the first method compiled from real Java bytecode by our own
     * compiler (M1c step 1). Compiles to {@code wfe; b .-4}, identical to the M0
     * hand-emitted spin image.
     */
    public static void spin()
    {
        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * M1c: the full first-light boot, compiled from this Java by our own baseline
     * compiler (the metacircular goal). Equivalent to the hand-emitted
     * {@code vm.EmitBoot}: drop EL2→EL1, enable FP, set a stack, bring up the AUX
     * mini-UART, print the boot message, then park.
     */
    public static void boot()
    {
        Magic.dropToEL1();
        Magic.writeCPACR_EL1(0x300000L);   // CPACR_EL1.FPEN = 0b11 (no FP trap)
        Magic.isb();
        Magic.writeSP(0x80000L);           // stack below the image (needed before any call)

        Heap.init();
        Uart.init();
        installFaultVectors();             // turn a CPU fault into a printed report, not a silent hang
        initClasses();                     // run static initializers (writer-generated body)
        run();

        while (true)
        {
            Magic.wfe();
        }
    }

    /**
     * Install a minimal EL1 exception vector table so a CPU fault prints a report instead
     * of hanging silently (the failure mode when the boot path faults with no vectors set).
     * Each of the 16 architectural entries branches to {@link #reportFault}; a 2 KiB-aligned
     * Heap buffer holds the table, published for instruction fetch before {@code VBAR_EL1}
     * points at it. Diagnostic aid — harmless when nothing faults.
     */
    static void installFaultVectors()
    {
        // Never taken (reportFaultAddr is the writer-stashed address, always nonzero); the
        // dead call makes reportFault reachable so the writer compiles it and fills the static.
        if (reportFaultAddr == 0L)
        {
            reportFault();
        }
        long raw = Heap.alloc(0x1000);
        long table = (raw + 0x7FFL) & ~0x7FFL;             // VBAR_EL1 requires 2 KiB alignment
        int i = 0;
        while (i < 16)
        {
            long entry = table + i * 0x80L;                // 16 entries, 0x80 bytes apart
            long rel = (reportFaultAddr - entry) / 4L;      // B takes a word offset
            Magic.store32(entry, A64Enc.b((int) rel));
            i += 1;
        }
        Heap.publishCode(table, table + 0x800L);
        Magic.writeVBAR_EL1(table);
        Magic.isb();                                       // the new vector base takes effect
    }

    /**
     * EL1 exception handler (reached by a branch from every vector entry): print the syndrome,
     * faulting PC and fault address, then park. Does not return — this is a last-resort report.
     */
    static void reportFault()
    {
        long esr = Magic.readESR_EL1();
        long elr = Magic.readELR_EL1();
        long far = Magic.readFAR_EL1();
        long el = Magic.readCurrentEL();
        Uart.write(Magic.bytes("\nFAULT el="));
        printHex(el);
        Uart.write(Magic.bytes(" esr="));
        printHex(esr);
        Uart.write(Magic.bytes(" elr="));
        printHex(elr);
        Uart.write(Magic.bytes(" far="));
        printHex(far);
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }

    /** Print {@code v} as {@code 0x} + 16 hex digits over the UART. */
    static void printHex(long v)
    {
        Uart.putc(0x30);                                   // '0'
        Uart.putc(0x78);                                   // 'x'
        int shift = 60;
        while (shift >= 0)
        {
            int nib = (int) ((v >> shift) & 0xFL);
            Uart.putc(nib < 10 ? 0x30 + nib : 0x41 + nib - 10);   // 0-9, A-F
            shift -= 4;
        }
    }

    /**
     * Runs every used class's {@code <clinit>} once, eagerly, before the program.
     * The body is empty here — the boot-image writer replaces it with a sequence
     * of calls to the discovered static initializers (closed-world eager init).
     */
    static void initClasses()
    {
    }

    /**
     * {@code instanceof} support: is {@code ref}'s class {@code targetType} or a
     * subclass? Walks the object's Type up its superclass chain (TIB→Type→super).
     * {@code ref}/{@code targetType} are raw addresses (references are direct
     * pointers, Types are laid out by the writer). The compiler synthesizes calls
     * to this for the {@code instanceof} bytecode.
     */
    // Walks the superclass chain; at each class, a match on the class's own Type or on
    // any interface in its itable directory (terminated by a 0 interfaceType) counts. So
    // this answers class instanceof and interface instanceof — including interfaces
    // inherited from a superclass, since each class's directory is checked on the way
    // up. Not yet: super-interfaces (a directory lists directly-declared interfaces, so
    // `x instanceof Base` where x implements `Greeter extends Base` is missed).
    static int instanceOf(long ref, long targetType)
    {
        if (ref == 0L)
        {
            return 0;    // null is never an instance
        }
        long type = Magic.load64(Magic.load64(ref));   // header→TIB (@0), TIB→Type (@0)
        while (type != 0L)
        {
            if (type == targetType)
            {
                return 1;
            }
            long dir = Magic.load64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET);
            if (dir != 0L)
            {
                long entry = dir;
                long iface = Magic.load64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
                while (iface != 0L)                    // 0 interfaceType terminates the directory
                {
                    if (iface == targetType)
                    {
                        return 1;
                    }
                    entry += ObjectModel.ITABLE_ENTRY_SIZE;
                    iface = Magic.load64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
                }
            }
            type = Magic.load64(type + ObjectModel.TYPE_SUPER_OFFSET);
        }
        return 0;
    }

    /** {@code checkcast} support: return {@code ref} if the cast holds, else halt
     *  (no exceptions yet). Null always passes. */
    static long checkCast(long ref, long targetType)
    {
        if (ref != 0L && instanceOf(ref, targetType) == 0)
        {
            while (true)
            {
                Magic.wfe();
            }
        }
        return ref;
    }

    // ----- exception unwinding --------------------------------------------
    // Addresses/counts of the handler and frame tables, filled by the writer.
    static long handlerTable, handlerCount;   // entries: {machineStart, machineEnd, handler, catchType}
    static long frameTable, frameCount;       // entries: {codeStart, codeEnd, frameSize}

    // A second frame table for methods JIT-compiled at runtime: their code isn't in
    // the image, so the writer can't describe them. The loader appends one entry per
    // compiled method (codeStart, codeEnd, frameSize) as it emits. Same triple
    // layout as frameTable; frameSizeAt consults both, so unwinding can pop a JIT'd
    // frame just like a compiled one.
    static long jitFrameTable, jitFrameCount;

    /** Record a JIT'd method's machine-PC range and frame size, so unwind can pop it. */
    static void addJitFrame(long codeStart, long codeEnd, long frameSize)
    {
        if (jitFrameTable == 0L)
        {
            jitFrameTable = Heap.alloc(JIT_FRAME_MAX * 24);      // JIT_FRAME_MAX * 24 bytes
        }
        if (jitFrameCount < JIT_FRAME_MAX)
        {
            long e = jitFrameTable + jitFrameCount * 24L;
            Magic.store64(e, codeStart);
            Magic.store64(e + 8L, codeEnd);
            Magic.store64(e + 16L, frameSize);
            jitFrameCount = jitFrameCount + 1L;
        }
    }
    static final int JIT_FRAME_MAX = 256;

    // A jit handler table paralleling the image handlerTable, so a metal-built/JIT'd method's
    // try/catch is findable during a cross-method unwind. Entries {machStart, machEnd, handler,
    // catchType} (32 bytes), same layout as handlerTable; findHandler consults both.
    static long jitHandlerTable, jitHandlerCount;
    static final int JIT_HANDLER_MAX = 256;

    /** Record a JIT'd method's try/catch range so a cross-method unwind can resume into it. */
    static void addJitHandler(long machStart, long machEnd, long handler, long catchType)
    {
        if (jitHandlerTable == 0L)
        {
            jitHandlerTable = Heap.alloc(JIT_HANDLER_MAX * 32);
        }
        if (jitHandlerCount < JIT_HANDLER_MAX)
        {
            long e = jitHandlerTable + jitHandlerCount * 32L;
            Magic.store64(e, machStart);
            Magic.store64(e + 8L, machEnd);
            Magic.store64(e + 16L, handler);
            Magic.store64(e + 24L, catchType);
            jitHandlerCount = jitHandlerCount + 1L;
        }
    }

    /**
     * Unwind the stack looking for a handler for {@code exc}, starting at machine
     * PC {@code pc} with stack pointer {@code sp}. At each frame: if a try/catch
     * covers the PC and the type matches, resume there; otherwise pop the frame
     * (via its frame-table size, reading the saved LR at [sp]) and continue in the
     * caller. Halts if the exception reaches the top uncaught. (Callee-saved locals
     * are not restored during the walk — a handler must not read pre-try locals.)
     */
    static void unwind(long exc, long pc, long sp)
    {
        while (true)
        {
            long h = findHandler(pc, exc);
            if (h != 0L)
            {
                Magic.resume(h, sp, exc);          // never returns
            }
            long fs = frameSizeAt(pc);
            if (fs == 0L)
            {
                while (true)
                {
                    Magic.wfe();    // uncaught at the top
                }
            }
            pc = Magic.load64(sp) - 4L;             // the call site (return address - one instruction)
            sp = sp + fs;                           // pop this frame
        }
    }

    private static long findHandler(long pc, long exc)
    {
        long h = findHandlerIn(handlerTable, handlerCount, pc, exc);   // image methods
        if (h != 0L)
        {
            return h;
        }
        return findHandlerIn(jitHandlerTable, jitHandlerCount, pc, exc);   // metal-built / JIT'd methods
    }

    private static long findHandlerIn(long table, long count, long pc, long exc)
    {
        long i = 0L;
        while (i < count)
        {
            long e = table + i * 32L;
            if (pc >= Magic.load64(e) && pc < Magic.load64(e + 8L))
            {
                long catchType = Magic.load64(e + 24L);
                if (catchType == 0L || instanceOf(exc, catchType) != 0)
                {
                    return Magic.load64(e + 16L);
                }
            }
            i = i + 1L;
        }
        return 0L;
    }

    /** Frame size covering machine PC {@code pc}, from either table (0 = none). Package-visible for the self-check. */
    static long frameSizeAt(long pc)
    {
        long fs = frameSizeIn(frameTable, frameCount, pc);        // image methods
        if (fs != 0L)
        {
            return fs;
        }
        return frameSizeIn(jitFrameTable, jitFrameCount, pc);     // runtime JIT'd methods
    }

    /** Frame size of the {codeStart,codeEnd,frameSize} entry covering {@code pc}, or 0. */
    private static long frameSizeIn(long table, long count, long pc)
    {
        long i = 0L;
        while (i < count)
        {
            long e = table + i * 24L;
            if (pc >= Magic.load64(e) && pc < Magic.load64(e + 8L))
            {
                return Magic.load64(e + 16L);
            }
            i = i + 1L;
        }
        return 0L;
    }

    // ----- garbage collection (conservative mark-sweep) --------------------
    static final long STACK_TOP = 0x80000L;   // SP init; the stack grows down from here
    static long staticsStart, staticsEnd;     // image statics region, filled by the writer

    /**
     * Conservative mark-sweep, entered via {@code Magic.gc()} (which spills the
     * callee-saved registers so live references there are on the stack). Roots are
     * the stack [{@code scanFrom}, STACK_TOP) and the statics region; anything
     * transitively reachable survives, everything else is swept onto the free list.
     * Object sizes come from the status word — no per-type maps, and objects aren't
     * moved (so no precise stack maps are needed). May over-retain (false roots).
     */
    static void gcCollect(long scanFrom)
    {
        markRange(scanFrom, STACK_TOP);
        markRange(staticsStart, staticsEnd);
        boolean changed = true;                       // trace: mark fields of marked objects to a fixpoint
        while (changed)
        {
            changed = false;
            long o = Heap.BASE;
            while (o < Magic.load64(Heap.PTR_CELL))
            {
                long st = Magic.load64(o + 8L);
                long size = st & -8L;
                if (size == 0L)
                {
                    o = Magic.load64(Heap.PTR_CELL);    // corrupt: stop
                }
                else
                {
                    if ((st & 1L) != 0L && markRange(o + 16L, o + size))
                    {
                        changed = true;
                    }
                    o = o + size;
                }
            }
        }
        Heap.resetFreeList();                          // sweep
        reclaimed = 0L;
        long o = Heap.BASE;
        while (o < Magic.load64(Heap.PTR_CELL))
        {
            long st = Magic.load64(o + 8L);
            long size = st & -8L;
            if (size == 0L)
            {
                o = Magic.load64(Heap.PTR_CELL);
            }
            else
            {
                if ((st & 1L) != 0L)
                {
                    Magic.store64(o + 8L, size);    // unmark (clear bit0)
                }
                else
                {
                    Heap.addFree(o, size);
                    reclaimed = reclaimed + size;
                }
                o = o + size;
            }
        }
    }

    static long reclaimed;   // bytes freed by the last collection

    // ----- runtime class loading (M4) --------------------------------------
    static long guestBytes, guestLen;   // raw Guest.class blob, filled by the writer
    static long greeterBytes, greeterLen; // raw Greeter.class blob (an interface Guest loads)
    static long alphaBytes, alphaLen;   // raw Alpha.class blob (implements Greeter at vtable slot 0)
    static long betaBytes, betaLen;     // raw Beta.class blob (implements Greeter at vtable slot 1)
    static long myExcBytes, myExcLen;   // raw MyExc.class blob (a throwable Guest catches)
    static long mathBytes, mathLen;     // raw java.base java/lang/Math.class blob
    // ----- self-build input: the compile-reachable class set, name-indexed (M5.5c step 2) -----
    static long classDir;               // directory of {nameAddr, nameLen, bytesAddr, bytesLen} entries
    static long classCount;             // number of directory entries
    // Addresses of the runtime helpers the shared baseline compiler calls, stashed by
    // the writer so the on-metal JIT (via MetalSymbols) can BL them. Indexed to match
    // the ids in compiler/Symbols: heapAlloc=0, allocArray=1, gcCollect=2, instanceOf=3,
    // checkCast=4, unwind=5.
    static long heapAlloc;              // Heap.alloc(I)J, so on-metal `new` can BL it
    static long allocArray;            // Heap.allocArray(II)J
    static long gcCollect;             // VM.gcCollect(J)V
    static long instanceOfAddr;        // VM.instanceOf(JJ)I
    static long checkCastAddr;         // VM.checkCast(JJ)J
    static long unwindAddr;            // VM.unwind(JJJ)V
    static long reportFaultAddr;       // VM.reportFault()V — the exception-vector handler's address

    /** Mark every heap object pointed to by an 8-aligned word in [lo,hi). Returns true if any newly marked. */
    private static boolean markRange(long lo, long hi)
    {
        boolean any = false;
        while (lo < hi)
        {
            long w = Magic.load64(lo);
            if (w >= Heap.BASE && w < Magic.load64(Heap.PTR_CELL) && (w & 7L) == 0L)
            {
                long st = Magic.load64(w + 8L);
                long size = st & -8L;
                if (size != 0L && (st & 1L) == 0L && w + size <= Magic.load64(Heap.PTR_CELL))
                {
                    Magic.store64(w + 8L, st + 1L);    // set mark bit
                    any = true;
                }
            }
            lo = lo + 8L;
        }
        return any;
    }

    /**
     * The program proper — a framed method (so operand values can spill across
     * calls). Prints the banner, then exercises the object model: allocate a
     * heap object, mutate its field, and print the result.
     */
    /** Print {@code v} (0..9999) in decimal, no leading zeros. Uses only / and * (no irem). */
    static void printDec(int v)
    {
        int th = v / 1000;
        int hu = (v - th * 1000) / 100;
        int te = (v - th * 1000 - hu * 100) / 10;
        int on = v - th * 1000 - hu * 100 - te * 10;
        if (th > 0)
        {
            Uart.putc(0x30 + th);
        }
        if (th > 0 || hu > 0)
        {
            Uart.putc(0x30 + hu);
        }
        if (th > 0 || hu > 0 || te > 0)
        {
            Uart.putc(0x30 + te);
        }
        Uart.putc(0x30 + on);
    }

    static void run()
    {
        Uart.write(Magic.bytes("hello from joe-ng\n"));     // putc turns \n into \r\n
        Uart.write(Magic.bytes("core "));                 // the clock we calibrated the baud to
        printDec(Uart.coreHz / 1000000);                  // MHz (0 = mailbox gave no answer)
        Uart.write(Magic.bytes("MHz\n"));


        Cell c = new Cell(0x6A);           // 'j', set by the constructor (putfield)
        c.inc();                           // virtual dispatch through the TIB vtable -> 'k'
        Uart.putc(c.get());                // virtual dispatch: read the field back
        Uart.putc(0x0A);                   // newline

        byte[] a = new byte[3];            // runtime heap array (newarray/bastore)
        a[0] = 0x41;
        a[1] = 0x42;
        a[2] = 0x0A;   // "AB\n"
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
        try
        {
            throw new MyExc();
        }
        catch (MyExc e)
        {
            Uart.putc(0x45);                               // 'E' — caught
        }
        Uart.putc(0x0A);

        // cross-method: thrower() throws, catcher() (its caller) catches
        catcher();                                         // -> 'U'
        Uart.putc(0x0A);

        // GC: allocate garbage, collect, then show a freed block is reused
        gcGarbage();
        Magic.gc();
        Cell fresh = new Cell(0x2A);                       // should come from the free list
        Uart.putc(Heap.lastFromFreeList != 0 ? 0x52 : 0x4E); // 'R' reused / 'N' fresh bump
        Uart.putc(0x0A);

        // M4: parse+compile+run a class embedded only as raw bytes, on the metal
        Uart.putc(Loader.loadGuest());                     // '*' from Guest.answer(), JIT'd at runtime
        Uart.putc(Loader.loadMath());                      // 'M' from java.base java.lang.Math.max(0x4D,0x21)
        Uart.putc(0x0A);

        // The runs above JIT-compiled framed methods and registered their frames.
        // Prove VM.unwind can now size a JIT'd frame: pick a real registered entry
        // and check frameSizeAt finds it in range and rejects a PC just past it.
        Uart.putc(jitUnwindReady() ? 0x46 : 0x6E);         // 'F' frame found / 'n' not
        Uart.putc(0x0A);

        // M5.5c step 2: the writer embedded the compile-reachable class set as a
        // name-indexed table. Prove the metal writer's input path: look each class up
        // by its own stored name and confirm it resolves back to itself with intact
        // classfile magic — the self-build reads its sources from the image alone.
        Uart.putc(classTableReady() ? 0x43 : 0x78);        // 'C' class table OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 1b: the metal class model answers the writer's class-graph queries
        // over that table via the shared ClassReader. Prove its leaf queries on metal
        // against known classes (Dog's super, Cell's field count, Config's <clinit>).
        Uart.putc(classModelReady() ? 0x4B : 0x78);        // 'K' class model OK / 'x' broken
        Uart.putc(0x0A);

        // The class model's superclass-chain walks: vtable flattening (super-first +
        // override-in-place), interface methods, allInterfaces, findImpl — the multi-class
        // recursion the writer's TIB/itable layout needs. Verified on metal against known
        // hierarchy facts (Dog overrides Animal.sound in the same slot; Robot implements
        // Speaker; Cell's two virtuals).
        Uart.putc(chainWalksReady() ? 0x56 : 0x78);        // 'V' chain walks OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3: the metal writer's relocating compile. Drive the shared Baseline
        // core over a real method with MetalWriterSymbols — which, unlike the JIT's
        // MetalSymbols, emits placeholders and *records* relocation sites for later layout.
        Uart.putc(relocatingCompileReady() ? 0x42 : 0x78); // 'B' relocating compile OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b: the metal layout engine. Discover a call closure, place each method
        // in a Heap buffer, compile at its base, and patch the BL sites to their callees'
        // bases -- then *execute* the built code. The built putc prints '~' before the 'L'.
        boolean built = selfBuildClosureAndRun();          // prints '~' from metal-built code
        Uart.putc(built ? 0x4C : 0x78);                    // 'L' layout engine OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: the first non-call relocation kind. Build Counter.bump/get, lay out
        // a statics slot, patch their getstatic/putstatic address loads to it, then run
        // bump() x3 and get() -- which must return 3.
        Uart.putc(selfBuildStaticsAndRun() ? 0x53 : 0x78); // 'S' static-field reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: object allocation. Build Cell.make = `new Cell(v).value`, lay out
        // Cell's Type + TIB (via MetalClassModel), patch the `new`'s TIB load and the
        // Heap.alloc helper call, then run make(0x37) -> 0x37.
        Uart.putc(selfBuildNewAndRun() ? 0x4F : 0x78);     // 'O' object/new reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: instanceof. Build Cell.selfCheck (= new Cell(0) instanceof Cell),
        // patch the `type` Type-address load and the VM.instanceOf helper, then run it -> 1.
        Uart.putc(selfBuildInstanceofAndRun() ? 0x54 : 0x78);  // 'T' type/instanceof reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: string literal. Build Cell.tag (= Magic.bytes("Z")[0]), intern "Z"
        // as a byte[] and patch the ldc-string load to it, then run tag() -> 'Z'.
        Uart.putc(selfBuildStringAndRun() ? 0x67 : 0x78);  // 'g' string reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: invokevirtual. Build Cell.viaVirtual (= new Cell(v); c.get()) plus
        // Cell's vtable methods, fill the TIB vtable, and dispatch get() through it -> 0x37.
        Uart.putc(selfBuildVirtualAndRun() ? 0x44 : 0x78); // 'D' virtual dispatch reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: invokeinterface. Build Robot.probe (= new Robot(); s.speak()), lay
        // out Speaker's interface Type + Robot's itable directory, and dispatch speak() -> 'R'.
        Uart.putc(selfBuildInterfaceAndRun() ? 0x69 : 0x78);  // 'i' interface dispatch reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: throw/catch (exceptionSlot). Build MyExc.probe (= try { throw new
        // MyExc(); } catch (MyExc e) { return 1; }) and run it -> 1 (same-method, inline catch).
        Uart.putc(selfBuildExceptionAndRun() ? 0x65 : 0x78);  // 'e' exception reloc OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.2: cross-class discovery. Build Cell.readCounter (calls Counter.bump/get
        // in another class, sharing the Counter.count static) by BFS, and run it -> 1.
        Uart.putc(selfBuildCrossAndRun() ? 0x58 : 0x78);   // 'X' cross-class discovery OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3: cross-class new + virtual. Build Animal.dogSound (= new Dog().sound(),
        // Dog in another class, dispatched through its TIB vtable) and run it -> 'W'.
        Uart.putc(selfBuildDynAndRun() ? 0x79 : 0x78);     // 'y' cross-class new/virtual OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3: cross-class interface. Build Cell.viaSpeaker (= new Robot(); s.speak(),
        // Robot + Speaker in other classes) and dispatch through the itable -> 'R'.
        Uart.putc(selfBuildCrossIfaceAndRun() ? 0x4A : 0x78);  // 'J' cross-class interface OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.3 capstone: the metal writer builds Guest.answer's whole closure (every
        // reloc kind, five classes) and runs it -> 42. '!' on success.
        Uart.putc(selfBuildAnswerAndRun() ? 0x21 : 0x78);  // '!' full-closure capstone OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 4 (essence): recompile VM.instanceOf on metal and assert it is byte-identical
        // to the image's own copy at instanceOfAddr -- the self-build fixpoint, one method wide.
        Uart.putc(selfFixpointInstanceOf() ? 0x3D : 0x78); // '=' metal bytes == image bytes / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 4: the fixpoint on a relocation-bearing method -- checkCast's call to
        // instanceOf is patched to the image's own address; the result must byte-match the image.
        Uart.putc(selfFixpointCheckCast() ? 0x2B : 0x78);  // '+' relocated metal bytes == image / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 3b.4: eager static init. Build Cell.readConfig (reads Config.mark), discover
        // + run Config.<clinit> first, so it reads 0x37 (not the zeroed default).
        Uart.putc(buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("readConfig"), Magic.bytes("()I")) == 0x37
                  ? 0x40 : 0x78);                          // '@' eager <clinit> init OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c cross-method unwind. Build MyExc.catchIt (calls throwIt, which throws with no local
        // handler); the throw must unwind out of throwIt into catchIt's catch via the jit frame +
        // handler tables registered for the metal-built closure -> 1.
        Uart.putc(buildClosure(Magic.bytes("vm/MyExc"), Magic.bytes("catchIt"), Magic.bytes("()I")) == 1
                  ? 0x75 : 0x78);                          // 'u' cross-method unwind OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 3b.4: runtime-load blobs folded into the writer's input. Guest (+ Greeter/Alpha/
        // Beta/MyExc) are now in the class table, so the metal writer builds Guest.answer's whole
        // closure -- every reloc kind across five formerly JIT-only classes -- and runs it -> 42.
        Uart.putc(buildClosure(Magic.bytes("vm/Guest"), Magic.bytes("answer"), Magic.bytes("()I")) == 42
                  ? 0x47 : 0x78);                          // 'G' metal-built Guest.answer OK / 'x' broken
        Uart.putc(0x0A);

        // M5.5c step 4: whole-image code-region layout fixpoint. Reproduce the seed ImageBuilder's
        // method discovery + layout order from vm/VM.boot, then assert every anchored method lands at
        // its own address in the running image -- proving the metal writer reconstructs the exact
        // 0x80000-relative placement of the code region it booted from.
        Uart.putc(fixpointCodeLayout() ? 0x5A : 0x78);     // 'Z' code-region layout reproduced / 'x' off
        Uart.putc(0x0A);

        // M5.5c step 4: whole-image data-region layout fixpoint. Reproduce the seed ImageBuilder's
        // layout of every region after the code -- Types, TIBs, interned strings, statics, itables,
        // unwind frame/handler tables, blobs, class table -- and assert each boundary + count lands
        // where the running image stashed it, proving the metal writer reconstructs the whole image map.
        Uart.putc(fixpointDataLayout() ? 0x48 : 0x78);     // 'H' data-region layout reproduced / 'x' off
        Uart.putc(0x0A);

        // M5.5c step 4: whole code-region content fixpoint. Compile every one of the 485 boot-closure
        // methods at its image base with all relocations resolved to image addresses, and assert each
        // is byte-identical to the running image -- the metal writer reproduces the exact machine code
        // it is executing, across the whole code region.
        Uart.putc(fixpointCode() ? 0x24 : 0x78);           // '$' code content byte-identical / 'x' differ
        Uart.putc(0x0A);

        // M5.5c step 4 CAPSTONE -- the whole-image self-build fixpoint. Every immutable region is now
        // byte-identical to the running image: all 485 methods' code ('$' above), and every data region
        // after it -- Type records, TIB vtables, itables, interned string byte[]s, unwind frame/handler
        // tables, blobs, and the class table. (The mutable statics data segment is excluded: runtime has
        // written it -- Config.<clinit>, counters, freeHead; its layout + writer-stashed values are
        // covered by 'H'.) joe-ng has reconstructed, on bare metal, the exact image it is running.
        Uart.write(fixpointDataContent() ? Magic.bytes("FIX") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 1: materialise the clean reproduced image (image') into a heap buffer -- the exact
        // kernel8.img the seed would emit -- ready to persist to storage. Verifies its immutable regions
        // match the running image and its statics segment is reset to the as-written values.
        Uart.write(fixpointMaterialize() ? Magic.bytes("IMG") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 2: EMMC single-sector read. Bring up the SD controller + card (auto-detecting
        // EMMC2 on real hardware vs EMMC under QEMU), read block 0, and check the boot-sector signature
        // 0xAA55 at byte 510 -- present on any partitioned/FAT card, so it works on the test SD and a
        // real card alike. Proves the driver can read the medium it will persist the image to.
        Uart.write(sdReadOk() ? Magic.bytes("SD") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 2b: EMMC single-sector write. Write a known pattern to a scratch block, read it
        // back, and verify the round-trip -- proving the driver can mutate the card it will persist to.
        Uart.write(sdWriteOk() ? Magic.bytes("WR") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 3: FAT32 write. Mount the boot partition, find KERNEL8.IMG, overwrite its whole
        // cluster chain with a known pattern and read it back byte-identical -- the file-level write
        // path the self-build will use to persist image'.
        Uart.write(fatWriteOk() ? Magic.bytes("FAT") : Magic.bytes("x"));
        Uart.putc(0x0A);

        // M5.5d slice 4 -- THE SELF-HOSTING LOOP. Reproduce image', write it over the SD card's
        // KERNEL8.IMG, verify the readback, then reset the SoC: the firmware reloads the image joe-ng
        // just wrote and boots it, and it reproduces itself again. "Drop the seed JVM" made literal.
        if (persistImage())
        {
            Uart.write(Magic.bytes("PST rebooting into the self-written image\n"));
            Reset.reboot();                                  // never returns
        }
        Uart.write(Magic.bytes("x\n"));                      // no SD card / size mismatch: skip the reboot
    }

    /**
     * Materialise image' and write it over the SD card's KERNEL8.IMG (the file the firmware boots),
     * verifying the readback. Returns true only if the medium now holds the reproduced image. Skips
     * (false) when there is no card or the on-card file differs in size from the reproduction (so a
     * plain dev boot with no matching SD does not reboot).
     */
    private static boolean persistImage()
    {
        discoverImage();
        layoutDataRegions();
        long len = imageEndWord() * 4L;
        if (Emmc.init() != 0 || !Fat32.mount() || !Fat32.findKernel() || Fat32.kernelSize() != len)
        {
            return false;
        }
        long buf = materializeImage();
        long wlen = (len + 511L) / 512L * 512L;              // whole sectors
        long chk = Heap.alloc((int) wlen);
        if (!Fat32.writeKernel(buf, wlen) || !Fat32.readKernel(chk, wlen))
        {
            return false;
        }
        long i = 0L;
        while (i < len)                                      // the written file must read back identical
        {
            if ((Magic.load32(chk + i) & 0xFFFFFFFFL) != (Magic.load32(buf + i) & 0xFFFFFFFFL))
            {
                return false;
            }
            i += 4L;
        }
        return true;
    }

    /** Whether KERNEL8.IMG can be located and its cluster chain rewritten + read back byte-identical. */
    private static boolean fatWriteOk()
    {
        if (Emmc.init() != 0 || !Fat32.mount() || !Fat32.findKernel())
        {
            return false;
        }
        long nbytes = (Fat32.kernelSize() + 511L) / 512L * 512L;   // whole sectors of the file
        long w = Heap.alloc((int) nbytes);
        long r = Heap.alloc((int) nbytes);
        long i = 0L;
        while (i < nbytes)
        {
            Magic.store32(w + i, (int) (0xFA700000L + i));         // a recognizable per-word pattern
            i += 4L;
        }
        if (!Fat32.writeKernel(w, nbytes) || !Fat32.readKernel(r, nbytes))
        {
            return false;
        }
        i = 0L;
        while (i < nbytes)
        {
            if ((Magic.load32(r + i) & 0xFFFFFFFFL) != ((0xFA700000L + i) & 0xFFFFFFFFL))
            {
                return false;
            }
            i += 4L;
        }
        return true;
    }

    /** Whether the EMMC driver initialises and reads block 0 with a valid boot-sector signature. */
    private static boolean sdReadOk()
    {
        long sd = Heap.alloc(512);
        return Emmc.init() == 0
            && Emmc.readBlock(0L, sd)
            && Magic.load8(sd + 510L) == 0x55
            && Magic.load8(sd + 511L) == 0xAA;
    }

    /** Whether a written scratch block reads back byte-identical (single-sector write round-trip). */
    private static boolean sdWriteOk()
    {
        long w = Heap.alloc(512);
        long r = Heap.alloc(512);
        int i = 0;
        while (i < 128)                                    // fill with a recognizable pattern
        {
            Magic.store32(w + i * 4L, 0x5EED1234 + i);
            i += 1;
        }
        if (Emmc.init() != 0 || !Emmc.writeBlock(4096L, w) || !Emmc.readBlock(4096L, r))
        {
            return false;                                  // block 4096 = a scratch sector well past the boot area
        }
        i = 0;
        while (i < 128)
        {
            if (Magic.load32(r + i * 4L) != 0x5EED1234 + i)
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** Whether every immutable data region is byte-identical to the running image (mutable statics excluded). */
    private static boolean fixpointDataContent()
    {
        discoverImage();
        layoutDataRegions();
        return firstDataMismatch() < 0;
    }

    // ----- M5.5d slice 1: materialise the clean reproduced image (image') into a heap buffer -----

    /** Total word length of the image, through the end of the class-table bytes. */
    private static int imageEndWord()
    {
        int cc = (int) classCount;
        int cur = dClassDirStart + cc * (4 * (ObjectModel.WORD / 4));
        int i = 0;
        while (i < cc)
        {
            long e = classDir + i * 32L;
            cur += align8W((int) Magic.load64(e + 8L)) + align8W((int) Magic.load64(e + 24L));
            i += 1;
        }
        return cur;
    }

    /**
     * Build the clean reproduced image {@code image'} into a fresh heap buffer: the immutable regions
     * (code, Types, TIBs, itables, strings, unwind tables, blobs, class table) are byte-identical to the
     * running image and copied from it (proven by the FIX fixpoint); the mutable statics data segment is
     * (re)written to its *as-written* values -- zero except the writer-stashed addresses/counts -- rather
     * than the runtime-mutated values the live copy now holds. The result is exactly what the seed would
     * have emitted as {@code kernel8.img}, ready to persist to storage (M5.5d). Requires discovery + layout.
     */
    private static long materializeImage()
    {
        int endW = imageEndWord();
        long buf = Heap.alloc((endW * 4 + 511) & ~511);      // padded to a whole sector (zeroed tail)
        int w = 0;
        while (w < endW)                                     // copy the whole running image (byte-identical)
        {
            Magic.store32(buf + w * 4L, Magic.load32(dAddr(w)));
            w += 1;
        }
        int si = 0;
        while (si < drStatN)                                 // overwrite each static slot with its clean value
        {
            long val = staticValue(drStatCls[si], drStatName[si]);
            long slot = buf + (dStaticsStart + si * (ObjectModel.WORD / 4)) * 4L;
            Magic.store32(slot, (int) val);
            Magic.store32(slot + 4L, (int) (val >>> 32));
            si += 1;
        }
        return buf;
    }

    /** Whether {@code buf}'s words [lo,hi) equal the running image's. */
    private static boolean regionMatches(long buf, int lo, int hi)
    {
        int w = lo;
        while (w < hi)
        {
            if ((Magic.load32(buf + w * 4L) & 0xFFFFFFFFL) != (Magic.load32(dAddr(w)) & 0xFFFFFFFFL))
            {
                return false;
            }
            w += 1;
        }
        return true;
    }

    /**
     * M5.5d slice 1: build image' and verify it is a clean reproduction -- its immutable regions match
     * the running image byte-for-byte, and its statics data segment is *reset* to the as-written values
     * (proven by Config.mark: 0 in image', 0x37 in the live image where its {@code <clinit>} ran).
     */
    private static boolean fixpointMaterialize()
    {
        discoverImage();
        layoutDataRegions();
        long buf = materializeImage();
        if (!regionMatches(buf, 0, dStaticsStart) || !regionMatches(buf, dStaticsEnd, imageEndWord()))
        {
            return false;                                    // immutable regions must be byte-identical
        }
        int markW = (int) ((statImgAddr(Magic.bytes("vm/Config"), Magic.bytes("mark")) - 0x8_0000L) / 4L);
        return Magic.load32(buf + markW * 4L) == 0          // image' has the clean (zero) static
            && Magic.load32(dAddr(markW)) == 0x37;          // the live image has the runtime-set value
    }

    /** Whether every stashed data-region boundary + unwind count matches the metal writer's layout. */
    private static boolean fixpointDataLayout()
    {
        discoverImage();
        layoutDataRegions();
        return dAddr(dStaticsStart) == staticsStart
            && dAddr(dStaticsEnd) == staticsEnd
            && dAddr(dFrameStart) == frameTable
            && dAddr(dHandlerStart) == handlerTable
            && dAddr(dBlobStart) == guestBytes
            && dAddr(dClassDirStart) == classDir
            && drFrameCount == frameCount
            && drHandlerCount == handlerCount;
    }

    /** Whether every stashed method-address anchor lands at the metal writer's derived offset. */
    private static boolean fixpointCodeLayout()
    {
        discoverImage();
        return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("reportFault"), Magic.bytes("()V")) == reportFaultAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V")) == gcCollect
            && imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J")) == heapAlloc
            && imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J")) == allocArray
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I")) == instanceOfAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J")) == checkCastAddr
            && imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V")) == unwindAddr;
    }

    // ----- M5.5c step 3b.2: object allocation (new -> tib + Type/TIB region) -----

    private static byte[][] nbClass;   // distinct classes needing a laid-out Type/TIB (new or type-tested)
    private static long[] nbTibAddr;   // ... its TIB address
    private static long[] nbTypeAddr;  // ... its Type address
    private static int nbCount;

    private static byte[][] ifIface;   // distinct interfaces referenced by invokeinterface
    private static long[] ifTypeAddr;  // ... its (interface) Type address
    private static int ifCount;

    private static long excSlot;       // the closure's in-flight-exception word (athrow/catch)

    /**
     * Build {@code Cell.make} (= {@code new Cell(v).value}) into a Heap buffer, lay out
     * Cell's Type + TIB via {@link MetalClassModel}, patch the `new`'s TIB address load and
     * the {@code Heap.alloc} helper call, then run {@code make(0x37)} — which must return 0x37.
     */
    private static boolean selfBuildNewAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("make");
        clDesc[0] = Magic.bytes("(I)I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long makeEntry = codeBuf + clWordOff[0] * 4L;
        long r = Magic.call2(makeEntry, 0x37L, 0L);        // make(0x37)
        return ok && (int) r == 0x37;
    }

    /**
     * Build {@code Cell.selfCheck} (= {@code new Cell(0) instanceof Cell ? 1 : 0}) into a Heap
     * buffer, patch the `new`, the `instanceof` Type load, and the {@code VM.instanceOf} helper
     * call, then run it — which must return 1 (a Cell is a Cell).
     */
    private static boolean selfBuildInstanceofAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("selfCheck");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // selfCheck()
        return ok && (int) r == 1;
    }

    /** Lay out the Types/TIBs (and interface Types + itables) the closure needs. */
    private static void layoutClassRegions(long codeBuf)
    {
        nbClass = new byte[16][];
        nbTibAddr = new long[16];
        nbTypeAddr = new long[16];
        nbCount = 0;
        ifIface = new byte[16][];
        ifTypeAddr = new long[16];
        ifCount = 0;
        // pass 1: interface Types (needed as directory keys + interfaceType targets)
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int k = 0;
            while (k < sym.ifCount())
            {
                addInterfaceType(sym.ifClassOff(k));
                k += 1;
            }
            m += 1;
        }
        // pass 2: class Types + TIBs (+ itable directory for implementors)
        m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int t = 0;
            while (t < sym.tibCount())
            {
                addClassRegion(sym.tibClassOff(t), codeBuf);
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                addClassRegion(sym.typeClassOff(y), codeBuf);
                y += 1;
            }
            m += 1;
        }
    }

    /** Build an interface's Type ({@code {0,0,0}} — not instantiated) once, if new. */
    private static void addInterfaceType(int classOff)
    {
        if (findInterface(classOff) >= 0)
        {
            return;
        }
        ifIface[ifCount] = utf8Copy(classOff);
        ifTypeAddr[ifCount] = Heap.alloc(ObjectModel.TYPE_SIZE);   // zeroed: instanceSize/super/itableDir = 0
        ifCount += 1;
    }

    /** Index of the interface whose name equals the Utf8 at {@code classOff} in {@code cB}, or -1. */
    private static int findInterface(int classOff)
    {
        int j = 0;
        while (j < ifCount)
        {
            if (utf8Eq(cB, classOff, ifIface[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Build {@code classOff}'s Type + TIB (vtable filled from placed methods) once, if new. */
    private static void addClassRegion(int classOff, long codeBuf)
    {
        if (findTibClass(classOff) >= 0)
        {
            return;
        }
        byte[] name = utf8Copy(classOff);
        long type = Heap.alloc(ObjectModel.TYPE_SIZE);
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET,
                      ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(name)));
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, 0L);       // Cell's super is Object (a root)
        int vsize = MetalClassModel.vtableSize(name);                  // builds the vtable scratch
        long tib = Heap.alloc(ObjectModel.tibSize(vsize));
        Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT), type);
        int slot = 0;
        while (slot < vsize)                                           // fill each placed vtable method's address
        {
            int j = findPlacedBytes(MetalClassModel.vtableSlotName(slot), MetalClassModel.vtableSlotDesc(slot));
            if (j >= 0)
            {
                Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)),
                              codeBuf + clWordOff[j] * 4L);
            }
            slot += 1;
        }
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, buildItableDir(name, codeBuf));
        nbClass[nbCount] = name;
        nbTypeAddr[nbCount] = type;
        nbTibAddr[nbCount] = tib;
        nbCount += 1;
    }

    /** Build {@code clsName}'s itable directory over the referenced interfaces it implements (0 if none). */
    private static long buildItableDir(byte[] clsName, long codeBuf)
    {
        int impls = 0;
        int k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                impls += 1;
            }
            k += 1;
        }
        if (impls == 0)
        {
            return 0L;
        }
        long dir = Heap.alloc((impls + 1) * ObjectModel.ITABLE_ENTRY_SIZE);   // +1 zeroed sentinel
        int e = 0;
        k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                long entry = dir + e * ObjectModel.ITABLE_ENTRY_SIZE;
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET, ifTypeAddr[k]);
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET, buildItable(ifIface[k], codeBuf));
                e += 1;
            }
            k += 1;
        }
        return dir;
    }

    /** Build a class's itable for {@code iface}: each interface method's slot → the placed impl address. */
    private static long buildItable(byte[] iface, long codeBuf)
    {
        int n = MetalClassModel.interfaceMethodCount(iface);
        long itab = Heap.alloc(n * ObjectModel.WORD);
        int slot = 0;
        while (slot < n)
        {
            byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, slot);
            byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, slot);
            int j = findPlacedBytes(mName, mDesc);
            if (j >= 0)
            {
                Magic.store64(itab + slot * ObjectModel.WORD, codeBuf + clWordOff[j] * 4L);
            }
            slot += 1;
        }
        return itab;
    }

    /** Index of the placed method whose (name,desc) equal the byte arrays given, or -1. */
    private static int findPlacedBytes(byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < clCount)
        {
            if (bytesEqual(clName[j], name) && bytesEqual(clDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Whether two heap byte arrays are equal in length and content. */
    private static boolean bytesEqual(byte[] a, byte[] b)
    {
        if (a.length != b.length)
        {
            return false;
        }
        int i = 0;
        while (i < a.length)
        {
            if (a[i] != b[i])
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** Index of the laid-out class whose name equals the Utf8 at {@code classOff} in {@code cB}, or -1. */
    private static int findTibClass(int classOff)
    {
        int j = 0;
        while (j < nbCount)
        {
            if (utf8Eq(cB, classOff, nbClass[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Patch each method's calls, runtime-helper calls, and `new` TIB loads, then write it out. */
    private static boolean patchNewAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));
                }
                c += 1;
            }
            int h = 0;
            while (h < sym.helperCount())
            {
                int site = sym.helperSiteWord(h);
                long siteAbs = buf + (baseOff + site) * 4L;
                long rel = (helperAddr(sym.helperId(h)) - siteAbs) / 4L;   // BL to the image helper
                words[site] = A64Enc.bl((int) rel);
                h += 1;
            }
            int t = 0;
            while (t < sym.tibCount())
            {
                int k = findTibClass(sym.tibClassOff(t));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.tibSiteWord(t), sym.tibReg(t), nbTibAddr[k]);
                }
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                int k = findTibClass(sym.typeClassOff(y));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), nbTypeAddr[k]);
                }
                y += 1;
            }
            int s = 0;
            while (s < sym.strCount())
            {
                long arr = internLiteral(sym.strUtf8Off(s));   // lay the literal out as a byte[]
                patchAddrWords(words, sym.strSiteWord(s), sym.strReg(s), arr);
                s += 1;
            }
            int f = 0;
            while (f < sym.ifCount())
            {
                int k = findInterface(sym.ifClassOff(f));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), ifTypeAddr[k]);
                }
                f += 1;
            }
            int x = 0;
            while (x < sym.excCount())
            {
                patchAddrWords(words, sym.excSiteWord(x), sym.excReg(x), excSlot);  // the shared exc slot
                x += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    /** Intern the Utf8 literal at {@code cB[utf8Off]} as a heap {@code byte[]} (as {@code Loader.internString}). */
    private static long internLiteral(int utf8Off)
    {
        int len = ClassReader.u2(cB, utf8Off);
        long arr = Heap.allocArray(len, 1);
        int i = 0;
        while (i < len)
        {
            Magic.store8(arr + ObjectModel.ARRAY_BASE_OFFSET + i, ClassReader.u1(cB, utf8Off + 2 + i));
            i += 1;
        }
        return arr;
    }

    /**
     * Build {@code Cell.tag} (= {@code Magic.bytes("Z")[0]}) into a Heap buffer, intern the "Z"
     * literal as a byte[] and patch the ldc-string address load to it, then run it → {@code 'Z'}.
     */
    private static boolean selfBuildStringAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("tag");
        clDesc[0] = Magic.bytes("()I");
        clCount = 1;

        int body = findMethodBody(clName[0], clDesc[0]);
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, 0L);
        clSym[0] = sym;
        clWords[0] = words;
        clSize[0] = words.length;
        clWordOff[0] = 0;

        long codeBuf = Heap.alloc(words.length * 4);
        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + words.length * 4L);

        long r = Magic.call0(codeBuf);                     // tag()
        return ok && (int) r == 0x5A;                      // 'Z'
    }

    /**
     * Build {@code Cell.viaVirtual} (= {@code new Cell(v); c.get()}) plus Cell's vtable methods
     * ({@code get}, {@code inc}) into a Heap buffer, fill Cell's TIB vtable with their addresses,
     * then run {@code viaVirtual(0x37)} — which dispatches {@code get()} through the TIB → 0x37.
     */
    private static boolean selfBuildVirtualAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Cell"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("viaVirtual");
        clDesc[0] = Magic.bytes("(I)I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("(I)V");
        clName[2] = Magic.bytes("get");         // Cell's vtable methods, placed so the TIB can
        clDesc[2] = Magic.bytes("()I");         // point its vtable slots at them
        clName[3] = Magic.bytes("inc");
        clDesc[3] = Magic.bytes("()V");
        clCount = 4;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call2(codeBuf + clWordOff[0] * 4L, 0x37L, 0L);  // viaVirtual(0x37)
        return ok && (int) r == 0x37;
    }

    /**
     * Build {@code Robot.probe} (= {@code Speaker s = new Robot(); s.speak()}) plus Robot's
     * {@code <init>}/{@code speak} into a Heap buffer, lay out Speaker's interface Type and
     * Robot's Type/TIB + itable directory, patch the interfaceType load, then run {@code probe()}
     * — which dispatches {@code speak()} through Robot's itable → {@code 'R'}.
     */
    private static boolean selfBuildInterfaceAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Robot"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("probe");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("()V");
        clName[2] = Magic.bytes("speak");   // the itable's impl for Speaker.speak
        clDesc[2] = Magic.bytes("()I");
        clCount = 3;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // probe()
        return ok && (int) r == 0x52;                       // 'R'
    }

    /**
     * Build {@code MyExc.probe} (= {@code try { throw new MyExc(); } catch (MyExc e) { return 1; }})
     * into a Heap buffer, lay out MyExc's Type/TIB and an exception slot, patch the `new`, the
     * catch-type load, the exception-slot loads, and the instanceOf helper, then run it → 1. The
     * throw/catch is same-method, so it resolves inline (no cross-method unwind).
     */
    private static boolean selfBuildExceptionAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/MyExc"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("probe");
        clDesc[0] = Magic.bytes("()I");
        clName[1] = Magic.bytes("<init>");
        clDesc[1] = Magic.bytes("()V");
        clCount = 2;

        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        excSlot = Heap.alloc(8);                            // the closure's in-flight-exception word
        layoutClassRegions(codeBuf);
        boolean ok = patchNewAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);

        long r = Magic.call0(codeBuf + clWordOff[0] * 4L);  // probe()
        return ok && (int) r == 1;
    }

    /** Absolute image address of the runtime helper with the given {@code compiler.Symbols} id. */
    private static long helperAddr(int id)
    {
        if (id == 0)
        {
            return heapAlloc;          // HEAP_ALLOC
        }
        if (id == 1)
        {
            return allocArray;         // HEAP_ALLOC_ARRAY
        }
        if (id == 2)
        {
            return gcCollect;          // GC_COLLECT
        }
        if (id == 3)
        {
            return instanceOfAddr;     // INSTANCE_OF
        }
        if (id == 4)
        {
            return checkCastAddr;      // CHECK_CAST
        }
        return unwindAddr;             // UNWIND
    }

    // ----- M5.5c step 3b.2: cross-class discovery (BFS over calls; per-method class context) -----

    private static byte[][] lcName;    // loaded-class cache: name / bytes / cp offsets / tags / afterCp
    private static byte[][] lcBytes;
    private static int[][] lcOff;
    private static int[][] lcTag;
    private static int[] lcAfterCp;
    private static int lcCount;

    private static byte[][] gmClsName; // discovered methods: owning class name (for matching)
    private static int[] gmClsIdx;     // ... its class-cache index (for context)
    private static byte[][] gmName;
    private static byte[][] gmDesc;
    private static int[] gmSize;
    private static int[] gmWordOff;
    private static int[][] gmWords;
    private static MetalWriterSymbols[] gmSym;
    private static int[] gmFrameSize;  // per method: frame size + machine handler ranges (cross-method unwind)
    private static int[] gmHN;
    private static int[][] gmHStart;
    private static int[][] gmHEnd;
    private static int[][] gmHandler;
    private static int[][] gmHCatch;   // catch-type Class cp index per handler
    private static int gmCount;

    /** Cross-class calls + statics: {@code Cell.readCounter} → {@code Counter.bump/get} → 1. */
    private static boolean selfBuildCrossAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("readCounter"), Magic.bytes("()I")) == 1;
    }

    /** Cross-class new + virtual: {@code Animal.dogSound} = {@code new Dog().sound()} → 'W'. */
    private static boolean selfBuildDynAndRun()
    {
        return buildClosure(Magic.bytes("vm/Animal"), Magic.bytes("dogSound"), Magic.bytes("()I")) == 0x57;
    }

    /** Cross-class interface: {@code Cell.viaSpeaker} = {@code new Robot(); s.speak()} → 'R'. */
    private static boolean selfBuildCrossIfaceAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("viaSpeaker"), Magic.bytes("()I")) == 0x52;
    }

    /**
     * The capstone: build {@code Cell.capstone}'s whole closure — spanning Cell, Robot, Speaker,
     * Dog, Animal, MyExc and Counter, exercising every relocation kind at once (new,
     * invokevirtual, invokeinterface, instanceof, ldc-string, cross-class call, throw/catch,
     * cross-class static) — and run it → 262. (Guest.answer would be ideal but Guest/Alpha/Beta/
     * Greeter are runtime-load blobs, not in the compile-reachable class table the writer reads.)
     */
    private static boolean selfBuildAnswerAndRun()
    {
        return buildClosure(Magic.bytes("vm/Cell"), Magic.bytes("capstone"), Magic.bytes("()I")) == 262;
    }

    /**
     * The self-build fixpoint in miniature (step 4 essence): recompile a method the seed writer
     * put in the running image — {@code VM.instanceOf}, stashed at {@code instanceOfAddr} and
     * relocation-free — on metal, and assert it is <em>byte-identical</em> to the image's own
     * copy. Byte-equal ⇒ joe-ng's metal writer reproduces the exact machine code it is running.
     */
    private static boolean selfFixpointInstanceOf()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/VM"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        int body = findMethodBody(Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, instanceOfAddr);   // compile at its real image base

        // It must be relocation-free (else its bytes depend on layout we're not reproducing here).
        if (sym.callCount() != 0 || sym.helperCount() != 0 || sym.staticCount() != 0
                || sym.tibCount() != 0 || sym.typeCount() != 0 || sym.strCount() != 0
                || sym.ifCount() != 0 || sym.excCount() != 0)
        {
            return false;
        }
        return fixpointEquals(words, instanceOfAddr);
    }

    /**
     * The fixpoint extended to a <em>relocation-bearing</em> method: {@code VM.checkCast} (stashed
     * at {@code checkCastAddr}) has exactly one reloc — a call to {@code VM.instanceOf} (stashed at
     * {@code instanceOfAddr}). Recompile it on metal, patch that call to the image's own instanceOf
     * address, and assert byte-equality with the image — the metal writer relocates as the seed did.
     */
    private static boolean selfFixpointCheckCast()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/VM"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        int body = findMethodBody(Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        if (body < 0)
        {
            return false;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
        int[] words = compileInto(body, sym, checkCastAddr);    // compile at its real image base

        // Exactly one relocation: a call to instanceOf; nothing else.
        if (sym.callCount() != 1 || !sym.callNameIs(0, Magic.bytes("instanceOf"))
                || sym.helperCount() != 0 || sym.staticCount() != 0 || sym.tibCount() != 0
                || sym.typeCount() != 0 || sym.strCount() != 0 || sym.ifCount() != 0 || sym.excCount() != 0)
        {
            return false;
        }
        int site = sym.callSiteWord(0);
        long siteAbs = checkCastAddr + site * 4L;
        words[site] = A64Enc.bl((int) ((instanceOfAddr - siteAbs) / 4L));   // BL to the image's instanceOf

        return fixpointEquals(words, checkCastAddr);
    }

    /** Whether {@code words} are byte-identical to the running image starting at {@code imgAddr}. */
    private static boolean fixpointEquals(int[] words, long imgAddr)
    {
        int w = 0;
        while (w < words.length)                            // mask to 32 bits: int[] sign-extends, load32 zero-extends
        {
            if ((words[w] & 0xFFFFFFFFL) != (Magic.load32(imgAddr + w * 4L) & 0xFFFFFFFFL))
            {
                return false;
            }
            w += 1;
        }
        return true;
    }

    /**
     * Discover, build, and run a method's closure across classes: BFS over recorded calls and
     * (for {@code new}) the instantiated classes' vtable methods; lay each class's Type/TIB out;
     * resolve every cross-class call, static, helper, and TIB load; then execute the entry and
     * return its result (a negative sentinel on a build failure).
     */
    private static long buildClosure(byte[] entryCls, byte[] entryName, byte[] entryDesc)
    {
        lcName = new byte[32][];
        lcBytes = new byte[32][];
        lcOff = new int[32][];
        lcTag = new int[32][];
        lcAfterCp = new int[32];
        lcCount = 0;
        gmClsName = new byte[64][];
        gmClsIdx = new int[64];
        gmName = new byte[64][];
        gmDesc = new byte[64][];
        gmSize = new int[64];
        gmWordOff = new int[64];
        gmWords = new int[64][];
        gmSym = new MetalWriterSymbols[64];
        gmFrameSize = new int[64];
        gmHN = new int[64];
        gmHStart = new int[64][];
        gmHEnd = new int[64][];
        gmHandler = new int[64][];
        gmHCatch = new int[64][];
        gmCount = 0;

        enqueueMethod(entryCls, entryName, entryDesc);

        int i = 0;
        while (i < gmCount)
        {
            setClassContext(gmClsIdx[i]);
            int body = findMethodBody(gmName[i], gmDesc[i]);
            if (body < 0)
            {
                return -1L;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            gmSym[i] = sym;
            gmWords[i] = words;
            gmSize[i] = words.length;
            gmFrameSize[i] = fFrameSize;                    // capture frame + handlers for unwind
            gmHN[i] = fHN;
            gmHStart[i] = new int[fHN];
            gmHEnd[i] = new int[fHN];
            gmHandler[i] = new int[fHN];
            gmHCatch[i] = new int[fHN];
            int h = 0;
            while (h < fHN)
            {
                gmHStart[i][h] = fHStart[h];
                gmHEnd[i][h] = fHEnd[h];
                gmHandler[i][h] = fHandler[h];
                gmHCatch[i][h] = fHCatch[h];
                h += 1;
            }
            discoverFrom(sym);
            i += 1;
        }

        int cur = 0;
        int p = 0;
        while (p < gmCount)
        {
            gmWordOff[p] = cur;
            cur += gmSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        collectStaticsG();
        long staticsBuf = Heap.alloc(stCount * 8);
        int s = 0;
        while (s < stCount)
        {
            long slot = staticsBuf + s * 8L;
            Magic.store64(slot, 0L);
            stAddr[s] = slot;
            s += 1;
        }

        excSlot = Heap.alloc(8);                            // the closure's in-flight-exception word
        layoutClassRegionsG(codeBuf);
        boolean ok = patchCrossAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);
        registerFramesAndHandlers(codeBuf);                 // so cross-method throw/catch can unwind

        runGeneratedInitClasses(codeBuf);                   // eager static init before the entry runs

        int entry = findMethodG(entryCls, entryName, entryDesc);
        long r = Magic.call0(codeBuf + gmWordOff[entry] * 4L);
        return ok ? r : -2L;
    }

    /**
     * Generate the closure's {@code initClasses} method on metal — a synthetic frame that saves
     * {@code LR}, {@code BL}s each discovered {@code <clinit>} in discovery order, restores, and
     * returns — then call it once. This reproduces the seed writer's synthetic eager-init method
     * ({@code ImageBuilder.generateInitClasses}) as real A64 the metal writer emits itself, rather
     * than a Java-side call loop, so the on-metal build matches the seed's shape (toward the
     * self-build fixpoint). A closure with no {@code <clinit>} generates nothing.
     */
    private static void runGeneratedInitClasses(long codeBuf)
    {
        byte[] clinit = Magic.bytes("<clinit>");
        int n = 0;
        int ci = 0;
        while (ci < gmCount)
        {
            if (bytesEqual(gmName[ci], clinit))
            {
                n += 1;
            }
            ci += 1;
        }
        if (n == 0)
        {
            return;
        }
        int total = 2 + n + 3;                              // prologue(2) + n BLs + epilogue(3)
        long initBuf = Heap.alloc(total * 4);
        int frame = A64Enc.align16(8);                      // LR only
        int w = 0;
        Magic.store32(initBuf + w * 4L, A64Enc.subImm(31, 31, frame));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.strx(30, 31, 0));
        w += 1;
        ci = 0;
        while (ci < gmCount)
        {
            if (bytesEqual(gmName[ci], clinit))
            {
                long here = initBuf + w * 4L;
                Magic.store32(here, A64Enc.bl((int) ((codeBuf + gmWordOff[ci] * 4L - here) >> 2)));
                w += 1;
            }
            ci += 1;
        }
        Magic.store32(initBuf + w * 4L, A64Enc.ldrx(30, 31, 0));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.addImm(31, 31, frame));
        w += 1;
        Magic.store32(initBuf + w * 4L, A64Enc.ret());
        w += 1;
        Heap.publishCode(initBuf, initBuf + total * 4L);
        long unused = Magic.call0(initBuf);                 // bound to a local (no pop2 in Baseline)
    }

    // ----- M5.5c step 4: whole-image code-region fixpoint (discovery + sizing order) -----
    // Reproduce the seed ImageBuilder's method discovery + layout order from vm/VM.boot, so each
    // method lands at the same 0x80000-relative word offset it occupies in the running image.
    // Validated against the seven stashed method-address anchors: if every derived offset matches
    // the image's own address, the code-region ordering has been reproduced byte-for-byte.
    private static byte[][] imClsName;   // per discovered method: class name / cache idx / name / desc
    private static int[] imCls;
    private static byte[][] imName;
    private static byte[][] imDesc;
    private static int[] imSize;         // ... its compiled word count (placement-independent)
    private static int imN;
    private static byte[][] usedCls;     // classes whose <clinit> has been scheduled (eager-init dedup)
    private static int usedN;
    private static byte[][] tibSeenCls;  // classes whose vtable has been expanded (tibClasses dedup)
    private static int tibSeenN;
    private static int clinitCount;      // discovered <clinit>s (the initClasses method's BL count)
    // data-region sets, collected during discovery in the seed's order (for the region layout)
    private static byte[][] drStr;       // interned string literals (content-deduped)
    private static int drStrN;
    private static byte[][] drTypeRef;   // instanceof/checkcast/interface/catch Type classes
    private static int drTypeRefN;
    private static byte[][] drUsedIf;    // invokeinterface target interfaces
    private static int drUsedIfN;
    private static byte[][] drStatCls;   // distinct static fields: owner class
    private static byte[][] drStatName;  // ... and field name
    private static int drStatN;
    private static int drFrameCount;     // methods with a frame (unwind frame-table entries)
    private static int drHandlerCount;   // total try/catch handlers (unwind handler-table entries)
    private static byte[][] typeClasses; // Types region: instantiated + type-ref classes + their supers
    private static int typeClassesN;
    // computed 0x80000-relative WORD offsets of each region boundary (see layoutDataRegions)
    private static int dTypesStart, dTibStart, dStrStart, dStaticsStart, dStaticsEnd;
    private static int dItStart, dFrameStart, dHandlerStart, dBlobStart, dClassDirStart;
    private static int[] dTibOff;        // parallel to tibSeenCls: each TIB's 0x80000-relative word offset
    private static int[] dStrOff;        // parallel to drStr: each interned byte[]'s word offset
    private static int[] dItDirOff;      // parallel to tibSeenCls: itable-directory word offset, or -1 (no itables)
    private static int[] dBlobOff;       // the 6 embedded blobs' word offsets (Guest/Greeter/Alpha/Beta/MyExc/Math)
    // per-method frame + handler info (parallel to im*), for the unwind-table content
    private static int[] imFrameSize;
    private static int[] imHNa;
    private static int[][] imHStartA;
    private static int[][] imHEndA;
    private static int[][] imHandlerA;
    private static byte[][][] imHCatchCls;   // per handler: catch class name bytes, or null (finally)

    private static int imFind(byte[] cls, byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < imN)
        {
            if (bytesEqual(imClsName[j], cls) && bytesEqual(imName[j], name) && bytesEqual(imDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Enqueue a method in discovery order; dedup-at-enqueue == the seed's FIFO dedup-at-dequeue. */
    private static void imEnqueue(byte[] cls, byte[] name, byte[] desc)
    {
        if (bytesEqual(cls, Magic.bytes("vm/VM")) && bytesEqual(name, Magic.bytes("initClasses")))
        {
            return;    // initClasses is generated, not discovered — the seed places it last (see discoverImage)
        }
        if (imFind(cls, name, desc) >= 0)
        {
            return;
        }
        imClsName[imN] = cls;
        imCls[imN] = loadClass(cls);
        imName[imN] = name;
        imDesc[imN] = desc;
        imN += 1;
    }

    private static boolean usedAdd(byte[] cls)
    {
        int j = 0;
        while (j < usedN)
        {
            if (bytesEqual(usedCls[j], cls))
            {
                return false;
            }
            j += 1;
        }
        usedCls[usedN] = cls;
        usedN += 1;
        return true;
    }

    /** The seed's use(): on a class's first use, schedule its {@code <clinit>} (eager init). */
    private static void useClinit(byte[] cls)
    {
        if (usedAdd(cls) && MetalClassModel.hasClinit(cls))
        {
            clinitCount += 1;
            imEnqueue(cls, Magic.bytes("<clinit>"), Magic.bytes("()V"));
        }
    }

    private static boolean tibSeenAdd(byte[] cls)
    {
        int j = 0;
        while (j < tibSeenN)
        {
            if (bytesEqual(tibSeenCls[j], cls))
            {
                return false;
            }
            j += 1;
        }
        tibSeenCls[tibSeenN] = cls;
        tibSeenN += 1;
        return true;
    }

    /** 0x80000-relative address of discovered method (cls,name,desc), or 0 if not discovered. */
    private static long imAddrOf(byte[] cls, byte[] name, byte[] desc)
    {
        int j = imFind(cls, name, desc);
        if (j < 0)
        {
            return 0L;
        }
        long off = 0L;
        int i = 0;
        while (i < j)
        {
            off += imSize[i];
            i += 1;
        }
        return 0x8_0000L + off * 4L;                         // CodeBuffer.LOAD_ADDRESS (the image base)
    }

    /** Discover + size the whole {@code boot} closure in the seed's exact order (no relocation, no run). */
    private static void discoverImage()
    {
        lcName = new byte[256][];
        lcBytes = new byte[256][];
        lcOff = new int[256][];
        lcTag = new int[256][];
        lcAfterCp = new int[256];
        lcCount = 0;
        imClsName = new byte[2048][];
        imCls = new int[2048];
        imName = new byte[2048][];
        imDesc = new byte[2048][];
        imSize = new int[2048];
        imFrameSize = new int[2048];
        imHNa = new int[2048];
        imHStartA = new int[2048][];
        imHEndA = new int[2048][];
        imHandlerA = new int[2048][];
        imHCatchCls = new byte[2048][][];
        imN = 0;
        usedCls = new byte[512][];
        usedN = 0;
        tibSeenCls = new byte[128][];
        tibSeenN = 0;
        clinitCount = 0;
        drStr = new byte[2048][];
        drStrN = 0;
        drTypeRef = new byte[256][];
        drTypeRefN = 0;
        drUsedIf = new byte[128][];
        drUsedIfN = 0;
        drStatCls = new byte[1024][];
        drStatName = new byte[1024][];
        drStatN = 0;
        drFrameCount = 0;
        drHandlerCount = 0;

        imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("boot"), Magic.bytes("()V"));
        int i = 0;
        while (i < imN)
        {
            setClassContext(imCls[i]);
            int body = findMethodBody(imName[i], imDesc[i]);
            boolean isEntry = i == 0;                        // boot is the frameless entry (as the seed)
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            imSize[i] = compileInto(body, sym, 0L, isEntry).length;
            imFrameSize[i] = fFrameSize;                      // capture frame + handler info for the unwind tables
            imHNa[i] = fHN;
            imHStartA[i] = new int[fHN];
            imHEndA[i] = new int[fHN];
            imHandlerA[i] = new int[fHN];
            imHCatchCls[i] = new byte[fHN][];
            int hh = 0;
            while (hh < fHN)
            {
                imHStartA[i][hh] = fHStart[hh];
                imHEndA[i][hh] = fHEnd[hh];
                imHandlerA[i][hh] = fHandler[hh];
                imHCatchCls[i][hh] = fHCatch[hh] == 0 ? null : utf8Copy(ClassReader.classNameOff(cB, cOff, fHCatch[hh]));
                hh += 1;
            }
            discoverImageFrom(sym, imClsName[i]);
            i += 1;
        }
        // initClasses is placed last, its body generated: SUB/STR + one BL per discovered <clinit> + LDR/ADD/RET.
        imClsName[imN] = Magic.bytes("vm/VM");
        imCls[imN] = loadClass(Magic.bytes("vm/VM"));
        imName[imN] = Magic.bytes("initClasses");
        imDesc[imN] = Magic.bytes("()V");
        imSize[imN] = 2 + clinitCount + 3;
        imFrameSize[imN] = A64Enc.align16(8);               // initClasses frame (LR save), no handlers
        imHNa[imN] = 0;
        imHStartA[imN] = new int[0];
        imHEndA[imN] = new int[0];
        imHandlerA[imN] = new int[0];
        imHCatchCls[imN] = new byte[0][];
        imN += 1;
        drFrameCount += 1;                                   // initClasses has a frame (LR save) too
    }

    /** Enqueue the runtime-helper method the writer synthesised a {@code BL} to (matches WriterSymbols.HELPER_KEY). */
    private static void imEnqueueHelper(int id)
    {
        if (id == 0)
        {
            imEnqueue(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J"));
        }
        else if (id == 1)
        {
            imEnqueue(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J"));
        }
        else if (id == 2)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V"));
        }
        else if (id == 3)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        }
        else if (id == 4)
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        }
        else
        {
            imEnqueue(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V"));
        }
    }

    /** Append method's callees, eager {@code <clinit>}s, and new classes' vtable methods, in the seed's order. */
    private static void discoverImageFrom(MetalWriterSymbols sym, byte[] ownerCls)
    {
        // 1: callees + synthesised helper calls, merged by emission order (the seed unifies both in
        //    one callSites list; the metal writer splits them, so re-merge by ascending word index).
        int ci = 0;
        int hi = 0;
        int nc = sym.callCount();
        int nh = sym.helperCount();
        while (ci < nc || hi < nh)
        {
            boolean takeCall;
            if (ci >= nc)
            {
                takeCall = false;
            }
            else if (hi >= nh)
            {
                takeCall = true;
            }
            else
            {
                takeCall = sym.callSiteWord(ci) < sym.helperSiteWord(hi);
            }
            if (takeCall)
            {
                imEnqueue(utf8Copy(sym.callClassOff(ci)), utf8Copy(sym.callNameOff(ci)), utf8Copy(sym.callDescOff(ci)));
                ci += 1;
            }
            else
            {
                imEnqueueHelper(sym.helperId(hi));
                hi += 1;
            }
        }
        // 2: data-region sets, in the seed's per-method order (strings, type refs, interface refs,
        //    handler catch classes, unwind counts) -- these feed the Types/strings/itable/unwind layout.
        int sr = 0;
        while (sr < sym.strCount())
        {
            drAddStr(utf8Copy(sym.strUtf8Off(sr)));
            sr += 1;
        }
        int ty = 0;
        while (ty < sym.typeCount())
        {
            drAddTypeRef(utf8Copy(sym.typeClassOff(ty)));
            ty += 1;
        }
        int inf = 0;
        while (inf < sym.ifCount())
        {
            byte[] ic = utf8Copy(sym.ifClassOff(inf));
            drAddTypeRef(ic);
            drAddUsedIf(ic);
            inf += 1;
        }
        int hc = 0;
        while (hc < fHN)                                      // catch classes are type-checked (VM.instanceOf)
        {
            if (fHCatch[hc] != 0)
            {
                drAddTypeRef(utf8Copy(ClassReader.classNameOff(cB, cOff, fHCatch[hc])));
            }
            hc += 1;
        }
        if (fFrameSize > 0)
        {
            drFrameCount += 1;
        }
        drHandlerCount += fHN;

        int t = 0;
        while (t < sym.tibCount())                           // 3: each new'd class's <clinit>
        {
            useClinit(utf8Copy(sym.tibClassOff(t)));
            t += 1;
        }
        // 4: statics region -- real static fields (getstatic/putstatic) and the shared $exception
        //    slot (athrow/catch), merged by emission order as the seed's single staticRefs list is,
        //    plus each field owner's <clinit>.
        int si = 0;
        int xi = 0;
        int ns = sym.staticCount();
        int nx = sym.excCount();
        while (si < ns || xi < nx)
        {
            boolean takeStatic;
            if (si >= ns)
            {
                takeStatic = false;
            }
            else if (xi >= nx)
            {
                takeStatic = true;
            }
            else
            {
                takeStatic = sym.staticSiteWord(si) < sym.excSiteWord(xi);
            }
            if (takeStatic)
            {
                drAddStat(utf8Copy(sym.staticClassOff(si)), utf8Copy(sym.staticNameOff(si)));
                useClinit(utf8Copy(sym.staticClassOff(si)));
                si += 1;
            }
            else
            {
                drAddStat(Magic.bytes("vm/VM"), Magic.bytes("$exception"));
                xi += 1;
            }
        }
        useClinit(ownerCls);                                 // 5: the method's own class <clinit>
        t = 0;
        while (t < sym.tibCount())                           // 6: a newly instantiated class's vtable methods
        {
            byte[] tc = utf8Copy(sym.tibClassOff(t));
            if (tibSeenAdd(tc))
            {
                int vs = MetalClassModel.vtableSize(tc);
                int sl = 0;
                while (sl < vs)
                {
                    imEnqueue(MetalClassModel.vtableSlotOwner(sl), MetalClassModel.vtableSlotName(sl),
                              MetalClassModel.vtableSlotDesc(sl));
                    sl += 1;
                }
            }
            t += 1;
        }
    }

    private static void drAddStr(byte[] s)
    {
        int i = 0;
        while (i < drStrN)
        {
            if (bytesEqual(drStr[i], s))
            {
                return;
            }
            i += 1;
        }
        drStr[drStrN] = s;
        drStrN += 1;
    }

    private static void drAddTypeRef(byte[] c)
    {
        int i = 0;
        while (i < drTypeRefN)
        {
            if (bytesEqual(drTypeRef[i], c))
            {
                return;
            }
            i += 1;
        }
        drTypeRef[drTypeRefN] = c;
        drTypeRefN += 1;
    }

    private static void drAddUsedIf(byte[] c)
    {
        int i = 0;
        while (i < drUsedIfN)
        {
            if (bytesEqual(drUsedIf[i], c))
            {
                return;
            }
            i += 1;
        }
        drUsedIf[drUsedIfN] = c;
        drUsedIfN += 1;
    }

    private static void drAddStat(byte[] cls, byte[] nm)
    {
        int i = 0;
        while (i < drStatN)
        {
            if (bytesEqual(drStatCls[i], cls) && bytesEqual(drStatName[i], nm))
            {
                return;
            }
            i += 1;
        }
        drStatCls[drStatN] = cls;
        drStatName[drStatN] = nm;
        drStatN += 1;
    }

    /** The seed's addTypeClass: add {@code cls} and each non-root superclass up the chain (dedup). */
    private static void addTypeClass(byte[] cls)
    {
        while (cls != null && !MetalClassModel.isRoot(cls) && typeClassAdd(cls))
        {
            cls = MetalClassModel.superName(cls);
        }
    }

    private static boolean typeClassAdd(byte[] cls)
    {
        int i = 0;
        while (i < typeClassesN)
        {
            if (bytesEqual(typeClasses[i], cls))
            {
                return false;
            }
            i += 1;
        }
        typeClasses[typeClassesN] = cls;
        typeClassesN += 1;
        return true;
    }

    /**
     * Reproduce the seed ImageBuilder's data-region layout after the code region, computing each
     * region's 0x80000-relative WORD offset: [Types][TIBs][strings][statics][itables][frame table]
     * [handler table][blobs][class table]. Requires {@link #discoverImage} to have run (it fills the
     * sets this consumes). Validated against the stashed region anchors.
     */
    private static void layoutDataRegions()
    {
        int cur = 0;
        int i = 0;
        while (i < imN)                                      // code region (all methods + initClasses)
        {
            cur += imSize[i];
            i += 1;
        }
        cur += cur % 2;                                      // pad to 8 bytes before the data regions

        // Types: instantiated classes + type-ref classes, each with its whole superclass chain.
        typeClasses = new byte[256][];
        typeClassesN = 0;
        int t = 0;
        while (t < tibSeenN)
        {
            addTypeClass(tibSeenCls[t]);
            t += 1;
        }
        t = 0;
        while (t < drTypeRefN)
        {
            addTypeClass(drTypeRef[t]);
            t += 1;
        }
        dTypesStart = cur;
        cur += typeClassesN * (ObjectModel.TYPE_SIZE / 4);

        dTibStart = cur;                                     // TIBs: one per instantiated class
        dTibOff = new int[tibSeenN];
        t = 0;
        while (t < tibSeenN)
        {
            dTibOff[t] = cur;
            cur += ObjectModel.tibSize(MetalClassModel.vtableSize(tibSeenCls[t])) / 4;
            t += 1;
        }

        dStrStart = cur;                                     // interned string byte[] literals
        dStrOff = new int[drStrN];
        int s = 0;
        while (s < drStrN)
        {
            dStrOff[s] = cur;
            cur += (ObjectModel.ARRAY_BASE_OFFSET + ((drStr[s].length + 7) & ~7)) / 4;
            s += 1;
        }

        dStaticsStart = cur;                                 // one 8-byte slot per distinct static field
        cur += drStatN * (ObjectModel.WORD / 4);
        dStaticsEnd = cur;

        dItStart = cur;                                      // itable directories + itables per class
        dItDirOff = new int[tibSeenN];
        t = 0;
        while (t < tibSeenN)
        {
            int impls = 0;
            int u = 0;
            while (u < drUsedIfN)
            {
                if (MetalClassModel.implementsInterface(tibSeenCls[t], drUsedIf[u]))
                {
                    impls += 1;
                }
                u += 1;
            }
            if (impls > 0)
            {
                dItDirOff[t] = cur;
                cur += (impls + 1) * (ObjectModel.ITABLE_ENTRY_SIZE / 4);   // +1 zeroed sentinel
                u = 0;
                while (u < drUsedIfN)
                {
                    if (MetalClassModel.implementsInterface(tibSeenCls[t], drUsedIf[u]))
                    {
                        cur += MetalClassModel.interfaceMethodCount(drUsedIf[u]) * (ObjectModel.WORD / 4);
                    }
                    u += 1;
                }
            }
            else
            {
                dItDirOff[t] = -1;
            }
            t += 1;
        }

        dFrameStart = cur;                                   // unwind: frame entries (6 words each)
        cur += drFrameCount * 6;
        dHandlerStart = cur;                                 // unwind: handler entries (8 words each)
        cur += drHandlerCount * 8;

        dBlobStart = cur;                                    // embedded raw .class blobs, 8-byte aligned
        dBlobOff = new int[6];
        int bb = 0;
        while (bb < 6)
        {
            dBlobOff[bb] = cur;
            cur += align8W(MetalClassModel.bytesOf(blobClass(bb)).length);
            bb += 1;
        }

        dClassDirStart = cur;                                // class table directory (names + bytes follow)
    }

    /** Image words an 8-byte-aligned run of {@code len} bytes occupies (companion to the seed's align8Words). */
    private static int align8W(int len)
    {
        return ((len + 7) & ~7) / 4;
    }

    private static long dAddr(int word)
    {
        return 0x8_0000L + word * 4L;
    }

    // ----- image-address resolvers for each relocation kind (over the reproduced layout) -----

    private static int findByteArr(byte[][] arr, int n, byte[] want)
    {
        int i = 0;
        while (i < n)
        {
            if (bytesEqual(arr[i], want))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Image Type address of {@code cls} (a class or interface — both live in the Types region). */
    private static long typeImgAddr(byte[] cls)
    {
        int i = findByteArr(typeClasses, typeClassesN, cls);
        return i < 0 ? 0L : dAddr(dTypesStart + i * (ObjectModel.TYPE_SIZE / 4));
    }

    private static long tibImgAddr(byte[] cls)
    {
        int j = findByteArr(tibSeenCls, tibSeenN, cls);
        return j < 0 ? 0L : dAddr(dTibOff[j]);
    }

    private static long strImgAddr(byte[] s)
    {
        int k = findByteArr(drStr, drStrN, s);
        return k < 0 ? 0L : dAddr(dStrOff[k]);
    }

    private static long statImgAddr(byte[] cls, byte[] nm)
    {
        int i = 0;
        while (i < drStatN)
        {
            if (bytesEqual(drStatCls[i], cls) && bytesEqual(drStatName[i], nm))
            {
                return dAddr(dStaticsStart + i * (ObjectModel.WORD / 4));
            }
            i += 1;
        }
        return 0L;
    }

    /** Image address of the runtime helper with id {@code id} (matches imEnqueueHelper's method keys). */
    private static long helperImgAddr(int id)
    {
        if (id == 0)
        {
            return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J"));
        }
        if (id == 1)
        {
            return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J"));
        }
        if (id == 2)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V"));
        }
        if (id == 3)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I"));
        }
        if (id == 4)
        {
            return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J"));
        }
        return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V"));
    }

    /** Patch every relocation site in {@code sym}'s just-compiled {@code words} to its image address. */
    private static void patchImageRelocs(MetalWriterSymbols sym, int[] words, long base)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            int site = sym.callSiteWord(c);
            long target = imAddrOf(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)), utf8Copy(sym.callDescOff(c)));
            words[site] = A64Enc.bl((int) ((target - (base + site * 4L)) / 4L));
            c += 1;
        }
        int h = 0;
        while (h < sym.helperCount())
        {
            int site = sym.helperSiteWord(h);
            long target = helperImgAddr(sym.helperId(h));
            words[site] = A64Enc.bl((int) ((target - (base + site * 4L)) / 4L));
            h += 1;
        }
        int b = 0;
        while (b < sym.tibCount())
        {
            patchAddrWords(words, sym.tibSiteWord(b), sym.tibReg(b), tibImgAddr(utf8Copy(sym.tibClassOff(b))));
            b += 1;
        }
        int y = 0;
        while (y < sym.typeCount())
        {
            patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), typeImgAddr(utf8Copy(sym.typeClassOff(y))));
            y += 1;
        }
        int f = 0;
        while (f < sym.ifCount())
        {
            patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), typeImgAddr(utf8Copy(sym.ifClassOff(f))));
            f += 1;
        }
        int st = 0;
        while (st < sym.staticCount())
        {
            patchAddrWords(words, sym.staticSiteWord(st), sym.staticReg(st),
                           statImgAddr(utf8Copy(sym.staticClassOff(st)), utf8Copy(sym.staticNameOff(st))));
            st += 1;
        }
        int sg = 0;
        while (sg < sym.strCount())
        {
            patchAddrWords(words, sym.strSiteWord(sg), sym.strReg(sg), strImgAddr(utf8Copy(sym.strUtf8Off(sg))));
            sg += 1;
        }
        int x = 0;
        while (x < sym.excCount())
        {
            patchAddrWords(words, sym.excSiteWord(x), sym.excReg(x),
                           statImgAddr(Magic.bytes("vm/VM"), Magic.bytes("$exception")));
            x += 1;
        }
        int pc = 0;
        while (pc < sym.pcCount())
        {
            patchAddrWords(words, sym.pcSiteWord(pc), sym.pcReg(pc), base + sym.pcTarget(pc) * 4L);
            pc += 1;
        }
    }

    /** The generated initClasses method's words at image {@code base}: BL each {@code <clinit>} (in
     *  discovery order) to its image address, framed exactly as {@link #runGeneratedInitClasses}. */
    private static int[] genInitClassesWords(long base)
    {
        byte[] clinit = Magic.bytes("<clinit>");
        int frame = A64Enc.align16(8);
        int[] words = new int[2 + clinitCount + 3];
        words[0] = A64Enc.subImm(31, 31, frame);
        words[1] = A64Enc.strx(30, 31, 0);
        int wi = 2;
        int j = 0;
        while (j < imN)
        {
            if (bytesEqual(imName[j], clinit))
            {
                long target = imAddrOf(imClsName[j], clinit, Magic.bytes("()V"));
                words[wi] = A64Enc.bl((int) ((target - (base + wi * 4L)) / 4L));
                wi += 1;
            }
            j += 1;
        }
        words[wi] = A64Enc.ldrx(30, 31, 0);
        words[wi + 1] = A64Enc.addImm(31, 31, frame);
        words[wi + 2] = A64Enc.ret();
        return words;
    }

    /** Whole code-region content fixpoint: compile every method at its image base with all relocations
     *  resolved to image addresses, and word-compare against the running image. */
    private static boolean fixpointCode()
    {
        discoverImage();
        layoutDataRegions();
        byte[] initName = Magic.bytes("initClasses");
        byte[] vmName = Magic.bytes("vm/VM");
        int idx = 0;
        while (idx < imN)
        {
            long base = imAddrOf(imClsName[idx], imName[idx], imDesc[idx]);
            int[] words;
            if (bytesEqual(imName[idx], initName) && bytesEqual(imClsName[idx], vmName))
            {
                words = genInitClassesWords(base);
            }
            else
            {
                setClassContext(imCls[idx]);
                int body = findMethodBody(imName[idx], imDesc[idx]);
                MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
                words = compileInto(body, sym, base, idx == 0);
                patchImageRelocs(sym, words, base);
            }
            if (!fixpointEquals(words, base))
            {
                return false;
            }
            idx += 1;
        }
        return true;
    }

    // ----- data-region content materialise + compare (Types/itables/TIBs/strings/statics/unwind/blobs/classtable) -----

    /** Compare a 32-bit {@code val} against the image word at 0x80000-relative {@code word}; the word on
     *  mismatch, else -1. */
    private static int chkW(int word, long val)
    {
        if ((val & 0xFFFFFFFFL) != (Magic.load32(dAddr(word)) & 0xFFFFFFFFL))
        {
            return word;
        }
        return -1;
    }

    /** Compare a 64-bit {@code val} (two words) against the image; first mismatching word, or -1. */
    private static int chkLong(int word, long val)
    {
        int r = chkW(word, val);
        if (r >= 0)
        {
            return r;
        }
        return chkW(word + 1, val >>> 32);
    }

    /** Compare {@code b} packed (little-endian, 8-byte-aligned) against the image at {@code word}; -1 if equal. */
    private static int chkBytes(int word, byte[] b)
    {
        int total = ((b.length + 7) & ~7) / 4;
        int p = 0;
        while (p < total)
        {
            long packed = 0L;
            int q = 0;
            while (q < 4)
            {
                int bi = p * 4 + q;
                if (bi < b.length)
                {
                    packed |= ((long) (b[bi] & 0xFF)) << (q * 8);
                }
                q += 1;
            }
            int r = chkW(word + p, packed);
            if (r >= 0)
            {
                return r;
            }
            p += 1;
        }
        return -1;
    }

    private static byte[] blobClass(int b)
    {
        if (b == 0) { return Magic.bytes("vm/Guest"); }
        if (b == 1) { return Magic.bytes("vm/Greeter"); }
        if (b == 2) { return Magic.bytes("vm/Alpha"); }
        if (b == 3) { return Magic.bytes("vm/Beta"); }
        if (b == 4) { return Magic.bytes("vm/MyExc"); }
        return Magic.bytes("java/lang/Math");
    }

    /** The writer-stashed value of static {@code vm/VM.name}, or 0 for a runtime-init / $exception slot. */
    private static long staticValue(byte[] cls, byte[] nm)
    {
        if (!bytesEqual(cls, Magic.bytes("vm/VM")))
        {
            return 0L;
        }
        if (bytesEqual(nm, Magic.bytes("frameTable"))) { return dAddr(dFrameStart); }
        if (bytesEqual(nm, Magic.bytes("frameCount"))) { return drFrameCount; }
        if (bytesEqual(nm, Magic.bytes("handlerTable"))) { return dAddr(dHandlerStart); }
        if (bytesEqual(nm, Magic.bytes("handlerCount"))) { return drHandlerCount; }
        if (bytesEqual(nm, Magic.bytes("staticsStart"))) { return dAddr(dStaticsStart); }
        if (bytesEqual(nm, Magic.bytes("staticsEnd"))) { return dAddr(dStaticsEnd); }
        if (bytesEqual(nm, Magic.bytes("classDir"))) { return dAddr(dClassDirStart); }
        if (bytesEqual(nm, Magic.bytes("classCount"))) { return classCount; }
        if (bytesEqual(nm, Magic.bytes("heapAlloc"))) { return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("alloc"), Magic.bytes("(I)J")); }
        if (bytesEqual(nm, Magic.bytes("allocArray"))) { return imAddrOf(Magic.bytes("vm/Heap"), Magic.bytes("allocArray"), Magic.bytes("(II)J")); }
        if (bytesEqual(nm, Magic.bytes("gcCollect"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("gcCollect"), Magic.bytes("(J)V")); }
        if (bytesEqual(nm, Magic.bytes("instanceOfAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("instanceOf"), Magic.bytes("(JJ)I")); }
        if (bytesEqual(nm, Magic.bytes("checkCastAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("checkCast"), Magic.bytes("(JJ)J")); }
        if (bytesEqual(nm, Magic.bytes("unwindAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("unwind"), Magic.bytes("(JJJ)V")); }
        if (bytesEqual(nm, Magic.bytes("reportFaultAddr"))) { return imAddrOf(Magic.bytes("vm/VM"), Magic.bytes("reportFault"), Magic.bytes("()V")); }
        long blobV = blobStatic(nm);
        return blobV;
    }

    /** Blob address/length statics ({@code guestBytes}/{@code guestLen}/...), or 0 if {@code nm} is none. */
    private static long blobStatic(byte[] nm)
    {
        int b = 0;
        while (b < 6)
        {
            byte[] c = blobClass(b);
            if (bytesEqual(nm, blobAddrName(b)))
            {
                return dAddr(dBlobOff[b]);
            }
            if (bytesEqual(nm, blobLenName(b)))
            {
                return MetalClassModel.bytesOf(c).length;
            }
            b += 1;
        }
        return 0L;
    }

    private static byte[] blobAddrName(int b)
    {
        if (b == 0) { return Magic.bytes("guestBytes"); }
        if (b == 1) { return Magic.bytes("greeterBytes"); }
        if (b == 2) { return Magic.bytes("alphaBytes"); }
        if (b == 3) { return Magic.bytes("betaBytes"); }
        if (b == 4) { return Magic.bytes("myExcBytes"); }
        return Magic.bytes("mathBytes");
    }

    private static byte[] blobLenName(int b)
    {
        if (b == 0) { return Magic.bytes("guestLen"); }
        if (b == 1) { return Magic.bytes("greeterLen"); }
        if (b == 2) { return Magic.bytes("alphaLen"); }
        if (b == 3) { return Magic.bytes("betaLen"); }
        if (b == 4) { return Magic.bytes("myExcLen"); }
        return Magic.bytes("mathLen");
    }

    /** First 0x80000-relative word where the reproduced data regions differ from the image, or -1 if identical. */
    private static int firstDataMismatch()
    {
        int wordSlot = ObjectModel.WORD / 4;
        // ----- Types: { instanceSize, superType, itableDir } -----
        int i = 0;
        while (i < typeClassesN)
        {
            byte[] cls = typeClasses[i];
            int tw = dTypesStart + i * (ObjectModel.TYPE_SIZE / 4);
            int r = chkLong(tw + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET / 4,
                            ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(cls)));
            if (r >= 0) { return r; }
            byte[] sup = MetalClassModel.superName(cls);
            long superAddr = sup == null || MetalClassModel.isRoot(sup) ? 0L : typeImgAddr(sup);
            r = chkLong(tw + ObjectModel.TYPE_SUPER_OFFSET / 4, superAddr);
            if (r >= 0) { return r; }
            int j = findByteArr(tibSeenCls, tibSeenN, cls);
            long dir = j >= 0 && dItDirOff[j] >= 0 ? dAddr(dItDirOff[j]) : 0L;
            r = chkLong(tw + ObjectModel.TYPE_ITABLE_DIR_OFFSET / 4, dir);
            if (r >= 0) { return r; }
            i += 1;
        }
        // ----- itable directories + itables -----
        int t = 0;
        while (t < tibSeenN)
        {
            if (dItDirOff[t] >= 0)
            {
                int r = chkItable(t);
                if (r >= 0) { return r; }
            }
            t += 1;
        }
        // ----- TIBs: [Type][vtable code addresses] -----
        int jt = 0;
        while (jt < tibSeenN)
        {
            byte[] cls = tibSeenCls[jt];
            int tw = dTibOff[jt];
            int r = chkLong(tw + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT) / 4, typeImgAddr(cls));
            if (r >= 0) { return r; }
            int vs = MetalClassModel.vtableSize(cls);
            int slot = 0;
            while (slot < vs)
            {
                long a = imAddrOf(MetalClassModel.vtableSlotOwner(slot), MetalClassModel.vtableSlotName(slot),
                                  MetalClassModel.vtableSlotDesc(slot));
                r = chkLong(tw + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)) / 4, a);
                if (r >= 0) { return r; }
                slot += 1;
            }
            jt += 1;
        }
        // ----- interned string byte[] objects: [null TIB][status][length][bytes] -----
        int k = 0;
        while (k < drStrN)
        {
            byte[] sb = drStr[k];
            int sw = dStrOff[k];
            int r = chkLong(sw + ObjectModel.TIB_OFFSET / 4, 0L);
            if (r >= 0) { return r; }
            r = chkLong(sw + ObjectModel.STATUS_OFFSET / 4, 0L);
            if (r >= 0) { return r; }
            r = chkLong(sw + ObjectModel.ARRAY_LENGTH_OFFSET / 4, sb.length);
            if (r >= 0) { return r; }
            r = chkBytes(sw + ObjectModel.ARRAY_BASE_OFFSET / 4, sb);
            if (r >= 0) { return r; }
            k += 1;
        }
        // NOTE: the statics region is the program's mutable data segment -- the running image has
        // mutated it (Config.<clinit> wrote mark, counters incremented, freeHead updated, ...), so its
        // byte content is not comparable against a live system. Its layout and the immutable writer-
        // stashed values (staticsStart/frameTable/classDir/helper addrs/...) are validated by the 'H'
        // marker; staticValue() records how they were written for completeness.
        // ----- unwind frame table: { codeStart, codeEnd, frameSize } in method order -----
        int fw = dFrameStart;
        int m = 0;
        while (m < imN)
        {
            if (imFrameSize[m] > 0)
            {
                long base = imAddrOf(imClsName[m], imName[m], imDesc[m]);
                int r = chkLong(fw, base);
                if (r >= 0) { return r; }
                r = chkLong(fw + 2, base + imSize[m] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(fw + 4, imFrameSize[m]);
                if (r >= 0) { return r; }
                fw += 6;
            }
            m += 1;
        }
        // ----- unwind handler table: { start, end, handler, catchType } -----
        int hw = dHandlerStart;
        m = 0;
        while (m < imN)
        {
            long base = imAddrOf(imClsName[m], imName[m], imDesc[m]);
            int h = 0;
            while (h < imHNa[m])
            {
                int r = chkLong(hw, base + imHStartA[m][h] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(hw + 2, base + imHEndA[m][h] * 4L);
                if (r >= 0) { return r; }
                r = chkLong(hw + 4, base + imHandlerA[m][h] * 4L);
                if (r >= 0) { return r; }
                byte[] cc = imHCatchCls[m][h];
                r = chkLong(hw + 6, cc == null ? 0L : typeImgAddr(cc));
                if (r >= 0) { return r; }
                hw += 8;
                h += 1;
            }
            m += 1;
        }
        // ----- blobs: raw .class bytes -----
        int b = 0;
        while (b < 6)
        {
            int r = chkBytes(dBlobOff[b], MetalClassModel.bytesOf(blobClass(b)));
            if (r >= 0) { return r; }
            b += 1;
        }
        // ----- class table: directory {nameAddr,nameLen,bytesAddr,bytesLen} -----
        int cc2 = (int) classCount;
        int cur = dClassDirStart + cc2 * (4 * wordSlot);
        int ci = 0;
        while (ci < cc2)
        {
            long e = classDir + ci * 32L;                    // read the embedded directory entry
            int nameLen = (int) Magic.load64(e + 8L);
            int bytesLen = (int) Magic.load64(e + 24L);
            int nameW = cur;
            cur += align8W(nameLen);
            int bytesW = cur;
            cur += align8W(bytesLen);
            int de = dClassDirStart + ci * (4 * wordSlot);
            int r = chkLong(de, dAddr(nameW));
            if (r >= 0) { return r; }
            r = chkLong(de + 2, nameLen);
            if (r >= 0) { return r; }
            r = chkLong(de + 4, dAddr(bytesW));
            if (r >= 0) { return r; }
            r = chkLong(de + 6, bytesLen);
            if (r >= 0) { return r; }
            ci += 1;
        }
        return -1;
    }

    /** Compare {@code tibSeenCls[t]}'s itable directory + itables against the image; first bad word or -1. */
    private static int chkItable(int t)
    {
        byte[] c = tibSeenCls[t];
        // ordered implemented interfaces (usedInterfaces order)
        int impls = 0;
        int u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                impls += 1;
            }
            u += 1;
        }
        int dir = dItDirOff[t];
        int itbase = dir + (impls + 1) * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
        // directory entries { interfaceType, itable } + zeroed sentinel
        int e = 0;
        int off = itbase;
        u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                int entry = dir + e * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
                int r = chkLong(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET / 4, typeImgAddr(drUsedIf[u]));
                if (r >= 0) { return r; }
                r = chkLong(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET / 4, dAddr(off));
                if (r >= 0) { return r; }
                off += MetalClassModel.interfaceMethodCount(drUsedIf[u]) * (ObjectModel.WORD / 4);
                e += 1;
            }
            u += 1;
        }
        int sent = dir + impls * (ObjectModel.ITABLE_ENTRY_SIZE / 4);
        int r = chkLong(sent + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET / 4, 0L);
        if (r >= 0) { return r; }
        r = chkLong(sent + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET / 4, 0L);
        if (r >= 0) { return r; }
        // itables: each interface method -> the class's impl address
        off = itbase;
        u = 0;
        while (u < drUsedIfN)
        {
            if (MetalClassModel.implementsInterface(c, drUsedIf[u]))
            {
                byte[] iface = drUsedIf[u];
                int n = MetalClassModel.interfaceMethodCount(iface);
                int s = 0;
                while (s < n)
                {
                    byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, s);
                    byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, s);
                    int vslot = MetalClassModel.vtableSlot(c, mName, mDesc);
                    long a = imAddrOf(MetalClassModel.vtableSlotOwner(vslot), mName, mDesc);
                    r = chkLong(off + s * (ObjectModel.WORD / 4), a);
                    if (r >= 0) { return r; }
                    s += 1;
                }
                off += n * (ObjectModel.WORD / 4);
            }
            u += 1;
        }
        return -1;
    }

    /** Enqueue a compiled method's callees (calls) and, for each {@code new}, the instantiated
     *  class's vtable methods (so its TIB can be filled). Runs with {@code cB} = the method's class. */
    private static void discoverFrom(MetalWriterSymbols sym)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            enqueueMethod(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)),
                          utf8Copy(sym.callDescOff(c)));
            c += 1;
        }
        int t = 0;
        while (t < sym.tibCount())
        {
            byte[] tc = utf8Copy(sym.tibClassOff(t));
            int vs = MetalClassModel.vtableSize(tc);           // builds the vtable scratch for tc
            int slot = 0;
            while (slot < vs)
            {
                byte[] owner = MetalClassModel.vtableSlotOwner(slot);
                if (!MetalClassModel.isRoot(owner))
                {
                    enqueueMethod(owner, MetalClassModel.vtableSlotName(slot), MetalClassModel.vtableSlotDesc(slot));
                }
                slot += 1;
            }
            enqueueClinit(tc);
            t += 1;
        }
        int su = 0;
        while (su < sym.staticCount())                          // a used class's <clinit> (eager init)
        {
            enqueueClinit(utf8Copy(sym.staticClassOff(su)));
            su += 1;
        }
    }

    /** Enqueue {@code cls}'s {@code <clinit>} if it has one (closed-world eager static init). */
    private static void enqueueClinit(byte[] cls)
    {
        if (MetalClassModel.hasClinit(cls))
        {
            enqueueMethod(cls, Magic.bytes("<clinit>"), Magic.bytes("()V"));
        }
    }

    /** Register every placed method's frame + try/catch ranges into the jit unwind tables, so a
     *  throw in one metal-built method can unwind into another's catch (cross-method unwind). */
    private static void registerFramesAndHandlers(long codeBuf)
    {
        int m = 0;
        while (m < gmCount)
        {
            long base = codeBuf + gmWordOff[m] * 4L;
            long end = base + gmSize[m] * 4L;
            addJitFrame(base, end, gmFrameSize[m]);
            setClassContext(gmClsIdx[m]);                  // resolve catch-type cp in this method's class
            int h = 0;
            while (h < gmHN[m])
            {
                long ms = base + gmHStart[m][h] * 4L;
                long me = base + gmHEnd[m][h] * 4L;
                long hh = base + gmHandler[m][h] * 4L;
                long ct = typeAddrOfClassCp(gmHCatch[m][h]);
                addJitHandler(ms, me, hh, ct);
                h += 1;
            }
            m += 1;
        }
    }

    /** Type address for the catch-type Class cp entry {@code cp} in the current class context
     *  ({@code 0} = catch-all/finally, or an unresolved type — matches any exception). */
    private static long typeAddrOfClassCp(int cp)
    {
        if (cp == 0)
        {
            return 0L;
        }
        byte[] name = utf8Copy(ClassReader.classNameOff(cB, cOff, cp));
        int k = findTibClassBytes(name);
        if (k >= 0)
        {
            return nbTypeAddr[k];
        }
        int ki = findInterfaceBytes(name);
        if (ki >= 0)
        {
            return ifTypeAddr[ki];
        }
        return 0L;
    }

    /** Lay out interface Types, then class Types/TIBs (+ itable directories), across the closure. */
    private static void layoutClassRegionsG(long codeBuf)
    {
        nbClass = new byte[32][];
        nbTypeAddr = new long[32];
        nbTibAddr = new long[32];
        nbCount = 0;
        ifIface = new byte[32][];
        ifTypeAddr = new long[32];
        ifCount = 0;
        int m = 0;
        while (m < gmCount)                                // pass 1: interface Types
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int k = 0;
            while (k < sym.ifCount())
            {
                addInterfaceTypeG(utf8Copy(sym.ifClassOff(k)));
                k += 1;
            }
            m += 1;
        }
        m = 0;
        while (m < gmCount)                                // pass 2: class Types/TIBs + itable dirs
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int t = 0;
            while (t < sym.tibCount())
            {
                addClassRegionG(utf8Copy(sym.tibClassOff(t)), codeBuf);
                t += 1;
            }
            int y = 0;
            while (y < sym.typeCount())
            {
                addClassRegionG(utf8Copy(sym.typeClassOff(y)), codeBuf);
                y += 1;
            }
            m += 1;
        }
    }

    /** Build an interface's Type ({@code {0,0,0}}) once, if new. */
    private static void addInterfaceTypeG(byte[] name)
    {
        if (findInterfaceBytes(name) >= 0)
        {
            return;
        }
        ifIface[ifCount] = name;
        ifTypeAddr[ifCount] = Heap.alloc(ObjectModel.TYPE_SIZE);   // zeroed
        ifCount += 1;
    }

    /** Index of the laid-out interface with name {@code name}, or -1. */
    private static int findInterfaceBytes(byte[] name)
    {
        int j = 0;
        while (j < ifCount)
        {
            if (bytesEqual(ifIface[j], name))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Build {@code name}'s Type + TIB (vtable filled from placed methods across classes) once. */
    private static void addClassRegionG(byte[] name, long codeBuf)
    {
        if (findTibClassBytes(name) >= 0 || findInterfaceBytes(name) >= 0)
        {
            return;   // already laid out (or an interface, whose Type is built in pass 1)
        }
        long type = Heap.alloc(ObjectModel.TYPE_SIZE);
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET,
                      ObjectModel.scalarSize(MetalClassModel.instanceFieldCount(name)));
        // superType: 0 for a root super (chain ends), else the laid-out super's Type. Heap.alloc
        // does not zero the header region (where superType@8 lives), so this MUST be written — a
        // stale non-zero value sends instanceOf's super-chain walk off into garbage.
        byte[] sup = MetalClassModel.superName(name);
        int si = sup == null || MetalClassModel.isRoot(sup) ? -1 : findTibClassBytes(sup);
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, si >= 0 ? nbTypeAddr[si] : 0L);
        int vs = MetalClassModel.vtableSize(name);
        long tib = Heap.alloc(ObjectModel.tibSize(vs));
        Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.TIB_TYPE_SLOT), type);
        int slot = 0;
        while (slot < vs)
        {
            int j = findMethodG(MetalClassModel.vtableSlotOwner(slot), MetalClassModel.vtableSlotName(slot),
                                MetalClassModel.vtableSlotDesc(slot));
            if (j >= 0)
            {
                Magic.store64(tib + ObjectModel.tibSlotOffset(ObjectModel.tibVMethodSlot(slot)),
                              codeBuf + gmWordOff[j] * 4L);
            }
            slot += 1;
        }
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, buildItableDirG(name, codeBuf));
        nbClass[nbCount] = name;
        nbTypeAddr[nbCount] = type;
        nbTibAddr[nbCount] = tib;
        nbCount += 1;
    }

    /** Build {@code clsName}'s itable directory over the referenced interfaces it implements (0 if none). */
    private static long buildItableDirG(byte[] clsName, long codeBuf)
    {
        int impls = 0;
        int k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                impls += 1;
            }
            k += 1;
        }
        if (impls == 0)
        {
            return 0L;
        }
        long dir = Heap.alloc((impls + 1) * ObjectModel.ITABLE_ENTRY_SIZE);   // +1 zeroed sentinel
        int e = 0;
        k = 0;
        while (k < ifCount)
        {
            if (MetalClassModel.implementsInterface(clsName, ifIface[k]))
            {
                long entry = dir + e * ObjectModel.ITABLE_ENTRY_SIZE;
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET, ifTypeAddr[k]);
                Magic.store64(entry + ObjectModel.ITABLE_ENTRY_TABLE_OFFSET, buildItableG(clsName, ifIface[k], codeBuf));
                e += 1;
            }
            k += 1;
        }
        return dir;
    }

    /** Build {@code clsName}'s itable for {@code iface}: each interface method's slot → its impl (via the vtable). */
    private static long buildItableG(byte[] clsName, byte[] iface, long codeBuf)
    {
        int n = MetalClassModel.interfaceMethodCount(iface);
        long itab = Heap.alloc(n * ObjectModel.WORD);
        int slot = 0;
        while (slot < n)
        {
            byte[] mName = MetalClassModel.interfaceMethodNameAt(iface, slot);
            byte[] mDesc = MetalClassModel.interfaceMethodDescAt(iface, slot);
            int vslot = MetalClassModel.vtableSlot(clsName, mName, mDesc);   // the class's impl lives in its vtable
            if (vslot >= 0)
            {
                int j = findMethodG(MetalClassModel.vtableSlotOwner(vslot), mName, mDesc);
                if (j >= 0)
                {
                    Magic.store64(itab + slot * ObjectModel.WORD, codeBuf + gmWordOff[j] * 4L);
                }
            }
            slot += 1;
        }
        return itab;
    }

    /** Index of the laid-out class whose name equals {@code name}, or -1. */
    private static int findTibClassBytes(byte[] name)
    {
        int j = 0;
        while (j < nbCount)
        {
            if (bytesEqual(nbClass[j], name))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Point the {@code cB/cOff/cTag/cAfterCp} cursor at the cached class {@code ci}. */
    private static void setClassContext(int ci)
    {
        cB = lcBytes[ci];
        cOff = lcOff[ci];
        cTag = lcTag[ci];
        cAfterCp = lcAfterCp[ci];
    }

    /** Load + parse a class once, caching it; returns its cache index. */
    private static int loadClass(byte[] name)
    {
        int i = 0;
        while (i < lcCount)
        {
            if (bytesEqual(lcName[i], name))
            {
                return i;
            }
            i += 1;
        }
        byte[] b = MetalClassModel.bytesOf(name);
        int[] off = new int[ClassReader.cpCount(b)];
        int[] tag = new int[off.length];
        lcAfterCp[lcCount] = ClassReader.constantPool(b, off, tag);
        lcName[lcCount] = name;
        lcBytes[lcCount] = b;
        lcOff[lcCount] = off;
        lcTag[lcCount] = tag;
        return lcCount++;
    }

    /** Enqueue a method (class,name,desc) for the closure if not already present. */
    private static void enqueueMethod(byte[] clsName, byte[] name, byte[] desc)
    {
        if (findMethodG(clsName, name, desc) >= 0)
        {
            return;
        }
        gmClsName[gmCount] = clsName;
        gmClsIdx[gmCount] = loadClass(clsName);
        gmName[gmCount] = name;
        gmDesc[gmCount] = desc;
        gmCount += 1;
    }

    /** Index of the discovered method matching (class,name,desc), or -1. */
    private static int findMethodG(byte[] clsName, byte[] name, byte[] desc)
    {
        int j = 0;
        while (j < gmCount)
        {
            if (bytesEqual(gmClsName[j], clsName) && bytesEqual(gmName[j], name) && bytesEqual(gmDesc[j], desc))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Gather the distinct statics referenced across all discovered methods (by class+field content). */
    private static void collectStaticsG()
    {
        stClass = new byte[16][];
        stName = new byte[16][];
        stAddr = new long[16];
        stCount = 0;
        int m = 0;
        while (m < gmCount)
        {
            setClassContext(gmClsIdx[m]);
            MetalWriterSymbols sym = gmSym[m];
            int c = 0;
            while (c < sym.staticCount())
            {
                int classOff = sym.staticClassOff(c);
                int nameOff = sym.staticNameOff(c);
                if (findStatic(classOff, nameOff) < 0)
                {
                    stClass[stCount] = utf8Copy(classOff);
                    stName[stCount] = utf8Copy(nameOff);
                    stCount += 1;
                }
                c += 1;
            }
            m += 1;
        }
    }

    /** Patch every discovered method's cross-class calls, statics, and helpers, then write it out. */
    private static boolean patchCrossAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < gmCount)
        {
            setClassContext(gmClsIdx[m]);                  // reloc identities are offsets into this class
            MetalWriterSymbols sym = gmSym[m];
            int[] words = gmWords[m];
            int baseOff = gmWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findMethodG(utf8Copy(sym.callClassOff(c)), utf8Copy(sym.callNameOff(c)),
                                    utf8Copy(sym.callDescOff(c)));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(gmWordOff[j] - (baseOff + site));   // cross-class BL (same buffer)
                }
                c += 1;
            }
            int h = 0;
            while (h < sym.helperCount())
            {
                int site = sym.helperSiteWord(h);
                long siteAbs = buf + (baseOff + site) * 4L;
                long rel = (helperAddr(sym.helperId(h)) - siteAbs) / 4L;
                words[site] = A64Enc.bl((int) rel);
                h += 1;
            }
            int t = 0;
            while (t < sym.staticCount())
            {
                int k = findStatic(sym.staticClassOff(t), sym.staticNameOff(t));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.staticSiteWord(t), sym.staticReg(t), stAddr[k]);
                }
                t += 1;
            }
            int b = 0;
            while (b < sym.tibCount())
            {
                int k = findTibClassBytes(utf8Copy(sym.tibClassOff(b)));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.tibSiteWord(b), sym.tibReg(b), nbTibAddr[k]);
                }
                b += 1;
            }
            int y = 0;
            while (y < sym.typeCount())                     // instanceof/checkcast: class Type or interface Type
            {
                byte[] tn = utf8Copy(sym.typeClassOff(y));
                int k = findTibClassBytes(tn);
                if (k >= 0)
                {
                    patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), nbTypeAddr[k]);
                }
                else
                {
                    int ki = findInterfaceBytes(tn);
                    if (ki < 0)
                    {
                        ok = false;
                    }
                    else
                    {
                        patchAddrWords(words, sym.typeSiteWord(y), sym.typeReg(y), ifTypeAddr[ki]);
                    }
                }
                y += 1;
            }
            int st = 0;
            while (st < sym.strCount())
            {
                patchAddrWords(words, sym.strSiteWord(st), sym.strReg(st), internLiteral(sym.strUtf8Off(st)));
                st += 1;
            }
            int f = 0;
            while (f < sym.ifCount())
            {
                int k = findInterfaceBytes(utf8Copy(sym.ifClassOff(f)));
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.ifSiteWord(f), sym.ifReg(f), ifTypeAddr[k]);
                }
                f += 1;
            }
            int xc = 0;
            while (xc < sym.excCount())
            {
                patchAddrWords(words, sym.excSiteWord(xc), sym.excReg(xc), excSlot);
                xc += 1;
            }
            int pcx = 0;
            while (pcx < sym.pcCount())                        // athrow self-PC: relocate to its final address
            {
                long selfPc = buf + (baseOff + sym.pcTarget(pcx)) * 4L;
                patchAddrWords(words, sym.pcSiteWord(pcx), sym.pcReg(pcx), selfPc);
                pcx += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    // ----- M5.5c step 3b: metal layout engine (build + execute a call closure) -----
    // The class context + method table are static (like Loader's registries) so the helpers
    // stay low-arity — the baseline compiler allows only 7 operand-stack slots (OP_MAX).

    private static byte[] cB;       // current class bytes
    private static int[] cOff;      // ... its constant-pool offset table
    private static int[] cTag;      // ... and tags
    private static int cAfterCp;    // offset just past the constant pool

    private static byte[][] clName; // placed methods: name / descriptor identity bytes
    private static byte[][] clDesc;
    private static int[] clSize;    // ... A64 word count
    private static int[] clWordOff; // ... assigned word offset in the buffer
    private static int[][] clWords; // ... compiled words
    private static MetalWriterSymbols[] clSym;  // ... recorded relocations
    private static int clCount;

    private static int fDescOff;    // descriptor Utf8 offset of the last findMethodBody hit
    private static boolean fStatic; // ... and whether it is static (frame/ABI)

    /**
     * Build the call closure of {@code Uart.putc} into a {@link Heap} buffer — discover,
     * place, compile-at-base, patch the BL sites — then run it: the metal writer's layout
     * engine producing working code. The built {@code putc} prints {@code '~'}. Returns
     * whether every recorded call resolved to a placed method.
     */
    private static boolean selfBuildClosureAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("board/bcm2711/Uart"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("putc");
        clDesc[0] = Magic.bytes("(I)V");
        clCount = 1;

        // discover + compile (base 0; pure-call word counts are placement-independent).
        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            discoverCallees(sym);
            i += 1;
        }

        // place contiguously and allocate the buffer.
        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long buf = Heap.alloc(cur * 4);

        // patch each recorded call to its callee's base, then write the words to the buffer.
        boolean ok = patchAndWrite(buf);
        Heap.publishCode(buf, buf + cur * 4L);             // I-cache maintenance before executing built code

        long entry = buf + clWordOff[0] * 4L;
        long unused = Magic.call2(entry, 0x7EL, 0L);       // run the built putc('~'); ignore the void return
        return ok;
    }

    /** Enqueue any callee of {@code sym} not already in the method table. */
    private static void discoverCallees(MetalWriterSymbols sym)
    {
        int c = 0;
        while (c < sym.callCount())
        {
            int nameOff = sym.callNameOff(c);
            int descOff = sym.callDescOff(c);
            if (findPlaced(nameOff, descOff) < 0)
            {
                clName[clCount] = utf8Copy(nameOff);
                clDesc[clCount] = utf8Copy(descOff);
                clCount += 1;
            }
            c += 1;
        }
    }

    /** Patch every method's call sites to their callees' bases and write the words to {@code buf}. */
    private static boolean patchAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));   // relative BL
                }
                c += 1;
            }
            int w = 0;
            while (w < words.length)
            {
                long addr = buf + (baseOff + w) * 4L;
                Magic.store32(addr, words[w]);
                w += 1;
            }
            m += 1;
        }
        return ok;
    }

    /** The Code-attribute body offset of {@code name+desc} in {@link #cB}; sets {@link #fDescOff}/{@link #fStatic}. */
    private static int findMethodBody(byte[] name, byte[] desc)
    {
        byte[] codeAttr = Magic.bytes("Code");
        int p = ClassReader.methodsStart(cB, cAfterCp);
        int n = ClassReader.u2(cB, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            boolean isStatic = (ClassReader.u2(cB, p) & 0x0008) != 0;
            int nameOff = cOff[ClassReader.u2(cB, p + 2)];
            int descOff = cOff[ClassReader.u2(cB, p + 4)];
            int attrs = p + 6;
            if (utf8Eq(cB, nameOff, name) && utf8Eq(cB, descOff, desc))
            {
                fDescOff = descOff;
                fStatic = isStatic;
                return findCodeBody(attrs, codeAttr);
            }
            p = ClassReader.skipAttributes(cB, attrs);
            i += 1;
        }
        return -1;
    }

    /** The Code-attribute body offset among the attribute table at {@code attrs}, or -1. */
    private static int findCodeBody(int attrs, byte[] codeAttr)
    {
        int ac = ClassReader.u2(cB, attrs);
        int q = attrs + 2;
        int a = 0;
        while (a < ac)
        {
            int anOff = cOff[ClassReader.u2(cB, q)];
            int alen = ClassReader.u4(cB, q + 2);
            int bodyOff = q + 6;
            if (utf8Eq(cB, anOff, codeAttr))
            {
                return bodyOff;
            }
            q = bodyOff + alen;
            a += 1;
        }
        return -1;
    }

    /** Compile the method whose Code-attribute body is at {@code body} into A64 words at {@code base}. */
    private static int[] compileInto(int body, MetalWriterSymbols sym, long base)
    {
        return compileInto(body, sym, base, false);
    }

    private static int[] compileInto(int body, MetalWriterSymbols sym, long base, boolean isEntry)
    {
        int maxLocals = ClassReader.u2(cB, body + 2);      // after max_stack(2)
        int codeLen = ClassReader.u4(cB, body + 4);
        int codeStart = body + 8;
        byte[] code = new byte[codeLen];
        int k = 0;
        while (k < codeLen)
        {
            code[k] = (byte) ClassReader.u1(cB, codeStart + k);
            k += 1;
        }
        Baseline bl = new Baseline(cB, cOff, cTag, sym);
        setExceptions(bl, codeStart + codeLen);            // exception_table follows the bytecode
        int[] words = bl.compileBody(code, fDescOff, fStatic, maxLocals, base, isEntry);
        // Capture the frame size + machine handler ranges for cross-method unwind registration.
        fFrameSize = bl.frameSize();
        fHN = bl.handlerCount();
        int h = 0;
        while (h < fHN)
        {
            fHStart[h] = bl.handlerStartWord(h);
            fHEnd[h] = bl.handlerEndWord(h);
            fHandler[h] = bl.handlerWord(h);               // fHCatch[h] set by setExceptions
            h += 1;
        }
        return words;
    }

    private static int fFrameSize;                         // last-compiled method's frame + handlers
    private static int fHN;
    private static final int[] fHStart = new int[16];
    private static final int[] fHEnd = new int[16];
    private static final int[] fHandler = new int[16];
    private static final int[] fHCatch = new int[16];      // catch-type Class cp index per handler

    /** Read the exception_table at {@code ex} and install it on {@code bl}. */
    private static void setExceptions(Baseline bl, int ex)
    {
        int en = ClassReader.u2(cB, ex);
        int[] es = new int[en];
        int[] ee = new int[en];
        int[] eh = new int[en];
        int[] ec = new int[en];
        int j = 0;
        while (j < en)
        {
            int e = ex + 2 + j * 8;
            es[j] = ClassReader.u2(cB, e);
            ee[j] = ClassReader.u2(cB, e + 2);
            eh[j] = ClassReader.u2(cB, e + 4);
            ec[j] = ClassReader.u2(cB, e + 6);
            fHCatch[j] = ec[j];
            j += 1;
        }
        bl.setExceptionTable(es, ee, eh, ec, en);
    }

    /** Index of the placed method whose (name,desc) equal the Utf8 at {@code nameOff/descOff} in {@link #cB}, or -1. */
    private static int findPlaced(int nameOff, int descOff)
    {
        int j = 0;
        while (j < clCount)
        {
            if (utf8Eq(cB, nameOff, clName[j]) && utf8Eq(cB, descOff, clDesc[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Copy the Utf8 entry at {@code cB[off]} onto a fresh heap byte array. */
    private static byte[] utf8Copy(int off)
    {
        int len = ClassReader.u2(cB, off);
        byte[] out = new byte[len];
        int j = 0;
        while (j < len)
        {
            out[j] = (byte) ClassReader.u1(cB, off + 2 + j);
            j += 1;
        }
        return out;
    }

    // ----- M5.5c step 3b.2: static-field relocations + a statics region -----

    private static byte[][] stClass; // distinct statics: owner class-name / field-name identity
    private static byte[][] stName;
    private static long[] stAddr;    // ... its assigned 8-byte slot address
    private static int stCount;

    /**
     * Build {@code Counter.bump}/{@code get} into a Heap buffer, lay out a slot for the
     * shared static {@code count}, patch their getstatic/putstatic address loads to it, then
     * run {@code bump()} three times and {@code get()} — which must return 3.
     */
    private static boolean selfBuildStaticsAndRun()
    {
        cB = MetalClassModel.bytesOf(Magic.bytes("vm/Counter"));
        cOff = new int[ClassReader.cpCount(cB)];
        cTag = new int[cOff.length];
        cAfterCp = ClassReader.constantPool(cB, cOff, cTag);

        clName = new byte[8][];
        clDesc = new byte[8][];
        clSize = new int[8];
        clWordOff = new int[8];
        clWords = new int[8][];
        clSym = new MetalWriterSymbols[8];
        clName[0] = Magic.bytes("bump");
        clDesc[0] = Magic.bytes("()V");
        clName[1] = Magic.bytes("get");
        clDesc[1] = Magic.bytes("()I");
        clCount = 2;

        // compile each at base 0 (no calls to discover here).
        int i = 0;
        while (i < clCount)
        {
            int body = findMethodBody(clName[i], clDesc[i]);
            if (body < 0)
            {
                return false;
            }
            MetalWriterSymbols sym = new MetalWriterSymbols(cB, cOff);
            int[] words = compileInto(body, sym, 0L);
            clSym[i] = sym;
            clWords[i] = words;
            clSize[i] = words.length;
            i += 1;
        }

        // place methods contiguously.
        int cur = 0;
        int p = 0;
        while (p < clCount)
        {
            clWordOff[p] = cur;
            cur += clSize[p];
            p += 1;
        }
        long codeBuf = Heap.alloc(cur * 4);

        // collect distinct statics and give each a zeroed 8-byte slot.
        collectStatics();
        long staticsBuf = Heap.alloc(stCount * 8);
        int s = 0;
        while (s < stCount)
        {
            long slot = staticsBuf + s * 8L;
            Magic.store64(slot, 0L);                        // Heap.alloc doesn't zero; count starts 0
            stAddr[s] = slot;
            s += 1;
        }

        boolean ok = patchStaticsAndWrite(codeBuf);
        Heap.publishCode(codeBuf, codeBuf + cur * 4L);     // I-cache maintenance before executing built code

        // execute: three bumps then a read.
        long bumpEntry = codeBuf + clWordOff[0] * 4L;
        long getEntry = codeBuf + clWordOff[1] * 4L;
        long u = Magic.call0(bumpEntry);
        u = Magic.call0(bumpEntry);
        u = Magic.call0(bumpEntry);
        int got = (int) Magic.call0(getEntry);
        return ok && got == 3;
    }

    /** Gather the distinct statics referenced across all placed methods (by class+field content). */
    private static void collectStatics()
    {
        stClass = new byte[16][];
        stName = new byte[16][];
        stAddr = new long[16];
        stCount = 0;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int c = 0;
            while (c < sym.staticCount())
            {
                int classOff = sym.staticClassOff(c);
                int nameOff = sym.staticNameOff(c);
                if (findStatic(classOff, nameOff) < 0)
                {
                    stClass[stCount] = utf8Copy(classOff);
                    stName[stCount] = utf8Copy(nameOff);
                    stCount += 1;
                }
                c += 1;
            }
            m += 1;
        }
    }

    /** Index of the static whose (class,field) equal the Utf8 at {@code classOff/nameOff} in {@code cB}, or -1. */
    private static int findStatic(int classOff, int nameOff)
    {
        int j = 0;
        while (j < stCount)
        {
            if (utf8Eq(cB, classOff, stClass[j]) && utf8Eq(cB, nameOff, stName[j]))
            {
                return j;
            }
            j += 1;
        }
        return -1;
    }

    /** Patch every method's calls + static address loads, then write the words to {@code buf}. */
    private static boolean patchStaticsAndWrite(long buf)
    {
        boolean ok = true;
        int m = 0;
        while (m < clCount)
        {
            MetalWriterSymbols sym = clSym[m];
            int[] words = clWords[m];
            int baseOff = clWordOff[m];
            int c = 0;
            while (c < sym.callCount())
            {
                int j = findPlaced(sym.callNameOff(c), sym.callDescOff(c));
                if (j < 0)
                {
                    ok = false;
                }
                else
                {
                    int site = sym.callSiteWord(c);
                    words[site] = A64Enc.bl(clWordOff[j] - (baseOff + site));
                }
                c += 1;
            }
            int t = 0;
            while (t < sym.staticCount())
            {
                int classOff = sym.staticClassOff(t);
                int nameOff = sym.staticNameOff(t);
                int k = findStatic(classOff, nameOff);
                if (k < 0)
                {
                    ok = false;
                }
                else
                {
                    patchAddrWords(words, sym.staticSiteWord(t), sym.staticReg(t), stAddr[k]);
                }
                t += 1;
            }
            writeWords(buf, baseOff, words);
            m += 1;
        }
        return ok;
    }

    /** Fill a 2-word address-load placeholder at {@code site} with MOVZ/MOVK of {@code addr} (as CodeBuffer.patchAddr). */
    private static void patchAddrWords(int[] words, int site, int reg, long addr)
    {
        words[site] = A64Enc.movz(reg, (int) (addr & 0xFFFFL), 0);
        words[site + 1] = A64Enc.movk(reg, (int) ((addr >>> 16) & 0xFFFFL), 1);
    }

    /** Store a method's words into {@code buf} at word offset {@code baseOff}. */
    private static void writeWords(long buf, int baseOff, int[] words)
    {
        int w = 0;
        while (w < words.length)
        {
            long addr = buf + (baseOff + w) * 4L;
            Magic.store32(addr, words[w]);
            w += 1;
        }
    }

    /**
     * Drive the shared {@link Baseline} core over {@code Uart.write([B)V} with a relocating
     * {@link MetalWriterSymbols}, exactly as {@code Loader} drives the JIT, and check it
     * produced a placeholder {@code bl} plus a recorded call to {@code putc} — the metal
     * writer's compile-into-a-buffer capability, before layout wires the sites up.
     */
    private static boolean relocatingCompileReady()
    {
        byte[] b = MetalClassModel.bytesOf(Magic.bytes("board/bcm2711/Uart"));
        int[] off = new int[ClassReader.cpCount(b)];
        int[] tag = new int[off.length];
        int afterCp = ClassReader.constantPool(b, off, tag);
        byte[] wname = Magic.bytes("write");
        byte[] wdesc = Magic.bytes("([B)V");
        byte[] code = Magic.bytes("Code");
        int p = ClassReader.methodsStart(b, afterCp);
        int n = ClassReader.u2(b, p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            boolean isStatic = (ClassReader.u2(b, p) & 0x0008) != 0;
            int nameOff = off[ClassReader.u2(b, p + 2)];
            int descOff = off[ClassReader.u2(b, p + 4)];
            int attrs = p + 6;
            if (utf8Eq(b, nameOff, wname) && utf8Eq(b, descOff, wdesc))
            {
                int ac = ClassReader.u2(b, attrs);
                int q = attrs + 2;
                int a = 0;
                while (a < ac)
                {
                    int anOff = off[ClassReader.u2(b, q)];
                    int alen = ClassReader.u4(b, q + 2);
                    int body = q + 6;
                    if (utf8Eq(b, anOff, code))
                    {
                        return compileWriteAndCheck(b, off, tag, body, descOff, isStatic);
                    }
                    q = body + alen;
                    a += 1;
                }
            }
            p = ClassReader.skipAttributes(b, attrs);
            i += 1;
        }
        return false;
    }

    /** Compile the method whose Code-attribute body starts at {@code body}, then assert the reloc. */
    private static boolean compileWriteAndCheck(byte[] b, int[] off, int[] tag, int body,
                                                int descOff, boolean isStatic)
    {
        int maxLocals = ClassReader.u2(b, body + 2);       // after max_stack(2)
        int codeLen = ClassReader.u4(b, body + 4);
        int codeStart = body + 8;
        byte[] code = new byte[codeLen];
        int k = 0;
        while (k < codeLen)
        {
            code[k] = (byte) ClassReader.u1(b, codeStart + k);
            k += 1;
        }
        int ex = codeStart + codeLen;                      // exception_table follows the bytecode
        int en = ClassReader.u2(b, ex);
        int[] es = new int[en];
        int[] ee = new int[en];
        int[] eh = new int[en];
        int[] ec = new int[en];
        int j = 0;
        while (j < en)
        {
            int e = ex + 2 + j * 8;
            es[j] = ClassReader.u2(b, e);
            ee[j] = ClassReader.u2(b, e + 2);
            eh[j] = ClassReader.u2(b, e + 4);
            ec[j] = ClassReader.u2(b, e + 6);
            j += 1;
        }
        MetalWriterSymbols sym = new MetalWriterSymbols(b, off);
        Baseline bl = new Baseline(b, off, tag, sym);
        bl.setExceptionTable(es, ee, eh, ec, en);
        int[] words = bl.compileBody(code, descOff, isStatic, maxLocals, 0x80000L, false);
        return !sym.failed()
               && sym.callCount() == 1                     // the loop's single putc call
               && words[sym.callSiteWord(0)] == A64Enc.bl(0)  // placeholder in place, site in range
               && sym.callNameIs(0, Magic.bytes("putc"));   // callee identity resolved from the cp
    }

    /** Whether the Utf8 entry at {@code b[off]} equals the plain bytes {@code want}. */
    private static boolean utf8Eq(byte[] b, int off, byte[] want)
    {
        int len = ClassReader.u2(b, off);
        if (len != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < len)
        {
            if (ClassReader.u1(b, off + 2 + j) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** The metal class model's superclass-chain walks agree with the known hierarchy. */
    private static boolean chainWalksReady()
    {
        byte[] dog = Magic.bytes("vm/Dog");
        byte[] animal = Magic.bytes("vm/Animal");
        byte[] cell = Magic.bytes("vm/Cell");
        byte[] robot = Magic.bytes("vm/Robot");
        byte[] speaker = Magic.bytes("vm/Speaker");
        byte[] sound = Magic.bytes("sound");
        byte[] speak = Magic.bytes("speak");
        byte[] get = Magic.bytes("get");
        byte[] inc = Magic.bytes("inc");
        byte[] retI = Magic.bytes("()I");
        byte[] retV = Magic.bytes("()V");
        return MetalClassModel.vtableSize(dog) == 1                          // Dog overrides, no new slot
               && MetalClassModel.vtableSize(animal) == 1
               && MetalClassModel.vtableSize(cell) == 2                      // get, inc
               && MetalClassModel.vtableSlot(dog, sound, retI) == 0
               && MetalClassModel.vtableSlot(animal, sound, retI) == 0       // override shares the slot
               && MetalClassModel.vtableOwnerIs(dog, 0, dog)                 // owner becomes Dog
               && MetalClassModel.vtableOwnerIs(animal, 0, animal)
               && MetalClassModel.vtableSlot(cell, get, retI) >= 0
               && MetalClassModel.vtableSlot(cell, inc, retV) >= 0
               && MetalClassModel.interfaceMethodCount(speaker) == 1
               && MetalClassModel.interfaceMethodSlot(speaker, speak, retI) == 0
               && MetalClassModel.implementsInterface(robot, speaker)
               && !MetalClassModel.implementsInterface(dog, speaker)
               && MetalClassModel.findImplIs(robot, speak, retI, robot)
               && MetalClassModel.findImplIs(dog, sound, retI, dog);
    }

    /** The metal class model's leaf queries agree with the known shapes of embedded classes. */
    private static boolean classModelReady()
    {
        return MetalClassModel.superIs(Magic.bytes("vm/Dog"), Magic.bytes("vm/Animal"))
               && MetalClassModel.instanceFieldCount(Magic.bytes("vm/Cell")) == 1
               && MetalClassModel.hasClinit(Magic.bytes("vm/Config"))
               && !MetalClassModel.hasClinit(Magic.bytes("vm/Cell"))
               && !MetalClassModel.isRoot(Magic.bytes("vm/Dog"))
               && MetalClassModel.isRoot(Magic.bytes("java/lang/Object"));
    }

    /**
     * Every class in the embedded table looks up by its own name to its own bytes, and
     * those bytes start with the classfile magic {@code 0xCAFEBABE}. Proves the metal
     * self-build can resolve its input classes by name from the image (M5.5c step 2).
     */
    private static boolean classTableReady()
    {
        if (classCount == 0L)
        {
            return false;                                  // no table embedded
        }
        long i = 0L;
        while (i < classCount)
        {
            long e = classDir + i * 32L;                   // 4 longs per directory entry
            long nameAddr = Magic.load64(e);
            long nameLen = Magic.load64(e + 8L);
            long bytesAddr = Magic.load64(e + 16L);
            if (findClass(nameAddr, nameLen) != bytesAddr)
            {
                return false;                              // name did not resolve to its own bytes
            }
            if (Magic.load8(bytesAddr) != 0xCA || Magic.load8(bytesAddr + 1L) != 0xFE
                    || Magic.load8(bytesAddr + 2L) != 0xBA || Magic.load8(bytesAddr + 3L) != 0xBE)
            {
                return false;                              // corrupt class bytes
            }
            i = i + 1L;
        }
        return true;
    }

    /** Scan the class table for the name at [nameAddr,nameLen); return its class bytes address, or 0. */
    private static long findClass(long nameAddr, long nameLen)
    {
        long i = 0L;
        while (i < classCount)
        {
            long e = classDir + i * 32L;
            if (Magic.load64(e + 8L) == nameLen && bytesEqual(Magic.load64(e), nameAddr, nameLen))
            {
                return Magic.load64(e + 16L);
            }
            i = i + 1L;
        }
        return 0L;
    }

    /** Whether {@code len} bytes at {@code a} equal those at {@code b}. */
    private static boolean bytesEqual(long a, long b, long len)
    {
        long i = 0L;
        while (i < len)
        {
            if (Magic.load8(a + i) != Magic.load8(b + i))
            {
                return false;
            }
            i = i + 1L;
        }
        return true;
    }

    /** True if frameSizeAt resolves a real JIT'd frame but not a PC outside it. */
    private static boolean jitUnwindReady()
    {
        if (jitFrameCount == 0L)
        {
            return false;                                  // no framed JIT'd method ran
        }
        long e = jitFrameTable;                            // first registered entry
        long start = Magic.load64(e);
        long end = Magic.load64(e + 8L);
        long size = Magic.load64(e + 16L);
        return frameSizeAt(start) == size                  // inside -> its frame size
               && frameSizeAt(end) != size;                // just past the end -> not this frame
    }

    /** Allocate objects that become unreachable, giving the collector something to reclaim. */
    private static void gcGarbage()
    {
        int i = 0;
        while (i < 8)
        {
            Cell junk = new Cell(i);                       // dead as soon as the next iteration overwrites it
            i = i + 1;
        }
    }

    private static void thrower()
    {
        throw new MyExc();
    }

    private static void catcher()
    {
        try
        {
            thrower();                                     // throws; unwinds into this frame
        }
        catch (MyExc e)
        {
            Uart.putc(0x55);                               // 'U' — caught after unwinding
        }
    }
}
