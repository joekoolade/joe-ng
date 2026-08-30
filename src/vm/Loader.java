package vm;

import asm.A64Enc;
import board.bcm2711.Uart;
import classfile.ClassReader;
import compiler.Baseline;
import compiler.Intrinsics;
import compiler.Symbols;
import objectmodel.ObjectModel;
import magic.Magic;

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

    private static long gbase;      // class blob base address
    private static long gp;         // parse cursor
    private static int[] gcp;       // byte offset of each constant-pool entry body
    private static byte[] gbytes;   // heap copy of the current class, for the shared ClassReader
    private static int[] gBcToWord; // last compiled method's bci -> machine word offset (Baseline.bcToWord)
    private static int gAfterCp;    // offset just past the constant pool (stable; gp gets reused as a cursor)
    private static int[] gcpTag;    // tag of each entry (7 = Class — used for dependencies)
    private static int gcpCount;
    // Per-blob constant-pool PARSE CACHE. parseConstPool used to copy the whole blob (toBytes) + allocate
    // gcp/gcpTag/litObjByCp EVERY call; RTA (resolveVirtuals) re-parses each instantiated class's superclass
    // chain once per fixpoint iteration -> thousands of parses -> ~190 MiB of garbage byte[]/int[] copies that
    // fill the demand-load arena (blocker (e)). Cache the parse keyed by blob base so each blob is copied+parsed
    // ONCE; later calls reuse it. The cache arrays are scanned loader statics, so the byte[]/int[] survive GC.
    private static final int MAXPARSECACHE = 2048;
    private static long[] pcBase;    // cached blob base address (0 = empty slot)
    private static byte[][] pcBytes; // the toBytes copy
    private static int[][] pcCp;     // cp entry offsets
    private static int[][] pcCpTag;  // cp entry tags
    private static long[][] pcLitObj;// per-entry ldc-String intern cache
    private static int[] pcCpCount;  // cp entry count
    private static int[] pcAfterCp;  // offset past the constant pool
    private static int pcN;
    private static int gClassModifiers;   // current class's access_flags (ACC_SUPER stripped) — cached into clModifiers
    private static int gcodeLen;    // length of the located method's bytecode
    private static int gMaxLocals;  // ... and its max_locals (frame sizing)
    private static int gFoundDescOff;  // descriptor Utf8 offset of the last findMethod hit
    private static int gFoundStatic;   // 1 if that method is static
    private static int gFrameSize;     // frame size of the last method the core compiled
    private static int gRegLocals;     // its callee-saved local count (x19..) — for unwind's pre-try local restore
    // Try/catch ranges of the last method the core compiled (machine word offsets + catch-type cp),
    // captured for emitMethod to register into VM's jit handler table (cross-method unwind).
    private static int gHN;
    private static int[] gHStartW;
    private static int[] gHEndW;
    private static int[] gHandlerW;
    private static int[] gHCatchCp;
    private static long gnameP, gdescP;   // packed name/descriptor being searched for
    private static int gnameLen;
    private static int gdescLen;
    private static long gStatics;   // this class's statics block
    private static int[] gsfName;   // Utf8 offset of each static field's name (index = slot)
    private static int gsfCount;
    private static int[] gifName;   // Utf8 offset of each instance field's name (index = slot)
    private static int[] gifAccess; // access_flags of each own instance field (index = slot; 0 for inherited)
    private static int[] gifDescOff;// Utf8 offset of each own instance field's type descriptor (index = slot)
    private static int gifCount;
    private static int gThisNameOff;     // Utf8 offset of this class's own name
    private static long gMethodsStart;   // address of the methods_count (for callee lookup)
    private static long gBsmOff;         // address of the BootstrapMethods attr body (num_bootstrap_methods), or 0
    // Flattened vtable of the class being built: superclass slots first, overrides
    // replacing in place, new methods appended. Each slot's signature may live in a
    // superclass's blob (gvBase), and its implementation is either an inherited
    // compiled buffer (gvImplBuf) or one of this class's own methods (gvImplCode).
    private static final int MAXMV = 512;
    private static VtSlot[] gvTab;  // the building class's flattened vtable (reified; reused per class)
    private static int gvCount;     // flattened vtable size
    private static long gTib;       // this class's TIB { Type, vtable... }, built before emit
    private static long gType;      // this class's Type { superType } — a metal instanceof chain node
    private static int gSuperNameOff;  // superclass name Utf8 offset (unloaded/Object => no inherit)
    private static int[] gImplIfName;  // name Utf8 offsets of the interfaces this class implements
    private static int gImplIfCount;

    // Global method registry across all loaded classes, so a call in one class can
    // link to a method compiled in another. Each entry captures where its class /
    // name / descriptor Utf8 bytes live (all in that class's blob) plus its buffer.
    private static final int MAXREG = 6144;
    private static RVMMethod[] rgTab;   // global method registry (reified: one RVMMethod per compiled method)
    private static int rgCount;

    // Static-field registry: per loaded class, each static field's {class, name, slot address} so a
    // cross-class getstatic/putstatic (e.g. Long.formatUnsignedLong0 reading Integer.digits) resolves.
    private static RVMField[] sgTab;   // global static-field registry (reified: one RVMField per static field)
    private static int sgCount;

    // Class registry: per loaded class, what another class needs to `new` it and
    // dispatch through it — its name (base+offset), TIB, and instance-field count.
    private static final int MAXCLASS = 1024;
    // The class registry, reified: one RVMClass per loaded class (base/nameOff/tib/type/statics/fieldCount/
    // vtCount/vtStart/superReg/modifiers/isIface). The direct-interface list (clIfaceReg/clIfaceRegN) and the
    // <clinit> dependency arrays (clDep*) stay separate.
    // Two-phase load (structure then bodies): phase A registers every class's structure (Type, TIB, fields,
    // vtable SLOT numbering, interface slots) in super/interface-first order (acyclic); phase B compiles all
    // method bodies + builds the TIBs, by which point every cross-class new/field/vtable/itable/cast target is
    // registered -> load order no longer matters (only method-CALL cycles remain, handled by patchRelocs).
    private static RVMClass[] clTab;
    private static int clCount;
    // Array Type cache (per demand-load batch, since resetLoader reclaims the heap under them). primArrTib is
    // indexed by newarray atype (4..11); refArr* is a small element-Type-keyed registry for reference arrays.
    private static long[] primArrTib;     // arrayTib per primitive atype (0 = not yet created)
    private static boolean[] primArrAdopted;   // true = writer-baked image TIB (never refill: its Object
                                               // impls are baked code; loader bufs die with the batch heap)
    private static long[] refArrElem;     // element Type key (reference arrays)
    private static long[] refArrTib;      // arrayTib for that element
    private static boolean[] refArrAdopted;    // true = writer-baked image TIB (never refill)
    private static int refArrCount;
    // java.lang.Class mirrors: one Class object per VM Type (identity stable), materialised on ldc class-literal
    // or Object.getClass(). Per batch (they live in the reclaimed demand heap, like array Types).
    private static long[] mirType;        // VM Type addr key
    private static long[] mirObj;         // its Class mirror object
    private static int mirN;
    private static long classTibCache;    // guest java/lang/Class's TIB (0 = not loaded)
    // Directly-declared interfaces per registered class/interface, as registry indices, flat:
    // clIfaceReg[r*MAX_DIRECT_IF + j], j < clIfaceRegN[r]. buildItableDir transitively closes over these so
    // an itable directory keys on the SUPER-interfaces too (e.g. ArrayList implements List extends Iterable
    // -> its directory has both List and Iterable, so invokeinterface Iterable.iterator resolves).
    private static final int MAX_DIRECT_IF = 8;
    private static int[] clIfaceReg;
    private static int[] clIfaceRegN;
    private static int[] ifClosureBuf;   // scratch: registry indices in the current class's transitive iface closure
    // Every instantiated class's imap (the shared itable), captured at fillTib so refillImaps can repair
    // default-method slots that were 0 because the interface holding the default hadn't been EMITTED yet when
    // this class's buildImap ran (phase B is superclass-first, not super-interface-first).
    private static final int MAXIMAP = 2048;
    private static long[] instImaps;
    private static int[] instImapReg;    // the class registry index each imap belongs to (for its iface closure)
    private static int instImapN;
    // GC ROOTS for synthesised lambda classes. A lambda's TIB/Type/itableDir/imap are built by finishLambdaClass
    // in the GC heap [BASE,PTR) and are referenced ONLY by (a) baked immediates in JIT code (not scanned) and
    // (b) its runtime instances' object headers (word 0, which the collector does not trace). Unlike loaded
    // CLASSES (rooted via clTib[]), array types (refArrTib[]) and string literals (litAnchor[]), lambda metadata
    // sat in no scanned root — so once a large closure (java.util.stream) fills the arena and GC runs during load
    // (before any instance exists), the lambda TIB was swept and a reused block scribbled its Type to garbage,
    // making a later Function.apply on it NPE. Holding each TIB here (a scanned static array) keeps it live.
    private static final int MAXLAMBDATIB = 8192;
    private static long[] lambdaTibRoots;
    private static int lambdaTibRootN;

    // Field registry: per instance field of each class, its class/name (base+offset)
    // and slot, so a cross-class get/putfield can find the offset.
    private static final int MAXFIELD = 4096;
    private static RVMField[] fldTab;   // global instance-field registry (reified: reuses RVMField; slot/access/descOff)
    private static int fldCount;

    // Vtable-slot registry: per virtual method of each class, its class/name/desc
    // (base+offset) and vtable slot, so a cross-class invokevirtual can find the
    // slot in the receiver class's vtable (dispatch itself uses the object's TIB).
    private static final int MAXVT = 16384;
    private static long[] vtClassBase;   // class the vtable belongs to (base + off)
    private static int[] vtClassOff;
    private static long[] vtNameBase;    // method signature blob (may be a superclass's)
    private static int[] vtNameOff;
    private static int[] vtDescOff;
    private static int[] vtSlot;
    private static long[] vtBuf;         // slot's implementation buffer
    private static int vtCount;

    // Interface-method registry. Every distinct interface method (name+descriptor)
    // gets a global index; each implementing class then carries an "imap" indexed by
    // it, holding that class's implementation. This is the itable: it decouples the
    // call site from where the method happens to sit in a given class's vtable, so
    // two classes implementing the same interface at different vtable slots both
    // dispatch correctly. Interfaces are loaded before their implementors.
    private static final int MAXIFM = 2048;   // flattened per-interface runs duplicate inherited signatures
    private static long[] ifBase;        // interface blob holding the signature
    private static int[] ifNameOff;
    private static int[] ifDescOff;
    private static int ifCount;
    private static boolean gIsInterface; // is the class being loaded an interface?

    // Blobs handed to the loader, plus the dependencies between them, so load order
    // is derived rather than hand-maintained. A class must be loaded after every
    // class it names — its superclass and interfaces (needed for field layout,
    // vtable flattening and itable indices) but also anything it instantiates,
    // calls or type-tests (needed by the class/method/field registries).
    private static final int MAXBLOB = 1024;
    private static long[] pdBase;        // blob address
    private static int[] pdLen;          // blob length
    private static int[] pdNameOff;      // its own this_class name Utf8 offset
    private static int[] pdDone;         // 1 once phase-A structure is loaded
    private static int[] pdDoneB;        // 1 once phase-B bodies are compiled + TIB filled
    private static boolean[] pdNeedsString;   // blob materializes a String (CONSTANT_String or invokedynamic-concat)
    private static int stringPdIndex;    // pd index of java/lang/String, or -1 (set by probeAll)
    // Inheritance edges (super + direct interfaces) recorded by probeAll -- an ACYCLIC graph (Java forbids cyclic
    // inheritance), so phase A can always order super/interface-first with no stall. (This is distinct from the
    // full CONSTANT_Class dep list, which IS cyclic and is what made single-phase linking load-order-fragile.)
    private static int[] pdSuperOff;     // super name Utf8 offset (in pdBase[i]), or 0 if none (Object/interface)
    private static int[] pdIfOff;        // pdIfOff[i*MAX_DIRECT_IF+k] = direct-interface name Utf8 offset
    private static int[] pdIfN;          // number of direct interfaces recorded for blob i
    private static int pdCount;
    private static final int MAXDEP = 49152;
    private static int[] dpOwner;        // index into pd* of the blob that has this dependency
    private static int[] dpOff;          // dependency's name Utf8 offset (in pdBase[dpOwner])
    private static int dpCount;

    private static int u1(long p)
    {
        return Magic.load8(p) & 0xFF;
    }

    /**
     * A registry entry whose blob pointer is 0 — the shape a batch-reclaimed entry takes, since the reclaim
     * zeroes the memory it rewinds. Name the caller and the class being compiled, then halt. Called BEFORE
     * the read, so {@code lr} still holds the caller's return address; after the fault it is gone, and the
     * raw abort names only the two-line helper that happened to do the load.
     */
    /**
     * Walk the arming table and report the first entry whose blob has gone to zero since it was inserted.
     * Paired with the insertion guard: if the entry was born valid and is zero here, something wrote over
     * it, and the isolation runs have already ruled out the collector.
     */
    /** Halt NAMED if {@code entry} is a code buffer the collector swept — the initializer is about to be
     *  called and would execute zeros, reported minutes later from an unnamed address. */
    private static void checkClinitEntry(long entry, int idx)
    {
        if (Heap.codeBlockFreeAt(entry) == 1)
        {
            Uart.write(Magic.bytes("\nCLINIT ENTRY WAS SWEPT idx="));
            VM.printDec(idx);
            Uart.write(Magic.bytes(" entry="));
            VM.printHex(entry);
            Uart.putc(0x0A);
            VMGc.reportSweptPc(entry);
            printCurrentClass();
            Uart.putc(0x0A);
            while (true) { Magic.wfe(); }
        }
    }

    /**
     * After a code sweep, every phase-A cell must still point into an ALLOCATED code buffer. A cell whose
     * target has been freed is the exact shape of the fault: the dispatcher branches through the cell into
     * a buffer the collector zeroed, and executes zeros. Checking here names the entry while the tables are
     * still intact, instead of leaving a trap in an unnamed buffer minutes later.
     */
    public static void verifyCells()
    {
        int k = 0;
        while (k < dlN)
        {
            if (dlTab[k] != null && dlTab[k].cell != 0L)
            {
                long target = Magic.load64(dlTab[k].cell);
                if (target >= Heap.CODE_BASE && target < Heap.CODE_LIMIT
                        && Heap.codeBlockFreeAt(target) == 1)
                {
                    Uart.write(Magic.bytes("\nCELL -> FREED CODE k="));
                    VM.printDec(k);
                    Uart.write(Magic.bytes(" target="));
                    VM.printHex(target);
                    Uart.write(Magic.bytes(" cell="));
                    VM.printHex(dlTab[k].cell);
                    Uart.write(Magic.bytes("\n  method: "));
                    printFrameAt(target);
                    Uart.putc(0x0A);
                    while (true) { Magic.wfe(); }
                }
            }
            k += 1;
        }
    }

    private static void verifyDlTab()
    {
        int k = 0;
        while (k < dlN)
        {
            if (dlTab[k] == null || dlTab[k].blob == 0L)
            {
                Uart.write(Magic.bytes("\nDL WENT ZERO at k="));
                VM.printDec(k);
                Uart.write(Magic.bytes(" of "));
                VM.printDec(dlN);
                Uart.write(Magic.bytes(" null="));
                VM.printDec(dlTab[k] == null ? 1 : 0);
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
        printFrameAt(lr - 4L);
        Uart.write(Magic.bytes("\n  while compiling "));
        printCurrentClass();
        Uart.putc(0x0A);
        while (true)
        {
            Magic.wfe();
        }
    }
    private static int u2(long p)
    {
        return (u1(p) << 8) | u1(p + 1);
    }
    private static int u4(long p)
    {
        return (u2(p) << 16) | u2(p + 2);
    }

    private static void seek(long nameP, int nameLen, long descP, int descLen)
    {
        gnameP = nameP;
        gnameLen = nameLen;
        gdescP = descP;
        gdescLen = descLen;
    }

    /**
     * Run the class's static initializer if it has one. Called after
     * {@link #parseFields} (so the statics block exists) and before the entry
     * method, so {@code <clinit>}'s {@code putstatic}s land before first use. It is
     * just another method the loader compiles and runs; it dirties the seek key, so
     * the caller sets the entry seek afterwards.
     */
    private static void runClinit(long bytes)
    {
        if (clinitBlocked())
        {
            return;    // unrunnable <clinit> (calls natives / reads properties): its statics are seeded instead
        }
        seek(0x3C636C696E69743EL, 8, 0x282956L, 3);    // "<clinit>" "()V"
        long code = findMethod(bytes);
        if (code != 0L && clinitCompilable(code, gcodeLen))
        {
            if (clinitN >= MAXBLOB) { capHalt(Magic.bytes("MAXBLOB-clinit"), clinitN); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
            // Record the initializer's PRECISE dependencies (from its bytecode) BEFORE compile — compile uses the
            // same gcp/gbase, but scanning first keeps the cp-parse state pristine for the walk.
                clDepStart[clinitN] = clDepTop;
            scanClinitDeps(code, gcodeLen);
                clDepN[clinitN] = clDepTop - clDepStart[clinitN];
            // CAPTURE the body; do not compile it. Initialization already happens on demand (JVMS 5.5, the
            // lazy-init arc), but the COMPILE stayed here at load time, so an initializer the program never
            // touches still cost its full A64 body -- twice over, since each batch that loads the class
            // recompiles it. java/lang/Character$UnicodeScript is the extreme case: 32K of bytecode -> ~1.2 MB
            // of code, emitted in two batches and executed in neither, which was 3.5 MB of a 4.0 MB arena.
            // clinitEntryOf compiles on the first ACTUAL run, and its own relocs are patched there (the
            // batch-wide patchRelocs is long past by then) exactly as lazyCompile does for deferred bodies.
            clinitCode[clinitN] = code;
            clinitCodeLen[clinitN] = gcodeLen;
            clinitDescOff[clinitN] = gFoundDescOff;
            clinitStatic[clinitN] = gFoundStatic;
            clinitLocals[clinitN] = gMaxLocals;
            clinitEntry[clinitN] = 0L;              // uncompiled: clinitCode[] is now the "enqueued" marker
                clinitPd[clinitN] = findPdByName(gbase, gThisNameOff);   // which blob (for dependency-ordered running)
            if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/io/FileDescriptor")))
            {
                clinitFdFirst = clinitN;   // run FIRST in runClinits: it registers the JavaIOFileDescriptorAccess
            }                              // that NativeDispatcher/NioSocketImpl <clinit>s read via SharedSecrets
            clinitN += 1;
        }
    }

    /**
     * The compiled entry of initializer {@code i}, compiling its captured body on first request.
     *
     * <p>This is the whole point of deferring: an initializer that never runs never gets an A64 body. The
     * shape mirrors {@link #lazyCompile} because the situation is identical — a body captured during one
     * batch, compiled at an arbitrary later moment when the batch's compile context is long gone. So it
     * restores that context from the class registry, keeps the class's already-filled TIB, and patches its
     * OWN reloc sites, since the batch-wide {@code patchRelocs} has already run and will not run again for
     * these. Without that last step a cross-class call in the initializer stays a {@code bl 0}.
     *
     * <p>Memoized into {@code clinitEntry[i]}, which is also what keeps the buffer alive: the array is a
     * heap {@code long[]}, so the conservative root scan sees the entry address and pins the block. An
     * uncompiled slot holds 0 and pins nothing, which is correct — there is nothing to keep.
     */
    private static long clinitEntryOf(int i)
    {
        if (clinitEntry[i] != 0L)
        {
            return clinitEntry[i];                      // already compiled (or run once before)
        }
        if (clinitCode[i] == 0L)
        {
            return 0L;                                  // empty slot: nothing was enqueued here
        }
        int pd = clinitPd[i];
        int reg = clinitRegOf(i);
        if (pd < 0 || reg < 0)
        {
            capHalt(Magic.bytes("clinit-compile-ctx"), i);   // no blob/class to compile against -- name it, don't guess
        }
        restoreCtxForCompile(pdBase[pd], pdLen[pd], reg);
        gMaxLocals = clinitLocals[i];                   // AFTER the restore: parseFields/parseVtable clobber it
        compileReuseTib = true;                         // the class's TIB exists and is filled -- do not rebuild it
        int rcMark = rcCount;                           // this compile's own reloc sites start here
        int rsMark = rsCount;
        long buf = compile(clinitCode[i], clinitCodeLen[i], clinitDescOff[i], clinitStatic[i]);
        compileReuseTib = false;
        patchRelocsFrom(rcMark, rsMark);                // resolve OUR sites: batch patchRelocs is long past
        if (buf == 0L)
        {
            capHalt(Magic.bytes("clinit-compile-null"), i);  // about to call address 0 -- halt with an index instead
        }
        clinitEntry[i] = buf;
        return buf;
    }

    /** The class-registry index of initializer {@code i}'s owner, matched by blob base (as ensureClinit does). */
    private static int clinitRegOf(int i)
    {
        int pd = clinitPd[i];
        if (pd < 0 || clTab == null)
        {
            return -1;
        }
        long base = pdBase[pd];
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && clTab[r].base == base)
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /**
     * Walk the {@code <clinit>} bytecode and record the classes whose initialization it needs — the JVM's own
     * init triggers ({@code getstatic}/{@code putstatic}/{@code invokestatic} owner, {@code new}/{@code anewarray}
     * class) plus {@code ldc} of a Class literal (a class literal in a {@code <clinit>} typically feeds reflective
     * access — {@code getEnumConstants} reads the enum's {@code $VALUES}, which only its own {@code <clinit>} sets).
     * Self-references are skipped. These are the exact edges clinit ordering needs, without the spurious ones the
     * whole constant pool carries (field types, signatures, an inner class naming its outer, ...).
     */
    private static void scanClinitDeps(long code, int len)
    {
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            int cnOff = -1;
            if (op == 0xB2 || op == 0xB3 || op == 0xB8)        // getstatic / putstatic / invokestatic: owner init
            {
                cnOff = refClassNameOff(u2(code + pc + 1));
            }
            else if (op == 0xBB || op == 0xBD)                 // new / anewarray: the class is initialized
            {
                cnOff = classCpNameOff(u2(code + pc + 1));
            }
            else if (op == 0x12)                               // ldc: a Class literal feeds reflective init
            {
                int ci = u1(code + pc + 1);
                if (gcpTag[ci] == 7)
                {
                    cnOff = classCpNameOff(ci);
                }
            }
            else if (op == 0x13)                               // ldc_w
            {
                int ci = u2(code + pc + 1);
                if (gcpTag[ci] == 7)
                {
                    cnOff = classCpNameOff(ci);
                }
            }
            if (cnOff >= 0 && cnOff != gThisNameOff)            // skip self-references
            {
                addClDep(cnOff);
            }
            pc += insnLen(code, pc);
        }
    }

    /** Append a precise clinit dependency (name Utf8 offset in the current blob), deduped within this <clinit>. */
    private static void addClDep(int nameOff)
    {
        int k = clDepStart[clinitN];
        while (k < clDepTop)
        {
            if (clDepOff[k] == nameOff)
            {
                return;
            }
            k += 1;
        }
        if (clDepTop >= clDepOff.length) { capHalt(Magic.bytes("MAXDEP-clinit"), clDepTop); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        clDepOff[clDepTop] = nameOff;
        clDepTop += 1;
    }

    /**
     * Cheap pre-check that a {@code <clinit>} won't trip a FATAL compiler gap (symbols.fail HALTS, so we can't
     * just try to compile and recover). Currently rejects {@code ldc}/{@code ldc_w} of a constant the Baseline
     * compiler doesn't handle (anything but Integer/Float/String) — the common gap in stock initializers
     * (Math, Arrays, ... ldc a Class/long/double/MethodType). Such a class is treated as unrunnable and left to
     * seeding. Extend as other fatal gaps surface. (Native-calling <clinit>s compile fine but wild-branch at
     * run time, so those stay in the name blocklist.)
     */
    private static boolean clinitCompilable(long code, int len)
    {
        // Allowlist: java/util/regex/Pattern.<clinit> ldc's Pattern.class for the `desiredAssertionStatus()`
        // assertions idiom (a tag-7 Class literal), which the generic ldc-tag gate below rejects. Its remaining
        // work (accept/lastAccept = new Node()/new LastNode(); putstatic) is runnable, and it MUST run -- compile
        // reads `lastAccept` (a static) as the node-chain terminal; a null lastAccept -> unlinked nodes -> NPE in
        // SliceNode.study. Class literals are supported (Milestone 0d) + desiredAssertionStatus() returns false.
        // Kept as a targeted allow (not a blanket tag-7 allow) because many other <clinit>s use the same idiom but
        // are intentionally skipped-and-seeded (String/ArraysSupport/Unsafe...) and hang if actually run.
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/regex/Pattern")))
        {
            return true;
        }
        // java/net/Socket.<clinit> ldc's InputStream.class/OutputStream.class (tag-7 Class literals, supported)
        // to bind its STATE/IN/OUT VarHandles via the overlaid MethodHandles/MhUtil/VarHandle shim. It is
        // runnable and MUST run -- an unbound STATE -> NullPointerException in getAndBitwiseOrState during connect.
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/net/Socket")))
        {
            return true;
        }
        // sun/nio/ch/NioSocketImpl.<clinit> ldc's NioSocketImpl.class for the desiredAssertionStatus() idiom
        // (it uses `assert`), which the tag-7 gate rejects. It MUST run: it binds `nd = new SocketDispatcher()`
        // (an unbound nd -> read/write dispatch on null) and disables assertions (desiredAssertionStatus()=false).
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("sun/nio/ch/NioSocketImpl")))
        {
            return true;
        }
        // java/net/StandardSocketOptions.<clinit> creates its option constants with Integer.class/Boolean.class
        // (tag-7 Class literals, supported). It MUST run: close() reads SO_LINGER, and an unbound (null) option
        // makes Net.getSocketOption(fd, null) NPE on name.type().
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/net/StandardSocketOptions")))
        {
            return true;
        }
        // java/util/jar/Attributes$Name.<clinit> ldc's Attributes$Name.class to hand to
        // CDS.initializeFromArchive (overlaid to a no-op here -- metal has no class-data archive), which trips
        // the tag-7 gate. It MUST run: it builds KNOWN_NAMES, the map every manifest attribute name is looked
        // up in, and Name.of dereferences it unguarded -- a skipped initializer is an NPE on the first
        // manifest header. The rest of the body is plain `new Name(...)` + HashMap + Map.copyOf.
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/jar/Attributes$Name")))
        {
            return true;
        }
        // java/util/ImmutableCollections.<clinit> ldc's its own class for CDS.initializeFromArchive (a no-op
        // overlay here) -- the tag-7 gate again. It MUST run: it seeds SALT32L (the hash mixer every MapN/SetN
        // probe uses) from System.nanoTime and builds the EMPTY_* singletons. Skipped, SALT32L stays 0 and the
        // EMPTY singletons stay null, and Map.copyOf(...) -- which java.util.jar.Attributes$Name's initializer
        // calls -- spins in MapN's probe loop instead of failing. Everything it needs is wired: nanoTime is a
        // provided native, CDS is overlaid.
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/ImmutableCollections")))
        {
            return true;
        }
        // java/util/HexFormat.<clinit> is the assertion idiom PLUS real work, which the rule below rejects --
        // and the rejection is SILENT, so HEX_FORMAT simply stayed null and HexFormat.of() returned null. That
        // rule's comment assumes such classes are clinitBlocked or seeded instead; HexFormat is neither, so it
        // fell in the gap between the two. Its body is runnable here: SharedSecrets.getJavaLangAccess() is
        // seeded with a MetalJavaLangAccess (seedJavaLangAccess), the digit tables are plain newarray/bastore,
        // and the singletons are `new HexFormat(...)` over String literals. `jla` is only dereferenced by the
        // FORMATTING methods (uncheckedNewStringWithLatin1Bytes), not by parseHex.
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/HexFormat")))
        {
            return true;
        }
        // A pervasive idiom is a <clinit> that ONLY disables assertions: `ldc X.class; invokevirtual
        // desiredAssertionStatus; ...; putstatic $assertionsDisabled` (many java.util.stream classes). Its lone
        // tag-7 ldc trips the gate below, so those <clinit>s were skipped -> $assertionsDisabled stayed false ->
        // assertions ENABLED -> an `assert` evaluates an uncompiled method and the null-vtable guard throws
        // AIOOBE. Allow it, but ONLY when the <clinit> is PURELY the assertion idiom: its sole tag-7 use is the
        // idiom AND it has no side-effecting op (new/newarray/invoke* other than desiredAssertionStatus). A
        // <clinit> that does the idiom PLUS real init (String/ArraysSupport/Unsafe...) is NOT let through here
        // (those are clinitBlocked/seeded anyway) -- running its extra init out of order regresses other classes.
        boolean sawAssertIdiom = false;
        boolean risky = false;
        int pc = 0;
        while (pc < len)
        {
            int op = u1(code + pc);
            int cpi = -1;
            if (op == 0x12)                              // ldc
            {
                cpi = u1(code + pc + 1);
            }
            else if (op == 0x13)                         // ldc_w
            {
                cpi = u2(code + pc + 1);
            }
            if (cpi >= 0)
            {
                int tag = gcpTag[cpi];
                if (tag == 7)                            // Class literal: OK only as the assertion idiom
                {
                    if (!isAssertionIdiom(code, pc))
                    {
                        return false;
                    }
                    sawAssertIdiom = true;
                }
                else if (tag != 3 && tag != 4 && tag != 8)   // not Integer / Float / String
                {
                    return false;
                }
            }
            else if (op == 0xbb || op == 0xbc || op == 0xbd || op == 0xc5     // new / newarray / anewarray / multianew
                    || op == 0xb8 || op == 0xb7 || op == 0xb9 || op == 0xba)  // invoke static/special/interface/dynamic
            {
                risky = true;
            }
            else if (op == 0xb6 && !isDesiredAssertionStatusCall(code, pc))   // invokevirtual != desiredAssertionStatus
            {
                risky = true;
            }
            pc += insnLen(code, pc);
        }
        // The idiom-allow is only safe if that's ALL the <clinit> does; if it also has side effects, running it
        // here (out of the normal init order) is what broke other classes -- reject so it stays skipped/seeded.
        if (sawAssertIdiom && risky)
        {
            return false;
        }
        return true;
    }

    /** True if the {@code ldc} at {@code pc} is immediately followed by {@code invokevirtual Class
     *  .desiredAssertionStatus} -- the javac assertions idiom. */
    private static boolean isAssertionIdiom(long code, int pc)
    {
        int nx = pc + insnLen(code, pc);
        return isDesiredAssertionStatusCall(code, nx);
    }

    /** True if the op at {@code pc} is {@code invokevirtual Class.desiredAssertionStatus}. */
    private static boolean isDesiredAssertionStatusCall(long code, int pc)
    {
        if (u1(code + pc) != 0xb6)                       // invokevirtual
        {
            return false;
        }
        int idx = u2(code + pc + 1);
        return utf8IsAtBase(gbase, mrefNameOff(idx), Magic.bytes("desiredAssertionStatus"));
    }

    /**
     * True if the current class's {@code <clinit>} must NOT be run — it calls natives / reads system properties
     * / needs JVM services absent on metal. We run every other class's initializer by default and seed only
     * these (provideKnownStatics / seedIntegerCache). Grows as new unrunnable stock initializers surface.
     */
    /** Entry addresses of this batch's compiled {@code <clinit>}s, in load order (run after patchRelocs). */
    private static long[] clinitEntry;   // compiled entry of initializer i, or 0 until clinitEntryOf compiles it
    private static long[] clinitCode;    // its BYTECODE (captured at load); non-zero == this slot is enqueued
    private static int[] clinitCodeLen;
    private static int[] clinitDescOff;
    private static int[] clinitStatic;
    private static int[] clinitLocals;
    private static int[] clinitPd;       // pd-blob index of each enqueued <clinit> (for dependency-ordered running)
    private static int clinitN;
    private static int clinitFdFirst;    // index of java/io/FileDescriptor's enqueued <clinit> (run first), or -1
    private static int clinitRunFrom;    // watermark: clinits [0,clinitRunFrom) already ran (incremental forName load)
    private static int[] clinitRan;      // 1 once initializer i has actually executed (batch sweep OR lazy barrier)
    // PRECISE per-<clinit> init dependencies: the classes the initializer BODY actively touches
    // (getstatic/putstatic/invokestatic owner, new/anewarray class, ldc Class literal), name Utf8 offsets in the
    // owning blob's gbase. Used by clinitDepBlocked INSTEAD of the whole-constant-pool dp table, whose field-type /
    // signature / enclosing-class refs create spurious cycles (e.g. an inner enum's cp names its outer class, so
    // the outer's <clinit> and the inner's appear mutually dependent). The <clinit> bytecode names exactly the
    // classes whose initialization it needs.
    private static int[] clDepOff;       // flat: dependency class name Utf8 offsets
    private static int[] clDepStart;     // per enqueued <clinit>: start index into clDepOff
    private static int[] clDepN;         // per enqueued <clinit>: dependency count
    private static int clDepTop;         // running top of clDepOff

    /** Run each enqueued {@code <clinit>} now that patchRelocs has fixed every cross-class call. */
    private static void runClinits()
    {
        // Run each <clinit> AFTER the <clinit>s of the classes it references (its CONSTANT_Class deps), so an
        // initializer that reads another class's static (e.g. ArraysSupport.<clinit> -> Unsafe.getUnsafe(),
        // whose theUnsafe is set by Unsafe.<clinit>) sees it initialized. Two-phase compiles bodies
        // superclass-first, NOT usage-dependency-first, so enqueue order is NOT a safe run order -- this
        // restores the single-phase dependency-first init order. Cycles (rare among initializers) are broken by
        // force-running the first pending, matching the loader's own cycle handling.
        boolean[] done = new boolean[clinitN];
        int remaining = clinitN;
        // Incremental load (Class.forName after launch): clinits [0,clinitRunFrom) ran in an earlier batch. Mark
        // them done so this pass runs ONLY the newly-enqueued initializers (they may still depend on the earlier
        // ones, which count as satisfied). Without this watermark a second loadAll would re-run every prior
        // <clinit>, double-initialising the whole running program.
        int w = 0;
        while (w < clinitRunFrom && w < clinitN)
        {
            done[w] = true;
            remaining -= 1;
            w += 1;
        }
        // FileDescriptor.<clinit> registers the JavaIOFileDescriptorAccess that the socket stack's own
        // initializers read back via SharedSecrets. That edge is registry-mediated, so it appears in no
        // class's bytecode; runClinits used to encode it as "run FileDescriptor first, unconditionally".
        // With initialization lazy, the same rule lives in initPrereq instead -- FileDescriptor initializes
        // when the first sun/nio/ch or java/net class does, which is both later and exactly as ordered. The
        // eager pre-run is kept only for a FileDescriptor that is NOT lazy (nothing today).
        if (clinitFdFirst >= 0 && clinitFdFirst < clinitN && !done[clinitFdFirst]
                && !lazyClinitGated(pdBase[clinitPd[clinitFdFirst]], pdNameOff[clinitPd[clinitFdFirst]]))
        {
            if (logClinit != 0)
            {
                Uart.write(Magic.bytes("  clinit(fd-first) java/io/FileDescriptor\n"));
            }
            long fdEntry = clinitEntryOf(clinitFdFirst);
            checkClinitEntry(fdEntry, clinitFdFirst);
            long unusedFd = Magic.call0(fdEntry);
            clinitRan[clinitFdFirst] = 1;
            done[clinitFdFirst] = true;
            remaining -= 1;
        }
        while (remaining > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < clinitN)
            {
                if (!done[i] && lazyClinitGated(pdBase[clinitPd[i]], pdNameOff[clinitPd[i]]))
                {
                    // JVMS 5.5 initialization-on-first-active-use: leave this one PENDING. The class stays at
                    // ST_INSTANTIATED and its initializer runs from the barrier in lazyCompile, the first time
                    // one of its methods is called -- or never, if the program never touches it.
                    done[i] = true;                      // bookkeeping only: clinitRan[i] stays 0
                    remaining -= 1;
                    progress = 1;
                }
                else if (!done[i] && !clinitDepBlocked(i, done))
                {
                    if (logClinit != 0)                  // #43: name each <clinit> as it runs (spot a hanging one)
                    {
                        int cpd = clinitPd[i];
                        Uart.write(Magic.bytes("  clinit "));
                        writeName(pdBase[cpd] + pdNameOff[cpd] + 2, u2(pdBase[cpd] + pdNameOff[cpd]));
                        Uart.putc(0x0A);
                    }
                    long entry = clinitEntryOf(i);   // compiled HERE, on the run that actually needs it
                    checkClinitEntry(entry, i);
                    long unused = Magic.call0(entry);
                    clinitRan[i] = 1;
                    done[i] = true;
                    remaining -= 1;
                    progress = 1;
                }
                i += 1;
            }
            if (progress == 0)                          // initializer cycle: force-run the first pending
            {
                int j = 0;
                while (j < clinitN && done[j])
                {
                    j += 1;
                }
                if (j < clinitN)
                {
                    long cyEntry = clinitEntryOf(j);
                    checkClinitEntry(cyEntry, j);
                    long unused = Magic.call0(cyEntry);
                    clinitRan[j] = 1;
                    done[j] = true;
                    remaining -= 1;
                }
                else
                {
                    remaining = 0;
                }
            }
        }
        clinitRunFrom = clinitN;                         // these have run; a later incremental batch starts past here
    }

    /**
     * The classes whose {@code <clinit>} is run on FIRST ACTIVE USE (JVMS 5.5) instead of at batch end.
     * Deliberately small to start: correctness rests on the trigger set being complete for the class, and
     * today the only barrier is {@link #lazyCompile} — a call to one of the class's own methods. That is
     * sound exactly when nothing reads the class's statics from OUTSIDE it, since a cross-class
     * {@code getstatic} compiles to a direct address load with no barrier to fire.
     *
     * <p>Both classes here satisfy that. {@code CleanerFactory.commonCleaner} is private and read only by
     * {@code cleaner()}; {@code ConditionalSpecialCasing}'s tables are private and read only by its own
     * {@code toUpperCaseEx}/{@code toLowerCaseEx}. They also demonstrate the two outcomes: NetDemo calls
     * {@code CleanerFactory.cleaner()} while setting up the socket (so its initializer runs late, on
     * demand), and never calls {@code ConditionalSpecialCasing} at all (so its initializer — which builds a
     * HashMap of Entry objects — never runs).
     */
    private static boolean lazyClinitGated(long base, int off)
    {
        return !clinitEagerKept(base, off);
    }

    /**
     * The classes whose {@code <clinit>} still runs at batch end, under {@link #runClinits}'
     * dependency ordering, instead of on first active use.
     *
     * <p>What is left here is the socket bring-up order, which is hand-tuned and NOT derivable from
     * bytecode. The load-bearing example is {@code FileDescriptor.<clinit>}: it registers the
     * {@code JavaIOFileDescriptorAccess} that {@code NioSocketImpl}/{@code NativeDispatcher} read back
     * through {@code SharedSecrets}, an edge no dependency scan can see because it runs through a
     * registry rather than a direct reference. `runClinits` special-cases it to run first; a barrier
     * firing on first use would not reproduce that. The dispatchers and `Socket` sit in the same
     * hand-ordered bring-up, and `Unsafe`/`ArraysSupport` supply array offsets that half of java.base
     * reads through statics.
     */
    private static boolean clinitEagerKept(long base, int off)
    {
        return false;                                   // nothing: every initializer now runs on first use
    }

    /**
     * The one initialization edge that is REAL but invisible to every automatic mechanism here.
     * {@code FileDescriptor.<clinit>} registers a {@code JavaIOFileDescriptorAccess} into
     * {@code SharedSecrets}; {@code NioSocketImpl} and the dispatchers read it back out at their own
     * {@code <clinit>} time. The dependency runs through a registry, so it appears in neither class's
     * bytecode — no dependency scan, and no compile-time barrier, can find it. {@code runClinits}
     * encodes it as "run FileDescriptor first"; this is the same rule for the lazy regime, and stating
     * it explicitly is what lets the whole socket stack initialize on demand rather than staying eager.
     */
    private static void initPrereq(int reg)
    {
        if (!utf8HasPrefix(clTab[reg].base, clTab[reg].nameOff, Magic.bytes("sun/nio/ch/"))
                && !utf8HasPrefix(clTab[reg].base, clTab[reg].nameOff, Magic.bytes("java/net/")))
        {
            return;
        }
        int fd = classRegByNameBytes(Magic.bytes("java/io/FileDescriptor"));
        if (fd >= 0 && fd != reg)
        {
            ensureClinit(fd);
        }
    }

    /** Class-registry index of the class with this exact name, or -1. */
    private static int classRegByNameBytes(byte[] name)
    {
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && utf8IsAtBase(clTab[r].base, clTab[r].nameOff, name))
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /**
     * Initialization barrier: run class {@code reg}'s pending {@code <clinit>} now, if it has one. Called
     * from {@link #lazyCompile} BEFORE the compile context is restored — the initializer's own calls can
     * re-enter lazyCompile, and each nested compile clobbers the {@code g*} context, so it must not run
     * inside ours.
     */
    private static void ensureClinit(int reg)
    {
        if (reg < 0 || clTab == null || clTab[reg] == null)
        {
            return;
        }
        if (clTab[reg].state >= RVMClass.ST_INITIALIZED)
        {
            drainCtorInit(reg);                         // already initialized -- but see drainCtorInit
            return;
        }
        if (!lazyClinitGated(clTab[reg].base, clTab[reg].nameOff))
        {
            // Only a gated class initializes here. Every other initializer stays under runClinits'
            // dependency-ordered control: a lazy compile can happen DURING runClinits (an initializer calling
            // a deferred method), and without this check the barrier would run whatever initializer happened
            // to be pending for that class, ahead of the ones it depends on.
            return;
        }
        initPrereq(reg);                                // the one edge no bytecode scan can see (see below)
        int i = 0;
        while (i < clinitN)
        {
            if (clinitRan[i] == 0 && clinitCode[i] != 0L && pdBase[clinitPd[i]] == clTab[reg].base)
            {
                clinitRan[i] = 1;                        // set BEFORE the call: the initializer may re-enter here
                Uart.write(Magic.bytes("  clinit-lazy "));
                printNameAt(clTab[reg].base, clTab[reg].nameOff);
                Uart.putc(0x0A);
                long entry = clinitEntryOf(i);           // compile it now -- this is the first (and only) run
                if (Heap.codeBlockFreeAt(entry) == 1)   // GUARD: about to call a SWEPT initializer.
                {                                                //   Name it here, where the class is in hand.
                    Uart.write(Magic.bytes("  CLINIT ENTRY WAS SWEPT: "));
                    printNameAt(clTab[reg].base, clTab[reg].nameOff);
                    Uart.write(Magic.bytes(" entry="));
                    VM.printHex(entry);
                    Uart.write(Magic.bytes(" idx="));
                    VM.printDec(i);
                    Uart.putc(0x0A);
                    VMGc.reportSweptPc(entry);
                    while (true) { Magic.wfe(); }
                }
                // BEFORE running it: initialize what the initializer ITSELF reads. Compiling the body just
                // recorded its cross-class getstatic/new sites (lzCompiling is false here, so they land in
                // the ctor-init table), and running it first would read those classes' statics while they
                // are still null. That is exactly what made an overlaid StandardCharsets useless -- its
                // initializer copied `sun.nio.cs.UTF_8.INSTANCE` into UTF_8 before sun/nio/cs/UTF_8 had
                // initialized, so the field came out null and `s.getBytes(UTF_8)` threw a bare NPE.
                drainCtorInit(reg);
                long unused = Magic.call0(entry);
                clTab[reg].state = RVMClass.ST_INITIALIZED;
                break;
            }
            i += 1;
        }
        drainCtorInit(reg);                             // ... and whatever its constructors actively use
    }

    // Classes an EAGERLY compiled constructor was seen to touch. <init> bodies compile at load time (see
    // notInit), so lzCompiling is false while they compile and the collection above never sees their
    // getstatic/new sites -- which is how `new ZipInputStream(...)` reached `UTF_8.INSTANCE` before
    // sun/nio/cs/UTF_8 had initialized, and read null. Recorded per OWNING class and fired when that class
    // is initialized, walking the superclass chain: `new C` runs C's constructor AND every super
    // constructor above it, so their active uses come due at the same moment.
    private static final boolean CTOR_TRACE = false;
    private static final int MAXCTORINIT = 8192;
    private static int[] ctorOwner;
    private static int[] ctorNeed;
    private static int ctorInitN;

    /** Record that the constructor being compiled (of the class currently in {@code g*}) uses {@code reg}. */
    private static void noteCtorInit(int reg)
    {
        if (ctorOwner == null || gbase == 0L)
        {
            return;
        }
        if (ctorInitN >= MAXCTORINIT)
        {
            capHalt(Magic.bytes("MAXCTORINIT"), ctorInitN);   // silently dropping an edge = a null static later
        }
        int owner = classRegByName(gThisNameOff);
        if (owner < 0 || owner == reg)
        {
            return;
        }
        int i = 0;
        while (i < ctorInitN)
        {
            if (ctorOwner[i] == owner && ctorNeed[i] == reg)
            {
                return;
            }
            i += 1;
        }
        ctorOwner[ctorInitN] = owner;
        ctorNeed[ctorInitN] = reg;
        ctorInitN += 1;
        if (CTOR_TRACE)
        {
            Uart.write(Magic.bytes("  ctorinit "));
            printNameAt(clTab[owner].base, clTab[owner].nameOff);
            Uart.write(Magic.bytes(" -> "));
            printNameAt(clTab[reg].base, clTab[reg].nameOff);
            Uart.putc(0x0A);
        }
    }

    /** Initialize what {@code reg}'s constructors -- and those of every superclass -- actively use. Entries
     *  are consumed as they fire, which is also what keeps the mutual recursion with {@link #ensureClinit}
     *  finite. */
    private static void drainCtorInit(int reg)
    {
        if (ctorOwner == null)
        {
            return;
        }
        int c = reg;
        while (c >= 0 && clTab != null && clTab[c] != null)
        {
            if (CTOR_TRACE)
            {
                Uart.write(Magic.bytes("  ctordrain "));
                printNameAt(clTab[c].base, clTab[c].nameOff);
                Uart.putc(0x0A);
            }
            int i = 0;
            while (i < ctorInitN)
            {
                if (ctorOwner[i] == c)
                {
                    int need = ctorNeed[i];
                    ctorOwner[i] = -1;                  // consume BEFORE recursing
                    ensureClinit(need);
                }
                i += 1;
            }
            c = clTab[c].superReg;
        }
    }

    // Classes a lazily-compiling method was seen to touch (cross-class getstatic/putstatic owner, `new`
    // target). Collected DURING the compile and initialized right after it, which is still strictly before
    // the method can run -- so those two triggers need no emitted runtime barrier at all. Recording is gated
    // on lzCompiling: a load-time compile (an initializer) must leave ordering to runClinits.
    private static final int MAXPENDINIT = 64;
    private static int[] lzInitReg;
    private static int lzInitN;
    private static boolean lzCompiling;

    /** Note that the method being lazily compiled touches class {@code reg}, so it must be initialized
     *  before that method runs (JVMS 5.5: a static access or a {@code new} is an active use). */
    static void noteInitNeeded(int reg)
    {
        // NOT gated on the class's own state: a class with no <clinit> reaches ST_INITIALIZED at load, yet
        // its constructors' active uses (recorded below) still come due the first time it is instantiated.
        if (reg < 0 || clTab == null || clTab[reg] == null)
        {
            return;
        }
        if (!lzCompiling)
        {
            noteCtorInit(reg);                          // a load-time <init> compile: due when its class is used
            return;
        }
        if (lzInitN >= MAXPENDINIT)
        {
            return;
        }
        int i = 0;
        while (i < lzInitN)
        {
            if (lzInitReg[i] == reg)
            {
                return;                                 // already noted for this compile
            }
            i += 1;
        }
        lzInitReg[lzInitN] = reg;
        lzInitN += 1;
    }

    /** Initialize everything the just-compiled method touches. Drains rather than iterates: an initializer
     *  can compile further methods, which can note more classes. */
    private static void drainPendingInit()
    {
        while (lzInitN > 0)
        {
            lzInitN -= 1;
            int reg = lzInitReg[lzInitN];
            ensureClinit(reg);
        }
    }

    /** Class-registry index of the class whose Type node is {@code type}, or -1. */
    private static int classRegByType(long type)
    {
        if (type == 0L)
        {
            return -1;
        }
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && clTab[r].type == type)
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /** Class-registry index of the class loaded from blob {@code base}, or -1. */
    private static int classRegByBlob(long base)
    {
        int r = 0;
        while (r < clCount)
        {
            if (clTab[r] != null && clTab[r].base == base)
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /** True if class {@code reg} has an enqueued {@code <clinit>} that has not executed yet. */
    private static boolean clinitPendingFor(int reg)
    {
        int i = 0;
        while (i < clinitN)
        {
            if (clinitRan[i] == 0 && clinitCode[i] != 0L && pdBase[clinitPd[i]] == clTab[reg].base)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** True if clinit {@code i} references a class whose own (still-unrun) {@code <clinit>} must run first. */
    private static boolean clinitDepBlocked(int i, boolean[] done)
    {
        int pd = clinitPd[i];
        if (pd < 0)
        {
            return false;
        }
        int e = clDepStart[i] + clDepN[i];
        int d = clDepStart[i];
        while (d < e)                                   // the initializer's PRECISE (bytecode) deps, not the whole cp
        {
            int jpd = findPdByName(pdBase[pd], clDepOff[d]);   // the referenced class's blob
            if (jpd >= 0 && jpd != pd)
            {
                // An eagerly-run initializer that touches a LAZY-INIT class is an active use of it, and
                // runClinits has already passed that class over. Initialize it here, which is exactly the
                // JVMS rule; otherwise this initializer would read its statics unset.
                ensureClinit(classRegByBlob(pdBase[jpd]));
                int k = 0;
                while (k < clinitN)                     // does that blob have a not-yet-run <clinit>?
                {
                    if (clinitPd[k] == jpd && !done[k])
                    {
                        return true;
                    }
                    k += 1;
                }
            }
            d += 1;
        }
        return false;
    }

    private static boolean clinitBlocked()
    {
        return utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/System"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Integer$IntegerCache"))
                // primitive wrappers: <clinit> sets TYPE = Class.getPrimitiveClass(...) (a native)
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Integer"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Long"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Float"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Double"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Character"))
                // NOTE: java/lang/Boolean is NOT blocked -- the metal OVERLAY replaces the stock class, and its
                // <clinit> only sets TRUE/FALSE (no native primitive TYPE), so it is safe (and needed) to run.
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Byte"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Short"))
                // M3 sockets: these <clinit>s call natives (initIDs/poll consts/SharedSecrets/iovMax). Skipping
                // is sound for the blocking connect/read/write/close path -- none of the skipped statics are
                // read there (IPv4-only, no poll consts, no IOV_MAX).
                // NOTE: java/io/FileDescriptor is NOT skipped -- its <clinit> registers the
                // JavaIOFileDescriptorAccess that NioSocketImpl.<clinit> reads via SharedSecrets; skipping it
                // left the access null, so getJavaIOFileDescriptorAccess() took the ensureClassInitialized ->
                // MethodHandles.lookup branch (denied) and trapped. Its 3 natives (initIDs/getHandle/getAppend)
                // are stubbed in nativeBuf, and runClinits() runs it before NioSocketImpl's (dependency order).
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("sun/nio/ch/Net"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("sun/nio/ch/IOUtil"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("sun/nio/ch/NativeThread"))
                // Inet4/6Address.<clinit> is just `init()` -- a native that caches JNI field IDs. We never
                // instantiate them (the InetAddress overlay returns a plain InetAddress), so their <clinit>s
                // are inert; skip them (they'd trap on the unwired native otherwise).
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/net/Inet4Address"))
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/net/Inet6Address"))
                // ContinuationSupport.<clinit> sets SUPPORTED = isSupported0() (a native). Metal has no
                // continuations, so skipping it leaves SUPPORTED=false -- which is correct, and makes
                // pinIfSupported/unpinIfSupported no-ops that never touch the denied Continuation class.
                // (Reached via collection iterators / Thread.isVirtual checks in several java.util closures.)
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("jdk/internal/vm/ContinuationSupport"))
                // jdk/internal/misc/VM.<clinit> = initialize() (a native setting savedProps/direct-memory/page
                // state). Metal has no such native; the reachable collection/reference paths don't read VM's
                // statics, so skip it (defaults are inert). Pulled transitively by reference/buffer machinery.
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("jdk/internal/misc/VM"))
                // Arrays$LegacyMergeSort.<clinit> reads the java.util.Arrays.useLegacyMergeSort property via
                // AccessController/GetBooleanAction (denied). Skipping leaves userRequested=false -- correct,
                // so Arrays.sort takes the modern TimSort path (reached by any object Arrays.sort/Collections.sort).
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/Arrays$LegacyMergeSort"))
                // java/util/stream/AbstractTask.<clinit> sets LEAF_TARGET = ForkJoinPool.getCommonPoolParallelism()
                // << 2. The common pool is never set up on metal (no ForkJoin), so getCommonPoolParallelism NPEs.
                // LEAF_TARGET is read ONLY by parallel task splitting (AbstractTask.compute/getLeafTarget), which a
                // SEQUENTIAL stream never reaches -- so skipping the <clinit> (LEAF_TARGET=0, never read) is sound.
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/stream/AbstractTask"))
                // java/util/stream/Tripwire.<clinit> sets ENABLED = Boolean.getBoolean(<debug property>) (reads
                // System props -> denied on metal). ENABLED is a debug-only assert flag, read solely in
                // `if (Tripwire.ENABLED) trip(...)`; skipping leaves it false (correct), never taken.
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/stream/Tripwire"))
                // java/util/Tripwire is the same debug-flag class as the stream one (ENABLED = Boolean.getBoolean),
                // pulled by the java.util.stream spliterator machinery. Same safe skip (ENABLED false, never taken).
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/Tripwire"))
                // java/util/zip/ZipOutputStream.<clinit> is one field: inhibitZip64 = Boolean.getBoolean(
                // "jdk.util.zip.inhibitZip64"), a system-property read (denied on metal). Skipping leaves it
                // FALSE, which is the correct default -- Zip64 stays enabled. Same shape as Tripwire above.
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/zip/ZipOutputStream"));
    }

    /** Compile+run a two-int-arg static method matching the seek key, with args {@code a,b}. */
    private static long load2(long bytes, int len, long a, long b)
    {
        parseConstPool(bytes, len);
        parseFields();
        long code = findMethod(bytes);
        if (code == 0L)
        {
            return 0L;
        }
        long buf = compile(code, gcodeLen, gFoundDescOff, gFoundStatic);   // two int args
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
        parseConstPool(bytes, len);
        parseFields();
        long code = findMethodByBytes(gbase, name, desc);
        if (code == 0L)
        {
            probeFound = 0;
            return 0L;
        }
        probeFound = 1;
        long buf = compile(code, gcodeLen, gFoundDescOff, gFoundStatic);
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
        entryPoint(VM.integerBytes, Magic.bytes("parseInt"), Magic.bytes("(Ljava/lang/String;I)I"));   // reachability-gated
        loadAll();
        parseIntBuf = globalMethodBuf(Magic.bytes("java/lang/Integer"), Magic.bytes("parseInt"), Magic.bytes("(Ljava/lang/String;I)I"));
    }


    /** The compiled buffer of a loaded method, by class/name/descriptor (from the global registry). */
    private static long globalMethodBuf(byte[] cls, byte[] name, byte[] desc)
    {
        int i = 0;
        while (i < rgCount)
        {
            if (utf8IsAtBase(rgTab[i].base, rgTab[i].classOff, cls)
                    && utf8IsAtBase(rgTab[i].base, rgTab[i].nameOff, name)
                    && utf8IsAtBase(rgTab[i].base, rgTab[i].descOff, desc))
            {
                return rgTab[i].buf;
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
        if (dlTab == null)
        {
            return 0L;
        }
        int k = 0;
        while (k < dlN)
        {
            DynLink d = dlTab[k];
            if (utf8IsAtBase(d.blob, d.classOff, cls)
                    && utf8IsAtBase(d.blob, d.nameOff, name)
                    && utf8IsAtBase(d.blob, d.descOff, desc))
            {
                return Magic.load64(d.cell);
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
    static void launch(byte[] className, byte[] argsLine)
    {
        if (VM.lazyCompileAddr == 0L)                       // dead at runtime (writer stashes the address);
        {                                                  //   makes lazyCompile reachable so the writer
            long u = lazyCompile(-1);                       //   compiles + stashes it for the 1b trampoline
        }
        if (VM.resolveLinkStubAddr == 0L)                   // same trick for the link-stub trampoline's target
        {
            long u = resolveLinkStub(-1);
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
        entryPoint(entry, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        // Force-load the exceptions the JIT's implicit checks throw without a bytecode `new` (null/bounds/div/
        // cast/negative-array). Otherwise a program that never NAMES them leaves them unloaded, so newExc gives
        // the thrown object a TIB of 0 -> uncatchable (instanceOf can't walk its type chain) and nameless in traces.
        pullClass(Magic.bytes("java/lang/NullPointerException"));
        pullClass(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"));
        pullClass(Magic.bytes("java/lang/ArithmeticException"));
        pullClass(Magic.bytes("java/lang/ClassCastException"));
        pullClass(Magic.bytes("java/lang/NegativeArraySizeException"));
        pullClass(Magic.bytes("java/lang/ArrayStoreException"));           // aastore covariant type mismatch
        pullClass(Magic.bytes("java/lang/InternalError"));                 // any other unexpected hardware trap
        // System.in's empty-stream seed needs this class present; nothing else guarantees it, and a program
        // that touches System.in would otherwise find null (see seedSystemIn). Tiny, and loaded once.
        pullClass(Magic.bytes("java/io/ByteArrayInputStream"));
        // Metal JavaLangAccess: seeded into SharedSecrets so EnumMap.getKeyUniverse (getEnumConstantsShared)
        // works (System.<clinit> which normally registers the JLA is skipped). Pulled always -- tiny, and only
        // reached when something builds an EnumMap (e.g. java.util.stream's StreamOpFlag).
        pullClass(Magic.bytes("jdk/internal/access/MetalJavaLangAccess"));
        // The atomic scalar wrappers are frequently referenced only by a class literal ({@code AtomicInteger
        // .class}, e.g. reflectively via a field updater); force-load them so the literal's Type/mirror + the
        // field registry (for getDeclaredField/newUpdater access checks) exist even when nothing instantiates them.
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicInteger"));
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicLong"));
        pullClass(Magic.bytes("java/util/concurrent/atomic/AtomicReference"));
        loadAll();                                          // reachability-gated JIT of the whole closure
        seedSystemStreams();                                // System.out/err -> UART
        seedSystemIn();                                     // System.in -> an empty stream (never null)
        seedNetExtendedOptions();                           // Net.EXTENDED_OPTIONS (close() SO_LINGER path)
        buildRunTramp();                                    // enable Thread.start(): the shared Runnable.run()
                                                            //   trampoline (needs Runnable loaded by loadAll;
                                                            //   harmless if the program spawns no threads)
        // Build the String[] argv AFTER loadAll: guestString needs the loaded String class's TIB, so the argv
        // MUST be built here, not before resetLoader() (that was the "args[i] throws" bug).
        long argv = buildArgv(argsLine);
        long buf = globalMethodBuf(className, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        if (buf == 0L)
        {
            Uart.write(Magic.bytes("launch: no main(String[]) in "));
            Uart.write(className);
            Uart.putc(0x0A);
            return;
        }
        long unused = Magic.call2(buf, argv, 0L);           // main(args) — x1 unused by a 1-arg static
        Uart.write(Magic.bytes("\n[main returned normally]\n"));
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
            Uart.putc(0x0A);
        }
    }

    /** Build a guest {@code String[]} from a space-separated {@code argsLine} (each token becomes a
     *  {@link #guestString}). Called from {@link #launch} after {@code loadAll} so String's TIB is valid. */
    private static long buildArgv(byte[] line)
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
        long argv = Heap.allocArray(n, 8);                  // String[n] (8-byte reference elements)
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
            Magic.store64(argv + 24L + idx * 8L, guestString(tok));
            idx += 1;
        }
        return argv;
    }

    /** Copy a class name into scratch and pull its raw {@code .class} bytes from the embedded classDir;
     *  register them ({@link #addBlob}) and return the bytes address, or 0 if the class is not embedded. */
    private static long pullClass(byte[] name)
    {
        long scratch = Heap.allocData(name.length + 8);
        int i = 0;
        while (i < name.length)
        {
            Magic.store8(scratch + i, name[i]);
            i += 1;
        }
        long bytes = VM.dirBytes(scratch, name.length);
        if (bytes == 0L)
        {
            return 0L;
        }
        addBlob(bytes, (int) VM.dirLen(scratch, name.length));
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
        VM.printHex(Magic.load64(Heap.CODE_PTR_CELL));
        Uart.write(Magic.bytes(" peak="));
        VM.printHex(Heap.codePeak);
        Uart.write(Magic.bytes(" zeroBound="));
        VM.printHex(codeHeapHigh);
        Uart.putc(0x0A);
    }


    /** A fresh guest {@code java/lang/String} (LATIN1) holding {@code ascii} (requires String loaded). */
    static long guestString(byte[] ascii)
    {
        long arr = Heap.allocArray(ascii.length, 1);    // a real byte[] (elem size 1, length@16, data@24)
        long bt = byteArrayTib();                        // type value as [B so String.getBytes()'s `checkcast [B`
        if (bt != 0L)                                    // resolves (else it spins forever -- e.g. println(arg))
        {
            Magic.store64(arr + ObjectModel.TIB_OFFSET, bt);
        }
        int i = 0;
        while (i < ascii.length)
        {
            Magic.store8(arr + 24L + i, ascii[i]);
            i += 1;
        }
        long obj = Heap.alloc(stringSize());
        Magic.store64(obj + 0L, stringTib());           // wrap it as a guest java/lang/String
        Magic.store64(obj + 16L, arr);                  // value field (offset 16); coder@24 stays 0 = LATIN1
        return obj;
    }

    // ----- M-B: Thread.getStackTrace() -> StackTraceElement[] (walk the frame chain, materialise elements) -----

    /** A guest java/lang/String from a length-prefixed Utf8 at {@code base+off} (u2 length, then bytes); 0 if base 0. */
    static long guestStringUtf8(long base, int off)
    {
        if (base == 0L)
        {
            return 0L;
        }
        int len = u2(base + off);
        long arr = Heap.allocArray(len, 1);
        long bt = byteArrayTib();
        if (bt != 0L)
        {
            Magic.store64(arr + ObjectModel.TIB_OFFSET, bt);
        }
        int i = 0;
        while (i < len)
        {
            Magic.store8(arr + 24L + i, (byte) u1(base + off + 2L + i));
            i += 1;
        }
        long obj = Heap.alloc(stringSize());
        Magic.store64(obj + 0L, stringTib());
        Magic.store64(obj + 16L, arr);
        return obj;
    }

    /** TIB of {@code java/lang/StackTraceElement} (0 if not loaded). */
    private static long steTib()
    {
        int i = classIndexByName(Magic.bytes("java/lang/StackTraceElement"));
        return i >= 0 ? clTab[i].tib : 0L;
    }

    /**
     * Build one {@code StackTraceElement} for machine PC {@code pc}: resolve its method (a JIT'd method, a
     * {@code <clinit>}, or a writer-compiled image method) to declaringClass / methodName / fileName / line and
     * write those four fields at their slot offsets. Mirrors {@link #printFrameAt}'s lookup, but materialises an
     * object instead of printing. Image frames carry the combined {@code owner/Class.method} as the method name
     * (they sit above the guest frames the caller inspects, so their exact split doesn't matter).
     */
    private static long frameToElement(long pc, long steTib)
    {
        long bestBuf = 0L;
        int bestReg = -1;
        int bestClin = -1;
        int i = 0;
        while (i < rgCount)
        {
            if (rgTab[i].buf != 0L && rgTab[i].buf <= pc && rgTab[i].buf > bestBuf
                    && inSameCodeBlock(rgTab[i].buf, pc)) { bestBuf = rgTab[i].buf; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < clinitN)
        {
            if (clinitEntry[c] != 0L && clinitEntry[c] <= pc && clinitEntry[c] > bestBuf
                    && inSameCodeBlock(clinitEntry[c], pc)) { bestBuf = clinitEntry[c]; bestClin = c; bestReg = -1; }
            c += 1;
        }
        long clsStr = 0L;
        long methStr = 0L;
        long fileStr = 0L;
        long line = 0L;
        if (bestReg >= 0)
        {
            clsStr = guestStringUtf8(rgTab[bestReg].base, rgTab[bestReg].classOff);
            methStr = guestStringUtf8(rgTab[bestReg].base, rgTab[bestReg].nameOff);
            fileStr = guestStringUtf8(rgTab[bestReg].src, 0);
            line = lineAtOffset(rgTab[bestReg].line, (int) ((pc - bestBuf) >> 2));
        }
        else if (bestClin >= 0)
        {
            int pd = clinitPd[bestClin];
            clsStr = guestStringUtf8(pdBase[pd], pdNameOff[pd]);
            methStr = guestString(Magic.bytes("<clinit>"));
        }
        else
        {
            long tab = VM.imageSymTable;                    // writer-compiled VM/board method: image symbol table
            long n = VM.imageSymCount;
            long k = 0;
            while (k < n)
            {
                long e = tab + k * 40L;
                long start = Magic.load64(e);
                if (pc >= start && pc < Magic.load64(e + 8L))
                {
                    methStr = guestStringUtf8(Magic.load64(e + 16L), 0);   // "owner/Class.method" (combined)
                    fileStr = guestStringUtf8(Magic.load64(e + 24L), 0);
                    line = lineAtOffset(Magic.load64(e + 32L), (int) ((pc - start) >> 2));
                    k = n;
                }
                else
                {
                    k += 1;
                }
            }
        }
        long ste = Heap.alloc(48);                          // header(16) + 4 field slots
        Magic.store64(ste + 0L, steTib);
        Magic.store64(ste + 16L, clsStr);
        Magic.store64(ste + 24L, methStr);
        Magic.store64(ste + 32L, fileStr);
        Magic.store64(ste + 40L, line);
        return ste;
    }

    /**
     * Walk the frame chain from ({@code pc},{@code sp}) with {@link VM#frameSizeAt}: record each frame's PC into
     * {@code arr} (a StackTraceElement[]) if non-0, else just count. Stops at the run-trampoline (so a spawned
     * thread's bottom frame is run(), not the trampoline). {@code savedX30} (non-0 only for a parked thread) lets
     * the FIRST frame step over a leaf {@code taskYield} whose caller return is in x30, not on the stack.
     */
    private static int traceWalk(long pc, long sp, long savedX30, long arr, long steTib)
    {
        long cpc = pc;
        long csp = sp;
        boolean first = true;
        int n = 0;
        while (n < 64 && cpc > 0x1000L)
        {
            if (VM.runTrampAddr != 0L && cpc >= VM.runTrampAddr && cpc < VM.runTrampAddr + 64L)
            {
                break;                                     // run-trampoline: end the trace at run()
            }
            if (arr != 0L)
            {
                Magic.store64(arr + 24L + n * 8L, frameToElement(cpc, steTib));
            }
            n += 1;
            long cfs = VM.frameSizeAt(cpc);
            if (cfs == 0L)
            {
                if (first && savedX30 > 0x1000L)
                {
                    cpc = savedX30 - 4L;                   // leaf top frame (taskYield): caller return is x30, SP unchanged
                    first = false;
                    continue;
                }
                break;                                     // top of the resolvable stack
            }
            cpc = Magic.load64(csp) - 4L;                  // caller's return address (the call site)
            csp += cfs;
            first = false;
        }
        return n;
    }

    /**
     * Materialise a {@code StackTraceElement[]} from a Throwable's INLINE backtrace ({@code bt0..bt7} at
     * {@code obj+16..+72}, 0-terminated), which {@link VM#unwind} fills at THROW time.
     *
     * <p>An exception that has been constructed but not yet thrown therefore yields an EMPTY array, which is
     * correct rather than merely convenient: stock semantics would capture at construction, and joe-ng
     * deliberately captures at throw so that propagation allocates nothing. Callers that walk the result are
     * fine with empty -- JUnit's {@code maybeTrimStackTrace}, the reason this exists, loops over the array and
     * returns early when it finds no match.
     */
    static long traceFromThrowable(long exc)
    {
        if (exc == 0L)
        {
            return 0L;
        }
        long tib = steTib();
        int n = 0;
        while (n < 8 && Magic.load64(exc + 16L + (long) n * 8L) != 0L)
        {
            n += 1;
        }
        long arr = Heap.allocArray(n, 8);
        if (tib != 0L)
        {
            Magic.store64(arr + ObjectModel.TIB_OFFSET, refArrayTib(Magic.load64(tib)));
        }
        int i = 0;
        while (i < n)
        {
            Magic.store64(arr + 24L + (long) i * 8L,
                    frameToElement(Magic.load64(exc + 16L + (long) i * 8L), tib));
            i += 1;
        }
        return arr;
    }

    /** Materialise a {@code StackTraceElement[]} for the frame chain at ({@code pc},{@code sp}). */
    static long buildTrace(long pc, long sp, long savedX30)
    {
        long tib = steTib();
        int count = traceWalk(pc, sp, savedX30, 0L, tib);  // pass 1: count
        long arr = Heap.allocArray(count, 8);              // StackTraceElement[count]
        if (tib != 0L)
        {
            // Give the array the [LStackTraceElement; TIB (element Type = TIB[0]) so a `checkcast
            // [LStackTraceElement;` on a Map.Entry.getValue() result passes instead of walking a null Type.
            Magic.store64(arr + ObjectModel.TIB_OFFSET, refArrayTib(Magic.load64(tib)));
        }
        traceWalk(pc, sp, savedX30, arr, tib);             // pass 2: fill
        return arr;
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
    private static long findMethodByBytes(long base, byte[] name, byte[] desc)
    {
        long p = gp;
        gMethodsStart = p;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (utf8IsAtBase(base, gcp[u2(p + 2)], name) && utf8IsAtBase(base, gcp[u2(p + 4)], desc))
            {
                long code = findCode(base, p + 8, attrs);
                if (code != 0L)
                {
                    gFoundDescOff = gcp[u2(p + 4)];
                    gFoundStatic = (access & 0x0008) != 0 ? 1 : 0;
                    return code;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0L;
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
        long code = findMethod(VM.guestBytes);
        if (code == 0L)
        {
            return 0;
        }
        return (int) Magic.call0(bufOf(code));
    }

    /**
     * Name the class the loader is compiling right now, for the fault reporter. A wild pointer inside a
     * shared helper ({@code u1}, {@code utf8EqAt}) says nothing about which class's constant pool was being
     * walked, and that is the first question worth answering.
     */
    static void printCurrentClass()
    {
        if (gbase != 0L && gThisNameOff != 0)
        {
            printNameAt(gbase, gThisNameOff);
        }
        else
        {
            Uart.write(Magic.bytes("<none>"));
        }
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
            long src = CODE_ROOTS + i * CODE_ROOT_ENTRY;
            long owner = Magic.load64(src + 8L);
            if (owner < lo || owner >= hi)
            {
                long dst = CODE_ROOTS + kept * CODE_ROOT_ENTRY;
                Magic.store64(dst, Magic.load64(src));
                Magic.store64(dst + 8L, owner);
                kept += 1;
            }
            i += 1;
        }
        codeRootN = kept;
    }

    /** Lazy-method count and the dispatch cell of entry {@code i} (0 if it has none), for CodeCompact. */
    static int lazyCount()
    {
        return lzN;
    }

    static long lazyCellAt(int i)
    {
        return lzTab == null || i < 0 || i >= lzTab.length || lzTab[i] == null ? 0L : lzTab[i].slot;
    }

    /** Registered class-record count, for {@link CodeCompact}'s precise reference enumeration. */
    static int classRegCount()
    {
        return clCount;
    }

    /** The class record at {@code i}, or null. Package-visible for the same reason. */
    static RVMClass classRegAt(int i)
    {
        return clTab == null || i < 0 || i >= clTab.length ? null : clTab[i];
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
        if (relocRecording == 0)
        {
            return;
        }
        CodeEdges.note(site, target);
    }

    /** Record a heap address being baked into the instruction stream. Image and statics addresses are
     *  permanent and need no root; only the managed heap is collectable. */
    static void noteCodeRoot(long addr)
    {
        if (relocRecording == 0)
        {
            return;    // the sizing pass emits the same addresses to a throwaway buffer; only the real
        }              // emit pass produces code that will run, and it is the one relocRecording marks
        if (addr < Heap.BASE || addr >= 0x1000_0000L)
        {
            return;
        }
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
        Magic.store64(CODE_ROOTS + codeRootN * CODE_ROOT_ENTRY, addr);
        Magic.store64(CODE_ROOTS + codeRootN * CODE_ROOT_ENTRY + 8L, codeRootOwner);
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
    private static final long DEMAND_ZERO_SPAN = 0x0180_0000L;   // 24 MiB

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
     *  costs in data and code is the number this arc has to fit inside the arena without a rewind. */
    static final boolean LIFETIME_TRACE = true;
    private static int  batchN;
    private static long batchDataTotal;
    private static long batchCodeTotal;
    private static long batchDataPeak;
    private static long batchCodePeak;

    private static void resetLoader()
    {
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
                demandHeapMark = Magic.load64(Heap.PTR_CELL);   // watermark: heap level after the philosophers demo
                // Code-arena watermark, captured at the same moment: everything below it (the boot-time
                // vector table + scheduler switch stubs, the original run-trampoline, the pre-mark demos'
                // code) is permanent; everything a later batch compiles above it dies with the batch --
                // the demand model already assumes nothing but the image survives across batches (the data
                // rewind kills a batch's objects), so its code is dead too. Pre-zero a span above the mark
                // for the same cold-DRAM reason as the data heap.
                codeHeapMark = Magic.load64(Heap.CODE_PTR_CELL);
                long cz = codeHeapMark;
                long czEnd = codeHeapMark + CODE_ZERO_SPAN;
                if (czEnd > Heap.CODE_LIMIT) { czEnd = Heap.CODE_LIMIT; }
                while (cz < czEnd)
                {
                    Magic.store64(cz, 0L);
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
                    Magic.store64(z0, 0L);
                    z0 += 8L;
                }
                demandHeapHigh = zEnd;                           // record it so the else-branch keeps it re-zeroed
            }
            else
            {
                long usedData = Magic.load64(Heap.PTR_CELL) - demandHeapMark;   // this batch's footprint, before
                long usedCode = Magic.load64(Heap.CODE_PTR_CELL) - codeHeapMark;//   anything is reclaimed
                batchN += 1;
                batchDataTotal += usedData;
                batchCodeTotal += usedCode;
                if (usedData > batchDataPeak) { batchDataPeak = usedData; }
                if (usedCode > batchCodePeak) { batchCodePeak = usedCode; }
                if (LIFETIME_TRACE != false)
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
                long oldPtr = Magic.load64(Heap.PTR_CELL);      // ZERO all heap ever used above the mark, so a reused
                if (oldPtr > demandHeapHigh)                     // block never carries a PRIOR batch's code bytes: an
                {                                                // uninitialized/OOB slot then reads 0 (a caught blr 0)
                    demandHeapHigh = oldPtr;                     // instead of stale code -> a layout-dependent wild branch.
                }                                                // Zero up to the HIGH-WATER mark (not just the previous
                if (RECLAIM_BY_GC == false)                      // the arc's switch: rewind, or let the
                {                                                //   collector decide what is still live
                    long z = demandHeapMark;                     // batch) so a bigger batch's OOB reads land on 0 too.
                    while (z < demandHeapHigh)
                    {
                        Magic.store64(z, 0L);
                        z += 8L;
                    }
                    Magic.store64(Heap.PTR_CELL, demandHeapMark);   // reclaim the previous demo's dead code + objects
                    Magic.store64(Heap.FREE_CELL, 0L);              // core 0's free-list entries are above it again
                }
                // Code-arena rewind (same batch-death model): zero every code byte the previous batches wrote
                // above the mark -- a stale JIT buffer executed through a dangling pointer is the worst kind of
                // wild branch (zeros decode as a caught udf instead) -- flush the zeroes past the I-cache, and
                // rewind the bump. A batch that spawns threads must (already) rebuild the run-trampoline; its
                // compiled methods die with its registries exactly like its heap objects.
                if (RECLAIM_CODE_BY_GC == false)
                {
                    long ocPtr = Magic.load64(Heap.CODE_PTR_CELL);
                    if (ocPtr > codeHeapHigh)
                    {
                        codeHeapHigh = ocPtr;
                    }
                    long cz = codeHeapMark;
                    while (cz < codeHeapHigh)
                    {
                        Magic.store64(cz, 0L);
                        cz += 8L;
                    }
                    Heap.publishCode(codeHeapMark, codeHeapHigh);   // drop stale I-cache lines over dead code
                    Magic.store64(Heap.CODE_PTR_CELL, codeHeapMark);
                    codeRootN = 0;                                  // the code those roots belonged to is gone
                }
                if (RECLAIM_CODE_BY_GC == false)
                {
                    VM.dropJitTablesAbove(codeHeapMark);        // frame/handler entries for the dead code would
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
                    if (VM.taskThreadObj[tt] >= demandHeapMark)
                    {
                        VM.taskThreadObj[tt] = 0L;
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
                dlTab = null;
                dlN = 0;
                lzTab = null;
                lzN = 0;
            }
        }
        litAnchor = null;                               // per-batch GC anchor for interned literals: the rewind
        litAnchorN = 0;                                 //   reclaimed both the literals and the anchor array
        VM.byteArrayTibCache = 0L;                      // the batch's [B TIB was just reclaimed with its heap
        rgTab = new RVMMethod[MAXREG];
        rgCount = 0;
        sgTab = new RVMField[MAXREG];
        sgCount = 0;
        relocRecording = 0;
        lkCount = 0;                                    // link stubs name utf8 inside THIS batch's blobs
        linkTrampAddr = 0L;                             //   (and the trampoline lives in reclaimable code)
        rcAddr = new long[MAXRELOC];
        rcBase = new long[MAXRELOC];
        rcTail = new int[MAXRELOC];
        rcClass = new int[MAXRELOC];
        rcName = new int[MAXRELOC];
        rcDesc = new int[MAXRELOC];
        rcCount = 0;
        rsAddr = new long[MAXRELOC];
        rsBase = new long[MAXRELOC];
        rsReg = new int[MAXRELOC];
        rsClass = new int[MAXRELOC];
        rsName = new int[MAXRELOC];
        rsCount = 0;
        clinitEntry = new long[MAXBLOB];
        clinitCode = new long[MAXBLOB];
        clinitCodeLen = new int[MAXBLOB];
        clinitDescOff = new int[MAXBLOB];
        clinitStatic = new int[MAXBLOB];
        clinitLocals = new int[MAXBLOB];
        clinitPd = new int[MAXBLOB];
        clinitRan = new int[MAXBLOB];
        lzInitReg = new int[MAXPENDINIT];
        ctorOwner = new int[MAXCTORINIT];
        ctorNeed = new int[MAXCTORINIT];
        ctorInitN = 0;
        lzInitN = 0;
        clDepOff = new int[MAXDEP];
        clDepStart = new int[MAXBLOB];
        clDepN = new int[MAXBLOB];
        clDepTop = 0;
        clinitN = 0;
        clinitFdFirst = -1;
        clinitRunFrom = 0;
        primArrTib = new long[12];         // array Types live in the (reclaimed) demand heap: recreate per batch
        primArrAdopted = new boolean[12];  // (adopted image TIBs are permanent but re-adopting is idempotent)
        refArrElem = new long[64];
        refArrTib = new long[64];
        refArrAdopted = new boolean[64];
        refArrCount = 0;
        mirType = new long[256];           // Class mirrors: also per batch (reclaimed heap)
        mirObj = new long[256];
        mirN = 0;
        classTibCache = 0L;
        clTab = new RVMClass[MAXCLASS];
        clCount = 0;
        clIfaceReg = new int[MAXCLASS * MAX_DIRECT_IF];
        clIfaceRegN = new int[MAXCLASS];
        ifClosureBuf = new int[MAXIFM];
        instImaps = new long[MAXIMAP];
        instImapReg = new int[MAXIMAP];
        instImapN = 0;
        lambdaTibRoots = new long[MAXLAMBDATIB];
        lambdaTibRootN = 0;
        pcBase = new long[MAXPARSECACHE];
        pcBytes = new byte[MAXPARSECACHE][];
        pcCp = new int[MAXPARSECACHE][];
        pcCpTag = new int[MAXPARSECACHE][];
        pcLitObj = new long[MAXPARSECACHE][];
        pcCpCount = new int[MAXPARSECACHE];
        pcAfterCp = new int[MAXPARSECACHE];
        pcN = 0;
        fldTab = new RVMField[MAXFIELD];
        fldCount = 0;
        vtClassBase = new long[MAXVT];
        vtClassOff = new int[MAXVT];
        vtNameBase = new long[MAXVT];
        vtNameOff = new int[MAXVT];
        vtDescOff = new int[MAXVT];
        vtSlot = new int[MAXVT];
        vtBuf = new long[MAXVT];
        vtCount = 0;
        gvTab = new VtSlot[MAXMV];
        int gvi = 0;
        while (gvi < MAXMV)                             // pre-fill: slots are overwritten (reused) per class
        {
            gvTab[gvi] = new VtSlot();
            gvi += 1;
        }
        ifBase = new long[MAXIFM];
        ifNameOff = new int[MAXIFM];
        ifDescOff = new int[MAXIFM];
        ifCount = 0;
        pdBase = new long[MAXBLOB];
        pdNameOff = new int[MAXBLOB];
        pdDone = new int[MAXBLOB];
        pdDoneB = new int[MAXBLOB];
        pdNeedsString = new boolean[MAXBLOB];
        pdSuperOff = new int[MAXBLOB];
        pdIfOff = new int[MAXBLOB * MAX_DIRECT_IF];
        pdIfN = new int[MAXBLOB];
        pdCount = 0;
        collectedTab = new long[REACHTAB];
        pdPendTo = new int[MAXBLOB];
        pdVirtTo = new int[MAXBLOB];
        pdPendEpoch = new int[MAXBLOB];
        pdVirtEpoch = new int[MAXBLOB];
        pdDfltTo = new int[MAXBLOB];
        pdSeeded = new int[MAXBLOB];
        pdSeedC = new int[MAXBLOB];
        pendPullTo = 0;
        pnIndexed = 0;
        pnBucket = null;                                 // rebuilt by the next probeAll; blobs are all new
        dpOwner = new int[MAXDEP];
        dpOff = new int[MAXDEP];
        pdLen = new int[MAXBLOB];
        gEntryBlob = 0L;                                 // no reachability mark unless a caller sets an entry
        gRootBlob = 0L;
        gStubBlob = 0L;
        markActive = 0;
        reachCode = new long[MAXREACH];
        reachTab = new long[REACHTAB];
        reachN = 0;
        VM.jitFrameCount = 0L;                           // a demo's JIT'd frames/handlers are dead once it returns;
        VM.jitLocalCount = 0L;                           // reset the local table IN LOCKSTEP with the frame table (parallel
                                                         // {codeStart,codeEnd,regLocals}); addJitFrame guards only on
                                                         // jitFrameCount, so a stale jitLocalCount would keep growing past
                                                         // JIT_FRAME_MAX and overrun jitLocalTable into adjacent heap.
        VM.jitHandlerCount = 0L;                         // resetting per batch stops them accumulating past the caps
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
                VM.printHex(Magic.load64(Heap.PTR_CELL) - demandHeapMark);   // heap grown above the watermark
                Uart.write(Magic.bytes(" freedThisPass="));
                VM.printHex(VM.reclaimed);
                Uart.write(Magic.bytes(" live="));      // the survivors -- if this stays small, the heap's
                VM.printHex(VMGc.liveBytes);            //   high water mark is garbage, not retention
                Uart.write(Magic.bytes(" codeOnly="));  // blocks kept alive ONLY by a code immediate: the
                VM.printHex(VMGc.codeOnly);             //   hazard the batch rewind has been covering for
                Uart.write(Magic.bytes(" codeRoots="));
                VM.printHex(Loader.codeRootN);
                Uart.write(Magic.bytes(" codeArena="));  // how much of the arena is actually in use
                VM.printHex(Magic.load64(Heap.CODE_PTR_CELL) - codeHeapMark);
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
                VM.printHex(Magic.load64(Heap.LARGE_PTR_CELL) - Heap.LARGE_BASE);
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

    // ----- reachable-method compilation (M-B) --------------------------------
    // When a caller marks an entry point, loadAll compiles only the methods reachable from it (a call-graph
    // closure over the loaded blobs) instead of every method of every class. This lets a big real java.base
    // class load through the normal closure path without choking on its unreachable methods (toString,
    // parseInt's String.format paths, ...). Without an entry set, everything compiles (unchanged behaviour).
    private static final int MAXREACH = 8192;
    private static long gEntryBlob;                      // entry method's blob (0 => mark disabled)
    private static long gRootBlob;                       // a defineClass'd blob: EVERY method is a root (see rootBlob)
    private static long gStubBlob;                       // an incrementally loaded blob: STUB its virtuals (see stubBlob)
    private static byte[] gEntryName, gEntryDesc;        // entry method name/descriptor
    private static int markActive;                       // 1 once markReachable has run (compileClass then filters)
    private static long[] reachCode;                     // bytecode addresses of the reachable methods
    private static final int REACHTAB = 16384;           // power of two > 2*MAXREACH: the set never fills
    private static long[] reachTab;                      // ... and the same addresses as an open-addressed set,
                                                         //   because collectBlob asks isReach once per method of
                                                         //   every blob, every round -- a linear scan makes that
                                                         //   blobs x methods x reachN
    private static int reachN;

    /** Declare the method loadAll should treat as the reachability root (call before addBlob/loadAll). */
    static void entryPoint(long blobBytes, byte[] name, byte[] desc)
    {
        gEntryBlob = blobBytes;
        gEntryName = name;
        gEntryDesc = desc;
    }

    /**
     * Mark a whole blob as a reachability ROOT: every method it declares is seeded, and the class counts as
     * instantiated. This is what {@code ClassLoader.defineClass} means -- the bytes come from the program, so
     * nothing already loaded calls into the class and RTA has no way to see who will. Marked from the entry
     * method only, the closure reaches NONE of it, {@link #compileClass} prunes every method, and
     * {@link #fillTib} then fills a vtable of zeros: the class loads, reflection into it works (that resolves
     * through the method registry), and the first virtual call inside it hits the null-vtable guard as a
     * bare {@code ArrayIndexOutOfBoundsException}.
     */
    static void rootBlob(long blobBytes)
    {
        gRootBlob = blobBytes;
    }

    /**
     * Give a blob's own virtual methods DEFERRAL STUBS even where reachability analysis prunes them, so its
     * vtable has no holes — without marking them reachable, which is the part that matters.
     *
     * <p>This is the weaker sibling of {@link #rootBlob}, and the difference is the whole point.
     * {@code Class.forName} loads by NAME into a live program, so RTA marks nothing in the class (it seeds
     * from a {@code <clinit>} most classes do not have) and {@link #compileClass} skips every method — leaving
     * the class's own vtable slots 0 while REFLECTION still worked, because that resolves through the method
     * registry and compiles on demand. A virtual call inside such a class then hit the null-vtable guard.
     *
     * <p>Seeding them reachable instead, as {@code rootBlob} does, is what an earlier attempt did and it
     * "pulled a huge closure into the 2nd (incremental) batch and corrupted the heap" — an arbitrary
     * java.base class named at runtime drags in everything it mentions. A deferral stub pulls NOTHING: it is
     * a few instructions that route the first call into {@link #lazyCompile}, which compiles the body then
     * and resolves its own relocs, exactly as reflection's on-demand path already does.
     */
    static void stubBlob(long blobBytes)
    {
        gStubBlob = blobBytes;
    }

    /**
     * Parse a blob's constant pool and advance {@code gp}/{@code gMethodsStart} to its methods table (and set
     * {@code gThisNameOff}) — the lightweight parse the reachability mark needs, without {@link #parseFields}'
     * field-layout + statics allocation.
     */
    private static void parseForMethods(long base, int len)
    {
        parseConstPool(base, len);
        long p = gp;
        gThisNameOff = gcp[u2(gbase + gcp[u2(p + 2)])];   // this_class -> name (for class-qualified call matching)
        p += 6;                                           // access_flags, this_class, super_class
        p += 2 + u2(p) * 2;                               // interfaces
        int fcount = u2(p);
        p += 2;
        int f = 0;
        while (f < fcount)
        {
            p = skipAttributes(p + 8, u2(p + 6));         // field: access(2) name(2) desc(2) count(2), then attributes
            f += 1;
        }
        gMethodsStart = p;
        gp = p;
    }

    /** Record that {@code code}'s refs have been pended; false if they already were. */
    private static boolean addCollected(long code)
    {
        int i = (int) ((code >> 3) * 0x9E3779B1L) & (REACHTAB - 1);
        while (collectedTab[i] != 0L && collectedTab[i] != code)
        {
            i = (i + 1) & (REACHTAB - 1);
        }
        if (collectedTab[i] == code)
        {
            return false;
        }
        collectedTab[i] = code;
        return true;
    }

    /**
     * Slot for {@code code} in {@link #reachTab}: its index if present, else the first free slot. Open
     * addressing with linear probing over a power-of-two table twice {@code MAXREACH}, so it can never fill
     * and the probe always terminates. Addresses are 8-aligned, so the low three bits carry nothing --
     * multiply before masking or every key lands in one eighth of the table.
     */
    private static int reachSlot(long code)
    {
        int i = (int) ((code >> 3) * 0x9E3779B1L) & (REACHTAB - 1);
        while (reachTab[i] != 0L && reachTab[i] != code)
        {
            i = (i + 1) & (REACHTAB - 1);
        }
        return i;
    }

    /** True if {@code code} (a method's bytecode address) was marked reachable. */
    private static boolean isReach(long code)
    {
        return reachTab[reachSlot(code)] == code;
    }

    /** Add {@code code} to the reachable set if new; returns true if it was newly added. */
    private static boolean addReach(long code)
    {
        if (code == 0L || reachN >= MAXREACH)
        {
            return false;
        }
        int i = reachSlot(code);
        if (reachTab[i] == code)
        {
            return false;
        }
        reachTab[i] = code;
        reachCode[reachN] = code;
        reachN += 1;
        return true;
    }

    /**
     * Mark every method reachable from the entry point: seed the entry (plus every {@code run()V}, which the
     * thread trampoline enters outside the call graph), then repeatedly follow each reachable method's calls
     * — invokestatic/special to the named class's method, invokevirtual/interface to that name+descriptor in
     * every loaded class (a receiver could be any of them) — until the set stops growing.
     */
    private static final int MAXPEND = 49152;
    private static final int PEND_PULL = 2;              // kind: pull the class only (field/type ref; no method)
    private static long[] pendBase;                      // call-site refs of the round's reachable methods:
    private static int[] pendClass, pendName, pendDesc, pendKind;   // (base, class/name/desc offsets, kind)
    private static int pendN;

    // Rapid Type Analysis (RTA): a virtual/interface call's targets are only the methods that an INSTANTIATED
    // receiver could dispatch to -- not every loaded class carrying the name+desc (the old CHA over-approx,
    // which marked e.g. Float/Double.toString just because they were loaded, dragging in the FloatToDecimal ->
    // new String(...,Charset) -> charset closure). We track types created by `new` (0xbb) in reached methods;
    // a virtual pend then marks, for each instantiated class, the method it resolves up its own superclass chain.
    private static boolean[] pdInstantiated;             // pd blob is `new`'d in some reached method
    private static long[] instBase;                      // `new X` sites: (base, this-name Utf8 offset)
    private static int[] instOff;
    private static int instN;

    // Per-pass accumulators for LOAD_PROFILE, in raw CNTPCT ticks (converted only when printed).
    static int mrRounds;
    static long mrProbe, mrSeed, mrCollect, mrPull, mrStruct, mrInst, mrStatic, mrVirt, mrDflt;

    /**
     * True if blob {@code b} was compiled by an EARLIER batch, so the mark can skip it. Phase B is guarded by
     * the same flag ({@code pdDoneB[i] == 0}), which is the whole argument: a settled blob is never recompiled
     * and its TIB never rebuilt, so marking one of its methods now cannot compile anything. All the mark can
     * still do for it is re-derive references its own batch already followed. On an incremental load that is
     * ~97% of every pass (75 settled blobs to pull one new class), and it is repeated once per round.
     *
     * <p>What a settled blob's UNMARKED method costs instead is one late resolution at the site that reaches
     * it -- a deferral stub in the vtable, or a link stub at the call -- which is the machinery the reflection
     * arc built and validated. Within the first batch nothing is settled, so nothing here changes.
     */
    private static boolean markSettled(int b)
    {
        return pdDoneB[b] != 0;
    }

    /** Discard the method-table cache: blob addresses and indices are per batch, so the offsets go stale. */
    private static void resetMethodTables()
    {
        if (mtName == null)                              // the flat arrays are scratch -- allocate once, reuse
        {
            mtName = new int[MAXMETH];
            mtDesc = new int[MAXMETH];
            mtHash = new int[MAXMETH];
            mtCode = new long[MAXMETH];
            mtStart = new int[MAXBLOB];
            mtLen = new int[MAXBLOB];
        }
        mtBuilt = new int[MAXBLOB];                      // only the per-blob flags need clearing (1024 ints)
        pdSuperCache = new int[MAXBLOB];
        mtN = 0;
    }

    private static void markReachable()
    {
        resetMethodTables();
        reachN = 0;
        reachTab = new long[REACHTAB];                   // the set is rebuilt from the entry each batch
        collectedTab = new long[REACHTAB];               // ... and so is "whose refs are already pended"
        pdPendTo = new int[MAXBLOB];                     // Heap.alloc zeroes its payload, so every watermark
        pdVirtTo = new int[MAXBLOB];                     //   starts at 0 and a blob added later starts there
        pdDfltTo = new int[MAXBLOB];                     //   too -- it must consider every pend once
        pdSeeded = new int[MAXBLOB];
        pdSeedC = new int[MAXBLOB];
        pendPullTo = 0;
        pendN = 0;

        pendBase = new long[MAXPEND];
        pendClass = new int[MAXPEND];
        pendName = new int[MAXPEND];
        pendDesc = new int[MAXPEND];
        pendKind = new int[MAXPEND];
        pdInstantiated = new boolean[MAXBLOB];
        instBase = new long[MAXPEND];
        instOff = new int[MAXPEND];
        instN = 0;
        parseForMethods(gEntryBlob, blobLenOf(gEntryBlob));
        addReach(findMethodByBytes(gbase, gEntryName, gEntryDesc));
        seedRootBlob();                                 // a defineClass'd class: all of it is a root
        seedAllNamed(Magic.bytes("run"), Magic.bytes("()V"));   // trampoline entry (Runnable.run)
        // Each round is two bounded passes over the blobs (so the const pool is parsed O(blobs) times, not
        // per-ref): collect the call-site refs of every reachable method, then mark each ref's target(s).
        // Reachability-gated closure: each round (a) collects the class/method refs of every reachable
        // method, (b) PULLS any referenced class not yet loaded from the embedded dir + its super/interfaces,
        // (c) marks the invoke targets now resolvable. So a program pulls only its reachable closure -- the
        // basis for embedding all of java.base without dragging every class's full constant-pool closure in.
        boolean grew = true;
        mrRounds = 0;
        mrProbe = 0L;
        mrSeed = 0L;
        mrCollect = 0L;
        mrPull = 0L;
        mrStruct = 0L;
        mrInst = 0L;
        mrStatic = 0L;
        mrVirt = 0L;
        mrDflt = 0L;
        while (grew)
        {
            grew = false;
            mrRounds += 1;
            long tp = Magic.readCNTPCT_EL0();
            probeAll();                                 // set pdNameOff for all (incl. just-pulled) + dep list
            buildNameIndex();                           // ... which is what makes the names hashable
            mrProbe += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = seedNativelyReached() || grew;       // the five signatures RTA cannot infer (see the method)
            grew = seedClinits() || grew;               // runnable <clinit>s: pull the classes an initializer calls
            mrSeed += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            int b = 0;
            while (b < pdCount)                         // collect refs of NEWLY reachable methods
            {
                if (!markSettled(b))
                {
                    collectBlob(pdBase[b], pdLen[b]);
                }
                b += 1;
            }
            mrCollect += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            int r = pendPullTo;                         // pull referenced classes not yet loaded. Only the new
            while (r < pendN)                           //   pends: an older one either pulled its class already
            {                                           //   or named one the dir does not have, and neither
                if (!nameRegistered(pendBase[r], pendClass[r])   // answer changes by being asked again.
                        && registerNameFromDir(pendBase[r], pendClass[r]) != 0L)
                {
                    grew = true;
                }
                r += 1;
            }
            pendPullTo = pendN;
            mrPull += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            b = 0;
            while (b < pdCount)                         // pull each loaded class's super + interfaces
            {
                if (!markSettled(b))
                {
                    grew = pullStructural(pdBase[b], pdLen[b]) || grew;
                }
                b += 1;
            }
            mrStruct += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = computeInstantiated() || grew;       // RTA: flag the pd blobs `new`'d by a reached method
            mrInst += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            b = 0;
            buildStaticPendIndex();
            while (b < pdCount)                         // resolve static/special targets (class-qualified, precise)
            {
                if (!markSettled(b) && (pdPendTo[b] < pendN || pdPendEpoch[b] != pdCount))
                {
                    virtFrom = pdPendEpoch[b] == pdCount ? pdPendTo[b] : 0;   // chain may have grown -> redo all
                    grew = resolveBlob(pdBase[b], pdLen[b]) || grew;
                    pdPendTo[b] = pendN;
                    pdPendEpoch[b] = pdCount;
                }
                b += 1;
            }
            mrStatic += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = resolveVirtuals() || grew;           // RTA: virtual/interface targets, in instantiated types only
            mrVirt += Magic.readCNTPCT_EL0() - tp;
            tp = Magic.readCNTPCT_EL0();
            grew = markDefaults() || grew;              // RTA: interface DEFAULT methods (resolveVirtuals only walks
            mrDflt += Magic.readCNTPCT_EL0() - tp;
        }                                               //   class hierarchies, so an unoverridden default is missed)
        markActive = 1;
    }

    /**
     * RTA for interface DEFAULT methods: {@link #resolveVirtuals} only marks targets found by walking an
     * instantiated class's SUPERCLASS chain, so a called interface method that no class overrides (it uses the
     * interface's own default body, e.g. {@code EnumMap} inheriting {@code Map.putIfAbsent}) is never compiled --
     * its imap slot then stays 0 and the invokeinterface wild-branches. Mark any concrete (default) method in a
     * loaded interface whose name+descriptor matches a pending interface-call (kind 1). Over-marking is harmless
     * (only compiles a bit more); pends only hold actually-called signatures, so this is targeted. Marking a
     * default reachable triggers another round that pulls its own callees (putIfAbsent -> get/put), so the
     * default's transitive closure compiles too.
     */
    private static boolean markDefaults()
    {
        boolean grew = false;
        buildPendIndex();
        int b = 0;
        while (b < pdCount)
        {
            if (markSettled(b) || pdDfltTo[b] >= pendN)
            {
                b += 1;
                continue;
            }
            parseConstPool(pdBase[b], pdLen[b]);
            boolean iface = (u2(gp) & 0x0200) != 0;      // ACC_INTERFACE (access_flags right after the constant pool)
            if (iface)
            {
                virtFrom = pdDfltTo[b];
                parseForMethods(pdBase[b], pdLen[b]);
                grew = matchDefaults() || grew;          // same inversion as resolveVirtuals: per method, not per pend
            }
            pdDfltTo[b] = pendN;
            b += 1;
        }
        return grew;
    }

    /** Pull {@code base}'s superclass + interfaces from the dir (needed for its TIB/itable), if not loaded. */
    private static boolean pullStructural(long base, int len)
    {
        parseConstPool(base, len);
        long p = gp;
        boolean grew = false;
        int superIdx = u2(p + 4);
        if (superIdx != 0)
        {
            int nameOff = gcp[u2(gbase + gcp[superIdx])];
            if (!nameRegistered(gbase, nameOff) && registerNameFromDir(gbase, nameOff) != 0L)
            {
                grew = true;
            }
        }
        int ifCount = u2(p + 6);
        int k = 0;
        while (k < ifCount)
        {
            int nameOff = gcp[u2(gbase + gcp[u2(p + 8 + k * 2)])];
            if (!nameRegistered(gbase, nameOff) && registerNameFromDir(gbase, nameOff) != 0L)
            {
                grew = true;
            }
            k += 1;
        }
        return grew;
    }

    /** Collect the call-site refs of every already-reachable method in blob {@code base} into the pending set. */
    private static void collectBlob(long base, int len)
    {
        parseForMethods(base, len);
        findBootstrapMethods();                         // set gBsmOff so collectRefs can resolve lambda indys
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            long code = findCode(base, p + 8, attrs);
            if (code != 0L && isReach(code) && addCollected(code))
            {
                collectRefs(base, code, gcodeLen);      // ONCE: a method's refs never change, and the pend
            }                                           //   list now accumulates instead of being rebuilt
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Append each invoke in {@code [code, code+len)} to the pending-ref set (offsets index the cp of {@code base}). */
    /**
     * Record every class a reachable method references, so reachability-gated closure pulls only what a
     * reached method actually touches (not the whole class's constant pool): invoke targets (mark the method
     * + pull its class), field owners, and {@code new}/{@code checkcast}/{@code instanceof}/{@code anewarray}
     * types (pull-only). {@code gcp}/{@code gbase} are parsed for {@code base} by the caller.
     */
    private static void collectRefs(long base, long code, int len)
    {
        int pc = 0;
        while (pc < len && pendN < MAXPEND)
        {
            int op = u1(code + pc);
            // Dead-branch pruning for metal-invariant flags: a getstatic of such a flag immediately guarding a
            // branch is never-taken code on metal, yet reachability would still walk it and pull its whole
            // closure. `getstatic jfrTracing; ifeq L` (JFR off -> the ThrowableTracer/JFR arm is dead, which is
            // what otherwise drags jdk/internal/event + AtomicLong + reflect out of stock Throwable/Error), and
            // `getstatic $assertionsDisabled; ifne L` (assertions off -> the assert arm is dead): skip to L so
            // the guarded arm's refs aren't collected. The compiler still emits the dead call as an unresolved
            // bl, which never executes.
            if (op == 0xb2)                                          // getstatic
            {
                int fidx = u2(code + pc + 1);
                int nextOp = u1(code + pc + 3);
                if ((nextOp == 0x99 && utf8IsAtBase(base, mrefNameOff(fidx), Magic.bytes("jfrTracing")))
                        || (nextOp == 0x9a && utf8IsAtBase(base, mrefNameOff(fidx), Magic.bytes("$assertionsDisabled"))))
                {
                    int off = u2(code + pc + 4);
                    if (off >= 0x8000) { off -= 0x10000; }
                    int target = pc + 3 + off;                       // the taken (dead-arm-skipping) branch target
                    if (target > pc + 6 && target <= len)
                    {
                        pc = target;
                        continue;
                    }
                }
            }
            if (op == 0xb8 || op == 0xb7 || op == 0xb6 || op == 0xb9)   // invoke static/special/virtual/interface
            {
                int idx = u2(code + pc + 1);
                addPend(base, refClassNameOff(idx), mrefNameOff(idx), mrefDescOff(idx),
                        (op == 0xb8 || op == 0xb7) ? 0 : 1);            // class-qualified vs name+desc
            }
            else if (op == 0xb2 || op == 0xb3 || op == 0xb4 || op == 0xb5)   // get/put static|field: pull owner
            {
                addPend(base, refClassNameOff(u2(code + pc + 1)), 0, 0, PEND_PULL);
            }
            else if (op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1)   // new/anewarray/checkcast/instanceof
            {
                int cnOff = classCpNameOff(u2(code + pc + 1));
                addPend(base, cnOff, 0, 0, PEND_PULL);
                if (op == 0xbb)                          // RTA: `new C` makes C an instantiated receiver type
                {
                    addInst(base, cnOff);
                }
            }
            else if (op == 0x12 && gcpTag[u1(code + pc + 1)] == 7)           // ldc of a CONSTANT_Class (X.class
            {                                                               // literal): pull the class so its Type/
                addPend(base, classCpNameOff(u1(code + pc + 1)), 0, 0, PEND_PULL);   // mirror exists for reflection
            }
            else if (op == 0x13 && gcpTag[u2(code + pc + 1)] == 7)           // ldc_w of a CONSTANT_Class literal
            {
                addPend(base, classCpNameOff(u2(code + pc + 1)), 0, 0, PEND_PULL);
            }
            else if (op == 0xba && isLambdaIndy(u2(code + pc + 1)))          // lambda/method-ref: mark its impl body
            {
                int idx = u2(code + pc + 1);
                int mref = lambdaImplMref(idx);
                int mk = lambdaImplKind(idx);
                addPend(base, refClassNameOff(mref), mrefNameOff(mref), mrefDescOff(mref),
                        (mk == 5 || mk == 9) ? 1 : 0);   // invokeVirtual/Interface -> name+desc; else class-qualified
                if (mk == 8)                             // constructor ref (X::new) instantiates X, like `new X`:
                {                                        // mark it a receiver type so RTA pulls its overridden
                    addInst(base, refClassNameOff(mref));//   virtual methods (hashCode/equals/...) and fills its vtable
                }
            }
            pc += insnLen(code, pc);
        }
    }

    /** Name Utf8 offset of a {@code CONSTANT_Class} at cp index {@code idx} (for the current {@code gcp}/{@code gbase}). */
    private static int classCpNameOff(int idx)
    {
        return gcp[u2(gbase + gcp[idx])];
    }

    /** Append a pending ref (dedup-free; the pull/resolve phases idempotently re-check registration). */
    private static void addPend(long base, int classOff, int nameOff, int descOff, int kind)
    {
        if (pendN >= MAXPEND)
        {
            return;
        }
        pendBase[pendN] = base;
        pendClass[pendN] = classOff;
        pendName[pendN] = nameOff;
        pendDesc[pendN] = descOff;
        pendKind[pendN] = kind;
        pendN += 1;
    }

    /** For blob {@code base}, mark every pending-ref target it defines. Returns true if any method was newly marked. */
    private static boolean resolveBlob(long base, int len)
    {
        parseForMethods(base, len);
        boolean grew = false;
        // Only static/special calls (kind 0) resolve here: they name a concrete class, so mark that method iff
        // this blob IS that class. PEND_PULL pulls a class only; virtual/interface (kind 1) go through
        // resolveVirtuals (RTA over instantiated types), NOT "mark in every class carrying the name+desc".
        // Reached through the class-name index rather than by scanning every pend per blob -- that guard was
        // a string compare per (blob, pend), which is pdCount x pendN of them per round.
        int r = psBucket[utf8Hash(gbase, gThisNameOff) & (PVTAB - 1)];
        while (r >= 0)
        {
            if (r >= virtFrom && utf8EqAt(pendBase[r], pendClass[r], gbase, gThisNameOff))
            {
                long code = findMethodByRef(pendBase[r], pendName[r], pendDesc[r]);
                if (code != 0L)
                {
                    grew = addReach(code) || grew;
                }
                else
                {
                    // ref names THIS class but it does not declare the method: an INHERITED static/special
                    // (declared in a superclass, e.g. ArrayList.subListRangeCheck -> AbstractList's). Walk up.
                    grew = markInheritedStatic(pendBase[r], pendName[r], pendDesc[r], base, len) || grew;
                }
            }
            r = psNext[r];
        }
        return grew;
    }

    /** Reachability for an inherited static/special call: walk the ref class's (== current blob) super chain and
     *  mark the declaring ancestor's method. Restores the current blob's parse state before returning. */
    private static boolean markInheritedStatic(long refBase, int nameOff, int descOff, long curBase, int curLen)
    {
        boolean grew = false;
        int pd = findPdByName(gbase, gThisNameOff);        // current blob's pd (the ref class)
        while (pd >= 0 && pdSuperOff[pd] != 0)
        {
            int spd = findPdByName(pdBase[pd], pdSuperOff[pd]);
            if (spd < 0)
            {
                break;
            }
            parseForMethods(pdBase[spd], pdLen[spd]);      // search the ancestor's method table
            long code = findMethodByRef(refBase, nameOff, descOff);
            if (code != 0L)
            {
                grew = addReach(code);
                break;
            }
            pd = spd;
        }
        parseForMethods(curBase, curLen);                  // restore resolveBlob's current blob
        return grew;
    }

    /** Record {@code base}'s {@code new C} target (Utf8 name at {@code off}) as an instantiated type (deduped). */
    private static void addInst(long base, int off)
    {
        int i = 0;
        while (i < instN)
        {
            if (utf8EqAt(instBase[i], instOff[i], base, off))
            {
                return;
            }
            i += 1;
        }
        if (instN >= MAXPEND)
        {
            return;
        }
        instBase[instN] = base;
        instOff[instN] = off;
        instN += 1;
    }

    /** pd index of the loaded blob whose this-class name equals the Utf8 at {@code base+off}, or -1. */
    private static int findPdByName(long base, int off)
    {
        int i = 0;
        while (i < pdCount)
        {
            if (utf8EqAt(base, off, pdBase[i], pdNameOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Flag every pd blob that was {@code new}'d by a reached method; returns true if a new one was flagged. */
    private static boolean computeInstantiated()
    {
        boolean grew = false;
        int k = 0;
        while (k < instN)
        {
            int pd = findPdByName(instBase[k], instOff[k]);
            if (pd >= 0 && !pdInstantiated[pd])
            {
                pdInstantiated[pd] = true;
                grew = true;
            }
            k += 1;
        }
        // Instances the VM creates WITHOUT a bytecode `new`, so RTA can't see the site: string literals + concat
        // materialize a String (intern / newStringFromBytes); the JIT's null/bounds/div/cast checks throw these
        // exceptions via VM.newHelper. Flag them instantiated whenever loaded so their virtual methods resolve.
        grew = flagInstByName(Magic.bytes("java/lang/String")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/NullPointerException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ArithmeticException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/ClassCastException")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/NegativeArraySizeException")) || grew;
        // M2: System.out/err are PrintStream instances allocated by Loader.seedSystemStreams (Heap.alloc, no
        // bytecode `new`), so RTA can't see the site -> flag it so println/print virtual methods compile + its
        // vtable fills (else System.out.println dispatches to an unfilled slot -> wrong overload / wild branch).
        grew = flagInstByName(Magic.bytes("java/io/PrintStream")) || grew;
        // M4: Class mirrors (Loader.classMirror) and the boot task's lazy Thread (Loader.allocThreadObj) are
        // VM-alloc'd too -> flag them so getName/isInstance/... and getName/run compile + their vtables fill.
        grew = flagInstByName(Magic.bytes("java/lang/Class")) || grew;
        grew = flagInstByName(Magic.bytes("java/lang/Thread")) || grew;
        // The metal JavaLangAccess (seeded into SharedSecrets by seedJavaLangAccess, Heap.alloc, no bytecode
        // `new`) -> flag it so its getEnumConstantsShared interface method compiles + its itable fills (else
        // EnumMap.getKeyUniverse's invokeinterface hits an empty imap slot).
        grew = flagInstByName(Magic.bytes("jdk/internal/access/MetalJavaLangAccess")) || grew;
        return grew;
    }

    /** Flag the loaded blob named {@code name} instantiated (a VM-created type with no `new` site); true if newly. */
    private static boolean flagInstByName(byte[] name)
    {
        int i = 0;
        while (i < pdCount)
        {
            if (!pdInstantiated[i] && utf8IsAtBase(pdBase[i], pdNameOff[i], name))
            {
                pdInstantiated[i] = true;
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** pd index of {@code pd}'s superclass, or -1 (Object / super not loaded). */
    private static int superPdOf(int pd)
    {
        parseConstPool(pdBase[pd], pdLen[pd]);
        int superIdx = u2(gp + 4);                       // gp -> access_flags; this(+2), super(+4)
        if (superIdx == 0)
        {
            return SUPER_NONE;                           // java/lang/Object
        }
        return findPdByName(gbase, gcp[u2(gbase + gcp[superIdx])]);
    }

    private static final int SUPER_NONE = -2;            // declares no superclass; permanent
    private static final int SUPER_UNLOADED = -1;        // names one, but that blob is not loaded YET
    private static int[] pdSuperCache;                   // superPdOf memo, +3 biased so 0 means "not resolved"

    /**
     * {@link #superPdOf} memoized. Two of its three answers are PERMANENT: a class's superclass NAME is fixed
     * by its classfile, so once that blob is loaded the link never changes, and "declares no superclass" never
     * changes. Only {@link #SUPER_UNLOADED} is retried.
     *
     * <p>Measured at ZERO on its own -- the chain walk's cost was `parseForMethods`, not this. It pays only
     * now that {@link #ensureMethodTable} has removed the bigger parse and left this one exposed.
     */
    private static int cachedSuperOf(int pd)
    {
        int v = pdSuperCache[pd];
        if (v != 0)
        {
            return v - 3;
        }
        int r = superPdOf(pd);
        if (r != SUPER_UNLOADED)
        {
            pdSuperCache[pd] = r + 3;
        }
        return r;
    }

    private static int[] virtResolved;                   // per-pend: STAMP of the class that last dispatched it
    private static int virtStamp;                        // current class's stamp (index+1, so 0 means "never")

    /**
     * RTA: mark each virtual/interface call's real targets — for every instantiated receiver type, the method
     * it resolves up its own superclass chain (nearest definition wins). Replaces the CHA "mark in every class
     * carrying name+desc". Structured class-outer / level-once: each (instantiated class, superclass-chain
     * level) is parsed ONCE and matched against every virtual pend, instead of re-parsing the whole chain per
     * pend — the naive per-pend form re-parsed (and re-copied via toBytes/parseConstPool) tens of thousands of
     * times, churning ~90 MiB of heap and blowing the demand-load arena past the A64 bl reach.
     */
    private static boolean resolveVirtuals()
    {
        boolean grew = false;
        if (virtResolved == null || virtResolved.length < pendN)
        {
            virtResolved = new int[pendN + 64];
        }
        buildPendIndex();
        int c = 0;
        while (c < pdCount)
        {
            if (pdInstantiated[c] && !markSettled(c)
                    && (pdVirtTo[c] < pendN || pdVirtEpoch[c] != pdCount))
            {                                            // a settled receiver's vtable is built and frozen, and
                                                         // a class with no NEW pends has nothing to dispatch --
                                                         // skipping it skips the whole superclass chain walk,
                                                         // which is what this pass actually spends its time on.
                // "No pend dispatched yet for this class" by STAMP rather than by clearing the array: the
                // clear was pendN writes per instantiated class, and pendN reaches 24,826 here.
                virtStamp = c + 1;                       // +1: the array starts at 0, and 0 is a valid class index
                virtFrom = pdVirtEpoch[c] == pdCount ? pdVirtTo[c] : 0;   // chain may have grown -> redo all
                pdVirtTo[c] = pendN;
                pdVirtEpoch[c] = pdCount;
                int cur = c;
                int guard = 0;
                while (cur >= 0 && guard < 64)           // walk C's superclass chain, parsing each level once
                {
                    if (ensureMethodTable(cur))
                    {
                        grew = matchLevelCached(cur) || grew;
                    }
                    else
                    {
                        parseForMethods(pdBase[cur], pdLen[cur]);
                        grew = matchLevel() || grew;
                    }
                    cur = cachedSuperOf(cur);
                    guard += 1;
                }
            }
            c += 1;
        }
        return grew;
    }

    /**
     * Match the current class level (as left by {@link #parseForMethods}) against the virtual pends, by walking
     * the level's OWN methods and looking each up in {@link #buildPendIndex}'s table.
     *
     * <p>The direction is the point. Asking "for each pend, does this class define it?" is
     * {@code pends x methods} string compares per level, and the level count is
     * {@code instantiated classes x chain depth} -- with 699 pends over 75 classes that pass alone was 57% of
     * a batch's whole reachability mark. Asking "for each method, is it pended?" is one hash probe per method:
     * {@code methods} instead of {@code pends x methods}.
     *
     * <p>Semantics are unchanged. Methods are visited in declaration order and {@code virtResolved} is set on
     * first match, so a pend still binds to the first definition carrying Code, and the chain walk still stops
     * at the nearest one rather than also marking an override-shadowed super definition.
     */
    // ---- per-blob method-table cache ---------------------------------------------------------------------
    // matchLevel is called once per superclass level per instantiated class per round, and every call
    // re-derived the same immutable facts: a full parseConstPool, a walk of the method table, and an FNV hash
    // over each method's name AND descriptor. A blob's method table never changes, so build it once.
    private static final int MAXMETH = 65536;
    private static int[] mtName, mtDesc, mtHash;         // per method: Utf8 offsets (blob-relative) + sig hash
    private static long[] mtCode;                        // ... and its Code address, 0 for abstract/native
    private static int[] mtStart, mtBuilt;               // per blob: slice start, and 0=no 1=yes 2=did not fit
    private static int[] mtLen;                          // per blob: method count
    private static int mtN;                              // flat-array high-water

    /**
     * Build blob {@code b}'s method-table slice if absent. False if the flat arrays are full, in which case the
     * caller must fall back to parsing -- a cap that degrades to today's behaviour rather than failing.
     */
    private static boolean ensureMethodTable(int b)
    {
        if (mtBuilt[b] == 1)
        {
            return true;
        }
        if (mtBuilt[b] == 2)
        {
            return false;
        }
        parseForMethods(pdBase[b], pdLen[b]);
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        if (mtN + mcount > MAXMETH)
        {
            mtBuilt[b] = 2;
            return false;
        }
        mtStart[b] = mtN;
        mtLen[b] = mcount;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            int nameOff = gcp[u2(p + 2)];
            int descOff = gcp[u2(p + 4)];
            mtName[mtN] = nameOff;
            mtDesc[mtN] = descOff;
            mtHash[mtN] = sigHash(gbase, nameOff, descOff);
            mtCode[mtN] = findCode(gbase, p + 8, attrs);
            mtN += 1;
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        mtBuilt[b] = 1;
        return true;
    }

    /**
     * {@link #matchLevel} over the cached table. Methods with no Code are skipped outright, which is exactly
     * what the parsing form does: it probes the bucket but only consumes the pend ({@code virtResolved}) when
     * {@code findCode} answers non-zero, so an abstract declaration never shadows a superclass's real body.
     */
    private static boolean matchLevelCached(int b)
    {
        boolean grew = false;
        long base = pdBase[b];
        int i = mtStart[b];
        int end = i + mtLen[b];
        while (i < end)
        {
            long code = mtCode[i];
            if (code != 0L)
            {
                int q = pvBucket[mtHash[i] & (PVTAB - 1)];
                while (q >= 0)
                {
                    if (q >= virtFrom && virtResolved[q] != virtStamp
                            && utf8EqAt(pendBase[q], pendName[q], base, mtName[i])
                            && utf8EqAt(pendBase[q], pendDesc[q], base, mtDesc[i]))
                    {
                        virtResolved[q] = virtStamp;     // nearest def; don't also mark a super's shadowed one
                        grew = addReach(code) || grew;
                    }
                    q = pvNext[q];
                }
            }
            i += 1;
        }
        return grew;
    }

    private static boolean matchLevel()
    {
        boolean grew = false;
        long p = gp;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            int nameOff = gcp[u2(p + 2)];
            int descOff = gcp[u2(p + 4)];
            int q = pvBucket[sigHash(gbase, nameOff, descOff) & (PVTAB - 1)];
            while (q >= 0)
            {
                if (q >= virtFrom && virtResolved[q] != virtStamp
                        && utf8EqAt(pendBase[q], pendName[q], gbase, nameOff)
                        && utf8EqAt(pendBase[q], pendDesc[q], gbase, descOff))
                {
                    long code = findCode(gbase, p + 8, attrs);
                    if (code != 0L)
                    {
                        virtResolved[q] = virtStamp;     // nearest def; don't also mark a super's shadowed one
                        grew = addReach(code) || grew;
                    }
                }
                q = pvNext[q];
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return grew;
    }

    /**
     * The {@link #matchLevel} inversion for {@link #markDefaults}: mark every concrete method of the current
     * interface whose name+descriptor is pended. No nearest-definition rule here -- a default is marked
     * wherever it is found, and {@link #addReach} dedupes -- so there is no {@code virtResolved} to consult.
     */
    private static boolean matchDefaults()
    {
        boolean grew = false;
        long p = gp;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            int nameOff = gcp[u2(p + 2)];
            int descOff = gcp[u2(p + 4)];
            int q = pvBucket[sigHash(gbase, nameOff, descOff) & (PVTAB - 1)];
            while (q >= 0)
            {
                if (q >= virtFrom
                        && utf8EqAt(pendBase[q], pendName[q], gbase, nameOff)
                        && utf8EqAt(pendBase[q], pendDesc[q], gbase, descOff))
                {
                    long code = findCode(gbase, p + 8, attrs);
                    if (code != 0L)
                    {
                        grew = addReach(code) || grew;
                        break;                           // marked; other pends on the same method add nothing
                    }
                }
                q = pvNext[q];
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return grew;
    }

    private static final int PVTAB = 2048;               // power of two; chained, so a full table only lengthens chains
    private static int[] pvBucket;                       // bucket head -> pend index, -1 empty
    private static int[] pvNext;                         // chain link, per pend
    private static int[] psBucket;                       // ... and the same for STATIC/special pends, keyed on the
    private static int[] psNext;                         //   callee CLASS name, for resolveBlob

    /** Index this round's STATIC/special pends (kind 0) by callee class name, for {@link #resolveBlob}. */
    private static void buildStaticPendIndex()
    {
        if (psBucket == null)
        {
            psBucket = new int[PVTAB];
        }
        if (psNext == null || psNext.length < pendN)
        {
            psNext = new int[pendN + 64];
        }
        int i = 0;
        while (i < PVTAB)
        {
            psBucket[i] = -1;
            i += 1;
        }
        int p = 0;
        while (p < pendN)
        {
            if (pendKind[p] == 0)
            {
                int h = utf8Hash(pendBase[p], pendClass[p]) & (PVTAB - 1);
                psNext[p] = psBucket[h];
                psBucket[h] = p;
            }
            else
            {
                psNext[p] = -1;
            }
            p += 1;
        }
    }

    /** Index this round's VIRTUAL pends (kind 1) by their name+descriptor hash, for {@link #matchLevel}. */
    private static void buildPendIndex()
    {
        if (pvBucket == null)
        {
            pvBucket = new int[PVTAB];
        }
        if (pvNext == null || pvNext.length < pendN)
        {
            pvNext = new int[pendN + 64];
        }
        int i = 0;
        while (i < PVTAB)
        {
            pvBucket[i] = -1;
            i += 1;
        }
        int p = 0;
        while (p < pendN)
        {
            if (pendKind[p] == 1)
            {
                int h = sigHash(pendBase[p], pendName[p], pendDesc[p]) & (PVTAB - 1);
                pvNext[p] = pvBucket[h];
                pvBucket[h] = p;
            }
            else
            {
                pvNext[p] = -1;
            }
            p += 1;
        }
    }

    /** FNV-1a over a name Utf8 and a descriptor Utf8 -- the key {@link #matchLevel} probes on. */
    private static int sigHash(long base, int nameOff, int descOff)
    {
        return utf8Hash(base, nameOff) * 31 + utf8Hash(base, descOff);
    }

    /** FNV-1a over the bytes of the length-prefixed Utf8 at {@code base + off}. */
    private static int utf8Hash(long base, int off)
    {
        int len = u2(base + off);
        long q = base + off + 2L;
        int h = 0x811C9DC5;
        int i = 0;
        while (i < len)
        {
            h = (h ^ u1(q + i)) * 0x01000193;
            i += 1;
        }
        return h;
    }

    /**
     * Seed the signatures no call site names, in ONE pass over the blobs. Each is a method some NATIVE or
     * out-of-graph path enters, so RTA prunes it and its vtable slot stays 0:
     * <ul>
     *   <li>{@code run()V} -- the thread trampoline enters a Runnable outside the call graph.</li>
     *   <li>{@code getAndBitwiseOr}/{@code compareAndSet} -- the VarHandle overlay's ops. Their call sites are
     *       signature-polymorphic ({@code getAndBitwiseOr:(LSocket;I)I}), so they never match the overlay's
     *       own descriptor and normal invoke-target marking misses them.</li>
     *   <li>{@code getMethodName} -- StackTraceElement is instantiated natively by {@code frameToElement}.</li>
     *   <li>{@code getEnumConstants} -- Class mirrors are instantiated natively by {@code classMirror}, the
     *       same blind spot. Stock code reaches this one through a mirror it did not create:
     *       {@code EnumMap.getKeyUniverse -> getJavaLangAccess().getEnumConstantsShared(k) ->
     *       k.getEnumConstants()}. Pruned, the call lands in dispatchTargetGuard as a bare AIOOBE -- which is
     *       where StreamOpFlag's initializer died.</li>
     * </ul>
     *
     * <p>One pass, not five: {@link #parseForMethods} re-parses the whole constant pool, and doing that five
     * times per blob per round made these seeds a tenth of the entire mark.
     */
    private static boolean seedNativelyReached()
    {
        boolean grew = false;
        int b = 0;
        while (b < pdCount)
        {
            if (!markSettled(b) && pdSeeded[b] == 0)
            {
                pdSeeded[b] = 1;                         // these five signatures do not change; one look each
                // ONE parse, five scans: findMethodByBytes reads gp/gcp and never advances them, so the
                // constant pool this sets up is good for all five lookups.
                parseForMethods(pdBase[b], pdLen[b]);
                grew = addReach(findMethodByBytes(gbase, Magic.bytes("run"), Magic.bytes("()V"))) || grew;
                grew = addReach(findMethodByBytes(gbase, Magic.bytes("getAndBitwiseOr"),
                        Magic.bytes("(Ljava/lang/Object;I)I"))) || grew;
                grew = addReach(findMethodByBytes(gbase, Magic.bytes("compareAndSet"),
                        Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"))) || grew;
                grew = addReach(findMethodByBytes(gbase, Magic.bytes("getMethodName"),
                        Magic.bytes("()Ljava/lang/String;"))) || grew;
                grew = addReach(findMethodByBytes(gbase, Magic.bytes("getEnumConstants"),
                        Magic.bytes("()[Ljava/lang/Object;"))) || grew;
            }
            b += 1;
        }
        return grew;
    }

    /** Add the {@code name/desc} method of every loaded blob that defines it (for run()V trampoline seeds). */
    private static boolean seedAllNamed(byte[] name, byte[] desc)
    {
        boolean grew = false;
        int b = 0;
        while (b < pdCount)
        {
            if (!markSettled(b))
            {
                parseForMethods(pdBase[b], pdLen[b]);
                grew = addReach(findMethodByBytes(gbase, name, desc)) || grew;
            }
            b += 1;
        }
        return grew;
    }

    /**
     * Seed every method of the {@link #rootBlob root blob} (a class defined from supplied bytes), and flag it
     * instantiated. Both halves are needed: seeding the methods is what gives them deferral stubs and so a
     * filled vtable, and the instantiated flag is what lets {@link #resolveVirtuals} mark the INHERITED
     * virtuals it calls up its superclass chain -- RTA infers instantiation from a `new` site, and the only
     * `new` for a defined class is a later reflective one it cannot see.
     */
    private static boolean seedRootBlob()
    {
        if (gRootBlob == 0L)
        {
            return false;
        }
        boolean grew = false;
        parseForMethods(gRootBlob, blobLenOf(gRootBlob));
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            grew = addReach(findCode(gbase, p + 8, attrs)) || grew;   // 0 for a native/abstract method
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        int i = 0;
        while (i < pdCount)
        {
            if (pdBase[i] == gRootBlob && !pdInstantiated[i])
            {
                pdInstantiated[i] = true;
                grew = true;
            }
            i += 1;
        }
        return grew;
    }

    /**
     * Mark every runnable {@code <clinit>} reachable, so {@link #collectRefs} pulls the classes an initializer
     * calls. A {@code <clinit>} is never the target of an invoke bytecode (the loader runs it implicitly at
     * class load), so without this its callees are never in the reachable closure — e.g.
     * {@code ArraysSupport.<clinit>} calls {@code SharedSecrets.getJavaLangAccess()}, and an unpulled
     * {@code SharedSecrets} leaves that {@code invokestatic} unresolved (bl to an unpatched site → wild
     * branch when the initializer runs). Only clinits that will ACTUALLY run are seeded: {@link #clinitBlocked}
     * (native/property-reading) ones are skipped, as are {@link #clinitCompilable} rejects (an unrunnable
     * initializer whose refs would needlessly grow the closure) — matching exactly what {@link #runClinit} executes.
     */
    private static boolean seedClinits()
    {
        boolean grew = false;
        int b = 0;
        while (b < pdCount)
        {
            // Settled blobs skipped for the same reason as everywhere else: their initializer was marked and
            // compiled by their own batch, and phase B will not compile it again. Re-marking it here only
            // re-parsed the constant pool -- and reported `grew`, buying an extra round for nothing. Once per
            // blob for the same reason: a class's initializer, and whether it is blocked, do not change.
            if (!markSettled(b) && pdSeedC[b] == 0)
            {
                pdSeedC[b] = 1;
                parseForMethods(pdBase[b], pdLen[b]);
                if (!clinitBlocked())
                {
                    long code = findMethodByBytes(gbase, Magic.bytes("<clinit>"), Magic.bytes("()V"));
                    if (code != 0L && clinitCompilable(code, gcodeLen))
                    {
                        grew = addReach(code) || grew;
                    }
                }
            }
            b += 1;
        }
        return grew;
    }

    /** Length of the blob at address {@code base} (matched against the pending-blob table). */
    private static int blobLenOf(long base)
    {
        int b = 0;
        while (b < pdCount)
        {
            if (pdBase[b] == base)
            {
                return pdLen[b];
            }
            b += 1;
        }
        return 0;
    }


    /** Find the method matching a name/descriptor given as cross-blob offsets ({@code refBase}), in the current cp. */
    private static long findMethodByRef(long refBase, int nameOff, int descOff)
    {
        long p = gp;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (utf8EqAt(refBase, nameOff, gbase, gcp[u2(p + 2)]) && utf8EqAt(refBase, descOff, gbase, gcp[u2(p + 4)]))
            {
                long code = findCode(gbase, p + 8, attrs);
                if (code != 0L)
                {
                    return code;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0L;
    }




    /**
     * Repeatedly probe the registered blobs for class references and register any that are embedded in
     * {@link VM#classDir} but not yet registered, until the transitive closure is complete. References
     * to unembedded roots ({@code java/lang/Object}, {@code magic/Magic}) are simply not found and skipped.
     */
    private static void resolveClosureFromDir()
    {
        boolean grew = true;
        while (grew)
        {
            grew = false;
            probeAll();                                // (re)builds pdNameOff + the dep list
            int d = 0;
            while (d < dpCount)
            {
                int owner = dpOwner[d];
                int off = dpOff[d];                    // referenced class name (Utf8 in pdBase[owner])
                if (!nameRegistered(pdBase[owner], off) && registerNameFromDir(pdBase[owner], off) != 0L)
                {
                    grew = true;
                }
                d += 1;
            }
        }
    }

    /** True if the class name at {@code off} in {@code base} starts with {@code prefix}. */
    private static boolean utf8HasPrefix(long base, int off, byte[] prefix)
    {
        int len = u2(base + off);
        if (len < prefix.length)
        {
            return false;
        }
        int k = 0;
        while (k < prefix.length)
        {
            if (u1(base + off + 2 + k) != (prefix[k] & 0xFF))
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
    private static boolean isDenylisted(long base, int off)
    {
        // Narrow ALLOW for the VarHandle-as-atomic-field-accessor shim (overlays, not the real invoke runtime):
        // java.net.Socket uses VarHandle for its `state`/`in`/`out` fields. Allowed BEFORE the java/lang/invoke
        // prefix deny below. Everything else in java/lang/invoke stays denied.
        if (utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/VarHandle"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/MethodHandles"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/invoke/MhUtil"))
                // Reflection arc: these java/lang/reflect classes are overlaid (JDK-free) and DO run on metal
                // (Class.getModifiers/getDeclaredField*, reflective Field.get/set); the rest of java/lang/reflect
                // stays denied below.
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/Modifier"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/Field"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/Method"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/Constructor"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/Array"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/AccessibleObject"))
                // M3: java/lang/ClassLoader is overlaid (JDK-free) -- loadClass -> forName, defineClass(byte[]).
                // Allowed here; jdk/internal/loader + java/security stay denied below (no delegation/unloading).
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/ClassLoader"))
                // ExtendedSocketOptions is overlaid to a no-op (Net.<clinit> sets EXTENDED_OPTIONS from it);
                // the rest of sun/net/ext stays denied.
                || utf8HasPrefix(base, off, Magic.bytes("sun/net/ext/ExtendedSocketOptions")))
        {
            return false;
        }
        return utf8HasPrefix(base, off, Magic.bytes("java/lang/invoke/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/foreign/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/foreign/"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/nio/fs/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/file/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/loader/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/security/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/util/ServiceLoader"))
                || utf8HasPrefix(base, off, Magic.bytes("java/util/spi/"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/util/"))
                // java/net is LOADABLE now (M3: stock java.net over net.Tcp). SocksSocketImpl IS taken
                // (Socket.createImpl always wraps the platform impl in it) -- overlaid as a pure delegator,
                // NOT denied. The HTTP-CONNECT proxy impl + www/ext + GC-auto-close SocketCleanable stay trapped.
                || utf8HasPrefix(base, off, Magic.bytes("java/net/HttpConnectSocketImpl"))
                || utf8HasPrefix(base, off, Magic.bytes("java/net/SocketCleanable"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/net/www/"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/net/ext/"))
                // Heavy socket subtrees statically referenced by NioSocketImpl/Net but never TAKEN on the
                // blocking client path -- deny so a stray call traps instead of dragging in
                // streams/ForkJoin/regex/ConcurrentHashMap (which OOM'd the demand-load). Poller = the async
                // poll path (our fd stays blocking); NetworkInterface/ExtendedSocketOption = bind/opts we
                // never use. (SocketOptionRegistry IS reached by close() -> overlaid, not denied.)
                || utf8HasPrefix(base, off, Magic.bytes("sun/nio/ch/Poller"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/nio/ch/ExtendedSocketOption"))
                || utf8HasPrefix(base, off, Magic.bytes("java/net/NetworkInterface"))
                // Exceptions = the error-message formatter (String.format->Formatter->regex + a security
                // property read->Properties/stream), reached only at NioSocketImpl throw sites; IPAddressUtil
                // = link-local scoped-address cache (ConcurrentHashMap), reached only under isLinkLocalAddress().
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/util/Exceptions"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/net/util/IPAddressUtil"))
                // Jar signature verification: joe-ng reads archives, it does not authenticate them. Left
                // loadable, JarInputStream's default verifying constructor drags in JarVerifier -> the whole
                // sun.security provider closure (SunEC, BigDecimal, regex, streams) -- hundreds of classes for
                // a code path an unsigned jar never runs. Denied, so `new JarInputStream(in, false)` works and
                // a verifying one traps loudly instead of exploding the closure.
                || utf8HasPrefix(base, off, Magic.bytes("sun/security/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/util/jar/JarVerifier"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/logger/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/reflect/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/reflect/"))
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/module/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/lang/module/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/spi/"))
                // cold ICU/normalizer/break-iterator: Pattern references but never runs them for a literal match.
                // (NOT java/util/concurrent -- the philosophers demand-load java/util/concurrent/Semaphore.)
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/icu/"))
                || utf8HasPrefix(base, off, Magic.bytes("java/text/"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/text/"))
                // grapheme-boundary tables (\b{g}): a 15x15 [[Z built via multianewarray in its <clinit>; a
                // literal split never matches graphemes, so this whole subtree is cold.
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/util/regex/Grapheme"))
                // case-folding tables ([[I via multianewarray): only CASE_INSENSITIVE regex needs them.
                || utf8HasPrefix(base, off, Magic.bytes("jdk/internal/lang/CaseFolding"))
                // The charset ENCODER/DECODER fallback: stock String's byte[]-ctor/getBytes take the pure-Java
                // UTF-8 fast path (identity vs the overlay sun/nio/cs singletons); the decode/encodeWithEncoder
                // branches that would pull CharsetDecoder/nio buffers are statically present but never taken.
                // (java/nio/charset/Charset itself is NOT denied -- the overlay must load.)
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/CharsetDecoder"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/CharsetEncoder"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/Coder"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/Coding"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/CharacterCoding"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/Malformed"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/Unmappable"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/IllegalCharsetName"))
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/charset/UnsupportedCharset"))
                // java/nio/ByteBuffer is LOADABLE (overlay -> socket temp buffers); CharBuffer stays denied.
                || utf8HasPrefix(base, off, Magic.bytes("java/nio/CharBuffer"))
                || utf8HasPrefix(base, off, Magic.bytes("sun/nio/cs/Array"));
    }

    /** True if some registered blob's own name equals the class name at {@code off} in {@code base}. */
    private static boolean nameRegistered(long base, int off)
    {
        if (pnBucket != null)
        {
            int j = pnBucket[utf8Hash(base, off) & (PVTAB - 1)];
            while (j >= 0)
            {
                if (utf8EqAt(base, off, pdBase[j], pdNameOff[j]))
                {
                    return true;
                }
                j = pnNext[j];
            }
        }
        // Blobs added since the index was built -- this round's own pulls. Their pdNameOff is not set until
        // the next probeAll, so they cannot be hashed; scan them. That is exactly what the whole loop used to
        // do for them, and it stays correct for the same reason: registerNameFromDir dedupes by ADDRESS
        // (alreadyBlob), so an unreliable answer here costs a re-check, never a duplicate blob.
        int k = pnIndexed;
        while (k < pdCount)
        {
            if (utf8EqAt(base, off, pdBase[k], pdNameOff[k]))
            {
                return true;
            }
            k += 1;
        }
        return false;
    }

    // ---- incremental-mark watermarks -------------------------------------------------------------------
    // The round loop stays (it is what drives the fixpoint), but every pass now records how far through the
    // pend list it has already got, per blob / per class. A round then costs only the work that is NEW since
    // the last one, so each (blob, pend) and (class, pend) pair is processed exactly ONCE across the whole
    // mark instead of once per round. With 33 rounds over 24,826 pends that is the difference between
    // O(rounds x pends x blobs) and O(pends x blobs).
    //
    // Pends ACCUMULATE for this to work (the list used to be rebuilt from scratch each round). The total is
    // unchanged -- the old last round already collected every reachable method's refs -- because collectBlob
    // now collects each method once, tracked in collectedTab.
    private static long[] collectedTab;                  // method code addresses whose refs are already pended
    private static int[] pdPendTo;                       // per blob: pends resolved by resolveBlob
    private static int[] pdVirtTo;                       // per class: pends dispatched by resolveVirtuals
    // ... and the blob count when that happened. A pend watermark alone is UNSOUND for these two passes,
    // because both walk the class's SUPERCLASS CHAIN and that chain GROWS as ancestors are pulled in later
    // rounds: a pend already considered for C can resolve to an inherited method that was not visible the
    // first time. Whenever the blob set has changed, the class reconsiders every pend. (Conservative -- any
    // new blob might be an ancestor -- but the rounds after class-pulling stops are the incremental ones,
    // and they are the majority.) markDefaults needs no epoch: an interface matches against its OWN methods.
    private static int[] pdPendEpoch;
    private static int[] pdVirtEpoch;
    private static int[] pdDfltTo;                       // per blob: pends matched by markDefaults
    private static int[] pdSeeded;                       // per blob: seedNativelyReached done
    private static int[] pdSeedC;                        // per blob: seedClinits done
    private static int pendPullTo;                       // pends already looked up in the class dir
    private static int virtFrom;                         // matchLevel/matchDefaults: ignore pends below this

    private static int[] pnBucket;                       // registered class names, by hash -> pd index
    private static int[] pnNext;                         // chain link, per blob
    private static int pnIndexed;                        // blobs [0,pnIndexed) are indexed with a valid pdNameOff

    /**
     * Index the registered class names, for {@link #nameRegistered}. Built right after {@link #probeAll}, which
     * is what fills {@code pdNameOff} -- before that a blob has no name to hash.
     *
     * <p>The pull loop asks {@code nameRegistered} once per pend, and a linear scan made that
     * {@code pends x blobs} string compares per round. With 24,826 pends over 448 blobs it was 11M per round,
     * 33 rounds -- 54% of the entire mark on hardware, and the largest single item left after the first two
     * rounds of this work.
     */
    private static void buildNameIndex()
    {
        if (pnBucket == null)
        {
            pnBucket = new int[PVTAB];
            pnNext = new int[MAXBLOB];
        }
        int i = 0;
        while (i < PVTAB)
        {
            pnBucket[i] = -1;
            i += 1;
        }
        i = 0;
        while (i < pdCount)
        {
            int h = utf8Hash(pdBase[i], pdNameOff[i]) & (PVTAB - 1);
            pnNext[i] = pnBucket[h];
            pnBucket[h] = i;
            i += 1;
        }
        pnIndexed = pdCount;
    }

    /** Register the class named at Utf8 offset {@code off} in {@code base} if it's embedded; returns its base or 0. */
    private static long registerNameFromDir(long base, int off)
    {
        long namePtr = base + off + 2;                 // skip the u2 length prefix
        int len = u2(base + off);
        // java/lang/Object is every class's superclass, so auto-pulling it from the dir would drag it into
        // EVERY closure (changing every vtable). It's an implicit root: loaded only when a demo explicitly
        // seeds it (the HashMap closure does, so String there inherits its hashCode/equals slots).
        if (utf8IsAtBase(base, off, Magic.bytes("java/lang/Object")))
        {
            return 0L;
        }
        if (isDenylisted(base, off))                   // metal-absent subtree: prune (a call to it traps, see patchRelocs)
        {
            return 0L;
        }
        long bytes = VM.dirBytes(namePtr, len);
        if (bytes == 0L || alreadyBlob(bytes))
        {
            return 0L;                                 // unembedded root (Object/Magic), or already pulled this pass
        }
        if (!LOAD_TRACE)
        {
            addBlob(bytes, (int) VM.dirLen(namePtr, len));
            return bytes;
        }
        long t0 = Magic.readCNTPCT_EL0();
        addBlob(bytes, (int) VM.dirLen(namePtr, len));
        long us = elapsedUs(t0);
        Uart.write(Magic.bytes("  load "));
        writeName(namePtr, len);
        Uart.putc(0x20);
        printDur(us);
        Uart.putc(0x0A);
        return bytes;
    }

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
    private static boolean alreadyBlob(long bytes)
    {
        int i = 0;
        while (i < pdCount)
        {
            if (pdBase[i] == bytes)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** Write {@code len} raw name bytes to the UART (a '/'-separated internal class name). */
    private static void writeName(long p, int len)
    {
        int i = 0;
        while (i < len)
        {
            Uart.putc((byte) u1(p + i));
            i += 1;
        }
    }

    /** #43 fault diagnostic: name the loaded class whose Type node is {@code type} (the wild-branch receiver's
     *  class), over the UART, or nothing if unknown. */
    static void reportClassOfType(long type)
    {
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                Uart.write(Magic.bytes(" class="));
                writeName(clTab[i].base + clTab[i].nameOff + 2, u2(clTab[i].base + clTab[i].nameOff));
                Uart.write(Magic.bytes(" gvCount="));
                VM.printDec(clTab[i].vtCount);
                return;
            }
            i += 1;
        }
        Uart.write(Magic.bytes(" class=? (unregistered Type)"));
    }

    /** Print the loaded class name whose Type node is {@code type} (just the bare name), or a placeholder.
     *  Used by {@code Throwable.printStackTrace()} for the header line. */
    static void printClassName(long type)
    {
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                writeName(clTab[i].base + clTab[i].nameOff + 2, u2(clTab[i].base + clTab[i].nameOff));
                return;
            }
            i += 1;
        }
        Uart.write(Magic.bytes("<exception>"));
    }

    /** M4 {@code Class.getName()}: a fresh guest String of {@code type}'s dotted binary name (registry
     *  name bytes, '/'->'.'), or 0 if the Type isn't in the loaded registry. */
    static long classNameString(long type)
    {
        int len = classNameLen(type);
        if (len <= 0)
        {
            return 0L;
        }
        long arr = Heap.allocArray(len, 1);
        writeClassName(type, arr + 24L, 0);
        long obj = Heap.alloc(stringSize());
        Magic.store64(obj + 0L, stringTib());
        Magic.store64(obj + 16L, arr);                  // value byte[]; coder@24 stays 0 = LATIN1
        return obj;
    }

    private static long[] primTypeCache;                 // primitive Type per atype-style index (see primTypeIdx)

    /** Index 0..8 for a JVMS primitive descriptor char (Z C F D B S I J V), or -1. */
    private static int primTypeIdx(int c)
    {
        if (c == 0x5A) { return 0; }                    // 'Z' boolean
        if (c == 0x43) { return 1; }                    // 'C' char
        if (c == 0x46) { return 2; }                    // 'F' float
        if (c == 0x44) { return 3; }                    // 'D' double
        if (c == 0x42) { return 4; }                    // 'B' byte
        if (c == 0x53) { return 5; }                    // 'S' short
        if (c == 0x49) { return 6; }                    // 'I' int
        if (c == 0x4A) { return 7; }                    // 'J' long
        if (c == 0x56) { return 8; }                    // 'V' void
        return -1;
    }

    /**
     * The Type node backing {@code int.class} and friends, created on demand and cached. It is a real heap
     * node rather than a small sentinel in the mirror deliberately: every {@code Class} native dereferences
     * the mirror's Type word, so a tiny value there would have to be guarded at each of them.
     */
    static long primitiveType(int descChar)
    {
        int idx = primTypeIdx(descChar);
        if (idx < 0)
        {
            return 0L;
        }
        if (primTypeCache == null)
        {
            primTypeCache = new long[9];
        }
        if (primTypeCache[idx] == 0L)
        {
            long type = Heap.allocData(ObjectModel.TYPE_SIZE);
            Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET,
                    ObjectModel.PRIM_TYPE_TAG | (long) descChar);
            Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, 0L);         // no super: instanceof stops here
            Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, 0L);
            primTypeCache[idx] = type;
        }
        return primTypeCache[idx];
    }

    /** The {@code Class} mirror for a primitive, e.g. {@code int.class}. Identity-stable via the mirror cache. */
    static long primitiveMirror(int descChar)
    {
        long t = primitiveType(descChar);
        return t == 0L ? 0L : classMirror(t);
    }

    /** True if {@code type} is a primitive Type ({@link ObjectModel#PRIM_TYPE_TAG}). */
    static boolean isPrimitiveType(long type)
    {
        if (type == 0L)
        {
            return false;
        }
        long instSize = Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET);
        return (instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.PRIM_TYPE_TAG;
    }

    /** The descriptor char of a primitive Type ('I'), or 0. */
    private static int primTypeChar(long type)
    {
        if (!isPrimitiveType(type))
        {
            return 0;
        }
        return (int) (Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET) & 0xFFL);
    }

    /**
     * Install {@code Integer.TYPE} and friends. The writer cannot bake these: the seed JVM's value is a HOST
     * {@code java.lang.Class}, which has no image representation, so the snapshot stores 0 and {@code int.class}
     * -- which javac compiles to {@code getstatic Integer.TYPE}, not to an {@code ldc} -- reads null. Seeded
     * here for the same reason {@code System.out} and the Integer cache are. No-op for classes not in the batch.
     */
    static void seedPrimitiveTypes()
    {
        seedPrimType(Magic.bytes("java/lang/Integer"), 0x49);      // 'I'
        seedPrimType(Magic.bytes("java/lang/Long"), 0x4A);         // 'J'
        seedPrimType(Magic.bytes("java/lang/Double"), 0x44);       // 'D'
        seedPrimType(Magic.bytes("java/lang/Float"), 0x46);        // 'F'
        seedPrimType(Magic.bytes("java/lang/Short"), 0x53);        // 'S'
        seedPrimType(Magic.bytes("java/lang/Byte"), 0x42);         // 'B'
        seedPrimType(Magic.bytes("java/lang/Character"), 0x43);    // 'C'
        seedPrimType(Magic.bytes("java/lang/Boolean"), 0x5A);      // 'Z'
        seedPrimType(Magic.bytes("java/lang/Void"), 0x56);         // 'V'
    }

    private static void seedPrimType(byte[] cls, int descChar)
    {
        long slot = staticSlotOf(cls, Magic.bytes("TYPE"));
        if (slot != 0L)
        {
            Magic.store64(slot, primitiveMirror(descChar));
        }
    }

    /** True if {@code type} is an array Type (its instanceSize slot carries {@link ObjectModel#ARRAY_TYPE_TAG}). */
    static boolean isArrayType(long type)
    {
        if (type == 0L)
        {
            return false;
        }
        long instSize = Magic.load64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET);
        return (instSize & ObjectModel.ARRAY_TYPE_TAG_MASK) == ObjectModel.ARRAY_TYPE_TAG;
    }

    /**
     * The JVMS descriptor char of a PRIMITIVE array Type's element ('I' for {@code int[]}), or 0.
     *
     * <p>Recovered by identity against the per-atype cache rather than from the Type itself, because the
     * element size cannot tell {@code byte[]} from {@code boolean[]} (both 1) or {@code int[]} from
     * {@code float[]} (both 4) -- and because that works for a writer-BAKED array Type the loader merely
     * adopted, which no metal-side field would have been filled in for.
     */
    private static int primElemCharOf(long type)
    {
        int atype = 4;
        while (atype < 12)
        {
            long tib = primArrTib[atype];
            if (tib != 0L && Magic.load64(tib) == type)
            {
                return primDescChar(atype);
            }
            atype += 1;
        }
        return 0;
    }

    /** Length of {@code type}'s dotted binary name, or 0 if it has none (unregistered / unresolved element). */
    private static int classNameLen(long type)
    {
        if (isPrimitiveType(type))
        {
            return primNameLen(primTypeChar(type));
        }
        if (isArrayType(type))
        {
            long el = Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
            if (el == 0L)
            {
                return primElemCharOf(type) == 0 ? 0 : 2;        // "[I"; 0 = a ref array whose element is unresolved
            }
            int n = elemDescLen(el);
            return n == 0 ? 0 : 1 + n;
        }
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                return u2(clTab[i].base + clTab[i].nameOff);
            }
            i += 1;
        }
        return 0;
    }

    /** The Java source name of a primitive descriptor char ("int"), as bytes. */
    private static byte[] primNameBytes(int c)
    {
        if (c == 0x5A) { return Magic.bytes("boolean"); }
        if (c == 0x43) { return Magic.bytes("char"); }
        if (c == 0x46) { return Magic.bytes("float"); }
        if (c == 0x44) { return Magic.bytes("double"); }
        if (c == 0x42) { return Magic.bytes("byte"); }
        if (c == 0x53) { return Magic.bytes("short"); }
        if (c == 0x49) { return Magic.bytes("int"); }
        if (c == 0x4A) { return Magic.bytes("long"); }
        return Magic.bytes("void");
    }

    private static int primNameLen(int c)
    {
        return c == 0 ? 0 : primNameBytes(c).length;
    }

    private static int writePrimName(int c, long dst, int pos)
    {
        byte[] nm = primNameBytes(c);
        int k = 0;
        while (k < nm.length)
        {
            Magic.store8(dst + pos + k, nm[k]);
            k += 1;
        }
        return pos + nm.length;
    }

    /** Length of an array element's DESCRIPTOR form: "[I" for a nested array, "Ljava.lang.String;" otherwise. */
    private static int elemDescLen(long el)
    {
        if (isArrayType(el))
        {
            return classNameLen(el);
        }
        int n = classNameLen(el);
        return n == 0 ? 0 : n + 2;                               // 'L' + name + ';'
    }

    /** Write {@code type}'s name at {@code dst+pos} (dots for '/'); returns the position after it. */
    private static int writeClassName(long type, long dst, int pos)
    {
        if (isPrimitiveType(type))
        {
            return writePrimName(primTypeChar(type), dst, pos);
        }
        if (isArrayType(type))
        {
            Magic.store8(dst + pos, (byte) 0x5B);                // '['
            pos += 1;
            long el = Magic.load64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET);
            if (el == 0L)
            {
                Magic.store8(dst + pos, (byte) primElemCharOf(type));
                return pos + 1;
            }
            if (isArrayType(el))
            {
                return writeClassName(el, dst, pos);             // nested: "[[I"
            }
            Magic.store8(dst + pos, (byte) 0x4C);                // 'L'
            pos = writeClassName(el, dst, pos + 1);
            Magic.store8(dst + pos, (byte) 0x3B);                // ';'
            return pos + 1;
        }
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                int len = u2(clTab[i].base + clTab[i].nameOff);
                long src = clTab[i].base + clTab[i].nameOff + 2;
                int k = 0;
                while (k < len)
                {
                    int c = u1(src + k);
                    Magic.store8(dst + pos + k, (byte) (c == 0x2F ? 0x2E : c));   // '/' -> '.'
                    k += 1;
                }
                return pos + len;
            }
            i += 1;
        }
        return pos;
    }

    /**
     * {@code Class.forName0(byte[])} native: resolve a binary class name (raw ASCII bytes, dots) to its Class
     * mirror, loading the class incrementally into the LIVE program if it is not already loaded. Returns 0 (=>
     * the guest throws {@code ClassNotFoundException}) if the name contains '/' (not a valid binary name), is
     * not embedded in the classDir, or resolves to no loaded Type.
     */
    static long forNameMirror(long nameArr)
    {
        if (nameArr <= 0x1000L)
        {
            return 0L;                                  // boot force-compile guard passes 0
        }
        int len = (int) Magic.load64(nameArr + 16L);    // byte[] length @16
        long src = nameArr + 24L;                        // byte[] elements @24
        byte[] slash = new byte[len];
        int i = 0;
        while (i < len)
        {
            int c = u1(src + i);
            if (c == 0x2F)                              // '/' is not a valid binary-name char -> not found
            {
                return 0L;
            }
            slash[i] = (byte) (c == 0x2E ? 0x2F : c);   // '.' -> '/' (classDir keys are internal names)
            i += 1;
        }
        int ci = classIndexByName(slash);
        if (ci >= 0)
        {
            ensureClinit(ci);                               // JVMS 5.5: Class.forName(name) INITIALIZES the class
            return classMirror(clTab[ci].type);             // already loaded: cached mirror (identity-stable)
        }
        long type = loadClassIncremental(slash);
        ensureClinit(classRegByType(type));             // JVMS 5.5: forName INITIALIZES, and this batch's
        return type == 0L ? 0L : classMirror(type);     // runClinits left a gated initializer pending
    }

    /**
     * Pull class {@code slash} (an internal '/'-separated name) and its dependency closure into the running
     * program WITHOUT {@link #resetLoader} — the registries are append-only and prior blobs are skipped
     * ({@code pdDone}/{@code pdDoneB}), so the live program's compiled code + heap survive. Every method of the
     * pulled class is seeded reachable (reflection may invoke any). Returns its Type, or 0 if not embedded.
     */
    private static long loadClassIncremental(byte[] slash)
    {
        long blob = pullClass(slash);
        if (blob == 0L)
        {
            return 0L;                                  // not embedded in the classDir
        }
        // Seed the class's <clinit> as the reachability root: it (and everything it transitively calls) gets
        // compiled + run, and the class's structure/Type/mirror is registered by loadStructure regardless. The
        // class's OTHER methods are compiled LAZILY when reflectively invoked (M2) — eagerly seeding them all
        // pulled a huge closure into the 2nd (incremental) batch and corrupted the heap.
        stubBlob(blob);                                 // vtable-complete without pulling its closure (see stubBlob)
        entryPoint(blob, Magic.bytes("<clinit>"), Magic.bytes("()V"));   // may be absent (addReach(0) is a no-op)
        loadAll();
        int ci = classIndexByName(slash);
        return ci < 0 ? 0L : clTab[ci].type;
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
    static long defineFromBytes(long byteArr, int off, int len)
    {
        if (byteArr <= 0x1000L || len <= 0)
        {
            return 0L;                                  // boot force-compile guard / empty input
        }
        long blob = Heap.allocData(len);                // raw offset-0 base: classfile byte 0 lives at blob+0
        long srcAddr = byteArr + 24L + off;             // byte[] elements @24, plus the caller's offset
        int i = 0;
        while (i < len)
        {
            Magic.store8(blob + i, (byte) Magic.load8(srcAddr + i));
            i += 1;
        }
        addBlob(blob, len);
        rootBlob(blob);                                 // EVERY method is a root: nothing loaded calls into a
                                                        //   class the program just handed us (see rootBlob)
        entryPoint(blob, Magic.bytes("<clinit>"), Magic.bytes("()V"));   // enables the mark; <clinit> may be absent
        loadAll();
        int ci = 0;                                     // find the class we just added by its unique blob base
        while (ci < clCount)
        {
            if (clTab[ci].base == blob)
            {
                return clTab[ci].type;
            }
            ci += 1;
        }
        return 0L;
    }

    /** M4 {@code Thread.currentThread()}: a bare guest {@code java/lang/Thread} (no ctor run; fields null)
     *  wrapping a VM-created task, or 0 if Thread isn't in the loaded batch. */
    static long allocThreadObj()
    {
        int i = classIndexByName(Magic.bytes("java/lang/Thread"));
        if (i < 0)
        {
            return 0L;
        }
        long obj = Heap.alloc(16 + clTab[i].fieldCount * 8);
        Magic.store64(obj + 0L, clTab[i].tib);
        return obj;
    }

    /** Print the ONE demand-compiled method (or {@code <clinit>}) containing {@code addr} as a single line
     *  "class.method +0xoff" -- the printStackTrace frame formatter (compact vs {@link #reportMethodAt}). */
    /**
     * Whether {@code pc} lies in the same code block as the compiled buffer {@code buf}.
     *
     * <p>The pc -> method lookups pick the nearest registered buffer at-or-below the pc, which has no upper
     * bound of its own: an address in an UNREGISTERED buffer -- a lambda thunk, a line table, a stub, or
     * plain garbage from a derailed unwind -- silently borrows the name of whatever method sits below it.
     * Every bogus pc then reports as the same method (the one with the highest registered buffer), which is
     * how a fault report came to blame {@code LinkedKeySet.toArray} for addresses that were not code at all,
     * and {@code String.<clinit>+0xC8} for a buffer that was never String's.
     *
     * <p>A method's buffer IS a code block, so the block containing {@code buf} is the method's extent and a
     * pc outside it belongs to something else. An unregistered block ({@code 0}) is ACCEPTED rather than
     * rejected: that is the pre-existing behaviour, and image/native addresses must keep resolving.
     */
    private static boolean inSameCodeBlock(long buf, long pc)
    {
        long end = Heap.codeBlockEndAt(buf);
        return end == 0L || pc < end;
    }

    /**
     * Name the nearest registered body BELOW {@code addr}, ignoring the code-block bound. A frame that no
     * table claims is either compiled by a path that never registers it, or registered but rejected because
     * the pc ran past its block end -- and only the distance to the nearest entry tells those apart.
     */
    private static void printNearestBelow(long addr)
    {
        long best = 0L;
        int bestReg = -1;
        int i = 0;
        while (i < rgCount)
        {
            if (rgTab[i].buf != 0L && rgTab[i].buf <= addr && rgTab[i].buf > best)
            {
                best = rgTab[i].buf;
                bestReg = i;
            }
            i += 1;
        }
        if (bestReg < 0)
        {
            return;
        }
        Uart.write(Magic.bytes(" after "));
        writeName(rgTab[bestReg].base + rgTab[bestReg].classOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].classOff));
        Uart.putc(0x2E);
        writeName(rgTab[bestReg].base + rgTab[bestReg].nameOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].nameOff));
        Uart.write(Magic.bytes(" +"));
        VM.printHex(addr - best);
        Uart.write(Magic.bytes(" end="));
        VM.printHex(Heap.codeBlockEndAt(best));
    }

    static void printFrameAt(long addr)
    {
        long bestBuf = 0L;
        int bestReg = -1;
        int bestClin = -1;
        int i = 0;
        while (i < rgCount)
        {
            if (rgTab[i].buf != 0L && rgTab[i].buf <= addr && rgTab[i].buf > bestBuf
                    && inSameCodeBlock(rgTab[i].buf, addr)) { bestBuf = rgTab[i].buf; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < clinitN)
        {
            if (clinitEntry[c] != 0L && clinitEntry[c] <= addr && clinitEntry[c] > bestBuf
                    && inSameCodeBlock(clinitEntry[c], addr)) { bestBuf = clinitEntry[c]; bestClin = c; bestReg = -1; }
            c += 1;
        }
        if (bestReg < 0 && bestClin < 0)
        {
            if (!printImageFrameAt(addr))                            // writer-compiled VM/driver code: image symbol table
            {
                Uart.write(Magic.bytes("<unclaimed pc="));
                VM.printHex(addr);
                printNearestBelow(addr);                        // the only handle on a frame no table claims
                Uart.putc(0x3E);

            }
            return;
        }
        if (bestReg >= 0)
        {
            writeName(rgTab[bestReg].base + rgTab[bestReg].classOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].classOff));
            Uart.putc(0x2E);
            writeName(rgTab[bestReg].base + rgTab[bestReg].nameOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].nameOff));
            int line = lineAtOffset(rgTab[bestReg].line, (int) ((addr - bestBuf) >> 2));   // (pc-base)/4 = word offset
            if (rgTab[bestReg].src != 0L)
            {
                Uart.putc(0x28);                                 // '('
                writeName(rgTab[bestReg].src + 2, u2(rgTab[bestReg].src));   // SourceFile filename
                if (line >= 0)
                {
                    Uart.putc(0x3A);                             // ':'
                    VM.printDec(line);
                }
                Uart.putc(0x29);                                 // ')'
            }
        }
        else
        {
            int pd = clinitPd[bestClin];
            writeName(pdBase[pd] + pdNameOff[pd] + 2, u2(pdBase[pd] + pdNameOff[pd]));
            Uart.write(Magic.bytes(".<clinit>"));
        }
        Uart.write(Magic.bytes(" [pc="));                        // debug: absolute PC + offset into the method
        VM.printHex(addr);                                       // printHex already prints the "0x" prefix
        Uart.write(Magic.bytes(" +"));
        VM.printHex(addr - bestBuf);
        Uart.putc(0x5D);                                         // ']'
    }

    /**
     * Resolve a writer-compiled (image) code address via the embedded image symbol table (built by
     * ImageBuilder): entries {@code {codeStart, codeEnd, nameAddr, srcAddr, lineAddr}} (5 longs). Prints
     * {@code owner/Class.method(SourceFile.java:line) [pc=... +off]} like a loaded frame, or returns false
     * if {@code addr} is in no image method. Linear scan (traces are rare).
     */
    private static boolean printImageFrameAt(long addr)
    {
        long tab = VM.imageSymTable;
        if (tab == 0L)
        {
            return false;
        }
        long n = VM.imageSymCount;
        long i = 0;
        while (i < n)
        {
            long e = tab + i * 40L;                              // 5 longs per entry
            long start = Magic.load64(e);
            if (addr >= start && addr < Magic.load64(e + 8L))
            {
                long nameAddr = Magic.load64(e + 16L);
                long srcAddr = Magic.load64(e + 24L);
                writeName(nameAddr + 2, u2(nameAddr));           // "owner/Class.method"
                int srcLen = u2(srcAddr);
                if (srcLen > 0)
                {
                    Uart.putc(0x28);                             // '('
                    writeName(srcAddr + 2, srcLen);              // SourceFile filename
                    int line = lineAtOffset(Magic.load64(e + 32L), (int) ((addr - start) >> 2));
                    if (line >= 0)
                    {
                        Uart.putc(0x3A);                         // ':'
                        VM.printDec(line);
                    }
                    Uart.putc(0x29);                             // ')'
                }
                Uart.write(Magic.bytes(" [pc="));
                VM.printHex(addr);
                Uart.write(Magic.bytes(" +"));
                VM.printHex(addr - start);
                Uart.putc(0x5D);
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** #43 fault diagnostic: name the demand-compiled method whose code contains {@code addr} (the highest
     *  registered buffer base <= addr), plus the byte offset into it, over the UART. */
    static void reportMethodAt(long addr)
    {
        // Print the (up to) 4 registered methods whose buffer base is nearest at-or-below addr, closest first,
        // each with base + delta. If methods are densely packed the first line is the container; a large delta
        // to the next-lower base means addr sits in a gap (an unregistered/synthetic method).
        int printed = 0;
        long ceil = addr + 1L;                             // strictly-below-ceil watermark; walks downward
        while (printed < 5)
        {
            long bestBuf = 0L;
            int bestReg = -1;
            int bestClin = -1;
            int i = 0;
            while (i < rgCount)                            // registered cross-class methods
            {
                if (rgTab[i].buf != 0L && rgTab[i].buf < ceil && rgTab[i].buf > bestBuf)
                {
                    bestBuf = rgTab[i].buf; bestReg = i; bestClin = -1;
                }
                i += 1;
            }
            int c = 0;
            while (c < clinitN)                            // compiled <clinit>s (NOT in rgBuf)
            {
                if (clinitEntry[c] != 0L && clinitEntry[c] < ceil && clinitEntry[c] > bestBuf)
                {
                    bestBuf = clinitEntry[c]; bestClin = c; bestReg = -1;
                }
                c += 1;
            }
            if (bestReg < 0 && bestClin < 0) { break; }
            Uart.write(Magic.bytes("\n    @0x"));
            VM.printHex(bestBuf);
            Uart.write(Magic.bytes(" (+0x"));
            VM.printHex(addr - bestBuf);
            Uart.write(Magic.bytes(") "));
            if (bestReg >= 0)
            {
                writeName(rgTab[bestReg].base + rgTab[bestReg].classOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].classOff));
                Uart.putc(0x2E);
                writeName(rgTab[bestReg].base + rgTab[bestReg].nameOff + 2, u2(rgTab[bestReg].base + rgTab[bestReg].nameOff));
            }
            else
            {
                int pd = clinitPd[bestClin];
                writeName(pdBase[pd] + pdNameOff[pd] + 2, u2(pdBase[pd] + pdNameOff[pd]));
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
        long rt = runnableTypeAddr();
        int slot = runnableRunSlot();
        long buf = Heap.allocCode(96);
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 0, 0));         w += 1;  // x17 = receiver.tib
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 17, 0));        w += 1;  // x17 = Type
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 17, 16));       w += 1;  // x17 = itable dir
        Magic.store32(buf + w * 4L, A64Enc.movz(15, (int) (rt & 0xFFFFL), 0));          w += 1;  // x15 = Runnable Type
        Magic.store32(buf + w * 4L, A64Enc.movk(15, (int) ((rt >> 16) & 0xFFFFL), 1));  w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(15, (int) ((rt >> 32) & 0xFFFFL), 2));  w += 1;
        Magic.store32(buf + w * 4L, A64Enc.ldrx(16, 17, 0));        w += 1;  // loop: x16 = entry.interfaceType
        Magic.store32(buf + w * 4L, A64Enc.cmpReg(16, 15));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.bcond(0, 3));            w += 1;  // b.eq found
        Magic.store32(buf + w * 4L, A64Enc.addImm(17, 17, 16));     w += 1;  // next entry
        Magic.store32(buf + w * 4L, A64Enc.b(-4));                  w += 1;  // back to loop
        Magic.store32(buf + w * 4L, A64Enc.ldrx(16, 17, 8));        w += 1;  // found: x16 = Runnable's itable
        Magic.store32(buf + w * 4L, A64Enc.ldrx(16, 16, slot * 8)); w += 1;  // x16 = run() buffer
        Magic.store32(buf + w * 4L, A64Enc.blr(16));                w += 1;  // run() with x0 = receiver
        long te = VM.taskExitAddr;
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) te, 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) (te >> 16), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.blr(16));                w += 1;  // taskExit() — never returns
        Heap.publishCode(buf, buf + w * 4L);
        VM.runTrampAddr = buf;
    }

    /** java/lang/Runnable's ONE Type (0 if not loaded: no Runnables can exist yet). */
    private static long runnableTypeAddr()
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, Magic.bytes("java/lang/Runnable")))
            {
                return clTab[i].type;
            }
            i += 1;
        }
        return 0L;
    }

    /** run()V's slot in Runnable's flattened per-interface run (0 if Runnable isn't loaded). */
    private static int runnableRunSlot()
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, Magic.bytes("java/lang/Runnable")))
            {
                int s = 0;
                while (s < clTab[i].ifmCount)
                {
                    int k = clTab[i].ifmStart + s;
                    if (isName(ifBase[k], ifNameOff[k], 0x72756EL, 3)
                            && isName(ifBase[k], ifDescOff[k], 0x282956L, 3))
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
        parseConstPool(VM.integerBytes, (int) VM.integerLen);
        int[] seen = new int[64];
        int seenN = 0;
        int c = 1;
        while (c < gcpCount)
        {
            int t = gcpTag[c];
            if (t == 9 || t == 10 || t == 11)           // Fieldref / Methodref / InterfaceMethodref
            {
                int classOff = refClassNameOff(c);
                int k = 0;
                while (k < seenN && seen[k] != classOff)
                {
                    k += 1;
                }
                if (k == seenN && seenN < 64 && !utf8IsAtBase(gbase, classOff, Magic.bytes("java/lang/Integer")))
                {
                    seen[seenN] = classOff;
                    seenN += 1;
                    Uart.write(Magic.bytes("    needs "));
                    writeName(gbase + classOff + 2, u2(gbase + classOff));
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
     * access ({@code gbase + off}) the rest of the loader uses.
     */
    private static void parseConstPool(long base, int len)
    {
        gbase = base;
        int ci = 0;
        while (ci < pcN && pcBase[ci] != base)
        {
            ci += 1;
        }
        if (ci < pcN)                                   // cache HIT: reuse the one-time copy + parse for this blob
        {
            gbytes = pcBytes[ci];
            gcp = pcCp[ci];
            gcpTag = pcCpTag[ci];
            litObjByCp = pcLitObj[ci];
            gcpCount = pcCpCount[ci];
            gAfterCp = pcAfterCp[ci];
            gp = base + gAfterCp;
            return;
        }
        gbytes = toBytes(base, len);
        gcpCount = ClassReader.cpCount(gbytes);
        gcp = new int[gcpCount];
        gcpTag = new int[gcpCount];
        litObjByCp = new long[gcpCount];                // per-blob ldc-String intern cache (one object per cp entry)
        gAfterCp = ClassReader.constantPool(gbytes, gcp, gcpTag);   // stable: gp is later reused as a walk cursor
        gp = base + gAfterCp;
        if (pcN < MAXPARSECACHE)                         // remember it: RTA/phase-A/phase-B all re-parse this blob
        {
            pcBase[pcN] = base;
            pcBytes[pcN] = gbytes;
            pcCp[pcN] = gcp;
            pcCpTag[pcN] = gcpTag;
            pcLitObj[pcN] = litObjByCp;
            pcCpCount[pcN] = gcpCount;
            pcAfterCp[pcN] = gAfterCp;
            pcN += 1;
        }
    }

    /** Copy an embedded blob onto the heap so the shared reader can index it. */
    private static byte[] toBytes(long addr, int len)
    {
        byte[] b = new byte[len];
        int i = 0;
        while (i < len)
        {
            b[i] = (byte) Magic.load8(addr + i);
            i += 1;
        }
        return b;
    }

    /** Parse the fields, assigning each static field a slot; allocate a zeroed statics block. */
    private static void parseFields()
    {
        long p = gp;
        gIsInterface = (u2(p) & 0x0200) != 0;             // ACC_INTERFACE
        gThisNameOff = gcp[u2(gbase + gcp[u2(p + 2)])];   // this_class -> Class -> name Utf8 offset
        gSuperNameOff = u2(p + 4) == 0 ? 0 : gcp[u2(gbase + gcp[u2(p + 4)])];   // super_class -> name
        int islot = superFieldCount();                  // own instance fields sit after inherited ones
        p += 6;                                         // access_flags, this_class, super_class
        gImplIfCount = u2(p);                           // interfaces this class implements
        gImplIfName = new int[gImplIfCount];
        int ii = 0;
        while (ii < gImplIfCount)
        {
            int ci = u2(p + 2 + ii * 2);                // Class entry index
            gImplIfName[ii] = gcp[u2(gbase + gcp[ci])]; // -> interface name Utf8 offset
            ii += 1;
        }
        p += 2 + gImplIfCount * 2;                      // interfaces
        int fcount = u2(p);
        p += 2;
        gsfName = new int[fcount + 1];
        gifName = new int[fcount + islot + 1];
        gifAccess = new int[fcount + islot + 1];
        gifDescOff = new int[fcount + islot + 1];
        int slot = 0;
        int f = 0;
        while (f < fcount)
        {
            int access = u2(p);
            int nameIdx = u2(p + 2);
            int descIdx = u2(p + 4);
            p += 6;                                     // access, name, descriptor
            if ((access & 0x0008) != 0)
            {
                gsfName[slot] = gcp[nameIdx];    // ACC_STATIC
                slot += 1;
            }
            else
            {
                gifName[islot] = gcp[nameIdx];   // instance field at the next inherited+own slot
                gifAccess[islot] = access;       // reflection: getModifiers / access checks
                gifDescOff[islot] = gcp[descIdx];// reflection: field type descriptor
                islot += 1;
            }
            int attrs = u2(p);
            p += 2;
            p = skipAttributes(p, attrs);
            f += 1;
        }
        gsfCount = slot;
        gifCount = islot;                               // total: inherited + own
        gvCount = 0;                                    // no vtable unless parseVtable runs
        gMethodsStart = p;                              // methods_count follows the fields
        gStatics = Heap.allocData(slot * 8 + 8);
        int z = 0;
        while (z < slot)
        {
            Magic.store64(gStatics + z * 8, 0L);    // statics default to 0
            z += 1;
        }
        gp = p;
    }

    /**
     * Build this class's flattened vtable: inherit the superclass's slots (copied
     * from the super's registered vtable — signature + already-compiled impl
     * buffer), then walk this class's own virtual methods, an override replacing the
     * inherited slot in place (so it keeps the super's index) and a new method
     * appending. buildTib later fills the TIB from these slots.
     */
    private static void parseVtable(long bytes)
    {
        gvCount = 0;
        int superReg = classRegByName(gSuperNameOff);
        if (superReg >= 0)
        {
            inheritVtable(gSuperNameOff);               // copy the super's flattened slots
        }
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (isVirtual(u2(p), gcp[u2(p + 2)]))
            {
                int slot = findVtSlot(gcp[u2(p + 2)], gcp[u2(p + 4)]);   // override an inherited slot?
                if (slot < 0)
                {
                    if (gvCount >= MAXMV) { capHalt(Magic.bytes("MAXMV-vt"), gvCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
                    slot = gvCount;                     // else append a new slot
                    gvCount += 1;
                }
                gvTab[slot].base = gbase;
                gvTab[slot].name = gcp[u2(p + 2)];
                gvTab[slot].desc = gcp[u2(p + 4)];
                gvTab[slot].implCode = findCode(bytes, p + 8, attrs);   // this class's own impl
                gvTab[slot].implBuf = 0L;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Copy the registered flattened vtable of the class named {@code superOff} into gv[]. */
    private static void inheritVtable(int superOff)
    {
        int i = 0;
        while (i < vtCount)
        {
            if (utf8EqAt(gbase, superOff, vtClassBase[i], vtClassOff[i]))
            {
                int slot = vtSlot[i];
                gvTab[slot].base = vtNameBase[i];
                gvTab[slot].name = vtNameOff[i];
                gvTab[slot].desc = vtDescOff[i];
                gvTab[slot].implBuf = vtBuf[i];             // inherited (already-compiled) impl
                gvTab[slot].implCode = 0L;
                if (slot + 1 > gvCount)
                {
                    gvCount = slot + 1;
                }
            }
            i += 1;
        }
    }

    /** Flattened-vtable slot whose name+descriptor match, or -1 (used for override detection). */
    private static int findVtSlot(int nameOff, int descOff)
    {
        int s = 0;
        while (s < gvCount)
        {
            if (utf8EqAt(gbase, nameOff, gvTab[s].base, gvTab[s].name)
                    && utf8EqAt(gbase, descOff, gvTab[s].base, gvTab[s].desc))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /** Instance-field count of the superclass (inherited fields), 0 if super is Object/unloaded. */
    private static int superFieldCount()
    {
        int r = classRegByName(gSuperNameOff);
        return r >= 0 ? clTab[r].fieldCount : 0;
    }

    /** A method goes in the vtable if it is instance, non-private, and not a constructor. */
    private static boolean isVirtual(int access, int nameOff)
    {
        if ((access & 0x0008) != 0)
        {
            return false;                               // static
        }
        if (isName(gbase, nameOff, 0x3C696E69743EL, 6))
        {
            return false;                               // "<init>"
        }
        if (isName(gbase, nameOff, 0x3C636C696E69743EL, 8))
        {
            return false;                               // "<clinit>"
        }
        // PRIVATE instance methods get a vtable slot too: javac (11+ nestmates) compiles a private-method
        // call as invokevirtual, so it must resolve through the vtable like any other. (They still never
        // override a superclass slot in practice; a same-name+desc collision is a rare non-issue.)
        return true;
    }

    /** Absolute address of the static field referenced by constant-pool Fieldref {@code idx}. */
    static long staticAddr(int idx)
    {
        int nameOff = ClassReader.refNameOff(gbytes, gcp, idx);  // Fieldref -> name Utf8 offset
        int s = 0;
        while (s < gsfCount)                            // same class: match by name Utf8 offset
        {
            if (gsfName[s] == nameOff)
            {
                return gStatics + s * 8;
            }
            s += 1;
        }
        noteInitNeeded(classRegOf(u2(gbase + gcp[idx])));   // cross-class static access = an active use
        return globalStaticAddr(idx);                   // another class (e.g. Long -> Integer.digits)
    }

    /** Slot address of a static field declared in another loaded class, matched by class+name, or 0. */
    private static long globalStaticAddr(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int i = 0;
        while (i < sgCount)
        {
            if (utf8EqAt(gbase, classOff, sgTab[i].base, sgTab[i].classOff)
                    && utf8EqAt(gbase, nameOff, sgTab[i].base, sgTab[i].nameOff))
            {
                return sgTab[i].addr;
            }
            i += 1;
        }
        return 0L;                                      // unresolved (class not loaded yet) -> reloc will patch it
    }

    /** From {@code gp} (at the methods), return the bytecode address of the sought method. */
    private static long findMethod(long base)
    {
        long p = gp;
        gMethodsStart = p;                              // remember for on-demand callee lookup
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            p += 2;                                     // access_flags
            int nameIdx = u2(p);
            int descIdx = u2(p + 2);
            p += 4;
            int attrs = u2(p);
            p += 2;
            if (isName(base, gcp[nameIdx], gnameP, gnameLen)
                    && isName(base, gcp[descIdx], gdescP, gdescLen))
            {
                long code = findCode(base, p, attrs);
                if (code != 0L)
                {
                    gFoundDescOff = gcp[descIdx];       // for the shared core's prologue
                    gFoundStatic = (access & 0x0008) != 0 ? 1 : 0;
                    return code;
                }
            }
            else
            {
                p = skipAttributes(p, attrs);
            }
            m += 1;
        }
        return 0L;
    }

    /** Find the Code attribute among {@code attrs} at {@code p}; return the bytecode address. */
    private static long findCode(long base, long p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p += 2;
            int alen = u4(p);
            p += 4;
            if (isName(base, gcp[anIdx], 0x436f6465L, 4))              // "Code"
            {
                gMaxLocals = u2(p + 2);                 // after max_stack(2)
                gcodeLen = u4(p + 4);
                return p + 8;
            }
            p += alen;
            a += 1;
        }
        return 0L;
    }

    private static long skipAttributes(long p, int attrs)
    {
        int a = 0;
        while (a < attrs)
        {
            p += 2;
            int alen = u4(p);
            p += 4 + alen;
            a += 1;
        }
        return p;
    }

    /** Compare the Utf8 at {@code off} against {@code expected} (bytes packed big-endian, {@code len} bytes). */
    private static boolean isName(long base, int off, long expected, int len)
    {
        if (off == 0)
        {
            return false;
        }
        if (u2(base + off) != len)
        {
            return false;
        }
        long got = 0L;
        int j = 0;
        while (j < len)
        {
            got = (got << 8) | u1(base + off + 2 + j);
            j += 1;
        }
        return got == expected;
    }

    // ----- on-metal JIT: drive the shared compiler/Baseline core over each method --
    // A method and its callees form a small program; we assign every reachable
    // method a buffer before emitting any, so invokestatic's BL targets are known
    // without compiling nested-and-reentrant (the shared static compile state and
    // the writer-side 10-local ceiling both make on-the-fly recursion awkward).
    private static final int MAXM = 512;
    private static long[] mCode;      // each reachable method's bytecode address
    private static int[] mLen;        // ... and its length
    private static long[] mBuf;       // ... and the buffer assigned to it
    private static long[] mLine;      // ... its line table addr (built in emitMethod; registerAll copies -> rgLine)
    private static long[] mSrc;       // ... its class's SourceFile filename Utf8 addr (-> rgSrc)
    private static int[] mLocals;     // ... its max_locals
    private static int[] mDescOff;    // ... its descriptor Utf8 offset (for the shared core's prologue)
    private static int[] mStatic;     // ... 1 if static
    private static int[] mDefer;      // M8 defer: 1 if this method's compile is deferred to first call (gated)
    private static int mCount;

    /** The on-metal symbol seam: resolves the shared Baseline core's references to addresses. */
    private static final MetalSymbols METAL_SYMBOLS = new MetalSymbols();
    private static long gExcSlot;   // one heap word holding the in-flight exception during athrow

    /** Address of the metal in-flight-exception slot (the writer's vm/VM.$exception). */
    static long exceptionSlotAddr()
    {
        if (gExcSlot == 0L)
        {
            gExcSlot = Heap.allocData(8);
        }
        return gExcSlot;
    }





    /**
     * Compile the entry method and every static method it transitively calls,
     * then return the entry's buffer. Three flat passes — discover (BFS the call
     * graph), place (size each method and hand it a buffer), emit (now every BL
     * target address is known) — so no method's compile nests inside another's.
     * Scope: same-class static callees, no recursion/cycles beyond dedup.
     */
    private static long compile(long code, int len, int descOff, int isStatic)
    {
        allocMethodTables();
        addMethod(code, len, gMaxLocals, descOff, isStatic);
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
            buildTib();                                 // -- rebuilding here would allocate a THROWAWAY TIB and leave
        }                                               // gTib pointing at it, so compileClass fills the wrong TIB.
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        Heap.publishCode(Heap.CODE_BASE, Magic.load64(Heap.CODE_PTR_CELL));   // I-cache maintenance over the JIT buffers
        return mBuf[0];
    }
    // When set, compile() reuses the caller's already-allocated gTib (two-phase clinit) instead of rebuilding it.
    private static boolean compileReuseTib;

    /**
     * Compile <em>every</em> method of the current class into its own buffer (in
     * this class's context), then publish. Used for cross-class loading: each class
     * is compiled whole so its methods can be registered and linked from others.
     * Cross-class calls in the body resolve through the global registry, so classes
     * a method depends on must be compiled+registered first (no cross-class cycles).
     */
    private static void compileClass(long bytes)
    {
        allocMethodTables();
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)                              // seed all of the class's methods
        {
            int attrs = u2(p + 6);
            long code = findCode(bytes, p + 8, attrs);
            // A stub-blob's own virtual methods are kept even when RTA prunes them, so the class's vtable has
            // no holes -- but STUB ONLY, so nothing of theirs is pulled into this batch (see stubBlob).
            boolean stubOnly = gbase == gStubBlob && gStubBlob != 0L && code != 0L
                    && !isReach(code) && isVirtual(u2(p), gcp[u2(p + 2)]);
            if (code != 0L && (markActive == 0 || isReach(code) || stubOnly))
            {
                int isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                if (phaseACelled(gcp[u2(p + 2)], gcp[u2(p + 4)]))
                {
                    // Metadata-only: this static already has a structure-time cell (armPhaseACells), so it is
                    // never sized, emitted or registered here -- it compiles on first call through that cell.
                }
                else
                {
                    addMethod(code, gcodeLen, gMaxLocals, gcp[u2(p + 4)], isStatic);
                    // Everything else takes the other route to the same engine: a deferral stub standing in
                    // for the body (statics went the cell way above). Only <init> is still compiled here --
                    // <clinit> now defers too, since lazy initialization means loading a class no longer runs
                    // it (see notInit).
                    boolean defer = stubOnly                    // a stub-blob virtual: NEVER compile the body here
                            || (stage2Gated(gbase, gThisNameOff) && notInit(gcp[u2(p + 2)]));
                    if (defer && mCount > 0 && mCode[mCount - 1] == code)
                    {
                        mDefer[mCount - 1] = 1;         // compile this method on first call, not now
                    }
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        int i = 0;
        while (i < mCount)                              // place
        {
            sizeMethod(i);
            i += 1;
        }
        if (!gIsInterface)                              // an interface has no instances -> no vtable/TIB to fill; its
        {                                               // concrete static/default methods are still compiled above
            fillTib();                                  // TIB was allocated by loadOne (before <clinit>); fill slots now
        }
        i = 0;
        while (i < mCount)                              // emit
        {
            emitMethod(i);
            i += 1;
        }
        Heap.publishCode(Heap.CODE_BASE, Magic.load64(Heap.CODE_PTR_CELL));   // I-cache maintenance over the JIT buffers
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
    private static void capHalt(byte[] which, int count)
    {
        Uart.write(Magic.bytes("\nCAP EXCEEDED: "));
        Uart.write(which);
        Uart.write(Magic.bytes(" count="));
        VM.printDec(count);
        Uart.putc(0x0A);
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
     * dependency discovery never reaches them. When one is missing {@link #newExc} has no TIB to give the
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
        long scratch = Heap.allocData(name.length + 8);
        int i = 0;
        while (i < name.length)
        {
            Magic.store8(scratch + i, name[i]);
            i += 1;
        }
        long bytes = VM.dirBytes(scratch, name.length);
        if (bytes == 0L)
        {
            return false;
        }
        int k = 0;
        while (k < pdCount)
        {
            if (pdBase[k] == bytes)
            {
                return true;
            }
            k += 1;
        }
        return false;
    }

    private static void addBlob(long bytes, int len)
    {
        int i = 0;
        while (i < pdCount)                             // dedup by address: the same class may be referenced by
        {                                               // several others and reached twice in one closure pass
            if (pdBase[i] == bytes)
            {
                return;
            }
            i += 1;
        }
        if (pdCount >= MAXBLOB) { capHalt(Magic.bytes("MAXBLOB"), pdCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        pdBase[pdCount] = bytes;
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
     * compiles to an unresolved call ({@link #globalBuf} -> 0), and later blobs resolve back to it.
     */
    private static void loadAll()
    {
        long tAll = Magic.readCNTPCT_EL0();
        long tMark = tAll;
        ensureObjectBlob();                              // every vtable starts with Object's 9 slots
        if (gEntryBlob != 0L)                            // reachability requested: mark + PULL the reachable
        {                                                // closure on demand (no pre-pull-all resolveClosureFromDir)
            markReachable();
        }
        ensureImplicitExcBlobs();                        // AFTER the closure is pulled -- the gate asks whether
                                                         //   this batch has Throwable, which markReachable is what
                                                         //   brings in; before it, the answer is always no
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
                    loadStructure(pdBase[i], pdLen[i]);
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
                    loadStructure(pdBase[j], pdLen[j]);
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
        while (remainingB > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < pdCount)
            {
                if (pdDoneB[i] == 0 && superReadyB(i))
                {
                    loadBodies(pdBase[i], pdLen[i]);
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
                    loadBodies(pdBase[j], pdLen[j]);
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
        patchRelocs();                                  // every body is compiled now: fix up the cross-class method
                                                        // CALLs left unresolved while their target compiled later
        long tRest = Magic.readCNTPCT_EL0();
        refillImaps();                                  // repair default-method imap slots left 0 by phase-B ordering
        refillArrayTibVtables();                        // Object's vtable is filled now -> repair any array TIB that
                                                        // was created (e.g. by an early string-literal byte[]) before it
        // Seed BEFORE runClinits: a <clinit> can call these (StreamOpFlag.<clinit> builds an EnumMap -- needs the
        // JLA -- and boxes flag values via Integer.valueOf -- needs the IntegerCache). The seeds are independent
        // of any <clinit> (they build the JLA object / boxed caches directly), so running them first is sound.
        seedJavaLangAccess();                           // SharedSecrets.javaLangAccess (EnumMap.getKeyUniverse)
        seedIntegerCache();                             // the [-128,127] Integer cache valueOf uses (clinit skipped:
                                                        //   low=high=0 would index the NULL cache -> NPE); no-op if
                                                        //   Integer isn't in this batch
        seedLongCache();                                // same for Long$LongCache (fixed -128..127, no `high`)
        seedPrimitiveTypes();                           // Integer.TYPE etc: int.class is a getstatic, not an ldc
        runClinits();                                   // NOW run each compiled <clinit>: its cross-class calls are patched
        if (LOAD_PROFILE)
        {
            profileLoadAll(tAll, tMark, tProbe, tA, tB, tPatch, tRest);
        }
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
        while (lc < clCount)
        {
            if (clTab[lc].state >= RVMClass.ST_INSTANTIATED
                    && lazyClinitGated(clTab[lc].base, clTab[lc].nameOff) && clinitPendingFor(lc))
            {
                lcPend += 1;
            }
            else if (clTab[lc].state >= RVMClass.ST_INSTANTIATED)
            {
                clTab[lc].state = RVMClass.ST_INITIALIZED;
                lcOk += 1;
            }
            else
            {
                Uart.write(Magic.bytes("  lifecycle DIFF "));
                writeName(clTab[lc].base + clTab[lc].nameOff + 2, u2(clTab[lc].base + clTab[lc].nameOff));
                Uart.write(Magic.bytes(" state="));
                VM.printDec(clTab[lc].state);
                Uart.putc(0x0A);
            }
            lc += 1;
        }
        Uart.write(Magic.bytes("  lifecycle OK "));
        VM.printDec(lcOk);
        if (lcPend > 0)                                 // deliberately uninitialized: waiting for first active use
        {
            Uart.write(Magic.bytes(" (+"));
            VM.printDec(lcPend);
            Uart.write(Magic.bytes(" lazy-init pending)"));
        }
        Uart.putc(0x0A);
        VM.byteArrayTibCache = byteArrayTib();          // type concat results ([B TIB) so stock getBytes can
                                                        //   checkcast/clone a concat String's value
        markActive = 0;                                 // don't leak the reachability state past this batch
        gEntryBlob = 0L;
        gRootBlob = 0L;
        gStubBlob = 0L;
        pendBase = null;                                // free the mark's large scratch arrays for the GC
        pendClass = null;
        pendName = null;
        pendDesc = null;
        pendKind = null;
    }

    /** Record each blob's own name and every class it names (its {@code Class} entries). */
    private static void probeAll()
    {
        dpCount = 0;
        stringPdIndex = -1;
        int i = 0;
        while (i < pdCount)
        {
            parseConstPool(pdBase[i], pdLen[i]);
            pdNameOff[i] = gcp[u2(gbase + gcp[u2(gp + 2)])];   // this_class -> name
            probeInheritance(i);                               // super + direct interfaces (phase-A ordering edges)
            if (utf8IsAtBase(pdBase[i], pdNameOff[i], Magic.bytes("java/lang/String")))
            {
                stringPdIndex = i;
            }
            pdNeedsString[i] = false;
            int c = 1;
            while (c < gcpCount)
            {
                if (gcpTag[c] == 7)                     // CONSTANT_Class
                {
                    addDep(i, gcp[u2(gbase + gcp[c])]);
                }
                else if (gcpTag[c] == 8 || gcpTag[c] == 18)   // CONSTANT_String / CONSTANT_InvokeDynamic:
                {
                    pdNeedsString[i] = true;            // materializes a String via newStringFromBytes (baked TIB)
                }
                c += 1;
            }
            i += 1;
        }
    }

    /**
     * Record blob {@code i}'s superclass + directly-implemented interface names (the acyclic edges phase A
     * orders by). Reads the class-file header at {@code gp} (access_flags), left current by the caller's
     * {@code parseConstPool}. Decomposed into locals so the host writer's operand stack stays shallow. A
     * CONSTANT_Class cp entry at index {@code c} has {@code gcp[c]} = its name_index offset; the name Utf8
     * offset is {@code gcp[nameIndex]} (mirrors probeAll's this_class parse).
     */
    private static void probeInheritance(int i)
    {
        int superCi = u2(gp + 4);
        pdSuperOff[i] = 0;
        if (superCi != 0)
        {
            int superNameIx = u2(gbase + gcp[superCi]);
            pdSuperOff[i] = gcp[superNameIx];
        }
        long p = gp + 6;
        int ifc = u2(p);
        p += 2;
        int n = 0;
        int k = 0;
        while (k < ifc && n < MAX_DIRECT_IF)
        {
            int ifCi = u2(p + k * 2);
            int ifNameIx = u2(gbase + gcp[ifCi]);
            pdIfOff[i * MAX_DIRECT_IF + n] = gcp[ifNameIx];
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
        if (pdSuperOff[i] != 0 && blocked(i, pdSuperOff[i]))
        {
            return false;
        }
        int k = 0;
        while (k < pdIfN[i])
        {
            if (blocked(i, pdIfOff[i * MAX_DIRECT_IF + k]))
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
                    && utf8EqAt(pdBase[i], off, pdBase[j], pdNameOff[j]))
            {
                return true;
            }
            j += 1;
        }
        return false;
    }

    private static void addDep(int owner, int nameOff)
    {
        if (dpCount >= MAXDEP) { capHalt(Magic.bytes("MAXDEP"), dpCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        dpOwner[dpCount] = owner;
        dpOff[dpCount] = nameOff;
        dpCount += 1;
    }

    /** True if no dependency of blob {@code i} names a blob that is still unloaded. */
    private static boolean ready(int i)
    {
        // A blob that materializes a String (string literal / concat) bakes String's TIB+size at compile time
        // (newStringFromBytes) but carries no CONSTANT_Class dep on it — so hold it until java/lang/String's
        // Type is registered, else it compiles against TIB=0 and yields a malformed String.
        if (pdNeedsString[i] && stringPdIndex >= 0 && stringPdIndex != i && pdDone[stringPdIndex] == 0)
        {
            return false;
        }
        int d = 0;
        while (d < dpCount)
        {
            if (dpOwner[d] == i && blocked(i, dpOff[d]))
            {
                return false;
            }
            d += 1;
        }
        return true;
    }

    /** True if some other still-unloaded blob declares the class named at {@code off}. */
    private static boolean blocked(int i, int off)
    {
        int j = 0;
        while (j < pdCount)
        {
            if (j != i && pdDone[j] == 0
                    && utf8EqAt(pdBase[i], off, pdBase[j], pdNameOff[j]))
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
    private static void loadStructure(long bytes, int len)
    {
        parseConstPool(bytes, len);
        gClassModifiers = u2(gp) & ~0x0020;             // class access_flags (gp is here, post-cp), ACC_SUPER stripped;
                                                        //   cached for Class.getModifiers() with NO extra parse (an
                                                        //   extra runtime/load re-parse corrupted the heap). Nested
                                                        //   classes report raw flags — InnerClasses override is TODO.
        parseFields();                                  // hierarchy-aware field layout (allocates gStatics)
        findBootstrapMethods();                         // locate BootstrapMethods (for invokedynamic), if any
        if (gIsInterface)
        {
            if (clCount >= MAXCLASS) { capHalt(Magic.bytes("MAXCLASS-iface"), clCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
            registerInterface();                        // give its methods global itable indices
            // Give the interface a Type and register it, so implementors' itable
            // directories can key on it and the core's interfaceType resolves (M5.4.e).
            // M8 itables: a BAKED interface ADOPTS the writer's Type node instead -- implementors'
            // dir entries then key on the shared node, so interface instanceof/checkcast in linked
            // baked code matches loader receivers. The adopted node's fields stand as written
            // (superType = Object's shared node; nothing reads an interface Type's instanceSize).
            findVtSig();                                // adoption entry (itparity compares later, post-fill)
            adoptStatics();                             // interface constants share the writer block too
            if (gAdoptType != 0L)
            {
                gType = gAdoptType;
                Uart.write(Magic.bytes("  typeadopt "));
                printNameAt(gbase, gThisNameOff);
                Uart.putc(0x0A);
            }
            else
            {
                gType = Heap.allocData(ObjectModel.TYPE_SIZE);
                Magic.store64(gType + 0, 0L);           // instanceSize (not instantiated)
                Magic.store64(gType + 8, 0L);           // superType
                Magic.store64(gType + 16, 0L);          // no itableDir
                Magic.store64(gType + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET, 0L);
                Magic.store64(gType + ObjectModel.TYPE_DEPTH_OFFSET, -1L);   // interface: never in a chain
                Magic.store64(gType + ObjectModel.TYPE_DISPLAY_OFFSET, 0L);
                // O(1) interface checks: number this interface from the shared counter (the writer
                // used 1..N-1; IDs >= 128 stay 0 = unnumbered -> those targets keep the dir walk).
                long nid = VM.ifaceIdNext;
                Magic.store64(gType + ObjectModel.TYPE_IMPLEMENTS_OFFSET,
                              nid > 0L && nid < 128L ? nid : 0L);
                Magic.store64(gType + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L, 0L);
                if (nid > 0L && nid < 128L)
                {
                    VM.ifaceIdNext = nid + 1L;
                }
            }
            clTab[clCount] = new RVMClass();
            clTab[clCount].state = RVMClass.ST_LOADED;
            clTab[clCount].base = gbase;
            clTab[clCount].nameOff = gThisNameOff;
            clTab[clCount].tib = 0L;
            clTab[clCount].type = gType;
            clTab[clCount].fieldCount = 0;
            clTab[clCount].vtCount = 0;
            clTab[clCount].statics = gStatics;              // interface constants block (reused by its phase-B bodies)
            clTab[clCount].vtStart = vtCount;               // no vtable entries appended for an interface
            clTab[clCount].isIface = true;
            clTab[clCount].ifmStart = gIfmStart;            // the flattened per-interface method run
            clTab[clCount].ifmCount = gIfmCount;            //   = this interface's itable slot numbering
            clTab[clCount].superReg = classRegByName(gSuperNameOff);   // an interface's super is Object (-1); kept for symmetry
            captureDirectIfaces();                      // an interface's extended interfaces (List extends Iterable)
            clTab[clCount].modifiers = gClassModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
            checkIfParity(clCount);                     // M8 itables: writer/loader slot numbering must agree
            clTab[clCount].state = RVMClass.ST_RESOLVED;    // lifecycle: structure complete
            clCount += 1;
            return;                                     // bodies (default/static methods) compiled in phase B
        }
        parseVtable(bytes);                             // flatten against the superclass: SLOT numbering (bufs still 0)
        checkVtParity();                                // M8 unification: writer/loader slot numbering must agree
        adoptStatics();                                 // M8 statics unification: ONE static block per baked class
        allocTib();                                     // allocate Type + empty TIB at a stable address (gTib)
        registerClassStructure();                       // class + fields + statics + vtable STRUCTURE (bufs 0)
        clTab[clCount - 1].modifiers = gClassModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
        clTab[clCount - 1].state = RVMClass.ST_RESOLVED;    // lifecycle: structure complete
    }

    /**
     * PHASE B -- compile every method body of an already-structure-registered class and fill its TIB. Runs in
     * superclass-first order so this class's parseVtable inherits its super's now-filled vtable buffers and its
     * fillTib is complete. All cross-class new/field/vtable/itable/cast targets are registered (phase A), so the
     * only unresolved refs left are method CALLS into not-yet-compiled bodies -- patchRelocs fixes those after.
     */
    private static void loadBodies(long bytes, int len)
    {
        parseConstPool(bytes, len);
        parseFields();                                  // re-derive layout (gImplIf*, gMethodsStart); gStatics is throwaway
        int reg = classRegByName(gThisNameOff);
        gStatics = clTab[reg].statics;                      // REUSE the phase-A static block (cross-class getstatic keys on it)
        findBootstrapMethods();
        if (clTab[reg].isIface)
        {
            gType = clTab[reg].type;                        // restore the interface's phase-A Type so a default method's
                                                        // self-reference (typeOfClass -> gType, e.g. Map.putIfAbsent
                                                        // calling this.get()) bakes the REAL interface Type -- else
                                                        // it bakes a stale gType and the implementor's itable-dir
                                                        // walk never matches (invokeinterface sentinel NPE).
            compileClass(bytes);                        // interface CONCRETE methods (static like List.of + defaults)
            registerAll();
            clTab[reg].state = RVMClass.ST_INSTANTIATED;    // lifecycle: bodies done
            return;
        }
        parseVtable(bytes);                             // NOW the super's vtBuf is filled -> inherited slot bufs are real
        gType = clTab[reg].type;                            // restore this class's Type + TIB (allocated in phase A)
        gTib = clTab[reg].tib;
        compileReuseTib = true;                         // keep runClinit's compile() from reallocating gTib (would
        runClinit(bytes);                               //   leave compileClass filling a throwaway TIB, not clTab[reg].tib)
        compileReuseTib = false;
        gType = clTab[reg].type;                            // (runClinit's compile leaves gType/gTib alone now, but be safe)
        gTib = clTab[reg].tib;
        provideKnownStatics();                          // seed static tables a skipped <clinit> would have built
        compileClass(bytes);                            // compile all methods; fillTib fills the (phase-A-allocated) TIB
        registerAll();                                  // methods -> globalBuf
        fillClassVtBuf(reg);                            // fill this class's registered vtable buffers (for subclasses)
        clTab[reg].state = RVMClass.ST_INSTANTIATED;    // lifecycle: bodies + TIB + itables done
    }

    /**
     * Seed the static tables a skipped {@code <clinit>} would have built, for known real java.base classes.
     * Currently {@code java/lang/Integer.digits} (the radix digit chars {@code Integer.toHexString} /
     * {@code formatUnsignedInt} index) — its real {@code <clinit>} also calls {@code Class.getPrimitiveClass}
     * (a native), so we provide just the table. gStatics is this class's block; the compiled getstatic reads
     * the same slot address.
     */
    private static void provideKnownStatics()
    {
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/String")))
        {
            long cs = staticSlotByName(Magic.bytes("COMPACT_STRINGS"));
            if (cs != 0L)
            {
                Magic.store64(cs, 1L);                  // LATIN1 world: stock getstatic COMPACT_STRINGS -> true
            }
            return;
        }
        if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/StringUTF16")))
        {
            // Its <clinit> sets the UTF-16 byte-order shifts via Unsafe.isBigEndian (unrunnable, skipped),
            // leaving BOTH shifts 0 -- putChar then stores the LOW byte twice and a char > 0xFF truncates
            // (the euro U+20AC read back as 0xAC). AArch64 runs little-endian: HI=0 (default), LO=8.
            long lo = staticSlotByName(Magic.bytes("LO_BYTE_SHIFT"));
            if (lo != 0L)
            {
                Magic.store64(lo, 8L);
            }
            return;
        }
        if (!utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/lang/Integer")))
        {
            return;
        }
        long slot = staticSlotByName(Magic.bytes("digits"));
        if (slot == 0L)
        {
            return;
        }
        long tab = Heap.allocArray(36, 1);              // '0'..'9','a'..'z' (radix digits, LATIN1)
        int i = 0;
        while (i < 36)
        {
            Magic.store8(tab + 24L + i, (byte) (i < 10 ? 0x30 + i : 0x61 + i - 10));
            i += 1;
        }
        Magic.store64(slot, tab);
    }

    /** Address of the current class's static field named {@code name}, or 0 if it has none. */
    private static long staticSlotByName(byte[] name)
    {
        int s = 0;
        while (s < gsfCount)
        {
            if (utf8IsAtBase(gbase, gsfName[s], name))
            {
                return gStatics + s * 8L;
            }
            s += 1;
        }
        return 0L;
    }

    /** Slot address of a static field {@code cls.name} from the global registry (any loaded class), or 0. */
    private static long staticSlotOf(byte[] cls, byte[] name)
    {
        int i = 0;
        while (i < sgCount)
        {
            if (utf8IsAtBase(sgTab[i].base, sgTab[i].classOff, cls) && utf8IsAtBase(sgTab[i].base, sgTab[i].nameOff, name))
            {
                return sgTab[i].addr;
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Seed {@code Integer$IntegerCache} (call after loadAll, once Integer is loaded): build the real
     * {@code Integer[256]} for -128..127 (each a boxed Integer with its {@code value}) and set {@code cache}
     * + {@code high}=127. Its real {@code <clinit>} is CDS/system-property driven (unrunnable), so we build
     * the table directly — the way {@code valueOf} expects it, so small-int boxing returns cached instances.
     */
    static void seedIntegerCache()
    {
        long cacheSlot = staticSlotOf(Magic.bytes("java/lang/Integer$IntegerCache"), Magic.bytes("cache"));
        long highSlot = staticSlotOf(Magic.bytes("java/lang/Integer$IntegerCache"), Magic.bytes("high"));
        int ii = classIndexByName(Magic.bytes("java/lang/Integer"));
        if (cacheSlot == 0L || highSlot == 0L || ii < 0)
        {
            return;
        }
        long itib = clTab[ii].tib;
        int isize = 16 + clTab[ii].fieldCount * 8;          // Integer: header + its instance fields (value)
        long arr = Heap.allocArray(256, 8);             // Integer[256] (8-byte reference elements)
        int k = 0;
        while (k < 256)
        {
            long box = Heap.alloc(isize);
            Magic.store64(box + 0L, itib);              // TIB
            Magic.store64(box + 16L, (long) (k - 128)); // value (Integer's first/only instance field, offset 16)
            Magic.store64(arr + 24L + k * 8L, box);
            k += 1;
        }
        Magic.store64(cacheSlot, arr);
        Magic.store64(highSlot, 127L);
    }

    /**
     * Seed {@code Long$LongCache} like {@link #seedIntegerCache}: a {@code Long[256]} for -128..127 (each a
     * boxed Long with its {@code value} at offset 16). LongCache is fixed-range (no {@code high} field), and
     * its {@code <clinit>} is skipped with the wrapper's (native TYPE), so {@code Long.valueOf} in that range
     * would index a null cache. No-op if Long isn't in this batch.
     */
    static void seedLongCache()
    {
        long cacheSlot = staticSlotOf(Magic.bytes("java/lang/Long$LongCache"), Magic.bytes("cache"));
        int li = classIndexByName(Magic.bytes("java/lang/Long"));
        if (cacheSlot == 0L || li < 0)
        {
            return;
        }
        long ltib = clTab[li].tib;
        int lsize = 16 + clTab[li].fieldCount * 8;          // Long: header + its instance field (value)
        long arr = Heap.allocArray(256, 8);             // Long[256] (8-byte reference elements)
        int k = 0;
        while (k < 256)
        {
            long box = Heap.alloc(lsize);
            Magic.store64(box + 0L, ltib);              // TIB
            Magic.store64(box + 16L, (long) (k - 128)); // value (Long's first/only instance field, offset 16)
            Magic.store64(arr + 24L + k * 8L, box);
            k += 1;
        }
        Magic.store64(cacheSlot, arr);
    }

    /**
     * Install {@code System.out} / {@code System.err} with a metal {@link java.io.PrintStream} overlay (call
     * after {@code loadAll} for a batch whose closure includes {@code java/lang/System} + {@code java/io/PrintStream}).
     * Stock {@code System.initPhase1}/{@code setOut0} that would set these are native-heavy and unrunnable, so we
     * allocate a bare PrintStream instance (the overlay is field-free — a 16-byte header with just its TIB, no
     * ctor call needed) and drop it into each static slot. {@code getstatic System.out} then reads a real object
     * and {@code invokevirtual println} dispatches through the overlay's vtable. No-op if either class is absent.
     */
    static void seedSystemStreams()
    {
        int pi = classIndexByName(Magic.bytes("java/io/PrintStream"));
        if (pi < 0)
        {
            return;
        }
        long ptib = clTab[pi].tib;
        int psize = 16 + clTab[pi].fieldCount * 8;          // field-free overlay -> 16, but honor any fields it declares
        long outSlot = staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("out"));
        if (outSlot != 0L)
        {
            long ps = Heap.alloc(psize);
            Magic.store64(ps + 0L, ptib);               // TIB (vtable for println dispatch)
            Magic.store64(outSlot, ps);
        }
        long errSlot = staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("err"));
        if (errSlot != 0L)
        {
            long ps = Heap.alloc(psize);
            Magic.store64(ps + 0L, ptib);
            Magic.store64(errSlot, ps);
        }
    }

    /** Byte offset of instance field {@code fname} declared by registered class {@code classIdx}, or -1.
     *  The byte[]-name sibling of {@link #vhFieldOffset} (which keys on a raw pointer + a TIB). */
    private static long instanceFieldOffset(int classIdx, byte[] fname)
    {
        int j = 0;
        while (j < fldCount)
        {
            if (utf8EqAt(clTab[classIdx].base, clTab[classIdx].nameOff, fldTab[j].base, fldTab[j].classOff)
                    && utf8IsAtBase(fldTab[j].base, fldTab[j].nameOff, fname))
            {
                return 16L + fldTab[j].slot * 8L;
            }
            j += 1;
        }
        return -1L;
    }

    /**
     * Install {@code System.in} as an EMPTY stream — a {@code java.io.ByteArrayInputStream} over a zero-length
     * array, so it reads as immediate end-of-file. There is no console input on metal, but the field being
     * NULL is worse than it being empty: stock code passes {@code System.in} straight into a constructor that
     * null-checks it, so a test doing {@code new ZipInputStream(System.in)} died with a bare
     * NullPointerException before reaching anything it meant to test (java/util/zip/ZipInputStream/Skip).
     *
     * <p>Seeded rather than constructed, like {@link #seedSystemStreams}: the fields are filled directly at
     * their registered offsets, so no {@code <init>} has to run. Reading answers -1 without ever touching the
     * buffer, since {@code count} is 0.
     */
    static void seedSystemIn()
    {
        long inSlot = staticSlotOf(Magic.bytes("java/lang/System"), Magic.bytes("in"));
        if (inSlot == 0L)
        {
            return;
        }
        int bi = classIndexByName(Magic.bytes("java/io/ByteArrayInputStream"));
        if (bi < 0)
        {
            return;                                     // not in this batch: leave the slot as it was
        }
        long bufOff = instanceFieldOffset(bi, Magic.bytes("buf"));
        long posOff = instanceFieldOffset(bi, Magic.bytes("pos"));
        long cntOff = instanceFieldOffset(bi, Magic.bytes("count"));
        if (bufOff < 0L || posOff < 0L || cntOff < 0L)
        {
            return;
        }
        long obj = Heap.alloc(16 + clTab[bi].fieldCount * 8);
        Magic.store64(obj + 0L, clTab[bi].tib);
        Magic.store64(obj + bufOff, Heap.allocArray(0, 1));   // empty byte[]; never read (count == 0)
        Magic.store64(obj + posOff, 0L);
        Magic.store64(obj + cntOff, 0L);
        Magic.store64(inSlot, obj);
    }

    /**
     * Seed {@code sun.nio.ch.Net.EXTENDED_OPTIONS} with an {@link sun.net.ext.ExtendedSocketOptions} overlay
     * instance. Net.<clinit> (which normally sets it) is native-heavy and reads system properties (props are
     * null on metal -> NPE), so it stays blocked; but {@code Socket.close() -> Net.getSocketOption} derefs
     * EXTENDED_OPTIONS on the SO_LINGER path. The overlay's {@code isOptionSupported} is always false (no
     * jdk.net options on metal), so getSocketOption falls through to the ordinary getIntOption0 path. Same
     * direct-static-seed trick as {@link #seedSystemStreams}. No-op if either class is absent.
     */
    /**
     * Seed a {@code MetalJavaLangAccess} instance into {@code SharedSecrets.javaLangAccess} so
     * {@code EnumMap.getKeyUniverse} (-&gt; {@code getJavaLangAccess().getEnumConstantsShared()}) works. On stock
     * the JLA is registered by {@code System.<clinit>}, which the metal skips, leaving the field null. Runs inside
     * {@code loadAll} BEFORE {@code runClinits}, since {@code StreamOpFlag.<clinit>} builds an EnumMap. No-op when
     * neither class is in the batch (non-EnumMap programs). Field-free overlay -> just the TIB (itable dispatch).
     */
    static void seedJavaLangAccess()
    {
        int mi = classIndexByName(Magic.bytes("jdk/internal/access/MetalJavaLangAccess"));
        if (mi < 0)
        {
            return;
        }
        long slot = staticSlotOf(Magic.bytes("jdk/internal/access/SharedSecrets"), Magic.bytes("javaLangAccess"));
        if (slot != 0L)
        {
            long inst = Heap.alloc(16 + clTab[mi].fieldCount * 8);   // field-free -> header only
            Magic.store64(inst + 0L, clTab[mi].tib);                 // TIB (itable dir for getEnumConstantsShared dispatch)
            Magic.store64(slot, inst);
        }
    }

    static void seedNetExtendedOptions()
    {
        int ei = classIndexByName(Magic.bytes("sun/net/ext/ExtendedSocketOptions"));
        if (ei < 0)
        {
            return;
        }
        long slot = staticSlotOf(Magic.bytes("sun/nio/ch/Net"), Magic.bytes("EXTENDED_OPTIONS"));
        if (slot != 0L)
        {
            long inst = Heap.alloc(16 + clTab[ei].fieldCount * 8);   // field-free overlay -> just the TIB header
            Magic.store64(inst + 0L, clTab[ei].tib);                 // TIB (vtable for isOptionSupported dispatch)
            Magic.store64(slot, inst);
        }
    }

    /** Record a method (deduped by bytecode address; dedup also breaks cycles). */
    private static void addMethod(long code, int len, int maxLocals, int descOff, int isStatic)
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
        mCount += 1;
    }

    /** Allocate the per-batch method tables (discover/place/emit work set). */
    private static void allocMethodTables()
    {
        mCode = new long[MAXM];
        mLen = new int[MAXM];
        mBuf = new long[MAXM];
        mLine = new long[MAXM];
        mSrc = new long[MAXM];
        mLocals = new int[MAXM];
        mDescOff = new int[MAXM];
        mStatic = new int[MAXM];
        mDefer = new int[MAXM];
        mCount = 0;
    }

    /** Copy method {@code i}'s bytecode out of the current class blob into a heap byte[]. */
    private static byte[] extractCode(int i)
    {
        int off = (int) (mCode[i] - gbase);
        int len = mLen[i];
        byte[] c = new byte[len];
        int k = 0;
        while (k < len)
        {
            c[k] = gbytes[off + k];
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
                long c = calleeCodeOf(idx);                 // sets gcodeLen / gMaxLocals
                if (c != 0L)
                {
                    addMethod(c, gcodeLen, gMaxLocals, mrefDescOff(idx), op == 0xb8 ? 1 : 0);
                }
            }
            pc += insnLen(code, pc);
        }
    }

    /** Compile method {@code i} with the shared core (returns its A64 at {@code base}). */
    private static int[] compileMethod(int i, long base)
    {
        Baseline b = new Baseline(gbytes, gcp, gcpTag, METAL_SYMBOLS);
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
        int[] words = b.compileBody(extractCode(i), mDescOff[i], mStatic[i] != 0, mLocals[i], base, false);
        gFrameSize = b.frameSize();
        gRegLocals = b.regLocals();                    // for unwind's pre-try local restore
        gHN = b.handlerCount();                        // capture the machine-code handler ranges for emitMethod
        gHStartW = new int[gHN];
        gHEndW = new int[gHN];
        gHandlerW = new int[gHN];
        gHCatchCp = new int[gHN];
        int h = 0;
        while (h < gHN)
        {
            gHStartW[h] = b.handlerStartWord(h);
            gHEndW[h] = b.handlerEndWord(h);
            gHandlerW[h] = b.handlerWord(h);
            gHCatchCp[h] = ec[h];
            h += 1;
        }
        gBcToWord = b.bcToWord();                       // bci -> machine word offset, for the stack-trace line table
        return words;
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
            mBuf[i] = Heap.allocCode(32);               // just the stub -> no dry-run compile at load (genuine defer)
            return;
        }
        int sz0 = compileMethod(i, 0L).length * 4;
        if (sz0 >= 0x80000)
        {
            Uart.write(Magic.bytes("  HUGE body bytes="));
            VM.printHex((long) sz0);
            Uart.write(Magic.bytes(" for "));
            printCurrentClass();
            Uart.putc(0x0A);
        }
        mBuf[i] = Heap.allocCode(sz0);
    }

    /** Emit method {@code i}'s A64 (from the shared core) into its assigned buffer. */
    private static void emitMethod(int i)
    {
        if (mDefer[i] != 0)                             // deferred: install a stub; the body compiles on first call
        {
            emitDeferredStub(i);
            return;
        }
        codeRootOwner = mBuf[i];                        // roots baked by this compile belong to this buffer
        relocRecording = 1;                             // record unresolved cross-class sites at their real address
        int[] words = compileMethod(i, mBuf[i]);        // real base -> resolved addresses
        relocRecording = 0;
        codeRootOwner = 0L;
        mLine[i] = buildLineTable(i);                   // stack-trace debug info (PC-offset -> source line)
        mSrc[i] = sourceFileAddr();
        long out = mBuf[i];
        int k = 0;
        while (k < words.length)
        {
            Magic.store32(out, words[k]);
            out += 4;
            k += 1;
        }
        if (gFrameSize > 0)                             // let VM.unwind pop this JIT'd frame
        {
            VM.addJitFrame(mBuf[i], out, gFrameSize, gRegLocals);
        }
        int h = 0;                                      // register try/catch ranges: a throw in another JIT'd
        while (h < gHN)                                 // method can unwind into this method's catch (cross-method)
        {
            long ms = mBuf[i] + (long) gHStartW[h] * 4L;
            long me = mBuf[i] + (long) gHEndW[h] * 4L;
            long hh = mBuf[i] + (long) gHandlerW[h] * 4L;
            long ct = gHCatchCp[h] == 0 ? 0L : typeOfClass(gHCatchCp[h]);   // catch-type Type (0 = catch-all)
            VM.addJitHandler(ms, me, hh, ct);
            h += 1;
        }
    }

    /** True if {@code nameOff} is neither {@code <clinit>} nor {@code <init>} (the initializers must run at load). */
    /**
     * True if this method may take the deferral route -- everything except {@code <init>}.
     *
     * <p>{@code <clinit>} USED to be excluded too, on the stated grounds that "loading a class runs them".
     * The lazy-initialization arc (#114-#117) retired that premise: classes now initialize on first active
     * use, the eager list is empty, and initializers reach the engine through {@code ensureClinit} like any
     * other call. An eagerly compiled initializer for a class that never initializes is pure waste, and it
     * was expensive waste -- {@code java/lang/Character$UnicodeScript.<clinit>} compiles to 1.17 MB, was
     * emitted once per batch that loaded the class, and NEVER RAN. Four instances, ~4.7 MB, in an 8 MB
     * arena: roughly 70% of every code byte the VM emitted.
     *
     * <p>{@code <init>} stays eager. Constructors are reached through {@code invokespecial} on paths that
     * assume a compiled body, and they are small; the measured cost was entirely in {@code <clinit>}.
     */
    private static boolean notInit(int nameOff)
    {
        return !utf8IsAtBase(gbase, nameOff, Magic.bytes("<init>"));
    }

    /**
     * Install method {@code i}'s deferral stub into its (stub-sized) buffer instead of compiling the body:
     * capture everything {@code compile()} needs (blob/context/bytecode) into an lz entry, then emit a stub
     * that routes the first call through the shared trampoline. Because the buffer IS the method's registered
     * address AND its TIB vtable slot, every caller -- direct BL or virtual blr -- hits the stub, so the body
     * is compiled exactly once, on first use (memoized in {@link #lazyCompile}).
     */
    private static void emitDeferredStub(int i)
    {
        lazyEnsureTables();
        if (lzN >= MAXLAZY) { capHalt(Magic.bytes("MAXLAZY-defer"), lzN); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        int idx = lzN;
        int reg = classRegByName(gThisNameOff);
        int pd = findPdByName(gbase, gThisNameOff);
        lzTab[idx] = new LazyMethod();
        lzTab[idx].blob = gbase;
        lzTab[idx].len = pdLen[pd];
        lzTab[idx].reg = reg;
        lzTab[idx].nameOff = 0;
        lzTab[idx].descOff = mDescOff[i];
        lzTab[idx].slot = 0L;                               // no single slot to patch: the stub buffer is shared
        lzTab[idx].code = mCode[i];                         // compile straight from the captured bytecode
        lzTab[idx].codeLen = mLen[i];
        lzTab[idx].isStatic = mStatic[i];
        lzTab[idx].maxLocals = mLocals[i];
        lzTab[idx].cache = 0L;
        lzN += 1;
        long buf = mBuf[i];                             // stub-sized buffer allocated by sizeMethod
        noteStub(buf, idx);                             // ... and make it NAMEABLE: without this a fault landing
                                                        //   here reports stubIdx -1 and reads as an ordinary body
        Heap.pinCodeAt(buf);                            // the batch-path twin of buildLazyCompileStub: same stub,
                                                        //   same lifetime problem, same fix (see the note there)
        int w = 0;
        // x17/x16, NOT x9/x10: x0.. are the argument registers, so a stub that scratches x9/x10 destroys
        // the 10th and 11th arguments of the very call it is standing in for. x16/x17 are the architectural
        // intra-procedure scratch pair and are never arguments. (This is what broke demo deep10.)
        Magic.store32(buf + w * 4L, A64Enc.movz(17, idx & 0xFFFF, 0));                           w += 1;  // x17 = idx
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (lazyTrampAddr & 0xFFFF), 0));         w += 1;  // x16 = tramp
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.br(16));                                              w += 1;
        Heap.publishCode(buf, buf + w * 4L);
    }

    /** Buffer assigned to the method whose bytecode is at {@code code}. */
    private static long bufOf(long code)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return mBuf[i];
            }
            i += 1;
        }
        return 0L;                                       // not one of this class's compiled methods
    }

    /** Index into m*[] of the method whose bytecode is at {@code code}, or -1. */
    private static int methodIndexOf(long code)
    {
        int i = 0;
        while (i < mCount)
        {
            if (mCode[i] == code)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    // ----- stack-trace debug info: PC -> source line (SourceFile + LineNumberTable) -----

    /** Address of the current class's SourceFile filename Utf8 (persists in the blob), or 0 if none. */
    private static long sourceFileAddr()
    {
        int off = ClassReader.sourceFileNameOff(gbytes, gcp, gAfterCp, Magic.bytes("SourceFile"));
        return off < 0 ? 0L : gbase + off;
    }

    /**
     * Build method {@code i}'s persistent line table from {@code gBcToWord} (bci -> machine word offset, just
     * compiled) and the method's LineNumberTable. Layout: {@code {u32 count, (u32 wordOffset, u32 line) * count}}
     * -- one entry per source-line transition, in code-heap memory (persists, like the method buffer). The
     * frame resolver finds the largest {@code wordOffset <= (pc-base)/4} and reads its line. Returns 0 if the
     * class was compiled without line info (no {@code -g}).
     */
    private static long buildLineTable(int i)
    {
        if (gBcToWord == null)
        {
            return 0L;
        }
        int codeBodyOff = (int) (mCode[i] - gbase) - 8;   // Code attr body (max_stack) sits 8 bytes before code[]
        int lntOff = ClassReader.lineNumberTableOff(gbytes, gcp, codeBodyOff, Magic.bytes("LineNumberTable"));
        if (lntOff < 0)
        {
            return 0L;
        }
        int n = 0;
        int prev = -1;
        int bci = 0;
        while (bci < gBcToWord.length)                    // pass 1: count line transitions
        {
            if (gBcToWord[bci] >= 0)
            {
                int line = ClassReader.lineForBci(gbytes, lntOff, bci);
                if (line != prev)
                {
                    n += 1;
                    prev = line;
                }
            }
            bci += 1;
        }
        if (4 + n * 8 >= 0x80000)
        {
            Uart.write(Magic.bytes("  HUGE lineTable n="));
            VM.printDec(n);
            Uart.write(Magic.bytes(" for "));
            printCurrentClass();
            Uart.putc(0x0A);
        }
        long tab = Heap.allocCode(4 + n * 8);
        Magic.store32(tab, n);
        long w = tab + 4;
        prev = -1;
        bci = 0;
        while (bci < gBcToWord.length)                    // pass 2: emit (wordOffset, line) at each transition
        {
            if (gBcToWord[bci] >= 0)
            {
                int line = ClassReader.lineForBci(gbytes, lntOff, bci);
                if (line != prev)
                {
                    Magic.store32(w, gBcToWord[bci]);
                    Magic.store32(w + 4, line);
                    w += 8;
                    prev = line;
                }
            }
            bci += 1;
        }
        return tab;
    }

    /** Source line for machine word offset {@code wordOff} within a method's line table, or -1. */
    private static int lineAtOffset(long tab, int wordOff)
    {
        if (tab == 0L)
        {
            return -1;
        }
        int n = Magic.load32(tab);
        long w = tab + 4;
        int best = -1;
        int bestOff = -1;
        int i = 0;
        while (i < n)
        {
            int off = Magic.load32(w);
            if (off <= wordOff && off > bestOff)
            {
                bestOff = off;
                best = Magic.load32(w + 4);
            }
            w += 8;
            i += 1;
        }
        return best;
    }

    // ----- cross-class linking (global method registry) --------------------
    /** Register a compiled method so other classes can link to it by class+name+descriptor. */
    private static void register(long base, int classOff, int nameOff, int descOff, long buf, long lineTab, long srcAddr, int access)
    {
        if (rgCount >= MAXREG) { capHalt(Magic.bytes("MAXREG-method"), rgCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        rgTab[rgCount] = new RVMMethod();
        rgTab[rgCount].base = base;
        rgTab[rgCount].classOff = classOff;
        rgTab[rgCount].nameOff = nameOff;
        rgTab[rgCount].descOff = descOff;
        rgTab[rgCount].buf = buf;
        rgTab[rgCount].line = lineTab;
        rgTab[rgCount].src = srcAddr;
        rgTab[rgCount].access = access;
        rgCount += 1;
    }

    /** Register every compiled method of the current class (walk its methods table). */
    private static void registerAll()
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            long code = findCode(gbase, p + 8, attrs);
            if (code != 0L)
            {
                int mi = methodIndexOf(code);
                if (mi >= 0)
                {
                    register(gbase, gThisNameOff, gcp[u2(p + 2)], gcp[u2(p + 4)], mBuf[mi], mLine[mi], mSrc[mi], u2(p));
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Buffer of the method named by Methodref {@code idx} in another loaded class (registry lookup). */
    private static long globalBuf(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int i = 0;
        while (i < rgCount)
        {
            if (utf8EqAt(gbase, classOff, rgTab[i].base, rgTab[i].classOff)
                    && utf8EqAt(gbase, nameOff, rgTab[i].base, rgTab[i].nameOff)
                    && utf8EqAt(gbase, descOff, rgTab[i].base, rgTab[i].descOff))
            {
                return rgTab[i].buf;
            }
            i += 1;
        }
        gbMiss += 1;
        if (reportUnresolved != 0)                      // probe mode: name the unembedded reference (surface)
        {
            Uart.write(Magic.bytes("    ? "));
            writeName(gbase + classOff + 2, u2(gbase + classOff));
            Uart.putc(0x2E);                            // '.'
            writeName(gbase + nameOff + 2, u2(gbase + nameOff));
            Uart.putc(0x0A);
        }
        return 0L;                                      // unresolved: an unloaded class or a native
    }

    static int reportUnresolved;                        // when != 0, globalBuf prints each unresolved reference
    static int gbMiss;                                  // count of unresolved cross-class calls (debug)

    /** Buffer to BL for a static/special call: this class's own method, else the registry. */
    static long resolveCallBuf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            long local = bufOf(calleeCodeOf(idx));      // same class: local buffer
            if (local != 0L)
            {
                return local;
            }
            // else fall through: a same-class NATIVE (no Code attr -> calleeCodeOf 0) resolves to its VM helper;
            // an as-yet-uncompiled same-class method stays 0 here and is patched later by globalBufByRef.
        }
        long g = globalBuf(idx);                        // cross class: another loaded class
        return g != 0L ? g : nativeBuf(idx);            // else a provided java.base native, or 0
    }

    // ----- relocation: patch cross-class refs a class couldn't resolve when it compiled ---------
    // In a dependency cycle the loader force-loads one class before the other, so its cross-class
    // invokestatic/special (bl) and getstatic/putstatic (address load) to the not-yet-loaded class compile
    // to a stub. Each such site is recorded (address + the ref as base+offsets, which stay valid), and
    // patchRelocs() re-resolves + rewrites them after every class is loaded -- so no manual seed-ordering.
    private static final int MAXRELOC = 24576;
    private static int relocRecording;                  // 1 only during emitMethod (the real-base emit pass)
    private static long[] rcAddr, rcBase;               // call sites: bl address, ref blob base,
    private static int[] rcClass, rcName, rcDesc;       //   class/name/descriptor Utf8 offsets
    private static int[] rcTail;                        //   1 = a tail branch (b), not a call (bl) -- lambda thunks
    private static int rcCount;
    private static long[] rsAddr, rsBase;               // static sites: address-load site, ref blob base,
    private static int[] rsReg, rsClass, rsName;        //   destination reg, class/name Utf8 offsets
    private static int rsCount;
    // #43 trap diagnostics: every call site rewritten to bl denylistTrap, recorded so denylistTrap can read x30
    // (the return address) and report WHICH pruned callee actually fired at runtime (vs the dead-branch refs).
    private static final int MAXTRAPWIRE = 512;
    private static long[] trapWireSite = new long[MAXTRAPWIRE];   // the bl call-site address
    private static int trapWireCount;

    /** Record an unresolved cross-class call at {@code blAddr} (the ref names class/name/desc in the current cp). */
    static void recordCallReloc(long blAddr, int idx)
    {
        if (relocRecording == 0 || rcCount >= MAXRELOC)
        {
            return;
        }
        rcAddr[rcCount] = blAddr;
        rcBase[rcCount] = gbase;
        rcClass[rcCount] = refClassNameOff(idx);
        rcName[rcCount] = mrefNameOff(idx);
        rcDesc[rcCount] = mrefDescOff(idx);
        rcTail[rcCount] = 0;
        rcCount += 1;
    }

    /** Record an unresolved TAIL branch ({@code b}, not {@code bl}) at {@code bAddr} to Methodref {@code idx} --
     *  used by a lambda/method-ref thunk whose cross-class impl isn't registered yet at thunk-build time. */
    static void recordTailReloc(long bAddr, int idx)
    {
        if (relocRecording == 0 || rcCount >= MAXRELOC)
        {
            return;
        }
        rcAddr[rcCount] = bAddr;
        rcBase[rcCount] = gbase;
        rcClass[rcCount] = refClassNameOff(idx);
        rcName[rcCount] = mrefNameOff(idx);
        rcDesc[rcCount] = mrefDescOff(idx);
        rcTail[rcCount] = 1;
        rcCount += 1;
    }

    /** Record an unresolved cross-class static-field address load at {@code loadAddr} (2 words, into {@code reg}). */
    static void recordStaticReloc(long loadAddr, int reg, int idx)
    {
        if (relocRecording == 0 || rsCount >= MAXRELOC)
        {
            return;
        }
        rsAddr[rsCount] = loadAddr;
        rsReg[rsCount] = reg;
        rsBase[rsCount] = gbase;
        rsClass[rsCount] = refClassNameOff(idx);
        rsName[rsCount] = mrefNameOff(idx);
        rsCount += 1;
    }

    /** #43 diagnostic: which trap-wired call site (by index, as printed at patch time) does return address {@code lr}
     *  belong to? Returns the TRAPWIRE index, or -1. The bl site is {@code lr - 4}. */
    static int trapIndexFor(long lr)
    {
        long site = lr - 4L;
        int i = 0;
        while (i < trapWireCount)
        {
            if (trapWireSite[i] == site) { return i; }
            i += 1;
        }
        return -1;
    }

    /** Re-resolve and rewrite every recorded reloc site now that all classes are loaded. */
    private static void patchRelocs()
    {
        trapWireCount = 0;                                     // #43: fresh trap-site table per batch
        patchRelocsFrom(0, 0);
    }

    /**
     * Patch the reloc sites recorded from {@code rcStart}/{@code rsStart} onward, leaving earlier ones (and
     * the #43 trap-site table) alone. A LAZY compile runs long after its batch's {@link #patchRelocs}, so its
     * own unresolved sites would otherwise stay {@code bl 0} forever and branch to address 0 — which the
     * firmware's low-memory shim turns into a re-entry of the image entry point ("BOOT RE-ENTERED"). The
     * eager path never hit this because every body was emitted before the batch-end patch. Re-resolution here
     * is also what supplies {@link #globalBufByRef}'s super-chain walk for an INHERITED static/special call
     * (e.g. {@code ArrayList.subList} calling {@code subListRangeCheck}, declared on {@code AbstractList}),
     * which the compile-time {@link #resolveCallBuf} cannot see.
     */
    private static void patchRelocsFrom(int rcStart, int rsStart)
    {
        int i = rcStart;
        while (i < rcCount)
        {
            long target = globalBufByRef(rcBase[i], rcClass[i], rcName[i], rcDesc[i]);
            if (target == 0L)
            {
                // Unresolved. Two very different causes, and they want opposite treatment:
                //   - the callee's class is DENYLISTED (a metal-absent subtree on a cold branch) -> trap, as
                //     before; resolving it would demand-load exactly what the denylist exists to keep out.
                //   - the class is merely ABSENT from a closure that was computed without ever reading this
                //     body -- the RTA-through-reflection gap -> a link stub, which resolves on first call.
                // The trapwire site is recorded either way, so a link stub that fails to resolve still lands
                // in denylistTrap with its index intact.
                target = VM.denylistTrapAddr;
                if (trapWireCount < MAXTRAPWIRE)
                {
                    if (logTrapWire != 0)                      // verbose per-call dump (index -> callee); off by default
                    {
                        Uart.write(Magic.bytes("  TRAPWIRE["));   // matched at runtime by x30 in denylistTrap
                        VM.printDec(trapWireCount);
                        Uart.write(Magic.bytes("] "));
                        writeName(rcBase[i] + rcClass[i] + 2, u2(rcBase[i] + rcClass[i]));   // callee class Utf8
                        Uart.putc(0x2E);                       // '.'
                        writeName(rcBase[i] + rcName[i] + 2, u2(rcBase[i] + rcName[i]));     // callee method Utf8
                        Uart.putc(0x0A);
                    }
                    trapWireSite[trapWireCount] = rcAddr[i];   // ALWAYS recorded: denylistTrap looks up the fired
                    trapWireCount += 1;                        // site by x30, so the table must exist even when quiet
                }
                if (!isDenylisted(rcBase[i], rcClass[i]))
                {
                    long stub = linkStubFor(rcBase[i] + rcClass[i], rcBase[i] + rcName[i], rcBase[i] + rcDesc[i]);
                    if (stub != 0L)
                    {
                        target = stub;
                    }
                }
            }
            if (target != 0L)
            {
                if (target >= Heap.BASE)                       // DIAGNOSTIC: a call "resolved" into the DATA heap
                {                                              //   (blob/object, not code) -> name the callee
                    Uart.write(Magic.bytes("  BADPATCH "));
                    writeName(rcBase[i] + rcClass[i] + 2, u2(rcBase[i] + rcClass[i]));
                    Uart.putc(0x2E);
                    writeName(rcBase[i] + rcName[i] + 2, u2(rcBase[i] + rcName[i]));
                    Uart.write(Magic.bytes(" -> "));
                    VM.printHex(target);
                    Uart.putc(0x0A);
                }
                long d = target - rcAddr[i];                   // A64 bl reaches +-128 MiB (26-bit word offset)
                if (d > 0x07FFFFFFL || d < -0x08000000L)
                {
                    VM.jitFail(Symbols.FAIL_BL_RANGE, (int) (rcAddr[i] >> 12), (int) (target >> 12));
                    for (;;) { }
                }
                int off = (int) (d >> 2);
                Heap.pinCodeAt(target);                       // CODE->CODE edge: after this store the only
                                                              //   record of `target` is a displacement inside
                                                              //   the caller's instructions, which nothing
                                                              //   scans -- pin it or the sweep may free it
                CodeEdges.note(rcAddr[i], target);            // ... and record the edge, so compaction can
                                                              //   find and rewrite it when `target` moves
                if (rcTail[i] != 0)
                {
                    Magic.store32(rcAddr[i], A64Enc.b(off));   // lambda/method-ref thunk: tail branch, not a call
                }
                else
                {
                    Magic.store32(rcAddr[i], A64Enc.bl(off));  // rewrite bl 0 -> bl target
                }
            }
            i += 1;
        }
        int j = rsStart;
        while (j < rsCount)
        {
            long addr = globalStaticByRef(rsBase[j], rsClass[j], rsName[j]);
            if (addr != 0L)
            {
                Magic.store32(rsAddr[j], A64Enc.movz(rsReg[j], (int) addr, 0));   // rewrite the movz+movk
                Magic.store32(rsAddr[j] + 4L, A64Enc.movk(rsReg[j], (int) (addr >> 16), 1));
            }
            j += 1;
        }
        Heap.publishCode(Heap.CODE_BASE, Magic.load64(Heap.CODE_PTR_CELL));   // I-cache maintenance over the patched code
    }

    /** Method buffer for a call ref given as blob base + Utf8 offsets (patchRelocs re-resolution), or 0. */
    private static long globalBufByRef(long refBase, int classOff, int nameOff, int descOff)
    {
        int i = 0;
        while (i < rgCount)
        {
            if (utf8EqAt(refBase, classOff, rgTab[i].base, rgTab[i].classOff)
                    && utf8EqAt(refBase, nameOff, rgTab[i].base, rgTab[i].nameOff)
                    && utf8EqAt(refBase, descOff, rgTab[i].base, rgTab[i].descOff))
            {
                return rgTab[i].buf;
            }
            i += 1;
        }
        // Class-qualified miss: an INHERITED static/special method (invokestatic/invokespecial to a method the ref
        // names via a subclass but that is declared in a SUPERclass, e.g. `ArrayList.subListRangeCheck` really
        // AbstractList.subListRangeCheck). Walk the ref class's super chain and match each ancestor's registration.
        int pd = findPdByName(refBase, classOff);
        while (pd >= 0 && pdSuperOff[pd] != 0)
        {
            int spd = findPdByName(pdBase[pd], pdSuperOff[pd]);
            if (spd < 0)
            {
                break;
            }
            int j = 0;
            while (j < rgCount)
            {
                if (utf8EqAt(pdBase[spd], pdNameOff[spd], rgTab[j].base, rgTab[j].classOff)
                        && utf8EqAt(refBase, nameOff, rgTab[j].base, rgTab[j].nameOff)
                        && utf8EqAt(refBase, descOff, rgTab[j].base, rgTab[j].descOff))
                {
                    return rgTab[j].buf;
                }
                j += 1;
            }
            pd = spd;
        }
        // Last tier: a METADATA-ONLY class's celled static is never eagerly compiled or registered, so both
        // scans above miss it. Its phase-A cell holds callable code -- the lazy stub, or the compiled body
        // once first-called -- so a `bl` may target that directly (the same tier bufBySigU uses for bake
        // stubs). Without this a reloc into a celled method traps: NioSocketImpl's `lambda$closerFor$0` is
        // the one that bites, since the lambda IS the Cleaner action run by close().
        return dlStubByRef(refBase, classOff, nameOff, descOff);
    }

    /**
     * The phase-A cell for a method whose class, name and descriptor each live at a {@code (base, offset)}
     * pair, or 0. The three offset-keyed lookups differ only in where those runs come from — a reloc's ref
     * blob, the compile context plus a walked superclass, or the absolute utf8 runs of a bake stub — so they
     * all funnel through here. Returns the CELL address; callers read it for callable code (the lazy stub,
     * or the body once first-called) or hand it to an emitter to indirect through.
     */
    private static long dlCellOf(long clsBase, int clsOff, long nameBase, int nameOff, long descBase, int descOff)
    {
        int k = 0;
        while (k < dlN)
        {
            if (utf8EqAt(clsBase, clsOff, dlTab[k].blob, dlTab[k].classOff)
                    && utf8EqAt(nameBase, nameOff, dlTab[k].blob, dlTab[k].nameOff)
                    && utf8EqAt(descBase, descOff, dlTab[k].blob, dlTab[k].descOff))
            {
                return dlTab[k].cell;
            }
            k += 1;
        }
        return 0L;
    }

    /** Current contents of the phase-A cell for a method ref given as blob base + Utf8 offsets, or 0. */
    private static long dlStubByRef(long refBase, int classOff, int nameOff, int descOff)
    {
        if (dlTab == null)
        {
            return 0L;
        }
        long cell = dlCellOf(refBase, classOff, refBase, nameOff, refBase, descOff);
        return cell == 0L ? 0L : Magic.load64(cell);
    }

    /** Static-slot address for a field ref given as blob base + Utf8 offsets, or 0. */
    private static long globalStaticByRef(long refBase, int classOff, int nameOff)
    {
        int i = 0;
        while (i < sgCount)
        {
            if (utf8EqAt(refBase, classOff, sgTab[i].base, sgTab[i].classOff)
                    && utf8EqAt(refBase, nameOff, sgTab[i].base, sgTab[i].nameOff))
            {
                return sgTab[i].addr;
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Resolve an {@code invokestatic}/{@code invokespecial} to a PROVIDED java.base native — a real
     * method with no bytecode, implemented by a writer-stashed VM helper. This is how unmodified java.base
     * classes reach the runtime services they assume (time, bit conversions, ...) on metal.
     */
    private static long nativeBuf(int idx)
    {
        return nativeBufAt(gbase, refClassNameOff(idx), gbase, mrefNameOff(idx));
    }

    /**
     * The provided-native table, keyed by a class name and a method name each given as a
     * {@code (base, offset)} pair. Compile-time resolution passes the current blob's constant-pool
     * offsets ({@link #nativeBuf}); {@link #resolveBakeStub} passes absolute {@code {u2 len}{bytes}}
     * runs at offset 0, so a BAKED body calling a native (its stub can't be compiled, so it fires
     * the resolver) reaches the same VM helper the loader would have linked. Before this, only the
     * compile-time path could see the table, so a metadata-only class whose baked body called a
     * native halted with {@code bakeresolve-find} — no bytecode exists for a native to fall back to.
     */
    static long nativeBufAt(long clsBase, int clsOff, long nameBase, int nameOff)
    {
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/System")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("nanoTime")))          { return VM.nanoTimeAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("currentTimeMillis"))) { return VM.currentTimeMillisAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("arraycopy")))         { return VM.arraycopyAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("identityHashCode")))  { return VM.identityAddr; }  // ref IS its address -> identity hash
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/String")))
        {
            // There is no intern table on metal: every String is its own canonical instance, so intern() is
            // identity. Callers use it to shrink allocation (java.util.jar.Attributes$Name) or to compare by
            // ==; the latter would be wrong here, and no reached java.base code does it.
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("intern")))             { return VM.identityAddr; }
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("printStackTrace0")))  { return VM.printStackTraceAddr; }   // (this)V
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/io/FileInputStream")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("open0")))             { return VM.fileOpenAddr; }   // (String)J -> RAMFS entry
        }
        // VarHandle overlay: resolve an instance field's byte offset from the target object's class.
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/invoke/VarHandle")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffset0")))      { return VM.vhFieldOffsetAddr; }  // (byte[],Object)J
        }
        // Atomic*FieldUpdater overlays resolve the target field's byte offset the same way as VarHandle, and
        // resolve their caller's class (getCallerClass) for the field-access check.
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicIntegerFieldUpdater"))
                || utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicLongFieldUpdater"))
                || utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/AtomicReferenceFieldUpdater")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffset0")))     { return VM.vhFieldOffsetAddr; }    // (byte[],Object)J
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/util/concurrent/atomic/FieldUpdaterCheck"))
                || utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/AccessibleObject")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("callerClass0")))     { return VM.classAtPcAddr; }        // (J)Class
        }
        // Reflective Field.get/set: resolve the field's byte offset from the target object's class (same
        // loader field registry as the VarHandle/atomic-updater shims).
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Field")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldOffset0")))     { return VM.vhFieldOffsetAddr; }    // (byte[],Object)J
        }
        // Reflective Method.invoke: resolve a method-registry index by name, then its buffer/access/descriptor.
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("stackTrace0")))       { return VM.stackTraceAddr; }    // (Throwable)[STE
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Method")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodResolve0")))   { return VM.methodResolveAddr; }    // (Class,byte[])I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }       // (I,byte[],long[])I
        }
        // Reflective Constructor.newInstance: resolve <init> by arity, read its descriptor (shared methodInfo0),
        // and allocate the instance.
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Constructor")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("ctorResolve0")))     { return VM.constructorResolveAddr; }  // (Class,I)I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }          // (I,byte[],long[])I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("allocInstance0")))   { return VM.allocInstanceAddr; }       // (Class)Object
        }
        // FileDescriptor.<clinit> runs (to register the JavaIOFileDescriptorAccess); its 3 natives are inert
        // on metal -- initIDs is a no-op, and handle/append are Windows/append-mode fields unused by sockets.
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/io/FileDescriptor")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("getHandle")))         { return VM.sockZeroAddr; }    // (I)J -> 0
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("getAppend")))         { return VM.sockZeroAddr; }    // (I)Z -> false
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/net/InetAddress")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("resolve0")))          { return VM.dnsResolveAddr; }  // (byte[])I -> WiFi DNS
        }
        // M3 socket natives: stock sun.nio.ch backed by net.Tcp (fd int = the net.Tcp handle).
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/Net")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("socket0")))           { return VM.sockSocket0Addr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("connect0")))          { return VM.sockConnect0Addr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("available")))         { return VM.sockAvailableAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("localPort")))         { return VM.sockZeroAddr; }     // -> 0
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("getIntOption0")))     { return VM.sockZeroAddr; }     // -> 0 (SO_LINGER)
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("localInetAddress")))  { return VM.sockZeroAddr; }     // -> null wildcard
            // Net.<clinit> capability probes: no poll, IPv4-only, no reuse-port on metal -> all 0/false.
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollinValue")))       { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("polloutValue")))      { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollerrValue")))      { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollhupValue")))      { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollnvalValue")))     { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("pollconnValue")))     { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isIPv6Available0")))  { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isReusePortAvailable0"))) { return VM.sockZeroAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isExclusiveBindAvailable"))) { return VM.sockZeroAddr; }  // -> 0
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/SocketDispatcher")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("read0")))             { return VM.sockRead0Addr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("write0")))            { return VM.sockWrite0Addr; }
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/UnixDispatcher")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("close0")))            { return VM.sockClose0Addr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("preClose0")))         { return VM.sockNoopAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/IOUtil")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fdVal")))             { return VM.fdValAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("setfdVal")))          { return VM.setFdValAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("sun/nio/ch/NativeThread")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("current0")))          { return VM.sockZeroAddr; }     // -> 0
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("supportPendingSignals0"))) { return VM.sockZeroAddr; } // -> false
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("signal0")))           { return VM.sockNoopAddr; }      // no thread to wake
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Object")))
        {
            // Object.clone() shallow copy: same block-copy as the [T.clone() intrinsic (TIB + body from the
            // status-word size), so reuse arrayClone. Cloneable collections super.clone() then fix their links.
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("clone0")))            { return VM.arrayCloneAddr; }    // (Object)Object
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Array")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("newArray0")))         { return VM.newReflectArrayAddr; } // (Class,I)Object
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Class")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("getName0")))          { return VM.classNameAddr; }     // (Class)String
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("forName0")))          { return VM.forNameAddr; }       // (byte[])Class
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("classModifiers0")))   { return VM.classModifiersAddr; } // (Class)I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isInstance0")))       { return VM.instanceOfAddr; }    // (Object,J)Z == VM.instanceOf(JJ)I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("superclass0")))       { return VM.superclassAddr; }    // (Class)Class
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldMods0")))        { return VM.fieldModsAddr; }     // (Class,byte[])I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("fieldTypeChar0")))    { return VM.fieldTypeCharAddr; } // (Class,byte[])I
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("getComponentType0")))  { return VM.componentTypeAddr; } // (Class)Class
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isArray0")))          { return VM.isArrayClassAddr; }  // (Class)J
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("isPrimitive0")))      { return VM.isPrimClassAddr; }   // (Class)J
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("primitiveClass0")))   { return VM.primClassAddr; }     // (J)Class
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredMethodAt0")))  { return VM.declMethodAddr; }      // (Class,I)String
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("declaredMethodCount0"))) { return VM.declMethodCountAddr; } // (Class)J
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Throwable")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("stackTrace0")))       { return VM.stackTraceAddr; }    // (Throwable)[STE
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/reflect/Method")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("annoPresent0")))      { return VM.annoPresentAddr; }   // (I,byte[])I
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/ClassLoader")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("defineClass0")))      { return VM.defineClassAddr; }   // (String,byte[],II)Class
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Thread")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("currentThread0")))    { return VM.currentThreadAddr; } // ()Thread
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Float")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("floatToRawIntBits"))) { return VM.identityAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("intBitsToFloat")))    { return VM.identityAddr; }
        }
        if (utf8IsAtBase(clsBase, clsOff, Magic.bytes("java/lang/Double")))
        {
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("doubleToRawLongBits"))) { return VM.identityAddr; }
            if (utf8IsAtBase(nameBase, nameOff, Magic.bytes("longBitsToDouble")))    { return VM.identityAddr; }
        }
        return 0L;
    }

    /**
     * Record the current class's directly-declared interfaces (from {@code gImplIfName}) as registry indices
     * at {@code clCount}, for {@link #buildItableDir}'s transitive closure. Interfaces this class implements
     * are already loaded (dep ordering), so they resolve now.
     */
    private static void captureDirectIfaces()
    {
        int n = 0;
        int k = 0;
        while (k < gImplIfCount && n < MAX_DIRECT_IF)
        {
            int r = classRegByName(gImplIfName[k]);
            if (r >= 0)
            {
                clIfaceReg[clCount * MAX_DIRECT_IF + n] = r;
                n += 1;
            }
            k += 1;
        }
        clIfaceRegN[clCount] = n;
    }

    /** Register the current class (its TIB + instance-field layout) for cross-class new/fields. */
    /**
     * PHASE A registration: record the class's Type + TIB + field layout + statics + flattened-vtable STRUCTURE
     * (name/descriptor/slot per slot). The vtable BUFFERS are left 0 -- the method bodies aren't compiled yet;
     * {@link #fillClassVtBuf} fills them in phase B. clVtStart pins where this class's vt entries begin.
     */
    private static void registerClassStructure()
    {
        if (clCount >= MAXCLASS) { capHalt(Magic.bytes("MAXCLASS"), clCount); }              // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (sgCount + gsfCount >= MAXREG) { capHalt(Magic.bytes("MAXREG"), sgCount); }       // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (fldCount + gifCount >= MAXFIELD) { capHalt(Magic.bytes("MAXFIELD"), fldCount); } // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        if (vtCount + gvCount >= MAXVT) { capHalt(Magic.bytes("MAXVT"), vtCount); }          // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        clTab[clCount] = new RVMClass();
        clTab[clCount].state = RVMClass.ST_LOADED;
        clTab[clCount].base = gbase;
        clTab[clCount].nameOff = gThisNameOff;
        clTab[clCount].tib = gTib;
        clTab[clCount].type = gType;
        clTab[clCount].fieldCount = gifCount;
        clTab[clCount].vtCount = gvCount;
        clTab[clCount].statics = gStatics;
        clTab[clCount].vtStart = vtCount;                   // this class's slots occupy vt[vtCount .. vtCount+gvCount)
        clTab[clCount].isIface = false;
        clTab[clCount].superReg = classRegByName(gSuperNameOff);   // superclass registry index (-1 for Object), for the
                                                        //   FULL-chain itable closure (an interface implemented N levels
                                                        //   up the superclass chain must still land in this class's dir)
        if (logVtable != 0)                             // #43: class -> vtable slot count (spot too-short vtables)
        {
            Uart.write(Magic.bytes("  C "));
            writeName(gbase + gThisNameOff + 2, u2(gbase + gThisNameOff));
            Uart.putc(0x20);
            VM.printDec(gvCount);
            Uart.putc(0x0A);
        }
        captureDirectIfaces();
        clCount += 1;
        armPhaseACells();                               // cells for this class's statics, before any body compiles
        int st = 0;
        while (st < gsfCount)                           // register this class's static fields (cross-class getstatic)
        {
            sgTab[sgCount] = new RVMField();
            sgTab[sgCount].base = gbase;
            sgTab[sgCount].classOff = gThisNameOff;
            sgTab[sgCount].nameOff = gsfName[st];
            sgTab[sgCount].addr = gStatics + st * 8L;
            sgCount += 1;
            st += 1;
        }
        int s = 0;
        while (s < gifCount)
        {
            if (gifName[s] != 0)                        // skip inherited slots (registered by the super)
            {
                fldTab[fldCount] = new RVMField();
                fldTab[fldCount].base = gbase;
                fldTab[fldCount].classOff = gThisNameOff;
                fldTab[fldCount].nameOff = gifName[s];
                fldTab[fldCount].slot = s;
                fldTab[fldCount].access = gifAccess[s];
                fldTab[fldCount].descOff = gifDescOff[s];
                fldCount += 1;
            }
            s += 1;
        }
        int v = 0;
        while (v < gvCount)                            // register the whole flattened vtable STRUCTURE (bufs 0 for now)
        {
            vtClassBase[vtCount] = gbase;
            vtClassOff[vtCount] = gThisNameOff;
            vtNameBase[vtCount] = gvTab[v].base;           // signature blob (a super's, for inherited slots)
            vtNameOff[vtCount] = gvTab[v].name;
            vtDescOff[vtCount] = gvTab[v].desc;
            vtSlot[vtCount] = v;
            vtBuf[vtCount] = 0L;                        // filled by fillClassVtBuf once the bodies are compiled
            vtCount += 1;
            v += 1;
        }
    }

    /**
     * PHASE B: fill the vtable BUFFERS for class {@code reg}'s registered slots, now that its bodies are compiled
     * ({@code slotBuf} = this class's own compiled buffer, or -- for an inherited slot -- the super's, which
     * phase B filled first because it runs superclass-first). Subclasses then inherit real buffers via parseVtable.
     */
    private static void fillClassVtBuf(int reg)
    {
        int start = clTab[reg].vtStart;
        int cnt = clTab[reg].vtCount;
        int v = 0;
        while (v < cnt)
        {
            vtBuf[start + v] = slotBuf(v);             // gv* is current (this class's phase-B parseVtable just ran)
            v += 1;
        }
    }

    /**
     * Give every method of the interface being loaded a global interface-method
     * index (deduped by name+descriptor). Implementors later build an imap indexed
     * by it, and {@code invokeinterface} resolves a call site to the same index.
     */
    // M8 itables: the current interface's flattened-run bounds, left by registerInterface for the
    // RVMClass fill (registerInterface runs before the clTab entry exists).
    private static int gIfmStart;
    private static int gIfmCount;

    /**
     * Capture this interface's FLATTENED method list as a contiguous run in the ifm registry
     * (ifBase/ifNameOff/ifDescOff): each super-interface's flattened run first, in interfaces[]
     * declaration order, then this interface's own virtual methods -- deduped by name+descriptor so
     * a redeclaration keeps the inherited position. This IS the per-interface itable slot numbering,
     * identical to the writer's {@code ClassFile.interfaceMethods} (itparity-checked at load).
     * Flattening (not own-only lists) is what lets a call typed to a super-interface (a
     * BinaryOperator lambda invoked as BiFunction) index the right slot.
     */
    private static void registerInterface()
    {
        gIfmStart = ifCount;
        int k = 0;
        while (k < gImplIfCount)                        // super-interfaces' flattened runs first
        {
            int r = classRegByName(gImplIfName[k]);
            if (r >= 0)
            {
                int s = 0;
                while (s < clTab[r].ifmCount)
                {
                    int src = clTab[r].ifmStart + s;
                    ifmAppendUnique(ifBase[src], ifNameOff[src], ifDescOff[src]);
                    s += 1;
                }
            }
            k += 1;
        }
        long p = gMethodsStart;                         // then this interface's own declarations
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (isVirtual(u2(p), gcp[u2(p + 2)]))
            {
                ifmAppendUnique(gbase, gcp[u2(p + 2)], gcp[u2(p + 4)]);
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        gIfmCount = ifCount - gIfmStart;
    }

    /** Append (base, name, desc) to the current interface's run unless the signature is in it. */
    private static void ifmAppendUnique(long base, int nameOff, int descOff)
    {
        int i = gIfmStart;
        while (i < ifCount)
        {
            if (utf8EqAt(base, nameOff, ifBase[i], ifNameOff[i])
                    && utf8EqAt(base, descOff, ifBase[i], ifDescOff[i]))
            {
                return;
            }
            i += 1;
        }
        if (ifCount >= MAXIFM) { capHalt(Magic.bytes("MAXIFM"), ifCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
        ifBase[ifCount] = base;
        ifNameOff[ifCount] = nameOff;
        ifDescOff[ifCount] = descOff;
        ifCount += 1;
    }

    /** Per-interface itable slot for an InterfaceMethodref call site: the signature's position in
     *  the ref'd interface's flattened run -- the writer's numbering (0 if the interface isn't
     *  registered or lacks the signature: a denylist-fringe site whose entry is never taken). */
    static int ifSlotOf(int idx)
    {
        int r = classRegByName(refClassNameOff(idx));
        if (r < 0)
        {
            return 0;
        }
        int s = ifmSlotIn(r, gbase, mrefNameOff(idx), mrefDescOff(idx));
        return s >= 0 ? s : 0;
    }

    /** Slot of (name, desc in {@code base}) within registered interface {@code r}'s run, or -1. */
    private static int ifmSlotIn(int r, long base, int nameOff, int descOff)
    {
        int s = 0;
        while (s < clTab[r].ifmCount)
        {
            int i = clTab[r].ifmStart + s;
            if (utf8EqAt(base, nameOff, ifBase[i], ifNameOff[i])
                    && utf8EqAt(base, descOff, ifBase[i], ifDescOff[i]))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /** Per-interface itable for interface {@code ir} on the CURRENT class: slot s = the class's
     *  impl of the interface's s-th flattened method (vtable match), else a DEFAULT declared by an
     *  interface in this class's own closure ({@code n} entries of {@link #ifClosureBuf} -- not the
     *  arbitrary declaring interface: two unrelated interfaces can share a signature), else 0
     *  (refilled after phase B compiles late defaults). */
    private static long buildItableFor(int ir, int n)
    {
        int count = clTab[ir].ifmCount;
        long it = Heap.allocData(count > 0 ? count * 8 : 8);
        int s = 0;
        while (s < count)
        {
            int i = clTab[ir].ifmStart + s;
            long buf = 0L;
            int vs = findVtSlotAt(ifBase[i], ifNameOff[i], ifDescOff[i]);
            if (vs >= 0)
            {
                buf = slotBuf(vs);
                if (buf == 0L)
                {
                    // The impl has a Code attribute but no buffer: RTA PRUNED it -- nothing statically
                    // reachable called it, so it was never pulled into the batch and never given a deferral
                    // stub, and this itable entry would stay 0. An interface call reaching it later then hits
                    // dispatchTargetGuard as a bare AIOOBE with no hint of what was missing -- which is
                    // exactly what `SharedSecrets.getJavaLangAccess().getEnumConstantsShared(...)` inside
                    // EnumMap does once its caller arrives through demand-loaded code.
                    //
                    // Minting here rather than in slotBuf is deliberate. Doing it for every vtable slot costs
                    // 3-4x the code arena per batch (measured: 8K->31K, 20K->64K, 23K->89K), and buys nothing
                    // for plain virtual dispatch: RTA marks all virtuals of an INSTANTIATED class, and a class
                    // that is never instantiated can never be a receiver. Interface entries are the case that
                    // actually goes empty, and they are a small fraction of the methods.
                    buf = mintPrunedStub(vs);
                }
            }
            else
            {
                buf = defaultBySig(n, ifBase[i], ifNameOff[i], ifDescOff[i]);
            }
            Magic.store64(it + s * 8, buf);
            s += 1;
        }
        return it;
    }

    /** Like {@link #findVtSlot} but for a name+descriptor living in another blob. */
    private static int findVtSlotAt(long base, int nameOff, int descOff)
    {
        int s = 0;
        while (s < gvCount)
        {
            if (utf8EqAt(base, nameOff, gvTab[s].base, gvTab[s].name)
                    && utf8EqAt(base, descOff, gvTab[s].base, gvTab[s].desc))
            {
                return s;
            }
            s += 1;
        }
        return -1;
    }

    /** Class-registry index of the class whose name Utf8 is at {@code nameOff} in gbase, or -1. */
    private static int classRegByName(int nameOff)
    {
        if (nameOff == 0)
        {
            return -1;
        }
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, nameOff, clTab[i].base, clTab[i].nameOff))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Vtable slot of a cross-class virtual/interface method. For invokevirtual the
     * ref's class is the (loaded) declaring class, so a class+name+descriptor match
     * finds the slot. For invokeinterface the ref's class is the interface (not a
     * loaded class), so that match fails and we fall back to name+descriptor — sound
     * while a single loaded class implements it.
     */
    static int logVtable;                               // #43 diagnostic: when != 0, log high-slot vtable resolutions
    static int logClinit;                                     // #43 diagnostic: when != 0, name each <clinit> as it runs
    static int logTrapWire;                                  // #43 diagnostic: when != 0, dump each patchRelocs trap-wired callee

    /**
     * The vtable slot for Methodref {@code idx} resolved ONLY against the class the ref names — no name+
     * descriptor fallback. -1 when that class has no such slot, which is the question
     * {@link #defaultDispatch} needs answered honestly: {@link #globalVtableSlot}'s fallback would hand back
     * an unrelated class's slot rather than admit the miss.
     */
    private static int exactVtableSlot(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))     // the class being compiled: its own flattened vtable
        {
            int own = findVtSlot(mrefNameOff(idx), mrefDescOff(idx));
            if (own >= 0)
            {
                return own;
            }
        }
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int i = 0;
        while (i < vtCount)
        {
            if (utf8EqAt(gbase, classOff, vtClassBase[i], vtClassOff[i])
                    && utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i])
                    && utf8EqAt(gbase, descOff, vtNameBase[i], vtDescOff[i]))
            {
                return vtSlot[i];
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Registry index of the interface in the ref CLASS's transitive closure that declares Methodref
     * {@code idx}'s name+descriptor, or -1. Only consulted once {@link #exactVtableSlot} has missed, so the
     * closure walk costs nothing on the ordinary path.
     */
    private static int defaultIfaceRegOf(int idx)
    {
        int rc = classRegByName(refClassNameOff(idx));
        if (rc < 0 || clTab[rc] == null || clTab[rc].isIface)
        {
            return -1;
        }
        int n = ifaceClosureOf(rc);
        int i = 0;
        while (i < n)
        {
            int ir = ifClosureBuf[i];
            if (ifmSlotIn(ir, gbase, mrefNameOff(idx), mrefDescOff(idx)) >= 0)
            {
                return ir;
            }
            i += 1;
        }
        return -1;
    }

    /** True if this {@code invokevirtual} must go through the itable -- see {@link Symbols#defaultDispatch}. */
    static boolean defaultDispatch(int idx)
    {
        return exactVtableSlot(idx) < 0 && defaultIfaceRegOf(idx) >= 0;
    }

    /** Type node of the interface declaring the inherited default named by Methodref {@code idx}, or 0. */
    static long defaultIfaceTypeOf(int idx)
    {
        int ir = defaultIfaceRegOf(idx);
        return ir < 0 ? 0L : clTab[ir].type;
    }

    /** Slot of Methodref {@code idx} within that interface's flattened method list. */
    static int defaultIfaceSlotOf(int idx)
    {
        int ir = defaultIfaceRegOf(idx);
        return ir < 0 ? 0 : ifmSlotIn(ir, gbase, mrefNameOff(idx), mrefDescOff(idx));
    }

    private static int globalVtableSlot(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        int i = 0;
        while (i < vtCount)                             // class-qualified (invokevirtual)
        {
            if (utf8EqAt(gbase, classOff, vtClassBase[i], vtClassOff[i])
                    && utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i])
                    && utf8EqAt(gbase, descOff, vtNameBase[i], vtDescOff[i]))
            {
                logVtableSlot(classOff, nameOff, descOff, vtSlot[i], 0x51);   // 'Q' class-qualified
                return vtSlot[i];
            }
            i += 1;
        }
        i = 0;
        while (i < vtCount)                             // name+descriptor (invokeinterface / inherited)
        {
            if (utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i])
                    && utf8EqAt(gbase, descOff, vtNameBase[i], vtDescOff[i]))
            {
                logVtableSlot(classOff, nameOff, descOff, vtSlot[i], 0x46);   // 'F' name+desc fallback
                return vtSlot[i];
            }
            i += 1;
        }
        // A MISS IS ALWAYS A BUG: returning 0 dispatches through vtable slot 0 of whatever the receiver
        // happens to be -- one of java/lang/Object's nine virtuals -- so the call silently returns the wrong
        // thing instead of failing. It happens when the referenced class is not registered at COMPILE time,
        // which is routine for a method reached only reflectively: it is compiled on demand, and its callees'
        // classes were never pulled. Static call sites got late resolution (link stubs) in PR #192; virtual
        // ones still need it. VT_TRACE names the sites meanwhile.
        if (VT_TRACE)
        {
            Uart.write(Magic.bytes("  VTMISS "));
            writeName(gbase + classOff + 2, u2(gbase + classOff));
            Uart.putc(0x2E);
            writeName(gbase + nameOff + 2, u2(gbase + nameOff));
            Uart.putc(0x0A);
        }
        return -1;                                      // unresolved -> the caller lowers a late-dispatch site
    }

    // ----- late virtual dispatch ------------------------------------------------------------------------
    // A virtual call whose class is not registered at compile time has no slot to bake into the instruction.
    // Static call sites solved this with link stubs (PR #192); a virtual site cannot, because the target
    // depends on the RECEIVER. So the site records its (class,name,desc) here, emits the index in x17, and
    // calls a trampoline that resolves against the receiver's actual type at first execution.
    private static final int MAXVSITE = 16384;
    private static final int VSHASH = 32768;             // power of two, > 2x MAXVSITE (open addressing)
    private static long[] vsName, vsDesc;                // Utf8 ADDRESSES (blob base + offset), not offsets
    private static int vsCount;
    private static int[] vsBucket;                       // hash -> site index + 1 (0 = empty)
    private static long virtualTrampAddr;
    private static long vsMemoType, vsMemoBuf;           // one-entry memo: the same site is usually monomorphic
    private static int vsMemoIdx = -1;

    /** Record an unresolved virtual site; returns its index (what the emitted code puts in x17). */
    static int virtualSiteIndex(int methodCp)
    {
        if (vsName == null)
        {
            vsName = new long[MAXVSITE];
            vsDesc = new long[MAXVSITE];
            vsBucket = new int[VSHASH];                  // allocArray does NOT zero: fill it explicitly
            int z = 0;
            while (z < VSHASH)
            {
                vsBucket[z] = 0;
                z += 1;
            }
        }
        long nameAddr = gbase + mrefNameOff(methodCp);
        long descAddr = gbase + mrefDescOff(methodCp);
        // Dedup, because a site is now allocated for EVERY dispatch guard, and the compiler visits each site
        // more than once (size pass then emit pass). Without this the table would fill with duplicates and
        // the two passes would bake different indices for the same call.
        int h = (int) ((nameAddr * 31L + descAddr) >> 3) & (VSHASH - 1);
        while (vsBucket[h] != 0)
        {
            int cand = vsBucket[h] - 1;
            if (vsName[cand] == nameAddr && vsDesc[cand] == descAddr)
            {
                return cand;
            }
            h = (h + 1) & (VSHASH - 1);
        }
        if (vsCount >= MAXVSITE)
        {
            capHalt(Magic.bytes("MAXVSITE"), vsCount);   // returning a stale index would MIS-RESOLVE silently
        }
        int idx = vsCount;
        vsName[idx] = nameAddr;
        vsDesc[idx] = descAddr;
        vsBucket[h] = idx + 1;
        vsCount += 1;
        return idx;
    }

    /**
     * Resolve an unresolved virtual site against the RECEIVER's dynamic type. Reuses
     * {@link #resolveLinkTarget}, which demand-loads the class and walks the same tiers a link stub does --
     * the only difference is that the class name comes from the receiver rather than from the call site,
     * which is what makes it virtual dispatch.
     */
    static long virtualResolve(long recv, int idx)
    {
        if (recv == 0L || idx < 0 || idx >= vsCount || clTab == null)
        {
            return VM.denylistTrapAddr;
        }
        long tib = Magic.load64(recv + ObjectModel.TIB_OFFSET);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return VM.denylistTrapAddr;                 // a raw array has no Type to dispatch through
        }
        long type = Magic.load64(tib);
        if (idx == vsMemoIdx && type == vsMemoType && vsMemoBuf != 0L)
        {
            return vsMemoBuf;                           // monomorphic site: skip the whole lookup
        }
        int ci = classRegByType(type);
        if (ci < 0)
        {
            return VM.denylistTrapAddr;
        }
        long buf = resolveLinkTarget(clTab[ci].base + clTab[ci].nameOff, vsName[idx], vsDesc[idx]);
        if (buf == 0L)
        {
            return VM.denylistTrapAddr;
        }
        vsMemoIdx = idx;
        vsMemoType = type;
        vsMemoBuf = buf;
        return buf;
    }

    /**
     * The late-dispatch trampoline: the twin of {@link #buildLinkTramp}, but the receiver is already in x0 and
     * the site index arrives in x17. Saves x0..x15 + LR so the resolve cannot disturb the arguments, then
     * tail-branches to the target -- so the callee returns straight to the original call site.
     */
    static long virtualTramp()
    {
        if (virtualTrampAddr != 0L)
        {
            return virtualTrampAddr;
        }
        long buf = Heap.allocCode(256);
        long ra = VM.virtualResolveAddr;
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.subImm(31, 31, 144)); w += 1;
        int r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.strx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.strx(30, 31, 128));   w += 1;   // save LR
        Magic.store32(buf + w * 4L, A64Enc.movReg(1, 17));       w += 1;   // x1 = site idx (x0 IS the receiver)
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (ra & 0xFFFF), 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ra >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ra >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.blr(16));             w += 1;   // x0 = resolved target
        Magic.store32(buf + w * 4L, A64Enc.movReg(16, 0));       w += 1;   // x16 = target (outside the restore set)
        r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.ldrx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.ldrx(30, 31, 128));   w += 1;
        Magic.store32(buf + w * 4L, A64Enc.addImm(31, 31, 144)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.br(16));              w += 1;   // tail-call: returns to the call site
        Heap.publishCode(buf, buf + w * 4L);
        virtualTrampAddr = buf;
        return buf;
    }

    /** VarHandle overlay: vtable slot of an op by NAME only (its op names are unique), regardless of the
     *  signature-polymorphic call-site descriptor. Returns -1 if the VarHandle overlay isn't registered yet. */
    private static int varHandleSlotByName(int nameOff)
    {
        int i = 0;
        while (i < vtCount)
        {
            if (utf8IsAtBase(vtClassBase[i], vtClassOff[i], Magic.bytes("java/lang/invoke/VarHandle"))
                    && utf8EqAt(gbase, nameOff, vtNameBase[i], vtNameOff[i]))
            {
                return vtSlot[i];
            }
            i += 1;
        }
        return -1;
    }

    /** VarHandle shim: byte offset of instance field {@code fname} (raw bytes at {@code fnBase..+fnLen}) within
     *  the class whose TIB is {@code tib}. Uses the class registry (TIB->class) + field registry (class+name->
     *  slot). Returns -1 if unresolved. */
    static long vhFieldOffset(long fnBase, int fnLen, long tib)
    {
        int ci = 0;
        while (ci < clCount)
        {
            if (clTab[ci].tib == tib)
            {
                int j = 0;
                while (j < fldCount)
                {
                    if (utf8EqAt(clTab[ci].base, clTab[ci].nameOff, fldTab[j].base, fldTab[j].classOff)
                            && rawEqUtf8(fnBase, fnLen, fldTab[j].base, fldTab[j].nameOff))
                    {
                        return 16L + fldTab[j].slot * 8L;
                    }
                    j += 1;
                }
            }
            ci += 1;
        }
        return -1L;
    }

    /** Field-registry index of instance field {@code fname} (raw bytes) declared by the class whose Type is
     *  {@code typeAddr}, or -1. Used by the reflection field-metadata natives. */
    private static int fieldRegIndex(long typeAddr, long fnBase, int fnLen)
    {
        int ci = 0;
        while (ci < clCount)
        {
            if (clTab[ci].type == typeAddr)
            {
                int j = 0;
                while (j < fldCount)
                {
                    if (utf8EqAt(clTab[ci].base, clTab[ci].nameOff, fldTab[j].base, fldTab[j].classOff)
                            && rawEqUtf8(fnBase, fnLen, fldTab[j].base, fldTab[j].nameOff))
                    {
                        return j;
                    }
                    j += 1;
                }
            }
            ci += 1;
        }
        return -1;
    }

    /** Reflection: access_flags of the instance field, or -1 if the class declares no such own field. */
    static int fieldMods(long typeAddr, long fnBase, int fnLen)
    {
        int j = fieldRegIndex(typeAddr, fnBase, fnLen);
        return j < 0 ? -1 : fldTab[j].access;
    }

    /**
     * {@code Class.getModifiers()}: the loaded class's Java modifiers. For a nested class the real flags live
     * in the enclosing class's {@code InnerClasses} attribute (this class's own {@code access_flags} lack the
     * {@code private}/{@code protected}/{@code static} distinction), so prefer the {@code InnerClasses} entry
     * whose {@code inner_class_info} is this class. The VM-only {@code ACC_SUPER} (0x20) bit is stripped.
     */
    static int classModifiersOf(long type)
    {
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                return clTab[i].modifiers;                     // cached at load time (see computeModifiersAtLoad)
            }
            i += 1;
        }
        return 0;
    }

    /**
     * Reflection ({@code Class.getDeclaredMethod}): the method-registry index of the first method named
     * {@code nameArr} (a raw {@code byte[]}) declared by the class whose Type is {@code type}, or -1. Matches
     * class name + method name (no overload/param-type resolution yet — first match wins).
     */
    static int methodResolve(long type, long nameArr)
    {
        if (type == 0L || nameArr <= 0x1000L)
        {
            return -1;
        }
        ensureClinit(classRegByType(type));             // reflective invoke is an active use (JVMS 5.5) --
                                                        // Class.getEnumConstants reaches values() this way, and
                                                        // values() reads $VALUES, which only <clinit> sets
        int idx = methodResolveRegistry(type, nameArr);
        if (idx >= 0)
        {
            return idx;                                    // already compiled + registered
        }
        return compileMethodOnDemand(type, nameArr);       // reflectively-only method: JIT it now, then resolve
    }

    /** Method-registry index of the method named {@code nameArr} declared by the class whose Type is {@code type}
     *  (class name + method name match), or -1 if it is not currently compiled/registered. */
    private static int methodResolveRegistry(long type, long nameArr)
    {
        int ci = 0;
        while (ci < clCount && clTab[ci].type != type)
        {
            ci += 1;
        }
        if (ci >= clCount)
        {
            return -1;
        }
        int nlen = (int) Magic.load64(nameArr + 16L);
        long nbase = nameArr + 24L;
        int i = 0;
        while (i < rgCount)
        {
            if (utf8EqAt(clTab[ci].base, clTab[ci].nameOff, rgTab[i].base, rgTab[i].classOff)
                    && rawEqUtf8(nbase, nlen, rgTab[i].base, rgTab[i].nameOff))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * On-demand compile: a method invoked ONLY reflectively was never RTA-reached, so it isn't compiled. Compile
     * JUST that method (its declaring class is already structure-loaded) into the code arena and register it, so
     * {@code Method.invoke} can call its buffer. Uses {@code compileReuseTib} so the class's already-filled TIB is
     * left alone (invoke dispatches to the buffer directly, not through the vtable). Returns the new registry
     * index, or -1. Limitation: the method's cross-class callees must already be compiled (no dep pull here); a
     * same-class callee it needs is compiled alongside it (they share this compile batch).
     */
    private static int compileMethodOnDemand(long type, long nameArr)
    {
        int ci = 0;
        while (ci < clCount && clTab[ci].type != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clTab[ci].isIface)
        {
            return -1;
        }
        long blob = clTab[ci].base;
        int len = blobLenOf(blob);
        parseConstPool(blob, len);                         // set up the class's compile state (as loadBodies does,
        parseFields();                                     //   minus the TIB fill): statics block + vtable scratch
        int reg = classRegByName(gThisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        gStatics = clTab[reg].statics;                         // reuse the phase-A statics (cross-class getstatic keys on it)
        findBootstrapMethods();
        parseVtable(blob);                                 // gvImplBuf/gvImplCode (for any invokevirtual in the body)
        gType = clTab[reg].type;
        gTib = clTab[reg].tib;
        parseForMethods(blob, len);                        // fresh gMethodsStart for the by-name method walk
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int nlen = (int) Magic.load64(nameArr + 16L);
        long nbase = nameArr + 24L;
        long code = 0L;
        int descOff = 0;
        int isStatic = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (rawEqUtf8(nbase, nlen, blob, gcp[u2(p + 2)]))
            {
                long c = findCode(blob, p + 8, attrs);     // sets gcodeLen + gMaxLocals for this method
                if (c != 0L)
                {
                    code = c;
                    descOff = gcp[u2(p + 4)];
                    isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                    break;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return -1;                                     // no such method, or abstract/native (no Code)
        }
        compileReuseTib = true;                            // do NOT rebuild/refill the class's TIB
        compile(code, gcodeLen, descOff, isStatic);
        compileReuseTib = false;
        registerAll();                                     // register the just-compiled method(s) -> globalBuf
        patchRelocs();                                     // resolve its cross-class calls (idempotent over prior relocs)
        return methodResolveRegistry(type, nameArr);
    }

    /** Parameter count of a raw method descriptor Utf8 ("(...)ret") at {@code descAddr} (array params fold once). */
    private static int descParamCountRaw(long descAddr)
    {
        long p = descAddr + 2 + 1;                          // past u2 length and '('
        int n = 0;
        while (u1(p) != 0x29)                               // ')'
        {
            int c = u1(p);
            if (c == 0x4C)                                  // 'L' reference
            {
                while (u1(p) != 0x3B) { p += 1; }
                p += 1;
                n += 1;
            }
            else if (c == 0x5B)                             // '[' array prefix: folds into its element
            {
                p += 1;
            }
            else                                            // primitive
            {
                p += 1;
                n += 1;
            }
        }
        return n;
    }

    /** Method-registry index of the class's {@code <init>} with {@code paramCount} parameters, or -1. */
    private static int ctorResolveRegistry(int ci, int paramCount)
    {
        int i = 0;
        while (i < rgCount)
        {
            if (utf8EqAt(clTab[ci].base, clTab[ci].nameOff, rgTab[i].base, rgTab[i].classOff)
                    && utf8IsAtBase(rgTab[i].base, rgTab[i].nameOff, Magic.bytes("<init>"))
                    && descParamCountRaw(rgTab[i].base + rgTab[i].descOff) == paramCount)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Reflection ({@code Class.getDeclaredConstructor}): registry index of the {@code <init>} of the class whose
     * Type is {@code type} taking {@code paramCount} parameters (matched by ARITY — no param-type resolution yet),
     * compiling it on demand if it was never RTA-reached. Returns -1 if none.
     */
    static int constructorResolve(long type, int paramCount)
    {
        if (type == 0L)
        {
            return -1;
        }
        int ci = 0;
        while (ci < clCount && clTab[ci].type != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clTab[ci].isIface)
        {
            return -1;
        }
        int idx = ctorResolveRegistry(ci, paramCount);
        if (idx >= 0)
        {
            return idx;
        }
        // on-demand: compile the matching <init> (its declaring class is already structure-loaded)
        long blob = clTab[ci].base;
        int len = blobLenOf(blob);
        parseConstPool(blob, len);
        parseFields();
        int reg = classRegByName(gThisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        gStatics = clTab[reg].statics;
        findBootstrapMethods();
        parseVtable(blob);
        gType = clTab[reg].type;
        gTib = clTab[reg].tib;
        parseForMethods(blob, len);
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        long code = 0L;
        int descOff = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            int dOff = gcp[u2(p + 4)];
            if (utf8IsAtBase(blob, gcp[u2(p + 2)], Magic.bytes("<init>")) && descParamCountRaw(blob + dOff) == paramCount)
            {
                long c = findCode(blob, p + 8, attrs);
                if (c != 0L)
                {
                    code = c;
                    descOff = dOff;
                    break;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return -1;
        }
        compileReuseTib = true;
        compile(code, gcodeLen, descOff, 0);               // <init> is an instance method (receiver = the new object)
        compileReuseTib = false;
        registerAll();
        patchRelocs();
        return ctorResolveRegistry(ci, paramCount);
    }

    /** Reflection ({@code Constructor.newInstance}): allocate an instance of the class whose Type is {@code type}
     *  (zeroed fields + its TIB in the header), or 0. The caller runs the {@code <init>} on it. */
    static long allocInstance(long type)
    {
        if (type == 0L)
        {
            return 0L;
        }
        int ci = 0;
        while (ci < clCount && clTab[ci].type != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clTab[ci].isIface)
        {
            return 0L;
        }
        ensureClinit(ci);                               // reflective instantiation is an active use, like `new`
        long obj = Heap.alloc(16 + clTab[ci].fieldCount * 8);  // header(16) + instance fields (incl. inherited)
        Magic.store64(obj + ObjectModel.TIB_OFFSET, clTab[ci].tib);
        return obj;
    }

    /**
     * Reflection: fill {@code paramCharsArr} (a guest {@code byte[]}) with the first descriptor char of each of
     * method {@code rgIndex}'s parameters (primitives 'I'/'J'/'Z'/... verbatim; a reference or array param as
     * 'L'/'['), and write {@code out} (a guest {@code long[3]}): {@code [buffer, access_flags, returnChar]}.
     * Returns the parameter count. Float/double params are marshalled as their raw bits (no v-register support).
     */
    static int methodInfo(int rgIndex, long paramCharsArr, long outArr)
    {
        long descAddr = rgTab[rgIndex].base + rgTab[rgIndex].descOff;   // "(...)ret" Utf8 (u2 length, then bytes)
        long p = descAddr + 2 + 1;                              // past u2 length and '('
        long pc = paramCharsArr + 24L;                          // byte[] elements
        int n = 0;
        while (u1(p) != 0x29)                                   // ')'
        {
            int c = u1(p);
            if (c == 0x4C)                                      // 'L' reference: skip to ';'
            {
                Magic.store8(pc + n, (byte) 0x4C);
                while (u1(p) != 0x3B) { p += 1; }               // ';'
                p += 1;
            }
            else if (c == 0x5B)                                // '[' array: reference; skip prefixes + element
            {
                Magic.store8(pc + n, (byte) 0x5B);
                while (u1(p) == 0x5B) { p += 1; }
                if (u1(p) == 0x4C) { while (u1(p) != 0x3B) { p += 1; } }
                p += 1;
            }
            else                                               // primitive: one char, one slot
            {
                Magic.store8(pc + n, (byte) c);
                p += 1;
            }
            n += 1;
        }
        Magic.store64(outArr + 24L + 0L, rgTab[rgIndex].buf);      // out[0] = compiled buffer
        Magic.store64(outArr + 24L + 8L, (long) rgTab[rgIndex].access);   // out[1] = access flags
        Magic.store64(outArr + 24L + 16L, (long) u1(p + 1));   // out[2] = return-type char (after ')')
        return n;
    }


    /**
     * Scan the class-level {@code InnerClasses} attribute for the entry whose {@code inner_class_info_index}
     * equals {@code thisClass}; return its {@code inner_class_access_flags}, or -1 if this class is not listed
     * (a top-level class). Assumes {@code gMethodsStart}/{@code gcp} are current (post {@code parseForMethods}).
     */
    private static int innerAccessOf(int thisClass)
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)                                // skip the methods table
        {
            p = skipAttributes(p + 8, u2(p + 6));
            m += 1;
        }
        int cacount = u2(p);                              // class attributes_count
        p += 2;
        byte[] want = Magic.bytes("InnerClasses");
        int a = 0;
        while (a < cacount)
        {
            int nameIdx = u2(p);
            int alen = u4(p + 2);
            if (utf8IsStr(gcp[nameIdx], want))
            {
                long q = p + 6;                           // attribute body: number_of_classes
                int nc = u2(q);
                q += 2;
                int e = 0;
                while (e < nc)
                {
                    if (u2(q) == thisClass)               // inner_class_info_index == this_class
                    {
                        return u2(q + 6);                 // inner_class_access_flags
                    }
                    q += 8;                               // inner(2) outer(2) name(2) flags(2)
                    e += 1;
                }
                return -1;
            }
            p += 6 + alen;
            a += 1;
        }
        return -1;
    }

    /** Reflection: first char of the field's JVM type descriptor (the byte after the u2 Utf8 length), or -1. */
    static int fieldTypeChar(long typeAddr, long fnBase, int fnLen)
    {
        int j = fieldRegIndex(typeAddr, fnBase, fnLen);
        return j < 0 ? -1 : u1(fldTab[j].base + fldTab[j].descOff + 2L);
    }

    /** True if the raw byte range {@code rawBase..+rawLen} equals the Utf8 (u2 length + bytes) at {@code utBase+utOff}. */
    private static boolean rawEqUtf8(long rawBase, int rawLen, long utBase, int utOff)
    {
        if (u2(utBase + utOff) != rawLen)
        {
            return false;
        }
        int k = 0;
        while (k < rawLen)
        {
            if ((Magic.load8(rawBase + k) & 0xFF) != u1(utBase + utOff + 2 + k))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /** #43: print a high-slot vtable resolution (class.name slot [Q|F]) so a garbage-slot wild-branch is traceable. */
    private static void logVtableSlot(int classOff, int nameOff, int descOff, int slot, int path)
    {
        if (logVtable == 0 || slot < 20) { return; }
        Uart.write(Magic.bytes("  V "));
        writeName(gbase + classOff + 2, u2(gbase + classOff));
        Uart.putc(0x2E);
        writeName(gbase + nameOff + 2, u2(gbase + nameOff));
        Uart.putc(0x20);
        VM.printDec(slot);
        Uart.putc(0x20);
        Uart.putc((byte) path);
        Uart.putc(0x0A);
    }

    /** Class-registry index of the class named by a {@code new}/type {@code Class} entry, or -1. */
    private static int classRegOf(int classIdx)
    {
        int nameOff = gcp[u2(gbase + gcp[classIdx])];   // Class entry -> name Utf8 offset
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, nameOff, clTab[i].base, clTab[i].nameOff))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** True if the class of {@code *ref} {@code idx} is a loaded (registered) class. */
    private static boolean refClassRegistered(int idx)
    {
        int classOff = refClassNameOff(idx);
        int i = 0;
        while (i < clCount)
        {
            if (utf8EqAt(gbase, classOff, clTab[i].base, clTab[i].nameOff))
            {
                return true;
            }
            i += 1;
        }
        return false;
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
        if (utf8IsAtBase(gbase, refClassNameOff(idx), Magic.bytes("java/lang/Object"))
                && utf8IsAtBase(gbase, mrefNameOff(idx), Magic.bytes("<init>")))
        {
            return false;
        }
        if (utf8Eq(refClassNameOff(idx), gThisNameOff) || refClassRegistered(idx))
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
        return classLoadable(gbase, refClassNameOff(idx));
    }

    /** True if the class named at {@code (base, off)} could be demand-loaded: not denylisted, and present in
     *  the embedded classDir. Deliberately narrower than "not registered": see {@link #isRealSpecial}. */
    private static boolean classLoadable(long base, int off)
    {
        if (isDenylisted(base, off))
        {
            return false;
        }
        return VM.dirBytes(base + off + 2, u2(base + off)) != 0L;
    }

    /**
     * Instance-field offset for a Fieldref in another class (or an inherited field
     * named through a subclass). A class-qualified match wins; failing that (the ref
     * names a subclass for a field its superclass declares), a name-only match finds
     * the inherited field's slot — the flattened layout keeps it consistent.
     */
    private static int globalFieldOffset(int idx)
    {
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        int i = 0;
        while (i < fldCount)                            // class-qualified
        {
            if (utf8EqAt(gbase, classOff, fldTab[i].base, fldTab[i].classOff)
                    && utf8EqAt(gbase, nameOff, fldTab[i].base, fldTab[i].nameOff))
            {
                return 16 + fldTab[i].slot * 8;
            }
            i += 1;
        }
        // Class-qualified miss: the field is INHERITED. Resolve it by walking the ref class's SUPERCLASS chain
        // and matching a field DECLARED by each ancestor -- NOT a blind name-only match. Multiple unrelated
        // classes can declare a same-named field at different slots (e.g. Pattern.buffer @72 vs
        // Pattern$SliceNode.buffer): a name-only match on `Slice.buffer` wrongly returned Pattern's slot, so the
        // getfield read a bogus field off a Slice object -> null -> the #43 NPE in Pattern$BnM.optimize.
        int pd = findPdByName(gbase, classOff);
        while (pd >= 0)
        {
            int i2 = 0;
            while (i2 < fldCount)
            {
                if (utf8EqAt(pdBase[pd], pdNameOff[pd], fldTab[i2].base, fldTab[i2].classOff)   // declared by THIS ancestor
                        && utf8EqAt(gbase, nameOff, fldTab[i2].base, fldTab[i2].nameOff))
                {
                    return 16 + fldTab[i2].slot * 8;
                }
                i2 += 1;
            }
            if (pdSuperOff[pd] == 0)
            {
                break;
            }
            pd = findPdByName(pdBase[pd], pdSuperOff[pd]);   // ascend to the superclass
        }
        return 16;
    }

    /**
     * Build this class's Type (a one-word node holding its superclass's Type, so
     * {@code instanceof} can walk the chain) and its TIB: slot 0 is the Type, then
     * one vtable entry per flattened slot. {@code new} stores the TIB into each
     * instance's header, so an object reaches both its vtable and its Type.
     */
    private static void buildTib()
    {
        allocTib();
        fillTib();
    }

    /**
     * Allocate this class's Type + TIB and set the fixed parts (Type node, TIB[0]=Type). The vtable slots and
     * itable directory are filled later by {@link #fillTib} once the methods are placed. Splitting the alloc
     * out lets loadOne register the class and pin a stable {@code gTib} address BEFORE compiling its
     * {@code <clinit>}, so a {@code new Self()} in that initializer (e.g. {@code Unsafe.theUnsafe = new Unsafe()})
     * bakes the correct TIB rather than the previous class's stale {@code gTib}. The <clinit> runs deferred
     * (after patchRelocs), by which point {@link #fillTib} has populated the vtable.
     */
    private static void allocTib()
    {
        int sr = classRegByName(gSuperNameOff);
        // ObjectModel layout: Type = { instanceSize@0, superType@8, itableDir@16 } (24
        // bytes), so VM.instanceOf and the shared Baseline core read JIT'd objects the
        // same way they read image objects (M5.4.e). The itableDir slot currently holds
        // the flat imap; step 2 replaces it with a proper itable directory.
        // M8 Type adoption: a baked class ADOPTS the writer's Type node instead of allocating its
        // own -- one Type per class across both worlds. The two stores below are then idempotent
        // (instanceSize matches by field-layout parity; superType matches because supers adopt
        // too, so clTab[sr].type IS the writer's super node); itableDir stays loader-owned
        // (buildTib overwrites it with this world's imap dir -- the writer side never reads it).
        if (gAdoptType != 0L)
        {
            gType = gAdoptType;
            Uart.write(Magic.bytes("  typeadopt "));
            printNameAt(gbase, gThisNameOff);
            Uart.putc(0x0A);
        }
        else
        {
            gType = Heap.allocData(ObjectModel.TYPE_SIZE);
        }
        Magic.store64(gType + 0, 16 + gifCount * 8);       // TYPE_INSTANCE_SIZE_OFFSET
        Magic.store64(gType + 8, sr >= 0 ? clTab[sr].type : 0L);   // TYPE_SUPER_OFFSET (0 at Object)
        if (gAdoptType == 0L)
        {
            // O(1) type checks: metal-built class Types get a depth + display like the writer's
            // (adopted nodes already carry the writer's -- never overwrite with batch-heap arrays).
            Magic.store64(gType + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET, 0L);
            Magic.store64(gType + ObjectModel.TYPE_DEPTH_OFFSET, 0L);
            Magic.store64(gType + ObjectModel.TYPE_DISPLAY_OFFSET, 0L);
            Magic.store64(gType + ObjectModel.TYPE_IMPLEMENTS_OFFSET, 0L);       // bitmap built at fillTib
            Magic.store64(gType + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L, 0L);
            buildDisplay(gType, sr >= 0 ? clTab[sr].type : 0L);
            buildRefMap(gType, sr >= 0 ? clTab[sr].type : 0L);   // GC reference map (adopted nodes carry
        }                                                        //   the writer's -- never overwrite it)
        gTib = Heap.allocData((1 + gvCount) * 8);
        Magic.store64(gTib, gType);                      // TIB[0] = Type (slots filled by fillTib)
    }

    /**
     * Build this class's GC reference map into its Type node: the superclass's map (already built — phase A
     * runs super-first) plus one bit per OWN slot whose descriptor may hold a pointer. Same shape as
     * {@link #buildDisplay}/{@code buildImplBitmap}, and the same numbering the writer uses, so an adopted
     * Type and a metal-built one describe an instance identically.
     *
     * <p>Degrades to "no map" (both words 0, so the collector scans conservatively) when the super has no
     * map or the class is wider than the two words can describe — never to a map that under-describes an
     * object, which would free live data.
     */
    private static void buildRefMap(long type, long superType)
    {
        long w0 = 1L;                                   // bit 0: this map is computed
        long w1 = 0L;
        if (superType != 0L)
        {
            w0 = Magic.load64(superType + ObjectModel.TYPE_REFMAP_OFFSET);
            w1 = Magic.load64(superType + ObjectModel.TYPE_REFMAP_OFFSET + 8L);
            if ((w0 & 1L) == 0L)
            {
                Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET, 0L);      // super is unmapped: so are we
                Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET + 8L, 0L);
                return;
            }
        }
        int s = superFieldCount();                      // own fields start after the inherited ones
        while (s < gifCount)
        {
            if (s > ObjectModel.TYPE_REFMAP_MAX_SLOT)
            {
                Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET, 0L);      // too wide to describe
                Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET + 8L, 0L);
                return;
            }
            if (mayHoldPointer(gifDescOff[s]))
            {
                if (s < 63)
                {
                    w0 = w0 | (1L << (s + 1));
                }
                else
                {
                    w1 = w1 | (1L << (s - 63));
                }
            }
            s += 1;
        }
        Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET, w0);
        Magic.store64(type + ObjectModel.TYPE_REFMAP_OFFSET + 8L, w1);
    }

    /** Whether the field whose descriptor Utf8 sits at {@code descOff} may hold a pointer the collector
     *  must follow: a reference ({@code L}/{@code [}) or a {@code long} — this VM keeps raw TIB/Type/
     *  statics addresses in {@code long} fields ({@link RVMClass}), and skipping those would sweep the
     *  live metadata. A stored Utf8 offset points at the length {@code u2}, so the text starts at +2. */
    private static boolean mayHoldPointer(int descOff)
    {
        int k = u1(gbase + descOff + 2);
        return k == 0x4C || k == 0x5B || k == 0x4A;     // 'L' object, '[' array, 'J' long
    }

    /** Fill the vtable slots (this class's + inherited impl code) and the itable directory. */
    private static void fillTib()
    {
        int s = 0;
        while (s < gvCount)
        {
            Magic.store64(gTib + 8 + s * 8, slotBuf(s));   // TIB[1+slot] = impl code
            s += 1;
        }
        long dir = buildItableDir();                    // M8 itables: per-interface tables (writer numbering)
        if (dir != 0L && instImaps != null && instImapN < instImaps.length)
        {
            instImaps[instImapN] = dir;                 // capture the DIR for the post-phase-B default refill
            instImapReg[instImapN] = classRegByName(gThisNameOff);
            instImapN += 1;
        }
        Magic.store64(gType + 16, dir);                 // TYPE_ITABLE_DIR_OFFSET
        buildImplBitmap(gType);                         // O(1) interface checks: super bits | dir bits
    }

    /** Print the Utf8 class name at (base, off) over the UART. */
    private static void printNameAt(long base, int off)
    {
        int len = u2(base + off);
        int i = 0;
        while (i < len)
        {
            Uart.putc(u1(base + off + 2 + i));
            i += 1;
        }
    }

    // ----- the lazy-compile engine: every method body compiles on its first call -----
    // A method is reached one of two ways, both landing in the same engine. A STATIC gets a phase-A cell at
    // structure registration (armPhaseACells) and its callers emit `ldr x16,[cell]; blr x16`; a VIRTUAL (or
    // any non-static) gets a deferral stub as both its registered buffer and its TIB slot (emitDeferredStub).
    // Either way the first call routes through the shared trampoline into lazyCompile, which compiles the one
    // method and data-patches the cell/slot, so later calls dispatch straight to the body.
    private static final int MAXLAZY = 8192;
    private static LazyMethod[] lzTab;     // deferred/lazy methods (reified: one LazyMethod per entry)
    private static int    lzN;
    private static long   lazyTrampAddr;   // the shared arg-preserving trampoline

    private static final boolean LAZY_TRACE  = false;   // per-method "jitc" compile trace over the UART (debug)

    /**
     * Per-batch phase timing for {@link #loadAll} over the UART (debug). A demand-load's cost is invisible in
     * the {@code load} lines -- those time only {@link #addBlob}, i.e. putting the blob on the pending list.
     * All the real work happens afterwards in one batch, so this is what says WHERE it goes.
     */
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
    private static final boolean LOAD_TRACE = false;

    /** Name every {@code invokevirtual} whose vtable slot could not be resolved at compile time (debug). Each
     *  one is a silent wrong dispatch through slot 0 -- see {@link #globalVtableSlot}. */
    private static final boolean VT_TRACE = false;

    /** One line per batch: classes, relocs, registry size, and microseconds per {@link #loadAll} phase. */
    private static void profileLoadAll(long tAll, long tMark, long tProbe, long tA, long tB, long tPatch, long tRest)
    {
        Uart.write(Magic.bytes("  loadall pd="));
        VM.printDec(pdCount);
        Uart.write(Magic.bytes(" rc="));
        VM.printDec(rcCount);
        Uart.write(Magic.bytes(" rg="));
        VM.printDec(rgCount);
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
        printDur(elapsedUs(tRest));
        Uart.write(Magic.bytes(" total="));
        printDur(elapsedUs(tAll));
        Uart.putc(0x0A);
        Uart.write(Magic.bytes("    mark rounds="));
        VM.printDec(mrRounds);
        Uart.write(Magic.bytes(" reach="));
        VM.printDec(reachN);
        Uart.write(Magic.bytes(" pend="));
        VM.printDec(pendN);
        Uart.write(Magic.bytes(" probe="));
        printDur(ticksUs(mrProbe));
        Uart.write(Magic.bytes(" seed="));
        printDur(ticksUs(mrSeed));
        Uart.write(Magic.bytes(" collect="));
        printDur(ticksUs(mrCollect));
        Uart.write(Magic.bytes(" pull="));
        printDur(ticksUs(mrPull));
        Uart.write(Magic.bytes(" struct="));
        printDur(ticksUs(mrStruct));
        Uart.write(Magic.bytes(" inst="));
        printDur(ticksUs(mrInst));
        Uart.write(Magic.bytes(" static="));
        printDur(ticksUs(mrStatic));
        Uart.write(Magic.bytes(" virt="));
        printDur(ticksUs(mrVirt));
        Uart.write(Magic.bytes(" dflt="));
        printDur(ticksUs(mrDflt));
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
    private static DynLink[] dlTab;    // phase-A dynamic-linking table (reified: one DynLink per method cell)
    private static int    dlN;

    /** Per-method stub: x9 = deferred index, then branch to the shared trampoline. */
    /**
     * Stubs, by address: {stub, lazy index}. A stub is 32 bytes of movz/movk/br — its whole purpose is to
     * be branched to from somewhere else, which is exactly the reference the collector cannot see. Recording
     * them lets the sweep say WHICH stub it is freeing, and the fault reporter say which method's stub the
     * PC landed in. 64 KiB = 4,096 entries; recording stops rather than wrapping.
     */
    // Placed in the free 256 KiB between CODE_INDEX's end (0x0374_0000) and CODE_PIN_BITMAP (0x0378_0000).
    // It used to be "64 KiB below the swept log" -- 0x037B_0000 -- which is INSIDE CODE_PIN_BITMAP
    // [0x0378_0000, 0x037C_0000): the two overlapped exactly, so stub-table writes clobbered the pin bits
    // for the arena's top 4 MiB and pin writes clobbered stub entries. Nothing detected it because a lost
    // pin only matters if that code is later swept, and a corrupted stub entry only makes stubIdxAt lie.
    // The new region is also 4x larger: at 16 bytes an entry the old 64 KiB held 4,096 stubs while a suite
    // run creates 10,000+, so stubIdxAt silently returned -1 for most of them -- which is why the
    // "it was the deferral stub for ..." diagnostics and the stub-mislink check kept coming back empty.
    // Fixed scratch at 0x0306_0000, in the free band between Bcm2711.MBOX_BUFFER (0x0305_0000, a few
    // hundred bytes) and Loader.CODE_ROOTS (0x0310_0000); 0x030A_0000..0x0310_0000 stays free after it.
    //
    // TWO homes were wrong before this one, both from picking an address that LOOKED unused:
    //   0x0374_0000 (#145) overlays THREE tables -- Heap.STATS (histograms; STATS+128 is stub entry 8),
    //     VMGc.STALE_TAB (STATS+0x400 = entry 64) and VMGc.FREED_RANGES (0x0376_0000 = entry 8192). A suite
    //     run creates 10,000+ stubs, so all three were overwritten with stub buffer addresses. Visible in
    //     every boot log since as `reqCode: 16=33563776` -- 0x2002480, a code-arena pointer sitting where an
    //     allocation count belongs.
    //   0x0380_0000 is NOT the free hole below MARK_BITMAP it appears to be: VM.SEC_STACK_HI puts the
    //     secondary core stacks there, tops at 0x0390/0x03A0/0x03B0_0000 growing DOWN, so core 1's stack
    //     occupies exactly that address.
    // These are diagnostic tables, so nothing miscomputes -- but FREED_RANGES is what makes a swept-pc fault
    // readable (it is what diagnosed #146) and the histograms are the measurement the arena work is steered
    // by, so a corrupted one costs a whole investigation. Check a candidate address against EVERY table in
    // the 0x0300_0000+ band, not just the one being cleared.
    public static final long STUB_TAB = 0x0306_0000L;
    private static final long STUB_TAB_END = 0x030A_0000L;         // 256 KiB = 16,384 stubs
    public static long stubTabN;

    /** Note that {@code buf} is the deferral stub for lazy method {@code idx}. */
    static void noteStub(long buf, int idx)
    {
        if (STUB_TAB + stubTabN * 16L + 16L <= STUB_TAB_END)
        {
            Magic.store64(STUB_TAB + stubTabN * 16L, buf);
            Magic.store64(STUB_TAB + stubTabN * 16L + 8L, (long) idx);
            stubTabN = stubTabN + 1L;
        }
    }

    /** The lazy index of the stub containing {@code addr}, or -1. Stubs are 32 bytes. */
    public static long stubIdxAt(long addr)
    {
        long i = 0;
        while (i < stubTabN)
        {
            long b = Magic.load64(STUB_TAB + i * 16L);
            if (addr >= b && addr < b + 32L)
            {
                return Magic.load64(STUB_TAB + i * 16L + 8L);
            }
            i += 1L;
        }
        return -1L;
    }

    /** Name the method a lazy index belongs to, for a diagnostic. */
    public static void printLazyName(long idx)
    {
        int k = (int) idx;
        if (k < 0 || k >= lzN || lzTab[k] == null)
        {
            Uart.write(Magic.bytes("<idx out of range>"));
            return;
        }
        printNameAt(clTab[lzTab[k].reg].base, clTab[lzTab[k].reg].nameOff);
        Uart.putc(0x2E);
        writeName(lzTab[k].blob + lzTab[k].nameOff + 2, u2(lzTab[k].blob + lzTab[k].nameOff));
    }

    private static long buildLazyCompileStub(int idx)
    {
        long buf = Heap.allocCode(32);
        noteStub(buf, idx);
        // A deferral stub outlives its own dispatch cell. Once the first call patches the cell to the compiled
        // body the stub looks unreferenced, and the sweep frees it -- correctly, by reachability. But the cell
        // is not its only caller: stale dispatch copies (an inherited TIB slot, an itable entry) still name the
        // stub, and calling one is HARMLESS by design -- it re-enters lazyCompile and returns the real body.
        // It turns fatal only once the stub has been swept: the space is reused and the branch lands in
        // whatever data now lives there. So pin it. 32 bytes each, and it is the safety net that makes every
        // stale dispatch copy correct.
        Heap.pinCodeAt(buf);
        int w = 0;
        // x17/x16, NOT x9/x10: x0.. are the argument registers, so a stub that scratches x9/x10 destroys
        // the 10th and 11th arguments of the very call it is standing in for. x16/x17 are the architectural
        // intra-procedure scratch pair and are never arguments. (This is what broke demo deep10.)
        Magic.store32(buf + w * 4L, A64Enc.movz(17, idx & 0xFFFF, 0));                           w += 1;  // x17 = idx
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (lazyTrampAddr & 0xFFFF), 0));         w += 1;  // x16 = tramp
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.br(16));                                              w += 1;
        Heap.publishCode(buf, buf + w * 4L);
        return buf;
    }

    /**
     * The shared arg-preserving trampoline. Entered with x9 = deferred index, x0..x7 = the call's args, x30 =
     * caller return. Saves the arg registers + LR, calls {@code lazyCompile(idx)} (via the writer-stashed
     * {@code VM.lazyCompileAddr}) which returns the freshly-compiled buffer, restores the args + LR, and
     * tail-branches into the method so it sees the exact original call state.
     */
    private static void buildLazyTramp()
    {
        long buf = Heap.allocCode(256);
        long ca = VM.lazyCompileAddr;
        int w = 0;
        // Preserve x0..x15, not x0..x7. lazyCompile runs the WHOLE compiler between the entry to this
        // trampoline and the tail-branch, so every argument register the callee will read must survive it.
        // Saving only x0..x7 silently dropped the 9th argument onward -- garbage that shifted whenever
        // compilation order changed, which is exactly how demo deep10 printed a different wrong answer each
        // time the loader changed. The 16 extra loads/stores happen once per method, on its first call.
        Magic.store32(buf + w * 4L, A64Enc.subImm(31, 31, 144)); w += 1;   // sub sp,sp,#144
        int r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.strx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.strx(30, 31, 128));   w += 1;   // save LR
        Magic.store32(buf + w * 4L, A64Enc.movReg(0, 17));       w += 1;   // x0 = idx (the stub put it in x17)
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (ca & 0xFFFF), 0));         w += 1;  // x16 = lazyCompile
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ca >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ca >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.blr(16));             w += 1;   // x0 = fresh buffer
        Magic.store32(buf + w * 4L, A64Enc.movReg(16, 0));       w += 1;   // x16 = target (outside the restore set)
        r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.ldrx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.ldrx(30, 31, 128));   w += 1;   // restore LR
        Magic.store32(buf + w * 4L, A64Enc.addImm(31, 31, 144)); w += 1;   // add sp,sp,#144
        Magic.store32(buf + w * 4L, A64Enc.br(16));              w += 1;   // tail-call the fresh method
        Heap.publishCode(buf, buf + w * 4L);
        lazyTrampAddr = buf;
    }

    // ---- late link resolution: the call sites RTA cannot see -------------------------------------------
    // RTA marks a method reachable, then walks its body to mark what IT calls. A method reached ONLY through
    // Method.invoke breaks that chain: it compiles on demand (the method registry has it), but nothing
    // statically reachable names it, so its OWN callees are never marked and their classes are never pulled.
    // patchRelocs then finds no target for each of its call sites. Those sites used to go straight to
    // denylistTrap -- correct for a genuinely pruned class, wrong for a class that is merely absent from a
    // closure computed without ever reading this body. So an unresolved site whose callee is NOT denylisted
    // gets a link stub instead: on the FIRST call (i.e. only if the site is actually reached) it demand-loads
    // the class and resolves the method, exactly as resolveBakeStub does for the baked world, and memoizes.
    // A cold site costs one 32-byte stub and never runs; a genuinely unresolvable one still lands in
    // denylistTrap, with the trapwire index intact because the trampoline restores LR before tail-branching.

    private static final int MAXLINKSTUB = 256;
    private static long[] lkClsU  = new long[MAXLINKSTUB];   // absolute {u2 len}{bytes} runs, as resolveBakeStub takes
    private static long[] lkNameU = new long[MAXLINKSTUB];
    private static long[] lkDescU = new long[MAXLINKSTUB];
    private static long[] lkMemo  = new long[MAXLINKSTUB];   // resolved target, filled on first call
    private static long[] lkStub  = new long[MAXLINKSTUB];
    private static int    lkCount;
    private static long   linkTrampAddr;

    /**
     * The stub every unresolved-but-not-denylisted call site is patched to, deduped by callee identity so N
     * sites calling the same method share one. Returns 0 if the table is full, and the caller falls back to
     * the trap.
     */
    private static long linkStubFor(long clsU, long nameU, long descU)
    {
        int k = 0;
        while (k < lkCount)
        {
            if (utf8EqAt(lkClsU[k], 0, clsU, 0)
                    && utf8EqAt(lkNameU[k], 0, nameU, 0)
                    && utf8EqAt(lkDescU[k], 0, descU, 0))
            {
                return lkStub[k];
            }
            k += 1;
        }
        if (lkCount >= MAXLINKSTUB)
        {
            return 0L;
        }
        if (linkTrampAddr == 0L)
        {
            buildLinkTramp();
        }
        lkClsU[lkCount] = clsU;
        lkNameU[lkCount] = nameU;
        lkDescU[lkCount] = descU;
        lkMemo[lkCount] = 0L;
        lkStub[lkCount] = buildLinkStub(lkCount);
        lkCount += 1;
        return lkStub[lkCount - 1];
    }

    /** x17 = stub index, then jump to the shared link trampoline. Same shape as the lazy deferral stub, and
     *  x16/x17 for the same reason: x0.. are the call's arguments and must survive untouched. */
    private static long buildLinkStub(int idx)
    {
        long buf = Heap.allocCode(32);
        Heap.pinCodeAt(buf);                                 // only a patched `bl` displacement names it
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.movz(17, idx & 0xFFFF, 0));                           w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (linkTrampAddr & 0xFFFF), 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((linkTrampAddr >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((linkTrampAddr >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.br(16));                                              w += 1;
        Heap.publishCode(buf, buf + w * 4L);
        return buf;
    }

    /**
     * The shared arg-preserving trampoline for link stubs — the twin of {@link #buildLazyTramp}, resolving
     * through {@code resolveLinkStub} instead of compiling. Preserving x0..x15 matters for the same reason it
     * does there: a whole demand-load runs between entry and the tail-branch, so every argument register the
     * callee will read must survive it. Restoring LR before the branch is what keeps the callee's return —
     * and, when resolution fails and the target is denylistTrap, what keeps that trap's x30-keyed trapwire
     * lookup and stack walk reading exactly as they do for a direct call.
     */
    private static void buildLinkTramp()
    {
        long buf = Heap.allocCode(256);
        long ra = VM.resolveLinkStubAddr;
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.subImm(31, 31, 144)); w += 1;   // sub sp,sp,#144
        int r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.strx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.strx(30, 31, 128));   w += 1;   // save LR
        Magic.store32(buf + w * 4L, A64Enc.movReg(0, 17));       w += 1;   // x0 = idx (the stub put it in x17)
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (ra & 0xFFFF), 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ra >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((ra >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.blr(16));             w += 1;   // x0 = resolved target
        Magic.store32(buf + w * 4L, A64Enc.movReg(16, 0));       w += 1;   // x16 = target (outside the restore set)
        r = 0;
        while (r <= 15)
        {
            Magic.store32(buf + w * 4L, A64Enc.ldrx(r, 31, r * 8)); w += 1;
            r += 1;
        }
        Magic.store32(buf + w * 4L, A64Enc.ldrx(30, 31, 128));   w += 1;   // restore LR
        Magic.store32(buf + w * 4L, A64Enc.addImm(31, 31, 144)); w += 1;   // add sp,sp,#144
        Magic.store32(buf + w * 4L, A64Enc.br(16));              w += 1;   // tail-call the resolved method
        Heap.publishCode(buf, buf + w * 4L);
        linkTrampAddr = buf;
    }

    /**
     * A link stub fired: the site really is reached, so resolve it now. Memoized, so the demand-load happens
     * once however many times the site is called. Returns {@code VM.denylistTrapAddr} when the callee cannot
     * be resolved after all — the site then behaves exactly as it did before this path existed, trap message
     * and backtrace included. Called only from the trampoline (via the stashed address).
     */
    static long resolveLinkStub(int idx)
    {
        if (idx < 0 || idx >= lkCount)
        {
            return VM.denylistTrapAddr;                 // dead force-reference (writer) / bad index
        }
        if (lkMemo[idx] != 0L)
        {
            return lkMemo[idx];
        }
        VM.loaderLock();                                // demand-loads a class: one compiler at a time
        long buf = resolveLinkTarget(lkClsU[idx], lkNameU[idx], lkDescU[idx]);
        if (buf == 0L)
        {
            buf = VM.denylistTrapAddr;
        }
        lkMemo[idx] = buf;
        VM.loaderUnlock();
        return buf;
    }

    /**
     * Demand-load the callee's class and find its callable buffer — the same three-tier lookup
     * {@link #resolveBakeStub} uses (registered body, celled static, TIB slot), plus the provided-native
     * fallback. Returns 0 rather than halting: an unresolvable callee here is a legitimate outcome (the class
     * is genuinely absent), and the caller turns it back into the denylist trap.
     */
    private static long resolveLinkTarget(long clsU, long nameU, long descU)
    {
        if (clTab == null)
        {
            return 0L;
        }
        Uart.write(Magic.bytes("  linkresolve "));
        printNameAt(clsU, 0);
        Uart.putc(0x2E);
        printNameAt(nameU, 0);
        Uart.putc(0x0A);
        int len = u2(clsU);
        byte[] slash = new byte[len];
        int i = 0;
        while (i < len)
        {
            slash[i] = (byte) u1(clsU + 2 + i);
            i += 1;
        }
        if (classIndexByName(slash) < 0 && loadClassIncremental(slash) == 0L)
        {
            return 0L;                                  // not in the classDir after all
        }
        int reg = classIndexByName(slash);
        if (reg >= 0 && clTab[reg].state < RVMClass.ST_INSTANTIATED)
        {
            return 0L;                                  // half-lifecycle: do not branch into it
        }
        long buf = bufBySigU(clsU, nameU, descU);
        if (buf == 0L)
        {
            buf = nativeBufAt(clsU, 0, nameU, 0);       // a PROVIDED NATIVE has no bytecode to find
        }
        if (buf == 0L)
        {
            buf = compileSigOnDemand(clsU, nameU, descU);   // never compiled AND not dispatchable: JIT it now
        }
        return buf;
    }

    /**
     * Compile one method of an already structure-loaded class, chosen by name AND descriptor, and return its
     * buffer. The last resort for a link stub: {@link #bufBySigU}'s three tiers all answer through a DISPATCH
     * table (a registered buffer, a static cell, a vtable slot), and a method can be perfectly callable while
     * appearing in none of them.
     *
     * <p>The case that forced this is a <b>static method on an interface</b>. {@code registerInterface} walks
     * only {@code isVirtual} methods, to give them itable indices; a static one gets no itable index, no
     * vtable slot and no static cell, so it is registered nowhere at all. {@code Arguments.of} in the JUnit
     * shim is exactly that, and it is what a reflectively-reached {@code @MethodSource} factory calls. The
     * older reflective path ({@link #compileMethodOnDemand}) refuses interfaces outright for the same reason
     * it has no TIB to reuse — but {@code compileReuseTib} already means we never touch one.
     *
     * <p>Matching on the DESCRIPTOR as well as the name is not optional here: {@code of(T)} and the varargs
     * {@code of(T...)} are different methods at the same name, and compiling the wrong one links the call
     * site to a body with the wrong signature. Returns 0 if there is no such method or it has no Code.
     */
    private static long compileSigOnDemand(long clsU, long nameU, long descU)
    {
        int reg = regBySigU(clsU);
        if (reg < 0)
        {
            return 0L;
        }
        long blob = clTab[reg].base;
        int len = blobLenOf(blob);
        parseConstPool(blob, len);                      // rebuild this class's compile state (as loadBodies
        parseFields();                                  //   does, minus the TIB fill)
        gStatics = clTab[reg].statics;                  // reuse the phase-A statics block
        findBootstrapMethods();
        parseVtable(blob);
        gType = clTab[reg].type;
        gTib = clTab[reg].tib;                          // 0 for an interface; compileReuseTib means unused
        parseForMethods(blob, len);
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        long code = 0L;
        int descOff = 0;
        int isStatic = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (utf8EqAt(blob, gcp[u2(p + 2)], nameU, 0)
                    && utf8EqAt(blob, gcp[u2(p + 4)], descU, 0))
            {
                long c = findCode(blob, p + 8, attrs);  // sets gcodeLen + gMaxLocals
                if (c != 0L)
                {
                    code = c;
                    descOff = gcp[u2(p + 4)];
                    isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                    break;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        if (code == 0L)
        {
            return 0L;                                  // no such signature, or abstract/native (no Code)
        }
        int rcMark = rcCount;                           // patch only THIS compile's relocs (see patchRelocsFrom)
        int rsMark = rsCount;
        compileReuseTib = true;
        compile(code, gcodeLen, descOff, isStatic);
        compileReuseTib = false;
        registerAll();
        patchRelocsFrom(rcMark, rsMark);                // its own callees, including further link stubs
        return bufBySigU(clsU, nameU, descU);
    }

    /**
     * Compile deferred method {@code idx} at call time: restore its class's compile context, re-find the
     * method, compile just it, install the fresh buffer into the TIB slot, and return it. Called only from
     * the trampoline (via the stashed address). Guarded so the writer's dead force-reference is a no-op.
     */
    static long lazyCompile(int idx)
    {
        if (idx < 0 || lzTab == null || idx >= lzN)
        {
            return 0L;                                  // dead force-reference (writer) / bad index
        }
        if (lzTab[idx].cache != 0L)
        {
            return lzTab[idx].cache;                         // memoized: compile once, however many callers hit the stub
        }
        VM.loaderLock();                                // SMP: the compile context is static -- one compiler at a time
        long done = lazyCompileLocked(idx);
        VM.loaderUnlock();
        return done;
    }

    /** {@link #lazyCompile}'s body, run under the loader lock. */
    private static long lazyCompileLocked(int idx)
    {
        if (lzTab[idx].cache != 0L)
        {
            return lzTab[idx].cache;                    // another core compiled it while we waited for the lock
        }
        ensureClinit(lzTab[idx].reg);                   // JVMS 5.5 barrier: first call into the class initializes it.
                                                        // BEFORE restoreCtxForCompile -- the initializer re-enters here
                                                        // and each nested compile clobbers the g* context.
        restoreCtxForCompile(lzTab[idx].blob, lzTab[idx].len, lzTab[idx].reg);
        long code;
        int len;
        int descOff;
        int isStatic;
        if (lzTab[idx].code != 0L)                           // genuine deferral: the body was captured, compile it
        {
            code = lzTab[idx].code;
            len = lzTab[idx].codeLen;
            descOff = lzTab[idx].descOff;
            isStatic = lzTab[idx].isStatic;
            gMaxLocals = lzTab[idx].maxLocals;
        }
        else                                            // 1b/1c: re-find the method by name+descriptor
        {
            code = findMethodByOffsets(lzTab[idx].nameOff, lzTab[idx].descOff);
            if (code == 0L)
            {
                return 0L;
            }
            len = gcodeLen;
            descOff = gFoundDescOff;
            isStatic = gFoundStatic;
        }
        // M8 endgame (the Loader USES baked java.base): if the writer baked this exact method and
        // listed it as loader-linkable, run the image's compiled stock code instead of compiling our
        // own copy -- the JikesRVM boot-image contract. Field offsets and direct calls inside baked
        // code are world-independent; the link table already excludes anything that isn't.
        if (BAKED_LINK)
        {
            int bkName = lzTab[idx].nameOff;
            if (bkName == 0)
            {
                bkName = findNameByCode(lzTab[idx].code);    // deferral entries capture only bytecode
            }
            long baked = bakedBuf(bkName, descOff);
            if (baked != 0L)
            {
                Uart.write(Magic.bytes("  baked "));
                printNameAt(gbase, gThisNameOff);
                Uart.putc(0x2E);
                printNameAt(gbase, bkName);
                Uart.putc(0x0A);
                lzTab[idx].cache = baked;
                if (lzTab[idx].slot != 0L)
                {
                    Magic.store64(lzTab[idx].slot, baked);
                }
                return baked;
            }
        }
        if (LAZY_TRACE)                                  // per-method compile trace (debug; off by default)
        {
            Uart.write(Magic.bytes("  jitc "));
            printNameAt(gbase, gThisNameOff);
            Uart.putc(0x2E);
            int trName = lzTab[idx].nameOff;            // deferral entries capture bytecode, not the name
            if (trName == 0)
            {
                trName = findNameByCode(lzTab[idx].code);
            }
            printNameAt(lzTab[idx].blob, trName);
            printNameAt(lzTab[idx].blob, lzTab[idx].descOff);
            Uart.putc(0x0A);
        }
        compileReuseTib = true;                         // keep this class's TIB (already filled)
        int rcMark = rcCount;                           // this compile's own reloc sites start here
        int rsMark = rsCount;
        lzCompiling = true;                             // collect the classes this body actively uses
        long buf = compile(code, len, descOff, isStatic);
        lzCompiling = false;
        compileReuseTib = false;
        patchRelocsFrom(rcMark, rsMark);                // batch-end patchRelocs is long past: resolve OUR sites now,
        if (buf == 0L)                                  // or a `bl 0` wild-branches to address 0 (see patchRelocsFrom)
        {
            capHalt(Magic.bytes("lazy-compile-null"), idx);   // the trampoline would `br 0` -- halt with a name instead
        }
        rememberLazyBody(idx, buf);
        lzTab[idx].cache = buf;                         // memoize BEFORE draining: an initializer we are about
                                                        // to run may call straight back into this method
        drainPendingInit();                             // initialize what the body touches -- still before it runs
        if (lzTab[idx].slot != 0L)                           // 1b/1c: point the TIB slot / offset cell at the buffer
        {
            Magic.store64(lzTab[idx].slot, buf);
        }
        return buf;
    }

    /**
     * Point the method registry at a freshly lazy-compiled body. {@link #printFrameAt} names a PC by the
     * nearest registered buffer at-or-below it, so an unregistered fresh buffer makes every frame in that
     * method report as whatever unrelated method happens to sit below it (the socket stack traced as
     * {@code InternalError.<init>} until this landed). A celled static has no registry entry at all, so it
     * gets one here; a deferred method has one pointing at its stub, which is updated in place — which also
     * lets later direct calls link straight to the body instead of through the stub.
     */
    private static void rememberLazyBody(int idx, long buf)
    {
        int nameOff = lzTab[idx].nameOff;
        if (nameOff == 0)
        {
            nameOff = findNameByCode(lzTab[idx].code);   // deferral entries capture bytecode, not the name
        }
        int i = 0;
        while (i < rgCount)
        {
            if (rgTab[i].base == lzTab[idx].blob && rgTab[i].nameOff == nameOff
                    && rgTab[i].descOff == lzTab[idx].descOff)
            {
                rgTab[i].buf = buf;
                rgTab[i].line = mLine[0];
                rgTab[i].src = mSrc[0];
                return;
            }
            i += 1;
        }
        register(lzTab[idx].blob, gThisNameOff, nameOff, lzTab[idx].descOff, buf, mLine[0], mSrc[0], 0);
    }

    /** Re-establish the g* compile context for an already-structure-registered class (loadBodies' preamble). */
    private static void restoreCtxForCompile(long bytes, int len, int reg)
    {
        if (reg >= 0 && clTab[reg].state < RVMClass.ST_RESOLVED)
        {
            // Lifecycle guard: a lazy compile needs the class's STRUCTURE (field layout, statics,
            // vtable numbering, Type/TIB). Firing before phase A completes would compile against a
            // half-built context and corrupt silently -- halt loudly instead.
            capHalt(Magic.bytes("lifecycle-compile"), reg);
        }
        parseConstPool(bytes, len);
        parseFields();
        gStatics = clTab[reg].statics;
        findBootstrapMethods();
        parseVtable(bytes);
        gType = clTab[reg].type;
        gTib = clTab[reg].tib;
        provideKnownStatics();
    }

    // M8 endgame: consult the writer's baked-method link table before lazy-compiling (default ON).
    private static final boolean BAKED_LINK = true;

    /**
     * M8 object links: resolve a called bake stub — the writer couldn't compile this method, so
     * demand-load its class into the running program (the loaded world shares the baked world's
     * statics, Types, vtable numbering and itables, so the lazily-compiled code and the baked code
     * agree on everything) and return a callable buffer for the method. The three utf8 args are
     * absolute {u2 len}{bytes} runs from the stub table. Halts loudly if the class isn't embedded
     * or the method can't be found — a stub fired that the system genuinely cannot satisfy.
     */
    static long resolveBakeStub(long clsU, long nameU, long descU)
    {
        if (clTab == null)
        {
            capHalt(Magic.bytes("bakeresolve-early"), 0);   // a stub fired before the loader exists
        }
        Uart.write(Magic.bytes("  bakeresolve "));
        printNameAt(clsU, 0);
        Uart.putc(0x2E);
        printNameAt(nameU, 0);
        Uart.putc(0x0A);
        int len = u2(clsU);
        byte[] slash = new byte[len];
        int i = 0;
        while (i < len)
        {
            slash[i] = (byte) u1(clsU + 2 + i);
            i += 1;
        }
        if (classIndexByName(slash) < 0)
        {
            if (loadClassIncremental(slash) == 0L)
            {
                capHalt(Magic.bytes("bakeresolve-load"), 0);
            }
        }
        int lreg = classIndexByName(slash);
        if (lreg >= 0 && clTab[lreg].state < RVMClass.ST_INSTANTIATED)
        {
            // Lifecycle guard: baked code is about to CALL into this class, so its bodies/cells and
            // TIB must be in place (phase B). A short state here means the incremental load pipeline
            // returned a half-lifecycle class -- halt loudly rather than branch into a 0 buffer.
            capHalt(Magic.bytes("lifecycle-resolve"), lreg);
        }
        long buf = bufBySigU(clsU, nameU, descU);
        if (buf == 0L)
        {
            buf = nativeBufAt(clsU, 0, nameU, 0);       // a PROVIDED NATIVE has no bytecode to find: link the VM helper
        }
        if (buf == 0L)
        {
            capHalt(Magic.bytes("bakeresolve-find"), 0);
        }
        return buf;
    }

    /** A callable buffer for (class, name, desc) given as absolute utf8 runs: a registered compiled
     *  buffer, else a phase-A static cell's stub, else the class's TIB slot (virtual lazy stub) — any
     *  of which self-compiles on entry if still lazy. 0 if unknown. */
    private static long bufBySigU(long clsU, long nameU, long descU)
    {
        int k = 0;
        while (k < rgCount)
        {
            if (rgTab[k].buf != 0L
                    && utf8EqAt(rgTab[k].base, rgTab[k].classOff, clsU, 0)
                    && utf8EqAt(rgTab[k].base, rgTab[k].nameOff, nameU, 0)
                    && utf8EqAt(rgTab[k].base, rgTab[k].descOff, descU, 0))
            {
                return rgTab[k].buf;
            }
            k += 1;
        }
        long cell = dlCellOf(clsU, 0, nameU, 0, descU, 0);   // tier 2: a celled static, callable via its cell
        if (cell != 0L)
        {
            return Magic.load64(cell);
        }
        k = 0;
        while (k < vtCount)
        {
            if (utf8EqAt(vtClassBase[k], vtClassOff[k], clsU, 0)
                    && utf8EqAt(vtNameBase[k], vtNameOff[k], nameU, 0)
                    && utf8EqAt(vtNameBase[k], vtDescOff[k], descU, 0))
            {
                int r = regBySigU(clsU);
                if (r >= 0 && clTab[r].tib != 0L)
                {
                    return Magic.load64(clTab[r].tib + 8 + vtSlot[k] * 8L);
                }
            }
            k += 1;
        }
        return 0L;
    }

    /** Registry index of the loaded class named by the absolute utf8 run {@code clsU}, or -1. */
    private static int regBySigU(long clsU)
    {
        int r = 0;
        while (r < clCount)
        {
            if (utf8EqAt(clTab[r].base, clTab[r].nameOff, clsU, 0))
            {
                return r;
            }
            r += 1;
        }
        return -1;
    }

    /**
     * M8 world unification: linked baked code dispatches invokevirtual by WRITER vtable-slot number
     * on whatever receiver it is handed -- usually a LOADER-built object. That is sound only if both
     * worlds flatten a class's vtable identically, so for every baked class (the writer emits its
     * slot signatures) verify OUR freshly-built flattening slot-for-slot at structure time. A
     * divergence prints loudly BEFORE any cross-world dispatch can land on the wrong slot.
     */
    // M8 Type adoption: the writer's Type node for the class currently in phase A (0 = none) --
    // set by checkVtParity from the signature table, consumed by allocTib so a baked class has
    // ONE Type across both worlds (cross-world instanceof/checkcast compare equal pointers).
    private static long gAdoptType;

    private static long gVtSigSlots;    // matched vtSig entry's slot-pair table (0 = class not baked)
    private static int gVtSigCount;     // ... its slot count
    private static long gAdoptStatics;  // M8 statics unification: the writer's dense per-class block (0 = none)
    private static int gAdoptStaticCount;   // ... its declared-static slot count (guard vs gsfCount)

    /** Find the current class's vtSig/adoption entry: sets gAdoptType/gAdoptStatics + parity data. */
    private static void findVtSig()
    {
        gAdoptType = 0L;
        gVtSigSlots = 0L;
        gVtSigCount = 0;
        gAdoptStatics = 0L;
        gAdoptStaticCount = 0;
        int n = (int) VM.vtSigCount;
        int i = 0;
        while (i < n)
        {
            long e = VM.vtSigTable + (long) i * 48L;
            if (utf8EqAt(gbase, gThisNameOff, Magic.load64(e), 0))
            {
                gVtSigSlots = Magic.load64(e + 8L);
                gVtSigCount = (int) Magic.load64(e + 16L);
                gAdoptType = Magic.load64(e + 24L);
                gAdoptStatics = Magic.load64(e + 32L);
                gAdoptStaticCount = (int) Magic.load64(e + 40L);
                return;
            }
            i += 1;
        }
    }

    /** M8 statics unification: replace the freshly-allocated gStatics with the writer's dense
     *  per-class block -- ONE home per static field across both worlds (this world's <clinit>
     *  run then initializes the SHARED slots; deferred classes carry the seed-JVM snapshot).
     *  Slot numbering = declared statics in declaration order on both sides; a count mismatch
     *  keeps the loader block (safe degrade) and prints the divergence. */
    private static void adoptStatics()
    {
        if (gAdoptStatics == 0L)
        {
            return;
        }
        if (gAdoptStaticCount != gsfCount)
        {
            Uart.write(Magic.bytes("  staticadopt "));
            printNameAt(gbase, gThisNameOff);
            Uart.write(Magic.bytes(" DIFF "));
            VM.printDec(gAdoptStaticCount);
            Uart.putc(0x2F);
            VM.printDec(gsfCount);
            Uart.putc(0x0A);
            return;
        }
        gStatics = gAdoptStatics;
    }

    private static void checkVtParity()
    {
        findVtSig();
        if (gVtSigSlots != 0L)
        {
            vtParityAt(gVtSigSlots, gVtSigCount);
        }
    }

    /** M8 itables: verify interface {@code reg}'s flattened per-interface slot numbering against
     *  the writer's (the vtSig entry carries its flattened interfaceMethods list) -- checked at
     *  registration, before any implementor builds an itable from the run. Uses the entry found by
     *  the branch's earlier {@link #findVtSig} call. */
    private static void checkIfParity(int reg)
    {
        if (gVtSigSlots == 0L)
        {
            return;                                     // not a baked interface: nothing to compare
        }
        Uart.write(Magic.bytes("  itparity "));
        printNameAt(gbase, gThisNameOff);
        if (gVtSigCount != clTab[reg].ifmCount)
        {
            Uart.write(Magic.bytes(" DIFF count "));
            VM.printDec(gVtSigCount);
            Uart.putc(0x2F);
            VM.printDec(clTab[reg].ifmCount);
            Uart.putc(0x0A);
            return;
        }
        int s = 0;
        while (s < gVtSigCount)
        {
            long p = gVtSigSlots + (long) s * 16L;
            int i = clTab[reg].ifmStart + s;
            if (!utf8EqAt(ifBase[i], ifNameOff[i], Magic.load64(p), 0)
                    || !utf8EqAt(ifBase[i], ifDescOff[i], Magic.load64(p + 8L), 0))
            {
                Uart.write(Magic.bytes(" DIFF slot "));
                VM.printDec(s);
                Uart.putc(0x0A);
                return;
            }
            s += 1;
        }
        Uart.write(Magic.bytes(" OK "));
        VM.printDec(gVtSigCount);
        Uart.putc(0x0A);
    }

    /** Compare the current class's gv flattening against writer slot signatures at {@code slots}. */
    private static void vtParityAt(long slots, int count)
    {
        Uart.write(Magic.bytes("  vtparity "));
        printNameAt(gbase, gThisNameOff);
        if (count != gvCount)
        {
            Uart.write(Magic.bytes(" DIFF count "));
            VM.printDec(count);
            Uart.putc(0x2F);
            VM.printDec(gvCount);
            Uart.putc(0x0A);
            int w = 0;                                  // name the writer slots the loader lacks: a bare count
                                                        //   mismatch says nothing, and the missing names ARE
                                                        //   the diagnosis (six Object virtuals = no Object)
            while (w < count)
            {
                long q = slots + (long) w * 16L;
                int f = 0;
                int found = 0;
                while (f < gvCount)
                {
                    if (utf8EqAt(gvTab[f].base, gvTab[f].name, Magic.load64(q), 0)
                            && utf8EqAt(gvTab[f].base, gvTab[f].desc, Magic.load64(q + 8L), 0))
                    {
                        found = 1;
                        f = gvCount;
                    }
                    f += 1;
                }
                if (found == 0)
                {
                    Uart.write(Magic.bytes("    missing slot "));
                    VM.printDec(w);
                    Uart.putc(0x20);
                    writeName(Magic.load64(q) + 2L, u2(Magic.load64(q)));
                    writeName(Magic.load64(q + 8L) + 2L, u2(Magic.load64(q + 8L)));
                    Uart.putc(0x0A);
                }
                w += 1;
            }
            return;
        }
        int s = 0;
        while (s < count)
        {
            long p = slots + (long) s * 16L;
            if (!utf8EqAt(gvTab[s].base, gvTab[s].name, Magic.load64(p), 0)
                    || !utf8EqAt(gvTab[s].base, gvTab[s].desc, Magic.load64(p + 8L), 0))
            {
                Uart.write(Magic.bytes(" DIFF slot "));
                VM.printDec(s);
                Uart.putc(0x0A);
                return;
            }
            s += 1;
        }
        Uart.write(Magic.bytes(" OK "));
        VM.printDec(count);
        Uart.putc(0x0A);
    }

    /**
     * The writer-baked, loader-linkable compiled buffer for the CURRENT class's method with the given
     * (name, descriptor) Utf8 offsets in {@code gbase} -- or 0 (compile our own copy). Table entries are
     * {classUtf8, nameUtf8, descUtf8, code} quadruples of image addresses; the name runs are
     * {u2 len}{bytes}, the same shape as classfile Utf8s, so {@link #utf8EqAt} compares them directly
     * (each run's own address with offset 0).
     */
    private static long bakedBuf(int nameOff, int descOff)
    {
        if (nameOff == 0)
        {
            return 0L;
        }
        int n = (int) VM.bakedCount;
        int i = 0;
        while (i < n)
        {
            long e = VM.bakedTable + (long) i * 32L;
            if (utf8EqAt(gbase, gThisNameOff, Magic.load64(e), 0)
                    && utf8EqAt(gbase, nameOff, Magic.load64(e + 8L), 0)
                    && utf8EqAt(gbase, descOff, Magic.load64(e + 16L), 0))
            {
                return Magic.load64(e + 24L);
            }
            i += 1;
        }
        return 0L;
    }

    /** The name Utf8 offset of the current blob's method whose Code attribute is at {@code code} -- the
     *  inverse of {@link #findMethodByOffsets}, for deferred entries that captured only bytecode.
     *  Compile context must be established (gp at the method table). 0 if not found. */
    private static int findNameByCode(long code)
    {
        long p = gp;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (findCode(gbase, p + 8, attrs) == code)
            {
                return gcp[u2(p + 2)];
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0;
    }

    /** Find a method in the current blob by its (name, descriptor) Utf8 offsets; sets gcodeLen/gFoundDescOff/
     *  gFoundStatic like {@link #findMethod}. Returns its Code address, or 0. */
    private static long findMethodByOffsets(int nameOff, int descOff)
    {
        long p = gp;
        gMethodsStart = p;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p + 6);
            if (gcp[u2(p + 2)] == nameOff && gcp[u2(p + 4)] == descOff)
            {
                long code = findCode(gbase, p + 8, attrs);
                if (code != 0L)
                {
                    gFoundDescOff = gcp[u2(p + 4)];
                    gFoundStatic = (access & 0x0008) != 0 ? 1 : 0;
                    return code;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0L;
    }

    /**
     * The offset-table cell a direct call should indirect through, or 0 for a normal `bl`. Every static is
     * celled at structure registration, so the caller emits `ldr x16,[cell]; blr x16` and the cell resolves
     * itself on first call. Runs at compile time in both the size and emit passes, and dedups by target, so
     * both passes see the same fixed 5-word sequence and sizing stays consistent.
     */
    static long lazyStaticCell(int methodCp)
    {
        if (dlTab == null)
        {
            return 0L;                                  // no cells yet (nothing celled): normal BL path
        }
        return dlCellFor(refClassNameOff(methodCp), mrefNameOff(methodCp), mrefDescOff(methodCp));
    }

    /**
     * The phase-A cell for a static/special call ref, or 0. Resolution follows JVMS order: the ref'd class
     * first, then its superclasses — an INHERITED static is named through the subclass by javac (e.g.
     * {@code ArrayList.subListRangeCheck}, declared on {@code AbstractList}). Without the walk such a call
     * finds no cell, and no registry entry either (a metadata-only class's celled statics are never eagerly
     * compiled or registered), so it would trap as an unresolved callee.
     */
    private static long dlCellFor(int classOff, int nameOff, int descOff)
    {
        long cell = dlCellAt(gbase, classOff, nameOff, descOff);
        if (cell != 0L)
        {
            return cell;
        }
        int pd = findPdByName(gbase, classOff);
        while (pd >= 0 && pdSuperOff[pd] != 0)
        {
            int spd = findPdByName(pdBase[pd], pdSuperOff[pd]);
            if (spd < 0)
            {
                return 0L;
            }
            cell = dlCellAt(pdBase[spd], pdNameOff[spd], nameOff, descOff);
            if (cell != 0L)
            {
                return cell;
            }
            pd = spd;
        }
        return 0L;
    }

    /** Cell for the method (name, desc in gbase) declared by the class named at {@code classOff} in
     *  {@code clsBase}, or 0 if that exact class has no such phase-A cell. */
    private static long dlCellAt(long clsBase, int classOff, int nameOff, int descOff)
    {
        return dlCellOf(clsBase, classOff, gbase, nameOff, gbase, descOff);
    }

    /** M8 phase-A: at structure registration of the gated class, allocate an offset cell + lazy stub for each
     *  of its (static) methods, captured straight from the classfile method table (available at phase A via
     *  gMethodsStart). The cells live in the {@code dl*} table so a later caller finds them regardless of load
     *  order; each carries an lz entry that compiles the body on first call and data-patches the cell. */
    private static void armPhaseACells()
    {
        if (!stage2Gated(gbase, gThisNameOff))
        {
            return;
        }
        lazyEnsureTables();
        if (dlTab == null)
        {
            dlTab = new DynLink[MAXLAZY];
            dlN = 0;
        }
        int reg = clCount - 1;                          // this class's registry index (registerClassStructure did clCount++)
        int pd = findPdByName(gbase, gThisNameOff);
        int blobLen = pdLen[pd];
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        int armed = 0;
        while (m < mcount)
        {
            int access = u2(p);
            int attrs = u2(p + 6);
            int nameOff = gcp[u2(p + 2)];
            int descOff = gcp[u2(p + 4)];
            long code = findCode(gbase, p + 8, attrs);  // sets gcodeLen + gMaxLocals
            if (code != 0L && (access & 0x0008) != 0    // static methods only (Objects is all-static)
                    && !utf8IsAtBase(gbase, nameOff, Magic.bytes("<clinit>")) && lzN < MAXLAZY && dlN < MAXLAZY)
            {
                int idx = lzN;
                lzTab[idx] = new LazyMethod();
                lzTab[idx].blob = gbase;
                lzTab[idx].len = blobLen;
                lzTab[idx].reg = reg;
                lzTab[idx].nameOff = nameOff;
                lzTab[idx].descOff = descOff;
                lzTab[idx].code = code;
                lzTab[idx].codeLen = gcodeLen;
                lzTab[idx].isStatic = 1;
                lzTab[idx].maxLocals = gMaxLocals;
                lzTab[idx].cache = 0L;
                long cell = Heap.allocData(8);
                lzTab[idx].slot = cell;                     // first call data-patches the cell -> later calls dispatch direct
                Magic.store64(cell, buildLazyCompileStub(idx));
                lzN += 1;
                DynLink d = new DynLink();
                if (gbase == 0L)                            // PROBE: born zero, or zeroed later? This is the
                {                                            //   write site, so the class is still in hand.
                    Uart.write(Magic.bytes("\nDL BORN ZERO at dlN="));
                    VM.printDec(dlN);
                    Uart.write(Magic.bytes(" while compiling "));
                    printCurrentClass();
                    Uart.putc(0x0A);
                    while (true) { Magic.wfe(); }
                }
                d.blob = gbase;
                d.classOff = gThisNameOff;
                d.nameOff = nameOff;
                d.descOff = descOff;
                d.cell = cell;
                dlTab[dlN] = d;
                dlN += 1;
                armed += 1;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        verifyDlTab();                                      // ... and check none went zero since insertion
        if (LOAD_TRACE)
        {
            Uart.write(Magic.bytes("  phaseA: "));
            VM.printDec(armed);
            Uart.write(Magic.bytes(" cells at structure time for "));
            printNameAt(gbase, gThisNameOff);
            Uart.putc(0x0A);
        }
    }

    /**
     * M8 stage 5: LAZY, FULL STOP. EVERY demand-loaded class is metadata-only — phase-A cells for its
     * statics, deferred stubs for its virtuals — with only {@link #eagerKept} (now `java/lang/Object`
     * alone) on the eager path. Initializers are the one thing still compiled at load: {@code <init>} and
     * {@code <clinit>} run as part of loading, which is what has kept the hand-tuned clinit ordering intact
     * through the whole arc.
     *
     * <p>This drops the last restriction — the `java/`/`jdk/`/`sun/` prefix — so demo and plugin classes are
     * lazy too, not just `java.base`. There was never a reason for guest code to be the eager exception; the
     * prefix was scaffolding from when laziness was gated to a handful of named java.base utilities.
     */
    private static boolean stage2Gated(long base, int off)
    {
        return !eagerKept(base, off);
    }

    /**
     * The classes KEPT on the eager compile path — down to ONE. Stage 5 emptied this list a prefix at a
     * time, each with its own Pi run: the reflection floor and Throwable hierarchy, the reference/cleaner/
     * event subsystem, concurrency and {@code Unsafe}, {@code System}/{@code Thread}/the access shims, the
     * charset/buffer/fd data layer, the invoke shims, and finally the socket-native stack itself.
     *
     * <p>{@code java.lang.Object} stays because it is not a class like the others: its 9 virtuals are the
     * prefix of EVERY vtable in both worlds, so its slots are what writer-baked code and loader-compiled
     * code agree on. Deferring them would put stubs in that shared prefix, and a stub there is entered
     * before the loader can be sure which world the receiver came from. There is no demand for making it
     * lazy either — every program uses it immediately.
     */
    private static boolean eagerKept(long base, int off)
    {
        return utf8IsAtBase(base, off, Magic.bytes("java/lang/Object"));
    }

    /** M8 Stage 2: true if the current class's method (name, desc) already has a structure-time phase-A cell,
     *  so compileClass can skip its eager compile (it will compile on first call through the cell). */
    private static boolean phaseACelled(int nameOff, int descOff)
    {
        if (dlTab == null)
        {
            return false;
        }
        int k = 0;
        while (k < dlN)
        {
            DynLink d = dlTab[k];
            if (d.blob == gbase && d.nameOff == nameOff && d.descOff == descOff)
            {
                return true;
            }
            k += 1;
        }
        return false;
    }

/** Allocate the shared lazy tables + trampoline if not yet done (shared by 1b and 1c). */
    private static void lazyEnsureTables()
    {
        if (lzTab == null)
        {
            lzTab = new LazyMethod[MAXLAZY];
            lzN = 0;
        }
        if (lazyTrampAddr == 0L)
        {
            buildLazyTramp();
        }
    }

/**
     * Repair default-method imap slots left 0 during phase B. {@link #buildImap} fills an unoverridden interface
     * method's slot with {@link #defaultImplOf} = the interface default's COMPILED buffer, but phase B is
     * superclass-first (not super-interface-first), so the interface holding the default may be emitted AFTER an
     * implementor -- leaving its rgBuf (hence the slot) 0 at buildImap time (e.g. Streams$RangeIntSpliterator's
     * slot for Spliterator.getExactSizeIfKnown -> copyInto blr'd a 0 slot). Now that every body is emitted, re-run
     * defaultImplOf for each still-0 slot. Safe: defaultImplOf is context-free (persistent ifBase/rgBuf), returns
     * 0 for abstract methods, and a filled slot for an interface a class doesn't implement is never dispatched
     * (its dir lacks that interface). Mirrors fillClassVtBuf for the vtable.
     */
    private static void refillImaps()
    {
        int m = 0;
        while (m < instImapN)
        {
            long dir = instImaps[m];                    // the class's itable DIRECTORY (per-interface tables)
            int reg = instImapReg[m];
            if (reg >= 0 && dir != 0L)
            {
                int n = ifaceClosureOf(reg);            // the class's full interface set (persistent registries)
                int k = 0;
                long t = Magic.load64(dir);
                while (t != 0L)                         // walk entries by their TYPE key (order-independent)
                {
                    int ir = regOfType(t);
                    if (ir >= 0)
                    {
                        refillItable(n, ir, Magic.load64(dir + k * 16 + 8));
                    }
                    k += 1;
                    t = Magic.load64(dir + k * 16);
                }
            }
            m += 1;
        }
    }

    /** Refill still-0 slots of interface {@code ir}'s itable {@code it} with late-compiled defaults. */
    private static void refillItable(int n, int ir, long it)
    {
        int s = 0;
        while (s < clTab[ir].ifmCount)
        {
            if (Magic.load64(it + s * 8L) == 0L)
            {
                int i = clTab[ir].ifmStart + s;
                long b = defaultBySig(n, ifBase[i], ifNameOff[i], ifDescOff[i]);
                if (b != 0L)
                {
                    Magic.store64(it + s * 8L, b);
                }
            }
            s += 1;
        }
    }

    /** {@link #ifaceClosure} but for an already-registered class {@code reg}, from the PERSISTENT registries
     *  ({@code clIfaceReg}/{@code clSuperReg}) rather than the transient current-compile state -- so refillImaps
     *  can recompute a class's interface set after phase B. Fills {@link #ifClosureBuf}; returns the count. */
    private static int ifaceClosureOf(int reg)
    {
        int n = 0;
        int j = 0;
        while (j < clIfaceRegN[reg])                    // reg's own directly-declared interfaces
        {
            n = addIfaceUnique(n, clIfaceReg[reg * MAX_DIRECT_IF + j]);
            j += 1;
        }
        int sr = clTab[reg].superReg;                       // the whole superclass chain's direct interfaces
        int guard = 0;
        while (sr >= 0 && guard < 64)
        {
            j = 0;
            while (j < clIfaceRegN[sr])
            {
                n = addIfaceUnique(n, clIfaceReg[sr * MAX_DIRECT_IF + j]);
                j += 1;
            }
            sr = clTab[sr].superReg;
            guard += 1;
        }
        int i = 0;
        while (i < n)                                   // fold in each collected interface's extended interfaces
        {
            int r = ifClosureBuf[i];
            j = 0;
            while (j < clIfaceRegN[r])
            {
                n = addIfaceUnique(n, clIfaceReg[r * MAX_DIRECT_IF + j]);
                j += 1;
            }
            i += 1;
        }
        return n;
    }

    /** A concrete (default) impl of interface-method global-index {@code g} declared in one of the {@code n}
     *  interfaces in {@link #ifClosureBuf}, or 0. Unlike {@link #defaultImplOf} (which only checks the interface
     *  the method was FIRST registered in), this finds a default an implementing SUB-interface provides for a
     *  method declared abstract in a super-interface -- e.g. {@code Spliterator.OfInt}'s
     *  {@code tryAdvance(Consumer)} for the abstract {@code Spliterator.tryAdvance(Consumer)}. */
    private static long defaultBySig(int n, long base, int nameOff, int descOff)
    {
        int i = 0;
        while (i < n)
        {
            long ibase = clTab[ifClosureBuf[i]].base;       // a closure interface's blob; its own methods registered under it
            int k = 0;
            while (k < rgCount)
            {
                if (rgTab[k].base == ibase && rgTab[k].buf != 0L
                        && utf8EqAt(base, nameOff, rgTab[k].base, rgTab[k].nameOff)
                        && utf8EqAt(base, descOff, rgTab[k].base, rgTab[k].descOff))
                {
                    return rgTab[k].buf;
                }
                k += 1;
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * itable directory (ObjectModel): one {@code {interfaceType, itable}} entry per
     * implemented interface — TRANSITIVELY, so a super-interface reached only through
     * another interface (or the superclass) is keyed too. Every entry shares this class's
     * flat imap as its itable — the metal indexes the imap by the interface method's
     * <em>global</em> slot ({@link #ifSlotOf}), so one table serves all interfaces. The
     * directory adds the interfaceType-keyed lookup the core's {@code invokeinterface}
     * searches for; without the transitive entries, a call site typed to a super-interface
     * (e.g. {@code Iterable.iterator} on an {@code ArrayList implements List extends Iterable})
     * would miss and the search would run past the sentinel.
     */
    private static long buildItableDir()
    {
        int n = ifaceClosure();
        if (n == 0)
        {
            return 0L;
        }
        long dir = Heap.allocData((n + 1) * 16);             // {interfaceType@0, itable@8} + sentinel
        int k = 0;
        while (k < n)
        {
            Magic.store64(dir + k * 16 + 0, clTab[ifClosureBuf[k]].type);   // interfaceType
            Magic.store64(dir + k * 16 + 8, buildItableFor(ifClosureBuf[k], n));   // PER-interface itable
            k += 1;
        }
        Magic.store64(dir + k * 16 + 0, 0L);             // sentinel: interfaceType 0 ends the directory
        Magic.store64(dir + k * 16 + 8, 0L);
        return dir;
    }

    /**
     * The transitive set of interfaces the current class implements, as registry indices in
     * {@link #ifClosureBuf} (return = count). Seeds with this class's directly-declared interfaces plus the
     * superclass's, then repeatedly folds in each collected interface's own extended interfaces
     * ({@link #clIfaceReg}) until closed. De-duped.
     */
    private static int ifaceClosure()
    {
        int n = 0;
        int k = 0;
        while (k < gImplIfCount)                         // this class's directly-declared interfaces
        {
            int r = classRegByName(gImplIfName[k]);
            if (r >= 0)
            {
                n = addIfaceUnique(n, r);
            }
            k += 1;
        }
        int sr = classRegByName(gSuperNameOff);          // interfaces inherited from the ENTIRE superclass chain --
        int guard = 0;                                   // an interface implemented several levels up (e.g. IntPipeline$1
        while (sr >= 0 && guard < 64)                    // -> StatelessOp -> ReferencePipeline implements Stream) must
        {                                                // still be in this class's dir, else invokeinterface misses it
            int j = 0;                                   // (walking only the immediate super dropped Stream 2 levels up).
            while (j < clIfaceRegN[sr])
            {
                n = addIfaceUnique(n, clIfaceReg[sr * MAX_DIRECT_IF + j]);
                j += 1;
            }
            sr = clTab[sr].superReg;
            guard += 1;
        }
        int i = 0;
        while (i < n)                                    // fold in each collected interface's extended interfaces
        {
            int r = ifClosureBuf[i];
            int j = 0;
            while (j < clIfaceRegN[r])
            {
                n = addIfaceUnique(n, clIfaceReg[r * MAX_DIRECT_IF + j]);
                j += 1;
            }
            i += 1;
        }
        return n;
    }

    /** Append registry index {@code r} to {@link #ifClosureBuf} if absent; return the new count. */
    private static int addIfaceUnique(int n, int r)
    {
        int i = 0;
        while (i < n)
        {
            if (ifClosureBuf[i] == r)
            {
                return n;
            }
            i += 1;
        }
        if (n < ifClosureBuf.length)
        {
            ifClosureBuf[n] = r;
            n += 1;
        }
        return n;
    }

    // ----- reflective method enumeration ----------------------------------------------------------------
    // getDeclaredMethods over the METHOD REGISTRY rather than the classfile: the registry is exactly the set
    // the VM knows about (every method of a registered class is there, compiled or as a deferral stub), and it
    // is already keyed by class+name+descriptor. Reflection is not a hot path, so a linear scan is fine.

    /**
     * The {@code want}-th method DECLARED by the class mirrored by {@code mirror} -- or, for {@code want < 0},
     * how many there are. Walks the CLASSFILE method table, not the method registry.
     *
     * <p>The registry was the obvious source and is the wrong one: it holds only what RTA marked reachable,
     * and a test method is called by nobody, so every {@code @Test} is pruned from it. Enumerating the
     * classfile sees what the class actually declares; the caller then resolves each by name through the
     * ordinary reflection path, which compiles it on demand.
     *
     * <p>{@code <init>}/{@code <clinit>} are excluded, as {@code getDeclaredMethods} specifies.
     */
    static long declaredMethodName(long mirror, int want)
    {
        if (mirror <= 0x1000L)
        {
            return 0L;
        }
        long type = Magic.load64(mirror + 16L);
        int ci = classRegByType(type);
        if (ci < 0)
        {
            return want < 0 ? 0L : 0L;
        }
        long base = clTab[ci].base;
        parseForMethods(base, blobLenOf(base));
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int seen = 0;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            int nameOff = gcp[u2(p + 2)];
            if (!utf8IsAtBase(base, nameOff, Magic.bytes("<init>"))
                    && !utf8IsAtBase(base, nameOff, Magic.bytes("<clinit>")))
            {
                if (want >= 0 && seen == want)
                {
                    return utf8ToString(base, nameOff);
                }
                seen += 1;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return want < 0 ? (long) seen : 0L;
    }

    /** The Utf8 at {@code base+off} as a fresh guest String (no '/'->'.' rewrite; this is a plain name). */
    private static long utf8ToString(long base, int off)
    {
        int len = u2(base + off);
        long arr = Heap.allocArray(len, 1);
        int k = 0;
        while (k < len)
        {
            Magic.store8(arr + 24L + k, (byte) u1(base + off + 2 + k));
            k += 1;
        }
        long obj = Heap.alloc(stringSize());
        Magic.store64(obj + 0L, stringTib());
        Magic.store64(obj + 16L, arr);
        return obj;
    }

    // ----- runtime annotations ------------------------------------------------------------------------
    // Marker-level: "does this method carry @Foo". Enough for discovery (@Test/@BeforeEach/@Disabled); element
    // VALUES need annotation instances, which need Proxy or synthesized classes, and are a separate piece.
    //
    // Only RuntimeVISIBLEAnnotations count. javac emits RuntimeINVISIBLEAnnotations unless the annotation type
    // itself is declared @Retention(RUNTIME) -- so an annotation without that is not merely unreadable here, it
    // is absent from the classfile entirely.

    /** True if the method registered at {@code rgIndex} carries the annotation whose descriptor is the
     *  {@code descLen} bytes at the {@code byte[]} payload {@code descArr} (e.g. "Lorg/junit/jupiter/api/Test;"). */
    static boolean methodAnnoPresent(int rgIndex, long descArr, int descLen)
    {
        if (rgIndex < 0 || rgIndex >= rgCount || rgTab[rgIndex] == null)
        {
            return false;
        }
        long base = rgTab[rgIndex].base;
        int nameOff = rgTab[rgIndex].nameOff;
        int descOff = rgTab[rgIndex].descOff;
        parseForMethods(base, blobLenOf(base));
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (utf8EqAt(base, gcp[u2(p + 2)], base, nameOff)
                    && utf8EqAt(base, gcp[u2(p + 4)], base, descOff))
            {
                return annoInAttrs(base, p + 8, attrs, descArr, descLen);
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return false;
    }

    /** Scan an attribute list for RuntimeVisibleAnnotations and look for the wanted descriptor in it. */
    private static boolean annoInAttrs(long base, long p, int attrs, long descArr, int descLen)
    {
        int a = 0;
        while (a < attrs)
        {
            int anIdx = u2(p);
            p += 2;
            int alen = u4(p);
            p += 4;
            if (utf8IsAtBase(base, gcp[anIdx], Magic.bytes("RuntimeVisibleAnnotations")))
            {
                return annoListHas(base, p, descArr, descLen);
            }
            p += alen;
            a += 1;
        }
        return false;
    }

    /** {@code { u2 num_annotations; annotation[] }} -- true if any annotation's type descriptor matches. */
    private static boolean annoListHas(long base, long p, long descArr, int descLen)
    {
        int n = u2(p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            int typeIdx = u2(p);
            p += 2;
            if (utf8EqArr(base, gcp[typeIdx], descArr, descLen))
            {
                return true;
            }
            p = skipElementPairs(p);                     // must skip precisely: a later annotation may be the one
            i += 1;
        }
        return false;
    }

    /** {@code { u2 num_pairs; { u2 name_index; element_value }[] }} -> the position after it. */
    private static long skipElementPairs(long p)
    {
        int n = u2(p);
        p += 2;
        int i = 0;
        while (i < n)
        {
            p = skipElementValue(p + 2);                 // past element_name_index
            i += 1;
        }
        return p;
    }

    /** One {@code element_value} (JVMS 4.7.16.1) -> the position after it. Recursive for '@' and '['. */
    private static long skipElementValue(long p)
    {
        int tag = u1(p);
        p += 1;
        if (tag == 0x65)                                 // 'e' enum: type_name_index + const_name_index
        {
            return p + 4;
        }
        if (tag == 0x40)                                 // '@' nested annotation: type_index, then its pairs
        {
            return skipElementPairs(p + 2);
        }
        if (tag == 0x5B)                                 // '[' array of element_value
        {
            int n = u2(p);
            p += 2;
            int i = 0;
            while (i < n)
            {
                p = skipElementValue(p);
                i += 1;
            }
            return p;
        }
        return p + 2;                                    // B C D F I J S Z s c: one u2 constant-pool index
    }

    /** True if the Utf8 at {@code base+off} equals the {@code n} bytes of the byte[] payload at {@code arr}. */
    private static boolean utf8EqArr(long base, int off, long arr, int n)
    {
        if (u2(base + off) != n)
        {
            return false;
        }
        int k = 0;
        while (k < n)
        {
            if (u1(base + off + 2 + k) != (Magic.load8(arr + 24L + k) & 0xFF))
            {
                return false;
            }
            k += 1;
        }
        return true;
    }

    /** Compiled buffer for flattened slot {@code s}: inherited (pre-resolved) or this class's own. */
    private static long slotBuf(int s)
    {
        if (gvTab[s].implBuf != 0L)
        {
            return gvTab[s].implBuf;                         // inherited from a superclass
        }
        if (gvTab[s].implCode == 0L)
        {
            // No Code attribute: a NATIVE instance method. Its slot would stay 0 and a call through it would
            // hit the null-vtable guard (an AIOOBE with no hint of what was missing) -- which is what
            // `new Attributes$Name(...)` -> `String.intern()` did. Provided natives already have VM helpers;
            // link one here so a VIRTUAL call reaches it, as invokestatic/invokespecial already do.
            return nativeBufAt(gbase, gThisNameOff, gvTab[s].base, gvTab[s].name);
        }
        return bufOf(gvTab[s].implCode);                     // this class's own method
    }

    /**
     * A deferral stub for a vtable slot whose method RTA pruned. {@link #emitDeferredStub} cannot serve here:
     * it works off the per-method compile arrays ({@code mCode}/{@code mLen}/...), which only exist for
     * methods that were pulled into the batch. Everything needed is in the vtable entry instead — the Code
     * attribute address, and {@code maxLocals}/{@code codeLen} read back from the two header fields that
     * precede the bytecode ({@code {u2 maxStack}{u2 maxLocals}{u4 codeLen}}). Non-static by construction: a
     * vtable slot only ever holds an instance method. 0 if the lazy table is full, leaving the old behaviour.
     */
    private static long mintPrunedStub(int s)
    {
        long code = gvTab[s].implCode;
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
        int pd = findPdByName(gbase, gThisNameOff);
        if (pd < 0 || code - 6L < gbase || code >= gbase + pdLen[pd])
        {
            return 0L;
        }
        lazyEnsureTables();
        if (lzN >= MAXLAZY)
        {
            return 0L;
        }
        if (lazyTrampAddr == 0L)
        {
            buildLazyTramp();
        }
        int idx = lzN;
        lzTab[idx] = new LazyMethod();
        lzTab[idx].blob = gbase;
        lzTab[idx].len = pdLen[pd];
        lzTab[idx].reg = classRegByName(gThisNameOff);
        lzTab[idx].nameOff = 0;
        lzTab[idx].descOff = gvTab[s].desc;
        lzTab[idx].slot = 0L;                               // shared stub buffer: no single slot to patch
        lzTab[idx].code = code;
        lzTab[idx].codeLen = u4(code - 4L);                 // Code attr: {u2 maxStack}{u2 maxLocals}{u4 len}
        lzTab[idx].isStatic = 0;
        lzTab[idx].maxLocals = u2(code - 6L);
        lzTab[idx].cache = 0L;
        lzN += 1;
        long buf = Heap.allocCode(32);
        noteStub(buf, idx);
        Heap.pinCodeAt(buf);
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.movz(17, idx & 0xFFFF, 0));                           w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) (lazyTrampAddr & 0xFFFF), 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 16) & 0xFFFF), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) ((lazyTrampAddr >> 32) & 0xFFFF), 2)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.br(16));                                              w += 1;
        Heap.publishCode(buf, buf + w * 4L);
        gvTab[s].implBuf = buf;                         // a class implementing N interfaces that each declare
        return buf;                                     //   this method would otherwise mint N identical stubs
    }

    /**
     * Vtable slot of the virtual method named by Methodref {@code idx}. Same-class
     * calls use this class's own vtable; a call whose ref names another class (a
     * cross-class {@code invokevirtual}) or an interface resolves via the global
     * vtable registry against the receiver class's layout.
     */
    static int vtableSlotOf(int idx)
    {
        // VarHandle ops are signature-polymorphic: the call-site descriptor is the actual arg types
        // (e.g. getAndBitwiseOr:(Ljava/net/Socket;I)I), NOT the overlay method's (Ljava/lang/Object;I)I, so
        // the normal name+descriptor match misses. VarHandle's op names are unique, so resolve by name only
        // against the VarHandle overlay's vtable.
        if (utf8IsStr(refClassNameOff(idx), Magic.bytes("java/lang/invoke/VarHandle")))
        {
            int vs = varHandleSlotByName(mrefNameOff(idx));
            if (vs >= 0)
            {
                return vs;
            }
        }
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            int s = findVtSlot(mrefNameOff(idx), mrefDescOff(idx));   // this class's flattened vtable
            if (s >= 0)
            {
                logVtableSlot(refClassNameOff(idx), mrefNameOff(idx), mrefDescOff(idx), s, 0x53);   // 'S' same-class
                return s;
            }
        }
        return globalVtableSlot(idx);
    }





    /** Instance-field byte offset for the field named by {@code *ref} index. */
    static int fieldOffsetOf(int idx)
    {
        if (utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            int nameOff = mrefNameOff(idx);                // Fieldref layout == Methodref layout
            int s = 0;
            while (s < gifCount)
            {
                if (gifName[s] == nameOff)
                {
                    return 16 + s * 8;                     // this class's own field (ObjectModel: +16)
                }
                s += 1;
            }
        }
        return globalFieldOffset(idx);                     // another class, or an inherited field
    }

    /** Resolve Methodref {@code idx} to its (same-class) method's bytecode; set {@code gcodeLen}. */
    private static long calleeCodeOf(int idx)
    {
        if (!utf8Eq(refClassNameOff(idx), gThisNameOff))
        {
            return 0L;                                  // not this class (Object.<init>, JDK, ...)
        }
        int nameOff = mrefNameOff(idx);
        int descOff = mrefDescOff(idx);
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);                      // access, name, descriptor, attrs
            if (utf8Eq(gcp[u2(p + 2)], nameOff) && utf8Eq(gcp[u2(p + 4)], descOff))
            {
                long c = findCode(gbase, p + 8, attrs);
                if (c != 0L)
                {
                    return c;
                }
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
        return 0L;
    }

    /** Name Utf8 offset of Methodref {@code idx}. */
    private static int mrefNameOff(int idx)
    {
        return ClassReader.refNameOff(gbytes, gcp, idx);
    }

    /** Class-name Utf8 offset of a {@code *ref} constant (Fieldref/Methodref layout). */
    private static int refClassNameOff(int idx)
    {
        return ClassReader.refClassNameOff(gbytes, gcp, idx);
    }

    /** Descriptor Utf8 offset of Methodref {@code idx}. */
    private static int mrefDescOff(int idx)
    {
        return ClassReader.refDescOff(gbytes, gcp, idx);
    }

    // ----- invokedynamic: BootstrapMethods + StringConcatFactory recognition (M-B slice 1) -----

    /** True if the Utf8 at {@code off} in the current class equals the literal {@code want} (raw bytes). */
    private static boolean utf8IsStr(int off, byte[] want)
    {
        return utf8IsAtBase(gbase, off, want);
    }

    /** Locate the class-level {@code BootstrapMethods} attribute (after the methods table); set gBsmOff (0 if none). */
    private static void findBootstrapMethods()
    {
        gBsmOff = 0L;
        long p = gMethodsStart;                         // methods_count
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)                              // skip every method + its attributes
        {
            int macount = u2(p + 6);                    // access(2) name(2) desc(2) attrs_count(2)
            p = skipAttributes(p + 8, macount);
            m += 1;
        }
        int cacount = u2(p);                            // class attributes_count
        p += 2;
        byte[] want = Magic.bytes("BootstrapMethods");
        int a = 0;
        while (a < cacount)
        {
            int nameIdx = u2(p);
            int alen = u4(p + 2);
            if (utf8IsStr(gcp[nameIdx], want))
            {
                gBsmOff = p + 6;                        // attribute body: num_bootstrap_methods
                return;
            }
            p += 6 + alen;
            a += 1;
        }
    }

    /** Address of {@code bootstrap_methods[k]} (its {@code bootstrap_method_ref}) within the attribute. */
    private static long bsmEntryOff(int k)
    {
        long p = gBsmOff + 2;                           // skip num_bootstrap_methods
        int j = 0;
        while (j < k)
        {
            int nargs = u2(p + 2);
            p += 4 + nargs * 2;                         // bsm_ref(2) num_args(2) args(num_args*2)
            j += 1;
        }
        return p;
    }

    /** True if the invokedynamic at cp {@code idx} bootstraps via {@code StringConcatFactory.makeConcatWithConstants}. */
    static boolean isStringConcat(int idx)
    {
        if (gBsmOff == 0L)
        {
            return false;
        }
        int bmIdx = u2(gbase + gcp[idx]);               // invokedynamic.bootstrap_method_attr_index
        long e = bsmEntryOff(bmIdx);
        int mhIdx = u2(e);                              // bootstrap_method_ref -> CONSTANT_MethodHandle
        int mrefIdx = u2(gbase + gcp[mhIdx] + 1);       // MethodHandle{ kind(u1), reference_index(u2) }
        return utf8IsStr(refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/StringConcatFactory"))
            && utf8IsStr(mrefNameOff(mrefIdx), Magic.bytes("makeConcatWithConstants"));
    }

    /** Utf8 body offset of the concat recipe (bootstrap_arguments[0], a String) for indy {@code idx}. */
    static int concatRecipeOff(int idx)
    {
        int bmIdx = u2(gbase + gcp[idx]);
        long e = bsmEntryOff(bmIdx);
        int arg0Idx = u2(e + 4);                        // bootstrap_arguments[0] = String cp index (the recipe)
        return ClassReader.stringUtf8Off(gbytes, gcp, arg0Idx);
    }

    /** True if the Utf8 at {@code off} in blob {@code base} equals the literal {@code want}. */
    private static boolean utf8IsAtBase(long base, int off, byte[] want)
    {
        if (u2(base + off) != want.length)
        {
            return false;
        }
        int j = 0;
        while (j < want.length)
        {
            if (u1(base + off + 2 + j) != (want[j] & 0xFF))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Loaded-class-registry index of the class named {@code want}, or -1 if it isn't loaded. */
    private static int classIndexByName(byte[] want)
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, want))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Loaded-class-registry index of {@code java/lang/String}, or -1 if it isn't loaded. */
    private static int stringClassIndex()
    {
        return classIndexByName(Magic.bytes("java/lang/String"));
    }

    /** Allocate a mini {@code java/lang/NullPointerException} (TIB set, field-free) — the JIT's null-check helper. */
    static long newNpe()
    {
        return newExc(Magic.bytes("java/lang/NullPointerException"));
    }

    /** Allocate a mini {@code java/lang/ArrayIndexOutOfBoundsException} — the JIT's bounds-check helper. */
    static long newAioobe()
    {
        return newExc(Magic.bytes("java/lang/ArrayIndexOutOfBoundsException"));
    }

    /** Allocate a mini {@code java/lang/ArithmeticException} — the JIT's divide-by-zero helper. */
    static long newArith()
    {
        return newExc(Magic.bytes("java/lang/ArithmeticException"));
    }

    /** Allocate a mini {@code java/lang/ArrayStoreException} — the JIT's aastore type-mismatch helper. */
    static long newArrayStoreException()
    {
        return newExc(Magic.bytes("java/lang/ArrayStoreException"));
    }

    /** Allocate a mini {@code java/lang/ClassCastException} — the JIT's failed-checkcast helper. */
    static long newCce()
    {
        return newExc(Magic.bytes("java/lang/ClassCastException"));
    }

    /** Allocate a mini {@code java/lang/InternalError} — the fault handler's catch-all for an unexpected trap. */
    static long newInternalError()
    {
        return newExc(Magic.bytes("java/lang/InternalError"));
    }

    /**
     * Allocate a loaded exception class by name: header + fields, with its TIB stored so {@code catch}
     * dispatch can walk its Type chain. No constructor runs — the mini exceptions are field-free, and the
     * JIT synthesises these (there is no {@code new} bytecode to invoke {@code <init>}).
     */
    private static long newExc(byte[] name)
    {
        int i = classIndexByName(name);
        long tib = i >= 0 ? clTab[i].tib : 0L;
        long obj = Heap.alloc(i >= 0 ? (16 + clTab[i].fieldCount * 8) : 16);
        Magic.store64(obj + 0L, tib);
        return obj;
    }

    /** TIB of the loaded mini {@code java/lang/String} (for the concat's {@code newStringFromBytes}), or 0. */
    static long stringTib()
    {
        int i = stringClassIndex();
        return i >= 0 ? clTab[i].tib : 0L;
    }

    /** Instance size (bytes) of the loaded mini {@code java/lang/String} (header + fields). */
    static int stringSize()
    {
        int i = stringClassIndex();
        return i >= 0 ? (16 + clTab[i].fieldCount * 8) : 24;
    }

    // ----- invokedynamic: lambda synthesis (LambdaMetafactory), M-B slice 1c -----
    // A lambda `(caps...) -> body(caps..., samArgs...)` is synthesised as a tiny class: a heap object whose
    // fields hold the captures, a Type whose itable maps the functional-interface method to a THUNK that
    // loads the captures into arg registers and tail-calls the lambda-body method. So `iface.sam()` on the
    // lambda dispatches (via the normal itable path) into the body with the captured values. Slice 1c
    // supports a zero-arg SAM (Runnable-like); the body may capture any number of values.

    private static int indyBsmIndex(int idx)
    {
        return u2(gbase + gcp[idx]);                    // invokedynamic.bootstrap_method_attr_index
    }

    /** True if the invokedynamic at {@code idx} bootstraps via {@code LambdaMetafactory.metafactory}. */
    static boolean isLambdaIndy(int idx)
    {
        if (gBsmOff == 0L)
        {
            return false;
        }
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e);                              // bootstrap_method_ref -> MethodHandle
        int mrefIdx = u2(gbase + gcp[mhIdx] + 1);       // MethodHandle.reference_index -> Methodref
        return utf8IsStr(refClassNameOff(mrefIdx), Magic.bytes("java/lang/invoke/LambdaMetafactory"))
            && utf8IsStr(mrefNameOff(mrefIdx), Magic.bytes("metafactory"));
    }

    /**
     * True if the invokedynamic at {@code idx} bootstraps via {@code java/lang/runtime/ObjectMethods.bootstrap}
     * — a record's synthesised {@code equals}/{@code hashCode}/{@code toString}. We don't synthesise record
     * semantics yet; RTA pulls these in for any instantiated record (e.g. {@code Collectors$CollectorImpl}),
     * but they are essentially never actually invoked in the paths we run (a Collector is never compared,
     * hashed or printed during {@code collect()}). The JIT lowers such an indy to a runtime trap so the record
     * still compiles; see {@code Baseline.lowerRecordTrap}.
     */
    static boolean isRecordIndy(int idx)
    {
        if (gBsmOff == 0L)
        {
            return false;
        }
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e);
        int mrefIdx = u2(gbase + gcp[mhIdx] + 1);
        return utf8IsStr(refClassNameOff(mrefIdx), Magic.bytes("java/lang/runtime/ObjectMethods"));
    }

    /** JIT buffer of the lambda body (bootstrap_arguments[1] MethodHandle -> its Methodref, same class). */
    private static long lambdaImplBuf(int idx)
    {
        return resolveCallBuf(lambdaImplMref(idx));
    }

    /** Methodref (cp index) the impl MethodHandle (bootstrap_arguments[1]) points at. */
    private static int lambdaImplMref(int idx)
    {
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e + 6);                          // bootstrap_arguments[1] = the impl MethodHandle
        return u2(gbase + gcp[mhIdx] + 1);              // MethodHandle.reference_index -> Methodref
    }

    /** reference_kind of the impl MethodHandle: 6 = invokeStatic (lambda body / static ref), 5/9 = invokeVirtual/
     *  invokeInterface (an UNBOUND instance method ref, whose receiver is the SAM's first arg). */
    private static int lambdaImplKind(int idx)
    {
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mhIdx = u2(e + 6);
        return u1(gbase + gcp[mhIdx]);                  // MethodHandle.reference_kind
    }

    /** Type of the functional interface = the indy descriptor's return class, or 0 if not loaded. */
    private static long lambdaIfaceType(int idx)
    {
        int descOff = mrefDescOff(idx);
        long p = gbase + descOff + 2;                   // skip the u2 length
        while (u1(p) != ')')
        {
            p += 1;
        }
        p += 1;                                         // past ')'
        if (u1(p) != 'L')
        {
            return 0L;
        }
        long nameStart = p + 1;
        long q = nameStart;
        while (u1(q) != ';')
        {
            q += 1;
        }
        int nameLen = (int) (q - nameStart);
        int i = 0;
        while (i < clCount)
        {
            if (rawNameEq(clTab[i].base, clTab[i].nameOff, nameStart, nameLen))
            {
                return clTab[i].type;
            }
            i += 1;
        }
        return 0L;
    }

    /** The SAM's descriptor Utf8 offset: bootstrap_arguments[0] MethodType (its name is the indy's name). */
    private static int lambdaSamDescOff(int idx)
    {
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mtIdx = u2(e + 4);                          // bootstrap_arguments[0] = MethodType (SAM signature)
        return gcp[u2(gbase + gcp[mtIdx])];             // MethodType.descriptor_index -> Utf8 offset
    }

    /** SAM parameter count (bootstrap_arguments[0] MethodType) — slice 1c supports only 0. */
    static int lambdaSamArgc(int idx)
    {
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mtIdx = u2(e + 4);
        int descOff = gcp[u2(gbase + gcp[mtIdx])];
        return ClassReader.descParamCount(gbytes, descOff);
    }

    /** Instance size of a lambda object: header + one field per captured value. */
    static int lambdaSize(int idx)
    {
        return 16 + ClassReader.descParamCount(gbytes, mrefDescOff(idx)) * 8;
    }

    /** Build the synthetic lambda class (thunk + imap + itable dir + Type + TIB); returns the TIB address. */
    static long buildLambdaTib(int idx)
    {
        long ifaceType = lambdaIfaceType(idx);
        int nc = ClassReader.descParamCount(gbytes, mrefDescOff(idx));   // number of captured values
        int ia = lambdaSamArgc(idx);                                    // SAM (interface method) args
        int kind = lambdaImplKind(idx);
        long thunk = Heap.allocCode(128);
        int w = 0;
        if (kind == 5 || kind == 9)
        {
            // Instance method ref -> vtable-dispatch the referent on its receiver (so overrides resolve).
            if (nc == 0)
            {
                // UNBOUND (String::compareTo): the SAM's first arg is the receiver, the rest are the method
                // args. Shift the SAM args down to x0..x(ia-1) so x0 = receiver, x1.. = method args.
                int j = 0;
                while (j < ia)
                {
                    Magic.store32(thunk + w * 4L, A64Enc.movReg(j, 1 + j));    // x(j) = samArg[j] (x0 = receiver)
                    w += 1;
                    j += 1;
                }
            }
            else
            {
                // BOUND (obj::method): the receiver is the captured field[0]; the SAM args are ALREADY in
                // x1..x(ia) where the instance method wants them. Load the receiver into x0.
                Magic.store32(thunk + w * 4L, A64Enc.ldrx(0, 0, 16));          // x0 = obj.field[0] (captured recv)
                w += 1;
            }
            int slot = globalVtableSlot(lambdaImplMref(idx));                 // vtable slot of the referenced method
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(16, 0, 0));                        w += 1;  // x16 = recv.tib (TIB@0)
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(16, 16, 8 + slot * 8));            w += 1;  // x16 = vtable[slot]
            Magic.store32(thunk + w * 4L, A64Enc.br(16));                                w += 1;  // tail-call
            Heap.publishCode(thunk, thunk + w * 4L);
            return finishLambdaClass(thunk, ifaceType, idx, nc);
        }
        if (kind == 8)
        {
            // CONSTRUCTOR reference (Num::new): alloc the object, set its TIB, run <init>(obj, samArgs), return
            // the object. Unlike the other thunks this makes two CALLS (Heap.alloc, <init>), so it needs a frame
            // to preserve LR and the SAM args across them. (No captures: the ctor args are all SAM args.)
            int cr = classRegByName(refClassNameOff(lambdaImplMref(idx)));    // the class being constructed
            int size = 16 + clTab[cr].fieldCount * 8;
            long ctib = clTab[cr].tib;
            long initBuf = lambdaImplBuf(idx);                               // its <init> buffer (cross-class ok)
            int frame = ((2 + ia + 1) & ~1) * 8;                            // LR + obj + ia args, 16-byte aligned
            Magic.store32(thunk + w * 4L, A64Enc.subImm(31, 31, frame));                 w += 1;  // sub sp, #frame
            Magic.store32(thunk + w * 4L, A64Enc.strx(30, 31, 0));                       w += 1;  // str x30,[sp] (LR)
            int k = 0;
            while (k < ia)
            {
                Magic.store32(thunk + w * 4L, A64Enc.strx(1 + k, 31, 16 + k * 8));       w += 1;  // save ctor arg k
                k += 1;
            }
            Magic.store32(thunk + w * 4L, A64Enc.movz(0, size, 0));                      w += 1;  // x0 = instance size
            long h1 = thunk + w * 4L;
            Magic.store32(h1, A64Enc.bl((int) ((VM.heapAlloc - h1) / 4L)));              w += 1;  // x0 = Heap.alloc(size)
            Magic.store32(thunk + w * 4L, A64Enc.movz(1, (int) ctib, 0));                w += 1;
            Magic.store32(thunk + w * 4L, A64Enc.movk(1, (int) (ctib >> 16), 1));        w += 1;  // x1 = the class TIB
            Magic.store32(thunk + w * 4L, A64Enc.strx(1, 0, 0));                         w += 1;  // obj.tib = TIB
            Magic.store32(thunk + w * 4L, A64Enc.strx(0, 31, 8));                        w += 1;  // save obj at [sp+8]
            k = 0;
            while (k < ia)
            {
                Magic.store32(thunk + w * 4L, A64Enc.ldrx(1 + k, 31, 16 + k * 8));       w += 1;  // restore ctor arg k
                k += 1;
            }
            long h2 = thunk + w * 4L;
            if (initBuf != 0L)
            {
                // A CODE->CODE edge, pinned for the same reason patchRelocs pins its targets: after this store
                // the displacement inside the thunk is the ONLY record of initBuf, and nothing scans encodings.
                Heap.pinCodeAt(initBuf);
                CodeEdges.note(h2, initBuf);
                Magic.store32(h2, A64Enc.bl((int) ((initBuf - h2) / 4L)));                        // <init>(obj, args)
            }
            else
            {
                // The constructed class's <init> isn't compiled yet (its body is a later PHASE-B batch): emit
                // `bl 0` and record a call site so patchRelocs rewrites it to the real <init> — same late-binding
                // the normal lambda-body path (recordTailReloc) uses. Without this the `bl` with initBuf==0
                // overflows imm26 and wild-branches (a constructor method-ref, Box::new, would jump to junk).
                Magic.store32(h2, A64Enc.bl(0));
                recordCallReloc(h2, lambdaImplMref(idx));
            }
            w += 1;
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(0, 31, 8));                        w += 1;  // x0 = obj (return)
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(30, 31, 0));                       w += 1;  // restore LR
            Magic.store32(thunk + w * 4L, A64Enc.addImm(31, 31, frame));                 w += 1;  // add sp, #frame
            Magic.store32(thunk + w * 4L, A64Enc.ret());                                 w += 1;
            Heap.publishCode(thunk, thunk + w * 4L);
            return finishLambdaClass(thunk, ifaceType, idx, nc);
        }
        long implBuf = lambdaImplBuf(idx);
        // The body is lambda$xxx(captures..., samArgs...). On entry x0 = lambda obj, x1..x(ia) = SAM args.
        // Build the thunk: move the SAM args to x(nc)..x(nc+ia-1), load the captures into x0..x(nc-1),
        // then tail-call the body. Shift direction avoids clobbering: dest-source = nc-1, so shift UP
        // (high->low) when nc>=1, DOWN (low->high) when nc==0.
        if (nc >= 1)
        {
            int j = ia - 1;
            while (j >= 0)
            {
                Magic.store32(thunk + w * 4L, A64Enc.movReg(nc + j, 1 + j));   // x(nc+j) = samArg[j]
                w += 1;
                j -= 1;
            }
        }
        else
        {
            int j = 0;
            while (j < ia)
            {
                Magic.store32(thunk + w * 4L, A64Enc.movReg(j, 1 + j));        // x(j) = samArg[j]
                w += 1;
                j += 1;
            }
        }
        int c = nc - 1;
        while (c >= 0)
        {
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(c, 0, 16 + c * 8));   // xC = obj.field[c] (x0 last)
            w += 1;
            c -= 1;
        }
        long bAt = thunk + w * 4L;
        if (implBuf != 0L)
        {
            Heap.pinCodeAt(implBuf);
            CodeEdges.note(bAt, implBuf);                                        // as above: a tail branch is
            Magic.store32(bAt, A64Enc.b((int) ((implBuf - bAt) / 4L)));     //   still a code->code edge
        }
        else
        {
            // A method ref whose cross-class impl isn't registered yet: emit `b 0` and record a tail reloc so
            // patchRelocs rewrites it once every class is compiled (same late-binding as a normal bl call site).
            Magic.store32(bAt, A64Enc.b(0));
            recordTailReloc(bAt, lambdaImplMref(idx));
        }
        w += 1;
        Heap.publishCode(thunk, thunk + w * 4L);
        return finishLambdaClass(thunk, ifaceType, idx, nc);
    }

    /** Registry index of the loaded class/interface whose Type node is {@code type}, or -1. */
    private static int regOfType(long type)
    {
        int i = 0;
        while (i < clCount)
        {
            if (clTab[i].type == type)
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** Wrap a built lambda thunk into a class: imap (SAM slot -> thunk), itable dir, Type, TIB; return the TIB. */
    private static long finishLambdaClass(long thunk, long ifaceType, int idx, int nc)
    {
        // M8 itables: PER-interface itables. The SAM's identity is (indy name, MethodType desc);
        // each directory entry -- the functional interface AND every interface it transitively
        // extends (a BinaryOperator accumulator is invoked as BiFunction by Stream.reduce) --
        // gets its OWN itable with the thunk at that interface's flattened slot of the SAM.
        int samName = mrefNameOff(idx);
        int samDesc = lambdaSamDescOff(idx);
        int fnReg = regOfType(ifaceType);
        int n = fnReg >= 0 ? ifaceClosureOf(fnReg) : 0;
        long dir = Heap.allocData((n + 2) * 16);
        Magic.store64(dir + 0L, ifaceType);              // the functional interface itself
        Magic.store64(dir + 8L, lambdaItableFor(fnReg, samName, samDesc, thunk));
        int e = 1;
        int di = 0;
        while (di < n)
        {
            Magic.store64(dir + e * 16L + 0L, clTab[ifClosureBuf[di]].type);
            Magic.store64(dir + e * 16L + 8L, lambdaItableFor(ifClosureBuf[di], samName, samDesc, thunk));
            e += 1;
            di += 1;
        }
        Magic.store64(dir + e * 16L + 0L, 0L);           // sentinel: interfaceType 0 ends the directory
        Magic.store64(dir + e * 16L + 8L, 0L);
        // Type { instanceSize, superType=Object, itableDir } -- no depth/display (a lambda is
        // only ever type-checked through its interface dir, so the walk fallback covers it).
        //
        // superType MUST be Object's Type, not 0. typeAssignable walks self -> itable dir -> superType, so a
        // 0 super ends the walk at the lambda's own interfaces: `lambda instanceof Object` answered FALSE,
        // and every aastore of a lambda into an Object[] threw ArrayStoreException. That is not an obscure
        // path -- it is EVERY varargs call taking a lambda, e.g. Arguments.of((Executable) () -> ...) in the
        // stock BasicGZIPInputStreamTest, whose argument array is an Object[].
        long type = Heap.allocData(ObjectModel.TYPE_SIZE);
        Magic.store64(type + 0L, 16 + nc * 8);
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, objectTypeAddr());
        Magic.store64(type + 16L, dir);
        Magic.store64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_DEPTH_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_DISPLAY_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L, 0L);
        buildImplBitmap(type);                           // a lambda's dir interfaces ARE numbered
        // TIB { Type } (slot 0; the lambda has no vtable methods of its own).
        long tib = Heap.allocData(8);
        Magic.store64(tib + 0L, type);
        if (lambdaTibRoots != null && lambdaTibRootN < lambdaTibRoots.length)
        {
            lambdaTibRoots[lambdaTibRootN] = tib;   // keep this TIB (and, via its trace, Type/dir/itables) a GC root
            lambdaTibRootN += 1;
        }
        return tib;
    }

    /** A lambda's itable for interface {@code ir}: zeroes except the thunk at the interface's
     *  flattened slot of the SAM (name/desc offsets in the CURRENT blob). {@code ir < 0} (the
     *  functional interface isn't registered) degrades to a one-slot table holding the thunk. */
    private static long lambdaItableFor(int ir, int samName, int samDesc, long thunk)
    {
        if (ir < 0)
        {
            long one = Heap.allocData(8);
            Magic.store64(one, thunk);
            return one;
        }
        int count = clTab[ir].ifmCount;
        long it = Heap.allocData(count > 0 ? count * 8 : 8);
        int s = 0;
        while (s < count)
        {
            Magic.store64(it + s * 8L, 0L);
            s += 1;
        }
        int slot = ifmSlotIn(ir, gbase, samName, samDesc);
        Magic.store64(it + (slot >= 0 ? slot : 0) * 8L, thunk);
        return it;
    }

    /** True if the Utf8 at {@code off} in {@code base} equals the {@code len} raw bytes at {@code raw}. */
    private static boolean rawNameEq(long base, int off, long raw, int len)
    {
        if (u2(base + off) != len)
        {
            return false;
        }
        int i = 0;
        while (i < len)
        {
            if (u1(base + off + 2 + i) != u1(raw + i))
            {
                return false;
            }
            i += 1;
        }
        return true;
    }


    /** Compare two Utf8 entries in the current class by length + bytes. */
    private static boolean utf8Eq(int offA, int offB)
    {
        return utf8EqAt(gbase, offA, gbase, offB);
    }

    /** Compare a Utf8 entry in {@code baseA} against one in {@code baseB} (may be different classes). */
    private static boolean utf8EqAt(long baseA, int offA, long baseB, int offB)
    {
        if (baseA == 0L || baseB == 0L)                // a reclaimed registry entry: halt NAMED, not wild
        {
            badRead(baseA == 0L ? 0L : 1L, Magic.readLR());
        }
        if (baseA == baseB && offA == offB)
        {
            return true;
        }
        int la = u2(baseA + offA);
        if (la != u2(baseB + offB))
        {
            return false;
        }
        int j = 0;
        while (j < la)
        {
            if (u1(baseA + offA + 2 + j) != u1(baseB + offB + 2 + j))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** STR Xt, [Xn, #off] — off a multiple of 8 (rn=31 = SP, rt=31 = xzr). */
    private static int strx(int rt, int rn, int off)
    {
        return A64Enc.strx(rt, rn, off);
    }
    /** LDR Xt, [Xn, #off] — off a multiple of 8 (rn=31 = SP). */
    private static int ldrx(int rt, int rn, int off)
    {
        return A64Enc.ldrx(rt, rn, off);
    }



    /** Type node of the class named by {@code Class} entry {@code classIdx}, or 0 if unloaded. */
    static long typeOfClass(int classIdx)
    {
        // Self-reference (a class doing `x instanceof Self` / `(Self) x`): the current class isn't in the
        // registry until AFTER its own compile, but its Type node (gType) is already built by buildTib —
        // and it's the very node the class's own instances carry, so instanceof/checkcast resolve correctly.
        int nameOff = gcp[u2(gbase + gcp[classIdx])];
        if (u1(gbase + nameOff + 2) == 0x5B)            // '[' : an array class ("[B", "[Ljava/...;", "[[I")
        {
            long tib = arrayTibOfDesc(gbase, nameOff);
            return tib == 0L ? 0L : Magic.load64(tib);  // the array Type (checkcast/instanceof target)
        }
        if (utf8EqAt(gbase, nameOff, gbase, gThisNameOff))
        {
            return gType;
        }
        int r = classRegOf(classIdx);
        return r >= 0 ? clTab[r].type : 0L;
    }

    // ----- array Types -----------------------------------------------------
    // An array carries a real Type (via a 1-word array TIB in its header @0) so checkcast/instanceof against an
    // array class resolve precisely and `arr instanceof Object` walks the super chain. Primitive-array Types are
    // cached by atype; reference-array Types by element Type. All live in the per-batch demand heap (recreated
    // after each resetLoader). A raw array (VM-internal byte buffers, boot-time) keeps a small element size in
    // @0 instead — untyped, never checkcast, distinguished by magnitude (<= MAX_RAW_ARRAY_TIB).

    /** java/lang/Object's Type (array super), or 0 if Object isn't loaded yet. */
    static long objectTypeAddr()
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, Magic.bytes("java/lang/Object")))
            {
                return clTab[i].type;
            }
            i += 1;
        }
        return 0L;
    }

    /** Allocate an array Type {tag|elemSize, super=Object, itableDir=0, elementType} + a 1-word TIB; return the TIB. */
    private static long makeArrayTib(int elemSize, long elementType)
    {
        long type = Heap.allocData(ObjectModel.ARRAY_TYPE_SIZE);
        long objType = objectTypeAddr();
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET, ObjectModel.ARRAY_TYPE_TAG | (long) elemSize);
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, objType);            // arr instanceof Object
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, 0L);            // Cloneable/Serializable: later
        Magic.store64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET, elementType);
        Magic.store64(type + ObjectModel.TYPE_DEPTH_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_DISPLAY_OFFSET, 0L);
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET, 1L);   // empty-but-computed bitmap
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L, 0L);
        buildDisplay(type, objType);                     // depth 1, display [Object, self]
        // The array TIB carries java/lang/Object's vtable so an invokevirtual on an array works. An array's static
        // type is Object at a dispatch site (e.g. Arrays.deepEquals0's `e1.equals(e2)`, or `element.toString()` in
        // String.valueOf/join), so the receiver's TIB must resolve equals/hashCode/toString to Object's impls.
        // Without the vtable the dispatch read past a 1-word TIB and BLR'd garbage (a wild branch to the image entry).
        int nv = arrayVtableCount();
        long tib = Heap.allocData(8 + nv * 8);
        Magic.store64(tib + ObjectModel.TIB_TYPE_SLOT * 8, type);                // TIB[0] = Type
        fillObjectVtable(tib);                                                   // TIB[1..] = Object's vtable slots
        return tib;
    }

    /** Number of vtable slots an array TIB reserves (= java/lang/Object's flattened vtable count), or 0. */
    private static int arrayVtableCount()
    {
        int oi = objectClassIndex();
        return oi >= 0 ? clTab[oi].vtCount : 0;
    }

    /** Copy java/lang/Object's vtable slots into the array TIB {@code tib} (after its Type slot). Object's slots
     *  are only FILLED when Object's body is compiled (fillTib), which can happen AFTER an array TIB is first
     *  created (e.g. a string literal interns a byte[] before Object compiles) -- so a freshly-made array TIB may
     *  copy zeros. {@link #refillArrayTibVtables} re-runs this over every cached array TIB once Object is done. */
    private static void fillObjectVtable(long tib)
    {
        int oi = objectClassIndex();
        if (oi < 0)
        {
            return;
        }
        int nv = clTab[oi].vtCount;
        int k = 0;
        while (k < nv)
        {
            Magic.store64(tib + 8L + (long) k * 8L, Magic.load64(clTab[oi].tib + 8L + (long) k * 8L));
            k += 1;
        }
    }

    /** Re-copy Object's (now-filled) vtable into every cached array TIB. Called at the end of {@link #loadAll},
     *  after Object's body is compiled, to repair any array TIB that was created with a still-empty Object vtable
     *  (else an invokevirtual on that array reads a 0 slot and the null-vtable guard throws AIOOBE). */
    private static void refillArrayTibVtables()
    {
        if (primArrTib != null)
        {
            int a = 0;
            while (a < primArrTib.length)
            {
                if (primArrTib[a] != 0L && !primArrAdopted[a])
                {
                    fillObjectVtable(primArrTib[a]);   // adopted (baked) TIBs keep their writer impls
                }
                a += 1;
            }
        }
        int r = 0;
        while (r < refArrCount)
        {
            if (refArrTib[r] != 0L && !refArrAdopted[r])
            {
                fillObjectVtable(refArrTib[r]);        // adopted (baked) TIBs keep their writer impls
            }
            r += 1;
        }
    }

    /** Class-registry index of {@code java/lang/Object}, or -1 if it isn't loaded yet. */
    private static int objectClassIndex()
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, Magic.bytes("java/lang/Object")))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /** newarray atype (4=bool..11=long) -> element size in bytes. */
    private static int primElemSize(int atype)
    {
        return atype == 4 || atype == 8 ? 1              // boolean, byte
             : atype == 5 || atype == 9 ? 2              // char, short
             : atype == 6 || atype == 10 ? 4             // float, int
             : 8;                                        // double, long
    }

    /** The array TIB for a primitive array of the given newarray {@code atype}; adopted or created on demand. */
    static long primArrayTib(int atype)
    {
        if (atype < 0 || atype >= 12)
        {
            return 0L;
        }
        if (primArrTib[atype] == 0L)
        {
            // M8 array-Type unification: ADOPT the writer-baked canonical array TIB (VM.primArrayTibs,
            // 8 longs indexed atype-4) so byte[] is ONE class across both worlds -- writer-tagged
            // arrays and loader arrays carry the same Type node, and instanceof/checkcast targets
            // resolve to it on both sides. Metal-built only if the image predates the table.
            long tab = VM.primArrayTibs;
            long baked = tab == 0L || atype < 4 ? 0L : Magic.load64(tab + (long) (atype - 4) * 8L);
            if (baked != 0L)
            {
                primArrTib[atype] = baked;
                primArrAdopted[atype] = true;
                Uart.write(Magic.bytes("  arrayadopt "));
                Uart.putc(0x5B);                        // '['
                Uart.putc(primDescChar(atype));
                Uart.putc(0x0A);
            }
            else
            {
                primArrTib[atype] = makeArrayTib(primElemSize(atype), 0L);
            }
        }
        return primArrTib[atype];
    }

    /** O(1) type checks: give {@code type} a depth + superclass display (depth+1 Type addrs,
     *  display[d] = ancestor at depth d, display[depth] = self). Copies the super's display, so it
     *  requires the super's Type to be complete (supers register first; adopted supers carry the
     *  writer's). A super without a display leaves this one without too (the walk fallback holds). */
    private static void buildDisplay(long type, long superType)
    {
        long d = 0L;
        long sd = 0L;
        if (superType != 0L)
        {
            sd = Magic.load64(superType + ObjectModel.TYPE_DISPLAY_OFFSET);
            if (sd == 0L)
            {
                return;                                  // super has no display: stay on the walk
            }
            d = Magic.load64(superType + ObjectModel.TYPE_DEPTH_OFFSET) + 1L;
        }
        long disp = Heap.allocData(((int) d + 1) * 8);
        long i = 0L;
        while (i < d)
        {
            Magic.store64(disp + i * 8L, Magic.load64(sd + i * 8L));
            i += 1;
        }
        Magic.store64(disp + d * 8L, type);
        Magic.store64(type + ObjectModel.TYPE_DEPTH_OFFSET, d);
        Magic.store64(type + ObjectModel.TYPE_DISPLAY_OFFSET, disp);
    }

    /** O(1) interface checks: (re)build {@code type}'s doesImplement bitmap = the super's bitmap |
     *  any bits already present (an ADOPTED node keeps its writer bits) | this type's own itable-dir
     *  interfaces' ID bits. Unnumbered dir interfaces are simply skipped -- their targets fall back
     *  to the dir walk, so the bitmap stays definitive for every NUMBERED interface. A super without
     *  a computed bitmap leaves this type without one too (walk fallback). */
    private static void buildImplBitmap(long type)
    {
        long b0 = 1L;                                    // bit 0 = computed marker
        long b1 = 0L;
        long superType = Magic.load64(type + ObjectModel.TYPE_SUPER_OFFSET);
        if (superType != 0L)
        {
            long sb0 = Magic.load64(superType + ObjectModel.TYPE_IMPLEMENTS_OFFSET);
            if ((sb0 & 1L) == 0L)
            {
                return;                                  // super uncomputed: stay on the walk
            }
            b0 |= sb0;
            b1 |= Magic.load64(superType + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L);
        }
        long own0 = Magic.load64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET);
        if ((own0 & 1L) != 0L)                           // adopted node: keep the writer's bits
        {
            b0 |= own0;
            b1 |= Magic.load64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L);
        }
        long e = Magic.load64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET);
        if (e != 0L)
        {
            long iface = Magic.load64(e + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
            while (iface != 0L)
            {
                long id = Magic.load64(iface + ObjectModel.TYPE_IMPLEMENTS_OFFSET);
                if (id > 0L && id < 64L)
                {
                    b0 |= 1L << (int) id;
                }
                else if (id >= 64L && id < 128L)
                {
                    b1 |= 1L << (int) (id - 64L);
                }
                e += ObjectModel.ITABLE_ENTRY_SIZE;
                iface = Magic.load64(e + ObjectModel.ITABLE_ENTRY_IFACE_OFFSET);
            }
        }
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET, b0);
        Magic.store64(type + ObjectModel.TYPE_IMPLEMENTS_OFFSET + 8L, b1);
    }

    /** JVMS descriptor char for a newarray atype (4..11). */
    private static int primDescChar(int atype)
    {
        if (atype == 4)  { return 0x5A; }   // 'Z'
        if (atype == 5)  { return 0x43; }   // 'C'
        if (atype == 6)  { return 0x46; }   // 'F'
        if (atype == 7)  { return 0x44; }   // 'D'
        if (atype == 8)  { return 0x42; }   // 'B'
        if (atype == 9)  { return 0x53; }   // 'S'
        if (atype == 10) { return 0x49; }   // 'I'
        return 0x4A;                        // 'J'
    }

    /** Array TIB for a byte[] (newarray atype 8) — used to type String value arrays / interned literals. */
    static long byteArrayTib()
    {
        return primArrayTib(8);
    }

    /** JVMS field-descriptor char -> newarray atype (Z/B/C/S/I/J/F/D), or -1 for a reference/array element. */
    private static int atypeForDescChar(int c)
    {
        if (c == 0x5A) { return 4; }    // 'Z' boolean
        if (c == 0x43) { return 5; }    // 'C' char
        if (c == 0x46) { return 6; }    // 'F' float
        if (c == 0x44) { return 7; }    // 'D' double
        if (c == 0x42) { return 8; }    // 'B' byte
        if (c == 0x53) { return 9; }    // 'S' short
        if (c == 0x49) { return 10; }   // 'I' int
        if (c == 0x4A) { return 11; }   // 'J' long
        return -1;                      // 'L' reference or '[' nested array
    }

    /** Array TIB for the array descriptor at {@code base+nameOff} (a Utf8 like "[B" / "[Ljava/lang/String;" /
     *  "[[I"), or 0. */
    private static long arrayTibOfDesc(long base, int nameOff)
    {
        return arrayTibOfDescN(base, nameOff + 2, u2(base + nameOff));   // skip the u2 length prefix
    }

    /** Array TIB for the {@code n}-byte descriptor at {@code base+off}. Nested descriptors recurse: the
     *  element of "[[I" is "[I", whose Type keys the ref-array cache -- so int[][] shares the SAME canonical
     *  "[I" element node both worlds bake/adopt, and the covariance walk discriminates nested arrays. */
    private static long arrayTibOfDescN(long base, int off, int n)
    {
        int c = u1(base + off + 1);                     // char after the leading '['
        int atype = atypeForDescChar(c);
        if (atype >= 0)
        {
            return primArrayTib(atype);                 // "[<primitive>"
        }
        if (c == 0x5B)                                  // '[': nested -- element desc is this minus one '['
        {
            long elTib = arrayTibOfDescN(base, off + 1, n - 1);
            return refArrayTib(elTib == 0L ? 0L : Magic.load64(elTib));
        }
        return refArrayTib(elementTypeOfArrayDescN(base, off, n));   // "[L<class>;"
    }

    /** Element Type of the {@code n}-byte reference-array descriptor "[L<class>;" at {@code base+off}
     *  (the class's Type, or 0 when unresolved). */
    private static long elementTypeOfArrayDescN(long base, int off, int n)
    {
        if (u1(base + off + 1) != 0x4C)                 // not 'L'
        {
            return 0L;
        }
        int i = 0;                                      // strip the leading "[L" and trailing ';' -> class name
        while (i < clCount)
        {
            if (utf8SliceEq(base, off + 2, n - 3, clTab[i].base, clTab[i].nameOff))
            {
                return clTab[i].type;
            }
            i += 1;
        }
        return 0L;
    }

    /** True if the {@code n}-byte slice at {@code aBase+aOff} equals the Utf8 name at {@code bBase+bNameOff}. */
    private static boolean utf8SliceEq(long aBase, int aOff, int n, long bBase, int bNameOff)
    {
        if (u2(bBase + bNameOff) != n)
        {
            return false;
        }
        int j = 0;
        while (j < n)
        {
            if (u1(aBase + aOff + j) != u1(bBase + bNameOff + 2 + j))
            {
                return false;
            }
            j += 1;
        }
        return true;
    }

    /** Array TIB for a reference array with the given element Type; created + cached (keyed by element Type). */
    static long refArrayTib(long elementType)
    {
        int i = 0;
        while (i < refArrCount)
        {
            if (refArrElem[i] == elementType)
            {
                return refArrTib[i];
            }
            i += 1;
        }
        // M8 ref-array unification: ADOPT the writer-baked ref-array TIB whose element Type matches.
        // The table (VM.refArrayTibs: {elementType, tib} pairs) is keyed by the SAME adopted element
        // Type nodes both worlds share, so a writer-tagged Integer[] and a loader Integer[] carry one
        // TIB -- and the covariance walk compares one set of nodes. Metal-built when not baked.
        long tib = 0L;
        boolean adopted = false;
        if (elementType != 0L)
        {
            long tab = VM.refArrayTibs;
            long n = VM.refArrayTibCount;
            long k = 0;
            while (k < n)
            {
                if (Magic.load64(tab + k * 16L) == elementType)
                {
                    tib = Magic.load64(tab + k * 16L + 8L);
                    adopted = true;
                    int r = regOfType(elementType);
                    if (r >= 0)
                    {
                        Uart.write(Magic.bytes("  arrayadopt [L"));
                        writeName(clTab[r].base + clTab[r].nameOff + 2, u2(clTab[r].base + clTab[r].nameOff));
                        Uart.putc(0x3B);                // ';'
                    }
                    else
                    {
                        Uart.write(Magic.bytes("  arrayadopt [[ (nested)"));   // element is itself an array Type
                    }
                    Uart.putc(0x0A);
                    break;
                }
                k += 1;
            }
        }
        if (tib == 0L)
        {
            tib = makeArrayTib(ObjectModel.WORD, elementType);   // reference elements are 8-byte pointers
        }
        if (refArrCount < refArrTib.length)
        {
            refArrElem[refArrCount] = elementType;
            refArrTib[refArrCount] = tib;
            refArrAdopted[refArrCount] = adopted;
            refArrCount += 1;
        }
        return tib;
    }

    /** Array TIB for an {@code anewarray} whose element class is Class-entry {@code classCp}. */
    static long refArrayTibForClass(int classCp)
    {
        return refArrayTib(typeOfClass(classCp));
    }

    // ----- java.lang.Class mirrors -----------------------------------------
    // A Class object per VM Type, materialised once and cached, so `X.class` (ldc class-literal) and
    // `obj.getClass()` return the SAME identity for the same Type -- which is what stock code compares
    // (e.g. Arrays.copyOf: `newType == Object[].class`). The mirror carries the Type node pointer in its sole
    // field (@16); Class methods (getName/getComponentType/isInstance/...) are added on demand. It is typed as
    // the guest java/lang/Class when loaded, else a bare identity object (TIB 0) -- enough for `==`.

    /** guest java/lang/Class's TIB, or 0 if it isn't loaded in this batch. */
    private static long classTib()
    {
        if (classTibCache == 0L)
        {
            int i = 0;
            while (i < clCount)
            {
                if (utf8IsAtBase(clTab[i].base, clTab[i].nameOff, Magic.bytes("java/lang/Class")))
                {
                    classTibCache = clTab[i].tib;
                    break;
                }
                i += 1;
            }
        }
        return classTibCache;
    }

    /** The Class mirror for VM Type {@code type}, materialised + cached (identity stable across ldc/getClass). */
    static long classMirror(long type)
    {
        if (type == 0L)
        {
            return 0L;
        }
        int i = 0;
        while (i < mirN)
        {
            if (mirType[i] == type)
            {
                return mirObj[i];
            }
            i += 1;
        }
        long obj = Heap.alloc(24);                          // header(16) + Type-pointer field(@16)
        Magic.store64(obj + 0L, classTib());                // TIB (0 -> a bare identity object; fine for ==)
        Magic.store64(obj + 16L, type);                     // the VM Type this Class mirrors
        if (mirN < mirType.length)
        {
            mirType[mirN] = type;
            mirObj[mirN] = obj;
            mirN += 1;
        }
        return obj;
    }

    /** {@code ldc} of a CONSTANT_Class (a class literal {@code X.class}) at cp {@code classCp} -> its Class mirror. */
    static long classLiteral(int classCp)
    {
        return classMirror(typeOfClass(classCp));
    }

    /** The Class mirror of the JIT'd method containing machine PC {@code pc} (getCallerClass), or 0. */
    static long classMirrorAtPc(long pc)
    {
        long bestBuf = 0L;
        int bestReg = -1;
        int i = 0;
        while (i < rgCount)
        {
            if (rgTab[i].buf != 0L && rgTab[i].buf <= pc && rgTab[i].buf > bestBuf)
            {
                bestBuf = rgTab[i].buf;
                bestReg = i;
            }
            i += 1;
        }
        if (bestReg < 0)
        {
            return 0L;
        }
        int ci = 0;
        while (ci < clCount)
        {
            if (utf8EqAt(clTab[ci].base, clTab[ci].nameOff, rgTab[bestReg].base, rgTab[bestReg].classOff))
            {
                return classMirror(clTab[ci].type);
            }
            ci += 1;
        }
        return 0L;
    }

    /** {@code Object.getClass()}: object header -> TIB -> Type -> its Class mirror. */
    static long getClassOf(long obj)
    {
        if (obj == 0L)
        {
            return 0L;
        }
        long tib = Magic.load64(obj + 0L);
        if (tib <= ObjectModel.MAX_RAW_ARRAY_TIB)
        {
            return 0L;                                      // a raw array (no Type) — no mirror
        }
        return classMirror(Magic.load64(tib));              // TIB[0] = Type (class Type or array Type)
    }

    /** True if the *ref at {@code idx} is a {@code getClass()Ljava/lang/Class;} call (intrinsified to a helper). */
    static boolean isGetClass(int idx)
    {
        return isName(gbase, mrefNameOff(idx), 0x676574436C617373L, 8)   // "getClass"
                && utf8IsAtBase(gbase, mrefDescOff(idx), Magic.bytes("()Ljava/lang/Class;"));
    }

    /** {@code invokevirtual "[T".clone()} — a virtual call on an ARRAY receiver (owner Utf8 starts '[').
     *  Array TIBs carry no vtable, so this is intrinsified to {@code VM.arrayClone} instead of dispatching. */
    static boolean isArrayClone(int idx)
    {
        long p = gbase + ClassReader.refClassNameOff(gbytes, gcp, idx);
        return u2(p) >= 1 && u1(p + 2) == 0x5B                            // owner "[..." (an array class)
                && utf8IsAtBase(gbase, mrefNameOff(idx), Magic.bytes("clone"));
    }

    /** {@code Class.desiredAssertionStatus()Z} -- intrinsified to {@code false} (assertions are off on metal), so
     *  a stock {@code <clinit>}'s `$assertionsDisabled` idiom needs neither the mirror's vtable nor the method. */
    static boolean isDesiredAssertionStatus(int idx)
    {
        return utf8IsAtBase(gbase, mrefNameOff(idx), Magic.bytes("desiredAssertionStatus"))
                && utf8IsAtBase(gbase, mrefDescOff(idx), Magic.bytes("()Z"));
    }

    /**
     * Object-monitor op for an {@code invokevirtual} on {@code java/lang/Object}: 0 none, 1 {@code wait()V},
     * 2 {@code wait(J)V}, 3 {@code notify()V}, 4 {@code notifyAll()V}. Recognised by name+descriptor so the
     * compiler can lower it DIRECTLY to a VM helper (like getClass) instead of through the vtable -- wait/notify
     * are final and the mini Object's bodies are never compiled, so a vtable dispatch would hit a no-op slot.
     */
    static int monitorOp(int idx)
    {
        if (!utf8IsAtBase(gbase, refClassNameOff(idx), Magic.bytes("java/lang/Object")))
        {
            return 0;
        }
        int n = mrefNameOff(idx);
        int d = mrefDescOff(idx);
        if (utf8IsAtBase(gbase, n, Magic.bytes("wait")))
        {
            if (utf8IsAtBase(gbase, d, Magic.bytes("()V")))  { return 1; }
            if (utf8IsAtBase(gbase, d, Magic.bytes("(J)V"))) { return 2; }
            return 0;                                        // wait(JI)V etc.: unused, fall back to vtable
        }
        if (utf8IsAtBase(gbase, n, Magic.bytes("notify")) && utf8IsAtBase(gbase, d, Magic.bytes("()V")))
        {
            return 3;
        }
        if (utf8IsAtBase(gbase, n, Magic.bytes("notifyAll")) && utf8IsAtBase(gbase, d, Magic.bytes("()V")))
        {
            return 4;
        }
        return 0;
    }

    // ----- resolvers the on-metal MetalSymbols shares with emit* (M5.4.c) -----

    /** TIB of the class at {@code Class} entry {@code classIdx} (its own if not yet registered). */
    static long tibOfClass(int classIdx)
    {
        int r = classRegOf(classIdx);
        noteInitNeeded(r);                              // `new C` is an active use of C (JVMS 5.5)
        return r >= 0 ? clTab[r].tib : gTib;            // unregistered => the SELF case (objectSizeOf traps the rest)
    }

    /** True if the {@code Class} entry {@code classIdx} names the class being compiled. */
    private static boolean selfClassAt(int classIdx)
    {
        return utf8Eq(gcp[u2(gbase + gcp[classIdx])], gThisNameOff);
    }

    // Unresolved-`new` sites: a class named by a `new` that is neither registered nor the class being
    // compiled. Recorded (not halted on) because every real instance is a DENYLISTED class on a branch that
    // is never taken; the site index is baked into the trap call so the runtime can name the class if the
    // branch ever IS taken. Small by construction -- a whole jar batch produces about five.
    private static final int MAXUNRES = 256;
    private static long[] unresBase;
    private static int[] unresOff;
    private static int unresN;

    /** Record the class named by {@code classIdx} as an unresolved `new` target; its site index. */
    private static int unresolvedNewSite(int classIdx)
    {
        int off = gcp[u2(gbase + gcp[classIdx])];
        if (unresBase == null)
        {
            unresBase = new long[MAXUNRES];
            unresOff = new int[MAXUNRES];
            unresN = 0;
        }
        int i = 0;
        while (i < unresN)
        {
            if (unresBase[i] == gbase && unresOff[i] == off)
            {
                return i;
            }
            i += 1;
        }
        if (unresN >= MAXUNRES)
        {
            return 0;                                   // full: the trap still fires, it just names site 0
        }
        unresBase[unresN] = gbase;
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
     * patchable branch target — so this runs per execution. That is the right trade anyway: the sites that
     * reach here are cold by construction, and the second execution finds the class already registered, so all
     * that repeats is a registry lookup.
     *
     * <p>If the class still cannot be resolved (a genuinely denylisted subtree) this falls through to
     * {@link #reportUnresolvedNew}, so that diagnostic — and the guarantee that a wrong-typed object is never
     * returned — is exactly as it was.
     */
    static long resolveUnresolvedNew(long site, long pc)
    {
        int i = (int) site;
        if (unresBase == null || i < 0 || i >= unresN || clTab == null)
        {
            reportUnresolvedNew(site, pc);              // does not return
        }
        if (isDenylisted(unresBase[i], unresOff[i]))
        {
            // A metal-absent subtree. The call path gets this guard at patch time (patchRelocsFrom never
            // stubs a denylisted callee); a `new` site is recorded during the compile, with no such check,
            // so it has to happen here. Without it loadClassIncremental would cheerfully pull the class --
            // pullClass(byte[]) goes straight to the classDir -- which both defeats the denylist and turns
            // demo/UnresolvedNewDemo, whose whole point is that this halts, into a silent pass.
            reportUnresolvedNew(site, pc);              // does not return
        }
        long clsU = unresBase[i] + unresOff[i];
        int len = u2(clsU);
        byte[] slash = new byte[len];
        int k = 0;
        while (k < len)
        {
            slash[k] = (byte) u1(clsU + 2 + k);
            k += 1;
        }
        VM.loaderLock();                                // demand-loads a class: one compiler at a time
        if (classIndexByName(slash) < 0)
        {
            Uart.write(Magic.bytes("  newresolve "));
            printNameAt(clsU, 0);
            Uart.putc(0x0A);
            long pulled = loadClassIncremental(slash);
        }
        int reg = classIndexByName(slash);
        long tib = 0L;
        int fields = 0;
        if (reg >= 0 && clTab[reg].state >= RVMClass.ST_INSTANTIATED)
        {
            tib = clTab[reg].tib;                       // a half-lifecycle class has no filled TIB: do not use it
            fields = clTab[reg].fieldCount;
        }
        VM.loaderUnlock();
        if (tib == 0L)
        {
            reportUnresolvedNew(site, pc);              // does not return
        }
        long obj = Heap.alloc(16 + fields * 8);
        Magic.store64(obj + ObjectModel.TIB_OFFSET, tib);
        return obj;
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
            printNameAt(unresBase[i], unresOff[i]);
        }
        else
        {
            Uart.write(Magic.bytes("<site "));
            VM.printDec(i);
            Uart.write(Magic.bytes(">"));
        }
        Uart.write(Magic.bytes("\n  at "));
        printFrameAt(pc);
        Uart.putc(0x0A);
        while (true) { Magic.wfe(); }
    }

    /**
     * Scalar instance size (header + one 8-byte slot per field) of class {@code classIdx}, or
     * {@code -(site + 1)} when the class is neither registered nor the class being compiled -- see
     * {@link Symbols#objectSize}. The unregistered-but-SELF case is real (a class `new`ing itself before its
     * own registration completes) and keeps the {@code gifCount} size; anything else used to take that same
     * path and silently produce an object of the wrong type.
     */
    static int objectSizeOf(int classIdx)
    {
        int r = classRegOf(classIdx);
        if (r < 0 && !selfClassAt(classIdx))
        {
            return -(unresolvedNewSite(classIdx) + 1);
        }
        int fields = r >= 0 ? clTab[r].fieldCount : gifCount;
        return 16 + fields * 8;
    }

    /** Type node of the interface owning InterfaceMethodref {@code idx}. */
    static long ifaceTypeOfMethod(int idx)
    {
        int classIdx = u2(gbase + gcp[idx]);            // *ref.class_index -> Class entry
        return typeOfClass(classIdx);
    }

    // ----- the on-metal MetalSymbols stubs made real: strings + magic intrinsics -----

    /**
     * Intern the String literal at cp index {@code stringCp} as a heap {@code byte[]}
     * (the writer's [null TIB][status][length][bytes] layout, i.e. an ordinary array)
     * and return its address, so the shared core's {@code ldc} loads a real ref.
     */
    static long internString(int stringCp)
    {
        int off = ClassReader.stringUtf8Off(gbytes, gcp, stringCp);   // Utf8 body offset
        int len = u2(gbase + off);
        long arr = Heap.allocArray(len, 1);             // byte[] (raw element-size header), length set
        long bt = byteArrayTib();
        if (bt != 0L)
        {
            Magic.store64(arr + ObjectModel.TIB_OFFSET, bt);   // type it as [B so `checkcast [B` on String.value resolves
        }
        int i = 0;
        while (i < len)
        {
            Magic.store8(arr + 24 + i, u1(gbase + off + 2 + i));   // ARRAY_BASE_OFFSET = 24
            i += 1;
        }
        return arr;
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
        if (litObjByCp != null && stringCp < litObjByCp.length && litObjByCp[stringCp] != 0L)
        {
            return litObjByCp[stringCp];
        }
        long bytes = internString(stringCp);
        long tib = stringTib();
        long result;
        if (tib == 0L)
        {
            result = anchorLiteral(bytes);              // String not loaded: a raw byte[]
        }
        else
        {
            long obj = Heap.alloc(stringSize());
            Magic.store64(obj + 0L, tib);               // TIB
            Magic.store64(obj + 16L, bytes);            // value field (offset 16)
            result = anchorLiteral(obj);
        }
        if (litObjByCp != null && stringCp < litObjByCp.length)
        {
            litObjByCp[stringCp] = result;
        }
        return result;
    }

    // GC ROOT for interned literals: a JIT'd `ldc "..."` bakes the literal's heap address into code
    // IMMEDIATES only (movz/movk), which the conservative collector cannot see — so without an anchor a
    // collection would sweep every string literal not currently live on a stack, and the next execution of
    // that ldc would push a freed (re-zeroed / reused) object. Every interned literal is therefore also
    // recorded here; the array is reachable from this static -> the image statics root -> its body is
    // traced -> the literals stay marked. Literals live until the next batch rewind reclaims them wholesale.
    private static long[] litAnchor;
    private static int litAnchorN;
    // Per-blob ldc-String intern cache: litObjByCp[cpIndex] = the one interned object for that String cp entry
    // (so all ldc sites of the same literal in a class share one object). Reallocated each parseConstPool, so it
    // always matches the blob currently compiling; entries are anchored (litAnchor) like any interned literal.
    private static long[] litObjByCp;

    /** Record {@code obj} (a literal the JIT bakes into code) as a GC root; returns it for chaining. */
    private static long anchorLiteral(long obj)
    {
        if (litAnchor == null || litAnchorN == litAnchor.length)
        {
            long[] grown = new long[litAnchor == null ? 256 : litAnchor.length * 2];
            int i = 0;
            while (i < litAnchorN)
            {
                grown[i] = litAnchor[i];
                i += 1;
            }
            litAnchor = grown;
        }
        litAnchor[litAnchorN] = obj;
        litAnchorN += 1;
        return obj;
    }

    /** Whether the {@code *ref} at {@code idx} names owner {@code magic/Magic}. */
    static boolean isMagicOwner(int idx)
    {
        long p = gbase + ClassReader.refClassNameOff(gbytes, gcp, idx);
        if (u2(p) != 11)                                // "magic/Magic"
        {
            return false;
        }
        p += 2;
        return u1(p) == 'm' && u1(p + 1) == 'a' && u1(p + 2) == 'g' && u1(p + 3) == 'i'
            && u1(p + 4) == 'c' && u1(p + 5) == '/' && u1(p + 6) == 'M' && u1(p + 7) == 'a'
            && u1(p + 8) == 'g' && u1(p + 9) == 'i' && u1(p + 10) == 'c';
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
        if (isName(gbase, n, 0x6279746573L, 5))    { return Intrinsics.BYTES; }    // "bytes"
        if (isName(gbase, n, 0x6C6F616438L, 5))    { return Intrinsics.LOAD8; }    // "load8"
        if (isName(gbase, n, 0x6C6F61643332L, 6))  { return Intrinsics.LOAD32; }   // "load32"
        if (isName(gbase, n, 0x6C6F61643634L, 6))  { return Intrinsics.LOAD64; }   // "load64"
        if (isName(gbase, n, 0x73746F726538L, 6))  { return Intrinsics.STORE8; }   // "store8"
        if (isName(gbase, n, 0x73746F72653332L, 7)) { return Intrinsics.STORE32; } // "store32"
        if (isName(gbase, n, 0x73746F72653634L, 7)) { return Intrinsics.STORE64; } // "store64"
        if (isName(gbase, n, 0x737061776EL, 5))      { return Intrinsics.SPAWN; }    // "spawn"
        if (isName(gbase, n, 0x73656D57616974L, 7))  { return Intrinsics.SEM_WAIT; } // "semWait"
        if (isName(gbase, n, 0x73656D506F7374L, 7))  { return Intrinsics.SEM_POST; } // "semPost"
        if (isName(gbase, n, 0x736C6565704D73L, 7))  { return Intrinsics.SLEEP_MS; } // "sleepMs"
        if (isName(gbase, n, 0x6E657753656DL, 6))    { return Intrinsics.NEW_SEM; }  // "newSem"
        if (isName(gbase, n, 0x7265706F7274L, 6))    { return Intrinsics.REPORT; }   // "report"
        if (isName(gbase, n, 0x7072696E74537472L, 8)) { return Intrinsics.PRINT_STR; } // "printStr"
        if (isName(gbase, n, 0x616464724F66L, 6))    { return Intrinsics.ADDR_OF; }   // "addrOf"
        if (isName(gbase, n, 0x66726F6D41646472L, 8)) { return Intrinsics.FROM_ADDR; } // "fromAddr"
        if (isName(gbase, n, 0x63616C6C4EL, 5))      { return Intrinsics.CALL_N; }    // "callN"
        if (isName(gbase, n, 0x6D77616974L, 5))      { return Intrinsics.MON_WAIT; }    // "mwait"
        if (isName(gbase, n, 0x6D6E6F74696679L, 7))  { return Intrinsics.MON_NOTIFY; }  // "mnotify"
        if (isName(gbase, n, 0x6D6E6F74616C6CL, 7))  { return Intrinsics.MON_NOTALL; }  // "mnotall"
        if (isName(gbase, n, 0x746A6F696EL, 5))      { return Intrinsics.THREAD_JOIN; } // "tjoin"
        if (isName(gbase, n, 0x737461636B7472L, 7))  { return Intrinsics.STACK_TRACE; } // "stacktr"
        if (isName(gbase, n, 0x616C6C746872L, 6))    { return Intrinsics.ALL_THREADS; } // "allthr"
        if (isName(gbase, n, 0x686C646C6F636BL, 7))  { return Intrinsics.HOLDS_LOCK; }  // "hldlock"
        if (isName(gbase, n, 0x696E7472L, 4))        { return Intrinsics.INTR; }        // "intr"
        if (isName(gbase, n, 0x6973696E7472L, 6))    { return Intrinsics.IS_INTR; }     // "isintr"
        if (isName(gbase, n, 0x776173696E7472L, 7))  { return Intrinsics.WAS_INTR; }    // "wasintr"
        if (isName(gbase, n, 0x6973616C697665L, 7))  { return Intrinsics.IS_ALIVE; }    // "isalive"
        if (isName(gbase, n, 0x6A6F696E6D73L, 6))    { return Intrinsics.JOIN_TIMED; }  // "joinms"
        if (isName(gbase, n, 0x7061726BL, 4))        { return Intrinsics.PARK; }        // "park"
        if (isName(gbase, n, 0x756E7061726BL, 6))    { return Intrinsics.UNPARK; }      // "unpark"
        if (isName(gbase, n, 0x726561644C52L, 6))    { return Intrinsics.READ_LR; }     // "readLR" (getCallerClass)
        if (isName(gbase, n, 0x6D70696472L, 5))      { return Intrinsics.READ_MPIDR; }  // "mpidr" (which core am I?)
        if (isName(gbase, n, 0x7365747072696FL, 7))  { return Intrinsics.SET_PRIO; }    // "setprio"
        if (isName(gbase, n, 0x6765747072696FL, 7))  { return Intrinsics.GET_PRIO; }    // "getprio"
        return -1;
    }




    // encodings (pure int math — JDK-free)
    private static int movz(int rd, int imm)
    {
        return A64Enc.movz(rd, imm, 0);
    }
    private static int mov(int rd, int rm)
    {
        return A64Enc.movReg(rd, rm);
    }

    // condition codes (EQ=0 NE=1 GE=10 LT=11 GT=12 LE=13)
    private static int ifCond(int op)
    {
        return code6(op - 0x99);
    }
    private static int icmpCond(int op)
    {
        return code6(op - 0x9f);
    }
    private static int code6(int k)
    {
        if (k == 0)
        {
            return 0;    // eq
        }
        if (k == 1)
        {
            return 1;    // ne
        }
        if (k == 2)
        {
            return 11;    // lt
        }
        if (k == 3)
        {
            return 10;    // ge
        }
        if (k == 4)
        {
            return 12;    // gt
        }
        return 13;              // le
    }


    private static int opLen(int op)
    {
        if (op == 0x10 || op == 0x12 || op == 0x15 || op == 0x16 || op == 0x17 || op == 0x18 || op == 0x19
                || op == 0x36 || op == 0x37 || op == 0x38 || op == 0x39 || op == 0x3a || op == 0xa9 || op == 0xbc)
        {
            return 2;    // bipush/ldc/iload/lload/fload/dload/aload/istore/lstore/fstore/dstore/astore/ret/newarray
        }
        if (op == 0xb9 || op == 0xba)
        {
            return 5;    // invokeinterface: index(2)+count(1)+zero(1) / invokedynamic: index(2)+zero(2)
        }
        if (op == 0x11 || op == 0x13 || op == 0x14 || op == 0x84 || (op >= 0x99 && op <= 0xa8) || op == 0xc6 || op == 0xc7
                || op == 0xb2 || op == 0xb3 || op == 0xb4 || op == 0xb5 || op == 0xb6 || op == 0xb7 || op == 0xb8
                || op == 0xbb || op == 0xbd || op == 0xc0 || op == 0xc1)
        {
            return 3;    // sipush/ldc_w/ldc2_w/iinc/if*/goto/jsr/get-put(static|field)/invoke*/new/anewarray/cast/instanceof
        }
        return 1;
    }

    /**
     * Full instruction length at {@code pc} in the bytecode at memory {@code code} — position-aware, so the
     * variable-length {@code tableswitch}/{@code lookupswitch}/{@code wide}/{@code multianewarray} (present in
     * real java.base) don't misalign the scanners. Falls back to the fixed table {@link #opLen(int)}.
     */
    private static int insnLen(long code, int pc)
    {
        int op = u1(code + pc);
        if (op == 0xAA)                                    // tableswitch
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            return (p + 12 + (u4(code + p + 8) - u4(code + p + 4) + 1) * 4) - pc;
        }
        if (op == 0xAB)                                    // lookupswitch
        {
            int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            return (p + 8 + u4(code + p + 4) * 8) - pc;
        }
        if (op == 0xC4)                                    // wide (iinc = 6, else 4)
        {
            return u1(code + pc + 1) == 0x84 ? 6 : 4;
        }
        if (op == 0xC5)                                    // multianewarray
        {
            return 4;
        }
        return opLen(op);
    }
}
