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
    private static long[] gvBase;   // blob holding this slot's name/descriptor
    private static int[] gvName;    // method name Utf8 offset (in gvBase)
    private static int[] gvDesc;    // descriptor Utf8 offset (in gvBase)
    private static long[] gvImplBuf;   // inherited impl buffer (0 => this class's own)
    private static long[] gvImplCode;  // this class's own method bytecode (0 => inherited)
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
    private static long[] rgBase;   // declaring class blob base (holds its Utf8 strings)
    private static int[] rgClassOff;   // class name Utf8 offset
    private static int[] rgNameOff;    // method name Utf8 offset
    private static int[] rgDescOff;    // descriptor Utf8 offset
    private static long[] rgBuf;    // compiled buffer address
    private static long[] rgLine;   // per method: address of its {u32 count, (u32 wordOff, u32 line)*} table, or 0
    private static long[] rgSrc;    // per method: address of its class's SourceFile filename Utf8, or 0
    private static int[] rgAccess;  // per method: access_flags (ACC_STATIC etc.) -- reflective invoke + getModifiers
    private static int rgCount;

    // Static-field registry: per loaded class, each static field's {class, name, slot address} so a
    // cross-class getstatic/putstatic (e.g. Long.formatUnsignedLong0 reading Integer.digits) resolves.
    private static long[] sgBase;   // declaring class blob base
    private static int[] sgClassOff;// class name Utf8 offset
    private static int[] sgNameOff; // field name Utf8 offset
    private static long[] sgAddr;   // the field's static-slot address
    private static int sgCount;

    // Class registry: per loaded class, what another class needs to `new` it and
    // dispatch through it — its name (base+offset), TIB, and instance-field count.
    private static final int MAXCLASS = 1024;
    private static long[] clBase;
    private static int[] clNameOff;
    private static long[] clTib;
    private static int[] clFieldCount;
    private static int[] clVtCount;      // flattened vtable size (so a subclass can copy it)
    private static long[] clType;        // each class's Type node (for instanceof/checkcast)
    // Two-phase load (structure then bodies): phase A registers every class's structure (Type, TIB, fields,
    // vtable SLOT numbering, interface slots) in super/interface-first order (acyclic); phase B compiles all
    // method bodies + builds the TIBs, by which point every cross-class new/field/vtable/itable/cast target is
    // registered -> load order no longer matters (only method-CALL cycles remain, handled by patchRelocs).
    private static long[] clStatics;     // each class's static block base (gStatics), reused between the two phases
    private static int[]  clVtStart;     // start index of this class's slots in the vt registry (phase B fills bufs)
    private static boolean[] clIsIface;  // interface? (phase B compiles only its default/static bodies, no TIB fill)
    private static int[] clSuperReg;     // each class's superclass registry index (-1 = none), for full-chain itable closure
    private static int[] clModifiers;    // Class.getModifiers() value, computed at LOAD time (ACC_SUPER stripped,
                                         // InnerClasses-overridden for nested) — read at runtime WITHOUT re-parsing
    private static int clCount;
    // Array Type cache (per demand-load batch, since resetLoader reclaims the heap under them). primArrTib is
    // indexed by newarray atype (4..11); refArr* is a small element-Type-keyed registry for reference arrays.
    private static long[] primArrTib;     // arrayTib per primitive atype (0 = not yet created)
    private static long[] refArrElem;     // element Type key (reference arrays)
    private static long[] refArrTib;      // arrayTib for that element
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

    // Field registry: per instance field of each class, its class/name (base+offset)
    // and slot, so a cross-class get/putfield can find the offset.
    private static final int MAXFIELD = 4096;
    private static long[] fldBase;
    private static int[] fldClassOff;
    private static int[] fldNameOff;
    private static int[] fldSlot;
    private static int[] fldAccess;   // access_flags of each registered instance field (reflection: getModifiers)
    private static int[] fldDescOff;   // Utf8 offset of each registered instance field's type descriptor
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
    private static final int MAXIFM = 512;
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
            // Compile now (with this class's statics block live and cross-class calls recorded as relocs), but
            // ENQUEUE the entry — a <clinit> that calls another class (e.g. ArraysSupport -> SharedSecrets) would
            // hit an unpatched `bl 0` if run here mid-load. runClinits() runs the queue after patchRelocs.
            clinitEntry[clinitN] = compile(code, gcodeLen, gFoundDescOff, gFoundStatic);
            clinitPd[clinitN] = findPdByName(gbase, gThisNameOff);   // which blob (for dependency-ordered running)
            if (utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/io/FileDescriptor")))
            {
                clinitFdFirst = clinitN;   // run FIRST in runClinits: it registers the JavaIOFileDescriptorAccess
            }                              // that NativeDispatcher/NioSocketImpl <clinit>s read via SharedSecrets
            clinitN += 1;
        }
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
    private static long[] clinitEntry;
    private static int[] clinitPd;       // pd-blob index of each enqueued <clinit> (for dependency-ordered running)
    private static int clinitN;
    private static int clinitFdFirst;    // index of java/io/FileDescriptor's enqueued <clinit> (run first), or -1
    private static int clinitRunFrom;    // watermark: clinits [0,clinitRunFrom) already ran (incremental forName load)

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
        // FileDescriptor.<clinit> registers the JavaIOFileDescriptorAccess into SharedSecrets that many other
        // <clinit>s read via getJavaIOFileDescriptorAccess() (NativeDispatcher, NioSocketImpl). If it runs late,
        // that accessor sees a null field and falls back to MethodHandles.lookup().ensureInitialized (denied ->
        // trap). The usage-dependency heuristic below doesn't capture the SharedSecrets-mediated edge, so run it
        // first, unconditionally. It only WRITES a SharedSecrets field (no other class's statics read), so it is
        // safe to run before its own deps.
        if (clinitFdFirst >= 0 && clinitFdFirst < clinitN && !done[clinitFdFirst])
        {
            if (logClinit != 0)
            {
                Uart.write(Magic.bytes("  clinit(fd-first) java/io/FileDescriptor\n"));
            }
            long unusedFd = Magic.call0(clinitEntry[clinitFdFirst]);
            done[clinitFdFirst] = true;
            remaining -= 1;
        }
        while (remaining > 0)
        {
            int progress = 0;
            int i = 0;
            while (i < clinitN)
            {
                if (!done[i] && !clinitDepBlocked(i, done))
                {
                    if (logClinit != 0)                  // #43: name each <clinit> as it runs (spot a hanging one)
                    {
                        int cpd = clinitPd[i];
                        Uart.write(Magic.bytes("  clinit "));
                        writeName(pdBase[cpd] + pdNameOff[cpd] + 2, u2(pdBase[cpd] + pdNameOff[cpd]));
                        Uart.putc(0x0A);
                    }
                    long unused = Magic.call0(clinitEntry[i]);
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
                    long unused = Magic.call0(clinitEntry[j]);
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

    /** True if clinit {@code i} references a class whose own (still-unrun) {@code <clinit>} must run first. */
    private static boolean clinitDepBlocked(int i, boolean[] done)
    {
        int pd = clinitPd[i];
        if (pd < 0)
        {
            return false;
        }
        int d = 0;
        while (d < dpCount)
        {
            if (dpOwner[d] == pd)
            {
                int jpd = findPdByName(pdBase[pd], dpOff[d]);   // the referenced class's blob
                if (jpd >= 0 && jpd != pd)
                {
                    int k = 0;
                    while (k < clinitN)                 // does that blob have a not-yet-run <clinit>?
                    {
                        if (clinitPd[k] == jpd && !done[k])
                        {
                            return true;
                        }
                        k += 1;
                    }
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
                || utf8IsAtBase(gbase, gThisNameOff, Magic.bytes("java/util/Tripwire"));
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

    /**
     * Demand-load and run {@code demo/ParseAllDemo.main()} — which loads the UNMODIFIED real
     * {@code java/lang/Integer} through the normal closure/loadAll path (with the reachability mark set to
     * its {@code main}) and calls {@code Integer.parseInt}. Proves loadAll compiles only reachable methods:
     * loading real Integer whole would choke on toString/format's unbuilt deps.
     */
    static void loadIntegerReachable()
    {
        resetLoader();
        addBlob(VM.integerBytes, (int) VM.integerLen);           // the real, unmodified java.base class (big)
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.characterBytes, (int) VM.characterLen);
        addBlob(VM.numberFmtBytes, (int) VM.numberFmtLen);
        addBlob(VM.illegalArgBytes, (int) VM.illegalArgLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.throwableBytes, (int) VM.throwableLen);
        addBlob(VM.parseAllDemoBytes, (int) VM.parseAllDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.parseAllDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        // Resolve main from the global registry (robust to load order: the Integer<->Math cycle's force-load
        // may not leave the demo as the last-compiled class, so bufOf's last-batch table can't be trusted).
        long buf = globalMethodBuf(Magic.bytes("demo/ParseAllDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** The compiled buffer of a loaded method, by class/name/descriptor (from the global registry). */
    private static long globalMethodBuf(byte[] cls, byte[] name, byte[] desc)
    {
        int i = 0;
        while (i < rgCount)
        {
            if (utf8IsAtBase(rgBase[i], rgClassOff[i], cls)
                    && utf8IsAtBase(rgBase[i], rgNameOff[i], name)
                    && utf8IsAtBase(rgBase[i], rgDescOff[i], desc))
            {
                return rgBuf[i];
            }
            i += 1;
        }
        return 0L;
    }

    /**
     * Demand-load and run {@code demo/ToStringDemo.main()} — which calls the UNMODIFIED real
     * {@code Integer.toString(int)}, loaded via the reachable closure path. Closes the produce-a-String wall
     * with mini {@code jdk/internal/util/DecimalDigits} + the real byte[]+coder {@code String} constructor
     * (and {@code java/lang/StringLatin1}).
     */
    static void loadIntegerToString()
    {
        resetLoader();
        addBlob(VM.integerBytes, (int) VM.integerLen);           // the real, unmodified java.base class
        addBlob(VM.stringBytes, (int) VM.stringLen);             // real-shaped String (byte[]+coder)
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.toStringDemoBytes, (int) VM.toStringDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.toStringDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ToStringDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/HexLongDemo.main()} — the UNMODIFIED real {@code Integer.toHexString}
     * (formatUnsignedInt indexing the loader-seeded {@code Integer.digits}) and {@code Long.toString} (the
     * {@code DecimalDigits} long overloads), via the reachable closure path.
     */
    static void loadHexLong()
    {
        resetLoader();
        addBlob(VM.integerBytes, (int) VM.integerLen);   // Math (pulled by the closure) + Integer's cross-refs that
        addBlob(VM.longBytes, (int) VM.longLen);         // the cycle leaves unresolved are fixed by patchRelocs
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.hexLongDemoBytes, (int) VM.hexLongDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.hexLongDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/HexLongDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/LongMoreDemo.main()} — the UNMODIFIED real {@code Long.parseLong}
     * (parseInt's mini deps: String/Character/NumberFormatException) and {@code Long.toHexString}
     * (formatUnsignedLong0 indexing the loader-seeded {@code Integer.digits}), via the reachable closure.
     */
    static void loadLongMore()
    {
        resetLoader();
        addBlob(VM.longBytes, (int) VM.longLen);         // no seed-ordering: toUnsignedString0 -> Math.max and
        addBlob(VM.integerBytes, (int) VM.integerLen);   // formatUnsignedLong0 -> Integer.digits are patched by relocs
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.characterBytes, (int) VM.characterLen);
        addBlob(VM.numberFmtBytes, (int) VM.numberFmtLen);
        addBlob(VM.illegalArgBytes, (int) VM.illegalArgLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.throwableBytes, (int) VM.throwableLen);
        addBlob(VM.longMoreDemoBytes, (int) VM.longMoreDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.longMoreDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/LongMoreDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/MathIntDemo.main()} — the UNMODIFIED real integer {@code Math} methods
     * {@code floorDiv}/{@code floorMod} (pure) and {@code addExact} (throws a real {@code ArithmeticException}
     * on overflow, caught via cross-method unwind), via the reachable closure. No seed-ordering (relocs).
     */
    static void loadMathInt()
    {
        resetLoader();
        addBlob(VM.mathBytes, (int) VM.mathLen);
        addBlob(VM.integerBytes, (int) VM.integerLen);           // Integer.toString for the output
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.arithExcBytes, (int) VM.arithExcLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.throwableBytes, (int) VM.throwableLen);
        addBlob(VM.mathIntDemoBytes, (int) VM.mathIntDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.mathIntDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/MathIntDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/ObjectsDemo.main()} — the UNMODIFIED real {@code java/util/Objects}:
     * equals/hashCode dispatch through the mini {@code java/lang/Object} root's vtable slots into String's
     * real overrides; requireNonNull throws a real {@code NullPointerException} (caught via cross-method
     * unwind). Object is seeded explicitly (implicit root). No seed-ordering (relocs handle any cycle).
     */
    static void loadObjects()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);             // root: hashCode/equals slots String overrides
        addBlob(VM.objectsBytes, (int) VM.objectsLen);
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.integerBytes, (int) VM.integerLen);           // Integer.toString for the output
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.npeBytes, (int) VM.npeLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.throwableBytes, (int) VM.throwableLen);
        addBlob(VM.objectsDemoBytes, (int) VM.objectsDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.objectsDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ObjectsDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/ArraysDemo.main()} — the UNMODIFIED real {@code java/util/Arrays}:
     * fill/binarySearch (leaf) and equals (via the mini {@code ArraysSupport.mismatch}), via the reachable
     * closure. The array side of the surface.
     */
    static void loadArrays()
    {
        resetLoader();
        addBlob(VM.arraysBytes, (int) VM.arraysLen);
        addBlob(VM.arraysSupportBytes, (int) VM.arraysSupportLen);   // mini mismatch for Arrays.equals
        addBlob(VM.integerBytes, (int) VM.integerLen);               // Integer.toString for the output
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.arraysDemoBytes, (int) VM.arraysDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.arraysDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ArraysDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/BoxingDemo.main()} — real {@code Integer.valueOf} autoboxing (the
     * {@code new Integer} path) used as HashMap keys, looked up by a distinct boxed Integer of equal value
     * via real {@code Integer.hashCode}/{@code equals} (dispatched through the mini Object root's vtable
     * slots down the Integer -> Number -> Object chain). Object + Number seeded as roots; no seed-ordering.
     */
    static void loadBoxing()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);             // root: hashCode/equals slots Integer overrides
        addBlob(VM.numberBytes, (int) VM.numberLen);             // Integer's superclass (propagates the slots)
        addBlob(VM.integerBytes, (int) VM.integerLen);
        addBlob(VM.integerCacheBytes, (int) VM.integerCacheLen); // its statics read 0 (clinit skipped) -> valueOf -> new Integer
        addBlob(VM.stringBytes, (int) VM.stringLen);             // stock java.util.HashMap demand-loaded (mini retired)
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.boxingDemoBytes, (int) VM.boxingDemoLen);
        // reachability-gated closure (no pull-all): markReachable pulls the reachable closure on demand.
        entryPoint(VM.boxingDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        seedIntegerCache();                             // build the [-128,127] cache valueOf uses (clinit unrunnable)
        long buf = globalMethodBuf(Magic.bytes("demo/BoxingDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** Demand-load and run {@code demo/StrOpsDemo.main()} — String indexOf/substring on the mini String. */
    static void loadStrOps()
    {
        resetLoader();
        // java/lang/Object MUST be seeded first: it fixes hashCode/equals/toString to their canonical vtable
        // slots so String's overrides land on the SAME slots (see loadMap/loadList). split("::") pulls
        // java/util/HashMap, whose hash(Object) calls key.hashCode() polymorphically -- without this seed
        // Object.hashCode and String.hashCode get inconsistent slots and the dispatch is a `blr 0` -> reboot.
        addBlob(VM.objectBytes, (int) VM.objectLen);
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);           // Integer.toString for int results
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.strOpsDemoBytes, (int) VM.strOpsDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.strOpsDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        VM.unwindLog = 1;                               // #43: log exception throw-stacks during this batch (also the printStackTrace() mechanism)
        loadAll();
        seedSystemStreams();                            // M2: install System.out/err (PrintStream overlay -> UART)
        long buf = globalMethodBuf(Magic.bytes("demo/StrOpsDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
        VM.unwindLog = 0;                               // scoped to this batch: later demos throw EXPECTED exceptions
    }

    /** M3: java.io on the embedded read-only RAMFS — the guest {@code java/io/FileInputStream} overlay
     *  (open0 -> {@code VM.fileOpen}; reads via Magic loads) driven by {@code demo/FileDemo}. */
    static void loadFileIo()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // Integer.toString for int concat in the demo output
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.fileDemoBytes, (int) VM.fileDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.fileDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/FileDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
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

    /** The real-program milestone: {@code demo/WordCount} — ordinary stock-Java (no VM hooks) entered
     *  through a real {@code main(String[])}, with the argument array built here (a raw Object[] of guest
     *  Strings). Must print byte-identical output to the same class run on the host JDK. */
    static void loadWordCount()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // parseInt(args[1]) + boxed counts + toString
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.wordCountBytes, (int) VM.wordCountLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.wordCountBytes, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        loadAll();
        seedSystemStreams();                            // the program prints via System.out (M2 overlay)
        long a0 = guestString(Magic.bytes("/data/sample.txt"));
        long a1 = guestString(Magic.bytes("3"));
        long argv = Heap.allocArray(2, 8);              // String[2] (8-byte reference elements)
        Magic.store64(argv + 24L, a0);
        Magic.store64(argv + 32L, a1);
        long buf = globalMethodBuf(Magic.bytes("demo/WordCount"), Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        if (buf != 0L)
        {
            long unused = Magic.call2(buf, argv, 0L);   // main(args) — x1 unused by a 1-arg static
        }
    }

    /** The charset closure: {@code demo/CharsetDemo} — stock {@code new String(byte[])}/{@code getBytes()}
     *  via the overlay {@code Charset.defaultCharset()} -> {@code sun.nio.cs.UTF_8.INSTANCE} identity,
     *  pinning String's pure-Java UTF-8 fast paths (the decoder fallback is denylisted). */
    static void loadCharset()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // Integer.toString for the int concats
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.charsetDemoBytes, (int) VM.charsetDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.charsetDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        seedSystemStreams();                            // the demo prints via System.out (M2 overlay)
        long buf = globalMethodBuf(Magic.bytes("demo/CharsetDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** The long-running-program milestone: {@code demo/LispDemo} — a Lisp interpreter (ordinary stock
     *  Java) whose churn loop forces collections MID-COMPUTATION; every iteration must stay correct. */
    static void loadLisp()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // parseInt(args[1]) + boxed interpreter arithmetic
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.lispDemoBytes, (int) VM.lispDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.lispDemoBytes, Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        loadAll();
        seedSystemStreams();                            // the interpreter prints via System.out (M2 overlay)
        Heap.gcPressure = 0;                            // count only THIS run's mid-computation collections
        long a0 = guestString(Magic.bytes("/data/prog.lisp"));
        long a1 = guestString(Magic.bytes("600"));
        long argv = Heap.allocArray(2, 8);              // String[2] (8-byte reference elements)
        Magic.store64(argv + 24L, a0);
        Magic.store64(argv + 32L, a1);
        long buf = globalMethodBuf(Magic.bytes("demo/LispDemo"), Magic.bytes("main"), Magic.bytes("([Ljava/lang/String;)V"));
        if (buf != 0L)
        {
            long unused = Magic.call2(buf, argv, 0L);   // main(args) — x1 unused by a 1-arg static
        }
        Uart.write(Magic.bytes("  gc during lisp: collections="));
        VM.printDec(Heap.gcPressure);
        Uart.putc(0x0A);
    }

    /** The GC milestone: {@code demo/GcDemo} churns ~640 MB through the ~192 MB arena — it only completes
     *  if allocation pressure triggers collections and freed blocks are reused. Prints the evidence after. */
    static void loadGcDemo()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // Integer.toString for the int concats
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.gcDemoBytes, (int) VM.gcDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.gcDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        seedSystemStreams();                            // the demo prints via System.out (M2 overlay)
        Heap.gcPressure = 0;                            // count only THIS demo's collections
        VM.gcLog = 1;                                   // per-collection evidence (walked/marked/freed/bytes)
        long buf = globalMethodBuf(Magic.bytes("demo/GcDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
        Uart.write(Magic.bytes("  gc: collections="));
        VM.printDec(Heap.gcPressure);
        Uart.write(Magic.bytes(" lastReclaimed="));
        VM.printHex(VM.reclaimed);
        Uart.putc(0x0A);
        VM.gcLog = 0;                                   // scoped to this batch: later demos just count collections
    }

    /** Evidence line for the code-arena rewind: cur (mark + one batch) far below high (max batch ever). */
    static void printCodeArena()
    {
        Uart.write(Magic.bytes("code arena: mark="));
        VM.printHex(codeHeapMark);
        Uart.write(Magic.bytes(" cur="));
        VM.printHex(Magic.load64(Heap.CODE_PTR_CELL));
        Uart.write(Magic.bytes(" high="));
        VM.printHex(codeHeapHigh);
        Uart.putc(0x0A);
    }

    /** M4: Thread identity + Class reflection — {@code demo/ReflectDemo} (guest Thread/Class overlays,
     *  currentThread via {@code VM.taskThreadObj}, getName/isInstance/superclass natives). */
    static void loadReflect()
    {
        resetLoader();
        addBlob(VM.objectBytes, (int) VM.objectLen);    // Object first: canonical hashCode/equals/toString slots
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);  // Integer.toString for int concat + Integer.class literal
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.reflectDemoBytes, (int) VM.reflectDemoLen);
        // reachability-gated closure: markReachable pulls the reachable closure on demand (no pull-all).
        entryPoint(VM.reflectDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        buildRunTramp();                                // re-bake run()'s imap slot for THIS batch: the
                                                        //   interface-method registry resets per batch, and this
                                                        //   closure loads Runnable LAST (the philosophers-batch
                                                        //   tramp's baked slot would BLR a wrong imap entry)
        long buf = globalMethodBuf(Magic.bytes("demo/ReflectDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
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
        return i >= 0 ? clTib[i] : 0L;
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
            if (rgBuf[i] != 0L && rgBuf[i] <= pc && rgBuf[i] > bestBuf) { bestBuf = rgBuf[i]; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < clinitN)
        {
            if (clinitEntry[c] != 0L && clinitEntry[c] <= pc && clinitEntry[c] > bestBuf) { bestBuf = clinitEntry[c]; bestClin = c; bestReg = -1; }
            c += 1;
        }
        long clsStr = 0L;
        long methStr = 0L;
        long fileStr = 0L;
        long line = 0L;
        if (bestReg >= 0)
        {
            clsStr = guestStringUtf8(rgBase[bestReg], rgClassOff[bestReg]);
            methStr = guestStringUtf8(rgBase[bestReg], rgNameOff[bestReg]);
            fileStr = guestStringUtf8(rgSrc[bestReg], 0);
            line = lineAtOffset(rgLine[bestReg], (int) ((pc - bestBuf) >> 2));
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

    private static void resetLoader()
    {
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
                if (zEnd > 0x1000_0000L) { zEnd = 0x1000_0000L; }   // never cross into core 1's arena
                while (z0 < zEnd)
                {
                    Magic.store64(z0, 0L);
                    z0 += 8L;
                }
                demandHeapHigh = zEnd;                           // record it so the else-branch keeps it re-zeroed
            }
            else
            {
                long oldPtr = Magic.load64(Heap.PTR_CELL);      // ZERO all heap ever used above the mark, so a reused
                if (oldPtr > demandHeapHigh)                     // block never carries a PRIOR batch's code bytes: an
                {                                                // uninitialized/OOB slot then reads 0 (a caught blr 0)
                    demandHeapHigh = oldPtr;                     // instead of stale code -> a layout-dependent wild branch.
                }                                                // Zero up to the HIGH-WATER mark (not just the previous
                long z = demandHeapMark;                         // batch) so a bigger batch's OOB reads land on 0 too.
                while (z < demandHeapHigh)
                {
                    Magic.store64(z, 0L);
                    z += 8L;
                }
                Magic.store64(Heap.PTR_CELL, demandHeapMark);   // reclaim the previous demo's dead code + objects
                Magic.store64(Heap.FREE_CELL, 0L);              // core 0's free-list entries are above it again
                // Code-arena rewind (same batch-death model): zero every code byte the previous batches wrote
                // above the mark -- a stale JIT buffer executed through a dangling pointer is the worst kind of
                // wild branch (zeros decode as a caught udf instead) -- flush the zeroes past the I-cache, and
                // rewind the bump. A batch that spawns threads must (already) rebuild the run-trampoline; its
                // compiled methods die with its registries exactly like its heap objects.
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
                Heap.publishCode(codeHeapMark, codeHeapHigh);   // drop stale I-cache lines over the dead code
                Magic.store64(Heap.CODE_PTR_CELL, codeHeapMark);
                VM.dropJitTablesAbove(codeHeapMark);            // frame/handler entries for the dead code would
                                                                //   ALIAS the next batch's reused addresses
                // Stale-root hygiene: a still-registered task Thread from a RECLAIMED batch (e.g. a sleeper
                // that never exited) now points at rewound memory -- as a conservative GC root it would
                // falsely retain whatever the NEXT batch allocates at that address. Its object is gone
                // either way; currentThread() lazily re-wraps if such a task ever asks again.
                int tt = 0;
                while (tt < VM.taskCount)
                {
                    if (VM.taskThreadObj[tt] >= demandHeapMark)
                    {
                        VM.taskThreadObj[tt] = 0L;
                    }
                    tt += 1;
                }
            }
        }
        litAnchor = null;                               // per-batch GC anchor for interned literals: the rewind
        litAnchorN = 0;                                 //   reclaimed both the literals and the anchor array
        VM.byteArrayTibCache = 0L;                      // the batch's [B TIB was just reclaimed with its heap
        rgBase = new long[MAXREG];
        rgClassOff = new int[MAXREG];
        rgNameOff = new int[MAXREG];
        rgDescOff = new int[MAXREG];
        rgBuf = new long[MAXREG];
        rgLine = new long[MAXREG];
        rgSrc = new long[MAXREG];
        rgAccess = new int[MAXREG];
        rgCount = 0;
        sgBase = new long[MAXREG];
        sgClassOff = new int[MAXREG];
        sgNameOff = new int[MAXREG];
        sgAddr = new long[MAXREG];
        sgCount = 0;
        relocRecording = 0;
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
        clinitPd = new int[MAXBLOB];
        clinitN = 0;
        clinitFdFirst = -1;
        clinitRunFrom = 0;
        primArrTib = new long[12];         // array Types live in the (reclaimed) demand heap: recreate per batch
        refArrElem = new long[64];
        refArrTib = new long[64];
        refArrCount = 0;
        mirType = new long[256];           // Class mirrors: also per batch (reclaimed heap)
        mirObj = new long[256];
        mirN = 0;
        classTibCache = 0L;
        clBase = new long[MAXCLASS];
        clNameOff = new int[MAXCLASS];
        clTib = new long[MAXCLASS];
        clFieldCount = new int[MAXCLASS];
        clVtCount = new int[MAXCLASS];
        clType = new long[MAXCLASS];
        clStatics = new long[MAXCLASS];
        clVtStart = new int[MAXCLASS];
        clIsIface = new boolean[MAXCLASS];
        clSuperReg = new int[MAXCLASS];
        clModifiers = new int[MAXCLASS];
        clCount = 0;
        clIfaceReg = new int[MAXCLASS * MAX_DIRECT_IF];
        clIfaceRegN = new int[MAXCLASS];
        ifClosureBuf = new int[MAXIFM];
        instImaps = new long[MAXIMAP];
        instImapReg = new int[MAXIMAP];
        instImapN = 0;
        fldBase = new long[MAXFIELD];
        fldClassOff = new int[MAXFIELD];
        fldNameOff = new int[MAXFIELD];
        fldSlot = new int[MAXFIELD];
        fldAccess = new int[MAXFIELD];
        fldDescOff = new int[MAXFIELD];
        fldCount = 0;
        vtClassBase = new long[MAXVT];
        vtClassOff = new int[MAXVT];
        vtNameBase = new long[MAXVT];
        vtNameOff = new int[MAXVT];
        vtDescOff = new int[MAXVT];
        vtSlot = new int[MAXVT];
        vtBuf = new long[MAXVT];
        vtCount = 0;
        gvBase = new long[MAXMV];
        gvName = new int[MAXMV];
        gvDesc = new int[MAXMV];
        gvImplBuf = new long[MAXMV];
        gvImplCode = new long[MAXMV];
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
        dpOwner = new int[MAXDEP];
        dpOff = new int[MAXDEP];
        pdLen = new int[MAXBLOB];
        gEntryBlob = 0L;                                 // no reachability mark unless a caller sets an entry
        markActive = 0;
        reachCode = new long[MAXREACH];
        reachN = 0;
        VM.jitFrameCount = 0L;                           // a demo's JIT'd frames/handlers are dead once it returns;
        VM.jitLocalCount = 0L;                           // reset the local table IN LOCKSTEP with the frame table (parallel
                                                         // {codeStart,codeEnd,regLocals}); addJitFrame guards only on
                                                         // jitFrameCount, so a stale jitLocalCount would keep growing past
                                                         // JIT_FRAME_MAX and overrun jitLocalTable into adjacent heap.
        VM.jitHandlerCount = 0L;                         // resetting per batch stops them accumulating past the caps
    }

    // ----- reachable-method compilation (M-B) --------------------------------
    // When a caller marks an entry point, loadAll compiles only the methods reachable from it (a call-graph
    // closure over the loaded blobs) instead of every method of every class. This lets a big real java.base
    // class load through the normal closure path without choking on its unreachable methods (toString,
    // parseInt's String.format paths, ...). Without an entry set, everything compiles (unchanged behaviour).
    private static final int MAXREACH = 8192;
    private static long gEntryBlob;                      // entry method's blob (0 => mark disabled)
    private static byte[] gEntryName, gEntryDesc;        // entry method name/descriptor
    private static int markActive;                       // 1 once markReachable has run (compileClass then filters)
    private static long[] reachCode;                     // bytecode addresses of the reachable methods
    private static int reachN;

    /** Declare the method loadAll should treat as the reachability root (call before addBlob/loadAll). */
    static void entryPoint(long blobBytes, byte[] name, byte[] desc)
    {
        gEntryBlob = blobBytes;
        gEntryName = name;
        gEntryDesc = desc;
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

    /** True if {@code code} (a method's bytecode address) was marked reachable. */
    private static boolean isReach(long code)
    {
        int i = 0;
        while (i < reachN)
        {
            if (reachCode[i] == code)
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** Add {@code code} to the reachable set if new; returns true if it was newly added. */
    private static boolean addReach(long code)
    {
        if (code == 0L || isReach(code) || reachN >= MAXREACH)
        {
            return false;
        }
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

    private static void markReachable()
    {
        reachN = 0;
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
        seedAllNamed(Magic.bytes("run"), Magic.bytes("()V"));   // trampoline entry (Runnable.run)
        // Each round is two bounded passes over the blobs (so the const pool is parsed O(blobs) times, not
        // per-ref): collect the call-site refs of every reachable method, then mark each ref's target(s).
        // Reachability-gated closure: each round (a) collects the class/method refs of every reachable
        // method, (b) PULLS any referenced class not yet loaded from the embedded dir + its super/interfaces,
        // (c) marks the invoke targets now resolvable. So a program pulls only its reachable closure -- the
        // basis for embedding all of java.base without dragging every class's full constant-pool closure in.
        boolean grew = true;
        while (grew)
        {
            grew = false;
            probeAll();                                 // set pdNameOff for all (incl. just-pulled) + dep list
            grew = seedAllNamed(Magic.bytes("run"), Magic.bytes("()V")) || grew;   // Runnable trampoline entries
            // VarHandle overlay ops: their signature-polymorphic call sites (getAndBitwiseOr:(Lsome;I)I) don't
            // match the overlay descriptor, so the normal invoke-target marking misses them and they'd compile
            // to a 0 vtable slot. Seed them by the overlay's own descriptor so they're compiled + filled in.
            grew = seedAllNamed(Magic.bytes("getAndBitwiseOr"), Magic.bytes("(Ljava/lang/Object;I)I")) || grew;
            grew = seedAllNamed(Magic.bytes("compareAndSet"), Magic.bytes("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z")) || grew;
            // StackTraceElement is instantiated NATIVELY (Loader.frameToElement), which RTA can't see, so its
            // getters would compile to 0 vtable slots. Seed them by name+descriptor (no-op if STE isn't loaded).
            grew = seedAllNamed(Magic.bytes("getMethodName"), Magic.bytes("()Ljava/lang/String;")) || grew;
            grew = seedClinits() || grew;               // runnable <clinit>s: pull the classes an initializer calls
            pendN = 0;
            int b = 0;
            while (b < pdCount)                         // collect refs of reachable methods
            {
                collectBlob(pdBase[b], pdLen[b]);
                b += 1;
            }
            int r = 0;
            while (r < pendN)                           // pull referenced classes not yet loaded
            {
                if (!nameRegistered(pendBase[r], pendClass[r])
                        && registerNameFromDir(pendBase[r], pendClass[r]) != 0L)
                {
                    grew = true;
                }
                r += 1;
            }
            b = 0;
            while (b < pdCount)                         // pull each loaded class's super + interfaces
            {
                grew = pullStructural(pdBase[b], pdLen[b]) || grew;
                b += 1;
            }
            grew = computeInstantiated() || grew;       // RTA: flag the pd blobs `new`'d by a reached method
            b = 0;
            while (b < pdCount)                         // resolve static/special targets (class-qualified, precise)
            {
                grew = resolveBlob(pdBase[b], pdLen[b]) || grew;
                b += 1;
            }
            grew = resolveVirtuals() || grew;           // RTA: virtual/interface targets, in instantiated types only
            grew = markDefaults() || grew;              // RTA: interface DEFAULT methods (resolveVirtuals only walks
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
        int b = 0;
        while (b < pdCount)
        {
            parseConstPool(pdBase[b], pdLen[b]);
            boolean iface = (u2(gp) & 0x0200) != 0;      // ACC_INTERFACE (access_flags right after the constant pool)
            if (iface)
            {
                parseForMethods(pdBase[b], pdLen[b]);    // sets gMethodsStart/gp for findMethodByRef
                int p = 0;
                while (p < pendN)
                {
                    if (pendKind[p] == 1)
                    {
                        long code = findMethodByRef(pendBase[p], pendName[p], pendDesc[p]);
                        if (code != 0L)
                        {
                            grew = addReach(code) || grew;
                        }
                    }
                    p += 1;
                }
            }
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
            if (code != 0L && isReach(code))
            {
                collectRefs(base, code, gcodeLen);
            }
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
        int r = 0;
        while (r < pendN)
        {
            // Only static/special calls (kind 0) resolve here: they name a concrete class, so mark that method
            // iff this blob IS that class. PEND_PULL pulls a class only; virtual/interface (kind 1) go through
            // resolveVirtuals (RTA over instantiated types), NOT "mark in every class carrying the name+desc".
            if (pendKind[r] == 0 && utf8EqAt(pendBase[r], pendClass[r], gbase, gThisNameOff))
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
            r += 1;
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
            return -1;                                   // java/lang/Object
        }
        return findPdByName(gbase, gcp[u2(gbase + gcp[superIdx])]);
    }

    private static boolean[] virtResolved;               // per-pend: already dispatched for the current class

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
            virtResolved = new boolean[pendN + 64];
        }
        int c = 0;
        while (c < pdCount)
        {
            if (pdInstantiated[c])
            {
                int p = 0;
                while (p < pendN)                        // reset: no pend dispatched yet for this class
                {
                    virtResolved[p] = false;
                    p += 1;
                }
                int cur = c;
                int guard = 0;
                while (cur >= 0 && guard < 64)           // walk C's superclass chain, parsing each level once
                {
                    parseForMethods(pdBase[cur], pdLen[cur]);
                    p = 0;
                    while (p < pendN)
                    {
                        if (pendKind[p] == 1 && !virtResolved[p])
                        {
                            long code = findMethodByRef(pendBase[p], pendName[p], pendDesc[p]);
                            if (code != 0L)
                            {
                                virtResolved[p] = true;  // nearest def; don't also mark a super's override-shadowed one
                                grew = addReach(code) || grew;
                            }
                        }
                        p += 1;
                    }
                    cur = superPdOf(cur);
                    guard += 1;
                }
            }
            c += 1;
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
            parseForMethods(pdBase[b], pdLen[b]);
            grew = addReach(findMethodByBytes(gbase, name, desc)) || grew;
            b += 1;
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
            parseForMethods(pdBase[b], pdLen[b]);
            if (!clinitBlocked())
            {
                long code = findMethodByBytes(gbase, Magic.bytes("<clinit>"), Magic.bytes("()V"));
                if (code != 0L && clinitCompilable(code, gcodeLen))
                {
                    grew = addReach(code) || grew;
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
     * Demand-load and run {@code demo/DiningPhilosophers.main()} on the metal. Register only the demo
     * blob from the embedded class directory, then pull in every class it transitively references that
     * the image embedded in {@link VM#classDir} — the mini {@code java.base} — logging each as it loads.
     * Build the shared run-trampoline (so {@code Thread.start()} can enter a Runnable), then JIT + call
     * main, which spawns the philosopher tasks. The scheduler (already running) preempts them.
     */
    static void loadAndRun()
    {
        resetLoader();
        addBlob(VM.philBytes, (int) VM.philLen);       // the program (embedded as a static blob, like Guest)
        entryPoint(VM.philBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated (+ indy)
        loadAll();
        buildRunTramp();                               // needs Runnable loaded (ifCount populated)
        long buf = globalMethodBuf(Magic.bytes("demo/DiningPhilosophers"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);            // main()V returns void; assign to avoid a pop2
        }
    }

    /**
     * Demand-load and run {@code demo/ConcatDemo.main()} — the invokedynamic (string-concat) proof.
     * {@code java/lang/String} is registered first so it loads (and gets a TIB) before the concat compiles;
     * ConcatDemo's {@code "a" + b} sites JIT into byte[] builds wrapped in a mini String, then print.
     */
    static void loadConcat()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);           // load java/lang/String first (concat needs its TIB)
        addBlob(VM.concatDemoBytes, (int) VM.concatDemoLen);   // the program
        entryPoint(VM.concatDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated closure
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ConcatDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/LambdaDemo.main()} — the invokedynamic (lambda) proof. Its
     * {@code () -> ...} sites JIT into synthetic lambda classes (see {@link #buildLambdaTib}); calling
     * {@code r.run()} dispatches into the lambda body. {@code java/lang/Runnable} is pulled from classDir.
     */
    static void loadLambda()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);    // the SAM-with-arg lambda prints via concat -> String
        addBlob(VM.lambdaDemoBytes, (int) VM.lambdaDemoLen);
        // reachability-gated: markReachable follows the lambda indys to mark their bodies (+ Runnable/IntOp).
        entryPoint(VM.lambdaDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/LambdaDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
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
        int j = 0;
        while (j < pdCount)
        {
            if (utf8EqAt(base, off, pdBase[j], pdNameOff[j]))
            {
                return true;
            }
            j += 1;
        }
        return false;
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
        addBlob(bytes, (int) VM.dirLen(namePtr, len));
        Uart.write(Magic.bytes("  load "));
        writeName(namePtr, len);
        Uart.putc(0x0A);
        return bytes;
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
            if (clType[i] == type)
            {
                Uart.write(Magic.bytes(" class="));
                writeName(clBase[i] + clNameOff[i] + 2, u2(clBase[i] + clNameOff[i]));
                Uart.write(Magic.bytes(" gvCount="));
                VM.printDec(clVtCount[i]);
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
            if (clType[i] == type)
            {
                writeName(clBase[i] + clNameOff[i] + 2, u2(clBase[i] + clNameOff[i]));
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
        int i = 0;
        while (i < clCount)
        {
            if (clType[i] == type)
            {
                int len = u2(clBase[i] + clNameOff[i]);
                long src = clBase[i] + clNameOff[i] + 2;
                long arr = Heap.allocArray(len, 1);
                int k = 0;
                while (k < len)
                {
                    int c = u1(src + k);
                    Magic.store8(arr + 24L + k, c == 0x2F ? 0x2E : c);   // '/' -> '.'
                    k += 1;
                }
                long obj = Heap.alloc(stringSize());
                Magic.store64(obj + 0L, stringTib());
                Magic.store64(obj + 16L, arr);          // value byte[]; coder@24 stays 0 = LATIN1
                return obj;
            }
            i += 1;
        }
        return 0L;
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
            return classMirror(clType[ci]);             // already loaded: cached mirror (identity-stable)
        }
        long type = loadClassIncremental(slash);
        return type == 0L ? 0L : classMirror(type);
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
        entryPoint(blob, Magic.bytes("<clinit>"), Magic.bytes("()V"));   // may be absent (addReach(0) is a no-op)
        loadAll();
        int ci = classIndexByName(slash);
        return ci < 0 ? 0L : clType[ci];
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
        entryPoint(blob, Magic.bytes("<clinit>"), Magic.bytes("()V"));   // seed reachability root (may be absent)
        loadAll();
        int ci = 0;                                     // find the class we just added by its unique blob base
        while (ci < clCount)
        {
            if (clBase[ci] == blob)
            {
                return clType[ci];
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
        long obj = Heap.alloc(16 + clFieldCount[i] * 8);
        Magic.store64(obj + 0L, clTib[i]);
        return obj;
    }

    /** Print the ONE demand-compiled method (or {@code <clinit>}) containing {@code addr} as a single line
     *  "class.method +0xoff" -- the printStackTrace frame formatter (compact vs {@link #reportMethodAt}). */
    static void printFrameAt(long addr)
    {
        long bestBuf = 0L;
        int bestReg = -1;
        int bestClin = -1;
        int i = 0;
        while (i < rgCount)
        {
            if (rgBuf[i] != 0L && rgBuf[i] <= addr && rgBuf[i] > bestBuf) { bestBuf = rgBuf[i]; bestReg = i; bestClin = -1; }
            i += 1;
        }
        int c = 0;
        while (c < clinitN)
        {
            if (clinitEntry[c] != 0L && clinitEntry[c] <= addr && clinitEntry[c] > bestBuf) { bestBuf = clinitEntry[c]; bestClin = c; bestReg = -1; }
            c += 1;
        }
        if (bestReg < 0 && bestClin < 0)
        {
            if (!printImageFrameAt(addr))                            // writer-compiled VM/driver code: image symbol table
            {
                Uart.write(Magic.bytes("<image/native>"));
            }
            return;
        }
        if (bestReg >= 0)
        {
            writeName(rgBase[bestReg] + rgClassOff[bestReg] + 2, u2(rgBase[bestReg] + rgClassOff[bestReg]));
            Uart.putc(0x2E);
            writeName(rgBase[bestReg] + rgNameOff[bestReg] + 2, u2(rgBase[bestReg] + rgNameOff[bestReg]));
            int line = lineAtOffset(rgLine[bestReg], (int) ((addr - bestBuf) >> 2));   // (pc-base)/4 = word offset
            if (rgSrc[bestReg] != 0L)
            {
                Uart.putc(0x28);                                 // '('
                writeName(rgSrc[bestReg] + 2, u2(rgSrc[bestReg]));   // SourceFile filename
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
                if (rgBuf[i] != 0L && rgBuf[i] < ceil && rgBuf[i] > bestBuf)
                {
                    bestBuf = rgBuf[i]; bestReg = i; bestClin = -1;
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
                writeName(rgBase[bestReg] + rgClassOff[bestReg] + 2, u2(rgBase[bestReg] + rgClassOff[bestReg]));
                Uart.putc(0x2E);
                writeName(rgBase[bestReg] + rgNameOff[bestReg] + 2, u2(rgBase[bestReg] + rgNameOff[bestReg]));
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
     * it invokeinterface-dispatches {@code run()} on the receiver, then calls {@link VM#taskExit}. All the
     * class's itable-directory entries share one imap, so the first entry's table is it — no directory scan.
     */
    static void buildRunTramp()
    {
        int slot = runInterfaceSlot();
        long buf = Heap.allocCode(64);
        int w = 0;
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 0, 0));         w += 1;  // x17 = receiver.tib
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 17, 0));        w += 1;  // x17 = Type
        Magic.store32(buf + w * 4L, A64Enc.ldrx(17, 17, 16));       w += 1;  // x17 = itable dir
        Magic.store32(buf + w * 4L, A64Enc.ldrx(16, 17, 8));        w += 1;  // x16 = imap (shared table)
        Magic.store32(buf + w * 4L, A64Enc.ldrx(16, 16, slot * 8)); w += 1;  // x16 = run() buffer
        Magic.store32(buf + w * 4L, A64Enc.blr(16));                w += 1;  // run() with x0 = receiver
        long te = VM.taskExitAddr;
        Magic.store32(buf + w * 4L, A64Enc.movz(16, (int) te, 0));         w += 1;
        Magic.store32(buf + w * 4L, A64Enc.movk(16, (int) (te >> 16), 1)); w += 1;
        Magic.store32(buf + w * 4L, A64Enc.blr(16));                w += 1;  // taskExit() — never returns
        Heap.publishCode(buf, buf + w * 4L);
        VM.runTrampAddr = buf;
    }

    /** Global interface-method index of {@code run()} ("run"/"()V"), or 0 if not registered. */
    private static int runInterfaceSlot()
    {
        int i = 0;
        while (i < ifCount)
        {
            if (isName(ifBase[i], ifNameOff[i], 0x72756EL, 3)
                    && isName(ifBase[i], ifDescOff[i], 0x282956L, 3))
            {
                return i;
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

    /** Demand-load and run {@code demo/FloatDemo.main()} — verifies float/double arithmetic + conversions. */
    static void loadFloat()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);    // results printed via concat -> String
        addBlob(VM.floatDemoBytes, (int) VM.floatDemoLen);
        entryPoint(VM.floatDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated closure
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/FloatDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** Demand-load and run {@code demo/NativeDemo.main()} — verifies provided java.base natives. */
    static void loadNative()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.nativeDemoBytes, (int) VM.nativeDemoLen);
        entryPoint(VM.nativeDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated closure
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/NativeDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** Demand-load and run {@code demo/StrDemo.main()} — verifies real-shaped String + StringBuilder. */
    static void loadStr()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);              // String first (literals + StringBuilder need it)
        addBlob(VM.stringBuilderBytes, (int) VM.stringBuilderLen);
        addBlob(VM.strDemoBytes, (int) VM.strDemoLen);
        entryPoint(VM.strDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated closure
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/StrDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/ExcDemo.main()} — verifies implicit exceptions: the JIT's null/bounds
     * checks throw a real mini exception (from the loaded hierarchy) that catch clauses catch, both
     * main-local and via cross-method unwind (a bounds check inside {@code String.charAt}).
     */
    static void loadExc()
    {
        resetLoader();
        addBlob(VM.stringBytes, (int) VM.stringLen);              // String (charAt bounds + concat literals)
        addBlob(VM.throwableBytes, (int) VM.throwableLen);       // the mini exception hierarchy (Type chain for catch)
        addBlob(VM.exceptionBytes, (int) VM.exceptionLen);
        addBlob(VM.runtimeExcBytes, (int) VM.runtimeExcLen);
        addBlob(VM.npeBytes, (int) VM.npeLen);
        addBlob(VM.ioobeBytes, (int) VM.ioobeLen);
        addBlob(VM.aioobeBytes, (int) VM.aioobeLen);
        addBlob(VM.excDemoBytes, (int) VM.excDemoLen);
        entryPoint(VM.excDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated closure
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ExcDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /** Demand-load and run {@code demo/ListDemo.main()} — verifies the real-shaped java/util/ArrayList. */
    static void loadList()
    {
        resetLoader();
        // Stock java.util.ArrayList (mini collections retired): seed only String's happy path + Integer for the
        // "item"+i concat; the reachability-gated demand-loader pulls the real closure -- ArrayList ->
        // AbstractList -> AbstractCollection, List/Collection/Iterable/Iterator, ArrayList$Itr, Arrays/Objects/
        // System/Preconditions -- all UNMODIFIED java.base, linked load-order-robustly by the two-phase loader.
        // Object first: fixes hashCode/equals/toString to their canonical vtable slots so contains()/indexOf()'s
        // polymorphic `Object.equals` dispatch on String elements lands on String's slot (see loadMap).
        addBlob(VM.objectBytes, (int) VM.objectLen);
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.listDemoBytes, (int) VM.listDemoLen);
        entryPoint(VM.listDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));   // reachability-gated (+ indy)
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/ListDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
    }

    /**
     * Demand-load and run {@code demo/MapDemo.main()} — verifies the real-shaped java/util/HashMap. The
     * mini java/lang/Object is in the closure so String inherits+overrides its hashCode/equals slots,
     * which is how Object-keyed HashMap dispatches into the String keys' real implementations.
     */
    static void loadMap()
    {
        resetLoader();
        // Stock java.util.HashMap (mini collections retired): seed String's happy path + Integer; the demand-loader
        // pulls HashMap -> AbstractMap, Map, HashMap$Node, and the String-key hashCode/equals path -- unmodified
        // java.base, linked load-order-robustly by the two-phase loader.
        // java/lang/Object MUST be seeded first: it fixes the canonical vtable slots of hashCode/equals/toString
        // so EVERY class (String, AbstractMap, HashMap) puts them at the SAME slot. Without it each class numbers
        // those inherited methods by its own method order (String.hashCode lands at slot 28, AbstractMap's at 13),
        // and a polymorphic `Object.hashCode` dispatch -- HashMap.hash(key) does key.hashCode() -- resolves (via
        // globalVtableSlot's name+desc fallback) to the WRONG class's slot -> blr into an empty/foreign slot.
        addBlob(VM.objectBytes, (int) VM.objectLen);
        addBlob(VM.stringBytes, (int) VM.stringLen);
        addBlob(VM.stringLatin1Bytes, (int) VM.stringLatin1Len);
        addBlob(VM.integerBytes, (int) VM.integerLen);
        addBlob(VM.decimalDigitsBytes, (int) VM.decimalDigitsLen);
        addBlob(VM.mapDemoBytes, (int) VM.mapDemoLen);
        entryPoint(VM.mapDemoBytes, Magic.bytes("main"), Magic.bytes("()V"));    // reachability-gated (+ indy)
        loadAll();
        long buf = globalMethodBuf(Magic.bytes("demo/MapDemo"), Magic.bytes("main"), Magic.bytes("()V"));
        if (buf != 0L)
        {
            long unused = Magic.call0(buf);
        }
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
        gbytes = toBytes(base, len);
        gcpCount = ClassReader.cpCount(gbytes);
        gcp = new int[gcpCount];
        gcpTag = new int[gcpCount];
        litObjByCp = new long[gcpCount];                // per-blob ldc-String intern cache (one object per cp entry)
        gAfterCp = ClassReader.constantPool(gbytes, gcp, gcpTag);   // stable: gp is later reused as a walk cursor
        gp = base + gAfterCp;
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
                    slot = gvCount;                     // else append a new slot
                    gvCount += 1;
                }
                gvBase[slot] = gbase;
                gvName[slot] = gcp[u2(p + 2)];
                gvDesc[slot] = gcp[u2(p + 4)];
                gvImplCode[slot] = findCode(bytes, p + 8, attrs);   // this class's own impl
                gvImplBuf[slot] = 0L;
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
                gvBase[slot] = vtNameBase[i];
                gvName[slot] = vtNameOff[i];
                gvDesc[slot] = vtDescOff[i];
                gvImplBuf[slot] = vtBuf[i];             // inherited (already-compiled) impl
                gvImplCode[slot] = 0L;
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
            if (utf8EqAt(gbase, nameOff, gvBase[s], gvName[s])
                    && utf8EqAt(gbase, descOff, gvBase[s], gvDesc[s]))
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
        return r >= 0 ? clFieldCount[r] : 0;
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
            if (utf8EqAt(gbase, classOff, sgBase[i], sgClassOff[i])
                    && utf8EqAt(gbase, nameOff, sgBase[i], sgNameOff[i]))
            {
                return sgAddr[i];
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
        if (!compileReuseTib)                           // two-phase clinit: the class's TIB already exists (clTib[reg],
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
            if (code != 0L && (markActive == 0 || isReach(code)))   // reachability-pruned when a mark ran
            {
                int isStatic = (u2(p) & 0x0008) != 0 ? 1 : 0;
                addMethod(code, gcodeLen, gMaxLocals, gcp[u2(p + 4)], isStatic);
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
        if (gEntryBlob != 0L)                            // reachability requested: mark + PULL the reachable
        {                                                // closure on demand (no pre-pull-all resolveClosureFromDir)
            markReachable();
        }
        probeAll();                                      // this_class + super + interfaces + dep list over the final set

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

        patchRelocs();                                  // every body is compiled now: fix up the cross-class method
                                                        // CALLs left unresolved while their target compiled later
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
        runClinits();                                   // NOW run each compiled <clinit>: its cross-class calls are patched
        VM.byteArrayTibCache = byteArrayTib();          // type concat results ([B TIB) so stock getBytes can
                                                        //   checkcast/clone a concat String's value
        markActive = 0;                                 // don't leak the reachability state past this batch
        gEntryBlob = 0L;
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
            gType = Heap.allocData(24);
            Magic.store64(gType + 0, 0L);               // instanceSize (not instantiated)
            Magic.store64(gType + 8, 0L);               // superType
            Magic.store64(gType + 16, 0L);              // no itableDir
            clBase[clCount] = gbase;
            clNameOff[clCount] = gThisNameOff;
            clTib[clCount] = 0L;
            clType[clCount] = gType;
            clFieldCount[clCount] = 0;
            clVtCount[clCount] = 0;
            clStatics[clCount] = gStatics;              // interface constants block (reused by its phase-B bodies)
            clVtStart[clCount] = vtCount;               // no vtable entries appended for an interface
            clIsIface[clCount] = true;
            clSuperReg[clCount] = classRegByName(gSuperNameOff);   // an interface's super is Object (-1); kept for symmetry
            captureDirectIfaces();                      // an interface's extended interfaces (List extends Iterable)
            clModifiers[clCount] = gClassModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
            clCount += 1;
            return;                                     // bodies (default/static methods) compiled in phase B
        }
        parseVtable(bytes);                             // flatten against the superclass: SLOT numbering (bufs still 0)
        allocTib();                                     // allocate Type + empty TIB at a stable address (gTib)
        registerClassStructure();                       // class + fields + statics + vtable STRUCTURE (bufs 0)
        clModifiers[clCount - 1] = gClassModifiers;     // cached Class.getModifiers() (captured post-cp, no re-parse)
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
        gStatics = clStatics[reg];                      // REUSE the phase-A static block (cross-class getstatic keys on it)
        findBootstrapMethods();
        if (clIsIface[reg])
        {
            gType = clType[reg];                        // restore the interface's phase-A Type so a default method's
                                                        // self-reference (typeOfClass -> gType, e.g. Map.putIfAbsent
                                                        // calling this.get()) bakes the REAL interface Type -- else
                                                        // it bakes a stale gType and the implementor's itable-dir
                                                        // walk never matches (invokeinterface sentinel NPE).
            compileClass(bytes);                        // interface CONCRETE methods (static like List.of + defaults)
            registerAll();
            return;
        }
        parseVtable(bytes);                             // NOW the super's vtBuf is filled -> inherited slot bufs are real
        gType = clType[reg];                            // restore this class's Type + TIB (allocated in phase A)
        gTib = clTib[reg];
        compileReuseTib = true;                         // keep runClinit's compile() from reallocating gTib (would
        runClinit(bytes);                               //   leave compileClass filling a throwaway TIB, not clTib[reg])
        compileReuseTib = false;
        gType = clType[reg];                            // (runClinit's compile leaves gType/gTib alone now, but be safe)
        gTib = clTib[reg];
        provideKnownStatics();                          // seed static tables a skipped <clinit> would have built
        compileClass(bytes);                            // compile all methods; fillTib fills the (phase-A-allocated) TIB
        registerAll();                                  // methods -> globalBuf
        fillClassVtBuf(reg);                            // fill this class's registered vtable buffers (for subclasses)
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
            if (utf8IsAtBase(sgBase[i], sgClassOff[i], cls) && utf8IsAtBase(sgBase[i], sgNameOff[i], name))
            {
                return sgAddr[i];
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
        long itib = clTib[ii];
        int isize = 16 + clFieldCount[ii] * 8;          // Integer: header + its instance fields (value)
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
        long ltib = clTib[li];
        int lsize = 16 + clFieldCount[li] * 8;          // Long: header + its instance field (value)
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
        long ptib = clTib[pi];
        int psize = 16 + clFieldCount[pi] * 8;          // field-free overlay -> 16, but honor any fields it declares
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
            long inst = Heap.alloc(16 + clFieldCount[mi] * 8);   // field-free -> header only
            Magic.store64(inst + 0L, clTib[mi]);                 // TIB (itable dir for getEnumConstantsShared dispatch)
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
            long inst = Heap.alloc(16 + clFieldCount[ei] * 8);   // field-free overlay -> just the TIB header
            Magic.store64(inst + 0L, clTib[ei]);                 // TIB (vtable for isOptionSupported dispatch)
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
        mBuf[i] = Heap.allocCode(compileMethod(i, 0L).length * 4);
    }

    /** Emit method {@code i}'s A64 (from the shared core) into its assigned buffer. */
    private static void emitMethod(int i)
    {
        relocRecording = 1;                             // record unresolved cross-class sites at their real address
        int[] words = compileMethod(i, mBuf[i]);        // real base -> resolved addresses
        relocRecording = 0;
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
        rgBase[rgCount] = base;
        rgClassOff[rgCount] = classOff;
        rgNameOff[rgCount] = nameOff;
        rgDescOff[rgCount] = descOff;
        rgBuf[rgCount] = buf;
        rgLine[rgCount] = lineTab;
        rgSrc[rgCount] = srcAddr;
        rgAccess[rgCount] = access;
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
            if (utf8EqAt(gbase, classOff, rgBase[i], rgClassOff[i])
                    && utf8EqAt(gbase, nameOff, rgBase[i], rgNameOff[i])
                    && utf8EqAt(gbase, descOff, rgBase[i], rgDescOff[i]))
            {
                return rgBuf[i];
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
        int i = 0;
        while (i < rcCount)
        {
            long target = globalBufByRef(rcBase[i], rcClass[i], rcName[i], rcDesc[i]);
            if (target == 0L)
            {
                target = VM.denylistTrapAddr;                  // unresolved: the callee's class was pruned (#43
                if (trapWireCount < MAXTRAPWIRE)               // denylist) or never compiled -> trap, not a bl 0 wild branch
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
            }                                                  // denylist) or never compiled -> trap, not a bl 0 wild branch
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
        int j = 0;
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
            if (utf8EqAt(refBase, classOff, rgBase[i], rgClassOff[i])
                    && utf8EqAt(refBase, nameOff, rgBase[i], rgNameOff[i])
                    && utf8EqAt(refBase, descOff, rgBase[i], rgDescOff[i]))
            {
                return rgBuf[i];
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
                if (utf8EqAt(pdBase[spd], pdNameOff[spd], rgBase[j], rgClassOff[j])
                        && utf8EqAt(refBase, nameOff, rgBase[j], rgNameOff[j])
                        && utf8EqAt(refBase, descOff, rgBase[j], rgDescOff[j]))
                {
                    return rgBuf[j];
                }
                j += 1;
            }
            pd = spd;
        }
        return 0L;
    }

    /** Static-slot address for a field ref given as blob base + Utf8 offsets, or 0. */
    private static long globalStaticByRef(long refBase, int classOff, int nameOff)
    {
        int i = 0;
        while (i < sgCount)
        {
            if (utf8EqAt(refBase, classOff, sgBase[i], sgClassOff[i])
                    && utf8EqAt(refBase, nameOff, sgBase[i], sgNameOff[i]))
            {
                return sgAddr[i];
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
        int classOff = refClassNameOff(idx);
        int nameOff = mrefNameOff(idx);
        if (utf8IsStr(classOff, Magic.bytes("java/lang/System")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("nanoTime")))          { return VM.nanoTimeAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("currentTimeMillis"))) { return VM.currentTimeMillisAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("arraycopy")))         { return VM.arraycopyAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("identityHashCode")))  { return VM.identityAddr; }  // ref IS its address -> identity hash
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Throwable")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("printStackTrace0")))  { return VM.printStackTraceAddr; }   // (this)V
        }
        if (utf8IsStr(classOff, Magic.bytes("java/io/FileInputStream")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("open0")))             { return VM.fileOpenAddr; }   // (String)J -> RAMFS entry
        }
        // VarHandle overlay: resolve an instance field's byte offset from the target object's class.
        if (utf8IsStr(classOff, Magic.bytes("java/lang/invoke/VarHandle")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("fieldOffset0")))      { return VM.vhFieldOffsetAddr; }  // (byte[],Object)J
        }
        // Atomic*FieldUpdater overlays resolve the target field's byte offset the same way as VarHandle, and
        // resolve their caller's class (getCallerClass) for the field-access check.
        if (utf8IsStr(classOff, Magic.bytes("java/util/concurrent/atomic/AtomicIntegerFieldUpdater"))
                || utf8IsStr(classOff, Magic.bytes("java/util/concurrent/atomic/AtomicLongFieldUpdater"))
                || utf8IsStr(classOff, Magic.bytes("java/util/concurrent/atomic/AtomicReferenceFieldUpdater")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("fieldOffset0")))     { return VM.vhFieldOffsetAddr; }    // (byte[],Object)J
        }
        if (utf8IsStr(classOff, Magic.bytes("java/util/concurrent/atomic/FieldUpdaterCheck"))
                || utf8IsStr(classOff, Magic.bytes("java/lang/reflect/AccessibleObject")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("callerClass0")))     { return VM.classAtPcAddr; }        // (J)Class
        }
        // Reflective Field.get/set: resolve the field's byte offset from the target object's class (same
        // loader field registry as the VarHandle/atomic-updater shims).
        if (utf8IsStr(classOff, Magic.bytes("java/lang/reflect/Field")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("fieldOffset0")))     { return VM.vhFieldOffsetAddr; }    // (byte[],Object)J
        }
        // Reflective Method.invoke: resolve a method-registry index by name, then its buffer/access/descriptor.
        if (utf8IsStr(classOff, Magic.bytes("java/lang/reflect/Method")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("methodResolve0")))   { return VM.methodResolveAddr; }    // (Class,byte[])I
            if (utf8IsStr(nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }       // (I,byte[],long[])I
        }
        // Reflective Constructor.newInstance: resolve <init> by arity, read its descriptor (shared methodInfo0),
        // and allocate the instance.
        if (utf8IsStr(classOff, Magic.bytes("java/lang/reflect/Constructor")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("ctorResolve0")))     { return VM.constructorResolveAddr; }  // (Class,I)I
            if (utf8IsStr(nameOff, Magic.bytes("methodInfo0")))      { return VM.methodInfoAddr; }          // (I,byte[],long[])I
            if (utf8IsStr(nameOff, Magic.bytes("allocInstance0")))   { return VM.allocInstanceAddr; }       // (Class)Object
        }
        // FileDescriptor.<clinit> runs (to register the JavaIOFileDescriptorAccess); its 3 natives are inert
        // on metal -- initIDs is a no-op, and handle/append are Windows/append-mode fields unused by sockets.
        if (utf8IsStr(classOff, Magic.bytes("java/io/FileDescriptor")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("getHandle")))         { return VM.sockZeroAddr; }    // (I)J -> 0
            if (utf8IsStr(nameOff, Magic.bytes("getAppend")))         { return VM.sockZeroAddr; }    // (I)Z -> false
        }
        if (utf8IsStr(classOff, Magic.bytes("java/net/InetAddress")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("resolve0")))          { return VM.dnsResolveAddr; }  // (byte[])I -> WiFi DNS
        }
        // M3 socket natives: stock sun.nio.ch backed by net.Tcp (fd int = the net.Tcp handle).
        if (utf8IsStr(classOff, Magic.bytes("sun/nio/ch/Net")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("socket0")))           { return VM.sockSocket0Addr; }
            if (utf8IsStr(nameOff, Magic.bytes("connect0")))          { return VM.sockConnect0Addr; }
            if (utf8IsStr(nameOff, Magic.bytes("available")))         { return VM.sockAvailableAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("localPort")))         { return VM.sockZeroAddr; }     // -> 0
            if (utf8IsStr(nameOff, Magic.bytes("getIntOption0")))     { return VM.sockZeroAddr; }     // -> 0 (SO_LINGER)
            if (utf8IsStr(nameOff, Magic.bytes("localInetAddress")))  { return VM.sockZeroAddr; }     // -> null wildcard
            // Net.<clinit> capability probes: no poll, IPv4-only, no reuse-port on metal -> all 0/false.
            if (utf8IsStr(nameOff, Magic.bytes("pollinValue")))       { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("polloutValue")))      { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("pollerrValue")))      { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("pollhupValue")))      { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("pollnvalValue")))     { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("pollconnValue")))     { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("isIPv6Available0")))  { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("isReusePortAvailable0"))) { return VM.sockZeroAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("isExclusiveBindAvailable"))) { return VM.sockZeroAddr; }  // -> 0
        }
        if (utf8IsStr(classOff, Magic.bytes("sun/nio/ch/SocketDispatcher")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("read0")))             { return VM.sockRead0Addr; }
            if (utf8IsStr(nameOff, Magic.bytes("write0")))            { return VM.sockWrite0Addr; }
        }
        if (utf8IsStr(classOff, Magic.bytes("sun/nio/ch/UnixDispatcher")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("close0")))            { return VM.sockClose0Addr; }
            if (utf8IsStr(nameOff, Magic.bytes("preClose0")))         { return VM.sockNoopAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
        }
        if (utf8IsStr(classOff, Magic.bytes("sun/nio/ch/IOUtil")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("fdVal")))             { return VM.fdValAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("setfdVal")))          { return VM.setFdValAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("initIDs")))           { return VM.sockNoopAddr; }
        }
        if (utf8IsStr(classOff, Magic.bytes("sun/nio/ch/NativeThread")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("current0")))          { return VM.sockZeroAddr; }     // -> 0
            if (utf8IsStr(nameOff, Magic.bytes("init")))              { return VM.sockNoopAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("supportPendingSignals0"))) { return VM.sockZeroAddr; } // -> false
            if (utf8IsStr(nameOff, Magic.bytes("signal0")))           { return VM.sockNoopAddr; }      // no thread to wake
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Object")))
        {
            // Object.clone() shallow copy: same block-copy as the [T.clone() intrinsic (TIB + body from the
            // status-word size), so reuse arrayClone. Cloneable collections super.clone() then fix their links.
            if (utf8IsStr(nameOff, Magic.bytes("clone0")))            { return VM.arrayCloneAddr; }    // (Object)Object
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/reflect/Array")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("newArray0")))         { return VM.newReflectArrayAddr; } // (Class,I)Object
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Class")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("getName0")))          { return VM.classNameAddr; }     // (Class)String
            if (utf8IsStr(nameOff, Magic.bytes("forName0")))          { return VM.forNameAddr; }       // (byte[])Class
            if (utf8IsStr(nameOff, Magic.bytes("classModifiers0")))   { return VM.classModifiersAddr; } // (Class)I
            if (utf8IsStr(nameOff, Magic.bytes("isInstance0")))       { return VM.instanceOfAddr; }    // (Object,J)Z == VM.instanceOf(JJ)I
            if (utf8IsStr(nameOff, Magic.bytes("superclass0")))       { return VM.superclassAddr; }    // (Class)Class
            if (utf8IsStr(nameOff, Magic.bytes("fieldMods0")))        { return VM.fieldModsAddr; }     // (Class,byte[])I
            if (utf8IsStr(nameOff, Magic.bytes("fieldTypeChar0")))    { return VM.fieldTypeCharAddr; } // (Class,byte[])I
            if (utf8IsStr(nameOff, Magic.bytes("getComponentType0")))  { return VM.componentTypeAddr; } // (Class)Class
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/ClassLoader")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("defineClass0")))      { return VM.defineClassAddr; }   // (String,byte[],II)Class
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Thread")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("currentThread0")))    { return VM.currentThreadAddr; } // ()Thread
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Float")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("floatToRawIntBits"))) { return VM.identityAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("intBitsToFloat")))    { return VM.identityAddr; }
        }
        if (utf8IsStr(classOff, Magic.bytes("java/lang/Double")))
        {
            if (utf8IsStr(nameOff, Magic.bytes("doubleToRawLongBits"))) { return VM.identityAddr; }
            if (utf8IsStr(nameOff, Magic.bytes("longBitsToDouble")))    { return VM.identityAddr; }
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
        clBase[clCount] = gbase;
        clNameOff[clCount] = gThisNameOff;
        clTib[clCount] = gTib;
        clType[clCount] = gType;
        clFieldCount[clCount] = gifCount;
        clVtCount[clCount] = gvCount;
        clStatics[clCount] = gStatics;
        clVtStart[clCount] = vtCount;                   // this class's slots occupy vt[vtCount .. vtCount+gvCount)
        clIsIface[clCount] = false;
        clSuperReg[clCount] = classRegByName(gSuperNameOff);   // superclass registry index (-1 for Object), for the
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
        int st = 0;
        while (st < gsfCount)                           // register this class's static fields (cross-class getstatic)
        {
            sgBase[sgCount] = gbase;
            sgClassOff[sgCount] = gThisNameOff;
            sgNameOff[sgCount] = gsfName[st];
            sgAddr[sgCount] = gStatics + st * 8L;
            sgCount += 1;
            st += 1;
        }
        int s = 0;
        while (s < gifCount)
        {
            if (gifName[s] != 0)                        // skip inherited slots (registered by the super)
            {
                fldBase[fldCount] = gbase;
                fldClassOff[fldCount] = gThisNameOff;
                fldNameOff[fldCount] = gifName[s];
                fldSlot[fldCount] = s;
                fldAccess[fldCount] = gifAccess[s];
                fldDescOff[fldCount] = gifDescOff[s];
                fldCount += 1;
            }
            s += 1;
        }
        int v = 0;
        while (v < gvCount)                            // register the whole flattened vtable STRUCTURE (bufs 0 for now)
        {
            vtClassBase[vtCount] = gbase;
            vtClassOff[vtCount] = gThisNameOff;
            vtNameBase[vtCount] = gvBase[v];           // signature blob (a super's, for inherited slots)
            vtNameOff[vtCount] = gvName[v];
            vtDescOff[vtCount] = gvDesc[v];
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
        int start = clVtStart[reg];
        int cnt = clVtCount[reg];
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
    private static void registerInterface()
    {
        long p = gMethodsStart;
        int mcount = u2(p);
        p += 2;
        int m = 0;
        while (m < mcount)
        {
            int attrs = u2(p + 6);
            if (isVirtual(u2(p), gcp[u2(p + 2)])
                    && ifIndexOf(gbase, gcp[u2(p + 2)], gcp[u2(p + 4)]) < 0)
            {
                if (ifCount >= MAXIFM) { capHalt(Magic.bytes("MAXIFM"), ifCount); }   // loader-table overflow guard: halt with a clear message rather than OOB-corrupt
                ifBase[ifCount] = gbase;
                ifNameOff[ifCount] = gcp[u2(p + 2)];
                ifDescOff[ifCount] = gcp[u2(p + 4)];
                ifCount += 1;
            }
            p = skipAttributes(p + 8, attrs);
            m += 1;
        }
    }

    /** Global interface-method index for an InterfaceMethodref call site (0 if unknown). */
    static int ifSlotOf(int idx)
    {
        int g = ifIndexOf(gbase, mrefNameOff(idx), mrefDescOff(idx));
        return g >= 0 ? g : 0;
    }

    /** Global interface-method index for a name+descriptor in {@code base}, or -1. */
    private static int ifIndexOf(long base, int nameOff, int descOff)
    {
        int i = 0;
        while (i < ifCount)
        {
            if (utf8EqAt(base, nameOff, ifBase[i], ifNameOff[i])
                    && utf8EqAt(base, descOff, ifBase[i], ifDescOff[i]))
            {
                return i;
            }
            i += 1;
        }
        return -1;
    }

    /**
     * Build this class's imap: for each known interface method, the buffer of this
     * class's implementation (matched against the flattened vtable by name+
     * descriptor), or 0 if it does not implement it. Fixed size so an interface
     * loaded later cannot leave an earlier class's imap short.
     */
    private static long buildImap()
    {
        long imap = Heap.allocData(MAXIFM * 8);
        int g = 0;
        while (g < MAXIFM)
        {
            long buf = 0L;
            if (g < ifCount)
            {
                int s = findVtSlotAt(ifBase[g], ifNameOff[g], ifDescOff[g]);
                if (s >= 0)
                {
                    buf = slotBuf(s);
                }
                else
                {
                    buf = defaultImplOf(g);        // class doesn't override -> the interface's own DEFAULT method
                }                                  // (0 if the method is abstract / its default wasn't compiled)
            }
            Magic.store64(imap + g * 8, buf);
            g += 1;
        }
        return imap;
    }

    /**
     * The compiled buffer of the DEFAULT method for global interface-method index {@code g}, or 0. A default
     * method is defined (with a body) IN its interface, so it was compiled + registered when the interface's blob
     * ({@code ifBase[g]}) was loaded (loadBodies' interface branch compiles concrete + default methods). Match a
     * registered method in that SAME blob by name+descriptor; abstract interface methods have no such body -> 0
     * (unchanged from before). This lets an implementing class that doesn't override a default method still
     * dispatch it (e.g. EnumMap inherits Map.putIfAbsent) instead of hitting an empty imap slot (blr 0).
     */
    private static long defaultImplOf(int g)
    {
        int i = 0;
        while (i < rgCount)
        {
            if (rgBase[i] == ifBase[g]
                    && utf8EqAt(ifBase[g], ifNameOff[g], rgBase[i], rgNameOff[i])
                    && utf8EqAt(ifBase[g], ifDescOff[g], rgBase[i], rgDescOff[i]))
            {
                return rgBuf[i];
            }
            i += 1;
        }
        return 0L;
    }

    /** Like {@link #findVtSlot} but for a name+descriptor living in another blob. */
    private static int findVtSlotAt(long base, int nameOff, int descOff)
    {
        int s = 0;
        while (s < gvCount)
        {
            if (utf8EqAt(base, nameOff, gvBase[s], gvName[s])
                    && utf8EqAt(base, descOff, gvBase[s], gvDesc[s]))
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
            if (utf8EqAt(gbase, nameOff, clBase[i], clNameOff[i]))
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
        return 0;
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
            if (clTib[ci] == tib)
            {
                int j = 0;
                while (j < fldCount)
                {
                    if (utf8EqAt(clBase[ci], clNameOff[ci], fldBase[j], fldClassOff[j])
                            && rawEqUtf8(fnBase, fnLen, fldBase[j], fldNameOff[j]))
                    {
                        return 16L + fldSlot[j] * 8L;
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
            if (clType[ci] == typeAddr)
            {
                int j = 0;
                while (j < fldCount)
                {
                    if (utf8EqAt(clBase[ci], clNameOff[ci], fldBase[j], fldClassOff[j])
                            && rawEqUtf8(fnBase, fnLen, fldBase[j], fldNameOff[j]))
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
        return j < 0 ? -1 : fldAccess[j];
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
            if (clType[i] == type)
            {
                return clModifiers[i];                     // cached at load time (see computeModifiersAtLoad)
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
        while (ci < clCount && clType[ci] != type)
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
            if (utf8EqAt(clBase[ci], clNameOff[ci], rgBase[i], rgClassOff[i])
                    && rawEqUtf8(nbase, nlen, rgBase[i], rgNameOff[i]))
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
        while (ci < clCount && clType[ci] != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clIsIface[ci])
        {
            return -1;
        }
        long blob = clBase[ci];
        int len = blobLenOf(blob);
        parseConstPool(blob, len);                         // set up the class's compile state (as loadBodies does,
        parseFields();                                     //   minus the TIB fill): statics block + vtable scratch
        int reg = classRegByName(gThisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        gStatics = clStatics[reg];                         // reuse the phase-A statics (cross-class getstatic keys on it)
        findBootstrapMethods();
        parseVtable(blob);                                 // gvImplBuf/gvImplCode (for any invokevirtual in the body)
        gType = clType[reg];
        gTib = clTib[reg];
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
            if (utf8EqAt(clBase[ci], clNameOff[ci], rgBase[i], rgClassOff[i])
                    && utf8IsAtBase(rgBase[i], rgNameOff[i], Magic.bytes("<init>"))
                    && descParamCountRaw(rgBase[i] + rgDescOff[i]) == paramCount)
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
        while (ci < clCount && clType[ci] != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clIsIface[ci])
        {
            return -1;
        }
        int idx = ctorResolveRegistry(ci, paramCount);
        if (idx >= 0)
        {
            return idx;
        }
        // on-demand: compile the matching <init> (its declaring class is already structure-loaded)
        long blob = clBase[ci];
        int len = blobLenOf(blob);
        parseConstPool(blob, len);
        parseFields();
        int reg = classRegByName(gThisNameOff);
        if (reg < 0)
        {
            return -1;
        }
        gStatics = clStatics[reg];
        findBootstrapMethods();
        parseVtable(blob);
        gType = clType[reg];
        gTib = clTib[reg];
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
        while (ci < clCount && clType[ci] != type)
        {
            ci += 1;
        }
        if (ci >= clCount || clIsIface[ci])
        {
            return 0L;
        }
        long obj = Heap.alloc(16 + clFieldCount[ci] * 8);  // header(16) + instance fields (incl. inherited)
        Magic.store64(obj + ObjectModel.TIB_OFFSET, clTib[ci]);
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
        long descAddr = rgBase[rgIndex] + rgDescOff[rgIndex];   // "(...)ret" Utf8 (u2 length, then bytes)
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
        Magic.store64(outArr + 24L + 0L, rgBuf[rgIndex]);      // out[0] = compiled buffer
        Magic.store64(outArr + 24L + 8L, (long) rgAccess[rgIndex]);   // out[1] = access flags
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
        return j < 0 ? -1 : u1(fldBase[j] + fldDescOff[j] + 2L);
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
            if (utf8EqAt(gbase, nameOff, clBase[i], clNameOff[i]))
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
            if (utf8EqAt(gbase, classOff, clBase[i], clNameOff[i]))
            {
                return true;
            }
            i += 1;
        }
        return false;
    }

    /** invokespecial is a real call (not an Object.&lt;init&gt; pop) if its class is loaded. */
    static boolean isRealSpecial(int idx)
    {
        return utf8Eq(refClassNameOff(idx), gThisNameOff) || refClassRegistered(idx);
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
            if (utf8EqAt(gbase, classOff, fldBase[i], fldClassOff[i])
                    && utf8EqAt(gbase, nameOff, fldBase[i], fldNameOff[i]))
            {
                return 16 + fldSlot[i] * 8;
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
                if (utf8EqAt(pdBase[pd], pdNameOff[pd], fldBase[i2], fldClassOff[i2])   // declared by THIS ancestor
                        && utf8EqAt(gbase, nameOff, fldBase[i2], fldNameOff[i2]))
                {
                    return 16 + fldSlot[i2] * 8;
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
        gType = Heap.allocData(24);
        Magic.store64(gType + 0, 16 + gifCount * 8);       // TYPE_INSTANCE_SIZE_OFFSET
        Magic.store64(gType + 8, sr >= 0 ? clType[sr] : 0L);   // TYPE_SUPER_OFFSET (0 at Object)
        gTib = Heap.allocData((1 + gvCount) * 8);
        Magic.store64(gTib, gType);                      // TIB[0] = Type (slots filled by fillTib)
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
        long imap = buildImap();
        if (instImaps != null && instImapN < instImaps.length)    // capture for the post-phase-B default refill
        {
            instImaps[instImapN] = imap;
            instImapReg[instImapN] = classRegByName(gThisNameOff);
            instImapN += 1;
        }
        Magic.store64(gType + 16, buildItableDir(imap));          // TYPE_ITABLE_DIR_OFFSET
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
            long imap = instImaps[m];
            int reg = instImapReg[m];
            if (reg >= 0)
            {
                int n = ifaceClosureOf(reg);        // the class's full interface set (persistent registries)
                int g = 0;
                while (g < ifCount)
                {
                    if (Magic.load64(imap + g * 8L) == 0L)
                    {
                        long b = defaultInClosure(n, g);   // a concrete default of method g in one of those interfaces
                        if (b != 0L)
                        {
                            Magic.store64(imap + g * 8L, b);
                        }
                    }
                    g += 1;
                }
            }
            m += 1;
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
        int sr = clSuperReg[reg];                       // the whole superclass chain's direct interfaces
        int guard = 0;
        while (sr >= 0 && guard < 64)
        {
            j = 0;
            while (j < clIfaceRegN[sr])
            {
                n = addIfaceUnique(n, clIfaceReg[sr * MAX_DIRECT_IF + j]);
                j += 1;
            }
            sr = clSuperReg[sr];
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
    private static long defaultInClosure(int n, int g)
    {
        int i = 0;
        while (i < n)
        {
            long ibase = clBase[ifClosureBuf[i]];       // a closure interface's blob; its own methods registered under it
            int k = 0;
            while (k < rgCount)
            {
                if (rgBase[k] == ibase && rgBuf[k] != 0L
                        && utf8EqAt(ifBase[g], ifNameOff[g], rgBase[k], rgNameOff[k])
                        && utf8EqAt(ifBase[g], ifDescOff[g], rgBase[k], rgDescOff[k]))
                {
                    return rgBuf[k];
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
    private static long buildItableDir(long imap)
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
            Magic.store64(dir + k * 16 + 0, clType[ifClosureBuf[k]]);   // interfaceType
            Magic.store64(dir + k * 16 + 8, imap);                      // itable (the shared imap)
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
            sr = clSuperReg[sr];
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

    /** Compiled buffer for flattened slot {@code s}: inherited (pre-resolved) or this class's own. */
    private static long slotBuf(int s)
    {
        if (gvImplBuf[s] != 0L)
        {
            return gvImplBuf[s];                         // inherited from a superclass
        }
        return bufOf(gvImplCode[s]);                     // this class's own method
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
            if (utf8IsAtBase(clBase[i], clNameOff[i], want))
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
        long tib = i >= 0 ? clTib[i] : 0L;
        long obj = Heap.alloc(i >= 0 ? (16 + clFieldCount[i] * 8) : 16);
        Magic.store64(obj + 0L, tib);
        return obj;
    }

    /** TIB of the loaded mini {@code java/lang/String} (for the concat's {@code newStringFromBytes}), or 0. */
    static long stringTib()
    {
        int i = stringClassIndex();
        return i >= 0 ? clTib[i] : 0L;
    }

    /** Instance size (bytes) of the loaded mini {@code java/lang/String} (header + fields). */
    static int stringSize()
    {
        int i = stringClassIndex();
        return i >= 0 ? (16 + clFieldCount[i] * 8) : 24;
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
            if (rawNameEq(clBase[i], clNameOff[i], nameStart, nameLen))
            {
                return clType[i];
            }
            i += 1;
        }
        return 0L;
    }

    /** Global itable slot of the SAM: name = the indy's name, descriptor = bootstrap_arguments[0] MethodType. */
    private static int lambdaIfaceSlot(int idx)
    {
        int nameOff = mrefNameOff(idx);
        long e = bsmEntryOff(indyBsmIndex(idx));
        int mtIdx = u2(e + 4);                          // bootstrap_arguments[0] = MethodType (SAM signature)
        int descOff = gcp[u2(gbase + gcp[mtIdx])];      // MethodType.descriptor_index -> Utf8 offset
        return ifIndexOf(gbase, nameOff, descOff);
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
        int ifaceSlot = lambdaIfaceSlot(idx);
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
            return finishLambdaClass(thunk, ifaceType, ifaceSlot, nc);
        }
        if (kind == 8)
        {
            // CONSTRUCTOR reference (Num::new): alloc the object, set its TIB, run <init>(obj, samArgs), return
            // the object. Unlike the other thunks this makes two CALLS (Heap.alloc, <init>), so it needs a frame
            // to preserve LR and the SAM args across them. (No captures: the ctor args are all SAM args.)
            int cr = classRegByName(refClassNameOff(lambdaImplMref(idx)));    // the class being constructed
            int size = 16 + clFieldCount[cr] * 8;
            long ctib = clTib[cr];
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
            Magic.store32(h2, A64Enc.bl((int) ((initBuf - h2) / 4L)));                   w += 1;  // <init>(obj, args)
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(0, 31, 8));                        w += 1;  // x0 = obj (return)
            Magic.store32(thunk + w * 4L, A64Enc.ldrx(30, 31, 0));                       w += 1;  // restore LR
            Magic.store32(thunk + w * 4L, A64Enc.addImm(31, 31, frame));                 w += 1;  // add sp, #frame
            Magic.store32(thunk + w * 4L, A64Enc.ret());                                 w += 1;
            Heap.publishCode(thunk, thunk + w * 4L);
            return finishLambdaClass(thunk, ifaceType, ifaceSlot, nc);
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
            Magic.store32(bAt, A64Enc.b((int) ((implBuf - bAt) / 4L)));     // b implBuf (tail call)
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
        return finishLambdaClass(thunk, ifaceType, ifaceSlot, nc);
    }

    /** Wrap a built lambda thunk into a class: imap (SAM slot -> thunk), itable dir, Type, TIB; return the TIB. */
    private static long finishLambdaClass(long thunk, long ifaceType, int ifaceSlot, int nc)
    {
        // imap: the flat interface-method table, indexed by global SAM slot -> the thunk.
        long imap = Heap.allocData(MAXIFM * 8);
        int j = 0;
        while (j < MAXIFM)
        {
            Magic.store64(imap + j * 8L, 0L);
            j += 1;
        }
        Magic.store64(imap + ifaceSlot * 8L, thunk);
        // itable directory: { interfaceType, imap } + a zero sentinel.
        long dir = Heap.allocData(2 * 16);
        Magic.store64(dir + 0L, ifaceType);
        Magic.store64(dir + 8L, imap);
        Magic.store64(dir + 16L, 0L);
        Magic.store64(dir + 24L, 0L);
        // Type { instanceSize, superType=Object(0), itableDir }.
        long type = Heap.allocData(24);
        Magic.store64(type + 0L, 16 + nc * 8);
        Magic.store64(type + 8L, 0L);
        Magic.store64(type + 16L, dir);
        // TIB { Type } (slot 0; the lambda has no vtable methods of its own).
        long tib = Heap.allocData(8);
        Magic.store64(tib + 0L, type);
        return tib;
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
        return r >= 0 ? clType[r] : 0L;
    }

    // ----- array Types -----------------------------------------------------
    // An array carries a real Type (via a 1-word array TIB in its header @0) so checkcast/instanceof against an
    // array class resolve precisely and `arr instanceof Object` walks the super chain. Primitive-array Types are
    // cached by atype; reference-array Types by element Type. All live in the per-batch demand heap (recreated
    // after each resetLoader). A raw array (VM-internal byte buffers, boot-time) keeps a small element size in
    // @0 instead — untyped, never checkcast, distinguished by magnitude (<= MAX_RAW_ARRAY_TIB).

    /** java/lang/Object's Type (array super), or 0 if Object isn't loaded yet. */
    private static long objectTypeAddr()
    {
        int i = 0;
        while (i < clCount)
        {
            if (utf8IsAtBase(clBase[i], clNameOff[i], Magic.bytes("java/lang/Object")))
            {
                return clType[i];
            }
            i += 1;
        }
        return 0L;
    }

    /** Allocate an array Type {tag|elemSize, super=Object, itableDir=0, elementType} + a 1-word TIB; return the TIB. */
    private static long makeArrayTib(int elemSize, long elementType)
    {
        long type = Heap.allocData(ObjectModel.ARRAY_TYPE_SIZE);
        Magic.store64(type + ObjectModel.TYPE_INSTANCE_SIZE_OFFSET, ObjectModel.ARRAY_TYPE_TAG | (long) elemSize);
        Magic.store64(type + ObjectModel.TYPE_SUPER_OFFSET, objectTypeAddr());   // arr instanceof Object
        Magic.store64(type + ObjectModel.TYPE_ITABLE_DIR_OFFSET, 0L);            // Cloneable/Serializable: later
        Magic.store64(type + ObjectModel.ARRAY_TYPE_ELEMENT_OFFSET, elementType);
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
        return oi >= 0 ? clVtCount[oi] : 0;
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
        int nv = clVtCount[oi];
        int k = 0;
        while (k < nv)
        {
            Magic.store64(tib + 8L + (long) k * 8L, Magic.load64(clTib[oi] + 8L + (long) k * 8L));
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
                if (primArrTib[a] != 0L)
                {
                    fillObjectVtable(primArrTib[a]);
                }
                a += 1;
            }
        }
        int r = 0;
        while (r < refArrCount)
        {
            if (refArrTib[r] != 0L)
            {
                fillObjectVtable(refArrTib[r]);
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
            if (utf8IsAtBase(clBase[i], clNameOff[i], Magic.bytes("java/lang/Object")))
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

    /** The array TIB for a primitive array of the given newarray {@code atype}; created + cached on demand. */
    static long primArrayTib(int atype)
    {
        if (atype < 0 || atype >= 12)
        {
            return 0L;
        }
        if (primArrTib[atype] == 0L)
        {
            primArrTib[atype] = makeArrayTib(primElemSize(atype), 0L);
        }
        return primArrTib[atype];
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

    /** Array TIB for the array descriptor at {@code base+nameOff} (a Utf8 like "[B" / "[Ljava/lang/String;"), or 0. */
    private static long arrayTibOfDesc(long base, int nameOff)
    {
        int c = u1(base + nameOff + 3);                 // char after the leading '['
        int atype = atypeForDescChar(c);
        if (atype >= 0)
        {
            return primArrayTib(atype);                 // "[<primitive>"
        }
        return refArrayTib(elementTypeOfArrayDesc(base, nameOff));   // "[L...;" / "[[..." (reference element)
    }

    /** Element Type of a reference-array descriptor "[L<class>;" (the class's Type, or 0 for nested/unresolved). */
    private static long elementTypeOfArrayDesc(long base, int nameOff)
    {
        if (u1(base + nameOff + 3) != 0x4C)             // not 'L' (a nested array "[[..." — leave element 0)
        {
            return 0L;
        }
        int len = u2(base + nameOff);                   // strip the leading "[L" and trailing ';' -> class name
        int i = 0;
        while (i < clCount)
        {
            if (utf8SliceEq(base, nameOff + 4, len - 3, clBase[i], clNameOff[i]))
            {
                return clType[i];
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
        long tib = makeArrayTib(ObjectModel.WORD, elementType);   // reference elements are 8-byte pointers
        if (refArrCount < refArrTib.length)
        {
            refArrElem[refArrCount] = elementType;
            refArrTib[refArrCount] = tib;
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
                if (utf8IsAtBase(clBase[i], clNameOff[i], Magic.bytes("java/lang/Class")))
                {
                    classTibCache = clTib[i];
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
            if (rgBuf[i] != 0L && rgBuf[i] <= pc && rgBuf[i] > bestBuf)
            {
                bestBuf = rgBuf[i];
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
            if (utf8EqAt(clBase[ci], clNameOff[ci], rgBase[bestReg], rgClassOff[bestReg]))
            {
                return classMirror(clType[ci]);
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
        return r >= 0 ? clTib[r] : gTib;
    }

    /** Scalar instance size (header + one 8-byte slot per field) of class {@code classIdx}. */
    static int objectSizeOf(int classIdx)
    {
        int r = classRegOf(classIdx);
        int fields = r >= 0 ? clFieldCount[r] : gifCount;
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
