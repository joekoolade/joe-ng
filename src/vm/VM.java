package vm;

import asm.A64Enc;
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
        initClasses();                     // run static initializers (writer-generated body)
        run();

        while (true)
        {
            Magic.wfe();
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
        long i = 0L;
        while (i < handlerCount)
        {
            long e = handlerTable + i * 32L;
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
        Magic.dsb();                                       // publish the buffer (caches off), as Loader does
        Magic.isb();

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
        return bl.compileBody(code, fDescOff, fStatic, maxLocals, base, false);
    }

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
        Magic.dsb();
        Magic.isb();

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
