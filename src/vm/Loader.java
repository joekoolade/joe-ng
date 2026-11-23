/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-07-19
 */
package vm;

import asm.A64Enc;
import board.Uart;
import classfile.ClassReader;
import compiler.Baseline;
import compiler.Intrinsics;
import compiler.Symbols;
import objectmodel.ObjectModel;
import zip.ZipDir;
import magic.Address;
import magic.CodeArray;
import magic.CodeAddress;
import magic.DataReference;
import magic.AddressArray;
import magic.DataReferenceArray;
import magic.Magic;
import magic.ObjectReference;
import magic.ObjectReferenceArray;

import static vm.ClassBytes.insnLen;
import static vm.ClassBytes.opLen;
import static vm.ClassBytes.u1;
import static vm.ClassBytes.u2;
import static vm.ClassBytes.u4;

/**
 * A minimal classfile loader that runs <em>on the metal</em> (compiled into the
 * image by our own baseline compiler): it parses raw {@code .class} bytes it has
 * never seen, compiles a method's bytecode to A64 into a heap buffer, publishes
 * it, and executes it — M4, the metacircular goal (PLAN.md §4).
 *
 * <p>Deliberately tiny and JDK-free (no collections/strings — just primitive
 * arrays and {@code Magic} byte access), so the baseline compiler can compile it.
 * Shared parse state lives in statics because the baseline compiler keeps locals
 * in registers (≤10 slots per method). It parses just enough of JVMS §4 to find a
 * static method's Code and compiles only the bytecodes a {@code return &lt;const&gt;}
 * method uses; full parser/compiler self-hosting (M5) is a much larger effort.
 */
public final class Loader
{
    private Loader() {}

    static Address gp;      // parse cursor
    // Per-blob constant-pool PARSE CACHE. parseConstPool used to copy the whole blob (toBytes) + allocate
    // the cp/cpTag/litObj arrays EVERY call; RTA (resolveVirtuals) re-parses each instantiated class's superclass
    // chain once per fixpoint iteration -> thousands of parses -> ~190 MiB of garbage byte[]/int[] copies that
    // fill the demand-load arena (blocker (e)). Cache the parse keyed by blob base so each blob is copied+parsed
    // ONCE; later calls reuse it. Each entry is one ClassFileView (reified from seven parallel arrays); pcTab is a
    // scanned loader static, so the views -- and the byte[]/int[] they hold -- survive GC. pcBase stays a separate
    // flat key array because the lookup is a hot linear scan, and keying it on pcTab[ci].base would add a load
    // per step.
    private static final int MAXPARSECACHE = 2048;
    static AddressArray pcBase; // cached blob base address, the scan key (0 = empty slot)
    static ClassFileView[] pcTab; // the cached view for pcBase[i]
    static int pcN;
    /**
     * THE CURRENT CLASS: the parsed blob the loader is working on, set only by {@code parseConstPool} (through
     * {@link #useView}); null before the first parse of a boot. Its fields replaced seven separate statics that
     * copied it ({@code gbase}, {@code gcp}, {@code gbytes}, {@code gcpTag}, {@code gcpCount}, {@code gAfterCp},
     * {@code litObjByCp}; retired in phase 2, increment P2-2), so switching the current class is now ONE store and
     * a reader can never see a mix of two blobs' fields. Like those statics it is guarded by the loader lock.
     */
    static ClassFileView gView;
    private static long gnameP, gdescP;   // packed name/descriptor being searched for
    private static int gnameLen;
    private static int gdescLen;
    /**
     * The field layout of the class being built or compiled (static and instance field tables, the statics block,
     * an absent supertype): one object made by {@link #parseFields}, which replaced nine statics in increment
     * P2-4b. Null until the first {@code parseFields} of a boot.
     */
    static ClassBuild gBuild;
    static Address gBsmOff;      // address of the BootstrapMethods attr body (num_bootstrap_methods), or 0



    // Blobs handed to the loader, plus the dependencies between them, so load order
    // is derived rather than hand-maintained. A class must be loaded after every
    // class it names — its superclass and interfaces (needed for field layout,
    // vtable flattening and itable indices) but also anything it instantiates,
    // calls or type-tests (needed by the class/method/field registries).
    static final int MAXBLOB = 4096;
    static AddressArray pdBase;        // blob address
    static int[] pdLen;          // blob length
    static int[] pdNameOff;      // its own this_class name Utf8 offset
    private static int[] pdDone;         // 1 once phase-A structure is loaded
    static int[] pdDoneB;        // 1 once phase-B bodies are compiled + TIB filled
    private static boolean[] pdNeedsString;   // blob materializes a String (CONSTANT_String or invokedynamic-concat)
    private static int stringPdIndex;    // pd index of java/lang/String, or -1 (set by probeAll)
    // Inheritance edges (super + direct interfaces) recorded by probeAll -- an ACYCLIC graph (Java forbids cyclic
    // inheritance), so phase A can always order super/interface-first with no stall. (This is distinct from the
    // full CONSTANT_Class dep list, which IS cyclic and is what made single-phase linking load-order-fragile.)
    static int[] pdSuperOff;     // super name Utf8 offset (in pdBase[i]), or 0 if none (Object/interface)
    static int[] pdIfOff;        // pdIfOff[i*MAX_DIRECT_IF+k] = direct-interface name Utf8 offset
    static int[] pdIfN;          // number of direct interfaces recorded for blob i
    static int pdCount;
    // Blobs [0, pdProbedTo) have been through probeAll: their name/super/interfaces/needsString are set and
    // their CONSTANT_Class deps are already on the dep list. The blob table is append-only and deduped by
    // ADDRESS, so those answers are fixed by the classfile bytes and re-deriving them can only reproduce
    // them. Reset in resetLoader WITH dpCount and stringPdIndex -- the three are one piece of state.
    private static int pdProbedTo;
    private static int pbProbed;         // blobs probed this batch (instrument: separates "the watermark
                                         //   fires" from "it fires and the rest is genuinely new work")
    private static final int MAXDEP = 49152;
    private static int[] dpOwner;        // index into pd* of the blob that has this dependency
    static int[] dpOff;          // dependency's name Utf8 offset (in dpBase[d])
    private static final int MAXINDYNAME = 16384;  // scratch bytes for interned invokedynamic interface names
    static DataReference indyNameBuf;     // [u2 len][name] runs; a Loader static, so the statics scan roots it
    private static int indyNameUsed;
    static AddressArray dpBase;        // blob the name lives in: usually pdBase[dpOwner], but an
                                         //   invokedynamic's functional interface is named only inside a
                                         //   DESCRIPTOR, so its name is copied into indyNameBuf instead
    static int dpCount;


    /**
     * After a code sweep, every phase-A cell must still point into an ALLOCATED code buffer. A cell whose
     * target has been freed is the exact shape of the fault: the dispatcher branches through the cell into
     * a buffer the collector zeroed, and executes zeros. Checking here names the entry while the tables are
     * still intact, instead of leaving a trap in an unnamed buffer minutes later.
     */
    public static void verifyCells()
    {
        int k = 0;
        while (k < DynamicLinker.dlN)
        {
            if (DynamicLinker.dlTab[k] != null && !DynamicLinker.dlTab[k].cell.isZero())
            {
                long target = DynamicLinker.dlTab[k].cell.toAddress().loadLong();
                if (target >= Heap.CODE_BASE && target < Heap.CODE_LIMIT
                        && Heap.codeBlockFreeAt(Address.fromLong(target)) == 1)
                {
                    Uart.write(Magic.bytes("\nCELL -> FREED CODE k="));
                    VM.printDec(k);
                    Uart.write(Magic.bytes(" target="));
                    VM.printHex(target);
                    Uart.write(Magic.bytes(" cell="));
                    VM.printHex(DynamicLinker.dlTab[k].cell.toLong());
                    Uart.write(Magic.bytes("\n  method: "));
                    StackTrace.printFrameAt(Address.fromLong(target));
                    Uart.putc(0x0A);
                    while (true) { Magic.wfe(); }
                }
            }
            k += 1;
        }
    }

    static void verifyDlTab()
    {
        int k = 0;
        while (k < DynamicLinker.dlN)
        {
            if (DynamicLinker.dlTab[k] == null || DynamicLinker.dlTab[k].blob.isZero())
            {
                Uart.write(Magic.bytes("\nDL WENT ZERO at k="));
                VM.printDec(k);
                Uart.write(Magic.bytes(" of "));
                VM.printDec(DynamicLinker.dlN);
                Uart.write(Magic.bytes(" null="));
                VM.printDec(DynamicLinker.dlTab[k] == null ? 1 : 0);
                Uart.write(Magic.bytes(" while compiling "));
                printCurrentClass();
                Uart.putc(0x0A);
                while (true) { Magic.wfe(); }
            }
            k += 1;
        }
    }

    private static void badRead(long addr, long lr)
    {
        Uart.write(Magic.bytes("\nSTALE REGISTRY REF (blob "));
        VM.printHex(addr);
        Uart.write(Magic.bytes("\n  from "));
        StackTrace.printFrameAt(Address.fromLong(lr - 4L));
        Uart.write(Magic.bytes("\n  while compiling "));
        printCurrentClass();
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }


    static void seek(long nameP, int nameLen, long descP, int descLen)
    {
        gnameP = nameP;
        gnameLen = nameLen;
        gdescP = descP;
        gdescLen = descLen;
    }


    /**
     * Initialization barrier: run class {@code reg}'s pending {@code <clinit>} now, if it has one. Called
     * from {@link LazyCompiler#lazyCompile} BEFORE the compile context is restored — the initializer's own calls can
     * re-enter lazyCompile, and each nested compile clobbers the {@code g*} context, so it must not run
     * inside ours.
     */
    /**
     * Initialize the classes a {@code <clinit>} names, before that {@code <clinit>} runs.
     *
     * <p>The dependency set is the PRECISE one {@code scanClinitDeps} already collected from the initializer's
     * bytecode (getstatic/putstatic/invokestatic owners, {@code new}/{@code anewarray} targets, Class
     * literals) -- not the whole constant pool, so this initializes what is actually used and nothing more.
     */
    /**
     * ABSOLUTE address of the class name of the {@code *ref} at {@code refCp} -- a {@code {u2 len}{bytes}}
     * run inside the blob. Absolute because a blob never moves, so it is the same value in both compiler
     * passes; an offset would need a base the compile can re-point underneath it.
     */
    static Address refClassNameAddr(int refCp)
    {
        if (gView == null)
        {
            return Address.zero();
        }
        return gView.base.plus((long) refClassNameOff(refCp));
    }

    /** {@link #refClassNameAddr} for a {@code CONSTANT_Class} -- the {@code new} counterpart. */
    static Address classNameAddr(int classCp)
    {
        if (gView == null)
        {
            return Address.zero();
        }
        return gView.base.plus((long) gView.cp[u2(gView.base.plus(gView.cp[classCp]))]);
    }


    // Classes a lazily-compiling method was seen to touch (cross-class getstatic/putstatic owner, `new`
    // target). Collected DURING the compile and initialized right after it, which is still strictly before
    // the method can run -- so those two triggers need no emitted runtime barrier at all. Recording is gated
    // on lzCompiling: a load-time compile (an initializer) must leave ordering to runClinits.
    // Compiled bodies of the float/double string formatters, resolved BY NAME at runtime and memoised.
    // NOT reached by a static call from baked VM code, and that is the whole design: baking
    // Double.toString drags jdk/internal/math/DoubleToDecimal into the bake domain, which CANNOT carry it
    // -- its <clinit> uses an `ldc` class literal the host writer refuses, and its `special` method (the
    // NaN/Infinity/+-0.0 path) does not resolve there. Demand-loaded into the LOADER world it works
    // perfectly, measured before this was written: byte-identical to a host JVM on 1.5, 0.1, 0.0, -0.0,
    // 1e20, 1e-9 and NaN. Same shape as seedStandardProps reaching Properties.setProperty.
    private static long dblToStrBuf;
    private static long dblValueOfBuf;                  // Double.valueOf(D) for VMBox -- per LAUNCH, like dblToStrBuf
    private static long fltValueOfBuf;                  // Float.valueOf(F)
    private static boolean reportedNoFloatBox;
    private static long fltToStrBuf;

    static boolean lzCompiling;


    /** Compile+run a two-int-arg static method matching the seek key, with args {@code a,b}. */
    private static long load2(long bytes, int len, long a, long b)
    {
        DataReference base = DataReference.fromAddress(Address.fromLong(bytes));
        parseConstPool(base, len);
        parseFields();
        FoundMethod f = findMethod(base.toAddress());
        if (f == null)
        {
            return 0L;
        }
        long buf = compile(f.code, ClassBytes.codeLength(f.code), f.descOff, f.isStatic, f.isSync);   // two int args
        return Magic.call2(buf, a, b);
    }

    /** Set by {@link #probeStatic} to 1 if the target method was found+run, 0 if the class lacks it. */
    static int probeFound;

    /**
     * Real-java.base probe: compile (in isolation, transitively pulling same-class callees) and run the
     * static method named {@code name}{@code desc} in the raw class blob, with up to two args. Byte-name
     * matching (so method names longer than the 8-char packed seek work), no {@code <clinit>} — for pure
     * numeric methods that touch no static state. Returns the (long) result; sets {@link #probeFound}.
     */
    static long probeStatic(long bytes, int len, byte[] name, byte[] desc, long a, long b)
    {
        parseConstPool(DataReference.fromAddress(Address.fromLong(bytes)), len);
        parseFields();
        FoundMethod f = findMethodByBytes(gView.base, name, desc);
        if (f == null)
        {
            probeFound = 0;
            return 0L;
        }
        probeFound = 1;
        long buf = compile(f.code, ClassBytes.codeLength(f.code), f.descOff, f.isStatic, f.isSync);
        return Magic.call2(buf, a, b);
    }

    /** The compiled entry of real {@code Integer.parseInt(String,int)}, stashed by {@link #loadParseInt}. */
    static long parseIntBuf;

    /**
     * Close the wall to real {@code Integer.parseInt}: load the mini dep surface it actually needs
     * ({@code String} + {@code Character.digit} + the {@code NumberFormatException} hierarchy) as a normal
     * closure, then compile the UNMODIFIED JDK {@code Integer.parseInt(String,int)} in isolation, resolving
     * its cross-class calls against that just-loaded registry. The error-path refs it can't satisfy
     * (String.format, valueOf->IntegerCache) compile to unreached stubs — never taken for a valid radix-10
     * parse. The result buffer is stashed for {@link #runParseInt}.
     */
    static void loadParseInt()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.throwableBytes, (int) VM.throwableLen);
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.illegalArgBytes, (int) VM.illegalArgLen);
        addBlob(VM.numberFmtBytes, (int) VM.numberFmtLen);
        addBlob(VM.characterBytes, (int) VM.characterLen);
        addBlob(VM.integerBytes, (int) VM.integerLen);           // Integer as a blob: reachable pass compiles parseInt
        Closure.entryPoint(VM.integerBytes, Magic.bytes("parseInt"), Magic.bytes("(Ljava/lang/String;I)I"));   // reachability-gated
        loadAll();
        parseIntBuf = globalMethodBuf(Magic.bytes("java/lang/Integer"), Magic.bytes("parseInt"), Magic.bytes("(Ljava/lang/String;I)I"));
    }


    /** The compiled buffer of a loaded method, by class/name/descriptor (from the global registry). */
    private static long globalMethodBuf(byte[] cls, byte[] name, byte[] desc)
    {
        int i = 0;
        while (i < MethodRegistry.rgCount)
        {
            if (Utf8.utf8IsAtBase(MethodRegistry.rgTab[i].base.toAddress(), MethodRegistry.rgTab[i].classOff, cls)
                    && Utf8.utf8IsAtBase(MethodRegistry.rgTab[i].base.toAddress(), MethodRegistry.rgTab[i].nameOff, name)
                    && Utf8.utf8IsAtBase(MethodRegistry.rgTab[i].base.toAddress(), MethodRegistry.rgTab[i].descOff, desc))
            {
                return MethodRegistry.rgTab[i].buf.toLong();
            }
            i += 1;
        }
        return dlStubByName(cls, name, desc);           // a celled static has no registry entry: use its cell
    }

    /** Current contents of the phase-A cell for a method named by literal bytes, or 0. Now that EVERY class
     *  is metadata-only, a static named from outside the loader — {@code main(String[])} above all — lives
     *  only as a cell, never as a registry entry. The cell holds callable code either way: the lazy stub, or
     *  the compiled body once first-called. */
    private static long dlStubByName(byte[] cls, byte[] name, byte[] desc)
    {
        if (DynamicLinker.dlTab == null)
        {
            return 0L;
        }
        int k = 0;
        while (k < DynamicLinker.dlN)
        {
            DynLink d = DynamicLinker.dlTab[k];
            if (Utf8.utf8IsAtBase(d.blob.toAddress(), d.classOff, cls)
                    && Utf8.utf8IsAtBase(d.blob.toAddress(), d.nameOff, name)
                    && Utf8.utf8IsAtBase(d.blob.toAddress(), d.descOff, desc))
            {
                return d.cell.toAddress().loadLong();
            }
            k += 1;
        }
        return 0L;
    }


    /**
     * The OS-style program launcher: demand-load the class named by {@code className} from the embedded
     * classDir and run its {@code main(String[])} with {@code argv} (a guest {@code String[]}). This is the
     * one path the runtime uses to run any program — the generalization of the ~25 bespoke {@code loadX()}
     * demos. Object is seeded first (its vtable slots are canonical and it is never auto-pulled); everything
     * else the program reaches is demand-loaded from the classDir by {@link #loadAll}.
     */

    // ----- stub-table integrity watch ------------------------------------------------------------------
    // The bake stub table's three Utf8 runs per entry are IMAGE DATA and never change after the writer emits
    // them, so any difference means something scribbled on them. This is what found the code arena
    // overlapping the tail of the image (Heap.CODE_BASE below the image end): reading the damage where it was
    // USED said only that a name was corrupt, while checking here said WHEN, which named the demo and the
    // entry in one boot after several had been spent bisecting.
    //
    // Kept, not deleted. BootImageWriter now fails the build on that particular overlap, but this costs one
    // scan of a few hundred entries per launch and is SILENT on a healthy boot, so it still earns its place
    // as the check that says "something is writing into the image" whatever the cause turns out to be.
    //
    // Checksum, not a compare against a copy: a copy would itself be heap the collector may move around,
    // and the point is to touch nothing but the table.
    private static long stubTabSum;

    static long stubTabChecksum()
    {
        long h = 0x811C9DC5L;
        long n = VM.bakeStubCount;
        long i = 0;
        while (i < n)
        {
            long e = VM.bakeStubTable + i * 32L;
            int f = 0;
            while (f < 3)                                   // classUtf8, nameUtf8, descUtf8 -- not the memo,
            {                                               //   which is written at runtime by design
                long u = Address.fromLong(e).plus(f * 8L).loadLong();
                h = (h * 31L) + u;                          // the POINTER too: one boot clobbered a pointer
                if (u != 0L)                                //   while its neighbours in the entry survived
                {
                    int len = u2(u);
                    if (len > 0 && len <= 512)
                    {
                        int c = 0;
                        while (c < len)
                        {
                            h = (h * 31L) + u1(u + 2 + c);
                            c += 1;
                        }
                    }
                    else
                    {
                        h = (h * 31L) + 0xBADL;             // a wrecked length is itself a difference
                    }
                }
                f += 1;
            }
            i += 1;
        }
        return h;
    }

    /** Snapshot on the first call; on every later call report a change and where. */
    static void stubTabCheck(byte[] whenName)
    {
        if (VM.bakeStubTable == 0L || VM.bakeStubCount == 0L)
        {
            return;
        }
        long now = stubTabChecksum();
        if (stubTabSum == 0L)
        {
            stubTabSum = now;
            return;
        }
        if (now != stubTabSum)
        {
            Uart.write(Magic.bytes("\n  STUB TABLE CHANGED before "));
            Uart.write(whenName);
            Uart.putc(0x0A);
            stubTabReportFirstBad();
            stubTabSum = now;                               // resync so later launches report NEW damage only
        }
    }

    /** Name the first entry whose Utf8 no longer looks like a name, with its index and address. */
    private static void stubTabReportFirstBad()
    {
        long n = VM.bakeStubCount;
        long i = 0;
        while (i < n)
        {
            long e = VM.bakeStubTable + i * 32L;
            int f = 0;
            while (f < 3)
            {
                long u = Address.fromLong(e).plus(f * 8L).loadLong();
                boolean ok = u != 0L;
                if (ok)
                {
                    int len = u2(u);
                    ok = len > 0 && len <= 512;
                    int c = 0;
                    while (ok && c < len)
                    {
                        int b = u1(u + 2 + c);
                        if (b < 0x20 || b > 0x7E)
                        {
                            ok = false;
                        }
                        c += 1;
                    }
                }
                if (!ok)
                {
                    Uart.write(Magic.bytes("    entry="));
                    VM.printHex(i);
                    Uart.write(Magic.bytes(" field="));
                    VM.printHex((long) f);
                    Uart.write(Magic.bytes(" ptr="));
                    VM.printHex(u);
                    Uart.write(Magic.bytes(" entryAddr="));
                    VM.printHex(e);
                    Uart.putc(0x0A);
                    return;
                }
                f += 1;
            }
            i += 1;
        }
        Uart.write(Magic.bytes("    (checksum moved but every entry still reads as a name)\n"));
    }

    /** True once {@link #bringUpRuntime} has allocated the loader tables and added the core blobs. */
    static boolean runtimeUp;

    /**
     * One-time runtime bring-up: the loader tables and the core classes.
     *
     * <p>This is where the VM's ONE {@code resetLoader()} happens -- it is what ALLOCATES the loader tables
     * (class/field/static registries, the pending-blob list, the {@code <clinit>} records), so it cannot be
     * skipped; it just must not happen again. It deliberately does NOT compile anything: a closure built here,
     * without the program in it, is a DIFFERENT closure, and the supers a program incidentally pulls
     * ({@code java/lang/Number} for the atomics, {@code java/io/InputStream} for ByteArrayInputStream) would be
     * missing from it -- which shows up as {@code UNREGISTERED SUPER (fields alias)} and a program that then
     * NPEs. Loading belongs to the first {@link #launchMain}, which has the program in hand.
     *
     * <p>Idempotent, and the flag is set FIRST: a pull can run an initializer that reaches back here.
     */
    static void bringUpRuntime()
    {
        if (runtimeUp)
        {
            return;
        }
        runtimeUp = true;
        if (VM.lazyCompileAddr == 0L)                       // dead at runtime (writer stashes the address);
        {                                                  //   makes lazyCompile reachable so the writer
            long u = LazyCompiler.lazyCompile(-1);                       //   compiles + stashes it for the 1b trampoline
        }
        if (VM.resolveLinkStubAddr == 0L)                   // same trick for the link-stub trampoline's target
        {
            long u = DynamicLinker.resolveLinkStub(-1);
        }
        resetLoader();                                      // the only one: it ALLOCATES the loader tables
        addBlob(VM.objectBytes, (int) VM.objectLen);        // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);        // String + System.out streams
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);      // number formatting (near-universal)
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
    }

    /**
     * Run {@code className.main(argsLine)} on the RUNNING VM, resetting nothing.
     *
     * <p>The difference from {@link #launch} is the whole point: {@code launch} begins with
     * {@code resetLoader()}, so every program it starts re-derives the entire base closure from scratch --
     * each class parsed, laid out, compiled and its {@code <clinit>} run AGAIN, with the previous program's
     * loaded state discarded. This method inherits that state. A class already loaded stays loaded, and an
     * initializer that already ran does NOT run again: its class is still registered at
     * {@code ST_INITIALIZED}, so {@link ClassInitializer#ensureClinit} returns at once.
     *
     * <p>Everything else keeps {@code launch}'s ORDER exactly, because that order is load-bearing -- the
     * support classes are pulled into the same batch as the program (so their supers are present), the seeds
     * come after {@code loadAll} (they are built from loaded classes), and argv is built after it too
     * ({@code guestString} needs String's TIB). The first call therefore does what {@code launch} does minus
     * the reset; each later call adds only what its own program pulls in.
     */
    static void launchMain(byte[] className, byte[] argsLine)
    {
        stubTabCheck(className);
        bringUpRuntime();                                   // once per boot; a no-op on every later call
        long entry = pullClass(className);                  // pull the program itself from the classDir by name
        if (entry == 0L)
        {
            Uart.write(Magic.bytes("launchMain: class not found: "));
            Uart.write(className);
            Uart.putc(0x0A);
            return;
        }
        Closure.entryPoint(entry, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        pullSupportClasses();
        loadAll();                                          // first call: the base closure. Later: only the new.
        // EVERY call, not once. Each of these is an ENSURE, not a set: it does nothing if its slot is already
        // filled, and nothing if the class it needs is not registered YET. Those two cases are why a one-shot
        // latch is wrong -- java/io/PrintStream was absent from the FIRST program's closure, so the streams
        // seeded nothing, and a latch set before the work succeeded meant they were never seeded at all.
        // System.out then read null in the third program (the demo suite's PipDemo). launch got this right by
        // accident, re-seeding after every program; doing it deliberately costs a few registry lookups.
        BootSeeds.seedSystemIn();                                     // System.in -> an empty stream (never null)
        buildRunTramp();                                    // enable Thread.start() (needs Runnable loaded)
        // seedSystemProps runs LAST of the seeds, because it is the only one that COMPILES a method:
        // resolving Properties.setProperty rebuilds the loader's compile cursor (gView/gBuild) for
        // java/util/Properties, and every seed after it would then be standing on the wrong class's state.
        // That is the hazard already recorded for demand-loading inside buildLambdaTib, reached from a
        // different direction.
        BootSeeds.seedSystemProps();                                  // System.props -> a real Properties (setProperty)
        BootSeeds.seedSavedProps();                                   // jdk.internal.misc.VM.savedProps -> its own snapshot
        BootSeeds.seedSystemStreams();                                // System.out/err -> stock PrintStream over the UART
        BootSeeds.seedBooted();                                       // jdk.internal.misc.VM.initLevel -> SYSTEM_BOOTED, last
        BootSeeds.seedRefAccess();                                    // Reference.<clinit> -> SharedSecrets.javaLangRefAccess
        ObjectReference argv = buildArgv(argsLine);         // after loadAll: guestString needs String's TIB
        long buf = globalMethodBuf(className, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        if (buf == 0L)
        {
            Uart.write(Magic.bytes("launchMain: no main(String[]) in "));
            Uart.write(className);
            Uart.putc(0x0A);
            return;
        }
        long unused = Magic.call2(buf, argv.toLong(), 0L);  // main(args) -- x1 unused by a 1-arg static
        reportSyncCoverage();
        ClassInitializer.reportPendInitDrops();
        Uart.write(Magic.bytes("\n[main returned normally]\n"));
        reportGc();
    }

    /**
     * Force-load the classes a program cannot be relied on to NAME but the runtime still needs.
     *
     * <p>Shared by {@link #launch} and {@link #bringUpRuntime} so the two cannot drift: the implicit-check
     * exceptions in particular are load-bearing -- a program that never names them leaves them unloaded, so
     * {@code newExc} gives the thrown object a TIB of 0, which is uncatchable ({@code instanceOf} cannot walk
     * its type chain) and nameless in a stack trace.
     */
    private static void pullSupportClasses()
    {
        pullClass(Magic.bytes("java/lang/NullPointerException"));
        pullClass(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"));
        pullClass(Magic.bytes("java/lang/ArithmeticException"));
        pullClass(Magic.bytes("java/lang/ClassCastException"));
        pullClass(Magic.bytes("java/lang/NegativeArraySizeException"));
        pullClass(Magic.bytes("java/lang/ArrayStoreException"));           // aastore covariant type mismatch
        pullClass(Magic.bytes("java/lang/InternalError"));                 // any other unexpected hardware trap
        // The REFLECTION failures, for the same reason and with the same consequence. The VM throws these
        // itself (Class.forName, getDeclaredMethod, getDeclaredField), so a program that never NAMES the class
        // leaves it unloaded and the thrown object gets a TIB of 0 -- no Type, so getClass() answers null and
        // the object is nameless. Library code REPORTS a caught exception by calling toString() on it, which
        // is a virtual call through that missing Type: picocli's "Could not register converter" handler does
        // exactly that for every converter it cannot load, which on joe-ng is several.
        pullClass(Magic.bytes("java/lang/ClassNotFoundException"));
        pullClass(Magic.bytes("java/lang/NoSuchMethodException"));
        pullClass(Magic.bytes("java/lang/NoSuchFieldException"));
        // A FAILED initializer (JVMS 12.4.2): the first use throws ExceptionInInitializerError around a non-Error,
        // every later one NoClassDefFoundError. Loaded up front because the throw happens inside the failing
        // initialization, often under the loader lock, which is no place to start loading a class.
        pullClass(Magic.bytes("java/lang/ExceptionInInitializerError"));
        pullClass(Magic.bytes("java/lang/NoClassDefFoundError"));
        // System.in's empty-stream seed needs this class present; nothing else guarantees it, and a program
        // that touches System.in would otherwise find null (see seedSystemIn). Tiny, and loaded once.
        pullClass(Magic.bytes("java/io/ByteArrayInputStream"));
        // ... or, under /etc/init stdin=uart, the console stream (Console.stdinOn; same seed, other class).
        if (Console.stdinOn != 0)
        {
            pullClass(Magic.bytes("jdk/internal/misc/MetalConsoleIn"));
        }
        // seedSystemProps needs Properties the same way, and without it NOTHING is seeded: System.props stays
        // null, System.getProperty answers nothing, and picocli's `Ansi.isWindows()` --
        // `System.getProperty("os.name").toLowerCase()` -- NPEs INSIDE the library. That is the console
        // launcher's second failure, reached from addSubcommand via usage-width detection.
        //
        // Unsafe comes WITH it, and that pairing is the point: `Properties.<init>` ends in
        // `Unsafe.storeFence()`, and a link stub for it has nothing to resolve against unless the class is
        // registered. Pulling Properties alone therefore traded one failure for another -- it broke the demo
        // suite with a failed resolve (TRAPWIRE index=-1) inside that very constructor.
        pullClass(Magic.bytes("jdk/internal/misc/Unsafe"));
        pullClass(Magic.bytes("java/util/Properties"));
        // Metal JavaLangAccess: seeded into SharedSecrets so EnumMap.getKeyUniverse (getEnumConstantsShared)
        // works (System.<clinit> which normally registers the JLA is skipped).
        pullClass(Magic.bytes("jdk/internal/access/MetalJavaLangAccess"));
        // Metal JavaIOAccess: System.console() does `SharedSecrets.getJavaIOAccess().console()` with NO null
        // check, so an unregistered shim is an NPE from inside java.base rather than a missing feature.
        pullClass(Magic.bytes("jdk/internal/access/MetalJavaIOAccess"));
        // java/lang/ref/Reference: HotSpot initializes it during startup, and its <clinit> is what registers
        // SharedSecrets.javaLangRefAccess -- read with NO null check by Runtime.runFinalization (seedRefAccess).
        pullClass(Magic.bytes("java/lang/ref/Reference"));
        // System.out/err: stock PrintStreams over the UART, built by MetalStdStreams (seedSystemStreams).
        pullClass(Magic.bytes("jdk/internal/misc/MetalStdStreams"));
        // The atomic scalar wrappers are frequently referenced only by a class literal; force-load them so the
        // literal's Type/mirror + the field registry exist even when nothing instantiates them.
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicInteger"));
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicLong"));
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicReference"));
    }

    /** Print the collection figures if the program collected. Shared by both launch paths. */
    private static void reportGc()
    {
        if (Heap.gcPressure != 0)                           // the program collected: report it unconditionally,
        {                                                   //   so GC evidence needs no debug flag on real HW
            Uart.write(Magic.bytes("gc: collections="));
            VM.printDec(Heap.gcPressure);
            Uart.write(Magic.bytes(" lastProbes="));        // words examined by the last collection: the
            VM.printHex(VMGc.probes);                       //   precision metric (PLAN.md "GC metadata")
            Uart.write(Magic.bytes(" roots="));             // ... split into the irreducibly conservative
            VM.printHex(VMGc.rootProbes);                   //   root scan and the TRACE side, which is what
            Uart.write(Magic.bytes(" heap="));              //   type metadata actually shrinks
            VM.printHex(VMGc.probes - VMGc.rootProbes);
            Uart.write(Magic.bytes(" nomap="));             // blocks still scanned without any metadata
            VM.printHex(VMGc.nomap);
            Uart.write(Magic.bytes(" lastReclaimed="));
            VM.printHex(VM.reclaimed);
            Uart.write(Magic.bytes(" idleRoots="));        // per-core IDLE stacks scanned as roots, of the
            VM.printDec(VMGc.idleRoots);                   //   idle tasks found: N is what says the root
            Uart.putc(0x2F);                               //   FIRES, N < M that one was out of band and
            VM.printDec(VMGc.idleSeen);                    //   was SKIPPED rather than silently guessed
            Uart.write(Magic.bytes(" idleMarked="));       // ... of which actually RETAINED something: 0 =
            VM.printDec(VMGc.idleMarked);                  //   the hole is closed, not a loss repaired
            Uart.write(Magic.bytes(" idleGc="));           // collections that ran ON an idle task (the
            VM.printDec(VMGc.idleGc);                      //   mis-bracketed case): expected 0 -- measured
            Uart.write(Magic.bytes(" byCore="));           // which core ran them (S5a: any core collects)
            int bc = 0;
            while (bc < 4)
            {
                if (bc != 0)
                {
                    Uart.putc(0x2F);
                }
                VM.printDec((int) VMGc.gcByCore[bc]);
                bc += 1;
            }
            Uart.write(Magic.bytes(" busy="));             // ... asked for while another core collected
            VM.printDec((int) VMGc.gcBusy);
            Uart.write(Magic.bytes(" arenaLive="));        // cores 1-3's arenas after the last collection
            VM.printHex(VMGc.arenaLive);
            Uart.write(Magic.bytes(" arenaFreed="));
            VM.printHex(VMGc.arenaFreed);
            Uart.putc(0x0A);
        }
    }

    static void launch(byte[] className, byte[] argsLine)
    {
        stubTabCheck(className);
        if (VM.lazyCompileAddr == 0L)                       // dead at runtime (writer stashes the address);
        {                                                  //   makes lazyCompile reachable so the writer
            long u = LazyCompiler.lazyCompile(-1);                       //   compiles + stashes it for the 1b trampoline
        }
        if (VM.resolveLinkStubAddr == 0L)                   // same trick for the link-stub trampoline's target
        {
            long u = DynamicLinker.resolveLinkStub(-1);
        }
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);        // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);        // String + System.out streams
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);      // number formatting (near-universal)
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        long entry = pullClass(className);                  // pull the program itself from the classDir by name
        if (entry == 0L)
        {
            Uart.write(Magic.bytes("launch: class not found: "));
            Uart.write(className);
            Uart.putc(0x0A);
            return;
        }
        Closure.entryPoint(entry, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        pullSupportClasses();
        loadAll();                                          // reachability-gated JIT of the whole closure
        BootSeeds.seedSystemIn();                                     // System.in -> an empty stream (never null)
        buildRunTramp();                                    // enable Thread.start(): the shared Runnable.run()
                                                            //   trampoline (needs Runnable loaded by loadAll;
                                                            //   harmless if the program spawns no threads)
        // seedSystemProps runs LAST of the seeds, because it is the only one that COMPILES a method:
        // resolving Properties.setProperty rebuilds the loader's compile cursor (gView/gBuild) for
        // java/util/Properties, and every seed after it would then be standing on the wrong class's state.
        // That is the hazard already recorded for demand-loading inside buildLambdaTib, reached from a
        // different direction.
        BootSeeds.seedSystemProps();                                  // System.props -> a real Properties (setProperty)
        BootSeeds.seedSavedProps();                                   // jdk.internal.misc.VM.savedProps -> its own snapshot
        BootSeeds.seedSystemStreams();                                // System.out/err -> stock PrintStream over the UART
        BootSeeds.seedBooted();                                       // jdk.internal.misc.VM.initLevel -> SYSTEM_BOOTED, last
        BootSeeds.seedRefAccess();                                    // Reference.<clinit> -> SharedSecrets.javaLangRefAccess
        // Build the String[] argv AFTER loadAll: guestString needs the loaded String class's TIB, so the argv
        // MUST be built here, not before resetLoader() (that was the "args[i] throws" bug).
        ObjectReference argv = buildArgv(argsLine);
        long buf = globalMethodBuf(className, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        if (buf == 0L)
        {
            Uart.write(Magic.bytes("launch: no main(String[]) in "));
            Uart.write(className);
            Uart.putc(0x0A);
            return;
        }
        long unused = Magic.call2(buf, argv.toLong(), 0L);  // main(args) — x1 unused by a 1-arg static
        reportSyncCoverage();
        ClassInitializer.reportPendInitDrops();
        Uart.write(Magic.bytes("\n[main returned normally]\n"));
        reportGc();
    }

    /** Build a guest {@code String[]} from a space-separated {@code argsLine} (each token becomes a
     *  {@link #guestString}). Called from {@link #launch} after {@code loadAll} so String's TIB is valid. */
    private static ObjectReference buildArgv(byte[] line)
    {
        int len = line.length;
        int n = 0;
        int i = 0;
        while (i < len)                                     // pass 1: count tokens
        {
            while (i < len && line[i] == 0x20) { i += 1; }
            if (i >= len) { break; }
            n += 1;
            while (i < len && line[i] != 0x20) { i += 1; }
        }
        ObjectReference argv = Heap.allocArray(n, 8).toObjectReference();                  // String[n] (8-byte reference elements)
        DataReference strType = stringTypeAddr();
        if (!strType.isZero())
        {
            // A real String[]: an untyped (raw) array's elements are not references to a precise collector.
            argv.toAddress().store(ArrayTypes.refArrayTib(strType.toAddress()));
        }
        int idx = 0;
        i = 0;
        while (i < len)                                     // pass 2: fill
        {
            while (i < len && line[i] == 0x20) { i += 1; }
            if (i >= len) { break; }
            int start = i;
            while (i < len && line[i] != 0x20) { i += 1; }
            byte[] tok = new byte[i - start];
            int k = 0;
            while (k < tok.length)
            {
                tok[k] = line[start + k];
                k += 1;
            }
            argv.toAddress().plus(24L).plus(idx * 8L).store(guestString(tok));
            idx += 1;
        }
        return argv;
    }

    /** Copy a class name into scratch and pull its raw {@code .class} bytes from the embedded classDir;
     *  register them ({@link #addBlob}) and return the bytes address, or 0 if the class is not embedded. */
    private static long pullClass(byte[] name)
    {
        DataReference scratch = Heap.allocData(name.length + 8);
        int i = 0;
        while (i < name.length)
        {
            scratch.plus(i).store(name[i]);
            i += 1;
        }
        long bytes = VM.dirBytes(scratch.toAddress(), name.length).toLong();
        if (bytes == 0L)
        {
            return 0L;
        }
        addBlob(bytes, (int) VM.dirLen(scratch.toAddress(), name.length));
        return bytes;
    }


    /**
     * Evidence line for the code-arena rewind. {@code peak} is the real high-water; {@code zeroBound} is
     * {@code codeHeapHigh}, which is NOT a peak -- it is seeded to {@code mark + CODE_ZERO_SPAN} (8 MiB)
     * because it bounds the rewind path's re-zeroing loop. It was previously printed as "high", and every
     * "high-water unchanged" conclusion in the GC arcs was reading that constant.
     */
    static void printCodeArena()
    {
        Uart.write(Magic.bytes("code arena: mark="));
        VM.printHex(codeHeapMark);
        Uart.write(Magic.bytes(" cur="));
        VM.printHex(Address.fromLong(Heap.CODE_PTR_CELL).loadLong());
        Uart.write(Magic.bytes(" peak="));
        VM.printHex(Heap.codePeak);
        Uart.write(Magic.bytes(" zeroBound="));
        VM.printHex(codeHeapHigh);
        Uart.putc(0x0A);
    }


    /** A fresh guest {@code java/lang/String} (LATIN1) holding {@code ascii} (requires String loaded). */
    static long guestString(byte[] ascii)
    {
        ObjectReference arr = Heap.allocArray(ascii.length, 1).toObjectReference();    // a real byte[] (elem size 1, length@16, data@24)
        Address bt = ArrayTypes.byteArrayTib();                     // type value as [B so String.getBytes()'s `checkcast [B`
        if (!bt.isZero())                                // resolves (else it spins forever -- e.g. println(arg))
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);
        }
        int i = 0;
        while (i < ascii.length)
        {
            arr.toAddress().plus(24L).plus(i).store(ascii[i]);
            i += 1;
        }
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().store(stringTib());           // wrap it as a guest java/lang/String
        obj.toAddress().plus(16L).store(arr);                  // value field (offset 16); coder@24 stays 0 = LATIN1
        return obj.toLong();
    }


    /**
     * A fresh guest {@code byte[]} holding a copy of {@code src}, or 0 for a null source.
     *
     * <p>The copy is the point: {@code src} was allocated by whichever world produced it (a writer-baked
     * {@code new byte[]} carries no array Type), while this one is a real {@code [B} -- so guest code may
     * {@code checkcast} it, store it in a field the collector traces, and hand it on.
     */
    static long guestBytes(byte[] src)
    {
        if (src == null)
        {
            return 0L;
        }
        ObjectReference arr = Heap.allocArray(src.length, 1).toObjectReference();
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);
        }
        int i = 0;
        while (i < src.length)
        {
            arr.toAddress().plus(24L).plus(i).store(src[i]);
            i += 1;
        }
        return arr.toLong();
    }

    /**
     * A heap blob holding {@code s} in CLASSFILE Utf8 SHAPE: a big-endian u2 length, then the bytes.
     *
     * <p>Needed because the resolver family ({@code compileSigOnDemand}, {@code bufBySigU},
     * {@code resolveLinkTarget}) takes pointers INTO a class blob, where every name is length-prefixed --
     * while VM-side code holds names as plain {@code byte[]}. Passing {@code Magic.addrOf(Magic.bytes(...))}
     * to one of those reads the first two characters as a length and then garbage; that mistake was made once
     * already, in a class-chain fallback that was written and removed before it shipped.
     */
    static DataReference utf8Blob(byte[] s)
    {
        DataReference p = Heap.allocData(s.length + 2);
        p.store((byte) ((s.length >> 8) & 0xFF));
        p.plus(1L).store((byte) (s.length & 0xFF));
        int i = 0;
        while (i < s.length)
        {
            p.plus(2L).plus(i).store(s[i]);
            i += 1;
        }
        return p;
    }

    /** A guest java/lang/String from a length-prefixed Utf8 at {@code base+off} (u2 length, then bytes); 0 if base 0. */
    static long guestStringUtf8(Address base, int off)
    {
        if (base.isZero())
        {
            return 0L;
        }
        int len = u2(base.plus(off));
        ObjectReference arr = Heap.allocArray(len, 1).toObjectReference();
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);
        }
        int i = 0;
        while (i < len)
        {
            arr.toAddress().plus(24L).plus(i).store((byte) u1(base.plus(off + 2L + i)));
            i += 1;
        }
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().store(stringTib());
        obj.toAddress().plus(16L).store(arr);
        return obj.toLong();
    }


    /** A guest String of the {@code len} bytes at {@code src}. */
    static long guestBytes(Address src, int len)
    {
        return guestBytesMapped(src, len, false);
    }

    /** A guest String of the internal class name at {@code src} as a BINARY name: '/' written as '.'. */
    static long guestBinaryName(Address src, int len)
    {
        return guestBytesMapped(src, len, true);
    }

    private static long guestBytesMapped(Address src, int len, boolean dots)
    {
        ObjectReference arr = Heap.allocArray(len, 1).toObjectReference();
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);
        }
        int i = 0;
        while (i < len)
        {
            int b = u1(src.plus(i));
            if (dots && b == 0x2F)
            {
                b = 0x2E;
            }
            arr.toAddress().plus(24L).plus(i).store((byte) b);
            i += 1;
        }
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().store(stringTib());
        obj.toAddress().plus(16L).store(arr);
        return obj.toLong();
    }


    /** Copy an ASCII {@code byte[]} into a fresh mini String and run the compiled real {@code Integer.parseInt(s, 10)}. */
    static int runParseInt(byte[] ascii)
    {
        if (parseIntBuf == 0L)
        {
            return 0;
        }
        return (int) Magic.call2(parseIntBuf, guestString(ascii), 10L);
    }

    /** Like {@link #findMethod} but matches name+descriptor by byte content (for names &gt; 8 chars). */
    static FoundMethod findMethodByBytes(DataReference base, byte[] name, byte[] desc)
    {
        Address p = gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p.plus(6));                      // access, name, descriptor, attrs
            if (Utf8.utf8IsAtBase(base.toAddress(), gView.cp[u2(p.plus(2))], name) && Utf8.utf8IsAtBase(base.toAddress(), gView.cp[u2(p.plus(4))], desc))
            {
                long code = findCode(base.toAddress(), p.plus(8), attrs);
                if (code != 0L)
                {
                    return new FoundMethod(code, gView.cp[u2(p.plus(4))], access);
                }
            }
            p = skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return null;
    }

    /**
     * Load a small class hierarchy on the metal and run it. Animal is loaded first,
     * then Dog (which inherits Animal's fields and flattened vtable and overrides a
     * method), then Guest (which {@code new}s a Dog and dispatches through an
     * Animal-typed reference). Returns 42 = '*'.
     */
    static int loadGuest()
    {
        resetLoader();
        // Handed over deliberately worst-first — Guest depends on all three, and the
        // implementors depend on the interface. loadAll derives the real order.
        addBlob(VM.guestBytes, (int) VM.guestLen);
        addBlob(VM.betaBytes, (int) VM.betaLen);
        addBlob(VM.alphaBytes, (int) VM.alphaLen);
        addBlob(VM.greeterBytes, (int) VM.greeterLen);
        addBlob(VM.myExcBytes, (int) VM.myExcLen);
        loadAll();
        seek(0x616e73776572L, 6, 0x282949L, 3);        // "answer" "()I"
        FoundMethod f = findMethod(Address.fromLong(VM.guestBytes));
        if (f == null)
        {
            return 0;
        }
        return (int) Magic.call0(bufOf(f.code));
    }

    /**
     * Name the class the loader is compiling right now, for the fault reporter. A wild pointer inside a
     * shared helper ({@code u1}, {@code utf8EqAt}) says nothing about which class's constant pool was being
     * walked, and that is the first question worth answering.
     */
    static void printCurrentClass()
    {
        // NO CONTEXT and BAD CONTEXT are different answers, and every caller of this is a failure path that
        // needs both. A null gView means no compile is in progress; a current view carrying a torn
        // gView.thisNameOff is a pointer that LOOKS usable and is not -- and this is read from VM.loaderLock's
        // watchdog WITHOUT the lock, i.e. deliberately while the owner may be mid-update. printNameAt loops
        // for whatever u2 it finds, so a wrong offset streams up to 65535 bytes at ~87us a character and
        // buries the report being read. That comment at the watchdog already claims a torn read here
        // "misnames a class rather than faulting"; the cap is what makes the claim true.
        if (gView == null || gView.thisNameOff == 0)
        {
            Uart.write(Magic.bytes("<none>"));           // no compile in progress -- distinct from a bad pointer
            return;
        }
        printUtf8Capped(gView.base.plus(gView.thisNameOff));
    }

    // ----- code-embedded roots ---------------------------------------------------------------------
    /**
     * Heap addresses that compiled code carries INSIDE its instruction stream. {@code MetalSymbols.emitAddr}
     * bakes a TIB, Type, interface Type or class literal into a {@code MOVZ}+{@code MOVK} pair, which splits
     * the address across two instruction immediate fields — so scanning the code arena as *data* can never
     * recover it, and the collector does not scan it at all. Every such address is recorded here as a plain
     * word the collector CAN scan.
     *
     * <p>Nothing depends on it yet: the batch rewind still kills a class's code and its metadata together,
     * and while a batch is current the registries hold the same objects through {@code RVMClass.tib}/{@code
     * type}/{@code statics}. It exists because the opposite is true the moment code outlives its batch or
     * freed memory is reused promptly — then a TIB whose only surviving reference is a code immediate is a
     * dangling pointer. The table also answers whether that hazard is real for actual workloads: the
     * collector counts how many of these addresses it would otherwise have left unmarked.
     *
     * <p>Lives in fixed scratch (0x0305_0000..0x0380_0000 is free between {@code CORE_FLAGS} and the
     * secondary cores' stacks), not in the managed heap — a root table that the collector could itself
     * reclaim is no root table.
     */
    static final long CODE_ROOTS     = 0x0310_0000L;
    static final long CODE_ROOTS_END = 0x0350_0000L;      // 4 MiB = 262,144 entries of {addr, owner}
    /** Bytes per entry: the address, and the code buffer that baked it in. Ownership is what lets a swept
     *  method's roots be dropped; without it the table only ever grows (measured at 41% of capacity after a
     *  single suite run, i.e. fatal for a program twice as long). */
    static final long CODE_ROOT_ENTRY = 16L;
    /** The buffer currently being emitted into — the owner recorded with each root. Only ever read while
     *  {@code relocRecording} is set, which is exactly the real-base emit pass, so it cannot go stale. */
    static long codeRootOwner;
    static long codeRootN;
    /** 1 = the table filled and addresses went unrecorded. Increment 4 must refuse to reclaim code or trim
     *  the heap while this is set: the missing entries are exactly the references nothing else records. */
    static int  codeRootOverflow;

    /**
     * Drop the code roots owned by a method the collector just swept. Their addresses were only ever
     * reachable through that method's instruction stream, so keeping them would retain heap objects nothing
     * can reach — and, more pressingly, would let the table grow without bound: one suite run filled 41% of
     * it before ownership existed.
     */
    static void dropCodeRootsIn(long lo, long hi)
    {
        long kept = 0;
        long i = 0;
        while (i < codeRootN)
        {
            Address src = Address.fromLong(CODE_ROOTS + i * CODE_ROOT_ENTRY);
            long owner = src.plus(8L).loadLong();
            if (owner < lo || owner >= hi)
            {
                Address dst = Address.fromLong(CODE_ROOTS + kept * CODE_ROOT_ENTRY);
                dst.store(src.loadLong());
                dst.plus(8L).store(owner);
                kept += 1;
            }
            i += 1;
        }
        codeRootN = kept;
    }

    /** Lazy-method count and the dispatch cell of entry {@code i} (0 if it has none), for CodeCompact. */
    static int lazyCount()
    {
        return LazyCompiler.lzN;
    }

    static long lazyCellAt(int i)
    {
        return LazyCompiler.lzTab == null || i < 0 || i >= LazyCompiler.lzTab.length || LazyCompiler.lzTab[i] == null ? 0L : LazyCompiler.lzTab[i].slot.toLong();
    }


    /**
     * Record a code -> code edge the JIT is about to encode, for {@link CodeEdges}. Gated exactly like
     * {@link #noteCodeRoot} and for the same reason: the SIZING pass emits the same branches into a throwaway
     * buffer, so without this every real edge is accompanied by a phantom whose site never receives a branch.
     * The first census run showed it as a perfect 50/50 ok/MISMATCH split (ok=3 MISMATCH=3, ok=7 MISMATCH=7).
     *
     * <p>Only the JIT's compile-time-resolved branch needs the gate. {@code patchRelocsFrom} runs AFTER the
     * emit pass (with the flag already cleared) and the lambda thunks are built once, so both record directly.
     */
    static void noteCodeEdge(long site, long target)
    {
        if (Relocator.relocRecording == 0)
        {
            return;
        }
        CodeEdges.note(site, target);
    }

    /** Record a heap address being baked into the instruction stream. Image and statics addresses are
     *  permanent and need no root; only the managed heap is collectable. {@code data}: the address is an
     *  allocData payload (marked one header lower), else an object; kept in bit 0 of the entry's address word
     *  (both are 8-aligned), so the collector marks exactly (frames/GC arc S6d). */
    static void noteCodeRoot(long addr, boolean data)
    {
        if (Relocator.relocRecording == 0)
        {
            return;    // the sizing pass emits the same addresses to a throwaway buffer; only the real
        }              // emit pass produces code that will run, and it is the one relocRecording marks
        if (addr < Heap.BASE || addr >= VMGc.META_HIGH)
        {
            return;    // up to the end of core 3's arena: since S5a cores 1-3's arenas are collected too, and a class
        }              //   compiled on one of them bakes its own literals and TIBs (this stopped at core 0's heap)
        if (CODE_ROOTS + codeRootN * CODE_ROOT_ENTRY >= CODE_ROOTS_END)
        {
            if (RECLAIM_BY_GC)
            {
                // The heap is now reclaimed by reachability, so this table is the ONLY record of the
                // addresses compiled code carries in its instruction stream. Dropping an entry would let
                // the collector free a block that live code still points at -- silent corruption, found
                // later and somewhere else. Halt with the count instead, the way every other loader table
                // overflow does; the fix is a larger window, not a smaller guarantee.
                capHalt(Magic.bytes("CODEROOTS"), (int) codeRootN);
            }
            if (codeRootOverflow == 0)
            {
                Uart.write(Magic.bytes("  code-root table full: reclamation must stay off\n"));
                codeRootOverflow = 1;
            }
            return;
        }
        Address.fromLong(CODE_ROOTS).plus(codeRootN * CODE_ROOT_ENTRY).store(data ? addr | 1L : addr);
        Address.fromLong(CODE_ROOTS).plus(codeRootN * CODE_ROOT_ENTRY).plus(8L).store(codeRootOwner);
        codeRootN += 1;
    }

    /** Reset every loader registry to empty, ready for a fresh {@link #loadAll} batch. */
    private static long demandHeapMark;                 // free-heap watermark, taken once reclaim is armed
    private static long demandHeapHigh;                 // high-water: the largest extent any batch reached above the mark
    private static int reclaimArmed;                    // 1 after the philosophers demo (see armHeapReclaim)
    private static long codeHeapMark;                   // code-arena level at arm time (boot stubs live below)
    private static long codeHeapHigh;                   // highest code level any batch reached (re-zero bound)
    private static final long CODE_ZERO_SPAN = 0x0080_0000L;   // pre-zero 8 MiB above the code mark (cold DRAM)
    /** Span pre-zeroed above the mark so cold-boot DRAM garbage can't wild-branch a tall batch (see resetLoader).
     *  Comfortably exceeds the ~18 MiB per-batch bl-range budget; clamped below core 1's arena (0x1000_0000). */
    private static final long DEMAND_ZERO_SPAN = 0x0600_0000L;   // 96 MiB (scaled with MAXBLOB 1024 -> 4096)

    /**
     * Start reclaiming the demand-load heap between batches. Called once, AFTER the philosophers demo — whose
     * scheduler tasks persist on the heap and must not be reclaimed under it. From here each {@link #resetLoader}
     * rewinds the bump pointer to the watermark, freeing the previous demo's (now-dead) code + objects. Without
     * this the heap grows unbounded and, by the ~14th stock closure, marches demand-load code past the A64 `bl`
     * +-128 MiB reach. Only core 0's arena is rewound; per-core heap arenas ({@link Heap}) keep the
     * secondaries out of it, so this is SMP-safe.
     */
    static void armHeapReclaim()
    {
        reclaimArmed = 1;
    }

    /**
     * Reclaim the demand-load heap BY REACHABILITY rather than by rewinding the bump pointer — **the
     * default since the metadata-lifetime arc's increment 6**. The data heap is left where it is and a
     * collection runs at the END of each reset, by which point every registry has been replaced, so the
     * previous batch's classes, TIBs, Types, itables and statics are unreachable and die like any other
     * garbage. What made this viable, in order: the live set is genuinely small (4–6 MB against a heap mark
     * that used to climb past 100 MB), compiled code's baked-in addresses are recorded so the collector can
     * see them ({@link #CODE_ROOTS}), the collector trims its bump pointer back past the highest survivor,
     * and the allocator splits and coalesces so freed memory is actually reusable. Without that last piece
     * a batch's garbage was reusable only one allocation per block, and {@code demo/LispDemo} did not
     * finish.
     *
     * <p>The CODE arena is reclaimed the same way now, under its own switch ({@link #RECLAIM_CODE_BY_GC}):
     * its buffers live outside the managed heap and the addresses pointing at them are {@code long}s the
     * collector does not follow, so code reachability is carried by a separate bitmap and the code-root
     * table rather than by tracing. Setting this to false restores the old whole-heap rewind,
     * which is always safe (it discards everything above the watermark) and is the fallback if metadata
     * lifetime is ever suspect.
     */
    static final boolean RECLAIM_BY_GC = true;

    /**
     * Reclaim the CODE arena by reachability too — the last piece of the batch model, and now the default.
     * {@link #resetLoader} no longer rewinds the code arena; the collector sweeps unreachable compiled
     * methods into a free list instead ({@code VMGc.sweepCode}), which is worth roughly 70% of everything
     * ever compiled: {@code codeUsed} tracks {@code codeLive} (1.75/1.82 MB late in the suite) where the
     * un-swept arena was pinned at 9.51 MB.
     *
     * <p>The consequence that shapes the rest of the reset: a batch's code no longer dies with its batch,
     * so anything keyed by machine ADDRESS must be retired per swept range rather than wholesale. That is
     * why the code-root drop and {@link VM#dropJitTablesIn} hang off the sweep, and why the unconditional
     * {@link VM#dropJitTablesAbove} below is gated on this being off — under reclamation it would discard
     * the unwind entries of methods that are still alive.
     *
     * <p>Nothing below the loader's code watermark is ever swept: the boot vector table, the scheduler's
     * switch stubs and the run trampoline are entered from hardware registers and stub-internal branches
     * no scan can see. Setting this back to false restores the whole-arena rewind, which is always safe
     * (it discards everything above the watermark) and is the fallback if code lifetime is ever suspect.
     */
    static final boolean RECLAIM_CODE_BY_GC = true;

    /** Per-batch footprint accounting, printed when {@link #LIFETIME_TRACE} is on: what each batch actually
     *  costs in data and code is the number this arc has to fit inside the arena without a rewind. OFF by
     *  default -- it is ~20 lines PER BATCH of allocator histograms, which buried the program's own output.
     *  Turn it on for arena/compaction work, where those histograms are the measurement. */
    static final boolean LIFETIME_TRACE = false;
    private static int  batchN;
    private static long batchDataTotal;
    private static long batchCodeTotal;
    private static long batchDataPeak;
    private static long batchCodePeak;

    private static void resetLoader()
    {
        loaderGen += 1;                                 // every registry built from here on is a new generation
        ClassInitializer.initDoneMemoClear();                            // classes re-register: no init-guard answer carries over
        if (ClassMirrors.MIRROR_RESET_WATCH)
        {
            Uart.write(Magic.bytes("\n  LOADER RESET (Class mirrors invalidated)\n"));
        }
        CodeCompact.plan();          // ONE plan per batch, taken HERE: the previous batch's class/method
                                     //   registries are still live (this method is about to clear them) and
                                     //   the arena reflects everything that batch compiled. Taking it from a
                                     //   collection instead was opportunistic -- batches whose collections
                                     //   found no live registry silently reprinted the PREVIOUS plan, which
                                     //   read as MOVABLE=0 for 19 batches then an identical 1,439 five times.
        if (reclaimArmed != 0)
        {
            if (demandHeapMark == 0L)
            {
                demandHeapMark = Address.fromLong(Heap.PTR_CELL).loadLong();   // watermark: heap level after the philosophers demo
                // Code-arena watermark, captured at the same moment: everything below it (the boot-time
                // vector table + scheduler switch stubs, the original run-trampoline, the pre-mark demos'
                // code) is permanent; everything a later batch compiles above it dies with the batch --
                // the demand model already assumes nothing but the image survives across batches (the data
                // rewind kills a batch's objects), so its code is dead too. Pre-zero a span above the mark
                // for the same cold-DRAM reason as the data heap.
                codeHeapMark = Address.fromLong(Heap.CODE_PTR_CELL).loadLong();
                long cz = codeHeapMark;
                long czEnd = codeHeapMark + CODE_ZERO_SPAN;
                if (czEnd > Heap.CODE_LIMIT) { czEnd = Heap.CODE_LIMIT; }
                while (cz < czEnd)
                {
                    Address.fromLong(cz).store(0L);
                    cz += 8L;
                }
                codeHeapHigh = czEnd;
                VMGc.codeSweepFloor = codeHeapMark;    // never sweep the boot stubs below the watermark
                if (RECLAIM_CODE_BY_GC)
                {
                    VMGc.sweepCode = 1;
                }
                // Pre-zero a generous span above the mark ONCE. The else-branch below only re-zeros up to the
                // PRIOR batches' high-water, so the FIRST batch to grow taller than every previous one would
                // otherwise read never-touched DRAM in its top region -- at cold power-on that's arbitrary
                // garbage (not zeroed), so an OOB slot read (a code buffer's trailing word, an itable/imap
                // over-scan) becomes a wild branch that DIFFERS every cold boot (e.g. HashMap, the tallest
                // batch, hanging on one boot but not the next). Zeroing [mark, mark+SPAN) makes every such
                // read deterministically hit 0 (a caught blr 0). Batches rewind to the mark, so no successful
                // batch approaches SPAN (the biggest fit under MAXBLOB=1024); SPAN stays far below core 1-3's
                // arenas (0x1000_0000), so this never touches live secondary-core data.
                long z0 = demandHeapMark;
                long zEnd = demandHeapMark + DEMAND_ZERO_SPAN;
                if (zEnd > Heap.LARGE_BASE) { zEnd = Heap.LARGE_BASE; }   // the demand heap is the SMALL
                                                        // region: this span must stop where the large-object
                                                        // region begins, or it wipes live tables allocated
                                                        // there. It was clamped at core 1's arena when core
                                                        // 0's was one contiguous range.
                while (z0 < zEnd)
                {
                    Address.fromLong(z0).store(0L);
                    z0 += 8L;
                }
                demandHeapHigh = zEnd;                           // record it so the else-branch keeps it re-zeroed
            }
            else
            {
                long usedData = Address.fromLong(Heap.PTR_CELL).loadLong() - demandHeapMark;   // this batch's footprint, before
                long usedCode = Address.fromLong(Heap.CODE_PTR_CELL).loadLong() - codeHeapMark;//   anything is reclaimed
                batchN += 1;
                batchDataTotal += usedData;
                batchCodeTotal += usedCode;
                if (usedData > batchDataPeak) { batchDataPeak = usedData; }
                if (usedCode > batchCodePeak) { batchCodePeak = usedCode; }
                if (LIFETIME_TRACE != false)
                {
                    if (LOAD_LOG)
                    {
                        Uart.write(Magic.bytes("  batch "));
                        VM.printDec(batchN);
                        Uart.write(Magic.bytes(": data="));
                        VM.printHex(usedData);
                        Uart.write(Magic.bytes(" code="));
                        VM.printHex(usedCode);
                        Uart.write(Magic.bytes(" cumData="));
                        VM.printHex(batchDataTotal);           // what a no-rewind run would have to hold
                        Uart.write(Magic.bytes(" cumCode="));
                        VM.printHex(batchCodeTotal);
                        Uart.putc(0x0A);
                    }
                }
                long oldPtr = Address.fromLong(Heap.PTR_CELL).loadLong();      // ZERO all heap ever used above the mark, so a reused
                if (oldPtr > demandHeapHigh)                     // block never carries a PRIOR batch's code bytes: an
                {                                                // uninitialized/OOB slot then reads 0 (a caught blr 0)
                    demandHeapHigh = oldPtr;                     // instead of stale code -> a layout-dependent wild branch.
                }                                                // Zero up to the HIGH-WATER mark (not just the previous
                if (RECLAIM_BY_GC == false)                      // the arc's switch: rewind, or let the
                {                                                //   collector decide what is still live
                    long z = demandHeapMark;                     // batch) so a bigger batch's OOB reads land on 0 too.
                    while (z < demandHeapHigh)
                    {
                        Address.fromLong(z).store(0L);
                        z += 8L;
                    }
                    Address.fromLong(Heap.PTR_CELL).store(demandHeapMark);   // reclaim the previous demo's dead code + objects
                    Heap.clearCoreFreeLists(0);                     // core 0's free-list entries are above it again
                }
                // Code-arena rewind (same batch-death model): zero every code byte the previous batches wrote
                // above the mark -- a stale JIT buffer executed through a dangling pointer is the worst kind of
                // wild branch (zeros decode as a caught udf instead) -- flush the zeroes past the I-cache, and
                // rewind the bump. A batch that spawns threads must (already) rebuild the run-trampoline; its
                // compiled methods die with its registries exactly like its heap objects.
                if (RECLAIM_CODE_BY_GC == false)
                {
                    long ocPtr = Address.fromLong(Heap.CODE_PTR_CELL).loadLong();
                    if (ocPtr > codeHeapHigh)
                    {
                        codeHeapHigh = ocPtr;
                    }
                    long cz = codeHeapMark;
                    while (cz < codeHeapHigh)
                    {
                        Address.fromLong(cz).store(0L);
                        cz += 8L;
                    }
                    Heap.publishCode(Address.fromLong(codeHeapMark), Address.fromLong(codeHeapHigh));   // drop stale I-cache lines over dead code
                    Address.fromLong(Heap.CODE_PTR_CELL).store(codeHeapMark);
                    codeRootN = 0;                                  // the code those roots belonged to is gone
                }
                if (RECLAIM_CODE_BY_GC == false)
                {
                    VM.dropJitTablesAbove(Address.fromLong(codeHeapMark));        // frame/handler entries for the dead code would
                }                                               //   ALIAS the next batch's reused addresses
                // Under code reclamation this drop must NOT happen: the entries above the mark no longer all
                // belong to dead code, and a surviving method stripped of its frame size / catch handlers is
                // a mis-unwound stack the next time an exception crosses it. The sweep retires entries per
                // swept range instead (VM.dropJitTablesIn), which is the same hygiene at method granularity.
                // Stale-root hygiene: a still-registered task Thread from a RECLAIMED batch (e.g. a sleeper
                // that never exited) now points at rewound memory -- as a conservative GC root it would
                // falsely retain whatever the NEXT batch allocates at that address. Its object is gone
                // either way; currentThread() lazily re-wraps if such a task ever asks again.
                int tt = 0;
                while (tt < VM.taskCount && RECLAIM_BY_GC == false)
                {
                    if (VM.taskThreadObj.get(tt).toLong() >= demandHeapMark)
                    {
                        VM.taskThreadObj.set(tt, ObjectReference.nullReference());
                    }
                    tt += 1;
                }
                // Phase-A cells and their lazy-method entries live in the memory just rewound. Every other
                // registry is rebuilt below, but these two were not, so the NEXT batch's dlCellOf walked
                // last batch's DynLink objects -- whose fields the rewind had zeroed -- and compared a name
                // against blob 0, reading a classfile at a wild address. (Zeroing makes it look like a
                // "class named by the empty string"; before the zero pass it would have matched stale text.)
                // They are dropped here, inside the reclaim, so a batch that runs BEFORE reclaim is armed
                // keeps its still-valid cells: the philosophers' surviving tasks dispatch through them.
                DynamicLinker.dlTab = null;
                DynamicLinker.dlN = 0;
                DynamicLinker.dlBucket = null;                        // the name index goes with the table it indexes
                LazyCompiler.lzTab = null;
                LazyCompiler.lzN = 0;
                // ... and the IMAGE-side memos that point into the code just rewound. bakeResolve caches into
                // the writer-emitted bake-stub table, which no reclaim can reach, so without this a memo
                // keeps handing out a deferral stub whose lazy index belongs to the table being dropped.
                VirtualDispatch.invalidateBakeMemos();
            }
        }
        litAnchor = null;                               // per-batch GC anchor for interned literals: the rewind
        litAnchorN = 0;                                 //   reclaimed both the literals and the anchor array
        strPool = null;                                 // ... and the string pool those anchors keep alive
        strPoolN = 0;
        VirtualDispatch.clearSiteMemos();                               // a (site, Type) answer holds within a launch only
        VM.byteArrayTibCache = Address.zero();          // the batch's [B TIB was just reclaimed with its heap
        VM.stringTypeCache = DataReference.zero();            // ... and so was the Type it is paired with: a STALE one
                                                        //   could be matched by a later Type at the same address
        MethodRegistry.rgTab = new RVMMethod[MethodRegistry.MAXREG];
        MethodRegistry.rgCount = 0;
        MethodRegistry.bdTab = null;                                   // reflection-only entries name this launch's blobs too
        MethodRegistry.bdCount = 0;
        MethodRegistry.rgIndexed = 0;                                   // the index is keyed by registry INDEX, so it must
        MethodRegistry.rgBucket = null;                                 //   be rebuilt whenever the registry is cleared
        Statics.sgTab = new RVMField[MethodRegistry.MAXREG];
        Statics.sgIndexed = 0;                                  // BESIDE the table it indexes: a watermark that
        Statics.sgBucket = null;                                //   outlives its table under-marks SILENTLY, and
                                                        //   sgCount returning to the same value would slip
                                                        //   past the "went backwards" check in buildSgIndex
        Statics.sgCount = 0;
        // THE SEED FLAGS RESET WITH THE REGISTRY THEY GUARD. A fresh statics block reads 0 everywhere, so a
        // flag left set here would skip the seed for the whole launch and leave System.out / the Integer
        // cache / int.class null -- silently. Same rule as every other watermark in this file: it resets
        // beside the table it indexes.
        BootSeeds.sdVmLock = false;
        BootSeeds.sdJio = false;
        BootSeeds.sdJla = false;
        BootSeeds.sdPrim = false;
        BootSeeds.sdPrimMask = 0;
        Relocator.relocRecording = 0;
        DynamicLinker.lkCount = 0;                                    // link stubs name utf8 inside THIS batch's blobs
        DynamicLinker.linkTrampAddr = 0L;                             //   (and the trampoline lives in reclaimable code)
        // FILLED WITH -1, because the zero default is a VALID REGISTRY INDEX. recordTailReloc (lambda and
        // method-reference thunks) did not set this field, so every such site memoised to rgTab[0] and
        // dispatched into an unrelated method -- the suite caught it as `reflective lambda thread = 0
        // (want 7)`. Both recorders set it explicitly now; this makes a third one that forgets fail safe
        // (re-resolve) rather than silently wrong.
        Relocator.rcReg = new int[Relocator.MAXRELOC];
        int rr = 0;
        while (rr < Relocator.MAXRELOC)
        {
            Relocator.rcReg[rr] = -1;
            rr += 1;
        }
        Relocator.rcAddr = CodeArray.create(Relocator.MAXRELOC);
        Relocator.rcBase = AddressArray.create(Relocator.MAXRELOC);
        Relocator.rcTail = new int[Relocator.MAXRELOC];
        Relocator.rcRecv = new int[Relocator.MAXRELOC];
        Relocator.rcStub = new long[Relocator.MAXRELOC];                    // per-site unres memo; dies with the sites it indexes
        Relocator.rcDone = CodeArray.create(Relocator.MAXRELOC);                    // per-site target last WRITTEN: see Relocator.rcDone
        Relocator.rcGate = new int[Relocator.MAXRELOC];                     // per-site lookup gate, likewise: set when each site is
        Relocator.rcGateCell = new long[Relocator.MAXRELOC];                //   recorded, so not filled here
        Relocator.rcGateDl = new long[Relocator.MAXRELOC];
        Relocator.rcGateRg = new long[Relocator.MAXRELOC * Relocator.GATE_RG];
        Relocator.rcClass = new int[Relocator.MAXRELOC];
        Relocator.rcName = new int[Relocator.MAXRELOC];
        Relocator.rcDesc = new int[Relocator.MAXRELOC];
        Relocator.rcCount = 0;
        Relocator.pcListsValid = false;                           // the patch lists index these tables: rebuild
        Relocator.pcWritten = new int[Relocator.MAXRELOC];
        Relocator.rsAddr = new long[Relocator.MAXRELOC];
        Relocator.rsBase = AddressArray.create(Relocator.MAXRELOC);
        Relocator.rsReg = new int[Relocator.MAXRELOC];
        Relocator.rsClass = new int[Relocator.MAXRELOC];
        Relocator.rsName = new int[Relocator.MAXRELOC];
        Relocator.rsCount = 0;
        ClassInitializer.clinitEntry = CodeArray.create(MAXBLOB);
        ClassInitializer.clinitCode = new long[MAXBLOB];
        ClassInitializer.clinitCodeLen = new int[MAXBLOB];
        ClassInitializer.clinitDescOff = new int[MAXBLOB];
        ClassInitializer.clinitStatic = new int[MAXBLOB];
        ClassInitializer.clinitPd = new int[MAXBLOB];
        ClassInitializer.clinitBase = AddressArray.create(MAXBLOB);
        ClassInitializer.clinitNameOff = new int[MAXBLOB];
        ClassInitializer.clinitRan = new int[MAXBLOB];
        ClassInitializer.clinitBusy = new int[MAXBLOB];
        ClassInitializer.clinitOwner = new int[MAXBLOB];
        ClassInitializer.clinitDone = new int[MAXBLOB];
        ClassInitializer.lzInitReg = new int[ClassInitializer.MAXPENDINIT];
        ClassInitializer.lzInitN = 0;
        dblToStrBuf = 0L;                               // per LAUNCH: a memo that outlived the code buffers
        dblValueOfBuf = 0L;                             //   (the boxing twins, for the same reason)
        fltValueOfBuf = 0L;
        fltToStrBuf = 0L;                               //   it names would call into swept memory
        // THE SAME RULE FOR THE CACHED HELPER BINDINGS, and the first one MEASURED wrong. objectCloneBuf holds a
        // LINK STUB, and a stub is just "resolve link entry k": lkCount went back to 0 above, so in the next
        // launch entry k named some OTHER method, and every Object.clone() ran that instead. The demo suite's
        // MessageDigest.clone handed back its receiver (copy == this), so the original and the clone shared one
        // engine -- `sha256 clone = ...FORK-WRONG` on QEMU and the Pi, while DigestProbe, booted alone with a
        // single launch, passed. The other three cache compiled bodies of this launch, the dblToStrBuf hazard.
        ProxySupport.objectCloneBuf = 0L;
        ProxySupport.objectCloneCls = DataReference.zero();
        ProxySupport.objectCloneName = DataReference.zero();
        ProxySupport.objectCloneDesc = DataReference.zero();
        ProxySupport.proxyHelperBuf = 0L;
        AnnotationInstances.annoEqBuf = 0L;
        AnnotationInstances.annoHashBuf = 0L;
        AnnotationInstances.annoStrBuf = 0L;
        ClassInitializer.lzInitDropped = 0;                              // per LAUNCH, beside the table they describe --
        ClassInitializer.lzInitWant = 0;                                 //   reportPendInitDrops prints at the end of one
        ClassInitializer.lzInitWantMax = 0;
        ClassInitializer.lzInitFullSaid = false;
        ClassInitializer.clDepOff = new int[MAXDEP];
        ClassInitializer.clDepStart = new int[MAXBLOB];
        ClassInitializer.clDepN = new int[MAXBLOB];
        ClassInitializer.clDepTop = 0;
        ClassInitializer.clinitN = 0;
        ClassInitializer.clinitFdFirst = -1;
        ClassInitializer.clinitRunFrom = 0;
        ArrayTypes.primArrTib = DataReferenceArray.create(12);         // array Types live in the (reclaimed) demand heap: recreate per batch
        ArrayTypes.primArrAdopted = new boolean[12];  // (adopted image TIBs are permanent but re-adopting is idempotent)
        ArrayTypes.refArrElem = DataReferenceArray.create(64);
        ArrayTypes.refArrTib = DataReferenceArray.create(64);
        ArrayTypes.refArrAdopted = new boolean[64];
        ArrayTypes.refArrCount = 0;
        // CLASS MIRRORS. 256 was not enough for the console launcher, which overflowed it mid-discovery
        // (`CLASS MIRROR CACHE FULL ... first overflow is org/junit/jupiter/api/parallel/ResourceLock`) --
        // and an overflow is not a capacity nuisance, it SILENTLY BREAKS IDENTITY: past the end every ask
        // mints a fresh mirror, so `getClass() == X.class` answers false while `getClass() == getClass()`
        // can still hold by luck, and stock code compares mirrors constantly. Sized for a launcher-scale
        // closure with room over: the cost is two words per entry, and only for classes actually asked.
        ClassMirrors.mirType = AddressArray.create(ClassMirrors.MAXMIRROR);     // Class mirrors: also per batch (reclaimed heap)
        ClassMirrors.mirObj = ObjectReferenceArray.create(ClassMirrors.MAXMIRROR);
        ClassMirrors.mirN = 0;
        ClassMirrors.classTibCache = DataReference.zero();
        ClassRegistry.clTab = new RVMClass[ClassRegistry.MAXCLASS];
        ClassRegistry.clCount = 0;
        ClassRegistry.clIndexed = 0;                                  // BESIDE the table it indexes: a watermark that
        ClassRegistry.clBucket = null;                                //   outlives its table under-marks SILENTLY
        // BESIDE the table it indexes, deliberately: a watermark that outlives its table under-marks
        // SILENTLY, and a fresh clTab left beside a stale ctIndexed would answer registry indices for a
        // previous launch's classes. Nulling the bucket array is what forces the rebuild.
        ClassRegistry.ctBucket = null;
        InterfaceInvocation.clIfaceReg = new int[ClassRegistry.MAXCLASS * InterfaceInvocation.MAX_DIRECT_IF];
        InterfaceInvocation.clIfaceRegN = new int[ClassRegistry.MAXCLASS];
        InterfaceInvocation.ifClosureBuf = new int[InterfaceInvocation.MAXIFM];
        InterfaceInvocation.instImaps = DataReferenceArray.create(InterfaceInvocation.MAXIMAP);
        InterfaceInvocation.instImapReg = new int[InterfaceInvocation.MAXIMAP];
        InterfaceInvocation.instImapDone = new boolean[InterfaceInvocation.MAXIMAP];
        InterfaceInvocation.instImapDecl = new int[InterfaceInvocation.MAXIMAP][];
        InterfaceInvocation.instImapDeclN = new int[InterfaceInvocation.MAXIMAP];
        int dm = 0;
        while (dm < InterfaceInvocation.MAXIMAP)
        {
            InterfaceInvocation.instImapDecl[dm] = null;                    // fill explicitly: see buildSgIndex
            dm += 1;
        }
        InterfaceInvocation.instImapN = 0;
        TibBuilder.lambdaTibRoots = DataReferenceArray.create(TibBuilder.MAXLAMBDATIB);
        TibBuilder.lambdaTibVtCap = new int[TibBuilder.MAXLAMBDATIB];
        TibBuilder.lambdaTibFilled = new boolean[TibBuilder.MAXLAMBDATIB];    // beside the table it tracks: a latch that outlives
                                                        //   its array would skip a TIB that needs filling
        TibBuilder.lambdaHostBase = AddressArray.create(TibBuilder.MAXLAMBDATIB);
        TibBuilder.lambdaHostNameOff = new int[TibBuilder.MAXLAMBDATIB];
        TibBuilder.lambdaTibRootN = 0;
        // The proxy and annotation TIB tables are rooted ONLY through lambdaTibRoots, which was just dropped,
        // and they find entries by raw Type ADDRESS. Kept, an entry outlives the Type it names: once the GC
        // frees that Type its address can come back as a different class's, and the lookup would hand out a
        // TIB built for another interface, whose slots call the anno*/proxy helper bodies reset per launch
        // (see objectCloneBuf). Not measured failing -- the suite resets only in its early demos, before
        // either table fills -- but wrong by construction, so they reset beside the root they depend on.
        ProxySupport.proxyN = 0;
        AnnotationInstances.annoTibN = 0;
        // The same for a cached registry Type: it names a class of the registry this method replaces.
        // MEASURED stable in the suite (thrTypeCache filled at generation 4 and matched a fresh lookup to the
        // end; the Cloneable/Serializable pair never filled), so this is hygiene, not a fix.
        thrTypeCache = DataReference.zero();
        ArrayTypes.cloneableTypeCache = DataReference.zero();
        ArrayTypes.serializableTypeCache = DataReference.zero();
        ArrayTypes.arrayIfaceScanned = 0;
        pcBase = AddressArray.create(MAXPARSECACHE);
        pcTab = new ClassFileView[MAXPARSECACHE];
        pcN = 0;
        // gView is NOT cleared here. The seven statics it replaced (P2-2) were never reset either: they kept naming
        // the last blob across a reset until the next parse re-pointed them, and code between the two may read
        // them. Keeping the view keeps that behaviour exactly; the arrays it holds stay alive only until then.
        FieldRegistry.fldTab = new RVMField[FieldRegistry.MAXFIELD];
        FieldRegistry.fldIndexed = 0;                                 // beside the table it indexes, as sgIndexed is
        FieldRegistry.fldBucket = null;
        FieldRegistry.fldCount = 0;
        VtableLayout.vtClassBase = AddressArray.create(VtableLayout.MAXVT);
        VtableLayout.vtClassOff = new int[VtableLayout.MAXVT];
        VtableLayout.vtNameBase = AddressArray.create(VtableLayout.MAXVT);
        VtableLayout.vtNameOff = new int[VtableLayout.MAXVT];
        VtableLayout.vtDescOff = new int[VtableLayout.MAXVT];
        VtableLayout.vtSlot = new int[VtableLayout.MAXVT];
        VtableLayout.vtBuf = new long[VtableLayout.MAXVT];
        VtableLayout.vtCount = 0;
        VtableLayout.vtBucket = null;                                // the class-name index is keyed by table INDEX: rebuild
        VtableLayout.gvTab = new VtSlot[VtableLayout.MAXMV];
        int gvi = 0;
        while (gvi < VtableLayout.MAXMV)                             // pre-fill: slots are overwritten (reused) per class
        {
            VtableLayout.gvTab[gvi] = new VtSlot();
            gvi += 1;
        }
        InterfaceInvocation.ifBase = AddressArray.create(InterfaceInvocation.MAXIFM);
        InterfaceInvocation.ifNameOff = new int[InterfaceInvocation.MAXIFM];
        InterfaceInvocation.ifDescOff = new int[InterfaceInvocation.MAXIFM];
        InterfaceInvocation.ifDecl = AddressArray.create(InterfaceInvocation.MAXIFM);
        InterfaceInvocation.ifCount = 0;
        InterfaceInvocation.ifBucket = null;                                // the run index is keyed by entry INDEX: cleared with it
        pdBase = AddressArray.create(MAXBLOB);
        pdNameOff = new int[MAXBLOB];
        pdDone = new int[MAXBLOB];
        pdDoneB = new int[MAXBLOB];
        pdNeedsString = new boolean[MAXBLOB];
        pdSuperOff = new int[MAXBLOB];
        pdIfOff = new int[MAXBLOB * InterfaceInvocation.MAX_DIRECT_IF];
        pdIfN = new int[MAXBLOB];
        pdCount = 0;
        // THE HIT LISTS ARE KEYED BY BLOB INDEX, and this is a per-LAUNCH reset: blob 7 of the next program
        // is a different class, and pendN restarts at 0 so a stale hvTo would be HIGHER than pendN and
        // extendHits would never run again -- silently marking nothing. Dropped here, with the arrays
        // rebuilt lazily.
        MethodTableCache.hvQ = null;
        MethodTableCache.hvCodeIdx = null;
        MethodTableCache.hvLen = null;
        MethodTableCache.hvTo = null;
        Closure.collectedTab = new long[Closure.REACHTAB];
        Closure.colN = 0;
        Closure.markFullClear = true;
        Closure.pdPendTo = new int[MAXBLOB];
        Closure.pdVirtTo = new int[MAXBLOB];
        Closure.pdPendEpoch = new int[MAXBLOB];
        Closure.pdVirtEpoch = new int[MAXBLOB];
        Closure.pdDfltTo = new int[MAXBLOB];
        Closure.pdSeeded = new int[MAXBLOB];
        Closure.pdSeedC = new int[MAXBLOB];
        Closure.pendPullTo = 0;
        pnIndexed = 0;
        pnBucket = null;                                 // rebuilt by the next probeAll; blobs are all new
        // THE PROBE WATERMARK AND THE DEP LIST IT APPENDS TO ARE ONE PIECE OF STATE, reset together. probeAll
        // no longer clears dpCount itself (it probes only [pdProbedTo, pdCount) and appends), so a fresh
        // dpOwner/dpOff/dpBase left beside a stale dpCount would have ready() reading zeroed deps as real
        // ones -- and a stale pdProbedTo above the new pdCount would skip every blob of the next program,
        // silently, which is exactly the shape that cost the virt memo half a closure.
        pdProbedTo = 0;
        dpCount = 0;
        stringPdIndex = -1;
        dpOwner = new int[MAXDEP];
        dpOff = new int[MAXDEP];
        dpBase = AddressArray.create(MAXDEP);
        indyNameUsed = 0;                                // interned names live for the whole batch:
                                                 //   a pend outlives the probeAll that made it
        pdLen = new int[MAXBLOB];
        gEntryBlob = 0L;                                 // no reachability mark unless a caller sets an entry
        gRootBlob = 0L;
        gStubBlob = 0L;
        markActive = 0;
        Closure.reachCode = new long[Closure.MAXREACH];
        Closure.reachTab = new long[Closure.REACHTAB];
        Closure.reachN = 0;
        Closure.markFullClear = true;                           // a fresh table: see Closure.markFullClear
        Closure.reachRefused = 0;                               // resets BESIDE the table it counts, like every
                                                        //   other watermark here
        VM.jitFrameCount = 0L;                           // a demo's JIT'd frames/handlers are dead once it returns;
        VM.jitLocalCount = 0L;                           // reset the local table IN LOCKSTEP with the frame table (parallel
                                                         // {codeStart,codeEnd,gcMapWord}); addJitFrame guards only on
                                                         // jitFrameCount, so a stale jitLocalCount would keep growing past
                                                         // JIT_FRAME_MAX and overrun jitLocalTable into adjacent heap.
        VM.jitHandlerCount = 0L;                         // resetting per batch stops them accumulating past the caps
        StubFrames.registerTrampFrames();                           // ... but the trampolines outlive the table (see there)
        if (RECLAIM_BY_GC != false && reclaimArmed != 0)
        {
            // Every registry above has just been replaced, so the previous batch's classes, TIBs, Types,
            // itables, statics blocks and classfile copies are now unreachable -- collect them the ordinary
            // way. Runs HERE, at the end of the reset, rather than where the rewind used to be: before the
            // registries are cleared those objects are still reachable and nothing would be freed.
            Magic.gc();
            if (LIFETIME_TRACE != false)
            {
                Uart.write(Magic.bytes("  reclaim-by-gc: top="));
                VM.printHex(Address.fromLong(Heap.PTR_CELL).loadLong() - demandHeapMark);   // heap grown above the watermark
                Uart.write(Magic.bytes(" freedThisPass="));
                VM.printHex(VM.reclaimed);
                Uart.write(Magic.bytes(" live="));      // the survivors -- if this stays small, the heap's
                VM.printHex(VMGc.liveBytes);            //   high water mark is garbage, not retention
                Uart.write(Magic.bytes(" codeOnly="));  // blocks kept alive ONLY by a code immediate: the
                VM.printHex(VMGc.codeOnly);             //   hazard the batch rewind has been covering for
                Uart.write(Magic.bytes(" codeRoots="));
                VM.printHex(Loader.codeRootN);
                Uart.write(Magic.bytes(" codeArena="));  // how much of the arena is actually in use
                VM.printHex(Address.fromLong(Heap.CODE_PTR_CELL).loadLong() - codeHeapMark);
                Uart.write(Magic.bytes(" codeLive="));  // reachable compiled code / total ever compiled:
                VM.printHex(VMGc.codeLive);             //   the ratio that decides whether reclaiming code
                Uart.putc(0x2F);                        //   is worth building at all
                VM.printHex(VMGc.codeUsed);
                // Fragmentation survey: the arena sits ~4x its live set, and the question compaction exists
                // to answer is WHY. If the free list holds most of the gap but every block is smaller than
                // what allocations ask for, the space is fragmented and moving code (or merging neighbours)
                // recovers it. If instead allocations are being served from the free list and the arena
                // merely reached this size at peak demand, compaction recovers nothing.
                Heap.surveyCodeFree();
                Uart.write(Magic.bytes(" free="));       // total free bytes / how many blocks hold them
                VM.printHex(Heap.codeFreeBytes);
                Uart.putc(0x2F);
                VM.printHex(Heap.codeFreeBlocks);
                Uart.write(Magic.bytes(" maxFree="));    // the largest single free block: free >> maxFree
                VM.printHex(Heap.codeFreeMax);           //   means the space exists but cannot be handed out
                Uart.write(Magic.bytes(" tiny="));       // blocks under 256B -- too small for most methods
                VM.printHex(Heap.codeFreeTiny);
                Uart.write(Magic.bytes(" reuse="));      // allocations served from the list vs forced to
                VM.printHex(Heap.codeReuseCount);        //   grow the arena; the bump count IS the cost of
                Uart.putc(0x2F);                         //   fragmentation, in allocations
                VM.printHex(Heap.codeBumpCount);
                Uart.write(Magic.bytes(" bumpB="));      // and in bytes of arena those allocations added
                VM.printHex(Heap.codeBumpBytes);
                Uart.write(Magic.bytes(" stale="));      // slots caught pointing at freed code, split by
                VM.printDec((int) VMGc.rawStaleSeen);     //   whether they were still stale a collection later
                Uart.putc(0x2F);
                VM.printDec((int) VMGc.stalePersisted);
                Uart.putc(0x2F);
                VM.printDec((int) VMGc.staleRewritten);
                Uart.write(Magic.bytes(" lgLive="));     // the region's live set, and what its trim
                VM.printHex(VMGc.largeLive);         //   has handed back over the run
                Uart.write(Magic.bytes(" lgTrim="));
                VM.printHex(VMGc.largeTrimmed);
                Uart.write(Magic.bytes(" ovf="));
                VM.printDec((int) VMGc.overflows);
                Uart.write(Magic.bytes(" largeTop="));    // the large region: how far it has grown, and
                VM.printHex(Address.fromLong(Heap.LARGE_PTR_CELL).loadLong() - Heap.LARGE_BASE);
                Uart.write(Magic.bytes(" reuse="));       //   whether requests are finding blocks there
                VM.printDec((int) Heap.largeReuse);
                Uart.putc(0x2F);
                VM.printDec((int) Heap.largeBump);
                Uart.write(Magic.bytes(" merged="));      // blocks folded into a neighbour by the last
                VM.printHex(Heap.codeMergedBlocks);       //   coalescing pass, and the bytes they carried
                Uart.putc(0x2F);
                VM.printHex(Heap.codeMergedBytes);
                Uart.putc(0x0A);
                // Would size classes help? Two readings decide it. The request histograms say what rounding
                // to 16/32/64/... would cost, and bumpWhy says whether the allocations that grew an arena
                // met a genuine shortage (noSpace -- no policy invents bytes) or enough bytes in the wrong
                // shape (wrongShape -- exactly what size classes prevent by construction).
                Uart.write(Magic.bytes("  reqCode:"));
                Heap.printHist(Heap.codeHist());
                Uart.putc(0x0A);
                Uart.write(Magic.bytes("  reqData:"));
                Heap.printHist(Heap.dataHist());
                Uart.putc(0x0A);
                Uart.write(Magic.bytes("  bumpWhy code noSpace="));
                VM.printDec((int) Heap.codeBumpNoSpace);
                Uart.write(Magic.bytes(" wrongShape="));
                VM.printDec((int) Heap.codeBumpWrongShape);
                Uart.write(Magic.bytes(" | data noSpace="));
                VM.printDec((int) Heap.dataBumpNoSpace);
                Uart.write(Magic.bytes(" wrongShape="));
                VM.printDec((int) Heap.dataBumpWrongShape);
                Uart.putc(0x0A);
                // ... and the same split in BYTES, which is what the arena high-water is actually made of.
                // wrongShapeBytes is the ceiling on what a size-class allocator could have served from the
                // free list instead of growing the arena.
                Uart.write(Magic.bytes("  bumpBytes code noSpace="));
                VM.printHex(Heap.codeNoSpaceBytes);
                Uart.write(Magic.bytes(" wrongShape="));
                VM.printHex(Heap.codeWrongShapeBytes);
                Uart.write(Magic.bytes(" | data noSpace="));
                VM.printHex(Heap.dataNoSpaceBytes);
                Uart.write(Magic.bytes(" wrongShape="));
                VM.printHex(Heap.dataWrongShapeBytes);
                Uart.putc(0x0A);
                CodeEdges.report();                      // the code->code edge set compaction must rewrite,
                                                         //   re-decoded and checked against what was emitted
                Heap.printGrowthAges();
                CodeCompact.report();                    // ... and what moving it would recover, plus whether
                                                         //   every reference to it could be rewritten
                Heap.printLargeFails();                  // the state AT each large failure -- what a data-heap
                Heap.resetAdjSampling();                 //   compactor would have had to work with. Sampling
                                                         //   restarts per batch: the cap was being spent on
                                                         //   the early demos, and the interesting failures
                                                         //   (Lisp's doubling loop) come last.

            }
        }
    }

    static long gEntryBlob;                      // entry method's blob (0 => mark disabled)
    static long gRootBlob;                       // a defineClass'd blob: EVERY method is a root (see rootBlob)
    static long gStubBlob;                       // an incrementally loaded blob: STUB its virtuals (see stubBlob)
    static int markActive;                       // 1 once markReachable has run (compileClass then filters)


    /** The task whose {@link #defineFromBytes} classes are a stoppable program's, or -1 (VMNatives.runDefining). */
    static int stoppableDefiner = -1;


    /**
     * Parse a blob's constant pool and advance {@code gp} to its methods table ({@code gView.methodsStart}, which
     * the view decoded with the rest of the header) — the lightweight parse the reachability mark needs, without {@link #parseFields}'
     * field-layout + statics allocation.
     */
    static void parseForMethods(Address base, int len)
    {
        parseConstPool(DataReference.fromAddress(base), len);
        gp = gView.methodsStart;                        // the view decoded the header and skipped the fields once
    }


    /**
     * CUMULATIVE load cost across every batch, for answering "what is slowing this boot down".
     *
     * <p>Separate from the per-pass accumulators below, which {@link Closure#markReachable} RESETS each batch: a
     * launcher boot runs dozens of batches, so per-batch numbers say nothing about where the whole boot
     * went. Printed as ONE line rather than per batch, because {@code LOAD_PROFILE}'s per-batch output is
     * itself seconds of UART traffic at 115200 baud -- the profiler would be measuring its own printing,
     * which is exactly how the per-class `load` lines hid ~3.5s of the demand-load arc.
     */
    /**
     * The "clinit" span split into its parts, because the LABEL WAS MISLEADING: {@code runClinits} does
     * almost nothing now (every initializer defers to the lazy barrier), and the time is in the three REFILL
     * passes ahead of it -- each of which walks everything accumulated so far, every batch.
     */
    static long cumRfImap, cumRfSynth, cumRfArr, cumSeeds, cumRunCl;
    // AND THE WORK THE THREE ABOVE CANNOT SEE, which is why these exist: those tally SLOT READS, and 3.3M of
    // them over a launcher boot cannot account for `imap`'s 1,366ms (~68 cycles a read on a 166MHz core,
    // against the handful a load-compare-increment costs). What they miss is what happens INSIDE a still-0
    // slot: `defaultBySig` probes the registry once per CLOSURE INTERFACE, and each probe FNV-folded the
    // interface's class name AND the method name, byte by byte, to compute a bucket index.
    //
    // THEY RANKED IT IN ONE RUN, and against the four candidates already counted: over a demo-suite boot the
    // class-name bytes were 916k, the method-name bytes 415k, and every slot read put together 61k. `chain`
    // read 0k -- the bucket those probes reach is EMPTY, because a permanently-unfillable slot is exactly a
    // search that must fail -- so computing the key WAS the search. The class half is cached now
    // ({@link RVMClass#nameHash}), which is why `hcls` should read 0: a counter that stays zero is the
    // assertion that the cache fires, not noise.
    // THE SEED BLOCK, which the same reading says is now the LARGEST item in this phase -- 1,369ms
    // cumulative at batch 209, against imap's 603ms. It is SIX functions run unconditionally on EVERY batch,
    // and between them they call `staticSlotOf` FIFTEEN times; that is a linear walk of all `sgCount` static
    // registry entries with two Utf8 compares an entry, and sgCount grows with every class loaded all boot.
    // THIRTEENTH instance of this file's most common defect, and the first one found sitting inside a
    // function that had never been split.
    // THE `unres` ARM, now the second-largest item in a batch (507.742ms of callT's 1,300.917ms, 39%). It
    // re-derives two immutable facts per still-unresolved site per batch: `isDenylisted` (SIXTY non-inlined
    // utf8HasPrefix calls -- this VM's baseline compiler does not inline) and `linkStubFor`'s linear scan of
    // every stub minted so far. Which of the two dominates is a question for counters: indexing the SCAN was
    // tried once in the patch arc and MEASURED to buy nothing, which is evidence the prefix chain is the
    // half that costs -- but that measurement was taken against a different closure, so it is checked here
    // rather than trusted.
    static int pcUnresMemo;                             // sites answered from rcStub (the arm skipped entirely)
    static int pcUnresDeny;                             // ... and sites that still ran the full arm
    static int pcDenySteps;                             // utf8HasPrefix calls (ALL callers; isDenylisted dominates)
    static int pcStubSteps;                             // linkStubFor scan entries compared
    // `statT` -- the static-reloc half of `patch`, 463.914ms cumulative at batch 209 and the one top-level
    // split that had NEVER been looked inside. The loop is short enough to read: for every static site from
    // rsStart, `globalStaticByRef` walks ALL `sgCount` registry entries with TWO utf8EqAt an entry, and
    // `patchRelocs` calls it with rsStart 0 at every batch end -- so every site pays a full-table walk again
    // on every batch, while sgCount grows with each class loaded.
    //
    // COUNTED BEFORE BEING BELIEVED, because the unres arm one increment ago ranked the same way by reading
    // -- index the scan -- and the counters put the prefix chain 5.6x ahead of it. Reading names a shape; it
    // cannot say whether the cost is the walk's DEPTH (an index fixes it) or the number of SITES (an index
    // does nothing). WHAT THE SUITE ANSWERED, at batch 64: `ps:n=136 steps=37k miss=136 tab=280` --
    // **every single site MISSES**, 136 of 136, each walking all 280 entries to say so, and `patchRelocs`
    // asks again next batch because a class loaded later must still resolve. So the depth IS the cost, the
    // unres arm's memo does NOT transfer (the negative answer is not immutable), and the remedy is to make
    // the negative cheap rather than to cache it.
    //
    // AND THE COUNTERS CLOSED THE ACCOUNT, which is the check that says nothing else is hiding in there:
    // 37k sgTab steps (two utf8EqAt each) plus ~26k regBySigU steps (one each) is ~100k compares against a
    // measured 46.9ms, i.e. ~0.47us a compare -- the same per-compare cost the unres arm's `deny=116k`
    // showed. `statT` is these two scans and essentially nothing else.
    static long psSites;                                // static reloc sites resolved (cumulative, all batches)
                                                        //   lands on an EMPTY bucket, as the imap fix's chain does)
    static long psMiss;                                 // ... sites that found NOTHING (a full-table walk each)
    static long syObjSteps;                             // objectClassIndex scan steps (all three callers)
    static int rfHashSteps;                             // ... CLASS-name bytes still folded (0 = cache firing)

    static int cumBatches;
    static long cumMark, cumProbe, cumA, cumB, cumPatch, cumClinit, cumAll;

    /**
     * PHASE B's SPLIT. `B` is the largest item in the dominant batch -- 23,346ms of batch 1's 47,566ms
     * (49%), and batch 1 is 86% of ALL load time -- and it had never been sub-split, so every reading of it
     * was a guess. The earlier perf arc settled three wrong guesses about `patch` by splitting the timer
     * rather than reading the code; this is the same move on the one phase that pass never opened.
     *
     * <p>The split asks the SHAPE question. The loop rescans all pdCount blobs on every pass
     * (`while (remainingB > 0) { for each blob: if (!done && superReadyB(i)) ... }`), which is the
     * O(everything-loaded) pattern this file says to suspect first. `bWorkT` is time INSIDE loadBodies, so
     * B minus that is the scan; `bPasses` and `bChecks` say whether the rescan is really quadratic or
     * whether the dependency order settles it in a pass or two.
     */
    private static long bWorkT;                          // cumulative ticks inside loadBodies, this batch
    private static int bPasses;                          // outer repeats of the phase-B loop
    private static int bChecks;                          // superReadyB probes (the rescan's real cost)

    /**
     * ...AND THE SPLIT INSIDE loadBodies, because the first split refuted the obvious answer. Phase B's
     * rescan -- the O(everything-loaded) shape this file says to suspect first, and which the loop plainly
     * has -- measured 493ms of 35,536ms: FOUR passes, 5,368 checks, 1.4%. The other 98.6% is real per-blob
     * work, ~26ms x 1342 blobs, so the question moves inside.
     */
    private static long lbParseT;                        // parseConstPool + parseFields + parseVtable
    private static long lbClinitT;                       // runClinit (captures the initializer body)
    private static long lbCompileT;                      // compileClass (bodies + deferral stubs + fillTib)
    private static long lbRegT;                          // registerAll + fillClassVtBuf

    /**
     * ...AND THE SPLIT INSIDE compileClass, which the loadBodies split named at 70% of phase B (27,853ms).
     * Five terms, because there are five candidates and guessing between them is what this arc keeps paying
     * for: the method WALK; PLACE ({@code sizeMethod}, which DRY-RUN COMPILES anything not deferred -- and
     * {@code <init>} is never deferred, so every constructor is compiled TWICE); {@code fillTib}; EMIT (the
     * real compile, or a deferral stub); and PUBLISH.
     *
     * <p>Publish gets its own term for a specific reason: {@code compileClass} ends with
     * {@code Heap.publishCode(Heap.CODE_BASE, <arena pointer>)} -- I-cache maintenance over the WHOLE code
     * arena, once PER CLASS. 1342 calls over a region that grows all batch is the O(everything-loaded) shape,
     * and `pubT` was already the largest CUMULATIVE item late in the earlier profile (2,616ms by batch 26).
     * That makes it a suspect, which is exactly why it is measured here rather than acted on.
     */
    private static long ccSeedT;                         // the method walk (findCode / addMethod / defer)
    private static long ccPlaceT;                        // sizeMethod per method (dry-run compile when not deferred)
    private static long ccTibT;                          // fillTib
    private static long ccEmitT;                         // emitMethod per method (real compile or stub)
    private static long ccPubT;                          // publishEmitted: the bodies this compile wrote

    /**
     * ...AND THE SPLIT INSIDE `parse`, the second item in phase B (9,614ms, 24%). Three passes run per blob
     * -- parseConstPool, parseFields, parseVtable -- so the first question is which.
     *
     * <p>The second is whether the CONSTANT-POOL CACHE is working. It exists (RTA, phase A and phase B all
     * re-parse the same blob, so the copy+parse is memoised) but its lookup is a LINEAR SCAN over pcBase,
     * and pcN grows to the blob count -- 1342 in batch 1. That is the per-item scan over a table that grows
     * all boot, the shape this file says to suspect first and which has been the answer six times. It is
     * MEASURED here rather than assumed, because the last two things suspected on shape alone (phase B's
     * rescan, publishCode) came in at 1.4% and 2.4%.
     */
    private static long pParseCpT;                       // parseConstPool
    private static long pFieldsT;                        // parseFields
    private static long pVtableT;                        // parseVtable
    private static int pcCalls;                          // parseConstPool invocations this batch
    private static long pcSteps;                         // total linear-scan steps across those calls
    private static int pcHits;                           // ... of which found a cached parse


    /** The SETUP before the round loop, which no sub-timer covered -- ~417ms of a 712ms mark. */
    static long mrReset, mrAlloc, mrEntry, mrRoot, mrExc;


    /** pd index of the loaded blob whose this-class name equals the Utf8 at {@code base+off}, or -1. */
    private static long fpCalls;                         // findPdByName calls this batch
    private static long fpSteps;                         // ... and blob entries compared across them
    // The OTHER growing terms inside the same lookup, counted so the split is measured rather than argued:
    // both registry tiers walk a NAME-keyed bucket, and a hot name (`<init>`, `run`, `get`) chains one entry
    // per class that declares it -- so the chain grows with the registry all boot, exactly like the scans
    // this file has already had to index seven times.
    static long t1Steps, t2Steps, dlSteps;

    /**
     * The pd index of the blob named at {@code (base, off)}, or -1.
     *
     * <p>PROBED, not scanned. This was a linear walk of all {@code pdCount} blobs, and
     * {@link DynamicLinker#globalBufByRef}'s super-chain tier calls it once for the ref class plus once per superclass
     * LEVEL, for every site that misses the direct tier -- and those sites never memoise, so they re-resolve
     * on every batch while {@code pdCount} grows all boot. Eighth instance of this file's most common defect.
     *
     * <p>It was the SECOND of two growing terms in the same lookup, and only measurement got the order right:
     * keyed on the method name alone, the registry bucket was ~60 entries per resolve and 156k steps against
     * this one's 704k -- but the registry chain cost ~650ns a step and this ~67ns, so the registry was the
     * bigger half and was fixed first. With it gone this became essentially all of what remained.
     *
     * <p>Reuses {@code pnBucket}, the index {@link Closure#buildNameIndex} already builds for
     * {@code nameRegistered} -- same key, same table, nothing new to invalidate.
     *
     * <p>THE LOWEST MATCHING INDEX STILL WINS, which the scan gave for free and a bucket chain does not:
     * {@code buildNameIndex} head-inserts in ascending order, so the chain runs DESCENDING. The chain is
     * therefore searched for the MINIMUM rather than stopped at the first hit. (Blobs added since the index
     * was built all have indices above {@code pnIndexed}, so they can only win when the indexed half misses
     * -- which is why the tail scan runs second rather than being folded in.)
     */
    static int findPdByName(Address base, int off)
    {
        fpCalls += 1;
        if (pnBucket == null)                            // no index yet (fresh resetLoader): scan, as before
        {
            int j = 0;
            while (j < pdCount)
            {
                fpSteps += 1;
                if (utf8EqAt(base, off, pdBase.get(j), pdNameOff[j]))
                {
                    return j;
                }
                j += 1;
            }
            return -1;
        }
        int best = -1;
        int k = pnBucket[Utf8.utf8Hash(base, off) & (PVTAB - 1)];
        while (k >= 0)
        {
            fpSteps += 1;
            if ((best < 0 || k < best) && utf8EqAt(base, off, pdBase.get(k), pdNameOff[k]))
            {
                best = k;
            }
            k = pnNext[k];
        }
        if (best >= 0)
        {
            return best;
        }
        int i = pnIndexed;                               // added since the index was built; not hashable yet
        while (i < pdCount)
        {
            fpSteps += 1;
            if (utf8EqAt(base, off, pdBase.get(i), pdNameOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }


    /**
     * Power of two; chained, so an undersized table only lengthens chains -- and it WAS undersized by a
     * factor of 33. A launcher batch pends 67,889 signatures against 2048 buckets, so every bucket held ~33
     * entries and matchLevelCached walked all of them PER METHOD PER LEVEL VISIT, running two utf8EqAt string
     * compares on each one that is in range. 25,129 level visits x ~20 methods x ~33 entries is tens of
     * millions of string compares, which is where `virt` went.
     *
     * <p>Sized for the pend counts actually seen (MAXPEND is 262,144) so chains are ~1: 64K ints is 256KB,
     * in line with pvNext, which is already an int per pend entry. A hash table's SIZE cannot change which
     * entries match -- only how many are examined -- so unlike the previous attempt at `virt` this makes no
     * claim about work being skippable.
     */
    static final int PVTAB = 65536;


    /** Length of the blob at address {@code base} (matched against the pending-blob table). */
    static int blobLenOf(Address base)
    {
        int b = 0;
        while (b < pdCount)
        {
            if (pdBase.get(b).EQ(base))
            {
                return pdLen[b];
            }
            b += 1;
        }
        return 0;
    }


    /** True if the class name at {@code off} in {@code base} starts with {@code prefix}. */
    static boolean utf8HasPrefix(Address base, int off, byte[] prefix)
    {
        pcDenySteps += 1;
        int len = u2(base.plus(off));
        if (len < prefix.length)
        {
            return false;
        }
        int k = 0;
        while (k < prefix.length)
        {
            if (u1(base.plus(off + 2 + k)) != (prefix[k] & 0xFF))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /**
     * DENYLIST (#43): subtrees the metal environment fundamentally lacks -- indy/MethodHandle (intrinsified,
     * never used), foreign memory, the module/class-loader/service-loader machinery, filesystem, reflection,
     * logging, security. Stock java.base references them only from never-executed cold paths (e.g. a literal
     * regex match touches none of them), so pruning them from the demand-load closure keeps big classes like
     * java.util.regex.Pattern under MAXBLOB. A call that DOES reach a pruned class traps loudly (patchRelocs
     * points it at VM.denylistTrap) instead of wild-branching. Keep in sync with writer.ReachScan.DENY.
     */
    static boolean isDenylisted(Address base, int off)
    {
        // The locale data layer (the locale-provider arc, 2026-10-24): Calendar's constructor reads the first day
        // of the week through CalendarDataUtility, and Date.toString names its zone through TimeZoneNameUtility.
        // The provider reaches its compiled bundles (sun/text/resources -- the seed's java.base classes, embedded
        // like the rest) and the provider interfaces (java/util/spi). A DENIED class literal answers null, which
        // is how Calendar.getInstance reached ConcurrentHashMap.get(null) as an NPE. (sun/util/ itself is no
        // longer denied at all -- see below. Keep in sync with writer.ReachScan.isDenied.)
        if (utf8HasPrefix(base, off, Magic.bytes("sun/text/resources/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/util/spi/"))
                // java/text and its provider interfaces (increment 2): SimpleDateFormat / DateFormat run stock over
                // the same provider layer. And all of sun/text (increment 4): BreakIterator runs stock, on
                // RuleBasedBreakIterator and its rule data. And jdk/internal/icu (2026-10-25): java.text.Normalizer,
                // java.text.Bidi and regex CANON_EQ run stock on ICU, its data files (nfc.nrm, nfkc.nrm,
                // uprops.icu, ubidi.icu) copied to /lib/java.base like the other java.base resources.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/text/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/icu/")))
        {
            return false;
        }
        // Historical ALLOW list from when java/lang/invoke/ was denied as a prefix: VarHandle (java.net.Socket's
        // `state`/`in`/`out` fields) and the classes below were narrowed out of it one by one. The prefix deny is
        // GONE (the java.lang.invoke arc runs stock invoke), so for those classes this clause is now redundant;
        // it still matters for java/lang/ClassLoader and as the record of why each one was first allowed.
        if (utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/VarHandle"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/MethodHandles"))
                // java.lang.invoke arc, increment 0 (2026-10-26): STOCK MethodType (the prefix covers
                // MethodTypeForm, which it needs). Pure shape computation plus an intern table; constructing one
                // reaches neither LambdaForm nor Invokers. Keep in sync with writer.ReachScan.isDenied.
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/MethodType"))
                // Stock VarHandle.<clinit> reads MethodHandleStatics.UNSAFE. NOT covered by the
                // "MethodHandles" prefix above -- that stops at the lower-case 's', so MethodHandleStatics
                // was DENIED and its UNSAFE read NULL, which is the NPE stock VarHandle's initializer died
                // of. Overlaid, because its real <clinit> pulls java/lang/reflect/ClassFileFormatVersion and a
                // ClassFileDumper that writes to a filesystem.
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/MethodHandleStatics"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/invoke/MhUtil"))
                // java/lang/ClassLoader is STOCK (overlaid from the JDK 26 source, natives Java over the VM's one
                // class namespace -- jdk.internal.misc.MetalLoaders), and so is jdk/internal/loader below it.
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/ClassLoader"))
                // NARROWED OUT of the java/text/ denial: joe-ng OVERLAYS BreakIterator (whitespace line
                // breaking, no locale tables), so the reason for the broad denial -- stock's factory going
                // through the java/text/spi/ provider machinery -- does not apply to it. Without this the
                // call site is trap-wired at PATCH TIME and the overlay is never reached: a denied class is
                // denied before any link stub gets a chance to resolve it.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/BreakIterator"))
                // NARROWED OUT of the same denial, and for the same reason one layer along: stock
                // java/util/Formatter is 5,036 lines of PURE LOGIC with ZERO natives, so it runs STOCK (the
                // 539-line overlay that used to shadow it is deleted) -- and the ONLY denied class its
                // numeric conversions reach is DecimalFormatSymbols, for the four ASCII symbols. That one IS
                // overlaid, because its whole job is reading locale data through LocaleProviderAdapter ->
                // ResourceBundle -> the module/service machinery. Without this line the call site is
                // trap-wired at PATCH TIME and the overlay is never consulted.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/DecimalFormatSymbols"))
                // ParsePosition: two ints and accessors, no dependencies. Stock Locale.forLanguageTag parses
                // with `new ParsePosition(0)`, so while it was denied every language tag halted the VM
                // (UNRESOLVED NEW). It is a data holder, not the locale-data machinery the denial exists for.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/ParsePosition"))
                // Stock java/text/DecimalFormat and the PURE-LOGIC classes it is built from: its supertypes
                // NumberFormat and Format, FieldPosition, DigitList, DontCareFieldPosition, ParseException,
                // AttributedCharacterIterator (Format.Field extends its Attribute) and
                // CharacterIteratorFieldDelegate. While denied, `new DecimalFormat(pattern)` was a CTOR SKIPPED
                // with every field 0 -- a silent wrong answer. None of these reads locale data: the symbols come
                // from the overlaid DecimalFormatSymbols. NumberFormat's locale FACTORIES (getInstance(Locale),
                // getNumberInstance, ...) still go through java/text/spi and the provider adapter, which stay
                // denied, so they trap loudly rather than answer.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/DecimalFormat"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/NumberFormat"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/Format"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/FieldPosition"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/DigitList"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/DontCareFieldPosition"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/ParseException"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/AttributedCharacterIterator"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/CharacterIteratorFieldDelegate"))
                // ... and the last two of that closure (read off the classes' constant pools rather than found
                // one boot at a time): StringBufFactory (Format's buffer abstraction) and AttributedString
                // (formatToCharacterIterator). java/text/spi stays denied, deliberately.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/StringBufFactory"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/AttributedString"))
                // DateFormatSymbols is OVERLAID (from the JDK 26 source): only its locale-data reads are replaced,
                // and they read the REAL generated CLDR bundles java.base ships -- sun/text/resources/cldr/
                // FormatData (root) and FormatData_en, plain ListResourceBundles with no natives -- instead of
                // going through the provider adapter. Formatter's %tb/%tA/%tp reach it (System.Logger's header).
                || utf8HasPrefix(base, off, Magic.bytes("java/text/DateFormatSymbols"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/text/resources/cldr/FormatData"))
                // Stock MessageFormat (System.Logger's {0} parameters): pure pattern logic. A Number argument formats
                // through NumberFormat.getInstance(locale), which the NumberFormat overlay serves; the Date and list
                // element paths (DateFormat, SimpleDateFormat, ListFormat, ChoiceFormat) stay denied and trap.
                || utf8HasPrefix(base, off, Magic.bytes("java/text/MessageFormat"))
                // sun/text/Normalizer is OVERLAID (from the JDK 26 source): getCombiningClass is exact below
                // U+0300 and throws above it. Stock String's Turkish case mapping reaches it through
                // ConditionalSpecialCasing. The rest of sun/text/ (the ICU-backed normalizer) stays denied.
                || utf8HasPrefix(base, off, Magic.bytes("sun/text/Normalizer")))
        {
            return false;
        }
        return utf8HasPrefix(base, off, Magic.bytes("java/lang/foreign/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/foreign/"))
                // java/nio/file/ and sun/nio/fs/ are NOT denied any more: java.nio.file runs STOCK
                // (MacOSXFileSystemProvider, the variant the seed bakes) over the read-only RAMFS, its
                // UnixNativeDispatcher natives implemented against jdk.internal.misc.MetalRamfs and the ones
                // not implemented THROWING. (Keep in sync with writer.ReachScan.DENY.)
                // jdk/internal/loader/ is NOT denied any more: the stock built-in loaders (ClassLoaders,
                // BuiltinClassLoader, URLClassPath) run over the class-path jar through file:/jar: URLs -- the
                // loader arc. BootLoader is overlaid (loadLibrary stays a no-op). The native-library natives
                // (NativeLibraries.load/findBuiltinLib, NativeLibrary.findEntry0) are unbound and trap if reached:
                // this VM loads no native libraries.
                // java/security/ is NOT denied any more (security arc, 2026-11-01): the JCA runs STOCK -- provider
                // lookup through sun.security.jca, the SUN/SunJCE/SunRsaSign/SunEC providers, X.509, KeyStore.
                // None of java/security, sun/security, javax/crypto, javax/net or javax/security has a native. The
                // cold part of it is closureDeferred (see securityCore).
                // java/util/ServiceLoader is NOT denied: it is OVERLAID (guestsrc/java/util/ServiceLoader).
                // Stock discovery goes through ClassLoader.getResources -> java/net/URL -> a protocol handler
                // in jdk/internal/loader, which was denied when this was written (the loader arc un-denied it
                // since) -- so there was no way to hand the stock class the jar's bytes. The overlay reads
                // META-INF/services/* through getResourceAsStream instead. The
                // denial had to come out first: a denied class is trap-wired at PATCH TIME, so no link stub
                // ever runs and the overlay would never be consulted.
                // sun/util/ is NOT denied any more (2026-10-31). Its subpackages had been narrowed out one by one
                // (locale, cldr, resources, spi, logging, calendar, PreHashedMap); the three classes still behind
                // the prefix are each on an ordinary java.util path: ResourceBundleEnumeration (getKeys of a
                // List/PropertyResourceBundle), PropertyResourceBundleCharset (new PropertyResourceBundle(
                // InputStream)) and BuddhistCalendar (the "buddhist" calendar type). Pure Java, no natives.
                // java/net is LOADABLE (M3: stock java.net over net.Tcp), and nothing in it is denied any more
                // (2026-11-01). SocksSocketImpl is overlaid as a pure delegator. HttpConnectSocketImpl (the
                // HTTP-proxy socket) loads, but its <clinit> reflects on sun.net.www.protocol.http.HttpURLConnection,
                // which is denied below: new Socket(httpProxy) throws ExceptionInInitializerError naming it instead
                // of halting at a denylist trap. java/net/SocketCleanable no longer exists in JDK 26.
                // org/junit/vintage/ and org/junit/platform/reporting/ are NOT denied any more (2026-11-07).
                // Both were denied so the ServiceLoader overlay would skip them by name: the reporting
                // listener's constructor called java/nio/file/Path.of when java/nio/file was denied, and the
                // vintage engine's JUnit4VersionCheck does `new BigDecimal(version)` when java.math did not
                // work. Both premises expired: java/nio/file runs stock over the RAMFS, and java.math works.
                // sun/net/www/ is NOT denied any more (2026-11-01), https included. file: and jar: had been
                // narrowed out for the class path; now the http, https, ftp, mailto and jrt handlers and the HTTP
                // client load too, so URL.openConnection answers a real HttpURLConnection and the HTTP-proxy
                // socket's initializer finds its client. No natives on the unix side. It stays closureDeferred
                // (pulled when reached).
                //
                // An https CONNECTION still cannot be made: HttpsURLConnectionImpl extends
                // javax/net/ssl/HttpsURLConnection, which is not in the classDir (ImageBuilder.demandLoadable ships
                // only named javax/ classes; the SSL implementation, sun/security/ssl, is denied). openConnection
                // throws NoClassDefFoundError naming it (RVMClass.absentSuperOff) -- it took a WILD BRANCH before
                // that was fixed, which is why https was held back one change. The handler and URL work.
                // sun/net/ext/ is NOT denied any more (2026-11-01): it holds only ExtendedSocketOptions, which runs
                // STOCK (Net.<clinit> sets EXTENDED_OPTIONS from it). Its forName of jdk.net.ExtendedSocketOptions
                // finds no class, so it answers NoExtendedSocketOptions, as a JDK without jdk.net does.
                // sun/nio/ch/Poller and sun/nio/ch/ExtendedSocketOption are NOT denied any more (2026-10-29). They
                // were kept out because their closures OOM'd the demand-load of the day. ExtendedSocketOption is one
                // static SocketOption with no natives; denied, NioSocketImpl read its SO_OOBINLINE as null on every
                // connection. Poller is reached only from a VIRTUAL thread (NioSocketImpl.park/shutdown*), and
                // Thread.isVirtual() is false here, so its <clinit> (kqueue pollers + their threads) never runs --
                // the stock call now links lazily instead of being TRAP-WIRED. (SocketOptionRegistry IS reached by
                // close() -> overlaid, not denied.) java/net/NetworkInterface is NOT denied either: it is OVERLAID
                // from the JDK 26 source, its natives Java over the WiFi stack's lease.
                // jdk/internal/util/Exceptions and sun/net/util/IPAddressUtil are NO LONGER DENIED. They were
                // kept out because their closures (Formatter -> regex, a security-property read, a
                // ConcurrentHashMap) OOM'd the demand-load of the day; all of those load now, Security is
                // narrowed in above, and stock InetAddress calls both on its ordinary paths (literal parsing,
                // IPv4-mapped conversion, the UnknownHostException message).
                // sun/security/ is NOT denied any more either (security arc): the provider implementations.
                // java/util/jar/JarVerifier is NOT denied any more (2026-11-01): signed jars verify stock, through
                // sun.security.util.SignatureFileVerifier, and JarFile runs stock (its overlay only worked around
                // the denial). Its closure is deferred with the rest of the JCA -- see securityDeferred.
                // java/lang/reflect/ is NOT denied any more (2026-10-30). The prefix had been narrowed one class
                // at a time until only eight were left behind it -- the Annotated*Type interfaces,
                // InaccessibleObjectException, MalformedParametersException and ReflectPermission: interfaces,
                // exceptions and a BasicPermission, no natives -- so the whole package loads. Its overlays
                // (Field, Method, Constructor, Executable, Parameter, Array, AccessibleObject, RecordComponent,
                // Proxy) are unaffected; that a denied class is trap-wired at PATCH TIME, so an overlay can never
                // rescue one, is the reason each of them had to be narrowed out first.
                // jdk/internal/reflect/ is NOT denied any more (2026-10-30). Reflection and ReflectionFactory were
                // already narrowed out (both overlaid); the rest is accessor interfaces and classes, the
                // @CallerSensitive annotations and ConstantPool. Its natives (ConstantPool's, and the two
                // NativeAccessor invoke0/newInstance0) are unbound, so reaching one THROWS UnsatisfiedLinkError
                // naming the method (missingNativeError) -- the Method/Constructor/Field overlays do not route
                // through these accessors.
                // jdk/internal/module/ is NOT denied any more (2026-10-30). ServicesCatalog and Resources had been
                // narrowed out for the built-in loaders; the rest (ModuleBootstrap, Modules, ModuleInfo, the
                // finders) has no natives. No ModuleBootstrap runs -- there is no boot layer, so
                // BuiltinClassLoader's package-to-module map stays empty -- and the package is closureDeferred, so
                // only code that actually runs pulls it.
                // java/lang/module/ is NOT denied any more (2026-10-29). Nothing in the VM keyed a native on it,
                // and stock MethodHandleProxies reads ModuleDescriptor$Modifier.SYNTHETIC -- denied, that read
                // null (its overlay works around it).
                // java/util/spi/, java/text/, java/text/spi/ and sun/text/ are NOT listed: the first clause above
                // allows all four prefixes and returns before this one, so entries here could never match.
                // (NOT java/util/concurrent -- the philosophers demand-load java/util/concurrent/Semaphore.)
                // java/time is NOT DENIED any more (the date/time arc). It was kept out because Formatter NAMES
                // ten of its classes for %t and the dependency pass of the day pulled +357 classes into every
                // batch; System.Logger's log() formats through ZonedDateTime.now(), and rule 1 says implement.
                // java/lang/CharacterName, Character$UnicodeBlock and Character$UnicodeScript are NOT denied any
                // more (2026-10-24). picocli's CJK width check calls UnicodeBlock.of on every usage line (TextProbe
                // trapped here); CharacterName reads the java.base resource java/lang/uniName.dat, which the build
                // now copies to /lib/java.base (Makefile JAVA_BASE_RES). Rule 1 says implement.
                // java/util/concurrent/TimeUnit$1 (the ChronoUnit switch map) is NOT denied either: its
                // <clinit> calls ChronoUnit.values(), which loads now that java/time/temporal/ does.
                // jdk/internal/util/regex/Grapheme is NOT denied any more (increment 4): its 15x15 [[Z table is
                // built by multianewarray, which the metal JIT lowers through VM.multiNewArray, and
                // BreakIterator.getCharacterInstance is the grapheme iterator.
                // jdk/internal/lang/CaseFolding WAS denied here, on the premise its own comment stated:
                // "case-folding tables ([[I via multianewarray): only CASE_INSENSITIVE regex needs them".
                // THAT PREMISE EXPIRED: JUnit's TimeoutDurationParser.<clinit> compiles
                // `([1-9]\d*) ?((?:[nμm]?s)|m|h|d)?` with flags 66 = CASE_INSENSITIVE | UNICODE_CASE, so
                // Pattern.CIRangeU asks this class which characters fold INTO the range [1-9]. It is
                // OVERLAID now (guestsrc), which is what the denial had to be narrowed for: a denied class
                // is trap-wired at PATCH TIME, so no link stub runs and the overlay is never consulted.
                // It was overlaid because the stock class builds its tables with multianewarray, which the
                // JIT could not lower at the time; it can now (VM.multiNewArray, see Grapheme above), so
                // the overlay is no longer forced by that.
                // The charset coders (CharsetDecoder/CharsetEncoder/CoderResult/CodingErrorAction) and
                // sun/nio/cs/Array* are NOT denied any more: the charsets run STOCK with real coders, so
                // InputStreamReader/OutputStreamWriter decode and encode. (Keep in sync with ReachScan.DENY.)
                // IllegalCharsetNameException / UnsupportedCharsetException are NOT denied: they are plain
                // IllegalArgumentException subclasses with no dependencies, stock Charset throws the latter,
                // and stock code CATCHES both (PrintStream.toCharset). While they were
                // denied, such a catch clause could not name its class -- see the CATCH_BY_NAME note in
                // emitMethod for what an unresolvable catch class used to do.
                // java/nio/ByteBuffer runs STOCK (heap and direct). java/nio/CharBuffer is loadable and STOCK too: java.util.Scanner buffers its input in a heap CharBuffer (CharBuffer.allocate)
                // and reads through Readable.read(CharBuffer), so denying it trapped Scanner's first token.
                ;
    }


    static int[] pnBucket;                       // registered class names, by hash -> pd index
    static int[] pnNext;                         // chain link, per blob
    static int pnIndexed;                        // blobs [0,pnIndexed) are indexed with a valid pdNameOff


    /**
     * Microseconds since the {@code CNTPCT_EL0} reading {@code t0}. The counter is free-running and
     * independent of the timer tick, so this reads correctly whether or not preemption is armed.
     */
    static long elapsedUs(long t0)
    {
        long f = Magic.readCNTFRQ_EL0();
        if (f == 0L)
        {
            return 0L;
        }
        return (Magic.readCNTPCT_EL0() - t0) * 1000000L / f;
    }

    /** A duration in microseconds, as {@code NNNus} under a millisecond and {@code N.NNNms} above it. */
    static void printDur(long us)
    {
        if (us < 1000L)
        {
            VM.printDec((int) us);
            Uart.write(Magic.bytes("us"));
            return;
        }
        VM.printDec((int) (us / 1000L));
        Uart.putc(0x2E);                                // '.', then the microsecond remainder, zero-padded
        long r = us % 1000L;
        if (r < 100L)
        {
            Uart.putc(0x30);
        }
        if (r < 10L)
        {
            Uart.putc(0x30);
        }
        VM.printDec((int) r);
        Uart.write(Magic.bytes("ms"));
    }

    /** True if a blob at address {@code bytes} is already pending/loaded. */
    static boolean alreadyBlob(long bytes)
    {
        int i = 0;
        while (i < pdCount)
        {
            if (pdBase.get(i).toLong() == bytes)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** Write {@code len} raw name bytes to the UART (a '/'-separated internal class name). */
    static void writeName(Address p, int len)
    {
        int i = 0;
        while (i < len)
        {
            Uart.putc((byte) u1(p.plus(i)));
            i += 1;
        }
    }

    /** #43 fault diagnostic: name the loaded class whose Type node is {@code type} (the wild-branch receiver's
     *  class), over the UART, or nothing if unknown. */
    static void reportClassOfType(Address type)
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(DataReference.fromAddress(type)))
            {
                Uart.write(Magic.bytes(" class="));
                writeName(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff + 2), u2(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff)));
                Uart.write(Magic.bytes(" gvCount="));
                VM.printDec(ClassRegistry.clTab[i].vtCount);
                return;
            }
            i += 1;
        }
        Uart.write(Magic.bytes(" class=? (unregistered Type)"));
    }

    /** Print the loaded class name whose Type node is {@code type} (just the bare name), or a placeholder.
     *  Used by {@code Throwable.printStackTrace()} for the header line. */
    static void printClassName(Address type)
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(DataReference.fromAddress(type)))
            {
                writeName(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff + 2), u2(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff)));
                return;
            }
            i += 1;
        }
        Uart.write(Magic.bytes("<exception>"));
    }

    /** Print the name of any class Type -- registered, baked-but-unregistered, or neither ({@code Type@addr}). For
     *  diagnostics ({@link HeapShadow}); prints nothing it did not find. */
    static void printTypeName(Address type)
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(DataReference.fromAddress(type)))
            {
                writeName(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff + 2), u2(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff)));
                return;
            }
            i += 1;
        }
        long nm = bakedTypeName(type);
        if (nm != 0L)
        {
            writeName(Address.fromLong(nm + 2L), u2(Address.fromLong(nm)));
            return;
        }
        Uart.write(Magic.bytes("Type@"));
        VM.printHex(type.toLong());
    }

    /** M4 {@code Class.getName()}: a fresh guest String of {@code type}'s dotted binary name (registry
     *  name bytes, '/'->'.'), or 0 if the Type isn't in the loaded registry. */
    static long classNameString(DataReference type)
    {
        int len = classNameLen(type.toAddress());
        if (len <= 0)
        {
            reportNamelessType(type.toAddress());
            return 0L;
        }
        ObjectReference arr = Heap.allocArray(len, 1).toObjectReference();
        writeClassName(type.toAddress(), arr.toLong() + 24L, 0);
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().store(stringTib());
        obj.toAddress().plus(16L).store(arr);                  // value byte[]; coder@24 stays 0 = LATIN1
        return obj.toLong();
    }

    /**
     * {@code Class.getName()} is about to answer NULL, and that is a SILENT WRONG ANSWER rather than a gap:
     * stock's {@code getName()} never returns null, so the null flows into ordinary library code and NPEs
     * arbitrarily far from here.
     *
     * <p>The launcher is where this was found and it shows the cost exactly. JUnit builds a
     * {@code ClassSelector} as {@code className = clazz.getName()}, and its whole {@code hashCode()} is
     * {@code aload_0; getfield className; invokevirtual String.hashCode} -- so a null name surfaces as a
     * bare NPE inside {@code HashMap.put}, THREE frames and one collection deep, naming a JUnit class and
     * nothing about this VM.
     *
     * <p>The report names WHICH of the two cases it is, because they want opposite investigations: an array
     * whose ELEMENT type never resolved (the name is genuinely underivable and the element is the thing to
     * chase), or a Type the class registry does not hold -- which for a synthesised lambda/annotation TIB is
     * BY CONSTRUCTION (a hidden class has no binary name) and for anything else is a registration gap.
     * Capped, because {@code getName()} is called freely and an unbounded report would flood the UART and
     * starve the run it is meant to diagnose.
     */
    private static void reportNamelessType(Address type)
    {
        if (namelessN >= 8)
        {
            namelessN += 1;
            return;
        }
        namelessN += 1;
        Uart.write(Magic.bytes("\n  CLASS NAME UNRESOLVED (Class.getName() answers NULL): type=0x"));
        VM.printHex(type);
        if (ArrayTypes.isArrayType(type))
        {
            Uart.write(Magic.bytes("\n  ... an ARRAY whose ELEMENT type is unresolved -- chase the element, not this"));
        }
        else
        {
            Uart.write(Magic.bytes("\n  ... not in the class registry; synthesised(lambda/anno TIB)="));
            VM.printDec(isSynthesisedType(type));
            Uart.write(Magic.bytes(" -- a synthesised type has no binary name BY CONSTRUCTION"));
        }
        Uart.putc(0x0A);
    }

    /** {@link TibBuilder#isSynthesisedTib} keyed by TYPE: a synthesised TIB holds its Type in slot 0. */
    private static int isSynthesisedType(Address type)
    {
        return synthIndexOf(type) >= 0 ? 1 : 0;
    }

    /** The synthesised-TIB table index whose TIB carries {@code type}, or -1. */
    private static int synthIndexOf(Address type)
    {
        if (TibBuilder.lambdaTibRoots == null)
        {
            return -1;
        }
        int i = 0;
        while (i < TibBuilder.lambdaTibRootN)
        {
            if (!TibBuilder.lambdaTibRoots.get(i).isZero() && TibBuilder.lambdaTibRoots.get(i).loadAddress().EQ(type))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Length of the host-class prefix of synthesised type {@code type}'s name: its binary name, or 0. */
    private static int synthHostLen(Address type)
    {
        int i = synthIndexOf(type);
        if (i < 0 || TibBuilder.lambdaHostBase.get(i).isZero())
        {
            return 0;
        }
        return u2(TibBuilder.lambdaHostBase.get(i).plus(TibBuilder.lambdaHostNameOff[i]));
    }

    /** How many times {@code Class.getName()} has answered null; the report itself is capped at 8. */
    static int namelessN;


    /** Length of {@code type}'s dotted binary name, or 0 if it has none (unregistered / unresolved element). */
    static int classNameLen(Address type)
    {
        if (Primitives.isPrimitiveType(type))
        {
            return Primitives.primNameLen(Primitives.primTypeChar(type));
        }
        if (ArrayTypes.isArrayType(type))
        {
            Address el = type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadAddress();
            if (el.isZero())
            {
                return ArrayTypes.primElemCharOf(type) == 0 ? 0 : 2;        // "[I"; 0 = a ref array whose element is unresolved
            }
            int n = elemDescLen(el);
            return n == 0 ? 0 : 1 + n;
        }
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(DataReference.fromAddress(type)))
            {
                return u2(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff));
            }
            i += 1;
        }
        long baked = bakedTypeName(type);
        if (baked != 0L)
        {
            return u2(baked);
        }
        return isSynthesisedType(type) != 0 ? synthHostLen(type) + SYNTH_NAME_LEN : 0;
    }

    /**
     * The name Utf8 ({u2 len}{bytes}) of a writer-baked class Type the registry does not hold, or 0. A baked
     * class is registered only when its blob is pulled, and VM code can `new` one without that (see
     * ImageBuilder's type-name table); getName() on such an object used to answer NULL.
     */
    private static long bakedTypeName(Address type)
    {
        long rel = type.diff(VM.bakedTypeBase).toLong();
        if (VM.bakedTypeNames.isZero() || rel < 0L || rel % ObjectModel.TYPE_SIZE != 0L)
        {
            return 0L;
        }
        long i = rel / ObjectModel.TYPE_SIZE;
        return i < VM.bakedTypeCount ? VM.bakedTypeNames.plus(i * 8L).loadLong() : 0L;
    }

    /** {@code "$$Lambda/0x"} + 16 hex digits -- the length {@link #writeSynthName} writes. */
    private static final int SYNTH_NAME_LEN = 11 + 16;

    /**
     * Name a SYNTHESISED (lambda/annotation) type, which the class registry does not hold.
     *
     * <p>It used to answer nothing, so {@code getClass().getName()} on a lambda returned NULL -- and stock's
     * {@code getName()} NEVER returns null, so that null flows into ordinary library code and NPEs somewhere
     * unrelated. This VM has just paid five boots chasing exactly that shape for a different object.
     *
     * <p>The shape is stock's: a hidden class reads {@code Outer$$Lambda/0x00007f...}, its name deliberately
     * opaque and NOT a binary name that could be looked up. A lambda's host class (recorded when its TIB was
     * built) is the {@code Outer}, dotted; an annotation instance or proxy has none and stays bare. The TYPE
     * ADDRESS is what makes it distinct per lambda, which matters because a single constant name would make
     * every lambda compare equal by name.
     */
    private static int writeSynthName(Address type, long dst, int pos)
    {
        int si = synthIndexOf(type);
        if (si >= 0 && !TibBuilder.lambdaHostBase.get(si).isZero())
        {
            Address src = TibBuilder.lambdaHostBase.get(si).plus(TibBuilder.lambdaHostNameOff[si]);
            int len = u2(src);
            int h = 0;
            while (h < len)
            {
                int c = u1(src.plus(2L + h));
                Address.fromLong(dst).plus(pos).plus(h).store((byte) (c == 0x2F ? 0x2E : c));   // '/' -> '.'
                h += 1;
            }
            pos += len;
        }
        byte[] pre = Magic.bytes("$$Lambda/0x");
        int k = 0;
        while (k < pre.length)
        {
            Address.fromLong(dst).plus(pos).plus(k).store(pre[k]);
            k += 1;
        }
        pos += pre.length;
        int d = 15;
        while (d >= 0)
        {
            int nib = (int) ((type.toLong() >>> (d * 4)) & 0xFL);
            Address.fromLong(dst).plus(pos).store((byte) (nib < 10 ? 0x30 + nib : 0x61 + nib - 10));
            pos += 1;
            d -= 1;
        }
        return pos;
    }


    /** Length of an array element's DESCRIPTOR form: "[I" for a nested array, "Ljava.lang.String;" otherwise. */
    private static int elemDescLen(Address el)
    {
        if (ArrayTypes.isArrayType(el))
        {
            return classNameLen(el);
        }
        int n = classNameLen(el);
        return n == 0 ? 0 : n + 2;                               // 'L' + name + ';'
    }

    /** Write {@code type}'s name at {@code dst+pos} (dots for '/'); returns the position after it. */
    private static int writeClassName(Address type, long dst, int pos)
    {
        if (Primitives.isPrimitiveType(type))
        {
            return Primitives.writePrimName(Primitives.primTypeChar(type), dst, pos);
        }
        if (ArrayTypes.isArrayType(type))
        {
            Address.fromLong(dst).plus(pos).store((byte) 0x5B);                // '['
            pos += 1;
            Address el = type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).loadAddress();
            if (el.isZero())
            {
                // A PRIMITIVE array names itself by descriptor char. An element of 0 that matches no
                // primitive TIB is an array whose element class never resolved -- write '?' rather than the
                // NUL that `primElemCharOf` returns, which rendered as `class [` in a ClassCastException and
                // told the reader nothing. A message with an embedded NUL is worse than a short one.
                int pc = ArrayTypes.primElemCharOf(type);
                Address.fromLong(dst).plus(pos).store((byte) (pc != 0 ? pc : 0x3F));   // '?'
                return pos + 1;
            }
            if (ArrayTypes.isArrayType(el))
            {
                return writeClassName(el, dst, pos);             // nested: "[[I"
            }
            Address.fromLong(dst).plus(pos).store((byte) 0x4C);                // 'L'
            pos = writeClassName(el, dst, pos + 1);
            Address.fromLong(dst).plus(pos).store((byte) 0x3B);                // ';'
            return pos + 1;
        }
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[i].type.EQ(DataReference.fromAddress(type)))
            {
                int len = u2(ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff));
                Address src = ClassRegistry.clTab[i].base.plus(ClassRegistry.clTab[i].nameOff + 2);
                int k = 0;
                while (k < len)
                {
                    int c = u1(src.plus(k));
                    Address.fromLong(dst).plus(pos).plus(k).store((byte) (c == 0x2F ? 0x2E : c));   // '/' -> '.'
                    k += 1;
                }
                return pos + len;
            }
            i += 1;
        }
        long baked = bakedTypeName(type);
        if (baked != 0L)
        {
            int len = u2(baked);
            int k = 0;
            while (k < len)
            {
                int c = u1(baked + 2L + k);
                Address.fromLong(dst).plus(pos).plus(k).store((byte) (c == 0x2F ? 0x2E : c));   // '/' -> '.'
                k += 1;
            }
            return pos + len;
        }
        return writeSynthName(type, dst, pos);   // unregistered => synthesised; classNameLen sized it
    }


    /**
     * Pull class {@code slash} (an internal '/'-separated name) and its dependency closure into the running
     * program WITHOUT {@link #resetLoader} — the registries are append-only and prior blobs are skipped
     * ({@code pdDone}/{@code pdDoneB}), so the live program's compiled code + heap survive. Every method of the
     * pulled class is seeded reachable (reflection may invoke any). Returns its Type, or 0 if not embedded.
     */
    static DataReference loadClassIncremental(byte[] slash)
    {
        DataReference blob = DataReference.fromLong(pullClass(slash));
        if (blob.isZero())
        {
            return DataReference.zero();                                  // not embedded in the classDir
        }
        LazyCompiler.pullIndyIfaces(blob);                      // its lambdas' interfaces: named in no CONSTANT_Class
        // Seed the class's <clinit> as the reachability root: it (and everything it transitively calls) gets
        // compiled + run, and the class's structure/Type/mirror is registered by loadStructure regardless. The
        // class's OTHER methods are compiled LAZILY when reflectively invoked (M2) — eagerly seeding them all
        // pulled a huge closure into the 2nd (incremental) batch and corrupted the heap.
        Closure.stubBlob(blob.toLong());                        // vtable-complete without pulling its closure (see stubBlob)
        Closure.entryPoint(blob.toLong(), Magic.bytes("<clinit>"), Magic.bytes("()V"));   // may be absent (addReach(0) is a no-op)
        loadAll();
        int ci = ClassRegistry.classIndexByName(slash);
        return ci < 0 ? DataReference.zero() : ClassRegistry.clTab[ci].type;
    }

    /**
     * {@code ClassLoader.defineClass(name, byte[], off, len)} native: materialize a class from SUPPLIED
     * classfile bytes (not the embedded classDir) into the LIVE program. The caller's {@code byte[]} bytes are
     * copied into a fresh heap blob (a clean offset-0 base the loader can read raw), registered, and loaded
     * incrementally — the same seed-{@code <clinit>} + {@code loadAll} path as {@link #loadClassIncremental},
     * so the class's not-yet-loaded dependency closure is demand-pulled from the classDir. Returns the new
     * class's Type (the caller wraps it in a Class mirror), or 0 on empty/malformed input.
     *
     * <p>The class's own name comes from the classfile (this_class), so the {@code name} argument is advisory
     * and unused; the just-defined class is located after load by its unique blob base ({@code clBase}). No
     * duplicate-definition check, no delegation hierarchy (single application loader).
     */
    static DataReference defineFromBytes(ObjectReference byteArr, int off, int len)
    {
        if (byteArr.toLong() <= 0x1000L || len <= 0)
        {
            return DataReference.zero();                                  // boot force-compile guard / empty input
        }
        DataReference blob = Heap.allocData(len);             // raw offset-0 base: classfile byte 0 lives at blob+0
        long blobKey = blob.toLong();                   // ... and as the long the blob table and clTab compare by
        Address srcAddr = byteArr.toAddress().plus(24L).plus(off);   // byte[] elements @24, plus the caller's offset
        int i = 0;
        while (i < len)
        {
            blob.plus(i).store((byte) srcAddr.plus(i).loadUnsignedByte());
            i += 1;
        }
        addBlob(blobKey, len);
        if (stoppableDefiner >= 0 && stoppableDefiner == VM.curTask())
        {
            Closure.addStoppableBlob(blob.toAddress(), len);                // a /run program's class: its loops poll for a stop
        }
        LazyCompiler.pullIndyIfaces(blob);                           // as loadClassIncremental: a lambda's interface is named
                                                        //   in no CONSTANT_Class, so nothing else pulls it
        Closure.rootBlob(blobKey);                              // EVERY method is a root: nothing loaded calls into a
                                                        //   class the program just handed us (see rootBlob)
        Closure.entryPoint(blobKey, Magic.bytes("<clinit>"), Magic.bytes("()V"));   // enables the mark; <clinit> may be absent
        loadAll();
        int ci = 0;                                     // find the class we just added by its unique blob base
        while (ci < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[ci].base.toLong() == blobKey)
            {
                return ClassRegistry.clTab[ci].type;
            }
            ci += 1;
        }
        return DataReference.zero();
    }

    /** M4 {@code Thread.currentThread()}: a bare guest {@code java/lang/Thread} (no ctor run; fields null)
     *  wrapping a VM-created task, or 0 if Thread isn't in the loaded batch. */
    static long allocThreadObj()
    {
        int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/Thread"));
        if (i < 0)
        {
            return 0L;
        }
        ObjectReference obj = Heap.alloc(16 + ClassRegistry.clTab[i].fieldCount * 8).toObjectReference();
        obj.toAddress().store(ClassRegistry.clTab[i].tib);
        return obj.toLong();
    }


    /**
     * Decode a JIT call site's PHASE-A CELL sequence and name the method it ACTUALLY reached.
     *
     * <p>A deferred static call lowers to {@code movz/movk x17,<cell>; ldr x16,[x17]; blr x16} -- the cell
     * holds the callee's body address and is patched when that callee compiles. The instruction stream alone
     * only says a call happened; following the cell says WHERE IT WENT, which is the difference between "the
     * argument was marshalled wrong" and "the call reached a different method than the source names".
     *
     * <p>Scans the 16 words ending at {@code up} (a return address handed to a fault reporter), so it covers
     * the faulting call AND the one before it -- the pair that matters when a result flows straight out of
     * one call into the next.
     */
    static void reportCallCells(Address up)
    {
        Address w = up.minus(60L);
        while (w.toLong() <= up.toLong() - 4L)
        {
            if ((w.loadInt() & 0xFFFFFFFFL) == 0xD63F0200L                    // blr x16
                    && (w.minus(4L).loadInt() & 0xFFFFFFFFL) == 0xF9400230L)       // ldr x16,[x17]
            {
                Uart.write(Magic.bytes("      call at up-0x"));
                VM.printHex(up.toLong() - w.toLong());
                Uart.write(Magic.bytes(" cell=0x"));
                VM.printHex(cellImmAt(w.minus(8L)));
                Uart.write(Magic.bytes(" -> "));
                if (cellImmAt(w.minus(8L)) != 0L)
                {
                    StackTrace.printFrameAt(Address.fromLong(cellImmAt(w.minus(8L))).loadAddress());
                }
                Uart.putc(0x0A);
            }
            w = w.plus(4L);
        }
    }

    /** Reconstruct the {@code x17} immediate built by the (up to three) movz/movk words ending at {@code at}. */
    private static long cellImmAt(Address at)
    {
        long v = 0L;
        int k = 0;
        while (k < 3)
        {
            long insn = at.minus((long) (k * 4)).loadInt() & 0xFFFFFFFFL;
            if ((insn & 0x1FL) != 17L
                    || (((insn >> 23) & 0x1FFL) != 0x1A5L && ((insn >> 23) & 0x1FFL) != 0x1E5L))
            {
                k = 3;
            }
            else
            {
                v |= ((insn >> 5) & 0xFFFFL) << (int) (((insn >> 21) & 3L) * 16L);
                k += 1;
            }
        }
        return v;
    }

    /** #43 fault diagnostic: name the demand-compiled method whose code contains {@code addr} (the highest
     *  registered buffer base <= addr), plus the byte offset into it, over the UART. */
    static void reportMethodAt(Address addr)
    {
        // Print the (up to) 4 registered methods whose buffer base is nearest at-or-below addr, closest first,
        // each with base + delta. If methods are densely packed the first line is the container; a large delta
        // to the next-lower base means addr sits in a gap (an unregistered/synthetic method).
        int printed = 0;
        long ceil = addr.toLong() + 1L;                    // strictly-below-ceil watermark; walks downward
        while (printed < 5)
        {
            long bestBuf = 0L;
            int bestReg = -1;
            int bestClin = -1;
            int i = 0;
            while (i < MethodRegistry.rgCount)                            // registered cross-class methods
            {
                if (MethodRegistry.rgTab[i].buf.toLong() != 0L && MethodRegistry.rgTab[i].buf.toLong() < ceil && MethodRegistry.rgTab[i].buf.toLong() > bestBuf)
                {
                    bestBuf = MethodRegistry.rgTab[i].buf.toLong(); bestReg = i; bestClin = -1;
                }
                i += 1;
            }
            int c = 0;
            while (c < ClassInitializer.clinitN)                            // compiled <clinit>s (NOT in rgBuf)
            {
                if (ClassInitializer.clinitEntry.get(c) != 0L && ClassInitializer.clinitEntry.get(c) < ceil && ClassInitializer.clinitEntry.get(c) > bestBuf)
                {
                    bestBuf = ClassInitializer.clinitEntry.get(c); bestClin = c; bestReg = -1;
                }
                c += 1;
            }
            if (bestReg < 0 && bestClin < 0) { break; }
            Uart.write(Magic.bytes("\n    @0x"));
            VM.printHex(bestBuf);
            Uart.write(Magic.bytes(" (+0x"));
            VM.printHex(addr.toLong() - bestBuf);
            Uart.write(Magic.bytes(") "));
            if (bestReg >= 0)
            {
                writeName(MethodRegistry.rgTab[bestReg].base.plus(MethodRegistry.rgTab[bestReg].classOff + 2),
                        u2(MethodRegistry.rgTab[bestReg].base.plus(MethodRegistry.rgTab[bestReg].classOff)));
                Uart.putc(0x2E);
                writeName(MethodRegistry.rgTab[bestReg].base.plus(MethodRegistry.rgTab[bestReg].nameOff + 2),
                        u2(MethodRegistry.rgTab[bestReg].base.plus(MethodRegistry.rgTab[bestReg].nameOff)));
            }
            else
            {
                writeName(ClassInitializer.clinitBase.get(bestClin).plus(ClassInitializer.clinitNameOff[bestClin] + 2),
                        u2(ClassInitializer.clinitBase.get(bestClin).plus(ClassInitializer.clinitNameOff[bestClin])));
                Uart.write(Magic.bytes(".<clinit>"));
            }
            ceil = bestBuf;
            printed += 1;
        }
        if (printed == 0) { Uart.write(Magic.bytes("(no registered method)")); }
    }

    /**
     * Build the shared run-trampoline (stored in {@link VM#runTrampAddr}): entered with x0 = a Runnable,
     * it invokeinterface-dispatches {@code run()} on the receiver, then calls {@link VM#taskExit}.
     * M8 itables: the directory now holds PER-interface itables, so the tramp scans the receiver's
     * directory for Runnable's (one, adopted-or-loader) Type and indexes that entry's itable at
     * run()'s per-interface slot. Unguarded past the sentinel, like the old dir[0] shortcut: the
     * tramp is only ever entered with real Runnables, whose dir always carries the entry.
     */
    static void buildRunTramp()
    {
        if (VM.runTrampAddr != 0L)
        {
            return;                                     // already built; it is code, so rebuilding leaks an arena block
        }
        // The itable scan this used to emit BY HAND was, in its own words, "unguarded past the sentinel", and
        // it also baked Runnable's Type in as an immediate -- built once, cached for the life of the VM. Both
        // are the hazards this VM has been closing everywhere else, and here they land on a FRESHLY SPAWNED
        // task: nothing is on the stack, so a wild branch out of the scan names no caller and reports nothing.
        // The lookup is Java now (resolveRun), which can bounds-check, fall back, and say what it could not
        // find; the stub is just a frame, a call, and the dispatch.
        Address buf = Heap.allocCode(96);
        // PINNED: reached through a spawned task's entry pc, which no trace reads. Its only root was the
        // conservative scan of the raw static runTrampAddr -- gone once the statics are read by kind (frames/GC arc
        // S6d).
        Heap.pinCodeAt(buf);
        int w = 0;
        buf.plus(w * 4L).store(A64Enc.subImm(31, 31, 16)); // frame: the receiver must survive
        w += 1;
        buf.plus(w * 4L).store(A64Enc.strx(0, 31, 0)); //   the resolve call
        w += 1;
        long rr = VM.runResolveAddr;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (rr & 0xFFFFL), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((rr >>> 16) & 0xFFFFL), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // resolveRun(recv) -> run() buffer
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movReg(16, 0)); // x16 = the resolved buffer
        w += 1;
        buf.plus(w * 4L).store(A64Enc.ldrx(0, 31, 0)); // x0 = the receiver again
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // run() with x0 = receiver
        w += 1;
        long te = VM.taskExitAddr;
        buf.plus(w * 4L).store(A64Enc.movz(16, (int) (te & 0xFFFFL), 0));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.movk(16, (int) ((te >>> 16) & 0xFFFFL), 1));
        w += 1;
        buf.plus(w * 4L).store(A64Enc.blr(16)); // taskExit() — never returns
        w += 1;
        Heap.publishCode(buf, buf.plus(w * 4L));
        VM.runTrampAddr = buf.toLong();
    }

    /**
     * The run-trampoline's dispatch, in Java: find {@code run()V} for the Runnable a spawned task was handed.
     * Returns a callable buffer, or {@code denylistTrap} after naming what failed -- never a garbage word.
     *
     * <p>Every intermediate is checked, and the directory scan STOPS at its 0 terminator rather than walking
     * past it: on a fresh task stack a bad word here becomes a branch that no report can attribute, because
     * there is no caller on the stack to name.
     */
    static long resolveRun(ObjectReference recv)
    {
        if (recv.isNull())
        {
            return 0L;                                  // boot force-compile probe: make the helper reachable
        }
        if (recv.toLong() < Heap.BASE || recv.toLong() >= Heap.managedTop().toLong() || (recv.toLong() & 7L) != 0L)
        {
            Uart.write(Magic.bytes("\n  RUNTRAMP: receiver is not an object: "));
            VM.printHex(recv.toLong());
            Uart.putc(0x0A);
            return VM.denylistTrapAddr;
        }
        // A Thread enters through MetalThreadEntry.metalThreadEntry -- run() plus uncaught-handler dispatch -- and
        // only falls back to Runnable.run when that entry is absent, which is REPORTED: the thread still runs, but
        // a handler set on it would silently never fire.
        long buf = InterfaceInvocation.ifaceMethodFromItable(recv, metalThreadEntryTypeAddr().toAddress(), 0);
        if (buf == 0L || !DynamicLinker.plausibleCode(Address.fromLong(buf)))
        {
            if (!metalThreadEntryTypeAddr().isZero() && !reportedNoThreadEntry)
            {
                reportedNoThreadEntry = true;
                Uart.write(Magic.bytes("\n  RUNTRAMP: no metalThreadEntry -- uncaught-exception handlers will not fire\n"));
            }
            buf = InterfaceInvocation.runFromItable(recv);
        }
        if (buf == 0L || !DynamicLinker.plausibleCode(Address.fromLong(buf)))
        {
            Uart.write(Magic.bytes("\n  RUNTRAMP: no run()V for "));
            long tib = recv.toAddress().loadLong();
            if (tib > 0x1000L)
            {
                reportClassOfType(Address.fromLong(tib).loadAddress());
            }
            Uart.write(Magic.bytes(" (buf="));
            VM.printHex(buf);
            Uart.write(Magic.bytes(")\n"));
            return VM.denylistTrapAddr;
        }
        return buf;
    }

    private static boolean reportedNoThreadEntry;

    /** java/lang/MetalThreadEntry's Type (0 if not loaded). Its one method is slot 0 of its itable run. */
    private static DataReference metalThreadEntryTypeAddr()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/MetalThreadEntry")))
            {
                return ClassRegistry.clTab[i].type;
            }
            i += 1;
        }
        return DataReference.zero();
    }


    /** java/lang/Runnable's ONE Type (0 if not loaded: no Runnables can exist yet). */
    static DataReference runnableTypeAddr()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Runnable")))
            {
                return ClassRegistry.clTab[i].type;
            }
            i += 1;
        }
        return DataReference.zero();
    }

    /** run()V's slot in Runnable's flattened per-interface run (0 if Runnable isn't loaded). */
    static int runnableRunSlot()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Runnable")))
            {
                int s = 0;
                while (s < ClassRegistry.clTab[i].ifmCount)
                {
                    int k = ClassRegistry.clTab[i].ifmStart + s;
                    if (Utf8.isName(InterfaceInvocation.ifBase.get(k), InterfaceInvocation.ifNameOff[k], 0x72756EL, 3)
                            && Utf8.isName(InterfaceInvocation.ifBase.get(k), InterfaceInvocation.ifDescOff[k], 0x282956L, 3))
                    {
                        return s;
                    }
                    s += 1;
                }
            }
            i += 1;
        }
        return 0;
    }

    /** Load java.lang.Math from java.base and run Math.max(0x4D, 0x21) -> 'M'. */
    static int loadMath()
    {
        seek(0x6d6178L, 3, 0x2849492949L, 5);          // "max" "(II)I"
        return (int) load2(VM.mathBytes, (int) VM.mathLen, 0x4DL, 0x21L);
    }

    /** Compile+run a real, unmodified {@code java/lang/Integer.bitCount(int)} (a pure SWAR popcount). */
    static int intBitCount(int n)
    {
        seek(0x626974436F756E74L, 8, 0x28492949L, 4);  // "bitCount" "(I)I"
        return (int) load2(VM.integerBytes, (int) VM.integerLen, n & 0xFFFFFFFFL, 0L);
    }

    /** Compile+run real {@code java/lang/Integer.reverse(int)} (pure bit reversal). */
    static int intReverse(int n)
    {
        seek(0x72657665727365L, 7, 0x28492949L, 4);    // "reverse" "(I)I"
        return (int) load2(VM.integerBytes, (int) VM.integerLen, n & 0xFFFFFFFFL, 0L);
    }


    /**
     * Attempt a FULL load of real {@code java/lang/Integer} (all methods + {@code <clinit>}), to map where
     * the loader's reach ends on unmodified java.base bytecode: the first unsupported opcode/intrinsic is
     * named by {@link vm.VM#jitFail}, and missing cross-class references / natives surface here.
     */
    static void loadIntegerFull()
    {
        // Statically map real java/lang/Integer's dependency + native surface: every distinct class it
        // calls into (Methodref/InterfaceMethodref/Fieldref), minus itself. A pure constant-pool scan (no
        // compile) so it never halts on an unsupported opcode -- those are a separate (float) gap. These
        // classes carry the JNI natives + bootstrap that Integer's string/parse methods assume.
        resetLoader();
        parseConstPool(DataReference.fromAddress(Address.fromLong(VM.integerBytes)), (int) VM.integerLen);
        int[] seen = new int[64];
        int seenN = 0;
        int c = 1;
        while (c < gView.cpCount)
        {
            int t = gView.cpTag[c];
            if (t == 9 || t == 10 || t == 11)           // Fieldref / Methodref / InterfaceMethodref
            {
                int classOff = refClassNameOff(c);
                int k = 0;
                while (k < seenN && seen[k] != classOff)
                {
                    k += 1;
                }
                if (k == seenN && seenN < 64 && !Utf8.utf8IsAtBase(gView.base.toAddress(), classOff, Magic.bytes("java/lang/Integer")))
                {
                    seen[seenN] = classOff;
                    seenN += 1;
                    Uart.write(Magic.bytes("    needs "));
                    writeName(gView.base.plus(classOff + 2), u2(gView.base.plus(classOff)));
                    Uart.putc(0x0A);
                }
            }
            c += 1;
        }
        Uart.write(Magic.bytes("  (surface = "));
        VM.printDec(seenN);
        Uart.write(Magic.bytes(" classes)\n"));
    }

    /**
     * Parse the constant pool with the <em>shared</em> {@link ClassReader} — the same
     * code the seed JVM runs, now compiled into the image by our own compiler (M5).
     * It reads a {@code byte[]}, so the embedded blob is copied onto the heap first;
     * its offsets are classfile-relative, so they still line up with the raw-address
     * access ({@code gView.base + off}) the rest of the loader uses.
     */
    static void parseConstPool(DataReference base, int len)
    {
        // AHEAD OF `useView`, DELIBERATELY: the report names the current view, so a guard placed
        // after the assignment would describe the blob being switched TO and say nothing about the one being
        // clobbered. Here it names the view this call is about to replace, and who else is in the loader.
        if (PARSE_CTX_WATCH)
        {
            checkParseCtxOwned(1);
        }
        pcCalls += 1;
        int ci = 0;
        while (ci < pcN && pcBase.get(ci).NE(base.toAddress()))
        {
            ci += 1;
        }
        pcSteps += ci;                                  // what the scan actually costs, not what its shape suggests
        if (ci < pcN)                                   // cache HIT: reuse the one-time copy + parse for this blob
        {
            // ACQUIRE, AND ITS POSITION IS THE ENTIRE POINT -- it has to sit BETWEEN the two loads.
            // The scan above observed `pcN`; the reads below take the entry that count claims is filled. A
            // barrier orders accesses BEFORE it against accesses AFTER it, so one placed ahead of the scan
            // constrains neither against the other and does nothing at all. There is no dependency to fall
            // back on either: `ci` is a local counter, so the only link from `pcN` to `&pcTab[ci]` is a
            // CONTROL dependency, and AArch64 orders stores after one of those, never loads.
            //
            // Get it wrong and a task observes a PUBLISHED count with an UNPUBLISHED entry and takes
            // `pcTab[ci]` (before increment P2-1, `pcBytes[ci]`) as NULL -- the null-`gbytes` NPE inside
            // `ClassReader.u1` this file records. **THE FIRST CUT OF THIS BARRIER WAS PLACED AHEAD OF THE
            // SCAN**, where it could not meet the requirement its own comment stated, and it shipped through a
            // review that quoted the placement accurately and called it correct. See the release side below.
            Magic.dsb();
            pcHits += 1;
            useView(pcTab[ci]);
            return;
        }
        ClassFileView v = new ClassFileView();
        v.base = base;
        v.bytes = toBytes(base.toAddress(), len);
        v.cpCount = ClassReader.cpCount(v.bytes);
        v.cp = new int[v.cpCount];
        v.cpTag = new int[v.cpCount];
        v.litObj = ObjectReferenceArray.create(v.cpCount);   // per-blob ldc-String intern cache (one object per cp entry)
        v.afterCp = ClassReader.constantPool(v.bytes, v.cp, v.cpTag);   // stable: gp is later reused as a walk cursor
        readHeader(v);
        useView(v);
        // RELEASE-PUBLISH, AND THE ORDER IS THE WHOLE POINT. `pcN` is incremented LAST so a reader either
        // sees the entry complete or does not see it at all -- which is correct on a machine that keeps
        // stores in program order, and this is not one. AArch64 may make the `pcN += 1` visible to another
        // core BEFORE the `pcTab[pcN] = v` beside it, and a reader that lands in that window takes a
        // NULL out of a slot whose count says it is filled. (Before increment P2-1 the entry was SIX stores
        // into parallel arrays -- `pcBytes[pcN] = ...` and its siblings -- each needing that ordering; it is
        // now one reference store of a view built completely beforehand.)
        //
        // THAT IS A CANDIDATE MECHANISM FOR A RECORDED FAILURE THIS FILE COULD NOT EXPLAIN: the contended
        // launcher arm dies at batch 21 with an NPE inside `ClassReader.u1` whose null argument is
        // `gbytes`, and CLAUDE.md states outright that `gbytes` is never WRITTEN null and no mechanism is
        // claimed. It is not written null -- it is READ from an array slot that is not visible yet, which
        // is a different thing and is exactly what an unordered publish produces. Both tasks reaching the
        // same blob is the ordinary case for the launcher: the log names `ctx~java/lang/Object`.
        //
        // FITS, NOT PROVEN. The failure is intermittent (2 in 4 contended runs) and this has not been
        // reproduced against an instrumented boot, so the barrier is justified on its own terms -- an
        // unordered publish read by another core is a defect whether or not it is THAT defect.
        if (pcN < MAXPARSECACHE)                         // remember it: RTA/phase-A/phase-B all re-parse this blob
        {
            pcBase.set(pcN, base.toAddress());
            pcTab[pcN] = v;                              // the view is complete before this one store publishes it
            Magic.dsb();                                 // the key and the view land BEFORE the count
            pcN += 1;                                    //   that makes them reachable (see above)
        }
    }

    /**
     * Make {@code v} the current class: one store of {@link #gView}, plus the parse cursor {@code gp}, which is a
     * walk position rather than part of the view and starts just past the constant pool.
     */
    private static void useView(ClassFileView v)
    {
        gView = v;
        gp = v.base.plus(v.afterCp);
    }

    /**
     * Decode {@code v}'s class header once, as the view is built: access_flags, this/super names, the direct
     * interfaces, and where the methods table starts (past the fields). Reads the blob only -- no loader state.
     */
    private static void readHeader(ClassFileView v)
    {
        Address p = v.base.plus(v.afterCp);
        v.accessFlags = u2(p);
        v.thisNameOff = v.cp[u2(v.base.plus(v.cp[u2(p.plus(2))]))];   // this_class -> Class -> name Utf8 offset
        int superIdx = u2(p.plus(4));
        if (superIdx == 0)
        {
            v.superNameOff = 0;                         // java/lang/Object
        }
        else
        {
            v.superNameOff = v.cp[u2(v.base.plus(v.cp[superIdx]))];
        }
        p = p.plus(6);                                  // access_flags, this_class, super_class
        int icount = u2(p);
        v.implIfName = new int[icount];
        int ii = 0;
        while (ii < icount)
        {
            int ci = u2(p.plus(2 + ii * 2));            // Class entry index
            v.implIfName[ii] = v.cp[u2(v.base.plus(v.cp[ci]))];   // -> interface name Utf8 offset
            ii += 1;
        }
        p = p.plus(2 + icount * 2);
        int fcount = u2(p);
        p = p.plus(2);
        int f = 0;
        while (f < fcount)
        {
            p = skipAttributes(p.plus(8), u2(p.plus(6)));   // field: access(2) name(2) desc(2) count(2), then attributes
            f += 1;
        }
        v.methodsStart = p;
    }

    /** Copy an embedded blob onto the heap so the shared reader can index it. */
    private static byte[] toBytes(Address addr, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = (byte) addr.plus(i).loadUnsignedByte();
            i += 1;
        }
        return b;
    }

    /** Parse the fields, assigning each static field a slot; allocate a zeroed statics block. */
    static void parseFields()
    {
        Address p = gp;
        gBuild = new ClassBuild();                      // published first: superFieldCount records an absent super on it
        superUnregistered = false;
        gBuild.absentSuperOff = 0;
        int islot = superFieldCount();                  // own instance fields sit after inherited ones
        p = p.plus(6);                                         // access_flags, this_class, super_class
        int[] implIfName = gView.implIfName;
        int ii = 0;
        while (ii < implIfName.length)
        {
            if (gBuild.absentSuperOff == 0 && absentSupertype(implIfName[ii]))
            {
                gBuild.absentSuperOff = implIfName[ii];       // a superinterface that can never load: same as a superclass
            }
            ii += 1;
        }
        p = p.plus(2 + implIfName.length * 2);                 // interfaces
        int fcount = u2(p);
        if (superUnregistered && fcount != 0)
        {
            // Own fields would be laid at slot 0, on top of the inherited ones. Harmless for a field-free
            // class (the writer-rooted exceptions), silent whole-object corruption for any other. fcount
            // counts STATIC fields too, so a statics-only subclass warns spuriously -- erring toward saying
            // something, since the failure this catches is invisible.
            Uart.write(Magic.bytes("\n  UNREGISTERED SUPER (fields alias): "));
            printNameAt(gView.base.toAddress(), gView.thisNameOff);
            Uart.write(Magic.bytes(" extends "));
            printNameAt(gView.base.toAddress(), gView.superNameOff);
            Uart.putc(0x0A);
        }
        p = p.plus(2);
        gBuild.sfName = new int[fcount + 1];
        gBuild.sfAccess = new int[fcount + 1];
        gBuild.ifName = new int[fcount + islot + 1];
        gBuild.ifAccess = new int[fcount + islot + 1];
        gBuild.ifDescOff = new int[fcount + islot + 1];
        int[] sfKind = new int[fcount + 1];             // each static's exact kind: its block's bitmaps (S6b-2)
        int slot = 0;
        int f = 0;
        while (f < fcount)
        {
            int access = u2(p);
            int nameIdx = u2(p.plus(2));
            int descIdx = u2(p.plus(4));
            p = p.plus(6);                                     // access, name, descriptor
            if ((access & 0x0008) != 0)
            {
                gBuild.sfName[slot] = gView.cp[nameIdx];    // ACC_STATIC
                gBuild.sfAccess[slot] = access;        // the JIT's volatile barriers read ACC_VOLATILE from here
                sfKind[slot] = TibBuilder.fieldKind(gView.cp[descIdx]);
                slot += 1;
            }
            else
            {
                gBuild.ifName[islot] = gView.cp[nameIdx];   // instance field at the next inherited+own slot
                gBuild.ifAccess[islot] = access;       // reflection: getModifiers / access checks
                gBuild.ifDescOff[islot] = gView.cp[descIdx];// reflection: field type descriptor
                islot += 1;
            }
            int attrs = u2(p);
            p = p.plus(2);
            p = skipAttributes(p, attrs);
            f += 1;
        }
        gBuild.sfCount = slot;
        gBuild.ifCount = islot;                               // total: inherited + own
        VtableLayout.gvCount = 0;                                    // no vtable unless parseVtable runs
        gBuild.statics = Heap.allocStatics(slot);       // zeroed: statics default to 0
        int z = 0;
        while (z < slot)
        {
            Heap.markStatic(gBuild.statics, slot, z, sfKind[z]);   // which slots a precise trace follows
            z += 1;
        }
        gp = p;
    }


    /**
     * Instance-field count of the superclass -- own fields are laid out after it. 0 when the super is
     * {@code java/lang/Object} (nothing to inherit).
     *
     * <p>A NAMED SUPERCLASS THAT IS NOT REGISTERED WOULD BE A LAYOUT ERROR -- answering 0 puts this class's
     * own fields ON TOP of the inherited ones, and every field of both then aliases the other. It is only an
     * error if this class DECLARES a field, though: a field-free subclass of an unlaid-out super is the
     * normal, deliberate arrangement for the writer-rooted exception classes (see {@code vm/MyExc}), which
     * is why the report lives at the layout site below and not here.
     */
    private static boolean superUnregistered;

    /**
     * The superclass is not merely unregistered but ABSENT: no bytes in the classDir or the classpath jar
     * ({@link VM#dirBytes}), and not denylisted (a denylisted superclass is the deliberate, documented
     * constructor-skip case). Unregistered-but-present is normal and must stay allowed: a superclass pulled later
     * in the same load prints the UNREGISTERED SUPER line harmlessly (`Integer extends Number` at boot).
     *
     * <p>Such a class used to register anyway. Its fields aliased the superclass's, the superclass constructor
     * was skipped (`CTOR SKIPPED ... ABSENT from the classDir; NOT intended`), and the next call through the
     * superclass took a WILD BRANCH -- `new URL("https:..").openConnection()` went BOOT RE-ENTERED through
     * HttpsURLConnectionImpl, whose javax/net/ssl superclass the image does not ship.
     *
     * <p>The same holds for a direct SUPERINTERFACE (see {@link #absentSupertype}): a class implementing an interface
     * that cannot load was constructed with {@code getInterfaces().length == 0}. The absent name's offset in
     * {@code gView.base} is recorded as {@link ClassBuild#absentSuperOff} (0 if none).
     *
     * <p>Answers whether the supertype named at {@code nameOff} in {@code gView.base} can never load.
     */
    private static boolean absentSupertype(int nameOff)
    {
        return nameOff != 0
                && ClassRegistry.classRegByName(nameOff) < 0
                && VM.dirBytes(gView.base.plus(nameOff + 2L), u2(gView.base.plus(nameOff))).isZero()
                && !isDenylisted(gView.base.toAddress(), nameOff);
    }

    static int superFieldCount()
    {
        int r = ClassRegistry.classRegByName(gView.superNameOff);
        if (r >= 0)
        {
            return ClassRegistry.clTab[r].fieldCount;
        }
        superUnregistered = gView.superNameOff != 0
                && !Utf8.utf8IsAtBase(gView.base.toAddress(), gView.superNameOff, Magic.bytes("java/lang/Object"));
        if (superUnregistered && absentSupertype(gView.superNameOff))
        {
            gBuild.absentSuperOff = gView.superNameOff;
        }
        return 0;
    }

    /** A method goes in the vtable if it is instance, non-private, and not a constructor. */
    static boolean isVirtual(int access, int nameOff)
    {
        if ((access & 0x0008) != 0)
        {
            return false;                               // static
        }
        if (Utf8.isName(gView.base.toAddress(), nameOff, 0x3C696E69743EL, 6))
        {
            return false;                               // "<init>"
        }
        if (Utf8.isName(gView.base.toAddress(), nameOff, 0x3C636C696E69743EL, 8))
        {
            return false;                               // "<clinit>"
        }
        // PRIVATE instance methods get a vtable slot too: javac (11+ nestmates) compiles a private-method
        // call as invokevirtual, so it must resolve through the vtable like any other. (They still never
        // override a superclass slot in practice; a same-name+desc collision is a rare non-issue.)
        return true;
    }


    /** Slot address of a static field declared in another loaded class, matched by class+name, or 0. */
    // Print the CELL a static reference binds to, from both the compiled path (`staticaddr`) and the
    // reflective one (`reflectcell`). Off by default. Comparing the two is what located the same-class
    // fast-path bug below: a field read as null by compiled code and non-null by reflection is two different
    // cells, and no amount of reading either path alone distinguishes that from a value that really is null.
    static final boolean STATIC_ADDR_LOG = false;


    // ---- deferred class pulls: collect during a lazy compile, load AFTER it, recompile once -------------
    // The twin of lzInitReg/drainPendingInit above, for the class a getstatic NAMES rather than the class it
    // must initialize. Same reason for deferring: loading mid-compile can collect the in-progress buffer.
    private static final int MAXPENDPULL = 32;
    private static AddressArray lzPullU;
    static int lzPullN;
    // The length-prefixed names notePullNeededElement BUILT (an array's element class has no Utf8 entry of its
    // own to point at). Held here, as traced byte[]s, so the payload addresses in lzPullU stay valid until
    // drainPendingPulls has copied them; reset whenever the pull list is empty.
    private static byte[][] lzPullKeep;
    private static int lzPullKeepN;
    static boolean lzRetried;
    /** Bounds the ON-DEMAND compile paths to one pull-and-retry each. Separate from {@link #lzRetried},
     *  which those paths save and restore around their own attempt so a nested compile is unaffected. */
    static boolean odRetried;

    /** Note that the body being compiled reads a static of the (unloaded) class named at {@code clsU}.
     *  False if it is not worth retrying for -- absent from the classDir, denylisted, or the list is full. */
    static boolean notePullNeeded(Address clsU)
    {
        if (lzPullN >= MAXPENDPULL || isDenylisted(clsU, 0))
        {
            return false;                               // a denylisted class must stay absent: reading null
        }                                               //   is the intended outcome, not a closure to widen
        int i = 0;
        while (i < lzPullN)
        {
            if (lzPullU.get(i).EQ(clsU))
            {
                return true;                            // already noted (the same site is visited twice)
            }
            i += 1;
        }
        if (lzPullU == null)
        {
            lzPullU = AddressArray.create(MAXPENDPULL);
        }
        lzPullU.set(lzPullN, clsU);
        lzPullN += 1;
        return true;
    }

    /** Demand-load every class noted during the compile that just finished. Snapshots and CLEARS the list
     *  first: a pull runs initializers, which can re-enter the compiler and note pulls of their own. */
    static void drainPendingPulls()
    {
        // EVERY NAME IS COPIED BEFORE ANYTHING LOADS. A load runs initializers, which can re-enter the compiler
        // and note pulls of their own -- and a note made while the list is empty resets lzPullKeep, which would
        // drop a built element name this loop had not read yet.
        int n = lzPullN;
        byte[][] names = new byte[n][];
        int i = 0;
        while (i < n)
        {
            Address u = lzPullU.get(i);
            int len = u2(u);
            byte[] slash = new byte[len];
            int k = 0;
            while (k < len)
            {
                slash[k] = (byte) u1(u.plus(2 + k));
                k += 1;
            }
            names[i] = slash;
            i += 1;
        }
        lzPullN = 0;
        i = 0;
        while (i < n)
        {
            byte[] slash = names[i];
            if (ClassRegistry.classIndexByName(slash) < 0)
            {
                if (LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  staticresolve "));
                    Uart.write(slash);
                    Uart.putc(0x0A);
                }
                DataReference pulled = loadClassIncremental(slash);
            }
            i += 1;
        }
    }


    /** {@link #isDenylisted} for a class name that is an {@code n}-byte slice at {@code base+off}, not a Utf8 entry. */
    static boolean isDenylistedSlice(Address base, int off, int n)
    {
        byte[] b = new byte[n + 2];
        b[0] = (byte) (n >> 8);
        b[1] = (byte) n;
        int k = 0;
        while (k < n)
        {
            b[k + 2] = (byte) u1(base.plus(off + k));
            k += 1;
        }
        return isDenylisted(Address.fromObject(b).plus(24L), 0);
    }

    /**
     * {@link #notePullNeeded} for the element class of an ARRAY type check, whose name is a slice of the array
     * descriptor ({@code n} bytes at {@code base+off}) rather than a Utf8 entry of its own. The length-prefixed
     * name is built as a byte[] and held in {@link #lzPullKeep} until the drain copies it.
     */
    static boolean notePullNeededElement(Address base, int off, int n)
    {
        if (lzPullN == 0)
        {
            lzPullKeepN = 0;                            // nothing pending refers to an earlier built name
        }
        if (lzPullKeep == null)
        {
            lzPullKeep = new byte[MAXPENDPULL][];
        }
        if (lzPullKeepN >= MAXPENDPULL)
        {
            return false;
        }
        byte[] b = new byte[n + 2];
        b[0] = (byte) (n >> 8);
        b[1] = (byte) n;
        int k = 0;
        while (k < n)
        {
            b[k + 2] = (byte) u1(base.plus(off + k));
            k += 1;
        }
        int j = 0;
        while (j < lzPullKeepN)                         // the same element noted twice: reuse its name
        {
            if (Utf8.sameBytes(lzPullKeep[j], b))
            {
                return notePullNeeded(Address.fromObject(lzPullKeep[j]).plus(24L));
            }
            j += 1;
        }
        lzPullKeep[lzPullKeepN] = b;
        lzPullKeepN += 1;
        return notePullNeeded(Address.fromObject(b).plus(24L));   // byte[] elements start 24 bytes in
    }

    /** One permanently-zero word, shared by every static a late compile could not resolve. */
    static long unresolvedStaticCell()
    {
        if (unresStaticCell.isZero())
        {
            unresStaticCell = Heap.allocData(8);
            unresStaticCell.store(0L);         // allocData does NOT zero
        }
        return unresStaticCell.toLong();
    }

    /**
     * A {@code putstatic} that landed in the unresolved-static cell SILENTLY LOST ITS VALUE -- say so.
     *
     * <p>{@code patchRelocs} binds an unresolved static site to a shared permanently-zero cell so the access
     * reads null instead of ADDRESS 0 (the firmware shim, whose instruction words come back as a plausible
     * value). That is the right answer for a READ and the WRONG one for a WRITE: the store goes to a cell
     * nothing ever reads, the field stays null for the life of the VM, and nothing says a word. The site is
     * re-patched correctly at a later batch, so the READ that follows finds the real cell -- holding null.
     *
     * <p>THE CELL IS SUPPOSED TO STAY ZERO FOR EVER, which is what makes this detector sound rather than
     * heuristic: it exists only so reads answer null, so a NON-ZERO value in it is proof that a store
     * executed and was discarded. It cannot cry wolf on a passing boot.
     *
     * <p>Chased here because a JUnit {@code <clinit>} performs two stores -- {@code putstatic
     * executableInvoker} (which demonstrably took effect) and {@code putstatic defaultInterceptorCall} (which
     * read back null) -- and a lost store is the only mechanism that explains one working and the next not.
     * Re-zeroed after reporting so a second loss is a second report rather than a latch.
     */
    private static void reportLostStaticStore()
    {
        if (unresStaticCell.isZero())
        {
            return;
        }
        long v = unresStaticCell.loadLong();
        if (v == 0L)
        {
            return;
        }
        Uart.write(Magic.bytes("\n  STATIC STORE LOST: a putstatic bound to the unresolved-static cell discarded "));
        VM.printHex(v);
        Uart.write(Magic.bytes(" -- that field reads null for ever; batch "));
        VM.printDec(cumBatches);
        Uart.putc(0x0A);
        unresStaticCell.store(0L);             // re-arm: a second loss is a second report
    }

    /**
     * Name a static site bound to the zero cell WHEN ITS CLASS IS ALREADY LOADED -- a registration gap that no
     * later batch will repair, so the access reads null for ever. Once per class+field, through the same
     * dedupe table the other static reports use: a hot site would otherwise repeat it per batch.
     *
     * <p>Silent when the class is merely not loaded yet, which is the ordinary and self-correcting case.
     */
    static void reportZeroCellBind(Address refBase, int classOff, int nameOff)
    {
        if (DynamicLinker.regBySigU(refBase.plus(classOff)) < 0)
        {
            return;                                     // class not loaded yet: a later batch resolves it
        }
        long key = refBase.plus((long) classOff + (long) nameOff).toLong();
        if (unresStaticSeen == null)
        {
            unresStaticSeen = new long[64];
            unresStaticSeenN = 0;
        }
        int k = 0;
        while (k < unresStaticSeenN)
        {
            if (unresStaticSeen[k] == key)
            {
                return;
            }
            k += 1;
        }
        if (unresStaticSeenN < unresStaticSeen.length)
        {
            unresStaticSeen[unresStaticSeenN] = key;
            unresStaticSeenN += 1;
        }
        Uart.write(Magic.bytes("\n  STATIC BOUND TO THE ZERO CELL (reads null for ever; class IS loaded): "));
        printNameAt(refBase, classOff);
        Uart.putc(0x2E);
        printNameAt(refBase, nameOff);
        Uart.putc(0x0A);
    }

    private static DataReference unresStaticCell;   // a root: a data reference (frames/GC arc S6d)

    static long[] unresStaticSeen;
    static int unresStaticSeenN;

    /**
     * Name the class+field a late compile could not resolve -- silence here is what made this unfindable.
     * ONCE per field: the same unresolved static is re-reported on every method that reads it (88 lines for
     * two fields on one boot), and a log nobody reads is the state this bug lived in.
     */
    static void reportUnresolvedStatic(int idx)
    {
        long key = gView.base.plus(refClassNameOff(idx) + (long) mrefNameOff(idx)).toLong();
        if (unresStaticSeen == null)
        {
            unresStaticSeen = new long[64];
            unresStaticSeenN = 0;
        }
        int k = 0;
        while (k < unresStaticSeenN)
        {
            if (unresStaticSeen[k] == key)
            {
                return;
            }
            k += 1;
        }
        if (unresStaticSeenN < 64)
        {
            unresStaticSeen[unresStaticSeenN] = key;
            unresStaticSeenN += 1;
        }
        Address clsU = gView.base.plus(refClassNameOff(idx));
        Uart.write(Magic.bytes("\n  UNRESOLVED STATIC (reads null): "));
        printNameAt(clsU, 0);
        Uart.putc(0x2E);
        printNameAt(gView.base.plus(mrefNameOff(idx)), 0);
        Uart.write(Magic.bytes(" -- "));
        printWhyUnpulled(clsU);
        Uart.write(Magic.bytes("; needed by "));
        printCompiling();
        Uart.putc(0x0A);
    }

    /**
     * Say WHY the class behind an unresolved static is absent, instead of asserting a cause. This line used
     * to read "class never pulled" unconditionally -- a message that states a diagnosis it never checked, so
     * a class that WAS pulled but whose statics never reached {@code sgTab} reported as if the loader had
     * never heard of it. Each arm below is a different bug with a different fix, and they were indistinguish-
     * able on the wire.
     */
    static void printWhyUnpulled(Address clsU)
    {
        if (isDenylisted(clsU, 0))
        {
            Uart.write(Magic.bytes("DENYLISTED (reading null is intended)"));
            return;
        }
        boolean registered = DynamicLinker.regBySigU(clsU) >= 0;
        // Ask the IMAGE DIRECTORY, not classIndexByName -- that searches the LOADED registry (clTab), so it
        // answers "not loaded", which is the question we are already asking with regBySigU. The first cut of
        // this instrument used it and reported "absent from the classDir" for a class the image plainly
        // carries. An instrument that conflates two tables is worse than none: it fabricates a cause.
        boolean inDir = !VM.dirBytes(clsU.plus(2L), u2(clsU)).isZero();
        if (registered)
        {
            // The pull worked and the class is live, so the hole is in static REGISTRATION, not in loading:
            // adoptStatics/phase A never gave this class an sgTab entry. Nothing here widens the closure.
            Uart.write(Magic.bytes("class IS REGISTERED but has no static cell (registration gap)"));
        }
        else if (!inDir)
        {
            Uart.write(Magic.bytes("class absent from the classDir (nothing can pull it)"));
        }
        else if (lzRetried)
        {
            // The one retry a lazy compile gets was already spent on some OTHER class this same body named.
            Uart.write(Magic.bytes("in the classDir; the body's single retry was already spent"));
        }
        else if (!lzCompiling)
        {
            // compileReuseTib says "late compile", but the pull path needs lzCompiling -- so this getstatic
            // is being resolved outside the compile() the retry brackets (a stub mint, a reloc pass).
            Uart.write(Magic.bytes("in the classDir, but resolved outside the retry window"));
        }
        else
        {
            Uart.write(Magic.bytes("in the classDir; pull declined (list full?)"));
        }
    }

    /** From {@code gp} (at the methods), find the sought method (the {@code seek} key); null if absent. */
    static FoundMethod findMethod(Address base)
    {
        Address p = gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            p = p.plus(2);                                     // access_flags
            int nameIdx = u2(p);
            int descIdx = u2(p.plus(2));
            p = p.plus(4);
            int attrs = u2(p);
            p = p.plus(2);
            if (Utf8.isName(base, gView.cp[nameIdx], gnameP, gnameLen)
                    && Utf8.isName(base, gView.cp[descIdx], gdescP, gdescLen))
            {
                long code = findCode(base, p, attrs);
                if (code != 0L)
                {
                    return new FoundMethod(code, gView.cp[descIdx], access);
                }
            }
            else
            {
                p = skipAttributes(p, attrs);
            }
            m += 1;
        }
        return null;
    }

    /** Find the Code attribute among {@code attrs} at {@code p}; return the bytecode address (0 if none). Its
     *  length and max_locals are read back from that address: {@link ClassBytes#codeLength}, {@link ClassBytes#maxLocals}. */
    static long findCode(Address base, Address p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4);
            if (Utf8.isName(base, gView.cp[anIdx], 0x436f6465L, 4))              // "Code"
            {
                return p.plus(8).toLong();
            }
            p = p.plus(alen);
            a += 1;
        }
        return 0L;
    }

    static Address skipAttributes(Address p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            p = p.plus(2);
            int alen = u4(p);
            p = p.plus(4 + alen);
            a += 1;
        }
        return p;
    }

    /** Compare the Utf8 at {@code off} against {@code expected} (bytes packed big-endian, {@code len} bytes). */
    /** {@link #gView}'s base for the fault report ({@code VM.stashFaultRegs}): which blob the parser was reading. */
    static long gbaseForReport()
    {
        ClassFileView v = gView;                         // null before the first parse of a boot, and a fault can come first
        if (v == null)
        {
            return 0L;
        }
        return v.base.toLong();
    }


    // ----- on-metal JIT: drive the shared compiler/Baseline core over each method --
    // A method and its callees form a small program; we assign every reachable
    // method a buffer before emitting any, so invokestatic's BL targets are known
    // without compiling nested-and-reentrant (the shared static compile state and
    // the writer-side 10-local ceiling both make on-the-fly recursion awkward).
    private static final int MAXM = 512;
    static long[] mCode;      // each reachable method's bytecode address
    static int[] mLen;        // ... and its length
    static CodeArray mBuf;       // ... and the buffer assigned to it
    private static CodeArray mEnd;       // ... and the END of what emitMethod actually wrote there (0 = nothing,
                                      //     either deferred -- the stub publishes itself -- or not emitted)
    static CodeArray mLine;      // ... its line table addr (built in emitMethod; registerAll copies -> rgLine)
    static AddressArray mSrc; // ... its class's SourceFile filename Utf8 addr (-> rgSrc)
    static int[] mLocals;     // ... its max_locals
    static int[] mDescOff;    // ... its descriptor Utf8 offset (for the shared core's prologue)
    static int[] mStatic;     // ... 1 if static
    static int[] mSync;       // ... 1 if ACC_SYNCHRONIZED
    static int[] mDefer;      // M8 defer: 1 if this method's compile is deferred to first call (gated)
    static int mCount;

    /** The on-metal symbol seam: resolves the shared Baseline core's references to addresses. */
    private static final MetalSymbols METAL_SYMBOLS = new MetalSymbols();


    /**
     * Compile the entry method and every static method it transitively calls,
     * then return the entry's buffer. Three flat passes — discover (BFS the call
     * graph), place (size each method and hand it a buffer), emit (now every BL
     * target address is known) — so no method's compile nests inside another's.
     * Scope: same-class static callees, no recursion/cycles beyond dedup.
     */
    /**
     * Report if the compiler is entered WITHOUT the loader lock. Ungated: this is silent cross-task
     * corruption, not a degraded answer.
     *
     * <p>The entire compile context is STATIC -- {@code g*}, the {@code m*} tables --
     * so two tasks inside {@code compile()} at once interleave into each other's state. The first symptom
     * is a body compiled against SOMEBODY ELSE'S max_locals (`LOCALS UNDERSIZED`), or a report printing raw
     * constant-pool bytes where a class name should be, because {@code gView.base} moved under it. This VM has
     * had exactly that bug once already: four paths reached the compiler unlocked and it surfaced as an NPE
     * in {@code Baseline.s4} that looked nothing like a locking bug.
     *
     * <p>It names the TASK, the recorded owner, the depth and the site, because "unlocked" on its own does
     * not name the path -- and a path is what a fix needs. Said a few times rather than once: the first
     * offender is not necessarily the only one.
     *
     * <p>BOTH BUDGETS ARE CAPPED, and the COLLISION one is the one that matters. An earlier cut capped only
     * the benign entries, on the reading that a collision is rare enough to always print. It is not: this
     * runs at the entry of {@code compile()}, i.e. once per METHOD -- thousands of times a batch -- and a
     * collision is not a one-shot event, because the competing task is still inside the compiler for the
     * next several thousand calls. One line is ~110 characters and {@code Uart.putRaw} SPINS on the TX
     * holding register, so at 115200 baud that is ~9.6ms of BLOCKING serial each. An uncapped collision
     * report therefore starves the run it is meant to diagnose AND widens the very window it is reporting
     * on. Each budget says so when it is spent, because a report that goes quiet without a word reads
     * exactly like a condition that stopped.
     *
     * <p>NOTE THE PRECONDITION IT CANNOT CHECK: {@code loaderLock} used to be a NO-OP while {@code smpSched
     * == 0}, on the premise "one core in the table: no other compiler to race". That premise is about CORES
     * and the hazard is about TASKS -- a single core still time-slices preemptively -- and the Pi caught it
     * biting in the dining philosophers. The gate is {@code VM.loaderLockLive} now: live whenever core 0's
     * tick is armed too. It is still inert on one core with the tick off, and this guard is silent there.
     */
    private static final int SAID_MAX_BENIGN = 12;       // owner == -1: nobody to race, so a handful is plenty
    private static final int SAID_MAX_COLLIDE = 8;       // owner is ANOTHER TASK: rare per event, but it repeats per method
    private static int compileUnlockedSaid;
    private static int compileCollideSaid;               // capped SEPARATELY: see the note above on the per-method rate
    static int compileUnlockedN;                         // entries with the lock NOT ours (benign at boot: nobody else compiles)
    static int compileCollideN;                          // ... of those, the ones where ANOTHER TASK HELD IT: two compilers at once

    private static void checkCompileLockHeld(int where)
    {
        if (!VM.loaderLockLive() || VM.loaderOwner == VM.curTask())
        {
            return;                                      // held by us (possibly re-entrant), or the lock is inert
        }
        compileUnlockedN += 1;
        // THE COLLISION, as opposed to the merely unlocked. owner == -1 is "nobody holds it", which at boot
        // means no competitor exists and is why this guard fires on a PASSING run. owner >= 0 and not us is
        // the real hazard: another task is inside the compiler right now, and the static context is shared.
        boolean collide = VM.loaderOwner >= 0;
        // The TOTALS are counted before either budget is consulted, so a capped report still leaves an
        // accurate n=/collide= on the last line it does print.
        if (collide)
        {
            compileCollideN += 1;
            if (compileCollideSaid >= SAID_MAX_COLLIDE)
            {
                return;
            }
            compileCollideSaid += 1;
        }
        else
        {
            if (compileUnlockedSaid >= SAID_MAX_BENIGN)
            {
                return;
            }
            compileUnlockedSaid += 1;
        }
        if (collide)
        {
            Uart.write(Magic.bytes("\n  *** TWO COMPILERS AT ONCE ***"));
        }
        Uart.write(Magic.bytes(" n="));
        VM.printDec(compileUnlockedN);
        Uart.write(Magic.bytes(" collide="));
        VM.printDec(compileCollideN);
        Uart.write(Magic.bytes("\n  COMPILE WITHOUT THE LOADER LOCK (the static context can be clobbered): "));
        // CAPPED, because this line runs in the one state where the length word cannot be trusted.
        // printNameAt loops for whatever u2 it reads, and the condition being reported IS "gView.base moved under
        // the compiler" -- so a garbage length of 65535 is ~5.7 SECONDS of blocking UART plus a read far past
        // the blob, burying the failure this exists to name. Not hypothetical: printUtf8Capped's own doc
        // records a diagnostic that streamed several hundred bytes of the stub table onto the console for
        // exactly this reason. It prints <null>/<bad utf8 ptr> itself, which says more than printing nothing.
        printUtf8Capped(gView != null ? gView.base.plus(gView.thisNameOff) : Address.zero());
        Uart.write(Magic.bytes(" at="));
        VM.printDec(where);
        Uart.write(Magic.bytes(" task="));
        VM.printDec(VM.curTask());
        Uart.write(Magic.bytes(" owner="));
        VM.printDec(VM.loaderOwner);
        Uart.write(Magic.bytes(" depth="));
        VM.printDec(VM.loaderDepth);
        Uart.write(Magic.bytes(" site="));
        VM.printDec(VM.loaderLockSite);
        Uart.putc(0x0A);
        // A BUDGET THAT RUNS OUT SILENTLY READS AS A CONDITION THAT STOPPED. The n=/collide= totals on this
        // very line are the last ones a boot will see for this kind, so name the cap here rather than leave a
        // reader to infer it from the report thinning out.
        if (collide && compileCollideSaid == SAID_MAX_COLLIDE)
        {
            Uart.write(Magic.bytes("  (collision reports capped here; collide= above is the last shown)\n"));
        }
        else if (!collide && compileUnlockedSaid == SAID_MAX_BENIGN)
        {
            Uart.write(Magic.bytes("  (benign unlocked reports capped here; n= above is the last shown)\n"));
        }
    }

    // ----- THE PARSE-CONTEXT GUARD: who is touching the shared view, and did they take the lock -----
    //
    // `checkCompileLockHeld` above sits at `compile()`/`compileClass()`, so it can only ever see the two
    // COMPILER entries -- and that is not where the open failure is. The contended launcher arm dies at
    // batch 21 with an NPE inside `ClassReader.u1` whose null argument is `gbytes`, WHILE the stuck-lock
    // report says another task holds the lock doing a lazy compile. Main cannot be blocked on the lock and
    // NPE at the same time, so main reached the shared view on a path that takes no lock at all, and
    // `ClassReader.refNameOff`/`u1` are reached from resolution paths outside the two entries that guard
    // covers.
    //
    // TWENTY such paths were already found BY READING (the VMNatives reflection wrappers, 20 -> 0) and
    // closing them did not end it. So this one goes on the STATE rather than on the compiler: every
    // re-point of the view, plus the cp-ref readers the failing trace actually named.
    //
    // GATED, where `checkCompileLockHeld` is not, and the asymmetry is deliberate. That one ships because it
    // is SILENT on a passing boot. This one sits on the hottest path in the loader -- `gView` is read at
    // ~765 sites -- and THIS VM'S BASELINE COMPILER DOES NOT INLINE, so an always-on call
    // per constant-pool reference is exactly the per-item cost this file has had to strip twice (the
    // inflater paid a non-inlined call per BIT and it was worth 16.2x). `PARSE_CTX_WATCH` is `static final`,
    // so javac's conditional compilation (JLS 14.21) emits NOTHING for a guarded body and RTA never marks
    // this method reachable: an ARMED boot pays for it and a shipped one cannot.
    static final boolean PARSE_CTX_WATCH = false;
    private static int parseCtxNullSaid;
    private static int parseCtxUnlockedSaid;
    private static int parseCtxCollideSaid;
    static int parseCtxNullN;        // the view was read with a NULL gbytes -- the recorded arm-3 crash
    static int parseCtxUnlockedN;    // the view was touched with the loader lock NOT ours
    static int parseCtxCollideN;     // ... of those, the ones where ANOTHER TASK held it

    /**
     * Report an unguarded touch of the shared parse view. {@code where}: 1 = parseConstPool (the WRITER --
     * this call re-points the view), 2 = mrefNameOff, 3 = refClassNameOff, 4 = mrefDescOff, 5 = the
     * Fieldref name read in the static-resolution path. 2 and 5 are the two that reach
     * {@code ClassReader.refNameOff}, which is the frame the failing arm named.
     */
    static void checkParseCtxOwned(int where)
    {
        // THE NULL COMES FIRST, AHEAD OF THE OWNERSHIP RETURN, because a null view is the recorded CRASH
        // rather than a hazard: the caller is about to hand it to ClassReader and take the NPE inside `u1`.
        // It has to be reported even when the lock IS ours -- that would be a different and worse defect
        // than an unlocked touch, and the early return below would have hidden it completely.
        //
        // READERS ONLY (`where >= 2`), AND THE FIRST ARMED BOOT IS WHY. At the WRITER site the view is
        // legitimately empty on the very first call of a boot -- nothing has been parsed yet -- and
        // `parseConstPool` is about to ESTABLISH it, so asking whether the PREVIOUS view was null is not a
        // question about anything. Armed without this the guard fired exactly once per boot, at batch 1,
        // reporting `gbase=0x0 ctx~<null>`: a false positive on a passing run, which this file's own
        // standing rule calls worse than no report at all. The readers are where a null is real, because
        // they are one instruction from handing it to ClassReader.
        if (where >= 2 && gView == null)
        {
            parseCtxNullN += 1;
            if (parseCtxNullSaid < SAID_MAX_COLLIDE)
            {
                parseCtxNullSaid += 1;
                Uart.write(Magic.bytes("\n  *** PARSE VIEW IS NULL (gView) -- THIS IS THE ClassReader.u1 NPE ***\n  "));
                parseCtxWhere(where);
                // Before P2-2 this also printed gbase, because gbase was assigned ahead of the cache scan and a
                // non-zero gbase beside a null gbytes was a TORN view. The view is one reference now, published
                // whole, so a torn view cannot exist and only "no view at all" is left to report.
                Uart.putc(0x0A);
                if (parseCtxNullSaid == SAID_MAX_COLLIDE)
                {
                    Uart.write(Magic.bytes("  (null-view reports capped here; nulls= above is the last shown)\n"));
                }
            }
        }
        if (!VM.loaderLockLive() || VM.loaderOwner == VM.curTask())
        {
            return;                                      // held by us (possibly re-entrant), or the lock is inert
        }
        parseCtxUnlockedN += 1;
        // Same split as checkCompileLockHeld, and for the same reason: owner == -1 is "nobody holds it",
        // which at boot means no competitor exists. owner >= 0 and not us is the real hazard -- another task
        // is in the loader right now and this call is reading or moving the view it is standing on.
        boolean collide = VM.loaderOwner >= 0;
        if (collide)
        {
            parseCtxCollideN += 1;
            if (parseCtxCollideSaid >= SAID_MAX_COLLIDE)
            {
                return;
            }
            parseCtxCollideSaid += 1;
            Uart.write(Magic.bytes("\n  *** PARSE VIEW TOUCHED WHILE ANOTHER TASK HOLDS THE LOADER LOCK ***\n  "));
        }
        else
        {
            if (parseCtxUnlockedSaid >= SAID_MAX_BENIGN)
            {
                return;
            }
            parseCtxUnlockedSaid += 1;
            Uart.write(Magic.bytes("\n  PARSE VIEW TOUCHED WITH NO LOADER LOCK HELD\n  "));
        }
        parseCtxWhere(where);
        Uart.putc(0x0A);
        if (collide && parseCtxCollideSaid == SAID_MAX_COLLIDE)
        {
            Uart.write(Magic.bytes("  (collision reports capped here; collide= above is the last shown)\n"));
        }
        else if (!collide && parseCtxUnlockedSaid == SAID_MAX_BENIGN)
        {
            Uart.write(Magic.bytes("  (unlocked reports capped here; n= above is the last shown)\n"));
        }
    }

    /**
     * The shared tail of every parse-context report: the site, the lock state, and the running totals.
     * Split out so a capped report still carries an accurate n=/collide=/nulls= on the last line it prints.
     */
    private static void parseCtxWhere(int where)
    {
        Uart.write(Magic.bytes("at="));
        VM.printDec(where);
        Uart.write(Magic.bytes(" task="));
        VM.printDec(VM.curTask());
        Uart.write(Magic.bytes(" owner="));
        VM.printDec(VM.loaderOwner);
        Uart.write(Magic.bytes(" depth="));
        VM.printDec(VM.loaderDepth);
        Uart.write(Magic.bytes(" site="));
        VM.printDec(VM.loaderLockSite);
        Uart.write(Magic.bytes(" n="));
        VM.printDec(parseCtxUnlockedN);
        Uart.write(Magic.bytes(" collide="));
        VM.printDec(parseCtxCollideN);
        Uart.write(Magic.bytes(" nulls="));
        VM.printDec(parseCtxNullN);
        Uart.write(Magic.bytes(" ctx~"));
        // CAPPED, NEVER printNameAt, for the reason three diagnostics here have already had to be corrected:
        // this line runs in the one state where the length word cannot be trusted -- the condition being
        // reported IS "gView.base moved under a reader" -- so a garbage u2 of 65535 is ~5.7 SECONDS of blocking
        // UART plus a read far past the blob, burying the failure this exists to name.
        printUtf8Capped(gView != null ? gView.base.plus(gView.thisNameOff) : Address.zero());
    }

    /** {@code isSync} is MANDATORY rather than defaulted: dropping it compiles a synchronized method with no
     *  monitor, which is silent and is the exact bug ACC_SYNCHRONIZED support exists to fix. */
    static long compile(long code, int len, int descOff, int isStatic, int isSync)
    {
        checkCompileLockHeld(1);
        allocMethodTables();
        ClassInitializer.resetInitGuardMemo();                           // the guard decision is per COMPILE: both passes below
                                                        //   must agree, and sizeMethod can demand-load
        addMethod(code, len, ClassBytes.maxLocals(code), descOff, isStatic, isSync);
        int i = 0;
        while (i < mCount)                              // discover
        {
            scanCallees(mCode[i], mLen[i]);
            i += 1;
        }
        i = 0;
        while (i < mCount)                              // place
        {
            sizeMethod(i);
            i += 1;
        }
        if (!compileReuseTib)                           // two-phase clinit: the class's TIB already exists (clTab[reg].tib,
        {                                               // pinned in phase A) and gets filled by loadBodies' compileClass
            TibBuilder.buildTib();                                 // -- rebuilding here would allocate a THROWAWAY TIB and leave
        }                                               // gBuild.tib pointing at it, so compileClass fills the wrong TIB.
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        publishEmitted();                               // the bodies this wrote, NOT the whole arena
        return mBuf.get(0);
    }
    // When set, compile() reuses the caller's already-allocated gBuild.tib (two-phase clinit) instead of rebuilding it.
    //
    // Every setter RESTORES the value it found rather than clearing it, because compiles nest: lazyCompileLocked's
    // pull-and-retry drains pending pulls, each load's loadBodies used to end with `= false`, and the retry then
    // compiled with the flag clear -- so buildTib gave the class a THROWAWAY Type and TIB, and every
    // self-reference in that body baked them. `new InetAddress[]{a}` inside InetAddress.getAllByName became an
    // array of an unregistered Type, and storing an Inet4Address into it threw ArrayStoreException.
    static boolean compileReuseTib;

    /**
     * Compile <em>every</em> method of the current class into its own buffer (in
     * this class's context), then publish. Used for cross-class loading: each class
     * is compiled whole so its methods can be registered and linked from others.
     * Cross-class calls in the body resolve through the global registry, so classes
     * a method depends on must be compiled+registered first (no cross-class cycles).
     */
    private static void compileClass(DataReference bytes)
    {
        long cc0 = Magic.readCNTPCT_EL0();
        checkCompileLockHeld(2);
        allocMethodTables();
        boolean isInterface = gView.isInterface();      // taken once: compiling below can demand-load and re-point gView
        Address p = gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)                              // seed all of the class's methods
        {
            int attrs = u2(p.plus(6));
            long code = findCode(bytes.toAddress(), p.plus(8), attrs);
            // A stub-blob's own virtual methods are kept even when RTA prunes them, so the class's vtable has
            // no holes -- but STUB ONLY, so nothing of theirs is pulled into this batch (see stubBlob). A
            // NATIVELY INSTANTIATED class needs the same treatment for a different reason (see there).
            boolean holeFree = (gView.base.toLong() == gStubBlob && gStubBlob != 0L)
                    || Closure.nativelyInstantiated(gView.base.toAddress(), gView.thisNameOff);
            boolean stubOnly = holeFree && code != 0L
                    && !Closure.isReach(code) && isVirtual(u2(p), gView.cp[u2(p.plus(2))]);
            if (code != 0L && (markActive == 0 || Closure.isReach(code) || stubOnly))
            {
                int isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                if (LazyCompiler.phaseACelled(gView.cp[u2(p.plus(2))], gView.cp[u2(p.plus(4))]))
                {
                    // Metadata-only: this static already has a structure-time cell (armPhaseACells), so it is
                    // never sized, emitted or registered here -- it compiles on first call through that cell.
                }
                else
                {
                    addMethod(code, ClassBytes.codeLength(code), ClassBytes.maxLocals(code), gView.cp[u2(p.plus(4))], isStatic,
                              (u2(p) & 0x0020) != 0 ? 1 : 0);   // ACC_SYNCHRONIZED
                    // Everything else takes the other route to the same engine: a deferral stub standing in
                    // for the body (statics went the cell way above). EVERY method kind defers now,
                    // `<init>` included -- see notInit, which is retired.
                    boolean defer = stubOnly                    // a stub-blob virtual: NEVER compile the body here
                            || LazyCompiler.stage2Gated(gView.base.toAddress(), gView.thisNameOff);
                    if (defer && mCount > 0 && mCode[mCount - 1] == code)
                    {
                        mDefer[mCount - 1] = 1;         // compile this method on first call, not now
                    }
                }
            }
            p = skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        long cc1 = Magic.readCNTPCT_EL0();
        ccSeedT += cc1 - cc0;
        int i = 0;
        while (i < mCount)                              // place
        {
            sizeMethod(i);
            i += 1;
        }
        long cc2 = Magic.readCNTPCT_EL0();
        ccPlaceT += cc2 - cc1;
        if (!isInterface)                               // an interface has no instances -> no vtable/TIB to fill; its
        {                                               // concrete static/default methods are still compiled above
            TibBuilder.fillTib();                                  // TIB was allocated by loadOne (before <clinit>); fill slots now
        }
        long cc3 = Magic.readCNTPCT_EL0();
        ccTibT += cc3 - cc2;
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        long cc4 = Magic.readCNTPCT_EL0();
        ccEmitT += cc4 - cc3;
        publishEmitted();                               // the bodies this wrote, NOT the whole arena
        ccPubT += Magic.readCNTPCT_EL0() - cc4;
    }

    /**
     * Load one class end to end: parse it, run its {@code <clinit>}, build its
     * flattened vtable against the (already-loaded) superclass, compile every
     * method, and register it. Classes must be loaded superclass/dependency-first
     * so the registries are populated when a subclass or user is compiled.
     */
    /** Hand the loader a class blob; {@link #loadAll} works out when to load it. */
    /** A loader table is about to overflow: name it over the UART and halt, rather than write out of bounds and
     *  corrupt the heap (which surfaces later as a garbage-pointer fault). Deterministic; grows a cap when hit. */
    static void capHalt(byte[] which, int count)
    {
        Uart.write(Magic.bytes("\nCAP EXCEEDED: "));
        Uart.write(which);
        Uart.write(Magic.bytes(" count="));
        VM.printDec(count);
        Uart.putc(0x0A);
        // Same reason as denylistTrap: this is always reached mid-load or mid-compile, so the loader lock is
        // ours, and spinning while holding it hangs every other task behind a report they can no longer read.
        VM.loaderForceRelease();
        while (true) { }
    }

    /**
     * Put {@code java/lang/Object} in the batch. Every vtable in this VM begins with Object's nine virtual
     * slots: the writer bakes that prefix into every Type it emits, so a class ADOPTING a baked Type inherits
     * those slot NUMBERS. A batch that never registers Object flattens its classes from slot 0 instead, and
     * the loader's TIB then disagrees with the adopted Type by up to nine — {@code java/lang/String} came out
     * 86 slots against the baked 92, and a cross-world virtual call read past the end of the TIB into
     * whatever followed it (a data abort in the float/double demo, three batches after the first mismatch was
     * printed and ignored).
     *
     * <p>Twelve of the batch drivers added the blob by hand with a comment about canonical slots; the rest
     * silently did not. The invariant belongs to the loader, not to each caller, so it is asserted once here.
     * {@link #addBlob} dedups by address, so a driver that still seeds Object explicitly costs nothing.
     */
    private static void ensureObjectBlob()
    {
        if (VM.objectBytes != 0L && VM.objectLen != 0L)
        {
            addBlob(VM.objectBytes, (int) VM.objectLen);
        }
    }

    /**
     * Pull the exception classes the JIT can throw from an IMPLICIT check: bounds, null deref, divide-by-zero
     * and array store. NOTHING in the guest bytecode names them -- the compiler synthesises those throws -- so
     * dependency discovery never reaches them. When one is missing {@link RuntimeEntrypoints#newExc} has no TIB to give the
     * object and hands back a 16-byte husk with {@code tib=0}, which the JIT stores into {@code $exception}
     * and throws; the unwinder then gets a non-throwable and halts (BAD THROW). That is how a genuine
     * out-of-bounds in {@code LinkedKeySet.toArray} presented as an unreadable UNWIND LOST.
     *
     * <p>Gated on {@code java/lang/Throwable} ALREADY being in this batch, which is the cheap way to say "this
     * batch compiles java.base-shaped code where an implicit check can fire". Batches that load no exception
     * hierarchy at all (the early single-class guest demos) keep their tiny closures untouched, and for the
     * batches that do, the supers are present already -- only the leaves are added. Must therefore run AFTER
     * {@code markReachable} has pulled the closure: called before it, the gate sees only the demo's own blobs
     * and never fires (which is exactly how the first version of this shipped inert).
     */
    private static void ensureImplicitExcBlobs()
    {
        if (!hasBlobNamed(Magic.bytes("java/lang/Throwable")))
        {
            return;
        }
        pullClass(Magic.bytes("java/lang/NullPointerException"));
        pullClass(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"));
        pullClass(Magic.bytes("java/lang/ArithmeticException"));
        pullClass(Magic.bytes("java/lang/ArrayStoreException"));
    }

    /** Whether this batch already holds the classDir blob for {@code name} (compared by blob address). */
    private static boolean hasBlobNamed(byte[] name)
    {
        DataReference scratch = Heap.allocData(name.length + 8);
        int i = 0;
        while (i < name.length)
        {
            scratch.plus(i).store(name[i]);
            i += 1;
        }
        long bytes = VM.dirBytes(scratch.toAddress(), name.length).toLong();
        if (bytes == 0L)
        {
            return false;
        }
        int k = 0;
        while (k < pdCount)
        {
            if (pdBase.get(k).toLong() == bytes)
            {
                return true;
            }
            k += 1;
        }
        return false;
    }

    static void addBlob(long bytes, int len)
    {
        int i = 0;
        while (i < pdCount)                             // dedup by address: the same class may be referenced by
        {                                               // several others and reached twice in one closure pass
            if (pdBase.get(i).toLong() == bytes)
            {
                return;
            }
            i += 1;
        }
        if (pdCount >= MAXBLOB) { capHalt(Magic.bytes("MAXBLOB"), pdCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        pdBase.set(pdCount, Address.fromLong(bytes));
        pdLen[pdCount] = len;
        pdDone[pdCount] = 0;
        pdCount += 1;
    }

    /**
     * Load every recorded blob, dependencies first. Repeatedly loads any blob whose dependencies are all
     * satisfied (already loaded, or not among the blobs at all — {@code java/lang/Object} and other
     * unembedded names never block). When a pass makes no progress but blobs remain, that's a dependency
     * CYCLE (real java.base is a cyclic graph — e.g. {@code Integer} <-> {@code Math}); break it by
     * force-loading the first pending blob. A class's superclass/interfaces are still laid out first by
     * the dependency order; a still-unloaded cross-class METHOD reference in the force-loaded class simply
     * compiles to an unresolved call ({@link MethodRegistry#globalBuf} -> 0), and later blobs resolve back to it.
     */
    /**
     * A demand-load BATCH is one critical section, and the lock lives HERE rather than at each caller.
     *
     * <p>The compile context is STATIC -- {@code g*}, the {@code m*} method tables --
     * so two tasks inside the compiler at once interleave into each other's state. `lazyCompile` has always
     * taken the lock; the BATCH path had no lock of its own, and reached `compileClass` through five callers
     * that do not take one: `mirrorOfInternalName` and `mirrorForNameAt` ({@code Class.forName}),
     * {@code defineFromBytes} ({@code defineClass}), and `annoClassValue`/`annoEnumValue` (the Class- and
     * enum-valued annotation elements). This file already listed the first two as a KNOWN GAP
     * ("reflection-driven loading is not under the loader lock"); the annotation pair widens it.
     *
     * <p>MEASURED rather than reasoned: a guard at the compiler's entry reported TWELVE unlocked
     * `compileClass` entries on a single PASSING launcher run -- `java/lang/Object` at boot through
     * `java/io/ObjectStreamField` and `ConsoleTestExecutor$Factory` late -- so the window is open for the
     * whole run, not just during bring-up. The symptom when it does collide is a body compiled against
     * ANOTHER method's max_locals (`LOCALS UNDERSIZED: java/lang/Object uses slot 18 but max_locals=3`) or a
     * report printing raw constant-pool bytes where a class name belongs, because {@code gView.base} moved under
     * it. Intermittent: two failures in four contended runs, none in five solo ones.
     *
     * <p>ONE PLACE, NOT FIVE, for the reason `resetTaskTable` swallowed its own drain: an invariant that
     * belongs to the loader must not be left to each caller to remember, or a sixth caller forgets. The lock
     * is recursive per task, so a caller that already holds it (lazyCompile's drain, resolveUnresolvedNew,
     * resolveLinkStub, bakeResolve) just bumps the depth and pays nothing.
     *
     * <p>AND IT IS NOT FREE, WHICH THIS SAYS RATHER THAN LEAVES TO BE FOUND. The section spans {@code
     * runClinits}, so it is held across GUEST CODE -- and {@code clinitEntryOf} states the opposite
     * invariant in terms ("the lock covers the COMPILE only, never the running of an initializer: a {@code
     * <clinit>} can block on a monitor or spawn threads, and holding the loader lock across that would
     * deadlock any task that needs to compile before it can release"), as do {@code compileMethodOnDemand},
     * {@code compileSigOnDemand} and the deferred-{@code <init>} path. {@code lazyCompile} already violates
     * it -- its own comment records that ("this fixes the leak, not that") -- so the shape is not new; what
     * IS new is that the five reflection callers above now violate it too, where their {@code runClinits}
     * used to run unlocked.
     *
     * <p>NARROWING IT IS NOT A ONE-LINER, and that is the reason it is documented instead of done.
     * Unlocking around {@code runClinits} hands the BATCH STATE to whoever enters next: this loop reads
     * {@code clinitN} on every iteration and sizes its {@code done[]} from it at entry, so a second task's
     * {@code loadAll} appending initializers walks the first one's loop past the end of its own array, and
     * {@code clinitRunFrom}'s "already ran in an earlier batch" watermark stops meaning anything. The queue
     * has to become snapshot-safe before the lock can be given back mid-batch.
     *
     * <p>WHAT IS DONE INSTEAD: the stuck-lock watchdog now NAMES this shape. An owner in {@code
     * TASK_BLOCKED}/{@code TASK_SLEEPING} cannot be inside the compiler -- nothing there waits on another
     * task -- so it is guest code under the lock, and {@code VM.reportStuckLockVerdict} says exactly that
     * rather than printing a state number this file records being misread three times. Silent unless the
     * hazard actually bites, which is the measurement this wants before a restructure.
     *
     * <p>AND IT IS WHY {@code loaderLock}'s single-core gate MUST NOT BE CLOSED ENTIRELY FIRST. The gate makes
     * the lock inert on one scheduling core, which is wrong on its own terms (the hazard is per TASK) -- but
     * closing it while the section spans guest code would extend this deadlock exposure to every single-core
     * boot. It was closed only where the race was MEASURED: {@code VM.loaderLockLive} is live while core 0's
     * tick preempts (the Pi caught two philosophers in the parser at once). Order for the rest: make the
     * clinit run phase lock-free, then close the gate.
     */
    private static void loadAll()
    {
        VM.loaderLock(VM.LOCK_DEMAND_LOAD);
        try
        {
            loadAllLocked();
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
    }

    private static void loadAllLocked()
    {
        long tAll = Magic.readCNTPCT_EL0();
        long tMark = tAll;
        pbProbed = 0;                                    // per BATCH: probeAll runs once per mark round plus
                                                         //   once more below, and the count is only readable
                                                         //   as a batch total
        ensureObjectBlob();                              // every vtable starts with Object's 9 slots
        if (gEntryBlob != 0L)                            // reachability requested: mark + PULL the reachable
        {                                                // closure on demand (no pre-pull-all resolveClosureFromDir)
            Closure.markReachable();
        }
        long tex = Magic.readCNTPCT_EL0();
        ensureImplicitExcBlobs();                        // AFTER the closure is pulled -- the gate asks whether
                                                         //   this batch has Throwable, which markReachable is what
                                                         //   brings in; before it, the answer is always no
        mrExc += Magic.readCNTPCT_EL0() - tex;
        long tProbe = Magic.readCNTPCT_EL0();
        probeAll();                                      // this_class + super + interfaces + dep list over the final set
        long tA = Magic.readCNTPCT_EL0();

        // PHASE A: register every class's STRUCTURE, super/interface-first. That graph is acyclic, so linkReady
        // always finds a ready blob -- no force-load, no arbitrary order. (The fallback guards a malformed set.)
        int remainingA = pdCount;
        while (remainingA > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < pdCount)
            {
                if (pdDone[i] == 0 && linkReady(i))
                {
                    loadStructure(pdBase.get(i), pdLen[i]);
                    pdDone[i] = 1;
                    remainingA -= 1;
                    progress = 1;
                }
                i += 1;
            }
            if (progress == 0)                          // no linkReady blob (shouldn't happen: inheritance is acyclic)
            {
                int j = 0;
                while (j < pdCount && pdDone[j] != 0)
                {
                    j += 1;
                }
                if (j < pdCount)
                {
                    loadStructure(pdBase.get(j), pdLen[j]);
                    pdDone[j] = 1;
                    remainingA -= 1;
                }
                else
                {
                    remainingA = 0;
                }
            }
        }

        long tB = Magic.readCNTPCT_EL0();
        // PHASE B: compile every class's method bodies + fill its TIB, superclass-first (so inherited vtable
        // buffers are already filled). All new/field/vtable/itable/cast targets are structure-registered now, so
        // order is irrelevant except for the super-before-subclass buffer inheritance; method CALLs are patched.
        int remainingB = pdCount;
        bWorkT = 0L;
        bPasses = 0;
        bChecks = 0;
        lbParseT = 0L;
        lbClinitT = 0L;
        lbCompileT = 0L;
        lbRegT = 0L;
        pParseCpT = 0L;
        pFieldsT = 0L;
        pVtableT = 0L;
        pcCalls = 0;
        pcSteps = 0L;
        pcHits = 0;
        ccSeedT = 0L;
        ccPlaceT = 0L;
        ccTibT = 0L;
        ccEmitT = 0L;
        ccPubT = 0L;
        while (remainingB > 0)
        {
            bPasses += 1;
            int progress = 0;
            int i = 0;
            while (i < pdCount)
            {
                bChecks += 1;
                if (pdDoneB[i] == 0 && superReadyB(i))
                {
                    long bw0 = Magic.readCNTPCT_EL0();
                    loadBodies(DataReference.fromAddress(pdBase.get(i)), pdLen[i]);
                    bWorkT += Magic.readCNTPCT_EL0() - bw0;
                    pdDoneB[i] = 1;
                    remainingB -= 1;
                    progress = 1;
                }
                i += 1;
            }
            if (progress == 0)                          // no super-ready blob (shouldn't happen: super chain acyclic)
            {
                int j = 0;
                while (j < pdCount && pdDoneB[j] != 0)
                {
                    j += 1;
                }
                if (j < pdCount)
                {
                    long bw1 = Magic.readCNTPCT_EL0();   // counted too: otherwise `work` under-reports and
                    loadBodies(DataReference.fromAddress(pdBase.get(j)), pdLen[j]);     //   the scan looks worse than it actually is
                    bWorkT += Magic.readCNTPCT_EL0() - bw1;
                    pdDoneB[j] = 1;
                    remainingB -= 1;
                }
                else
                {
                    remainingB = 0;
                }
            }
        }

        long tPatch = Magic.readCNTPCT_EL0();
        Relocator.patchRelocs();                                  // every body is compiled now: fix up the cross-class method
                                                        // CALLs left unresolved while their target compiled later
        long tRest = Magic.readCNTPCT_EL0();
        InterfaceInvocation.refillImaps();                                  // repair default-method imap slots left 0 by phase-B ordering
        long tRf1 = Magic.readCNTPCT_EL0();
        TibBuilder.refillSynthTibVtables();                        // ... and every synthesised lambda/annotation TIB
        long tRf2 = Magic.readCNTPCT_EL0();
        ArrayTypes.refillArrayTibVtables();                        // Object's vtable is filled now -> repair any array TIB that
                                                        // was created (e.g. by an early string-literal byte[]) before it
        long tRf3 = Magic.readCNTPCT_EL0();
        cumRfImap += tRf1 - tRest;
        cumRfSynth += tRf2 - tRf1;
        cumRfArr += tRf3 - tRf2;
        // Seed BEFORE runClinits: a <clinit> can call these (StreamOpFlag.<clinit> builds an EnumMap -- needs the
        // JLA -- and boxes flag values via Integer.valueOf -- needs the IntegerCache). The seeds are independent
        // of any <clinit> (they build the JLA object / boxed caches directly), so running them first is sound.
        BootSeeds.seedJavaLangAccess();                           // SharedSecrets.javaLangAccess (EnumMap.getKeyUniverse)
        BootSeeds.seedJavaIOAccess();                             // SharedSecrets.javaIOAccess (System.console() -> null)
        BootSeeds.seedVmInitLock();                               // jdk.internal.misc.VM.lock (System.exit -> VM.shutdown)
        Primitives.seedPrimitiveTypes();                           // Integer.TYPE etc: int.class is a getstatic, not an ldc
        long tSeed = Magic.readCNTPCT_EL0();
        cumSeeds += tSeed - tRf3;
        ClassInitializer.runClinits();                                   // NOW run each compiled <clinit>: its cross-class calls are patched
        // THE BATCH'S WORK ENDS HERE, and this is the ONLY clock read that may stand for that. Every span
        // below is measured against it rather than against "now", because `Uart.putRaw` SPINS on the TX
        // holding register: at 115200 baud a character costs ~87us, so a report that reads the clock partway
        // through printing itself charges its own serial traffic to whatever phase it happens to be printing.
        // That is exactly what `clinit` and `tot` used to do -- ~300 characters, ~26ms, added to a phase whose
        // real cost was 7.5ms at batch 15 and 23.8ms at batch 209.
        long tEnd = Magic.readCNTPCT_EL0();
        cumRunCl += tEnd - tSeed;
        // CUMULATIVE totals, always accumulated (a few adds per batch) and printed only on request. This is
        // what says where a whole boot went, as opposed to one batch of it.
        cumBatches += 1;
        // PER BATCH, not every 25: the FIRST batch of a launcher boot is ~25 SECONDS on hardware, so a
        // report that waits for 25 batches says nothing about the part that dominates. One compact line is
        // ~13ms at 115200 baud, which is noise next to a multi-second batch -- and unlike LOAD_PROFILE's
        // per-batch block, it is one line rather than three.
        if (BATCH_COST)
        {
            batchCostLine(tAll, tMark, tProbe, tA, tB, tPatch, tRest, tEnd);
        }
        cumMark += tProbe - tMark;
        cumProbe += tA - tProbe;
        cumA += tB - tA;
        cumB += tPatch - tB;
        cumPatch += tRest - tPatch;
        cumClinit += tEnd - tRest;
        cumAll += tEnd - tAll;
        if (LOAD_PROFILE)
        {
            profileLoadAll(tAll, tMark, tProbe, tA, tB, tPatch, tRest, tEnd);
        }
        reportLostStaticStore();                        // a putstatic that landed in the unresolved-static cell
        // 4-phase lifecycle: batch initialization just completed -- every INSTANTIATED class's queued
        // <clinit> has run (or was deliberately skipped with seeded statics), so the whole batch
        // reaches INITIALIZED. The boot invariant flags any class stuck short of phase B (a hole in
        // the pipeline that previously failed silently as a 0 buffer or an unfilled TIB).
        // EXCEPT a lazy-init class: its initializer is deliberately PENDING, so it stays at
        // INSTANTIATED until the barrier runs it. Counted separately -- pending is a legitimate
        // state, a class short of INSTANTIATED still is not.
        int lcOk = 0;
        int lcPend = 0;
        int lc = 0;
        while (lc < ClassRegistry.clCount)
        {
            if (ClassRegistry.clTab[lc].state >= RVMClass.ST_INITIALIZED)
            {
                // ALREADY INITIALIZED: nothing below can change it -- `state` only advances, and the branches
                // that leave a class short of INITIALIZED only bump the LOAD_LOG counters. What it cost was
                // clinitPendingFor, a scan of every <clinit> record, for every class ever loaded, every batch:
                // 3.9% of SslEngineProbe's first TLS session (qprof, 2026-11-04). Counted as before, minus
                // that scan: a class only reaches INITIALIZED once its initializer has run or it has none.
                lcOk += 1;
                lc += 1;
                continue;
            }
            if (ClassRegistry.clTab[lc].state >= RVMClass.ST_INSTANTIATED
                    && ClassInitializer.lazyClinitGated(ClassRegistry.clTab[lc].base.toAddress(), ClassRegistry.clTab[lc].nameOff) && ClassInitializer.clinitPendingFor(lc))
            {
                lcPend += 1;
            }
            else if (ClassRegistry.clTab[lc].absentSuperOff != 0)
            {
                lcPend += 1;                            // never INITIALIZED: its guards must keep reaching
            }                                           //   ensureClinit, which throws (RVMClass.absentSuperOff)
            else if (ClassRegistry.clTab[lc].state >= RVMClass.ST_INSTANTIATED)
            {
                ClassRegistry.clTab[lc].state = RVMClass.ST_INITIALIZED;
                lcOk += 1;
            }
            else
            {
                Uart.write(Magic.bytes("  lifecycle DIFF "));
                writeName(ClassRegistry.clTab[lc].base.plus(ClassRegistry.clTab[lc].nameOff + 2), u2(ClassRegistry.clTab[lc].base.plus(ClassRegistry.clTab[lc].nameOff)));
                Uart.write(Magic.bytes(" state="));
                VM.printDec(ClassRegistry.clTab[lc].state);
                Uart.putc(0x0A);
            }
            lc += 1;
        }
        if (LOAD_LOG)
        {
            Uart.write(Magic.bytes("  lifecycle OK "));
            VM.printDec(lcOk);
            if (lcPend > 0)                                 // deliberately uninitialized: waiting for first active use
            {
                Uart.write(Magic.bytes(" (+"));
                VM.printDec(lcPend);
                Uart.write(Magic.bytes(" lazy-init pending)"));
            }
            Uart.putc(0x0A);
        }
        VM.byteArrayTibCache = ArrayTypes.byteArrayTib();          // type concat results ([B TIB) so stock getBytes can
                                                        //   checkcast/clone a concat String's value
        VM.stringTypeCache = stringTypeAddr();          // tell a String from any other object at a concat site
        markActive = 0;                                 // don't leak the reachability state past this batch
        gEntryBlob = 0L;
        gRootBlob = 0L;
        gStubBlob = 0L;
        // THE MARK'S SCRATCH IS KEPT, NOT FREED. Nulling these ran EVERY BATCH (loadAll is per batch), so
        // markReachable reallocated them every batch -- ~9.4MB by its own accounting, which Heap.alloc then
        // ZEROES. MEASURED at 414ms/batch of a 710ms mark at batch 165 of a launcher boot, ~58% of it, and
        // invisible to all nine of markReachable's sub-timers because they live inside the round loop while
        // this runs before it.
        //
        // The trade is ~9MB of steady-state heap against ~1.5GB of allocate-and-zero across a boot. It was
        // already transiently allocated every batch, so PEAK heap is unchanged; only the between-batch
        // trough rises. Clearing the tables that need zero semantics costs ~350KB of stores instead --
        // microseconds, which is why epoch-stamping them would buy nothing and would put the closure's
        // correctness at risk for it (an unreset watermark under-marks SILENTLY: reach 1040 -> 449 once,
        // and standalone runs still built the right closure -- only the suite caught it).
    }

    /**
     * Record each blob's own name and every class it names (its {@code Class} entries).
     *
     * <p>EACH BLOB IS PROBED EXACTLY ONCE, and that is sound BY CONSTRUCTION rather than by any claim about
     * when work may be skipped. Everything this pass writes is read straight out of the classfile bytes --
     * this_class, super, the direct interfaces, every {@code CONSTANT_Class} name, whether the pool holds a
     * {@code CONSTANT_String} -- and the blob table is strictly APPEND-ONLY: {@link #addBlob} dedups by
     * ADDRESS and only ever writes {@code pdBase[pdCount]}, so no index is re-pointed at different bytes for
     * the life of a launch. A second probe of blob {@code i} could only reproduce the first one's answers.
     * (That is the same argument the {@code virt} hit lists rest on, and the reason neither needs a rule for
     * invalidating anything.)
     *
     * <p>It had been re-probing EVERY blob on EVERY call -- once per {@link Closure#markReachable} round plus once
     * more at the top of {@link #loadAll} -- while {@code pdCount} grows all boot. Measured on a launcher
     * boot at batch 209 (1782 blobs): {@code probe} was 309.7ms of a 320.0ms {@code mark}, i.e. 97% of it,
     * plus 151.1ms at the top level -- together 471ms of a 676ms batch, 70%, and 4.9x what it cost at batch
     * 15 for 31% more blobs. Seventh instance of this file's most common defect, a per-item pass over a
     * table that grows all boot; the first one whose remedy is a WATERMARK rather than a hash index,
     * because the work is not a lookup.
     *
     * <p>The dep list is append-only for the same reason -- a dep is a {@code {base, offset}} pair into
     * immutable bytes -- so {@code dpCount} is NOT reset here any more. It and {@code stringPdIndex} are
     * reset in {@link #resetLoader} beside {@code pdProbedTo}: a watermark that outlives the table it
     * indexes under-marks SILENTLY, which is how the virt hit lists nearly shipped a half closure.
     */
    static void probeAll()
    {
        int i = pdProbedTo;
        pbProbed += pdCount - i;
        while (i < pdCount)
        {
            parseConstPool(DataReference.fromAddress(pdBase.get(i)), pdLen[i]);
            pdNameOff[i] = gView.cp[u2(gView.base.plus(gView.cp[u2(gp.plus(2))]))];   // this_class -> name
            probeInheritance(i);                               // super + direct interfaces (phase-A ordering edges)
            if (Utf8.utf8IsAtBase(pdBase.get(i), pdNameOff[i], Magic.bytes("java/lang/String")))
            {
                stringPdIndex = i;
            }
            pdNeedsString[i] = false;
            int c = 1;
            while (c < gView.cpCount)
            {
                if (gView.cpTag[c] == 7)                     // CONSTANT_Class
                {
                    addDep(i, gView.cp[u2(gView.base.plus(gView.cp[c]))]);
                }
                else if (gView.cpTag[c] == 8 || gView.cpTag[c] == 18)   // CONSTANT_String / CONSTANT_InvokeDynamic:
                {
                    pdNeedsString[i] = true;            // materializes a String via the concat's scEnd (baked TIB)
                    if (gView.cpTag[c] == 18)
                    {
                        addIndyIfaceDep(i, c);          // ... and names its functional interface in a descriptor
                        // AND PEND IT FOR A PULL. The dep list drives phase-A ORDERING, not pulling: a dep
                        // naming a class that is not in this batch counts as satisfied. Pulling is RTA's job
                        // in collectRefs -- which is SKIPPED for a settled blob (markSettled), so a lambda in
                        // a class compiled by an earlier batch never gets its interface pulled at all.
                        //
                        // For a CALL that is survivable: it becomes a link stub and resolves on first use,
                        // which is what makes the settled skip safe in general. A functional interface has no
                        // such fallback -- it is named ONLY as the indy descriptor's return type, never as a
                        // CONSTANT_Class, and buildLambdaTib bakes the resolved Type into the synthesised TIB
                        // at compile time. A miss stores 0, which is ALSO the itable directory's end
                        // sentinel, so the lambda satisfies no interface and every dispatch on it fails far
                        // from the indy that caused it.
                        //
                        // probeAll is the right place precisely because it is the ONE pass markSettled does
                        // not skip, and it already parses this blob's constant pool and visits this tag.
                        Closure.pendIndyIface(c);
                    }
                }
                c += 1;
            }
            i += 1;
        }
        pdProbedTo = pdCount;
    }

    /**
     * Record blob {@code i}'s superclass + directly-implemented interface names (the acyclic edges phase A
     * orders by). Reads the class-file header at {@code gp} (access_flags), left current by the caller's
     * {@code parseConstPool}. Decomposed into locals so the host writer's operand stack stays shallow. A
     * CONSTANT_Class cp entry at index {@code c} has {@code gView.cp[c]} = its name_index offset; the name Utf8
     * offset is {@code gView.cp[nameIndex]} (mirrors probeAll's this_class parse).
     */
    private static void probeInheritance(int i)
    {
        int superCi = u2(gp.plus(4));
        pdSuperOff[i] = 0;
        if (superCi != 0)
        {
            int superNameIx = u2(gView.base.plus(gView.cp[superCi]));
            pdSuperOff[i] = gView.cp[superNameIx];
        }
        Address p = gp.plus(6);
        int ifc = u2(p);
        p = p.plus(2);
        int n = 0;
        int k = 0;
        while (k < ifc && n < InterfaceInvocation.MAX_DIRECT_IF)
        {
            int ifCi = u2(p.plus(k * 2));
            int ifNameIx = u2(gView.base.plus(gView.cp[ifCi]));
            pdIfOff[i * InterfaceInvocation.MAX_DIRECT_IF + n] = gView.cp[ifNameIx];
            n += 1;
            k += 1;
        }
        pdIfN[i] = n;
    }

    /**
     * Phase-A readiness: blob {@code i}'s superclass and every direct interface are already structure-loaded
     * (or not among the blobs at all). The inheritance graph is acyclic, so scanning for a linkReady blob never
     * stalls -> phase A lays down structure strictly super/interface-first.
     */
    private static boolean linkReady(int i)
    {
        if (pdSuperOff[i] != 0 && blocked(i, pdBase.get(i), pdSuperOff[i]))
        {
            return false;
        }
        int k = 0;
        while (k < pdIfN[i])
        {
            if (blocked(i, pdBase.get(i), pdIfOff[i * InterfaceInvocation.MAX_DIRECT_IF + k]))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /**
     * Phase-B readiness: blob {@code i}'s superclass has had its BODIES compiled (its TIB vtable buffers are
     * filled), so this class's parseVtable inherits real buffers and its fillTib is complete. Interfaces need
     * not be body-done first -- the itable directory keys on interface Types (structure) and holds THIS class's
     * own method buffers. {@code pdDoneB} marks phase-B completion.
     */
    private static boolean superReadyB(int i)
    {
        return pdSuperOff[i] == 0 || !blockedB(i, pdSuperOff[i]);
    }

    /** Like {@link #blocked} but against phase-B completion ({@code pdDoneB}). */
    private static boolean blockedB(int i, int off)
    {
        int j = 0;
        while (j < pdCount)
        {
            if (j != i && pdDoneB[j] == 0
                    && utf8EqAt(pdBase.get(i), off, pdBase.get(j), pdNameOff[j]))
            {
                return true;
            }
            j += 1;
        }
        return false;
    }

    /**
     * Record the functional interface an {@code invokedynamic} names as a dependency of its owner.
     *
     * <p>It is a dependency NOTHING else records: a lambda's interface appears only as the RETURN TYPE inside
     * the indy's own descriptor, never as a {@code CONSTANT_Class}, so the tag-7 scan above cannot see it.
     * The cost of missing it is silent and severe: {@code buildLambdaTib} resolves the interface to a Type at
     * COMPILE time, and an unregistered one resolves to 0 -- which is also the itable directory's END
     * SENTINEL, so the lambda ends up satisfying no interface at all and every dispatch on it throws NPE from
     * the directory miss. That is what made the stock NextTokenWithNullDelimTest fail while every hand-written
     * replay of the same calls passed: the replay's batch happened to contain {@code ThrowingSupplier}
     * already, and the test's did not.
     *
     * <p>A non-lambda indy (string concat, a record's {@code ObjectMethods} bootstrap) is not special-cased:
     * its descriptor's return type is a genuine dependency of the class too, and a primitive return simply
     * has no name to add.
     */
    private static void addIndyIfaceDep(int owner, int c)
    {
        int nt = u2(gView.base.plus(gView.cp[c] + 2));                // InvokeDynamic { u2 bsmAttrIdx, u2 nameAndTypeIdx }
        int descOff = gView.cp[u2(gView.base.plus(gView.cp[nt] + 2))];     // NameAndType { u2 nameIdx, u2 descIdx } -> Utf8 offset
        int end = descOff + 2 + u2(gView.base.plus(descOff));
        int p = descOff + 2;
        while (p < end && u1(gView.base.plus(p)) != ')')
        {
            p += 1;
        }
        p += 1;                                         // past ')'
        if (p >= end || u1(gView.base.plus(p)) != 'L')
        {
            return;                                     // primitive return: no class named
        }
        int start = p + 1;
        int q = start;
        while (q < end && u1(gView.base.plus(q)) != ';')
        {
            q += 1;
        }
        addDepAt(owner, indyNameBuf.toAddress(), internIndyName(start, q - start));
    }

    /**
     * Copy the class name at {@code [start, start+len)} of the current blob into {@code indyNameBuf} as a
     * {@code [u2 len][bytes]} run and return its offset, reusing an identical earlier entry.
     *
     * <p>The copy is needed because a dependency is addressed as a Utf8 (length prefix and all) and this name
     * is embedded MID-descriptor, with no prefix of its own. The buffer is a Loader static, so the statics
     * root scan keeps it alive for as long as the dependency list points into it.
     */
    private static int internIndyName(int start, int len)
    {
        return internIndyNameAt(gView.base.plus(start), len);
    }

    /** As {@link #internIndyName}, from an absolute address. */
    static int internIndyNameAt(Address raw, int len)
    {
        if (indyNameBuf.isZero())
        {
            indyNameBuf = Heap.allocData(MAXINDYNAME);
        }
        int e = 0;
        while (e < indyNameUsed)
        {
            if (Utf8.rawNameEq(indyNameBuf.toAddress(), e, raw, len))
            {
                return e;                               // one interface, many lambdas: intern rather than repeat
            }
            e += 2 + u2(indyNameBuf.plus(e));
        }
        if (indyNameUsed + 2 + len > MAXINDYNAME)
        {
            capHalt(Magic.bytes("MAXINDYNAME"), indyNameUsed);
        }
        int off = indyNameUsed;
        indyNameBuf.plus(off).store((byte) (len >> 8));
        indyNameBuf.plus(off).plus(1).store((byte) len);
        int k = 0;
        while (k < len)
        {
            indyNameBuf.plus(off).plus(2).plus(k).store((byte) u1(raw.plus(k)));
            k += 1;
        }
        indyNameUsed = off + 2 + len;
        return off;
    }

    private static void addDep(int owner, int nameOff)
    {
        addDepAt(owner, pdBase.get(owner), nameOff);
    }

    /** As {@link #addDep}, but for a name that does not live in the owner's own blob. */
    private static void addDepAt(int owner, Address base, int nameOff)
    {
        if (dpCount >= MAXDEP) { capHalt(Magic.bytes("MAXDEP"), dpCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        dpOwner[dpCount] = owner;
        dpBase.set(dpCount, base);
        dpOff[dpCount] = nameOff;
        dpCount += 1;
    }

    /** True if no dependency of blob {@code i} names a blob that is still unloaded. */
    private static boolean ready(int i)
    {
        // A blob that materializes a String (string literal / concat) bakes String's TIB+size at compile time
        // (the concat's scEnd) but carries no CONSTANT_Class dep on it — so hold it until java/lang/String's
        // Type is registered, else it compiles against TIB=0 and yields a malformed String.
        if (pdNeedsString[i] && stringPdIndex >= 0 && stringPdIndex != i && pdDone[stringPdIndex] == 0)
        {
            return false;
        }
        int d = 0;
        while (d < dpCount)
        {
            if (dpOwner[d] == i && blocked(i, dpBase.get(d), dpOff[d]))
            {
                return false;
            }
            d += 1;
        }
        return true;
    }

    /** True if some other still-unloaded blob declares the class named at {@code off} in {@code base}. */
    private static boolean blocked(int i, Address base, int off)
    {
        int j = 0;
        while (j < pdCount)
        {
            if (j != i && pdDone[j] == 0
                    && utf8EqAt(base, off, pdBase.get(j), pdNameOff[j]))
            {
                return true;
            }
            j += 1;
        }
        return false;
    }

    /**
     * PHASE A -- register class STRUCTURE only (no method bodies, no TIB fill). Runs in super/interface-first
     * (acyclic) order, so parseVtable/parseFields/captureDirectIfaces always see their super+interfaces. After
     * this pass every class has a Type, an (empty) TIB at a stable address, a field layout, a static block, its
     * interface slots, and its vtable SLOT numbering registered -- so any OTHER class's phase-B body compile can
     * resolve new/getfield/invokevirtual/invokeinterface/checkcast against it regardless of order.
     */
    private static void loadStructure(Address bytes, int len)
    {
        parseConstPool(DataReference.fromAddress(bytes), len);
        int classModifiers = gView.accessFlags & ~0x0020;   // class access_flags, ACC_SUPER stripped; cached for
                                                        //   Class.getModifiers() with NO extra parse (an extra
                                                        //   runtime/load re-parse corrupted the heap). Taken HERE, into
                                                        //   a local: the parses below can switch gView to another blob.
                                                        //   A nested class's InnerClasses flags are read at REFLECTION
                                                        //   time instead (innerClassFlags, under the loader lock).
        parseFields();                                  // hierarchy-aware field layout (allocates gBuild.statics)
        Indy.findBootstrapMethods();                         // locate BootstrapMethods (for invokedynamic), if any
        if (gView.isInterface())
        {
            if (ClassRegistry.clCount >= ClassRegistry.MAXCLASS) { capHalt(Magic.bytes("ClassRegistry.MAXCLASS-iface"), ClassRegistry.clCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
            InterfaceInvocation.registerInterface();                        // give its methods global itable indices
            // Give the interface a Type and register it, so implementors' itable
            // directories can key on it and the core's interfaceType resolves (M5.4.e).
            // M8 itables: a BAKED interface ADOPTS the writer's Type node instead -- implementors'
            // dir entries then key on the shared node, so interface instanceof/checkcast in linked
            // baked code matches loader receivers. The adopted node's fields stand as written
            // (superType = Object's shared node; nothing reads an interface Type's instanceSize).
            VtableLayout.findVtSig();                                // adoption entry (itparity compares later, post-fill)
            adoptStatics();                             // interface constants share the writer block too
            if (!gBuild.adoptType.isZero())
            {
                gBuild.type = gBuild.adoptType;
                if (LOAD_LOG)
                {
                    Uart.write(Magic.bytes("  typeadopt "));
                    printNameAt(gView.base.toAddress(), gView.thisNameOff);
                    Uart.putc(0x0A);
                }
            }
            else
            {
                gBuild.type = Heap.allocType();
                gBuild.type.store(0L);           // instanceSize (not instantiated)
                gBuild.type.plus(8).store(0L);           // superType
                gBuild.type.plus(16).store(0L);          // no itableDir
                gBuild.type.plus(ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET).store(0L);
                gBuild.type.plus(ObjectModel.TYPE_DEPTH_OFFSET).store(-1L);   // interface: never in a chain
                gBuild.type.plus(ObjectModel.TYPE_DISPLAY_OFFSET).store(0L);
                // O(1) interface checks: number this interface from the shared counter (the writer
                // used 1..N-1; IDs >= 128 stay 0 = unnumbered -> those targets keep the dir walk).
                long nid = VM.ifaceIdNext;
                gBuild.type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).store(
                              nid > 0L && nid < 128L ? nid : 0L);
                gBuild.type.plus(ObjectModel.TYPE_IMPLEMENTS_OFFSET).plus(8L).store(0L);
                if (nid > 0L && nid < 128L)
                {
                    VM.ifaceIdNext = nid + 1L;
                }
            }
            ClassRegistry.clTab[ClassRegistry.clCount] = new RVMClass();
            ClassRegistry.clTab[ClassRegistry.clCount].state = RVMClass.ST_LOADED;
            ClassRegistry.clTab[ClassRegistry.clCount].base = gView.base;
            ClassRegistry.clTab[ClassRegistry.clCount].nameOff = gView.thisNameOff;
            ClassRegistry.clTab[ClassRegistry.clCount].nameHash = Utf8.utf8Hash(gView.base.toAddress(), gView.thisNameOff);   // folded once; see RVMClass.nameHash
            ClassRegistry.clTab[ClassRegistry.clCount].tib = DataReference.zero();
            ClassRegistry.clTab[ClassRegistry.clCount].type = gBuild.type;
            ClassRegistry.clTab[ClassRegistry.clCount].fieldCount = 0;
            ClassRegistry.clTab[ClassRegistry.clCount].vtCount = 0;
            ClassRegistry.clTab[ClassRegistry.clCount].statics = gBuild.statics;              // interface constants block (reused by its phase-B bodies)
            ClassRegistry.clTab[ClassRegistry.clCount].vtStart = VtableLayout.vtCount;               // no vtable entries appended for an interface
            ClassRegistry.clTab[ClassRegistry.clCount].isIface = true;
            ClassRegistry.clTab[ClassRegistry.clCount].ifmStart = gBuild.ifmStart;            // the flattened per-interface method run
            ClassRegistry.clTab[ClassRegistry.clCount].ifmCount = gBuild.ifmCount;            //   = this interface's itable slot numbering
            ClassRegistry.clTab[ClassRegistry.clCount].superNameOff = gView.superNameOff;
            ClassRegistry.clTab[ClassRegistry.clCount].absentSuperOff = gBuild.absentSuperOff;          // an interface extending one that cannot load
            ClassRegistry.clTab[ClassRegistry.clCount].superReg = ClassRegistry.classRegByName(gView.superNameOff);   // an interface's super is Object (-1); kept for symmetry
            InterfaceInvocation.captureDirectIfaces();                      // an interface's extended interfaces (List extends Iterable)
            ClassRegistry.clTab[ClassRegistry.clCount].modifiers = classModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
            InterfaceInvocation.checkIfParity(ClassRegistry.clCount);                     // M8 itables: writer/loader slot numbering must agree
            ClassRegistry.clTab[ClassRegistry.clCount].state = RVMClass.ST_RESOLVED;    // lifecycle: structure complete
            ClassRegistry.clCount += 1;
            // AN INTERFACE'S CONSTANTS NEED CELLS TOO. Interface fields are implicitly public static final,
            // and this branch never reaches registerClassStructure -- so until now no interface constant was
            // ever entered in sgTab, and every cross-class `getstatic` on one resolved to nothing. The report
            // said so in as many words: "class IS REGISTERED but has no static cell (registration gap)", for
            // org/junit/platform/launcher/core/LauncherConfig.DEFAULT.
            Statics.registerStaticFields();
            return;                                     // bodies (default/static methods) compiled in phase B
        }
        VtableLayout.parseVtable(bytes);                             // flatten against the superclass: SLOT numbering (bufs still 0)
        VtableLayout.checkVtParity();                                // M8 unification: writer/loader slot numbering must agree
        adoptStatics();                                 // M8 statics unification: ONE static block per baked class
        TibBuilder.allocTib();                                     // allocate Type + empty TIB at a stable address (gBuild.tib)
        registerClassStructure();                       // class + fields + statics + vtable STRUCTURE (bufs 0)
        ClassRegistry.clTab[ClassRegistry.clCount - 1].modifiers = classModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
        ClassRegistry.clTab[ClassRegistry.clCount - 1].state = RVMClass.ST_RESOLVED;    // lifecycle: structure complete
    }

    /**
     * PHASE B -- compile every method body of an already-structure-registered class and fill its TIB. Runs in
     * superclass-first order so this class's parseVtable inherits its super's now-filled vtable buffers and its
     * fillTib is complete. All cross-class new/field/vtable/itable/cast targets are registered (phase A), so the
     * only unresolved refs left are method CALLS into not-yet-compiled bodies -- patchRelocs fixes those after.
     */
    private static void loadBodies(DataReference bytes, int len)
    {
        long lb0 = Magic.readCNTPCT_EL0();
        parseConstPool(bytes, len);
        long lbA = Magic.readCNTPCT_EL0();
        pParseCpT += lbA - lb0;
        parseFields();                                  // re-derive the field layout; its fresh statics block is throwaway
        pFieldsT += Magic.readCNTPCT_EL0() - lbA;
        lbParseT += Magic.readCNTPCT_EL0() - lb0;
        int reg = ClassRegistry.classRegByName(gView.thisNameOff);
        gBuild.statics = ClassRegistry.clTab[reg].statics;                      // REUSE the phase-A static block (cross-class getstatic keys on it)
        Indy.findBootstrapMethods();
        if (ClassRegistry.clTab[reg].isIface)
        {
            gBuild.type = ClassRegistry.clTab[reg].type;                        // restore the interface's phase-A Type so a default method's
                                                        // self-reference (typeOfClass -> gBuild.type, e.g. Map.putIfAbsent
                                                        // calling this.get()) bakes the REAL interface Type -- else
                                                        // it bakes a stale Type and the implementor's itable-dir
                                                        // walk never matches (invokeinterface sentinel NPE).
            // AND ITS INITIALIZER. An interface with a constant whose value is not a compile-time constant
            // has a <clinit> exactly like a class (JVMS 4.7 / 5.5: `LauncherConfig DEFAULT = builder()
            // .build()` compiles to one). This branch used to skip runClinit, so no record was ever enqueued
            // and the constant read null for the life of the VM even once its cell existed. runClinit only
            // CAPTURES the body -- ensureClinit compiles and runs it on the first active use.
            ClassInitializer.runClinit(bytes.toAddress());
            compileClass(bytes);                        // interface CONCRETE methods (static like List.of + defaults)
            MethodRegistry.registerAll();
            ClassRegistry.clTab[reg].state = RVMClass.ST_INSTANTIATED;    // lifecycle: bodies done
            return;
        }
        long lb1 = Magic.readCNTPCT_EL0();
        VtableLayout.parseVtable(bytes.toAddress());                             // NOW the super's vtBuf is filled -> inherited slot bufs are real
        long lbB = Magic.readCNTPCT_EL0();
        pVtableT += lbB - lb1;
        lbParseT += lbB - lb1;
        gBuild.type = ClassRegistry.clTab[reg].type;                            // restore this class's Type + TIB (allocated in phase A)
        gBuild.tib = ClassRegistry.clTab[reg].tib;
        boolean reuseWas = compileReuseTib;              // a nested load resets it: restore, never clear
        compileReuseTib = true;                         // keep runClinit's compile() from reallocating gBuild.tib (would
        long lb2 = Magic.readCNTPCT_EL0();
        ClassInitializer.runClinit(bytes.toAddress());                               //   leave compileClass filling a throwaway TIB, not clTab[reg].tib)
        lbClinitT += Magic.readCNTPCT_EL0() - lb2;
        compileReuseTib = reuseWas;
        gBuild.type = ClassRegistry.clTab[reg].type;                            // (runClinit's compile leaves gBuild.type/tib alone now, but be safe)
        gBuild.tib = ClassRegistry.clTab[reg].tib;
        provideKnownStatics();                          // seed static tables a skipped <clinit> would have built
        long lb3 = Magic.readCNTPCT_EL0();
        compileClass(bytes);                            // compile all methods; fillTib fills the (phase-A-allocated) TIB
        long lb4 = Magic.readCNTPCT_EL0();
        lbCompileT += lb4 - lb3;
        MethodRegistry.registerAll();                                  // methods -> globalBuf
        VtableLayout.fillClassVtBuf(reg);                            // fill this class's registered vtable buffers (for subclasses)
        lbRegT += Magic.readCNTPCT_EL0() - lb4;
        ClassRegistry.clTab[reg].state = RVMClass.ST_INSTANTIATED;    // lifecycle: bodies + TIB + itables done
    }

    /**
     * Seed the static tables a skipped {@code <clinit>} would have built, for known real java.base classes.
     * Currently {@code java/lang/Integer.digits} (the radix digit chars {@code Integer.toHexString} /
     * {@code formatUnsignedInt} index) — its real {@code <clinit>} also calls {@code Class.getPrimitiveClass}
     * (a native), so we provide just the table. gBuild.statics is this class's block; the compiled getstatic reads
     * the same slot address.
     */
    static void provideKnownStatics()
    {
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff, Magic.bytes("java/lang/String")))
        {
            long cs = Statics.staticSlotByName(Magic.bytes("COMPACT_STRINGS"));
            if (cs != 0L)
            {
                Address.fromLong(cs).store(1L);                  // LATIN1 world: stock getstatic COMPACT_STRINGS -> true
            }
            return;
        }
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff, Magic.bytes("java/lang/StringUTF16")))
        {
            // Its <clinit> sets the UTF-16 byte-order shifts via Unsafe.isBigEndian (unrunnable, skipped),
            // leaving BOTH shifts 0 -- putChar then stores the LOW byte twice and a char > 0xFF truncates
            // (the euro U+20AC read back as 0xAC). AArch64 runs little-endian: HI=0 (default), LO=8.
            long lo = Statics.staticSlotByName(Magic.bytes("LO_BYTE_SHIFT"));
            if (lo != 0L)
            {
                Address.fromLong(lo).store(8L);
            }
            return;
        }
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff, Magic.bytes("java/lang/Integer")))
        {
            return;
        }
        long slot = Statics.staticSlotByName(Magic.bytes("digits"));
        if (slot == 0L)
        {
            return;
        }
        ObjectReference tab = Heap.allocArray(36, 1).toObjectReference();              // '0'..'9','a'..'z' (radix digits, LATIN1)
        int i = 0;
        while (i < 36)
        {
            tab.toAddress().plus(24L).plus(i).store((byte) (i < 10 ? 0x30 + i : 0x61 + i - 10));
            i += 1;
        }
        Address.fromLong(slot).store(tab);
    }


    /**
     * Fill the standard system properties every JVM defines.
     *
     * <p>Not decoration: library code reads these UNCONDITIONALLY and does not expect null. picocli's
     * {@code Ansi.isWindows()} does {@code System.getProperty("os.name").toLowerCase()} -- with no property it
     * NPEs inside the library, far from anything the VM did. A property is also the one place joe-ng can state
     * WHAT IT IS rather than imitate something else, so {@code java.vm.name} says joe-ng and {@code os.name}
     * says so too: code that branches on the platform should not be told it is running on Linux.
     *
     * <p>{@code line.separator} is "\n": {@code Uart.putc} is what turns that into CRLF for the console, so a
     * "\r\n" here would double the carriage returns.
     */
    /**
     * The compiled body of {@code Double.toString(double)}, resolved BY NAME and memoised -- 0 if it cannot
     * be had. {@link VMConcat#scDouble} calls it for string concat.
     *
     * <p>BY NAME rather than through a static call, because a static call from baked VM code would put
     * {@code DoubleToDecimal} in the BAKE domain, and the writer cannot compile it there. Through the
     * LOADER it is ordinary demand-loaded java.base and works.
     */
    static long doubleToStringBuf()
    {
        if (dblToStrBuf == 0L)
        {
            dblToStrBuf = fmtBuf(Magic.bytes("java/lang/Double"), Magic.bytes("(D)Ljava/lang/String;"));
        }
        return dblToStrBuf;
    }

    /**
     * Box a method reference's FLOAT or DOUBLE result: x0 holds its raw bits (the baseline compiler returns a
     * float/double as bits in an X register, like any value), and the boxing thunk hands them here through
     * {@code VMBox.box}. Calls the guest {@code Float.valueOf(F)}/{@code Double.valueOf(D)}, resolved BY NAME --
     * the same move as {@link #doubleToStringBuf}, for the same reason: {@code VMBox} is image code, and naming
     * {@code Double} there pulled it into the boot set, where {@code Double.<clinit>} faulted (measured in #333).
     * The bits are exactly what a guest float/double ARGUMENT carries, so they pass straight through.
     *
     * <p>If the method cannot be had, the result is NOT handed back as raw bits posing as a reference: it is
     * reported once by name and answered null, which fails at its first use rather than as a wild pointer.
     */
    static long boxFloating(long bits, int kind)
    {
        long buf;
        if (kind == 'F')
        {
            if (fltValueOfBuf == 0L)
            {
                fltValueOfBuf = boxValueOfBuf(Magic.bytes("java/lang/Float"), Magic.bytes("(F)Ljava/lang/Float;"));
            }
            buf = fltValueOfBuf;
        }
        else
        {
            if (dblValueOfBuf == 0L)
            {
                dblValueOfBuf = boxValueOfBuf(Magic.bytes("java/lang/Double"), Magic.bytes("(D)Ljava/lang/Double;"));
            }
            buf = dblValueOfBuf;
        }
        if (buf == 0L)
        {
            if (!reportedNoFloatBox)
            {
                reportedNoFloatBox = true;
                Uart.write(Magic.bytes("\n  FLOAT/DOUBLE RESULT NOT BOXED: Float/Double.valueOf could not be resolved\n"));
            }
            return 0L;
        }
        return Magic.call2(buf, bits, 0L);              // one argument; the second register is ignored
    }

    /** {@code cls.valueOf(desc)} resolved to callable code (compiled on demand), its class initialized first --
     *  calling a raw buffer is not an active use (see {@link #fmtBuf}). */
    private static long boxValueOfBuf(byte[] cls, byte[] desc)
    {
        long buf = DynamicLinker.bufBySigU(utf8Blob(cls).toAddress(), utf8Blob(Magic.bytes("valueOf")).toAddress(), utf8Blob(desc).toAddress());
        if (buf == 0L)
        {
            buf = LazyCompiler.compileSigOnDemand(utf8Blob(cls).toAddress(), utf8Blob(Magic.bytes("valueOf")).toAddress(), utf8Blob(desc).toAddress());
        }
        if (buf != 0L)
        {
            int reg = ClassRegistry.classRegByNameBytes(cls);
            if (reg >= 0)
            {
                ClassInitializer.ensureClinit(reg);
            }
        }
        return buf;
    }

    /** The float twin of {@link #doubleToStringBuf} -- a DIFFERENT string, never the widened double's. */
    static long floatToStringBuf()
    {
        if (fltToStrBuf == 0L)
        {
            fltToStrBuf = fmtBuf(Magic.bytes("java/lang/Float"), Magic.bytes("(F)Ljava/lang/String;"));
        }
        return fltToStrBuf;
    }

    /**
     * Resolve {@code cls.toString(desc)} to a compiled buffer, compiling it if nothing has called it yet.
     *
     * <p>THE SECOND STEP IS NOT OPTIONAL, and seedStandardProps records why: {@code rgTab} holds one entry
     * per COMPILED method and bodies compile on FIRST CALL, so a zero from {@link DynamicLinker#bufBySigU} means "not
     * yet", not "unavailable". Treating those alike is the "works in one closure, broken in another"
     * signature this file keeps paying for.
     */
    private static long fmtBuf(byte[] cls, byte[] desc)
    {
        long buf = DynamicLinker.bufBySigU(utf8Blob(cls).toAddress(), utf8Blob(Magic.bytes("toString")).toAddress(), utf8Blob(desc).toAddress());
        if (buf == 0L)
        {
            buf = LazyCompiler.compileSigOnDemand(utf8Blob(cls).toAddress(), utf8Blob(Magic.bytes("toString")).toAddress(), utf8Blob(desc).toAddress());
        }
        // INITIALIZE BEFORE CALLING, because calling a compiled buffer raw is not an ACTIVE USE and so
        // skips JVMS 5.5 entirely.
        //
        // THIS IS NOT WHAT FIXED THE EMPTY STRINGS, and saying so matters: that was an operand clobber in
        // VMConcat (see scDouble), and I added this first on the theory that DoubleToDecimal's statics were
        // unset. A run with it in place still printed `[]`, which refuted that. It stays because the
        // JVMS 5.5 argument is true on its own terms -- a raw buffer call initializes nothing -- and NOT
        // because it was ever observed to repair anything.
        if (buf != 0L)
        {
            int reg = ClassRegistry.classRegByNameBytes(cls);
            if (reg >= 0)
            {
                ClassInitializer.ensureClinit(reg);
            }
            int dec = ClassRegistry.classRegByNameBytes(helperOf(cls));
            if (dec >= 0)
            {
                ClassInitializer.ensureClinit(dec);
            }
        }
        return buf;
    }

    /** The {@code jdk/internal/math} formatter {@code cls}'s {@code toString} delegates to. */
    private static byte[] helperOf(byte[] cls)
    {
        if (cls.length == 16)                            // "java/lang/Double"
        {
            return Magic.bytes("jdk/internal/math/DoubleToDecimal");
        }
        return Magic.bytes("jdk/internal/math/FloatToDecimal");
    }


    /** Bumped by every {@link #resetLoader}: objects built before it belong to a registry that is gone. */
    static int loaderGen;


    /**
     * ACC_SYNCHRONIZED coverage for the launch that just ended, printed AT THE END rather than on the batch
     * line -- which is where these counters started, and where they could not fire: a launched program's
     * methods compile LAZILY on first call, long after batch 1's line is printed, so the batch line read
     * `syncStatic=0` for a demo full of static synchronized methods and I read that as "all covered". It was
     * "none seen yet". An instrument that cannot fire looks exactly like a condition that never happens.
     *
     * <p>{@code seen} counts static synchronized methods that reached the compiler at all; {@code skip}
     * those compiled WITHOUT a monitor because the world could not name one. seen &gt; 0 with skip == 0 is
     * the covered state; skip &gt; 0 names a real gap; seen == 0 means this program had none.
     */
    private static void reportSyncCoverage()
    {
        // Printed BEFORE the early return: the repair must be READABLE, not merely present -- a counter
        // nobody prints is the same defect as an instrument that cannot fire, and this one must not be
        // hidden behind an unrelated condition. 0 on a boot that reclaims would mean the repair is misplaced.
        Uart.write(Magic.bytes("\n  bakeMemosDropped="));
        VM.printDec(VirtualDispatch.bakeMemosDropped);
        if (compiler.Baseline.syncStaticSeen == 0 && compiler.Baseline.syncStaticSkipped == 0)
        {
            return;                                     // no static synchronized method in this program
        }
        Uart.write(Magic.bytes("\n  sync: static seen="));
        VM.printDec(compiler.Baseline.syncStaticSeen);
        Uart.write(Magic.bytes(" nomonitor="));
        VM.printDec(compiler.Baseline.syncStaticSkipped);
        Uart.putc(0x0A);
    }

    /** Record a method (deduped by bytecode address; dedup also breaks cycles). */
    private static void addMethod(long code, int len, int maxLocals, int descOff, int isStatic)
    {
        addMethod(code, len, maxLocals, descOff, isStatic, 0);
    }

    private static void addMethod(long code, int len, int maxLocals, int descOff, int isStatic, int isSync)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return;
            }
            i += 1;
        }
        if (mCount >= MAXM) { capHalt(Magic.bytes("MAXM"), mCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        mCode[mCount] = code;
        mLen[mCount] = len;
        mLocals[mCount] = maxLocals;
        mDescOff[mCount] = descOff;
        mStatic[mCount] = isStatic;
        mSync[mCount] = isSync;
        mCount += 1;
    }

    /** Allocate the per-batch method tables (discover/place/emit work set). */
    private static void allocMethodTables()
    {
        mCode = new long[MAXM];
        mLen = new int[MAXM];
        mBuf = CodeArray.create(MAXM);
        mEnd = CodeArray.create(MAXM);
        mLine = CodeArray.create(MAXM);
        mSrc = AddressArray.create(MAXM);
        mLocals = new int[MAXM];
        mDescOff = new int[MAXM];
        mStatic = new int[MAXM];
        mSync = new int[MAXM];
        mDefer = new int[MAXM];
        mCount = 0;
    }

    /** Copy method {@code i}'s bytecode out of the current class blob into a heap byte[]. */
    private static byte[] extractCode(int i)
    {
        int off = (int) (mCode[i] - gView.base.toLong());
        int len = mLen[i];
        byte[] c = new byte[len];
        int k = 0;
        while (k < len)
        {
            c[k] = gView.bytes[off + k];
            k += 1;
        }
        return c;
    }

    /** Add every same-class static method {@code code} calls to the work set. */
    private static void scanCallees(long code, int len)
    {
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            if (op == 0xb8 || op == 0xb7)                   // invokestatic / invokespecial
            {
                int idx = u2(code + pc + 1);
                FoundMethod f = calleeOf(idx);
                if (f != null)
                {
                    long c = f.code;
                    addMethod(c, ClassBytes.codeLength(c), ClassBytes.maxLocals(c), f.descOff, op == 0xb8 ? 1 : 0, f.isSync);
                }
            }
            pc += insnLen(code, pc);
        }
    }

    /** Compile method {@code i} with the shared core: its A64 at {@code base}, plus what emitMethod installs. */
    private static CompileResult compileMethod(int i, long base)
    {
        Baseline b = new Baseline(gView.bytes, gView.cp, gView.cpTag, METAL_SYMBOLS);
        // The exception_table follows the bytecode: u2 count, then {start,end,handler,
        // catch} u2s. catch=0 is a catch-all; else it's a Class cp index (as the writer's).
        long ex = mCode[i] + mLen[i];
        int n = u2(ex);
        int[] es = new int[n];
        int[] ee = new int[n];
        int[] eh = new int[n];
        int[] ec = new int[n];
        int k = 0;
        while (k < n)
        {
            long e = ex + 2 + k * 8;
            es[k] = u2(e);
            ee[k] = u2(e + 2);
            eh[k] = u2(e + 4);
            ec[k] = u2(e + 6);
            k += 1;
        }
        b.setExceptionTable(es, ee, eh, ec, n);
        // A JIT body gets a reference map only while something reads it -- the precise stack walk (StackRoots, on
        // unless gcroots=0) or the gcmaps=1 shadow check -- and never in the sizing pass (base 0), which needs only
        // the code's length. The maps are about a fifth of the code; S2 kept them off by default because cores 1-3's
        // arenas were never collected, and S5a collects them.
        b.setGcMaps(base != 0L && (StackRoots.on != 0 || GcMapShadow.on != 0));
        int[] words = b.compileBody(extractCode(i), mDescOff[i], mStatic[i] != 0, mSync[i] != 0,
                                    mLocals[i], base, false);
        return new CompileResult(b, words, ec);
    }

    /**
     * Size method {@code i} and allocate its buffer. The shared core emits fixed-width
     * address loads, so a method's word count is placement-independent — compiling at a
     * dummy base gives the exact size to reserve before the real emit pass.
     */
    private static void sizeMethod(int i)
    {
        if (mDefer[i] != 0)
        {
            mBuf.set(i, Heap.allocCode(32).toLong());               // just the stub -> no dry-run compile at load (genuine defer)
            return;
        }
        CompileResult r0 = compileMethod(i, 0L);
        int sz0 = r0.words.length * 4;                  // no map in the sizing pass: it goes to the heap anyway
        if (sz0 >= 0x80000)
        {
            Uart.write(Magic.bytes("  HUGE body bytes="));
            VM.printHex((long) sz0);
            Uart.write(Magic.bytes(" for "));
            printCurrentClass();
            Uart.putc(0x0A);
        }
        mBuf.set(i, Heap.allocCode(sz0).toLong());
    }

    /**
     * Name every method the VM COMPILES or DEFERS for one class, and say WHICH of the two happened.
     *
     * <p>THIS ANSWERS A QUESTION NO OTHER LINE CAN, and the launcher arc is currently stuck on exactly it.
     * The remaining failure is an NPE on a captured value that {@code javap} traces to one instance field,
     * assigned in one place -- {@code TestMethodTestDescriptor.<init>}'s {@code getstatic} + {@code putfield}.
     * With {@link #STATIC_ADDR_LOG} on, that class's {@code <clinit>} logs {@code ownstatic} for its
     * {@code putstatic} and the constructor's {@code getstatic} OF THE SAME FIELD IN THE SAME CLASS logs
     * nothing on any of the three resolution paths. From that I INFERRED the constructor was never compiled.
     *
     * <p>**That inference is exactly the move this file's own rules forbid** -- three reports have already
     * lied in this arc by stating a cause they never checked, and "the line is absent" has twice meant
     * "the logging does not cover this path" rather than "the code did not run". So: measure it. If the
     * constructor appears here, the silence is somewhere in {@code staticAddr} and the search moves there;
     * if it does not, the body genuinely never compiled and the bug is upstream of the constructor entirely.
     *
     * <p>**PREFIX-FILTERED, and that is not tidiness.** Every method of every class passes through here, and
     * an unfiltered dump over a ~1,700-blob closure floods the UART at 115200 baud and starves the run it is
     * meant to diagnose -- already paid for once, when the verbose patch-time dump was turned on to chase a
     * trap-wire index. One class's methods are a handful of lines.
     *
     * <p>Reports the PATH too: {@code late=1} is a lazy/on-demand/deferred-init compile ({@code compileReuseTib}
     * is set only by those), {@code late=0} a batch phase-B one. "Never compiled" and "compiled on a path
     * whose statics resolve differently" are different bugs, and the distinction costs one character here.
     */
    static final boolean COMPILE_WATCH = false;

    /** The class COMPILE_WATCH reports on, matched as a PREFIX so nested classes are included. */
    private static byte[] compileWatchClass()
    {
        return Magic.bytes("org/junit/platform/engine/discovery/ClassSelector");
    }

    /** One COMPILE_WATCH line for method {@code i}: who it is, and whether it was emitted or deferred. */
    private static void reportCompile(int i, boolean deferred)
    {
        if (gView == null || gView.thisNameOff == 0)
        {
            return;                                     // no context to name it with; say nothing rather than guess
        }
        if (!utf8HasPrefix(gView.base.toAddress(), gView.thisNameOff, compileWatchClass()))
        {
            return;
        }
        Uart.write(Magic.bytes("  compilewatch "));
        printNameAt(gView.base.toAddress(), gView.thisNameOff);
        Uart.putc(0x2E);
        // The work set records a method by its BYTECODE ADDRESS and its descriptor, never its name -- so the
        // name is recovered the same way LAZY_TRACE recovers it for a deferral entry. A 0 means the walk
        // found no method_info at that address (a cross-class callee), and a 0 offset must NOT be printed:
        // printNameAt would read whatever sits at the start of the blob and name it confidently.
        int nameOff = findNameByCode(mCode[i]);
        if (nameOff == 0)
        {
            Uart.write(Magic.bytes("<name not in this blob>"));
        }
        else
        {
            printNameAt(gView.base.toAddress(), nameOff);
        }
        printNameAt(gView.base.toAddress(), mDescOff[i]);
        if (deferred)
        {
            Uart.write(Magic.bytes(" -- DEFERRED (stub; the body compiles on first call)"));
        }
        else
        {
            Uart.write(Magic.bytes(" -- emitted buf="));
            VM.printHex(mBuf.get(i));
        }
        Uart.write(Magic.bytes(" late="));
        VM.printDec(compileReuseTib ? 1 : 0);
        Uart.putc(0x0A);
    }

    /** Emit method {@code i}'s A64 (from the shared core) into its assigned buffer. */
    /**
     * I-cache maintenance over the bodies this compile actually WROTE, not the whole arena.
     *
     * It was `publishCode(CODE_BASE, <arena pointer>)` -- a clean+invalidate per 64-byte line over EVERY line
     * of the code arena, once per class compiled, while the arena grows all boot and the bytes a class emits
     * do not. ELEVENTH instance of this file's most common defect, and the SECOND call site of the same
     * function: the patch-side twin was fixed one increment earlier (`patchRelocsFrom`, 12,680 -> 48.9ms).
     * MEASURED before being touched: `pub` was 7.074ms of a 21.486ms `compile` at batch 209 -- the largest
     * item in phase B -- and it had grown 1.080 -> 7.074ms (6.5x) across the boot while the work it publishes
     * did not. **It cost 6.9ms on a batch that compiled NOTHING**, which is the shape stated outright.
     *
     * The extent is MEASURED, not recomputed: `emitMethod` records where its store loop stopped. A deferred
     * method records 0 because `emitDeferredStub` publishes its own buffer, and the buffers are NOT
     * contiguous (`allocCode` serves swept blocks from a free list), so an arena-mark range would be unsound.
     *
     * The barriers are paid ONCE for the class rather than per method, keeping the wide version's ordering
     * exactly: every clean reaches unified memory BEFORE any invalidate is issued. Per-method publishCode
     * would have been hundreds of full barriers to avoid one arena walk -- the trade the patch-side fix
     * already rejected for the same reason.
     */
    private static void publishEmitted()
    {
        int i = 0;
        while (i < mCount)
        {
            if (mEnd.get(i) != 0L)
            {
                Heap.publishClean(Address.fromLong(mBuf.get(i)), Address.fromLong(mEnd.get(i)));
            }
            i += 1;
        }
        Heap.publishMid();
        i = 0;
        while (i < mCount)
        {
            if (mEnd.get(i) != 0L)
            {
                Heap.publishInval(Address.fromLong(mBuf.get(i)), Address.fromLong(mEnd.get(i)));
            }
            i += 1;
        }
        Heap.publishEnd();
    }

    /**
     * The reference map after a JIT body's code ({@code compiler.RefMaps}), copied into a heap {@code int[]}: the
     * address of its first element, or 0 when the body has none. Not in the code arena -- the maps are about a
     * fifth of the code they describe, and the arena is the tighter budget -- and an {@code int[]}, which the
     * collector does not scan. It stays alive while the JIT frame table names it ({@link VMGc}); a swept body's
     * entry is dropped, and the map goes with the next collection.
     */
    private static long gcMapOf(int[] words, int codeLen)
    {
        int n = words.length - codeLen;
        if (n <= 0)
        {
            return 0L;
        }
        int[] m = new int[n];
        int k = 0;
        while (k < n)
        {
            m[k] = words[codeLen + k];
            k += 1;
        }
        return ObjectReference.fromObject(m).toAddress().plus(ObjectModel.ARRAY_BASE_OFFSET).toLong();
    }

    private static void emitMethod(int i)
    {
        if (mDefer[i] != 0)                             // deferred: install a stub; the body compiles on first call
        {
            if (COMPILE_WATCH)
            {
                reportCompile(i, true);
            }
            mEnd.set(i, 0L);                               // emitDeferredStub publishes its own buffer
            LazyCompiler.emitDeferredStub(i);
            return;
        }
        if (COMPILE_WATCH)
        {
            reportCompile(i, false);
        }
        codeRootOwner = mBuf.get(i);                        // roots baked by this compile belong to this buffer
        Relocator.relocRecording = 1;                             // record unresolved cross-class sites at their real address
        CompileResult r = compileMethod(i, mBuf.get(i));    // real base -> resolved addresses
        Relocator.relocRecording = 0;
        codeRootOwner = 0L;
        int[] words = r.words;
        mLine.set(i, StackTrace.buildLineTable(i, r.bcToWord));       // stack-trace debug info (PC-offset -> source line)
        mSrc.set(i, StackTrace.sourceFileAddr());
        long out = mBuf.get(i);
        int codeLen = r.gcMapWord != 0 ? r.gcMapWord : words.length;   // the reference map rides after the code
        int k = 0;
        while (k < codeLen)
        {
            Address.fromLong(out).store(words[k]);
            out += 4;
            k += 1;
        }
        mEnd.set(i, out);                                  // the MEASURED extent of this body, for publishEmitted
        if (r.frameSize > 0)                            // let VM.unwind pop this JIT'd frame
        {
            VM.addJitFrame(Address.fromLong(mBuf.get(i)), Address.fromLong(out), r.frameSize, gcMapOf(words, codeLen));
        }
        int h = 0;                                      // register try/catch ranges: a throw in another JIT'd
        while (h < r.handlerCount)                      // method can unwind into this method's catch (cross-method)
        {
            long ms = mBuf.get(i) + (long) r.hStartW[h] * 4L;
            long me = mBuf.get(i) + (long) r.hEndW[h] * 4L;
            long hh = mBuf.get(i) + (long) r.handlerW[h] * 4L;
            long ct = 0L;                                   // 0 = catch-all (a finally, or catch_type 0)
            int catchCp = r.catchCp[h];
            if (catchCp != 0)
            {
                ct = DynamicTypeCheck.typeOfClass(catchCp).toLong();
                if (ct == 0L)
                {
                    // The catch class is not registered. This used to store the 0 typeOfClass answers --
                    // and 0 MEANS CATCH-ALL, so `catch (SomeDeniedException e)` caught EVERY exception.
                    // Not registered NOW is not not-registered-ever either (a class pulled later can be
                    // thrown later), so record the NAME and let the unwinder resolve it when an exception
                    // actually reaches this handler -- which is when JVMS 5.4.3.1 resolves a catch class.
                    ct = VM.CATCH_BY_NAME | (gView.base.plus(gView.cp[u2(gView.base.plus(gView.cp[catchCp]))]).toLong());
                }
            }
            VM.addJitHandler(Address.fromLong(ms), Address.fromLong(me), Address.fromLong(hh), ct);
            h += 1;
        }
    }


    /** Buffer assigned to the method whose bytecode is at {@code code}. */
    private static long bufOf(long code)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return mBuf.get(i);
            }
            i += 1;
        }
        return 0L;                                       // not one of this class's compiled methods
    }


    static int reportUnresolved;                        // when != 0, globalBuf prints each unresolved reference

    /** Buffer to BL for a static/special call: this class's own method, else the registry. */
    static long resolveCallBuf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gView.thisNameOff))
        {
            long local = bufOf(FoundMethod.codeOf(calleeOf(idx)));   // same class: local buffer
            if (local != 0L)
            {
                return local;
            }
            // else fall through: a same-class NATIVE (no Code attr -> calleeOf null) resolves to its VM helper;
            // an as-yet-uncompiled same-class method stays 0 here and is patched later by globalBufByRef.
        }
        long g = MethodRegistry.globalBuf(idx);                        // cross class: another loaded class
        return g != 0L ? g : nativeBuf(idx);            // else a provided java.base native, or 0
    }


    /** Registry index {@link DynamicLinker#globalBufByRef} resolved through, or -1 when it did not use the direct tier. */
    static int rgHitIdx;


    /** What the last {@link DynamicLinker#globalBufByRef} walked: valid only when {@code gwOk} (see {@code rcGate}). */
    static boolean gwOk;
    static int gwRgN;
    /** The {@code dlBucket} probe the last {@link DynamicLinker#dlCellOf} walked, as {@code rcGateDl} records it. */
    static long dlLastProbe;


    /**
     * Resolve an {@code invokestatic}/{@code invokespecial} to a PROVIDED java.base native — a real
     * method with no bytecode, implemented by a writer-stashed VM helper. This is how unmodified java.base
     * classes reach the runtime services they assume (time, bit conversions, ...) on metal.
     */
    private static long nativeBuf(int idx)
    {
        return nativeBufAt(gView.base.toAddress(), refClassNameOff(idx), gView.base.toAddress(), mrefNameOff(idx));
    }

    /**
     * The provided-native table, keyed by a class name and a method name each given as a
     * {@code (base, offset)} pair. Compile-time resolution passes the current blob's constant-pool
     * offsets ({@link #nativeBuf}); {@link DynamicLinker#resolveBakeStub} passes absolute {@code {u2 len}{bytes}}
     * runs at offset 0, so a BAKED body calling a native (its stub can't be compiled, so it fires
     * the resolver) reaches the same VM helper the loader would have linked. Before this, only the
     * compile-time path could see the table, so a metadata-only class whose baked body called a
     * native halted with {@code bakeresolve-find} — no bytecode exists for a native to fall back to.
     */
    static long nativeBufAt(Address clsBase, int clsOff, Address nameBase, int nameOff)
    {
        // jdk/internal/misc/CDS.getRandomSeedForDumping() -- stock declares it NATIVE, and its ONE caller in
        // all of java.base is ImmutableCollections.<clinit>. Answering the writer-baked build seed (rather
        // than the overlay's old hardcoded 0) is what stops that initializer falling through to nanoTime.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/CDS")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getRandomSeedForDumping"))) { return VM.cdsSeedAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/System")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("nanoTime")))          { return VM.nanoTimeAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("currentTimeMillis"))) { return VM.currentTimeMillisAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("arraycopy")))         { return VM.arraycopyAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("identityHashCode")))  { return VM.identityHashAddr; }  // ref IS its address -> identity hash
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setOut0")))           { return VM.setOut0Addr; }   // (PrintStream)V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setErr0")))           { return VM.setErr0Addr; }   // (PrintStream)V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setIn0")))            { return VM.setIn0Addr; }    // (InputStream)V
        }
        // NullPointerException.getExtendedNPEMessage() -> NULL: HotSpot's own answer with
        // -XX:-ShowCodeDetailsInExceptionMessages, and for every NPE not raised at an NPE-ing bytecode. The
        // "helpful" text is computed from the bytecode at the faulting BCI, and this VM maps a pc to a line, not
        // to a bci. Reached only after fillInStackTrace() (state 1); the VM's own implicit NPEs are allocated
        // without a constructor and stay at state 0, so getMessage() answers null without calling it.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/NullPointerException")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getExtendedNPEMessage"))) { return VM.sockZeroAddr; } // ()String -> null
        }
        // TimeZone.getSystemTimeZoneID(javaHome) -> NULL, the answer TimeZone_md.c's findJavaTZ_md gives with no
        // TZ variable and no /etc/timezone or /etc/localtime -- none of which exists here. setDefaultZone then
        // falls back to "GMT", stock's own default. getSystemGMTOffsetID is NOT mapped: it is reached only when
        // that ID fails to resolve, and it should throw if it ever is.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/TimeZone")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getSystemTimeZoneID"))) { return VM.sockZeroAddr; } // (String)String -> null
        }
        // java.lang.ref: see VMNatives.refGet0. PhantomReference declares its OWN refersTo0/clear0 natives,
        // and this table is keyed by the DECLARING class, so both classes are listed.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/ref/Reference"))
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/ref/PhantomReference")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("get0")))      { return VM.refGet0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("refersTo0"))) { return VM.refRefersTo0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("clear0")))    { return VM.refClear0Addr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/Unsafe")))
        {
            // One full barrier for all three: see VMNatives.unsafeFence. Reached from Hashtable/Properties
            // and the JUnit launcher's lazy holders.
            // Unsafe.objectFieldOffset(Class,String) -> the field registry, keyed on the CLASS (no instance
            // exists at initializer time, so the TIB-keyed VarHandle path cannot serve it).
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffsetOfClass0"))) { return VM.unsafeFieldOffsetAddr; } // (Class,byte[])J
            // Every ordered mode shares ONE full barrier: there is no one-way form to emit here, and
            // stronger than required is always correct where weaker could only be wrong invisibly.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fence0")))             { return VM.unsafeFenceAddr; }  // ()V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("parkNanos0")))         { return VM.parkNanosAddr; }    // (J)V timed park
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("staticFieldAddr0")))  { return VM.unsafeStaticAddrAddr; } // (Class,byte[])J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("storeFence")))         { return VM.unsafeFenceAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("loadFence")))          { return VM.unsafeFenceAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fullFence")))          { return VM.unsafeFenceAddr; }
            // A narrow accessor must know whether it is addressing a FIELD (one 8-byte slot) or an ARRAY
            // ELEMENT (its natural width), and the bulk moves must refuse a non-PRIMITIVE array as stock does;
            // see VMNatives.arrayKindOf for why Class.getComponentType cannot answer either question here.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("arrayKind0")))          { return VM.arrayKindAddr; } // (Object)J: 0 none, 1 primitive, 2 reference
            // The same native Constructor.allocInstance0 runs on -- zeroed fields, the class's TIB, and
            // ensureClinit first. nativeBuf keys on the DECLARING class, so it needs registering under both.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("allocInstance0")))      { return VM.allocInstanceAddr; } // (Class)Object
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("ensureClassInitialized0"))) { return VM.ensureMirrorInitAddr; } // (Class)V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("shouldBeInitialized0"))) { return VM.shouldBeInitAddr; } // (Class)J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/String")))
        {
            // The VM-wide string pool (see poolIntern). This was IDENTITY, on the premise that "no reached
            // java.base code" compares interned strings with ==. Stock StringLatin1/StringUTF16 do exactly that
            // -- `lang == "tr"` against Locale's interned language -- so Turkish case mapping was silently wrong.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("intern")))             { return VM.stringInternAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("printStackTrace0")))  { return VM.printStackTraceAddr; }   // (this)V
        }
        // java/io/UnixFileSystem.initIDs caches a JNI fieldID for File.path and does nothing else
        // (unix/native/libjava/UnixFileSystem_md.c). No JNI here, so the empty stub is exactly right. This is
        // what stopped the FIRST run-all-clinits boot, in UnixFileSystem.<clinit> itself.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/io/UnixFileSystem")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.noopNativeAddr; }  // ()V
        }
        // jdk/internal/misc/ScopedMemoryAccess.registerNatives does ONE thing: RegisterNatives binds closeScope0
        // for JNI (hotspot share/prims/scopedMemoryAccess.cpp, JVM_RegisterJDKInternalMiscScopedMemoryAccessMethods).
        // No JNI here, so the empty stub is exactly right. closeScope0 itself -- closing a SHARED foreign arena -- is
        // deliberately NOT wired: it does real work, so it must fail loudly if reached. Reached by
        // java.nio.Buffer.<clinit>, i.e. by every stock CharBuffer (Scanner's input buffer).
        // jdk/internal/misc/VM.initialize: RegisterNatives for JNI and nothing else (share/native/libjava/VM.c), so
        // empty is exactly right. The natives it would register (getNanoTimeAdjustment, ...) are wired or fail on
        // their own.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/VM")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initialize")))        { return VM.noopNativeAddr; }  // ()V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getNanoTimeAdjustment"))) { return VM.nanoTimeAdjAddr; } // (J)J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/ScopedMemoryAccess")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("registerNatives")))   { return VM.noopNativeAddr; }  // ()V
        }
        // java/io/ObjectStreamClass.initNative caches a JNI global ref to NoSuchMethodError's class, read only
        // by other natives in the same C file (share/native/libjava/ObjectStreamClass.c). No JNI here, so
        // empty is exactly right. Its SIBLING native hasStaticInitializer(Class)Z is deliberately NOT wired:
        // that one does real work (does this class declare its OWN <clinit>?), and answering a plausible
        // `false` would be a silent wrong answer -- it must be implemented or fail loudly.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/io/ObjectStreamClass")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initNative")))        { return VM.noopNativeAddr; }  // ()V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalBoard")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("buildId0")))          { return VM.boardBuildIdAddr; }     // ()J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("boardId0")))          { return VM.boardIdAddr; }          // ()I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setRebootFlags0")))   { return VM.boardRebootFlagsAddr; } // (I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("reboot0")))           { return VM.boardRebootAddr; }      // ()V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalBlock")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("init0")))             { return board.Block.hwInitAddr; }  // ()I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("read0")))             { return VM.blockReadAddr; }  // (J[BII)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("write0")))            { return VM.blockWriteAddr; } // (J[BII)I
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalConsoleIn")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("read0")))             { return VM.consoleReadAddr; }  // ([BII)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("available0")))        { return VM.consoleAvailAddr; } // ()I
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalRamfs")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("table0")))            { return VM.ramfsTableAddr; } // (J)J
            // The BCM2711 hardware RNG, behind /dev/random and /dev/urandom. Keyed by DECLARING CLASS like every
            // native here -- registering one under the wrong class is the LINK FAILED this file paid for once.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("hwEntropy0")))        { return VM.hwEntropyAddr; }  // ([BI)I
        }
        // Atomic*FieldUpdater overlays resolve the target field's byte offset the same way as VarHandle, and
        // resolve their caller's class (getCallerClass) for the field-access check.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicIntegerFieldUpdater"))
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicLongFieldUpdater"))
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicReferenceFieldUpdater")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffset0")))     { return VM.vhFieldOffsetAddr; }    // (byte[],Object)J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/FieldUpdaterCheck"))
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/AccessibleObject"))
                // Class.newInstance checks access against ITS caller, as stock's ensureMemberAccess does.
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Class"))
                // MhUtil's no-receiver findVarHandle: stock resolves the field against lookup.lookupClass(),
                // and joe-ng's Lookup carries none, so the DECLARING class comes from the caller's frame.
                // Keyed by DECLARING CLASS like every entry here -- registering a native under one of two
                // classes that declare it is the recorded `LINK FAILED ... no body for that name+descriptor`.
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/invoke/MhUtil")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("callerClass0")))     { return VM.classAtPcAddr; }        // (J)Class
        }
        // Reflective Field.get/set: resolve the field's byte offset from its DECLARING class, the lookup
        // Unsafe.objectFieldOffset uses. Keyed on the receiver's class instead, a subclass's same-named field won.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Field")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("signature0")))        { return VM.fieldSignatureAddr; } // (Class,byte[])String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffsetOfClass0"))) { return VM.unsafeFieldOffsetAddr; } // (Class,byte[])J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldAnnoGet0")))    { return VM.fieldAnnoGetAddr; }     // (Class,byte[],byte[])
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldAnnoAll0")))    { return VM.fieldAnnoAllAddr; }     // (Class,byte[])[Annotation
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldType0")))       { return VM.fieldTypeAddr; }        // (Class,byte[])Class
        }
        // Reflective Method.invoke: resolve a method-registry index by name, then its buffer/access/descriptor.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("stackTrace0")))       { return VM.stackTraceAddr; }    // (Throwable)[STE
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fillInStackTrace0"))) { return VM.fillTraceAddr; }     // (Throwable)V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Field")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("staticCell0")))       { return VM.staticCellAddr; }    // (Class,byte[])J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Method")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoDefaults0")))    { return VM.annoDefaultInstanceAddr; } // (Class)Object
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodResolve0")))   { return VM.methodResolveAddr; }    // (Class,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodResolveDesc0"))) { return VM.methodResolveDescAddr; } // (Class,byte[],byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }       // (I,byte[],long[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("exact0")))           { return VM.methodResolveExactAddr; } // (Class,byte[],byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("itableTarget0")))    { return VM.methodItableTargetAddr; } // (Object,I)J
        }
        // java.lang.invoke arc: MemberName resolution (the MethodHandleNatives overlay) walks JVMS 5.4.3 in Java
        // over these -- one class's own declaration per call, plus the reflection natives' methodInfo0.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/invoke/MethodHandleNatives")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("classBytes0")))      { return VM.classBytesAddr; }        // (Class)byte[]
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoPresent0")))     { return VM.annoPresentAddr; }       // (I,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declared0")))        { return VM.mhDeclaredAddr; }        // (Class,byte[],byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }        // (I,byte[],long[])I
        }
        // Reflective Constructor.newInstance: resolve <init> by arity, read its descriptor (shared methodInfo0),
        // and allocate the instance.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Constructor")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("ctorResolve0")))     { return VM.constructorResolveAddr; }  // (Class,I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("ctorResolveDesc0"))) { return VM.ctorResolveDescAddr; }   // (Class,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }          // (I,byte[],long[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("allocInstance0")))   { return VM.allocInstanceAddr; }       // (Class)Object
        }
        // FileDescriptor.<clinit> runs (to register the JavaIOFileDescriptorAccess); its 3 natives are inert
        // on metal -- initIDs is a no-op, and handle/append are Windows/append-mode fields unused by sockets.
        // jdk/internal/loader/BootLoader runs STOCK (the java.lang.invoke arc: ClassSpecializer finds the
        // pregenerated BoundMethodHandle species through loadClassOrNull). Its initializer's one native,
        // setBootLoaderUnnamedModule0, is JVM_SetBootLoaderUnnamedModule -> Modules::set_bootloader_unnamed_module,
        // which makes the boot loader's classes answer that Module from getModule(). Here every class already
        // answers Module.UNNAMED, the object defineUnnamedModule hands BootLoader, so EMPTY is the correct
        // semantics. getSystemPackageNames/getSystemPackageLocation stay unmapped and trap if reached.
        // jdk/internal/misc/PreviewFeatures.isPreviewEnabled() -> FALSE: JVM_IsPreviewEnabled answers
        // Arguments::enable_preview(), the --enable-preview flag, which this VM never has. Reached from
        // SwitchBootstraps.<clinit> (pattern switches linked through the generic invokedynamic path).
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/PreviewFeatures")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isPreviewEnabled"))) { return VM.sockZeroAddr; } // ()Z -> false
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalHiddenClasses")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("defineClass0"))) { return VM.defineClassAddr; } // (String,byte[],int,int)Class
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/loader/BootLoader")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setBootLoaderUnnamedModule0"))) { return VM.sockNoopAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/io/FileDescriptor")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getHandle")))         { return VM.sockZeroAddr; }    // (I)J -> 0
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getAppend")))         { return VM.sockZeroAddr; }    // (I)Z -> false
        }
        // java.net: STOCK InetAddress/Inet4Address/Inet6Address. The three init()s only cache JNI class/field IDs
        // (share/native/libnet/Inet{,4,6}Address.c), so empty is the correct semantics. Address families: IPv4
        // only -- the WiFi stack has no IPv6 -- so isIPv6Supported answers false, which makes InetAddress pick
        // Inet4AddressImpl, as a JDK built without IPv6 does.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/InetAddress")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isIPv4Available")))   { return VM.nativeTrueAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isIPv6Supported")))   { return VM.sockZeroAddr; }   // -> false
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/Inet4Address"))
                || Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/Inet6Address")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/Inet4AddressImpl")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("resolve0")))          { return VM.dnsResolveAddr; }  // (byte[])I -> WiFi DNS
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/NetworkInterface")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("wifi0")))             { return VM.netifStateAddr; }  // (J)J -> the lease
        }
        // M3 socket natives: stock sun.nio.ch backed by net.Tcp (fd int = the net.Tcp handle).
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/Net")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("socket0")))           { return VM.sockSocket0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("connect0")))          { return VM.sockConnect0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("available")))         { return VM.sockAvailableAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("shutdown0")))         { return VM.sockShutdown0Addr; } // (FD,I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("poll")))              { return VM.sockPollAddr; }     // (FD,I,J)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("localPort")))         { return VM.sockLocalPortAddr; } // (FD)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("bindPort0")))         { return VM.sockBindAddr; }     // (FD,I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("listen0")))           { return VM.sockListenAddr; }   // (FD,I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("accept0")))           { return VM.sockAcceptAddr; }   // (FD)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("remoteIp0")))         { return VM.sockRemoteIpAddr; } // (I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("remotePort0")))       { return VM.sockRemotePortAddr; } // (I)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getIntOption0")))     { return VM.sockZeroAddr; }     // -> 0 (SO_LINGER)
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("localInetAddress")))  { return VM.sockZeroAddr; }     // -> null wildcard
            // Net.<clinit> natives, answered as unix/native/libnio/ch/Net.c answers them on Linux: the <poll.h>
            // event bits (pollconnValue is POLLOUT there), isExclusiveBindAvailable -1, and the two capability
            // probes false -- this stack is IPv4-only and has no SO_REUSEPORT. initIDs only caches JNI class and
            // method IDs (InetSocketAddress, InetAddress), so empty is right.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollinValue")))       { return VM.pollInAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("polloutValue")))      { return VM.pollOutAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollerrValue")))      { return VM.pollErrAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollhupValue")))      { return VM.pollHupAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollnvalValue")))     { return VM.pollNvalAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollconnValue")))     { return VM.pollOutAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isIPv6Available0")))  { return VM.sockZeroAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isReusePortAvailable0"))) { return VM.sockZeroAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isExclusiveBindAvailable"))) { return VM.exclusiveBindNoneAddr; }  // -> -1
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("shouldShutdownWriteBeforeClose0"))) { return VM.sockZeroAddr; } // -> false
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/SocketDispatcher")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("read0")))             { return VM.sockRead0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("write0")))            { return VM.sockWrite0Addr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/UnixDispatcher")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("close0")))            { return VM.sockClose0Addr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("preClose0")))         { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/IOUtil")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fdVal")))             { return VM.fdValAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("setfdVal")))          { return VM.setFdValAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("configureBlocking"))) { return VM.sockConfigureBlockingAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("iovMax")))            { return VM.iovMaxAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("writevMax")))         { return VM.writevMaxAddr; }
        }
        // BootLoader.loadLibrary(String) is not a native, but its class stays DENIED (jdk/internal/loader), so it is
        // PROVIDED here like one. What it does on a stock JVM is dlopen a library so that library's JNI natives can
        // bind; on this VM every native is bound by THIS table, so there is no library and nothing to load --
        // empty is the correct semantics, decided the same way as initIDs. Its callers are the <clinit>s of
        // IOUtil, Net, NetworkInterface, InetAddress, ZipUtils and DefaultProxySelector.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/loader/BootLoader")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("loadLibrary")))       { return VM.sockNoopAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/NativeThread")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("current0")))          { return VM.sockZeroAddr; }     // -> 0
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("supportPendingSignals0"))) { return VM.sockZeroAddr; } // -> false
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("signal0")))           { return VM.sockNoopAddr; }      // no thread to wake
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Object")))
        {
            // The stock Object's natives (the overlay is the JDK 26 source). Each instance native takes its
            // receiver in x0, which is the one argument every helper here reads.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getClass")))          { return VM.getClassAddr; }      // (obj) -> mirror
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("hashCode")))          { return VM.identityHashAddr; }  // = identityHashCode
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("notify")))            { return VM.objNotifyAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("notifyAll")))         { return VM.objNotifyAllAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("wait0")))             { return VM.objWaitAddr; }       // (obj, ms)
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("clone")))             { return ProxySupport.objectCloneHelper(); }  // Cloneable check, then copy
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalObjects")))
        {
            // Object.clone's shallow copy: the same block copy as the [T.clone() intrinsic (TIB + body, sized by
            // the status word). Cloneable collections super.clone() and then fix their own links.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("copy")))              { return VM.arrayCloneAddr; }    // (Object)Object
        }
        // JavaLangAccess.classFileVersion/getClassFileAccessFlags: the same classfile-header reader Class uses,
        // registered under the overlay that declares its own copy (a native binds by DECLARING class).
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Proxy")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("proxyType0")))         { return VM.proxyTypeAddr; }   // ([Class,[Object,[Object,[Object,[Object)Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("proxyAlloc0")))        { return VM.proxyAllocAddr; }  // (Class)Object
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/RecordComponent")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("recordInfo0")))        { return VM.classRecordInfoAddr; }  // (Class,J)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("recordPart0")))        { return VM.classRecordPartAddr; }  // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("recordAnnos0")))       { return VM.classRecordAnnosAddr; } // (Class,J)[Annotation
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/access/MetalJavaLangAccess")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("classFileWord0")))     { return VM.classFileWordAddr; } // (Class,J)J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Array")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("newArray0")))         { return VM.newReflectArrayAddr; } // (Class,I)Object
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Class")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getName0")))          { return VM.classNameAddr; }     // (Class)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoGet0")))          { return VM.classAnnoGetAddr; }  // (Class,byte[]) -> instance
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoAll0")))          { return VM.classAnnoAllAddr; }   // (Class)[Annotation
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredClasses0")))  { return VM.classDeclClassesAddr; } // (Class)[Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("interfaces0")))       { return VM.classIfacesAddr; }   // (Class)[Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("genericSignature0"))) { return VM.classSignatureAddr; } // (Class)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("enclosingPart0")))    { return VM.classEnclosingPartAddr; } // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("nestPart0")))    { return VM.classNestPartAddr; } // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("classFileWord0")))    { return VM.classFileWordAddr; } // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("innerPart0")))        { return VM.classInnerPartAddr; }     // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("sealed0")))           { return VM.classSealedAddr; }   // (Class)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("permitted0")))           { return VM.classPermittedAddr; }   // (Class,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("forName0")))          { return VM.forNameAddr; }       // (byte[])Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("classModifiers0")))   { return VM.classModifiersAddr; } // (Class)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isInstance0")))       { return VM.instanceOfAddr; }    // (Object,J)Z == VM.instanceOf(Lmagic/ObjectReference;Lmagic/Address;)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("assignable0")))       { return VM.assignableAddr; }    // (J,J)Z -- interfaces too
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("superclass0")))       { return VM.superclassAddr; }    // (Class)Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldMods0")))        { return VM.fieldModsAddr; }     // (Class,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldTypeChar0")))    { return VM.fieldTypeCharAddr; } // (Class,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("getComponentType0")))  { return VM.componentTypeAddr; } // (Class)Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isArray0")))          { return VM.isArrayClassAddr; }  // (Class)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isPrimitive0")))      { return VM.isPrimClassAddr; }   // (Class)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("primitiveClass0")))   { return VM.primClassAddr; }     // (J)Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredMethodAt0")))  { return VM.declMethodAddr; }      // (Class,I)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredMethodDescAt0"))) { return VM.declMethodDescAddr; } // (Class,I)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredMethodCount0"))) { return VM.declMethodCountAddr; } // (Class)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredCtorCount0")))  { return VM.declCtorCountAddr; }  // (Class)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredCtorDescAt0"))) { return VM.declCtorDescAddr; }   // (Class,int)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredFieldAt0")))   { return VM.declFieldAddr; }       // (Class,I)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredFieldDescAt0"))) { return VM.declFieldDescAddr; }  // (Class,I)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredFieldCount0"))) { return VM.declFieldCountAddr; }  // (Class)J
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("stackTrace0")))       { return VM.stackTraceAddr; }    // (Throwable)[STE
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fillInStackTrace0"))) { return VM.fillTraceAddr; }     // (Throwable)V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Method")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("paramTypes0")))       { return VM.paramTypesAddr; }    // (I)Class[]
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("returnType0")))       { return VM.returnTypeAddr; }    // (I)Class
        }
        // Executable.getParameterTypes serves a CONSTRUCTOR as well as a Method -- the registry holds `<init>`
        // like any other method -- so the same native is registered under both declaring classes. A native is
        // keyed by DECLARING CLASS here, and registering it under only one of them is the LINK FAILED already
        // paid for once (ClassLoader.resourceExists0 under java/lang/Class).
        // The annotation readers moved up from Method for the same reason: a constructor's annotations are keyed
        // by its registry slot exactly as a method's are.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Executable")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("paramTypes0")))       { return VM.paramTypesAddr; }    // (I)Class[]
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoPresent0")))      { return VM.annoPresentAddr; }   // (I,byte[])I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoGet0")))          { return VM.annoGetAddr; }       // (I,byte[]) -> instance
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoAll0")))          { return VM.methodAnnoAllAddr; } // (I)[Annotation
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("signature0")))        { return VM.methodSignatureAddr; } // (I)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("exceptionName0")))    { return VM.methodExceptionNameAddr; } // (I,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("paramMeta0")))    { return VM.methodParamMetaAddr; } // (I,J)J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("paramName0")))    { return VM.methodParamNameAddr; } // (I,J)String
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("paramAnnoRow0")))    { return VM.methodParamAnnoRowAddr; } // (I,J)[Annotation
        }
        // The VM's one class namespace, under stock java.lang.ClassLoader (whose natives are Java over these).
        // Keyed by the helper, not ClassLoader: stock declares its own defineClass0 with another signature.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalLoaders")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("defineClass0")))      { return VM.defineClassAddr; }   // (String,byte[],II)Class
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("lookup0")))           { return VM.forNameNoInitAddr; } // (byte[])Class
        }
        // A test mover for the identity-hash side table (frames/GC arc S7-1): copy an object, carry its hash over.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalGcTest")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("move0")))             { return VM.testMoveAddr; }        // (Object)Object
        }
        // The VM's run-time counters (VMStats), read by the web server's statistics pages.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalStats")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("fill0")))             { return VM.statsFillAddr; }       // (I,long[])J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("taskThread0")))       { return VM.statsTaskThreadAddr; } // (I)Thread
        }
        // A /run program's stop (jdk.internal.misc.MetalRunExit): mark a thread's task, and the defining phase.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("jdk/internal/misc/MetalRunExit")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("stop0")))             { return VM.runStopAddr; }      // (Thread,Throwable)I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("defining0")))         { return VM.runDefiningAddr; }  // (Z)V
        }
        // System.exit -> Runtime.exit -> Shutdown.exit -> beforeHalt(); runHooks(); halt(status) -> halt0.
        // Both ends are NATIVE, so the class loaded fine and the calls resolved nowhere -- reported as
        // `LINK FAILED: java/lang/Shutdown.beforeHalt()V -- class OK but no body`, then a denylist trap.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Shutdown")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("beforeHalt")))        { return VM.shutdownBeforeHaltAddr; } // ()V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("halt0")))             { return VM.shutdownHalt0Addr; }      // (I)V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Thread")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("currentThread0")))    { return VM.currentThreadAddr; } // ()Thread
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("yield0")))            { return VM.threadYieldAddr; }   // ()V
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Runtime")))
        {
            // Four A72s on a BCM2711, and this VM brings all four up. Read by ForkJoinPool's initializer to
            // fix the common pool's parallelism.
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("availableProcessors"))) { return VM.availProcsAddr; } // ()I
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("gc")))                  { return VM.runtimeGcAddr; }    // ()V
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("freeMemory")))          { return VM.runtimeFreeAddr; }  // ()J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("totalMemory")))         { return VM.runtimeTotalAddr; } // ()J
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("maxMemory")))           { return VM.runtimeMaxAddr; }   // ()J
        }
        // Read once, by Finalizer's own initializer (`static final boolean ENABLED`), so every Finalizer path --
        // register, runFinalization, the finalizer thread's start -- follows this one answer.
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/ref/Finalizer")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("isFinalizationEnabled"))) { return VM.finalizationEnabledAddr; } // ()Z
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Float")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("floatToRawIntBits"))) { return VM.identityAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("intBitsToFloat")))    { return VM.identityAddr; }
        }
        if (Utf8.utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Double")))
        {
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("doubleToRawLongBits"))) { return VM.identityAddr; }
            if (Utf8.utf8IsAtBase(nameBase, nameOff, Magic.bytes("longBitsToDouble")))    { return VM.identityAddr; }
        }
        return 0L;
    }


    /** 1 if {@code tib} is one of the synthesised TIBs (lambda / annotation) held in {@code lambdaTibRoots}. */
    /**
     * Describe the object a WILD BRANCH was about to dispatch on, from inside the BOOT RE-ENTERED handler.
     *
     * <p>RECV_WATCH cannot answer this: it is armed by the METAL JIT when it lowers a dispatch site, so it
     * can only see sites the metal JIT compiled. The failing site is writer-BAKED java.base, which the metal
     * JIT never lowers -- arming the watch there yields silence, and silence reads as "no such receiver".
     *
     * <p>EVERY DEREFERENCE IS BOUNDED, because this runs after a wild branch: the register may hold anything,
     * and a report that faults replaces the diagnosis with a second fault. A TIB BELOW the heap is itself the
     * answer rather than an error -- that is a writer-BAKED TIB, which would mean the hole is in the image
     * rather than in anything the loader built.
     */
    static void reportReceiverAt(ObjectReference recv)
    {
        Uart.write(Magic.bytes("\n    recv=0x"));
        VM.printHex(recv.toLong());
        if (recv.toLong() < Heap.BASE || recv.toLong() >= Heap.managedTop().toLong())
        {
            Uart.write(Magic.bytes("  NOT A MANAGED HEAP ADDRESS (not an object)\n"));
            return;
        }
        long tib = recv.toAddress().loadLong();
        Uart.write(Magic.bytes(" tib=0x"));
        VM.printHex(tib);
        if (tib == 0L)
        {
            Uart.write(Magic.bytes("  NULL TIB\n"));
            return;
        }
        if (tib < Heap.BASE || tib >= Heap.managedTop().toLong())
        {
            Uart.write(Magic.bytes("  TIB IS BELOW THE HEAP -> a writer-BAKED TIB"));
            if (tib < 0x80000L)
            {
                Uart.write(Magic.bytes(" -- and below the IMAGE too, so not a TIB at all\n"));
                return;
            }
            // A baked TIB is image memory and is safe to dereference -- and it is precisely the case where
            // the vtable word matters MOST, because the writer FILLED those slots: a zero here is a RUNTIME
            // CLOBBER, not a pruned slot. Returning before printing it (as this did) hid the one fact the
            // report existed to produce.
            Uart.putc(0x0A);
        }
        Address tibA = Address.fromLong(tib);
        DataReference type = tibA.loadDataReference();
        Uart.write(Magic.bytes(" type=0x"));
        VM.printHex(type.toAddress());
        // The WHOLE prefix: one zero slot is a clobbered or pruned entry, all-zero is a clobbered or unbuilt TIB,
        // and the two want completely different investigations. Which slot the call READ is not known here --
        // the `x16 written at` line of the call-site report decodes the actual load. (This used to print
        // vtable[3] as "the slot read", which was toString's slot under the hand-written Object only.)
        Uart.write(Magic.bytes("\n    vtable[0..8] ="));
        int vi = 0;
        while (vi < 9)
        {
            Uart.write(Magic.bytes(" 0x"));
            VM.printHex(tibA.plus(8L).plus((long) (vi * 8)).loadLong());
            vi += 1;
        }
        Uart.write(Magic.bytes("  synthesised(lambda/anno TIB)="));
        VM.printDec(TibBuilder.isSynthesisedTib(tibA));
        Uart.write(Magic.bytes("\n    class="));
        int r = ClassRegistry.classRegByType(type.toAddress());
        if (r >= 0 && ClassRegistry.clTab != null && ClassRegistry.clTab[r] != null)
        {
            printNameAt(ClassRegistry.clTab[r].base.toAddress(), ClassRegistry.clTab[r].nameOff);
        }
        else
        {
            Uart.write(Magic.bytes("<not in the class registry>"));
        }
        Uart.putc(0x0A);
    }


    private static void registerClassStructure()
    {
        if (ClassRegistry.clCount >= ClassRegistry.MAXCLASS) { capHalt(Magic.bytes("ClassRegistry.MAXCLASS"), ClassRegistry.clCount); }              // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (Statics.sgCount + gBuild.sfCount >= MethodRegistry.MAXREG) { capHalt(Magic.bytes("MethodRegistry.MAXREG"), Statics.sgCount); }       // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (FieldRegistry.fldCount + gBuild.ifCount >= FieldRegistry.MAXFIELD) { capHalt(Magic.bytes("FieldRegistry.MAXFIELD"), FieldRegistry.fldCount); } // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (VtableLayout.vtCount + VtableLayout.gvCount >= VtableLayout.MAXVT) { capHalt(Magic.bytes("VtableLayout.MAXVT"), VtableLayout.vtCount); }          // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        ClassRegistry.clTab[ClassRegistry.clCount] = new RVMClass();
        ClassRegistry.clTab[ClassRegistry.clCount].state = RVMClass.ST_LOADED;
        ClassRegistry.clTab[ClassRegistry.clCount].base = gView.base;
        ClassRegistry.clTab[ClassRegistry.clCount].nameOff = gView.thisNameOff;
        ClassRegistry.clTab[ClassRegistry.clCount].nameHash = Utf8.utf8Hash(gView.base.toAddress(), gView.thisNameOff);       // folded once; see RVMClass.nameHash
        ClassRegistry.clTab[ClassRegistry.clCount].tib = gBuild.tib;
        ClassRegistry.clTab[ClassRegistry.clCount].type = gBuild.type;
        ClassRegistry.clTab[ClassRegistry.clCount].absentSuperOff = gBuild.absentSuperOff;
        ClassRegistry.clTab[ClassRegistry.clCount].fieldCount = gBuild.ifCount;
        ClassRegistry.clTab[ClassRegistry.clCount].vtCount = VtableLayout.gvCount;
        ClassRegistry.clTab[ClassRegistry.clCount].statics = gBuild.statics;
        ClassRegistry.clTab[ClassRegistry.clCount].vtStart = VtableLayout.vtCount;                   // this class's slots occupy vt[VtableLayout.vtCount .. VtableLayout.vtCount+gvCount)
        ClassRegistry.clTab[ClassRegistry.clCount].isIface = false;
        ClassRegistry.clTab[ClassRegistry.clCount].superNameOff = gView.superNameOff;        // keep the NAME too: superReg is -1 for ever if the
                                                        //   super was not registered yet (see RVMClass)
        ClassRegistry.clTab[ClassRegistry.clCount].superReg = ClassRegistry.classRegByName(gView.superNameOff);   // superclass registry index (-1 for Object), for the
                                                        //   FULL-chain itable closure (an interface implemented N levels
                                                        //   up the superclass chain must still land in this class's dir)
        if (VtableLayout.logVtable != 0)                             // #43: class -> vtable slot count (spot too-short vtables)
        {
            Uart.write(Magic.bytes("  C "));
            writeName(gView.base.plus(gView.thisNameOff + 2), u2(gView.base.plus(gView.thisNameOff)));
            Uart.putc(0x20);
            VM.printDec(VtableLayout.gvCount);
            Uart.putc(0x0A);
        }
        InterfaceInvocation.captureDirectIfaces();
        ClassRegistry.clCount += 1;
        LazyCompiler.armPhaseACells();                               // cells for this class's statics, before any body compiles
        Statics.registerStaticFields();
        int s = 0;
        while (s < gBuild.ifCount)
        {
            if (gBuild.ifName[s] != 0)                        // skip inherited slots (registered by the super)
            {
                FieldRegistry.fldTab[FieldRegistry.fldCount] = new RVMField();
                FieldRegistry.fldTab[FieldRegistry.fldCount].base = gView.base;
                FieldRegistry.fldTab[FieldRegistry.fldCount].classOff = gView.thisNameOff;
                FieldRegistry.fldTab[FieldRegistry.fldCount].nameOff = gBuild.ifName[s];
                FieldRegistry.fldTab[FieldRegistry.fldCount].slot = s;
                FieldRegistry.fldTab[FieldRegistry.fldCount].access = gBuild.ifAccess[s];
                FieldRegistry.fldTab[FieldRegistry.fldCount].descOff = gBuild.ifDescOff[s];
                FieldRegistry.fldCount += 1;
            }
            s += 1;
        }
        int v = 0;
        while (v < VtableLayout.gvCount)                            // register the whole flattened vtable STRUCTURE (bufs 0 for now)
        {
            VtableLayout.vtClassBase.set(VtableLayout.vtCount, gView.base.toAddress());
            VtableLayout.vtClassOff[VtableLayout.vtCount] = gView.thisNameOff;
            VtableLayout.vtNameBase.set(VtableLayout.vtCount, VtableLayout.gvTab[v].base.toAddress());           // signature blob (a super's, for inherited slots)
            VtableLayout.vtNameOff[VtableLayout.vtCount] = VtableLayout.gvTab[v].name;
            VtableLayout.vtDescOff[VtableLayout.vtCount] = VtableLayout.gvTab[v].desc;
            VtableLayout.vtSlot[VtableLayout.vtCount] = v;
            VtableLayout.vtBuf[VtableLayout.vtCount] = 0L;                        // filled by fillClassVtBuf once the bodies are compiled
            VtableLayout.vtCount += 1;
            v += 1;
        }
    }


    /**
     * Give every method of the interface being loaded a global interface-method
     * index (deduped by name+descriptor). Implementors later build an imap indexed
     * by it, and {@code invokeinterface} resolves a call site to the same index.
     */


    static int logClinit;                                     // #43 diagnostic: when != 0, name each <clinit> as it runs
    static int logTrapWire;                                  // #43 diagnostic: when != 0, dump each patchRelocs trap-wired callee


    /** VarHandle overlay: vtable slot of an op by NAME only (its op names are unique), regardless of the
     *  signature-polymorphic call-site descriptor. Returns -1 if the VarHandle overlay isn't registered yet. */


    /** Reflection: access_flags of the instance field, or -1 if the class declares no such own field. */


    /** Watch the vtable slot chosen for two named methods, whatever the slot number (see logVtableSlot). */
    static final boolean METHOD_WATCH_ON = false;


    /** True if the class of {@code *ref} {@code idx} is a loaded (registered) class. */
    private static boolean refClassRegistered(int idx)
    {
        return ClassRegistry.classRegAt(gView.base, refClassNameOff(idx)) >= 0;   // the class-name index, as classRegOf
    }

    /**
     * invokespecial is a real call (not an {@code Object.<init>} pop) if its class is loaded — except for
     * {@code java/lang/Object.<init>} itself, which is empty by definition and is a no-op in BOTH worlds
     * (the writer's compiler skips it the same way).
     *
     * <p>The exception has to be stated, not inferred from "Object isn't registered". Every constructor
     * begins with this call, and once {@link #ensureObjectBlob} put Object in every batch, the ref started
     * resolving as a real call to a method that reachability pruning had (correctly) left uncompiled —
     * {@code java/lang/String$CaseInsensitiveComparator.<init>} then sized a call to a target that does not
     * exist and dereferenced a constant-pool Utf8 as a code address. It only worked before because no batch
     * that pruned Object's body had Object registered at all.
     */
    static boolean isRealSpecial(int idx)
    {
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), refClassNameOff(idx), Magic.bytes("java/lang/Object"))
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefNameOff(idx), Magic.bytes("<init>")))
        {
            return false;
        }
        if (utf8Eq(refClassNameOff(idx), gView.thisNameOff) || refClassRegistered(idx))
        {
            return true;
        }
        // Not registered YET, but demand-loadable: emit a REAL call and let patchRelocs give it a link stub,
        // which resolves when the site is reached. Skipping it is what a deferred `new` cannot survive --
        // resolveUnresolvedNew allocates the object with the right TIB, and then the constructor beside it was
        // compiled away as if it were Object.<init>, so every field stayed 0. That is exactly how
        // `new ReferencePipeline$Head(...)` produced an object whose sourceStage was null, and
        // AbstractPipeline.isParallel then NPE'd on `sourceStage.parallel`.
        //
        // Still skipped: a DENYLISTED or genuinely absent superclass. Those are the "unloaded root" the
        // skip exists for -- a class extending something the metal environment does not have -- and turning
        // their super() into a call that traps would break boots that are correct today.
        boolean real = classLoadable(gView.base, refClassNameOff(idx));
        if (!real)
        {
            reportSkippedInit(idx);
        }
        return real;
    }

    /**
     * A non-Object {@code <init>} compiled away as a pop, named.
     *
     * <p>THIS IS THE ONE REMAINING SILENT PATH TO A RAW OBJECT. The object is still allocated with the right
     * TIB, so nothing faults and nothing traps -- its fields simply stay 0 for ever, and the first symptom is
     * an NPE on some field, in some other class, arbitrarily far away. That is how a deferred
     * {@code new ReferencePipeline$Head} produced a null {@code sourceStage}; this file records the shape
     * twice, and both times it cost boots because the skip itself said nothing.
     *
     * <p>DENYLISTED IS THE EXPECTED CASE and is reported at a lower volume than ABSENT: a class extending
     * something this VM deliberately does not carry is exactly what the skip exists for. A class ABSENT from
     * the classDir is the surprising one -- it means a constructor was dropped for a class the image was
     * supposed to be able to load.
     */
    private static void reportSkippedInit(int idx)
    {
        Uart.write(Magic.bytes("\n  CTOR SKIPPED (fields stay 0): "));
        printNameAt(gView.base.toAddress(), refClassNameOff(idx));
        Uart.putc(0x2E);
        printNameAt(gView.base.toAddress(), mrefNameOff(idx));
        if (isDenylisted(gView.base.toAddress(), refClassNameOff(idx)))
        {
            Uart.write(Magic.bytes(" -- DENYLISTED (skipping is intended)"));
        }
        else
        {
            Uart.write(Magic.bytes(" -- ABSENT from the classDir; NOT intended"));
        }
        Uart.putc((byte) 0x0A);
    }

    /** True if the class named at {@code (base, off)} could be demand-loaded: not denylisted, and present in
     *  the embedded classDir. Deliberately narrower than "not registered": see {@link #isRealSpecial}. */
    private static boolean classLoadable(DataReference base, int off)
    {
        if (isDenylisted(base.toAddress(), off))
        {
            return false;
        }
        return !VM.dirBytes(base.plus(off + 2), u2(base.plus(off))).isZero();
    }

    /**
     * Instance-field offset for a Fieldref in another class (or an inherited field
     * named through a subclass). A class-qualified match wins; failing that (the ref
     * names a subclass for a field its superclass declares), a name-only match finds
     * the inherited field's slot — the flattened layout keeps it consistent.
     */
    /** Watch ONE field name's resolved offset. A getfield that resolves to a WRONG-but-valid slot is silent:
     *  `UNRESOLVED FIELD (aliases slot 0)` fires only on a total miss. Set to a name to trace it.
     *
     *  <p>EVERY TIER REPORTS NOW, which is what makes the watch able to say "not here". Tiers 1 and 2 were
     *  logged and the other three were not, so a field resolved by the same-class fast path (tier 0) or
     *  handed slot 0 by the fall-through (tiers 3 and 4) left NO line -- indistinguishable from a field the
     *  watch never saw. Chasing the open layout-sensitivity defect, an armed watch printed nothing at all
     *  for `this$0` for exactly that reason, which reads as "the resolver is not involved" and is not the
     *  same claim. Tier 0 same-class hit, 1 class-qualified, 2 super-chain, 3 pull-noted (compile discarded
     *  and redone), 4 gave up at slot 0, 9 same-class miss falling through to the global path. */
    private static final byte[] FIELD_WATCH = Magic.bytes("className");
    private static final boolean FIELD_WATCH_ON = false;

    static void fieldOffsetLog(int classOff, int nameOff, int off, int tier)
    {
        if (!FIELD_WATCH_ON || !Utf8.utf8IsAtBase(gView.base.toAddress(), nameOff, FIELD_WATCH))
        {
            return;
        }
        Uart.write(Magic.bytes("  fieldoff "));
        printNameAt(gView.base.toAddress(), classOff);
        Uart.putc(0x2E);
        printNameAt(gView.base.toAddress(), nameOff);
        Uart.write(Magic.bytes(" -> +"));
        VM.printDec(off);
        Uart.write(Magic.bytes(" tier"));
        VM.printDec(tier);
        Uart.putc(0x0A);
    }


    /** Name a field reference that resolved to nothing -- it is about to alias slot 0. */
    static void reportUnresolvedField(Address clsU, Address nameU)
    {
        Uart.write(Magic.bytes("\n  UNRESOLVED FIELD (aliases slot 0): "));
        printNameAt(clsU, 0);
        Uart.putc(0x2E);
        printNameAt(nameU, 0);
        Uart.write(Magic.bytes(" -- needed by "));
        printCompiling();
        Uart.putc(0x0A);
    }


    /** Print the Utf8 class name at (base, off) over the UART. */
    /** Name the class the metal JIT is compiling right now. Called from {@link VM#jitFail}: a bare reason
     *  code says WHAT the compiler cannot do but never WHERE, and the where is the whole lead. */
    static void printCompiling()
    {
        if (gView == null)
        {
            Uart.write(Magic.bytes("<no compile context>"));
            return;
        }
        printNameAt(gView.base.toAddress(), gView.thisNameOff);
    }

    /** Print the {@code {u2 len}{bytes}} Utf8 at {@code off} in the blob at {@code base}. */
    static void printNameAt(Address base, int off)
    {
        int len = u2(base.plus(off));
        int i = 0;
        while (i < len)
        {
            Uart.putc(u1(base.plus(off + 2 + i)));
            i += 1;
        }
    }

    /**
     * Print a {@code {u2 len}{bytes}} Utf8 at an ABSOLUTE address, with the length CAPPED.
     *
     * <p>The cap is the whole point. {@link #printNameAt} trusts its length word, which is right where the
     * pointer is known good and catastrophic where it is not: a diagnostic added to the bakeresolve halts read
     * a length after a demand-load and streamed several hundred bytes of the stub table onto the console,
     * burying the failure it was added to name. A name is never 512 bytes, so a longer one means the pointer
     * is wrong -- which is itself worth saying, and worth saying in one short line.
     */
    static void printUtf8Capped(Address u)
    {
        if (u.isZero())
        {
            Uart.write(Magic.bytes("<null>"));
            return;
        }
        int len = u2(u);
        if (len <= 0 || len > 512)
        {
            Uart.write(Magic.bytes("<bad utf8 ptr>"));
            return;
        }
        int i = 0;
        while (i < len)
        {
            Uart.putc(u1(u.plus(2 + i)));
            i += 1;
        }
    }


    /**
     * Per-batch phase timing for {@link #loadAll} over the UART (debug). A demand-load's cost is invisible in
     * the {@code load} lines -- those time only {@link #addBlob}, i.e. putting the blob on the pending list.
     * All the real work happens afterwards in one batch, so this is what says WHERE it goes.
     */
    /**
     * The one-line-per-batch cost report (see {@link #batchCostLine}). OFF by default.
     *
     * <p>A FLAG RATHER THAN A DELETION: this line is what found all four O(everything-loaded) defects in the
     * load-path arc -- it is the only instrument that says WHICH batch is expensive and which PHASE dominates
     * it, and reconstructing that from a quiet boot is not possible. But a launcher boot is ~170 batches, so
     * left on it is ~170 lines of arithmetic nobody is reading, in exactly the logs where the program's own
     * output is what matters.
     *
     * <p>It is kept SEPARATE from {@link #LOAD_PROFILE} deliberately: that one prints a three-line block per
     * batch, which at 115200 baud starts to measure its own output, so a perf boot usually wants this alone.
     * The cumulative totals are accumulated either way -- a few adds per batch -- so turning this on does not
     * change what the numbers mean.
     */
    private static final boolean BATCH_COST = false;

    private static final boolean LOAD_PROFILE = false;

    /**
     * The two PER-CLASS boot lines -- {@code load <cls> NNus} and {@code phaseA: N cells ... for <cls>} --
     * over the UART (debug). Off by default because they are not free: a 448-class closure prints ~850 of
     * them, and at 115200 baud that is SECONDS of every boot. The `load` list is a genuinely useful
     * diagnostic (which classes a closure pulled, and in what order, has diagnosed several bugs in this
     * project), so this is a flag rather than a deletion -- one character to get them back.
     *
     * <p>Kept INDEPENDENT of {@link #LOAD_PROFILE} on purpose: a profiling boot wants the phase timings
     * WITHOUT the serial traffic those lines add to the very phases being timed.
     */
    static final boolean LOAD_TRACE = false;

    /**
     * Per-resolution chatter -- `linkresolve`/`newresolve`/`staticresolve`/`ifaceresolve`/`baked`/
     * `clinit-lazy`/`typeadopt`/`vtparity OK` and the rest. OFF by default: a boot that resolves normally
     * says nothing about it, so what remains on the wire is the program's own output and anything that
     * actually went wrong. Turn it on to watch what a batch pulls and links.
     *
     * <p>FAILURES ARE NOT GATED. A parity DIFF, an unresolved static, a null class literal and every trap
     * still print with this off -- the flag suppresses the "went fine" half only, never a diagnosis. That
     * distinction is the whole point: these lines were what made several bugs findable, and a log nobody
     * can read is the state those bugs lived in.
     */
    static final boolean LOAD_LOG = false;


    /** One line per batch: classes, relocs, registry size, and microseconds per {@link #loadAll} phase. */
    /**
     * ONE LINE per batch: which phase this batch spent its time in, and how big it was.
     *
     * <p>Deliberately compact. The question "why is this boot slow" is answered by seeing WHICH batch is
     * expensive and which PHASE dominates it, and that needs a line per batch -- but the per-batch block
     * LOAD_PROFILE prints is three lines of detail, which at 115200 baud starts to measure its own output.
     */
    private static void batchCostLine(long tAll, long tMark, long tProbe, long tA, long tB, long tPatch, long tRest, long tEnd)
    {
        Uart.write(Magic.bytes("  batch "));
        VM.printDec(cumBatches);
        Uart.write(Magic.bytes(": +"));
        VM.printDec(pdCount);                            // blobs in this batch
        Uart.write(Magic.bytes("blob mark="));
        printDur(spanUs(tMark, tProbe));
        Uart.write(Magic.bytes(" probe="));
        printDur(spanUs(tProbe, tA));
        Uart.write(Magic.bytes(" A="));
        printDur(spanUs(tA, tB));
        Uart.write(Magic.bytes(" B="));
        printDur(spanUs(tB, tPatch));
        Uart.write(Magic.bytes("[work="));
        long bWorkUs = bWorkT * 1000000L / Magic.readCNTFRQ_EL0();
        printDur(bWorkUs);
        Uart.write(Magic.bytes(" scan="));
        printDur(spanUs(tB, tPatch) - bWorkUs);          // B is tB..tPatch; tA..tB is phase A
        Uart.write(Magic.bytes(" passes="));
        VM.printDec(bPasses);
        Uart.write(Magic.bytes(" checks="));
        VM.printDec(bChecks);
        Uart.write(Magic.bytes(" | parse="));
        printDur(bWorkT == 0L ? 0L : lbParseT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" clinit="));
        printDur(lbClinitT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" compile="));
        printDur(lbCompileT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" reg="));
        printDur(lbRegT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" | seed="));
        printDur(ccSeedT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" place="));
        printDur(ccPlaceT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" tib="));
        printDur(ccTibT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" emit="));
        printDur(ccEmitT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" pub="));
        printDur(ccPubT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" | cp="));
        printDur(pParseCpT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" fields="));
        printDur(pFieldsT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" vtab="));
        printDur(pVtableT * 1000000L / Magic.readCNTFRQ_EL0());
        Uart.write(Magic.bytes(" pcCalls="));
        VM.printDec(pcCalls);
        Uart.write(Magic.bytes(" pcHits="));
        VM.printDec(pcHits);
        Uart.write(Magic.bytes(" pcSteps="));
        VM.printDec((int) (pcSteps / 1000L));
        Uart.write(Magic.bytes("k"));
        Uart.putc(0x5D);
        Uart.write(Magic.bytes(" patch="));
        printDur(spanUs(tPatch, tRest));
        Uart.write(Magic.bytes(" clinit="));
        printDur(spanUs(tRest, tEnd));
        Uart.write(Magic.bytes(" tot="));
        printDur(spanUs(tAll, tEnd));
        Uart.write(Magic.bytes(" rounds="));
        VM.printDec(Closure.mrRounds);
        Uart.write(Magic.bytes(" pend="));
        VM.printDec(Closure.pendN);
        // THE MARKED SET ITSELF, which is the only number that says the closure is the same. `pend` is a
        // QUEUE LENGTH and `grew` a count of level-visits, so both move when redundant work is removed while
        // the answer stays put -- neither can tell that apart from under-marking, and under-marking here is
        // SILENT (reach 1040 -> 449 once, with standalone runs still building the right closure).
        Uart.write(Magic.bytes(" reach="));
        VM.printDec(Closure.reachN);
        // The mark's SUB-phases on the same line. `virt` (resolveVirtuals) is the named remaining bottleneck
        // from the demand-load arc -- "65% of the mark, 40% of the first batch", left unfixed because the
        // sound invalidation rule was never found -- so it is the first thing this has to be able to confirm
        // or rule out.
        Uart.write(Magic.bytes(" v:walks="));
        VM.printDec(Closure.vWalks);
        Uart.write(Magic.bytes(" levels="));
        VM.printDec((int) Closure.vLevels);
        Uart.write(Magic.bytes(" grew="));
        VM.printDec((int) Closure.vLevelsGrew);
        Uart.write(Magic.bytes(" cached="));
        VM.printDec(Closure.vCached);
        Uart.write(Magic.bytes(" parsed="));
        VM.printDec(Closure.vParsed);
        Uart.write(Magic.bytes(" meth="));
        VM.printDec((int) (Closure.vMethods / 1000L));
        Uart.write(Magic.bytes("k cacheT="));
        printDur(ticksUs(Closure.vCacheT));
        Uart.write(Magic.bytes(" parseT="));
        printDur(ticksUs(Closure.vParseT));
        Uart.write(Magic.bytes(" [virt="));
        printDur(ticksUs(Closure.mrVirt));
        Uart.write(Magic.bytes(" pull="));
        printDur(ticksUs(Closure.mrPull));
        Uart.write(Magic.bytes(" coll="));
        printDur(ticksUs(Closure.mrCollect));
        Uart.write(Magic.bytes(" seed="));
        printDur(ticksUs(Closure.mrSeed));
        Uart.write(Magic.bytes(" probe="));
        printDur(ticksUs(Closure.mrProbe));
        Uart.write(Magic.bytes(" dflt="));
        printDur(ticksUs(Closure.mrDflt));
        // struct/inst/static WERE ALREADY MEASURED AND SIMPLY NOT PRINTED, and that hid where `mark` went:
        // the six above sum to ~294ms of a 711ms mark at batch 165 of a launcher boot, so ~59% of it was
        // unattributed. A sub-split that does not add up to its total is not a split; print every term.
        Uart.write(Magic.bytes(" struct="));
        printDur(ticksUs(Closure.mrStruct));
        Uart.write(Magic.bytes(" fetch="));            // INSIDE pull+struct, not beside them -- see mrFetch
        printDur(ticksUs(Closure.mrFetch));
        // ... and what the fetch is DOING, since the timer alone cannot say. Cumulative over the launch, not
        // per batch: the question these answer is which term GROWS, and both scans grow with what has already
        // been asked (the name cache) or with the archive (the central directory).
        Uart.write(Magic.bytes(" jf:n="));
        VM.printDec((int) JarFs.jfLookups);
        Uart.write(Magic.bytes(" scan="));
        VM.printDec((int) (JarFs.jfScanSteps / 1000L));
        Uart.write(Magic.bytes("k finds="));
        VM.printDec((int) JarFs.jfFinds);
        Uart.write(Magic.bytes(" fsteps="));
        VM.printDec((int) (ZipDir.findSteps / 1000L));
        Uart.write(Magic.bytes("k infl="));
        VM.printDec((int) (JarFs.jfInflated / 1024L));
        // COLLECTIONS, because the counters above ruled everything else out. Batch 188 of a launcher boot is
        // 564ms of `fetch` for SIX lookups, two directory searches and 3 KB inflated -- microseconds of work.
        // What sits inside that bracket and none of those counters is `Heap.allocData` in JarFs.remember, and
        // an allocation that happens to cross the threshold pays for a whole collection. That would be
        // deterministic batch-for-batch (same allocation sequence), and it would be untouched by making the
        // inflater 16x cheaper -- which is exactly what the last two boots showed.
        Uart.write(Magic.bytes("k gc="));
        VM.printDec(Heap.gcPressure);
        Uart.write(Magic.bytes(" inst="));
        printDur(ticksUs(Closure.mrInst));
        Uart.write(Magic.bytes(" static="));
        printDur(ticksUs(Closure.mrStatic));
        Uart.write(Magic.bytes(" reset="));
        printDur(ticksUs(mrReset));
        Uart.write(Magic.bytes(" alloc="));
        printDur(ticksUs(mrAlloc));
        Uart.write(Magic.bytes(" entry="));
        printDur(ticksUs(mrEntry));
        Uart.write(Magic.bytes(" root="));
        printDur(ticksUs(mrRoot));
        Uart.write(Magic.bytes(" exc="));
        printDur(ticksUs(mrExc));
        Uart.write(Magic.bytes("] {imap="));
        printDur(ticksUs(cumRfImap));
        Uart.write(Magic.bytes(" synth="));
        printDur(ticksUs(cumRfSynth));
        Uart.write(Magic.bytes(" arr="));
        printDur(ticksUs(cumRfArr));
        Uart.write(Magic.bytes(" seeds="));
        printDur(ticksUs(cumSeeds));
        Uart.write(Magic.bytes(" runcl="));
        printDur(ticksUs(cumRunCl));
        // The COUNTS these walk, so the growth is visible alongside the time rather than inferred from it.
        Uart.write(Magic.bytes(" n:imap="));
        VM.printDec(InterfaceInvocation.instImapN);
        Uart.write(Magic.bytes(" synth="));
        VM.printDec(TibBuilder.lambdaTibRootN);
        Uart.write(Magic.bytes(" clinits="));
        VM.printDec(ClassInitializer.clinitN);
        // The refill memo, MEASURED: skip = the memo firing, clos = the closure walks it was meant to avoid,
        // holeEnd = imaps still short after a repair (those can never be memoised and pay in full every batch).
        Uart.write(Magic.bytes(" rf:skip="));
        VM.printDec(InterfaceInvocation.rfSkip);
        Uart.write(Magic.bytes(" visit="));
        VM.printDec(InterfaceInvocation.rfVisit);
        Uart.write(Magic.bytes(" clos="));
        VM.printDec(InterfaceInvocation.rfClos);
        Uart.write(Magic.bytes(" holeEnd="));
        VM.printDec(InterfaceInvocation.rfHoleEnd);
        // WHICH of the three is the growing one. `type` is the only walk that is O(everything loaded).
        Uart.write(Magic.bytes(" rfs:type="));
        VM.printDec(ClassRegistry.rfTypeSteps / 1000);
        Uart.write(Magic.bytes("k clos="));
        VM.printDec(InterfaceInvocation.rfClosSteps / 1000);
        Uart.write(Magic.bytes("k fill="));
        VM.printDec(InterfaceInvocation.rfFillSteps / 1000);
        // INSIDE a still-0 slot, which the three above cannot see. `hcls`/`hnam` are name BYTES folded per
        // probe -- they dwarfed the slot reads 22:1, so the refill's cost was re-deriving an immutable hash
        // rather than scanning anything. `hcls=0k` is the cached class-name prefix doing its job.
        Uart.write(Magic.bytes("k dbs="));
        VM.printDec(InterfaceInvocation.rfDbsCalls / 1000);
        Uart.write(Magic.bytes("k probe="));
        VM.printDec(InterfaceInvocation.rfProbeSteps / 1000);
        Uart.write(Magic.bytes("k hcls="));
        VM.printDec(rfHashSteps / 1000);
        Uart.write(Magic.bytes("k hnam="));
        VM.printDec(InterfaceInvocation.rfHashNameSteps / 1000);
        Uart.write(Magic.bytes("k chain="));
        VM.printDec(InterfaceInvocation.rfChainSteps / 1000);
        // THE SEED BLOCK's registry walks. `seeds` is the largest item in this phase and had never been
        // split; these say how much of it is `staticSlotOf` scanning a table that grows all boot.
        Uart.write(Magic.bytes("k sd:n="));
        VM.printDec(Statics.sgScanCalls);
        Uart.write(Magic.bytes(" steps="));
        VM.printDec(Statics.sgScanSteps / 1000);
        Uart.putc(0x6B);
        // allocCode, the difference between `place` and `emit`: calls, and the free-list scan under them.
        Uart.write(Magic.bytes(" ac:n="));
        VM.printDec((int) (Heap.codeAllocCalls / 1000L));
        Uart.write(Magic.bytes("k scan="));
        VM.printDec((int) (Heap.codeScanSteps / 1000L));
        Uart.write(Magic.bytes("k reuse="));
        VM.printDec((int) (Heap.codeReuseCount / 1000L));
        Uart.write(Magic.bytes("k bump="));
        VM.printDec((int) (Heap.codeBumpCount / 1000L));
        Uart.putc(0x6B);
        // The probe watermark: blobs actually probed THIS batch against the table it walks. A batch that adds
        // one class should read pb:1 -- if it reads pdCount the memo is not firing, which a falling `probe`
        // timer alone could not distinguish from a batch that happened to be cheap.
        Uart.write(Magic.bytes(" pb:probed="));
        VM.printDec(pbProbed);
        Uart.write(Magic.bytes(" of="));
        VM.printDec(pdCount);
        // patch re-walk: sites taking the memo vs re-resolving vs unresolved (cumulative), plus the site count
        Uart.write(Magic.bytes(" pc:n="));
        VM.printDec(Relocator.rcCount);
        Uart.write(Magic.bytes(" memo="));
        VM.printDec(Relocator.pcMemo);
        Uart.write(Magic.bytes(" res="));
        VM.printDec(Relocator.pcResolve);
        Uart.write(Magic.bytes(" unres="));
        VM.printDec(Relocator.pcUnres);
        Uart.write(Magic.bytes(" callT="));
        printDur(ticksUs(Relocator.pcCallT));
        Uart.write(Magic.bytes(" statT="));
        printDur(ticksUs(Relocator.pcStatT));
        // WHAT IS INSIDE `statT`, which nothing has ever split. `n` is sites resolved across all batches,
        // `steps` the registry entries compared, `miss` the sites that found nothing and walked the whole
        // table to say so. steps/n is the walk's depth: if it tracks sgCount the scan is the cost and an
        // index pays; if it stays small the cost is per-SITE and an index buys nothing.
        Uart.write(Magic.bytes(" ps:n="));
        VM.printDec((int) psSites);                      // RAW: the first cut printed these in thousands and
        Uart.write(Magic.bytes(" steps="));              //   both read 0k, which says nothing at all
        VM.printDec((int) (Statics.psSteps / 1000L));
        Uart.write(Magic.bytes("k miss="));
        VM.printDec((int) psMiss);
        Uart.write(Magic.bytes(" tab="));                // ... and the table they walk, so steps/n can be
        VM.printDec(Statics.sgCount);                            //   read against the depth a full walk would cost
        Uart.write(Magic.bytes(" rb:n="));               // the OTHER scan in the same loop: regBySigU, run on
        VM.printDec((int) ClassRegistry.rbCalls);                      //   every miss, over a SECOND table that also grows
        Uart.write(Magic.bytes(" steps="));
        VM.printDec((int) (ClassRegistry.rbSteps / 1000L));
        Uart.write(Magic.bytes("k cl="));
        VM.printDec(ClassRegistry.clCount);
        // WHAT IS INSIDE `synth`, which nothing has ever split. `chg` is the decisive one: writes whose
        // value actually differed from what the slot held. Near zero and the pass is re-deriving an answer
        // it already has; large and only the objectClassIndex hoist (`obj`) is available.
        Uart.write(Magic.bytes(" sy:n="));
        VM.printDec((int) TibBuilder.syTibs);                       // RAW: this is the counter that must FREEZE once
        Uart.write(Magic.bytes(" slots="));              //   every TIB has latched, the way sd:n does
        VM.printDec((int) (TibBuilder.sySlots / 1000L));
        Uart.write(Magic.bytes("k chg="));
        VM.printDec((int) TibBuilder.syChanged);
        Uart.write(Magic.bytes(" obj="));
        VM.printDec((int) (syObjSteps / 1000L));
        Uart.putc(0x6B);
        Uart.write(Magic.bytes(" pubT="));
        printDur(ticksUs(Relocator.pcPubT));
        Uart.write(Magic.bytes(" lookT="));
        printDur(ticksUs(Relocator.pcLookT));
        // findPdByName: a LINEAR scan over pdCount, called by globalBufByRef tier 2 once for the ref class
        // plus once per superclass LEVEL. Counts, not timers -- two readCNTPCT per call would be a visible
        // share of what is being measured (already paid for once, in the imap refill).
        Uart.write(Magic.bytes(" fp:n="));
        VM.printDec((int) fpCalls);
        Uart.write(Magic.bytes(" steps="));
        VM.printDec((int) (fpSteps / 1000));
        Uart.write(Magic.bytes("k t1="));
        VM.printDec((int) (t1Steps / 1000));
        Uart.write(Magic.bytes("k t2="));
        VM.printDec((int) (t2Steps / 1000));
        Uart.write(Magic.bytes("k dl="));
        VM.printDec((int) (dlSteps / 1000));
        Uart.write(Magic.bytes("k"));
        // WHY the unres arm costs what it does: sites it answered from the memo against sites that ran the
        // full arm, and the two growing scans inside that arm.
        Uart.write(Magic.bytes(" un:memo="));
        VM.printDec(pcUnresMemo / 1000);
        Uart.write(Magic.bytes("k full="));
        VM.printDec(pcUnresDeny / 1000);
        Uart.write(Magic.bytes("k deny="));
        VM.printDec(pcDenySteps / 1000);
        Uart.write(Magic.bytes("k stub="));
        VM.printDec(pcStubSteps / 1000);
        Uart.write(Magic.bytes("k unresT="));
        printDur(ticksUs(Relocator.pcUnresT));
        Uart.write(Magic.bytes(" tailT="));
        printDur(ticksUs(Relocator.pcTailT));
        Uart.write(Magic.bytes(" skip="));
        VM.printDec((int) (Relocator.pcSkipped / 1000L));
        Uart.putc(0x6B);
        // CUMULATIVE, and on the batch line rather than in a report nobody runs: this is the only number
        // that says whether the loader lock was ever actually contended. Zero across a whole boot means the
        // run never tested the lock, so a clean boot says nothing about it either way.
        Uart.write(Magic.bytes(" lk:wait="));
        VM.printDec(VM.loaderWaits);
        Uart.write(Magic.bytes(" yields="));
        VM.printDec(VM.loaderWaitYields);
        Uart.write(Magic.bytes(" rel="));
        VM.printDec(VM.loaderReleases);
        // INITIALIZERS RUN UNDER THE LOCK, cumulative. The per-occurrence report is capped at eight because
        // it fires on nearly every initializer; without this total, "capped and counted" would be only half
        // true -- the count would exist and be invisible, which is the shape of an instrument that cannot be
        // read. A non-zero value is EXPECTED today (the lazyCompile -> ensureClinit route): it measures a
        // standing hazard rather than an event, and it is the number that would drop if the clinit phase
        // were ever moved out from under the lock.
        Uart.write(Magic.bytes(" clinitLk="));
        VM.printDec(ClassInitializer.clinitLockN);
        // ACC_SYNCHRONIZED coverage is NOT reported here. It was, and it could not fire: a launched
        // program's methods compile lazily on first call, long after batch 1's line is printed, so this
        // line read "0 static synchronized methods" for a program full of them and I read that as "all
        // covered". See reportSyncCoverage, which prints at the END of a launch, where the answer exists.
        Uart.write(Magic.bytes("}"));
        Uart.putc(0x0A);
    }

    /**
     * Where the WHOLE boot's load time went, in one line. Call it from anywhere (a demo, a probe, the end of
     * a launch); it costs nothing until called.
     *
     * <p>Prints the per-phase totals AND the bytes {@link Closure#markReachable} allocated across every batch,
     * because those are two different kinds of cost and the fix differs: time in `mark` is the closure walk,
     * while bytes are GC pressure from the per-batch tables being rebuilt. A boot can be slow for either.
     */
    static void loadCostReport()
    {
        Uart.write(Magic.bytes("\nload cost: batches="));
        VM.printDec(cumBatches);
        Uart.write(Magic.bytes(" mark="));
        printDur(ticksUs(cumMark));
        Uart.write(Magic.bytes(" probe="));
        printDur(ticksUs(cumProbe));
        Uart.write(Magic.bytes(" phaseA="));
        printDur(ticksUs(cumA));
        Uart.write(Magic.bytes(" phaseB="));
        printDur(ticksUs(cumB));
        Uart.write(Magic.bytes(" patch="));
        printDur(ticksUs(cumPatch));
        Uart.write(Magic.bytes(" clinit="));
        printDur(ticksUs(cumClinit));
        Uart.write(Magic.bytes(" total="));
        printDur(ticksUs(cumAll));
        Uart.write(Magic.bytes("\n           classes="));
        VM.printDec(ClassRegistry.clCount);
        Uart.write(Magic.bytes(" blobs="));
        VM.printDec(pdCount);
        Uart.write(Magic.bytes(" markTables="));
        VM.printDec((int) (Closure.cumBytes >> 20));
        Uart.write(Magic.bytes("MB allocated over those batches (MAXPEND="));
        VM.printDec(Closure.MAXPEND);
        Uart.write(Magic.bytes(")\n"));
    }

    private static void profileLoadAll(long tAll, long tMark, long tProbe, long tA, long tB, long tPatch, long tRest, long tEnd)
    {
        Uart.write(Magic.bytes("  loadall pd="));
        VM.printDec(pdCount);
        Uart.write(Magic.bytes(" rc="));
        VM.printDec(Relocator.rcCount);
        Uart.write(Magic.bytes(" rg="));
        VM.printDec(MethodRegistry.rgCount);
        Uart.write(Magic.bytes(" mark="));
        printDur(spanUs(tMark, tProbe));
        Uart.write(Magic.bytes(" probe="));
        printDur(spanUs(tProbe, tA));
        Uart.write(Magic.bytes(" A="));
        printDur(spanUs(tA, tB));
        Uart.write(Magic.bytes(" B="));
        printDur(spanUs(tB, tPatch));
        Uart.write(Magic.bytes(" patch="));
        printDur(spanUs(tPatch, tRest));
        Uart.write(Magic.bytes(" clinit="));
        printDur(spanUs(tRest, tEnd));
        Uart.write(Magic.bytes(" total="));
        printDur(spanUs(tAll, tEnd));
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("    mark rounds="));
        VM.printDec(Closure.mrRounds);
        Uart.write(Magic.bytes(" reach="));
        VM.printDec(Closure.reachN);
        Uart.write(Magic.bytes(" pend="));
        VM.printDec(Closure.pendN);
        Uart.write(Magic.bytes(" probe="));
        printDur(ticksUs(Closure.mrProbe));
        Uart.write(Magic.bytes(" seed="));
        printDur(ticksUs(Closure.mrSeed));
        Uart.write(Magic.bytes(" collect="));
        printDur(ticksUs(Closure.mrCollect));
        Uart.write(Magic.bytes(" pull="));
        printDur(ticksUs(Closure.mrPull));
        Uart.write(Magic.bytes(" struct="));
        printDur(ticksUs(Closure.mrStruct));
        Uart.write(Magic.bytes(" inst="));
        printDur(ticksUs(Closure.mrInst));
        Uart.write(Magic.bytes(" static="));
        printDur(ticksUs(Closure.mrStatic));
        Uart.write(Magic.bytes(" virt="));
        printDur(ticksUs(Closure.mrVirt));
        Uart.write(Magic.bytes(" dflt="));
        printDur(ticksUs(Closure.mrDflt));
        Uart.putc(0x0A);
    }

    /** A raw CNTPCT tick count as microseconds. */
    private static long ticksUs(long ticks)
    {
        long f = Magic.readCNTFRQ_EL0();
        if (f == 0L)
        {
            return 0L;
        }
        return ticks * 1000000L / f;
    }

    /** Microseconds between two {@code CNTPCT_EL0} readings. */
    private static long spanUs(long t0, long t1)
    {
        long f = Magic.readCNTFRQ_EL0();
        if (f == 0L)
        {
            return 0L;
        }
        return (t1 - t0) * 1000000L / f;
    }


    /** Append the Utf8 at {@code utf8Addr} as it is. */
    static int putUtf8(byte[] out, int p, Address utf8Addr)
    {
        int len = u2(utf8Addr);
        int i = 0;
        while (i < len && p < out.length)
        {
            out[p] = (byte) u1(utf8Addr.plus(2L + i));
            p += 1;
            i += 1;
        }
        return p;
    }


    /** Registry index of the loaded class named by the absolute utf8 run {@code clsU}, or -1. */
    /**
     * The Type of the class whose Utf8 name is at {@code clsU}, or 0 if it is not registered -- for a JIT
     * handler whose catch class was unresolved when the method compiled ({@code VM.CATCH_BY_NAME}).
     *
     * <p>0 means NO MATCH there, and that is exact rather than conservative: an exception object exists, so its
     * class and every superclass are registered. A catch class that is not registered can therefore not be one
     * of them -- whether it is denied, absent, or simply never loaded.
     */
    static long catchTypeByName(long clsU)
    {
        int r = DynamicLinker.regBySigU(Address.fromLong(clsU));
        return r < 0 ? 0L : ClassRegistry.clTab[r].type.toLong();
    }


    /**
     * M8 world unification: linked baked code dispatches invokevirtual by WRITER vtable-slot number
     * on whatever receiver it is handed -- usually a LOADER-built object. That is sound only if both
     * worlds flatten a class's vtable identically, so for every baked class (the writer emits its
     * slot signatures) verify OUR freshly-built flattening slot-for-slot at structure time. A
     * divergence prints loudly BEFORE any cross-world dispatch can land on the wrong slot.
     */


    /** M8 statics unification: replace the freshly-allocated gBuild.statics with the writer's dense
     *  per-class block -- ONE home per static field across both worlds (this world's <clinit>
     *  run then initializes the SHARED slots; deferred classes carry the seed-JVM snapshot).
     *  Slot numbering = declared statics in declaration order on both sides; a count mismatch
     *  keeps the loader block (safe degrade) and prints the divergence. */
    private static void adoptStatics()
    {
        if (gBuild.adoptStatics.isZero())
        {
            return;
        }
        if (gBuild.adoptStaticCount != gBuild.sfCount)
        {
            if (LOAD_LOG)
            {
                Uart.write(Magic.bytes("  staticadopt "));
                printNameAt(gView.base.toAddress(), gView.thisNameOff);
                Uart.write(Magic.bytes(" DIFF "));
                VM.printDec(gBuild.adoptStaticCount);
                Uart.putc(0x2F);
                VM.printDec(gBuild.sfCount);
                Uart.putc(0x0A);
            }
            return;
        }
        gBuild.statics = gBuild.adoptStatics;
    }


    /** The name Utf8 offset of the current blob's method whose Code attribute is at {@code code} -- the
     *  inverse of {@link #findMethodByOffsets}, for deferred entries that captured only bytecode.
     *  Compile context must be established (gp at the method table). 0 if not found. */
    static int findNameByCode(long code)
    {
        Address p = gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));
            if (findCode(gView.base.toAddress(), p.plus(8), attrs) == code)
            {
                return gView.cp[u2(p.plus(2))];
            }
            p = skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return 0;
    }

    /** Find a method in the current blob by its (name, descriptor) Utf8 offsets, like {@link #findMethod};
     *  null if the class has no such method with a body. */
    static FoundMethod findMethodByOffsets(int nameOff, int descOff)
    {
        Address p = gp;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p.plus(6));
            if (gView.cp[u2(p.plus(2))] == nameOff && gView.cp[u2(p.plus(4))] == descOff)
            {
                long code = findCode(gView.base.toAddress(), p.plus(8), attrs);
                if (code != 0L)
                {
                    return new FoundMethod(code, gView.cp[u2(p.plus(4))], access);
                }
            }
            p = skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return null;
    }


/** Allocate the shared lazy tables + trampoline if not yet done (shared by 1b and 1c). */

    /** The registered body interface {@code ir} declares for (name, desc in {@code base}), or 0 if none is. */
    static long regBodyIn(int ir, Address base, int nameOff, int descOff)
    {
        DataReference ibase = ClassRegistry.clTab[ir].base;
        int k = MethodRegistry.rgBucket[Utf8.utf8HashFrom(ClassRegistry.clTab[ir].nameHash, base, nameOff) & (MethodRegistry.RGTAB - 1)];
        while (k >= 0)
        {
            if (MethodRegistry.rgTab[k].base.EQ(ibase) && MethodRegistry.rgTab[k].buf.toLong() != 0L
                    && utf8EqAt(base, nameOff, MethodRegistry.rgTab[k].base.toAddress(), MethodRegistry.rgTab[k].nameOff)
                    && utf8EqAt(base, descOff, MethodRegistry.rgTab[k].base.toAddress(), MethodRegistry.rgTab[k].descOff))
            {
                return MethodRegistry.rgTab[k].buf.toLong();
            }
            k = MethodRegistry.rgNext[k];
        }
        return 0L;
    }


    /** The Utf8 at {@code base+off} as a fresh guest String (no '/'->'.' rewrite; this is a plain name). */
    static long utf8ToString(Address base, int off)
    {
        int len = u2(base.plus(off));
        ObjectReference arr = Heap.allocArray(len, 1).toObjectReference();
        int k = 0;
        while (k < len)
        {
            arr.toAddress().plus(24L).plus(k).store((byte) u1(base.plus(off + 2 + k)));
            k += 1;
        }
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().plus(0L).store(stringTib());
        obj.toAddress().plus(16L).store(arr);
        return obj.toLong();
    }


    /** Append the ASCII of {@code v} (decimal, signed) at {@code p}; returns the new position. */
    static int putDec(char[] out, int p, long v, boolean signed)
    {
        // THE DIGITS ARE TAKEN IN THE NEGATIVE DOMAIN, for the reason {@code VMConcat.scLong} already
        // records: there is no type wider than long to borrow, and {@code -Long.MIN_VALUE} is still
        // {@code Long.MIN_VALUE} -- negating left the value NEGATIVE, the {@code v > 0} loop ran ZERO
        // times, the {@code v == 0} arm did not fire either, and {@code Long.MIN_VALUE} rendered as a
        // BARE "-" with no digits at all. The negative side of two's complement holds one more value than
        // the positive side, so working there covers every long; {@code v % 10} is then non-positive and
        // negating just the DIGIT is always in range.
        //
        // REACHABLE RATHER THAN THEORETICAL, which is why it is fixed here and not merely noted: this
        // renders one component of a record's toString, and the line below hands it B/S/I/J directly AND
        // F/D BY THEIR RAW BITS -- and {@code Double.doubleToRawLongBits(-0.0)} is 0x8000000000000000,
        // which IS {@code Long.MIN_VALUE}. So a record with a {@code double} field holding -0.0 hit this,
        // as did any {@code long} field at MIN_VALUE.
        if (signed && v < 0L)
        {
            if (p < out.length)
            {
                out[p] = '-';
                p += 1;
            }
        }
        else if (v > 0L)
        {
            v = -v;                                      // magnitude, in the negative domain
        }
        byte[] tmp = new byte[24];
        int k = 0;
        if (v == 0L)
        {
            tmp[0] = (byte) '0';
            k = 1;
        }
        while (v < 0L)
        {
            tmp[k] = (byte) ('0' - (int) (v % 10L));
            v = v / 10L;
            k += 1;
        }
        while (k > 0 && p < out.length)
        {
            k -= 1;
            out[p] = (char) tmp[k];
            p += 1;
        }
        return p;
    }

    /**
     * Append the Utf8 at {@code utf8Addr} (u2 length, then bytes), DECODED from modified UTF-8.
     *
     * <p>This renders a record COMPONENT NAME, and a Java identifier may hold any letter -- {@code record
     * N(int \u00e9)} is legal -- so copying the encoded bytes verbatim is the same defect the value side
     * carries, one field over. {@link #decodeUtf8At} is the decoder {@link #internString} already uses.
     *
     * <p>Its {@code decCh}/{@code decNext} statics are safe here because the name is consumed in this loop
     * and nothing between two iterations can run guest code -- unlike {@link ObjectMethods#putComponent}'s reference arm,
     * which calls {@code toString} and could reach a literal intern.
     */
    static int putUtf8(char[] out, int p, Address utf8Addr)
    {
        int len = u2(utf8Addr);
        Address src = utf8Addr.plus(2L);
        int i = 0;
        while (i < len && p < out.length)
        {
            decodeUtf8At(src, i, len);
            out[p] = (char) decCh;
            p += 1;
            i = decNext;
        }
        return p;
    }


    /**
     * Append a guest {@code String}'s characters, HONOURING ITS CODER; {@code "?"} if it or its array is
     * missing.
     *
     * <p>A String's {@code value} array is its bytes in one of two encodings and the {@code coder} field at
     * offset 24 says which -- so copying that array verbatim is right for LATIN1 and produces GARBAGE for
     * UTF16, where each character is two little-endian bytes. A {@code record R(String s)} holding
     * {@code "\u20ac"} rendered as the two LATIN1 characters {@code \u00ac} and {@code ' '}: the byte pair
     * {@code AC 20} read as two characters instead of one.
     *
     * <p>A raw {@code byte[]} has no coder field to read, so it is detected by {@code strBytes} answering the
     * argument ITSELF and treated as LATIN1 -- which is what a VM-side buffer is. Every caller here passes a
     * real String, so that arm is defence rather than a live path.
     *
     * <p>Little-endian because {@code StringUTF16.LO_BYTE_SHIFT} is seeded to 8 for AArch64, the same reason
     * {@link #internString} stores it that way; reading it the other way round swaps the bytes of every
     * character.
     */
    static int putGuestString(char[] out, int p, ObjectReference str)
    {
        if (str.isNull())
        {
            return putAscii(out, p, Magic.bytes("?"));
        }
        long arr = VM.strBytes(str);
        if (arr == 0L)
        {
            return putAscii(out, p, Magic.bytes("?"));
        }
        int nbytes = (int) Address.fromLong(arr).plus(16L).loadLong();
        int coder = ObjectReference.fromLong(arr).EQ(str) ? 0 : (int) str.toAddress().plus(24L).loadLong();
        if (coder == 0)
        {
            int i = 0;
            while (i < nbytes && p < out.length)
            {
                out[p] = (char) u1(arr + 24L + i);
                p += 1;
                i += 1;
            }
            return p;
        }
        int i = 0;
        while (i + 1 < nbytes && p < out.length)
        {
            out[p] = (char) (u1(arr + 24L + i) | (u1(arr + 24L + i + 1) << 8));   // LO first
            p += 1;
            i += 2;
        }
        return p;
    }

    /**
     * Append a plain ASCII {@code byte[]} to a BYTE buffer; returns the new position.
     *
     * <p>The twin of {@link #putAscii}, kept because {@link RuntimeEntrypoints#castFailureMessage} builds its message in a
     * {@code byte[]} and renders CLASS NAMES rather than user data. Widening that path too would be scope
     * with no measured defect behind it -- {@code guestStringUtf8}, which it shares the shape of, has the
     * same latent gap and the same reason to leave it alone.
     */
    static int putUtf8Bytes(byte[] out, int p, byte[] b)
    {
        int i = 0;
        while (i < b.length && p < out.length)
        {
            out[p] = b[i];
            p += 1;
            i += 1;
        }
        return p;
    }

    /** Append a plain ASCII {@code byte[]} to the record buffer, widened one character per byte. */
    static int putAscii(char[] out, int p, byte[] b)
    {
        int i = 0;
        while (i < b.length && p < out.length)
        {
            out[p] = (char) (b[i] & 0xFF);
            p += 1;
            i += 1;
        }
        return p;
    }


    /**
     * Wrap the first {@code n} UTF-16 code units of {@code src} as a guest {@code java/lang/String},
     * LATIN1 when every one of them fits a byte and UTF16 otherwise.
     *
     * <p>This is {@link #internString}'s epilogue applied to a buffer instead of to a Utf8 source, and it is
     * what {@link #guestString} cannot do: that one hardcodes {@code coder = 0}, so anything built through it
     * is LATIN1 whatever it holds. Choosing the narrower coder when it fits is the same choice stock makes,
     * and it is why an all-ASCII record renders byte-for-byte as it did before.
     *
     * <p>UTF16 is stored LO byte first because {@code StringUTF16.LO_BYTE_SHIFT} is seeded to 8 for AArch64.
     */
    static long guestStringChars(char[] src, int n)
    {
        int maxCh = 0;
        int i = 0;
        while (i < n)
        {
            if (src[i] > maxCh)
            {
                maxCh = src[i];
            }
            i += 1;
        }
        int coder = maxCh < 256 ? 0 : 1;
        ObjectReference arr = Heap.allocArray(coder == 0 ? n : n * 2, 1).toObjectReference();
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);   // type it as [B so `checkcast [B` resolves
        }
        i = 0;
        while (i < n)
        {
            if (coder == 0)
            {
                arr.toAddress().plus(24L).plus(i).store((byte) src[i]);
            }
            else
            {
                arr.toAddress().plus(24L).plus(i << 1).store((byte) (src[i] & 0xFF));
                arr.toAddress().plus(24L).plus(i << 1).plus(1).store((byte) ((src[i] >> 8) & 0xFF));
            }
            i += 1;
        }
        ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
        obj.toAddress().plus(0L).store(stringTib());
        obj.toAddress().plus(16L).store(arr);
        obj.toAddress().plus(24L).store((long) coder);
        return obj.toLong();
    }


    static long helperBuf(Address cls, byte[] name, byte[] desc)
    {
        DataReference n = utf8Blob(name);
        DataReference d = utf8Blob(desc);
        long buf = DynamicLinker.bufBySigU(cls, n.toAddress(), d.toAddress());
        return buf != 0L ? buf : LazyCompiler.compileSigOnDemand(cls, n.toAddress(), d.toAddress());
    }


    /** Compiled buffer for flattened slot {@code s}: inherited (pre-resolved) or this class's own. */
    static long slotBuf(int s)
    {
        if (VtableLayout.gvTab[s].implBuf != 0L)
        {
            return VtableLayout.gvTab[s].implBuf;                         // inherited from a superclass
        }
        if (VtableLayout.gvTab[s].implCode == 0L)
        {
            // No Code attribute: a NATIVE instance method. Its slot would stay 0 and a call through it would
            // hit the null-vtable guard (an AIOOBE with no hint of what was missing) -- which is what
            // `new Attributes$Name(...)` -> `String.intern()` did. Provided natives already have VM helpers;
            // link one here so a VIRTUAL call reaches it, as invokestatic/invokespecial already do.
            return nativeBufAt(gView.base.toAddress(), gView.thisNameOff, VtableLayout.gvTab[s].base.toAddress(), VtableLayout.gvTab[s].name);
        }
        return bufOf(VtableLayout.gvTab[s].implCode);                     // this class's own method
    }

    /**
     * A deferral stub for a vtable slot whose method RTA pruned. {@link LazyCompiler#emitDeferredStub} cannot serve here:
     * it works off the per-method compile arrays ({@code mCode}/{@code mLen}/...), which only exist for
     * methods that were pulled into the batch. Everything needed is in the vtable entry instead — the Code
     * attribute address, and {@code maxLocals}/{@code codeLen} read back from the two header fields that
     * precede the bytecode ({@code {u2 maxStack}{u2 maxLocals}{u4 codeLen}}). Non-static by construction: a
     * vtable slot only ever holds an instance method. 0 if the lazy table is full, leaving the old behaviour.
     */
    static long mintPrunedStub(int s)
    {
        long code = VtableLayout.gvTab[s].implCode;
        if (code == 0L)
        {
            // slotBuf answers 0 from two paths, and neither has bytecode to defer to. The common one is an
            // ABSTRACT method the class declares itself -- `AbstractCollection.iterator`, `AbstractMap
            // .entrySet`, `AbstractList.size` all reach here on a real boot. The other is a NATIVE instance
            // method whose VM helper is missing (nativeBufAt found nothing). Without the guard the two Code
            // header reads below happen at addresses -4 and -6.
            return 0L;
        }
        // The Code attribute's header must lie INSIDE this class's blob, or those reads walk off it. findCode
        // returns the bytecode start, so maxLocals sits at code-6 and codeLen at code-4; both must be within
        // [blob, blob+len). The read is raw -- there is no bounds check under it -- so it is checked here.
        int pd = findPdByName(gView.base.toAddress(), gView.thisNameOff);
        if (pd < 0 || code - 6L < gView.base.toLong() || code >= gView.base.plus(pdLen[pd]).toLong())
        {
            return 0L;
        }
        LazyCompiler.lazyEnsureTables();
        if (LazyCompiler.lzN >= LazyCompiler.MAXLAZY)
        {
            return 0L;
        }
        if (LazyCompiler.lazyTrampAddr == 0L)
        {
            LazyCompiler.buildLazyTramp();
        }
        int idx = LazyCompiler.lzN;
        LazyCompiler.lzTab[idx] = new LazyMethod();
        LazyCompiler.lzTab[idx].blob = gView.base;
        LazyCompiler.lzTab[idx].len = pdLen[pd];
        LazyCompiler.lzTab[idx].reg = ClassRegistry.classRegByName(gView.thisNameOff);
        LazyCompiler.lzTab[idx].nameOff = 0;
        LazyCompiler.lzTab[idx].descOff = VtableLayout.gvTab[s].desc;
        LazyCompiler.lzTab[idx].slot = DataReference.zero();             // shared stub buffer: no single slot to patch
        LazyCompiler.lzTab[idx].code = code;
        if (LazyCompiler.lzN >= LazyCompiler.lzNHigh)
        {
            LazyCompiler.lzNHigh = LazyCompiler.lzN + 1;                      // high-water across reclaims
        }
        LazyCompiler.lzTab[idx].codeLen = ClassBytes.codeLength(code);
        LazyCompiler.lzTab[idx].isStatic = 0;
        LazyCompiler.lzTab[idx].isSync = VtableLayout.gvTab[s].isSync;                // else this body compiles with NO monitor
        LazyCompiler.lzTab[idx].cache = CodeAddress.zero();
        LazyCompiler.lzN += 1;
        Address buf = Heap.allocCode(32);
        LazyCompiler.noteStub(buf, idx);
        Heap.pinCodeAt(buf);
        LazyCompiler.lzTab[idx].stubLit = CodeAddress.fromAddress(LazyCompiler.writeLazyStub(buf, idx));
        VtableLayout.gvTab[s].implBuf = buf.toLong();                // a class implementing N interfaces that each declare
        return buf.toLong();                            //   this method would otherwise mint N identical stubs
    }


    /**
     * Resolve Methodref {@code idx} to its method in THIS class, or null (another class, or no body). The result
     * carries the ACC_SYNCHRONIZED bit scanCallees needs; it replaced the {@code gCalleeSync} static (P2-5).
     */
    private static FoundMethod calleeOf(int idx)
    {
        if (!utf8Eq(refClassNameOff(idx), gView.thisNameOff))
        {
            return null;                                // not this class (Object.<init>, JDK, ...)
        }
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        Address p = gView.methodsStart;
        int mcount = u2(p);
        p = p.plus(2);
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p.plus(6));                      // access, name, descriptor, attrs
            if (utf8Eq(gView.cp[u2(p.plus(2))], nameOff) && utf8Eq(gView.cp[u2(p.plus(4))], descOff))
            {
                long c = findCode(gView.base.toAddress(), p.plus(8), attrs);
                if (c != 0L)
                {
                    return new FoundMethod(c, descOff, u2(p));
                }
            }
            p = skipAttributes(p.plus(8), attrs);
            m += 1;
        }
        return null;
    }

    /** Name Utf8 offset of Methodref {@code idx}. */
    static int mrefNameOff(int idx)
    {
        if (PARSE_CTX_WATCH)
        {
            checkParseCtxOwned(2);
        }
        return ClassReader.refNameOff(gView.bytes, gView.cp, idx);
    }

    /** Class-name Utf8 offset of a {@code *ref} constant (Fieldref/Methodref layout). */
    static int refClassNameOff(int idx)
    {
        if (PARSE_CTX_WATCH)
        {
            checkParseCtxOwned(3);
        }
        return ClassReader.refClassNameOff(gView.bytes, gView.cp, idx);
    }

    /** Descriptor Utf8 offset of Methodref {@code idx}. */
    static int mrefDescOff(int idx)
    {
        if (PARSE_CTX_WATCH)
        {
            checkParseCtxOwned(4);
        }
        return ClassReader.refDescOff(gView.bytes, gView.cp, idx);
    }


    /** True if the Utf8 at {@code off} in the current class equals the literal {@code want} (raw bytes). */
    static boolean utf8IsStr(int off, byte[] want)
    {
        return Utf8.utf8IsAtBase(gView.base.toAddress(), off, want);
    }


    /** Loaded-class-registry index of {@code java/lang/String}, or -1 if it isn't loaded. */
    private static int stringClassIndex()
    {
        return ClassRegistry.classIndexByName(Magic.bytes("java/lang/String"));
    }


    /** {@code obj}'s Type, or 0 for a RAW array, whose TIB slot holds a small tag rather than a Type pointer. */
    static Address typeOfObject(ObjectReference obj)
    {
        long tib = obj.toAddress().loadLong();
        return tib > ObjectModel.MAX_RAW_ARRAY_TIB ? Address.fromLong(tib).loadAddress() : Address.zero();
    }


    /** {@code type}'s name as {@code Class.getName()} spells it, as raw ASCII; empty if it has none (a lambda). */
    static byte[] typeNameBytes(Address type)
    {
        int len = type.isZero() ? 0 : classNameLen(type);
        byte[] out = new byte[len < 0 ? 0 : len];
        if (out.length > 0)
        {
            writeClassName(type, Magic.addrOf(out) + (long) ObjectModel.ARRAY_BASE_OFFSET, 0);
        }
        return out;
    }


    // ----- multianewarray (JVMS 6.5) ---------------------------------------


    /**
     * Append the name of {@code type}, or a description when it has none.
     *
     * <p>AN UNREGISTERED TYPE IS NOT A REASON TO GO SILENT -- it is the most interesting answer there is. A
     * synthesised lambda or annotation object has a Type and an itable but NO class-registry entry (it has no
     * binary name, mirroring a hidden class), so a message that bailed out on one would omit itself exactly
     * when the cast failed for the most surprising reason.
     */
    static int putTypeName(byte[] out, int p, Address type, long tib)
    {
        if (type.toLong() <= 0x1000L)
        {
            // No Type node. Say what was actually READ rather than guessing which kind of object it is --
            // inferring from an absence is what produced two wrong readings of this very failure.
            p = putUtf8Bytes(out, p, Magic.bytes("<no Type; tib="));
            p = RuntimeEntrypoints.putHex(out, p, tib);
            return putUtf8Bytes(out, p, Magic.bytes(">"));
        }
        // AN ARRAY TYPE IS NOT SYNTHESISED, AND SAYING SO WAS COSTING A DIAGNOSIS. Array Types carry no
        // class-registry entry and no itable directory, so the synthesised arm below rendered EVERY array as
        // `<synthesised, implements nothing>` -- identical to a lambda whose interface failed to resolve.
        // That is two unrelated bugs sharing one string: a launcher failure at
        // `Arrays.stream(getParameters())` read as a lambda-interface problem when both sides were arrays.
        // `writeClassName` already renders arrays (nested and primitive elements included), so this reuses it
        // rather than adding a second walk over the same relation -- two walks disagreeing is a trap this
        // file has paid for more than once.
        if (ArrayTypes.isArrayType(type))
        {
            return writeClassName(type, Magic.addrOf(out) + 24L, p);   // elements at +24; descriptor form
        }
        int r = ClassRegistry.regOfType(type);
        if (r >= 0 && ClassRegistry.clTab != null && ClassRegistry.clTab[r] != null)
        {
            return putDotted(out, p, ClassRegistry.clTab[r].base.plus(ClassRegistry.clTab[r].nameOff));
        }
        // No binary name, so say what it SATISFIES instead: the itable directory lists the interfaces, and
        // for a lambda that is its functional interface -- which is what distinguishes "the factory object
        // came back unchanged" from "some other synthesised object was returned".
        //
        // A MISSING DIRECTORY AND AN EMPTY ONE ARE DIFFERENT FAULTS and must not share a word. dir == 0 means
        // the Type has no directory AT ALL (an interface Type is a chain dead end, so this is what a cast
        // TARGET looks like); a directory whose first entry is the 0 sentinel means the object IS a lambda
        // and `buildLambdaTib` could not resolve its functional interface. Reporting both as "nothing" is
        // what made the two indistinguishable in the log.
        p = putUtf8Bytes(out, p, Magic.bytes("<synthesised, implements"));
        Address dir = type.plus(16L).loadAddress();
        if (dir.isZero())
        {
            return putUtf8Bytes(out, p, Magic.bytes(" -- NO itable directory (not a lambda; an interface or bare Type)>"));
        }
        int n = 0;
        while (!dir.isZero() && n < 8)
        {
            Address ifType = dir.plus(n * 16L).loadAddress();
            if (ifType.isZero())
            {
                break;                                   // sentinel ends the directory
            }
            int ir = ClassRegistry.regOfType(ifType);
            p = putUtf8Bytes(out, p, Magic.bytes(" "));
            if (ir >= 0 && ClassRegistry.clTab != null && ClassRegistry.clTab[ir] != null)
            {
                p = putDotted(out, p, ClassRegistry.clTab[ir].base.plus(ClassRegistry.clTab[ir].nameOff));
            }
            else
            {
                p = putUtf8Bytes(out, p, Magic.bytes("?"));
            }
            n += 1;
        }
        if (n == 0)
        {
            // An EMPTY directory on a Type that has one: a lambda built with ifaceType 0. Say which, because
            // the fix is `LAMBDA IFACE UNRESOLVED`'s and nothing else's.
            p = putUtf8Bytes(out, p, Magic.bytes(" nothing (lambda: functional interface UNRESOLVED)"));
        }
        return putUtf8Bytes(out, p, Magic.bytes(">"));
    }


    /** Append an internal class name with '/' rendered as '.', the form a Java programmer reads. */
    static int putDotted(byte[] out, int p, Address utf8Addr)
    {
        int len = u2(utf8Addr);
        int i = 0;
        while (i < len && p < out.length)
        {
            int c = u1(utf8Addr.plus(2L + i));
            out[p] = (byte) (c == '/' ? '.' : c);
            p += 1;
            i += 1;
        }
        return p;
    }


    /** TIB of the loaded mini {@code java/lang/String} (for the concat's {@code VMConcat.scEnd}), or 0. */
    static DataReference stringTib()
    {
        int i = stringClassIndex();
        return i >= 0 ? ClassRegistry.clTab[i].tib : DataReference.zero();
    }

    /** Instance size (bytes) of the loaded mini {@code java/lang/String} (header + fields). */
    static int stringSize()
    {
        int i = stringClassIndex();
        return i >= 0 ? (16 + ClassRegistry.clTab[i].fieldCount * 8) : 24;
    }


    /** Emit one instruction word at word index {@code w}; returns the next index. */
    static int emitAt(Address thunk, int w, int word)
    {
        thunk.plus(w * 4L).store(word);
        return w + 1;
    }

    /** Load an absolute trampoline address into x16, the register both thunk tails branch through. */
    static int emitTrampAddr(Address thunk, int w, Address addr)
    {
        w = emitAt(thunk, w, A64Enc.movz(16, (int) (addr.toLong() & 0xFFFF), 0));
        w = emitAt(thunk, w, A64Enc.movk(16, (int) ((addr.toLong() >> 16) & 0xFFFF), 1));
        w = emitAt(thunk, w, A64Enc.movk(16, (int) ((addr.toLong() >> 32) & 0xFFFF), 2));
        return w;
    }


    /** Compare two Utf8 entries in the current class by length + bytes. */
    static boolean utf8Eq(int offA, int offB)
    {
        return utf8EqAt(gView.base.toAddress(), offA, gView.base.toAddress(), offB);
    }

    /** Compare a Utf8 entry in {@code baseA} against one in {@code baseB} (may be different classes). */
    static boolean utf8EqAt(Address baseA, int offA, Address baseB, int offB)
    {
        if (baseA.isZero() || baseB.isZero())                // a reclaimed registry entry: halt NAMED, not wild
        {
            badRead(baseA.isZero() ? 0L : 1L, Magic.readLR());
        }
        if (baseA.EQ(baseB) && offA == offB)
        {
            return true;
        }
        int la = u2(baseA.plus(offA));
        if (la != u2(baseB.plus(offB)))
        {
            return false;
        }
        int j = 0;
        while (j < la)
        {
            if (u1(baseA.plus(offA + 2 + j)) != u1(baseB.plus(offB + 2 + j)))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }


    /** Class-registry index of {@code java/lang/Object}, or -1 if it isn't loaded yet. */
    static int objectClassIndex()
    {
        int i = 0;
        while (i < ClassRegistry.clCount)
        {
            syObjSteps += 1;
            if (Utf8.utf8IsAtBase(ClassRegistry.clTab[i].base.toAddress(), ClassRegistry.clTab[i].nameOff, Magic.bytes("java/lang/Object")))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }


    /**
     * The Type of {@code java/lang/String}, or 0 if it is not registered. Read ONCE per batch into
     * {@link VM#stringTypeCache} rather than per ask: the caller is a concat argument, and a
     * {@code classIndexByName} scan there is a linear walk of a table that grows all boot.
     *
     * <p>An EXACT compare against this is complete because {@code String} is final, and unambiguous because
     * the two worlds share one Type -- the loader ADOPTS the writer's node, so a writer-baked String object
     * and a guest-built one carry the same address.
     */
    static DataReference stringTypeAddr()
    {
        int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/String"));
        return i < 0 ? DataReference.zero() : ClassRegistry.clTab[i].type;
    }


    /** {@code ldc} of a CONSTANT_Class (a class literal {@code X.class}) at cp {@code classCp} -> its Class mirror. */
    /**
     * The {@code Class} object of the class CURRENTLY BEING COMPILED -- a STATIC synchronized method's
     * implicit monitor (JVMS 2.11.10). 0 when there is no Type to mirror, which is what lets the compiler
     * decline to emit an acquire it could never pair with a release.
     *
     * <p>No constant-pool index is needed and none is wanted: {@code gBuild.type} IS this class's Type node, built
     * by {@code buildTib} in phase A and restored by {@code restoreCtxForCompile} for every lazy compile --
     * which is exactly the self-reference case {@code typeOfClass} already special-cases, because the class
     * is not in the registry until after its own compile. Going through the constant pool would ask the
     * registry a question it cannot answer yet.
     *
     * <p>{@code classMirror} caches per Type, so this is the SAME object {@code Foo.class} yields.
     */


    /** The Type of {@code java/lang/Throwable}, or 0 if it is not loaded. Cached: this is asked on a fault path. */
    static DataReference throwableTypeAddr()
    {
        if (thrTypeCache.isZero())
        {
            int i = ClassRegistry.classIndexByName(Magic.bytes("java/lang/Throwable"));
            thrTypeCache = i < 0 ? DataReference.zero() : ClassRegistry.clTab[i].type;
        }
        return thrTypeCache;
    }

    private static DataReference thrTypeCache;


    /** {@link #ctorFrameKind}: a frame stock keeps in a construction-time trace. */
    static final int FRAME_KEEP = 0;
    /** {@link #ctorFrameKind}: a {@code fillInStackTrace} frame of a class the exception is an instance of. */
    static final int FRAME_FILL = 1;
    /** {@link #ctorFrameKind}: an {@code <init>} frame of a class the exception is an instance of. */
    static final int FRAME_INIT = 2;

    /**
     * Which frames a trace taken at CONSTRUCTION leaves out, for the frame at {@code pc} and exception
     * {@code exc}. HotSpot's {@code java_lang_Throwable::fill_in_stack_trace} skips the {@code fillInStackTrace}
     * frames at the top, then every {@code <init>} frame whose holder {@code exc} is an instance of -- so
     * {@code new X()} reports the line of the {@code new}, and a constructor that builds a DIFFERENT exception
     * keeps its own frame. The method is named from the JIT registry or, for baked code (stock exception
     * constructors live in the image), from the image symbol table; a pc found in neither is kept.
     */
    static int ctorFrameKind(Address pc, ObjectReference exc)
    {
        long blk = Heap.codeBlockStartAt(pc).toLong();
        if (blk != 0L)
        {
            int i = 0;
            while (i < MethodRegistry.rgCount)
            {
                if (MethodRegistry.rgTab[i].buf.toLong() == blk)
                {
                    int kind = ctorNameKind(MethodRegistry.rgTab[i].base.plus(MethodRegistry.rgTab[i].nameOff + 2L), u2(MethodRegistry.rgTab[i].base.plus(MethodRegistry.rgTab[i].nameOff)));
                    if (kind == FRAME_KEEP)
                    {
                        return FRAME_KEEP;
                    }
                    Address type = (typeByName(MethodRegistry.rgTab[i].base.plus(MethodRegistry.rgTab[i].classOff + 2L), u2(MethodRegistry.rgTab[i].base.plus(MethodRegistry.rgTab[i].classOff)))).toAddress();
                    return !type.isZero() && VM.instanceOf(exc, type) != 0 ? kind : FRAME_KEEP;
                }
                i += 1;
            }
            return FRAME_KEEP;
        }
        long tab = VM.imageSymTable;
        long k = 0;
        while (k < VM.imageSymCount)
        {
            Address e = Address.fromLong(tab + k * 40L);
            if (pc.toLong() >= e.loadLong() && pc.toLong() < e.plus(8L).loadLong())
            {
                Address sym = e.plus(16L).loadAddress();        // "owner/Class.method", length-prefixed
                int symLen = u2(sym);
                int dot = symLen - 1;
                while (dot > 0 && u1(sym.plus(2L + dot)) != 0x2E)
                {
                    dot -= 1;
                }
                if (dot <= 0)
                {
                    return FRAME_KEEP;
                }
                int kind = ctorNameKind(sym.plus(2L + dot + 1L), symLen - dot - 1);
                if (kind == FRAME_KEEP)
                {
                    return FRAME_KEEP;
                }
                DataReference type = typeByName(sym.plus(2L), dot);
                return !type.isZero() && VM.instanceOf(exc, type.toAddress()) != 0 ? kind : FRAME_KEEP;
            }
            k += 1;
        }
        return FRAME_KEEP;
    }

    /** {@link #FRAME_FILL} for the name {@code fillInStackTrace}, {@link #FRAME_INIT} for {@code <init>}, else keep. */
    private static int ctorNameKind(Address p, int len)
    {
        if (Utf8.bytesAt(p, len, Magic.bytes("<init>")))
        {
            return FRAME_INIT;
        }
        if (Utf8.bytesAt(p, len, Magic.bytes("fillInStackTrace")))
        {
            return FRAME_FILL;
        }
        return FRAME_KEEP;
    }


    /** The Type of the registered class whose internal name is the {@code len} bytes at {@code p}, or 0. */
    private static DataReference typeByName(Address p, int len)
    {
        int ci = 0;
        while (ci < ClassRegistry.clCount)
        {
            RVMClass c = ClassRegistry.clTab[ci];
            if (c != null && u2(c.base.plus(c.nameOff)) == len && Utf8.sameBytesAt(c.base.plus(c.nameOff + 2L), p, len))
            {
                return c.type;
            }
            ci += 1;
        }
        return DataReference.zero();
    }


    /**
     * Watch the RECEIVER of one method name's dispatch sites.
     *
     * <p>Set {@code RECV_WATCH_ON} and point {@code RECV_WATCH} at a method name to have every dispatch on
     * that name describe the object it is about to dispatch ON. The open layout-sensitivity defect needs
     * exactly this and nothing else answers it: the constructor is called, the field offset is right and
     * consistent, and the value still reads null -- so the remaining question is whether the object
     * {@code iterator()} runs against is the one that was constructed, and that is a question about
     * IDENTITY, which no value watch can answer.
     */
    private static final byte[] RECV_WATCH = Magic.bytes("getName");
    private static final boolean RECV_WATCH_ON = false;

    static boolean isWatchedRecv(int idx)
    {
        if (!RECV_WATCH_ON)
        {
            return false;
        }
        int n = mrefNameOff(idx);
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), n, RECV_WATCH))
        {
            return false;
        }
        // NARROWED TO THE CALLER, not just the callee: `iterator` is dispatched all over the suite, and an
        // unfiltered watch floods the UART and starves the run it is meant to diagnose -- already paid for
        // once by the COMPILE_WATCH arc. The failing dispatch is Map.forEach's, so that is the only one armed.
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff,
                Magic.bytes("org/junit/platform/engine/discovery/ClassSelector")))
        {
            return false;
        }
        reportArmed(n, idx);
        return true;
    }

    /**
     * Print what a receiver IS: its TIB, its Type, its class NAME, its size, and its first field slots.
     *
     * <p>The name is the part a raw address cannot give and is why this lives in the loader -- it comes from
     * the class registry. A receiver whose Type is not registered says so rather than printing nothing,
     * because "unregistered" and "I could not look it up" are different answers and this project has been
     * misled by conflating them before.
     */
    static void describeRecv(ObjectReference ref, long site)
    {
        Uart.write(Magic.bytes("  RECVWATCH site="));
        VM.printDec((int) site);
        Uart.write(Magic.bytes(" recv="));
        VM.printHex(ref.toLong());
        if (ref.toLong() < Heap.BASE || ref.toLong() >= Heap.managedTop().toLong() || (ref.toLong() & 7L) != 0L)
        {
            Uart.write(Magic.bytes(" -- NOT a managed heap reference\n"));
            return;
        }
        DataReference tib = ref.toAddress().loadDataReference();
        Uart.write(Magic.bytes(" tib="));
        VM.printHex(tib.toAddress());
        if (tib.isZero())
        {
            Uart.write(Magic.bytes(" -- NULL TIB (object never got one)\n"));
            return;
        }
        DataReference type = tib.loadDataReference();
        Uart.write(Magic.bytes(" type="));
        VM.printHex(type.toAddress());
        Uart.write(Magic.bytes(" class="));
        int reg = ClassRegistry.classRegByType(type.toAddress());
        if (reg >= 0)
        {
            printNameAt(ClassRegistry.clTab[reg].base.toAddress(), ClassRegistry.clTab[reg].nameOff);
        }
        else
        {
            Uart.write(Magic.bytes("<unregistered Type>"));
        }
        Uart.write(Magic.bytes("\n    status="));
        VM.printHex(ref.toAddress().plus(8L).loadLong());
        Uart.write(Magic.bytes(" slot0(+16)="));
        VM.printHex(ref.toAddress().plus(16L).loadLong());
        Uart.write(Magic.bytes(" slot1(+24)="));
        VM.printHex(ref.toAddress().plus(24L).loadLong());
        Uart.putc((byte) 0x0A);
    }

    /**
     * Watch how ONE class's method REFERENCES resolve: which tier answered, and to what.
     *
     * <p>Unlike every other watch here this one EMITS NOTHING -- it prints from inside the resolver, so it
     * cannot perturb codegen. That matters: the receiver watch had to be validated against a passing image
     * precisely because it does emit, and a watch that changes the code it measures is worse than none.
     *
     * <p>The open layout-sensitivity defect has been narrowed to "the constructor runs and its putfield does
     * not take effect on this object": `<init>` is emitted as a real call, the field offset is +24 on both
     * the store and the load, and the receiver is a genuine LinkedEntrySet whose this$0 is 0. What is left
     * is WHICH BODY the `<init>` reference resolved to -- and a constructor that resolved to some other
     * class's body, or to a cell that was never filled, stores nothing and says nothing.
     */
    private static final byte[] BUF_WATCH_CLASS = Magic.bytes("java/util/LinkedHashMap$LinkedEntrySet");
    private static final boolean BUF_WATCH_ON = false;

    /** Tier that answered a {@link DynamicLinker#globalBufByRef}: 1 class-qualified, 2 super-chain, 3 `<init>` cell
     *  (constructors short-circuit here, never walking a chain), 4 final cell tier. A 0 buffer is the
     *  interesting answer and is labelled, because "resolved to nothing" and "resolved to something wrong"
     *  are different bugs and an address alone cannot tell them apart. */
    static void bufResolveLog(Address refBase, int classOff, int nameOff, int descOff, long buf, int tier)
    {
        if (!BUF_WATCH_ON || !Utf8.utf8IsAtBase(refBase, classOff, BUF_WATCH_CLASS))
        {
            return;
        }
        Uart.write(Magic.bytes("  BUFWATCH "));
        printNameAt(refBase, classOff);
        Uart.putc(0x2E);
        printNameAt(refBase, nameOff);
        Uart.putc(0x20);
        printNameAt(refBase, descOff);
        Uart.write(Magic.bytes(" -> buf="));
        VM.printHex(buf);
        Uart.write(Magic.bytes(" tier"));
        VM.printDec(tier);
        if (buf == 0L)
        {
            Uart.write(Magic.bytes("  -- RESOLVED TO NOTHING"));
        }
        Uart.putc(0x0A);
    }

    /** Late (link-stub) resolution of a watched class's method: which tier answered, and with what. The
     *  patch-time twin is {@link #bufResolveLog}; a site that resolves to nothing at PATCH time takes a link
     *  stub and is resolved again HERE, on first call, so a silent site has to be explained at both ends.
     *  Tier 1 bufBySigU (registered buffer / static cell / vtable slot), 2 provided native, 3
     *  compileSigOnDemand; 0 marks an EARLY return, where lnkFailWhy says which guard refused. */
    static void linkResolveLog(Address clsU, Address nameU, Address descU, long buf, int tier)
    {
        if (!BUF_WATCH_ON || !Utf8.utf8IsAtBase(clsU, 0, BUF_WATCH_CLASS))
        {
            return;
        }
        Uart.write(Magic.bytes("  LINKWATCH "));
        printNameAt(clsU, 0);
        Uart.putc(0x2E);
        printNameAt(nameU, 0);
        Uart.putc(0x20);
        printNameAt(descU, 0);
        Uart.write(Magic.bytes(" -> buf="));
        VM.printHex(buf);
        Uart.write(Magic.bytes(" tier"));
        VM.printDec(tier);
        Uart.write(Magic.bytes(" why"));
        VM.printDec(DynamicLinker.lnkFailWhy);
        Uart.putc(0x0A);
    }

    /** Watch ONE method name's call sites: the compiler emits a WATCH_RET print of whatever each returns. */
    private static final boolean CALL_WATCH_ON = false;

    /** Watch STORES to one field name: the compiler prints each value as it is written. */
    private static final boolean FIELD_STORE_WATCH = false;

    /**
     * Watch the LAST ARGUMENT passed to calls of one or two method names.
     *
     * <p>A store watch can only see a value that is STORED, and picocli guards every {@code factory} field
     * with {@code Assert.notNull} on the line above the {@code putfield} -- so on the failing run the store
     * is never reached and the watch cannot fire. Measured from the jar's bytecode, not inferred. Watching
     * the ARGUMENT instead sees the value at each hand-off, which is what names the frame that loses it.
     */
    private static final boolean ARG_WATCH_ON = false;

    /**
     * Say, at COMPILE time, which class got a watch on which callee.
     *
     * <p>Without it a runtime watch line is an unlabelled number and the reader has to INFER which site
     * emitted it -- and an absent line cannot be told apart from a site that was never compiled, never
     * armed, or simply never executed. Those are three different bugs. This is the same discriminator the
     * field watch needed and never got, which is how "the watch does not fire" stayed open.
     *
     * <p>Two lines per site is expected and correct: the compiler visits every call twice, once to size the
     * method and once to emit it.
     */
    /**
     * A method whose body reaches a local slot its {@code max_locals} does not cover.
     *
     * <p>UNGATED, because this is silent caller corruption rather than a degraded answer: the prologue saves
     * exactly {@code min(max_locals, LOC_MAX)} of x19..x28, so a body writing past that overwrites a
     * CALLER'S local and the damage surfaces arbitrarily far away -- a reference that was non-null two
     * bytecodes ago reading as null, with no fault and no trace.
     */
    static void reportLocalsUndersized(int needed, int declared)
    {
        Uart.write(Magic.bytes("  LOCALS UNDERSIZED: "));
        // CAPPED, and this is the site where it matters most: THIS report is what the open SMP-contention
        // bug prints, and it prints it with a CLOBBERED gView.base. The recorded symptom is exactly
        // `LOCALS UNDERSIZED: java/lang/Object uses slot 18 but max_locals=3` with RAW CONSTANT-POOL BYTES
        // rendered as a class name on the lines around it -- a report walking a blob at a wrong offset.
        // printNameAt trusts the u2 length it lands on, so that is unbounded output over the UART on the one
        // line a reader most needs. printUtf8Capped answers <bad utf8 ptr>, which is both short and is itself
        // the evidence that the context was clobbered rather than merely wrong.
        printUtf8Capped(gView != null ? gView.base.plus(gView.thisNameOff) : Address.zero());
        Uart.write(Magic.bytes(" uses slot "));
        VM.printDec(needed - 1);
        Uart.write(Magic.bytes(" but max_locals="));
        VM.printDec(declared);
        Uart.write(Magic.bytes(" -- the prologue saves too few callee-saved registers, so THE CALLER'S\n"));
        Uart.write(Magic.bytes("  LOCALS ARE CORRUPTED from this call onward.\n"));
    }

    private static void reportArmed(int nameOff, int site)
    {
        if (gView == null || gView.thisNameOff == 0)
        {
            return;                                     // no context to name it with; say nothing rather than guess
        }
        Uart.write(Magic.bytes("  ARMED site="));
        VM.printDec(site);
        Uart.putc(0x20);
        printNameAt(gView.base.toAddress(), gView.thisNameOff);
        Uart.putc(0x2E);
        printNameAt(gView.base.toAddress(), nameOff);
        Uart.putc(0x0A);
    }

    /**
     * Trace local slot 2 of one class, after every call it makes.
     *
     * <p>Two readings 24 bytecodes apart proved the picocli factory is alive at bytecode 54 and null at 77;
     * they cannot say WHICH of the intervening calls lost it. This prints the local after each, so one boot
     * bisects the method instead of one boot per candidate.
     */
    private static final boolean LOCAL2_WATCH = false;

    /**
     * The x21 callee-saved discipline check.
     *
     * <p>MEASURED: picocli's factory (local slot 2 of {@code CommandLine.<init>}, i.e. x21) is intact after
     * five calls and gone after {@code new CommandLine$Interpreter(this)}. Every frame from there down --
     * {@code Interpreter.<init>}, {@code registerBuiltInConverters}, {@code excl} -- is {@code locals=2} and
     * so does not OWN x21; they merely pass it through. Printing x21 deeper cannot find the violator because
     * most frames down there legitimately use it for their own slot 2. Asking every non-owner "did you leave
     * it as you found it?" can, and the INNERMOST reporter is adjacent to the culprit.
     */
    /** Dump every frame the unwinder pops: pc, image-or-JIT, frameSize, regLocals, and the words at
     *  [sp+0]/[sp+8]/[sp+16] -- i.e. what an image frame's save area ACTUALLY holds. */
    static final boolean UNWIND_DUMP = false;

    private static final boolean X21_CHECK = false;

    static boolean checkX21()
    {
        return X21_CHECK;
    }

    /** Name the class each instrumented method belongs to, so a runtime id maps to something. */
    static void reportX21Site(int id)
    {
        if (gView == null || gView.thisNameOff == 0)
        {
            return;
        }
        Uart.write(Magic.bytes("  X21CHECK id="));
        VM.printDec(id);
        Uart.putc(0x20);
        printNameAt(gView.base.toAddress(), gView.thisNameOff);
        Uart.putc(0x0A);
    }

    static boolean watchLocal2()
    {
        if (!LOCAL2_WATCH || gView == null || gView.thisNameOff == 0)
        {
            return false;
        }
        // The constructor that loses the value, and the callee the bisect named. Two exact names rather than
        // a prefix: every nested picocli class would bury the reading in noise.
        return Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff,
                        Magic.bytes("org/junit/platform/console/shadow/picocli/CommandLine"))
                || Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff,
                        Magic.bytes("org/junit/platform/console/shadow/picocli/CommandLine$Interpreter"));
    }

    static boolean isWatchedCallArgs(int idx)
    {
        if (!ARG_WATCH_ON)
        {
            return false;
        }
        int n = mrefNameOff(idx);
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("forAnnotatedObject")))
        {
            return false;
        }
        reportArmed(n, idx);
        return true;
    }

    static boolean isWatchedField(int idx)
    {
        // CLASS-QUALIFIED, and that is not tidiness: the watch prints the cp INDEX, not the owner, so a
        // field of the same name in another class reads as the same site. `className` alone matched
        // ClassSelector, ClassSource AND ObjectStreamClass$ExceptionInfo, and cp index 7 is not unique
        // across them -- an ambiguous line is an instrument that invites the wrong conclusion.
        return FIELD_STORE_WATCH
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefNameOff(idx), Magic.bytes("className"))
                && Utf8.utf8IsAtBase(gView.base.toAddress(), refClassNameOff(idx),
                        Magic.bytes("org/junit/platform/engine/discovery/ClassSelector"));
    }

    static boolean isWatchedCall(int idx)
    {
        if (!CALL_WATCH_ON)
        {
            return false;
        }
        int n = mrefNameOff(idx);
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("getName")))
        {
            return false;
        }
        // Only the ONE call site under investigation, or every `getName` in a 2371-class closure prints and
        // the UART traffic starves the run it exists to diagnose -- a cost this file records paying once.
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), gView.thisNameOff,
                Magic.bytes("org/junit/platform/engine/discovery/ClassSelector")))
        {
            return false;
        }
        reportArmed(n, idx);
        return true;
    }

    /** True if the *ref at {@code idx} is {@code jdk/internal/reflect/Reflection.getCallerClass()} (intrinsified). */
    static boolean isGetCallerClass(int idx)
    {
        return Utf8.utf8IsAtBase(gView.base.toAddress(), mrefNameOff(idx), Magic.bytes("getCallerClass"))
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefDescOff(idx), Magic.bytes("()Ljava/lang/Class;"))
                && Utf8.utf8IsAtBase(gView.base.toAddress(), refClassNameOff(idx), Magic.bytes("jdk/internal/reflect/Reflection"));
    }

    /** True if the *ref at {@code idx} is a {@code getClass()Ljava/lang/Class;} call (intrinsified to a helper). */
    static boolean isGetClass(int idx)
    {
        return Utf8.isName(gView.base.toAddress(), mrefNameOff(idx), 0x676574436C617373L, 8)   // "getClass"
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefDescOff(idx), Magic.bytes("()Ljava/lang/Class;"));
    }

    /**
     * True if the *ref at {@code idx} names one of {@code java/lang/Object}'s PUBLIC methods, whatever its
     * owner -- the set interface method resolution has to consider (JVMS 5.4.3.4).
     *
     * <p>Owner-agnostic on purpose, and unambiguous: {@code getClass}/{@code wait}/{@code notify}/
     * {@code notifyAll} are final in Object, and an interface may not declare a default method
     * override-equivalent to {@code hashCode}/{@code equals}/{@code toString} (JLS 9.4.1.2). So a *ref with
     * one of these name+descriptor pairs always means Object's method, and routing it to the virtual path
     * dispatches to the receiver's override exactly as an {@code invokevirtual} would.
     */
    static boolean isObjectPublicMethod(int idx)
    {
        if (isGetClass(idx) || monitorOp(idx) != 0)
        {
            return true;
        }
        int n = mrefNameOff(idx);
        int d = mrefDescOff(idx);
        return (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("hashCode"))
                        && Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("()I")))
                || (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("toString"))
                        && Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("()Ljava/lang/String;")))
                || (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("equals"))
                        && Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("(Ljava/lang/Object;)Z")));
    }

    /** {@code invokevirtual "[T".clone()} — a virtual call on an ARRAY receiver (owner Utf8 starts '[').
     *  Array TIBs carry no vtable, so this is intrinsified to {@code VM.arrayClone} instead of dispatching. */
    static boolean isArrayClone(int idx)
    {
        Address p = gView.base.plus(ClassReader.refClassNameOff(gView.bytes, gView.cp, idx));
        return u2(p) >= 1 && u1(p.plus(2)) == 0x5B                            // owner "[..." (an array class)
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefNameOff(idx), Magic.bytes("clone"));
    }

    /** {@code Class.desiredAssertionStatus()Z} -- intrinsified to {@code false} (assertions are off on metal), so
     *  a stock {@code <clinit>}'s `$assertionsDisabled` idiom needs neither the mirror's vtable nor the method. */
    static boolean isDesiredAssertionStatus(int idx)
    {
        return Utf8.utf8IsAtBase(gView.base.toAddress(), mrefNameOff(idx), Magic.bytes("desiredAssertionStatus"))
                && Utf8.utf8IsAtBase(gView.base.toAddress(), mrefDescOff(idx), Magic.bytes("()Z"));
    }

    /**
     * Object-monitor op for an {@code invokevirtual} on {@code java/lang/Object}: 0 none, 1 {@code wait()V},
     * 2 {@code wait(J)V}, 3 {@code notify()V}, 4 {@code notifyAll()V}. Recognised by name+descriptor so the
     * compiler can lower it DIRECTLY to a VM helper (like getClass) instead of through the vtable -- wait/notify
     * are final and the mini Object's bodies are never compiled, so a vtable dispatch would hit a no-op slot.
     */
    static int monitorOp(int idx)
    {
        // The OWNER is deliberately not checked. `wait`/`notify`/`notifyAll` are FINAL in Object, so no class
        // or interface can declare a method override-equivalent to them (JLS 8.4.3.3 / 9.4) -- the name and
        // descriptor alone are unambiguous. Matching on the owner missed the shape JVMS 5.4.3.4 allows:
        // `interfaceTyped.wait()` is an invokeinterface whose owner is the INTERFACE, and Object's monitor
        // methods have no itable slot AND no usable vtable slot (their bodies are bake stubs), so it would
        // dispatch into whatever the directory's slot happened to hold.
        int n = mrefNameOff(idx);
        int d = mrefDescOff(idx);
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("wait")))
        {
            if (Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("()V")))  { return 1; }
            if (Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("(J)V"))) { return 2; }
            return 0;                                        // wait(JI)V etc.: unused, fall back to vtable
        }
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("notify")) && Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("()V")))
        {
            return 3;
        }
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("notifyAll")) && Utf8.utf8IsAtBase(gView.base.toAddress(), d, Magic.bytes("()V")))
        {
            return 4;
        }
        return 0;
    }

    // ----- resolvers the on-metal MetalSymbols shares with emit* (M5.4.c) -----


    /** True if the {@code Class} entry {@code classIdx} names the class being compiled. */
    private static boolean selfClassAt(int classIdx)
    {
        return utf8Eq(gView.cp[u2(gView.base.plus(gView.cp[classIdx]))], gView.thisNameOff);
    }

    // Unresolved-`new` sites: a class named by a `new` that is neither registered nor the class being
    // compiled. Recorded (not halted on) because every real instance is a DENYLISTED class on a branch that
    // is never taken; the site index is baked into the trap call so the runtime can name the class if the
    // branch ever IS taken. Small by construction -- a whole jar batch produces about five.
    /**
     * Distinct `new` sites compiled before their class was registered. Was 256, and FULL RETURNED SITE 0 -- which
     * resolveUnresolvedNew then RESOLVED AND ALLOCATED, so every later deferred `new` past the cap built an
     * instance of whatever class site 0 named (MetalStdStreams$Uart, the first one a launch records): a SILENT
     * wrong type. MEASURED in a 24-probe batch once the loader stack was closure-deferred (more classes arrive
     * late, so more `new` sites are compiled against unregistered classes): ICU's `new IsAcceptable()` and the
     * VarHandle behind AtomicReferenceArray.AA both came back as Uart objects, and the next call on them failed
     * to dispatch. The batch passed 24/24 without the deferral and halted deterministically with it. Sized like
     * the sibling site tables; a full table now halts by name, as MAXVSITE's does.
     */
    private static final int MAXUNRES = 4096;
    private static AddressArray unresBase;
    private static int[] unresOff;
    private static int unresN;

    /** Record the class named by {@code classIdx} as an unresolved `new` target; its site index. */
    private static int unresolvedNewSite(int classIdx)
    {
        int off = gView.cp[u2(gView.base.plus(gView.cp[classIdx]))];
        if (unresBase == null)
        {
            unresBase = AddressArray.create(MAXUNRES);
            unresOff = new int[MAXUNRES];
            unresN = 0;
        }
        int i = 0;
        while (i < unresN)
        {
            if (unresBase.get(i).EQ(gView.base.toAddress()) && unresOff[i] == off)
            {
                return i;
            }
            i += 1;
        }
        if (unresN >= MAXUNRES)
        {
            capHalt(Magic.bytes("MAXUNRES"), unresN);    // returning a stale index would MIS-RESOLVE silently
        }
        unresBase.set(unresN, gView.base.toAddress());
        unresOff[unresN] = off;
        unresN += 1;
        return unresN - 1;
    }

    /**
     * A deferred `new` was REACHED: resolve it now. The compile-time failure means only that the class was not
     * registered when the body was emitted, which is the `new` half of the RTA-through-reflection gap — a body
     * compiled on demand can instantiate a class nothing pulled, because {@code loadClassIncremental} loads a
     * class WITHOUT its dependencies on purpose. Demand-load it, then allocate exactly as
     * {@code Baseline.lowerNew}'s resolved path does: size from the field count, the class's own TIB in the
     * header. Returns the reference.
     *
     * <p>Unlike a call site there is no stub to memoize into — a `new` is a fixed instruction sequence, not a
     * patchable branch target — so this runs per execution. These sites were assumed cold, and are not: a warm
     * TLS session executes them repeatedly (qprof, SslEngineProbe, 2026-11-06). What repeats is the registry
     * lookup, so it goes through the name index on the Utf8 at clsU, with no byte[] copy unless the class has to
     * be loaded.
     *
     * <p>If the class still cannot be resolved (a genuinely denylisted subtree) this falls through to
     * {@link #reportUnresolvedNew}, so that diagnostic — and the guarantee that a wrong-typed object is never
     * returned — is exactly as it was.
     */
    static long resolveUnresolvedNew(long site, long pc)
    {
        int i = (int) site;
        if (unresBase == null || i < 0 || i >= unresN || ClassRegistry.clTab == null)
        {
            reportUnresolvedNew(site, pc);              // does not return
        }
        if (isDenylisted(unresBase.get(i), unresOff[i]))
        {
            // A metal-absent subtree. The call path gets this guard at patch time (patchRelocsFrom never
            // stubs a denylisted callee); a `new` site is recorded during the compile, with no such check,
            // so it has to happen here. Without it loadClassIncremental would cheerfully pull the class --
            // pullClass(byte[]) goes straight to the classDir -- which both defeats the denylist and turns
            // demo/UnresolvedNewDemo, whose whole point is that this halts, into a silent pass.
            reportUnresolvedNew(site, pc);              // does not return
        }
        Address clsU = unresBase.get(i).plus(unresOff[i]);
        int len = u2(clsU);
        VM.loaderLock(VM.LOCK_NEW_RESOLVE);                                // demand-loads a class: one compiler at a time
        DataReference tib;
        int fields;
        int reg;
        try
        {
        // The name is already a Utf8 run at clsU: ask the index directly, and build the byte[] copy only when
        // the class has to be LOADED -- these sites are NOT cold (a warm TLS session executes them repeatedly).
        reg = DynamicLinker.regBySigU(clsU);
        if (reg < 0)
        {
            if (LOAD_LOG)
            {
                Uart.write(Magic.bytes("  newresolve "));
                printNameAt(clsU, 0);
                Uart.putc(0x0A);
            }
            byte[] slash = new byte[len];
            int k = 0;
            while (k < len)
            {
                slash[k] = (byte) u1(clsU.plus(2 + k));
                k += 1;
            }
            DataReference pulled = loadClassIncremental(slash);
            reg = DynamicLinker.regBySigU(clsU);
        }
        tib = DataReference.zero();
        fields = 0;
        if (reg >= 0 && ClassRegistry.clTab[reg].state >= RVMClass.ST_INSTANTIATED)
        {
            tib = ClassRegistry.clTab[reg].tib;                       // a half-lifecycle class has no filled TIB: do not use it
            fields = ClassRegistry.clTab[reg].fieldCount;
        }
        }
        finally
        {
            VM.loaderUnlock();   // see the note in lazyCompile: a throw here used to strand the lock
        }
        if (tib.isZero())
        {
            reportUnresolvedNew(site, pc);              // does not return
        }
        // JVMS 5.5: CREATING AN INSTANCE IS AN ACTIVE USE, so the class initializes BEFORE the object exists --
        // and in particular before the <init> the JIT calls next. Without this the initializer runs only when
        // some LATER path happens to force it (a link-resolved call into the class), by which time <init> has
        // already read its statics as 0. That is not an abstract ordering point: java/util/ArrayList's <init>
        // reads DEFAULTCAPACITY_EMPTY_ELEMENTDATA, so a deferred `new ArrayList<>()` produced a list whose
        // elementData was null and whose first add() threw NPE -- with the boot log showing `newresolve` and
        // `<init>` ahead of `clinit-lazy` for the same class.
        ClassInitializer.ensureClinit(reg);
        ObjectReference obj = Heap.alloc(16 + fields * 8).toObjectReference();
        obj.toAddress().plus(ObjectModel.TIB_OFFSET).store(tib);
        return obj.toLong();
    }

    /**
     * A deferred `new` was reached and could NOT be resolved. Name the class and the calling method, then halt
     * -- there is no correct object to return, and the old behaviour (an instance carrying an unrelated
     * class's TIB) corrupts silently instead.
     */
    static void reportUnresolvedNew(long site, long pc)
    {
        Uart.write(Magic.bytes("\nUNRESOLVED NEW: "));
        int i = (int) site;
        if (unresBase != null && i >= 0 && i < unresN)
        {
            printNameAt(unresBase.get(i), unresOff[i]);
        }
        else
        {
            Uart.write(Magic.bytes("<site "));
            VM.printDec(i);
            Uart.write(Magic.bytes(">"));
        }
        Uart.write(Magic.bytes("\n  at "));
        StackTrace.printFrameAt(Address.fromLong(pc));
        Uart.putc(0x0A);
        while (true) { Magic.wfe(); }
    }

    /**
     * Scalar instance size (header + one 8-byte slot per field) of class {@code classIdx}, or
     * {@code -(site + 1)} when the class is neither registered nor the class being compiled -- see
     * {@link Symbols#objectSize}. The unregistered-but-SELF case is real (a class `new`ing itself before its
     * own registration completes) and keeps the {@code gBuild.ifCount} size; anything else used to take that same
     * path and silently produce an object of the wrong type.
     */
    static int objectSizeOf(int classIdx)
    {
        int r = ClassRegistry.classRegOf(classIdx);
        if (r < 0 && !selfClassAt(classIdx))
        {
            int site = unresolvedNewSite(classIdx);
            newLowerLog(classIdx, -1, site);            // DEFERRED: NEW_UNRESOLVED, allocated at first reach
            return -(site + 1);
        }
        int fields = r >= 0 ? ClassRegistry.clTab[r].fieldCount : gBuild.ifCount;
        newLowerLog(classIdx, 16 + fields * 8, -1);     // INLINE: Heap.alloc + this class's TIB, here and now
        return 16 + fields * 8;
    }

    /**
     * Which path a {@code new} of a watched class took, and the size it was given.
     *
     * <p>The two paths differ in far more than their code: the INLINE one allocates at a size derived from
     * the registered class and stores that class's TIB, while the DEFERRED one records a site and lets
     * {@code VM.newUnresolved} demand-load and allocate on first reach. Both push exactly one reference, so
     * nothing downstream can tell them apart -- which is why the choice has to be reported rather than
     * inferred from the object afterwards. Print-only: it emits no code and cannot perturb the lowering.
     */
    private static void newLowerLog(int classIdx, int size, int site)
    {
        if (!BUF_WATCH_ON)
        {
            return;
        }
        int nameOff = gView.cp[u2(gView.base.plus(gView.cp[classIdx]))];
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), nameOff, BUF_WATCH_CLASS))
        {
            return;
        }
        Uart.write(Magic.bytes("  NEWWATCH new "));
        printNameAt(gView.base.toAddress(), nameOff);
        Uart.write(Magic.bytes(" in "));
        printNameAt(gView.base.toAddress(), gView.thisNameOff);
        if (size >= 0)
        {
            Uart.write(Magic.bytes(" -> INLINE size="));
            VM.printDec(size);
        }
        else
        {
            Uart.write(Magic.bytes(" -> DEFERRED (NEW_UNRESOLVED) site="));
            VM.printDec(site);
        }
        Uart.putc(0x0A);
    }


    // ----- the on-metal MetalSymbols stubs made real: strings + magic intrinsics -----

    /**
     * Intern the String literal at cp index {@code stringCp} as a heap {@code byte[]}
     * (the writer's [null TIB][status][length][bytes] layout, i.e. an ordinary array)
     * and return its address, so the shared core's {@code ldc} loads a real ref.
     */
    /** The character decoded by {@link #decodeUtf8At}, and the byte index just past it. */
    static int decCh;
    static int decNext;

    /**
     * Decode ONE character of MODIFIED UTF-8 at {@code src + i}, leaving it in {@link #decCh} and the next
     * byte index in {@link #decNext}.
     *
     * <p>Modified UTF-8, not standard: a NUL is the two bytes {@code C0 80}, and a character outside the BMP
     * is encoded as its two SURROGATES separately (three bytes each) rather than as one four-byte sequence.
     * Decoding surrogates individually is exactly what a UTF16 String wants -- each becomes one char.
     *
     * <p>A malformed sequence keeps the lead byte as its own character rather than consuming bytes it cannot
     * verify: a decoder that ran off the end of a literal would read whatever followed it in the blob.
     */
    static void decodeUtf8At(Address src, int i, int len)
    {
        int b0 = u1(src.plus(i));
        if (b0 < 0x80)
        {
            decCh = b0;
            decNext = i + 1;
        }
        else if ((b0 & 0xE0) == 0xC0 && i + 1 < len)
        {
            decCh = ((b0 & 0x1F) << 6) | (u1(src.plus(i + 1)) & 0x3F);
            decNext = i + 2;
        }
        else if ((b0 & 0xF0) == 0xE0 && i + 2 < len)
        {
            decCh = ((b0 & 0x0F) << 12) | ((u1(src.plus(i + 1)) & 0x3F) << 6) | (u1(src.plus(i + 2)) & 0x3F);
            decNext = i + 3;
        }
        else
        {
            decCh = b0;                                 // malformed: keep the byte, consume nothing extra
            decNext = i + 1;
        }
    }

    /** The coder of the value array {@link #internString} last built: 0 = LATIN1, 1 = UTF16. */
    private static int lastLiteralCoder;

    /**
     * The value array for a {@code CONSTANT_String} literal, DECODED from modified UTF-8.
     *
     * <p>This used to copy the Utf8 body VERBATIM and leave the coder LATIN1, which is right for ASCII and
     * wrong for everything else: {@code "\u00ff"} came out as its two encoded bytes, so it had LENGTH 2 and a
     * first character of 195. Every non-ASCII literal in the VM had the wrong length AND the wrong contents,
     * and ASCII being unaffected is why it went unnoticed for so long.
     *
     * <p>It surfaced through picocli, which word-wraps by handing
     * {@code plainString().replace("-", "\u00ff")} to a {@code BreakIterator} and then slicing the original
     * text with the boundaries that come back -- a replacement one character too long shifts every boundary
     * past a hyphen and the slice runs off the end of the buffer.
     *
     * <p>LATIN1 whenever every character fits a byte, UTF16 otherwise -- the same choice stock makes, and the
     * reason the coder has to travel back to {@link #internStringObj}. The UTF16 byte order is little-endian
     * because {@code StringUTF16.LO_BYTE_SHIFT} is seeded to 8 for AArch64 (its {@code <clinit>} asks Unsafe
     * and cannot run here); storing it the other way round would read back with the bytes swapped.
     */
    static long internString(int stringCp)
    {
        int off = ClassReader.stringUtf8Off(gView.bytes, gView.cp, stringCp);   // Utf8 body offset
        int len = u2(gView.base.plus(off));
        Address src = gView.base.plus(off + 2L);
        int nchars = 0;                                 // pass 1: how many CHARS, and do they all fit a byte?
        int maxCh = 0;
        int i = 0;
        while (i < len)
        {
            decodeUtf8At(src, i, len);
            if (decCh > maxCh)
            {
                maxCh = decCh;
            }
            nchars += 1;
            i = decNext;
        }
        lastLiteralCoder = maxCh < 256 ? 0 : 1;
        ObjectReference arr = Heap.allocArray(lastLiteralCoder == 0 ? nchars : nchars * 2, 1).toObjectReference();
        Address bt = ArrayTypes.byteArrayTib();
        if (!bt.isZero())
        {
            arr.toAddress().plus(ObjectModel.TIB_OFFSET).store(bt);   // type it as [B so `checkcast [B` on String.value resolves
        }
        i = 0;
        int k = 0;                                      // pass 2: store the decoded characters
        while (i < len)
        {
            decodeUtf8At(src, i, len);
            if (lastLiteralCoder == 0)
            {
                arr.toAddress().plus(24).plus(k).store((byte) decCh);      // ARRAY_BASE_OFFSET = 24
            }
            else
            {
                arr.toAddress().plus(24).plus(k << 1).store((byte) (decCh & 0xFF));            // LO first (HI_BYTE_SHIFT = 0)
                arr.toAddress().plus(24).plus(k << 1).plus(1).store((byte) ((decCh >> 8) & 0xFF));
            }
            i = decNext;
            k += 1;
        }
        return arr.toLong();
    }

    /**
     * Intern a string literal as a mini {@code java/lang/String} OBJECT (byte[] value wrapped) if String
     * is loaded — so String methods work on literals — else as a raw byte[] (unchanged for String-free
     * guests). {@code VM.strBytes}/{@code printStr} accept either, so a String and a byte[] stay
     * interchangeable wherever raw bytes are wanted.
     */
    static long internStringObj(int stringCp)
    {
        // Intern per (blob, cp entry): the SAME String literal must yield the SAME object, so `ldc "x"` at two
        // sites compares == (JLS string interning). Without this each ldc site allocated a distinct object, so
        // e.g. Objects.requireNonNull("pants") == "pants" was false (BasicObjectsTest.testRequireNonNull). Keyed
        // by cp index in the current blob's cache (reset per parseConstPool); cross-blob interning is not modelled.
        ObjectReferenceArray lit = gView.litObj;
        if (stringCp < lit.length() && !lit.get(stringCp).isNull())
        {
            return lit.get(stringCp).toLong();
        }
        ObjectReference bytesRef = ObjectReference.fromLong(internString(stringCp));   // the byte[]: a reference
        long bytes = bytesRef.toLong();                 // a raw copy; bytesRef keeps the array alive
        DataReference tib = stringTib();
        long result;
        if (tib.isZero())
        {
            result = anchorLiteral(bytes);              // String not loaded: a raw byte[]
        }
        else
        {
            ObjectReference obj = Heap.alloc(stringSize()).toObjectReference();
            obj.toAddress().plus(0L).store(tib);               // TIB
            obj.toAddress().plus(16L).store(bytes);            // value field (offset 16)
            obj.toAddress().plus(24L).store((long) lastLiteralCoder); // coder (offset 24): 0 LATIN1, 1 UTF16
            long canon = poolLookup(obj.toLong());               // the same literal in ANOTHER class is the SAME object
            if (canon != 0L)
            {
                result = canon;
            }
            else
            {
                result = anchorLiteral(obj.toLong());
                poolInsert(obj.toLong());
            }
        }
        lit = gView.litObj;                             // re-read: interning above may have parsed another blob
        if (stringCp < lit.length())
        {
            lit.set(stringCp, ObjectReference.fromLong(result));
        }
        return result;
    }

    // GC ROOT for interned literals: a JIT'd `ldc "..."` bakes the literal's heap address into code
    // IMMEDIATES only (movz/movk), which the conservative collector cannot see — so without an anchor a
    // collection would sweep every string literal not currently live on a stack, and the next execution of
    // that ldc would push a freed (re-zeroed / reused) object. Every interned literal is therefore also
    // recorded here; the array is reachable from this static -> the image statics root -> its body is
    // traced -> the literals stay marked. Literals live until the next batch rewind reclaims them wholesale.
    private static ObjectReferenceArray litAnchor;   // object references: exact roots (frames/GC arc S6b)
    private static int litAnchorN;
    // Per-blob ldc-String intern cache: gView.litObj[cpIndex] = the one interned object for that String cp entry
    // (so all ldc sites of the same literal in a class share one object). It lives in the blob's ClassFileView, so
    // it always matches the blob currently compiling; entries are anchored (litAnchor) like any interned literal.

    // THE VM-WIDE STRING POOL (JLS 3.10.5, String.intern). Open addressing over String object addresses, 0 =
    // empty, keyed on CONTENT (coder + value bytes). Every literal goes through it, so `ldc "x"` in two classes
    // yields ONE object, and String.intern() answers that same object. Before it, literals were interned per
    // CLASS only and intern() was identity -- so `new String("tr").intern() == "tr"` was false, and stock
    // StringLatin1's `lang == "tr"` (Turkish case mapping) never matched a Locale's interned language.
    //
    // LIFETIME: cleared in resetLoader beside litAnchor and the parse cache, the two other places a literal is
    // remembered -- an entry outliving them could hand out an object from a reclaimed batch. Entries are kept
    // alive by litAnchor, not by this array (a long[] is not traced). STATED LIMITS: writer-BAKED literals are
    // not in the pool (only guest code compares against them here, measured for the case-mapping paths), and
    // an intern()ed string is held for the rest of the launch where stock's table is weak.
    private static ObjectReferenceArray strPool;
    private static int strPoolN;

    /** FNV-1a over a String's coder and value bytes. */
    private static int poolHash(long str)
    {
        Address val = Address.fromLong(str).plus(16L).loadAddress();
        int len = (int) val.plus(16L).loadLong();
        int h = 0x811C9DC5 ^ (int) Address.fromLong(str).plus(24L).loadLong();
        int i = 0;
        while (i < len)
        {
            h = (h ^ (val.plus(24L).plus(i).loadUnsignedByte() & 0xFF)) * 0x01000193;
            i += 1;
        }
        return h & 0x7FFFFFFF;
    }

    /** Whether two Strings have the same coder and the same value bytes. */
    private static boolean poolSame(long a, long b)
    {
        if (Address.fromLong(a).plus(24L).loadLong() != Address.fromLong(b).plus(24L).loadLong())
        {
            return false;
        }
        Address va = Address.fromLong(a).plus(16L).loadAddress();
        Address vb = Address.fromLong(b).plus(16L).loadAddress();
        int len = (int) va.plus(16L).loadLong();
        if (len != (int) vb.plus(16L).loadLong())
        {
            return false;
        }
        int i = 0;
        while (i < len)
        {
            if (va.plus(24L).plus(i).loadUnsignedByte() != vb.plus(24L).plus(i).loadUnsignedByte())
            {
                return false;
            }
            i += 1;
        }
        return true;
    }

    /** The pooled String with {@code str}'s content, or 0. */
    private static long poolLookup(long str)
    {
        if (strPool == null)
        {
            return 0L;
        }
        int mask = strPool.length() - 1;
        int i = poolHash(str) & mask;
        while (!strPool.get(i).isNull())
        {
            if (poolSame(strPool.get(i).toLong(), str))
            {
                return strPool.get(i).toLong();
            }
            i = (i + 1) & mask;
        }
        return 0L;
    }

    /** Add {@code str}, known absent, growing the table at half full. */
    private static void poolInsert(long str)
    {
        if (strPool == null || (strPoolN + 1) * 2 > strPool.length())
        {
            ObjectReferenceArray old = strPool;
            strPool = ObjectReferenceArray.create(old == null ? 1024 : old.length() * 2);
            int k = 0;
            while (k < strPool.length())
            {
                strPool.set(k, ObjectReference.nullReference());   // allocArray does not zero elements on this VM
                k += 1;
            }
            strPoolN = 0;
            if (old != null)
            {
                int j = 0;
                while (j < old.length())
                {
                    if (!old.get(j).isNull())
                    {
                        poolPut(old.get(j).toLong());
                    }
                    j += 1;
                }
            }
        }
        poolPut(str);
    }

    private static void poolPut(long str)
    {
        int mask = strPool.length() - 1;
        int i = poolHash(str) & mask;
        while (!strPool.get(i).isNull())
        {
            i = (i + 1) & mask;
        }
        strPool.set(i, ObjectReference.fromLong(str));
        strPoolN += 1;
    }

    /** {@code String.intern()}: the canonical String with this content, adding {@code str} if there is none.
     *  Called under the loader lock -- literal interning runs inside compiles, which hold it. */
    static long poolIntern(ObjectReference str)
    {
        long canon = poolLookup(str.toLong());
        if (canon != 0L)
        {
            return canon;
        }
        anchorLiteral(str.toLong());
        poolInsert(str.toLong());
        return str.toLong();
    }

    /** Record {@code obj} (a literal the JIT bakes into code) as a GC root; returns it for chaining. */
    private static long anchorLiteral(long obj)
    {
        if (litAnchor == null || litAnchorN == litAnchor.length())
        {
            ObjectReferenceArray grown = ObjectReferenceArray.create(litAnchor == null ? 256 : litAnchor.length() * 2);
            int i = 0;
            while (i < litAnchorN)
            {
                grown.set(i, litAnchor.get(i));
                i += 1;
            }
            litAnchor = grown;
        }
        litAnchor.set(litAnchorN, ObjectReference.fromLong(obj));
        litAnchorN += 1;
        return obj;
    }

    /** Whether the {@code *ref} at {@code idx} names owner {@code magic/Magic}. */
    /** {@link compiler.Symbols#mathIntrinsicId} in the current compile context. */
    static int mathIntrinsicId(int idx)
    {
        if (!Utf8.utf8IsAtBase(gView.base.toAddress(), ClassReader.refClassNameOff(gView.bytes, gView.cp, idx), Magic.bytes("java/lang/Math"))
                || !Utf8.utf8IsAtBase(gView.base.toAddress(), ClassReader.refDescOff(gView.bytes, gView.cp, idx), Magic.bytes("(JJ)J")))
        {
            return -1;
        }
        int n = ClassReader.refNameOff(gView.bytes, gView.cp, idx);
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("multiplyHigh")))
        {
            return compiler.Intrinsics.SMULH;
        }
        if (Utf8.utf8IsAtBase(gView.base.toAddress(), n, Magic.bytes("unsignedMultiplyHigh")))
        {
            return compiler.Intrinsics.UMULH;
        }
        return -1;
    }

    /** The {@link compiler.Unboxed} kind of the {@code *ref} at {@code idx}'s owner -- NONE unless magic.Address etc. */
    static int unboxedKind(int idx)
    {
        return compiler.Unboxed.ownerKind(new Utf8At(gView.base.toAddress(), ClassReader.refClassNameOff(gView.bytes, gView.cp, idx)));
    }

    /** The {@link compiler.Intrinsics} id of the unboxed-type method at {@code idx}, or -1. */
    static int unboxedOp(int idx)
    {
        return compiler.Unboxed.op(unboxedKind(idx), new Utf8At(gView.base.toAddress(), ClassReader.refNameOff(gView.bytes, gView.cp, idx)),
                new Utf8At(gView.base.toAddress(), ClassReader.refDescOff(gView.bytes, gView.cp, idx)));
    }

    static boolean isMagicOwner(int idx)
    {
        Address p = gView.base.plus(ClassReader.refClassNameOff(gView.bytes, gView.cp, idx));
        if (u2(p) != 11)                                // "magic/Magic"
        {
            return false;
        }
        p = p.plus(2);
        return u1(p) == 'm' && u1(p.plus(1)) == 'a' && u1(p.plus(2)) == 'g' && u1(p.plus(3)) == 'i'
            && u1(p.plus(4)) == 'c' && u1(p.plus(5)) == '/' && u1(p.plus(6)) == 'M' && u1(p.plus(7)) == 'a'
            && u1(p.plus(8)) == 'g' && u1(p.plus(9)) == 'i' && u1(p.plus(10)) == 'c';
    }

    /**
     * {@link Intrinsics} id of the {@code magic/Magic} method at {@code idx}, or -1 if
     * not one this JIT recognises. It handles the memory + string-bytes ops a JIT'd
     * class might plausibly call; the boot-only register/barrier ops (msr, eret,
     * write*…) are only ever writer-compiled, never JIT'd, so they are not recognised.
     */
    static int magicId(int idx)
    {
        int n = mrefNameOff(idx);
        if (Utf8.isName(gView.base.toAddress(), n, 0x6279746573L, 5))    { return Intrinsics.BYTES; }    // "bytes"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6C6F616438L, 5))    { return Intrinsics.LOAD8; }    // "load8"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6C6F61643332L, 6))  { return Intrinsics.LOAD32; }   // "load32"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6C6F61643634L, 6))  { return Intrinsics.LOAD64; }   // "load64"
        if (Utf8.isName(gView.base.toAddress(), n, 0x73746F726538L, 6))  { return Intrinsics.STORE8; }   // "store8"
        if (Utf8.isName(gView.base.toAddress(), n, 0x73746F72653332L, 7)) { return Intrinsics.STORE32; } // "store32"
        if (Utf8.isName(gView.base.toAddress(), n, 0x73746F72653634L, 7)) { return Intrinsics.STORE64; } // "store64"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6361733634L, 5))      { return Intrinsics.CAS64; }    // "cas64"
        if (Utf8.isName(gView.base.toAddress(), n, 0x737061776EL, 5))      { return Intrinsics.SPAWN; }    // "spawn"
        if (Utf8.isName(gView.base.toAddress(), n, 0x73656D57616974L, 7))  { return Intrinsics.SEM_WAIT; } // "semWait"
        if (Utf8.isName(gView.base.toAddress(), n, 0x73656D506F7374L, 7))  { return Intrinsics.SEM_POST; } // "semPost"
        if (Utf8.isName(gView.base.toAddress(), n, 0x736C6565704D73L, 7))  { return Intrinsics.SLEEP_MS; } // "sleepMs"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6E657753656DL, 6))    { return Intrinsics.NEW_SEM; }  // "newSem"
        if (Utf8.isName(gView.base.toAddress(), n, 0x7265706F7274L, 6))    { return Intrinsics.REPORT; }   // "report"
        if (Utf8.isName(gView.base.toAddress(), n, 0x7072696E74537472L, 8)) { return Intrinsics.PRINT_STR; } // "printStr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x616464724F66L, 6))    { return Intrinsics.ADDR_OF; }   // "addrOf"
        if (Utf8.isName(gView.base.toAddress(), n, 0x66726F6D41646472L, 8)) { return Intrinsics.FROM_ADDR; } // "fromAddr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x63616C6C4EL, 5))      { return Intrinsics.CALL_N; }    // "callN"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6D77616974L, 5))      { return Intrinsics.MON_WAIT; }    // "mwait"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6D6E6F74696679L, 7))  { return Intrinsics.MON_NOTIFY; }  // "mnotify"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6D6E6F74616C6CL, 7))  { return Intrinsics.MON_NOTALL; }  // "mnotall"
        if (Utf8.isName(gView.base.toAddress(), n, 0x746A6F696EL, 5))      { return Intrinsics.THREAD_JOIN; } // "tjoin"
        if (Utf8.isName(gView.base.toAddress(), n, 0x737461636B7472L, 7))  { return Intrinsics.STACK_TRACE; } // "stacktr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x616C6C746872L, 6))    { return Intrinsics.ALL_THREADS; } // "allthr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x686C646C6F636BL, 7))  { return Intrinsics.HOLDS_LOCK; }  // "hldlock"
        if (Utf8.isName(gView.base.toAddress(), n, 0x696E7472L, 4))        { return Intrinsics.INTR; }        // "intr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6973696E7472L, 6))    { return Intrinsics.IS_INTR; }     // "isintr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x776173696E7472L, 7))  { return Intrinsics.WAS_INTR; }    // "wasintr"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6973616C697665L, 7))  { return Intrinsics.IS_ALIVE; }    // "isalive"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6A6F696E6D73L, 6))    { return Intrinsics.JOIN_TIMED; }  // "joinms"
        if (Utf8.isName(gView.base.toAddress(), n, 0x7061726BL, 4))        { return Intrinsics.PARK; }        // "park"
        if (Utf8.isName(gView.base.toAddress(), n, 0x756E7061726BL, 6))    { return Intrinsics.UNPARK; }      // "unpark"
        if (Utf8.isName(gView.base.toAddress(), n, 0x726561644C52L, 6))    { return Intrinsics.READ_LR; }     // "readLR" (getCallerClass)
        if (Utf8.isName(gView.base.toAddress(), n, 0x72656164583136L, 7))  { return Intrinsics.READ_X16; }   // "readX16" (wild-branch target)
        if (Utf8.isName(gView.base.toAddress(), n, 0x6D70696472L, 5))      { return Intrinsics.READ_MPIDR; }  // "mpidr" (which core am I?)
        if (Utf8.isName(gView.base.toAddress(), n, 0x7365747072696FL, 7))  { return Intrinsics.SET_PRIO; }    // "setprio"
        if (Utf8.isName(gView.base.toAddress(), n, 0x6765747072696FL, 7))  { return Intrinsics.GET_PRIO; }    // "getprio"
        return -1;
    }


}
